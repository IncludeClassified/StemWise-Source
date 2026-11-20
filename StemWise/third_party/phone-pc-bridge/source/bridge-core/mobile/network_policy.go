package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/netip"
	"sync"
	"syscall"
)

// SocketBinder binds each underlying engine socket to the host-approved Android
// Network. An error refuses the socket before any connect or packet can occur.
type SocketBinder interface {
	BindSocket(fd int) error
}

var errNetworkPolicyUnavailable = errors.New("bridge_network_unavailable")

type socketPolicy struct {
	mu          sync.Mutex
	binder      SocketBinder
	enabled     bool
	generation  uint64
	dns         []string
	dnsNext     uint64
	pending     map[uint64]context.CancelFunc
	connections map[*policyDNSConn]struct{}
}

// SetSocketBinder installs the app's socket policy before the engine starts.
// The binder remains owned by this client across network suspension/recovery.
func (c *Client) SetSocketBinder(binder SocketBinder) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed || c.started || c.socketPolicy != nil || binder == nil {
		return errors.New("Install one socket binder before network startup")
	}
	c.socketPolicy = &socketPolicy{binder: binder}
	return nil
}

// SetNetworkDNS supplies literal DNS server IPs from the approved Network's
// LinkProperties. DNS never falls back to Android's default/cellular resolver.
func (c *Client) SetNetworkDNS(snapshot string) error {
	if len(snapshot) > 2048 {
		return errors.New("Invalid approved network DNS")
	}
	var input []string
	if json.Unmarshal([]byte(snapshot), &input) != nil || input == nil || len(input) > 16 {
		return errors.New("Invalid approved network DNS")
	}
	servers := make([]string, 0, len(input))
	seen := make(map[netip.Addr]bool)
	for _, raw := range input {
		ip, err := netip.ParseAddr(raw)
		if err != nil || ip.IsUnspecified() || ip.IsMulticast() {
			return errors.New("Invalid approved network DNS")
		}
		ip = ip.Unmap()
		if !seen[ip] {
			seen[ip] = true
			servers = append(servers, net.JoinHostPort(ip.String(), "53"))
		}
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed || c.started || c.socketPolicy == nil {
		return errors.New("Set approved network DNS while the engine is suspended")
	}
	p := c.socketPolicy
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.enabled {
		return errors.New("Suspend network policy before changing DNS")
	}
	p.dns = servers
	return nil
}

func (p *socketPolicy) ready() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.enabled && p.binder != nil
}

func (p *socketPolicy) resume() error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.binder == nil {
		return errNetworkPolicyUnavailable
	}
	p.enabled = true
	return nil
}

func (p *socketPolicy) suspend() {
	p.mu.Lock()
	p.enabled = false
	p.generation++
	for _, cancel := range p.pending {
		cancel()
	}
	connections := make([]*policyDNSConn, 0, len(p.connections))
	for connection := range p.connections {
		connections = append(connections, connection)
	}
	p.mu.Unlock()
	for _, connection := range connections {
		connection.Close()
	}
}

func (p *socketPolicy) bind(fd int) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if !p.enabled || p.binder == nil {
		return errNetworkPolicyUnavailable
	}
	return p.binder.BindSocket(fd)
}

func (p *socketPolicy) control(_ string, _ string, raw syscall.RawConn) error {
	var bindErr error
	if err := raw.Control(func(fd uintptr) { bindErr = p.bind(int(fd)) }); err != nil {
		return err
	}
	return bindErr
}

// The resolver-provided address is intentionally ignored: Android has no usable
// resolv.conf, and only DNS servers from the approved Network are authoritative.
func (p *socketPolicy) dialDNS(parent context.Context, network, _ string) (net.Conn, error) {
	if network != "udp" && network != "tcp" {
		return nil, errNetworkPolicyUnavailable
	}
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	p.mu.Lock()
	if !p.enabled || p.binder == nil || len(p.dns) == 0 {
		p.mu.Unlock()
		return nil, errNetworkPolicyUnavailable
	}
	id := p.dnsNext
	p.dnsNext++
	server := p.dns[id%uint64(len(p.dns))]
	generation := p.generation
	if p.pending == nil {
		p.pending = make(map[uint64]context.CancelFunc)
	}
	p.pending[id] = cancel
	p.mu.Unlock()
	dialer := net.Dialer{Control: p.control}
	connection, err := dialer.DialContext(ctx, network, server)
	p.mu.Lock()
	delete(p.pending, id)
	if err != nil {
		p.mu.Unlock()
		return nil, err
	}
	if !p.enabled || generation != p.generation || ctx.Err() != nil {
		p.mu.Unlock()
		connection.Close()
		return nil, errNetworkPolicyUnavailable
	}
	wrapped := &policyDNSConn{Conn: connection, policy: p}
	if p.connections == nil {
		p.connections = make(map[*policyDNSConn]struct{})
	}
	p.connections[wrapped] = struct{}{}
	p.mu.Unlock()
	if packet, ok := connection.(net.PacketConn); ok {
		return &policyDNSPacketConn{policyDNSConn: wrapped, packet: packet}, nil
	}
	return wrapped, nil
}

type policyDNSConn struct {
	net.Conn
	policy *socketPolicy
}

func (c *policyDNSConn) Close() error {
	err := c.Conn.Close()
	c.policy.mu.Lock()
	delete(c.policy.connections, c)
	c.policy.mu.Unlock()
	return err
}

// Preserve PacketConn so net.Resolver uses UDP framing rather than adding the
// TCP length prefix to datagrams.
type policyDNSPacketConn struct {
	*policyDNSConn
	packet net.PacketConn
}

func (c *policyDNSPacketConn) ReadFrom(data []byte) (int, net.Addr, error) {
	return c.packet.ReadFrom(data)
}

func (c *policyDNSPacketConn) WriteTo(data []byte, address net.Addr) (int, error) {
	return c.packet.WriteTo(data, address)
}

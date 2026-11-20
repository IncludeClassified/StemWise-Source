package mobile

import (
	"encoding/json"
	"errors"
	"net"
	"net/netip"
	"sync/atomic"

	"tailscale.com/net/netmon"
)

var androidInterfaces atomic.Pointer[[]netmon.Interface]

func interfaces(snapshot string) ([]netmon.Interface, error) {
	if len(snapshot) > 65536 {
		return nil, errors.New("Oversized Android network state")
	}
	var input []struct {
		Index        int      `json:"index"`
		Name         string   `json:"name"`
		MTU          int      `json:"mtu"`
		Up           bool     `json:"up"`
		Loopback     bool     `json:"loopback"`
		PointToPoint bool     `json:"pointToPoint"`
		Multicast    bool     `json:"multicast"`
		Addresses    []string `json:"addresses"`
	}
	if err := json.Unmarshal([]byte(snapshot), &input); err != nil || input == nil || len(input) > 64 {
		return nil, errors.New("Malformed Android network state")
	}
	result := make([]netmon.Interface, 0, len(input))
	seen := make(map[string]bool)
	for _, i := range input {
		if i.Index < 1 || i.Name == "" || len(i.Name) > 64 || i.MTU < 0 || seen[i.Name] {
			return nil, errors.New("Invalid Android interface")
		}
		seen[i.Name] = true
		var flags net.Flags
		if i.Up {
			flags |= net.FlagUp
		}
		if i.Loopback {
			flags |= net.FlagLoopback
		}
		if i.PointToPoint {
			flags |= net.FlagPointToPoint
		}
		if i.Multicast {
			flags |= net.FlagMulticast
		}
		addrs := make([]net.Addr, 0, len(i.Addresses))
		for _, a := range i.Addresses {
			prefix, err := netip.ParsePrefix(a)
			if err != nil {
				return nil, errors.New("Invalid Android interface address")
			}
			addrs = append(addrs, &net.IPNet{IP: net.IP(prefix.Addr().AsSlice()), Mask: net.CIDRMask(prefix.Bits(), prefix.Addr().BitLen())})
		}
		result = append(result, netmon.Interface{Interface: &net.Interface{Index: i.Index, Name: i.Name, MTU: i.MTU, Flags: flags}, AltAddrs: addrs})
	}
	return result, nil
}

// SetNetworkState receives Android's authoritative Java network snapshot before
// Start and on ConnectivityManager changes. Go's net.Interfaces is blocked on
// current Android; pretending it works would leave the embedded node unreliable.
func (c *Client) SetNetworkState(snapshot, defaultInterface, gateway string) error {
	parsed, err := interfaces(snapshot)
	if err != nil {
		return err
	}
	if len(defaultInterface) > 64 {
		return errors.New("Invalid default interface")
	}
	if defaultInterface != "" {
		found := false
		for _, i := range parsed {
			if i.Name == defaultInterface {
				found = true
			}
		}
		if !found {
			return errors.New("Default interface absent from native snapshot")
		}
	}
	if gateway != "" {
		if _, err := netip.ParseAddr(gateway); err != nil {
			return errors.New("Invalid native gateway")
		}
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return errors.New("Bridge transport closed")
	}
	if err := platformNetworkOwner(c); err != nil {
		return err
	}
	androidInterfaces.Store(&parsed)
	platformNetwork(defaultInterface, gateway)
	if c.local != nil {
		c.node.Sys().NetMon.Get().InjectEvent()
	}
	return nil
}

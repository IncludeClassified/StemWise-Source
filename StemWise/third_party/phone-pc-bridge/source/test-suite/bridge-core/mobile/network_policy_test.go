package mobile

import (
	"context"
	"errors"
	"io"
	"net"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

type policyTestBinder struct {
	binds atomic.Int32
	deny  atomic.Bool
}

func (b *policyTestBinder) BindSocket(fd int) error {
	if fd < 0 {
		return errors.New("invalid socket")
	}
	b.binds.Add(1)
	if b.deny.Load() {
		return errNetworkPolicyUnavailable
	}
	return nil
}

func TestNetworkPolicyGatesActualTCPSockets(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	binder := &policyTestBinder{}
	policy := &socketPolicy{binder: binder}
	dialer := net.Dialer{Timeout: time.Second, Control: policy.control}
	if conn, err := dialer.Dial("tcp", listener.Addr().String()); err == nil {
		conn.Close()
		t.Fatal("suspended policy created a TCP connection")
	}
	if binder.binds.Load() != 0 {
		t.Fatal("denied policy reached Android binder")
	}
	if err := policy.resume(); err != nil {
		t.Fatal(err)
	}
	connection, err := dialer.Dial("tcp", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	if binder.binds.Load() != 1 {
		t.Fatal("actual TCP socket bypassed binder")
	}
	accepted, err := listener.Accept()
	if err != nil {
		t.Fatal(err)
	}
	accepted.Close()
	binder.deny.Store(true)
	if conn, err := dialer.Dial("tcp", listener.Addr().String()); err == nil {
		conn.Close()
		t.Fatal("binder refusal still connected")
	}
	policy.suspend()
	if conn, err := dialer.Dial("tcp", listener.Addr().String()); err == nil {
		conn.Close()
		t.Fatal("policy loss still connected")
	}
}

func TestNetworkPolicyGatesActualUDPAndClosesDNSOnLoss(t *testing.T) {
	listener, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	binder := &policyTestBinder{}
	policy := &socketPolicy{binder: binder, dns: []string{listener.LocalAddr().String()}}
	if _, err := policy.dialDNS(context.Background(), "udp", "203.0.113.9:53"); err == nil {
		t.Fatal("suspended DNS socket created")
	}
	if err := policy.resume(); err != nil {
		t.Fatal(err)
	}
	connection, err := policy.dialDNS(context.Background(), "udp", "203.0.113.9:53")
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	if binder.binds.Load() != 1 {
		t.Fatal("actual UDP socket bypassed binder")
	}
	if _, err := connection.Write([]byte("approved-network-only")); err != nil {
		t.Fatal(err)
	}
	listener.SetReadDeadline(time.Now().Add(time.Second))
	data := make([]byte, 64)
	n, peer, err := listener.ReadFrom(data)
	if err != nil || string(data[:n]) != "approved-network-only" {
		t.Fatal("DNS did not use approved server", err)
	}
	if _, err := listener.WriteTo([]byte("ack"), peer); err != nil {
		t.Fatal(err)
	}
	connection.SetReadDeadline(time.Now().Add(time.Second))
	if n, err := connection.Read(data); err != nil || string(data[:n]) != "ack" {
		t.Fatal("UDP exchange failed", err)
	}
	policy.suspend()
	if _, err := connection.Write([]byte("forbidden")); !errors.Is(err, net.ErrClosed) {
		t.Fatal("existing DNS socket survived policy loss", err)
	}
	if _, err := policy.dialDNS(context.Background(), "udp", listener.LocalAddr().String()); err == nil {
		t.Fatal("policy loss recreated DNS socket")
	}
	if err := policy.resume(); err != nil {
		t.Fatal(err)
	}
	restored, err := policy.dialDNS(context.Background(), "udp", "203.0.113.9:53")
	if err != nil {
		t.Fatal("approved policy did not recover", err)
	}
	restored.Close()
}

func TestNetworkPolicyClosesActualTCPDNS(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	policy := &socketPolicy{binder: &policyTestBinder{}, dns: []string{listener.Addr().String()}}
	if err := policy.resume(); err != nil {
		t.Fatal(err)
	}
	connection, err := policy.dialDNS(context.Background(), "tcp", "203.0.113.9:53")
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	peer, err := listener.Accept()
	if err != nil {
		t.Fatal(err)
	}
	defer peer.Close()
	policy.suspend()
	peer.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := peer.Read(make([]byte, 1)); !errors.Is(err, io.EOF) {
		t.Fatal("TCP DNS peer did not observe closure", err)
	}
	policy.mu.Lock()
	pending := len(policy.pending)
	connections := len(policy.connections)
	policy.mu.Unlock()
	if pending != 0 || connections != 0 {
		t.Fatal("suspended policy leaked DNS resources", pending, connections)
	}
	if _, err := policy.dialDNS(context.Background(), "tcp4", listener.Addr().String()); err == nil {
		t.Fatal("unsupported resolver transport allowed")
	}
}

func TestNetworkPolicyActualResolverUsesOnlyApprovedDNS(t *testing.T) {
	listener, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	serverDone := make(chan error, 1)
	go func() {
		listener.SetReadDeadline(time.Now().Add(2 * time.Second))
		buffer := make([]byte, 1500)
		n, peer, err := listener.ReadFrom(buffer)
		if err != nil {
			serverDone <- err
			return
		}
		var parser dnsmessage.Parser
		header, err := parser.Start(buffer[:n])
		if err != nil {
			serverDone <- err
			return
		}
		question, err := parser.Question()
		if err != nil || question.Name.String() != "bridge-policy.test." || question.Type != dnsmessage.TypeA {
			serverDone <- errors.New("unexpected actual DNS query")
			return
		}
		message := dnsmessage.Message{
			Header:    dnsmessage.Header{ID: header.ID, Response: true, Authoritative: true, RecursionAvailable: true},
			Questions: []dnsmessage.Question{question},
			Answers:   []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: question.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}, Body: &dnsmessage.AResource{A: [4]byte{192, 0, 2, 99}}}},
		}
		reply, err := message.Pack()
		if err == nil {
			_, err = listener.WriteTo(reply, peer)
		}
		serverDone <- err
	}()
	binder := &policyTestBinder{}
	policy := &socketPolicy{binder: binder, dns: []string{listener.LocalAddr().String()}}
	if err := policy.resume(); err != nil {
		t.Fatal(err)
	}
	defer policy.suspend()
	resolver := &net.Resolver{PreferGo: true, Dial: policy.dialDNS}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	ips, err := resolver.LookupIP(ctx, "ip4", "bridge-policy.test.")
	if err != nil || len(ips) != 1 || ips[0].String() != "192.0.2.99" {
		t.Fatal("actual resolver did not use approved DNS", ips, err)
	}
	select {
	case err := <-serverDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-ctx.Done():
		t.Fatal("approved DNS server did not finish")
	}
	bindCount := binder.binds.Load()
	if bindCount != 1 {
		t.Fatal("resolver socket bypassed policy", bindCount)
	}
	policy.suspend()
	if _, err := resolver.LookupIP(ctx, "ip4", "unapproved-policy.test."); err == nil {
		t.Fatal("suspended resolver still resolved")
	}
	if binder.binds.Load() != bindCount {
		t.Fatal("suspended resolver opened another socket")
	}
}

func TestNetworkPolicyConfigurationBeforeStartup(t *testing.T) {
	c, err := NewClient(filepath.Join(t.TempDir(), "node"), "offline-policy", "secret-not-sent")
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if err := c.SetSocketBinder(nil); err == nil {
		t.Fatal("nil policy accepted")
	}
	if err := c.SetNetworkDNS(`[]`); err == nil {
		t.Fatal("DNS accepted without binder")
	}
	if err := c.SetSocketBinder(&policyTestBinder{}); err != nil {
		t.Fatal(err)
	}
	if err := c.SetSocketBinder(&policyTestBinder{}); err == nil {
		t.Fatal("policy owner replaced")
	}
	for _, raw := range []string{`null`, `{}`, `["example.com"]`, `["192.0.2.1:53"]`, `["0.0.0.0"]`, `["ff02::1"]`, strings.Repeat(" ", 2049)} {
		if err := c.SetNetworkDNS(raw); err == nil {
			t.Fatal("invalid approved DNS accepted", raw)
		}
	}
	if err := c.SetNetworkDNS(`["192.0.2.53","2001:db8::53","192.0.2.53"]`); err != nil {
		t.Fatal(err)
	}
	if len(c.socketPolicy.dns) != 2 || c.socketPolicy.dns[0] != "192.0.2.53:53" || c.socketPolicy.dns[1] != "[2001:db8::53]:53" {
		t.Fatal("DNS IP contract not preserved")
	}
	if err := c.socketPolicy.resume(); err != nil {
		t.Fatal(err)
	}
	if err := c.SetNetworkDNS(`[]`); err == nil {
		t.Fatal("live DNS policy changed without suspension")
	}
	c.socketPolicy.suspend()
	if err := c.SetNetworkDNS(`[]`); err != nil {
		t.Fatal(err)
	}
	if err := c.socketPolicy.resume(); err != nil {
		t.Fatal(err)
	}
	if _, err := c.socketPolicy.dialDNS(context.Background(), "udp", "192.0.2.1:53"); err == nil {
		t.Fatal("missing approved DNS fell back")
	}
	if c.started || c.local != nil {
		t.Fatal("policy configuration started node")
	}
}

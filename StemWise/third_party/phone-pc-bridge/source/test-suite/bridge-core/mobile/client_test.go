package mobile

import (
	"context"
	"encoding/json"
	"net/netip"
	"path/filepath"
	"testing"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/types/key"
)

func TestPairingBoundary(t *testing.T) {
	s := &ipnstate.Status{BackendState: "Running", Self: &ipnstate.PeerStatus{ID: "node-app", UserID: 1}}
	s.Peer = make(map[key.NodePublic]*ipnstate.PeerStatus)
	p := &ipnstate.PeerStatus{ID: "node-pc", DNSName: "pc.tailtest.ts.net.", UserID: 1, Online: false,
		TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.20")}}
	s.Peer[key.NodePublic{}] = p
	if got, err := target(s, "pc.tailtest.ts.net", "node-pc"); err != nil || got != p.TailscaleIPs[0] {
		t.Fatal("Exact paired target rejected", err)
	}
	for _, host := range []string{"pc.tailtest.ts.net.evil.test", "http://pc.tailtest.ts.net", "pc.tailtest.ts.net:80", "other.tailtest.ts.net"} {
		if _, err := target(s, host, "node-pc"); err == nil {
			t.Fatal("Wrong host accepted")
		}
	}
	if _, err := target(s, "pc.tailtest.ts.net", "node-other"); err == nil {
		t.Fatal("Wrong identity accepted")
	}
	p.UserID = 2
	if _, err := target(s, "pc.tailtest.ts.net", "node-pc"); err == nil {
		t.Fatal("Other account accepted")
	}
	p.UserID = 1
	p.Expired = true
	if _, err := target(s, "pc.tailtest.ts.net", "node-pc"); err == nil {
		t.Fatal("Expired device accepted")
	}
	p.Expired = false
	p.TailscaleIPs = []netip.Addr{netip.MustParseAddr("192.168.100.5")}
	if _, err := target(s, "pc.tailtest.ts.net", "node-pc"); err == nil {
		t.Fatal("LAN fallback accepted")
	}
	p.TailscaleIPs = []netip.Addr{netip.MustParseAddr("100.64.0.20")}
	s.BackendState = "Stopped"
	if _, err := target(s, "pc.tailtest.ts.net", "node-pc"); err == nil {
		t.Fatal("Stopped transport accepted")
	}
	if _, err := NewClient("relative-path", "bridge-app", ""); err == nil {
		t.Fatal("Nonprivate relative state directory accepted")
	}
	absolute, err := filepath.Abs(".")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := NewClient(absolute, "invalid/name", ""); err == nil {
		t.Fatal("Invalid node name accepted")
	}
}

func TestAndroidNetworkSnapshot(t *testing.T) {
	valid := `[{"index":3,"name":"wlan0","mtu":1500,"up":true,"addresses":["192.168.100.20/24","fe80::1234/64"]}]`
	actual, err := interfaces(valid)
	if err != nil || len(actual) != 1 || !actual[0].IsUp() || len(actual[0].AltAddrs) != 2 {
		t.Fatal("Native interface snapshot lost", err)
	}
	for _, invalid := range []string{`null`, `{}`, `[{"index":0,"name":"wlan0"}]`, `[{"index":3,"name":"wlan0","addresses":["bad-address"]}]`, `[{"index":3,"name":"wlan0"},{"index":4,"name":"wlan0"}]`} {
		if _, err := interfaces(invalid); err == nil {
			t.Fatal("Malformed/ambiguous network state accepted")
		}
	}
	c := &Client{active: make(map[string]context.CancelFunc)}
	if err := c.SetNetworkState(valid, "wlan0", "192.168.100.1"); err != nil {
		t.Fatal(err)
	}
	if err := c.SetNetworkState(valid, "missing0", ""); err == nil {
		t.Fatal("Missing default route accepted")
	}
	if err := c.Close(); err != nil {
		t.Fatal("Unstarted transport cannot close cleanly", err)
	}
	if err := c.SetNetworkState(valid, "wlan0", ""); err == nil {
		t.Fatal("Closed transport updated")
	}
}

func TestEnrollmentFailuresRemainDistinctAndFailClosed(t *testing.T) {
	for _, variant := range []string{"valid", "login", "approval", "self_expired", "peer_expired", "stopped", "starting", "nil_status", "nil_self", "owner_missing", "wrong_host", "wrong_owner"} {
		t.Run(variant, func(t *testing.T) {
			p := &ipnstate.PeerStatus{ID: "node-pi", DNSName: "pi.tailtest.ts.net.", UserID: 1,
				TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.20")}}
			s := &ipnstate.Status{BackendState: "Running", Self: &ipnstate.PeerStatus{ID: "node-app", UserID: 1},
				Peer: map[key.NodePublic]*ipnstate.PeerStatus{{}: p}}
			want, retryable := "bridge_transport_starting", true
			switch variant {
			case "valid":
				want = ""
			case "login":
				s.BackendState, s.Self = "NeedsLogin", nil
				want = "bridge_enrollment_required"
				retryable = false
			case "approval":
				s.BackendState = "NeedsMachineAuth"
				want = "bridge_device_approval_required"
				retryable = false
			case "self_expired":
				s.Self.Expired = true
				want = "bridge_enrollment_required"
				retryable = false
			case "peer_expired":
				p.Expired = true
				want = "paired_device_enrollment_expired"
				retryable = false
			case "stopped", "starting":
				s.BackendState = "Starting"
				if variant == "stopped" {
					s.BackendState = "Stopped"
					want, retryable = "bridge_transport_stopped", false
				}
				s.Self.Expired = true
			case "nil_status":
				s = nil
			case "nil_self":
				s.Self = nil
			case "owner_missing":
				s.Self.UserID, s.Self.Expired = 0, true
			case "wrong_host":
				p.DNSName, p.Expired = "other.tailtest.ts.net.", true
				want, retryable = "paired_dns_mismatch", false
			case "wrong_owner":
				p.UserID, p.Expired = 2, true
				want, retryable = "paired_owner_mismatch", false
			}
			address, err := target(s, "pi.tailtest.ts.net", "node-pi")
			if want == "" {
				if err != nil || address != p.TailscaleIPs[0] {
					t.Fatal("Healthy enrolled pairing was rejected", err)
				}
			} else {
				var failure readinessFailure
				if err == nil || json.Unmarshal([]byte(err.Error()), &failure) != nil || failure.Protocol != "v1" || failure.Code != want || failure.Retryable != retryable || failure.RequestSent || failure.Phase != "pre_send" || address.IsValid() {
					t.Fatal("Readiness status was hidden, misclassified or allowed a private request", err, address)
				}
			}
		})
	}
}

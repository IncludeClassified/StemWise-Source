package mobile

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strings"
	"testing"
	"time"

	"phonepcbridge/protocol"
	"tailscale.com/tsnet"
)

func TestConnectionReturnsPermanentReadinessFailureAfterOneProbe(t *testing.T) {
	for _, code := range []string{"paired_identity_mismatch", "paired_dns_mismatch", "paired_owner_mismatch", "paired_identity_ambiguous", "paired_address_invalid", "bridge_transport_stopped", "bridge_transport_unavailable"} {
		t.Run(code, func(t *testing.T) {
			client := &Client{node: &tsnet.Server{}, active: make(map[string]context.CancelFunc)}
			conn, err := client.NewConnection("pc.tailtest.ts.net", "nPc", "", "")
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			cause := fmt.Errorf("original exact probe: %w", readinessError(code, false, nil))
			calls := 0
			conn.pollInterval = time.Millisecond
			conn.exchange = func(context.Context, time.Duration, string, string, string, int, string, string, []byte) (string, error) {
				calls++
				return "", cause
			}
			if result, err := conn.Connect("permanent-readiness", 35); result != "" || err != cause || calls != 1 {
				t.Fatal("Permanent readiness refusal became repeated probing or a generic timeout", result, err, calls)
			}
			if len(client.active) != 0 {
				t.Fatal("Permanent refusal leaked its request lease")
			}
		})
	}
}

func TestConnectionRetriesTransientReadinessBeforeOriginalHealth(t *testing.T) {
	client := &Client{node: &tsnet.Server{}, active: make(map[string]context.CancelFunc)}
	conn, err := client.NewConnection("pc.tailtest.ts.net", "nPc", "", "")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.pollInterval = time.Millisecond
	calls := 0
	conn.exchange = func(_ context.Context, _ time.Duration, _, _, _ string, _ int, method, path string, _ []byte) (string, error) {
		calls++
		if method != "GET" {
			t.Fatal("Connection monitor attempted a mutation")
		}
		if calls < 3 {
			return "", readinessError("paired_peer_map_pending", true, nil)
		}
		u, err := url.Parse(path)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := json.Marshal(protocol.Health{Protocol: protocol.Codex, PCIdentity: "nPc", Nonce: u.Query().Get("nonce"), Epoch: strings.Repeat("a", 32)})
		wire, err := json.Marshal(map[string]any{"status": 200, "body": body})
		return string(wire), err
	}
	if result, err := conn.Connect("transient-readiness", 1000); err != nil || !strings.Contains(result, `"state":"connected"`) || calls != 3 {
		t.Fatal("Transient readiness failed to recover through original PC health", result, err, calls)
	}
}

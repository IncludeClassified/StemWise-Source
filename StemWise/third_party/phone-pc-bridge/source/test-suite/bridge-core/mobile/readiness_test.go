package mobile

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/types/key"
)

func TestColdProcessFirstAppCallWaitsForExactPeer(t *testing.T) {
	if os.Getenv("BRIDGE_COLD_READINESS_CHILD") != "1" {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestColdProcessFirstAppCallWaitsForExactPeer$")
		cmd.Env = append(os.Environ(), "BRIDGE_COLD_READINESS_CHILD=1")
		if output, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("Cold SDK process failed: %v\n%s", err, output)
		}
		return
	}
	c, statuses := streamReadinessClient(t, func(_ *http.Request, call int) (string, error) {
		switch call {
		case 1:
			return `{"BackendState":"NeedsLogin","HaveNodeKey":true,"Self":{}}`, nil
		case 2:
			return `{"BackendState":"Starting"}`, nil
		case 3:
			return `{"BackendState":"Running","Self":{"ID":"nApp","UserID":1},"Peer":{}}`, nil
		default:
			return streamReadyStatus, nil
		}
	})
	var dials, requests atomic.Int32
	served := make(chan error, 1)
	c.dial = func(_ context.Context, network, address string) (net.Conn, error) {
		if dials.Add(1) != 1 || statuses.Load() < 4 || network != "tcp" || address != "100.64.0.8:8781" {
			return nil, errors.New("request dialed before exact peer readiness or replayed")
		}
		client, server := net.Pipe()
		_ = server.SetDeadline(time.Now().Add(3 * time.Second))
		go func() {
			defer server.Close()
			r, err := http.ReadRequest(bufio.NewReader(server))
			if err != nil {
				served <- err
				return
			}
			defer r.Body.Close()
			requests.Add(1)
			if r.Method != "GET" || r.URL.Path != "/v2/codex/models" || r.Host != "pi.tailtest.ts.net:8781" {
				served <- errors.New("cold call changed the original app request")
				return
			}
			body := `{"protocol":"phone-pc-bridge-app-v1","bridgeIdentity":"nPi","data":{"data":[],"nextCursor":null}}`
			_, err = fmt.Fprintf(server, "HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s", len(body), body)
			served <- err
		}()
		return client, nil
	}
	raw, err := c.CallApp("first-cold-call", "pi.tailtest.ts.net", "nPi", "GET", "/v2/codex/models", "")
	if err != nil {
		t.Fatal("First call required a user retry despite eventual exact readiness", err)
	}
	var reply struct {
		HTTPStatus int `json:"httpStatus"`
	}
	if json.Unmarshal([]byte(raw), &reply) != nil || reply.HTTPStatus != 200 || requests.Load() != 1 || dials.Load() != 1 || statuses.Load() != 4 {
		t.Fatal("Cold first call did not preserve one actual HTTP request", raw, statuses.Load(), requests.Load())
	}
	select {
	case err := <-served:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(4 * time.Second):
		t.Fatal("Owned cold HTTP peer did not retire")
	}
}

func TestAppMutationsAreNotReplayedAfterLostAcknowledgment(t *testing.T) {
	for _, method := range []string{"POST", "PUT", "DELETE"} {
		t.Run(method, func(t *testing.T) {
			c, statuses := streamReadinessClient(t, func(_ *http.Request, _ int) (string, error) { return streamReadyStatus, nil })
			var dials, requests atomic.Int32
			served := make(chan error, 1)
			c.dial = func(context.Context, string, string) (net.Conn, error) {
				if dials.Add(1) != 1 {
					return nil, errors.New("ambiguous mutation replayed")
				}
				client, server := net.Pipe()
				_ = server.SetDeadline(time.Now().Add(3 * time.Second))
				go func() {
					defer server.Close()
					r, err := http.ReadRequest(bufio.NewReader(server))
					if err == nil {
						defer r.Body.Close()
						var body []byte
						body, err = io.ReadAll(r.Body)
						requests.Add(1)
						if r.Method != method || string(body) != `{"original":true}` {
							err = errors.New("original mutation changed")
						}
					}
					served <- err // Close after receiving bytes, without any HTTP acknowledgment.
				}()
				return client, nil
			}
			_, err := c.CallApp("lost-ack-"+method, "pi.tailtest.ts.net", "nPi", method, "/v2/jobs", `{"original":true}`)
			if err == nil || strings.Contains(err.Error(), `"requestSent":false`) || dials.Load() != 1 || requests.Load() != 1 || statuses.Load() != 1 {
				t.Fatal("Lost acknowledgment was replayed or falsely marked not sent", err, dials.Load(), requests.Load())
			}
			select {
			case err := <-served:
				if err != nil {
					t.Fatal(err)
				}
			case <-time.After(4 * time.Second):
				t.Fatal("Owned mutation peer did not retire")
			}
		})
	}
}

func TestReadinessBudgetAndEnrollmentGraceAreBounded(t *testing.T) {
	for _, phase := range []struct {
		raw, code string
		retryable bool
	}{
		{`{"BackendState":"Starting"}`, "bridge_transport_starting", true},
		{`{"BackendState":"Running","Self":{"ID":"nApp","UserID":1},"Peer":{}}`, "paired_peer_map_pending", true},
		{`{"BackendState":"NeedsLogin","HaveNodeKey":true,"Self":{}}`, "bridge_enrollment_required", false},
		{`{"BackendState":"NeedsMachineAuth","HaveNodeKey":true,"Self":{}}`, "bridge_device_approval_required", false},
	} {
		t.Run(phase.code, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				c, _ := streamReadinessClient(t, func(_ *http.Request, _ int) (string, error) { return phase.raw, nil })
				start := time.Now()
				_, _, err := c.readyTarget(context.Background(), c.local, "pi.tailtest.ts.net", "nPi")
				var failure readinessFailure
				if err == nil || json.Unmarshal([]byte(err.Error()), &failure) != nil || failure.Code != phase.code || failure.Retryable != phase.retryable || failure.RequestSent || !errors.Is(err, context.DeadlineExceeded) || time.Since(start) != readinessTimeout {
					t.Fatal("Readiness did not terminate at its own bound with truthful no-send classification", err, time.Since(start))
				}
			})
		})
	}
}

func TestKnownPairingConflictsAreHardEvenDuringStartup(t *testing.T) {
	for _, kind := range []string{"identity", "dns", "owner", "self", "duplicate"} {
		t.Run(kind, func(t *testing.T) {
			p := &ipnstate.PeerStatus{ID: "nPi", DNSName: "pi.tailtest.ts.net.", UserID: 1}
			s := &ipnstate.Status{BackendState: "Starting", Self: &ipnstate.PeerStatus{ID: "nApp", UserID: 1}, Peer: map[key.NodePublic]*ipnstate.PeerStatus{{}: p}}
			want := "paired_" + kind + "_mismatch"
			switch kind {
			case "identity":
				p.ID = "nOther"
			case "dns":
				p.DNSName = "other.tailtest.ts.net."
			case "owner":
				p.UserID = 2
			case "self":
				s.Self.ID = "nPi"
				want = "paired_identity_mismatch"
			case "duplicate":
				s.Peer[key.NewNode().Public()] = p
				want = "paired_identity_ambiguous"
			}
			_, err := target(s, "pi.tailtest.ts.net", "nPi")
			var failure readinessFailure
			if err == nil || json.Unmarshal([]byte(err.Error()), &failure) != nil || failure.Code != want || failure.Retryable || failure.RequestSent {
				t.Fatal("Known pairing conflict became retryable startup", err)
			}
		})
	}
}

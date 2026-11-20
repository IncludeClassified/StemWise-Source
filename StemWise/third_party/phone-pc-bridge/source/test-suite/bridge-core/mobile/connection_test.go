package mobile

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"phonepcbridge/protocol"
	"tailscale.com/tsnet"
)

func TestConnectionReturnsEnrollmentFailureWithoutPollingOrWake(t *testing.T) {
	for _, cause := range []error{errEnrollmentRequired, errDeviceApprovalRequired, errPeerEnrollmentExpired} {
		t.Run(cause.Error(), func(t *testing.T) {
			client := &Client{node: &tsnet.Server{}, active: make(map[string]context.CancelFunc)}
			conn, err := client.NewConnection("pc.tailtest.ts.net", "nPc", "pi.tailtest.ts.net", "nPi")
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			calls := 0
			conn.pollInterval = 5 * time.Millisecond
			conn.exchange = func(_ context.Context, _ time.Duration, _, host, _ string, _ int, method, _ string, _ []byte) (string, error) {
				calls++
				if host != conn.pcHost || method != "GET" {
					t.Fatal("Enrollment failure attempted a wake or mutation")
				}
				return "", errors.Join(errors.New("owned probe failure"), cause)
			}
			result, err := conn.Connect("owned-enrollment", 35)
			if result != "" || !errors.Is(err, cause) || calls != 1 || len(client.active) != 0 {
				t.Fatal("Enrollment failure became a deadline, replayed polling or retained its request", err, calls)
			}
		})
	}
}

func TestConnectionWakeAndCancellation(t *testing.T) {
	client := &Client{node: &tsnet.Server{}, active: make(map[string]context.CancelFunc)}
	conn, err := client.NewConnection("pc.tailtest.ts.net", "nPc", "pi.tailtest.ts.net", "nPi")
	if err != nil {
		t.Fatal(err)
	}
	var online, releasePC, wrongHealth atomic.Bool
	var wakeCount atomic.Int64
	var replyMode atomic.Value
	replyMode.Store("normal")
	blocked := make(chan struct{}, 1)
	probeDeadline := make(chan time.Duration, 32)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/v1/health":
			if !online.Load() {
				w.WriteHeader(503)
				return
			}
			if releasePC.Load() {
				select {
				case blocked <- struct{}{}:
				default:
				}
				<-r.Context().Done()
				return
			}
			id := "nPc"
			if wrongHealth.Load() {
				id = "wrong-pc"
			}
			json.NewEncoder(w).Encode(protocol.Health{Protocol: protocol.Codex, PCIdentity: id, Epoch: strings.Repeat("a", 32), Nonce: r.URL.Query().Get("nonce")})
		case "/v2/connection/wake":
			wakeCount.Add(1)
			if r.Method != "POST" || r.ContentLength != 0 || r.Header.Get("Transfer-Encoding") != "" || r.Header.Get("Content-Length") != "0" || r.URL.RawQuery != "" {
				t.Error("Invalid actual relay request framing")
				w.WriteHeader(400)
				return
			}
			expected := map[string]any{"protocol": protocol.App, "bridgeIdentity": "nPi", "pcIdentity": "nPc", "data": map[string]string{"state": "packet_sent", "pcState": "unconfirmed"}}
			switch replyMode.Load().(string) {
			case "wrong-nonce":
				expected["protocol"] = "wrong-protocol"
			case "wrong-pi":
				expected["bridgeIdentity"] = "other"
			case "wrong-pc":
				expected["pcIdentity"] = "other"
			case "oversized":
				expected["extra"] = strings.Repeat("x", 4097)
			case "redirect":
				w.Header().Set("Location", "/v1/health")
				w.WriteHeader(302)
				return
			case "wrong-status":
				w.WriteHeader(200)
				json.NewEncoder(w).Encode(expected)
				return
			}
			w.WriteHeader(202)
			json.NewEncoder(w).Encode(expected)
		default:
			w.WriteHeader(404)
		}
	}))
	defer server.Close()
	// Native private dialing/ownership is adapted. The SDK's actual validation,
	// request bytes, deadlines, cancellation and connection window run over HTTP.
	conn.exchange = func(parent context.Context, timeout time.Duration, id, host, identity string, port int, method, path string, body []byte) (string, error) {
		if host == conn.pcHost {
			if identity != "nPc" || port != protocol.Port || timeout != 3*time.Second {
				t.Error("Probe used a wrong target/deadline")
			}
		} else if host == conn.relayHost {
			if identity != "nPi" || port != protocol.PiPort || timeout != 25*time.Second {
				t.Error("Wake used a wrong target/deadline")
			}
		} else {
			return "", errors.New("Unknown target")
		}
		ctx, cancel := context.WithTimeout(parent, timeout)
		defer cancel()
		if host == conn.pcHost {
			deadline, _ := ctx.Deadline()
			select {
			case probeDeadline <- time.Until(deadline):
			default:
			}
		}
		r, err := http.NewRequestWithContext(ctx, method, server.URL+path, bytes.NewReader(body))
		if err != nil {
			return "", err
		}
		response, err := (&http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}).Do(r)
		if err != nil {
			return "", err
		}
		defer response.Body.Close()
		data, err := io.ReadAll(response.Body)
		if err != nil {
			return "", err
		}
		if err := ctx.Err(); err != nil {
			return "", err
		}
		raw, err := json.Marshal(map[string]any{"status": response.StatusCode, "body": data})
		return string(raw), err
	}
	conn.pollInterval = 5 * time.Millisecond
	if _, err := new(Connection).Check("uninitialized"); err == nil {
		t.Fatal("Uninitialized JNI connection accepted")
	}
	if _, err := conn.Check("offline"); err == nil {
		t.Fatal("Offline PC reported connected")
	}
	if ack, err := conn.Wake("wake"); err != nil || !strings.Contains(ack, `"state":"wake_sent"`) || strings.Contains(ack, `"state":"connected"`) {
		t.Fatal("Wake acknowledgment falsely became connection", err)
	}
	for _, mode := range []string{"wrong-nonce", "wrong-pi", "wrong-pc", "oversized", "redirect", "wrong-status"} {
		replyMode.Store(mode)
		if _, err := conn.Wake(mode); err == nil {
			t.Fatal("Invalid wake acknowledgment accepted", mode)
		}
	}
	replyMode.Store("normal")
	for len(probeDeadline) > 0 {
		<-probeDeadline
	}
	before := wakeCount.Load()
	if _, err := conn.Connect("monitor", 35); err == nil {
		t.Fatal("Offline monitor reported connected")
	}
	if wakeCount.Load() != before {
		t.Fatal("Monitor-only window emitted wake")
	}
	select {
	case remaining := <-probeDeadline:
		if remaining > 35*time.Millisecond || remaining <= 0 {
			t.Fatal("Probe exceeded the overall window deadline", remaining)
		}
	default:
		t.Fatal("No actual probe deadline observed")
	}
	online.Store(true)
	wrongHealth.Store(true)
	if _, err := conn.Check("wrong-pc"); err == nil {
		t.Fatal("Different PC health accepted")
	}
	wrongHealth.Store(false)
	if state, err := conn.Connect("reachable", 100); err != nil || !strings.Contains(state, `"state":"connected"`) {
		t.Fatal("Fresh paired PC response rejected", err)
	}
	releasePC.Store(true)
	result := make(chan error, 1)
	go func() { _, err := conn.Connect("cancel-window", 1000); result <- err }()
	select {
	case <-blocked:
	case <-time.After(time.Second):
		t.Fatal("Actual in-flight probe not observed")
	}
	if _, err := conn.Connect("second-window", 100); err == nil {
		t.Fatal("Concurrent window accepted")
	}
	client.Cancel("cancel-window")
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatal("Canceled window returned success", err)
		}
	case <-time.After(time.Second):
		t.Fatal("Cancel did not end actual HTTP request")
	}
	if _, err := client.NewConnection("pc.tailtest.ts.net", "nPc", "other.tailtest.ts.net", "nOther"); err == nil {
		t.Fatal("Active pairing silently changed")
	}
	go func() { _, err := conn.Connect("profile-close", 1000); result <- err }()
	select {
	case <-blocked:
	case <-time.After(time.Second):
		t.Fatal("Profile-close probe not observed")
	}
	conn.Close()
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatal("Profile close did not cancel active work", err)
		}
	case <-time.After(time.Second):
		t.Fatal("Profile close did not terminate active HTTP")
	}
	if _, err := conn.Check("closed-profile"); err == nil {
		t.Fatal("Closed profile reported connected")
	}
	if _, err := client.NewConnection("pc.tailtest.ts.net", "nPc", "other.tailtest.ts.net", "nOther"); err != nil {
		t.Fatal("Deliberate pairing edit required closing the node", err)
	}
	if _, err := client.NewConnection("other-pc.tailtest.ts.net", "nOther", "partial.tailtest.ts.net", ""); err == nil {
		t.Fatal("Incomplete pairing accepted")
	}
	client.Close()
	if _, err := conn.Check("closed"); err == nil {
		t.Fatal("Closed owning client reported connected")
	}
}

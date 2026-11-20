package backend

import (
	"context"
	"encoding/json"
	"errors"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

func TestWakeOnlyExplicitAppRequestIncludingLegacyState(t *testing.T) {
	s, n, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	var packets atomic.Int64
	s.wake = func(context.Context) error { packets.Add(1); return nil }
	if w := call("PUT", "/v2/connection/settings", map[string]bool{"autoWake": true}, "nAlice"); w.Code != 404 {
		t.Fatal(w.Body.String())
	}
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Pending", Text: "original-" + randomToken(), Files: []protocol.File{}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	for i := 0; i < 3; i++ {
		if err := s.workPass(); err != nil {
			t.Fatal(err)
		}
		for _, path := range []string{"/v2/connection", "/v2/app", "/v2/jobs", "/v2/jobs/" + input.RequestID + "/log"} {
			if w := call("GET", path, nil, "nAlice"); w.Code != 200 {
				t.Fatal(w.Body.String())
			}
		}
	}
	if packets.Load() != 0 || n.calls != 0 {
		t.Fatal("Pending jobs, old policy or polling sent wake", packets.Load(), n.calls)
	}
	if w := call("GET", "/v2/connection/wake", nil, "nAlice"); w.Code != 404 || packets.Load() != 0 {
		t.Fatal("Wrong method sent wake", w.Body.String())
	}
	for _, peer := range []string{"nDenied", "nPc", "nPi"} {
		if w := call("POST", "/v2/connection/wake", nil, peer); w.Code != 403 || packets.Load() != 0 {
			t.Fatal("Unpaired caller, PC adapter or Pi identity sent app wake", peer, w.Body.String())
		}
	}
	for _, body := range []string{`{}`, `{"autoWake":true}`, `null`, `unrecognized`, " "} {
		w := call("POST", "/v2/connection/wake", body, "nAlice")
		var response struct {
			Data appFailure `json:"data"`
		}
		if w.Code != 400 || packets.Load() != 0 || json.Unmarshal(w.Body.Bytes(), &response) != nil || response.Data.Code != "invalid_wake_request" || response.Data.Retryable {
			t.Fatal("Unexpected wake body reached the sender", body, w.Body.String())
		}
		for _, action := range []string{"Send to Bridge", "Retry", "settings Wake", "new explicit user action"} {
			if !strings.Contains(response.Data.Action, action) {
				t.Fatal("Actual wake error contradicts explicit authorization", action, w.Body.String())
			}
		}
	}
	if w := call("POST", "/v2/connection/wake", nil, "nAlice"); w.Code != 202 || packets.Load() != 1 || !strings.Contains(w.Body.String(), `"pcState":"unconfirmed"`) {
		t.Fatal(w.Body.String(), packets.Load())
	}
	if err := s.workPass(); err != nil || packets.Load() != 1 {
		t.Fatal("Manual request authorized later automatic wake", err)
	}
}

func TestUnconfirmedWakeRequiresNewUserRequest(t *testing.T) {
	s, _, call := fixtureAPI(t)
	advance := testPowerClock(s)
	registerFixture(t, call, "nAlice")
	var attempts atomic.Int64
	s.wake = func(context.Context) error { attempts.Add(1); return errors.New("injected_send_unknown") }
	w := call("POST", "/v2/connection/wake", nil, "nAlice")
	var reply struct {
		Data appFailure `json:"data"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &reply); err != nil {
		t.Fatal(err)
	}
	if w.Code != 503 || reply.Data.Code != "wake_unconfirmed" || reply.Data.Retryable || !strings.Contains(reply.Data.Action, "user") || attempts.Load() != 1 {
		t.Fatal("Uncertain send advertised automatic replay or false success", w.Body.String(), attempts.Load())
	}
	for i := 0; i < 3; i++ {
		if err := s.workPass(); err != nil {
			t.Fatal(err)
		}
		if w := call("GET", "/v2/connection", nil, "nAlice"); w.Code != 200 {
			t.Fatal(w.Body.String())
		}
	}
	if attempts.Load() != 1 {
		t.Fatal("Observation retried an uncertain wake", attempts.Load())
	}
	if w := call("POST", "/v2/connection/wake", nil, "nAlice"); w.Code != 202 || attempts.Load() != 1 || !strings.Contains(w.Body.String(), "wake_coalesced") {
		t.Fatal("Immediate explicit press bypassed the global cooldown", w.Body.String(), attempts.Load())
	}
	advance(powerWindow)
	if w := call("POST", "/v2/connection/wake", nil, "nAlice"); w.Code != 503 || attempts.Load() != 2 {
		t.Fatal("New explicit user request was rejected or duplicated", w.Body.String())
	}
}

func TestWakeEarlyErrorsNeverAuthorizeAutomaticRetry(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	var attempts atomic.Int64
	s.wake = func(context.Context) error { attempts.Add(1); return nil }
	s.cancel()
	for _, method := range []string{"POST", "GET"} {
		path := "/v2/connection"
		if method == "POST" {
			path += "/wake"
		}
		w := call(method, path, nil, "nAlice")
		var reply struct {
			Data appFailure `json:"data"`
		}
		if err := json.Unmarshal(w.Body.Bytes(), &reply); err != nil {
			t.Fatal(err)
		}
		if w.Code != 503 || reply.Data.Code != "bridge_stopping" || reply.Data.Retryable != (method == "GET") || attempts.Load() != 0 {
			t.Fatal("Wake transport error allowed retry or broke read-only recovery", w.Body.String(), attempts.Load())
		}
	}
}

func TestCancelledWakeNeverStartsSenderAfterAppLockWait(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	var attempts atomic.Int64
	s.wake = func(context.Context) error { attempts.Add(1); return nil }
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r := httptest.NewRequest("POST", "/v2/connection/wake", nil).WithContext(ctx)
	r.RemoteAddr = "nAlice"
	r.Header.Set("Content-Type", "application/json")
	started := make(chan struct{})
	authenticate := s.authenticate
	s.authenticate = func(ctx context.Context, remote string) (string, error) {
		id, err := authenticate(ctx, remote)
		close(started)
		return id, err
	}
	p := s.getPeer("nAlice")
	p.appMu.Lock()
	var unlock sync.Once
	w := httptest.NewRecorder()
	done := make(chan struct{})
	t.Cleanup(func() {
		cancel()
		unlock.Do(p.appMu.Unlock)
		checksync.Join(t, done, time.Second, "cancelled wake handler")
	})
	go func() { s.ServeHTTP(w, r); close(done) }()
	checksync.Receive(t, started, time.Second, "wake authentication")
	cancel()
	unlock.Do(p.appMu.Unlock)
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("Cancelled app request did not finish")
	}
	var reply struct {
		Data appFailure `json:"data"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &reply); err != nil {
		t.Fatal(err)
	}
	if attempts.Load() != 0 || w.Code != 503 || reply.Data.Code != "wake_unconfirmed" || reply.Data.Retryable {
		t.Fatal("Cancelled request started wake or authorized automatic retry", w.Body.String(), attempts.Load())
	}
}

func TestWakeSenderContextStopsWithRequestOrService(t *testing.T) {
	for _, serviceStop := range []bool{false, true} {
		s, _, call := fixtureAPI(t)
		registerFixture(t, call, "nAlice")
		ctx, cancel := context.WithCancel(context.Background())
		r := httptest.NewRequest("POST", "/v2/connection/wake", nil).WithContext(ctx)
		r.RemoteAddr = "nAlice"
		r.Header.Set("Content-Type", "application/json")
		entered := make(chan struct{})
		s.wake = func(ctx context.Context) error { close(entered); <-ctx.Done(); return ctx.Err() }
		w := httptest.NewRecorder()
		done := make(chan struct{})
		// Cancel and join even if the entry barrier assertion fails.
		retire := func() {
			cancel()
			s.cancel()
			checksync.Join(t, done, time.Second, "wake sender")
		}
		t.Cleanup(retire)
		go func() { s.ServeHTTP(w, r); close(done) }()
		select {
		case <-entered:
		case <-time.After(time.Second):
			cancel()
			s.cancel()
			t.Fatal("Sender never started")
		}
		if serviceStop {
			s.cancel()
		} else {
			cancel()
		}
		finished := false
		select {
		case <-done:
			finished = true
		case <-time.After(time.Second):
		}
		cancel()
		s.cancel()
		if !finished {
			t.Fatal("Wake context ignored cancellation", serviceStop)
		}
		if w.Code != 503 || strings.Contains(w.Body.String(), "packet_sent") {
			t.Fatal("Cancelled sender claimed a packet acknowledgment", w.Body.String())
		}
	}
}

func TestWakeDoesNotAcknowledgeAfterRequestCancellation(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r := httptest.NewRequest("POST", "/v2/connection/wake", nil).WithContext(ctx)
	r.RemoteAddr = "nAlice"
	r.Header.Set("Content-Type", "application/json")
	s.wake = func(context.Context) error { cancel(); return nil }
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	if w.Code != 503 || strings.Contains(w.Body.String(), "packet_sent") || strings.Contains(w.Body.String(), `"retryable":true`) {
		t.Fatal("Cancellation after sender returned produced a success acknowledgment or retry authority", w.Body.String())
	}
}

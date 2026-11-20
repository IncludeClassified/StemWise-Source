package backend

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

type requestContextNative struct {
	*nativeFixture
	auth, ignoreCancellation bool
	entered                  chan context.Context
	release                  chan struct{}
	once                     sync.Once
}

func (n *requestContextNative) hold(ctx context.Context) error {
	n.once.Do(func() { n.entered <- ctx })
	if n.ignoreCancellation {
		<-n.release
		return nil
	}
	select {
	case <-n.release:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
func (n *requestContextNative) Models(ctx context.Context, cursor string) (json.RawMessage, error) {
	if err := n.hold(ctx); err != nil {
		return nil, err
	}
	return n.nativeFixture.Models(ctx, cursor)
}
func (n *requestContextNative) Call(ctx context.Context, r protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if n.auth && r.Operation == "auth" {
		if err := n.hold(ctx); err != nil {
			return protocol.AdapterReply{}, err
		}
	}
	return n.nativeFixture.Call(ctx, r)
}

func TestHTTPObservationsOwnCallerAndServiceLifetimeWithoutSavingCancelledSettings(t *testing.T) {
	for _, which := range []string{"models", "settings", "connection_auth", "late_catalog_reply"} {
		for _, owner := range []string{"caller", "service"} {
			t.Run(which+"/"+owner, func(t *testing.T) {
				s, base, call := fixtureAPI(t)
				base.online = true
				registerFixture(t, call, "nAlice")
				n := &requestContextNative{nativeFixture: base, auth: which == "connection_auth", ignoreCancellation: which == "late_catalog_reply", entered: make(chan context.Context, 1), release: make(chan struct{})}
				s.native = n
				method, path, body := "GET", "/v2/codex/models", []byte{}
				if which == "settings" || which == "late_catalog_reply" {
					method, path, body = "PUT", "/v2/codex/settings", []byte(`{"model":"fixture-model","effort":"high"}`)
				}
				if which == "connection_auth" {
					path = "/v2/connection"
				}
				ctx, cancel := context.WithCancel(context.Background())
				r := httptest.NewRequest(method, path, bytes.NewReader(body)).WithContext(ctx)
				r.RemoteAddr = "nAlice"
				r.Header.Set("Content-Type", "application/json")
				w := httptest.NewRecorder()
				done := make(chan struct{})
				var once sync.Once
				unblock := func() { once.Do(func() { close(n.release) }) }
				t.Cleanup(func() { cancel(); unblock(); checksync.Join(t, done, 3*time.Second, "HTTP observation retirement") })
				go func() { defer close(done); s.ServeHTTP(w, r) }()
				nativeCtx := checksync.Receive(t, n.entered, 3*time.Second, "actual native observation")
				if owner == "service" {
					s.cancel()
				} else {
					cancel()
				}
				checksync.Receive(t, nativeCtx.Done(), 3*time.Second, "cancelled native observation context")
				if n.ignoreCancellation {
					unblock() // A valid late catalog response still cannot authorize a settings save.
				}
				if !checksync.Join(t, done, 3*time.Second, "cancelled observer HTTP handler") {
					return
				}
				if which != "connection_auth" && w.Code != 503 {
					t.Fatal("Cancelled native catalog was returned as success", w.Code, w.Body.String())
				}
				state, err := s.loadApp(s.getPeer("nAlice"))
				if err != nil || state.Model != "" || state.Effort != "" || state.ServiceTier != "" {
					t.Fatal("Cancelled precommit validation changed saved settings", err)
				}
			})
		}
	}
}

type lostAdmissionResponse struct {
	*httptest.ResponseRecorder
	cancel context.CancelFunc
}

type cancelSettingsBody struct {
	*bytes.Reader
	cancel context.CancelFunc
}

func (b *cancelSettingsBody) Read(p []byte) (int, error) {
	n, err := b.Reader.Read(p)
	if b.Len() == 0 {
		b.cancel()
		return n, io.EOF
	}
	return n, err
}

func TestCancelledEmptySettingsCannotClearSavedChoices(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	if w := call("PUT", "/v2/codex/settings", map[string]string{"model": "fixture-model", "effort": "high"}, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r := httptest.NewRequest("PUT", "/v2/codex/settings", &cancelSettingsBody{Reader: bytes.NewReader([]byte(`{}`)), cancel: cancel}).WithContext(ctx)
	r.RemoteAddr = "nAlice"
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	state, err := s.loadApp(s.getPeer("nAlice"))
	if ctx.Err() != context.Canceled || w.Code != 503 || err != nil || state.Model != "fixture-model" || state.Effort != "high" {
		t.Fatal("Cancelling an empty settings body bypassed the precommit fence", w.Code, err)
	}
}

func (w *lostAdmissionResponse) Write([]byte) (int, error) {
	w.cancel()
	return 0, io.ErrClosedPipe
}

func TestCancelledAdmissionResponsePreservesAcceptedJobAndOriginalExecution(t *testing.T) {
	s, n, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Accepted lost response", Text: "exact accepted request", Files: []protocol.File{}}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r := httptest.NewRequest("POST", "/v2/jobs", bytes.NewReader(mustJSON(input))).WithContext(ctx)
	r.RemoteAddr = "nAlice"
	r.Header.Set("Content-Type", "application/json")
	w := &lostAdmissionResponse{ResponseRecorder: httptest.NewRecorder(), cancel: cancel}
	s.ServeHTTP(w, r)
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil || ctx.Err() != context.Canceled || w.Code != 202 || state.Jobs[input.RequestID] == nil || state.Jobs[input.RequestID].State != "queued" {
		t.Fatal("Lost caller response discarded accepted background work", err, w.Code)
	}
	n.online = true
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	if n.calls != 1 || n.input.JobID != input.RequestID || n.input.Text != input.Text {
		t.Fatal("Caller cancellation stopped or replayed accepted execution", n.calls)
	}
	if response := call("POST", "/v2/jobs", input, "nAlice"); response.Code != 200 || n.calls != 1 {
		t.Fatal("Original accepted identity could not reconcile idempotently", response.Code)
	}
}

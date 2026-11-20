package pc

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

func heldAuthHTTPRequest(t *testing.T, input protocol.AdapterRequest) (authFixture, context.CancelFunc, func(), <-chan struct{}, *httptest.ResponseRecorder) {
	t.Helper()
	f := newAuthFixture(t, "normal")
	hold := filepath.Join(f.home, "hold-config")
	if err := os.WriteFile(hold, []byte("owned request-context fixture"), 0600); err != nil {
		t.Fatal(err)
	}
	var once sync.Once
	release := func() { once.Do(func() { _ = os.Remove(hold) }) }
	input.Nonce = randomToken()
	body, _ := json.Marshal(input)
	ctx, cancel := context.WithCancel(context.Background())
	r := httptest.NewRequest("POST", "/internal/v1/codex", bytes.NewReader(body)).WithContext(ctx)
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	done := make(chan struct{})
	t.Cleanup(func() {
		cancel()
		release()
		f.service.cancel()
		checksync.Join(t, done, 3*time.Second, "actual adapter HTTP request")
	})
	go func() { defer close(done); f.service.handle(w, r) }()
	deadline := time.NewTimer(3 * time.Second)
	defer deadline.Stop()
	tick := time.NewTicker(5 * time.Millisecond)
	defer tick.Stop()
	for {
		if _, err := os.Stat(filepath.Join(f.home, "config-waiting")); err == nil {
			return f, cancel, release, done, w
		}
		select {
		case <-done:
			t.Fatal("Actual native auth read did not remain held", w.Code, w.Body.String())
		case <-deadline.C:
			t.Fatal("Actual native auth read did not reach its fixture checkpoint")
		case <-tick.C:
		}
	}
}

func TestCancelledAuthObservationReleasesNativeLeaseWithoutKillingRuntime(t *testing.T) {
	f, cancel, release, done, w := heldAuthHTTPRequest(t, protocol.AdapterRequest{Operation: "auth"})
	cancel()
	if !checksync.Join(t, done, 3*time.Second, "cancelled auth observation") {
		return
	}
	ctx, stop := context.WithTimeout(context.Background(), time.Second)
	defer stop()
	unlock, err := f.service.claimNative(ctx)
	if err != nil {
		t.Fatal("Cancelled read retained the serialized native lease", err)
	}
	unlock()
	p := f.service.peers["nPi"]
	p.connMu.Lock()
	native := p.conn
	p.connMu.Unlock()
	select {
	case <-native.Done():
		t.Fatal("Cancelling observation killed the owned native runtime")
	default:
	}
	var outcome protocol.AdapterReply
	var status AuthStatus
	if w.Code != 200 || protocol.DecodeStrict(w.Body.Bytes(), &outcome) != nil || outcome.State != "ok" || protocol.DecodeStrict(outcome.Result, &status) != nil || status.State != "unavailable" || (status.Code != "native_auth_unavailable" && status.Code != "native_auth_source_unverified") {
		t.Fatal("Cancelled auth observation became a ready account observation", w.Code, w.Body.String())
	}
	release()
	if fresh := f.call(protocol.AdapterRequest{Operation: "auth"}); fresh.State != "ok" || !bytes.Contains(fresh.Result, []byte("native_auth_ready")) {
		t.Fatal("Original native runtime could not recover after its late read reply", fresh)
	}
	if pids, starts := f.counts(t); len(pids) != 1 || starts != 0 {
		t.Fatal("Observation cancellation replaced the runtime or created a chat", pids, starts)
	}
}

func TestCancelledCreationResponseStillCommitsOriginalNativeReceipt(t *testing.T) {
	input := protocol.AdapterRequest{Operation: "create", AppID: randomToken(), RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Name: "Accepted original creation", Model: "fixture"}
	f, cancel, release, done, w := heldAuthHTTPRequest(t, input)
	cancel()
	release()
	if !checksync.Join(t, done, 3*time.Second, "accepted native creation after response loss") {
		return
	}
	var outcome protocol.AdapterReply
	if w.Code != 200 || protocol.DecodeStrict(w.Body.Bytes(), &outcome) != nil || outcome.State != "ok" {
		t.Fatal("Caller cancellation abandoned accepted creation receipt work", w.Code, w.Body.String())
	}
	input.Operation = "creation"
	if observed := f.call(input); observed.State != "ok" || !bytes.Equal(observed.Result, outcome.Result) {
		t.Fatal("Original receipt did not reconcile after the response was lost", observed)
	}
	if pids, starts := f.counts(t); len(pids) != 1 || starts != 1 {
		t.Fatal("Response loss duplicated or replaced accepted native creation", pids, starts)
	}
}

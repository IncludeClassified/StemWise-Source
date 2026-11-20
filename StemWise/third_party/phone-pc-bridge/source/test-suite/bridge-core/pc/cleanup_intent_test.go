package pc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
	"tailscale.com/tsnet"
)

func TestDiscardPersistsExactCleanupWithoutStartingNativeAndClosesOnlyProvenAbsentCreation(t *testing.T) {
	data, integration := t.TempDir(), t.TempDir()
	cfg := Config{Identity: "nPc", Host: "pc.tailtest.ts.net", CodexExecutable: filepath.Join(integration, "absent-native.exe"), DataDir: data, IntegrationDir: integration, AllowedPeers: []string{"nPi"}, BackendPeer: "nPi"}
	s, err := New(&tsnet.Server{}, cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	input := protocol.AdapterRequest{Operation: "discard", AppID: randomToken(), RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Name: "Never started job", Model: "fixture", ServiceTier: "opaque/Ω"}
	call := func(input protocol.AdapterRequest) protocol.AdapterReply {
		t.Helper()
		s.authenticate = func(context.Context, string) (string, error) { return "nPi", nil }
		input.Nonce = randomToken()
		raw, _ := json.Marshal(input)
		r := httptest.NewRequest("POST", "/internal/v1/codex", bytes.NewReader(raw))
		r.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		s.handle(w, r)
		var reply protocol.AdapterReply
		if w.Code != 200 || protocol.DecodeStrict(w.Body.Bytes(), &reply) != nil || reply.Nonce != input.Nonce {
			t.Fatal("Invalid adapter receipt", w.Code, w.Body.String())
		}
		return reply
	}
	assertAccepted := func(reply protocol.AdapterReply, deleted bool) {
		t.Helper()
		var receipt struct {
			AppID     string `json:"appId"`
			RequestID string `json:"requestId"`
			Accepted  bool   `json:"accepted"`
			Deleted   *bool  `json:"deleted,omitempty"`
		}
		if reply.State != "ok" || protocol.DecodeStrict(reply.Result, &receipt) != nil || receipt.AppID != input.AppID || receipt.RequestID != input.RequestID || !receipt.Accepted || (!deleted && receipt.Deleted != nil) || (deleted && (receipt.Deleted == nil || !*receipt.Deleted)) {
			t.Fatal("Discard did not durably accept the exact owner independently of native availability", reply)
		}
	}
	assertAccepted(call(input), false)
	if _, err := s.readCleanupIntent("nPi", input.AppID, input.RequestID); err != nil {
		t.Fatal("Accepted discard has no journal", err)
	}
	s.Close()
	s, err = New(&tsnet.Server{}, cfg)
	if err != nil {
		t.Fatal(err)
	}
	assertAccepted(call(input), false)
	wrong := input
	wrong.ServiceTier = "changed"
	if got := call(wrong); got.State == "ok" {
		t.Fatal("Changed immutable owner reused cleanup authorization")
	}
	connect := func(context.Context, string) (deletionNative, error) {
		t.Fatal("Proven never-started cleanup attempted native connection")
		return nil, nil
	}
	if _, err = s.pruneOwnedChats(time.Now(), connect); err != nil {
		t.Fatal(err)
	}
	request := creationInput{input.AppID, input.RequestID, input.CreatedAt, input.Name, input.Model, integration, input.ServiceTier}
	closed, err := s.readDeletedCreation("nPi", request)
	if err != nil || closed.ThreadID != "" || closed.Request == nil {
		t.Fatal("No-start retirement lacked exact durable closure", err, closed)
	}
	assertAccepted(call(input), true)
	native := &creationNativeFixture{threads: map[string]string{}}
	input.Operation = "create"
	if _, err = s.createThread(context.Background(), "nPi", input, native); err == nil || native.starts != 0 {
		t.Fatal("Discarded identity restarted native work", err, native.starts)
	}
}

func TestDiscardReceiptObservesActualOwnedNativeDeletionWithoutKnownCallerThread(t *testing.T) {
	s, _, input, _ := creationTestService(t)
	s.cfg.Identity, s.cfg.BackendPeer = "nPc", "nPi"
	s.cfg.AllowedPeers = []string{"nPi"}
	s.peers = map[string]*peer{"nPi": {identity: "nPi"}}
	s.authenticate = func(context.Context, string) (string, error) { return "nPi", nil }
	native, ctx := creationPeer(t, "app")
	if _, err := s.createThread(ctx, "nPi", input, native); err != nil {
		t.Fatal("Actual native fixture creation failed", err)
	}
	input.Operation = "discard"
	input.ThreadID = "" // The caller cancelled while its create acknowledgment was uncertain.
	call := func(request protocol.AdapterRequest) protocol.AdapterReply {
		t.Helper()
		request.Nonce = randomToken()
		raw, _ := json.Marshal(request)
		r := httptest.NewRequest("POST", "/internal/v1/codex", bytes.NewReader(raw))
		r.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		s.handle(w, r)
		var result protocol.AdapterReply
		if w.Code != 200 || protocol.DecodeStrict(w.Body.Bytes(), &result) != nil || result.Nonce != request.Nonce {
			t.Fatal("Uncorrelated discard receipt", w.Code, w.Body.String())
		}
		return result
	}
	assertReceipt := func(result protocol.AdapterReply, closed bool) {
		t.Helper()
		var receipt struct {
			AppID     string `json:"appId"`
			RequestID string `json:"requestId"`
			Accepted  bool   `json:"accepted"`
			Deleted   *bool  `json:"deleted,omitempty"`
		}
		if result.State != "ok" || protocol.DecodeStrict(result.Result, &receipt) != nil || receipt.AppID != input.AppID || receipt.RequestID != input.RequestID || !receipt.Accepted || (closed && (receipt.Deleted == nil || !*receipt.Deleted)) || (!closed && receipt.Deleted != nil) {
			t.Fatal("Discard confused intent and exact native closure", closed, result, string(result.Result))
		}
	}
	assertReceipt(call(input), false)
	if _, err := native.Read(ctx, "thread-1"); err != nil {
		t.Fatal("Cleanup admission deleted the actual native chat", err)
	}
	wrong := input
	wrong.ServiceTier = "changed"
	if result := call(wrong); result.State == "ok" || result.Result != nil {
		t.Fatal("Mismatched owner received closure proof", result)
	}
	if _, err := s.pruneOwnedChats(time.Now(), func(context.Context, string) (deletionNative, error) { return native, nil }); err != nil {
		t.Fatal("Exact native deletion fixture failed", err)
	}
	if _, err := native.Read(ctx, "thread-1"); err == nil {
		t.Fatal("Closed receipt preceded actual native deletion")
	}
	assertReceipt(call(input), true)
	assertReceipt(call(input), true)
	wrong = input
	wrong.Name = "different immutable name"
	if result := call(wrong); result.State == "ok" || result.Result != nil {
		t.Fatal("Changed envelope reused physical-deletion proof", result)
	}
}

func TestDiscardPreservesUnknownReservedCreationAcrossAgeAndRestart(t *testing.T) {
	s, n, input, path := creationTestService(t)
	s.cfg.AllowedPeers = []string{"nPi"}
	n.failStart = true
	if _, err := s.createThread(context.Background(), "nPi", input, n); err == nil || n.starts != 1 {
		t.Fatal("Fixture did not lose actual creation acknowledgment", err)
	}
	if err := s.authorizeCleanup(context.Background(), "nPi", input, false); err != nil {
		t.Fatal(err)
	}
	s.creations.Close()
	var err error
	s.creations, err = files.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	connect := func(context.Context, string) (deletionNative, error) {
		t.Fatal("Unknown reservation guessed a native chat")
		return nil, nil
	}
	if _, err = s.pruneOwnedChats(time.Now().Add(100*24*time.Hour), connect); err == nil {
		t.Fatal("Unknown reserved cleanup claimed physical deletion")
	}
	if got := s.cleanupSnapshot(); got.State != "unconfirmed" || got.PendingRequests != 1 {
		t.Fatal("Unknown cleanup was not pending", got)
	}
	for _, kind := range []string{"request", "cleanup"} {
		if _, _, err = s.creations.Read("nPi", creationID("nPi", input.AppID, input.RequestID, kind)); err != nil {
			t.Fatal("Unresolved owner was age-pruned", kind, err)
		}
	}
	if _, _, err = s.creations.Read("nPi", creationID("nPi", input.AppID, input.RequestID, "deleted")); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Unknown native start was marked absent", err)
	}
	if _, err = s.createThread(context.Background(), "nPi", input, n); err == nil || n.starts != 1 {
		t.Fatal("Discarded uncertain creation was replayed", err, n.starts)
	}
}

func TestCleanupJournalFailurePreventsNativeDeletionAndPreservesStorageCause(t *testing.T) {
	s, base, input, path := creationTestService(t)
	if _, err := s.createThread(context.Background(), "nPi", input, base); err != nil {
		t.Fatal(err)
	}
	for id := range base.threads {
		input.ThreadID = id
	}
	s.creations.Close()
	var err error
	s.creations, err = files.OpenWithBudget(path, files.NewBudget(^uint64(0)))
	if err != nil {
		t.Fatal(err)
	}
	native := &deletingNative{creationNativeFixture: base}
	err = s.deleteCreation(context.Background(), "nPi", input, native)
	var failure *creationError
	if !errors.As(err, &failure) || failure.code != "creation_store_unavailable" || !failure.unknown || failure.storagePhase != "put_cleanup_marker" || !errors.Is(err, files.ErrStorageCapacity) || native.calls != 0 {
		t.Fatal("Failed journal publication reached native deletion or lost cause", err, errors.Unwrap(err), native.calls)
	}
}

func TestClosedCleanupCheckpointSurvivesPartialPurgeAndRepeatedDelete(t *testing.T) {
	s, base, input, path := creationTestService(t)
	s.cfg.AllowedPeers = []string{"nPi"}
	if _, err := s.createThread(context.Background(), "nPi", input, base); err != nil {
		t.Fatal(err)
	}
	for id := range base.threads {
		input.ThreadID = id
	}
	native := &deletingNative{creationNativeFixture: base}
	if err := s.deleteCreation(context.Background(), "nPi", input, native); err != nil {
		t.Fatal(err)
	}
	for _, kind := range []string{"result", "request", "cleanup"} {
		if err := s.creations.Remove("nPi", creationID("nPi", input.AppID, input.RequestID, kind)); err != nil {
			t.Fatal(err)
		}
	}
	s.creations.Close()
	var err error
	s.creations, err = files.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if err = s.deleteCreation(context.Background(), "nPi", input, native); err != nil || native.calls != 1 {
		t.Fatal("Partial purge lost actual deletion checkpoint or replayed native delete", err, native.calls)
	}
	closedAt, err := s.creations.CommittedAt("nPi", creationID("nPi", input.AppID, input.RequestID, "deleted"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.pruneOwnedChats(closedAt.Add(protocol.Retention), func(context.Context, string) (deletionNative, error) {
		t.Fatal("Closed checkpoint reconnected native")
		return nil, nil
	}); err != nil {
		t.Fatal(err)
	}
	page, err := s.creations.List("nPi", "", 100)
	if err != nil || len(page.Files) != 0 {
		t.Fatal("Partial purge did not finish from exact checkpoint", err, len(page.Files))
	}
}

func TestDiscardSerializesWithActualNativeCreationAndFencesLaterReplay(t *testing.T) {
	s, base, input, _ := creationTestService(t)
	native := &heldCreationBoundary{creationNativeFixture: base, stage: "start", entered: make(chan struct{}), release: make(chan struct{})}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	created, discarded := make(chan error, 1), make(chan error, 1)
	var joined sync.WaitGroup
	var release sync.Once
	t.Cleanup(func() {
		release.Do(func() { close(native.release) })
		cancel()
		done := make(chan struct{})
		go func() { joined.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(4 * time.Second):
			t.Error("Creation/discard fixture did not retire")
		}
	})
	joined.Add(1)
	go func() { defer joined.Done(); _, err := s.createThread(ctx, "nPi", input, native); created <- err }()
	select {
	case <-native.entered:
	case <-ctx.Done():
		t.Fatal("Native creation did not start")
	}
	joined.Add(1)
	go func() { defer joined.Done(); discarded <- s.authorizeCleanup(ctx, "nPi", input, false) }()
	waitCreationUsers(t, s, "nPi", input, 2)
	if _, _, err := s.creations.Read("nPi", creationID("nPi", input.AppID, input.RequestID, "cleanup")); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Discard bypassed in-flight exact owner", err)
	}
	release.Do(func() { close(native.release) })
	for _, result := range []<-chan error{created, discarded} {
		select {
		case err := <-result:
			if err != nil {
				t.Fatal(err)
			}
		case <-ctx.Done():
			t.Fatal("Exact creation/discard did not finish")
		}
	}
	if _, err := s.createThread(ctx, "nPi", input, base); err == nil || base.starts != 1 {
		t.Fatal("Completed discard failed to fence later creation", err, base.starts)
	}
	if _, _, err := s.creations.Read("nPi", creationID("nPi", input.AppID, input.RequestID, "result")); err != nil {
		t.Fatal("Discard race lost actual native identity", err)
	}
}

func TestUnmatchedOrMalformedClosureRemainsPreservedAndUnconfirmed(t *testing.T) {
	for _, payload := range []string{`{}`, `{"requestHash":"` + strings.Repeat("a", 64) + `","threadId":"unknown-owner-thread"}`} {
		t.Run(payload, func(t *testing.T) {
			s, n, input, path := creationTestService(t)
			s.cfg.AllowedPeers = []string{"nPi"}
			id := randomToken()
			f, _ := files.Describe(id, "creation-deleted.json", []byte(payload))
			if _, err := s.creations.Put("nPi", f, strings.NewReader(payload)); err != nil {
				t.Fatal(err)
			}
			connect := func(context.Context, string) (deletionNative, error) {
				t.Fatal("Unmatched closure guessed a native owner")
				return nil, nil
			}
			if _, err := s.pruneOwnedChats(time.Now().Add(100*24*time.Hour), connect); err == nil {
				t.Fatal("Unmatched/malformed closure was reported healthy")
			}
			if status := s.cleanupSnapshot(); status.State != "unconfirmed" || status.Code != "chat_ownership_unconfirmed" || status.PendingRequests != 1 {
				t.Fatal("Unresolved closure hidden", status)
			}
			s.creations.Close()
			var err error
			s.creations, err = files.Open(path)
			if err != nil {
				t.Fatal(err)
			}
			actual, raw, err := s.creations.Read("nPi", id)
			if err != nil || actual != f || string(raw) != payload {
				t.Fatal("Unknown closure changed or expired", err)
			}
			if _, err = s.createThread(context.Background(), "nPi", input, n); err != nil || n.starts != 1 {
				t.Fatal("Unknown closure blocked unrelated exact creation", err, n.starts)
			}
		})
	}
}

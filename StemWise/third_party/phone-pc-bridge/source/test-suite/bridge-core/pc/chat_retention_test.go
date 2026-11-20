package pc

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
	"testing"
	"testing/synctest"
	"time"
)

type deletingNative struct {
	*creationNativeFixture
	calls   int
	offline bool
	last    string
}

func TestCorruptCreationRequestCannotDiscardItsRecoveryReceiptOrBlockHealthyCleanup(t *testing.T) {
	s, n, input, path := creationTestService(t)
	s.cfg.AllowedPeers, s.ctx = []string{"nPi"}, context.Background()
	native := &deletingNative{creationNativeFixture: n}
	if _, err := s.createThread(context.Background(), "nPi", input, n); err != nil {
		t.Fatal(err)
	}
	healthy := input
	healthy.RequestID = randomToken()
	if _, err := s.createThread(context.Background(), "nPi", healthy, n); err != nil {
		t.Fatal(err)
	}
	if err := s.authorizeCleanup(context.Background(), "nPi", healthy, false); err != nil {
		t.Fatal(err)
	}
	damagedPath := filepath.Join(path, "nPi", creationID("nPi", input.AppID, input.RequestID, "request"), "content")
	if err := os.WriteFile(damagedPath, []byte("damaged request ownership"), 0600); err != nil {
		t.Fatal(err)
	}
	connect := func(context.Context, string) (deletionNative, error) { return native, nil }
	if _, err := s.pruneOwnedChats(time.Now(), connect); err == nil {
		t.Fatal("Damaged ownership was reported healthy")
	}
	if native.calls != 1 {
		t.Fatal("Damaged request blocked healthy cleanup or authorized native deletion", native.calls)
	}
	if _, _, err := s.creations.Read("nPi", creationID("nPi", input.AppID, input.RequestID, "result")); err != nil {
		t.Fatal("Skipped damaged request caused its healthy recovery receipt to be discarded", err)
	}
	if raw, err := os.ReadFile(damagedPath); err != nil || string(raw) != "damaged request ownership" {
		t.Fatal("Damaged request evidence was changed", err)
	}
	if got := s.cleanupSnapshot(); got.State != "unconfirmed" {
		t.Fatal("Damaged creation ownership was hidden", got)
	}
}

func (n *deletingNative) DeleteThread(_ context.Context, id string) error {
	n.calls++
	n.last = id
	if n.offline {
		return errors.New("native unavailable")
	}
	delete(n.threads, id)
	return nil
}
func TestOwnedDeletionRequiresOriginalReceiptAndNeverDeletesExistingChat(t *testing.T) {
	s, base, input, _ := creationTestService(t)
	n := &deletingNative{creationNativeFixture: base}
	if _, err := s.createThread(context.Background(), "nPi", input, base); err != nil {
		t.Fatal(err)
	}
	for id := range n.threads {
		input.ThreadID = id
	}
	input.Operation = "delete"
	n.threads["existing-user-chat"] = s.cfg.IntegrationDir
	for _, kind := range []string{"app", "request", "thread", "timestamp", "model", "name"} {
		wrong := input
		switch kind {
		case "app":
			wrong.AppID = randomToken()
		case "request":
			wrong.RequestID = randomToken()
		case "thread":
			wrong.ThreadID = "existing-user-chat"
		case "timestamp":
			wrong.CreatedAt--
		case "model":
			wrong.Model = "changed"
		case "name":
			wrong.Name = "changed"
		}
		if err := s.deleteCreation(context.Background(), "nPi", wrong, n); err == nil || n.calls != 0 {
			t.Fatal("unowned deletion reached native", kind)
		}
	}
	n.offline = true
	if err := s.deleteCreation(context.Background(), "nPi", input, n); err == nil {
		t.Fatal("unconfirmed deletion reported success")
	}
	n.offline = false
	if err := s.deleteCreation(context.Background(), "nPi", input, n); err != nil {
		t.Fatal(err)
	}
	if err := s.deleteCreation(context.Background(), "nPi", input, n); err != nil || n.calls != 2 {
		t.Fatal("confirmed deletion replayed", err)
	}
	if _, exists := n.threads[input.ThreadID]; exists {
		t.Fatal("owned native chat remains")
	}
	if _, exists := n.threads["existing-user-chat"]; !exists {
		t.Fatal("existing native chat deleted")
	}
	input.Operation = "creation"
	input.ThreadID = ""
	if _, err := s.createThread(context.Background(), "nPi", input, base); err == nil || n.starts != 1 {
		t.Fatal("delivered work recreated")
	}
}
func TestReceiverExpiryKeepsCompactOwnershipOfflineAndStartsClosureLifetimeAfterDeletion(t *testing.T) {
	s, base, input, path := creationTestService(t)
	n := &deletingNative{creationNativeFixture: base}
	s.cfg.AllowedPeers = []string{"nPi"}
	if _, err := s.createThread(context.Background(), "nPi", input, base); err != nil {
		t.Fatal(err)
	}
	open := func(context.Context, string) (deletionNative, error) {
		if n.offline {
			return nil, errors.New("PC native offline")
		}
		return n, nil
	}
	old := time.Now().Add(-10 * 24 * time.Hour)
	if err := os.Chtimes(filepath.Join(path, "nPi", creationID("nPi", input.AppID, input.RequestID, "request")), old, old); err != nil {
		t.Fatal(err)
	}
	n.offline = true
	if _, err := s.pruneOwnedChats(time.Now(), open); err == nil {
		t.Fatal("Receiver deadline failed to leave native cleanup pending offline")
	}
	if status := s.cleanupSnapshot(); status.State != "unconfirmed" || status.PendingRequests != 1 {
		t.Fatal("Offline cleanup hidden", status)
	}
	page, err := s.creations.List("nPi", "", 100)
	if err != nil || len(page.Files) != 3 {
		t.Fatal("Ownership/journal lost offline", err, len(page.Files))
	}
	s.creations.Close()
	s.creations, err = files.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	n.offline = false
	if _, err = s.pruneOwnedChats(time.Now(), open); err != nil {
		t.Fatal(err)
	}
	page, err = s.creations.List("nPi", "", 100)
	if err != nil || len(page.Files) != 4 || len(n.threads) != 0 || n.calls != 1 {
		t.Fatal("Recovered cleanup did not confirm native deletion and retain bounded closure", err, n.calls, len(page.Files))
	}
	closedAt, err := s.creations.CommittedAt("nPi", creationID("nPi", input.AppID, input.RequestID, "deleted"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.pruneOwnedChats(closedAt.Add(protocol.Retention-time.Millisecond), open); err != nil || n.calls != 1 {
		t.Fatal("Closed cleanup replayed native delete", err, n.calls)
	}
	if _, err = s.pruneOwnedChats(closedAt.Add(protocol.Retention), open); err != nil {
		t.Fatal(err)
	}
	page, err = s.creations.List("nPi", "", 100)
	if err != nil || len(page.Files) != 0 {
		t.Fatal("Confirmed closure did not expire after its own lifetime", err, len(page.Files))
	}
	if status := s.cleanupSnapshot(); status.State != "current" || status.PendingRequests != 0 {
		t.Fatal("Recovered cleanup remains failed", status)
	}
}

func TestCleanupWorkerExpiresUndeliveredCreationAtReceiverDeadline(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, n, input, path := creationTestService(t)
		ctx, cancel := context.WithCancel(context.Background())
		s.ctx = ctx
		s.cfg.AllowedPeers = []string{"nPi"}
		s.peers = map[string]*peer{"nPi": {identity: "nPi"}}
		s.retentionChanged = make(chan struct{}, 1)
		var err error
		s.inbox, err = files.Open(path + "-incoming")
		if err != nil {
			t.Fatal(err)
		}
		defer s.inbox.Close()
		s.outbox, err = files.Open(path + "-outgoing")
		if err != nil {
			t.Fatal(err)
		}
		defer s.outbox.Close()
		input.CreatedAt = time.Now().Add(-5 * 24 * time.Hour).UnixMilli()
		if _, err = s.createThread(ctx, "nPi", input, n); err != nil {
			t.Fatal(err)
		}
		var thread string
		for id := range n.threads {
			thread = id
		}
		release, err := s.beginJobHandoff(ctx, "nPi", Request{Epoch: randomToken(), Operation: "text", Sequence: 1, ThreadID: thread, AppID: input.AppID, JobID: input.RequestID, Text: "Current indexed job"})
		if err != nil {
			t.Fatal(err)
		}
		release()
		accepted := time.Now()
		if err := os.Chtimes(filepath.Join(path, "nPi", creationID("nPi", input.AppID, input.RequestID, "request")), accepted, accepted); err != nil {
			t.Fatal(err)
		}
		done := make(chan struct{})
		go func() { defer close(done); s.pruneFiles() }()
		defer func() { cancel(); checksync.Join(t, done, time.Second, "chat-retention worker") }()
		synctest.Wait()
		initial := s.cleanupSnapshot()
		if initial.State != "current" || initial.PendingRequests != 0 {
			t.Fatal("Indexed undelivered job was not established", initial)
		}
		time.Sleep(protocol.Retention - time.Second)
		synctest.Wait()
		if status := s.cleanupSnapshot(); status.State != "current" || status.PendingRequests != 0 {
			t.Fatal("Cleanup occurred before receiver deadline", status)
		}
		time.Sleep(time.Second)
		synctest.Wait()
		status := s.cleanupSnapshot()
		// No executable is configured: expiry commits cleanup intent and keeps native deletion pending.
		if status.State != "unconfirmed" || status.PendingRequests != 1 || status.ObservedAt <= initial.ObservedAt {
			t.Fatal("Receiver deadline did not automatically request native cleanup", status)
		}
		if _, err := s.readCleanupIntent("nPi", input.AppID, input.RequestID); err != nil {
			t.Fatal("Automatic expiry lacked its durable exact cleanup obligation", err)
		}

	})
}

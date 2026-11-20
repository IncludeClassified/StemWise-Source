package pc

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"phonepcbridge/codex"
	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func handoffCreationService(t *testing.T) (*Service, *deletingNative, protocol.AdapterRequest, string) {
	t.Helper()
	s, native, input, path := creationTestService(t)
	s.cfg.DataDir = filepath.Dir(path)
	s.cfg.Identity, s.cfg.Host = "nPc", "pc.tailtest.ts.net"
	s.cfg.AllowedPeers = []string{"nPi"}
	s.epoch, s.ctx = randomToken(), context.Background()
	for _, name := range []string{"documentation.md", "bridge-core/phone-pc-bridge.aar", "sdk-check/bridge-pc-check.exe"} {
		file := filepath.Join(s.cfg.IntegrationDir, name)
		if err := os.MkdirAll(filepath.Dir(file), 0700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(file, []byte("isolated integration fixture"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	return s, &deletingNative{creationNativeFixture: native}, input, path
}

func createOwnedHandoff(t *testing.T, s *Service, n *deletingNative, input protocol.AdapterRequest, loopIndex int) (protocol.AdapterRequest, handoffFile) {
	t.Helper()
	if _, err := s.createThread(context.Background(), "nPi", input, n); err != nil {
		var failure *creationError
		phase := ""
		if errors.As(err, &failure) {
			phase = failure.storagePhase
		}
		t.Fatalf("Owned creation failed: loop_index=%d native_starts=%d error=%v storage_phase=%s cause=%+v", loopIndex, n.starts, err, phase, errors.Unwrap(err))
	}
	_, raw, err := s.creations.Read("nPi", creationID("nPi", input.AppID, input.RequestID, "result"))
	var receipt creationReceipt
	if err != nil || protocol.DecodeStrict(raw, &receipt) != nil {
		t.Fatal("Creation receipt unavailable", err)
	}
	input.ThreadID = receipt.ThreadID
	file, err := s.createHandoff(context.Background(), "nPi", Request{Epoch: s.epoch, Operation: "text", Sequence: 1, ThreadID: input.ThreadID, AppID: input.AppID, JobID: input.RequestID, Text: "original app job"})
	if err != nil {
		t.Fatal(err)
	}
	return input, file
}

func TestOrphanHandoffCapacityRecoversOnlyAfterExactOwnedDeletionAcrossRestart(t *testing.T) {
	s, n, input, path := handoffCreationService(t)
	owned := make([]protocol.AdapterRequest, 0, 128)
	for i := 0; i < 128; i++ {
		input.RequestID = randomToken()
		created, _ := createOwnedHandoff(t, s, n, input, i)
		owned = append(owned, created)
	}
	if _, err := s.createHandoff(context.Background(), "nPi", Request{Epoch: s.epoch, Operation: "text", Sequence: 1, ThreadID: "not-sent", AppID: input.AppID, JobID: randomToken(), Text: "over capacity"}); err == nil {
		t.Fatal("Unresolved handoff capacity was not enforced")
	}
	s.refreshHandoffStatus(s.scanHandoffStatus)
	status := s.cleanupSnapshot()
	if status.UnresolvedHandoffs != 128 || status.OldestHandoff == nil || status.OldestHandoff.AppID != input.AppID || status.OldestHandoff.Peer != "nPi" || status.OldestHandoff.CreatedAt <= 0 || status.HandoffCode != "" {
		t.Fatal("Bounded unresolved owner diagnostics missing", status)
	}
	n.offline = true
	if err := s.deleteCreation(context.Background(), "nPi", owned[0], n); err == nil || s.cleanupSnapshot().UnresolvedHandoffs != 128 {
		t.Fatal("Unconfirmed deletion discarded possibly active context", err)
	}
	s.creations.Close()
	var err error
	s.creations, err = files.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	s.handoffs = make(map[handoffKey][]handoffFile)
	n.offline = false
	deleteErr := s.deleteCreation(context.Background(), "nPi", owned[0], n)
	s.refreshHandoffStatus(s.scanHandoffStatus)
	if deleteErr != nil || s.cleanupSnapshot().UnresolvedHandoffs != 127 {
		t.Fatal("Exact deletion did not retire its persisted orphan", deleteErr)
	}
	connect := func(context.Context, string) (deletionNative, error) { return n, nil }
	if _, err := s.pruneOwnedChats(time.Now(), connect); err != nil {
		t.Fatal(err)
	}
	if got := s.cleanupSnapshot(); got.UnresolvedHandoffs != 127 || got.OldestHandoff == nil || len(n.threads) != 127 {
		t.Fatal("Exact cleanup removed other current owned chats or attachments", got)
	}
	input.RequestID = randomToken()
	_, file := createOwnedHandoff(t, s, n, input, 128)
	if _, err := os.Stat(file.path); err != nil {
		t.Fatal("Recovered capacity did not accept a new submission", err)
	}
	s.refreshHandoffStatus(s.scanHandoffStatus)
	if got := s.cleanupSnapshot(); got.UnresolvedHandoffs != 128 || len(n.threads) != 128 {
		t.Fatal("Reused capacity lost retained owners", got)
	}
}

func TestOwnedDeletionPreservesForeignOrDamagedHandoffContext(t *testing.T) {
	s, n, input, _ := handoffCreationService(t)
	created, exact := createOwnedHandoff(t, s, n, input, 0)
	s.handoffs = make(map[handoffKey][]handoffFile)
	s.trackHandoff(&codex.Connection{}, Request{ThreadID: created.ThreadID}, json.RawMessage(`{"turn":{"id":"unknown-completion"}}`), exact)
	for _, field := range []string{"peer", "app", "job", "thread"} {
		r := Request{Epoch: s.epoch, Operation: "text", Sequence: 1, ThreadID: created.ThreadID, AppID: input.AppID, JobID: input.RequestID, Text: "unresolved job"}
		peer := "nPi"
		switch field {
		case "peer":
			peer = "nOther"
		case "app":
			r.AppID = randomToken()
		case "job":
			r.JobID = randomToken()
		case "thread":
			r.ThreadID = "foreign-chat"
		}
		if _, err := s.createHandoff(context.Background(), peer, r); err != nil {
			t.Fatal(err)
		}
	}
	damaged := filepath.Join(s.cfg.DataDir, "handoffs", strings.Repeat("f", 64)+".md")
	if err := os.WriteFile(damaged, []byte("damaged owner context"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := s.deleteCreation(context.Background(), "nPi", created, n); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(exact.path); !os.IsNotExist(err) {
		t.Fatal("Exact owned orphan remained")
	}
	if len(s.handoffs) != 0 {
		t.Fatal("Deleted owned chat retained its in-memory handoff context")
	}
	s.refreshHandoffStatus(s.scanHandoffStatus)
	if got := s.cleanupSnapshot(); got.UnresolvedHandoffs != 5 || got.HandoffCode != "handoff_owner_unconfirmed" {
		t.Fatal("Foreign or damaged context was hidden or discarded", got)
	}
	if raw, err := os.ReadFile(damaged); err != nil || string(raw) != "damaged owner context" {
		t.Fatal("Damaged evidence was altered", err)
	}
}

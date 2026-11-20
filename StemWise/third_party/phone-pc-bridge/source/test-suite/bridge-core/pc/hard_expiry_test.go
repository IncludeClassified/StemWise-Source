package pc

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"testing/synctest"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

func TestPCReceiverDeadlinePurgesOwnedBytesOfflineAndResumesExactNativeCleanup(t *testing.T) {
	s, native := jobFilesService(t)
	input, _ := files.Describe(randomToken(), "input.bin", []byte("owned input"))
	if _, err := s.inbox.Put("nPi", input, strings.NewReader("owned input")); err != nil {
		t.Fatal(err)
	}
	owner, request := newFileJob(t, s, native, []protocol.File{input})
	bindFileJob(t, s, request)
	output, _ := files.Describe(randomToken(), "output.bin", []byte("owned output"))
	if _, err := s.QueueJobFileReader(context.Background(), "nPi", request.AppID, request.JobID, request.ThreadID, output, strings.NewReader("owned output")); err != nil {
		t.Fatal(err)
	}
	producer := filepath.Join(s.cfg.IntegrationDir, "sdk-check", "bridge-pc-check.exe")
	if err := os.MkdirAll(filepath.Dir(producer), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(producer, []byte("test producer"), 0600); err != nil {
		t.Fatal(err)
	}
	handoff, err := s.createHandoff(context.Background(), "nPi", request)
	if err != nil {
		t.Fatal(err)
	}
	other, otherRequest := newFileJob(t, s, native, nil)
	bindFileJob(t, s, otherRequest)
	native.threads["manual-user-chat"] = s.cfg.IntegrationDir
	accepted, err := s.creations.CommittedAt("nPi", creationID("nPi", owner.AppID, owner.RequestID, "request"))
	if err != nil {
		t.Fatal(err)
	}
	// Keep a fresh owner's receiver metadata beyond this exact owner's cutoff.
	fresh := accepted.Add(time.Hour)
	if err := os.Chtimes(filepath.Join(s.cfg.DataDir, "creations", "nPi", creationID("nPi", other.AppID, other.RequestID, "request")), fresh, fresh); err != nil {
		t.Fatal(err)
	}
	connects := 0
	offline := func(context.Context, string) (deletionNative, error) {
		connects++
		return nil, errors.New("native offline fixture")
	}
	deadline := accepted.Add(protocol.Retention)
	if _, err := s.pruneOwnedChats(deadline.Add(-time.Nanosecond), offline); err != nil || connects != 0 {
		t.Fatal("PC owner expired before receiver deadline", err, connects)
	}
	if _, err := s.pruneOwnedChats(deadline, offline); err == nil || connects != 1 {
		t.Fatal("Due PC cleanup hid its unavailable native deletion", err, connects)
	}
	for _, object := range []struct {
		store *files.Store
		id    string
	}{{s.inbox, input.ID}, {s.outbox, output.ID}} {
		if _, err := object.store.Info("nPi", object.id); !errors.Is(err, files.ErrAbsent) {
			t.Fatal("Native offline kept expired managed payload", err)
		}
	}
	if _, err := os.Stat(handoff.path); !os.IsNotExist(err) {
		t.Fatal("Expired prompt handoff remained on PC", err)
	}
	if _, err := s.readCleanupIntent("nPi", owner.AppID, owner.RequestID); err != nil {
		t.Fatal("Offline expiry forgot exact cleanup owner", err)
	}
	if _, err := s.QueueJobFileReader(context.Background(), "nPi", owner.AppID, owner.RequestID, owner.ThreadID, output, strings.NewReader("owned output")); err == nil {
		t.Fatal("Expired owner accepted late output publication")
	}
	if native.threads[owner.ThreadID] == "" || native.threads[other.ThreadID] == "" || native.threads["manual-user-chat"] == "" {
		t.Fatal("Offline cleanup pretended native deletion or touched another chat")
	}
	s.creations.Close()
	s.creations, err = files.Open(filepath.Join(s.cfg.DataDir, "creations"))
	if err != nil {
		t.Fatal(err)
	}
	deleting := &deletingNative{creationNativeFixture: native}
	if _, err := s.pruneOwnedChats(deadline, func(context.Context, string) (deletionNative, error) { return deleting, nil }); err != nil {
		t.Fatal(err)
	}
	if deleting.calls != 1 || deleting.last != owner.ThreadID || native.threads[owner.ThreadID] != "" || native.threads[other.ThreadID] == "" || native.threads["manual-user-chat"] == "" {
		t.Fatal("Restart cleanup did not delete only the exact expired native chat", deleting.calls, deleting.last)
	}
	if _, err := s.pruneOwnedChats(deadline, func(context.Context, string) (deletionNative, error) {
		t.Fatal("Closed native deletion was replayed")
		return nil, nil
	}); err != nil {
		t.Fatal(err)
	}
}

type expiryUploadSource struct {
	*io.PipeReader
	entered chan struct{}
	once    sync.Once
}

func (r *expiryUploadSource) Read(p []byte) (int, error) {
	r.once.Do(func() { close(r.entered) })
	return r.PipeReader.Read(p)
}

func TestPCReceiverDeadlineClosesHeldUploadBeforeCleanupCanAdmit(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, native := jobFilesService(t)
		owner, request := newFileJob(t, s, native, nil)
		bindFileJob(t, s, request)
		accepted, err := s.creations.CommittedAt("nPi", creationID("nPi", owner.AppID, owner.RequestID, "request"))
		if err != nil {
			t.Fatal(err)
		}
		reader, writer := io.Pipe()
		source := &expiryUploadSource{PipeReader: reader, entered: make(chan struct{})}
		f, _ := files.Describe(randomToken(), "pending.bin", []byte("actual"))
		done, result := make(chan struct{}), make(chan error, 1)
		t.Cleanup(func() {
			source.Close()
			writer.Close()
			checksync.Join(t, done, time.Second, "deadline upload retirement")
		})
		go func() {
			defer close(done)
			_, err := s.QueueJobFileReader(context.Background(), "nPi", owner.AppID, owner.RequestID, owner.ThreadID, f, source)
			result <- err
		}()
		checksync.Receive(t, source.entered, time.Second, "actual owned output source read")
		time.Sleep(accepted.Add(protocol.Retention).Sub(time.Now()))
		if err := checksync.Receive(t, result, time.Second, "receiver cutoff cancels blocked producer upload"); err == nil {
			t.Fatal("Expired output upload committed")
		}
		if _, err := s.outbox.Info("nPi", f.ID); !errors.Is(err, files.ErrAbsent) {
			t.Fatal("Expired owned upload published bytes", err)
		}
		if _, err := s.pruneOwnedChats(time.Now(), func(context.Context, string) (deletionNative, error) { return nil, errors.New("native unavailable") }); err == nil {
			t.Fatal("Native deletion falsely confirmed")
		}
		if _, err := s.creations.Info("nPi", creationID("nPi", owner.AppID, owner.RequestID, "output:"+f.ID)); !errors.Is(err, files.ErrAbsent) {
			t.Fatal("Deadline retained full output reference", err)
		}
	})
}

func TestPCExpiryUsesExistingReceiverMetadataNotImmutableSenderClock(t *testing.T) {
	for _, skew := range []time.Duration{-365 * 24 * time.Hour, 365 * 24 * time.Hour} {
		t.Run(skew.String(), func(t *testing.T) {
			s, native, input, path := creationTestService(t)
			s.ctx = context.Background()
			s.cfg.AllowedPeers = []string{"nPi"}
			input.CreatedAt = time.Now().Add(skew).UnixMilli()
			if _, err := s.createThread(context.Background(), "nPi", input, native); err != nil {
				t.Fatal(err)
			}
			id := creationID("nPi", input.AppID, input.RequestID, "request")
			accepted := time.Now().Add(-protocol.Retention + time.Hour).Truncate(time.Millisecond)
			if err := os.Chtimes(filepath.Join(path, "nPi", id), accepted, accepted); err != nil {
				t.Fatal(err)
			}
			s.creations.Close()
			var err error
			s.creations, err = files.Open(path)
			if err != nil {
				t.Fatal(err)
			}
			deleting := &deletingNative{creationNativeFixture: native}
			connect := func(context.Context, string) (deletionNative, error) { return deleting, nil }
			if _, err := s.pruneOwnedChats(accepted.Add(protocol.Retention-time.Millisecond), connect); err != nil || deleting.calls != 0 {
				t.Fatal("Sender clock/reopen shortened retained receiver lifetime", err)
			}
			if _, err := s.pruneOwnedChats(accepted.Add(protocol.Retention), connect); err != nil || deleting.calls != 1 || native.starts != 1 {
				t.Fatal("Receiver expiry reset on restart or repeated creation", err, deleting.calls)
			}
		})
	}
}

func TestPCUnknownOwnerBacklogIsBoundedAndClosedFenceDoesNotConsumeCapacity(t *testing.T) {
	s, native, input, _ := creationTestService(t)
	first := input
	for range 128 {
		input.RequestID = randomToken()
		if native.starts == 0 {
			first = input
		}
		if _, err := s.createThread(context.Background(), "nPi", input, native); err != nil {
			t.Fatal("Within-budget owner admission failed", err)
		}
	}
	input.RequestID = randomToken()
	_, err := s.createThread(context.Background(), "nPi", input, native)
	var failure *creationError
	if !errors.As(err, &failure) || failure.code != "creation_capacity_reached" || native.starts != 128 {
		t.Fatal("Unresolved owner backlog admitted unbounded native work", err, native.starts)
	}
	var receipt creationReceipt
	_, raw, err := s.creations.Read("nPi", creationID("nPi", first.AppID, first.RequestID, "result"))
	if err != nil || protocol.DecodeStrict(raw, &receipt) != nil {
		t.Fatal(err)
	}
	first.ThreadID = receipt.ThreadID
	if err := s.deleteCreation(context.Background(), "nPi", first, &deletingNative{creationNativeFixture: native}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.createThread(context.Background(), "nPi", input, native); err != nil || native.starts != 129 {
		t.Fatal("Closed replay fence incorrectly consumed live cleanup capacity", err, native.starts)
	}
}

type deadlineCleanupNative struct{ calls int }

func (n *deadlineCleanupNative) DeleteThread(ctx context.Context, _ string) error {
	n.calls++
	<-ctx.Done()
	return ctx.Err()
}

func TestPCPassPurgesEveryOwnerBeforeMemoizedBoundedNativeCleanup(t *testing.T) {
	for _, mode := range []string{"offline", "hung"} {
		t.Run(mode, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				s, base := jobFilesService(t)
				outputs := []protocol.File{}
				for range 2 {
					owner, request := newFileJob(t, s, base, nil)
					bindFileJob(t, s, request)
					f, _ := files.Describe(randomToken(), "pending.bin", []byte("owned pending bytes"))
					if _, err := s.QueueJobFileReader(context.Background(), "nPi", owner.AppID, owner.RequestID, owner.ThreadID, f, strings.NewReader("owned pending bytes")); err != nil {
						t.Fatal(err)
					}
					outputs = append(outputs, f)
					old := time.Now().Add(-protocol.Retention)
					if err := os.Chtimes(filepath.Join(s.cfg.DataDir, "creations", "nPi", creationID("nPi", owner.AppID, owner.RequestID, "request")), old, old); err != nil {
						t.Fatal(err)
					}
				}
				connects := 0
				native := &deadlineCleanupNative{}
				started := time.Now()
				_, err := s.pruneOwnedChats(time.Now(), func(context.Context, string) (deletionNative, error) {
					connects++
					for _, f := range outputs {
						if _, err := s.outbox.Info("nPi", f.ID); !errors.Is(err, files.ErrAbsent) {
							t.Fatal("A first native wait delayed another owner's local payload purge", err)
						}
					}
					if mode == "offline" {
						return nil, errors.New("isolated unavailable runtime")
					}
					return native, nil
				})
				if err == nil || connects != 1 || s.cleanupSnapshot().PendingRequests != 2 {
					t.Fatal("Cleanup hid pending obligations or repeatedly initialized unavailable native", err, connects)
				}
				if mode == "hung" && (native.calls != 1 || time.Since(started) != 30*time.Second) {
					t.Fatal("Each owner received a fresh unbounded native budget", native.calls, time.Since(started))
				}
			})
		})
	}
}

func TestPCCleanupBudgetRotatesPastHungPeerOnNextPass(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, base := jobFilesService(t)
		healthy, _ := newFileJob(t, s, base, nil)
		hung := protocol.AdapterRequest{Operation: "create", AppID: randomToken(), RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Name: "owned hung peer", Model: "fixture-model"}
		raw, err := s.createThread(context.Background(), "nOther", hung, base)
		if err != nil {
			t.Fatal(err)
		}
		var receipt struct{ Thread struct{ ID string } }
		if json.Unmarshal(raw, &receipt) != nil || receipt.Thread.ID == "" {
			t.Fatal("Missing exact hung-owner native receipt")
		}
		hung.ThreadID = receipt.Thread.ID
		for _, owner := range []struct {
			peer  string
			input protocol.AdapterRequest
		}{{"nPi", healthy}, {"nOther", hung}} {
			old := time.Now().Add(-protocol.Retention)
			if err := os.Chtimes(filepath.Join(s.cfg.DataDir, "creations", owner.peer, creationID(owner.peer, owner.input.AppID, owner.input.RequestID, "request")), old, old); err != nil {
				t.Fatal(err)
			}
		}
		blocked := &deadlineCleanupNative{}
		deleting := &deletingNative{creationNativeFixture: base}
		connect := func(_ context.Context, peer string) (deletionNative, error) {
			if peer == "nOther" {
				return blocked, nil
			}
			return deleting, nil
		}
		if _, err := s.pruneOwnedChats(time.Now(), connect); err == nil || blocked.calls != 1 || deleting.calls != 0 {
			t.Fatal("Hung first peer did not consume the one bounded pass", err, blocked.calls, deleting.calls)
		}
		if _, err := s.pruneOwnedChats(time.Now(), connect); err == nil || deleting.calls != 1 || base.threads[healthy.ThreadID] != "" || base.threads[hung.ThreadID] == "" {
			t.Fatal("Hung peer permanently starved independent exact cleanup", err, blocked.calls, deleting.calls)
		}
	})
}

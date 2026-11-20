package pc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http/httptest"
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

func TestFileHTTPDoesNotWaitForUnrelatedMaintenanceOwnership(t *testing.T) {
	s, native := jobFilesService(t)
	_, owner := newFileJob(t, s, native, nil)
	bindFileJob(t, s, owner)
	s.authenticate = func(context.Context, string) (string, error) { return "nPi", nil }
	incoming, _ := files.Describe(randomToken(), "incoming.bin", []byte("received bytes"))
	if _, err := s.inbox.Put("nPi", incoming, strings.NewReader("received bytes")); err != nil {
		t.Fatal(err)
	}
	outgoing, err := s.QueueFile(context.Background(), "nPi", randomToken(), "outgoing.bin", []byte("queued bytes"))
	if err != nil {
		t.Fatal(err)
	}
	upload, _ := files.Describe(randomToken(), "upload.bin", []byte("new bytes"))
	s.creationMu.Lock()
	var unlock sync.Once
	done, retired := make(chan error, 1), make(chan struct{})
	t.Cleanup(func() {
		unlock.Do(s.creationMu.Unlock)
		checksync.Join(t, retired, 3*time.Second, "routine file HTTP requests")
	})
	go func() {
		defer close(retired)
		for _, request := range []struct{ method, path, body, state string }{
			{"GET", "/v1/files/health?nonce=" + randomToken(), "", "ready"},
			{"GET", "/v1/files/" + incoming.ID + "/receipt?nonce=" + randomToken(), "", "stored"},
			{"GET", "/v1/outbox/" + outgoing.ID + "/info?nonce=" + randomToken(), "", "available"},
			{"GET", "/v1/outbox/" + outgoing.ID + "/content?nonce=" + randomToken(), "", ""},
			{"POST", contextUploadPath(upload), "new bytes", "stored"},
		} {
			w := httptest.NewRecorder()
			s.handle(w, httptest.NewRequest(request.method, request.path, strings.NewReader(request.body)))
			if w.Code != 200 {
				done <- fmt.Errorf("%s: HTTP %d %s", request.path, w.Code, w.Body.String())
				return
			}
			if request.state == "" {
				if w.Body.String() != "queued bytes" {
					done <- errors.New("exact output bytes changed")
					return
				}
			} else {
				var response protocol.FileReply
				if protocol.DecodeStrict(w.Body.Bytes(), &response) != nil || response.State != request.state {
					done <- fmt.Errorf("%s: invalid correlated receipt %s", request.path, w.Body.String())
					return
				}
			}
		}
		done <- nil
	}()
	if err := checksync.Receive(t, done, 2*time.Second, "file operations independent of maintenance ownership"); err != nil {
		t.Fatal(err)
	}
}

func TestProducerClaimsDoNotReadUnrelatedJobHistory(t *testing.T) {
	s, native := jobFilesService(t)
	_, unrelated := newFileJob(t, s, native, nil)
	bindFileJob(t, s, unrelated)
	_, current := newFileJob(t, s, native, nil)
	bindFileJob(t, s, current)
	corrupt := func(request Request) {
		t.Helper()
		path := filepath.Join(s.cfg.DataDir, "creations", "nPi", creationID("nPi", request.AppID, request.JobID, "inputs"), "content")
		data, err := os.ReadFile(path)
		if err != nil || len(data) == 0 {
			t.Fatal(err)
		}
		data[0] ^= 1
		if err := os.WriteFile(path, data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	corrupt(unrelated)
	output, _ := files.Describe(randomToken(), "current.bin", []byte("current output"))
	if actual, err := s.QueueJobFileReader(context.Background(), "nPi", current.AppID, current.JobID, current.ThreadID, output, strings.NewReader("current output")); err != nil || actual != output {
		t.Fatal("Exact current producer operation reread unrelated retained history", err)
	}
	if err := s.DiscardQueuedFile("nPi", output.ID); !errors.Is(err, ErrJobFileReferenced) {
		t.Fatal("Cached committed output lost its exact owner", err)
	}
	corrupt(current)
	next, _ := files.Describe(randomToken(), "refused.bin", []byte("refused"))
	if _, err := s.QueueJobFileReader(context.Background(), "nPi", current.AppID, current.JobID, current.ThreadID, next, strings.NewReader("refused")); !errors.Is(err, files.ErrCorrupt) {
		t.Fatal("Projection bypassed validation of the requested owner's current ledger", err)
	}
	if _, err := s.pruneTransferFiles(time.Now()); err == nil {
		t.Fatal("Explicit maintenance hid corrupt ownership")
	}
	if _, _, err := s.outbox.Read("nPi", output.ID); err != nil {
		t.Fatal("Unconfirmed maintenance deleted possibly owned output", err)
	}
}

func TestFileReferenceRefreshDoesNotBlockOrOverwriteCommittedOutput(t *testing.T) {
	s, native := jobFilesService(t)
	_, current := newFileJob(t, s, native, nil)
	bindFileJob(t, s, current)
	entered, release := make(chan struct{}), make(chan struct{})
	type collectionResult struct {
		fresh bool
		err   error
	}
	result, retired := make(chan collectionResult, 1), make(chan struct{})
	var unblock sync.Once
	t.Cleanup(func() {
		unblock.Do(func() { close(release) })
		checksync.Join(t, retired, 3*time.Second, "retained file-reference collection")
	})
	go func() {
		defer close(retired)
		fresh, err := s.refreshJobFiles("nPi", func(peer string) ([]storedJobFiles, error) {
			refs, err := s.scanJobFiles(peer)
			close(entered)
			<-release
			return refs, err
		})
		result <- collectionResult{fresh, err}
	}()
	checksync.Receive(t, entered, time.Second, "actual file-reference scan")
	output, _ := files.Describe(randomToken(), "new-output.bin", []byte("new output"))
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if got, err := s.QueueJobFileReader(ctx, "nPi", current.AppID, current.JobID, current.ThreadID, output, strings.NewReader("new output")); err != nil || got != output {
		t.Fatal("Stalled history collection blocked a current producer commit", err)
	}
	unblock.Do(func() { close(release) })
	if result := checksync.Receive(t, result, time.Second, "stale reference collection"); result.fresh || result.err != nil {
		t.Fatal("Old collection replaced newer committed ownership or hid a scan error", result.fresh, result.err)
	}
	if err := s.DiscardQueuedFile("nPi", output.ID); !errors.Is(err, ErrJobFileReferenced) {
		t.Fatal("Stale collection erased the newly committed output claim", err)
	}
	ageJobFile(t, s, "outgoing", "nPi", output.ID)
	if _, err := s.pruneTransferFiles(time.Now()); err != nil {
		t.Fatal(err)
	}
	if _, data, err := s.outbox.Read("nPi", output.ID); err != nil || !bytes.Equal(data, []byte("new output")) {
		t.Fatal("Maintenance discarded a current owner after a concurrent commit", err)
	}
}

func TestFileHTTPExpiresOnlyItsUnreferencedTargetAndVerifiesBytes(t *testing.T) {
	s, native := jobFilesService(t)
	owned, _ := files.Describe(randomToken(), "owned-input.bin", []byte("owned"))
	if _, err := s.inbox.Put("nPi", owned, strings.NewReader("owned")); err != nil {
		t.Fatal(err)
	}
	_, owner := newFileJob(t, s, native, []protocol.File{owned})
	bindFileJob(t, s, owner)
	orphan, _ := files.Describe(randomToken(), "orphan-input.bin", []byte("orphan"))
	if _, err := s.inbox.Put("nPi", orphan, strings.NewReader("orphan")); err != nil {
		t.Fatal(err)
	}
	for _, f := range []protocol.File{owned, orphan} {
		ageJobFile(t, s, "incoming", "nPi", f.ID)
		w := httptest.NewRecorder()
		s.fileRequest(w, httptest.NewRequest("GET", "/v1/files/"+f.ID+"/receipt?nonce="+randomToken(), nil), "nPi")
		var reply protocol.FileReply
		if json.Unmarshal(w.Body.Bytes(), &reply) != nil {
			t.Fatal(w.Body.String())
		}
		if f == owned && (w.Code != 200 || reply.File == nil || *reply.File != owned) {
			t.Fatal("Exact expiry deleted a current job's old prepared input", w.Code, reply)
		}
		if f == orphan && (w.Code != 404 || reply.State != "absent" || reply.File != nil) {
			t.Fatal("Exact expired orphan remained available", w.Code, reply)
		}
	}
	if err := os.WriteFile(filepath.Join(s.cfg.DataDir, "incoming", "nPi", owned.ID, "content"), []byte("wrong"), 0600); err != nil {
		t.Fatal(err)
	}
	w := httptest.NewRecorder()
	s.fileRequest(w, httptest.NewRequest("GET", "/v1/files/"+owned.ID+"/receipt?nonce="+randomToken(), nil), "nPi")
	if w.Code != 503 {
		t.Fatal("Ownership projection bypassed exact receipt byte verification", w.Code, w.Body.String())
	}
}

func TestCleanupCollectionDoesNotOwnRuntimeOrProducerLocks(t *testing.T) {
	s, native := jobFilesService(t)
	_, owner := newFileJob(t, s, native, nil)
	bindFileJob(t, s, owner)
	entered, release := make(chan struct{}), make(chan struct{})
	done, retired := make(chan error, 1), make(chan struct{})
	var unblock sync.Once
	t.Cleanup(func() {
		unblock.Do(func() { close(release) })
		checksync.Join(t, retired, 3*time.Second, "chat candidate collection")
	})
	go func() {
		defer close(retired)
		_, err := s.pruneOwnedChatsCollect(time.Now(), func(context.Context, string) (deletionNative, error) {
			t.Error("Current unexpired jobs must not start native cleanup")
			return nil, errors.New("unexpected native cleanup")
		}, func(peer string) ([]protocol.File, bool, error) {
			records, unknown, err := s.creationRecords(peer)
			if peer == "nPi" {
				close(entered)
				<-release
			}
			return records, unknown, err
		})
		done <- err
	}()
	checksync.Receive(t, entered, time.Second, "actual cleanup inventory")
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	nativeRelease, err := s.claimNative(ctx)
	if err != nil {
		t.Fatal("Historical collection blocked runtime status/auth lease", err)
	}
	nativeRelease()
	output, _ := files.Describe(randomToken(), "during-collection.bin", []byte("output"))
	if _, err := s.QueueJobFileReader(ctx, "nPi", owner.AppID, owner.JobID, owner.ThreadID, output, strings.NewReader("output")); err != nil {
		t.Fatal("Historical collection blocked an exact producer append", err)
	}
	unblock.Do(func() { close(release) })
	if err := checksync.Receive(t, done, time.Second, "completed cleanup inventory"); err != nil {
		t.Fatal(err)
	}
}

func TestHandoffCleanupSnapshotDoesNotWaitForScanOrWriter(t *testing.T) {
	s, native, input, _ := handoffCreationService(t)
	_, file := createOwnedHandoff(t, s, native, input, 0)
	if !s.refreshHandoffStatus(s.scanHandoffStatus) {
		t.Fatal("Initial maintenance observation failed")
	}
	before := s.cleanupSnapshot()
	if before.UnresolvedHandoffs != 1 || before.HandoffObservedAt <= 0 || before.HandoffCode != "" {
		t.Fatal("Initial handoff inventory unavailable", before)
	}
	entered, release := make(chan struct{}), make(chan struct{})
	done, retired := make(chan bool, 1), make(chan struct{})
	observed, observationRetired := make(chan CleanupStatus, 1), make(chan struct{})
	var unblock, unlock sync.Once
	s.handoffMu.Lock()
	t.Cleanup(func() {
		unlock.Do(s.handoffMu.Unlock)
		unblock.Do(func() { close(release) })
		checksync.Join(t, retired, 3*time.Second, "handoff diagnostic scan")
		checksync.Join(t, observationRetired, 3*time.Second, "cleanup status observation")
	})
	go func() {
		defer close(retired)
		done <- s.refreshHandoffStatus(func() (int, *handoffOwner, string) {
			count, oldest, code := s.scanHandoffStatus()
			close(entered)
			<-release
			return count, oldest, code
		})
	}()
	checksync.Receive(t, entered, time.Second, "actual retained handoff scan")
	go func() { defer close(observationRetired); observed <- s.cleanupSnapshot() }()
	if got := checksync.Receive(t, observed, time.Second, "nonblocking cleanup snapshot"); got.UnresolvedHandoffs != 1 || got.HandoffObservedAt != before.HandoffObservedAt {
		t.Fatal("Status lost its actual prior observation", got)
	}
	if err := s.removeHandoff(file); err != nil {
		t.Fatal(err)
	}
	if got := s.cleanupSnapshot(); got.HandoffCode != "handoff_status_pending" || got.HandoffObservedAt != before.HandoffObservedAt {
		t.Fatal("Dirty inventory acquired a fabricated fresh healthy status", got)
	}
	unblock.Do(func() { close(release) })
	if checksync.Receive(t, done, time.Second, "stale handoff scan") {
		t.Fatal("Old handoff scan overwrote a newer removal")
	}
	unlock.Do(s.handoffMu.Unlock)
	if !s.refreshHandoffStatus(s.scanHandoffStatus) {
		t.Fatal("Final handoff observation stayed stale")
	}
	if got := s.cleanupSnapshot(); got.UnresolvedHandoffs != 0 || got.HandoffCode != "" || got.HandoffObservedAt < before.HandoffObservedAt {
		t.Fatal("Final current inventory did not converge", got)
	}
}

func TestNativeCleanupBudgetStartsAfterHistoricalCollection(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, native := jobFilesService(t)
		owner, request := newFileJob(t, s, native, nil)
		bindFileJob(t, s, request)
		if err := s.authorizeCleanup(context.Background(), "nPi", owner, true); err != nil {
			t.Fatal(err)
		}
		deleting := &deletingNative{creationNativeFixture: native}
		connections := 0
		_, err := s.pruneOwnedChatsCollect(time.Now(), func(ctx context.Context, peer string) (deletionNative, error) {
			connections++
			if peer != "nPi" || ctx.Err() != nil {
				t.Fatal("Collection spent the native cleanup phase's budget", peer, ctx.Err())
			}
			return deleting, nil
		}, func(peer string) ([]protocol.File, bool, error) {
			records, unknown, err := s.creationRecords(peer)
			if peer == "nPi" {
				time.Sleep(31 * time.Second)
			}
			return records, unknown, err
		})
		if err != nil || connections != 1 || deleting.calls != 1 {
			t.Fatal("Available native cleanup was starved by a slow historical scan", err, connections, deleting.calls)
		}
		if _, err := s.creations.Info("nPi", creationID("nPi", owner.AppID, owner.RequestID, "deleted")); err != nil {
			t.Fatal("Cleanup did not persist its actual deletion checkpoint", err)
		}
	})
}

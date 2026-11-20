package pc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
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
	"tailscale.com/tsnet"
)

func jobFilesService(t *testing.T) (*Service, *creationNativeFixture) {
	t.Helper()
	s, native, _, _ := creationTestService(t)
	s.cfg.DataDir, s.cfg.Identity, s.cfg.Host = s.cfg.IntegrationDir, "nPc", "pc.tailtest.ts.net"
	s.cfg.AllowedPeers = []string{"nPi", "nOther"}
	s.cfg.CodexExecutable, _ = os.Executable()
	s.peers = map[string]*peer{"nPi": {identity: "nPi"}, "nOther": {identity: "nOther"}}
	s.epoch = randomToken()
	s.ctx, s.cancel = context.WithCancel(context.Background())
	s.retentionChanged = make(chan struct{}, 1)
	var err error
	s.inbox, err = files.Open(filepath.Join(s.cfg.DataDir, "incoming"))
	if err == nil {
		s.outbox, err = files.Open(filepath.Join(s.cfg.DataDir, "outgoing"))
	}
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.cancel(); s.inbox.Close(); s.outbox.Close() })
	return s, native
}

func newFileJob(t *testing.T, s *Service, native *creationNativeFixture, inputs []protocol.File) (protocol.AdapterRequest, Request) {
	t.Helper()
	input := protocol.AdapterRequest{Operation: "create", AppID: randomToken(), RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Name: "owned-file-job", Model: "fixture-model"}
	raw, err := s.createThread(context.Background(), "nPi", input, native)
	var reply struct{ Thread struct{ ID string } }
	if err != nil || json.Unmarshal(raw, &reply) != nil || reply.Thread.ID == "" {
		t.Fatal("Actual immutable creation failed", err)
	}
	input.ThreadID = reply.Thread.ID
	r := Request{Epoch: s.epoch, Operation: "text", Sequence: 1, AppID: input.AppID, JobID: input.RequestID, ThreadID: input.ThreadID, Text: "original job context", InputFiles: inputs}
	return input, r
}

func bindFileJob(t *testing.T, s *Service, request Request) {
	t.Helper()
	release, err := s.beginJobHandoff(context.Background(), "nPi", request)
	if err != nil {
		t.Fatal("Normal accepted send could not bind its exact inputs", err)
	}
	release()
}

func ageJobFile(t *testing.T, s *Service, folder, peer, id string) {
	t.Helper()
	old := time.Now().Add(-protocol.Retention - time.Hour)
	if err := os.Chtimes(filepath.Join(s.cfg.DataDir, folder, peer, id), old, old); err != nil {
		t.Fatal(err)
	}
}

func TestJobFilesSurviveAgeHealthAndStartupUntilFinalOwnedCleanup(t *testing.T) {
	s, native := jobFilesService(t)
	input, _ := files.Describe(randomToken(), "shared-input.bin", []byte("shared inputs"))
	if _, err := s.inbox.Put("nPi", input, strings.NewReader("shared inputs")); err != nil {
		t.Fatal(err)
	}
	first, request := newFileJob(t, s, native, []protocol.File{input})
	bindFileJob(t, s, request)
	second, shared := newFileJob(t, s, native, []protocol.File{input})
	bindFileJob(t, s, shared)
	output, _ := files.Describe(randomToken(), "owned-output.bin", []byte("actual output"))
	if _, err := s.QueueJobFileReader(context.Background(), "nPi", request.AppID, request.JobID, request.ThreadID, output, strings.NewReader("actual output")); err != nil {
		t.Fatal(err)
	}
	orphan, err := s.QueueFile(context.Background(), "nPi", randomToken(), "orphan.bin", []byte("cache"))
	if err != nil {
		t.Fatal(err)
	}
	for _, object := range []struct{ folder, id string }{{"incoming", input.ID}, {"outgoing", output.ID}, {"outgoing", orphan.ID}} {
		ageJobFile(t, s, object.folder, "nPi", object.id)
	}
	if _, err := s.pruneTransferFiles(time.Now()); err != nil {
		t.Fatal(err)
	}
	if _, err := s.outbox.Info("nPi", orphan.ID); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Ordinary unreferenced cache survived expiry", err)
	}
	w := httptest.NewRecorder()
	s.fileRequest(w, httptest.NewRequest("GET", "/v1/files/health?nonce="+randomToken(), nil), "nPi")
	if w.Code != 200 {
		t.Fatal("Reference-aware health failed", w.Body.String())
	}
	if _, _, err := s.inbox.Read("nPi", input.ID); err != nil {
		t.Fatal("Health deleted an accepted input", err)
	}
	if err := s.DiscardQueuedFile("nPi", output.ID); !errors.Is(err, ErrJobFileReferenced) {
		t.Fatal("Generic discard bypassed owned retention", err)
	}
	if _, err := s.QueueFile(context.Background(), "nPi", output.ID, output.Name, []byte("actual output")); !errors.Is(err, ErrJobFileReferenced) {
		t.Fatal("Generic queue entered an owned transfer", err)
	}
	s.inbox.Close()
	s.outbox.Close()
	s.creations.Close()
	reopened, err := New(&tsnet.Server{}, s.cfg)
	if err != nil {
		t.Fatal("Startup lost durable file references", err)
	}
	defer reopened.Close()
	if _, data, err := reopened.outbox.Read("nPi", output.ID); err != nil || string(data) != "actual output" {
		t.Fatal("Startup deleted an undelivered output", err)
	}
	deleting := &deletingNative{creationNativeFixture: native}
	first.Operation, second.Operation = "delete", "delete"
	if err := reopened.deleteCreation(context.Background(), "nPi", first, deleting); err != nil {
		t.Fatal(err)
	}
	if _, err := reopened.inbox.Info("nPi", input.ID); err != nil {
		t.Fatal("First cleanup deleted another retained job's shared input", err)
	}
	if _, err := reopened.outbox.Info("nPi", output.ID); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Authorized cleanup left its exclusively owned output", err)
	}
	if err := reopened.deleteCreation(context.Background(), "nPi", second, deleting); err != nil {
		t.Fatal(err)
	}
	if _, err := reopened.inbox.Info("nPi", input.ID); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Last owned reference was not retired", err)
	}
	if _, err := reopened.pruneTransferFiles(time.Now()); err != nil {
		t.Fatal("Verified deletion checkpoints became unresolved live owners", err)
	}
}

func TestIdleCreationDoesNotBlockOtherWorkOrExpiredCaches(t *testing.T) {
	s, native := jobFilesService(t)
	owner, request := newFileJob(t, s, native, nil)
	bindFileJob(t, s, request)
	output, _ := files.Describe(randomToken(), "delivered.bin", []byte("delivered"))
	if _, err := s.QueueJobFileReader(context.Background(), "nPi", request.AppID, request.JobID, request.ThreadID, output, strings.NewReader("delivered")); err != nil {
		t.Fatal(err)
	}
	orphan, err := s.QueueFile(context.Background(), "nPi", randomToken(), "unreferenced.bin", []byte("orphan"))
	if err != nil {
		t.Fatal(err)
	}
	idle, _ := newFileJob(t, s, native, nil)
	if f, err := s.QueueFile(context.Background(), "nPi", randomToken(), "generic.bin", []byte("generic")); err != nil {
		t.Error("Idle chat blocked an unrelated generic transfer", err)
	} else if err := s.DiscardQueuedFile("nPi", f.ID); err != nil {
		t.Error("Idle chat blocked unrelated explicit cache removal", err)
	}
	ageJobFile(t, s, "outgoing", "nPi", orphan.ID)
	if _, err := s.pruneTransferFiles(time.Now()); err != nil {
		t.Error("Idle chat made ordinary cache retention unconfirmed", err)
	}
	if _, err := s.outbox.Info("nPi", orphan.ID); !errors.Is(err, files.ErrAbsent) {
		t.Error("Idle chat kept an unrelated expired cache", err)
	}
	w := httptest.NewRecorder()
	s.fileRequest(w, httptest.NewRequest("GET", "/v1/files/health?nonce="+randomToken(), nil), "nPi")
	if w.Code != 200 {
		t.Error("Idle chat blocked file health", w.Body.String())
	}
	owner.Operation = "delete"
	if err := s.deleteCreation(context.Background(), "nPi", owner, &deletingNative{creationNativeFixture: native}); err != nil {
		t.Error("Idle chat blocked another delivered job's authorized cleanup", err)
	}
	if _, err := s.outbox.Info("nPi", output.ID); !errors.Is(err, files.ErrAbsent) {
		t.Error("Delivered output was pinned by idle chat", err)
	}
	for _, kind := range []string{"request", "result"} {
		if _, err := s.creations.Info("nPi", creationID("nPi", idle.AppID, idle.RequestID, kind)); err != nil {
			t.Error("Unrelated cleanup removed idle chat ownership", err)
		}
	}
	if _, err := s.creations.Info("nPi", creationID("nPi", idle.AppID, idle.RequestID, "inputs")); !errors.Is(err, files.ErrAbsent) {
		t.Error("Merely creating a chat invented a dispatch record", err)
	}
}

type heldFileReader struct {
	reader  io.Reader
	entered chan struct{}
	release <-chan struct{}
	first   bool
}

func (r *heldFileReader) Read(target []byte) (int, error) {
	if !r.first {
		r.first = true
		close(r.entered)
		<-r.release
	}
	return r.reader.Read(target)
}

func TestGenericQueueCannotRaceOwnedAdmissionOrCleanupIntoForeignBytes(t *testing.T) {
	s, native := jobFilesService(t)
	owner, request := newFileJob(t, s, native, nil)
	bindFileJob(t, s, request)
	f, _ := files.Describe(randomToken(), "generic.bin", []byte("generic bytes"))
	release := make(chan struct{})
	var once sync.Once
	unblock := func() { once.Do(func() { close(release) }) }
	source := &heldFileReader{reader: strings.NewReader("generic bytes"), entered: make(chan struct{}), release: release}
	done, retired := make(chan error, 1), make(chan struct{})
	t.Cleanup(func() { unblock(); checksync.Join(t, retired, time.Second, "generic stream retirement") })
	go func() {
		defer close(retired)
		_, err := s.QueueFileReader(context.Background(), "nPi", f, source)
		done <- err
	}()
	checksync.Receive(t, source.entered, time.Second, "generic stream is active")
	changed, _ := files.Describe(f.ID, f.Name, []byte("different job bytes"))
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Millisecond)
	defer cancel()
	if _, err := s.QueueJobFileReader(ctx, "nPi", request.AppID, request.JobID, request.ThreadID, changed, bytes.NewReader([]byte("different job bytes"))); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal("Owned admission bypassed the active generic transfer lease", err)
	}
	if _, err := s.creations.Info("nPi", creationID("nPi", request.AppID, request.JobID, "output:"+f.ID)); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Interrupted lease wait minted an output claim", err)
	}
	// The owner can be cleaned without waiting on unrelated generic I/O or claiming its bytes.
	owner.Operation = "delete"
	if err := s.deleteCreation(context.Background(), "nPi", owner, &deletingNative{creationNativeFixture: native}); err != nil {
		t.Fatal("Unrelated generic stream deadlocked owned cleanup", err)
	}
	// Cleanup owns no descriptor for this transfer; releasing the stream must preserve its result.
	unblock()
	if err := checksync.Receive(t, done, time.Second, "generic stream completes"); err != nil {
		t.Fatal(err)
	}
	if _, data, err := s.outbox.Read("nPi", f.ID); err != nil || string(data) != "generic bytes" {
		t.Fatal("Cleanup discarded unrelated generic bytes", err)
	}
}

func TestOwnedQueueRejectsExistingDifferentBytesBeforePublishingRef(t *testing.T) {
	s, native := jobFilesService(t)
	owner, request := newFileJob(t, s, native, nil)
	bindFileJob(t, s, request)
	foreign, err := s.QueueFile(context.Background(), "nPi", randomToken(), "foreign.bin", []byte("foreign"))
	if err != nil {
		t.Fatal(err)
	}
	changed, _ := files.Describe(foreign.ID, foreign.Name, []byte("different"))
	if _, err := s.QueueJobFileReader(context.Background(), "nPi", request.AppID, request.JobID, request.ThreadID, changed, strings.NewReader("different")); !errors.Is(err, files.ErrConflict) {
		t.Fatal("Owned queue overwrote a foreign immutable result", err)
	}
	if _, err := s.creations.Info("nPi", creationID("nPi", request.AppID, request.JobID, "output:"+foreign.ID)); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Rejected upload left an ownership claim", err)
	}
	owner.Operation = "delete"
	if err := s.deleteCreation(context.Background(), "nPi", owner, &deletingNative{creationNativeFixture: native}); err != nil {
		t.Fatal(err)
	}
	if _, raw, err := s.outbox.Read("nPi", foreign.ID); err != nil || string(raw) != "foreign" {
		t.Fatal("Owner cleanup deleted rejected foreign bytes", err)
	}
}

func TestGenericQueueLeaseWaitRetiresWhenServiceCloses(t *testing.T) {
	s, _ := jobFilesService(t)
	f, _ := files.Describe(randomToken(), "queued.bin", []byte("bytes"))
	release, err := s.claimCreation(context.Background(), "nPi", "outgoing-file", f.ID)
	if err != nil {
		t.Fatal(err)
	}
	defer release()
	done, retired := make(chan error, 1), make(chan struct{})
	t.Cleanup(func() { s.cancel(); checksync.Join(t, retired, time.Second, "generic lease wait retirement") })
	go func() {
		defer close(retired)
		_, err := s.QueueFileReader(context.Background(), "nPi", f, strings.NewReader("bytes"))
		done <- err
	}()
	s.cancel()
	if err := checksync.Receive(t, done, time.Second, "generic transfer observes service cancellation"); !errors.Is(err, context.Canceled) {
		t.Fatal("Generic queue ignored service shutdown while exact transfer was leased", err)
	}
	if _, err := s.outbox.Info("nPi", f.ID); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Cancelled queue committed bytes", err)
	}
}

func TestFileRetentionWorkerTreatsIdleCreationAsNoDispatch(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, native := jobFilesService(t)
		_, request := newFileJob(t, s, native, nil)
		s.cfg.CodexExecutable = "" // Idle ownership must require no native connection.
		done := make(chan struct{})
		go func() { defer close(done); s.pruneFiles() }()
		defer func() { s.cancel(); checksync.Join(t, done, time.Second, "idle file-retention worker") }()
		synctest.Wait()
		status := s.cleanupSnapshot()
		if status.State != "current" || status.Code != "" {
			t.Fatal("Idle chat was treated as an uncertain dispatch", status)
		}
		for _, kind := range []string{"request", "result"} {
			if _, err := s.creations.Info("nPi", creationID("nPi", request.AppID, request.JobID, kind)); err != nil {
				t.Fatal("Idle retention lost accepted chat ownership", err)
			}
		}
	})
}

func TestOwnedCleanupPreservesChangedDescriptor(t *testing.T) {
	s, native := jobFilesService(t)
	file, _ := files.Describe(randomToken(), "shared.bin", []byte("original"))
	if _, err := s.inbox.Put("nPi", file, strings.NewReader("original")); err != nil {
		t.Fatal(err)
	}
	owner, request := newFileJob(t, s, native, []protocol.File{file})
	bindFileJob(t, s, request)
	if err := s.inbox.Discard("nPi", file.ID); err != nil {
		t.Fatal(err)
	}
	changed, _ := files.Describe(file.ID, file.Name, []byte("changed bytes"))
	if _, err := s.inbox.Put("nPi", changed, strings.NewReader("changed bytes")); err != nil {
		t.Fatal(err)
	}
	owner.Operation = "delete"
	if err := s.deleteCreation(context.Background(), "nPi", owner, &deletingNative{creationNativeFixture: native}); err == nil {
		t.Fatal("Cleanup discarded changed bytes without exact ownership proof")
	}
	if _, _, err := s.inbox.Read("nPi", file.ID); err != nil {
		t.Fatal("Unconfirmed cleanup discarded changed bytes", err)
	}
	if _, err := s.creations.Info("nPi", creationID("nPi", request.AppID, request.JobID, "inputs")); err != nil {
		t.Fatal("Cleanup lost its unresolved reference", err)
	}
}

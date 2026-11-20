package backend

import (
	"bytes"
	"fmt"
	"io"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

func TestFileHealthAndMaintenancePreserveAgedBytesBeforeJobDeadline(t *testing.T) {
	for _, cleanup := range []string{"background", "file_request"} {
		for _, reference := range []string{"input", "output", "report", "inline_result"} {
			t.Run(cleanup+"_"+reference, func(t *testing.T) {
				s, _, call := fixtureAPI(t)
				registerFixture(t, call, "nAlice")
				p := s.getPeer("nAlice")
				state, err := s.loadApp(p)
				if err != nil {
					t.Fatal(err)
				}
				job := ownedOutputJob(randomToken())
				data := []byte("retained-by-job-" + randomToken())
				id := randomToken()
				if reference == "inline_result" {
					id = appResultID(state.ID, job.ID)
				}
				f, _ := files.Describe(id, "owned.bin", data)
				store, folder := s.outbox, "outgoing"
				switch reference {
				case "input":
					store, folder = s.inbox, "incoming"
					job.Input.Files = []protocol.File{f}
					job.Fingerprint = appHash(job.Input)
				case "output":
					job.Outputs = []protocol.File{f}
				case "report":
					job.Report = &appReport{Files: []protocol.File{f}}
				case "inline_result":
					result := "unpublished inline result"
					job.Report = &appReport{Result: &result}
				}
				state.Jobs[job.ID] = job
				if _, err := store.Put(p.identity, f, bytes.NewReader(data)); err != nil {
					t.Fatal(err)
				}
				old := time.Now().Add(-appRetention - time.Second)
				if err := os.Chtimes(filepath.Join(s.cfg.DataDir, folder, p.identity, f.ID), old, old); err != nil {
					t.Fatal(err)
				}
				if err := s.saveApp(state); err != nil {
					t.Fatal(err)
				}
				if cleanup == "file_request" {
					if response := call("GET", "/v1/files/health?nonce="+strings.Repeat("a", 32), nil, p.identity); response.Code != 200 {
						t.Fatal(response.Body.String())
					}
				}
				if _, err := s.retentionPass(time.Now()); err != nil {
					t.Fatal(err)
				}
				if actual, content, err := store.Read(p.identity, f.ID); err != nil || actual != f || !bytes.Equal(content, data) {
					t.Fatal("Cleanup removed bytes referenced by a retained job", err)
				}
			})
		}
	}
}

func TestReferenceCleanupRemovesBytesOnlyAfterFinalJob(t *testing.T) {
	for _, reference := range []string{"input", "output", "report", "inline_result"} {
		t.Run(reference, func(t *testing.T) {
			s, native, call := fixtureAPI(t)
			native.online = true
			registerFixture(t, call, "nAlice")
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			first, last := ownedOutputJob(randomToken()), ownedOutputJob(randomToken())
			first.State, last.State = "completed", "completed"
			data := []byte("last-reference-" + randomToken())
			id := randomToken()
			if reference == "inline_result" {
				id = appResultID(state.ID, last.ID)
			}
			f, _ := files.Describe(id, "shared.bin", data)
			first.Outputs = []protocol.File{f}
			switch reference {
			case "input":
				last.Input.Files = []protocol.File{f}
				last.Fingerprint = appHash(last.Input)
			case "output":
				last.Outputs = []protocol.File{f}
			case "report":
				last.Report = &appReport{Files: []protocol.File{f}}
			}
			state.Jobs[first.ID], state.Jobs[last.ID] = first, last
			for _, store := range []*files.Store{s.inbox, s.outbox} {
				if _, err := store.Put(p.identity, f, bytes.NewReader(data)); err != nil {
					t.Fatal(err)
				}
			}
			if err := s.saveApp(state); err != nil {
				t.Fatal(err)
			}
			if response := call("DELETE", "/v2/jobs/"+first.ID, nil, p.identity); response.Code != 200 {
				t.Fatal(response.Body.String())
			}
			for _, store := range []*files.Store{s.inbox, s.outbox} {
				if _, actual, err := store.Read(p.identity, f.ID); err != nil || !bytes.Equal(actual, data) {
					t.Fatal("Removing first owner lost retained reference", err)
				}
			}
			if response := call("DELETE", "/v2/jobs/"+last.ID, nil, p.identity); response.Code != 200 {
				t.Fatal(response.Body.String())
			}
			for _, store := range []*files.Store{s.inbox, s.outbox} {
				if _, err := store.Info(p.identity, f.ID); !os.IsNotExist(err) {
					t.Fatal("Final owner removal retained bytes", err)
				}
			}
		})
	}
}

type retainedAdmissionBody struct {
	source           io.Reader
	entered, release chan struct{}
	once             sync.Once
}

func (r *retainedAdmissionBody) Read(p []byte) (int, error) {
	r.once.Do(func() { close(r.entered); <-r.release })
	return r.source.Read(p)
}

func TestSuspendedAdmissionDoesNotReserveReferenceOrBlockCleanup(t *testing.T) {
	for _, concurrent := range []string{"background", "file_request", "delete_owner"} {
		t.Run(concurrent, func(t *testing.T) {
			s, native, call := fixtureAPI(t)
			native.online = true
			registerFixture(t, call, "nAlice")
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			f, _ := files.Describe(randomToken(), "context.bin", []byte("retained context"))
			if _, err := s.inbox.Put(p.identity, f, strings.NewReader("retained context")); err != nil {
				t.Fatal(err)
			}
			old := time.Now().Add(-appRetention - time.Second)
			if err := os.Chtimes(filepath.Join(s.cfg.DataDir, "incoming", p.identity, f.ID), old, old); err != nil {
				t.Fatal(err)
			}
			owner := ownedOutputJob(randomToken())
			owner.State, owner.Input.Files = "completed", []protocol.File{f}
			owner.Fingerprint = appHash(owner.Input)
			if concurrent == "delete_owner" {
				state.Jobs[owner.ID] = owner
				if err := s.saveApp(state); err != nil {
					t.Fatal(err)
				}
			}
			input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Admission has no reference before receipt", Text: "immutable input", Files: []protocol.File{f}}
			body := &retainedAdmissionBody{source: bytes.NewReader(mustJSON(input)), entered: make(chan struct{}), release: make(chan struct{})}
			request := httptest.NewRequest("POST", "/v2/jobs", body)
			request.RemoteAddr = p.identity
			request.Header.Set("Content-Type", "application/json")
			admitted := make(chan *httptest.ResponseRecorder, 1)
			go func() { response := httptest.NewRecorder(); s.ServeHTTP(response, request); admitted <- response }()
			var once sync.Once
			admissionFinished, sweepFinished, sweepStarted := false, false, false
			swept := make(chan error, 1)
			t.Cleanup(func() {
				once.Do(func() { close(body.release) })
				if !admissionFinished {
					select {
					case <-admitted:
					case <-time.After(3 * time.Second):
						t.Error("Admission worker remained live")
					}
				}
				if sweepStarted && !sweepFinished {
					select {
					case <-swept:
					case <-time.After(3 * time.Second):
						t.Error("Cleanup worker remained live")
					}
				}
			})
			select {
			case <-body.entered:
			case <-time.After(3 * time.Second):
				t.Fatal("Production admission never reached body receipt")
			}
			sweepStarted = true
			go func() {
				if concurrent == "background" {
					_, err := s.retentionPass(time.Now())
					swept <- err
					return
				}
				method, path := "GET", "/v1/files/health?nonce="+randomToken()
				if concurrent == "delete_owner" {
					method, path = "DELETE", "/v2/jobs/"+owner.ID
				}
				response := call(method, path, nil, p.identity)
				if response.Code != 200 {
					swept <- fmt.Errorf("cleanup HTTP %d: %s", response.Code, response.Body.String())
					return
				}
				if concurrent == "file_request" {
					if _, err := s.inbox.Info(p.identity, f.ID); err != nil {
						swept <- fmt.Errorf("health request pruned unclaimed bytes: %w", err)
						return
					}
					_, err := s.retentionPass(time.Now())
					swept <- err
					return
				}
				swept <- nil
			}()
			err = checksync.Receive(t, swept, 3*time.Second, "cleanup while request body is suspended")
			sweepFinished = true
			if err != nil {
				t.Fatal(err)
			}
			if _, err := s.inbox.Info(p.identity, f.ID); !os.IsNotExist(err) {
				t.Fatal("Suspended request reserved an input it has not admitted", err)
			}
			once.Do(func() { close(body.release) })
			select {
			case response := <-admitted:
				admissionFinished = true
				if response.Code != 409 || !strings.Contains(response.Body.String(), `"code":"input_bytes_unverified"`) {
					t.Fatal("Resumed admission accepted an expired unclaimed input", response.Code, response.Body.String())
				}
			case <-time.After(3 * time.Second):
				t.Fatal("Admission did not finish")
			}
			latest, err := s.loadApp(p)
			if err != nil || latest.Jobs[input.RequestID] != nil || native.calls != 0 {
				t.Fatal("Failed admission committed a job or dispatched native work", err)
			}
		})
	}
}

func TestReferenceAwareRetentionPrunesOrphansAfterReopenAndPreservesUnknownApp(t *testing.T) {
	s, native, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	keep, _ := files.Describe(randomToken(), "uncertain-reference.bin", []byte("preserve unknown references"))
	orphan, _ := files.Describe(randomToken(), "orphan.bin", []byte("discard aged orphan"))
	old := time.Now().Add(-appRetention - time.Second)
	for peer, f := range map[string]protocol.File{"nAlice": keep, "nDetached": orphan} {
		content := "preserve unknown references"
		if peer == "nDetached" {
			content = "discard aged orphan"
		}
		if _, err := s.inbox.Put(peer, f, strings.NewReader(content)); err != nil {
			t.Fatal(err)
		}
		if err := os.Chtimes(filepath.Join(s.cfg.DataDir, "incoming", peer, f.ID), old, old); err != nil {
			t.Fatal(err)
		}
	}
	_, badState := damageOwnedApp(t, s, "nAlice", "malformed")
	before, err := os.ReadFile(badState)
	if err != nil {
		t.Fatal(err)
	}
	s.Close()
	reopened, err := New(s.cfg, native, s.authenticate, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	if _, err := reopened.retentionPass(time.Now()); err == nil {
		t.Fatal("Unknown reference ownership was not reported")
	}
	if _, actual, err := reopened.inbox.Read("nAlice", keep.ID); err != nil || string(actual) != "preserve unknown references" {
		t.Fatal("Unknown app references were assumed absent", err)
	}
	if _, err := reopened.inbox.Info("nDetached", orphan.ID); !os.IsNotExist(err) {
		t.Fatal("Unregistered orphan peer escaped age cleanup after reopen", err)
	}
	if after, err := os.ReadFile(badState); err != nil || !bytes.Equal(before, after) {
		t.Fatal("Malformed app context was changed", err)
	}
}

func TestFileRequestCannotPruneAnotherAppsReferences(t *testing.T) {
	s, _, call := fixtureAPI(t)
	for _, peer := range []string{"nAlice", "nBob"} {
		registerFixture(t, call, peer)
	}
	state, err := s.loadApp(s.getPeer("nBob"))
	if err != nil {
		t.Fatal(err)
	}
	f, _ := files.Describe(randomToken(), "owned-by-bob.bin", []byte("context for Bob"))
	if _, err := s.inbox.Put(state.Peer, f, strings.NewReader("context for Bob")); err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-appRetention - time.Second)
	if err := os.Chtimes(filepath.Join(s.cfg.DataDir, "incoming", state.Peer, f.ID), old, old); err != nil {
		t.Fatal(err)
	}
	job := ownedOutputJob(randomToken())
	job.Input.Files, job.Input.RequestID = []protocol.File{f}, job.ID
	job.Fingerprint = appHash(job.Input)
	state.Jobs[job.ID] = job
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	if response := call("GET", "/v1/files/health?nonce="+randomToken(), nil, "nAlice"); response.Code != 200 {
		t.Fatal(response.Body.String())
	}
	if _, data, err := s.inbox.Read(state.Peer, f.ID); err != nil || string(data) != "context for Bob" {
		t.Fatal("One app's file request destroyed another app's referenced object", err)
	}
}

func TestFailedReferenceRemovalCommitPreservesDurableJobAndBytes(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	p.appMu.Lock()
	defer p.appMu.Unlock()
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	job := ownedOutputJob(randomToken())
	job.State = "completed"
	f, _ := files.Describe(randomToken(), "retained-result.bin", []byte("durable retained result"))
	job.Outputs = []protocol.File{f}
	state.Jobs[job.ID] = job
	if _, err := s.outbox.Put(p.identity, f, strings.NewReader("durable retained result")); err != nil {
		t.Fatal(err)
	}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	before, err := os.ReadFile(filepath.Join(s.cfg.DataDir, "apps", appStateName(p.identity)))
	if err != nil {
		t.Fatal(err)
	}
	// Make only coordinator writes unavailable; existing managed file handles remain usable.
	dataDir := s.cfg.DataDir
	s.cfg.DataDir = filepath.Join(t.TempDir(), "missing-parent", "bridge")
	err = s.removeAppJobs(state, map[string]*appJob{job.ID: job})
	s.cfg.DataDir = dataDir
	if err == nil {
		t.Fatal("Failed coordinator commit was reported as successful deletion")
	}
	if after, err := os.ReadFile(filepath.Join(dataDir, "apps", appStateName(p.identity))); err != nil || !bytes.Equal(before, after) {
		t.Fatal("Failed removal changed durable retained job", err)
	}
	if _, content, err := s.outbox.Read(p.identity, f.ID); err != nil || string(content) != "durable retained result" {
		t.Fatal("Uncommitted reference removal destroyed retained bytes", err)
	}
	state, err = s.loadApp(p)
	if err != nil || state.Jobs[job.ID] == nil || state.DeletedRequests[job.ID] != 0 {
		t.Fatal("Durable reference did not survive failed commit", err)
	}
}

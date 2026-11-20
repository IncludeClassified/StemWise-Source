package backend

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func TestPartialOutputCleanup(t *testing.T) {
	for _, mode := range []string{"delete", "expire_unclaimed"} {
		t.Run(mode, func(t *testing.T) {
			s, n, call := fixtureAPI(t)
			n.online = true
			registerFixture(t, call, "nAlice")
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			j := ownedOutputJob(randomToken())
			state.Jobs[j.ID] = j
			data := []byte("partial-output-" + randomToken())
			f, _ := files.Describe(randomToken(), "first.bin", data)
			missing, _ := files.Describe(randomToken(), "missing.bin", []byte("absent"))
			n.outputs[f.ID] = data
			n.report = string(mustJSON(appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "ready", Files: []protocol.File{f, missing}}))
			if err := s.refreshApp(p, state, j); err != nil {
				t.Fatal(err)
			}
			if j.State != "completed" || j.Code != "output_bytes_unverified" || len(j.Outputs) != 0 || j.Report == nil {
				t.Fatal("Fixture did not stage an unpublished partial batch")
			}
			if _, actual, err := s.outbox.Read(p.identity, f.ID); err != nil || !bytes.Equal(actual, data) {
				t.Fatal("Actual partial bytes were not staged", err)
			}
			if mode == "delete" {
				if w := call("DELETE", "/v2/jobs/"+j.ID, nil, p.identity); w.Code != 200 {
					t.Fatal(w.Body.String())
				}
				if w := call("POST", "/v2/jobs", j.Input, p.identity); w.Code != 410 || !strings.Contains(w.Body.String(), "request_deleted") {
					t.Fatal("Deleted partial batch could execute again", w.Body.String())
				}
			} else {
				old := time.Now().Add(-appRetention - time.Second)
				j.Created = old.UnixMilli()
				if err := os.Chtimes(filepath.Join(s.cfg.DataDir, "outgoing", p.identity, f.ID), old, old); err != nil {
					t.Fatal(err)
				}
				if err := s.saveApp(state); err != nil {
					t.Fatal(err)
				}
				if _, err := s.retentionPass(time.Now()); err != nil {
					t.Fatal(err)
				}
				stored, err := s.loadApp(p)
				if err != nil || stored.Jobs[j.ID] != nil || stored.DeletedRequests[j.ID] == 0 || n.calls != 0 {
					t.Fatal("Expired partial batch was retained or replayed", err)
				}
				if _, _, err := s.outbox.Read(p.identity, f.ID); !os.IsNotExist(err) {
					t.Fatal("Expired partial bytes remained", err)
				}
				return
			}
			if _, err := s.outbox.Info(p.identity, f.ID); !os.IsNotExist(err) {
				t.Fatal("Terminal job cleanup retained unpublished bytes", err)
			}
			state, err = s.loadApp(p)
			if err != nil || len(state.Jobs) != 0 || n.calls != 0 {
				t.Fatal("Cleanup retained job or triggered native execution", err, n.calls)
			}
		})
	}
}

func TestCleanupPreservesLiveInputAndUnpublishedReportReferences(t *testing.T) {
	for _, reference := range []string{"input", "report"} {
		t.Run(reference, func(t *testing.T) {
			s, n, call := fixtureAPI(t)
			n.online = true
			registerFixture(t, call, "nAlice")
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			data := []byte("live-owner-" + randomToken())
			f, _ := files.Describe(randomToken(), "shared.bin", data)
			for _, store := range []*files.Store{s.inbox, s.outbox} {
				if _, err := store.Put(p.identity, f, bytes.NewReader(data)); err != nil {
					t.Fatal(err)
				}
			}
			owner, claimant := ownedOutputJob(randomToken()), ownedOutputJob(randomToken())
			owner.Report = &appReport{Protocol: "v2", AppID: state.ID, JobID: owner.ID, Revision: 1, Status: "completed", Code: "ready", Files: []protocol.File{f}}
			if reference == "input" {
				owner.Input.Files = []protocol.File{f}
				owner.Fingerprint = appHash(owner.Input)
				owner.Report = nil
			}
			state.Jobs[owner.ID], state.Jobs[claimant.ID] = owner, claimant
			n.report = string(mustJSON(appReport{Protocol: "v2", AppID: state.ID, JobID: claimant.ID, Revision: 1, Status: "completed", Code: "ready", Files: []protocol.File{f}}))
			if err := s.refreshApp(p, state, claimant); err != nil {
				t.Fatal(err)
			}
			if reference == "report" && (claimant.Code != "artifact_belongs_to_another_job" || len(claimant.Outputs) != 0) {
				t.Fatal("Conflicting unpublished claim was not rejected")
			}
			if w := call("DELETE", "/v2/jobs/"+claimant.ID, nil, p.identity); w.Code != 200 {
				t.Fatal(w.Body.String())
			}
			for _, store := range []*files.Store{s.inbox, s.outbox} {
				if _, actual, err := store.Read(p.identity, f.ID); err != nil || !bytes.Equal(actual, data) {
					t.Fatal("Deleting claimant destroyed another job's live reference", err)
				}
			}
			state, err = s.loadApp(p)
			if err != nil || len(state.Jobs) != 1 || !bytes.Equal(mustJSON(state.Jobs[owner.ID]), mustJSON(owner)) {
				t.Fatal("Deleting claimant changed original owner", err)
			}
			p.appMu.Lock()
			err = s.removeAppJobs(state, map[string]*appJob{owner.ID: state.Jobs[owner.ID]})
			p.appMu.Unlock()
			if err != nil {
				t.Fatal(err)
			}
			for _, store := range []*files.Store{s.inbox, s.outbox} {
				if _, err := store.Info(p.identity, f.ID); !os.IsNotExist(err) {
					t.Fatal("Last reference removal retained bytes", err)
				}
			}
		})
	}
}

package backend

import (
	"bytes"
	"errors"
	"phonepcbridge/files"
	"phonepcbridge/protocol"
	"strings"
	"testing"
	"time"
)

func ownedOutputJob(id string) *appJob {
	now := time.Now().UnixMilli()
	input := appInput{RequestID: id, CreatedAt: now, Title: "owned output", Text: "request-" + randomToken(), Files: []protocol.File{}}
	return &appJob{ID: id, Input: input, Fingerprint: appHash(input), Developer: "bridge_job_" + id, ThreadID: "fixture-chat", ChatState: "created", TurnID: "fixture-turn", State: "submitted", Created: now, Outputs: []protocol.File{}}
}
func TestOutputClaimsRejectOtherJobFilesAndInlineResultsBeforeStaging(t *testing.T) {
	for _, inline := range []bool{false, true} {
		t.Run(map[bool]string{false: "artifact", true: "inline_result"}[inline], func(t *testing.T) {
			s, n, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			data := []byte("original-owner-" + randomToken())
			f, _ := files.Describe(randomToken(), "owned.bin", data)
			if _, err := s.outbox.Put("nAlice", f, bytes.NewReader(data)); err != nil {
				t.Fatal(err)
			}
			owner := ownedOutputJob(randomToken())
			owner.State = "completed"
			owner.Outputs = []protocol.File{f}
			owner.Report = &appReport{Protocol: "v2", AppID: state.ID, JobID: owner.ID, Revision: 1, Status: "completed", Code: "ready"}
			if !inline {
				owner.Report.Files = []protocol.File{f}
			}
			j := ownedOutputJob(randomToken())
			state.Jobs[owner.ID] = owner
			state.Jobs[j.ID] = j
			result := "must-not-be-staged-" + randomToken()
			n.report = string(mustJSON(appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "ready", Files: []protocol.File{f}, Result: &result}))
			for i := 0; i < 2; i++ {
				if err := s.refreshApp(p, state, j); err != nil {
					t.Fatal("Claim error not persisted as a job issue", err)
				}
			}
			if j.State != "completed" || j.Code != "artifact_belongs_to_another_job" || len(j.Outputs) != 0 || len(j.Log) != 1 {
				t.Fatal("Other job output was attributed or conflict hidden")
			}
			page, err := s.outbox.List("nAlice", "", 100)
			if err != nil || len(page.Files) != 1 {
				t.Fatal("Rejected claim staged partial outputs", err)
			}
			if _, actual, err := s.outbox.Read("nAlice", f.ID); err != nil || !bytes.Equal(actual, data) {
				t.Fatal("Original owner's bytes changed", err)
			}
			w := call("GET", "/v2/jobs/"+j.ID, nil, "nAlice")
			if w.Code != 200 || !strings.Contains(w.Body.String(), "artifact_belongs_to_another_job") || !strings.Contains(w.Body.String(), `"issue":{`) {
				t.Fatal("Claim conflict not available to apps", w.Body.String())
			}
		})
	}
}

func TestUnpublishedPartialOutputIsNotAvailableThroughBinaryAPI(t *testing.T) {
	s, n, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	state.Jobs[j.ID] = j
	first := []byte("actual-first-" + randomToken())
	f, _ := files.Describe(randomToken(), "first.bin", first)
	missing, _ := files.Describe(randomToken(), "missing.bin", []byte("not-delivered"))
	n.outputs[f.ID] = first
	n.report = string(mustJSON(appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "ready", Files: []protocol.File{f, missing}}))
	if err := s.refreshApp(p, state, j); err != nil {
		t.Fatal(err)
	}
	if len(j.Outputs) != 0 || j.Code != "output_bytes_unverified" {
		t.Fatal("Incomplete output batch published")
	}
	w := call("GET", "/v1/outbox?nonce="+randomToken()+"&cursor=&limit=100", nil, "nAlice")
	if w.Code != 200 || strings.Contains(w.Body.String(), f.ID) {
		t.Fatal("Binary listing exposed unpublished partial output", w.Body.String())
	}
	for _, suffix := range []string{"info", "content"} {
		if w := call("GET", "/v1/outbox/"+f.ID+"/"+suffix+"?nonce="+randomToken(), nil, "nAlice"); w.Code != 404 {
			t.Fatal("Guessed ID retrieved unpublished partial output", w.Code)
		}
	}
}

func TestPublishedBinaryOutputsRemainAccessibleWithPagination(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	j.State = "completed"
	j.Report = &appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "ready"}
	state.Jobs[j.ID] = j
	for _, id := range []string{strings.Repeat("1", 32), strings.Repeat("2", 32)} {
		data := []byte("published-" + id)
		f, _ := files.Describe(id, "owned.bin", data)
		if _, err := s.outbox.Put("nAlice", f, bytes.NewReader(data)); err != nil {
			t.Fatal(err)
		}
		j.Outputs = append(j.Outputs, f)
	}
	hidden, _ := files.Describe(strings.Repeat("0", 32), "staging.bin", []byte("private stage"))
	if _, err := s.outbox.Put("nAlice", hidden, strings.NewReader("private stage")); err != nil {
		t.Fatal(err)
	}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	for _, cursor := range []string{"", j.Outputs[0].ID} {
		w := call("GET", "/v1/outbox?nonce="+randomToken()+"&cursor="+cursor+"&limit=1", nil, "nAlice")
		if w.Code != 200 || strings.Contains(w.Body.String(), hidden.ID) {
			t.Fatal("Published listing unavailable or staging leaked", w.Body.String())
		}
		expected := j.Outputs[0].ID
		if cursor != "" {
			expected = j.Outputs[1].ID
		}
		if !strings.Contains(w.Body.String(), expected) {
			t.Fatal("Publication filtering broke page cursor", w.Body.String())
		}
	}
	for _, f := range j.Outputs {
		if w := call("GET", "/v1/outbox/"+f.ID+"/info?nonce="+randomToken(), nil, "nAlice"); w.Code != 200 {
			t.Fatal(w.Body.String())
		}
		if w := call("GET", "/v1/outbox/"+f.ID+"/content?nonce="+randomToken(), nil, "nAlice"); w.Code != 200 || w.Body.String() != "published-"+f.ID {
			t.Fatal("Published bytes not preserved")
		}
	}
}

func TestDeclaredFileCannotReuseItsOwnInlineResultID(t *testing.T) {
	s, n, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	state.Jobs[j.ID] = j
	result := "collision-" + randomToken()
	f, _ := files.Describe(appResultID(state.ID, j.ID), "result.txt", []byte(result))
	n.outputs[f.ID] = []byte(result)
	n.report = string(mustJSON(appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "ready", Files: []protocol.File{f}, Result: &result}))
	if err := s.refreshApp(p, state, j); err != nil {
		t.Fatal(err)
	}
	if j.Code != "output_id_conflict" || len(j.Outputs) != 0 {
		t.Fatal("Reserved inline result ID reused")
	}
	page, err := s.outbox.List("nAlice", "", 100)
	if err != nil || len(page.Files) != 0 {
		t.Fatal("Rejected duplicate-ID batch staged data", err)
	}
}

func TestInlineResultFailurePersistsOutcomeAndWithholdsWholeBatch(t *testing.T) {
	s, n, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	state.Jobs[j.ID] = j
	result := "expected-result-" + randomToken()
	occupiedBytes := []byte("preserved-conflict-" + randomToken())
	occupied, _ := files.Describe(appResultID(state.ID, j.ID), "result.txt", occupiedBytes)
	if _, err := s.outbox.Put(p.identity, occupied, bytes.NewReader(occupiedBytes)); err != nil {
		t.Fatal(err)
	}
	artifactBytes := []byte("actual-artifact-" + randomToken())
	artifact, _ := files.Describe(randomToken(), "owned.bin", artifactBytes)
	n.outputs[artifact.ID] = artifactBytes
	n.report = string(mustJSON(appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "ready", Result: &result, Files: []protocol.File{artifact}}))
	for i := 0; i < 2; i++ {
		if err := s.refreshApp(p, state, j); err != nil {
			t.Fatal("Inline staging failure escaped without saving its known outcome", err)
		}
	}
	loaded, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j = loaded.Jobs[j.ID]
	if j.State != "completed" || j.Report.Status != "completed" || j.Code != "output_bytes_unverified" || len(j.Outputs) != 0 || len(j.Log) != 1 || j.Log[0].Source != "bridge" {
		t.Fatal("Native outcome, independent issue or deduplicated diagnostic lost")
	}
	diagnostic := j.Log[0].Error
	if diagnostic.Cause != "inline_result_commit_unconfirmed" || diagnostic.Context["transfer_id"] != occupied.ID || diagnostic.Context["error_detail"] == "" || !validDiagnostic(&diagnostic) {
		t.Fatal("Inline failure lacks an exact valid debugging record", diagnostic)
	}
	if _, actual, err := s.outbox.Read(p.identity, occupied.ID); err != nil || !bytes.Equal(actual, occupiedBytes) {
		t.Fatal("Conflict bytes overwritten", err)
	}
	w := call("GET", "/v1/outbox?nonce="+randomToken()+"&cursor=&limit=100", nil, p.identity)
	if w.Code != 200 || strings.Contains(w.Body.String(), artifact.ID) || strings.Contains(w.Body.String(), occupied.ID) {
		t.Fatal("Partial inline batch published", w.Body.String())
	}
	for _, id := range []string{artifact.ID, occupied.ID} {
		if w := call("GET", "/v1/outbox/"+id+"/content?nonce="+randomToken(), nil, p.identity); w.Code != 404 {
			t.Fatal("Unverified batch readable by exact ID", w.Code)
		}
	}
	if w := call("GET", "/v2/jobs/"+j.ID, nil, p.identity); strings.Contains(w.Body.String(), "error_detail") {
		t.Fatal("Debug detail leaked into independent status")
	}
	if err := s.outbox.Remove(p.identity, occupied.ID); err != nil {
		t.Fatal(err)
	}
	n.online = true
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	loaded, err = s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j = loaded.Jobs[j.ID]
	if n.calls != 0 || j.Code != "native_turn_completed" || len(j.Outputs) != 2 || len(j.Log) != 1 {
		t.Fatal("Recovery replayed native work, retained wrong issue or lost diagnostic")
	}
	if _, actual, err := s.outbox.Read(p.identity, j.Outputs[0].ID); err != nil || string(actual) != result {
		t.Fatal("Original inline bytes not recovered", err)
	}
}

func TestOutputErrorDetailsRemainBoundedRedactedAndValid(t *testing.T) {
	secret := randomToken()
	for _, text := range []string{"", "password=" + secret + " Bearer " + secret + " " + strings.Repeat("Ω", 4096)} {
		entry := outputBytesLog(randomToken(), "producer_output_unconfirmed", errors.New(text))
		if !validDiagnostic(&entry.Error) || strings.Contains(entry.Error.Context["error_detail"], secret) || len(entry.Error.Context["error_detail"]) > 1100 {
			t.Fatal("Output error detail leaked credentials, exceeded bounds or broke schema")
		}
	}
}

package backend

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"phonepcbridge/protocol"
)

func TestPreviouslyAcceptedOwnedJobsRemainReadableWithoutRewritingIdentityOrReceipts(t *testing.T) {
	s, native, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	inputs := []appInput{}
	for _, id := range []string{strings.Repeat("a", 32), strings.Repeat("b", 32)} {
		input := appInput{RequestID: id, CreatedAt: 1, Title: "Accepted job", Text: "original " + id, Instructions: "unchanged custom", Files: []protocol.File{}}
		if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
			t.Fatal(w.Body.String())
		}
		inputs = append(inputs, input)
	}
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	expected := map[string][]byte{}
	for index, input := range inputs {
		job := state.Jobs[input.RequestID]
		job.State, job.Code, job.ThreadID, job.TurnID = "failed", "native_failed", "owned-thread-"+input.RequestID, "turn-"+input.RequestID
		job.Created = time.Now().Add(-time.Hour).UnixMilli() + int64(index)
		job.Updated = job.Created + 10
		job.CancelRequested, job.CancelAcknowledged, job.CancelRetired = job.Created+1, job.Created+2, job.Created+3
		job.CancelReason = "user_request"
		job.Log = []appLogEntry{protocolJobLog("accepted_failure_context")}
		expected[job.ID] = mustJSON(job)
	}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(s.cfg.DataDir, "apps", appStateName(p.identity))
	current, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(current, []byte(`"ownedChat"`)) {
		t.Fatal("Current admission/save retained the redundant marker")
	}
	var wire map[string]any
	if json.Unmarshal(current, &wire) != nil {
		t.Fatal("Invalid current state")
	}
	for _, job := range wire["jobs"].(map[string]any) {
		job.(map[string]any)["ownedChat"] = true
	}
	previouslyAccepted := mustJSON(wire)
	if err := os.WriteFile(path, previouslyAccepted, 0600); err != nil {
		t.Fatal(err)
	}
	reopened, err := New(s.cfg, s.native, s.authenticate, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	loaded, err := reopened.loadApp(reopened.getPeer("nAlice"))
	if err != nil {
		t.Fatal("Accepted jobs stopped loading", err)
	}
	peers, err := reopened.storedPeers()
	if err != nil || len(peers) != 1 || peers[0].identity != "nAlice" {
		t.Fatal("Accepted jobs disappeared from background discovery", err)
	}
	for id, want := range expected {
		if !bytes.Equal(mustJSON(loaded.Jobs[id]), want) {
			t.Fatal("Loading changed accepted identity, inputs, state or cancellation receipts", id)
		}
	}
	afterRead, err := os.ReadFile(path)
	if err != nil || !bytes.Equal(afterRead, previouslyAccepted) {
		t.Fatal("Read-only allowance rewrote accepted data", err)
	}
	for _, input := range inputs {
		if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 200 {
			t.Fatal("Original immutable job retry lost its accepted result", w.Body.String())
		}
	}
	if err := reopened.saveApp(loaded); err != nil {
		t.Fatal(err)
	}
	resaved, err := os.ReadFile(path)
	if err != nil || bytes.Contains(resaved, []byte(`"ownedChat"`)) {
		t.Fatal("Ordinary save did not use the current marker-free format", err)
	}
	again, err := reopened.loadApp(reopened.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	for id, want := range expected {
		if !bytes.Equal(mustJSON(again.Jobs[id]), want) {
			t.Fatal("Marker-free save changed accepted work", id)
		}
	}
	if native.calls != 0 {
		t.Fatal("Store preservation replayed accepted native work")
	}
}

func TestStoredOwnershipAllowanceRejectsFalseMalformedAliasesAndOtherUnknownFields(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	input := appInput{RequestID: randomToken(), CreatedAt: 1, Title: "Strict durable record", Text: "original", Files: []protocol.File{}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	p := s.getPeer("nAlice")
	path := filepath.Join(s.cfg.DataDir, "apps", appStateName(p.identity))
	current, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, field := range []string{`"ownedChat":false`, `"ownedChat":null`, `"ownedChat":"true"`, `"ownedChat":1`, `"ownedChat":true,"ownedChat":true`, `"OwnedChat":true`, `"ownedChat":true,"unknownJobField":true`} {
		t.Run(field, func(t *testing.T) {
			var wire map[string]json.RawMessage
			if json.Unmarshal(current, &wire) != nil {
				t.Fatal("Invalid fixture state")
			}
			var jobs map[string]json.RawMessage
			if json.Unmarshal(wire["jobs"], &jobs) != nil {
				t.Fatal("Invalid fixture jobs")
			}
			job := string(jobs[input.RequestID])
			jobs[input.RequestID] = json.RawMessage(job[:len(job)-1] + "," + field + "}")
			wire["jobs"] = mustJSON(jobs)
			if err := os.WriteFile(path, mustJSON(wire), 0600); err != nil {
				t.Fatal(err)
			}
			if _, err := s.loadApp(p); err == nil {
				t.Fatal("Unsupported durable marker or field was accepted")
			}
			if _, err := s.storedPeers(); err == nil {
				t.Fatal("Background discovery bypassed strict durable validation")
			}
		})
	}
	if err := os.WriteFile(path, current, 0600); err != nil {
		t.Fatal(err)
	}
	request := map[string]any{}
	if json.Unmarshal(mustJSON(input), &request) != nil {
		t.Fatal("Invalid job input")
	}
	request["requestId"], request["ownedChat"] = randomToken(), true
	if w := call("POST", "/v2/jobs", request, "nAlice"); w.Code != 400 {
		t.Fatal("Durable-read allowance leaked into HTTP admission", w.Body.String())
	}
}

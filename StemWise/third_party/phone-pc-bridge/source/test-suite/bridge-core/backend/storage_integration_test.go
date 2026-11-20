package backend

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func TestCorruptCachedOutputCannotBePublishedFromCommitMetadata(t *testing.T) {
	s, native, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	state.Jobs[j.ID] = j
	f, _ := files.Describe(randomToken(), "cached.bin", []byte("original"))
	if _, err = s.outbox.Put(p.identity, f, strings.NewReader("original")); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(s.cfg.DataDir, "outgoing", p.identity, f.ID, "content")
	if err = os.WriteFile(path, []byte("modified"), 0600); err != nil {
		t.Fatal(err)
	}
	native.report = string(mustJSON(appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "ready", Files: []protocol.File{f}}))
	if err = s.refreshApp(p, state, j); err != nil {
		t.Fatal(err)
	}
	state, err = s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j = state.Jobs[j.ID]
	if j.State != "completed" || j.Report == nil || j.Report.Status != "completed" || j.Code != "output_bytes_unverified" || len(j.Outputs) != 0 || len(j.Log) == 0 {
		t.Fatal("Cheap metadata authorized corrupt output publication", j)
	}
	if data, err := os.ReadFile(path); err != nil || string(data) != "modified" {
		t.Fatal("Damaged output was overwritten or discarded", err)
	}
}

func TestDisconnectPreservesRegistrationAndUnexpectedOwnedObject(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	id := randomToken()
	directory := filepath.Join(s.cfg.DataDir, "incoming", "nAlice", id)
	if err := os.MkdirAll(directory, 0700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(directory, "unexpected")
	if err := os.WriteFile(path, []byte("investigate"), 0600); err != nil {
		t.Fatal(err)
	}
	if w := call("DELETE", "/v2/app", nil, "nAlice"); w.Code != 503 {
		t.Fatal("Disconnect lost ownership of damaged data", w.Body.String())
	}
	if w := call("GET", "/v2/app", nil, "nAlice"); w.Code != 200 {
		t.Fatal("Registration removed before cleanup completed", w.Body.String())
	}
	select {
	case <-s.retentionChanged:
	default:
		t.Fatal("Failed explicit cleanup did not notify the maintenance owner")
	}
	if _, err := s.retentionPass(time.Now()); err != nil {
		t.Fatal(err)
	}
	if w := call("GET", "/v2/connection", nil, "nAlice"); w.Code != 200 || !strings.Contains(w.Body.String(), `"diagnosticCount":1`) || !strings.Contains(w.Body.String(), id) {
		t.Fatal("Owned damaged object not diagnosable", w.Body.String())
	}
	if w := call("GET", "/v2/connection", nil, "nBob"); w.Code != 200 || strings.Contains(w.Body.String(), id) {
		t.Fatal("Storage diagnostic leaked another app's object", w.Body.String())
	}
	if data, err := os.ReadFile(path); err != nil || string(data) != "investigate" {
		t.Fatal("Damaged bytes not preserved", err)
	}
}

func TestRegistrationPersistsCurrentSchemaDirectly(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	data, err := os.ReadFile(filepath.Join(s.cfg.DataDir, "apps", appStateName("nAlice")))
	if err != nil {
		t.Fatal(err)
	}
	var state appState
	if err = json.Unmarshal(data, &state); err != nil || state.Version != 2 {
		t.Fatal("Registration did not persist current schema", err, state.Version)
	}
	if _, err = s.loadApp(s.getPeer("nAlice")); err != nil {
		t.Fatal("Current registration cannot reopen", err)
	}
}

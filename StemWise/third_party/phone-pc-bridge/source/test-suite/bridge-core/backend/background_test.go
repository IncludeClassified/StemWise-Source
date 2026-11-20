package backend

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"phonepcbridge/files"
	"phonepcbridge/protocol"
	"strings"
	"testing"
	"time"
)

func TestWorkerScanFailureRemainsInConnectionStatusUntilRecovery(t *testing.T) {
	s, native, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	broken := filepath.Join(s.cfg.DataDir, "apps", strings.Repeat("f", 64)+".json")
	if err := os.WriteFile(broken, []byte("invalid fixture record"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := s.workPass(); err == nil {
		t.Fatal("Invalid record scan reported success")
	}
	status := func() map[string]json.RawMessage {
		t.Helper()
		response := call("GET", "/v2/connection", nil, "nAlice")
		var envelope struct {
			Data struct {
				BackgroundOperations map[string]json.RawMessage `json:"backgroundOperations"`
			} `json:"data"`
		}
		if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &envelope) != nil {
			t.Fatal("Connection diagnostics unavailable")
		}
		return envelope.Data.BackgroundOperations
	}
	var observed struct {
		FailureActive    bool   `json:"failureActive"`
		LastFailureAt    int64  `json:"lastFailureAt"`
		LastFailureCause string `json:"lastFailureCause"`
		LastSuccessAt    int64  `json:"lastSuccessAt"`
		RetryState       string `json:"retryState"`
		RetryDelayMs     int64  `json:"retryDelayMs"`
	}
	if json.Unmarshal(status()["job_worker"], &observed) != nil || !observed.FailureActive || observed.LastFailureAt <= 0 || observed.LastFailureCause == "" || observed.RetryState != "pending" || observed.RetryDelayMs != 10000 {
		t.Fatal("Worker scan failure disappeared from local connection diagnostics")
	}
	if err := os.Remove(broken); err != nil {
		t.Fatal(err)
	}
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	if json.Unmarshal(status()["job_worker"], &observed) != nil || observed.FailureActive || observed.LastSuccessAt <= 0 || observed.LastFailureAt <= 0 || observed.LastFailureCause == "" || observed.RetryState != "waiting" {
		t.Fatal("Successful pass failed to clear active failure while retaining latest observation")
	}
	if native.calls != 0 {
		t.Fatal("Diagnostics created native work")
	}
}

func TestWorkerSaveFailureIsObservableWithoutInventingAcceptedWork(t *testing.T) {
	s, native, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	base := time.Now().UnixMilli()
	first, prompt := "", ""
	for index := 0; index < 128; index++ {
		id := randomToken()
		if first == "" {
			first = id
		}
		input := appInput{RequestID: id, CreatedAt: base + int64(index), Title: "Valid capacity fixture", Text: strings.Repeat("x", 65536), Files: []protocol.File{}}
		state.Jobs[id] = &appJob{ID: id, Input: input, Fingerprint: appHash(input), State: "queued", Code: "awaiting_pc", Developer: "bridge_job_" + id, Outputs: []protocol.File{}, Created: input.CreatedAt, Updated: input.CreatedAt}
		prompt = randomToken()
		state.Prompts[prompt] = appPrompt{Title: "Valid prompt", Text: strings.Repeat("p", 65536)}
	}
	// Reserve all but one byte of the actual serialized record; each item stays valid.
	excess := len(mustJSON(state)) - (maxAppRecordBytes - 1)
	for id, value := range state.Prompts {
		if excess <= 0 {
			break
		}
		cut := len(value.Text) - 1
		if cut > excess {
			cut = excess
		}
		value.Text = value.Text[:len(value.Text)-cut]
		state.Prompts[id] = value
		excess -= cut
	}
	if excess > 0 || len(mustJSON(state)) != maxAppRecordBytes-1 {
		t.Fatal("Capacity fixture did not reach real save boundary")
	}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(s.cfg.DataDir, "apps", appStateName(p.identity))
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	native.online = true
	if err := s.workPass(); err == nil {
		t.Fatal("Growing a full record became saved work")
	}
	after, err := os.ReadFile(path)
	if err != nil || !bytes.Equal(before, after) || native.calls != 0 {
		t.Fatal("Failed reservation save changed durable work or submitted a prompt", err)
	}
	status := s.jobPassStatus.Snapshot()
	if !status.FailureActive || !strings.Contains(status.LastFailureCause, "capacity") {
		t.Fatal("Save failure was discarded", status)
	}
	for id, value := range state.Prompts {
		if len(value.Text) > 1024 {
			prompt = id
			break
		}
	}
	value := state.Prompts[prompt]
	value.Text = "keep"
	state.Prompts[prompt] = value
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	latest, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	if latest.Jobs[first].TurnID != "fixture-turn" || native.input.JobID != first || native.calls != 1 || s.jobPassStatus.Snapshot().FailureActive {
		t.Fatal("Recovered pass lost the original acknowledgment or duplicated submission", native.calls)
	}
}

func TestRetentionRemovalFailureIsObservableAndClearsAfterRepair(t *testing.T) {
	s, native, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	data := []byte("owned cleanup bytes")
	f, _ := files.Describe(randomToken(), "owned.bin", data)
	if _, err := s.outbox.Put(p.identity, f, bytes.NewReader(data)); err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	j.Outputs = []protocol.File{f}
	deliveredRetentionFixture(state, j, time.Now())
	state.Jobs[j.ID] = j
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	object := filepath.Join(s.cfg.DataDir, "outgoing", p.identity, f.ID)
	if err := os.WriteFile(filepath.Join(object, "unexpected"), []byte("owned fault fixture"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := s.retentionPass(time.Now()); err == nil {
		t.Fatal("Refused removal of damaged bytes became successful cleanup")
	}
	failed := s.retentionPassStatus.Snapshot()
	response := call("GET", "/v2/connection", nil, p.identity)
	if !failed.FailureActive || failed.LastFailureCause == "" || failed.RetryDelayMs != time.Minute.Milliseconds() || response.Code != 200 || !bytes.Contains(response.Body.Bytes(), []byte(`"app_retention"`)) {
		t.Fatal("Removal failure missing from existing status")
	}
	// Connection observation retries local cleanup and records that fresh failure.
	failed = s.retentionPassStatus.Snapshot()
	if err := os.Remove(filepath.Join(object, "unexpected")); err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-appRetention - time.Second)
	if err := os.Chtimes(object, old, old); err != nil {
		t.Fatal(err)
	}
	if _, err := s.retentionPass(time.Now()); err != nil {
		t.Fatal(err)
	}
	if status := s.retentionPassStatus.Snapshot(); status.FailureActive || status.LastSuccessAt <= 0 || status.LastFailureAt != failed.LastFailureAt {
		t.Fatal("Successful cleanup did not settle its failure", status)
	}
	if _, err := s.outbox.Info(p.identity, f.ID); !os.IsNotExist(err) || native.calls != 0 {
		t.Fatal("Recovery retained orphan bytes or created native work", err)
	}
}

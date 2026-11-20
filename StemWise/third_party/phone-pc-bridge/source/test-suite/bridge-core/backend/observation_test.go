package backend

import (
	"context"
	"encoding/json"
	"errors"
	"net/http/httptest"
	"phonepcbridge/internal/checksync"
	"strings"
	"sync"
	"testing"
	"time"
)

type observedNativeFixture struct {
	*nativeFixture
	reports          map[string]json.RawMessage
	failure          string
	started, release chan struct{}
	once             sync.Once
}

func (n *observedNativeFixture) History(ctx context.Context, thread, cursor string, limit int) (json.RawMessage, error) {
	if n.started != nil {
		n.once.Do(func() { close(n.started) })
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-n.release:
		}
	}
	if thread == n.failure {
		return nil, errors.New("owned_history_unavailable")
	}
	return mustJSON(map[string]any{"data": []json.RawMessage{n.reports[thread]}, "nextCursor": nil}), nil
}

func observationFixture(t *testing.T, peers ...string) (*Service, *observedNativeFixture, func(string, string, any, string) *httptest.ResponseRecorder) {
	t.Helper()
	s, base, call := fixtureAPI(t)
	n := &observedNativeFixture{nativeFixture: base, reports: map[string]json.RawMessage{}}
	n.online = true
	s.native = n
	for _, peer := range peers {
		registerFixture(t, call, peer)
		state, err := s.loadApp(s.getPeer(peer))
		if err != nil {
			t.Fatal(err)
		}
		for i := 0; i < 2; i++ {
			j := ownedOutputJob(randomToken())
			j.ThreadID = "owned-thread-" + randomToken()
			state.Jobs[j.ID] = j
			result := "actual-observed-" + randomToken()
			report := appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "ready", Result: &result}
			n.reports[j.ThreadID] = mustJSON(map[string]any{"id": j.TurnID, "status": "completed", "bridgeTerminalCommitted": true, "items": []any{map[string]any{"type": "agentMessage", "text": string(mustJSON(report))}}})
			if n.failure == "" {
				n.failure = j.ThreadID
			}
		}
		if err := s.saveApp(state); err != nil {
			t.Fatal(err)
		}
	}
	return s, n, call
}

func TestFailedJobObservationDoesNotStopOtherJobs(t *testing.T) {
	s, n, _ := observationFixture(t, "nAlice", "nBob")
	for i := 0; i < 2; i++ {
		if err := s.workPass(); err != nil {
			t.Fatal(err)
		}
	}
	for _, peer := range []string{"nAlice", "nBob"} {
		state, err := s.loadApp(s.getPeer(peer))
		if err != nil {
			t.Fatal(err)
		}
		for _, j := range state.Jobs {
			if j.ThreadID == n.failure {
				if j.State != "submitted" || j.Code != "job_observation_unavailable" || len(j.Outputs) != 0 || len(j.Log) != 1 || j.Log[0].Source != "bridge" || j.Log[0].Error.Context["turn_id"] != j.TurnID || !validDiagnostic(&j.Log[0].Error) {
					t.Fatal("Failed observation changed native status, lacks durable debug detail or duplicated logs")
				}
			} else if j.State != "completed" || len(j.Outputs) != 1 {
				t.Fatal("Failed history prevented another job from completing")
			}
		}
	}
	if n.calls != 0 {
		t.Fatal("Observation replayed a native turn")
	}
	n.failure = ""
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	for _, j := range state.Jobs {
		if j.State != "completed" || len(j.Outputs) != 1 {
			t.Fatal("Original acknowledged job did not recover after history returned")
		}
	}
	if n.calls != 0 {
		t.Fatal("History recovery replayed a native turn")
	}
}

func TestSlowAcknowledgedObservationKeepsSavedReadsAndConcurrentChanges(t *testing.T) {
	s, n, call := observationFixture(t, "nAlice")
	n.started, n.release = make(chan struct{}), make(chan struct{})
	done := make(chan error, 1)
	retired := make(chan struct{})
	var release sync.Once
	t.Cleanup(func() {
		release.Do(func() { close(n.release) })
		s.cancel()
		checksync.Join(t, retired, time.Second, "history observation")
	})
	go func() { defer close(retired); done <- s.workPass() }()
	select {
	case <-n.started:
	case <-time.After(time.Second):
		t.Fatal("History observation never started")
	}
	read := make(chan string, 1)
	readRetired := make(chan struct{})
	t.Cleanup(func() {
		release.Do(func() { close(n.release) })
		checksync.Join(t, readRetired, time.Second, "saved job read")
	})
	go func() { defer close(readRetired); read <- call("GET", "/v2/jobs", nil, "nAlice").Body.String() }()
	select {
	case body := <-read:
		if !strings.Contains(body, `"state":"submitted"`) {
			t.Fatal("Saved status changed before observation", body)
		}
	case <-time.After(time.Second):
		t.Fatal("PC history wait blocked saved-state API")
	}
	prompt := randomToken()
	if w := call("PUT", "/v2/prompts/"+prompt, appPrompt{Title: "Concurrent", Text: "preserve"}, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	for _, j := range state.Jobs {
		j.Code = "interrupt_requested"
	}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	release.Do(func() { close(n.release) })
	if err := checksync.Receive(t, done, time.Second, "released history observation"); err != nil {
		t.Fatal(err)
	}
	state, err = s.loadApp(s.getPeer("nAlice"))
	if err != nil || state.Prompts[prompt].Text != "preserve" {
		t.Fatal("Observation erased concurrent app settings", err)
	}
	preserved := 0
	for _, j := range state.Jobs {
		if j.Code == "interrupt_requested" {
			preserved++
		}
	}
	if preserved == 0 || n.calls != 0 {
		t.Fatal("Stale observation overwrote concurrent job change or repeated a turn")
	}
}

func TestRemovedJobObservationCannotRecreateRecordsOrOutputBytes(t *testing.T) {
	s, n, _ := observationFixture(t, "nAlice")
	n.failure = ""
	n.started, n.release = make(chan struct{}), make(chan struct{})
	done := make(chan error, 1)
	retired := make(chan struct{})
	var release sync.Once
	t.Cleanup(func() {
		release.Do(func() { close(n.release) })
		s.cancel()
		checksync.Join(t, retired, time.Second, "removed-job observation")
	})
	go func() { defer close(retired); done <- s.workPass() }()
	select {
	case <-n.started:
	case <-time.After(time.Second):
		t.Fatal("History observation never started")
	}
	p := s.getPeer("nAlice")
	p.appMu.Lock()
	state, err := s.loadApp(p)
	if err == nil {
		removed := make(map[string]*appJob, len(state.Jobs))
		for id, job := range state.Jobs {
			removed[id] = job
		}
		err = s.removeAppJobs(state, removed)
	}
	p.appMu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	release.Do(func() { close(n.release) })
	if err := checksync.Receive(t, done, time.Second, "released removed-job observation"); err != nil {
		t.Fatal(err)
	}
	state, err = s.loadApp(p)
	if err != nil || len(state.Jobs) != 0 {
		t.Fatal("Stale history resurrected removed records", err)
	}
	page, err := s.outbox.List(p.identity, "", 100)
	if err != nil || len(page.Files) != 0 || n.calls != 0 {
		t.Fatal("Removed observation staged bytes or resubmitted native work", err)
	}
}

package backend

import (
	"bytes"
	"context"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"phonepcbridge/protocol"
)

func TestDeletedTerminalRequestCannotExecuteAgainWhileDeletionReceiptIsRetained(t *testing.T) {
	s, n, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	n.online = true
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Deleted request", Text: "original-" + randomToken(), Files: []protocol.File{}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	output := state.Jobs[input.RequestID].Outputs[0]
	if w := call("DELETE", "/v2/jobs/"+input.RequestID, nil, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	if w := call("GET", "/v2/jobs/"+input.RequestID, nil, "nAlice"); w.Code != 410 || !strings.Contains(w.Body.String(), `"code":"request_deleted"`) {
		t.Fatal("Deleted job contents remain accessible")
	}
	if _, err := s.outbox.Info("nAlice", output.ID); !os.IsNotExist(err) {
		t.Fatal("Deleted transfer bytes remain", err)
	}
	for _, changed := range []bool{false, true} {
		attempt := input
		if changed {
			attempt.Text = "replacement"
		}
		w := call("POST", "/v2/jobs", attempt, "nAlice")
		if w.Code != 410 || !strings.Contains(w.Body.String(), `"code":"request_deleted"`) {
			t.Fatal("Deleted request identity was accepted or lost", w.Body.String())
		}
	}
	resumed, err := New(s.cfg, n, func(_ context.Context, remote string) (string, error) { return remote, nil }, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer resumed.Close()
	r := httptest.NewRequest("POST", "/v2/jobs", bytes.NewReader(mustJSON(input)))
	r.RemoteAddr = "nAlice"
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	resumed.ServeHTTP(w, r)
	if w.Code != 410 {
		t.Fatal("Service reopen forgot deleted request identity", w.Body.String())
	}
	if err := resumed.workPass(); err != nil {
		t.Fatal(err)
	}
	if n.calls != 1 {
		t.Fatal("Deleted request was submitted a second time", n.calls)
	}
	state, err = resumed.loadApp(resumed.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	if len(state.Jobs) != 0 || len(state.DeletedRequests) != 1 || bytes.Contains(mustJSON(state), []byte(input.Text)) {
		t.Fatal("Deletion receipt retained original prompt data or lost its reservation")
	}
}

func TestDeletionReceiptsExpireWithoutReadRenewal(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	id := randomToken()
	expires := now.Add(2 * time.Minute).UnixMilli()
	state.DeletedRequests = map[string]int64{id: expires}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	if w := call("DELETE", "/v2/jobs/"+id, nil, "nAlice"); w.Code != 200 {
		t.Fatal("Lost deletion acknowledgment could not be read idempotently", w.Body.String())
	}
	if delay, err := s.retentionPass(now); err != nil || delay > 2*time.Minute {
		t.Fatal("Receipt expiry was omitted from automatic retention scheduling", delay, err)
	}
	state, err = s.loadApp(s.getPeer("nAlice"))
	if err != nil || state.DeletedRequests[id] != expires {
		t.Fatal("Observation renewed deletion receipt", err)
	}
	if _, err := s.retentionPass(time.UnixMilli(expires)); err != nil {
		t.Fatal(err)
	}
	state, err = s.loadApp(s.getPeer("nAlice"))
	if err != nil || len(state.DeletedRequests) != 0 {
		t.Fatal("Expired deletion identity retained", err)
	}
}

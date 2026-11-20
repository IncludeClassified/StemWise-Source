package backend

import (
	"context"
	"strconv"
	"strings"
	"testing"
	"time"

	"phonepcbridge/protocol"
)

type queuedCancellationNative struct {
	*nativeFixture
	adapterCalls int
}

func (n *queuedCancellationNative) Call(ctx context.Context, request protocol.AdapterRequest) (protocol.AdapterReply, error) {
	n.adapterCalls++
	return n.nativeFixture.Call(ctx, request)
}

func TestQueuedCancellationIsDurableIdempotentAndNeverSubmits(t *testing.T) {
	s, n, call := fixtureAPI(t)
	guard := &queuedCancellationNative{nativeFixture: n}
	s.native = guard
	registerFixture(t, call, "nAlice")
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Queued cancellation", Text: "original-" + randomToken(), Files: []protocol.File{}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	for i := 0; i < 2; i++ {
		if w := call("POST", "/v2/jobs/"+input.RequestID+"/cancel", nil, "nAlice"); w.Code != 200 {
			t.Fatal("Unsent job cannot be cancelled idempotently", w.Body.String())
		}
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	j := state.Jobs[input.RequestID]
	if j.State != "cancelled" || j.Code != "cancelled_before_submission" || j.TurnID != "" || j.Report != nil || len(j.Outputs) != 0 || j.Input.Text != input.Text || j.CancelRequested <= 0 || j.CancelRetired != j.CancelRequested || j.CancelAcknowledged != 0 {
		t.Fatal("Unsent cancellation fabricated a native outcome or changed original input")
	}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	resumed, err := New(s.cfg, guard, func(_ context.Context, remote string) (string, error) { return remote, nil }, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer resumed.Close()
	n.online = true
	if err := resumed.workPass(); err != nil {
		t.Fatal(err)
	}
	if n.calls != 0 || guard.adapterCalls != 0 {
		t.Fatal("Cancelled queued job reached native creation, selection or submission")
	}
	current, err := resumed.loadApp(resumed.getPeer("nAlice"))
	if err != nil || current.Jobs[input.RequestID].State != "cancelled" || current.Jobs[input.RequestID].CancelRetired != j.CancelRetired || current.Jobs[input.RequestID].TurnID != "" {
		t.Fatal("Queued retirement receipt was not durable across worker restart", err)
	}
	if w := call("GET", "/v2/jobs/"+input.RequestID, nil, "nAlice"); w.Code != 200 || !strings.Contains(w.Body.String(), `"cancelRetired":`+strconv.FormatInt(j.CancelRetired, 10)) {
		t.Fatal("Saved job poll did not expose queued retirement receipt", w.Body.String())
	}
	if w := call("DELETE", "/v2/jobs/"+input.RequestID, nil, "nAlice"); w.Code != 200 {
		t.Fatal("Cancelled queued job cannot be removed", w.Body.String())
	}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 410 {
		t.Fatal("Deleted cancellation lost its replay protection", w.Body.String())
	}
}

func TestUncertainSubmissionCannotClaimQueuedCancellation(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	for _, phase := range []string{"unknown", "dispatching"} {
		j := ownedOutputJob(randomToken())
		j.State = phase
		j.TurnID = ""
		state.Jobs[j.ID] = j
		if err := s.saveApp(state); err != nil {
			t.Fatal(err)
		}
		if w := call("POST", "/v2/jobs/"+j.ID+"/cancel", nil, "nAlice"); w.Code != 202 {
			t.Fatal("Uncertain native submission was falsely cancelled", w.Body.String())
		}
		latest, err := s.loadApp(s.getPeer("nAlice"))
		if err != nil || latest.Jobs[j.ID].State != "unknown" || latest.Jobs[j.ID].CancelRequested == 0 {
			t.Fatal("Cancellation intent was not retained independently from uncertain native state", err)
		}
		state = latest
	}
}

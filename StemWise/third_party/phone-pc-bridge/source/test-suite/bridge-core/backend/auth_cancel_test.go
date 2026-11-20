package backend

import (
	"context"
	"sync"
	"testing"
	"time"

	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

type heldAuthRefusalNative struct {
	*authNativeFixture
	entered chan struct{}
	release chan struct{}
}

func (n *heldAuthRefusalNative) Call(ctx context.Context, r protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if r.Operation == "send" {
		close(n.entered)
		<-n.release // An already received exact refusal may arrive after caller cancellation.
	}
	return n.authNativeFixture.Call(ctx, r)
}

func TestCancellationBeforeExactAuthRefusalNeverRequeuesOrExecutes(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	n := &heldAuthRefusalNative{authNativeFixture: &authNativeFixture{nativeFixture: base, status: authFixtureStatus("ready", "native_auth_ready"), refuseSend: true}, entered: make(chan struct{}), release: make(chan struct{})}
	s.native = n
	registerFixture(t, call, "nAlice")
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Cancel auth refusal race", Text: "accepted immutable context", Files: []protocol.File{}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	var once sync.Once
	release := func() { once.Do(func() { close(n.release) }) }
	done := make(chan struct{})
	var workErr error
	t.Cleanup(func() { release(); checksync.Join(t, done, 3*time.Second, "auth-refused worker") })
	go func() { defer close(done); workErr = s.workPeer(s.getPeer("nAlice"), nil) }()
	checksync.Receive(t, n.entered, 3*time.Second, "held exact auth refusal")
	if w := boundedControlCall(t, call, release, "POST", "/v2/jobs/"+input.RequestID+"/cancel", nil); w.Code != 202 {
		t.Fatal(w.Code, w.Body.String())
	}
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil || state.Jobs[input.RequestID].CancelRequested == 0 || state.Jobs[input.RequestID].CancelRetired != 0 {
		t.Fatal("Missing durable pending cancellation", err)
	}
	created := state.Jobs[input.RequestID].Created
	release()
	checksync.Join(t, done, 3*time.Second, "original auth refusal")
	if workErr != nil {
		t.Fatal(workErr)
	}
	state, err = s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := state.Jobs[input.RequestID]
	if j.State != "cancelled" || j.Code != "cancelled_before_submission" || j.CancelRetired == 0 || j.TurnID != "" || j.Created != created || j.Fingerprint != appHash(input) {
		t.Fatal("Exact no-send refusal resurrected cancelled work", j)
	}
	// Recover the coordinator with the same durable state; authentication restoration may not resend.
	n.refuseSend = false
	s.native = n.authNativeFixture
	resumed, err := New(s.cfg, n.authNativeFixture, func(_ context.Context, remote string) (string, error) { return remote, nil }, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer resumed.Close()
	for range 2 {
		if err := resumed.workPass(); err != nil {
			t.Fatal(err)
		}
	}
	state, err = resumed.loadApp(resumed.getPeer("nAlice"))
	if err != nil || state.Jobs[input.RequestID].State != "cancelled" || state.Jobs[input.RequestID].ChatState != "deleted" || n.creations != 1 || n.sendAttempts != 1 || base.calls != 0 {
		t.Fatal("Cancelled original replayed or owned chat failed to retire", err, n.creations, n.sendAttempts, base.calls)
	}
}

func TestRecoveredCancelledQueueCannotDispatch(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	j.State, j.Code, j.TurnID = "queued", "native_auth_change_pending", ""
	j.CancelRequested, j.CancelReason = time.Now().UnixMilli(), "user_request"
	state.Jobs[j.ID] = j
	if err = s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	if err = s.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err = s.loadApp(p)
	if err != nil || state.Jobs[j.ID].State != "cancelled" || state.Jobs[j.ID].CancelRetired == 0 || base.calls != 0 {
		t.Fatal("Recovered cancellation queue dispatched native work", err, base.calls)
	}
}

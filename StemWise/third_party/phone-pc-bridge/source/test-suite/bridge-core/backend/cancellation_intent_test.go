package backend

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"testing"
	"time"

	"phonepcbridge/protocol"
)

type cancellationNative struct {
	*lostAcknowledgment
	interrupts          int
	failInterrupt       bool
	terminal            bool
	uncommittedTerminal bool
	target              protocol.AdapterRequest
	retireCalls         int
	failRetire          bool
	mismatchRetire      bool
	retireTarget        protocol.AdapterRequest
}

func (n *cancellationNative) Call(ctx context.Context, request protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if request.Operation == "retire" {
		n.retireCalls++
		n.retireTarget = request
		if n.failRetire {
			return protocol.AdapterReply{}, errors.New("owned terminal cleanup unavailable")
		}
		thread := request.ThreadID
		if n.mismatchRetire {
			thread = "another-thread"
		}
		return protocol.AdapterReply{State: "ok", Result: mustJSON(map[string]any{"threadId": thread, "turnId": request.TurnID, "terminalsRetired": true})}, nil
	}
	if request.Operation != "interrupt" {
		return n.lostAcknowledgment.Call(ctx, request)
	}
	n.interrupts++
	n.target = request
	if n.failInterrupt {
		return protocol.AdapterReply{}, errors.New("interrupt response lost")
	}
	return protocol.AdapterReply{State: "ok", Result: json.RawMessage(`{}`)}, nil
}

func (n *cancellationNative) History(ctx context.Context, thread, cursor string, limit int) (json.RawMessage, error) {
	if n.broken {
		return nil, errors.New("native history unavailable")
	}
	var record map[string]json.RawMessage
	if err := json.Unmarshal(n.record, &record); err != nil {
		return nil, err
	}
	var items []map[string]json.RawMessage
	if err := json.Unmarshal(record["items"], &items); err != nil {
		return nil, err
	}
	// Preserve the actual immutable native input used by reconciliation, without a completed report.
	record["items"] = mustJSON(items[:1])
	record["status"] = mustJSON("inProgress")
	record["bridgeTerminalCommitted"] = mustJSON(false)
	if n.terminal {
		record["status"] = mustJSON("interrupted")
		record["bridgeTerminalCommitted"] = mustJSON(true)
	} else if n.uncommittedTerminal {
		record["status"] = mustJSON("interrupted")
	}
	return mustJSON(map[string]any{"data": []any{record}, "nextCursor": nil}), nil
}

func TestCancellationIntentSurvivesUnknownSendAndRetriesOnlyExactTurn(t *testing.T) {
	s, base, call := fixtureAPI(t)
	n := &cancellationNative{lostAcknowledgment: &lostAcknowledgment{nativeFixture: base, broken: true}}
	s.native = n
	n.online = true
	registerFixture(t, call, "nAlice")
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Lost send cancellation", Text: "original-" + randomToken(), Files: []protocol.File{}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	if w := call("POST", "/v2/jobs/"+input.RequestID+"/cancel", nil, "nAlice"); w.Code != 202 {
		t.Fatal("Cancellation of uncertain acceptance must remain durable and pending", w.Code, w.Body.String())
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	var saved map[string]any
	if err := json.Unmarshal(mustJSON(state.Jobs[input.RequestID]), &saved); err != nil {
		t.Fatal(err)
	}
	requested, _ := saved["cancelRequested"].(float64)
	if requested <= 0 || state.Jobs[input.RequestID].State != "unknown" || n.interrupts != 0 {
		t.Fatal("Unknown submission was falsely cancelled or intent was not stored")
	}
	resumed, err := New(s.cfg, n, func(_ context.Context, remote string) (string, error) { return remote, nil }, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer resumed.Close()
	n.online = false
	if err := resumed.workPass(); err != nil {
		t.Fatal(err)
	}
	if n.calls != 1 || n.interrupts != 0 {
		t.Fatal("Disconnected cancellation replayed or invented a turn")
	}
	n.online, n.broken, n.failInterrupt = true, false, true
	if err := resumed.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err = resumed.loadApp(resumed.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	j := state.Jobs[input.RequestID]
	if j.State != "submitted" || n.interrupts != 1 || n.target.ThreadID != j.ThreadID || n.target.TurnID != "fixture-turn" || len(j.Log) == 0 || j.Log[len(j.Log)-1].Error.Code != "interrupt_unconfirmed" {
		t.Fatal("Recovered cancellation failed to target original turn with diagnostics")
	}
	if diagnostic := &j.Log[len(j.Log)-1].Error; !validDiagnostic(diagnostic) || !*diagnostic.Retryable {
		t.Fatal("Retrying interruption diagnostic contradicts saved retry policy")
	}
	n.failInterrupt = false
	if err := resumed.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err = resumed.loadApp(resumed.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	j = state.Jobs[input.RequestID]
	if err := json.Unmarshal(mustJSON(j), &saved); err != nil {
		t.Fatal(err)
	}
	if saved["cancelRequested"] != requested || saved["cancelAcknowledged"] == nil || j.State != "submitted" || len(j.Outputs) != 0 || n.interrupts != 2 {
		t.Fatal("Interrupt acknowledgment fabricated completion or lost durable intent")
	}
	if err := resumed.workPass(); err != nil {
		t.Fatal(err)
	}
	if n.interrupts != 2 || n.calls != 1 {
		t.Fatal("Acknowledged interrupt or original send was replayed")
	}
	n.uncommittedTerminal = true
	if err := resumed.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err = resumed.loadApp(resumed.getPeer("nAlice"))
	if err != nil || state.Jobs[input.RequestID].State != "submitted" || n.interrupts != 2 {
		t.Fatal("Uncommitted native interruption was treated as stopped", err)
	}
	n.terminal = true
	n.failRetire = true
	if err := resumed.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err = resumed.loadApp(resumed.getPeer("nAlice"))
	if err != nil || state.Jobs[input.RequestID].State != "submitted" || n.calls != 1 || n.interrupts != 2 || n.retireCalls != 1 {
		t.Fatal("Native turn termination falsely proved owned background processes retired", err)
	}
	last := state.Jobs[input.RequestID].Log[len(state.Jobs[input.RequestID].Log)-1]
	if last.Error.Code != "cancellation_retirement_unconfirmed" || !validDiagnostic(&last.Error) || state.Jobs[input.RequestID].ChatState == "deleted" {
		t.Fatal("Pending retirement lost valid investigation context or deleted its chat")
	}
	n.failRetire, n.mismatchRetire = false, true
	if err := resumed.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err = resumed.loadApp(resumed.getPeer("nAlice"))
	if err != nil || state.Jobs[input.RequestID].State != "submitted" || n.retireCalls != 2 {
		t.Fatal("Unrelated retirement receipt settled cancellation", err)
	}
	n.mismatchRetire = false
	if err := resumed.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err = resumed.loadApp(resumed.getPeer("nAlice"))
	if err != nil || state.Jobs[input.RequestID].State != "interrupted" || n.calls != 1 || n.interrupts != 2 || n.retireCalls != 3 {
		t.Fatal("Exact native retirement evidence was not used", err)
	}
	j = state.Jobs[input.RequestID]
	if n.retireTarget.ThreadID != j.ThreadID || n.retireTarget.TurnID != j.TurnID || n.retireTarget.RequestID != j.ID || n.retireTarget.AppID != state.ID || n.retireTarget.CreatedAt != j.Created || n.retireTarget.Name != j.Developer || n.retireTarget.Model != j.Model {
		t.Fatal("Retirement lost exact accepted creation ownership")
	}
	if j.CancelRetired <= 0 {
		t.Fatal("Exact retirement was not durably recorded")
	}
}

type heldCancellationRetirement struct {
	*cancellationNative
	entered chan struct{}
	release chan struct{}
}

func (n *heldCancellationRetirement) Call(ctx context.Context, request protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if request.Operation == "retire" {
		close(n.entered)
		select {
		case <-n.release:
		case <-ctx.Done():
			return protocol.AdapterReply{}, ctx.Err()
		}
	}
	return n.cancellationNative.Call(ctx, request)
}

func TestRetirementWaitLeavesSavedStatusResponsiveAndFencesNewerResult(t *testing.T) {
	s, p, j, file, call := outputHashFixture(t)
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	live := state.Jobs[j.ID]
	live.State, live.Report, live.Outputs = "submitted", nil, []protocol.File{}
	live.CancelRequested, live.CancelAcknowledged = time.Now().UnixMilli(), time.Now().UnixMilli()
	live.CancelReason = "user_request"
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	n := &heldCancellationRetirement{cancellationNative: &cancellationNative{lostAcknowledgment: &lostAcknowledgment{nativeFixture: s.native.(*nativeFixture), record: mustJSON(map[string]any{"id": j.TurnID, "items": []any{map[string]any{"type": "userMessage", "content": []any{}}}})}, terminal: true}, entered: make(chan struct{}), release: make(chan struct{})}
	n.online = true
	s.native = n
	var once sync.Once
	unblock := func() { once.Do(func() { close(n.release) }) }
	done := make(chan error, 1)
	finished := false
	t.Cleanup(func() {
		unblock()
		if !finished {
			select {
			case <-done:
			case <-time.After(3 * time.Second):
				t.Error("Retirement worker remained live")
			}
		}
	})
	go func() { done <- s.workPeer(p, nil) }()
	select {
	case <-n.entered:
	case <-time.After(3 * time.Second):
		t.Fatal("Native retirement did not start")
	}
	if w := boundedControlCall(t, call, unblock, "GET", "/v2/jobs/"+j.ID, nil); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	p.appMu.Lock()
	latest, err := s.loadApp(p)
	if err == nil {
		current := latest.Jobs[j.ID]
		current.State, current.Code, current.Report, current.Outputs = "completed", "newer_completed_result", j.Report, []protocol.File{file}
		current.CancelRetired = time.Now().UnixMilli()
		err = s.saveApp(latest)
	}
	p.appMu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	unblock()
	select {
	case err = <-done:
		finished = true
	case <-time.After(3 * time.Second):
		t.Fatal("Retirement did not settle")
	}
	if err != nil {
		t.Fatal(err)
	}
	latest, err = s.loadApp(p)
	if err != nil || latest.Jobs[j.ID].State != "completed" || latest.Jobs[j.ID].Code != "newer_completed_result" || len(latest.Jobs[j.ID].Outputs) != 1 || latest.Jobs[j.ID].Outputs[0] != file {
		t.Fatal("Stale retirement overwrote a newer canonical result", err)
	}
}

func TestCancelAlreadyCompletedPreservesCanonicalOutputs(t *testing.T) {
	s, p, j, file, call := outputHashFixture(t)
	n := &cancellationNative{lostAcknowledgment: &lostAcknowledgment{nativeFixture: s.native.(*nativeFixture)}}
	s.native = n
	before := string(mustJSON(j))
	if w := call("POST", "/v2/jobs/"+j.ID+"/cancel", nil, "nAlice"); w.Code != 200 {
		t.Fatal("Terminal race must expose canonical result", w.Code, w.Body.String())
	}
	state, err := s.loadApp(p)
	if err != nil || string(mustJSON(state.Jobs[j.ID])) != before || n.interrupts != 0 {
		t.Fatal("Completed canonical output changed during cancellation", err)
	}
	actual, data, err := s.outbox.Read(p.identity, file.ID)
	if err != nil || actual != file || string(data) != "exact verified output" {
		t.Fatal("Completed canonical bytes changed during cancellation", err)
	}
}

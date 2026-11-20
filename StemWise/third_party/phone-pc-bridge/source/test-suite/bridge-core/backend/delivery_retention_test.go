package backend

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

type ownershipHistoryNative struct {
	*nativeFixture
	turn json.RawMessage
}

func (n *ownershipHistoryNative) History(context.Context, string, string, int) (json.RawMessage, error) {
	return mustJSON(map[string]any{"data": []json.RawMessage{n.turn}, "nextCursor": nil}), nil
}

func TestUnownedOutputClaimPreservesIndependentNativeCompletion(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	data := []byte("generic cache is not an owned job output")
	f, _ := files.Describe(randomToken(), "unowned.bin", data)
	base.outputs[f.ID] = data
	report := appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "output_ready", Files: []protocol.File{f}}
	s.native = &ownershipHistoryNative{nativeFixture: base, turn: mustJSON(map[string]any{"id": j.TurnID, "status": "completed", "bridgeTerminalCommitted": true, "items": []any{map[string]any{"type": "agentMessage", "text": string(mustJSON(report)), "bridgeOutputOwnershipUnconfirmed": true}}})}
	state.Jobs[j.ID] = j
	if err = s.refreshApp(p, state, j); err != nil {
		t.Fatal(err)
	}
	state, err = s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j = state.Jobs[j.ID]
	if j.State != "completed" || j.Code != "job_output_ownership_unconfirmed" || len(j.Outputs) != 0 || j.Report != nil || len(j.Log) != 1 || j.Log[0].Error.Cause != "job_output_ownership_unconfirmed" {
		t.Fatal("Ownership failure hid native completion or published output", j)
	}
	if _, err = s.retentionPass(time.Now().Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	if w := call("GET", "/v2/jobs/"+j.ID, nil, p.identity); w.Code != 200 {
		t.Fatal("Unacknowledged ownership error lost diagnostic recovery", w.Body.String())
	}
}

type discardReceiptNative struct {
	*nativeFixture
	wrongOwner    bool
	deleted       bool
	accepted      int
	beforeReceipt func()
	requests      []protocol.AdapterRequest
}

func (n *discardReceiptNative) Call(ctx context.Context, r protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if r.Operation != "discard" {
		return n.nativeFixture.Call(ctx, r)
	}
	n.accepted++
	n.requests = append(n.requests, r)
	if n.beforeReceipt != nil {
		n.beforeReceipt()
	}
	app := r.AppID
	if n.wrongOwner {
		app = "different-owner"
	}
	result := map[string]any{"appId": app, "requestId": r.RequestID, "accepted": true}
	if n.deleted {
		result["deleted"] = true
	}
	return protocol.AdapterReply{State: "ok", Result: mustJSON(result)}, nil
}

func TestDisconnectCannotDropNewAdmissionDuringPCCleanupAuthorization(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	j.State = "failed"
	state.Jobs[j.ID] = j
	if err = s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	newInput := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Concurrent acceptance", Text: "preserve new accepted job", Files: []protocol.File{}}
	s.native = &discardReceiptNative{nativeFixture: base, beforeReceipt: func() {
		if w := call("POST", "/v2/jobs", newInput, p.identity); w.Code != 202 {
			t.Fatal(w.Body.String())
		}
	}}
	if w := call("DELETE", "/v2/app", nil, p.identity); w.Code != 409 {
		t.Fatal("Disconnect erased concurrent admission", w.Code, w.Body.String())
	}
	state, err = s.loadApp(p)
	if err != nil || state.Jobs[newInput.RequestID] == nil || state.Jobs[j.ID] == nil || len(state.Jobs) != 2 {
		t.Fatal("Scope fence lost retained state", err)
	}
}

func TestExplicitRemovalRequiresExactDurablePCDiscardReceipt(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	j.State, j.Created = "failed", time.Now().UnixMilli()
	f, _ := files.Describe(randomToken(), "input.bin", []byte("retain"))
	j.Input.Files = []protocol.File{f}
	j.Fingerprint = appHash(j.Input)
	state.Jobs[j.ID] = j
	if _, err = s.inbox.Put(p.identity, f, bytes.NewReader([]byte("retain"))); err != nil {
		t.Fatal(err)
	}
	if err = s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	path := "/v2/jobs/" + j.ID
	for _, mode := range []string{"offline", "wrong_owner"} {
		if mode == "wrong_owner" {
			s.native = &discardReceiptNative{nativeFixture: base, wrongOwner: true}
		}
		if w := call("DELETE", path, nil, p.identity); w.Code != 503 {
			t.Fatal("Unconfirmed discard removed job", mode, w.Body.String())
		}
		state, err = s.loadApp(p)
		if err != nil || state.Jobs[j.ID] == nil || state.DeletedRequests[j.ID] != 0 {
			t.Fatal("Failure committed deletion", mode, err)
		}
		if _, err = s.inbox.Info(p.identity, f.ID); err != nil {
			t.Fatal("Unconfirmed discard lost bytes", err)
		}
	}
	n := &discardReceiptNative{nativeFixture: base}
	s.native = n
	if w := call("DELETE", path, nil, p.identity); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	state, err = s.loadApp(p)
	if err != nil || state.Jobs[j.ID] != nil || state.DeletedRequests[j.ID] <= time.Now().Add(appRetention-time.Minute).UnixMilli() || n.accepted != 1 {
		t.Fatal("Old job deletion did not start bounded new barrier", err)
	}
	if _, err = s.inbox.Info(p.identity, f.ID); !os.IsNotExist(err) {
		t.Fatal("Explicit final-reference bytes retained", err)
	}
}

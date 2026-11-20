package backend

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"phonepcbridge/protocol"
)

type interactionNative struct {
	*nativeFixture
	s                 *Service
	job               *appJob
	turn              json.RawMessage
	interrupts        int
	retireCalls       int
	failRetire        bool
	mismatchRetire    bool
	backgroundRunning bool
	uncertain         bool
	t                 *testing.T
}

func (n *interactionNative) History(context.Context, string, string, int) (json.RawMessage, error) {
	return mustJSON(map[string]any{"data": []json.RawMessage{n.turn}, "nextCursor": nil}), nil
}
func (n *interactionNative) Call(ctx context.Context, r protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if r.Operation == "retire" {
		n.retireCalls++
		if r.ThreadID != n.job.ThreadID || r.TurnID != n.job.TurnID || r.RequestID != n.job.ID {
			n.t.Fatal("Automatic retirement targeted another accepted owner")
		}
		if n.failRetire {
			return protocol.AdapterReply{}, errors.New("owned background process remains active")
		}
		turn := r.TurnID
		if n.mismatchRetire {
			turn = "another-turn"
		} else {
			n.backgroundRunning = false
		}
		return protocol.AdapterReply{State: "ok", Result: mustJSON(map[string]any{"threadId": r.ThreadID, "turnId": turn, "terminalsRetired": true})}, nil
	}
	if r.Operation != "interrupt" {
		return n.nativeFixture.Call(ctx, r)
	}
	if r.ThreadID != n.job.ThreadID || r.TurnID != n.job.TurnID {
		n.t.Fatal("Interaction guard targeted another native turn")
	}
	state, err := n.s.loadApp(n.s.getPeer("nAlice"))
	if err != nil || len(state.Jobs[n.job.ID].Log) == 0 || state.Jobs[n.job.ID].Log[0].Error.Code != "native_interaction_required" || state.Jobs[n.job.ID].CancelRequested == 0 || state.Jobs[n.job.ID].CancelReason != "native_interaction_required" {
		n.t.Fatal("Interrupt preceded durable investigation context", err)
	}
	n.interrupts++
	if n.uncertain {
		return protocol.AdapterReply{}, errors.New("owned interrupt acknowledgment lost")
	}
	return protocol.AdapterReply{State: "ok", Result: json.RawMessage(`{}`)}, nil
}

func TestInteractiveJobStopsOnlyItsAcknowledgedTurnAndWaitsForNativeOutcome(t *testing.T) {
	for _, uncertain := range []bool{false, true} {
		t.Run(map[bool]string{false: "acknowledged", true: "uncertain"}[uncertain], func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			base.online = true
			registerFixture(t, call, "nAlice")
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			j := ownedOutputJob(randomToken())
			state.Jobs[j.ID] = j
			n := &interactionNative{nativeFixture: base, s: s, job: j, uncertain: uncertain, backgroundRunning: true, t: t, turn: mustJSON(map[string]any{"id": j.TurnID, "status": "inProgress", "items": []any{}, "bridgeInteractionRequired": true})}
			s.native = n
			if err := s.saveApp(state); err != nil {
				t.Fatal(err)
			}
			if err := s.workPass(); err != nil {
				t.Fatal(err)
			}
			state, err = s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			j = state.Jobs[j.ID]
			logs := 1
			if uncertain {
				logs++
			}
			if n.interrupts != 1 || j.State != "submitted" || j.Code != "native_interaction_required" || j.CancelRequested == 0 || j.CancelReason != "native_interaction_required" || j.CancelRetired != 0 || !n.backgroundRunning || len(j.Outputs) != 0 || len(j.Log) != logs {
				t.Fatal("Hidden interaction waited, claimed completion or lost its independent diagnostic", n.interrupts, j.State, j.Code)
			}
			w := call("GET", "/v2/jobs/"+j.ID, nil, "nAlice")
			if w.Code != 200 || !strings.Contains(w.Body.String(), `"issue":{`) || !strings.Contains(w.Body.String(), "native_interaction_required") {
				t.Fatal("Apps cannot investigate an unattended interruption", w.Body.String())
			}
			saved, err := s.loadApp(p)
			if err != nil || saved.Jobs[j.ID].State != "submitted" || len(saved.Jobs[j.ID].Log) != len(j.Log) {
				t.Fatal("Interaction or uncertain native state was not durable", err)
			}
			requested := j.CancelRequested
			s.Close()
			resumed, err := New(s.cfg, n, func(_ context.Context, remote string) (string, error) { return remote, nil }, nil)
			if err != nil {
				t.Fatal(err)
			}
			defer resumed.Close()
			n.s, n.uncertain = resumed, false
			p = resumed.getPeer("nAlice")
			// The original client request need not reappear after reconnection.
			n.turn = mustJSON(map[string]any{"id": j.TurnID, "status": "inProgress", "items": []any{}})
			if err := resumed.workPass(); err != nil {
				t.Fatal(err)
			}
			wantInterrupts := 1
			if uncertain {
				wantInterrupts++
			}
			if n.interrupts != wantInterrupts || n.retireCalls != 0 || !n.backgroundRunning {
				t.Fatal("Reopened automatic stop replayed an acknowledged interrupt or claimed retirement")
			}
			n.turn = mustJSON(map[string]any{"id": j.TurnID, "status": "interrupted", "items": []any{}})
			if err := resumed.workPass(); err != nil {
				t.Fatal(err)
			}
			state, err = resumed.loadApp(p)
			if err != nil || state.Jobs[j.ID].State != "submitted" || n.retireCalls != 0 {
				t.Fatal("Uncommitted interruption bypassed native retirement", err)
			}
			n.turn = mustJSON(map[string]any{"id": j.TurnID, "status": "interrupted", "bridgeTerminalCommitted": true, "items": []any{}})
			for _, stage := range []string{"background_active", "wrong_receipt", "retired"} {
				n.failRetire, n.mismatchRetire = stage == "background_active", stage == "wrong_receipt"
				if err := resumed.workPass(); err != nil {
					t.Fatal(err)
				}
				state, err = resumed.loadApp(p)
				if err != nil {
					t.Fatal(err)
				}
				j = state.Jobs[j.ID]
				if j.CancelRequested != requested || j.CancelReason != "native_interaction_required" || j.Code != "native_interaction_required" || n.interrupts != wantInterrupts || base.calls != 0 || len(j.Outputs) != 0 {
					t.Fatal("Automatic stop changed reason/identity or replayed work", stage)
				}
				if stage != "retired" {
					if j.State != "submitted" || j.CancelRetired != 0 || !n.backgroundRunning || j.Log[len(j.Log)-1].Error.Code != "cancellation_retirement_unconfirmed" {
						t.Fatal("Acknowledged interruption or wrong receipt claimed background retirement", stage)
					}
				} else if j.State != "interrupted" || j.CancelRetired == 0 || n.backgroundRunning || n.retireCalls != 3 {
					t.Fatal("Verified native retirement did not settle the original automatic stop")
				}
			}
		})
	}
}

func TestInteractiveJobDoesNotInterruptBeforeDurableDiagnostic(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	state.Jobs[j.ID] = j
	n := &interactionNative{nativeFixture: base, s: s, job: j, t: t}
	s.native = n
	original := s.cfg.DataDir
	blocked := filepath.Join(t.TempDir(), "not-a-directory")
	if err := os.WriteFile(blocked, []byte("owned"), 0600); err != nil {
		t.Fatal(err)
	}
	s.cfg.DataDir = blocked
	defer func() { s.cfg.DataDir = original }()
	p.appMu.Lock()
	defer p.appMu.Unlock()
	if err := s.requestInteractiveStop(state, j); err == nil || n.interrupts != 0 || base.calls != 0 {
		t.Fatal("Failed diagnostic persistence allowed a native mutation", err)
	}
}

func TestAutomaticStopIntentResumesAfterRestartBeforeAnyInterrupt(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	state.Jobs[j.ID] = j
	n := &interactionNative{nativeFixture: base, s: s, job: j, backgroundRunning: true, t: t, turn: mustJSON(map[string]any{"id": j.TurnID, "status": "inProgress", "items": []any{}, "bridgeInteractionRequired": true})}
	s.native = n
	// Stop at the real observation commit, before workExistingJob issues its native request.
	if err := s.refreshApp(p, state, j); err != nil {
		t.Fatal(err)
	}
	if j.CancelRequested == 0 || j.CancelReason != "native_interaction_required" || n.interrupts != 0 {
		t.Fatal("Automatic observation did not durably precede interruption")
	}
	requested := j.CancelRequested
	s.Close()
	resumed, err := New(s.cfg, n, func(_ context.Context, remote string) (string, error) { return remote, nil }, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer resumed.Close()
	n.s = resumed
	n.turn = mustJSON(map[string]any{"id": j.TurnID, "status": "inProgress", "items": []any{}})
	if err := resumed.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err = resumed.loadApp(resumed.getPeer("nAlice"))
	if err != nil || state.Jobs[j.ID].CancelRequested != requested || state.Jobs[j.ID].CancelAcknowledged == 0 || state.Jobs[j.ID].CancelRetired != 0 || n.interrupts != 1 || n.retireCalls != 0 || !n.backgroundRunning || base.calls != 0 {
		t.Fatal("Restart lost automatic stop intent or manufactured native retirement", err)
	}
}

func TestStoredCancellationReasonMustMatchDurableIntent(t *testing.T) {
	for _, test := range []struct {
		name, reason string
		requested    int64
		valid        bool
	}{{"no_intent", "", 0, true}, {"missing_reason", "", 1, false}, {"empty_reason", "", 1, false}, {"explicit_user", "user_request", 1, true}, {"automatic", "native_interaction_required", 1, true}, {"unknown", "untrusted_reason", 1, false}, {"missing_intent", "native_interaction_required", 0, false}} {
		t.Run(test.name, func(t *testing.T) {
			s, _, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			j := ownedOutputJob(randomToken())
			j.CancelReason, j.CancelRequested = test.reason, test.requested
			state.Jobs[j.ID] = j
			var wire map[string]any
			if err := json.Unmarshal(mustJSON(state), &wire); err != nil {
				t.Fatal(err)
			}
			if test.name == "empty_reason" {
				wire["jobs"].(map[string]any)[j.ID].(map[string]any)["cancelReason"] = ""
			}
			if err := os.WriteFile(filepath.Join(s.cfg.DataDir, "apps", appStateName("nAlice")), mustJSON(wire), 0600); err != nil {
				t.Fatal(err)
			}
			loaded, err := s.loadApp(p)
			if (err == nil) != test.valid || (test.valid && loaded.Jobs[j.ID].CancelReason != test.reason) {
				t.Fatal("Stored stop reason lost validation or an established user intent", err)
			}
		})
	}
}

func TestExplicitCancellationRetainsUserReasonWhenInteractionIsAlsoRequired(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	state.Jobs[j.ID] = j
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	released := make(chan struct{})
	close(released)
	n := &heldControlNative{nativeFixture: base, stage: "interrupt", entered: make(chan struct{}), release: released, turn: j.TurnID}
	s.native = n
	if w := call("POST", "/v2/jobs/"+j.ID+"/cancel", nil, "nAlice"); w.Code != 202 {
		t.Fatal(w.Code, w.Body.String())
	}
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err = s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j = state.Jobs[j.ID]
	if j.CancelReason != "user_request" || j.CancelRequested == 0 || j.CancelAcknowledged == 0 || j.CancelRetired != 0 || j.State != "submitted" || n.interrupts.Load() != 1 {
		t.Fatal("Automatic guard relabeled an explicit user cancellation or replayed interruption")
	}
}

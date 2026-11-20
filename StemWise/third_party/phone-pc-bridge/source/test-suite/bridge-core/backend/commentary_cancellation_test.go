package backend

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"testing"

	"phonepcbridge/protocol"
)

type progressCancellationNative struct {
	*nativeFailureFixture
	t            *testing.T
	service      *Service
	app          string
	owner        *appJob
	interrupts   int
	retirements  int
	failRetire   bool
	wrongReceipt bool
}

func (n *progressCancellationNative) Call(ctx context.Context, request protocol.AdapterRequest) (protocol.AdapterReply, error) {
	switch request.Operation {
	case "interrupt":
		n.interrupts++
		state, err := n.service.loadApp(n.service.getPeer("nAlice"))
		if err != nil || state.Jobs[n.owner.ID] == nil || state.Jobs[n.owner.ID].CancelRequested == 0 || request.ThreadID != n.owner.ThreadID || request.TurnID != n.owner.TurnID {
			n.t.Fatal("Interrupt preceded durable intent or targeted another turn", err)
		}
		return protocol.AdapterReply{State: "ok", Result: json.RawMessage(`{}`)}, nil
	case "retire":
		n.retirements++
		if request.AppID != n.app || request.RequestID != n.owner.ID || request.CreatedAt != n.owner.Created || request.Name != n.owner.Developer || request.Model != n.owner.Model || request.ServiceTier != n.owner.ServiceTier || request.ThreadID != n.owner.ThreadID || request.TurnID != n.owner.TurnID {
			n.t.Fatal("Retirement lost the immutable accepted owner", request)
		}
		if n.failRetire {
			return protocol.AdapterReply{}, errors.New("exact owned retirement unavailable")
		}
		turn := request.TurnID
		if n.wrongReceipt {
			turn = "different-turn"
		}
		return protocol.AdapterReply{State: "ok", Result: mustJSON(map[string]any{"threadId": request.ThreadID, "turnId": turn, "terminalsRetired": true})}, nil
	default:
		return n.nativeFixture.Call(ctx, request)
	}
}

func TestPersistedCommentaryCannotInvalidateUserOrAutomaticCancellationRetirement(t *testing.T) {
	for _, cause := range []string{"user_request", "native_interaction_required"} {
		t.Run(cause, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			base.online = true
			registerFixture(t, call, "nAlice")
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			job := ownedOutputJob(randomToken())
			state.Jobs[job.ID] = job
			if err := s.saveApp(state); err != nil {
				t.Fatal(err)
			}
			itemID := "progress-" + randomToken()
			item := map[string]any{"type": "agentMessage", "id": itemID, "phase": "commentary", "text": "[[bridge:processing]]", "bridgeReportCommitted": true}
			turn := map[string]any{"id": job.TurnID, "status": "inProgress", "items": []any{item}}
			n := &progressCancellationNative{nativeFailureFixture: &nativeFailureFixture{nativeFixture: base, turn: mustJSON(turn)}, t: t, service: s, app: state.ID, owner: job}
			s.native = n
			if err := s.workPass(); err != nil {
				t.Fatal(err)
			}
			state, err = s.loadApp(p)
			if err != nil || state.Jobs[job.ID] == nil || state.Jobs[job.ID].Progress == nil || state.Jobs[job.ID].Progress.Stage != "processing" || state.Jobs[job.ID].Progress.ItemID != itemID {
				t.Fatal("Normal worker did not persist the original committed progress marker", err)
			}
			if cause == "user_request" {
				if response := call("POST", "/v2/jobs/"+job.ID+"/cancel", nil, "nAlice"); response.Code != 202 {
					t.Fatal("Actual cancel request did not retain pending intent", response.Code, response.Body.String())
				}
			} else {
				turn["bridgeInteractionRequired"] = true
				n.turn = mustJSON(turn)
				if err := s.workPass(); err != nil {
					t.Fatal(err)
				}
			}
			state, err = s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			current := state.Jobs[job.ID]
			if current.CancelRequested == 0 || current.CancelReason != cause || current.Progress == nil || current.State != "submitted" || n.interrupts != 1 || n.retirements != 0 {
				t.Fatal("Fixture did not retain actual cancellation with preexisting durable progress", current)
			}
			response := call("GET", "/v2/jobs/"+job.ID, nil, "nAlice")
			var public struct {
				Data struct{ Progress json.RawMessage }
			}
			if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &public) != nil || !bytes.Equal(public.Data.Progress, []byte("null")) || bytes.Contains(response.Body.Bytes(), []byte(itemID)) {
				t.Fatal("Pending cancellation exposed progress or its private item ID", response.Body.String())
			}
			// The next observation is already terminal: no intermediate active poll
			// may quietly clear/persist progress before the retirement snapshot.
			n.turn = mustJSON(map[string]any{"id": job.TurnID, "status": "interrupted", "bridgeTerminalCommitted": true, "items": []any{item}})
			for index, retirement := range []string{"unavailable", "wrong_receipt", "confirmed"} {
				n.failRetire, n.wrongReceipt = retirement == "unavailable", retirement == "wrong_receipt"
				if err := s.workPass(); err != nil {
					t.Fatal(err)
				}
				state, err = s.loadApp(p)
				if err != nil {
					t.Fatal(err)
				}
				current = state.Jobs[job.ID]
				if n.retirements != index+1 || n.interrupts != 1 || base.calls != 0 || len(current.Outputs) != 0 || current.Created != job.Created || current.Fingerprint != job.Fingerprint {
					t.Fatal("Retirement replayed work or changed its immutable lifetime/identity", retirement)
				}
				if retirement != "confirmed" {
					if current.State != "submitted" || current.CancelRetired != 0 {
						t.Fatal("Unconfirmed retirement settled cancellation", retirement, current)
					}
				} else if current.State != "interrupted" || current.CancelRetired <= 0 || current.Progress != nil {
					t.Fatal("Persisted commentary prevented the exact receipt from settling cancellation", current)
				}
			}
			retiredAt := current.CancelRetired
			if err := s.workPass(); err != nil {
				t.Fatal(err)
			}
			state, err = s.loadApp(p)
			if err != nil || state.Jobs[job.ID] == nil || state.Jobs[job.ID].CancelRetired != retiredAt || n.retirements != 3 || n.interrupts != 1 || base.calls != 0 {
				t.Fatal("Settled cancellation returned to a pending retirement loop", err)
			}
		})
	}
}

package backend

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"phonepcbridge/protocol"
)

type restoringCancellationNative struct {
	*cancellationNative
	restored, denied  bool
	restores          []protocol.AdapterRequest
	unrestoredHistory int
}

func (n *restoringCancellationNative) Call(ctx context.Context, request protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if request.Operation == "select" && request.AppID != "" {
		n.restores = append(n.restores, request)
		if n.denied {
			exit, rpc := 43, -32009
			return protocol.AdapterReply{State: "unknown", Code: "chat_writer_unconfirmed", Failure: &protocol.AdapterFailure{Message: "owned writer unavailable local_process_diagnostic=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", RPCCode: &rpc, Process: &protocol.AdapterProcessFailure{WaitState: "exited", ExitCode: &exit, Stderr: "database_locked"}}}, nil
		}
		n.restored = true
	}
	return n.cancellationNative.Call(ctx, request)
}
func (n *restoringCancellationNative) History(ctx context.Context, thread, cursor string, limit int) (json.RawMessage, error) {
	if !n.restored {
		n.unrestoredHistory++
		return nil, errors.New("owned native writer is not loaded after reconnect")
	}
	return n.cancellationNative.History(ctx, thread, cursor, limit)
}

func TestCancellationRestoresExactWriterBeforeRecoveryAndNeverInterruptsDeniedWriter(t *testing.T) {
	for _, stage := range []string{"unknown", "submitted", "retired_checkpoint"} {
		t.Run(stage, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			n := &restoringCancellationNative{cancellationNative: &cancellationNative{lostAcknowledgment: &lostAcknowledgment{nativeFixture: base, broken: true}}}
			s.native = n
			n.online = true
			registerFixture(t, call, "nAlice")
			input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Reconnect cancellation", Text: "original-" + randomToken(), Files: []protocol.File{}}
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			if err := s.workPass(); err != nil {
				t.Fatal(err)
			}
			if w := call("POST", "/v2/jobs/"+input.RequestID+"/cancel", nil, "nAlice"); w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			n.broken = false
			if stage != "unknown" {
				n.failInterrupt = true
				if err := s.workPass(); err != nil {
					t.Fatal(err)
				}
				n.failInterrupt = false
			}
			p := s.getPeer("nAlice")
			if stage == "retired_checkpoint" {
				n.terminal = true
				p.appMu.Lock()
				state, err := s.loadApp(p)
				if err == nil {
					_, err = s.retireCancelledJob(p, state, state.Jobs[input.RequestID], nil)
				}
				p.appMu.Unlock()
				if err != nil {
					t.Fatal(err)
				}
			}
			n.restored, n.denied = false, true
			n.unrestoredHistory = 0
			interrupts, retireCalls := n.interrupts, n.retireCalls
			resumed, err := New(s.cfg, n, func(_ context.Context, remote string) (string, error) { return remote, nil }, nil)
			if err != nil {
				t.Fatal(err)
			}
			defer resumed.Close()
			if err := resumed.workPass(); err != nil {
				t.Fatal(err)
			}
			state, err := resumed.loadApp(resumed.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			j := state.Jobs[input.RequestID]
			if jobTerminal(j) || n.interrupts != interrupts || n.retireCalls != retireCalls || n.calls != 1 || n.unrestoredHistory != 0 {
				t.Fatal("Denied restoration read unloaded history, interrupted a denied writer, retired or replayed work", j.State, n.interrupts, n.retireCalls, n.calls, n.unrestoredHistory)
			}
			if len(j.Log) == 0 {
				t.Fatal("Restoration failure had no diagnostic")
			}
			entry := j.Log[len(j.Log)-1]
			if !validDiagnostic(&entry.Error) || !*entry.Error.Retryable || entry.Error.Context["native_rpc_code"] != "-32009" || entry.Error.Context["native_process_exit_code"] != "43" || entry.Error.Context["native_process_stderr"] != "database_locked" {
				t.Fatal("Restoration discarded bounded native diagnostic facts", entry)
			}
			n.denied = false
			if err := resumed.workPass(); err != nil {
				t.Fatal(err)
			}
			state, err = resumed.loadApp(resumed.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			j = state.Jobs[input.RequestID]
			if j.TurnID != "fixture-turn" || n.calls != 1 || n.unrestoredHistory != 0 {
				t.Fatal("Owned restoration failed to recover original turn without replay", j.State, n.calls)
			}
			if len(n.restores) == 0 {
				t.Fatal("Cancellation never attempted owned writer restoration")
			}
			for _, request := range n.restores {
				if request.AppID != state.ID || request.RequestID != j.ID || request.CreatedAt != j.Created || request.Name != j.Developer || request.Model != j.Model || request.ThreadID != j.ThreadID || request.TurnID != "" || request.JobID != "" {
					t.Fatal("Restoration lost exact immutable creation envelope", request)
				}
			}
		})
	}
}

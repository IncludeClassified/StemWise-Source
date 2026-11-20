package backend

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"phonepcbridge/protocol"
)

type dispatchFailureNative struct {
	*nativeFixture
	remote  *Remote
	sends   int
	catalog string
}

func (n *dispatchFailureNative) Models(ctx context.Context, cursor string) (json.RawMessage, error) {
	if n.catalog != "" {
		return json.RawMessage(n.catalog), nil
	}
	return n.nativeFixture.Models(ctx, cursor)
}

func TestActualTieredDispatchRejectionRetainsReviewedSelection(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	n := &dispatchFailureNative{nativeFixture: base, catalog: fixtureTierCatalog, remote: &Remote{PCIdentity: s.cfg.PCIdentity, PiIdentity: s.cfg.Identity}}
	s.native = n
	registerFixture(t, call, "nAlice")
	n.remote.Wire = func(ctx context.Context, method, path string, data []byte) (int, []byte, error) {
		var request protocol.AdapterRequest
		if protocol.DecodeStrict(data, &request) != nil {
			t.Fatal("Invalid adapter request")
		}
		reply := protocol.AdapterReply{Protocol: protocol.Adapter, PCIdentity: s.cfg.PCIdentity, Nonce: request.Nonce, State: "ok"}
		if request.Operation == "select" {
			selected, err := base.Call(ctx, request)
			if err != nil {
				t.Fatal(err)
			}
			reply.Result = selected.Result
		} else if request.Operation == "send" {
			if request.ServiceTier != "fixture-tier-a" {
				t.Fatal("Actual adapter request dropped captured tier")
			}
			rpc := -32602
			reply.State, reply.Code = "rejected", "native_operation_rejected"
			reply.Failure = &protocol.AdapterFailure{RPCCode: &rpc, Message: "native service-tier rejection"}
		} else {
			t.Fatal("Unexpected actual adapter operation", request.Operation)
		}
		return 200, mustJSON(reply), nil
	}
	input := tierJobInput(randomToken(), "fixture-tier-a")
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
	j := state.Jobs[input["requestId"].(string)]
	if j.State != "rejected" || len(j.Log) != 1 || n.sends != 1 {
		t.Fatal("Actual native rejection was not independently logged")
	}
	d := &j.Log[0].Error
	if !validDiagnostic(d) || d.Context["service_tier"] != "fixture-tier-a" || d.Context["model"] != "fixture-model" || d.Context["effort"] != "high" {
		t.Fatal("Native rejection lost captured reviewed selection", d)
	}
}

func TestTieredDispatchDiagnosticRemainsBoundedAndRedacted(t *testing.T) {
	j := &appJob{State: "submitted", Code: "interrupt_unconfirmed", ThreadID: "owned-thread", TurnID: "owned-turn", Model: strings.Repeat("&", 128), Effort: strings.Repeat("&", 32), ServiceTier: strings.Repeat("&", 128)}
	exit, rpc := 42, -32000
	reply := protocol.AdapterReply{State: "unknown", Code: "codex_unavailable", Failure: &protocol.AdapterFailure{Message: strings.Repeat("&", 2048), RPCCode: &rpc, Process: &protocol.AdapterProcessFailure{WaitState: "exited", ExitCode: &exit, Stderr: strings.Repeat("&", 1024), TerminationRequested: true}}}
	entry := dispatchJobLog(j, "interrupt", reply, nil)
	if !validDiagnostic(&entry.Error) || len(entry.Error.Context) > 16 || entry.Error.Context["service_tier"] == "" || entry.Error.Cause == "native_rpc_rejected" {
		t.Fatal("Tier context broke encoded/count budget or lost selection", entry.Error)
	}
	j.ServiceTier = "token=private_fixture_value"
	entry = dispatchJobLog(j, "interrupt", protocol.AdapterReply{State: "rejected", Code: "native_operation_rejected"}, nil)
	if strings.Contains(string(mustJSON(entry.Error)), "private_fixture_value") || entry.Error.Context["service_tier"] == "" {
		t.Fatal("Tier diagnostic bypassed existing secret redaction")
	}
}

func TestDispatchProcessDiagnosticsRespectEncodedBudgetBeforeChatCreation(t *testing.T) {
	for _, detail := range []string{strings.Repeat("&", 2048), strings.Repeat("\x01", 2048), strings.Repeat("Ω", 1024)} {
		j := &appJob{ID: strings.Repeat("a", 32), State: "queued", Code: "chat_creation_unconfirmed", Model: strings.Repeat("&", 128), Effort: strings.Repeat("&", 32)}
		exit, rpc := 37, -32000
		reply := protocol.AdapterReply{State: "unknown", Code: "codex_unavailable", Failure: &protocol.AdapterFailure{Message: detail, RPCCode: &rpc, Process: &protocol.AdapterProcessFailure{WaitState: "exited", ExitCode: &exit, Stderr: strings.Repeat("&", 1024), TerminationRequested: true}}}
		entry := dispatchJobLog(j, "observe_original_creation", reply, nil)
		encoded := mustJSON(entry.Error)
		if len(encoded) > 4096 || !validDiagnostic(&entry.Error) || entry.Error.Context["native_process_exit_code"] != "37" || entry.Error.Context["native_rpc_code"] != "-32000" || entry.Error.Context["native_process_wait"] != "exited" || entry.Error.Context["diagnostic_detail_truncated"] != "true" || entry.Error.Cause == "native_rpc_rejected" {
			t.Fatal("Escaped diagnostic exceeded its wire budget or lost essential native facts", len(encoded))
		}
		if _, exists := entry.Error.Context["thread_id"]; exists {
			t.Fatal("Uncreated chat fabricated an empty thread ID")
		}
	}
}

func (n *dispatchFailureNative) Call(ctx context.Context, request protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if request.Operation == "create" || request.Operation == "creation" || request.Operation == "auth" {
		return n.nativeFixture.Call(ctx, request)
	}
	if request.Operation == "send" {
		n.sends++
	}
	return n.remote.Call(ctx, request)
}

func TestDispatchFailuresHaveIndependentPersistedDiagnosticsAndNeverReplay(t *testing.T) {
	for _, kind := range []string{"select_rejected", "send_rejected", "send_unknown", "send_bad_ack", "send_transport", "select_http_failure", "send_http_failure", "select_process_exit", "send_process_exit"} {
		t.Run(kind, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			base.online = true
			n := &dispatchFailureNative{nativeFixture: base, remote: &Remote{PCIdentity: s.cfg.PCIdentity, PiIdentity: s.cfg.Identity}}
			s.native = n
			secret := randomToken()
			input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Owned rejection", Text: "owner-" + randomToken(), Instructions: "custom-" + randomToken(), Files: []protocol.File{}}
			rpcCode := -32000
			n.remote.Wire = func(ctx context.Context, method, path string, data []byte) (int, []byte, error) {
				var request protocol.AdapterRequest
				if protocol.DecodeStrict(data, &request) != nil || method != "POST" || path != "/internal/v1/codex" {
					t.Fatal("Unexpected adapter boundary")
				}
				reply := protocol.AdapterReply{Protocol: protocol.Adapter, PCIdentity: s.cfg.PCIdentity, Nonce: request.Nonce, State: "rejected", Code: "native_operation_rejected", Failure: &protocol.AdapterFailure{RPCCode: &rpcCode, Message: "Invalid native selection password=" + secret + " " + input.Text + " " + input.Instructions}}
				if request.Operation == "select" && kind != "select_rejected" && kind != "select_http_failure" && kind != "select_process_exit" {
					reply, _ = base.Call(ctx, request)
					reply.Protocol, reply.PCIdentity, reply.Nonce = protocol.Adapter, s.cfg.PCIdentity, request.Nonce
				} else if kind == "send_unknown" {
					reply.State, reply.Code = "unknown", "native_outcome_unconfirmed"
					reply.Failure.RPCCode = nil
				} else if strings.HasSuffix(kind, "process_exit") {
					exit := 42
					reply.State, reply.Code = "unknown", "codex_unavailable"
					reply.Failure.RPCCode = nil
					reply.Failure.Process = &protocol.AdapterProcessFailure{WaitState: "exited", ExitCode: &exit, Stderr: "database_locked password=" + secret + " " + input.Text + " " + input.Instructions, StderrTruncated: true}
				} else if kind == "send_bad_ack" {
					reply.State, reply.Code, reply.Failure = "ok", "", nil
					reply.Result = json.RawMessage(`{"turn":{}}`)
				} else if kind == "send_transport" {
					return 0, nil, errors.New("Lost transport token=" + secret)
				} else if strings.HasSuffix(kind, "http_failure") {
					reply.Code, reply.Failure = "codex_unavailable", nil
					return 503, mustJSON(reply), nil
				}
				return 200, mustJSON(reply), nil
			}
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
			j := state.Jobs[input.RequestID]
			wanted, sends := "unknown", 1
			if strings.HasSuffix(kind, "rejected") {
				wanted = "rejected"
			}
			if kind == "select_rejected" {
				sends = 0
			}
			if kind == "select_http_failure" || kind == "select_process_exit" {
				wanted, sends = "queued", 0
			}
			if j.State != wanted || j.TurnID != "" || j.Report != nil || len(j.Outputs) != 0 || len(j.Log) != 1 || n.sends != sends || j.Input.Text != input.Text {
				t.Fatal("Rejection or uncertainty fabricated native/task/output success")
			}
			entry := j.Log[0]
			encoded := string(mustJSON(entry))
			if entry.Source != "bridge" || !validDiagnostic(&entry.Error) || *entry.Error.Retryable != (wanted == "queued") || strings.Contains(encoded, secret) || strings.Contains(encoded, input.Text) || strings.Contains(encoded, input.Instructions) {
				t.Fatal("Diagnostic missing, retry-authorizing, malformed or private")
			}
			if strings.HasSuffix(kind, "rejected") && (entry.Error.Cause != "native_rpc_rejected" || entry.Error.Context["native_rpc_code"] != "-32000") {
				t.Fatal("Actual native RPC failure lost")
			}
			if strings.HasSuffix(kind, "http_failure") && entry.Error.Context["error_detail"] != "pc_adapter_rejected http_status=503 state=rejected code=codex_unavailable" {
				t.Fatal("Stored HTTP failure lost correlated debugging facts")
			}
			if strings.HasSuffix(kind, "process_exit") && (entry.Error.Context["native_process_wait"] != "exited" || entry.Error.Context["native_process_exit_code"] != "42" || !strings.Contains(entry.Error.Context["native_process_stderr"], "database_locked") || entry.Error.Context["native_process_stderr_truncated"] != "true") {
				t.Fatal("Connection-scoped process outcome lost from independent job log")
			}
			w := call("GET", "/v2/jobs/"+j.ID, nil, "nAlice")
			if strings.Contains(w.Body.String(), "error_detail") {
				t.Fatal("Debug detail leaked into independent status")
			}
			if wanted == "queued" {
				var observed struct {
					Data struct {
						Issue appFailure `json:"issue"`
					} `json:"data"`
				}
				if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &observed) != nil || observed.Data.Issue.Code != "pc_unreachable" {
					t.Fatal("Unreachable selection lost its independent job issue", w.Body.String())
				}
				for _, action := range []string{"Send to Bridge", "Retry", "settings Wake", "passive"} {
					if !strings.Contains(observed.Data.Issue.Action, action) {
						t.Fatal("Actual job issue contradicts explicit wake actions", action, w.Body.String())
					}
				}
			}
			reopened, err := New(s.cfg, n, s.authenticate, nil)
			if err != nil {
				t.Fatal(err)
			}
			defer reopened.Close()
			if err := reopened.workPass(); err != nil {
				t.Fatal(err)
			}
			state, err = reopened.loadApp(reopened.getPeer("nAlice"))
			if err != nil || n.sends != sends || string(mustJSON(state.Jobs[j.ID].Log[0])) != encoded {
				t.Fatal("Service recovery replayed work or changed original diagnostic", err)
			}
		})
	}
}

func TestAdapterFailureMetadataRequiresCorrelatedBoundedNonSuccessReply(t *testing.T) {
	for _, kind := range []string{"nonce", "identity", "state", "code", "oversized", "empty", "success", "process_state", "process_exit_missing", "process_exit_negative", "process_signaled", "process_not_started", "process_oversized", "process_utf8"} {
		t.Run(kind, func(t *testing.T) {
			r := &Remote{PCIdentity: "nPc", PiIdentity: "nPi"}
			r.Wire = func(_ context.Context, _, _ string, data []byte) (int, []byte, error) {
				var input protocol.AdapterRequest
				_ = json.Unmarshal(data, &input)
				reply := protocol.AdapterReply{Protocol: protocol.Adapter, PCIdentity: r.PCIdentity, Nonce: input.Nonce, State: "rejected", Code: "native_operation_rejected", Failure: &protocol.AdapterFailure{Message: "untrusted details"}}
				switch kind {
				case "nonce":
					reply.Nonce = randomToken()
				case "identity":
					reply.PCIdentity = "nOther"
				case "state":
					reply.State = "hello"
				case "code":
					reply.Code = "password=not_machine_code"
				case "oversized":
					reply.Failure.Message = strings.Repeat("x", 2049)
				case "empty":
					reply.Failure.Message = " "
				case "success":
					reply.State = "ok"
				default:
					exit := 42
					reply.Failure.Process = &protocol.AdapterProcessFailure{WaitState: "exited", ExitCode: &exit, Stderr: "database_locked"}
					switch kind {
					case "process_state":
						reply.Failure.Process.WaitState = "success"
					case "process_exit_missing":
						reply.Failure.Process.ExitCode = nil
					case "process_exit_negative":
						exit = -1
					case "process_signaled":
						reply.Failure.Process.WaitState = "signaled"
					case "process_not_started":
						reply.Failure.Process.WaitState = "not_started"
					case "process_oversized":
						reply.Failure.Process.Stderr = strings.Repeat("x", 1025)
					case "process_utf8":
						return 200, bytes.Replace(mustJSON(reply), []byte("database_locked"), []byte{0xff}, 1), nil
					}
				}
				return 200, mustJSON(reply), nil
			}
			reply, err := r.Call(context.Background(), protocol.AdapterRequest{Operation: "send"})
			if err == nil || reply.Failure != nil || reply.State != "" || reply.Code != "" {
				t.Fatal("Unconfirmed response retained attacker-controlled diagnostic")
			}
		})
	}
}

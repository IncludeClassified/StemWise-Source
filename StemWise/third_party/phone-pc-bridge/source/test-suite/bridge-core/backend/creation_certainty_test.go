package backend

import (
	"context"
	"encoding/json"
	"errors"
	"reflect"
	"testing"
	"time"

	"phonepcbridge/codex"
	"phonepcbridge/protocol"
)

type creationCertaintyNative struct {
	*nativeFixture
	call     func(context.Context, protocol.AdapterRequest) (protocol.AdapterReply, error)
	requests []protocol.AdapterRequest
}

func (n *creationCertaintyNative) Models(context.Context, string) (json.RawMessage, error) {
	return json.RawMessage(fixtureTierCatalog), nil
}

func (n *creationCertaintyNative) Call(ctx context.Context, request protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if request.Operation == "auth" {
		return n.nativeFixture.Call(ctx, request)
	}
	n.requests = append(n.requests, request)
	return n.call(ctx, request)
}

func creationWireRequest(t *testing.T, method, path string, data []byte, nonces map[string]bool) protocol.AdapterRequest {
	t.Helper()
	var request protocol.AdapterRequest
	if method != "POST" || path != "/internal/v1/codex" || protocol.DecodeStrict(data, &request) != nil || !token.MatchString(request.Nonce) || nonces[request.Nonce] {
		t.Fatal("Creation did not use the real correlated adapter request")
	}
	nonces[request.Nonce] = true
	return request
}

func TestDefiniteCreationFailureRetriesOnlyAfterAbsenceWithOriginalEnvelope(t *testing.T) {
	for _, code := range []string{"creation_not_sent", "creation_rejected", "success"} {
		t.Run(code, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			base.online = true
			n := &creationCertaintyNative{nativeFixture: base}
			s.native = n
			registerFixture(t, call, "nAlice")
			remote := &Remote{PCIdentity: s.cfg.PCIdentity, PiIdentity: s.cfg.Identity}
			n.call = remote.Call
			creates, observations := 0, 0
			nonces := map[string]bool{}
			remote.Wire = func(ctx context.Context, method, path string, data []byte) (int, []byte, error) {
				request := creationWireRequest(t, method, path, data, nonces)
				reply := protocol.AdapterReply{State: "ok"}
				switch request.Operation {
				case "create":
					creates++
					if creates == 1 && code != "success" {
						reply.State, reply.Code = "rejected", code
						reply.Failure = &protocol.AdapterFailure{Message: "fixture confirmed creation did not start"}
						if code == "creation_rejected" {
							rpc := -32602
							reply.Failure.RPCCode = &rpc
						}
					} else {
						if code != "success" && observations != 1 {
							t.Fatal("Creation was repeated before authenticated absence")
						}
						reply.Result = json.RawMessage(`{"thread":{"id":"fixture-chat"}}`)
					}
				case "creation":
					observations++
					reply.State, reply.Code = "rejected", "creation_request_absent"
				case "select", "send":
					var err error
					reply, err = base.Call(ctx, request)
					if err != nil {
						t.Fatal(err)
					}
				default:
					t.Fatal("Unexpected native operation", request.Operation)
				}
				reply.Protocol, reply.PCIdentity, reply.Nonce = protocol.Adapter, remote.PCIdentity, request.Nonce
				return 200, mustJSON(reply), nil
			}
			input := appInput{RequestID: randomToken(), CreatedAt: time.Now().Add(-time.Hour).UnixMilli(), Title: "Original creation certainty", Text: "unchanged original text", Instructions: "unchanged custom instruction", Files: []protocol.File{}, Model: "fixture-model", Effort: "high", ServiceTier: "fixture-tier-a"}
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			state, err := s.loadApp(s.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			expected := jobCreation(state.ID, state.Jobs[input.RequestID], "create")
			if err := s.workPass(); err != nil {
				t.Fatal(err)
			}
			state, err = s.loadApp(s.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			first := state.Jobs[input.RequestID]
			if code != "success" {
				if creates != 1 || observations != 0 || base.calls != 0 || first.State != "queued" || first.ChatState != "unknown" || first.ThreadID != "" || first.Code != code || len(first.Log) != 1 {
					t.Fatal("Definite creation failure replayed immediately or fabricated native success", first)
				}
				diagnostic := first.Log[0].Error
				if !validDiagnostic(&diagnostic) || !*diagnostic.Retryable || diagnostic.Context["adapter_state"] != "rejected" || diagnostic.Context["adapter_code"] != code {
					t.Fatal("Safe original-ID retry lost its independent certainty diagnostic", diagnostic)
				}
				if code == "creation_rejected" && (diagnostic.Cause != "native_rpc_rejected" || diagnostic.Context["native_rpc_code"] != "-32602") {
					t.Fatal("Explicit native rejection lost its original RPC evidence", diagnostic)
				}
			}
			reopened, err := New(s.cfg, n, s.authenticate, nil)
			if err != nil {
				t.Fatal(err)
			}
			defer reopened.Close()
			if w := call("PUT", "/v2/codex/settings", map[string]string{"model": "", "effort": "", "serviceTier": ""}, "nAlice"); w.Code != 200 {
				t.Fatal(w.Body.String())
			}
			for range 3 {
				if err := reopened.workPass(); err != nil {
					t.Fatal(err)
				}
			}
			wantCreates, wantObservations := 2, 1
			if code == "success" {
				wantCreates, wantObservations = 1, 0
			}
			if creates != wantCreates || observations != wantObservations || base.calls != 1 {
				t.Fatal("Creation/dispatch repeated after receipt success", creates, observations, base.calls)
			}
			for _, request := range n.requests {
				if request.Operation == "create" || request.Operation == "creation" {
					request.Operation = "create"
					if !reflect.DeepEqual(request, expected) {
						t.Fatal("Retry changed the original immutable creation envelope", request, expected)
					}
				}
			}
			state, err = reopened.loadApp(reopened.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			job := state.Jobs[input.RequestID]
			if job.State != "completed" || job.ThreadID != "fixture-chat" || job.TurnID != "fixture-turn" || job.Fingerprint != appHash(input) || !reflect.DeepEqual(job.Input, input) || job.Model != expected.Model || job.ServiceTier != expected.ServiceTier || len(job.Outputs) != 1 {
				t.Fatal("Recovery changed identity/settings or did not publish the original result", job)
			}
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 200 || base.calls != 1 {
				t.Fatal("Successful original request stopped being idempotent", w.Body.String())
			}
		})
	}
}

func TestUncertainCreationRepliesNeverAuthorizeRecreationAcrossReopen(t *testing.T) {
	for _, scenario := range []string{"unknown", "unknown_rpc", "unknown_safe_code", "safe_code_with_result", "transport", "outer_unknown_rpc", "malformed_json", "malformed_success", "absence_wrong_nonce", "absence_with_result"} {
		t.Run(scenario, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			base.online = true
			n := &creationCertaintyNative{nativeFixture: base}
			s.native = n
			registerFixture(t, call, "nAlice")
			remote := &Remote{PCIdentity: s.cfg.PCIdentity, PiIdentity: s.cfg.Identity}
			n.call = remote.Call
			if scenario == "outer_unknown_rpc" {
				// Typed native uncertainty cannot be undone by errors.As finding its nested RPC rejection.
				n.call = func(context.Context, protocol.AdapterRequest) (protocol.AdapterReply, error) {
					return protocol.AdapterReply{}, &codex.OutcomeUnknown{Cause: &codex.RPCError{Code: -32602, Message: "unconfirmed original creation"}}
				}
			}
			nonces := map[string]bool{}
			remote.Wire = func(_ context.Context, method, path string, data []byte) (int, []byte, error) {
				request := creationWireRequest(t, method, path, data, nonces)
				if request.Operation != "create" && request.Operation != "creation" {
					t.Fatal("Unconfirmed creation reached native dispatch", request.Operation)
				}
				reply := protocol.AdapterReply{Protocol: protocol.Adapter, PCIdentity: remote.PCIdentity, Nonce: request.Nonce, State: "unknown", Code: "creation_receipt_unconfirmed", Failure: &protocol.AdapterFailure{Message: "possible original creation; receipt unconfirmed"}}
				switch scenario {
				case "unknown_rpc":
					rpc := -32602
					reply.Failure.RPCCode = &rpc
				case "unknown_safe_code":
					reply.Code = "creation_rejected"
				case "safe_code_with_result":
					reply.State, reply.Code, reply.Result = "rejected", "creation_not_sent", json.RawMessage(`{}`)
				case "transport":
					return 0, nil, errors.New("fixture lost original creation response")
				case "malformed_json":
					return 200, []byte(`{"protocol":`), nil
				case "malformed_success":
					reply.State, reply.Code, reply.Result, reply.Failure = "ok", "", json.RawMessage(`{"thread":{}}`), nil
				case "absence_wrong_nonce", "absence_with_result":
					if request.Operation == "creation" {
						reply.State, reply.Code, reply.Failure = "rejected", "creation_request_absent", nil
						if scenario == "absence_wrong_nonce" {
							reply.Nonce = randomToken()
						} else {
							reply.Result = json.RawMessage(`{}`)
						}
					}
				}
				return 200, mustJSON(reply), nil
			}
			input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Uncertain original creation", Text: "preserve this accepted work", Files: []protocol.File{}}
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			if err := s.workPass(); err != nil {
				t.Fatal(err)
			}
			reopened, err := New(s.cfg, n, s.authenticate, nil)
			if err != nil {
				t.Fatal(err)
			}
			defer reopened.Close()
			for range 2 {
				if err := reopened.workPass(); err != nil {
					t.Fatal(err)
				}
			}
			operations := []string{}
			for _, request := range n.requests {
				operations = append(operations, request.Operation)
			}
			if !reflect.DeepEqual(operations, []string{"create", "creation", "creation"}) || base.calls != 0 {
				t.Fatal("Uncertain or malformed creation was automatically repeated", operations, base.calls)
			}
			state, err := reopened.loadApp(reopened.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			job := state.Jobs[input.RequestID]
			if job.State != "queued" || job.ChatState != "unknown" || job.ThreadID != "" || job.TurnID != "" || job.Report != nil || len(job.Outputs) != 0 || job.Fingerprint != appHash(input) || len(job.Log) == 0 {
				t.Fatal("Uncertainty fabricated success, discarded work or changed identity", job)
			}
			for _, entry := range job.Log {
				if !validDiagnostic(&entry.Error) || *entry.Error.Retryable || entry.Error.Cause == "native_rpc_rejected" {
					t.Fatal("Uncertainty became a definitive rejection or authorized native replay", entry)
				}
				if scenario == "unknown_rpc" && entry.Error.Context["native_rpc_code"] != "-32602" {
					t.Fatal("Keeping uncertainty discarded the nested native diagnostic", entry)
				}
			}
			if (scenario == "unknown_safe_code" || scenario == "safe_code_with_result") && job.Code != "creation_observation_unavailable" {
				t.Fatal("Contradictory reply falsely declared that creation did not start", job.Code)
			}
		})
	}
}

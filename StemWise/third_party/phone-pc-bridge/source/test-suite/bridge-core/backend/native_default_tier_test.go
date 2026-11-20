package backend

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"reflect"
	"strings"
	"testing"
	"time"

	"phonepcbridge/protocol"
)

func TestPerJobNativeDefaultTierSurvivesSavedPriorityChangesAndCreationRecovery(t *testing.T) {
	for _, choice := range []string{"native_default", "omitted", "false", "null"} {
		t.Run(choice, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			base.online = true
			n := &creationCertaintyNative{nativeFixture: base}
			s.native = n
			registerFixture(t, call, "nAlice")
			remote := &Remote{PCIdentity: s.cfg.PCIdentity, PiIdentity: s.cfg.Identity}
			n.call = remote.Call
			nonces := map[string]bool{}
			creates, observations, sends := 0, 0, 0
			capturedTier := "fixture-tier-a"
			if choice == "native_default" {
				capturedTier = ""
			}
			remote.Wire = func(ctx context.Context, method, path string, data []byte) (int, []byte, error) {
				request := creationWireRequest(t, method, path, data, nonces)
				var fields map[string]json.RawMessage
				if json.Unmarshal(data, &fields) != nil {
					t.Fatal("Malformed actual adapter JSON")
				}
				if _, present := fields["nativeDefaultServiceTier"]; present {
					t.Fatal("Admission-only default choice leaked into the PC contract")
				}
				if request.Operation == "create" || request.Operation == "creation" || request.Operation == "send" {
					if request.ServiceTier != capturedTier || (capturedTier == "" && len(fields["serviceTier"]) != 0) {
						t.Fatal("Captured native-default/inherited choice changed or became an explicit override", string(data))
					}
				}
				reply := protocol.AdapterReply{State: "ok"}
				switch request.Operation {
				case "create":
					creates++
					reply.State, reply.Code = "unknown", "creation_receipt_unconfirmed"
				case "creation":
					observations++
					reply.Result = json.RawMessage(`{"thread":{"id":"fixture-chat"}}`)
				case "select", "send":
					var err error
					reply, err = base.Call(ctx, request)
					if err != nil {
						t.Fatal(err)
					}
					if request.Operation == "send" {
						sends++
					}
				default:
					t.Fatal("Unexpected native operation", request.Operation)
				}
				reply.Protocol, reply.PCIdentity, reply.Nonce = protocol.Adapter, remote.PCIdentity, request.Nonce
				return 200, mustJSON(reply), nil
			}
			settings := func(tier string) {
				t.Helper()
				if w := call("PUT", "/v2/codex/settings", map[string]string{"model": "fixture-model", "effort": "high", "serviceTier": tier}, "nAlice"); w.Code != 200 {
					t.Fatal(w.Body.String())
				}
			}
			settings("fixture-tier-a")
			input := tierJobInput(randomToken(), "")
			input["model"], input["effort"], input["serviceTier"] = "", "", nil
			switch choice {
			case "native_default":
				input["nativeDefaultServiceTier"] = true
			case "false":
				input["nativeDefaultServiceTier"] = false
			case "null":
				input["nativeDefaultServiceTier"] = nil
			}
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
				t.Fatal("Explicit native-default choice was not admitted", choice, w.Code, w.Body.String())
			}
			settings("fixture-tier-b")
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 200 {
				t.Fatal("Original default choice was not idempotent after settings changed", w.Body.String())
			}
			if choice == "native_default" {
				input["serviceTier"] = ""
				if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 200 {
					t.Fatal("Null/empty tier changed an explicit native-default request", w.Body.String())
				}
				delete(input, "serviceTier")
				if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 200 {
					t.Fatal("Omitted tier changed an explicit native-default request", w.Body.String())
				}
			}
			changed := map[string]any{}
			for key, value := range input {
				changed[key] = value
			}
			changed["nativeDefaultServiceTier"] = choice != "native_default"
			if w := call("POST", "/v2/jobs", changed, "nAlice"); w.Code != 409 || !strings.Contains(w.Body.String(), "request_id_conflict") {
				t.Fatal("Toggling an accepted native-default choice did not conflict", w.Body.String())
			}
			if err := s.workPass(); err != nil {
				t.Fatal(err)
			}
			if creates != 1 || observations != 0 || sends != 0 {
				t.Fatal("Unknown creation was repeated or sent prematurely", creates, observations, sends)
			}
			reopened, err := New(s.cfg, n, s.authenticate, nil)
			if err != nil {
				t.Fatal(err)
			}
			defer reopened.Close()
			if err := reopened.workPass(); err != nil {
				t.Fatal(err)
			}
			state, err := reopened.loadApp(reopened.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			job := state.Jobs[input["requestId"].(string)]
			var original map[string]any
			if json.Unmarshal(mustJSON(job.Input), &original) != nil {
				t.Fatal("Stored original input is malformed")
			}
			flag, hasFlag := original["nativeDefaultServiceTier"]
			if (choice == "native_default" && flag != true) || (choice != "native_default" && hasFlag) || job.ServiceTier != capturedTier || job.Model != "fixture-model" || job.Effort != "high" || state.ServiceTier != "fixture-tier-b" || job.State != "completed" || len(job.Outputs) != 1 {
				t.Fatal("Reopen changed native-default intent, captured settings or job result", job)
			}
			var submitted appInput
			if appDecode(mustJSON(input), &submitted) != nil || job.Fingerprint != appHash(submitted) || !reflect.DeepEqual(job.Input, submitted) {
				t.Fatal("Immutable requested default was lost from stored input/hash")
			}
			if w := call("GET", "/v2/jobs/"+job.ID+"/inputs", nil, "nAlice"); w.Code != 200 || (choice == "native_default" && !strings.Contains(w.Body.String(), `"nativeDefaultServiceTier":true`)) {
				t.Fatal("Input endpoint did not retain the original explicit choice", w.Body.String())
			}
			if appJobView(job)["serviceTier"] != capturedTier || creates != 1 || observations != 1 || sends != 1 {
				t.Fatal("Native request/default view or creation recovery was not exact", creates, observations, sends)
			}
			if err := reopened.workPass(); err != nil || sends != 1 {
				t.Fatal("Recovered native-default job replayed", err, sends)
			}
		})
	}
}

func TestNativeDefaultTierValidationCannotAdmitConflictingOrMalformedChoice(t *testing.T) {
	s, base, call := fixtureAPI(t)
	n := &serviceTierNative{nativeFixture: base, catalog: fixtureTierCatalog}
	s.native = n
	registerFixture(t, call, "nAlice")
	for _, value := range []any{"true", 1, []bool{true}, map[string]bool{"value": true}} {
		input := tierJobInput(randomToken(), "")
		input["nativeDefaultServiceTier"] = value
		if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 400 || !strings.Contains(w.Body.String(), `"code":"invalid_job"`) {
			t.Fatal("Wrong-typed native-default flag was accepted", value, w.Body.String())
		}
	}
	input := tierJobInput(randomToken(), "fixture-tier-a")
	input["nativeDefaultServiceTier"] = true
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 400 || !strings.Contains(w.Body.String(), `"code":"invalid_service_tier_choice"`) {
		t.Fatal("Conflicting explicit/native-default tier was not rejected specifically", w.Body.String())
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil || len(state.Jobs) != 0 || n.catalogCalls != 0 || len(n.requests) != 0 {
		t.Fatal("Invalid tier choice changed durable state or reached native work", err)
	}
	input["serviceTier"] = ""
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal("A rejected pre-admission choice reserved its request ID", w.Body.String())
	}
	input = tierJobInput(randomToken(), "")
	input["model"], input["effort"], input["nativeDefaultServiceTier"] = "", "", true
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal("Native omission incorrectly required an explicit model", w.Body.String())
	}
}

func TestNativeDefaultTierCannotContradictItsDurableInputOrCapturedChoice(t *testing.T) {
	for _, invalid := range []string{"input_tier", "captured_tier"} {
		t.Run(invalid, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			n := &serviceTierNative{nativeFixture: base, catalog: fixtureTierCatalog}
			s.native = n
			registerFixture(t, call, "nAlice")
			input := tierJobInput(randomToken(), "")
			input["nativeDefaultServiceTier"] = true
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			state, err := s.loadApp(s.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			job := state.Jobs[input["requestId"].(string)]
			if invalid == "input_tier" {
				job.Input.ServiceTier = "fixture-tier-a"
				job.Fingerprint = appHash(job.Input)
			} else {
				job.ServiceTier = "fixture-tier-a"
			}
			if err := s.saveApp(state); err != nil {
				t.Fatal(err)
			}
			if _, err := s.loadApp(s.getPeer("nAlice")); err == nil {
				t.Fatal("Contradictory persisted native-default choice was accepted")
			}
			if w := call("GET", "/v2/jobs/"+job.ID, nil, "nAlice"); w.Code != 503 || n.catalogCalls != 0 || len(n.requests) != 0 {
				t.Fatal("Contradictory native-default record reached job/native success", w.Code, w.Body.String())
			}
		})
	}
}

func TestFalseNativeDefaultTierKeepsExistingAcceptedCanonicalHash(t *testing.T) {
	const canonical = `{"requestId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","title":"canonical","text":"unchanged","instructions":"","files":[],"createdAt":1,"model":"","effort":""}`
	sum := sha256.Sum256([]byte(canonical))
	expectedHash := hex.EncodeToString(sum[:])
	for _, suffix := range []string{"", `,"nativeDefaultServiceTier":false`, `,"nativeDefaultServiceTier":null`} {
		var input appInput
		if err := appDecode([]byte(strings.TrimSuffix(canonical, "}")+suffix+"}"), &input); err != nil {
			t.Fatal(err)
		}
		if string(mustJSON(input)) != canonical || appHash(input) != expectedHash {
			t.Fatal("False/omitted native-default field changed an established accepted input hash")
		}
	}
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	var input appInput
	if err := appDecode([]byte(canonical), &input); err != nil {
		t.Fatal(err)
	}
	state.Jobs[input.RequestID] = &appJob{ID: input.RequestID, Input: input, Fingerprint: expectedHash, Developer: "bridge_job_" + input.RequestID, State: "queued", Created: time.Now().UnixMilli(), Outputs: []protocol.File{}}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	reopened, err := New(s.cfg, s.native, s.authenticate, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	state, err = reopened.loadApp(reopened.getPeer("nAlice"))
	if err != nil || state.Jobs[input.RequestID].Fingerprint != expectedHash {
		t.Fatal("Established accepted input stopped loading without migration", err)
	}
	if w := call("POST", "/v2/jobs", strings.TrimSuffix(canonical, "}")+`,"nativeDefaultServiceTier":false}`, "nAlice"); w.Code != 200 {
		t.Fatal("False flag stopped reconciling an established accepted request", w.Body.String())
	}
}

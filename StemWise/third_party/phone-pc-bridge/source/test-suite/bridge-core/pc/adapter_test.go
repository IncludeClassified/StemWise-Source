package pc

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"phonepcbridge/codex"
	"phonepcbridge/protocol"
	"strings"
	"tailscale.com/tsnet"
	"testing"
	"time"
)

func TestAdapterProcessFailureReturnsCorrelatedScopedDiagnostic(t *testing.T) {
	for _, test := range []struct {
		mode             string
		storeUnavailable bool
	}{{"stderr-startup-normal", false}, {"stderr-app-disconnect-send", false}, {"stderr-startup-normal", true}} {
		name := test.mode
		if test.storeUnavailable {
			name += "/closed_store"
		}
		t.Run(name, func(t *testing.T) {
			mode := test.mode
			t.Setenv("BRIDGE_CHECK_PEER", mode)
			executable, err := os.Executable()
			if err != nil {
				t.Fatal(err)
			}
			integration := t.TempDir()
			producer := filepath.Join(integration, "sdk-check", "bridge-pc-check.exe")
			if err := os.MkdirAll(filepath.Dir(producer), 0700); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(producer, []byte("owned producer fixture"), 0600); err != nil {
				t.Fatal(err)
			}
			s, err := New(&tsnet.Server{}, Config{Identity: "nPc", Host: "pc.tailtest.ts.net", CodexExecutable: executable, DataDir: t.TempDir(), IntegrationDir: integration, AllowedPeers: []string{"nPi"}, BackendPeer: "nPi"})
			if err != nil {
				t.Fatal(err)
			}
			defer s.Close()
			if test.storeUnavailable {
				s.creations.Close()
			}
			s.authenticate = func(context.Context, string) (string, error) { return "nPi", nil }
			server := httptest.NewServer(http.HandlerFunc(s.handle))
			defer server.Close()
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			call := func(input protocol.AdapterRequest) (protocol.AdapterReply, error) {
				input.Nonce = randomToken()
				data, _ := json.Marshal(input)
				request, err := http.NewRequestWithContext(ctx, "POST", server.URL+"/internal/v1/codex", bytes.NewReader(data))
				if err != nil {
					return protocol.AdapterReply{}, err
				}
				request.Header.Set("Content-Type", "application/json")
				response, err := server.Client().Do(request)
				if err != nil {
					return protocol.AdapterReply{}, err
				}
				defer response.Body.Close()
				var got protocol.AdapterReply
				if err := json.NewDecoder(response.Body).Decode(&got); err != nil {
					return got, err
				}
				if response.StatusCode != 200 || got.Protocol != protocol.Adapter || got.PCIdentity != "nPc" || got.Nonce != input.Nonce {
					t.Fatal("Uncorrelated adapter response")
				}
				return got, nil
			}
			input := protocol.AdapterRequest{Operation: "models"}
			exit, code := 37, "codex_unavailable"
			const text, instructions = "private-adapter-owner-prompt", "private-adapter-custom-instructions"
			if mode == "stderr-app-disconnect-send" {
				for _, request := range []protocol.AdapterRequest{
					{Operation: "create", Model: "bridge-check-model", AppID: strings.Repeat("b", 32), RequestID: strings.Repeat("c", 32), CreatedAt: time.Now().UnixMilli(), Name: "Owned isolated fixture"},
					{Operation: "select", ThreadID: "thread-1"},
				} {
					if reply, err := call(request); err != nil || reply.State != "ok" {
						t.Fatal("Native fixture preparation failed", err, reply.State)
					}
				}
				input = protocol.AdapterRequest{Operation: "send", ThreadID: "thread-1", Text: text, Instructions: instructions, Model: "bridge-check-model", Effort: "high", AppID: strings.Repeat("b", 32), JobID: strings.Repeat("c", 32)}
				exit, code = 52, "native_outcome_unconfirmed"
			}
			got, err := call(input)
			if err != nil || got.State != "unknown" || got.Code != code || got.Result != nil || got.Failure == nil || got.Failure.Process == nil {
				t.Fatal("Scoped process failure did not cross actual HTTP adapter boundary", err, got.State, got.Code)
			}
			process := got.Failure.Process
			if process.WaitState != "exited" || process.ExitCode == nil || *process.ExitCode != exit || !strings.Contains(process.Stderr, "database_locked") || process.TerminationRequested {
				t.Fatal("Final natural exit or technical cause lost", process)
			}
			body, _ := json.Marshal(got.Failure)
			if test.storeUnavailable {
				if !strings.Contains(got.Failure.Message, "local_process_diagnostic_store_unavailable attempt_id=") {
					t.Fatal("Local persistence failure was not explicit", got.Failure.Message)
				}
			} else {
				page, err := s.creations.List("nPi", "", 100)
				if err != nil {
					t.Fatal(err)
				}
				count := 0
				for _, f := range page.Files {
					if f.Name != processDiagnosticName {
						continue
					}
					count++
					receipt, err := s.readProcessDiagnostic("nPi", f)
					if err != nil || !strings.Contains(got.Failure.Message, "local_process_diagnostic="+f.ID) || receipt.Operation != input.Operation || receipt.AppID != input.AppID || receipt.JobID != input.JobID || receipt.ThreadID != input.ThreadID {
						t.Fatal("Actual process failure lost local receipt correlation", err, receipt)
					}
					if mode == "stderr-startup-normal" && (!strings.Contains(receipt.Process.Stderr, "novel tensor VX7 cannot execute sampler opcode 91") || bytes.Contains(body, []byte("novel tensor VX7"))) {
						t.Fatal("Novel cause was lost locally or leaked onto wire")
					}
					for _, private := range []string{text, instructions, "private-native-token", "private-owner-prompt", "private-account", "private-config", "private-bearer"} {
						if strings.Contains(receipt.Process.Stderr, private) {
							t.Fatal("Private bytes persisted in local receipt")
						}
					}
				}
				if count != 1 {
					t.Fatal("Actual process failure did not persist exactly one local receipt", count)
				}
			}
			for _, private := range []string{text, instructions, "private-native-token", "private-owner-prompt", "private-account", "private-config", "private-bearer"} {
				if bytes.Contains(body, []byte(private)) {
					t.Fatal("Child stderr exposed private bytes")
				}
			}
		})
	}
}

func TestAdapterWorkflowAndIdentity(t *testing.T) {
	testAdapterWorkflowAndIdentity(t, "app", "random-owner-"+randomToken()+" Ω\nunchanged", "")
}

func TestAdapterPreservesRejectedNativeRPCDetailWithoutCredentialsOrInputs(t *testing.T) {
	testAdapterWorkflowAndIdentity(t, "app-reject-send", "random-owner-"+randomToken()+" Ω\nunchanged", "")
}

func TestAdapterRejectsEscapedPromptEchoWithoutPublishingHandoffOrFragments(t *testing.T) {
	testAdapterWorkflowAndIdentity(t, "app-reject-send", "private<&>-owner\nsecond-private-line \"💬\"", "")
}

func TestAdapterFailureRedactsCompleteJobEnvelopeAndEscapedPrivateFragments(t *testing.T) {
	input := protocol.AdapterRequest{Text: "private<&>-owner\nsecond-private-line \"💬\"", Instructions: "private-custom\nsecond-custom"}
	path := filepath.Join(t.TempDir(), "private-handoff.md")
	const markdown = "# Bridge developer handoff\n\nPrivate correlation: hidden-handoff-header\n\nFile-only workflow."
	message, err := codex.FormatJobMessage(input.Text, input.Instructions, path, markdown)
	if err != nil {
		t.Fatal(err)
	}
	quoted, _ := json.Marshal(message)
	fragments, _ := json.Marshal(input.Text)
	for _, envelope := range []string{message, string(quoted)} {
		failure := adapterFailure(&codex.RPCError{Code: -32000, Message: "Turn rejected " + envelope + "\n" + input.Text + " " + string(fragments[1:len(fragments)-1]) + " " + input.Instructions + " " + path + " Private correlation: hidden-handoff-header"}, input)
		if failure.RPCCode == nil || *failure.RPCCode != -32000 || !strings.Contains(failure.Message, "Turn rejected") || !strings.Contains(failure.Message, "[redacted input]") {
			t.Fatal("Useful native rejection facts were lost", failure)
		}
		for _, private := range []string{"private<&>-owner", "second-private-line", "private-custom", "second-custom", "hidden-handoff-header", "private-handoff.md", "handoffPath", "bridgeJobInput", `private\u003c\u0026\u003e-owner`} {
			if strings.Contains(failure.Message, private) {
				t.Fatal("Native rejection exposed an envelope field or escaped fragment")
			}
		}
	}
	encodedPath, _ := json.Marshal(path)
	for _, echoedPath := range []string{path, string(encodedPath[1 : len(encodedPath)-1])} {
		failure := adapterFailure(&codex.RPCError{Code: -32000, Message: "Turn rejected local file " + echoedPath}, input, path)
		if strings.Contains(failure.Message, "private-handoff.md") || !strings.Contains(failure.Message, "Turn rejected") || !strings.Contains(failure.Message, "[redacted input]") {
			t.Fatal("Known standalone native handoff path escaped or lost cause", failure)
		}
	}
}

func TestAdapterEscapedPromptPreservesNativeInputs(t *testing.T) {
	testAdapterWorkflowAndIdentity(t, "app", strings.Repeat("&", (64<<10)-len("fixture-custom")), "")
}

func TestAdapterServiceTierReachesNativeAndImmutableHandoff(t *testing.T) {
	for _, tier := range []string{"default", "fast", "opaque/Ω + 1"} {
		t.Run(tier, func(t *testing.T) { testAdapterWorkflowAndIdentity(t, "app", "original tiered owner text", tier) })
	}
}

func testAdapterWorkflowAndIdentity(t *testing.T, mode, ownerText, serviceTier string) {
	t.Setenv("BRIDGE_CHECK_PEER", mode)
	exe, _ := os.Executable()
	integration := t.TempDir()
	for _, name := range []string{"documentation.md", "bridge-core/phone-pc-bridge.aar", "sdk-check/bridge-pc-check.exe"} {
		path := filepath.Join(integration, name)
		if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte("owned isolated protocol fixture"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	s, err := New(&tsnet.Server{}, Config{Identity: "nPc", Host: "pc.tailtest.ts.net", CodexExecutable: exe, DataDir: t.TempDir(), IntegrationDir: integration, AllowedPeers: []string{"nPi", "nApp"}, BackendPeer: "nPi"})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	sender := "nPi"
	s.authenticate = func(context.Context, string) (string, error) { return sender, nil }
	call := func(input protocol.AdapterRequest) (int, protocol.AdapterReply) {
		t.Helper()
		input.Nonce = strings.Repeat("a", 32)
		body, _ := json.Marshal(input)
		request := httptest.NewRequest("POST", "/internal/v1/codex", bytes.NewReader(body))
		request.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		s.handle(w, request)
		var result protocol.AdapterReply
		if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
			t.Fatal(err)
		}
		return w.Code, result
	}
	sender = "nApp"
	if code, _ := call(protocol.AdapterRequest{Operation: "models"}); code != 403 {
		t.Fatal("Consumer reached private adapter")
	}
	sender = "nPi"
	for _, op := range []string{"models", "create"} {
		if code, result := call(protocol.AdapterRequest{Operation: op, Model: "bridge-check-model", AppID: strings.Repeat("b", 32), RequestID: strings.Repeat("c", 32), CreatedAt: time.Now().UnixMilli(), Name: "Owned creation", ServiceTier: serviceTier}); code != 200 || result.State != "ok" {
			t.Fatalf("%s failed: %v", op, result)
		}
	}
	if _, result := call(protocol.AdapterRequest{Operation: "select", ThreadID: "thread-1"}); result.State != "ok" {
		t.Fatal(result)
	}
	_, result := call(protocol.AdapterRequest{Operation: "send", ThreadID: "thread-1", Text: ownerText, Instructions: "fixture-custom", Model: "bridge-check-model", Effort: "high", AppID: strings.Repeat("b", 32), JobID: strings.Repeat("c", 32), ServiceTier: serviceTier})
	if mode == "app-reject-send" {
		if result.State != "rejected" || result.Code != "native_operation_rejected" || result.Result != nil || result.Failure == nil || result.Failure.RPCCode == nil || *result.Failure.RPCCode != -32000 || !strings.Contains(result.Failure.Message, "Turn rejected") || !strings.Contains(result.Failure.Message, "[redacted input]") || strings.Contains(result.Failure.Message, ownerText) || strings.Contains(result.Failure.Message, "fixture-custom") || strings.Contains(result.Failure.Message, "fixture-secret") || strings.Contains(result.Failure.Message, "handoffPath") || strings.Contains(result.Failure.Message, "handoffHeader") || strings.Contains(result.Failure.Message, "bridgeJobInput") || strings.Contains(result.Failure.Message, integration) || strings.Contains(result.Failure.Message, s.cfg.DataDir) {
			t.Fatal("Native rejection metadata lost or private content escaped", result.State)
		}
		return
	}
	if result.State != "ok" {
		t.Fatal(result)
	}
	var ack struct {
		Input               []map[string]string `json:"receivedInput"`
		ReceivedServiceTier *string             `json:"receivedServiceTier"`
		CreatedServiceTier  *string             `json:"createdServiceTier"`
	}
	if json.Unmarshal(result.Result, &ack) != nil || len(ack.Input) != 1 || len(ack.Input[0]) != 2 || ack.Input[0]["type"] != "text" {
		t.Fatal("Native inputs were merged, changed or workflow omitted")
	}
	message, err := codex.ParseJobMessage(ack.Input[0]["text"])
	if err != nil || message.Prompt != ownerText || message.Instructions != "fixture-custom" || filepath.Dir(message.Path) != filepath.Join(s.cfg.DataDir, "handoffs") || !strings.Contains(message.Header, "\nPC: nPc\n") || !strings.Contains(message.Header, "\nDeveloper thread: thread-1\n") || strings.Contains(ack.Input[0]["text"], "role=app_worker") {
		t.Fatal("Single native message lost immutable input or real-file handoff context", err)
	}
	if serviceTier == "" {
		if ack.ReceivedServiceTier != nil || ack.CreatedServiceTier != nil {
			t.Fatal("Empty tier changed native/default parameter omission")
		}
	} else if ack.ReceivedServiceTier == nil || *ack.ReceivedServiceTier != serviceTier || ack.CreatedServiceTier == nil || *ack.CreatedServiceTier != serviceTier {
		t.Fatal("Native tier wire values changed or were dropped", ack.ReceivedServiceTier, ack.CreatedServiceTier)
	}
	canonical, _ := json.Marshal(Request{Epoch: s.epoch, Operation: "text", Sequence: 1, ThreadID: "thread-1", Text: ownerText, Instructions: "fixture-custom", AppID: strings.Repeat("b", 32), JobID: strings.Repeat("c", 32), ServiceTier: serviceTier})
	expected := fmt.Sprintf("Request fingerprint: %x\n", sha256.Sum256(canonical))
	if !strings.Contains(message.Header, expected) {
		t.Fatal("Actual native handoff fingerprint did not bind exact service tier")
	}
	_, history := call(protocol.AdapterRequest{Operation: "history", ThreadID: "thread-1", Limit: 25})
	if history.State != "ok" || !bytes.Contains(history.Result, []byte(`\"bridgeJobReport\":\"v2\"`)) {
		t.Fatal("Exact protocol report unavailable", history)
	}
	for _, operation := range []string{"executeShell", "idle", "sessions", "read"} {
		_, bad := call(protocol.AdapterRequest{Operation: operation, ThreadID: "thread-1", Limit: 25})
		if bad.Code != "unsupported_adapter_operation" {
			t.Fatal("Unsupported or retired operation accepted", operation)
		}
	}
	for _, fields := range []string{`"operation":"models","operation":"send"`, `"Operation":"models"`, `"operation":"models","OPERATION":"send"`} {
		request := httptest.NewRequest(http.MethodPost, "/internal/v1/codex", strings.NewReader(`{"nonce":"`+strings.Repeat("a", 32)+`",`+fields+`}`))
		request.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		s.handle(w, request)
		if w.Code != 400 {
			t.Fatal("Duplicate or aliased adapter key accepted", fields)
		}
	}
}

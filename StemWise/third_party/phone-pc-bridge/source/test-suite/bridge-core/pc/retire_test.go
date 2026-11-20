package pc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"phonepcbridge/protocol"
	"tailscale.com/tsnet"
)

type retirementFixture struct {
	calls  int
	result json.RawMessage
	err    error
	after  func()
}

func (n *retirementFixture) Select(_ context.Context, id string) (json.RawMessage, error) {
	return json.Marshal(map[string]any{"thread": map[string]any{"id": id, "canAcceptDirectInput": true}})
}

func (n *retirementFixture) RetireTurn(context.Context, string, string) (json.RawMessage, error) {
	n.calls++
	if n.after != nil {
		n.after()
	}
	return n.result, n.err
}

func TestRetirementRequiresImmutableCreationOwnerAndExactNativeReceipt(t *testing.T) {
	s, base, input, _ := creationTestService(t)
	if _, err := s.createThread(context.Background(), "nPi", input, base); err != nil {
		t.Fatal(err)
	}
	for id := range base.threads {
		input.ThreadID = id
	}
	input.Operation, input.TurnID = "retire", "turn-1"
	raw, _ := json.Marshal(map[string]any{"threadId": input.ThreadID, "turnId": input.TurnID, "terminalsRetired": true})
	native := &retirementFixture{result: raw}
	for _, field := range []string{"app", "request", "time", "name", "model", "thread", "turn", "duplicate_job"} {
		wrong := input
		switch field {
		case "app":
			wrong.AppID = randomToken()
		case "request":
			wrong.RequestID = randomToken()
		case "time":
			wrong.CreatedAt--
		case "name":
			wrong.Name = "changed"
		case "model":
			wrong.Model = "changed"
		case "thread":
			wrong.ThreadID = "foreign-thread"
		case "turn":
			wrong.TurnID = ""
		case "duplicate_job":
			wrong.JobID = input.RequestID
		}
		if _, err := s.retireCreation(context.Background(), "nPi", wrong, native); err == nil || native.calls != 0 {
			t.Fatal("Unowned retirement reached native", field, err)
		}
	}
	for _, result := range []string{`{}`, `{"threadId":"other","turnId":"turn-1","terminalsRetired":true}`, `{"threadId":"` + input.ThreadID + `","turnId":"other","terminalsRetired":true}`, `{"threadId":"` + input.ThreadID + `","turnId":"turn-1","terminalsRetired":false}`, `{"threadId":"` + input.ThreadID + `","turnId":"turn-1","terminalsRetired":true,"extra":true}`} {
		native.result = json.RawMessage(result)
		_, err := s.retireCreation(context.Background(), "nPi", input, native)
		var failure *creationError
		if !errors.As(err, &failure) || failure.code != "turn_retirement_unconfirmed" || !failure.unknown {
			t.Fatal("Native ack became verified retirement", err)
		}
	}
	if _, err := s.retireCreation(context.Background(), "nPi", input, &retirementFixture{result: raw}); err != nil {
		t.Fatal("Exact retirement receipt rejected", err)
	}
	native = &retirementFixture{result: raw, after: func() {
		if err := s.creations.Remove("nPi", creationID("nPi", input.AppID, input.RequestID, "result")); err != nil {
			t.Error(err)
		}
	}}
	_, err := s.retireCreation(context.Background(), "nPi", input, native)
	var failure *creationError
	if !errors.As(err, &failure) || !failure.unknown {
		t.Fatal("Changed ownership committed retirement", err)
	}
}

func TestHTTPAdapterRetiresOnlyExactOwnedNativeTurn(t *testing.T) {
	for _, mode := range []string{"retire", "retire-running", "retire-rejected", "retire-reopen", "retire-owned-select-reopen", "retire-reopen-denied"} {
		t.Run(mode, func(t *testing.T) { testHTTPAdapterRetiresOnlyExactOwnedNativeTurn(t, mode) })
	}
}
func testHTTPAdapterRetiresOnlyExactOwnedNativeTurn(t *testing.T, mode string) {
	t.Setenv("BRIDGE_CHECK_PEER", mode)
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	s, err := New(&tsnet.Server{}, Config{Identity: "nPc", Host: "pc.tailtest.ts.net", CodexExecutable: executable, DataDir: t.TempDir(), IntegrationDir: t.TempDir(), AllowedPeers: []string{"nPi"}, BackendPeer: "nPi"})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	s.authenticate = func(context.Context, string) (string, error) { return "nPi", nil }
	call := func(input protocol.AdapterRequest) protocol.AdapterReply {
		t.Helper()
		input.Nonce = randomToken()
		raw, _ := json.Marshal(input)
		r := httptest.NewRequest("POST", "/internal/v1/codex", bytes.NewReader(raw))
		r.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		s.handle(w, r)
		var result protocol.AdapterReply
		if w.Code != 200 || protocol.DecodeStrict(w.Body.Bytes(), &result) != nil || result.Nonce != input.Nonce {
			t.Fatal("Invalid adapter receipt", w.Code, w.Body.String())
		}
		return result
	}
	input := protocol.AdapterRequest{Operation: "create", AppID: randomToken(), RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Name: "owned retirement", Model: "fixture"}
	if result := call(input); result.State != "ok" {
		t.Fatal(result)
	}
	if result := call(protocol.AdapterRequest{Operation: "select", ThreadID: "thread-1"}); result.State != "ok" {
		t.Fatal(result)
	}
	previous := s.peers["nPi"].conn
	if strings.Contains(mode, "reopen") {
		if err := previous.Close(); err != nil {
			t.Fatal(err)
		}
	}
	if mode == "retire-owned-select-reopen" {
		restore := input
		restore.Operation, restore.ThreadID = "select", "thread-1"
		wrong := restore
		wrong.AppID = randomToken()
		if result := call(wrong); result.State != "rejected" || result.Code != "chat_ownership_unconfirmed" {
			t.Fatal("Owned restoration admitted another app", result)
		}
		if result := call(restore); result.State != "ok" {
			t.Fatal("Exact owned writer failed to restore after actual connection reopen", result)
		}
	}
	input.Operation, input.ThreadID, input.TurnID = "retire", "thread-1", "turn-1"
	result := call(input)
	if strings.Contains(mode, "reopen") && s.peers["nPi"].conn == previous {
		t.Fatal("Fixture did not reopen the actual native connection")
	}
	if mode == "retire-reopen-denied" {
		if result.State != "unknown" || result.Code != "chat_writer_unconfirmed" || result.Result != nil || result.Failure == nil || result.Failure.Process != nil || !strings.Contains(result.Failure.Message, "Native writer does not confirm direct turn input") {
			t.Fatal("Writer denial after reopen did not stay pending before native cleanup", result)
		}
		return
	}
	if mode == "retire-running" || mode == "retire-rejected" {
		if result.State != "unknown" || result.Code != "turn_retirement_unconfirmed" || result.Result != nil || result.Failure == nil {
			t.Fatal("Unconfirmed native retirement claimed completion", result)
		}
		if mode == "retire-running" && !strings.Contains(result.Failure.Message, "Native background terminals are still running") {
			t.Fatal("Useful bounded technical cause lost", result.Failure.Message)
		}
		if mode == "retire-rejected" && (strings.Contains(result.Failure.Message, "private-retirement-native-data") || !strings.Contains(result.Failure.Message, "Codex rejected request (-32009)")) {
			t.Fatal("Arbitrary native RPC data exposed or rejection code lost", result.Failure.Message)
		}
		return
	}
	var receipt struct {
		ThreadID         string `json:"threadId"`
		TurnID           string `json:"turnId"`
		TerminalsRetired bool   `json:"terminalsRetired"`
	}
	if result.State != "ok" || protocol.DecodeStrict(result.Result, &receipt) != nil || receipt.ThreadID != input.ThreadID || receipt.TurnID != input.TurnID || !receipt.TerminalsRetired {
		t.Fatal("Verified native retirement did not cross adapter", result)
	}
	input.ThreadID = "foreign-thread"
	if result := call(input); result.State != "rejected" || result.Code != "chat_ownership_unconfirmed" || result.Result != nil {
		t.Fatal("Foreign thread retirement admitted", result)
	}
}

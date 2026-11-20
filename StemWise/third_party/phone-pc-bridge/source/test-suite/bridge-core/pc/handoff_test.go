package pc

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"phonepcbridge/codex"
	"phonepcbridge/files"
	"phonepcbridge/protocol"
	"tailscale.com/tsnet"
)

func TestHandoffLimitsMatchEnforcedProtocol(t *testing.T) {
	s := &Service{cfg: Config{DataDir: t.TempDir(), Identity: "nPc", Host: "pc.tailtest.ts.net"}, epoch: randomToken()}
	file, err := s.createHandoff(context.Background(), "nAlice", Request{Epoch: s.epoch, Operation: "text", ThreadID: "owned-chat", Sequence: 1, Text: "owned"})
	if err != nil {
		t.Fatal(err)
	}
	actual, err := os.ReadFile(file.path)
	if err != nil {
		t.Fatal(err)
	}
	for _, required := range []string{fmt.Sprintf("maxFileBytes: %d", protocol.MaxFileBytes), fmt.Sprintf("bufferApiBytes: %d", protocol.MaxBufferedFileBytes), fmt.Sprintf("transferTimeoutMs: %d", protocol.FileTransferTimeout.Milliseconds()), fmt.Sprintf("retentionMs: %d", protocol.Retention.Milliseconds()), "fileTransport: streaming"} {
		if !strings.Contains(string(actual), required) {
			t.Fatal("Actual injected handoff lost enforced limits", required)
		}
	}
}

func TestHandoffWakeGuidanceAllowsOnlyExplicitUserActions(t *testing.T) {
	s := &Service{cfg: Config{DataDir: t.TempDir(), Identity: "nPc", Host: "pc.tailtest.ts.net"}, epoch: randomToken()}
	file, err := s.createHandoff(context.Background(), "nAlice", Request{Epoch: s.epoch, Operation: "text", ThreadID: "owned-chat", Sequence: 1, Text: "owned"})
	if err != nil {
		t.Fatal(err)
	}
	actual, err := os.ReadFile(file.path)
	if err != nil {
		t.Fatal(err)
	}
	for _, required := range []string{"explicit user Wake, Send to Bridge, Retry or settings Wake action", "one empty-body POST /v2/connection/wake", "durably claim", "Passive polling, reconnect, startup, background recovery, job execution and agents never authorize wake", "new explicit user action"} {
		if !strings.Contains(string(actual), required) {
			t.Fatal("Actual worker handoff lost user-action wake policy", required)
		}
	}
}

func TestHandoffRequiresReservedCommentaryTokensWithoutStoppingTurn(t *testing.T) {
	s := &Service{cfg: Config{DataDir: t.TempDir(), Identity: "nPc", Host: "pc.tailtest.ts.net"}, epoch: randomToken()}
	file, err := s.createHandoff(context.Background(), "nAlice", Request{Epoch: s.epoch, Operation: "text", ThreadID: "owned-chat", Sequence: 1, Text: "owned"})
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(file.path)
	if err != nil {
		t.Fatal(err)
	}
	for _, required := range []string{"[[bridge:preparing]], [[bridge:reading]], [[bridge:processing]], [[bridge:testing]], [[bridge:packaging]], [[bridge:transferring]], [[bridge:waiting]]", "entire progress message MUST be only that one exact token", "Do not add a heading, bullet, bold/italic, backticks, code fence, quote, punctuation, prefix, suffix, explanation, percentage or JSON", "Continue your work and tool calls in the same native turn", "No interim JSON report, tool call, final response or extra turn is required for progress", "does not replace the final report"} {
		if !strings.Contains(string(data), required) {
			t.Fatal("Actual injected MD lost reserved-token progress contract", required)
		}
	}
}

func TestMandatoryHandoffBoundary(t *testing.T) {
	t.Setenv("BRIDGE_CHECK_PEER", "handoff")
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	runtimeDir := t.TempDir()
	producer := filepath.Join(runtimeDir, "sdk-check", "bridge-pc-check.exe")
	if err := os.Mkdir(filepath.Dir(producer), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(producer, []byte("isolated producer executable fixture"), 0600); err != nil {
		t.Fatal(err)
	}
	for _, packaging := range []string{"documentation.md", filepath.Join("bridge-core", "phone-pc-bridge.aar")} {
		if _, err := os.Stat(filepath.Join(runtimeDir, packaging)); !os.IsNotExist(err) {
			t.Fatal("Runtime-only fixture unexpectedly contains a packaging artifact", packaging, err)
		}
	}
	s, err := New(&tsnet.Server{}, Config{Identity: "nPc", Host: "pc.tailtest.ts.net", DataDir: dir, IntegrationDir: runtimeDir, CodexExecutable: executable, AllowedPeers: []string{"nAlice"}, BackendPeer: "nAlice"})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	s.authenticate = func(context.Context, string) (string, error) { return "nAlice", nil }
	call := func(input protocol.AdapterRequest) protocol.AdapterReply {
		t.Helper()
		input.Nonce = randomToken()
		data, _ := json.Marshal(input)
		request := httptest.NewRequest(http.MethodPost, "/internal/v1/codex", bytes.NewReader(data))
		request.Header.Set("Content-Type", "application/json")
		response := httptest.NewRecorder()
		s.handle(response, request)
		var result protocol.AdapterReply
		if json.Unmarshal(response.Body.Bytes(), &result) != nil || (response.Code != 200 && !(response.Code == 409 && result.State == "conflict")) {
			t.Fatal(response.Code, response.Body.String())
		}
		return result
	}
	if r := call(protocol.AdapterRequest{Operation: "select", ThreadID: "thread-1"}); r.State != "ok" {
		t.Fatal(r)
	}
	text := "Owner text unchanged: العربية 😀 <>&"
	req := protocol.AdapterRequest{Operation: "send", ThreadID: "thread-1", Text: text, Instructions: "Keep https://example.invalid/job?x=1&y=2 and quoted \"prompt\" unchanged.\nالعربية", AppID: strings.Repeat("a", 32), JobID: strings.Repeat("b", 32)}
	owner := protocol.AdapterRequest{Operation: "create", AppID: req.AppID, RequestID: req.JobID, CreatedAt: time.Now().UnixMilli(), Name: "Owned handoff"}
	if result := call(owner); result.State != "ok" {
		t.Fatal("Actual job creation was not accepted", result)
	}
	inputBytes := []byte("An actual received input")
	input, err := files.Describe(strings.Repeat("d", 32), strings.Repeat("&", protocol.MaxFileNameBytes), inputBytes)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.inbox.Put("nAlice", input, bytes.NewReader(inputBytes)); err != nil {
		t.Fatal(err)
	}
	req.Files = []protocol.File{input}
	for i := 1; i < protocol.MaxInputFiles; i++ {
		f, err := files.Describe(fmt.Sprintf("%032x", i), strings.Repeat("&", protocol.MaxFileNameBytes), inputBytes)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := s.inbox.Put("nAlice", f, bytes.NewReader(inputBytes)); err != nil {
			t.Fatal(err)
		}
		req.Files = append(req.Files, f)
	}
	ack := call(req)
	if ack.State != "ok" {
		t.Fatal(ack)
	}
	var received struct {
		Input []map[string]string `json:"receivedInput"`
	}
	if json.Unmarshal(ack.Result, &received) != nil || len(received.Input) != 1 || received.Input[0]["type"] != "text" {
		t.Fatal("Native owner input changed or handoff absent")
	}
	message, err := codex.ParseJobMessage(received.Input[0]["text"])
	if err != nil || message.Prompt != text || message.Instructions != req.Instructions {
		t.Fatal("Single native message changed original text, custom instructions or links", err)
	}
	path := message.Path
	data, err := os.ReadFile(path)
	hash := sha256.Sum256(data)
	if err != nil || message.SHA256 != hex.EncodeToString(hash[:]) || !strings.HasPrefix(string(data), message.Header) || strings.Contains(string(data), text) || !strings.Contains(string(data), "Paired sender: nAlice\n") || strings.Contains(received.Input[0]["text"], "role=app_worker") {
		t.Fatal("Native context differs from real scoped file", err)
	}
	if !strings.Contains(string(data), "App ID: "+req.AppID+"\nJob ID: "+req.JobID+"\n") {
		t.Fatal("Authenticated envelope missing from actual native Markdown")
	}
	if !bytes.Contains(data, []byte(producer)) {
		t.Fatal("Runtime dispatch lost its actual producer path")
	}
	if !bytes.Contains(data, []byte(input.ID)) || !bytes.Contains(data, []byte(input.SHA256)) || !bytes.Contains(data, []byte("immutable input files")) {
		t.Fatal("Native context omitted actual immutable inputs")
	}
	if r := call(protocol.AdapterRequest{Operation: "interrupt", ThreadID: "thread-1", TurnID: "turn-1"}); r.State != "ok" {
		t.Fatal(r)
	}
	deadline := time.Now().Add(time.Second)
	for {
		entries, err := os.ReadDir(filepath.Dir(path))
		if err != nil {
			t.Fatal(err)
		}
		if len(entries) == 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("Confirmed terminal turn retained attachments")
		}
		time.Sleep(time.Millisecond)
	}
	if err := os.Remove(producer); err != nil {
		t.Fatal(err)
	}
	missing := req
	missing.JobID, missing.Text, missing.Files = strings.Repeat("c", 32), "Missing producer must not reach native", nil
	owner.RequestID = missing.JobID
	if result := call(owner); result.State != "ok" {
		t.Fatal("Missing-producer fixture lacks its actual owned creation", result)
	}
	for _, producerState := range []string{"absent", "directory"} {
		if producerState == "directory" {
			if err := os.Mkdir(producer, 0700); err != nil {
				t.Fatal(err)
			}
		}
		failure := call(missing)
		if failure.State != "rejected" || failure.Code != "native_operation_rejected" || failure.Failure == nil || !strings.Contains(failure.Failure.Message, "pc_producer_unavailable") || !strings.Contains(failure.Failure.Message, producer) {
			t.Fatal("Missing or nonregular producer lacked an actionable pre-send rejection", producerState, failure)
		}
		if state := call(protocol.AdapterRequest{Operation: "select", ThreadID: "thread-1"}); state.State != "ok" || !bytes.Contains(state.Result, []byte(`"type":"idle"`)) {
			t.Fatal("Missing producer started native work", producerState, state)
		}
	}
	// Removing storage rejects before native turn creation; no skip flag exists.
	s.cfg.DataDir = ""
	if r := call(protocol.AdapterRequest{Operation: "send", ThreadID: "thread-1", Text: "Must not reach native"}); r.State != "rejected" {
		t.Fatal("Missing handoff storage permitted send", r)
	}
	if r := call(protocol.AdapterRequest{Operation: "select", ThreadID: "thread-1"}); r.State != "ok" || !bytes.Contains(r.Result, []byte(`"type":"idle"`)) {
		t.Fatal("Failed context started native work", r)
	}
}

func TestInputHandoffRequiresActualPairedRecipientBytes(t *testing.T) {
	dir := t.TempDir()
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	s, err := New(&tsnet.Server{}, Config{Identity: "nPc", Host: "pc.tailtest.ts.net", DataDir: dir, CodexExecutable: executable, AllowedPeers: []string{"nAlice"}, BackendPeer: "nAlice"})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	content := []byte("Independent recipient bytes")
	input, err := files.Describe(strings.Repeat("e", 32), "input.bin", content)
	if err != nil {
		t.Fatal(err)
	}
	r := Request{Operation: "text", ThreadID: "thread-1", Sequence: 1, Text: "Original request", AppID: strings.Repeat("a", 32), JobID: strings.Repeat("b", 32), InputFiles: []protocol.File{input}}
	if _, err := s.createHandoff(context.Background(), "nAlice", r); err == nil {
		t.Fatal("Absent input accepted")
	}
	if _, err := s.inbox.Put("nOther", input, bytes.NewReader(content)); err != nil {
		t.Fatal(err)
	}
	if _, err := s.createHandoff(context.Background(), "nAlice", r); err == nil {
		t.Fatal("Other peer's input accepted")
	}
	if _, err := s.inbox.Put("nAlice", input, bytes.NewReader(content)); err != nil {
		t.Fatal(err)
	}
	r.InputFiles[0].Name = "changed.bin"
	if _, err := s.createHandoff(context.Background(), "nAlice", r); err == nil {
		t.Fatal("Changed input descriptor accepted")
	}
	r.InputFiles[0] = input
	if err := os.WriteFile(filepath.Join(dir, "incoming", "nAlice", input.ID, "content"), bytes.Repeat([]byte("x"), len(content)), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := s.createHandoff(context.Background(), "nAlice", r); err == nil {
		t.Fatal("Metadata alone accepted corrupt recipient bytes")
	}
	if entries, err := os.ReadDir(filepath.Join(dir, "handoffs")); err == nil && len(entries) != 0 {
		t.Fatal("Rejected input created a developer handoff")
	}
}

func TestHandoffRetirementScopeAndRestartHistory(t *testing.T) {
	s := &Service{cfg: Config{DataDir: t.TempDir(), Identity: "nPc", Host: "pc.tailtest.ts.net"}, epoch: randomToken(), handoffs: make(map[handoffKey][]handoffFile)}
	r := Request{Epoch: s.epoch, Operation: "text", ThreadID: "thread-1", Sequence: 1, Text: "test"}
	file, err := s.createHandoff(context.Background(), "nAlice", r)
	if err != nil {
		t.Fatal(err)
	}
	c, foreign := &codex.Connection{}, &codex.Connection{}
	finish := func(connection *codex.Connection, thread, turn, status string) {
		p, _ := json.Marshal(map[string]any{"threadId": thread, "turn": map[string]string{"id": turn, "status": status}})
		s.observeHandoff(connection, codex.Message{Method: "turn/completed", Params: p})
	}
	// Terminal notification can arrive before the native acknowledgment is handled.
	finish(foreign, "thread-1", "turn-1", "completed")
	finish(c, "wrong-thread", "turn-1", "completed")
	finish(c, "thread-1", "wrong-turn", "completed")
	finish(c, "thread-1", "turn-1", "inProgress")
	s.trackHandoff(c, r, json.RawMessage(`{"turn":{"id":"turn-1"}}`), file)
	if _, err = os.Stat(file.path); err != nil {
		t.Fatal("Foreign or nonterminal notification retired context")
	}
	finish(c, "thread-1", "turn-1", "completed")
	if _, err = os.Stat(file.path); !os.IsNotExist(err) {
		t.Fatal("Exact completion did not retire context")
	}
	r.Sequence = 2
	file, err = s.createHandoff(context.Background(), "nAlice", r)
	if err != nil {
		t.Fatal(err)
	}
	finish(c, "thread-1", "turn-2", "completed")
	s.trackHandoff(c, r, json.RawMessage(`{"turn":{"id":"turn-2"}}`), file)
	if _, err = os.Stat(file.path); !os.IsNotExist(err) {
		t.Fatal("Completion before ack leaked context")
	}
}

func TestHandoffStorageLimitAndWriteFailure(t *testing.T) {
	dir := t.TempDir()
	s := &Service{cfg: Config{DataDir: dir, Identity: "nPc", Host: "pc.tailtest.ts.net"}, epoch: randomToken()}
	if err := os.WriteFile(filepath.Join(dir, "handoffs"), []byte("not a directory"), 0600); err != nil {
		t.Fatal(err)
	}
	r := Request{Epoch: s.epoch, Operation: "text", ThreadID: "thread-1", Text: "test", Sequence: 1}
	if _, err := s.createHandoff(context.Background(), "nAlice", r); err == nil {
		t.Fatal("Invalid storage accepted")
	}
	if err := os.Remove(filepath.Join(dir, "handoffs")); err != nil {
		t.Fatal(err)
	}
	for n := int64(1); n <= 128; n++ {
		r.Sequence = n
		if _, err := s.createHandoff(context.Background(), "nAlice", r); err != nil {
			t.Fatal(err)
		}
	}
	r.Sequence = 129
	if _, err := s.createHandoff(context.Background(), "nAlice", r); err == nil {
		t.Fatal("Unbounded context files")
	}
}

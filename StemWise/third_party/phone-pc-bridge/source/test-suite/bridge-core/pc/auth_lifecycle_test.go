package pc

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
	"tailscale.com/tsnet"
)

// This executable owns an actual stdio process; its account is fixed at startup.
func runAuthPeer(mode string) {
	home := os.Getenv("CODEX_HOME")
	source, _ := os.ReadFile(filepath.Join(home, "auth.json"))
	var saved map[string]string
	_ = json.Unmarshal(source, &saved)
	pid := os.Getpid()
	encoder := json.NewEncoder(os.Stdout)
	configReads := 0
	input := bufio.NewScanner(os.Stdin)
	input.Buffer(make([]byte, 4096), 1<<20)
	for input.Scan() {
		var message struct {
			ID     json.RawMessage            `json:"id"`
			Method string                     `json:"method"`
			Params map[string]json.RawMessage `json:"params"`
		}
		if json.Unmarshal(input.Bytes(), &message) != nil {
			os.Exit(61)
		}
		if len(message.ID) == 0 {
			continue
		}
		log, err := os.OpenFile(os.Getenv("BRIDGE_AUTH_EVENTS"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
		if err != nil {
			os.Exit(62)
		}
		_ = json.NewEncoder(log).Encode(map[string]any{"pid": pid, "method": message.Method, "account": saved["account"]})
		_ = log.Close()
		active := false
		if _, err := os.Stat(filepath.Join(home, "active")); err == nil {
			active = true
		}
		status := "idle"
		if active {
			status = "active"
		}
		thread := "owned-thread"
		if value := message.Params["threadId"]; len(value) > 0 {
			_ = json.Unmarshal(value, &thread)
		}
		var result any = map[string]any{}
		switch message.Method {
		case "initialize":
			result = map[string]any{"userAgent": "isolated-auth-fixture"}
		case "config/read":
			configReads++
			if saved["account"] == "a" && (mode == "rotate-before-start" && configReads == 3 || mode == "rotate-before-send" && configReads == 6) {
				_ = os.WriteFile(filepath.Join(home, "auth.json"), []byte(`{"account":"b","credential":"late-private"}`), 0600)
			}
			store := "file"
			if mode == "keyring" {
				store = "keyring"
			}
			if _, err := os.Stat(filepath.Join(home, "hold-config")); err == nil {
				_ = os.WriteFile(filepath.Join(home, "config-waiting"), []byte("waiting"), 0600)
				deadline := time.Now().Add(4 * time.Second)
				for time.Now().Before(deadline) {
					if _, err := os.Stat(filepath.Join(home, "hold-config")); os.IsNotExist(err) {
						break
					}
					time.Sleep(5 * time.Millisecond)
				}
			}
			result = map[string]any{"config": map[string]any{"cli_auth_credentials_store": store, "model_provider": "openai"}, "origins": map[string]any{}, "layers": nil}
		case "account/read":
			if saved["account"] == "" {
				result = map[string]any{"account": nil, "requiresOpenaiAuth": true}
			} else {
				result = map[string]any{"account": map[string]any{"type": "chatgpt", "email": "fixture@example.invalid", "planType": "pro"}, "requiresOpenaiAuth": true}
			}
		case "account/rateLimits/read":
			if mode == "unauthorized" {
				_ = encoder.Encode(map[string]any{"id": message.ID, "error": map[string]any{"code": 401, "message": "private-credential-must-never-escape"}})
				continue
			}
			if mode == "rotate-once" && saved["account"] == "a" {
				_ = os.WriteFile(filepath.Join(home, "auth.json"), []byte(`{"account":"b","credential":"rotated-private"}`), 0600)
			}
			result = map[string]any{"rateLimits": map[string]any{"primary": nil, "secondary": nil}, "rateLimitsByLimitId": nil}
		case "thread/loaded/list":
			result = map[string]any{"data": []string{"owned-thread"}, "nextCursor": nil}
		case "thread/read", "thread/resume", "thread/start":
			result = map[string]any{"thread": map[string]any{"id": thread, "cwd": os.Getenv("BRIDGE_AUTH_PROJECT"), "status": map[string]any{"type": status}, "canAcceptDirectInput": true}}
		case "thread/backgroundTerminals/list":
			result = map[string]any{"data": []any{}, "nextCursor": nil}
		case "model/list":
			result = map[string]any{"data": []any{}, "nextCursor": nil}
		default:
			_ = encoder.Encode(map[string]any{"id": message.ID, "error": map[string]any{"code": -32601, "message": "fixture-unsupported"}})
			continue
		}
		_ = encoder.Encode(map[string]any{"id": message.ID, "result": result})
	}
}

type authFixture struct {
	service      *Service
	home, events string
	call         func(protocol.AdapterRequest) protocol.AdapterReply
}

func newAuthFixture(t *testing.T, mode string) authFixture {
	t.Helper()
	home, root := t.TempDir(), t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "sdk-check"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "sdk-check", "bridge-pc-check.exe"), []byte("isolated producer-presence fixture"), 0600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("CODEX_HOME", home)
	t.Setenv("BRIDGE_AUTH_PEER", mode)
	t.Setenv("BRIDGE_CHECK_PEER", "")
	events := filepath.Join(root, "events.jsonl")
	t.Setenv("BRIDGE_AUTH_EVENTS", events)
	t.Setenv("BRIDGE_AUTH_PROJECT", root)
	if err := os.WriteFile(filepath.Join(home, "auth.json"), []byte(`{"account":"a","credential":"initial-private"}`), 0600); err != nil {
		t.Fatal(err)
	}
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	s, err := New(&tsnet.Server{}, Config{Identity: "nPc", Host: "pc.test.ts.net", CodexExecutable: executable, AllowedPeers: []string{"nPi"}, BackendPeer: "nPi", DataDir: root, IntegrationDir: root})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	s.authenticate = func(context.Context, string) (string, error) { return "nPi", nil }
	call := func(input protocol.AdapterRequest) protocol.AdapterReply {
		t.Helper()
		input.Nonce = randomToken()
		body, _ := json.Marshal(input)
		r := httptest.NewRequest("POST", "/internal/v1/codex", bytes.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		s.handle(w, r)
		var out protocol.AdapterReply
		if w.Code != 200 || protocol.DecodeStrict(w.Body.Bytes(), &out) != nil || out.Nonce != input.Nonce {
			t.Fatalf("Uncorrelated adapter response: %d %s", w.Code, w.Body.String())
		}
		if strings.Contains(w.Body.String(), "private") || strings.Contains(w.Body.String(), "fixture@example") || strings.Contains(w.Body.String(), home) {
			t.Fatal("Private auth data escaped")
		}
		return out
	}
	return authFixture{s, home, events, call}
}

func (f authFixture) counts(t *testing.T) (map[int]bool, int) {
	t.Helper()
	data, err := os.ReadFile(f.events)
	if err != nil {
		t.Fatal(err)
	}
	pids, starts := map[int]bool{}, 0
	for _, line := range bytes.Split(bytes.TrimSpace(data), []byte{'\n'}) {
		var event struct {
			PID    int
			Method string
		}
		if json.Unmarshal(line, &event) != nil {
			t.Fatal("Invalid fixture event")
		}
		pids[event.PID] = true
		if event.Method == "thread/start" {
			starts++
		}
	}
	return pids, starts
}

func TestExternalAuthChangeBlocksNewCreationUntilActualOwnedRuntimeIdle(t *testing.T) {
	f := newAuthFixture(t, "normal")
	request := protocol.AdapterRequest{Operation: "create", AppID: randomToken(), RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Name: "first", Model: "fixture"}
	if reply := f.call(request); reply.State != "ok" {
		t.Fatal(reply)
	}
	if reply := f.call(protocol.AdapterRequest{Operation: "select", ThreadID: "owned-thread"}); reply.State != "ok" {
		t.Fatal(reply)
	}
	firstJob := request.RequestID
	old := f.service.peers["nPi"].conn
	if err := os.WriteFile(filepath.Join(f.home, "active"), []byte("active"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(f.home, "auth.json"), []byte(`{"account":"b","credential":"new-private"}`), 0600); err != nil {
		t.Fatal(err)
	}
	request.RequestID = randomToken()
	reply := f.call(request)
	if reply.State != "rejected" || reply.Code != "native_auth_change_pending" || len(reply.Result) != 0 {
		t.Fatalf("Changed auth submitted or became unknown: %#v", reply)
	}
	if reply := f.call(protocol.AdapterRequest{Operation: "send", AppID: request.AppID, JobID: firstJob, ThreadID: "owned-thread", Text: "must remain unsent"}); reply.State != "rejected" || reply.Code != "native_auth_change_pending" || len(reply.Result) != 0 {
		t.Fatal("Changed auth submitted a new turn", reply)
	}
	if _, _, err := f.service.creations.Read("nPi", creationID("nPi", request.AppID, firstJob, "inputs")); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Auth-gated send committed dispatch intent", err)
	}
	select {
	case <-old.Done():
		t.Fatal("Active owned runtime was closed")
	default:
	}
	if pids, starts := f.counts(t); len(pids) != 1 || starts != 1 {
		t.Fatal("Active auth switch started another process or native creation", pids, starts)
	}
	if err := os.Remove(filepath.Join(f.home, "active")); err != nil {
		t.Fatal(err)
	}
	if reply := f.call(request); reply.State != "ok" {
		t.Fatal("Idle native account change did not recover", reply)
	}
	if pids, starts := f.counts(t); len(pids) != 2 || starts != 2 {
		t.Fatal("Account change did not reload exactly once", pids, starts)
	}
	select {
	case <-old.Done():
	default:
		t.Fatal("Old owned process was not retired")
	}
	if reply := f.call(request); reply.State != "ok" {
		t.Fatal(reply)
	}
	if _, starts := f.counts(t); starts != 2 {
		t.Fatal("Recovery replayed original creation", starts)
	}
}

func TestAuthFailureBeforeMutationReturnsDefinitiveRefusalWithoutCredentialExposure(t *testing.T) {
	f := newAuthFixture(t, "unauthorized")
	input := protocol.AdapterRequest{Operation: "create", AppID: randomToken(), RequestID: randomToken(), CreatedAt: 1, Name: "not admitted"}
	result := f.call(input)
	if result.State != "rejected" || result.Code != "native_auth_unavailable" || result.Failure != nil || len(result.Result) != 0 {
		t.Fatal("Auth failure became mutation or uncertain outcome", result)
	}
	if _, starts := f.counts(t); starts != 0 {
		t.Fatal("Unauthorized runtime created native work")
	}
	if _, _, err := f.service.creations.Read("nPi", creationID("nPi", input.AppID, input.RequestID, "request")); !os.IsNotExist(err) {
		t.Fatal("Preflight failure reserved creation", err)
	}
}

func TestAuthObservationHasNoReusableAuthorizationAndDoesNotExportIdentity(t *testing.T) {
	f := newAuthFixture(t, "normal")
	reply := f.call(protocol.AdapterRequest{Operation: "auth"})
	var status map[string]any
	if reply.State != "ok" || json.Unmarshal(reply.Result, &status) != nil || status["state"] != "ready" || status["code"] != "native_auth_ready" || status["validForMs"] != float64(0) || len(status) != 4 {
		t.Fatalf("Invalid safe auth observation: %s", reply.Result)
	}
	if _, starts := f.counts(t); starts != 0 {
		t.Fatal("Auth observation started native work")
	}
	if at, _ := status["observedAt"].(float64); at <= 0 {
		t.Fatal("Missing observation time")
	}
}

func TestAuthSourceRotationDuringPreflightIsNotAuthorizedUntilNewRuntimeVerifiesIt(t *testing.T) {
	f := newAuthFixture(t, "rotate-once")
	first := f.call(protocol.AdapterRequest{Operation: "auth"})
	var status map[string]any
	if json.Unmarshal(first.Result, &status) != nil || status["code"] != "native_auth_change_pending" {
		t.Fatal("Preflight source rotation silently authorized cached auth", string(first.Result))
	}
	second := f.call(protocol.AdapterRequest{Operation: "auth"})
	if json.Unmarshal(second.Result, &status) != nil || status["state"] != "ready" {
		t.Fatal("Rotated source failed verified reload", string(second.Result))
	}
	if pids, starts := f.counts(t); len(pids) != 2 || starts != 0 {
		t.Fatal("Source rotation submitted work or failed bounded reload", pids, starts)
	}
}

func TestUnknownCreationAndUnsupportedSourceCannotAuthorizeRuntimeReplacement(t *testing.T) {
	for _, mode := range []string{"keyring", "unknown-reservation"} {
		t.Run(mode, func(t *testing.T) {
			f := newAuthFixture(t, mode)
			if mode == "unknown-reservation" {
				if result := f.call(protocol.AdapterRequest{Operation: "auth"}); result.State != "ok" {
					t.Fatal(result)
				}
				request := creationInput{AppID: randomToken(), RequestID: randomToken(), CreatedAt: 1, Name: "uncertain", Project: f.service.cfg.IntegrationDir}
				raw, _ := json.Marshal(request)
				file, _ := files.Describe(creationID("nPi", request.AppID, request.RequestID, "request"), "creation-request.json", raw)
				if _, err := f.service.creations.Put("nPi", file, bytes.NewReader(raw)); err != nil {
					t.Fatal(err)
				}
				if err := os.WriteFile(filepath.Join(f.home, "auth.json"), []byte(`{"account":"b"}`), 0600); err != nil {
					t.Fatal(err)
				}
			}
			result := f.call(protocol.AdapterRequest{Operation: "auth"})
			var status map[string]any
			want := "blocked"
			if mode == "keyring" {
				want = "unavailable"
			}
			if json.Unmarshal(result.Result, &status) != nil || status["state"] != want {
				t.Fatal("Unverified source/owner became ready", string(result.Result))
			}
			if pids, starts := f.counts(t); len(pids) != 1 || starts != 0 {
				t.Fatal("Unverified source/owner replaced runtime or submitted", pids, starts)
			}
		})
	}
}

func TestActualAdapterAuthLeasePreservesCancelledWaiterAndIdleCollectionProgress(t *testing.T) {
	f := newAuthFixture(t, "normal")
	hold := filepath.Join(f.home, "hold-config")
	if err := os.WriteFile(hold, []byte("hold"), 0600); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Remove(hold) })
	operation := make(chan protocol.AdapterReply, 1)
	go func() { operation <- f.call(protocol.AdapterRequest{Operation: "auth"}) }()
	deadline := time.Now().Add(2 * time.Second)
	for {
		if _, err := os.Stat(filepath.Join(f.home, "config-waiting")); err == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("Actual native auth call did not enter held read")
		}
		time.Sleep(5 * time.Millisecond)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if release, err := f.service.claimNative(ctx); !errors.Is(err, context.DeadlineExceeded) {
		if release != nil {
			release()
		}
		t.Fatal("Cancelled native lease waiter did not retire", err)
	}
	cleanup := make(chan error, 1)
	go func() { _, err := f.service.pruneCreations(time.Now()); cleanup <- err }()
	select {
	case err := <-cleanup:
		if err != nil {
			t.Fatal("Idle collection failed while an unrelated auth operation was held", err)
		}
	case <-time.After(time.Second):
		t.Fatal("Idle collection waited on unrelated native auth work")
	}
	if err := os.Remove(hold); err != nil {
		t.Fatal(err)
	}
	select {
	case result := <-operation:
		if result.State != "ok" {
			t.Fatal(result)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Released auth operation stayed blocked")
	}
}

func TestClosedExactCreationCheckpointDoesNotBlockAuthReloadAfterInterruptedReceiptPurge(t *testing.T) {
	f := newAuthFixture(t, "normal")
	input := protocol.AdapterRequest{Operation: "create", AppID: randomToken(), RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Name: "closed"}
	if result := f.call(input); result.State != "ok" {
		t.Fatal(result)
	}
	request := creationInput{AppID: input.AppID, RequestID: input.RequestID, CreatedAt: input.CreatedAt, Name: input.Name, Project: f.service.cfg.IntegrationDir}
	if err := f.service.writeDeletedCreation("nPi", request, "owned-thread"); err != nil {
		t.Fatal(err)
	}
	if err := f.service.creations.Remove("nPi", creationID("nPi", input.AppID, input.RequestID, "request")); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(f.home, "auth.json"), []byte(`{"account":"b"}`), 0600); err != nil {
		t.Fatal(err)
	}
	result := f.call(protocol.AdapterRequest{Operation: "auth"})
	var status map[string]any
	if json.Unmarshal(result.Result, &status) != nil || status["state"] != "ready" {
		t.Fatal("Exact closed receipt prevented idle auth reload", string(result.Result))
	}
	if pids, _ := f.counts(t); len(pids) != 2 {
		t.Fatal("Closed receipt did not allow exact idle process replacement", pids)
	}
}

func TestCredentialChangeAtFinalNativeCreationBoundaryReleasesExactReservation(t *testing.T) {
	f := newAuthFixture(t, "rotate-before-start")
	input := protocol.AdapterRequest{Operation: "create", AppID: randomToken(), RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Name: "late change"}
	result := f.call(input)
	if result.State != "rejected" || result.Code != "native_auth_change_pending" || result.Failure != nil || len(result.Result) != 0 {
		t.Fatal("Late auth change did not remain proven unsent", result)
	}
	if _, starts := f.counts(t); starts != 0 {
		t.Fatal("Late change submitted native creation", starts)
	}
	if _, _, err := f.service.creations.Read("nPi", creationID("nPi", input.AppID, input.RequestID, "request")); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Safe no-send kept an uncertain creation reservation", err)
	}
	if result := f.call(input); result.State != "ok" {
		t.Fatal("Original immutable creation did not recover", result)
	}
	if pids, starts := f.counts(t); len(pids) != 2 || starts != 1 {
		t.Fatal("Late-change retry replayed or failed to reload", pids, starts)
	}
}

func TestCredentialChangeAtFinalNativeSendBoundaryDoesNotWriteTurn(t *testing.T) {
	f := newAuthFixture(t, "rotate-before-send")
	input := protocol.AdapterRequest{Operation: "create", AppID: randomToken(), RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Name: "late send change"}
	if result := f.call(input); result.State != "ok" {
		t.Fatal(result)
	}
	if result := f.call(protocol.AdapterRequest{Operation: "select", ThreadID: "owned-thread"}); result.State != "ok" {
		t.Fatal(result)
	}
	result := f.call(protocol.AdapterRequest{Operation: "send", AppID: input.AppID, JobID: input.RequestID, ThreadID: "owned-thread", Text: "exact original", Files: []protocol.File{}})
	if result.State != "rejected" || result.Code != "native_auth_change_pending" || result.Failure != nil || len(result.Result) != 0 {
		t.Fatal("Late send change did not remain proven unsent", result)
	}
	events, err := os.ReadFile(f.events)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(events, []byte(`"method":"turn/start"`)) {
		t.Fatal("Native turn was attempted after source change")
	}
	if count, _, _, _ := f.service.handoffSnapshot(); count != 0 {
		t.Fatal("Proven unsent attachment remained active", count)
	}
}

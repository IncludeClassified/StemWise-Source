package codex

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"phonepcbridge/internal/checksync"
)

// An isolated stdio provider speaks the real requests; it never reads live authentication.
func TestAuthenticationPeerProcess(t *testing.T) {
	if len(os.Args) < 2 || os.Args[1] != "-test.run=^TestAuthenticationPeerProcess$" {
		return
	}
	mode := os.Getenv("BRIDGE_AUTH_FIXTURE")
	scanner := bufio.NewScanner(os.Stdin)
	counts := map[string]int{}
	var held json.RawMessage
	var heldInspection json.RawMessage
	emit := func(value any) { raw, _ := json.Marshal(value); fmt.Println(string(raw)) }
	for scanner.Scan() {
		var message struct {
			ID     json.RawMessage
			Method string
			Params map[string]json.RawMessage
		}
		if json.Unmarshal(scanner.Bytes(), &message) != nil {
			os.Exit(95)
		}
		counts[message.Method]++
		var result any = map[string]any{}
		switch message.Method {
		case "initialized":
			continue
		case "account/read":
			var refresh bool
			if len(message.Params) != 1 || json.Unmarshal(message.Params["refreshToken"], &refresh) != nil {
				os.Exit(96)
			}
			if mode == "refresh_required" && !refresh {
				os.Exit(97)
			}
			result = map[string]any{"account": map[string]any{"type": "chatgpt", "email": "private@example.invalid", "accountId": "private-account-id", "planType": "private-plan"}, "requiresOpenaiAuth": true}
			switch mode {
			case "signed_out":
				result = map[string]any{"account": nil, "requiresOpenaiAuth": true}
			case "local_provider":
				result = map[string]any{"account": nil, "requiresOpenaiAuth": false}
			case "api_key":
				result = map[string]any{"account": map[string]any{"type": "apiKey", "token": "private-token"}, "requiresOpenaiAuth": true}
			case "bad_account":
				result = map[string]any{"account": map[string]any{"type": "future-mode"}, "requiresOpenaiAuth": true}
			case "missing_requires":
				result = map[string]any{"account": nil}
			case "auth_reject":
				emit(map[string]any{"id": message.ID, "error": map[string]any{"code": -32007, "message": "Authorization: Bearer private-token private@example.invalid"}})
				continue
			}
		case "config/read":
			if string(message.Params["includeLayers"]) != "false" {
				os.Exit(98)
			}
			config := map[string]any{"cli_auth_credentials_store": "file", "model_provider": "openai", "forced_login_method": "chatgpt", "api_key": "private-config-token", "forced_chatgpt_workspace_id": "private-workspace-id"}
			result = map[string]any{"config": config}
			switch mode {
			case "keyring", "auto", "ephemeral":
				config["cli_auth_credentials_store"] = mode
			case "missing_store":
				delete(config, "cli_auth_credentials_store")
			case "bad_store":
				config["cli_auth_credentials_store"] = "future-store"
			case "bad_config":
				result = map[string]any{"config": nil}
			case "policy_change":
				config["forced_chatgpt_workspace_id"] = fmt.Sprint("private-workspace-", counts[message.Method])
			}
		case "account/rateLimits/read":
			result = map[string]any{"rateLimits": map[string]any{"primary": map[string]any{"usedPercent": 100}, "secondary": nil, "privateField": "private-rate-account"}}
			if mode == "rates_reject" {
				emit(map[string]any{"id": message.ID, "error": map[string]any{"code": -32008, "message": "private-rate-error token=private-token"}})
				continue
			}
			if mode == "bad_rates" {
				result = map[string]any{"rateLimits": nil}
			}
		case "fixture/counts":
			result = counts
		case "fixture/hold":
			held = message.ID
			continue
		case "fixture/release":
			if held != nil {
				emit(map[string]any{"id": held, "result": map[string]any{}})
				held = nil
			}
		case "fixture/releaseInspection":
			if heldInspection != nil {
				emit(map[string]any{"id": heldInspection, "result": map[string]any{"data": []string{"owned-thread"}, "nextCursor": nil}})
				heldInspection = nil
			}
		case "thread/loaded/list":
			if mode == "loaded_timeout" {
				held = message.ID
				continue
			}
			ids := []string{"owned-thread"}
			if mode == "no_threads" || mode == "missing_thread" || mode == "lost_delete_ack" {
				ids = []string{}
			}
			if mode == "list_changed" && counts[message.Method] > 1 {
				ids = append(ids, "changed-thread")
			}
			result = map[string]any{"data": ids, "nextCursor": nil}
			if mode == "bad_loaded" {
				result = map[string]any{"data": nil}
			}
			if mode == "loaded_cursor_loop" {
				result = map[string]any{"data": []string{}, "nextCursor": "same-cursor"}
			}
			if mode == "event_during_scan" && counts[message.Method] == 1 {
				emit(map[string]any{"method": "turn/started", "params": map[string]any{"threadId": "owned-thread", "turn": map[string]string{"id": "new-turn", "status": "inProgress"}}})
				emit(map[string]any{"method": "turn/completed", "params": map[string]any{"threadId": "owned-thread", "turn": map[string]string{"id": "new-turn", "status": "completed"}}})
			}
			if mode == "event_fence_checkpoint" && counts[message.Method] == 1 {
				emit(map[string]any{"method": "turn/started", "params": map[string]any{"threadId": "owned-thread", "turn": map[string]string{"id": "new-turn", "status": "inProgress"}}})
				emit(map[string]any{"method": "turn/completed", "params": map[string]any{"threadId": "owned-thread", "turn": map[string]string{"id": "new-turn", "status": "completed"}}})
				emit(map[string]any{"method": "fixture/inspectionCheckpoint", "params": map[string]any{}})
				heldInspection = message.ID
				continue
			}
		case "thread/read":
			if mode == "missing_thread" || mode == "missing_active" || mode == "lost_delete_ack" {
				_, err := os.Stat(os.Getenv("BRIDGE_AUTH_DELETED_PATH"))
				if mode != "lost_delete_ack" || err == nil {
					emit(map[string]any{"id": message.ID, "error": map[string]any{"code": -32000, "message": "Native exact thread is unavailable"}})
					continue
				}
			}
			status := "idle"
			if mode == "active_thread" || mode == "missing_active" {
				status = "active"
			}
			if mode == "thread_error" {
				status = "systemError"
			}
			result = map[string]any{"thread": map[string]any{"id": "owned-thread", "status": map[string]string{"type": status}}}
			result.(map[string]any)["thread"].(map[string]any)["canAcceptDirectInput"] = true
		case "thread/resume":
			result = map[string]any{"thread": map[string]any{"id": "owned-thread", "status": map[string]string{"type": "idle"}, "canAcceptDirectInput": true}}
		case "thread/turns/list":
			result = map[string]any{"data": []any{}, "nextCursor": nil}
		case "thread/backgroundTerminals/clean":
		case "thread/delete":
			if mode == "lost_delete_ack" {
				path := os.Getenv("BRIDGE_AUTH_DELETED_PATH")
				if _, err := os.Stat(path); os.IsNotExist(err) {
					if os.WriteFile(path, []byte("deleted"), 0600) != nil {
						os.Exit(94)
					}
					os.Exit(0)
				}
			}
		case "thread/backgroundTerminals/list":
			result = map[string]any{"data": []any{}, "nextCursor": nil}
			if mode == "background_terminal" {
				result = map[string]any{"data": []any{map[string]string{"processId": "private-process", "command": "private-command"}}, "nextCursor": nil}
			}
			if mode == "bad_terminals" {
				result = map[string]any{"data": nil}
			}
		case "fixture/clientRequest":
			emit(map[string]any{"id": "client-request", "method": "item/commandExecution/requestApproval", "params": map[string]string{"threadId": "owned-thread", "turnId": "owned-turn"}})
		case "fixture/unknownTurn":
			emit(map[string]any{"method": "turn/completed", "params": map[string]any{"threadId": "owned-thread", "turn": map[string]string{"id": "owned-turn", "status": "completed"}}})
			emit(map[string]any{"method": "turn/completed", "params": map[string]any{"threadId": "owned-thread", "turn": map[string]string{"id": "owned-turn", "status": "failed"}}})
		case "fixture/activeTurn":
			emit(map[string]any{"method": "turn/started", "params": map[string]any{"threadId": "owned-thread", "turn": map[string]string{"id": "owned-turn", "status": "inProgress"}}})
		case "fixture/processStarted", "fixture/processCompleted":
			method := "item/started"
			if message.Method == "fixture/processCompleted" {
				method = "item/completed"
			}
			emit(map[string]any{"method": method, "params": map[string]any{"threadId": "owned-thread", "turnId": "owned-turn", "item": map[string]string{"id": "owned-command", "type": "commandExecution"}}})
		case "fixture/badTurn":
			emit(map[string]any{"method": "turn/started", "params": map[string]any{"threadId": "owned-thread", "turn": map[string]string{"id": "bad turn", "status": "inProgress"}}})
		default:
			if message.Method != "initialize" {
				os.Exit(99)
			}
		}
		emit(map[string]any{"id": message.ID, "result": result})
	}
	os.Exit(0)
}

func authPeer(t *testing.T, mode string) *Connection {
	t.Helper()
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(executable, "-test.run=^TestAuthenticationPeerProcess$")
	cmd.Env = append(os.Environ(), "BRIDGE_AUTH_FIXTURE="+mode)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, err := connect(ctx, cmd)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	return c
}

func authCounts(t *testing.T, c *Connection) map[string]int {
	t.Helper()
	raw, err := c.request(context.Background(), "fixture/counts", map[string]any{})
	var result map[string]int
	if err != nil || json.Unmarshal(raw, &result) != nil {
		t.Fatal(err)
	}
	return result
}

func TestNativeAccountProjectionAndAuthenticatedPreflight(t *testing.T) {
	for _, mode := range []string{"normal", "signed_out", "local_provider", "api_key", "refresh_required", "bad_account", "missing_requires", "auth_reject"} {
		t.Run(mode, func(t *testing.T) {
			c := authPeer(t, mode)
			account, err := c.ReadAccount(context.Background(), mode == "refresh_required")
			if mode == "bad_account" || mode == "missing_requires" || mode == "auth_reject" {
				if err == nil || strings.Contains(err.Error(), "private") {
					t.Fatal("Invalid or sensitive account error escaped", err)
				}
				var failure *AuthError
				if !errors.As(err, &failure) || failure.Operation != "account/read" || failure.Code != "native_auth_unavailable" {
					t.Fatal("Account error lost safe machine classification", err)
				}
				if mode == "auth_reject" && (failure.RPCCode == nil || *failure.RPCCode != -32007) {
					t.Fatal("Numeric native rejection lost", err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			kind, requires := "chatgpt", true
			if mode == "signed_out" || mode == "local_provider" {
				kind = ""
			}
			if mode == "local_provider" {
				requires = false
			}
			if mode == "api_key" {
				kind = "apiKey"
			}
			if account.Type != kind || account.RequiresOpenAIAuth != requires {
				t.Fatal("Native current account state changed", account)
			}
			raw, _ := json.Marshal(account)
			if strings.Contains(string(raw), "private") || strings.Contains(string(raw), "email") || strings.Contains(string(raw), "accountId") {
				t.Fatal("Account projection exposed identifiers", string(raw))
			}
			if counts := authCounts(t, c); counts["account/read"] != 1 || counts["thread/start"] != 0 || counts["account/login/start"] != 0 {
				t.Fatal("Account read changed auth or started model work", counts)
			}
		})
	}
	for _, mode := range []string{"normal", "rates_reject", "bad_rates"} {
		t.Run("provider_"+mode, func(t *testing.T) {
			c := authPeer(t, mode)
			err := c.CheckAccount(context.Background())
			if (err == nil) != (mode == "normal") {
				t.Fatal("Quota/provider response confused with validated authentication", err)
			}
			if err != nil && strings.Contains(err.Error(), "private") {
				t.Fatal("Raw provider response escaped", err)
			}
			if counts := authCounts(t, c); counts["account/rateLimits/read"] != 1 || counts["turn/start"] != 0 {
				t.Fatal("Preflight skipped authenticated provider or ran inference", counts)
			}
		})
	}
}

func TestNativeEffectiveAuthSourceIsExplicitPrivateAndComparable(t *testing.T) {
	for _, mode := range []string{"normal", "keyring", "auto", "ephemeral", "missing_store", "bad_store", "bad_config", "policy_change"} {
		t.Run(mode, func(t *testing.T) {
			c := authPeer(t, mode)
			config, err := c.ReadAuthConfig(context.Background(), t.TempDir())
			bad := mode == "missing_store" || mode == "bad_store" || mode == "bad_config"
			if (err != nil) != bad {
				t.Fatal("Unverified source guessed or valid source lost", config, err)
			}
			if bad {
				return
			}
			store := "file"
			if mode == "keyring" || mode == "auto" || mode == "ephemeral" {
				store = mode
			}
			if config.CredentialsStore != store || config.Provider != "openai" || config.ForcedLoginMethod != "chatgpt" {
				t.Fatal("Effective native auth source lost", config)
			}
			raw, _ := json.Marshal(config)
			if strings.Contains(string(raw), "private") || strings.Contains(string(raw), "policy") {
				t.Fatal("Auth configuration exposed private values", string(raw))
			}
			second, err := c.ReadAuthConfig(context.Background(), t.TempDir())
			if err != nil {
				t.Fatal(err)
			}
			if (config == second) == (mode == "policy_change") {
				t.Fatal("Private policy changes were ignored or stable source changed")
			}
		})
	}
}

func TestNativeIdleInspectionAndAtomicCloseRefuseUncertainRuntime(t *testing.T) {
	for _, mode := range []string{"normal", "no_threads", "active_thread", "thread_error", "background_terminal", "bad_loaded", "loaded_cursor_loop", "bad_terminals", "event_during_scan", "loaded_timeout"} {
		t.Run(mode, func(t *testing.T) {
			c := authPeer(t, mode)
			ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
			defer cancel()
			err := c.CloseWhenIdle(ctx)
			idle := mode == "normal" || mode == "no_threads"
			if (err == nil) != idle {
				t.Fatal("Native idle evidence did not control close", err)
			}
			select {
			case <-c.Done():
				if !idle {
					t.Fatal("Busy/unknown inspection killed native work", err)
				}
			default:
				if idle {
					t.Fatal("Verified idle runtime was not closed")
				}
			}
			if mode == "loaded_timeout" && !errors.Is(err, context.DeadlineExceeded) {
				t.Fatal("Bounded idle read lost cancellation", err)
			}
			if err != nil && strings.Contains(err.Error(), "private") {
				t.Fatal("Idle diagnostics exposed command/process details", err)
			}
		})
	}
}

func TestNativeLocalOutstandingWorkCannotBeOverruledByIdleHistory(t *testing.T) {
	for _, operation := range []string{"fixture/clientRequest", "fixture/unknownTurn", "fixture/activeTurn", "fixture/processStarted", "fixture/badTurn"} {
		t.Run(operation, func(t *testing.T) {
			c := authPeer(t, "normal")
			if _, err := c.request(context.Background(), operation, map[string]any{}); err != nil {
				t.Fatal(err)
			}
			if err := drainNativeEvents(context.Background(), c.Events()); err != nil {
				t.Fatal("Outstanding-work fixture event drain failed", err)
			}
			if err := c.CloseWhenIdle(context.Background()); err == nil {
				t.Fatal("Idle snapshot overruled outstanding or uncertain native work")
			}
			select {
			case <-c.Done():
				t.Fatal("Local safety refusal closed active native runtime")
			default:
			}
			if counts := authCounts(t, c); counts["thread/loaded/list"] != 0 {
				t.Fatal("Known local uncertainty unnecessarily queried idle state", counts)
			}
			if operation == "fixture/processStarted" {
				if _, err := c.request(context.Background(), "fixture/processCompleted", map[string]any{}); err != nil {
					t.Fatal(err)
				}
				if err := drainNativeEvents(context.Background(), c.Events()); err != nil {
					t.Fatal("Completed-process fixture event drain failed", err)
				}
				if err := c.RequireIdle(context.Background()); err != nil {
					t.Fatal("Confirmed process retirement left false active state", err)
				}
			}
		})
	}
	c := authPeer(t, "normal")
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	done, result := make(chan struct{}), make(chan error, 1)
	go func() { defer close(done); _, err := c.request(ctx, "fixture/hold", map[string]any{}); result <- err }()
	t.Cleanup(func() { cancel(); c.Close(); checksync.Join(t, done, time.Second, "pending native read") })
	waitNativePending(t, c, func(_ uint64, count int) bool { return count == 1 })
	if err := c.RequireIdle(context.Background()); err == nil {
		t.Fatal("Outstanding native RPC was ignored")
	}
	if _, err := c.request(context.Background(), "fixture/release", map[string]any{}); err != nil {
		t.Fatal(err)
	}
	if err := checksync.Receive(t, result, time.Second, "pending native response"); err != nil {
		t.Fatal(err)
	}
	if err := c.RequireIdle(context.Background()); err != nil {
		t.Fatal("Completed pending read prevented safe idle inspection", err)
	}
}

func TestNativeActivityDuringInspectionCannotBeHiddenByDrainingEvents(t *testing.T) {
	c := authPeer(t, "event_fence_checkpoint")
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	done, response := make(chan struct{}), make(chan error, 1)
	go func() { defer close(done); response <- c.CloseWhenIdle(ctx) }()
	t.Cleanup(func() { cancel(); c.Close(); checksync.Join(t, done, time.Second, "native activity-fenced inspection") })
	for _, method := range []string{"turn/started", "turn/completed", "fixture/inspectionCheckpoint"} {
		if m := checksync.Receive(t, c.Events(), time.Second, "inspection native event"); m.Method != method {
			t.Fatal("Fixture event boundary changed", m.Method)
		}
	}
	if _, err := c.request(ctx, "fixture/releaseInspection", map[string]any{}); err != nil {
		t.Fatal(err)
	}
	err := checksync.Receive(t, response, time.Second, "inspection rejected changed native state")
	if err == nil {
		t.Fatal("Native activity disappeared between two idle snapshots and permitted close")
	}
	select {
	case <-c.Done():
		t.Fatal("Changed native runtime was closed")
	default:
	}
	if err := c.CloseWhenIdle(ctx); err != nil {
		t.Fatal("A new stable inspection could not safely close retired runtime", err)
	}
}

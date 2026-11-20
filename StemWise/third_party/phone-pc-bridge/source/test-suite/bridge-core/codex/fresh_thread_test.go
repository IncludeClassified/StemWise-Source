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
)

func TestFreshThreadPeer(t *testing.T) {
	selected := false
	for _, arg := range os.Args[1:] {
		selected = selected || arg == "-test.run=^TestFreshThreadPeer$"
	}
	if !selected {
		return
	}
	mode := os.Getenv("BRIDGE_FRESH_MODE")
	status, writable, wrongID := "idle", any(true), false
	starts, resumes, turns := 0, 0, 0
	var startFrame, turnFrame map[string]any
	var heldStart json.RawMessage
	scanner := bufio.NewScanner(os.Stdin)
	for scanner.Scan() {
		var m struct {
			Message
			Params map[string]any `json:"params"`
		}
		if json.Unmarshal(scanner.Bytes(), &m) != nil {
			os.Exit(96)
		}
		result := any(map[string]any{})
		thread := func(id string) any {
			if wrongID {
				id = "different-thread"
			}
			return map[string]any{"thread": map[string]any{"id": id, "status": map[string]string{"type": status}, "canAcceptDirectInput": writable}}
		}
		switch m.Method {
		case "initialized":
			continue
		case "thread/start":
			starts++
			startFrame = m.Params
			if mode == "lost_start_ack" {
				heldStart = append(json.RawMessage(nil), m.ID...)
				fmt.Println(`{"method":"fixture/startReceived","params":{}}`)
				continue
			}
			if mode == "tier_reject_start" || mode == "buffered_reject" {
				fmt.Printf("{\"id\":%s,\"error\":{\"code\":-32015,\"message\":\"fixture tier unavailable\"}}\n", m.ID)
				if mode == "buffered_reject" {
					fmt.Println(`{"method":"fixture/startReplyWritten","params":{}}`)
				}
				continue
			}
			id := "fresh-1"
			switch mode {
			case "invalid_id":
				id = "../invalid"
			case "unloaded_ack":
				status = "notLoaded"
			case "unknown_status":
				status = "unknown"
			case "readonly_ack":
				writable = false
			case "missing_writer_ack":
				writable = nil
			case "malformed_writer_ack":
				writable = "true"
			}
			result = thread(id)
		case "thread/read":
			if path := os.Getenv("BRIDGE_CHECK_HANDOFF_PATH"); strings.HasPrefix(mode, "handoff-file-") && path != "" {
				if mode == "handoff-file-changed" {
					if os.WriteFile(path, []byte("changed workflow bytes"), 0600) != nil {
						os.Exit(88)
					}
				} else {
					if os.Remove(path) != nil {
						os.Exit(89)
					}
					if mode == "handoff-file-directory" && os.Mkdir(path, 0700) != nil {
						os.Exit(90)
					}
				}
			}
			result = thread(fmt.Sprint(m.Params["threadId"]))
		case "thread/resume":
			resumes++
			if m.Params["threadId"] == "fresh-1" {
				fmt.Printf("{\"id\":%s,\"error\":{\"code\":-32600,\"message\":\"no rollout found for newly started thread\"}}\n", m.ID)
				continue
			}
			result = thread(fmt.Sprint(m.Params["threadId"]))
		case "turn/start":
			turns++
			turnFrame = m.Params
			if mode == "tier_reject_turn" {
				fmt.Printf("{\"id\":%s,\"error\":{\"code\":-32015,\"message\":\"fixture tier unavailable\"}}\n", m.ID)
				continue
			}
			result = map[string]any{"turn": map[string]string{"id": "turn-1", "status": "inProgress"}}
		case "fixture/state":
			if value, ok := m.Params["status"].(string); ok {
				status = value
			}
			if value, ok := m.Params["writable"]; ok {
				writable = value
			}
			wrongID, _ = m.Params["wrongID"].(bool)
		case "fixture/counts":
			result = map[string]int{"starts": starts, "resumes": resumes, "turns": turns}
		case "fixture/releaseStart":
			if heldStart != nil {
				data, _ := json.Marshal(thread("fresh-1"))
				fmt.Printf("{\"id\":%s,\"result\":%s}\n", heldStart, data)
				heldStart = nil
			}
		case "fixture/frames":
			result = map[string]any{"start": startFrame, "turn": turnFrame}
		}
		if len(m.ID) != 0 {
			data, _ := json.Marshal(result)
			fmt.Printf("{\"id\":%s,\"result\":%s}\n", m.ID, data)
			if m.Method == "thread/start" && mode == "exit_after_start_ack" {
				os.Exit(53)
			}
			if m.Method == "thread/start" && mode == "buffered_success" {
				fmt.Println(`{"method":"fixture/startReplyWritten","params":{}}`)
			}
		}
	}
	os.Exit(0)
}

func freshThreadPeer(t *testing.T, mode string) (*Connection, context.Context) {
	t.Helper()
	executable, _ := os.Executable()
	cmd := exec.Command(executable, "-test.run=^TestFreshThreadPeer$")
	cmd.Env = append(os.Environ(), "BRIDGE_FRESH_MODE="+mode)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(cancel)
	c, err := connect(ctx, cmd)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	return c, ctx
}

func freshCounts(t *testing.T, c *Connection, ctx context.Context, starts, resumes, turns int) {
	t.Helper()
	raw, err := c.request(ctx, "fixture/counts", nil)
	var counts struct{ Starts, Resumes, Turns int }
	if err != nil || json.Unmarshal(raw, &counts) != nil || counts.Starts != starts || counts.Resumes != resumes || counts.Turns != turns {
		t.Fatal("Unexpected native create/resume/send side effects", string(raw), err)
	}
}

func TestFreshThreadIsUsableWithoutResumingMissingRollout(t *testing.T) {
	c, ctx := freshThreadPeer(t, "normal")
	if _, err := c.StartThread(ctx, t.TempDir(), "", ""); err != nil {
		t.Fatal(err)
	}
	for n := 0; n < 2; n++ {
		if _, err := c.Select(ctx, "fresh-1"); err != nil {
			t.Fatal("Fresh loaded writer incorrectly required a persisted rollout", err)
		}
	}
	if _, err := sendJob(t, c, ctx, "fresh-1", "actual freshly started writer input"); err != nil {
		t.Fatal("Fresh native writer remained unusable", err)
	}
	freshCounts(t, c, ctx, 1, 0, 1)
}

func TestFreshThreadAcknowledgementMustConfirmLoadedDirectWriter(t *testing.T) {
	for _, mode := range []string{"invalid_id", "unloaded_ack", "unknown_status", "readonly_ack", "missing_writer_ack", "malformed_writer_ack"} {
		t.Run(mode, func(t *testing.T) {
			c, ctx := freshThreadPeer(t, mode)
			_, err := c.StartThread(ctx, t.TempDir(), "", "")
			var unknown *OutcomeUnknown
			if !errors.As(err, &unknown) {
				t.Fatal("Unconfirmed accepted creation became usable or replayable", err)
			}
			c.mu.Lock()
			selected := len(c.selected)
			c.mu.Unlock()
			if selected != 0 {
				t.Fatal("Unconfirmed start manufactured a selected writer")
			}
			freshCounts(t, c, ctx, 1, 0, 0)
		})
	}
}

func TestKnownThreadSelectionRechecksFreshNativeOwnership(t *testing.T) {
	for _, mode := range []string{"unloaded", "readonly", "missing_writer", "malformed_writer", "wrong_id"} {
		t.Run(mode, func(t *testing.T) {
			c, ctx := freshThreadPeer(t, "normal")
			if _, err := c.StartThread(ctx, t.TempDir(), "", ""); err != nil {
				t.Fatal(err)
			}
			change := map[string]any{}
			switch mode {
			case "unloaded":
				change["status"], change["writable"] = "notLoaded", nil
			case "readonly":
				change["writable"] = false
			case "missing_writer":
				change["writable"] = nil
			case "malformed_writer":
				change["writable"] = "true"
			case "wrong_id":
				change["wrongID"] = true
			}
			if _, err := c.request(ctx, "fixture/state", change); err != nil {
				t.Fatal(err)
			}
			if _, err := c.Select(ctx, "fresh-1"); err == nil {
				t.Fatal("Cached selection hid lost native ownership")
			}
			c.mu.Lock()
			selected := c.selected["fresh-1"]
			c.mu.Unlock()
			if selected {
				t.Fatal("Failed selection retained stale writer authority")
			}
			resumes := 0
			if mode == "unloaded" {
				resumes = 1
			}
			freshCounts(t, c, ctx, 1, resumes, 0)
		})
	}
}

func TestUnknownLoadedThreadStillRequiresNativeResume(t *testing.T) {
	c, ctx := freshThreadPeer(t, "normal")
	if _, err := c.Select(ctx, "fresh-1"); err == nil || !strings.Contains(err.Error(), "no rollout found") {
		t.Fatal("Unowned readable writer bypassed native subscription", err)
	}
	freshCounts(t, c, ctx, 0, 1, 0)
	if _, err := c.Select(ctx, "stored-1"); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Select(ctx, "stored-1"); err != nil {
		t.Fatal(err)
	}
	freshCounts(t, c, ctx, 0, 2, 0)
}

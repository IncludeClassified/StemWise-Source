package codex

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

func TestRetirementPeer(t *testing.T) {
	selected := false
	for _, arg := range os.Args[1:] {
		selected = selected || arg == "-test.run=^TestRetirementPeer$"
	}
	if !selected {
		return
	}
	mode := os.Getenv("BRIDGE_RETIRE_MODE")
	child := exec.Command(os.Args[0], "-test.run=^TestOwnedProcessPeer$")
	child.Env = append(os.Environ(), "BRIDGE_OWNED_PEER=child")
	child.Stdout, child.Stderr = os.Stdout, os.Stderr
	if err := child.Start(); err != nil {
		os.Exit(91)
	}
	running, cleaned, pages, writerSelected := true, false, 0, false
	scanner := bufio.NewScanner(os.Stdin)
	for scanner.Scan() {
		var m struct {
			Message
			Params map[string]any `json:"params"`
		}
		if json.Unmarshal(scanner.Bytes(), &m) != nil {
			os.Exit(92)
		}
		result := any(map[string]any{})
		if m.Method != "initialize" && m.Method != "initialized" && m.Params["threadId"] != "thread-1" {
			os.Exit(93)
		}
		switch m.Method {
		case "initialized":
			continue
		case "thread/read", "thread/resume":
			status := "idle"
			if mode == "active" {
				status = "active"
			}
			if m.Method == "thread/resume" {
				writerSelected = true
			}
			writable := !((mode == "writer_revoked_before_clean" && writerSelected && m.Method == "thread/read") || (mode == "writer_revoked_after_clean" && cleaned))
			result = map[string]any{"thread": map[string]any{"id": "thread-1", "status": map[string]string{"type": status}, "canAcceptDirectInput": writable}}
		case "thread/turns/list":
			if m.Params["limit"] != float64(1) || m.Params["sortDirection"] != "desc" || m.Params["itemsView"] != "full" {
				os.Exit(94)
			}
			turnID, status := "turn-1", "interrupted"
			if mode == "different_turn" || (mode == "changed_after_clean" && cleaned) {
				turnID = "turn-2"
			}
			if mode == "in_progress" {
				status = "inProgress"
			}
			if mode == "changed_terminal_status" && cleaned {
				status = "completed"
			}
			result = map[string]any{"data": []any{map[string]any{"id": turnID, "status": status, "items": []any{}}}, "nextCursor": nil, "backwardsCursor": nil}
			if mode == "empty_history" {
				result = map[string]any{"data": []any{}, "nextCursor": nil}
			}
		case "thread/delete":
			if !writerSelected || !cleaned || running {
				fmt.Printf("{\"id\":%s,\"error\":{\"code\":-32100,\"message\":\"Deletion reached native before owned background work retired\"}}\n", m.ID)
				continue
			}
		case "thread/backgroundTerminals/clean":
			cleaned = true
			if mode == "unsupported" {
				fmt.Printf("{\"id\":%s,\"error\":{\"code\":-32601,\"message\":\"fixture unsupported\"}}\n", m.ID)
				continue
			}
			if mode == "lost_ack" {
				fmt.Println(`{"method":"fixture/cleanAdmitted","params":{}}`)
				continue
			}
			if mode == "malformed_ack" {
				result = map[string]any{"unknown": true}
			}
			if mode != "ack_only" {
				if err := child.Process.Kill(); err != nil {
					os.Exit(95)
				}
				child.Wait()
				running = false
			}
		case "thread/backgroundTerminals/list":
			pages++
			result = map[string]any{"data": []any{}, "nextCursor": nil}
			if running {
				result = map[string]any{"data": []any{map[string]any{"processId": "owned-process", "itemId": "owned-item"}}, "nextCursor": nil}
			}
			switch mode {
			case "missing_data":
				result = map[string]any{"nextCursor": nil}
			case "malformed_cursor":
				result = map[string]any{"data": []any{}, "nextCursor": 42}
			case "cursor_cycle":
				result = map[string]any{"data": []any{}, "nextCursor": "same-page"}
			case "page_limit":
				result = map[string]any{"data": []any{}, "nextCursor": fmt.Sprint(pages)}
			case "later_running":
				if m.Params["cursor"] == "next-page" {
					result = map[string]any{"data": []any{map[string]any{"processId": "later-owned-process"}}, "nextCursor": nil}
				} else {
					result = map[string]any{"data": []any{}, "nextCursor": "next-page"}
				}
			case "optional_cursor":
				result = map[string]any{"data": []any{}}
			case "empty_pages":
				if m.Params["cursor"] != "next-page" {
					result = map[string]any{"data": []any{}, "nextCursor": "next-page"}
				}
			}
		}
		if len(m.ID) != 0 {
			data, _ := json.Marshal(result)
			fmt.Printf("{\"id\":%s,\"result\":%s}\n", m.ID, data)
		}
	}
	if running {
		child.Process.Kill()
		child.Wait()
	}
	os.Exit(0)
}

func TestRetireTurnRequiresExactTerminalAndObservedEmptyBackgroundWork(t *testing.T) {
	for _, mode := range []string{"normal", "optional_cursor", "empty_pages", "active", "different_turn", "in_progress", "unselected", "pre_cancelled", "changed_after_clean", "changed_terminal_status", "writer_revoked_before_clean", "writer_revoked_after_clean", "unsupported", "lost_ack", "malformed_ack", "ack_only", "missing_data", "malformed_cursor", "cursor_cycle", "page_limit", "later_running"} {
		t.Run(mode, func(t *testing.T) {
			executable, _ := os.Executable()
			pidPath := filepath.Join(t.TempDir(), "background.pid")
			cmd := exec.Command(executable, "-test.run=^TestRetirementPeer$")
			cmd.Env = append(os.Environ(), "BRIDGE_RETIRE_MODE="+mode, "BRIDGE_OWNED_PID="+pidPath)
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			c, err := connect(ctx, cmd)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { c.Close() })
			dead, cleanup := observeOwnedProcess(t, awaitOwnedPID(t, pidPath))
			t.Cleanup(cleanup)
			if dead(0) {
				t.Fatal("Background child was not alive before retirement")
			}
			if mode != "unselected" {
				if _, err := c.Select(ctx, "thread-1"); err != nil {
					t.Fatal(err)
				}
			}
			var receipt json.RawMessage
			if mode == "lost_ack" {
				owned, stop := context.WithCancel(ctx)
				defer stop()
				finished := make(chan struct{})
				go func() { receipt, err = c.RetireTurn(owned, "thread-1", "turn-1"); close(finished) }()
				event(t, c, "fixture/cleanAdmitted")
				stop()
				select {
				case <-finished:
				case <-time.After(3 * time.Second):
					t.Fatal("Cancelled retirement observation did not finish")
				}
			} else {
				if mode == "pre_cancelled" {
					cancel()
				}
				receipt, err = c.RetireTurn(ctx, "thread-1", "turn-1")
			}
			success := mode == "normal" || mode == "optional_cursor" || mode == "empty_pages"
			if success {
				if err != nil || string(receipt) != `{"threadId":"thread-1","turnId":"turn-1","terminalsRetired":true}` {
					t.Fatal("Missing exact retirement receipt", string(receipt), err)
				}
				if !dead(time.Second) {
					t.Fatal("Cleanup receipt preceded actual background child termination")
				}
			} else {
				var unknown *OutcomeUnknown
				if !errors.As(err, &unknown) || len(receipt) != 0 {
					t.Fatal("Unconfirmed cleanup became a completed operation", string(receipt), err)
				}
			}
			if mode == "writer_revoked_after_clean" && !dead(time.Second) {
				t.Fatal("Post-clean writer revocation fixture did not actually terminate its child")
			}
			if (mode == "active" || mode == "different_turn" || mode == "in_progress" || mode == "unselected" || mode == "pre_cancelled" || mode == "writer_revoked_before_clean" || mode == "ack_only" || mode == "unsupported" || mode == "lost_ack") && dead(0) {
				t.Fatal("Wrong turn or unconfirmed cleanup killed the background child")
			}
		})
	}
}

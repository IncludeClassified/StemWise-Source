package codex

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

func TestDeletionInterruptsActiveOwnedWorkAndRequiresNativeAcknowledgment(t *testing.T) {
	c := peer(t, "delete-owned")
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if _, err := c.Select(ctx, "thread-1"); err != nil {
		t.Fatal(err)
	}
	if _, err := sendJob(t, c, ctx, "thread-1", "owned active expiry fixture"); err != nil {
		t.Fatal(err)
	}
	if err := c.DeleteThread(ctx, "thread-1"); err == nil {
		t.Fatal("interruption request claimed deletion")
	}
	if err := c.DeleteThread(ctx, "thread-1"); err != nil {
		t.Fatal(err)
	}
	c.mu.Lock()
	requests, messages, turns, selected := len(c.requests), len(c.messages), len(c.turns), c.selected["thread-1"]
	c.mu.Unlock()
	if requests != 0 || messages != 0 || turns != 0 || selected {
		t.Fatal("Confirmed native deletion retained owned conversation state")
	}
	if _, err := c.Read(ctx, "thread-1"); err != nil {
		rpc, ok := err.(*RPCError)
		if !ok || rpc.Code != -32000 || rpc.Message != "Thread deleted" || len(rpc.Data) != 0 {
			t.Fatal("Deletion read did not return the exact missing-thread RPC rejection", err)
		}
	} else {
		t.Fatal("Native thread remained readable after acknowledged deletion")
	}
	if err := drainNativeEvents(ctx, c.Events()); err != nil {
		t.Fatal("Deletion fixture event drain failed", err)
	}
	if err := c.DeleteThread(ctx, "thread-1"); err != nil {
		t.Fatal("native missing-thread retry rejected", err)
	}
}

func TestDeletionReadRejectionRequiresWholeRuntimeIdleAndLostAckCanRecover(t *testing.T) {
	for _, mode := range []string{"missing_thread", "missing_active"} {
		t.Run(mode, func(t *testing.T) {
			c := authPeer(t, mode)
			err := c.DeleteThread(context.Background(), "owned-thread")
			if (err == nil) != (mode == "missing_thread") {
				t.Fatal("Read rejection bypassed whole-runtime retirement authority", err)
			}
			counts := authCounts(t, c)
			want := 0
			if mode == "missing_thread" {
				want = 1
			}
			if counts["thread/delete"] != want {
				t.Fatal("Unverified running runtime reached native deletion", counts)
			}
		})
	}
	t.Run("lost_ack_reopen", func(t *testing.T) {
		t.Setenv("BRIDGE_AUTH_DELETED_PATH", filepath.Join(t.TempDir(), "deleted"))
		c := authPeer(t, "lost_delete_ack")
		err := c.DeleteThread(context.Background(), "owned-thread")
		var unknown *OutcomeUnknown
		if !errors.As(err, &unknown) {
			t.Fatal("Lost deletion acknowledgement became certain", err)
		}
		if err := c.Close(); err != nil {
			t.Fatal(err)
		}
		reopened := authPeer(t, "lost_delete_ack")
		if err := reopened.DeleteThread(context.Background(), "owned-thread"); err != nil {
			t.Fatal("Exact idempotent delete could not recover after lost acknowledgement/reopen", err)
		}
		if counts := authCounts(t, reopened); counts["thread/delete"] != 1 || counts["thread/loaded/list"] != 2 {
			t.Fatal("Recovery skipped runtime idleness or replayed deletion", counts)
		}
	})
}

func TestDeletionRetiresOwnedBackgroundProcessesBeforeRemovingThread(t *testing.T) {
	for _, mode := range []string{"normal", "empty_history", "ack_only", "unsupported"} {
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
				t.Fatal("Owned child not active before deletion")
			}
			err = c.DeleteThread(ctx, "thread-1")
			if mode == "normal" || mode == "empty_history" {
				if err != nil {
					t.Fatal("Deletion failed to retire owned background work", err)
				}
				if !dead(time.Second) {
					t.Fatal("Native deletion left owned background process alive")
				}
			} else {
				if err == nil {
					t.Fatal("Unsupported cleanup or acknowledgement alone authorized deletion")
				}
				if dead(0) {
					t.Fatal("Unconfirmed cleanup killed process outside supported native control")
				}
			}
		})
	}
}

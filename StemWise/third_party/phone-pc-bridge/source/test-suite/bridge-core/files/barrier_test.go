package files

import (
	"context"
	"os"
	"os/exec"
	"phonepcbridge/internal/checksync"
	"strings"
	"testing"
	"time"
)

// Run an intentionally failing test in its own process so its failure and cleanup
// can be asserted without making the enclosing package fail.
func TestStalledBarrierReportsFailureAndRetiresWorker(t *testing.T) {
	for _, mode := range []string{"entry", "completion"} {
		t.Run(mode, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestStalledBarrierFixture$", "-test.v")
			cmd.Env = append(os.Environ(), "BRIDGE_STALLED_BARRIER="+mode)
			cmd.WaitDelay = 250 * time.Millisecond
			output, err := cmd.CombinedOutput()
			if ctx.Err() != nil {
				t.Fatal("Stalled fixture escaped its local deadline", string(output))
			}
			if err == nil || !strings.Contains(string(output), "timed out waiting for fixture "+mode) || !strings.Contains(string(output), "fixture worker retired") {
				t.Fatal("Missing focused failure or actual worker retirement", err, string(output))
			}
		})
	}
}

func TestStalledBarrierFixture(t *testing.T) {
	selected := false
	for _, arg := range os.Args[1:] {
		if arg == "-test.run=^TestStalledBarrierFixture$" {
			selected = true
		}
	}
	mode := os.Getenv("BRIDGE_STALLED_BARRIER")
	if !selected || mode == "" {
		return
	}
	entered, release, retired := make(chan struct{}), make(chan struct{}), make(chan struct{})
	go func() {
		defer close(retired)
		if mode == "completion" {
			close(entered)
		}
		<-release
	}()
	t.Cleanup(func() {
		close(release)
		if checksync.Join(t, retired, time.Second, "fixture worker") {
			t.Log("fixture worker retired")
		}
	})
	if mode == "entry" {
		checksync.Receive(t, entered, 40*time.Millisecond, "fixture entry")
	} else {
		checksync.Receive(t, entered, time.Second, "fixture entry")
		checksync.Receive(t, retired, 40*time.Millisecond, "fixture completion")
	}
}

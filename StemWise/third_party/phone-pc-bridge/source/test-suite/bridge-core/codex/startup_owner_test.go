package codex

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

type startupTreeFixture struct {
	processOwner
	startedErr error
	retireErr  error
	release    <-chan struct{}
}

func (p *startupTreeFixture) started(cmd *exec.Cmd) error {
	if p.startedErr != nil {
		// The real child has started but has not joined its Windows job yet.
		return p.startedErr
	}
	return p.processOwner.started(cmd)
}

func (p *startupTreeFixture) retire() error {
	err := p.processOwner.retire()
	if p.release != nil {
		<-p.release
	}
	return errors.Join(err, p.retireErr)
}

func startupFixtureTree(check **startupTreeFixture, started, retirement error, release <-chan struct{}) func(*exec.Cmd) (processOwner, error) {
	return func(cmd *exec.Cmd) (processOwner, error) {
		tree, err := prepareProcessTree(cmd)
		if err != nil {
			return nil, err
		}
		*check = &startupTreeFixture{processOwner: tree, startedErr: started, retireErr: retirement, release: release}
		return *check, nil
	}
}

func TestStartupRetainsOwnerUntilRetirementConfirmed(t *testing.T) {
	for _, phase := range []string{"partial", "initialize", "cancel"} {
		for _, failedRetirement := range []bool{false, true} {
			t.Run(phase+map[bool]string{false: "/confirmed", true: "/unconfirmed"}[failedRetirement], func(t *testing.T) {
				mode := "initialize-rejected"
				if phase == "partial" {
					mode = "stderr-startup-hung"
				}
				if phase == "cancel" {
					mode = "initialize-held"
				}
				cmd := processPeerCommand(t, mode)
				ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
				defer cancel()
				ready := filepath.Join(t.TempDir(), "initialize-received")
				cmd.Env = append(cmd.Env, "BRIDGE_INIT_READY="+ready)
				cancelDone := make(chan struct{})
				if phase == "cancel" {
					go func() {
						defer close(cancelDone)
						ticker := time.NewTicker(time.Millisecond)
						defer ticker.Stop()
						for {
							if _, err := os.Stat(ready); err == nil {
								cancel()
								return
							}
							select {
							case <-ctx.Done():
								return
							case <-ticker.C:
							}
						}
					}()
					defer func() { cancel(); <-cancelDone }()
				}
				partialErr := error(nil)
				if phase == "partial" {
					partialErr = errors.New("fixture membership unavailable")
				}
				cleanupErr := error(nil)
				if failedRetirement {
					cleanupErr = errors.New("fixture retirement unconfirmed")
				}
				var tree *startupTreeFixture
				c, err := connectModeWithTree(ctx, cmd, true, startupFixtureTree(&tree, partialErr, cleanupErr, nil))
				if c != nil || err == nil || cmd.Process == nil || cmd.ProcessState == nil {
					t.Fatal("Failed real-child startup lost failure or bounded reaping", c, err)
				}
				if phase == "partial" && !errors.Is(err, partialErr) || phase == "cancel" && !errors.Is(err, context.Canceled) {
					t.Fatal("Original startup failure was lost", err)
				}
				if phase == "initialize" {
					var rpc *RPCError
					if !errors.As(err, &rpc) || rpc.Code != -32031 {
						t.Fatal("Initialization RPC error was lost", err)
					}
				}
				var retained *StartupCleanupError
				if errors.As(err, &retained) != failedRetirement {
					t.Fatal("Startup ownership disagrees with retirement proof", err)
				}
				if failedRetirement {
					if retained.Owner == nil || retained.Owner.tree != tree || !errors.Is(err, cleanupErr) {
						t.Fatal("Failed startup discarded its process owner or cleanup error", err)
					}
					for attempt := 0; attempt < 2; attempt++ {
						if err := retained.Owner.Close(); !errors.Is(err, cleanupErr) {
							t.Fatal("Retained cleanup failure became success", err)
						}
					}
				}
			})
		}
	}
}

func TestStartupOwnerReleasesAfterHeldRetirementCompletes(t *testing.T) {
	release := make(chan struct{})
	var once sync.Once
	unblock := func() { once.Do(func() { close(release) }) }
	defer unblock()
	var tree *startupTreeFixture
	cmd := processPeerCommand(t, "initialize-rejected")
	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	defer cancel()
	c, err := connectModeWithTree(ctx, cmd, true, startupFixtureTree(&tree, nil, nil, release))
	var retained *StartupCleanupError
	if c != nil || !errors.As(err, &retained) || retained.Owner == nil {
		t.Fatal("Timed-out retirement lost its owner", err)
	}
	unblock()
	if err := retained.Owner.Close(); err != nil {
		t.Fatal("Confirmed later retirement could not release the retained owner", err)
	}
}

func TestPartialPreparationFailureRetainsAllocatedOwner(t *testing.T) {
	prepareErr, cleanupErr := errors.New("fixture preparation failed"), errors.New("fixture allocated tree retirement failed")
	var tree *startupTreeFixture
	prepare := func(cmd *exec.Cmd) (processOwner, error) {
		owner, err := startupFixtureTree(&tree, nil, cleanupErr, nil)(cmd)
		if err != nil {
			return nil, err
		}
		return owner, prepareErr
	}
	cmd := processPeerCommand(t, "normal")
	c, err := connectModeWithTree(context.Background(), cmd, true, prepare)
	var retained *StartupCleanupError
	if c != nil || cmd.Process != nil || !errors.As(err, &retained) || retained.Owner.tree != tree || !errors.Is(err, prepareErr) || !errors.Is(err, cleanupErr) {
		t.Fatal("Partial preparation lost allocated ownership or errors", err)
	}
	assertStartupInputClosed(t, cmd)
}

func assertStartupInputClosed(t *testing.T, cmd *exec.Cmd) {
	t.Helper()
	input, ok := cmd.Stdin.(*os.File)
	if !ok {
		t.Fatal("Fixture did not exercise the owned StdinPipe")
	}
	if _, err := input.Read(make([]byte, 1)); !errors.Is(err, os.ErrClosed) {
		t.Fatal("Failed startup retained its never-started child pipe", err)
	}
}

func TestNoChildStartupFailuresReleasePipes(t *testing.T) {
	for _, phase := range []string{"preparation", "proxy_start"} {
		t.Run(phase, func(t *testing.T) {
			cmd := exec.Command(filepath.Join(t.TempDir(), "missing-codex"))
			prepareErr := errors.New("fixture preparation failed before allocating an owner")
			prepare := func(*exec.Cmd) (processOwner, error) { return nil, prepareErr }
			c, err := connectModeWithTree(context.Background(), cmd, phase == "preparation", prepare)
			var retained *StartupCleanupError
			if c != nil || err == nil || errors.As(err, &retained) || cmd.Process != nil {
				t.Fatal("No-child startup manufactured native ownership", c, err)
			}
			if phase == "preparation" && !errors.Is(err, prepareErr) {
				t.Fatal("Preparation cause was lost", err)
			}
			assertStartupInputClosed(t, cmd)
		})
	}
}

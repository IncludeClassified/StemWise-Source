package pc

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"phonepcbridge/internal/checksync"
	"tailscale.com/tsnet"
)

func TestPowerIntentProcess(t *testing.T) {
	if os.Getenv("BRIDGE_POWER_INTENT_PROCESS") != "1" {
		return
	}
	platform := &fakePowerSystem{state: PowerObservation{BootID: strings.Repeat("a", 32), TransitionID: strings.Repeat("b", 64), UptimeMS: 20000}, shutdown: func() error {
		fmt.Println("power_intent_committed")
		<-time.After(time.Hour)
		return nil
	}}
	executable, _ := os.Executable()
	s, err := New(&tsnet.Server{}, Config{Identity: "nPc", Host: "pc.tailtest.ts.net", CodexExecutable: executable, DataDir: os.Getenv("BRIDGE_POWER_INTENT_DIR"), AllowedPeers: []string{"nPi"}, BackendPeer: "nPi", powerSystem: platform})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	s.authenticate = func(context.Context, string) (string, error) { return "nPi", nil }
	callPower(t, s, PowerRequest{Operation: "shutdown", CycleID: os.Getenv("BRIDGE_POWER_INTENT_CYCLE"), TransitionID: platform.state.TransitionID})
	t.Fatal("Owned interruption fixture unexpectedly completed")
}

func TestPowerIntentSurvivesActualHostProcessDeathWithoutOSReplay(t *testing.T) {
	s, platform := powerFixture(t)
	cfg := s.cfg
	s.Close()
	cycle := randomToken()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestPowerIntentProcess$")
	cmd.Env = append(os.Environ(), "BRIDGE_POWER_INTENT_PROCESS=1", "BRIDGE_POWER_INTENT_DIR="+cfg.DataDir, "BRIDGE_POWER_INTENT_CYCLE="+cycle)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	var stderr strings.Builder
	cmd.Stderr = &stderr
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	waited := false
	ready, readerDone := make(chan string, 1), make(chan struct{})
	t.Cleanup(func() {
		if !waited {
			cmd.Process.Kill()
			cmd.Wait()
		}
		stdout.Close()
		checksync.Join(t, readerDone, 3*time.Second, "power process checkpoint reader")
	})
	go func() {
		defer close(readerDone)
		scanner := bufio.NewScanner(stdout)
		if scanner.Scan() {
			ready <- scanner.Text()
		} else {
			ready <- ""
		}
	}()
	if got := checksync.Receive(t, ready, 5*time.Second, "durable power intent before OS boundary"); got != "power_intent_committed" {
		t.Fatal(got, stderr.String())
	}
	if err := cmd.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	if err := cmd.Wait(); err == nil {
		t.Fatal("Owned power fixture was not interrupted")
	}
	waited = true
	reopened, err := New(&tsnet.Server{}, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	reopened.authenticate = func(context.Context, string) (string, error) { return "nPi", nil }
	_, reply := callPower(t, reopened, PowerRequest{Operation: "shutdown", CycleID: cycle, TransitionID: platform.state.TransitionID})
	if reply.State != "unknown" || reply.ShutdownState != "unconfirmed" || platform.calls != 0 {
		t.Fatal("Restart repeated uncertain physical power action", reply, platform.calls)
	}
}

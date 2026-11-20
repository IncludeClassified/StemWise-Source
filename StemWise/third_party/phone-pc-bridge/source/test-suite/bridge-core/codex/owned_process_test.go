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
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestOwnedProcessPeer(t *testing.T) {
	selected := false
	for _, arg := range os.Args[1:] {
		selected = selected || arg == "-test.run=^TestOwnedProcessPeer$"
	}
	if !selected {
		return
	}
	mode := os.Getenv("BRIDGE_OWNED_PEER")
	if mode == "child" {
		if err := os.WriteFile(os.Getenv("BRIDGE_OWNED_PID"), []byte(strconv.Itoa(os.Getpid())), 0600); err != nil {
			os.Exit(81)
		}
		time.Sleep(30 * time.Second)
		os.Exit(0)
	}
	if mode == "novel" {
		fmt.Fprintln(os.Stderr, "runtime tensor VX7 has unsupported stride layout in sampler phase")
		fmt.Fprintln(os.Stderr, "database is locked")
		os.Exit(38)
	}
	if mode != "private_request" {
		child := exec.Command(os.Args[0], "-test.run=^TestOwnedProcessPeer$")
		child.Env = append(os.Environ(), "BRIDGE_OWNED_PEER=child")
		child.Stdout, child.Stderr = os.Stdout, os.Stderr
		if err := child.Start(); err != nil {
			os.Exit(82)
		}
		child.Process.Release()
	}
	if mode == "startup_failure" || mode == "startup_cancel" {
		deadline := time.Now().Add(10 * time.Second)
		for time.Now().Before(deadline) {
			if _, err := os.Stat(os.Getenv("BRIDGE_OWNED_RELEASE")); err == nil {
				fmt.Println("malformed protocol")
				os.Exit(39)
			}
			time.Sleep(time.Millisecond)
		}
		os.Exit(83)
	}
	scanner := bufio.NewScanner(os.Stdin)
	for scanner.Scan() {
		var m Message
		if json.Unmarshal(scanner.Bytes(), &m) != nil {
			os.Exit(84)
		}
		if m.Method == "fixture/exit" {
			os.Exit(41)
		}
		if m.Method == "fixture/private" {
			var fields map[string]string
			json.Unmarshal(m.Params, &fields)
			for _, value := range fields {
				fmt.Fprintln(os.Stderr, value)
				fmt.Fprintln(os.Stderr, strconv.QuoteToASCII(value))
			}
			fmt.Fprint(os.Stderr, "token=split-private-")
			fmt.Fprintln(os.Stderr, "credential")
			fmt.Fprintln(os.Stderr, `{"account":"private-account-identity","prompt":"unlabeled private body"}`)
			fmt.Fprintln(os.Stderr, "novel tensor VX7 cannot execute sampler opcode 91")
			os.Exit(52)
		}
		if len(m.ID) != 0 {
			fmt.Printf("{\"id\":%s,\"result\":{}}\n", m.ID)
		}
	}
	os.Exit(0)
}

func ownedPeerCommand(t *testing.T, mode, pidPath, releasePath string) *exec.Cmd {
	t.Helper()
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(executable, "-test.run=^TestOwnedProcessPeer$")
	cmd.Env = append(os.Environ(), "BRIDGE_OWNED_PEER="+mode, "BRIDGE_OWNED_PID="+pidPath, "BRIDGE_OWNED_RELEASE="+releasePath)
	return cmd
}

func awaitOwnedPID(t *testing.T, path string) int {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if data, err := os.ReadFile(path); err == nil {
			if pid, err := strconv.Atoi(string(data)); err == nil && pid > 0 {
				return pid
			}
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("Actual owned descendant did not announce its PID")
	return 0
}

func TestOwnedCLIStopsActualDescendants(t *testing.T) {
	for _, mode := range []string{"close", "parent_exit", "startup_failure", "startup_cancel"} {
		t.Run(mode, func(t *testing.T) {
			dir := t.TempDir()
			pidPath, release := filepath.Join(dir, "child.pid"), filepath.Join(dir, "release")
			cmd := ownedPeerCommand(t, mode, pidPath, release)
			ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
			defer cancel()
			var c *Connection
			var connectErr error
			connected := make(chan struct{})
			go func() { c, connectErr = connect(ctx, cmd); close(connected) }()
			t.Cleanup(func() {
				cancel()
				select {
				case <-connected:
					if c != nil {
						c.Close()
					}
				case <-time.After(3 * time.Second):
					t.Error("Owned connection did not retire")
				}
			})
			pid := awaitOwnedPID(t, pidPath)
			dead, cleanup := observeOwnedProcess(t, pid)
			t.Cleanup(cleanup)
			if dead(0) {
				t.Fatal("Descendant died before production cleanup was exercised")
			}
			if mode == "startup_failure" {
				if err := os.WriteFile(release, []byte("release"), 0600); err != nil {
					t.Fatal(err)
				}
			}
			if mode == "startup_cancel" {
				cancel()
			}
			select {
			case <-connected:
			case <-time.After(3 * time.Second):
				t.Fatal("Owned connection did not settle")
			}
			if mode == "startup_failure" || mode == "startup_cancel" {
				if connectErr == nil || c != nil {
					t.Fatal("Malformed startup became success")
				}
				if mode == "startup_cancel" && !errors.Is(connectErr, context.Canceled) {
					t.Fatal("Startup cancellation classification lost", connectErr)
				}
			} else {
				if connectErr != nil {
					t.Fatal(connectErr)
				}
				if mode == "parent_exit" {
					_, err := c.request(ctx, "fixture/exit", nil)
					if err == nil {
						t.Fatal("Exited parent returned a successful response")
					}
				} else if err := c.Close(); err != nil {
					t.Fatal(err)
				}
			}
			// This assertion runs before fallback test cleanup can kill anything.
			if !dead(1500 * time.Millisecond) {
				t.Fatal("Production cleanup left the actual descendant running")
			}
		})
	}
}

func TestAttachedProxyDoesNotOwnSharedDaemonLifetime(t *testing.T) {
	dir := t.TempDir()
	pidPath := filepath.Join(dir, "shared.pid")
	cmd := ownedPeerCommand(t, "close", pidPath, "")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, err := connectMode(ctx, cmd, false)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	dead, cleanup := observeOwnedProcess(t, awaitOwnedPID(t, pidPath))
	t.Cleanup(cleanup)
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	if dead(100 * time.Millisecond) {
		t.Fatal("Closing the attaching proxy terminated its shared daemon")
	}
}

func TestNovelProcessFailureRetainsLocalTechnicalCause(t *testing.T) {
	cmd := ownedPeerCommand(t, "novel", "", "")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, err := connect(ctx, cmd)
	var process *ProcessError
	if !errors.As(err, &process) {
		t.Fatal("Missing process failure", err)
	}
	local := process.LocalDiagnostic()
	if !strings.Contains(local.Stderr, "runtime tensor VX7") || !strings.Contains(local.Stderr, "database is locked") {
		t.Fatal("Novel technical stderr was irreversibly discarded", process.Diagnostic())
	}
	if len(local.AttemptID) != 32 || local.PID != cmd.Process.Pid || strings.Contains(err.Error()+process.Diagnostic().Stderr, "VX7") {
		t.Fatal("Local diagnostics leaked to exported errors or lost attempt identity")
	}
}

func TestLocalTechnicalTailRedactsPrivateSentFieldsAndCredentials(t *testing.T) {
	cmd := ownedPeerCommand(t, "private_request", "", "")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, err := connect(ctx, cmd)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	fields := map[string]string{"text": "private-owner-body\nprivate-second-line \"💬\"", "instructions": "private-custom-instructions", "path": "C:/private-owner/bridge.md"}
	_, err = c.request(ctx, "fixture/private", fields)
	var process *ProcessError
	if !errors.As(err, &process) {
		t.Fatal("Lost native failure", err)
	}
	local := process.LocalDiagnostic()
	for _, secret := range []string{"private-owner-body", "private-second-line", "private-custom-instructions", "private-owner/bridge.md", "split-private-credential", "private-account-identity", "unlabeled private body", `\U0001f4ac`} {
		if strings.Contains(local.Stderr+err.Error()+process.Diagnostic().Stderr, secret) {
			t.Fatal("Private stderr bytes escaped their attempt redactor")
		}
	}
	if !strings.Contains(local.Stderr, "novel tensor VX7 cannot execute sampler opcode 91") || len(local.Stderr) > maxLocalStderrTail {
		t.Fatal("Safe unfamiliar runtime cause was lost or unbounded", local)
	}
}

func TestLocalTechnicalTailIsBoundedAndPrivacySaturationFailsClosed(t *testing.T) {
	d, err := newProcessDiagnostic()
	if err != nil {
		t.Fatal(err)
	}
	for n := 0; n < 2000; n++ {
		fmt.Fprintf(d, "unfamiliar engine failure stage %d\n", n)
	}
	local := (&ProcessError{Cause: errors.New("fixture failure"), diagnostic: d}).LocalDiagnostic()
	if len(local.Stderr) > maxLocalStderrTail || !local.StderrTruncated || !strings.Contains(local.Stderr, "stage 1999") {
		t.Fatal("Local diagnostic tail did not retain bounded latest technical cause")
	}
	frame, _ := json.Marshal(map[string]any{"params": map[string]string{"text": strings.Repeat("p", maxPrivateStderrContext+1)}})
	d.rememberPrivateFrame(frame)
	fmt.Fprintln(d, "unlabeled private context")
	local = (&ProcessError{Cause: errors.New("fixture failure"), diagnostic: d}).LocalDiagnostic()
	if !local.StderrTruncated || strings.Contains(local.Stderr, "unlabeled") || len(d.private) != 0 || d.privateBytes != 0 {
		t.Fatal("Redaction registry saturation retained private or unbounded context")
	}
	next, _ := newProcessDiagnostic()
	if next.attemptID == d.attemptID || len(next.localTail) != 0 || next.privateSuppressed {
		t.Fatal("Attempt diagnostic ownership leaked into a new process")
	}
}

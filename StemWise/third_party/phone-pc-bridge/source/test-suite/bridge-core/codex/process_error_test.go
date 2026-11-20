package codex

import (
	"context"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func processPeerCommand(t *testing.T, mode string) *exec.Cmd {
	t.Helper()
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(executable, "-test.run=^TestPeerProcess$")
	cmd.Env = append(os.Environ(), "BRIDGE_CHECK_PEER="+mode)
	return cmd
}

func requirePrivateProcessDiagnostic(t *testing.T, err error, private ...string) ProcessDiagnostic {
	t.Helper()
	var process *ProcessError
	if !errors.As(err, &process) {
		t.Fatal("Process failure lost scoped diagnostic", err)
	}
	d := process.Diagnostic()
	local := process.LocalDiagnostic()
	all := err.Error() + d.Stderr + local.Stderr
	if len(d.Stderr) > maxStderrTail || len(local.Stderr) > maxLocalStderrTail || len(err.Error()) > 2048 {
		t.Fatal("Process diagnostic exceeded bound")
	}
	for _, secret := range private {
		if strings.Contains(all, secret) {
			t.Fatal("Process diagnostic exposed private bytes")
		}
	}
	return d
}

func TestStartupExitRetainsFinalWaitAndSafeStderrCause(t *testing.T) {
	for _, mode := range []string{"stderr-startup-normal", "stderr-startup-delayed", "stderr-startup-noisy"} {
		t.Run(mode, func(t *testing.T) {
			cmd := processPeerCommand(t, mode)
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			c, err := connect(ctx, cmd)
			if c != nil {
				c.Close()
				t.Fatal("Failed startup became connected")
			}
			if err == nil {
				t.Fatal("Early native exit became successful initialization")
			}
			d := requirePrivateProcessDiagnostic(t, err, "split-private-token", "private-bearer", "private-owner-prompt", "private-account", "private-config")
			if d.WaitState != "exited" || d.ExitCode == nil || *d.ExitCode != 37 || !strings.Contains(d.Stderr, "database_locked") {
				t.Fatal("Actual final native exit/cause was lost", d)
			}
			if cmd.ProcessState == nil || !cmd.ProcessState.Exited() {
				t.Fatal("Failed child was not reaped")
			}
			if mode == "stderr-startup-noisy" && !d.StderrTruncated {
				t.Fatal("Discarded noisy diagnostics did not report truncation")
			}
		})
	}
}

func TestStartupFailureAttemptDoesNotContaminateLaterConnection(t *testing.T) {
	cmd := processPeerCommand(t, "stderr-startup-normal")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, err := connect(ctx, cmd)
	if err == nil {
		t.Fatal("Broken startup became success")
	}
	first := requirePrivateProcessDiagnostic(t, err, "private-account")
	c := peer(t, "normal")
	if _, err := c.Models(ctx, ""); err != nil {
		t.Fatal("Later independent connection inherited failure", err)
	}
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	d := (&ProcessError{Cause: io.EOF, diagnostic: c.process}).Diagnostic()
	if d.Stderr != "" || first.ExitCode == nil || *first.ExitCode != 37 {
		t.Fatal("Attempt diagnostics leaked across connection owners", d)
	}
}

func TestAdmittedSendFailureKeepsUnknownAndSuppressesActualPromptDump(t *testing.T) {
	c := peer(t, "stderr-disconnect-send")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := c.Select(ctx, "thread-1"); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "bridge.md")
	const workflow = "private-workflow-body containing database is locked"
	const text = "private-owner-message containing novel credential without key label"
	const instructions = "private-custom-instructions"
	if err := os.WriteFile(path, []byte(workflow), 0600); err != nil {
		t.Fatal(err)
	}
	_, err := c.SendJob(ctx, "thread-1", text, instructions, path, workflow, "", "", "")
	var unknown *OutcomeUnknown
	if !errors.As(err, &unknown) {
		t.Fatal("Accepted mutation became safe to replay", err)
	}
	d := requirePrivateProcessDiagnostic(t, err, text, instructions, workflow, "private-native-token", path)
	if d.WaitState != "exited" || d.ExitCode == nil || *d.ExitCode != 52 || !strings.Contains(d.Stderr, "database_locked") {
		t.Fatal("Admitted failure lost actual exit/cause", d)
	}
	if c.cmd.ProcessState == nil || !c.cmd.ProcessState.Exited() {
		t.Fatal("Failed admitted child was not reaped")
	}
}

func TestSingleJobMessageFailureRedactsParsedFieldsAndEscapedFragmentsLocally(t *testing.T) {
	c := peer(t, "stderr-disconnect-send-fragments")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := c.Select(ctx, "thread-1"); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "owner-private-path.md")
	const workflow = "# Bridge developer handoff\n\nContract: bridge-developer-v2\nPrivate correlation: hidden-header-value\n\nFile-only required workflow body."
	const text = "owner-fragment-one\nowner-fragment-two \"💬\""
	const instructions = "custom-fragment-one\ncustom-fragment-two"
	if err := os.WriteFile(path, []byte(workflow), 0600); err != nil {
		t.Fatal(err)
	}
	_, err := c.SendJob(ctx, "thread-1", text, instructions, path, workflow, "", "", "")
	var unknown *OutcomeUnknown
	if !errors.As(err, &unknown) {
		t.Fatal("Attempted native send lost its uncertain classification", err)
	}
	d := requirePrivateProcessDiagnostic(t, err, "owner-fragment-one", "owner-fragment-two", "custom-fragment-one", "custom-fragment-two", "hidden-header-value", "owner-private-path.md", `\U0001f4ac`, "private-native-token")
	var process *ProcessError
	if !errors.As(err, &process) || d.ExitCode == nil || *d.ExitCode != 52 || !strings.Contains(process.LocalDiagnostic().Stderr, "novel tensor VX7 cannot execute sampler opcode 91") {
		t.Fatal("Redaction lost actual native exit or useful technical cause", d)
	}
}

func TestStderrCaptureBoundsNoiseAndRejectsSplitPrivateBytes(t *testing.T) {
	d := &processDiagnostic{}
	for _, fragment := range []string{"password=never-", "export\n", "{\"prompt\":\"novel-secret\",\"error\":\"database is ", "locked\"}\n"} {
		if n, err := d.Write([]byte(fragment)); err != nil || n != len(fragment) {
			t.Fatal("Stderr sink interfered with child", err)
		}
	}
	for n := 0; n < 2000; n++ {
		d.Write([]byte("permission denied\nconnection refused\n"))
	}
	d.Write([]byte(strings.Repeat("private-oversized-secret", maxStderrLine)))
	d.Write([]byte("\nstartup database is locked"))
	d.finish(&exec.Cmd{}, nil)
	diagnostic := (&ProcessError{Cause: io.EOF, diagnostic: d}).Diagnostic()
	if len(diagnostic.Stderr) > maxStderrTail || !diagnostic.StderrTruncated || !strings.Contains(diagnostic.Stderr, "database_locked") {
		t.Fatal("Bounded redacted tail lost final technical marker", diagnostic)
	}
	if strings.Contains(diagnostic.Stderr, "secret") || strings.Contains(diagnostic.Stderr, "never-export") {
		t.Fatal("Raw split/oversized stderr escaped allowlist")
	}
	if len(d.partial) != 0 || cap(d.partial) > maxStderrLine || d.tailBytes > maxStderrTail {
		t.Fatal("Stderr capture retained unbounded/raw working data")
	}
}

func TestClosedStdoutOrStartupCancellationReapsOwnedChildWithinBound(t *testing.T) {
	for _, mode := range []string{"stderr-startup-hung", "stderr-startup-cancel"} {
		t.Run(mode, func(t *testing.T) {
			cmd := processPeerCommand(t, mode)
			timeout := 5 * time.Second
			if mode == "stderr-startup-cancel" {
				timeout = 100 * time.Millisecond
			}
			ctx, cancel := context.WithTimeout(context.Background(), timeout)
			defer cancel()
			started := time.Now()
			c, err := connect(ctx, cmd)
			if c != nil {
				c.Close()
				t.Fatal("Incomplete initialization became connected")
			}
			if err == nil || time.Since(started) > 3*time.Second {
				t.Fatal("Failed startup cleanup did not terminate within its bound", err)
			}
			d := requirePrivateProcessDiagnostic(t, err, "private-account")
			if !d.TerminationRequested || d.ExitCode == nil || d.WaitState == "unconfirmed" || cmd.ProcessState == nil {
				t.Fatal("Owned child cleanup was not confirmed", d)
			}
			if mode == "stderr-startup-cancel" && !errors.Is(err, context.DeadlineExceeded) {
				t.Fatal("Observation cancellation classification changed", err)
			}
		})
	}
}

func TestProcessStartFailureDoesNotExportArguments(t *testing.T) {
	cmd := exec.Command(filepath.Join(t.TempDir(), "missing-codex"), "--token=private-start-argument")
	c, err := connect(context.Background(), cmd)
	if c != nil || err == nil {
		t.Fatal("Missing executable became successful startup")
	}
	d := requirePrivateProcessDiagnostic(t, err, "private-start-argument")
	if d.WaitState != "not_started" || d.ExitCode != nil || d.Stderr != "" {
		t.Fatal("Start failure invented child exit status", d)
	}
}

func TestInitializationRejectionPreservesRPCCodeWithoutPrivateDump(t *testing.T) {
	cmd := processPeerCommand(t, "stderr-initialization-rejected")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, err := connect(ctx, cmd)
	if c != nil || err == nil {
		t.Fatal("Rejected initialization became connected")
	}
	var rpc *RPCError
	if !errors.As(err, &rpc) || rpc.Code != -32001 {
		t.Fatal("Rejected initialization lost actual RPC code", err)
	}
	d := requirePrivateProcessDiagnostic(t, err, "private-config-account")
	if !strings.Contains(d.Stderr, "configuration_invalid") || d.ExitCode == nil {
		t.Fatal("Initialization rejection lost safe cause/cleanup outcome", d)
	}
}

func TestExitedParentIsObservedWhileDescendantRetainsStdout(t *testing.T) {
	pidFile := filepath.Join(t.TempDir(), "owned-descendant.pid")
	cmd := processPeerCommand(t, "stderr-inherited-stdout-parent")
	cmd.Env = append(cmd.Env, "BRIDGE_CHECK_CHILD_PID="+pidFile)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, err := connect(ctx, cmd)
	if err != nil {
		t.Fatal("Parent did not initialize before its exit", err)
	}
	t.Cleanup(func() {
		if err := c.Close(); err != nil {
			t.Error(err)
		}
	})
	select {
	case <-c.Done():
	case <-time.After(1500 * time.Millisecond):
		t.Fatal("Inherited stdout hid actual parent exit until explicit Close")
	}
	if _, err := os.Stat(pidFile); err != nil {
		t.Fatal("Actual inheriting descendant was not started", err)
	}
	data, _ := os.ReadFile(pidFile)
	pid, parseErr := strconv.Atoi(string(data))
	if parseErr != nil || pid <= 0 {
		t.Fatal("Invalid owned descendant PID")
	}
	dead, cleanup := observeOwnedProcess(t, pid)
	t.Cleanup(cleanup)
	if !dead(1500 * time.Millisecond) {
		t.Fatal("Production cleanup did not retire inheriting descendant")
	}
	_, err = c.Models(ctx, "")
	d := requirePrivateProcessDiagnostic(t, err)
	if d.WaitState != "exited" || d.ExitCode == nil || *d.ExitCode != 41 || !strings.Contains(d.Stderr, "database_locked") {
		t.Fatal("Inherited stdout lost actual parent exit/cause", d)
	}
	if err := c.Close(); err != nil {
		t.Fatal("Close did not drain the owned stdout reader", err)
	}
	select {
	case <-c.readDone:
	default:
		t.Fatal("Close returned with its stdout reader still live")
	}
}

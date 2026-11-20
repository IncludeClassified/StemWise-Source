package codex

import (
	"errors"
	"fmt"
	"io"
	"os/exec"
	"strings"
	"sync"
	"time"

	"phonepcbridge/protocol"
)

const processReapTimeout = 1500 * time.Millisecond
const maxStderrLine = 4096
const maxStderrTail = 1024

// ProcessDiagnostic is scoped to one owned CLI/proxy attempt. Stderr contains
// only fixed technical markers; arbitrary account/configuration/prompt text is suppressed.
type ProcessDiagnostic struct {
	WaitState            string
	ExitCode             *int
	Stderr               string
	StderrTruncated      bool
	TerminationRequested bool
}

type processDiagnostic struct {
	mu                   sync.Mutex
	partial              []byte
	oversized            bool
	tail                 []string
	tailBytes            int
	truncated            bool
	waitState            string
	exitCode             *int
	terminationRequested bool
	reaped               <-chan struct{}
	attemptID            string
	pid                  int
	localTail            []string
	localBytes           int
	localTruncated       bool
	private              []string
	privateReplacer      *strings.Replacer
	privateBytes         int
	privateSuppressed    bool
}

// ProcessError preserves its original certainty/error classification through Unwrap.
type ProcessError struct {
	Cause      error
	diagnostic *processDiagnostic
}

func (e *ProcessError) Unwrap() error { return e.Cause }

func (e *ProcessError) Error() string {
	d := e.Diagnostic()
	cause := protocol.DiagnosticText(e.Cause.Error(), 512)
	var rpc *RPCError
	if errors.As(e.Cause, &rpc) {
		cause = fmt.Sprintf("Codex rejected request (%d)", rpc.Code)
	}
	value := fmt.Sprintf("Codex process failure: %s; wait_state=%s", cause, d.WaitState)
	if d.ExitCode != nil {
		value += fmt.Sprintf(" exit_code=%d", *d.ExitCode)
	}
	if d.TerminationRequested {
		value += " termination_requested=true"
	}
	if d.Stderr != "" {
		value += "; stderr_markers=" + d.Stderr
	}
	return value
}

// Diagnostic waits only for the bounded child reaper, never a later connection attempt.
func (e *ProcessError) Diagnostic() ProcessDiagnostic {
	if e.diagnostic == nil {
		return ProcessDiagnostic{WaitState: "unconfirmed"}
	}
	d := e.diagnostic
	if d.reaped != nil {
		timer := time.NewTimer(processReapTimeout)
		select {
		case <-d.reaped:
		case <-timer.C:
		}
		timer.Stop()
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	result := ProcessDiagnostic{WaitState: d.waitState, Stderr: strings.Join(d.tail, "\n"), StderrTruncated: d.truncated, TerminationRequested: d.terminationRequested}
	if result.WaitState == "" {
		result.WaitState = "unconfirmed"
	}
	if d.exitCode != nil {
		code := *d.exitCode
		result.ExitCode = &code
	}
	return result
}

// Fixed markers avoid exporting novel credentials or private text, even when a
// subprocess prints a complete request/configuration or splits a secret across writes.
var stderrMarkers = []struct{ fragment, code string }{
	{"database disk image is malformed", "database_corrupt"},
	{"database is locked", "database_locked"},
	{"toml parse error", "configuration_parse_failed"},
	{"failed to parse config", "configuration_parse_failed"},
	{"error parsing config", "configuration_parse_failed"},
	{"failed to load config", "configuration_load_failed"},
	{"invalid configuration", "configuration_invalid"},
	{"no space left on device", "storage_full"},
	{"read-only file system", "storage_read_only"},
	{"permission denied", "permission_denied"},
	{"access is denied", "permission_denied"},
	{"address already in use", "address_in_use"},
	{"connection refused", "connection_refused"},
	{"certificate verify failed", "certificate_verification_failed"},
	{"authentication failed", "authentication_failed"},
	{"unauthorized", "authentication_rejected"},
	{"rate limit", "rate_limited"},
	{"no such file or directory", "file_not_found"},
}

func (d *processDiagnostic) Write(data []byte) (int, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.partial == nil {
		d.partial = make([]byte, 0, maxStderrLine)
	}
	for _, b := range data {
		if b == '\n' {
			d.line()
			continue
		}
		if len(d.partial) < maxStderrLine {
			d.partial = append(d.partial, b)
		} else {
			d.oversized = true
			d.truncated = true
		}
	}
	return len(data), nil
}

func (d *processDiagnostic) line() {
	d.appendLocal(d.localLine(string(d.partial)))
	marker := "stderr_detail_suppressed"
	if !d.oversized {
		line := strings.ToLower(string(d.partial))
		for _, candidate := range stderrMarkers {
			if strings.Contains(line, candidate.fragment) {
				marker = candidate.code
				break
			}
		}
	}
	clear(d.partial)
	d.partial = d.partial[:0]
	d.oversized = false
	if len(d.tail) > 0 && d.tail[len(d.tail)-1] == marker {
		d.truncated = true
		return
	}
	d.tail = append(d.tail, marker)
	d.tailBytes += len(marker) + 1
	for d.tailBytes > maxStderrTail && len(d.tail) > 0 {
		d.tailBytes -= len(d.tail[0]) + 1
		d.tail = d.tail[1:]
		d.truncated = true
	}
}

func (d *processDiagnostic) finish(cmd *exec.Cmd, waitErr error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if len(d.partial) > 0 || d.oversized {
		d.line()
	}
	if cmd.ProcessState == nil {
		d.waitState = "unconfirmed"
		return
	}
	code := cmd.ProcessState.ExitCode()
	d.exitCode = &code
	d.waitState = "exited"
	if code < 0 {
		d.waitState = "signaled"
	}
	if errors.Is(waitErr, exec.ErrWaitDelay) || errors.Is(waitErr, io.ErrUnexpectedEOF) {
		d.truncated = true
		d.localTruncated = true
	}
}

func (d *processDiagnostic) terminating() { d.mu.Lock(); d.terminationRequested = true; d.mu.Unlock() }

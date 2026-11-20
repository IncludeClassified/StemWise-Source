package codex

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode"

	"phonepcbridge/protocol"
)

const maxLocalStderrTail = 8192
const maxPrivateStderrContext = 1 << 20
const maxPrivateStderrFragments = 4096

// LocalProcessDiagnostic is private to the owning host. It must never be put in
// wire replies; the caller may retain it with that attempt's existing local receipt.
type LocalProcessDiagnostic struct {
	AttemptID       string `json:"attemptId"`
	PID             int    `json:"pid,omitempty"`
	Stderr          string `json:"stderr"`
	StderrTruncated bool   `json:"stderrTruncated"`
}

func newProcessDiagnostic() (*processDiagnostic, error) {
	var id [16]byte
	if _, err := rand.Read(id[:]); err != nil {
		return nil, err
	}
	return &processDiagnostic{attemptID: hex.EncodeToString(id[:])}, nil
}

// LocalDiagnostic is an attempt-scoped, bounded snapshot, not an additional log
// service. Memory is reclaimed with the Connection/ProcessError owner.
func (e *ProcessError) LocalDiagnostic() LocalProcessDiagnostic {
	if e.diagnostic == nil {
		return LocalProcessDiagnostic{}
	}
	d := e.diagnostic
	e.Diagnostic()
	d.mu.Lock()
	defer d.mu.Unlock()
	return LocalProcessDiagnostic{AttemptID: d.attemptID, PID: d.pid, Stderr: strings.Join(d.localTail, "\n"), StderrTruncated: d.localTruncated}
}

var localPrivateLabel = regexp.MustCompile(`(?i)\b(?:account|email|prompt|input|instructions|config|configuration|authorization|cookie|credential|environment|connectionstring|database_url)["']?\s*[:=]`)
var localEmail = regexp.MustCompile(`[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}`)

func (d *processDiagnostic) localLine(raw string) string {
	if d.privateSuppressed {
		return "stderr_private_context_suppressed"
	}
	if d.oversized {
		d.localTruncated = true
		return "stderr_oversized_line_suppressed"
	}
	line := strings.TrimSpace(raw)
	// Dumps are not technical causes; retain their fixed exported category only.
	if strings.ContainsAny(line, "{}") || localPrivateLabel.MatchString(line) {
		return "stderr_structured_or_private_line_suppressed"
	}
	if d.privateReplacer != nil {
		line = d.privateReplacer.Replace(line)
	}
	line = protocol.DiagnosticText(line, maxStderrLine)
	line = localEmail.ReplaceAllString(line, "[email]")
	return strings.Map(func(r rune) rune {
		if unicode.IsControl(r) && r != '\t' {
			return -1
		}
		return r
	}, line)
}

func (d *processDiagnostic) appendLocal(line string) {
	if line == "" {
		return
	}
	if len(d.localTail) > 0 && d.localTail[len(d.localTail)-1] == line {
		d.localTruncated = true
		return
	}
	d.localTail = append(d.localTail, line)
	d.localBytes += len(line) + 1
	for d.localBytes > maxLocalStderrTail && len(d.localTail) > 0 {
		d.localBytes -= len(d.localTail[0]) + 1
		d.localTail[0] = ""
		d.localTail = d.localTail[1:]
		d.localTruncated = true
	}
}

func (d *processDiagnostic) rememberPrivateFrame(frame []byte) {
	var message struct{ Params any }
	if json.Unmarshal(frame, &message) != nil {
		return
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	previousCount := len(d.private)
	rememberText := func(text string) {
		for _, fragment := range append(strings.Split(text, "\n"), text) {
			fragment = strings.TrimSuffix(fragment, "\r")
			quoted, ascii := strconv.Quote(fragment), strconv.QuoteToASCII(fragment)
			d.rememberPrivate(fragment)
			d.rememberPrivate(quoted[1 : len(quoted)-1])
			d.rememberPrivate(ascii[1 : len(ascii)-1])
		}
	}
	var visit func(any, bool)
	visit = func(value any, private bool) {
		switch v := value.(type) {
		case map[string]any:
			for key, child := range v {
				sensitive := private
				switch key {
				case "text", "input", "instructions", "developerInstructions", "baseInstructions", "path", "cwd", "name":
					sensitive = true
				}
				visit(child, sensitive)
			}
		case []any:
			for _, child := range v {
				visit(child, private)
			}
		case string:
			if private && v != "" {
				rememberText(v)
				// The native text item contains one JSON envelope. Protect its
				// individual task fields too, without recursively parsing user data.
				if job, err := ParseJobMessage(v); err == nil {
					for _, field := range []string{job.Prompt, job.Instructions, job.Path, job.Header} {
						rememberText(field)
					}
				}
			}
		}
	}
	visit(message.Params, false)
	if d.privateSuppressed {
		d.localTail, d.localBytes = nil, 0
		d.privateReplacer = nil
		d.localTruncated = true
		d.appendLocal("stderr_private_context_suppressed")
		return
	}
	if len(d.private) != previousCount {
		// Prefer full escaped bodies over their shorter plain-text prefixes.
		sort.Slice(d.private, func(i, j int) bool { return len(d.private[i]) > len(d.private[j]) })
		pairs := make([]string, 0, 2*len(d.private))
		for _, value := range d.private {
			pairs = append(pairs, value, "[private]")
		}
		d.privateReplacer = strings.NewReplacer(pairs...)
	}
}

func (d *processDiagnostic) rememberPrivate(value string) {
	if d.privateSuppressed || value == "" {
		return
	}
	for _, previous := range d.private {
		if previous == value {
			return
		}
	}
	if len(d.private) >= maxPrivateStderrFragments || d.privateBytes+len(value) > maxPrivateStderrContext {
		clear(d.private)
		d.private = nil
		d.privateBytes = 0
		d.privateSuppressed = true
		return
	}
	d.private = append(d.private, value)
	d.privateBytes += len(value)
}

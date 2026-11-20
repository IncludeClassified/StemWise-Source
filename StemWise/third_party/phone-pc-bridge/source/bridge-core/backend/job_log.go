package backend

import (
	"bytes"
	"encoding/json"
	"phonepcbridge/protocol"
	"strings"
	"time"
)

// Diagnostic content never authorizes a transport or task state transition.
type appDiagnostic struct {
	Code         string            `json:"code"`
	Operation    string            `json:"operation"`
	Summary      string            `json:"summary"`
	Cause        string            `json:"cause"`
	Retryable    *bool             `json:"retryable"`
	Context      map[string]string `json:"context"`
	Expected     string            `json:"expected,omitempty"`
	Actual       string            `json:"actual,omitempty"`
	Stack        string            `json:"stack,omitempty"`
	SuggestedFix []string          `json:"suggestedFix"`
}

type appLogEntry struct {
	Source     string        `json:"source,omitempty"`
	Revision   int64         `json:"revision"`
	ObservedAt int64         `json:"observedAt"`
	Error      appDiagnostic `json:"error"`
}

func validDiagnostic(d *appDiagnostic) bool {
	if d == nil {
		return true
	}
	data, err := json.Marshal(d)
	if err != nil || len(data) > 4096 || !appCode.MatchString(d.Code) || !appCode.MatchString(d.Operation) || strings.TrimSpace(d.Summary) == "" || strings.TrimSpace(d.Cause) == "" || d.Retryable == nil || d.Context == nil || len(d.Context) > 16 || len(d.SuggestedFix) == 0 || len(d.SuggestedFix) > 8 {
		return false
	}
	for key, value := range d.Context {
		if !appCode.MatchString(key) || strings.TrimSpace(value) == "" {
			return false
		}
	}
	for _, fix := range d.SuggestedFix {
		if strings.TrimSpace(fix) == "" {
			return false
		}
	}
	return true
}

// Called after schema/size validation; rejected content must never enter job storage.
func requiresDiagnosticRedaction(d *appDiagnostic) bool {
	if d == nil {
		return false
	}
	values := append([]string{d.Summary, d.Cause, d.Expected, d.Actual, d.Stack}, d.SuggestedFix...)
	for _, value := range d.Context {
		values = append(values, value)
	}
	for _, value := range values {
		if protocol.DiagnosticText(value, 4096) != value {
			return true
		}
	}
	return false
}

func appendJobLog(j *appJob, entries []appLogEntry) {
	for _, entry := range entries {
		if entry.Source == "" {
			entry.Source = "developer"
		}
		if entry.Source == "developer" {
			if entry.Revision <= j.LogRevision {
				continue
			}
			j.LogRevision = entry.Revision
		} else {
			encoded, _ := json.Marshal(entry.Error)
			duplicate := false
			for _, old := range j.Log {
				other, _ := json.Marshal(old.Error)
				if old.Source == entry.Source && bytes.Equal(encoded, other) {
					duplicate = true
					break
				}
			}
			if duplicate {
				if powerLogIssue(j, entry) {
					j.pendingPowerIssue = true
				}
				continue
			}
		}
		entry.ObservedAt = time.Now().UnixMilli()
		if powerLogIssue(j, entry) {
			j.pendingPowerIssue = true
		}
		j.Log = append(j.Log, entry)
		if len(j.Log) > 8 {
			j.Log = j.Log[len(j.Log)-8:]
			j.LogDropped++
		}
	}
}

func jobNeedsPC(j *appJob) bool {
	return j.State == "completed" && j.Report != nil && j.Report.Status == "completed" && len(j.Outputs) == 0 && (j.Report.Result != nil || len(j.Report.Files) > 0)
}

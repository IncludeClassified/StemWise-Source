package backend

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	"phonepcbridge/protocol"
)

func fixtureDiagnostic() *appDiagnostic {
	retry := true
	return &appDiagnostic{Code: "source_unavailable", Operation: "read_input", Summary: "open returned ENOENT", Cause: "source_missing", Retryable: &retry, Context: map[string]string{"component": "input_reader", "exception": "ENOENT"}, Expected: "readable source", Actual: "missing source", SuggestedFix: []string{"verify source descriptor and retry original request"}}
}

func TestJobLogCommittedErrorsIndependentFromStatusAndPersisted(t *testing.T) {
	s, n, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	registerFixture(t, call, "nBob")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := &appJob{ID: randomToken(), TurnID: "fixture-turn", ThreadID: "fixture-chat", State: "submitted", Code: "native_acknowledged", Created: time.Now().UnixMilli(), Outputs: []protocol.File{}}
	j.Input = appInput{RequestID: j.ID, CreatedAt: j.Created, Title: "Diagnostic fixture", Text: "work-" + randomToken(), Files: []protocol.File{}}
	j.Fingerprint = appHash(j.Input)
	state.Jobs[j.ID] = j
	report := appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "working", Code: "recovering", Error: fixtureDiagnostic()}
	turn := func(committed bool) json.RawMessage {
		return mustJSON(map[string]any{"id": j.TurnID, "status": "inProgress", "items": []any{map[string]any{"type": "agentMessage", "text": string(mustJSON(report)), "bridgeReportCommitted": committed}}})
	}
	_, _, entries, err := appLatestReport(state.ID, j, turn(false))
	if err != nil || len(entries) != 0 {
		t.Fatal("Uncommitted error became a log", err)
	}
	r, status, entries, err := appLatestReport(state.ID, j, turn(true))
	if err != nil || status != "inProgress" || r.Status != "working" || len(entries) != 1 {
		t.Fatal(err)
	}
	appendJobLog(j, entries)
	appendJobLog(j, entries)
	if len(j.Log) != 1 || j.State != "submitted" || j.Code != "native_acknowledged" || len(j.Outputs) != 0 {
		t.Fatal("Log duplicated or changed independent status")
	}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	state, err = s.loadApp(p)
	if err != nil || len(state.Jobs[j.ID].Log) != 1 {
		t.Fatal("Diagnostic not durable", err)
	}
	w := call("GET", "/v2/jobs/"+j.ID+"/log", nil, "nAlice")
	if w.Code != 200 || !strings.Contains(w.Body.String(), "ENOENT") || !strings.Contains(w.Body.String(), `"observedAt":`) {
		t.Fatal(w.Body.String())
	}
	if w := call("GET", "/v2/jobs/"+j.ID+"/log", nil, "nBob"); w.Code != 404 {
		t.Fatal("Other app read diagnostic")
	}
	if w := call("GET", "/v2/jobs/"+j.ID, nil, "nAlice"); strings.Contains(w.Body.String(), "ENOENT") {
		t.Fatal("Diagnostics leaked into status")
	}
	n.online = true
	report.Status = "failed"
	report.Revision = 2
	n.report = string(mustJSON(report))
	if err := s.refreshApp(p, state, state.Jobs[j.ID]); err != nil {
		t.Fatal(err)
	}
	if state.Jobs[j.ID].State != "completed" || state.Jobs[j.ID].Report.Status != "failed" || len(state.Jobs[j.ID].Log) != 2 {
		t.Fatal("Agent failure conflated with native turn outcome")
	}
	if w := call("DELETE", "/v2/jobs/"+j.ID, nil, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	if w := call("GET", "/v2/jobs/"+j.ID+"/log", nil, "nAlice"); w.Code != 410 || !strings.Contains(w.Body.String(), `"code":"request_deleted"`) {
		t.Fatal("Deleted job retained log")
	}
}

func TestJobDiagnosticValidationAndBoundedLog(t *testing.T) {
	for _, name := range []string{"missing", "code", "operation", "summary", "cause", "retryable", "context", "fix", "oversized", "blank_summary", "blank_cause", "blank_context", "blank_fix"} {
		t.Run(name, func(t *testing.T) {
			d := fixtureDiagnostic()
			switch name {
			case "blank_summary":
				d.Summary = " \t\r\n"
			case "blank_cause":
				d.Cause = " "
			case "blank_context":
				d.Context["component"] = " "
			case "blank_fix":
				d.SuggestedFix = []string{" "}
			case "missing":
				d = nil
			case "code":
				d.Code = "Hello there"
			case "operation":
				d.Operation = "read input"
			case "summary":
				d.Summary = ""
			case "cause":
				d.Cause = ""
			case "retryable":
				d.Retryable = nil
			case "context":
				d.Context = nil
			case "fix":
				d.SuggestedFix = nil
			case "oversized":
				d.Stack = strings.Repeat("x", 4096)
			}
			j := &appJob{ID: randomToken(), TurnID: "owned"}
			r := appReport{Protocol: "v2", AppID: "app", JobID: j.ID, Revision: 1, Status: "failed", Code: "failed", Error: d}
			turn := mustJSON(map[string]any{"id": j.TurnID, "status": "completed", "bridgeTerminalCommitted": true, "items": []any{map[string]any{"type": "agentMessage", "text": string(mustJSON(r))}}})
			if _, _, entries, err := appLatestReport("app", j, turn); err == nil || err.Error() != "invalid_developer_diagnostic" || len(entries) != 0 {
				t.Fatal("Incomplete diagnostic accepted")
			}
		})
	}
	j := &appJob{}
	for revision := int64(1); revision <= 10; revision++ {
		appendJobLog(j, []appLogEntry{{Revision: revision, Error: *fixtureDiagnostic()}})
	}
	if len(j.Log) != 8 || j.LogDropped != 2 || j.Log[0].Revision != 3 {
		t.Fatal("Log bound or explicit truncation count incorrect")
	}
	appendJobLog(j, []appLogEntry{{Revision: 1, Error: *fixtureDiagnostic()}})
	if len(j.Log) != 8 || j.LogDropped != 2 {
		t.Fatal("Old repeated report recreated dropped diagnostics")
	}
}

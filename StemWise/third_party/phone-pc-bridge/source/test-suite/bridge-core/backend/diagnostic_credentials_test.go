package backend

import (
	"context"
	"strings"
	"testing"
)

func TestDeveloperCredentialDiagnosticsNeverReachStoredReportsOrLogs(t *testing.T) {
	for _, field := range []string{"summary", "cause", "context", "expected", "actual", "stack", "fix"} {
		t.Run(field, func(t *testing.T) {
			s, _, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			j := ownedOutputJob(randomToken())
			state.Jobs[j.ID] = j
			secret := randomToken()
			d := fixtureDiagnostic()
			value := "Bearer " + secret
			switch field {
			case "summary":
				d.Summary = value
			case "cause":
				d.Cause = "api_key=" + secret
			case "context":
				d.Context["response"] = "password='" + secret + "'"
			case "expected":
				d.Expected = value
			case "actual":
				d.Actual = "sk-" + secret
			case "stack":
				d.Stack = "refresh_token=" + secret
			case "fix":
				d.SuggestedFix = []string{"secret=" + secret}
			}
			report := appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "failed", Code: "source_unavailable", Error: d}
			turn := mustJSON(map[string]any{"id": j.TurnID, "status": "completed", "bridgeTerminalCommitted": true, "items": []any{map[string]any{"type": "agentMessage", "text": string(mustJSON(report))}}})
			for i := 0; i < 2; i++ {
				p.appMu.Lock()
				err := s.applyAppTurn(context.Background(), p, state, j, turn)
				p.appMu.Unlock()
				if err != nil {
					t.Fatal(err)
				}
			}
			state, err = s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			stored := state.Jobs[j.ID]
			if stored.State != "completed" || stored.Code != "invalid_developer_protocol" || stored.Report != nil || len(stored.Outputs) != 0 || len(stored.Log) != 1 || stored.Log[0].Source != "bridge" || stored.Log[0].Error.Cause != "developer_diagnostic_redaction_required" || strings.Contains(string(mustJSON(state)), secret) {
				t.Fatal("Credential-bearing developer output was stored, trusted, duplicated or confused with native status")
			}
			w := call("GET", "/v2/jobs/"+j.ID+"/log", nil, "nAlice")
			if w.Code != 200 || strings.Contains(w.Body.String(), secret) || !strings.Contains(w.Body.String(), "developer_diagnostic_redaction_required") {
				t.Fatal("Unsafe diagnostic was exported or rejection reason missing")
			}
		})
	}
}

func TestCredentialCheckPreservesValidDiagnosticAndRevisionConflicts(t *testing.T) {
	j := ownedOutputJob(randomToken())
	r := appReport{Protocol: "v2", AppID: "app", JobID: j.ID, Revision: 1, Status: "failed", Code: "source_unavailable", Error: fixtureDiagnostic()}
	item := func() map[string]any { return map[string]any{"type": "agentMessage", "text": string(mustJSON(r))} }
	first := item()
	turn := mustJSON(map[string]any{"id": j.TurnID, "status": "completed", "bridgeTerminalCommitted": true, "items": []any{first}})
	got, status, logs, err := appLatestReport("app", j, turn)
	if err != nil || status != "completed" || got.Status != "failed" || len(logs) != 1 || string(mustJSON(got)) != string(mustJSON(r)) {
		t.Fatal("Valid technical diagnostic changed or disappeared", err)
	}
	r.Error.Context["exception"] = "EACCES"
	turn = mustJSON(map[string]any{"id": j.TurnID, "status": "completed", "bridgeTerminalCommitted": true, "items": []any{first, item()}})
	if _, _, _, err := appLatestReport("app", j, turn); err == nil || err.Error() != "conflicting_developer_revision" {
		t.Fatal("Credential enforcement weakened immutable revision checks", err)
	}
}

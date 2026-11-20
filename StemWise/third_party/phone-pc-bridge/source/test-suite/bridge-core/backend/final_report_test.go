package backend

import (
	"phonepcbridge/protocol"
	"strings"
	"testing"
	"time"
)

func TestEndedNativeTurnRequiresFinalDeveloperReport(t *testing.T) {
	for _, status := range []string{"none", "working", "waiting"} {
		t.Run(status, func(t *testing.T) {
			s, n, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			j := &appJob{ID: randomToken(), TurnID: "owned-turn", ThreadID: "fixture-chat", State: "submitted", Created: time.Now().UnixMilli(), Outputs: []protocol.File{}}
			j.Input = appInput{RequestID: j.ID, CreatedAt: j.Created, Title: "final report fixture", Text: "original-" + randomToken(), Files: []protocol.File{}}
			j.Fingerprint = appHash(j.Input)
			state.Jobs[j.ID] = j
			items := []any{}
			if status != "none" {
				report := appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: status, Code: "pending"}
				items = append(items, map[string]any{"type": "agentMessage", "text": string(mustJSON(report))})
			}
			s.native = &nativeFailureFixture{nativeFixture: n, turn: mustJSON(map[string]any{"id": j.TurnID, "status": "completed", "bridgeTerminalCommitted": true, "items": items})}
			for i := 0; i < 2; i++ {
				if err := s.refreshApp(p, state, j); err != nil {
					t.Fatal(err)
				}
			}
			if j.State != "completed" || j.Code != "developer_terminal_report_missing" || len(j.Outputs) != 0 || len(j.Log) != 1 || j.Log[0].Source != "bridge" {
				t.Fatal("Missing final outcome was hidden or reported as success")
			}
			if status != "none" && (j.Report == nil || j.Report.Status != status) {
				t.Fatal("Bridge rewrote developer's actual claim")
			}
			w := call("GET", "/v2/jobs/"+j.ID, nil, "nAlice")
			if w.Code != 200 || !strings.Contains(w.Body.String(), "developer_terminal_report_missing") || !strings.Contains(w.Body.String(), `"issue":{`) {
				t.Fatal("Apps cannot interpret missing final outcome", w.Body.String())
			}
		})
	}
}

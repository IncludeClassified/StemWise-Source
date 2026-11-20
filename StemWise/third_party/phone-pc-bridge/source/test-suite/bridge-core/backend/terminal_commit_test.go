package backend

import (
	"bytes"
	"context"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func TestUncommittedCompletionCannotPublishOrReplay(t *testing.T) {
	for _, marker := range []string{"absent", "false"} {
		for _, itemCommitted := range []bool{false, true} {
			t.Run(marker+"/item_"+strconv.FormatBool(itemCommitted), func(t *testing.T) {
				s, n, call := fixtureAPI(t)
				registerFixture(t, call, "nAlice")
				p := s.getPeer("nAlice")
				state, err := s.loadApp(p)
				if err != nil {
					t.Fatal(err)
				}
				j := &appJob{ID: randomToken(), TurnID: "fixture-turn", ThreadID: "fixture-chat", State: "submitted", Code: "native_acknowledged", Created: time.Now().UnixMilli(), Outputs: []protocol.File{}}
				j.Input = appInput{RequestID: j.ID, CreatedAt: j.Created, Title: "Uncommitted completion", Text: "original-" + randomToken(), Files: []protocol.File{}}
				j.Fingerprint = appHash(j.Input)
				state.Jobs[j.ID] = j
				if err := s.saveApp(state); err != nil {
					t.Fatal(err)
				}
				data := []byte("actual-producer-bytes-" + randomToken())
				f, _ := files.Describe(randomToken(), "owned.bin", data)
				result := "private-uncommitted-result-" + randomToken()
				report := appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "output_ready", Result: &result, Files: []protocol.File{f}}
				turn := map[string]any{"id": j.TurnID, "status": "completed", "items": []any{map[string]any{"type": "agentMessage", "text": string(mustJSON(report)), "bridgeReportCommitted": itemCommitted}}}
				if marker == "false" {
					turn["bridgeTerminalCommitted"] = false
				}
				n.online, n.outputs = true, map[string][]byte{f.ID: data}
				native := &nativeFailureFixture{nativeFixture: n, turn: mustJSON(turn)}
				s.native = native
				for i := 0; i < 2; i++ {
					if err := s.workPass(); err != nil {
						t.Fatal(err)
					}
				}
				state, err = s.loadApp(p)
				if err != nil {
					t.Fatal(err)
				}
				j = state.Jobs[j.ID]
				if j.State != "submitted" || j.Code != "native_terminal_unconfirmed" || j.Report != nil || len(j.Outputs) != 0 || n.calls != 0 {
					t.Fatal("Uncommitted completion settled/published or replayed acknowledged work")
				}
				if len(j.Log) != 1 || j.Log[0].Source != "bridge" || j.Log[0].Error.Code != "native_terminal_unconfirmed" || !validDiagnostic(&j.Log[0].Error) {
					t.Fatal("Uncommitted native completion lost its separate deduplicated diagnostic")
				}
				for _, id := range []string{f.ID, appResultID(state.ID, j.ID)} {
					if _, err := s.outbox.Info(p.identity, id); !os.IsNotExist(err) {
						t.Fatal("Uncommitted native output bytes were staged", err)
					}
				}
				view := call("GET", "/v2/jobs/"+j.ID, nil, "nAlice")
				if view.Code != 200 || !strings.Contains(view.Body.String(), `"issue":{`) || !strings.Contains(view.Body.String(), `"code":"native_terminal_unconfirmed"`) || strings.Contains(view.Body.String(), result) {
					t.Fatal("App cannot distinguish unconfirmed completion or received native content")
				}
				resumed, err := New(s.cfg, native, func(_ context.Context, remote string) (string, error) { return remote, nil }, nil)
				if err != nil {
					t.Fatal(err)
				}
				defer resumed.Close()
				if err := resumed.workPass(); err != nil {
					t.Fatal(err)
				}
				stored, err := resumed.loadApp(resumed.getPeer("nAlice"))
				if err != nil || stored.Jobs[j.ID].State != "submitted" || stored.Jobs[j.ID].Report != nil || len(stored.Jobs[j.ID].Outputs) != 0 || n.calls != 0 {
					t.Fatal("Reopening accepted uncommitted completion or replayed native work", err)
				}
				turn["bridgeTerminalCommitted"] = true
				native.turn = mustJSON(turn)
				if err := resumed.workPass(); err != nil {
					t.Fatal(err)
				}
				state, err = resumed.loadApp(resumed.getPeer("nAlice"))
				if err != nil {
					t.Fatal(err)
				}
				j = state.Jobs[j.ID]
				if j.State != "completed" || j.Code != "native_turn_completed" || len(j.Outputs) != 2 || len(j.Log) != 1 || n.calls != 0 {
					t.Fatal("Committed recovery failed, lost the diagnostic or created replacement native work")
				}
				for _, output := range j.Outputs {
					_, actual, err := resumed.outbox.Read(p.identity, output.ID)
					want := data
					if output.ID == appResultID(state.ID, j.ID) {
						want = []byte(result)
					}
					if err != nil || !bytes.Equal(actual, want) {
						t.Fatal("Recovered output bytes differ", err)
					}
				}
			})
		}
	}
}

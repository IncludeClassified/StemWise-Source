package backend

import (
	"bytes"
	"path/filepath"
	"strings"
	"testing"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func TestOutputCapacityRetainsCompletionAndRecoversWithoutNativeReplay(t *testing.T) {
	for _, inline := range []bool{false, true} {
		t.Run(map[bool]string{false: "artifact", true: "inline"}[inline], func(t *testing.T) {
			s, n, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			j := ownedOutputJob(randomToken())
			state.Jobs[j.ID] = j
			if err := s.saveApp(state); err != nil {
				t.Fatal(err)
			}
			data := []byte("actual-owned-result-" + randomToken())
			f, _ := files.Describe(randomToken(), "owned.bin", data)
			report := appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "ready"}
			if inline {
				result := string(data)
				report.Result = &result
			} else {
				report.Files = []protocol.File{f}
			}
			n.online, n.report, n.outputs[f.ID] = true, string(mustJSON(report)), data
			reset := func(minimum uint64) {
				t.Helper()
				s.outbox.Close()
				var err error
				s.outbox, err = files.OpenWithBudget(filepath.Join(s.cfg.DataDir, "outgoing"), files.NewBudget(minimum))
				if err != nil {
					t.Fatal(err)
				}
			}
			reset(^uint64(0))
			if err := s.workPass(); err != nil {
				t.Fatal(err)
			}
			state, err = s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			j = state.Jobs[j.ID]
			if j.State != "completed" || j.Code != "output_bytes_unverified" || j.Report == nil || len(j.Outputs) != 0 || len(j.Log) != 1 || n.calls != 0 {
				t.Fatal("Capacity refusal lost native completion, published a partial batch or replayed work")
			}
			if !strings.Contains(j.Log[0].Error.Context["error_detail"], "storage_capacity_unavailable") || !strings.Contains(strings.Join(j.Log[0].Error.SuggestedFix, " "), "wait_for_active_transfers") {
				t.Fatal("Capacity diagnostic lacks its distinct cause or recovery action")
			}
			reset(storageReserveBytes)
			if err := s.workPass(); err != nil {
				t.Fatal(err)
			}
			state, err = s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			j = state.Jobs[j.ID]
			if len(j.Outputs) != 1 || j.Code != "native_turn_completed" || len(j.Log) != 1 || n.calls != 0 {
				t.Fatal("Capacity recovery replaced work, lost the log or failed to publish the original output")
			}
			_, actual, err := s.outbox.Read(p.identity, j.Outputs[0].ID)
			if err != nil || !bytes.Equal(actual, data) {
				t.Fatal("Recovered bytes differ", err)
			}
		})
	}
}

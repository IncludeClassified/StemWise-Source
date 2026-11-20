package backend

import (
	"bytes"
	"strings"
	"testing"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func reportProgressItem(text string, committed bool) map[string]any {
	return map[string]any{"type": "agentMessage", "text": text, "bridgeReportCommitted": committed}
}

func TestAgentProgressPreservesFinalReportAndVerifiedBatch(t *testing.T) {
	s, n, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	state.Jobs[j.ID] = j
	data := []byte{0, 255, 9, 3, 0, 128}
	f, err := files.Describe(randomToken(), "verified.bin", data)
	if err != nil {
		t.Fatal(err)
	}
	n.outputs[f.ID] = data
	working := appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "working", Code: "processing"}
	final := appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 2, Status: "completed", Code: "ready", Files: []protocol.File{f}}
	s.native = &nativeFailureFixture{nativeFixture: n, turn: mustJSON(map[string]any{
		"id": j.TurnID, "status": "completed", "bridgeTerminalCommitted": true,
		"items": []any{
			reportProgressItem("Reading the input and checking its format.", true),
			reportProgressItem(`{"progress":{"bridgeJobReport":"v2"},"message":"checking inputs"}`, true),
			reportProgressItem(string(mustJSON(working)), true),
			reportProgressItem("The output bytes are prepared.", true),
			reportProgressItem(string(mustJSON(final)), false),
		},
	})}
	for i := 0; i < 2; i++ {
		if err := s.refreshApp(p, state, j); err != nil {
			t.Fatal(err)
		}
	}
	state, err = s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j = state.Jobs[j.ID]
	if j.State != "completed" || j.Code != "native_turn_completed" || j.Report == nil || string(mustJSON(j.Report)) != string(mustJSON(final)) || len(j.Outputs) != 1 || j.Outputs[0] != f || len(j.Log) != 0 || n.calls != 0 {
		t.Fatal("Ordinary committed progress poisoned the final report, duplicated work or bypassed its batch", j)
	}
	w := call("GET", "/v2/jobs/"+j.ID, nil, p.identity)
	if w.Code != 200 || strings.Contains(w.Body.String(), `"issue":{`) || strings.Contains(w.Body.String(), "Reading the input") || !strings.Contains(w.Body.String(), f.ID) {
		t.Fatal("Public job lost verified output or exposed native progress", w.Body.String())
	}
	w = call("GET", "/v1/outbox/"+f.ID+"/content?nonce="+randomToken(), nil, p.identity)
	if w.Code != 200 || !bytes.Equal(w.Body.Bytes(), data) {
		t.Fatal("Final report did not publish the actual verified producer bytes", w.Code)
	}
}

func TestProgressCannotSettleMissingUnverifiedOrCancelledOutcome(t *testing.T) {
	for _, name := range []string{"missing_final", "uncommitted_terminal", "unverified_batch", "unowned_batch", "different_turn", "active_unknown", "cancel_pending"} {
		t.Run(name, func(t *testing.T) {
			s, n, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			j := ownedOutputJob(randomToken())
			j.Code = "native_acknowledged"
			state.Jobs[j.ID] = j
			data := []byte("actual output")
			f, err := files.Describe(randomToken(), "output.bin", data)
			if err != nil {
				t.Fatal(err)
			}
			n.outputs[f.ID] = data
			final := appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "ready", Files: []protocol.File{f}}
			item := reportProgressItem(string(mustJSON(final)), true)
			items := []any{reportProgressItem("Checking the produced data.", true), item}
			id, status, terminal := j.TurnID, "completed", true
			wantState, wantCode := "completed", "developer_terminal_report_missing"
			wantLookupError := false
			switch name {
			case "missing_final":
				items = items[:1]
			case "uncommitted_terminal":
				terminal, wantState, wantCode = false, "submitted", "native_terminal_unconfirmed"
			case "unverified_batch":
				n.outputs[f.ID] = []byte("wrong output")
				wantCode = "output_bytes_unverified"
			case "unowned_batch":
				item["bridgeOutputOwnershipUnconfirmed"] = true
				wantCode = "job_output_ownership_unconfirmed"
			case "different_turn":
				j.State, j.Code = "unknown", "dispatch_outcome_unconfirmed"
				id, wantState, wantCode = "another-turn", "unknown", "dispatch_outcome_unconfirmed"
				wantLookupError = true
			case "active_unknown":
				j.State, j.Code = "unknown", "dispatch_outcome_unconfirmed"
				status, terminal, wantState, wantCode = "inProgress", false, "unknown", "dispatch_outcome_unconfirmed"
			case "cancel_pending":
				j.CancelRequested, j.CancelReason = j.Created, "user_request"
				wantState, wantCode = "submitted", "cancellation_pending"
			}
			s.native = &nativeFailureFixture{nativeFixture: n, turn: mustJSON(map[string]any{"id": id, "status": status, "bridgeTerminalCommitted": terminal, "items": items})}
			if err := s.refreshApp(p, state, j); (wantLookupError && (err == nil || err.Error() != "native_turn_unavailable")) || (!wantLookupError && err != nil) {
				t.Fatal("Unexpected original-turn lookup outcome", err)
			}
			state, err = s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			j = state.Jobs[j.ID]
			if j.State != wantState || j.Code != wantCode || len(j.Outputs) != 0 || n.calls != 0 || j.CancelRetired != 0 {
				t.Fatal("Progress or a report bypassed native authority, verification or cancellation", j)
			}
			if w := call("GET", "/v1/outbox/"+f.ID+"/content?nonce="+randomToken(), nil, p.identity); w.Code != 404 {
				t.Fatal("Unconfirmed output became publicly available", w.Code)
			}
		})
	}
}

func TestClaimedReportRemainsStrictAlongsideOrdinaryProgress(t *testing.T) {
	j := ownedOutputJob(randomToken())
	final := appReport{Protocol: "v2", AppID: "app", JobID: j.ID, Revision: 1, Status: "completed", Code: "ready"}
	valid := string(mustJSON(final))
	for _, bad := range []string{
		`{"bridgeJobReport":`,
		`{"bridgeJobReport":null}`,
		`{"bridgeJobReport":"v1"}`,
		`{"bridge\u004aobReport":"v2","revision":`,
		strings.TrimSuffix(valid, "}") + `,"revision":1}`,
		strings.TrimSuffix(valid, "}") + `,"unexpected":true}`,
		strings.TrimSuffix(valid, "}") + `,"padding":"` + strings.Repeat("x", 32768) + `"}`,
	} {
		turn := mustJSON(map[string]any{"id": j.TurnID, "status": "completed", "bridgeTerminalCommitted": true, "items": []any{reportProgressItem("Preparing results.", true), reportProgressItem(bad, true), reportProgressItem(valid, true)}})
		if _, _, _, err := appLatestReport("app", j, turn); err == nil || err.Error() != "invalid_developer_protocol" {
			t.Fatal("Malformed explicit report claim was ignored", err)
		}
	}
	final.Code = "changed"
	turn := mustJSON(map[string]any{"id": j.TurnID, "status": "completed", "bridgeTerminalCommitted": true, "items": []any{reportProgressItem(valid, true), reportProgressItem("Checking the final result.", true), reportProgressItem(string(mustJSON(final)), true)}})
	if _, _, _, err := appLatestReport("app", j, turn); err == nil || err.Error() != "conflicting_developer_revision" {
		t.Fatal("Progress weakened conflicting report revisions", err)
	}
	for _, committed := range []bool{false, true} {
		turn := mustJSON(map[string]any{"id": j.TurnID, "status": "inProgress", "items": []any{reportProgressItem(`{"bridgeJobReport":`, committed)}})
		report, status, entries, err := appLatestReport("app", j, turn)
		if committed {
			if err == nil || err.Error() != "invalid_developer_protocol" {
				t.Fatal("Committed malformed claim was not rejected", err)
			}
		} else if err != nil || report != nil || len(entries) != 0 || status != "inProgress" {
			t.Fatal("An uncommitted preview acquired report authority", err)
		}
	}
}

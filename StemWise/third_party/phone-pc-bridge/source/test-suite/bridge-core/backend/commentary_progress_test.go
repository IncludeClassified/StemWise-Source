package backend

import (
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"testing/synctest"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

type commentaryView struct {
	Stage      string `json:"stage"`
	Source     string `json:"source"`
	ObservedAt int64  `json:"observedAt"`
	Formatting string `json:"formatting"`
}

type commentaryHarness struct {
	s      *Service
	native *nativeFailureFixture
	p      *peer
	jobID  string
}

func newCommentaryHarness(t *testing.T) *commentaryHarness {
	t.Helper()
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	j.Code = "native_acknowledged"
	state.Jobs[j.ID] = j
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	native := &nativeFailureFixture{nativeFixture: base}
	s.native = native
	return &commentaryHarness{s, native, p, j.ID}
}

func commentaryItem(id, text string) map[string]any {
	return map[string]any{"type": "agentMessage", "id": id, "phase": "commentary", "text": text, "bridgeReportCommitted": true}
}

func (h *commentaryHarness) observe(t *testing.T, status string, terminal bool, items ...map[string]any) {
	t.Helper()
	state, err := h.s.loadApp(h.p)
	if err != nil {
		t.Fatal(err)
	}
	j := state.Jobs[h.jobID]
	h.native.turn = mustJSON(map[string]any{"id": j.TurnID, "status": status, "bridgeTerminalCommitted": terminal, "items": items})
	if err := h.s.refreshApp(h.p, state, j); err != nil {
		t.Fatal(err)
	}
}

func (h *commentaryHarness) public(t *testing.T) (*commentaryView, []byte) {
	t.Helper()
	r := httptest.NewRequest("GET", "/v2/jobs/"+h.jobID, nil)
	r.RemoteAddr = h.p.identity
	w := httptest.NewRecorder()
	h.s.ServeHTTP(w, r)
	var response struct {
		Data map[string]json.RawMessage `json:"data"`
	}
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &response) != nil {
		t.Fatal("Job status unavailable", w.Code, w.Body.String())
	}
	raw, present := response.Data["progress"]
	if !present {
		t.Fatal("Job view omitted its explicit nullable progress field", w.Body.String())
	}
	if bytes.Equal(raw, []byte("null")) {
		return nil, w.Body.Bytes()
	}
	var view commentaryView
	if protocol.DecodeStrict(raw, &view) != nil || view.Source != "commentary_marker" || view.ObservedAt <= 0 {
		t.Fatal("Progress exposed an unrecognized shape, raw commentary or an invalid observation", string(raw))
	}
	return &view, w.Body.Bytes()
}

func (h *commentaryHarness) saved(t *testing.T) []byte {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(h.s.cfg.DataDir, "apps", appStateName(h.p.identity)))
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func TestReservedCommentaryMarkersAreIndependentInterimStages(t *testing.T) {
	h := newCommentaryHarness(t)
	for _, test := range []struct{ text, stage, formatting string }{
		{"[[bridge:preparing]]", "preparing", "plain"}, {"[[bridge:reading]]", "reading", "plain"}, {"[[bridge:processing]]", "processing", "plain"},
		{"[[bridge:testing]]", "testing", "plain"}, {"[[bridge:packaging]]", "packaging", "plain"}, {"[[bridge:transferring]]", "transferring", "plain"}, {"[[bridge:waiting]]", "waiting", "plain"},
		{" [[bridge:processing]]\n", "processing", "normalized"},
		{"[[BRIDGE:TESTING]]", "testing", "normalized"},
		{"**[[bridge:reading]]**", "reading", "normalized"}, {"*[[bridge:testing]]*", "testing", "normalized"}, {"_[[bridge:packaging]]_", "packaging", "normalized"},
		{"`[[bridge:transferring]]`", "transferring", "normalized"}, {"```\n[[bridge:waiting]]\n```", "waiting", "normalized"},
		{"# [[bridge:preparing]]", "preparing", "normalized"}, {"- [[bridge:processing]]", "processing", "normalized"},
	} {
		t.Run(test.text, func(t *testing.T) {
			item := commentaryItem("stage-"+randomToken(), test.text)
			if test.text == "[[bridge:preparing]]" {
				delete(item, "phase") // Older valid native commentary may omit phase.
			} else if test.text == "[[bridge:reading]]" {
				item["phase"] = ""
			}
			h.observe(t, "inProgress", false, item)
			progress, raw := h.public(t)
			if progress == nil || progress.Stage != test.stage || progress.Formatting != test.formatting {
				t.Fatal("Whole committed reserved marker did not become the exact interim stage", progress)
			}
			state, err := h.s.loadApp(h.p)
			if err != nil {
				t.Fatal(err)
			}
			j := state.Jobs[h.jobID]
			if j.State != "submitted" || j.Code != "native_acknowledged" || j.Report != nil || len(j.Outputs) != 0 || len(j.Log) != 0 || h.native.calls != 0 || bytes.Contains(raw, []byte(item["id"].(string))) {
				t.Fatal("Commentary became job authority, diagnostics, outputs, item IDs or native replay")
			}
			if bytes.Contains(raw, mustJSON(test.text)) || bytes.Contains(h.saved(t), mustJSON(test.text)) {
				t.Fatal("Progress retained the raw native marker instead of its bounded stage")
			}
		})
	}
}

func TestOrdinaryCommentaryCannotTriggerOrReplaceReservedProgress(t *testing.T) {
	for _, text := range []string{
		"preparing", "reading", "processing", "testing", "packaging", "transferring", "waiting", "completed", "failed",
		"I am processing the source now.", "transferring 50%", "not testing", "will be testing", "processing packaging", "processing\ntesting",
		"> [[bridge:processing]]", `"[[bridge:processing]]"`, "'[[bridge:processing]]'", "not [[bridge:testing]]", "will emit [[bridge:processing]]",
		`{"marker":"[[bridge:processing]]"}`, "Example: `[[bridge:processing]]`", "**processing**", strings.Repeat("x", 257),
	} {
		t.Run(text, func(t *testing.T) {
			h := newCommentaryHarness(t)
			knownID := "known-" + randomToken()
			h.observe(t, "inProgress", false, commentaryItem("ordinary-only", text))
			if progress, _ := h.public(t); progress != nil {
				t.Fatal("Ordinary text created a stage without a reserved marker", progress)
			}
			known := commentaryItem(knownID, "[[bridge:reading]]")
			h.observe(t, "inProgress", false, known)
			before, _ := h.public(t)
			id := "ordinary-" + randomToken()
			h.observe(t, "inProgress", false, known, commentaryItem(id, text))
			progress, public := h.public(t)
			if progress == nil || before == nil || *progress != *before {
				t.Fatal("Unrelated prose replaced or refreshed the last committed marker", progress, before)
			}
			stored := h.saved(t)
			if bytes.Contains(stored, []byte(id)) || !bytes.Contains(stored, []byte(knownID)) || bytes.Contains(public, []byte(knownID)) {
				t.Fatal("Progress retained an unrelated item's identity or exported its private item ID")
			}
			if text != "reading" && (bytes.Contains(public, mustJSON(text)) || bytes.Contains(stored, mustJSON(text))) {
				t.Fatal("Progress retained raw ordinary commentary")
			}
		})
	}
}

func TestMalformedExplicitCommentaryMarkerIsUnknownWithoutRawText(t *testing.T) {
	h := newCommentaryHarness(t)
	previousID := ""
	for _, text := range []string{
		"[[bridge:]]", "[[bridge:unknown]]", "[[bridge:completed]]", "[[bridge:failed]]", "[[bridge:processing]", "[[bridge:testing",
		"[[bridge:processing]] extra", "[[bridge:processing]][[bridge:testing]]", "[[bridge:processing]] testing", "**[[bridge:unknown]]**",
		"[[bridge:" + strings.Repeat("x", 257) + "]]",
	} {
		t.Run(text, func(t *testing.T) {
			knownID := "known-" + randomToken()
			known := commentaryItem(knownID, "[[bridge:reading]]")
			h.observe(t, "inProgress", false, known)
			id := "malformed-" + randomToken()
			h.observe(t, "inProgress", false, known, commentaryItem(id, text))
			progress, public := h.public(t)
			if progress == nil || progress.Stage != "unknown" || progress.Formatting != "unrecognized" {
				t.Fatal("Malformed explicit marker was accepted or kept an earlier stage", progress)
			}
			stored := h.saved(t)
			if bytes.Contains(public, mustJSON(text)) || bytes.Contains(stored, mustJSON(text)) || bytes.Contains(stored, []byte(knownID)) || (previousID != "" && bytes.Contains(stored, []byte(previousID))) || !bytes.Contains(stored, []byte(id)) {
				t.Fatal("Malformed marker retained raw text/history or lost its latest item identity")
			}
			previousID = id
		})
	}
}

func TestCommentaryRequiresCommittedExactActiveAgentItem(t *testing.T) {
	for _, name := range []string{"uncommitted", "user", "tool", "final_answer", "partial_answer", "missing_id", "invalid_id", "different_turn", "structured_report"} {
		t.Run(name, func(t *testing.T) {
			h := newCommentaryHarness(t)
			item := commentaryItem("stage-"+randomToken(), "[[bridge:processing]]")
			switch name {
			case "uncommitted":
				item["bridgeReportCommitted"] = false
			case "user":
				item["type"] = "userMessage"
			case "tool":
				item["type"] = "commandExecution"
			case "final_answer", "partial_answer":
				item["phase"] = name
			case "missing_id":
				delete(item, "id")
			case "invalid_id":
				item["id"] = "not/an/item-id"
			case "structured_report":
				state, err := h.s.loadApp(h.p)
				if err != nil {
					t.Fatal(err)
				}
				item["text"] = string(mustJSON(appReport{Protocol: "v2", AppID: state.ID, JobID: h.jobID, Revision: 1, Status: "working", Code: "processing"}))
			}
			if name == "different_turn" {
				state, err := h.s.loadApp(h.p)
				if err != nil {
					t.Fatal(err)
				}
				h.native.turn = mustJSON(map[string]any{"id": "another-turn", "status": "inProgress", "items": []any{item}})
				if err := h.s.refreshApp(h.p, state, state.Jobs[h.jobID]); err == nil || err.Error() != "native_turn_unavailable" {
					t.Fatal("Different turn supplied progress", err)
				}
			} else {
				h.observe(t, "inProgress", false, item)
			}
			if progress, _ := h.public(t); progress != nil {
				t.Fatal("Unrelated/uncommitted native item acquired progress authority", progress)
			}
		})
	}
}

func TestCommentaryNeverReplacesTerminalReportVerificationOrCancellation(t *testing.T) {
	for _, name := range []string{"missing_report", "uncommitted_terminal", "verified_report", "bad_output", "native_failed", "native_interrupted", "cancelled", "unknown"} {
		t.Run(name, func(t *testing.T) {
			h := newCommentaryHarness(t)
			h.observe(t, "inProgress", false, commentaryItem("first-stage", "[[bridge:testing]]"))
			if progress, _ := h.public(t); progress == nil {
				t.Fatal("Initial commentary stage missing")
			}
			state, err := h.s.loadApp(h.p)
			if err != nil {
				t.Fatal(err)
			}
			j := state.Jobs[h.jobID]
			status, terminal := "completed", true
			wantState, wantCode, outputs := "completed", "developer_terminal_report_missing", 0
			items := []map[string]any{commentaryItem("last-stage", "[[bridge:transferring]]")}
			if name == "verified_report" || name == "bad_output" {
				data := []byte("verified commentary result")
				f, err := files.Describe(randomToken(), "verified.bin", data)
				if err != nil {
					t.Fatal(err)
				}
				h.native.outputs[f.ID] = data
				if name == "bad_output" {
					h.native.outputs[f.ID] = []byte("wrong result")
					wantCode = "output_bytes_unverified"
				} else {
					wantCode, outputs = "native_turn_completed", 1
				}
				items = append(items, commentaryItem("final-report", string(mustJSON(appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "ready", Files: []protocol.File{f}}))))
			}
			switch name {
			case "uncommitted_terminal":
				terminal, wantState, wantCode = false, "submitted", "native_terminal_unconfirmed"
			case "native_failed", "native_interrupted":
				status = strings.TrimPrefix(name, "native_")
				wantState, wantCode = status, "native_turn_"+status
			case "cancelled":
				j.CancelRequested, j.CancelReason = time.Now().UnixMilli(), "user_request"
				status, terminal, wantState, wantCode = "inProgress", false, "submitted", "native_acknowledged"
			case "unknown":
				j.State, j.Code = "unknown", "dispatch_outcome_unconfirmed"
				status, terminal, wantState, wantCode = "inProgress", false, "unknown", "dispatch_outcome_unconfirmed"
			}
			if err := h.s.saveApp(state); err != nil {
				t.Fatal(err)
			}
			h.observe(t, status, terminal, items...)
			if progress, _ := h.public(t); progress != nil {
				t.Fatal("Progress survived terminal/unknown/cancelled authority", progress)
			}
			state, err = h.s.loadApp(h.p)
			if err != nil {
				t.Fatal(err)
			}
			j = state.Jobs[h.jobID]
			if j.State != wantState || j.Code != wantCode || len(j.Outputs) != outputs || h.native.calls != 0 {
				t.Fatal("Commentary bypassed the exact native/report/batch/cancellation outcome", j)
			}
		})
	}
}

func TestCommentaryObservationIsIdempotentDurableAndExpiresWithOriginalJob(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		h := newCommentaryHarness(t)
		state, err := h.s.loadApp(h.p)
		if err != nil {
			t.Fatal(err)
		}
		created := state.Jobs[h.jobID].Created
		id := "committed-stage-" + randomToken()
		item := commentaryItem(id, "[[bridge:processing]]")
		h.observe(t, "inProgress", false, item)
		first, _ := h.public(t)
		if first == nil {
			t.Fatal("Initial stage missing")
		}
		time.Sleep(time.Minute)
		h.observe(t, "inProgress", false, item, commentaryItem("ordinary-after-marker", "processing"))
		if again, _ := h.public(t); again == nil || *again != *first {
			t.Fatal("Repeated observation renewed the same native item's timestamp", again, first)
		}
		time.Sleep(time.Minute)
		h.observe(t, "inProgress", false, commentaryItem("ordinary-only-later", "I will be processing the next input."))
		if again, _ := h.public(t); again == nil || *again != *first || bytes.Contains(h.saved(t), []byte("ordinary-only-later")) {
			t.Fatal("An ordinary-only observation erased or refreshed the prior committed marker", again, first)
		}
		time.Sleep(time.Minute)
		latestID := "next-stage-" + randomToken()
		h.observe(t, "inProgress", false, item, commentaryItem(latestID, "[[bridge:packaging]]"))
		latest, _ := h.public(t)
		if latest == nil || latest.Stage != "packaging" || latest.ObservedAt <= first.ObservedAt || bytes.Contains(h.saved(t), []byte(id)) {
			t.Fatal("A new committed item did not replace the old observation", latest, first)
		}
		first, id = latest, latestID
		cfg, native, authenticate := h.s.cfg, h.native, h.s.authenticate
		h.s.Close()
		reopened, err := New(cfg, native, authenticate, nil)
		if err != nil {
			t.Fatal(err)
		}
		defer reopened.Close()
		h.s, h.p = reopened, reopened.getPeer("nAlice")
		if again, _ := h.public(t); again == nil || *again != *first {
			t.Fatal("Reopen lost or refreshed the original progress observation", again, first)
		}
		state, err = h.s.loadApp(h.p)
		if err != nil || state.Jobs[h.jobID].Created != created {
			t.Fatal("Observing progress changed the receiver's job deadline", err)
		}
		deadline := time.UnixMilli(created).Add(protocol.Retention)
		time.Sleep(time.Until(deadline))
		r := httptest.NewRequest("GET", "/v2/jobs/"+h.jobID, nil)
		r.RemoteAddr = h.p.identity
		w := httptest.NewRecorder()
		h.s.ServeHTTP(w, r)
		state, err = h.s.loadApp(h.p)
		if w.Code != 410 || err != nil || state.Jobs[h.jobID] != nil || bytes.Contains(h.saved(t), []byte(id)) || h.native.calls != 0 {
			t.Fatal("Progress renewed the original deadline or survived job expiry", w.Code, err)
		}
	})
}

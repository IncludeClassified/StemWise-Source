package backend

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"phonepcbridge/protocol"
)

type catalogFixture struct {
	*nativeFixture
	err error
	raw json.RawMessage
}

func (n *catalogFixture) Models(ctx context.Context, cursor string) (json.RawMessage, error) {
	if n.err != nil {
		return nil, n.err
	}
	if n.raw != nil {
		return n.raw, nil
	}
	return n.nativeFixture.Models(ctx, cursor)
}

func TestCatalogOutagePreservesQueuedJobAndModelSettings(t *testing.T) {
	s, base, call := fixtureAPI(t)
	n := &catalogFixture{nativeFixture: base}
	s.native = n
	n.online = true
	registerFixture(t, call, "nAlice")
	settings := map[string]string{"model": "fixture-model", "effort": "high"}
	if w := call("PUT", "/v2/codex/settings", settings, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Catalog reconnect", Text: "original-" + randomToken(), Files: []protocol.File{}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	n.err = errors.New("PC dropped after health probe")
	if w := call("PUT", "/v2/codex/settings", map[string]string{"model": "other", "effort": "low"}, "nAlice"); w.Code != 503 {
		t.Fatal("Catalog outage mislabeled as invalid selection", w.Code, w.Body.String())
	}
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	j := state.Jobs[input.RequestID]
	if j.State != "queued" || j.Code != "model_catalog_unavailable" || j.TurnID != "" || n.calls != 0 || state.Model != "fixture-model" || state.Effort != "high" || j.Model != "fixture-model" || j.Effort != "high" {
		t.Fatal("Temporary outage rejected/reserved work or changed model settings", j, n.calls)
	}
	n.err = nil
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err = s.loadApp(s.getPeer("nAlice"))
	if err != nil || state.Jobs[input.RequestID].State != "completed" || n.calls != 1 || n.input.Text != input.Text || n.input.Model != "fixture-model" || n.input.Effort != "high" {
		t.Fatal("Reconnect did not deliver the original job exactly once", err)
	}
}

func TestCatalogMustEstablishUnsupportedSelectionBeforeRejecting(t *testing.T) {
	for _, test := range []struct {
		name, catalog, model, effort string
		rejected                     bool
	}{
		{name: "malformed", catalog: `not json`, model: "fixture-model"},
		{name: "missing_data", catalog: `{"nextCursor":null}`, model: "fixture-model"},
		{name: "null_data", catalog: `{"data":null,"nextCursor":null}`, model: "fixture-model"},
		{name: "missing_cursor", catalog: `{"data":[]}`, model: "fixture-model"},
		{name: "cursor_cycle", catalog: `{"data":[],"nextCursor":"same"}`, model: "fixture-model"},
		{name: "missing_efforts", catalog: `{"data":[{"model":"fixture-model"}],"nextCursor":null}`, model: "fixture-model", effort: "high"},
		{name: "invalid_effort", catalog: `{"data":[{"model":"fixture-model","supportedReasoningEfforts":[{}]}],"nextCursor":null}`, model: "fixture-model", effort: "high"},
		{name: "unknown_model", catalog: `{"data":[],"nextCursor":null}`, model: "absent", rejected: true},
		{name: "unsupported_effort", catalog: `{"data":[{"model":"fixture-model","supportedReasoningEfforts":[]}],"nextCursor":null}`, model: "fixture-model", effort: "high", rejected: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			n := &catalogFixture{nativeFixture: base, raw: json.RawMessage(test.catalog)}
			s.native = n
			n.online = true
			registerFixture(t, call, "nAlice")
			input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Catalog check", Text: "owner-" + randomToken(), Files: []protocol.File{}, Model: test.model, Effort: test.effort}
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			if err := s.workPass(); err != nil {
				t.Fatal(err)
			}
			state, err := s.loadApp(s.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			j := state.Jobs[input.RequestID]
			wanted, code := "queued", "model_catalog_unavailable"
			if test.rejected {
				wanted, code = "rejected", "model_or_effort_unavailable"
			}
			if j.State != wanted || j.Code != code || j.TurnID != "" || n.calls != 0 {
				t.Fatal("Unproven catalog settled or dispatched work", j, n.calls)
			}
		})
	}
}

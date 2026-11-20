package backend

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
)

type catalogProjectionNative struct {
	*nativeFixture
	models      json.RawMessage
	modelCursor string
}

func (n *catalogProjectionNative) Models(_ context.Context, cursor string) (json.RawMessage, error) {
	n.modelCursor = cursor
	return n.models, nil
}

func TestCatalogProjectionExcludesNativePrivateFields(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	canary := "private_native_content_" + randomToken()
	n := &catalogProjectionNative{nativeFixture: base}
	s.native = n
	n.models = mustJSON(map[string]any{"data": []any{map[string]any{
		"id": "fixture-model", "model": "fixture-model", "displayName": "Public model", "description": "Public model description", "hidden": false, "isDefault": true,
		"defaultReasoningEffort": "high", "supportedReasoningEfforts": []any{map[string]any{"reasoningEffort": "high", "description": "Public effort description", "conversation": canary}},
		"inputModalities": []string{"text", "image"}, "supportsPersonality": false, "upgrade": "next-model",
		"conversation": canary, "availabilityNux": map[string]string{"message": canary}, "availableAccessPrograms": map[string]string{"account": canary}, "upgradeInfo": map[string]string{"private": canary},
	}}, "nextCursor": "next-model-page", "tools": []string{canary}})
	path := "/v2/codex/models?cursor=previous-model-page"
	w := call("GET", path, nil, "nAlice")
	if w.Code != 200 || strings.Contains(w.Body.String(), canary) {
		t.Fatal("Native private fields escaped the public catalog projection", path, w.Code)
	}
	var reply struct {
		Data struct {
			Rows       []map[string]json.RawMessage `json:"data"`
			NextCursor string                       `json:"nextCursor"`
		} `json:"data"`
	}
	if json.Unmarshal(w.Body.Bytes(), &reply) != nil || len(reply.Data.Rows) != 1 || reply.Data.NextCursor == "" {
		t.Fatal("Projected page metadata changed")
	}
	row := reply.Data.Rows[0]
	for _, field := range []string{"id", "model", "displayName", "description", "hidden", "isDefault", "defaultReasoningEffort", "supportedReasoningEfforts", "inputModalities", "supportsPersonality", "upgrade"} {
		if row[field] == nil {
			t.Fatal("Required public model metadata lost", field)
		}
	}
	if string(row["defaultReasoningEffort"]) != `"high"` || string(row["isDefault"]) != "true" {
		t.Fatal("Model defaults changed")
	}
	if n.modelCursor != "previous-model-page" || base.calls != 0 {
		t.Fatal("Catalog projection changed routing or executed native work")
	}
}

func TestMalformedCatalogCannotReachAppsOrAuthorizeSettings(t *testing.T) {
	for _, row := range []string{
		`{"model":"fixture-model","model":"fixture-model","supportedReasoningEfforts":[{"reasoningEffort":"high"}]}`,
		`{"Model":"fixture-model","supportedReasoningEfforts":[{"reasoningEffort":"high"}]}`,
		`{"model":"fixture-model","supportedReasoningEfforts":[{"reasoningEffort":"high","reasoningEffort":"high"}]}`,
		`{"model":"fixture-model","supportedReasoningEfforts":[{"ReasoningEffort":"high"}]}`,
		`{"model":"fixture-model","displayName":{"conversation":"private"},"supportedReasoningEfforts":[{"reasoningEffort":"high"}]}`,
		`{"model":"fixture-model","supportedReasoningEfforts":[{"reasoningEffort":"high"},{"reasoningEffort":"high"}]}`,
		`{"model":"fixture-model","defaultReasoningEffort":"low","supportedReasoningEfforts":[{"reasoningEffort":"high"}]}`,
		`{"model":"fixture-model","inputModalities":null,"supportedReasoningEfforts":[{"reasoningEffort":"high"}]}`,
	} {
		s, base, call := fixtureAPI(t)
		registerFixture(t, call, "nAlice")
		s.native = &catalogProjectionNative{nativeFixture: base, models: json.RawMessage(`{"data":[` + row + `],"nextCursor":null}`)}
		for _, request := range []struct {
			method, path string
			body         any
		}{
			{"GET", "/v2/codex/models", nil},
			{"PUT", "/v2/codex/settings", map[string]string{"model": "fixture-model", "effort": "high"}},
		} {
			w := call(request.method, request.path, request.body, "nAlice")
			if w.Code != 503 || !strings.Contains(w.Body.String(), `"code":"model_catalog_unavailable"`) {
				t.Fatal("Malformed catalog reached a public result or authorized settings", w.Code)
			}
		}
		state, err := s.loadApp(s.getPeer("nAlice"))
		if err != nil || state.Model != "" || state.Effort != "" || base.calls != 0 {
			t.Fatal("Unconfirmed catalog changed original settings", err)
		}
	}
}

func TestCatalogPageBoundsAndExactFields(t *testing.T) {
	valid := `{"model":"fixture-model","supportedReasoningEfforts":[]}`
	limit, route, code := 100, "/v2/codex/models", "model_catalog_unavailable"
	for _, page := range []string{
		`{"nextCursor":null}`, `{"data":null,"nextCursor":null}`, `{"Data":[],"nextCursor":null}`, `{"data":[]}`,
		`{"data":[],"data":[],"nextCursor":null}`, `{"data":[],"nextCursor":{}}`,
		`{"data":[],"nextCursor":"` + strings.Repeat("x", 4097) + `"}`,
		`{"data":[],"nextCursor":"current"}`,
		`{"data":[],"nextCursor":null,"ignored":"\ud800"}`,
		"{\"data\":[],\"nextCursor\":null,\"ignored\":\"\xff\"}",
		`{"data":[` + valid + `,` + valid + `],"nextCursor":null}`,
		`{"data":[` + strings.TrimSuffix(strings.Repeat(valid+",", limit+1), ",") + `],"nextCursor":null}`,
	} {
		s, base, call := fixtureAPI(t)
		registerFixture(t, call, "nAlice")
		n := &catalogProjectionNative{nativeFixture: base, models: json.RawMessage(page)}
		s.native = n
		w := call("GET", route+"?cursor=current", nil, "nAlice")
		if w.Code != 503 || !strings.Contains(w.Body.String(), `"code":"`+code+`"`) || base.calls != 0 {
			t.Fatal("Unconfirmed catalog page was presented as usable", w.Code)
		}
	}
}

func TestOlderCatalogKeepsUnknownEffortsAndDocumentedModalityFallback(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	s.native = &catalogProjectionNative{nativeFixture: base, models: json.RawMessage(`{"data":[{"model":"fixture-model"}],"nextCursor":null}`)}
	w := call("GET", "/v2/codex/models", nil, "nAlice")
	var reply struct {
		Data struct {
			Rows []struct {
				ID, Model  string
				Modalities []string        `json:"inputModalities"`
				Efforts    json.RawMessage `json:"supportedReasoningEfforts"`
			} `json:"data"`
		} `json:"data"`
	}
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &reply) != nil || len(reply.Data.Rows) != 1 {
		t.Fatal("Older catalog unavailable", w.Code)
	}
	row := reply.Data.Rows[0]
	if row.ID != "fixture-model" || row.Model != "fixture-model" || strings.Join(row.Modalities, ",") != "text,image" || string(row.Efforts) != "null" {
		t.Fatal("Unknown capabilities became a fabricated supported selection")
	}
	if w := call("PUT", "/v2/codex/settings", map[string]string{"model": "fixture-model", "effort": "high"}, "nAlice"); w.Code != 503 {
		t.Fatal("Unknown effort support authorized settings", w.Code)
	}
}

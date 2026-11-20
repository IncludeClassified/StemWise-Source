package backend

import (
	"context"
	"encoding/json"
	"phonepcbridge/protocol"
	"strings"
	"testing"
	"time"
	"unicode/utf8"
)

func TestCommittedNativeFailureProducesIndependentDiagnostic(t *testing.T) {
	j := &appJob{ID: randomToken(), TurnID: "owned-turn", ThreadID: "owned-thread", State: "submitted", Code: "native_acknowledged"}
	turn := mustJSON(map[string]any{"id": j.TurnID, "status": "failed", "bridgeTerminalCommitted": true, "error": map[string]any{"message": "HTTP provider connection failed", "codexErrorInfo": map[string]any{"httpConnectionFailed": map[string]any{"httpStatusCode": 503}}, "additionalDetails": "upstream reset"}, "items": []any{}})
	status, entries := nativeJobLog(j, turn)
	if status != "failed" || len(entries) != 1 || entries[0].Source != "native" {
		t.Fatal("Native failure has no usable independent log", len(entries))
	}
	if entries[0].Error.Code != "native_turn_failed" || entries[0].Error.Summary != "HTTP provider connection failed" || entries[0].Error.Context["turn_id"] != j.TurnID {
		t.Fatal("Native failure details lost")
	}
	if j.State != "submitted" || j.Code != "native_acknowledged" {
		t.Fatal("Diagnostic parsing changed job status")
	}
}

func TestNativeDiagnosticRejectsUncommittedOrDifferentTurnAndRedactsSecrets(t *testing.T) {
	j := &appJob{ID: randomToken(), TurnID: "owned-turn", ThreadID: "owned-thread"}
	for _, record := range []map[string]any{
		{"id": j.TurnID, "status": "failed", "items": []any{}},
		{"id": "other-turn", "status": "failed", "bridgeTerminalCommitted": true, "items": []any{}},
		{"id": j.TurnID, "status": "inProgress", "bridgeTerminalCommitted": true, "items": []any{}},
	} {
		if status, entries := nativeJobLog(j, mustJSON(record)); status != "" || len(entries) != 0 {
			t.Fatal("Unconfirmed native failure became fact")
		}
	}
	secret := "not-a-real-credential-" + randomToken()
	turn := mustJSON(map[string]any{"id": j.TurnID, "status": "failed", "bridgeTerminalCommitted": true, "error": map[string]any{"message": "Bearer " + secret + " sk-" + secret, "additionalDetails": `{"password":"private spaced value","access_token":"` + secret + `"}`}, "items": []any{}})
	_, entries := nativeJobLog(j, turn)
	encoded := string(mustJSON(entries))
	if len(entries) != 1 || strings.Contains(encoded, secret) || strings.Contains(encoded, "private spaced value") || !strings.Contains(encoded, "[redacted]") || !validDiagnostic(&entries[0].Error) {
		t.Fatal("Native diagnostic exposed a credential or invalid schema")
	}
	_, large := nativeJobLog(j, mustJSON(map[string]any{"id": j.TurnID, "status": "failed", "bridgeTerminalCommitted": true, "error": map[string]any{"message": strings.Repeat("Ω", 5000), "additionalDetails": strings.Repeat("字", 5000)}, "items": []any{}}))
	if len(large) != 1 || !validDiagnostic(&large[0].Error) || !utf8.ValidString(large[0].Error.Summary) || !strings.Contains(large[0].Error.Summary, "[truncated]") {
		t.Fatal("Large native error was not bounded safely")
	}
}

func TestNativeStructuredDiagnosticRedactsBeforeLogSerialization(t *testing.T) {
	j := &appJob{ID: randomToken(), TurnID: "owned-turn", ThreadID: "owned-thread"}
	info := json.RawMessage(`{"nested":[{"pass\u0077ord":"HEAD\"PRIVATE_TAIL"}],"status":503}`)
	additional, err := json.Marshal(`{"password":"HEAD\"PRIVATE_TAIL","code":"EACCES"}`)
	if err != nil {
		t.Fatal(err)
	}
	turn := mustJSON(map[string]any{"id": j.TurnID, "status": "failed", "bridgeTerminalCommitted": true, "error": map[string]any{"message": "Provider rejected request", "codexErrorInfo": info, "additionalDetails": string(additional)}})
	_, entries := nativeJobLog(j, turn)
	encoded := string(mustJSON(entries))
	if len(entries) != 1 || strings.Contains(encoded, "PRIVATE_TAIL") || strings.Contains(encoded, "HEAD") || !strings.Contains(encoded, "[redacted]") || !strings.Contains(encoded, "EACCES") || !validDiagnostic(&entries[0].Error) {
		t.Fatal("Serialized native diagnostic exposed structured credential content", encoded)
	}
	var redacted struct {
		Nested []map[string]string `json:"nested"`
		Status int                 `json:"status"`
	}
	if json.Unmarshal([]byte(entries[0].Error.Context["codex_error_info"]), &redacted) != nil || len(redacted.Nested) != 1 || redacted.Nested[0]["password"] != "[redacted]" || redacted.Status != 503 {
		t.Fatal("Structured native info was not sanitized before outer serialization", entries[0].Error.Context)
	}
}

type nativeFailureFixture struct {
	*nativeFixture
	turn json.RawMessage
}

func (n *nativeFailureFixture) History(context.Context, string, string, int) (json.RawMessage, error) {
	return json.Marshal(map[string]any{"data": []json.RawMessage{n.turn}, "nextCursor": nil})
}

func TestNativeAndProtocolLogsAreDurableIndependentAndDeduplicated(t *testing.T) {
	for _, status := range []string{"failed", "interrupted", "completed"} {
		t.Run(status, func(t *testing.T) {
			s, n, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			j := &appJob{ID: randomToken(), TurnID: "fixture-turn", ThreadID: "fixture-chat", State: "submitted", Code: "native_acknowledged", Created: time.Now().UnixMilli(), Outputs: []protocol.File{}}
			j.Input = appInput{RequestID: j.ID, CreatedAt: j.Created, Title: "native failure fixture", Text: "original-" + randomToken(), Files: []protocol.File{}}
			j.Fingerprint = appHash(j.Input)
			state.Jobs[j.ID] = j
			fixture := &nativeFailureFixture{nativeFixture: n, turn: mustJSON(map[string]any{"id": j.TurnID, "status": status, "bridgeTerminalCommitted": true, "error": map[string]any{"message": "owned provider reset"}, "items": []any{map[string]any{"type": "agentMessage", "text": `{"bridgeJobReport":"v2","code":"invalid conversational report"}`, "bridgeReportCommitted": true}}})}
			s.native = fixture
			for i := 0; i < 2; i++ {
				if err := s.refreshApp(p, state, j); err != nil {
					t.Fatal(err)
				}
			}
			expected := 2
			if status == "completed" {
				expected = 1
			}
			if j.State != status || j.Code != "invalid_developer_protocol" || len(j.Log) != expected || len(j.Outputs) != 0 {
				t.Fatal("Protocol parsing erased native facts or repeated diagnostics")
			}
			loaded, err := s.loadApp(p)
			if err != nil || len(loaded.Jobs[j.ID].Log) != expected {
				t.Fatal("Native/protocol log not durable", err)
			}
			w := call("GET", "/v2/jobs/"+j.ID+"/log", nil, "nAlice")
			if w.Code != 200 || !strings.Contains(w.Body.String(), `"source":"bridge"`) || strings.Contains(w.Body.String(), "invalid conversational report") {
				t.Fatal("Log lost protocol cause or exported conversation", w.Body.String())
			}
			if status != "completed" && !strings.Contains(w.Body.String(), `"source":"native"`) {
				t.Fatal("Native error attribution absent")
			}
			if w := call("GET", "/v2/jobs/"+j.ID, nil, "nAlice"); strings.Contains(w.Body.String(), "owned provider reset") {
				t.Fatal("Diagnostic leaked into status")
			}
		})
	}
}

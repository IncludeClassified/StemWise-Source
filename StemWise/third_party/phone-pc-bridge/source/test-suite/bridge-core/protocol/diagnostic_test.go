package protocol

import (
	"encoding/json"
	"strings"
	"testing"
	"unicode/utf8"
)

func TestDiagnosticRedactionDoesNotExposeEscapedOrTruncatedValues(t *testing.T) {
	secret := `HEAD\"PRIVATE_TAIL`
	complete, _ := json.Marshal(map[string]any{"password": secret, "code": "EACCES"})
	nested := `{"code":"EACCES","nested":[{"pass\u0077ord":"HEAD\"PRIVATE_TAIL"}],"other":"retained"}`
	encoded, _ := json.Marshal(string(complete))
	for name, value := range map[string]string{
		"complete_json":           string(complete),
		"escaped_key_nested_json": nested,
		"json_encoded_string":     string(encoded),
		"truncated_json":          `{"password":"HEAD\"PRIVATE_TAIL`,
		"truncated_encoded_json":  string(encoded[:len(encoded)-3]),
		"plain_quoted":            `provider password="HEAD\"PRIVATE_TAIL" code=EACCES`,
		"plain_truncated":         `provider password="HEAD\"PRIVATE_TAIL`,
		"single_quoted":           `provider password='HEAD\'PRIVATE_TAIL' code=EACCES`,
		"trailing_escape":         "provider password=\"HEAD_PRIVATE_TAIL\\",
		"escaped_slash_control":   `{"nested":[{"password":"HEAD\/back\\slash\nPRIVATE_TAIL"}]}`,
		"truncated_escaped_key":   `{"pass\u0077ord":"HEAD\"PRIVATE_TAIL`,
		"truncated_nested_array":  `[{"pass\u0077ord":"HEAD\"PRIVATE_TAIL`,
	} {
		t.Run(name, func(t *testing.T) {
			got := DiagnosticText(value, 1024)
			if strings.Contains(got, "PRIVATE_TAIL") || strings.Contains(got, "HEAD") || !strings.Contains(got, "[redacted]") {
				t.Fatal("Credential suffix escaped diagnostic redaction", got)
			}
			if strings.Contains(value, "EACCES") && json.Valid([]byte(value)) && !strings.Contains(got, "EACCES") {
				t.Fatal("Structured redaction lost unrelated failure code", got)
			}
		})
	}
}

func TestDiagnosticStructuredRedactionPreservesExactNumbers(t *testing.T) {
	value := `{"password":"synthetic-secret","sequence":9007199254740993,"large":1e1000}`
	got := DiagnosticText(value, 1024)
	if !json.Valid([]byte(got)) || strings.Contains(got, "synthetic-secret") || !strings.Contains(got, `"sequence":9007199254740993`) || !strings.Contains(got, `"large":1e1000`) {
		t.Fatal("Redaction changed exact nonsecret numeric metadata", got)
	}
}

func TestDiagnosticRedactionCoversPrefixedEncodedJSON(t *testing.T) {
	payload, err := json.Marshal(`{"password":"HEAD\"PRIVATE_TAIL","code":"EACCES"}`)
	if err != nil {
		t.Fatal(err)
	}
	for name, value := range map[string]string{"complete": string(payload), "truncated": string(payload[:len(payload)-3])} {
		t.Run(name, func(t *testing.T) {
			got := DiagnosticText("native failure: "+value, 1024)
			if strings.Contains(got, "HEAD") || strings.Contains(got, "PRIVATE_TAIL") || !strings.Contains(got, "[redacted]") || !strings.HasPrefix(got, "native failure: ") {
				t.Fatal("Prefixed encoded detail escaped redaction", got)
			}
			if name == "complete" && !strings.Contains(got, "EACCES") {
				t.Fatal("Embedded JSON redaction erased unrelated failure context", got)
			}
		})
	}
}

func TestDiagnosticRedactionPreservesHarmlessBytesAndBoundsOutput(t *testing.T) {
	for _, value := range []string{"EACCES: provider reset", "[EACCES] provider reset", "{ \"status\" : 503, \"items\" : [null, true, 0] }", `"{\"status\":503}"`, `native failure: "{\"status\":503}" code=EACCES`, `native failure: { "status" : 503 }`, `native failure: "provider reset" code=EACCES`} {
		if got := DiagnosticText(value, 4096); got != value {
			t.Fatal("Harmless diagnostic bytes changed", value, got)
		}
	}
	value := `{"password":"` + strings.Repeat("Ω", 5000) + `PRIVATE_TAIL"}`
	got := DiagnosticText(value, 64)
	if !utf8.ValidString(got) || len(got) > 64+len(" [truncated]") || strings.Contains(got, "PRIVATE_TAIL") || !strings.Contains(got, "[redacted]") {
		t.Fatal("Oversized malformed structured detail was not safely bounded", got)
	}
}

package protocol

import (
	"encoding/json"
	"io"
	"regexp"
	"strings"
	"unicode/utf8"
)

var diagnosticSecretKey = regexp.MustCompile(`(?i)^(?:api[_-]?key|(?:access|refresh)[_-]?token|token|password|secret|client[_-]?secret|authorization)$`)

var diagnosticSecrets = []*regexp.Regexp{
	regexp.MustCompile(`(?i)(bearer\s+)[^\s"'<>]+`),
	regexp.MustCompile(`\bsk-[A-Za-z0-9_-]+`),
	regexp.MustCompile(`(?is)((?:api[_-]?key|(?:access|refresh)[_-]?token|token|password|secret)["']?\s*[:=]\s*)(?:"(?:\\.|[^"\\])*(?:"|\\?$)|'(?:\\.|[^'\\])*(?:'|\\?$)|[^\s,;&<>]+)`),
}

// DiagnosticText bounds technical detail and redacts common credential forms.
func DiagnosticText(value string, limit int) string {
	if len(value) > 8192 {
		value = value[:8192]
		for !utf8.ValidString(value) {
			value = value[:len(value)-1]
		}
	}
	value, _ = redactDiagnostic(value, 0)
	if len(value) > limit {
		value = value[:limit]
		for !utf8.ValidString(value) {
			value = value[:len(value)-1]
		}
		value += " [truncated]"
	}
	return value
}

func redactDiagnostic(value string, depth int) (string, bool) {
	if depth > 32 {
		return "[redacted]", true
	}
	decoder := json.NewDecoder(strings.NewReader(value))
	decoder.UseNumber()
	var decoded any
	err := decoder.Decode(&decoded)
	if err == nil {
		var trailing any
		if decoder.Decode(&trailing) == io.EOF {
			if redactDiagnosticValue(&decoded, depth+1) {
				encoded, err := json.Marshal(decoded)
				if err != nil {
					return "[redacted]", true
				}
				return string(encoded), true
			}
			return value, false
		}
	}
	trimmed := strings.TrimSpace(value)
	if strings.HasPrefix(trimmed, "{") || strings.HasPrefix(trimmed, `"`) || diagnosticJSONArray(trimmed) {
		return "[redacted]", true // Malformed/truncated structured detail cannot authorize a raw fallback.
	}
	redacted := redactEmbeddedDiagnostic(value, depth)
	redacted = diagnosticSecrets[0].ReplaceAllString(redacted, "${1}[redacted]")
	redacted = diagnosticSecrets[1].ReplaceAllString(redacted, "[redacted]")
	redacted = diagnosticSecrets[2].ReplaceAllString(redacted, "${1}[redacted]")
	return redacted, redacted != value
}

func redactEmbeddedDiagnostic(value string, depth int) string {
	for offset := 0; offset < len(value); {
		next := strings.IndexAny(value[offset:], `"{[`)
		if next < 0 {
			break
		}
		start := offset + next
		if value[start] == '[' && !diagnosticJSONArray(value[start:]) {
			offset = start + 1
			continue
		}
		decoder := json.NewDecoder(strings.NewReader(value[start:]))
		decoder.UseNumber()
		var decoded any
		if decoder.Decode(&decoded) != nil {
			return value[:start] + "[redacted]"
		}
		end := start + int(decoder.InputOffset())
		if redactDiagnosticValue(&decoded, depth+1) {
			encoded, err := json.Marshal(decoded)
			if err != nil {
				return value[:start] + "[redacted]"
			}
			value = value[:start] + string(encoded) + value[end:]
			offset = start + len(encoded)
		} else {
			offset = end
		}
	}
	return value
}

func diagnosticJSONArray(value string) bool {
	if !strings.HasPrefix(value, "[") {
		return false
	}
	value = strings.TrimSpace(value[1:])
	return value == "" || strings.ContainsAny(value[:1], `[{"-0123456789]`) || strings.HasPrefix(value, "null") || strings.HasPrefix(value, "true") || strings.HasPrefix(value, "false")
}

func redactDiagnosticValue(value *any, depth int) bool {
	if depth > 32 {
		*value = "[redacted]"
		return true
	}
	changed := false
	switch decoded := (*value).(type) {
	case map[string]any:
		for key, child := range decoded {
			if diagnosticSecretKey.MatchString(key) {
				decoded[key], changed = "[redacted]", true
			} else if redactDiagnosticValue(&child, depth+1) {
				decoded[key], changed = child, true
			}
		}
	case []any:
		for index := range decoded {
			if redactDiagnosticValue(&decoded[index], depth+1) {
				changed = true
			}
		}
	case string:
		if redacted, ok := redactDiagnostic(decoded, depth+1); ok {
			*value, changed = redacted, true
		}
	}
	return changed
}

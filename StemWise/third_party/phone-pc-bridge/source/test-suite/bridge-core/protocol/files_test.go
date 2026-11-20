package protocol

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestFileJSONRequiresExplicitNonNullDescriptorFields(t *testing.T) {
	original := File{ID: strings.Repeat("a", 32), Name: "empty-😀.bin", Size: 0, SHA256: strings.Repeat("b", 64)}
	raw, err := json.Marshal(original)
	if err != nil {
		t.Fatal(err)
	}
	for _, decode := range []func([]byte, any) error{json.Unmarshal, DecodeStrict} {
		var valid File
		if err := decode(raw, &valid); err != nil || valid != original {
			t.Fatal("Explicit zero-byte descriptor changed", err)
		}
		for _, field := range []string{"id", "name", "size", "sha256"} {
			for _, missing := range []bool{false, true} {
				var fields map[string]json.RawMessage
				if err := json.Unmarshal(raw, &fields); err != nil {
					t.Fatal(err)
				}
				if missing {
					delete(fields, field)
				} else {
					fields[field] = json.RawMessage("null")
				}
				data, _ := json.Marshal(fields)
				value := original
				if err := decode(data, &value); err == nil || value != original {
					t.Fatal("Missing/null descriptor field was accepted or partially replaced its target", field, missing, err)
				}
			}
		}
	}
}

package protocol

import (
	"encoding/json"
	"testing"
)

func TestStrictJSONKeepsUnicodeAndRejectsAliases(t *testing.T) {
	for _, input := range []string{`{"text":"\ud800"}`, `{"text":"\udc00"}`, `{"text":"\ud800x"}`, `{"text":"\ud800\u0041"}`, `{"text":"first","text":"second"}`, `{"text":"valid","extra":true}`, `{"Text":"replacement"}`, `{"text":"original","TEXT":"replacement"}`} {
		var value struct {
			Text string `json:"text"`
		}
		if DecodeStrict([]byte(input), &value) == nil {
			t.Fatal("Invalid request accepted", input)
		}
	}
	for _, test := range []struct{ input, expected string }{{`{"text":"\ud83d\ude00"}`, "😀"}, {`{"text":"\\ud800"}`, `\ud800`}, {`{"text":"Ω\n\"unchanged\""}`, "Ω\n\"unchanged\""}} {
		var value struct {
			Text string `json:"text"`
		}
		if err := DecodeStrict([]byte(test.input), &value); err != nil || value.Text != test.expected {
			t.Fatal("Owner text changed", value.Text, err)
		}
	}
}

func TestStrictJSONChecksNestedSchemaAndPreservesMapKeys(t *testing.T) {
	type named struct {
		Text string `json:"text,omitempty"`
	}
	type request struct {
		Child  *named            `json:"child"`
		List   []named           `json:"list"`
		Lookup map[string]*named `json:"lookup"`
		Data   map[string]any    `json:"data"`
		Opaque json.RawMessage   `json:"opaque"`
	}
	for _, input := range []string{`{"child":{"Text":"changed"}}`, `{"list":[{"text":"original","TEXT":"changed"}]}`, `{"lookup":{"Owner":{"TEXT":"changed"}}}`, `{"child":{"text":"first","\u0074ext":"second"}}`, `{"opaque":{"same":1,"same":2}}`} {
		var value request
		if DecodeStrict([]byte(input), &value) == nil {
			t.Fatal("Nested alias or duplicate accepted", input)
		}
	}
	input := `{"child":{"text":"Ω"},"list":[{"text":"original"}],"lookup":{"Owner":{"text":"kept"}},"data":{"Text":"upper","text":"lower"},"opaque":{"Text":1,"text":2}}`
	var value request
	if err := DecodeStrict([]byte(input), &value); err != nil || value.Child.Text != "Ω" || value.List[0].Text != "original" || value.Lookup["Owner"].Text != "kept" || value.Data["Text"] != "upper" || value.Data["text"] != "lower" || string(value.Opaque) != `{"Text":1,"text":2}` {
		t.Fatal("Valid nested data or distinct map keys changed", err)
	}
}

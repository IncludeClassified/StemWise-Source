package backend

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"
	"time"

	"phonepcbridge/protocol"
)

func TestEscapedPromptBodiesPreserveAllowedText(t *testing.T) {
	s, native, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	for _, char := range []string{"&", "\x00", "\\", "\""} {
		t.Run(string([]byte(char)), func(t *testing.T) {
			prompt := appPrompt{Title: "Owned escaped prompt", Instructions: "keep\n&"}
			prompt.Text = strings.Repeat(char, (64<<10)-len(prompt.Instructions))
			body := mustJSON(prompt)
			if len(body) <= 128<<10 || len(body) > 512<<10 {
				t.Fatal("Fixture does not exercise encoded expansion", len(body))
			}
			promptID := randomToken()
			if w := call("PUT", "/v2/prompts/"+promptID, prompt, "nAlice"); w.Code != 200 {
				t.Fatal("Allowed escaped prompt rejected", w.Code)
			}
			input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: prompt.Title, Text: prompt.Text, Instructions: prompt.Instructions, Files: []protocol.File{}, Model: "fixture-model", Effort: "high"}
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
				t.Fatal("Allowed escaped job rejected", w.Code)
			}
			var read struct {
				Data appInput `json:"data"`
			}
			w := call("GET", "/v2/jobs/"+input.RequestID+"/inputs", nil, "nAlice")
			if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &read) != nil || !reflect.DeepEqual(read.Data, input) {
				t.Fatal("Original job input changed")
			}
			stored, err := s.loadApp(p)
			if err != nil || !reflect.DeepEqual(stored.Prompts[promptID], prompt) || !reflect.DeepEqual(stored.Jobs[input.RequestID].Input, input) {
				t.Fatal("Saved escaped bytes changed", err)
			}
			prompt.Text += char
			if w := call("PUT", "/v2/prompts/"+promptID, prompt, "nAlice"); w.Code != 400 {
				t.Fatal("Decoded text limit was relaxed")
			}
			input.Text += char
			input.RequestID = randomToken()
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 400 {
				t.Fatal("Oversized decoded job accepted")
			}
		})
	}
	if w := call("PUT", "/v2/prompts/"+randomToken(), strings.Repeat(" ", 512<<10)+`{"title":"owned","text":"x","instructions":""}`, "nAlice"); w.Code != 400 {
		t.Fatal("Encoded request bound not enforced")
	}
	if native.calls != 0 {
		t.Fatal("Request validation dispatched native work")
	}
}

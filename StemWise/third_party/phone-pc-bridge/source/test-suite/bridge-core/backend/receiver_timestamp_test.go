package backend

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func TestInvalidReceiverTimestampCannotExpireAcceptedInput(t *testing.T) {
	for _, variant := range []string{"missing", "null", "zero", "negative"} {
		t.Run(variant, func(t *testing.T) {
			s, _, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			content := []byte("accepted immutable input")
			f, _ := files.Describe(randomToken(), "input.bin", content)
			if _, err := s.inbox.Put("nAlice", f, bytes.NewReader(content)); err != nil {
				t.Fatal(err)
			}
			input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Receiver validation", Text: "original request", Files: []protocol.File{f}}
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			path := filepath.Join(s.cfg.DataDir, "apps", appStateName("nAlice"))
			original, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			var state map[string]any
			if err := json.Unmarshal(original, &state); err != nil {
				t.Fatal(err)
			}
			job := state["jobs"].(map[string]any)[input.RequestID].(map[string]any)
			switch variant {
			case "missing":
				delete(job, "created")
			case "null":
				job["created"] = nil
			case "zero":
				job["created"] = 0
			case "negative":
				job["created"] = -1
			}
			broken, _ := json.Marshal(state)
			if err := os.WriteFile(path, broken, 0600); err != nil {
				t.Fatal(err)
			}
			if w := call("GET", "/v2/jobs/"+input.RequestID, nil, "nAlice"); w.Code != 503 {
				t.Fatal("Invalid receiver time became expiry/success", w.Code, w.Body.String())
			}
			if _, err := s.retentionPass(time.Now()); err == nil {
				t.Fatal("Retention accepted invalid receiver-owned time")
			}
			actual, err := os.ReadFile(path)
			if err != nil || !bytes.Equal(actual, broken) {
				t.Fatal("Failed validation rewrote accepted ownership", err)
			}
			actualFile, actualBytes, err := s.inbox.Read("nAlice", f.ID)
			if err != nil || actualFile != f || !bytes.Equal(actualBytes, content) {
				t.Fatal("Invalid receiver timestamp deleted valid accepted input", err)
			}
		})
	}
}

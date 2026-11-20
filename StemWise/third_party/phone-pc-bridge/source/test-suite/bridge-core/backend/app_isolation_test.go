package backend

import (
	"bytes"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"phonepcbridge/protocol"
)

func damageOwnedApp(t *testing.T, s *Service, peer, kind string) ([]byte, string) {
	t.Helper()
	state, err := s.loadApp(s.getPeer(peer))
	if err != nil {
		t.Fatal(err)
	}
	var damaged []byte
	switch kind {
	case "malformed":
		damaged = []byte("owned_non_json_record")
	case "version":
		state.Version = 0
		damaged = mustJSON(state)
	case "job":
		state.Jobs[randomToken()] = &appJob{}
		damaged = mustJSON(state)
	}
	path := filepath.Join(s.cfg.DataDir, "apps", appStateName(peer))
	if err := os.WriteFile(path, damaged, 0600); err != nil {
		t.Fatal(err)
	}
	return damaged, path
}

func TestDamagedAppCannotStopHealthyWorkOrRetention(t *testing.T) {
	for _, kind := range []string{"malformed", "version", "job"} {
		t.Run(kind, func(t *testing.T) {
			s, n, call := fixtureAPI(t)
			badPeer := ""
			for i := 0; i < 256; i++ {
				candidate := "nOwnedBad" + strconv.Itoa(i)
				if appStateName(candidate) < appStateName("nAlice") {
					badPeer = candidate
					break
				}
			}
			if badPeer == "" {
				t.Fatal("Could not place damaged record before healthy record")
			}
			registerFixture(t, call, badPeer)
			registerFixture(t, call, "nAlice")
			input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Isolated healthy work", Text: "actual-" + randomToken(), Files: []protocol.File{}}
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			damaged, path := damageOwnedApp(t, s, badPeer, kind)
			n.online = true
			if err := s.workPass(); err == nil {
				t.Fatal("Damaged app was silently accepted")
			}
			state, err := s.loadApp(s.getPeer("nAlice"))
			if err != nil || state.Jobs[input.RequestID].State != "completed" || len(state.Jobs[input.RequestID].Outputs) != 1 || n.calls != 1 {
				t.Fatal("Damaged sibling prevented healthy job processing", err)
			}
			if w := call("GET", "/v2/app", nil, badPeer); w.Code != 503 {
				t.Fatal("Damaged app did not fail closed", w.Body.String())
			}
			output := state.Jobs[input.RequestID].Outputs[0]
			deliveredRetentionFixture(state, state.Jobs[input.RequestID], time.Now())
			prompt := randomToken()
			state.Prompts[prompt] = appPrompt{Title: "Preserve", Text: "saved prompt"}
			if err := s.saveApp(state); err != nil {
				t.Fatal(err)
			}
			if delay, err := s.retentionPass(time.Now()); err == nil || delay != time.Minute {
				t.Fatal("Damaged retention scan lost its explicit failure/retry", delay, err)
			}
			state, err = s.loadApp(s.getPeer("nAlice"))
			if err != nil || len(state.Jobs) != 0 || state.Prompts[prompt].Text != "saved prompt" {
				t.Fatal("Damaged sibling protected expired jobs or removed prompts", err)
			}
			if _, err := s.outbox.Info("nAlice", output.ID); !os.IsNotExist(err) {
				t.Fatal("Healthy expired output retained", err)
			}
			if actual, err := os.ReadFile(path); err != nil || !bytes.Equal(actual, damaged) {
				t.Fatal("Damaged record was overwritten or removed", err)
			}
		})
	}
}

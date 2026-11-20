package backend

import (
	"bytes"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func TestPendingExpiredUnlinkCannotBlockHealthyFileRoutes(t *testing.T) {
	s, native, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	bad, _ := files.Describe(randomToken(), "expired.bin", []byte("expired owned bytes"))
	if _, err := s.outbox.Put(p.identity, bad, strings.NewReader("expired owned bytes")); err != nil {
		t.Fatal(err)
	}
	unknown := filepath.Join(s.cfg.DataDir, "outgoing", p.identity, bad.ID, "unknown-member")
	if err := os.WriteFile(unknown, []byte("preserve unexpected entry"), 0600); err != nil {
		t.Fatal(err)
	}
	id := randomToken()
	state.Cleanup = map[string]appCleanup{}
	state.Cleanup[id] = appCleanup{Created: time.Now().Add(-appRetention).UnixMilli(), Name: "bridge_job_" + id, Files: []string{bad.ID}}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	if w := call("GET", "/v1/files/health?nonce="+randomToken(), nil, p.identity); w.Code != 200 || !strings.Contains(w.Body.String(), `"state":"ready"`) {
		t.Fatal("Independent file health failed because of old unlink", w.Code, w.Body.String())
	}
	data := []byte("independent current input")
	f, _ := files.Describe(randomToken(), "healthy.bin", data)
	q := url.Values{"nonce": {randomToken()}, "id": {f.ID}, "name": {f.Name}, "size": {strconv.FormatInt(f.Size, 10)}, "sha256": {f.SHA256}}
	if w := call("POST", "/v1/files/put?"+q.Encode(), string(data), p.identity); w.Code != 200 {
		t.Fatal("Old unlink blocked a new input", w.Code, w.Body.String())
	}
	if actual, content, err := s.inbox.Read(p.identity, f.ID); err != nil || actual != f || !bytes.Equal(content, data) {
		t.Fatal("Healthy input bytes differ", err)
	}
	if w := call("GET", "/v1/files/"+f.ID+"/receipt?nonce="+randomToken(), nil, p.identity); w.Code != 200 || !strings.Contains(w.Body.String(), f.SHA256) {
		t.Fatal("Old unlink blocked an independent receipt", w.Code, w.Body.String())
	}
	native.online = true
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Healthy output", Text: "independent output bytes", Files: []protocol.File{}}
	if w := call("POST", "/v2/jobs", input, p.identity); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	for pass := 0; pass < 3; pass++ {
		_ = s.workPass() // The independently reported unlink warning remains a real failure.
	}
	state, err = s.loadApp(p)
	if err != nil || state.Jobs[input.RequestID].State != "completed" || len(state.Jobs[input.RequestID].Outputs) != 1 {
		t.Fatal("Independent job did not finish", err)
	}
	output := state.Jobs[input.RequestID].Outputs[0]
	if w := call("GET", "/v1/outbox/"+output.ID+"/content?nonce="+randomToken(), nil, p.identity); w.Code != 200 || w.Body.String() != input.Text {
		t.Fatal("Old unlink blocked a verified output", w.Code, w.Body.String())
	}
	if len(state.Cleanup) == 0 {
		t.Fatal("Cleanup failure disappeared instead of remaining pending")
	}
	if actual, err := os.ReadFile(unknown); err != nil || string(actual) != "preserve unexpected entry" {
		t.Fatal("Unrelated entry was erased to make file routes pass", err)
	}
}

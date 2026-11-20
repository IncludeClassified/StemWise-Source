package backend

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"phonepcbridge/protocol"
)

func TestRestartRemovesAbandonedAppSnapshotAndPreservesCommittedRecord(t *testing.T) {
	s, native, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Interrupted snapshot", Text: "private job context must expire", Files: []protocol.File{}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	final := filepath.Join(s.cfg.DataDir, "apps", appStateName("nAlice"))
	snapshot, err := os.ReadFile(final)
	if err != nil || !bytes.Contains(snapshot, []byte(input.Text)) {
		t.Fatal("Missing actual persisted private job", err)
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	state.Jobs = map[string]*appJob{}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	committed, err := os.ReadFile(final)
	if err != nil {
		t.Fatal(err)
	}
	s.Close()
	stale := filepath.Join(s.cfg.DataDir, "apps", "."+strings.Repeat("a", 32)+".tmp")
	unknown := filepath.Join(s.cfg.DataDir, "apps", "owner-notes.tmp")
	if err := os.WriteFile(stale, snapshot, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(unknown, []byte("preserve unrelated entry"), 0600); err != nil {
		t.Fatal(err)
	}
	reopened, err := New(s.cfg, native, s.authenticate, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	if err := reopened.Run(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(stale); !os.IsNotExist(err) {
		t.Fatal("Restart retained an abandoned private snapshot", err)
	}
	if _, err := reopened.retentionPass(time.Now().Add(protocol.Retention)); err != nil {
		t.Fatal(err)
	}
	actual, err := os.ReadFile(final)
	if err != nil || !bytes.Equal(actual, committed) {
		t.Fatal("Recovery changed the committed app record", err)
	}
	if actual, err := os.ReadFile(unknown); err != nil || string(actual) != "preserve unrelated entry" {
		t.Fatal("Recovery removed an unrelated entry", err)
	}
	if err := reopened.Run(); err == nil {
		t.Fatal("A second runner started competing background work")
	}
}

func TestAppSnapshotRecoveryKeepsActiveWritesAndRejectsUnexpectedShapes(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	name := "." + strings.Repeat("b", 32) + ".tmp"
	path := filepath.Join(s.cfg.DataDir, "apps", name)
	if err := os.WriteFile(path, []byte("active writer bytes"), 0600); err != nil {
		t.Fatal(err)
	}
	s.mu.Lock()
	s.appWrites = map[string]bool{name: true}
	s.mu.Unlock()
	if err := s.recoverAppWrites(); err != nil {
		t.Fatal(err)
	}
	if actual, err := os.ReadFile(path); err != nil || string(actual) != "active writer bytes" {
		t.Fatal("Recovery crossed an active writer lease", err)
	}
	s.mu.Lock()
	delete(s.appWrites, name)
	s.mu.Unlock()
	if err := s.recoverAppWrites(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("Inactive abandoned writer did not recover", err)
	}
	if err := os.Mkdir(path, 0700); err != nil {
		t.Fatal(err)
	}
	if err := s.Run(); err == nil {
		t.Fatal("Malformed temporary storage became a healthy startup")
	}
	if info, err := os.Stat(path); err != nil || !info.IsDir() {
		t.Fatal("Recovery destroyed an unexpected object", err)
	}
}

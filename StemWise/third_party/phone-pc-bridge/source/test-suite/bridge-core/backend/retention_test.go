package backend

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

// Cleanup fixtures represent already delivered, fully published completed work.
// Unclaimed or partially published jobs use explicit removal or retention checks.
func deliveredRetentionFixture(state *appState, job *appJob, now time.Time) {
	job.State, job.ChatState = "completed", "deleted"
	job.Delivered = now.Add(-appRetention - time.Second).UnixMilli()
	job.Created = job.Delivered - time.Hour.Milliseconds()
	if job.Report == nil {
		job.Report = &appReport{Protocol: "v2", AppID: state.ID, JobID: job.ID, Revision: 1, Status: "completed", Code: "ready", Files: job.Outputs}
	}
}

func TestRetentionIsolatesUnexpectedStageWithoutProtectingJobsOrOtherStore(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	now := time.Now()
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	id := randomToken()
	f, _ := files.Describe(randomToken(), "owned.bin", []byte("expired"))
	if _, err := s.outbox.Put(state.Peer, f, bytes.NewReader([]byte("expired"))); err != nil {
		t.Fatal(err)
	}
	old := now.Add(-appRetention - time.Second)
	if err := os.Chtimes(filepath.Join(s.cfg.DataDir, "outgoing", state.Peer, f.ID), old, old); err != nil {
		t.Fatal(err)
	}
	input := appInput{RequestID: id, CreatedAt: old.UnixMilli(), Title: "Expiry fixture", Text: "owned context", Files: []protocol.File{}}
	state.Jobs[id] = &appJob{ID: id, Created: old.UnixMilli(), State: "completed", ThreadID: "fixture-chat", Input: input, Fingerprint: appHash(input), Outputs: []protocol.File{f}}
	deliveredRetentionFixture(state, state.Jobs[id], now)
	state.Prompts[randomToken()] = appPrompt{Title: "Keep", Text: "saved prompt"}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	stage := filepath.Join(s.cfg.DataDir, "incoming", state.Peer, ".incoming-"+strings.Repeat("0", 32))
	if err := os.MkdirAll(stage, 0700); err != nil {
		t.Fatal(err)
	}
	unknown := filepath.Join(stage, "unexpected.txt")
	if err := os.WriteFile(unknown, []byte("preserve"), 0600); err != nil {
		t.Fatal(err)
	}
	if delay, err := s.retentionPass(now); err != nil || delay <= 0 {
		t.Fatal("An isolated unexpected stage blocked healthy retention", delay, err)
	}
	if diagnostics, err := s.inbox.Diagnostics(""); err != nil || diagnostics.DiagnosticCount == 0 {
		t.Fatal("Preserved damaged stage lost its object diagnostic", diagnostics, err)
	}
	state, err = s.loadApp(s.getPeer("nAlice"))
	if err != nil || len(state.Jobs) != 0 || len(state.Prompts) != 1 {
		t.Fatal("Corrupt input stage protected expired jobs or removed saved prompts", err)
	}
	if _, err := s.outbox.Info(state.Peer, f.ID); !os.IsNotExist(err) {
		t.Fatal("Corrupt inbox protected expired outbox bytes", err)
	}
	if actual, err := os.ReadFile(unknown); err != nil || string(actual) != "preserve" {
		t.Fatal("Unknown input stage removed", err)
	}
}

func TestRetentionFileFailureDoesNotProtectOtherJobsOrPeers(t *testing.T) {
	s, _, call := fixtureAPI(t)
	now := time.Now()
	for _, peer := range []string{"nAlice", "nBob"} {
		registerFixture(t, call, peer)
		state, err := s.loadApp(s.getPeer(peer))
		if err != nil {
			t.Fatal(err)
		}
		f, _ := files.Describe(randomToken(), "owned.bin", []byte(peer))
		if _, err := s.outbox.Put(peer, f, strings.NewReader(peer)); err != nil {
			t.Fatal(err)
		}
		if peer == "nAlice" {
			if err := os.WriteFile(filepath.Join(s.cfg.DataDir, "outgoing", peer, f.ID, "content"), []byte("corrupt"), 0600); err != nil {
				t.Fatal(err)
			}
		}
		for _, outputs := range [][]protocol.File{{f}, {}} {
			id := randomToken()
			created := now.Add(-appRetention - time.Second).UnixMilli()
			input := appInput{RequestID: id, CreatedAt: created, Title: "Expiry fixture", Text: "owned context", Files: []protocol.File{}}
			state.Jobs[id] = &appJob{ID: id, Created: created, State: "completed", ThreadID: "fixture-chat", Input: input, Fingerprint: appHash(input), Outputs: outputs}
			deliveredRetentionFixture(state, state.Jobs[id], now)
		}
		liveID := randomToken()
		liveInput := appInput{RequestID: liveID, CreatedAt: now.UnixMilli(), Title: "Live fixture", Text: "preserve", Files: []protocol.File{}}
		state.Jobs[liveID] = &appJob{ID: liveID, Created: now.UnixMilli(), State: "queued", ThreadID: "fixture-chat", Input: liveInput, Fingerprint: appHash(liveInput), Outputs: []protocol.File{}}
		if err := s.saveApp(state); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := s.retentionPass(now); err == nil {
		t.Fatal("Corrupt file cleanup was reported as successful")
	}
	for _, peer := range []string{"nAlice", "nBob"} {
		state, err := s.loadApp(s.getPeer(peer))
		if err != nil || len(state.Jobs) != 1 {
			t.Fatal("One corrupt transfer protected expired job records", peer, err)
		}
		for _, j := range state.Jobs {
			if j.Input.Title != "Live fixture" || j.Input.Text != "preserve" {
				t.Fatal("Recent job was removed or changed")
			}
		}
		if peer == "nBob" {
			page, err := s.outbox.List(peer, "", 100)
			if err != nil || len(page.Files) != 0 {
				t.Fatal("One peer protected another peer's expired files", err)
			}
		}
	}
}

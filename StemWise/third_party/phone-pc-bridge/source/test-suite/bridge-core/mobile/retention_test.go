package mobile

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func mobileFileFixture(t *testing.T, state string) (*Client, *FileClient) {
	t.Helper()
	c, err := NewClient(state, "owned-file-retention", "")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	f, err := c.LocalFiles("pc.tailtest.ts.net", "nPc")
	if err != nil {
		t.Fatal(err)
	}
	return c, f
}

func managedMobileFile(t *testing.T, store *files.Store) protocol.File {
	t.Helper()
	id, err := files.NewID()
	if err != nil {
		t.Fatal(err)
	}
	f, _ := files.Describe(id, "owned.bin", []byte(id))
	if _, err := store.Put("nPc", f, strings.NewReader(id)); err != nil {
		t.Fatal(err)
	}
	return f
}

func TestMobileFileStartupPrunesExpiredAndAbandonedBytes(t *testing.T) {
	root := t.TempDir()
	state := filepath.Join(root, "node")
	c, f := mobileFileFixture(t, state)
	oldIn, oldOut := managedMobileFile(t, f.inbox), managedMobileFile(t, f.outbox)
	liveIn, liveOut := managedMobileFile(t, f.inbox), managedMobileFile(t, f.outbox)
	imported := filepath.Join(root, "app-imported.bin")
	if err := f.CopyReceived("fixture-copyreceived", oldIn.ID, imported); err != nil {
		t.Fatal(err)
	}
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-3*24*time.Hour - time.Second)
	paths := []string{filepath.Join(state, "bridge-incoming", "nPc", oldIn.ID), filepath.Join(state, "bridge-outgoing", "nPc", oldOut.ID)}
	for _, path := range paths {
		if err := os.Chtimes(path, old, old); err != nil {
			t.Fatal(err)
		}
	}
	stageID, _ := files.NewID()
	stage := filepath.Join(state, "bridge-incoming", "nPc", ".incoming-"+stageID)
	if err := os.Mkdir(stage, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(stage, "content"), []byte("actual abandoned partial bytes"), 0600); err != nil {
		t.Fatal(err)
	}
	c, f = mobileFileFixture(t, state)
	for _, path := range append(paths, stage) {
		if _, err := os.Lstat(path); !os.IsNotExist(err) {
			t.Fatal("SDK startup retained expired or abandoned managed bytes", path, err)
		}
	}
	for store, file := range map[*files.Store]protocol.File{f.inbox: liveIn, f.outbox: liveOut} {
		if actual, data, err := store.Read("nPc", file.ID); err != nil || actual != file || !bytes.Equal(data, []byte(file.ID)) {
			t.Fatal("SDK cleanup changed recent managed bytes", err)
		}
	}
	if data, err := os.ReadFile(imported); err != nil || string(data) != oldIn.ID || c.started {
		t.Fatal("SDK cleanup changed app-imported bytes or started networking", err)
	}
}

func TestMobileFileExpiryRunsWithoutNetworkOrFileRequests(t *testing.T) {
	state := t.TempDir()
	c, f := mobileFileFixture(t, state)
	file := managedMobileFile(t, f.outbox)
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(state, "bridge-outgoing", "nPc", file.ID)
	nearExpiry := time.Now().Add(-3*24*time.Hour + 2*time.Second)
	if err := os.Chtimes(path, nearExpiry, nearExpiry); err != nil {
		t.Fatal(err)
	}
	c, _ = mobileFileFixture(t, state)
	if _, err := os.Stat(path); err != nil {
		t.Fatal("Not-yet-expired object was deleted early", err)
	}
	deadline := time.Now().Add(6 * time.Second)
	for {
		_, err := os.Lstat(path)
		if os.IsNotExist(err) {
			break
		}
		if err != nil || time.Now().After(deadline) {
			t.Fatal("Idle SDK retained expired bytes without a file request", err)
		}
		time.Sleep(20 * time.Millisecond)
	}
	if c.started {
		t.Fatal("Offline cleanup started private networking")
	}
	done := c.fileRetentionDone
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-done:
	default:
		t.Fatal("Client.Close returned before the cache worker stopped")
	}
}

func TestMobileFileStartupIsolatesUnknownAndPreservesHealthyObjects(t *testing.T) {
	state := t.TempDir()
	c, f := mobileFileFixture(t, state)
	file := managedMobileFile(t, f.outbox)
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(state, "bridge-outgoing", "nPc", file.ID)
	old := time.Now().Add(-3*24*time.Hour - time.Second)
	if err := os.Chtimes(path, old, old); err != nil {
		t.Fatal(err)
	}
	stageID, _ := files.NewID()
	stage := filepath.Join(state, "bridge-incoming", "nPc", ".incoming-"+stageID)
	if err := os.MkdirAll(stage, 0700); err != nil {
		t.Fatal(err)
	}
	unknown := filepath.Join(stage, "unrecognized.txt")
	if err := os.WriteFile(unknown, []byte("preserve unknown data"), 0600); err != nil {
		t.Fatal(err)
	}
	c, err := NewClient(state, "owned-file-retention", "")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	local, err := c.LocalFiles("pc.tailtest.ts.net", "nPc")
	if err != nil || local == nil || c.started {
		t.Fatal("Unknown stage blocked healthy SDK file access", err)
	}
	raw, err := local.StorageDiagnostics("diagnostics")
	if err != nil {
		t.Fatal(err)
	}
	var diagnostics struct {
		Incoming protocol.FilePage `json:"incoming"`
	}
	if json.Unmarshal([]byte(raw), &diagnostics) != nil || diagnostics.Incoming.DiagnosticCount != 1 || len(diagnostics.Incoming.Diagnostics) != 1 || diagnostics.Incoming.Diagnostics[0].ID != ".incoming-"+stageID {
		t.Fatal("Unknown stage was not diagnosed", raw)
	}
	if data, err := os.ReadFile(unknown); err != nil || string(data) != "preserve unknown data" {
		t.Fatal("SDK cleanup deleted unknown data", err)
	}
	if _, err := os.Lstat(path); !os.IsNotExist(err) {
		t.Fatal("Damaged incoming stage protected expired outgoing bytes", err)
	}
	if err := os.Remove(unknown); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(stage); err != nil {
		t.Fatal(err)
	}
	if _, err := c.LocalFiles("pc.tailtest.ts.net", "nPc"); err != nil || c.started {
		t.Fatal("SDK could not recover after owned storage repair", err)
	}
}

func TestMobileFileReadsPruneExpiryBeforeBackgroundTimer(t *testing.T) {
	state := t.TempDir()
	_, f := mobileFileFixture(t, state)
	file := managedMobileFile(t, f.inbox)
	path := filepath.Join(state, "bridge-incoming", "nPc", file.ID)
	old := time.Now().Add(-3*24*time.Hour - time.Second)
	if err := os.Chtimes(path, old, old); err != nil {
		t.Fatal(err)
	}
	if _, err := f.ReadReceived("fixture-readreceived", file.ID); !os.IsNotExist(err) {
		t.Fatal("Expired cached bytes were returned before the next timer pass", err)
	}
	if _, err := os.Lstat(path); !os.IsNotExist(err) {
		t.Fatal("Expired cached bytes remained after the read", err)
	}
}

package mobile

import (
	"bufio"
	"bytes"
	"encoding/base64"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func TestSDKCacheOwnerRejectsCompetingCleanupAndReleasesOnClose(t *testing.T) {
	state := t.TempDir()
	owner, fileClient := mobileFileFixture(t, state)
	id, _ := files.NewID()
	data := []byte("actual-in-progress-" + id)
	file, _ := files.Describe(id, "owned.bin", data)
	source, writer := io.Pipe()
	done := make(chan error, 1)
	finished := false
	go func() { _, err := fileClient.outbox.Put("nPc", file, source); done <- err }()
	defer func() {
		writer.Close()
		source.Close()
		if !finished {
			select {
			case <-done:
			case <-time.After(3 * time.Second):
				t.Error("Owned upload did not stop")
			}
		}
	}()
	if _, err := writer.Write(data[:len(data)/2]); err != nil {
		t.Fatal(err)
	}
	peer := filepath.Join(state, "bridge-outgoing", "nPc")
	entries, err := os.ReadDir(peer)
	if err != nil || len(entries) != 1 || !strings.HasPrefix(entries[0].Name(), ".incoming-") {
		t.Fatal("Actual active upload stage missing", err)
	}
	stage := filepath.Join(peer, entries[0].Name())
	other, err := NewClient(state, "owned-file-retention", "")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { other.Close() })
	if competing, err := other.LocalFiles("pc.tailtest.ts.net", "nPc"); err == nil || competing != nil || !strings.Contains(err.Error(), "file_storage_in_use") {
		t.Fatal("Second SDK instance reached the active owner's cache", err)
	}
	if _, err := os.Stat(stage); err != nil {
		t.Fatal("Competing SDK cleanup removed the active upload", err)
	}
	if _, err := writer.Write(data[len(data)/2:]); err != nil {
		t.Fatal(err)
	}
	writer.Close()
	select {
	case err := <-done:
		finished = true
		if err != nil {
			t.Fatal("Competing SDK instance interrupted the original upload", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Original upload did not finish")
	}
	if err := owner.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, err := other.LocalFiles("pc.tailtest.ts.net", "nPc")
	if err != nil {
		t.Fatal("Closed SDK owner left its cache locked", err)
	}
	if actual, content, err := reopened.outbox.Read("nPc", id); err != nil || actual != file || !bytes.Equal(content, data) {
		t.Fatal("Original committed bytes changed across cache handoff", err)
	}
}

func TestSDKCacheLeaseProcessHelper(t *testing.T) {
	if os.Getenv("BRIDGE_CACHE_LEASE_HELPER") != "1" {
		return
	}
	data, err := base64.StdEncoding.DecodeString(os.Getenv("BRIDGE_CACHE_LEASE_BYTES"))
	if err != nil {
		t.Fatal(err)
	}
	_, f := mobileFileFixture(t, os.Getenv("BRIDGE_CACHE_LEASE_ROOT"))
	descriptor, err := f.Prepare("fixture-prepare", "owned-crash.bin", data)
	if err != nil {
		t.Fatal(err)
	}
	fmt.Println(descriptor)
	time.Sleep(time.Hour)
	t.Fatal("Owned cache process was not interrupted")
}

func TestSDKCacheLeaseRecoversAfterActualOwnerProcessExit(t *testing.T) {
	state := t.TempDir()
	id, _ := files.NewID()
	data := []byte("actual-crash-persistent-bytes-" + id)
	cmd := exec.Command(os.Args[0], "-test.run=^TestSDKCacheLeaseProcessHelper$")
	cmd.Env = append(os.Environ(), "BRIDGE_CACHE_LEASE_HELPER=1", "BRIDGE_CACHE_LEASE_ROOT="+state, "BRIDGE_CACHE_LEASE_BYTES="+base64.StdEncoding.EncodeToString(data))
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	stopped := false
	defer func() {
		if !stopped {
			cmd.Process.Kill()
			cmd.Wait()
		}
	}()
	ready := make(chan string, 1)
	go func() {
		scanner := bufio.NewScanner(stdout)
		if scanner.Scan() {
			ready <- scanner.Text()
		} else {
			ready <- ""
		}
	}()
	var file protocol.File
	select {
	case line := <-ready:
		if protocol.DecodeStrict([]byte(line), &file) != nil || !files.Valid(file) {
			t.Fatal("Actual SDK cache owner did not establish its committed descriptor", stderr.String())
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Owned cache process did not become ready")
	}
	actualPath := filepath.Join(state, "bridge-outgoing", "nPc", file.ID, "content")
	if actual, err := os.ReadFile(actualPath); err != nil || !bytes.Equal(actual, data) {
		t.Fatal("Owner process did not commit the actual bytes", err)
	}
	c, err := NewClient(state, "owned-file-retention", "")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	if f, err := c.LocalFiles("pc.tailtest.ts.net", "nPc"); err == nil || f != nil || !strings.Contains(err.Error(), "file_storage_in_use") {
		t.Fatal("A second process acquired the live cache owner's storage", err)
	}
	if err := cmd.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	if err := cmd.Wait(); err == nil {
		t.Fatal("Owned cache process was not interrupted")
	}
	stopped = true
	f, err := c.LocalFiles("pc.tailtest.ts.net", "nPc")
	if err != nil {
		t.Fatal("OS did not release the interrupted owner's cache lease", err)
	}
	if actual, content, err := f.outbox.Read("nPc", file.ID); err != nil || actual != file || !bytes.Equal(content, data) {
		t.Fatal("Cache owner crash changed the original verified bytes", err)
	}
}

func TestSDKCacheLeasePreservesUnrecognizedLockContents(t *testing.T) {
	state := t.TempDir()
	c, err := NewClient(state, "owned-file-retention", "")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	path := filepath.Join(state, ".bridge-files.lock")
	if err := os.WriteFile(path, []byte("preserve unrelated bytes"), 0600); err != nil {
		t.Fatal(err)
	}
	if f, err := c.LocalFiles("pc.tailtest.ts.net", "nPc"); err == nil || f != nil || !strings.Contains(err.Error(), "file_storage_lease_invalid") {
		t.Fatal("Unrecognized lease contents were accepted", err)
	}
	if data, err := os.ReadFile(path); err != nil || string(data) != "preserve unrelated bytes" {
		t.Fatal("Claiming cache ownership overwrote unrelated bytes", err)
	}
	if _, err := os.Stat(filepath.Join(state, "bridge-incoming")); !os.IsNotExist(err) {
		t.Fatal("Invalid ownership reached the managed cache", err)
	}
}

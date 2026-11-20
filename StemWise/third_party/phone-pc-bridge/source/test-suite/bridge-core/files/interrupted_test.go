package files

import (
	"bufio"
	"bytes"
	"encoding/base64"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"phonepcbridge/internal/checksync"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

type interruptedSource struct {
	data []byte
	sent bool
}

func (r *interruptedSource) Read(p []byte) (int, error) {
	if !r.sent {
		r.sent = true
		return copy(p, r.data), nil
	}
	fmt.Fprintln(os.Stdout, "put_paused")
	<-time.After(time.Hour)
	return 0, io.EOF
}

func TestInterruptedPutProcess(t *testing.T) {
	if os.Getenv("BRIDGE_STORE_CRASH_HELPER") != "1" {
		return
	}
	data, err := base64.StdEncoding.DecodeString(os.Getenv("BRIDGE_STORE_CRASH_BYTES"))
	if err != nil {
		t.Fatal(err)
	}
	store, err := Open(os.Getenv("BRIDGE_STORE_CRASH_ROOT"))
	if err != nil {
		t.Fatal(err)
	}
	id, err := NewID()
	if err != nil {
		t.Fatal(err)
	}
	f, err := Describe(id, "owned.bin", data)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Put("nOwned", f, &interruptedSource{data: data}); err != nil {
		t.Fatal(err)
	}
	t.Fatal("Interrupted producer unexpectedly completed")
}

func TestRestartPrunesActualInterruptedUploadBytes(t *testing.T) {
	root := filepath.Join(t.TempDir(), "store")
	marker, err := NewID()
	if err != nil {
		t.Fatal(err)
	}
	data := []byte("actual-interrupted-transfer-" + marker)
	cmd := exec.Command(os.Args[0], "-test.run=^TestInterruptedPutProcess$")
	cmd.Env = append(os.Environ(), "BRIDGE_STORE_CRASH_HELPER=1", "BRIDGE_STORE_CRASH_ROOT="+root, "BRIDGE_STORE_CRASH_BYTES="+base64.StdEncoding.EncodeToString(data))
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
	select {
	case value := <-ready:
		if value != "put_paused" {
			t.Fatal("Actual Put did not reach interruption", value, stderr.String())
		}
	case <-time.After(10 * time.Second):
		t.Fatal("Owned upload helper did not reach interruption")
	}
	entries, err := os.ReadDir(filepath.Join(root, "nOwned"))
	if err != nil || len(entries) != 1 || !strings.HasPrefix(entries[0].Name(), ".incoming-") {
		t.Fatal("Actual upload staging differs", err)
	}
	stage := filepath.Join(root, "nOwned", entries[0].Name())
	stored, err := os.ReadFile(filepath.Join(stage, "content"))
	if err != nil || !bytes.Equal(stored, data) {
		t.Fatal("Actual interrupted bytes not established", err)
	}
	if err := cmd.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	if err := cmd.Wait(); err == nil {
		t.Fatal("Owned producer was not interrupted")
	}
	stopped = true
	store, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if _, err := store.PruneExpired(time.Now(), 3*24*time.Hour); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Lstat(stage); !os.IsNotExist(err) {
		t.Fatal("Restart retained actual interrupted transfer bytes", err)
	}
}

func TestPruneAbandonedStagesPreservesUnknownAndCompleteObjects(t *testing.T) {
	for _, parts := range [][]string{nil, {"content"}, {"content", "metadata.json"}} {
		t.Run(fmt.Sprint(len(parts)), func(t *testing.T) {
			root := filepath.Join(t.TempDir(), "store")
			store, err := Open(root)
			if err != nil {
				t.Fatal(err)
			}
			defer store.Close()
			id, _ := NewID()
			data := []byte("committed-" + id)
			f, _ := Describe(id, "complete.bin", data)
			if _, err := store.Put("nOwned", f, bytes.NewReader(data)); err != nil {
				t.Fatal(err)
			}
			stageID, _ := NewID()
			stage := filepath.Join(root, "nOwned", ".incoming-"+stageID)
			if err := os.Mkdir(stage, 0700); err != nil {
				t.Fatal(err)
			}
			for _, part := range parts {
				if err := os.WriteFile(filepath.Join(stage, part), []byte("interrupted"), 0600); err != nil {
					t.Fatal(err)
				}
			}
			unknown := filepath.Join(root, "nOwned", ".incoming-not-a-managed-id")
			if err := os.Mkdir(unknown, 0700); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(filepath.Join(unknown, "user.txt"), []byte("preserve"), 0600); err != nil {
				t.Fatal(err)
			}
			if _, err := store.PruneExpired(time.Now(), 3*24*time.Hour); err != nil {
				t.Fatal(err)
			}
			if _, err := os.Lstat(stage); !os.IsNotExist(err) {
				t.Fatal("Managed partial stage remains", err)
			}
			if _, actual, err := store.Read("nOwned", id); err != nil || !bytes.Equal(actual, data) {
				t.Fatal("Complete bytes affected", err)
			}
			if actual, err := os.ReadFile(filepath.Join(unknown, "user.txt")); err != nil || string(actual) != "preserve" {
				t.Fatal("Unrelated bytes affected", err)
			}
		})
	}
	root := filepath.Join(t.TempDir(), "store")
	store, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	id, _ := NewID()
	stage := filepath.Join(root, "nOwned", ".incoming-"+id)
	if err := os.MkdirAll(stage, 0700); err != nil {
		t.Fatal(err)
	}
	unknown := filepath.Join(stage, "unexpected.txt")
	if err := os.WriteFile(unknown, []byte("preserve"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := store.PruneExpired(time.Now(), 3*24*time.Hour); err != nil {
		t.Fatal("Unknown staging content blocked unrelated work", err)
	}
	if diagnostics, err := store.Diagnostics(""); err != nil || diagnostics.DiagnosticCount != 1 || diagnostics.Diagnostics[0].ID != ".incoming-"+id {
		t.Fatal("Unknown staging content lost its object-scoped diagnosis", diagnostics, err)
	}
	if actual, err := os.ReadFile(unknown); err != nil || string(actual) != "preserve" {
		t.Fatal("Unexpected content deleted", err)
	}
}

type heldSource struct {
	entered, release chan struct{}
	data             []byte
	sent             bool
}

func (r *heldSource) Read(p []byte) (int, error) {
	if r.sent {
		return 0, io.EOF
	}
	r.sent = true
	close(r.entered)
	<-r.release
	return copy(p, r.data), nil
}
func TestPruneDoesNotInterruptActivePut(t *testing.T) {
	root := filepath.Join(t.TempDir(), "store")
	store, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		closed := make(chan struct{})
		go func() { defer close(closed); store.Close() }()
		checksync.Join(t, closed, 3*time.Second, "active-upload store close")
	})
	id, _ := NewID()
	data := []byte("live-transfer-" + id)
	f, _ := Describe(id, "live.bin", data)
	source := &heldSource{entered: make(chan struct{}), release: make(chan struct{}), data: data}
	put := make(chan error, 1)
	putRetired := make(chan struct{})
	var release sync.Once
	t.Cleanup(func() {
		release.Do(func() { close(source.release) })
		checksync.Join(t, putRetired, 3*time.Second, "active upload")
	})
	go func() { defer close(putRetired); _, err := store.Put("nOwned", f, source); put <- err }()
	checksync.Receive(t, source.entered, 3*time.Second, "active upload source")
	prune := make(chan error, 1)
	pruneRetired := make(chan struct{})
	t.Cleanup(func() {
		release.Do(func() { close(source.release) })
		checksync.Join(t, pruneRetired, 3*time.Second, "active-upload retention")
	})
	go func() {
		defer close(pruneRetired)
		_, err := store.PruneExpired(time.Now(), 3*24*time.Hour)
		prune <- err
	}()
	if err := checksync.Receive(t, prune, 3*time.Second, "active-upload retention"); err != nil {
		t.Fatal(err)
	}
	stages, err := os.ReadDir(filepath.Join(root, "nOwned"))
	if err != nil || len(stages) != 1 || !strings.HasPrefix(stages[0].Name(), ".incoming-") {
		t.Fatal("Retention removed active staging", err)
	}
	release.Do(func() { close(source.release) })
	if err := checksync.Receive(t, put, 3*time.Second, "released active upload"); err != nil {
		t.Fatal(err)
	}
	if _, actual, err := store.Read("nOwned", id); err != nil || !bytes.Equal(actual, data) {
		t.Fatal("Active upload lost bytes", err)
	}
}

func TestPruneRejectsLinkedStagePartWithoutDeletingTarget(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Symlink creation requires separate Windows policy; Linux host verifies this boundary")
	}
	parent := t.TempDir()
	root := filepath.Join(parent, "store")
	store, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	id, _ := NewID()
	stage := filepath.Join(root, "nOwned", ".incoming-"+id)
	if err := os.MkdirAll(stage, 0700); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(parent, "unrelated.bin")
	if err := os.WriteFile(target, []byte("preserve"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(target, filepath.Join(stage, "content")); err != nil {
		t.Fatal(err)
	}
	if _, err := store.PruneExpired(time.Now(), 3*24*time.Hour); err != nil {
		t.Fatal("Linked staging bytes blocked unrelated work", err)
	}
	if diagnostics, err := store.Diagnostics(""); err != nil || diagnostics.DiagnosticCount != 1 {
		t.Fatal("Linked staging bytes lost their diagnosis", diagnostics, err)
	}
	if actual, err := os.ReadFile(target); err != nil || string(actual) != "preserve" {
		t.Fatal("Linked target changed", err)
	}
	if _, err := os.Lstat(stage); err != nil {
		t.Fatal("Rejected stage was removed", err)
	}
}

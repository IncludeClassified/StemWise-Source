package mobile

import (
	"bytes"
	"context"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
)

func TestCopyReceivedCrashRecovery(t *testing.T) {
	const crashRoot = "BRIDGE_COPY_CRASH_ROOT"
	const id = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	if root := os.Getenv(crashRoot); root != "" {
		c, err := NewClient(filepath.Join(root, "node"), "crash-copy", "")
		if err != nil {
			t.Fatal(err)
		}
		f, err := c.LocalFiles("pi.tailtest.ts.net", "nPi")
		if err != nil {
			t.Fatal(err)
		}
		c.localFileCopy = func(_ context.Context, destination io.Writer, source io.Reader) (int64, error) {
			if _, err := io.CopyN(destination, source, 7); err != nil {
				t.Fatal(err)
			}
			os.Exit(86) // Abrupt process death deliberately bypasses every cleanup defer.
			return 0, nil
		}
		if err := f.CopyReceived("crashing-copy", id, filepath.Join(root, "output.zip")); err != nil {
			t.Fatal(err)
		}
		t.Fatal("Copy never reached the crash checkpoint")
	}
	root := t.TempDir()
	state := filepath.Join(root, "node")
	open := func() (*Client, *FileClient) {
		t.Helper()
		c, err := NewClient(state, "crash-copy", "")
		if err != nil {
			t.Fatal(err)
		}
		f, err := c.LocalFiles("pi.tailtest.ts.net", "nPi")
		if err != nil {
			c.Close()
			t.Fatal(err)
		}
		return c, f
	}
	c, f := open()
	payload := []byte(strings.Repeat("verified zip payload", 4096))
	descriptor, err := files.Describe(id, "output.zip", payload)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.inbox.Put("nPi", descriptor, bytes.NewReader(payload)); err != nil {
		t.Fatal(err)
	}
	cached := filepath.Join(state, "bridge-incoming", "nPi", id)
	before, err := os.Stat(cached)
	if err != nil {
		t.Fatal(err)
	}
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	child := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestCopyReceivedCrashRecovery$", "-test.timeout=8s")
	child.Env = append(os.Environ(), crashRoot+"="+root)
	output, err := child.CombinedOutput()
	var exit *exec.ExitError
	if !errors.As(err, &exit) || exit.ExitCode() != 86 {
		t.Fatalf("Crash checkpoint did not execute: %v %s", err, output)
	}
	destination := filepath.Join(root, "output.zip")
	if _, err := os.Lstat(destination); !os.IsNotExist(err) {
		t.Fatal("Process death published a partial final destination", err)
	}
	stages, err := filepath.Glob(filepath.Join(root, ".bridge-copy-*.tmp"))
	if err != nil || len(stages) != 1 {
		t.Fatal("Crash did not leave exactly its owned unpublished stage", stages, err)
	}
	if actual, err := os.ReadFile(stages[0]); err != nil || !bytes.Equal(actual, payload[:7]) {
		t.Fatal("Crash stage does not contain the actual copied prefix", err)
	}
	c, f = open()
	defer c.Close()
	f.exchange = func(context.Context, string, string, string, []byte) (string, error) {
		t.Fatal("Offline crash recovery contacted the bridge")
		return "", errors.New("unexpected network")
	}
	if err := f.CopyReceived("offline-recovery", id, destination); err != nil {
		t.Fatal("Same original cache could not recover offline", err)
	}
	if actual, err := os.ReadFile(destination); err != nil || !bytes.Equal(actual, payload) {
		t.Fatal("Recovered final does not match independently known bytes", err)
	}
	stages, err = filepath.Glob(filepath.Join(root, ".bridge-copy-*.tmp"))
	if err != nil || len(stages) != 0 {
		t.Fatal("Recovery retained owned stage bytes", stages, err)
	}
	if after, err := os.Stat(cached); err != nil || !after.ModTime().Equal(before.ModTime()) || c.started {
		t.Fatal("Offline recovery renewed retention or started the node", err)
	}
}

func TestCopyReceivedConcurrentDestinationClaim(t *testing.T) {
	c, f := cancellationClient(t)
	payload := strings.Repeat("verified output", 4096)
	descriptor, _ := files.Describe(strings.Repeat("a", 32), "output.zip", []byte(payload))
	if _, err := f.inbox.Put("nPi", descriptor, strings.NewReader(payload)); err != nil {
		t.Fatal(err)
	}
	destination := filepath.Join(t.TempDir(), "output.zip")
	entered, release := make(chan int, 1), make(chan struct{})
	var copied int
	c.localFileCopy = checkpointFileCopy(entered, release, &copied)
	done, retired := make(chan error, 1), make(chan struct{})
	var once sync.Once
	t.Cleanup(func() {
		c.Cancel("first")
		once.Do(func() { close(release) })
		checksync.Join(t, retired, 3*time.Second, "received copy")
	})
	go func() { defer close(retired); done <- f.CopyReceived("first", descriptor.ID, destination) }()
	checksync.Receive(t, entered, 3*time.Second, "copy prefix")
	other, err := c.LocalFiles("pi.tailtest.ts.net", "nPi")
	if err != nil {
		t.Fatal(err)
	}
	if err := other.CopyReceived("concurrent", descriptor.ID, destination); err == nil || err.Error() != "received_copy_in_use" {
		t.Fatal("Concurrent wrapper could remove or replace the active owned stage", err)
	}
	once.Do(func() { close(release) })
	if err := checksync.Receive(t, done, 3*time.Second, "copy publication"); err != nil {
		t.Fatal(err)
	}
	if actual, err := os.ReadFile(destination); err != nil || string(actual) != payload {
		t.Fatal("Concurrent request damaged the winning copy", err)
	}
}

func TestCopyReceivedPreservesDestinationCreatedDuringCopy(t *testing.T) {
	for _, kind := range []string{"matching", "conflicting", "hardlink", "symlink"} {
		t.Run(kind, func(t *testing.T) {
			c, f := cancellationClient(t)
			payload := []byte("original verified output bytes")
			descriptor, _ := files.Describe(strings.Repeat("a", 32), "output.zip", payload)
			if _, err := f.inbox.Put("nPi", descriptor, bytes.NewReader(payload)); err != nil {
				t.Fatal(err)
			}
			root := t.TempDir()
			destination, target := filepath.Join(root, "output.zip"), filepath.Join(root, "existing.zip")
			preserved := payload
			if kind != "matching" {
				preserved = []byte("independent app-owned file")
			}
			if err := os.WriteFile(target, preserved, 0600); err != nil {
				t.Fatal(err)
			}
			if kind == "symlink" {
				probe := filepath.Join(root, "symlink-probe")
				if err := os.Symlink(target, probe); err != nil {
					t.Skipf("Platform cannot create symlink fixture: %v", err)
				}
				if err := os.Remove(probe); err != nil {
					t.Fatal(err)
				}
			}
			var before os.FileInfo
			c.localFileCopy = func(_ context.Context, destinationWriter io.Writer, source io.Reader) (int64, error) {
				n, err := io.CopyN(destinationWriter, source, 7)
				if err != nil {
					return n, err
				}
				switch kind {
				case "matching", "conflicting":
					err = os.WriteFile(destination, preserved, 0600)
				case "hardlink":
					err = os.Link(target, destination)
				case "symlink":
					err = os.Symlink(target, destination)
				}
				if err != nil {
					return n, err
				}
				before, err = os.Lstat(destination)
				if err != nil {
					return n, err
				}
				more, err := io.Copy(destinationWriter, source)
				return n + more, err
			}
			if err := f.CopyReceived("copy", descriptor.ID, destination); !errors.Is(err, os.ErrExist) {
				t.Fatal("Publication replaced or did not detect an existing destination", err)
			}
			if actual, err := os.ReadFile(destination); err != nil || !bytes.Equal(actual, preserved) {
				t.Fatal("Publication changed the app-owned destination", err)
			}
			if after, err := os.Lstat(destination); err != nil || !os.SameFile(before, after) || before.Mode() != after.Mode() {
				t.Fatal("Publication changed destination identity/type", err)
			}
			if stages, err := filepath.Glob(filepath.Join(root, ".bridge-copy-*.tmp")); err != nil || len(stages) != 0 {
				t.Fatal("Refused publication retained stage bytes", stages, err)
			}
		})
	}
}

func TestCopyReceivedPreservesPublishedDestinationOnRetry(t *testing.T) {
	c, f := cancellationClient(t)
	payload := []byte("complete published bytes")
	descriptor, _ := files.Describe(strings.Repeat("a", 32), "output.zip", payload)
	if _, err := f.inbox.Put("nPi", descriptor, bytes.NewReader(payload)); err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	destination := filepath.Join(root, "output.zip")
	var stage string
	c.localFileCopy = func(_ context.Context, destinationWriter io.Writer, source io.Reader) (int64, error) {
		stages, err := filepath.Glob(filepath.Join(root, ".bridge-copy-*.tmp"))
		if err != nil || len(stages) != 1 {
			t.Fatal("Publication stage not observed", stages, err)
		}
		stage = stages[0]
		return io.Copy(destinationWriter, source)
	}
	if err := f.CopyReceived("publish", descriptor.ID, destination); err != nil {
		t.Fatal(err)
	}
	before, err := os.Stat(destination)
	if err != nil {
		t.Fatal(err)
	}
	// An interrupted copy and an independently created final may coexist.
	if err := os.WriteFile(stage, payload[:7], 0600); err != nil {
		t.Fatal(err)
	}
	c.localFileCopy = func(context.Context, io.Writer, io.Reader) (int64, error) {
		t.Fatal("Retry recopied an already published destination")
		return 0, nil
	}
	if err := f.CopyReceived("recover", descriptor.ID, destination); !errors.Is(err, os.ErrExist) {
		t.Fatal("Retry did not preserve the existing destination contract", err)
	}
	if actual, err := os.ReadFile(destination); err != nil || !bytes.Equal(actual, payload) {
		t.Fatal("Post-publication recovery changed verified final bytes", err)
	}
	if after, err := os.Stat(destination); err != nil || !os.SameFile(before, after) {
		t.Fatal("Post-publication recovery replaced the final destination", err)
	}
	if stages, err := filepath.Glob(filepath.Join(root, ".bridge-copy-*.tmp")); err != nil || len(stages) != 0 {
		t.Fatal("Post-publication recovery retained a stage", stages, err)
	}
}

func TestCopyReceivedCloseRetainsLeaseUntilStageCleanup(t *testing.T) {
	root := t.TempDir()
	state := filepath.Join(root, "node")
	c, err := NewClient(state, "held-copy", "")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	f, err := c.LocalFiles("pi.tailtest.ts.net", "nPi")
	if err != nil {
		t.Fatal(err)
	}
	payload := []byte(strings.Repeat("cached bytes", 4096))
	descriptor, _ := files.Describe(strings.Repeat("a", 32), "output.zip", payload)
	if _, err := f.inbox.Put("nPi", descriptor, bytes.NewReader(payload)); err != nil {
		t.Fatal(err)
	}
	destination := filepath.Join(root, "output.zip")
	entered, cancelled, release := make(chan struct{}), make(chan struct{}), make(chan struct{})
	c.localFileCopy = func(ctx context.Context, destinationWriter io.Writer, source io.Reader) (int64, error) {
		n, err := io.CopyN(destinationWriter, source, 7)
		if err != nil {
			return n, err
		}
		close(entered)
		select {
		case <-ctx.Done():
			close(cancelled)
		case <-release:
			return n, errors.New("fixture released before close")
		}
		<-release
		return n, ctx.Err()
	}
	copyDone, closeDone := make(chan error, 1), make(chan error, 1)
	copyRetired, closeRetired := make(chan struct{}), make(chan struct{})
	var once sync.Once
	t.Cleanup(func() {
		once.Do(func() { close(release) })
		checksync.Join(t, copyRetired, 3*time.Second, "held copy cleanup")
		checksync.Join(t, closeRetired, 3*time.Second, "client close")
	})
	go func() { defer close(copyRetired); copyDone <- f.CopyReceived("held", descriptor.ID, destination) }()
	checksync.Receive(t, entered, 3*time.Second, "copy prefix")
	go func() { defer close(closeRetired); closeDone <- c.Close() }()
	checksync.Receive(t, cancelled, 3*time.Second, "close cancellation")
	select {
	case err := <-closeDone:
		t.Fatal("Close released storage while actual copy cleanup was held", err)
	case <-time.After(100 * time.Millisecond):
	}
	next, err := NewClient(state, "held-copy", "")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := next.LocalFiles("pi.tailtest.ts.net", "nPi"); !errors.Is(err, errFileStorageInUse) {
		next.Close()
		t.Fatal("A new client acquired the still-active stage owner's storage", err)
	}
	if err := next.Close(); err != nil {
		t.Fatal(err)
	}
	once.Do(func() { close(release) })
	if err := checksync.Receive(t, copyDone, 3*time.Second, "cancelled copy cleanup"); !errors.Is(err, context.Canceled) {
		t.Fatal("Closed client copy did not retire through cancellation", err)
	}
	if err := checksync.Receive(t, closeDone, 3*time.Second, "closed client lease release"); err != nil {
		t.Fatal(err)
	}
	next, err = NewClient(state, "held-copy", "")
	if err != nil {
		t.Fatal(err)
	}
	defer next.Close()
	nextFiles, err := next.LocalFiles("pi.tailtest.ts.net", "nPi")
	if err != nil {
		t.Fatal("Retired client did not release storage", err)
	}
	if err := nextFiles.CopyReceived("recover", descriptor.ID, destination); err != nil {
		t.Fatal("Offline original cache could not be imported after close", err)
	}
	if actual, err := os.ReadFile(destination); err != nil || !bytes.Equal(actual, payload) || next.started {
		t.Fatal("New client recovery changed bytes or started network", err)
	}
}

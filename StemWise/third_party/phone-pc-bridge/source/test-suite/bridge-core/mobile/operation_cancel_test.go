package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

func cancellationClient(t *testing.T) (*Client, *FileClient) {
	t.Helper()
	c, err := NewClient(filepath.Join(t.TempDir(), "node"), "cancel-fixture", "")
	if err != nil {
		t.Fatal(err)
	}
	f, err := c.LocalFiles("pi.tailtest.ts.net", "nPi")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := c.Close(); err != nil {
			t.Error(err)
		}
	})
	return c, f
}

func TestReceiveCancellationLatchesAcrossDescriptorContentGap(t *testing.T) {
	c, f := cancellationClient(t)
	descriptor, _ := files.Describe(strings.Repeat("a", 32), "owned.bin", []byte("actual"))
	calls := 0
	f.exchange = func(ctx context.Context, id, method, path string, _ []byte) (string, error) {
		calls++
		if !strings.Contains(path, "/info?") {
			t.Fatal("Cancellation allowed a later content request")
		}
		nonce := strings.Split(path, "nonce=")[1]
		body, _ := json.Marshal(protocol.FileReply{Protocol: protocol.Files, PCIdentity: "nPi", PeerIdentity: "nApp", Nonce: nonce, State: "available", File: &descriptor})
		c.Cancel(id)
		wire, _ := json.Marshal(map[string]any{"status": 200, "body": body, "identity": "nApp"})
		return string(wire), nil
	}
	f.stream = func(context.Context, string, string, string, io.Reader) (int, io.ReadCloser, string, error) {
		t.Fatal("Cancellation allowed a later streaming request")
		return 0, nil, "", nil
	}
	_, err := f.ReceiveMatching("owned-receive", descriptor.ID, descriptor.Name, descriptor.Size, descriptor.SHA256)
	if !errors.Is(err, context.Canceled) || calls != 1 || len(c.active) != 0 {
		t.Fatal("Descriptor-gap cancellation was lost or leaked the operation", err, calls)
	}
}

func TestCopyReceivedCancellationRemovesPartialDestination(t *testing.T) {
	c, f := cancellationClient(t)
	data := []byte(strings.Repeat("content-", 8192))
	descriptor, _ := files.Describe(strings.Repeat("a", 32), "owned.bin", data)
	if _, err := f.inbox.Put("nPi", descriptor, strings.NewReader(string(data))); err != nil {
		t.Fatal(err)
	}
	destination := filepath.Join(t.TempDir(), "import.bin")
	entered, release := make(chan int, 1), make(chan struct{})
	var copied int
	c.localFileCopy = checkpointFileCopy(entered, release, &copied)
	done, retired := make(chan error, 1), make(chan struct{})
	var unblock sync.Once
	t.Cleanup(func() {
		c.Cancel("owned-copy")
		unblock.Do(func() { close(release) })
		checksync.Join(t, retired, 3*time.Second, "cancelled received-file copy")
	})
	go func() { defer close(retired); done <- f.CopyReceived("owned-copy", descriptor.ID, destination) }()
	written := checksync.Receive(t, entered, 3*time.Second, "first actual destination write")
	if _, err := os.Lstat(destination); !os.IsNotExist(err) {
		t.Fatal("Unverified copy became a final destination", err)
	}
	stages, err := filepath.Glob(filepath.Join(filepath.Dir(destination), ".bridge-copy-*.tmp"))
	if err != nil || len(stages) != 1 {
		t.Fatal("Copy checkpoint did not hold exactly its owned stage", stages, err)
	}
	partial, err := os.ReadFile(stages[0])
	if err != nil || written <= 0 || len(partial) != written || len(partial) >= len(data) || string(partial) != string(data[:written]) {
		t.Fatal("Copy checkpoint did not hold actual partial destination bytes", written, len(partial), err)
	}
	c.Cancel("owned-copy")
	unblock.Do(func() { close(release) })
	if err := checksync.Receive(t, done, 3*time.Second, "cancelled received-file result"); !errors.Is(err, context.Canceled) {
		t.Fatal("Cancelled copy claimed a completed import", err)
	}
	checksync.Receive(t, retired, 3*time.Second, "received-file worker retirement")
	if copied != written {
		t.Fatal("Cancelled copy continued writing after its held chunk", copied, written)
	}
	if _, err := os.Stat(destination); !os.IsNotExist(err) {
		t.Fatal("Partial imported file remains", err)
	}
	if _, err := os.Stat(stages[0]); !os.IsNotExist(err) {
		t.Fatal("Cancelled copy retained its owned partial stage", err)
	}
	if len(c.active) != 0 {
		t.Fatal("Copy operation remains registered")
	}
}

func TestDeliveryRegistersCancellationBeforeHashing(t *testing.T) {
	c, _ := cancellationClient(t)
	path := filepath.Join(t.TempDir(), "import.bin")
	data := []byte(strings.Repeat("content-", 8192))
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	descriptor, _ := files.Describe(strings.Repeat("b", 32), "import.bin", data)
	payload, _ := json.Marshal([]deliveryImport{{File: descriptor, Path: path}})
	entered, release := make(chan int, 1), make(chan struct{})
	var hashed int
	c.localFileCopy = checkpointFileCopy(entered, release, &hashed)
	// A broken cancellation path must still not start a real transport.
	c.node = nil
	var unblock sync.Once
	done, retired := make(chan error, 1), make(chan struct{})
	var result string
	t.Cleanup(func() {
		c.Cancel("owned-delivery")
		unblock.Do(func() { close(release) })
		checksync.Join(t, retired, 3*time.Second, "cancelled delivery hash")
	})
	go func() {
		defer close(retired)
		var err error
		result, err = c.ConfirmDelivery("owned-delivery", "pi.tailtest.ts.net", "nPi", strings.Repeat("c", 32), string(payload))
		done <- err
	}()
	written := checksync.Receive(t, entered, 3*time.Second, "first actual delivery-hash write")
	c.mu.Lock()
	active := c.active["owned-delivery"] != nil
	c.mu.Unlock()
	if !active || written <= 0 || int64(written) >= descriptor.Size {
		t.Fatal("Cancellation was not registered before actual partial hashing", active, written)
	}
	c.Cancel("owned-delivery")
	unblock.Do(func() { close(release) })
	if err := checksync.Receive(t, done, 3*time.Second, "cancelled delivery-hash result"); !errors.Is(err, context.Canceled) {
		t.Fatal("Hash cancellation proceeded toward delivery", err)
	}
	checksync.Receive(t, retired, 3*time.Second, "delivery-hash worker retirement")
	if hashed != written {
		t.Fatal("Cancelled verification continued hashing after its held chunk", hashed, written)
	}
	if result != "" || len(c.active) != 0 || c.started {
		t.Fatal("Cancelled verification acknowledged delivery, retained its operation or started transport")
	}
}

type checkpointFileWriter struct {
	io.Writer
	entered chan<- int
	release <-chan struct{}
	written *int
	once    sync.Once
}

func (w *checkpointFileWriter) Write(p []byte) (int, error) {
	n, err := w.Writer.Write(p)
	*w.written += n
	if n > 0 {
		w.once.Do(func() { w.entered <- n; <-w.release })
	}
	return n, err
}

func checkpointFileCopy(entered chan<- int, release <-chan struct{}, written *int) func(context.Context, io.Writer, io.Reader) (int64, error) {
	return func(ctx context.Context, destination io.Writer, source io.Reader) (int64, error) {
		return files.CopyContext(ctx, &checkpointFileWriter{Writer: destination, entered: entered, release: release, written: written}, source)
	}
}

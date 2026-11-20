package pc

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/sys/unix"
	"phonepcbridge/files"
	"phonepcbridge/protocol"
	"tailscale.com/tsnet"
)

func pcCapacityFixture(t *testing.T) (*Service, unix.Statfs_t) {
	t.Helper()
	path := os.Getenv("BRIDGE_TEST_SMALL_FS")
	if path == "" {
		t.Skip("Run through StorageCapacityCheck.py on an owned small filesystem")
	}
	var stat unix.Statfs_t
	if err := unix.Statfs(path, &stat); err != nil || stat.Type != unix.TMPFS_MAGIC || filepath.Clean(os.TempDir()) != filepath.Clean(path) {
		t.Fatal("Owned temporary filesystem differs", err)
	}
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	s, err := New(&tsnet.Server{}, Config{Identity: "nPc", Host: "pc.tailtest.ts.net", CodexExecutable: executable,
		AllowedPeers: []string{"nPi"}, BackendPeer: "nPi", DataDir: root, IntegrationDir: root})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	s.authenticate = func(context.Context, string) (string, error) { return "nPi", nil }
	return s, stat
}

func TestPcStorageReserveOnSmallFilesystem(t *testing.T) {
	testPcStorageReserve(t, false)
}

func TestPcStorageReserveOnInodeLimitedFilesystem(t *testing.T) {
	testPcStorageReserve(t, true)
}

func testPcStorageReserve(t *testing.T, inodeLimited bool) {
	s, stat := pcCapacityFixture(t)
	available := stat.Bavail * uint64(stat.Bsize)
	if (!inodeLimited && available >= 64<<20) || (inodeLimited && (available <= 512<<20 || stat.Files > 128)) {
		t.Fatal("Owned filesystem does not isolate the requested capacity condition")
	}
	data := []byte("owned-capacity-input-" + randomToken())
	f, _ := files.Describe(randomToken(), "owned.bin", data)
	nonce := randomToken()
	query := url.Values{"id": {f.ID}, "name": {f.Name}, "size": {strconv.FormatInt(f.Size, 10)}, "sha256": {f.SHA256}, "nonce": {nonce}}
	source := bytes.NewReader(data)
	request := httptest.NewRequest("POST", "/v1/files/put?"+query.Encode(), source)
	w := httptest.NewRecorder()
	s.handle(w, request)
	var reply protocol.FileReply
	if w.Code != 503 || protocol.DecodeStrict(w.Body.Bytes(), &reply) != nil || reply.Protocol != protocol.Files || reply.PCIdentity != "nPc" || reply.PeerIdentity != "nPi" || reply.Nonce != nonce || reply.State != protocol.StorageCapacityUnavailable || reply.File != nil || reply.Page != nil || source.Len() != len(data) {
		t.Fatal("Default PC upload did not refuse capacity before consuming source", w.Code)
	}
	queued := &io.LimitedReader{R: bytes.NewReader(data), N: int64(len(data))}
	if _, err := s.QueueFileReader(context.Background(), "nPi", f, queued); !errors.Is(err, files.ErrStorageCapacity) || queued.N != int64(len(data)) {
		t.Fatal("Default PC output queue consumed source or lost its capacity error", err)
	}
	for _, name := range []string{"incoming", "outgoing"} {
		entries, err := os.ReadDir(filepath.Join(s.cfg.DataDir, name))
		if err != nil || len(entries) != 0 {
			t.Fatal("Refused transfer retained managed bytes or staging", name, err)
		}
	}
	// Transfer admission leaves the adapter's small mutation receipts writable.
	native := &creationNativeFixture{threads: map[string]string{}}
	input := protocol.AdapterRequest{Operation: "create", AppID: randomToken(), RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Name: "owned-capacity"}
	first, err := s.createThread(context.Background(), "nPi", input, native)
	if err != nil {
		t.Fatal("Transfer reserve prevented a small durable mutation receipt", err)
	}
	second, err := s.createThread(context.Background(), "nPi", input, native)
	if err != nil || !bytes.Equal(first, second) || native.starts != 1 {
		t.Fatal("Low-space receipt recovery lost identity or repeated native creation", err)
	}
}

func TestPcStorageReservationsShareCapacity(t *testing.T) {
	s, stat := pcCapacityFixture(t)
	available := stat.Bavail * uint64(stat.Bsize)
	if available < 540<<20 || available > 548<<20 || stat.Ffree < 1024 {
		t.Fatal("Owned reservation filesystem bounds differ")
	}
	data := strings.Repeat("x", 20<<20)
	f, err := files.DescribeReader(randomToken(), "owned.bin", strings.NewReader(data))
	if err != nil {
		t.Fatal(err)
	}
	entered, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	done := make(chan error, 1)
	finished := false
	t.Cleanup(func() {
		once.Do(func() { close(release) })
		if !finished {
			select {
			case <-done:
			case <-time.After(5 * time.Second):
				t.Error("Owned blocked transfer did not stop")
			}
		}
	})
	source := &commitBlockedReader{block: func() error { close(entered); <-release; return io.ErrUnexpectedEOF }}
	go func() { _, err := s.inbox.Put("nPi", f, source); done <- err }()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("First default PC upload did not reserve space")
	}
	queued := &io.LimitedReader{R: strings.NewReader(data), N: f.Size}
	if _, err := s.QueueFileReader(context.Background(), "nPi", f, queued); !errors.Is(err, files.ErrStorageCapacity) || queued.N != f.Size {
		t.Fatal("Default PC incoming and outgoing stores counted the same free space", err)
	}
	once.Do(func() { close(release) })
	select {
	case err := <-done:
		finished = true
		if !errors.Is(err, io.ErrUnexpectedEOF) {
			t.Fatal("Interrupted transfer lost its actual error", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Interrupted transfer did not release its reservation")
	}
	if actual, err := s.QueueFileReader(context.Background(), "nPi", f, strings.NewReader(data)); err != nil || actual != f {
		t.Fatal("Unchanged output could not use released capacity", err)
	}
	content, err := os.Open(filepath.Join(s.cfg.DataDir, "outgoing", "nPi", f.ID, "content"))
	if err != nil {
		t.Fatal(err)
	}
	defer content.Close()
	if actual, err := files.DescribeReader(f.ID, f.Name, content); err != nil || actual != f {
		t.Fatal("Independent committed output bytes differ", err)
	}
}

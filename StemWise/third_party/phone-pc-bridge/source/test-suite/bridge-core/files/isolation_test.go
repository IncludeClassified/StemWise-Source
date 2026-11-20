package files

import (
	"bytes"
	"context"
	"errors"
	"os"
	"path/filepath"
	"phonepcbridge/internal/checksync"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestDamagedObjectsDoNotHideHealthyFiles(t *testing.T) {
	root := filepath.Join(t.TempDir(), "objects")
	store, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	healthy, _ := Describe(strings.Repeat("f", 32), "healthy.bin", []byte("healthy"))
	if _, err := store.Put("nOwned", healthy, strings.NewReader("healthy")); err != nil {
		t.Fatal(err)
	}
	for index := 0; index < 20; index++ {
		id := strings.Repeat("0", 30) + string("0123456789abcdef"[index/16]) + string("0123456789abcdef"[index%16])
		if err := os.Mkdir(filepath.Join(root, "nOwned", id), 0700); err != nil {
			t.Fatal(err)
		}
	}
	unknown := filepath.Join(root, "nOwned", "vendor-object")
	if err := os.WriteFile(unknown, []byte("preserve"), 0600); err != nil {
		t.Fatal(err)
	}
	page, err := store.List("nOwned", "", 100)
	if err != nil || len(page.Files) != 1 || page.Files[0] != healthy || page.DiagnosticCount != 21 || len(page.Diagnostics) != 16 {
		t.Fatal("Corrupt or unknown objects blocked healthy listing or unbounded diagnostics", page, err)
	}
	for _, issue := range page.Diagnostics {
		if issue.Peer != "nOwned" || issue.Code != "managed_object_corrupt" {
			t.Fatal("Lost object ownership", issue)
		}
	}
	if _, err := store.PruneExpired(time.Now(), 72*time.Hour); err != nil {
		t.Fatal(err)
	}
	issues, err := store.Diagnostics("nOwned")
	if err != nil || issues.DiagnosticCount != 21 || len(issues.Diagnostics) != 16 {
		t.Fatal(issues, err)
	}
	if _, content, err := store.Read("nOwned", healthy.ID); err != nil || string(content) != "healthy" {
		t.Fatal(err)
	}
	if content, err := os.ReadFile(unknown); err != nil || string(content) != "preserve" {
		t.Fatal("Unknown entry modified", err)
	}
	other, err := store.Diagnostics("nOther")
	if err != nil || other.DiagnosticCount != 0 {
		t.Fatal("Peer diagnostic leaked", other, err)
	}
}

func TestAbsentMeansMissingFinalObjectOnly(t *testing.T) {
	root := filepath.Join(t.TempDir(), "objects")
	store, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	id := strings.Repeat("a", 32)
	if _, err := store.Info("nOwned", id); !errors.Is(err, ErrAbsent) || !os.IsNotExist(err) {
		t.Fatal("Absent final not classified", err)
	}
	path := filepath.Join(root, "nOwned", id)
	if err := os.MkdirAll(path, 0700); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Info("nOwned", id); !errors.Is(err, ErrCorrupt) || os.IsNotExist(err) {
		t.Fatal("Incomplete final became replayable absence", err)
	}
	if _, _, err := store.OpenVerified("nOwned", id); !errors.Is(err, ErrCorrupt) || os.IsNotExist(err) {
		t.Fatal("Missing receipt became replayable absence", err)
	}
}

func TestMetadataIsCheapAndUseStillVerifiesBytes(t *testing.T) {
	root := filepath.Join(t.TempDir(), "objects")
	store, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	f, _ := Describe(strings.Repeat("a", 32), "owned.bin", []byte("original"))
	if _, err := store.Put("nOwned", f, strings.NewReader("original")); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "nOwned", f.ID, "content"), []byte("modified"), 0600); err != nil {
		t.Fatal(err)
	}
	if metadata, err := store.Info("nOwned", f.ID); err != nil || metadata != f {
		t.Fatal("Metadata unexpectedly verified payload", metadata, err)
	}
	if page, err := store.List("nOwned", "", 10); err != nil || len(page.Files) != 1 || page.Files[0] != f {
		t.Fatal(page, err)
	}
	if _, _, err := store.Read("nOwned", f.ID); !errors.Is(err, ErrCorrupt) {
		t.Fatal("Actual read accepted same-size corrupt bytes", err)
	}
	if _, file, err := store.OpenVerified("nOwned", f.ID); !errors.Is(err, ErrCorrupt) || file != nil {
		t.Fatal("Receipt accepted same-size corrupt bytes", err)
	}
	if _, err := store.Put("nOwned", f, strings.NewReader("original")); !errors.Is(err, ErrCorrupt) {
		t.Fatal("Idempotent commit accepted corrupt final", err)
	}
	if err := store.Remove("nOwned", f.ID); err != nil {
		t.Fatal("Explicit structural removal still required old payload hash", err)
	}
}

func TestOwnedDiscardNeedsNoValidOldDescriptor(t *testing.T) {
	root := filepath.Join(t.TempDir(), "objects")
	store, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	id := strings.Repeat("a", 32)
	for _, peer := range []string{"nOwned", "nOther"} {
		path := filepath.Join(root, peer, id)
		if err := os.MkdirAll(path, 0700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(path, "unknown"), []byte("owned context"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	if err := store.Remove("nOwned", id); !errors.Is(err, ErrCorrupt) {
		t.Fatal("Normal removal accepted unknown object", err)
	}
	if err := store.Discard("nOwned", id); err != nil {
		t.Fatal(err)
	}
	if err := store.Discard("nOwned", id); err != nil {
		t.Fatal("Discard retry failed", err)
	}
	if _, err := os.Lstat(filepath.Join(root, "nOwned", id)); !os.IsNotExist(err) {
		t.Fatal("Damaged owned object not discarded", err)
	}
	if content, err := os.ReadFile(filepath.Join(root, "nOther", id, "unknown")); err != nil || string(content) != "owned context" {
		t.Fatal("Discard crossed ownership", err)
	}
	for _, pair := range [][2]string{{"../outside", id}, {"nOwned", "../outside"}} {
		if err := store.Discard(pair[0], pair[1]); err == nil {
			t.Fatal("Discard accepted caller path", pair)
		}
	}
}

type hashPauseContext struct {
	context.Context
	checks           atomic.Int32
	entered, release chan struct{}
}

func (c *hashPauseContext) Err() error {
	if c.checks.Add(1) == 2 {
		close(c.entered)
		<-c.release
	}
	return c.Context.Err()
}

func TestPayloadHashDoesNotOwnGlobalStoreLock(t *testing.T) {
	store, err := Open(filepath.Join(t.TempDir(), "objects"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		closed := make(chan struct{})
		go func() { defer close(closed); store.Close() }()
		checksync.Join(t, closed, time.Second, "hash-verification store close")
	})
	data := bytes.Repeat([]byte("a"), 64<<10)
	f, _ := Describe(strings.Repeat("a", 32), "owned.bin", data)
	if _, err := store.Put("nOwned", f, bytes.NewReader(data)); err != nil {
		t.Fatal(err)
	}
	ctx := &hashPauseContext{Context: context.Background(), entered: make(chan struct{}), release: make(chan struct{})}
	finished := make(chan error, 1)
	retired := make(chan struct{})
	var release sync.Once
	t.Cleanup(func() {
		release.Do(func() { close(ctx.release) })
		checksync.Join(t, retired, time.Second, "payload verification")
	})
	go func() {
		defer close(retired)
		_, file, err := store.OpenVerifiedContext(ctx, "nOwned", f.ID)
		if file != nil {
			file.Close()
		}
		finished <- err
	}()
	select {
	case <-ctx.entered:
	case <-time.After(time.Second):
		t.Fatal("Hash phase never entered")
	}
	metadata := make(chan error, 1)
	metadataRetired := make(chan struct{})
	t.Cleanup(func() {
		release.Do(func() { close(ctx.release) })
		checksync.Join(t, metadataRetired, time.Second, "cheap metadata read")
	})
	go func() { defer close(metadataRetired); _, err := store.Info("nOwned", f.ID); metadata <- err }()
	select {
	case err := <-metadata:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("Payload verification blocked cheap metadata behind global lock")
	}
	release.Do(func() { close(ctx.release) })
	if err := checksync.Receive(t, finished, time.Second, "released payload verification"); err != nil {
		t.Fatal(err)
	}
}

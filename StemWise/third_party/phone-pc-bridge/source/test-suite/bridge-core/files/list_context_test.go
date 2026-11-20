package files

import (
	"context"
	"errors"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

func TestLateCursorListingCancellationCannotReturnSuccess(t *testing.T) {
	store, err := Open(filepath.Join(t.TempDir(), "objects"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	for _, digit := range []string{"1", "2", "3"} {
		file, err := Describe(strings.Repeat(digit, 32), "kept.bin", []byte(digit))
		if err != nil {
			t.Fatal(err)
		}
		if _, err := store.Put("nOwned", file, strings.NewReader(digit)); err != nil {
			t.Fatal(err)
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	visited := 0
	store.beforeListEntry = func(peer, id string) {
		visited++
		if peer == "nOwned" && id == strings.Repeat("3", 32) {
			cancel()
		}
	}
	page, err := store.ListContext(ctx, "nOwned", strings.Repeat("f", 32), 10)
	if !errors.Is(err, context.Canceled) || visited != 3 || len(page.Files) != 0 || page.NextCursor != "" {
		t.Fatal("Cancellation while skipping the sorted late cursor became success", page, err, visited)
	}
}

func TestListingSnapshotAllowsConcurrentPutAndRemoval(t *testing.T) {
	store, err := Open(filepath.Join(t.TempDir(), "objects"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { store.Close() })
	original, _ := Describe(strings.Repeat("1", 32), "original.bin", []byte("original"))
	if _, err := store.Put("nOwned", original, strings.NewReader("original")); err != nil {
		t.Fatal(err)
	}
	entered, release, retired := make(chan struct{}), make(chan struct{}), make(chan struct{})
	unblock := sync.OnceFunc(func() { close(release) })
	t.Cleanup(func() { unblock(); checksync.Join(t, retired, 3*time.Second, "listing snapshot") })
	var once sync.Once
	store.beforeListEntry = func(peer, id string) {
		if peer == "nOwned" && id == original.ID {
			once.Do(func() { close(entered); <-release })
		}
	}
	type listing struct {
		page protocol.FilePage
		err  error
	}
	listed := make(chan listing, 1)
	go func() {
		defer close(retired)
		page, err := store.ListContext(context.Background(), "nOwned", "", 10)
		listed <- listing{page, err}
	}()
	checksync.Receive(t, entered, 3*time.Second, "sorted listing snapshot")
	added, _ := Describe(strings.Repeat("2", 32), "new.bin", []byte("new contents"))
	put := make(chan error, 1)
	go func() {
		_, err := store.Put("nOwned", added, strings.NewReader("new contents"))
		if err == nil {
			err = store.Remove("nOwned", original.ID)
		}
		put <- err
	}()
	if err := checksync.Receive(t, put, 3*time.Second, "concurrent committed Put and Remove"); err != nil {
		t.Fatal(err)
	}
	if store.uploads.TryLock() {
		store.uploads.Unlock()
		t.Fatal("Listing did not pin the root against Close")
	}
	unblock()
	result := checksync.Receive(t, listed, 3*time.Second, "listing after concurrent mutation")
	if result.err != nil || len(result.page.Files) != 0 || result.page.DiagnosticCount != 0 || result.page.NextCursor != "" {
		t.Fatal("Advisory snapshot invented corruption or included a later commit", result.page, result.err)
	}
	actual, bytes, err := store.Read("nOwned", added.ID)
	if err != nil || actual != added || string(bytes) != "new contents" {
		t.Fatal("Concurrent committed object changed", actual, err)
	}
}

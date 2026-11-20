package files

import (
	"bytes"
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"phonepcbridge/protocol"
)

func accessRetentionStore(t *testing.T, cache bool) (*Store, string) {
	t.Helper()
	root := filepath.Join(t.TempDir(), "objects")
	var store *Store
	var err error
	if cache {
		store, err = OpenWithRetention(root, protocol.Retention)
	} else {
		store, err = Open(root)
	}
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { store.Close() })
	return store, root
}

func ageAccessObject(t *testing.T, root, id string) time.Time {
	t.Helper()
	path := filepath.Join(root, "nOwned", id)
	old := time.Now().Add(-protocol.Retention - time.Hour)
	if err := os.Chtimes(path, old, old); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	return info.ModTime()
}

func requireAccessBytes(t *testing.T, root string, f protocol.File, data []byte) {
	t.Helper()
	actual, err := os.ReadFile(filepath.Join(root, "nOwned", f.ID, "content"))
	if err != nil || !bytes.Equal(actual, data) {
		t.Fatal("Existing managed bytes changed", err)
	}
}

func TestAccessExpiryIsOptInAndDefaultStoresRetainReferencedBytes(t *testing.T) {
	for _, cache := range []bool{false, true} {
		t.Run(map[bool]string{false: "default", true: "mobile_cache"}[cache], func(t *testing.T) {
			store, root := accessRetentionStore(t, cache)
			f, _ := Describe(strings.Repeat("a", 32), "context.bin", []byte("retained context"))
			if _, err := store.Put("nOwned", f, strings.NewReader("retained context")); err != nil {
				t.Fatal(err)
			}
			ageAccessObject(t, root, f.ID)
			actual, content, err := store.Read("nOwned", f.ID)
			if cache {
				if !errors.Is(err, ErrAbsent) {
					t.Fatal("Expired cache read returned content", err)
				}
				if _, err := os.Stat(filepath.Join(root, "nOwned", f.ID)); !os.IsNotExist(err) {
					t.Fatal("Exact expired cache object not retired", err)
				}
			} else if err != nil || actual != f || string(content) != "retained context" {
				t.Fatal("Default store applied mobile age policy", err)
			}
		})
	}
}

func TestExpiredPutRevalidatesFreshSnapshotAndPreservesDescriptorConflict(t *testing.T) {
	for _, conflict := range []bool{false, true} {
		t.Run(map[bool]string{false: "same_descriptor", true: "conflicting_descriptor"}[conflict], func(t *testing.T) {
			store, root := accessRetentionStore(t, true)
			data := []byte("owned immutable bytes")
			f, _ := Describe(strings.Repeat("a", 32), "original.bin", data)
			if _, err := store.Put("nOwned", f, bytes.NewReader(data)); err != nil {
				t.Fatal(err)
			}
			old := ageAccessObject(t, root, f.ID)
			expected := f
			if conflict {
				expected.Name = "changed.bin"
			}
			actual, err := store.Put("nOwned", expected, bytes.NewReader(data))
			info, statErr := os.Stat(filepath.Join(root, "nOwned", f.ID))
			if statErr != nil {
				t.Fatal("Put erased the final object", statErr)
			}
			if conflict {
				if !errors.Is(err, ErrConflict) || !info.ModTime().Equal(old) {
					t.Fatal("Expired descriptor conflict was replaced or renewed", err)
				}
			} else if err != nil || actual != f || !info.ModTime().After(old) {
				t.Fatal("Expired identical cache did not commit fresh verified bytes", err)
			}
			requireAccessBytes(t, root, f, data)
		})
	}
}

func heldAccessPut(t *testing.T, store *Store, f protocol.File, data []byte) (<-chan error, func()) {
	t.Helper()
	source := &heldSource{entered: make(chan struct{}), release: make(chan struct{}), data: data}
	done := make(chan error, 1)
	retired := make(chan struct{})
	var release sync.Once
	releaseAndWait := func() {
		release.Do(func() { close(source.release) })
		select {
		case <-retired:
		case <-time.After(3 * time.Second):
			t.Error("Owned upload worker did not retire")
		}
	}
	t.Cleanup(releaseAndWait)
	go func() { defer close(retired); _, err := store.Put("nOwned", f, source); done <- err }()
	select {
	case <-source.entered:
	case <-time.After(3 * time.Second):
		t.Fatal("Owned source did not reach incoming stream")
	}
	return done, func() { release.Do(func() { close(source.release) }) }
}

func TestExpiredLateWinnerCannotBypassImmutableDescriptor(t *testing.T) {
	for _, conflict := range []bool{false, true} {
		t.Run(map[bool]string{false: "same_winner", true: "changed_winner"}[conflict], func(t *testing.T) {
			store, root := accessRetentionStore(t, true)
			data := []byte("owned incoming bytes")
			f, _ := Describe(strings.Repeat("a", 32), "original.bin", data)
			done, release := heldAccessPut(t, store, f, data)
			winner := f
			if conflict {
				winner.Name = "other.bin"
			}
			if _, err := store.Put("nOwned", winner, bytes.NewReader(data)); err != nil {
				t.Fatal(err)
			}
			old := ageAccessObject(t, root, f.ID)
			release()
			var err error
			select {
			case err = <-done:
			case <-time.After(3 * time.Second):
				t.Fatal("Late-winner upload did not finish")
			}
			info, statErr := os.Stat(filepath.Join(root, "nOwned", f.ID))
			if statErr != nil {
				t.Fatal("Late winner was erased", statErr)
			}
			if conflict {
				if !errors.Is(err, ErrConflict) || !info.ModTime().Equal(old) {
					t.Fatal("Changed expired winner was replaced or renewed", err)
				}
			} else if err != nil || !info.ModTime().After(old) {
				t.Fatal("Same expired winner prevented fresh verified commit", err)
			}
			requireAccessBytes(t, root, winner, data)
		})
	}
}

func TestIdempotentRecheckPreservesChangedExpiredReplacement(t *testing.T) {
	store, root := accessRetentionStore(t, true)
	data := []byte("owned immutable bytes")
	f, _ := Describe(strings.Repeat("a", 32), "original.bin", data)
	if _, err := store.Put("nOwned", f, bytes.NewReader(data)); err != nil {
		t.Fatal(err)
	}
	done, release := heldAccessPut(t, store, f, data)
	if err := store.Discard("nOwned", f.ID); err != nil {
		t.Fatal(err)
	}
	winner := f
	winner.Name = "replacement.bin"
	if _, err := store.Put("nOwned", winner, bytes.NewReader(data)); err != nil {
		t.Fatal(err)
	}
	old := ageAccessObject(t, root, winner.ID)
	release()
	select {
	case err := <-done:
		if !errors.Is(err, ErrConflict) {
			t.Fatal("Changed idempotent replacement did not conflict", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Idempotent upload did not finish")
	}
	info, err := os.Stat(filepath.Join(root, "nOwned", winner.ID))
	if err != nil || !info.ModTime().Equal(old) {
		t.Fatal("Conflict erased or renewed another immutable descriptor", err)
	}
	requireAccessBytes(t, root, winner, data)
}

func TestCanceledAndUnknownShapeAccessCannotDiscardExpiredBytes(t *testing.T) {
	for _, variant := range []string{"already_cancelled", "cancel_before_delete", "unknown_member"} {
		t.Run(variant, func(t *testing.T) {
			store, root := accessRetentionStore(t, true)
			data := []byte("owned immutable bytes")
			f, _ := Describe(strings.Repeat("a", 32), "original.bin", data)
			if _, err := store.Put("nOwned", f, bytes.NewReader(data)); err != nil {
				t.Fatal(err)
			}
			if variant == "unknown_member" {
				if err := os.WriteFile(filepath.Join(root, "nOwned", f.ID, "unknown"), []byte("preserve unknown"), 0600); err != nil {
					t.Fatal(err)
				}
			}
			ageAccessObject(t, root, f.ID)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			var selected context.Context = ctx
			if variant == "already_cancelled" {
				cancel()
			}
			if variant == "cancel_before_delete" {
				selected = &cancelAtCheck{Context: ctx, cancel: cancel, remaining: 2}
			}
			_, err := store.InfoContext(selected, "nOwned", f.ID)
			want := context.Canceled
			if variant == "unknown_member" {
				want = ErrCorrupt
			}
			if !errors.Is(err, want) {
				t.Fatal("Cancellation or unknown object became usable/absent", err)
			}
			requireAccessBytes(t, root, f, data)
		})
	}
}

func TestNonexpiredIdempotentPutDoesNotRenewRetention(t *testing.T) {
	store, root := accessRetentionStore(t, true)
	f, _ := Describe(strings.Repeat("a", 32), "original.bin", []byte("owned"))
	if _, err := store.Put("nOwned", f, strings.NewReader("owned")); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(root, "nOwned", f.ID)
	old := time.Now().Add(-time.Hour)
	if err := os.Chtimes(path, old, old); err != nil {
		t.Fatal(err)
	}
	before, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Put("nOwned", f, strings.NewReader("owned")); err != nil {
		t.Fatal(err)
	}
	after, err := os.Stat(path)
	if err != nil || !after.ModTime().Equal(before.ModTime()) {
		t.Fatal("Idempotent retry renewed cache deadline", err)
	}
}

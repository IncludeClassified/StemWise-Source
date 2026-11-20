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
	"testing"
	"time"
)

func TestPutDoesNotAcknowledgeReplacedVerifiedWinner(t *testing.T) {
	for _, changed := range []bool{false, true} {
		t.Run(map[bool]string{false: "same_descriptor_corrupt_replacement", true: "changed_descriptor"}[changed], func(t *testing.T) {
			root := filepath.Join(t.TempDir(), "objects")
			store, err := OpenWithRetention(root, time.Hour)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { closeConcurrentStore(t, store) })
			id := strings.Repeat("a", 32)
			data := []byte("verified winner")
			f, _ := Describe(id, "owned.bin", data)
			replacement, replacementData := f, bytes.Repeat([]byte("x"), len(data))
			if changed {
				replacement, _ = Describe(id, "replacement.bin", replacementData)
			}
			validData := data
			if changed {
				validData = replacementData
			}
			if _, err := store.Put("nReplacement", replacement, bytes.NewReader(validData)); err != nil {
				t.Fatal(err)
			}
			if !changed {
				if err := os.WriteFile(filepath.Join(root, "nReplacement", id, "content"), replacementData, 0600); err != nil {
					t.Fatal(err)
				}
			}
			base, cancel := context.WithTimeout(context.Background(), 8*time.Second)
			defer cancel()
			verified, release := make(chan bool, 1), make(chan struct{})
			var checkpoint sync.Once
			store.afterVerified = func() {
				checkpoint.Do(func() {
					unlocked := store.mu.TryLock()
					if unlocked {
						store.mu.Unlock()
					}
					verified <- unlocked
					<-release
				})
			}
			source := &heldSource{entered: make(chan struct{}), release: make(chan struct{}), data: data}
			var releaseSource, releaseHash sync.Once
			finished := make(chan error, 1)
			retired := make(chan struct{})
			t.Cleanup(func() {
				cancel()
				releaseSource.Do(func() { close(source.release) })
				releaseHash.Do(func() { close(release) })
				checksync.Join(t, retired, 3*time.Second, "verified winner upload")
			})
			go func() {
				defer close(retired)
				_, err := store.PutContext(base, "nOwned", f, source)
				finished <- err
			}()
			checksync.Receive(t, source.entered, 3*time.Second, "winner upload source")
			if _, err := store.Put("nOwned", f, bytes.NewReader(data)); err != nil {
				t.Fatal(err)
			}
			releaseSource.Do(func() { close(source.release) })
			if !checksync.Receive(t, verified, 3*time.Second, "verified winner before current-object check") {
				t.Fatal("Winner verification retained the store lock")
			}
			old := filepath.Join(root, "nOwned", id)
			// This checkpoint follows the actual hash and handle close. Discard
			// must succeed immediately, including on Windows, before replacement.
			if err := store.Discard("nOwned", id); err != nil {
				t.Fatal("Verified handle did not retire before current-object check", err)
			}
			if err := os.Rename(filepath.Join(root, "nReplacement", id), old); err != nil {
				t.Fatal(err)
			}
			releaseHash.Do(func() { close(release) })
			want := ErrCorrupt
			if changed {
				want = ErrConflict
			}
			if err := checksync.Receive(t, finished, 3*time.Second, "replaced winner result"); !errors.Is(err, want) {
				t.Fatal("Acknowledged a final object whose bytes were not verified", err)
			}
			checksync.Receive(t, retired, 3*time.Second, "verified winner retirement")
			if actual, err := os.ReadFile(filepath.Join(old, "content")); err != nil || !bytes.Equal(actual, replacementData) {
				t.Fatal("Verification changed replacement bytes", err)
			}
		})
	}
}

func TestIdempotentPutRechecksVerifiedIdentityAfterIncomingBytes(t *testing.T) {
	root := filepath.Join(t.TempDir(), "objects")
	store, err := OpenWithRetention(root, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { closeConcurrentStore(t, store) })
	data := []byte("verified original")
	f, _ := Describe(strings.Repeat("a", 32), "owned.bin", data)
	if _, err := store.Put("nOwned", f, bytes.NewReader(data)); err != nil {
		t.Fatal(err)
	}
	source := &heldSource{entered: make(chan struct{}), release: make(chan struct{}), data: data}
	finished := make(chan error, 1)
	retired := make(chan struct{})
	var release sync.Once
	t.Cleanup(func() {
		release.Do(func() { close(source.release) })
		checksync.Join(t, retired, 3*time.Second, "idempotent incoming-byte check")
	})
	go func() {
		defer close(retired)
		_, err := store.Put("nOwned", f, source)
		finished <- err
	}()
	checksync.Receive(t, source.entered, 3*time.Second, "idempotent incoming bytes")
	if err := store.Discard("nOwned", f.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Put("nOwned", f, bytes.NewReader(data)); err != nil {
		t.Fatal(err)
	}
	content := filepath.Join(root, "nOwned", f.ID, "content")
	corrupt := bytes.Repeat([]byte("x"), len(data))
	if err := os.WriteFile(content, corrupt, 0600); err != nil {
		t.Fatal(err)
	}
	release.Do(func() { close(source.release) })
	if err := checksync.Receive(t, finished, 3*time.Second, "replaced idempotent result"); !errors.Is(err, ErrCorrupt) {
		t.Fatal("Acknowledged a same-descriptor unverified replacement", err)
	}
	checksync.Receive(t, retired, 3*time.Second, "idempotent upload retirement")
	if actual, err := os.ReadFile(content); err != nil || !bytes.Equal(actual, corrupt) {
		t.Fatal("Idempotent verification changed replacement bytes", err)
	}
}

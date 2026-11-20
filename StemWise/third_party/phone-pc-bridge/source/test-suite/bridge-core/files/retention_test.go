package files

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestRetentionRemovesExpiredOrphanBytes(t *testing.T) {
	root := filepath.Join(t.TempDir(), "store")
	store, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	now := time.Now()
	age := 3 * 24 * time.Hour
	oldID, _ := NewID()
	liveID, _ := NewID()
	for _, id := range []string{oldID, liveID} {
		f, _ := Describe(id, "context.bin", []byte(id))
		if _, err := store.Put("nPeer", f, bytes.NewBufferString(id)); err != nil {
			t.Fatal(err)
		}
	}
	oldTime := now.Add(-age - time.Second)
	if err := os.Chtimes(filepath.Join(root, "nPeer", oldID), oldTime, oldTime); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Info("nPeer", oldID); err != nil {
		t.Fatal(err)
	}
	// Even corrupted expired bytes are disposable; corruption must not grant infinite retention.
	if err := os.WriteFile(filepath.Join(root, "nPeer", oldID, "content"), []byte("changed"), 0600); err != nil {
		t.Fatal(err)
	}
	delay, err := store.PruneExpired(now, age)
	if err != nil || delay <= 0 || delay > age {
		t.Fatal(delay, err)
	}
	if _, err := os.Stat(filepath.Join(root, "nPeer", oldID)); !os.IsNotExist(err) {
		t.Fatal("Expired orphan still on disk", err)
	}
	if _, data, err := store.Read("nPeer", liveID); err != nil || string(data) != liveID {
		t.Fatal("Recent bytes removed or altered", err)
	}
}

func TestDeletionPreservesUnknownObjectMembers(t *testing.T) {
	for _, operation := range []string{"remove", "prune"} {
		for _, kind := range []string{"file", "directory"} {
			t.Run(operation+"_"+kind, func(t *testing.T) {
				root := filepath.Join(t.TempDir(), "store")
				store, err := Open(root)
				if err != nil {
					t.Fatal(err)
				}
				defer store.Close()
				id, _ := NewID()
				f, _ := Describe(id, "owned.bin", []byte("owned"))
				if _, err := store.Put("nOwned", f, strings.NewReader("owned")); err != nil {
					t.Fatal(err)
				}
				path := filepath.Join(root, "nOwned", id)
				unknown := filepath.Join(path, "unrecognized.txt")
				if kind == "directory" {
					unknown = filepath.Join(path, "unrecognized", "keep.txt")
					if err := os.Mkdir(filepath.Dir(unknown), 0700); err != nil {
						t.Fatal(err)
					}
				}
				if err := os.WriteFile(unknown, []byte("unrecognized data"), 0600); err != nil {
					t.Fatal(err)
				}
				now, age := time.Now(), 3*24*time.Hour
				old := now.Add(-age - time.Second)
				if err := os.Chtimes(path, old, old); err != nil {
					t.Fatal(err)
				}
				if operation == "remove" {
					err = store.Remove("nOwned", id)
					if !errors.Is(err, ErrCorrupt) {
						t.Fatal("Unknown object contents were not rejected", err)
					}
				} else {
					_, err = store.PruneExpired(now, age)
					if err != nil {
						t.Fatal("Damaged object blocked healthy retention", err)
					}
					if diagnostics, err := store.Diagnostics(""); err != nil || diagnostics.DiagnosticCount != 1 || diagnostics.Diagnostics[0].ID != id {
						t.Fatal("Unknown object contents lost diagnosis", diagnostics, err)
					}
				}
				if data, err := os.ReadFile(unknown); err != nil || string(data) != "unrecognized data" {
					t.Fatal("Unknown object contents were changed", err)
				}
				if _, _, err := store.Read("nOwned", id); !errors.Is(err, ErrCorrupt) {
					t.Fatal("Unknown members were offered as a usable object", err)
				}
				if data, err := os.ReadFile(filepath.Join(path, "content")); err != nil || string(data) != "owned" {
					t.Fatal("Rejected deletion changed the managed bytes", err)
				}
			})
		}
	}
}

func TestExpiredObjectPreservesLinkedContent(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Linux host checks symlinks without changing Windows policy")
	}
	parent := t.TempDir()
	root := filepath.Join(parent, "store")
	store, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	id, _ := NewID()
	f, _ := Describe(id, "owned.bin", []byte("owned"))
	if _, err := store.Put("nOwned", f, strings.NewReader("owned")); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(parent, "unrelated.txt")
	if err := os.WriteFile(target, []byte("preserve target"), 0600); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(root, "nOwned", id)
	content := filepath.Join(path, "content")
	if err := os.Remove(content); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(target, content); err != nil {
		t.Fatal(err)
	}
	now, age := time.Now(), 3*24*time.Hour
	old := now.Add(-age - time.Second)
	if err := os.Chtimes(path, old, old); err != nil {
		t.Fatal(err)
	}
	if _, err := store.PruneExpired(now, age); err != nil {
		t.Fatal("Expired linked object blocked unrelated work", err)
	}
	if diagnostics, err := store.Diagnostics(""); err != nil || diagnostics.DiagnosticCount != 1 {
		t.Fatal("Expired linked object lost diagnosis", diagnostics, err)
	}
	if info, err := os.Lstat(content); err != nil || info.Mode()&os.ModeSymlink == 0 {
		t.Fatal("Rejected expired link changed", err)
	}
	if data, err := os.ReadFile(target); err != nil || string(data) != "preserve target" {
		t.Fatal("Unrelated link target changed", err)
	}
}

func TestRetentionFailureDoesNotProtectOtherExpiredBytes(t *testing.T) {
	root := filepath.Join(t.TempDir(), "store")
	store, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	now := time.Now()
	age := 3 * 24 * time.Hour
	stage := filepath.Join(root, "nFirst", ".incoming-"+strings.Repeat("0", 32))
	if err := os.MkdirAll(stage, 0700); err != nil {
		t.Fatal(err)
	}
	unknown := filepath.Join(stage, "unexpected.txt")
	if err := os.WriteFile(unknown, []byte("preserve"), 0600); err != nil {
		t.Fatal(err)
	}
	for _, peer := range []string{"nFirst", "nSecond"} {
		id := strings.Repeat("f", 32)
		f, _ := Describe(id, "expired.bin", []byte(peer))
		if _, err := store.Put(peer, f, strings.NewReader(peer)); err != nil {
			t.Fatal(err)
		}
		old := now.Add(-age - time.Second)
		if err := os.Chtimes(filepath.Join(root, peer, id), old, old); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := store.PruneExpired(now, age); err != nil {
		t.Fatal("Corrupt stage blocked unrelated work", err)
	}
	if diagnostics, err := store.Diagnostics(""); err != nil || diagnostics.DiagnosticCount != 1 {
		t.Fatal("Corrupt stage was reported without diagnosis", diagnostics, err)
	}
	for _, peer := range []string{"nFirst", "nSecond"} {
		if _, err := os.Stat(filepath.Join(root, peer, strings.Repeat("f", 32))); !os.IsNotExist(err) {
			t.Fatal("One corrupt stage protected unrelated expired bytes", peer, err)
		}
	}
	if actual, err := os.ReadFile(unknown); err != nil || string(actual) != "preserve" {
		t.Fatal("Unknown data removed", err)
	}
}

package files

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestRemoveRetry(t *testing.T) {
	root := filepath.Join(t.TempDir(), "objects")
	store, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { store.Close() })
	id, err := NewID()
	if err != nil {
		t.Fatal(err)
	}
	info, err := Describe(id, "input.bin", []byte("actual input"))
	if err != nil {
		t.Fatal(err)
	}
	for _, peer := range []string{"nPC", "nOther"} {
		if _, err := store.Put(peer, info, bytes.NewReader([]byte("actual input"))); err != nil {
			t.Fatal(err)
		}
	}
	if err := store.Remove("nPC", id); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Lstat(filepath.Join(root, "nPC", id)); !os.IsNotExist(err) {
		t.Fatal("Removal left the managed object", err)
	}
	store.Close()
	store, err = Open(root)
	if err != nil {
		t.Fatal(err)
	}
	// Model a caller interrupted after byte removal but before its own row deletion.
	if err := store.Remove("nPC", id); err != nil {
		t.Fatal("Cleanup could not resume after removal and reopen", err)
	}
	if err := store.Remove("nNeverCreated", id); err != nil {
		t.Fatal("An absent peer/object cannot be reconciled", err)
	}
	if _, data, err := store.Read("nOther", id); err != nil || !bytes.Equal(data, []byte("actual input")) {
		t.Fatal("Cleanup affected another peer", err)
	}
	for _, args := range [][2]string{{"../outside", id}, {"nPC", "../outside"}, {"nPC", ""}} {
		if err := store.Remove(args[0], args[1]); err == nil {
			t.Fatal("Invalid cleanup identity accepted", args)
		}
	}
	if err := os.Remove(filepath.Join(root, "nOther", id, "content")); err != nil {
		t.Fatal(err)
	}
	if err := store.Remove("nOther", id); err == nil {
		t.Fatal("Incomplete existing object was treated as already removed")
	}
	if _, err := os.Stat(filepath.Join(root, "nOther", id, "metadata.json")); err != nil {
		t.Fatal("Rejected removal modified the incomplete object", err)
	}
	if err := os.WriteFile(filepath.Join(root, "nOther", id, "content"), []byte("forged"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := store.Remove("nOther", id); !errors.Is(err, ErrCorrupt) {
		t.Fatal("Corrupt bytes were silently discarded", err)
	}
	if err := os.WriteFile(filepath.Join(root, "nUnsafe"), nil, 0600); err != nil {
		t.Fatal(err)
	}
	if err := store.Remove("nUnsafe", id); err == nil {
		t.Fatal("A non-directory peer was treated as absent")
	}
	if runtime.GOOS != "windows" {
		if err := os.Symlink("nPC", filepath.Join(root, "nLinked")); err != nil {
			t.Fatal(err)
		}
		if err := store.Remove("nLinked", id); !errors.Is(err, ErrCorrupt) {
			t.Fatal("A linked peer was treated as absent", err)
		}
	}
	store.Close()
	if err := store.Remove("nPC", id); err == nil {
		t.Fatal("Closed store reported successful cleanup")
	}
}

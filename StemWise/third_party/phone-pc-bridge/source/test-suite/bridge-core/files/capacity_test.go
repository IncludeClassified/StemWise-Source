package files

import (
	"bytes"
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"
)

func TestStorageCapacityPreservesSourcesAndExistingReceipts(t *testing.T) {
	path := filepath.Join(t.TempDir(), "objects")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	data := []byte("original retained bytes")
	id, _ := NewID()
	known, _ := Describe(id, "retained.bin", data)
	if _, err := store.Put("nOwned", known, bytes.NewReader(data)); err != nil {
		t.Fatal(err)
	}
	before, err := os.Stat(filepath.Join(path, "nOwned", id, "metadata.json"))
	if err != nil {
		t.Fatal(err)
	}
	store.Close()
	store, err = OpenWithBudget(path, NewBudget(^uint64(0)))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	newID, _ := NewID()
	newFile, _ := Describe(newID, "new.bin", data)
	source := &io.LimitedReader{R: bytes.NewReader(data), N: int64(len(data))}
	if _, err := store.Put("nOwned", newFile, source); !errors.Is(err, ErrStorageCapacity) || source.N != int64(len(data)) {
		t.Fatal("Capacity refusal consumed source or lost its distinct error", err)
	}
	entries, err := os.ReadDir(filepath.Join(path, "nOwned"))
	if err != nil || len(entries) != 1 || entries[0].Name() != id {
		t.Fatal("Rejected upload retained staging or changed the original object", err)
	}
	if got, err := store.Put("nOwned", known, bytes.NewReader(data)); err != nil || got != known {
		t.Fatal("Low space prevented an unchanged existing receipt", err)
	}
	after, err := os.Stat(filepath.Join(path, "nOwned", id, "metadata.json"))
	if err != nil || !after.ModTime().Equal(before.ModTime()) {
		t.Fatal("Low-space retry renewed retained storage", err)
	}
	if store.budget.claimed != 0 || store.budget.claimedInodes != 0 {
		t.Fatal("Refusal or existing receipt leaked an in-flight reservation")
	}
}

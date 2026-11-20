package files

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestCommittedAtUsesReceiverDirectoryWithoutExpiryHashingOrRenewal(t *testing.T) {
	root := filepath.Join(t.TempDir(), "objects")
	store, err := OpenWithRetention(root, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	id, _ := NewID()
	content := []byte("immutable receiver bytes")
	f, _ := Describe(id, "request.json", content)
	if _, err := store.Put("nPeer", f, bytes.NewReader(content)); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(root, "nPeer", id)
	accepted := time.Now().Add(-2 * time.Hour).Truncate(time.Second)
	if err := os.Chtimes(path, accepted, accepted); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(path, "content"), bytes.Repeat([]byte("x"), len(content)), 0600); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		got, err := store.CommittedAt("nPeer", id)
		if err != nil || !got.Equal(accepted) {
			t.Fatal("Receiver acceptance expired, rehashed or changed", got, err)
		}
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatal("Timestamp observation expired bytes", err)
	}
	if err := os.WriteFile(filepath.Join(path, "metadata.json"), []byte("broken metadata"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := store.CommittedAt("nPeer", id); err == nil {
		t.Fatal("Malformed object supplied an authoritative acceptance time")
	}
	if _, err := store.CommittedAt("../foreign", id); err == nil {
		t.Fatal("Timestamp observation accepted a foreign path")
	}
}

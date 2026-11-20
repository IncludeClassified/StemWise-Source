package files

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestPeerRetentionProtectsReferencesWithoutExtendingOtherObjects(t *testing.T) {
	root := filepath.Join(t.TempDir(), "objects")
	store, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	now := time.Now()
	age := 72 * time.Hour
	keep, orphan, other := strings.Repeat("a", 32), strings.Repeat("b", 32), strings.Repeat("c", 32)
	for peer, ids := range map[string][]string{"nAlice": {keep, orphan}, "nBob": {other}} {
		for _, id := range ids {
			f, _ := Describe(id, "owned.bin", []byte(id))
			if _, err := store.Put(peer, f, strings.NewReader(id)); err != nil {
				t.Fatal(err)
			}
			old := now.Add(-age - time.Second)
			if err := os.Chtimes(filepath.Join(root, peer, id), old, old); err != nil {
				t.Fatal(err)
			}
		}
	}
	if next, err := store.PrunePeerExpired(now, age, "nAlice", map[string]bool{keep: true}); err != nil || next <= 0 {
		t.Fatal("Protected old object caused failed or immediate repeat scan", next, err)
	}
	for peer, id := range map[string]string{"nAlice": keep, "nBob": other} {
		if _, content, err := store.Read(peer, id); err != nil || string(content) != id {
			t.Fatal("Scoped sweep destroyed protected or unrelated object", err)
		}
	}
	if _, err := store.Info("nAlice", orphan); !os.IsNotExist(err) {
		t.Fatal("Unreferenced aged object retained", err)
	}
	if _, err := store.PrunePeerExpired(now, age, "nAlice", nil); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Info("nAlice", keep); !os.IsNotExist(err) {
		t.Fatal("Released reference acquired a fresh retention lifetime", err)
	}
	if _, err := store.PrunePeerExpired(now, age, "../outside", nil); err == nil {
		t.Fatal("Scoped sweep accepted caller path")
	}
}

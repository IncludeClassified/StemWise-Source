package pc

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"

	"phonepcbridge/files"
)

func TestExplicitOwnedDiscardRemovesDamageWithoutTouchingNeighbors(t *testing.T) {
	for _, collection := range []string{"queued", "received"} {
		for _, damage := range []string{"metadata", "size", "unknown_member"} {
			t.Run(collection+"/"+damage, func(t *testing.T) {
				root := t.TempDir()
				store, err := files.Open(filepath.Join(root, "objects"))
				if err != nil {
					t.Fatal(err)
				}
				defer store.Close()
				s := &Service{peers: map[string]*peer{"nPi": {identity: "nPi"}}, inbox: store, outbox: store}
				id, neighbor := randomToken(), randomToken()
				data := []byte("owned immutable bytes")
				for _, owner := range []struct{ peer, id string }{{"nPi", id}, {"nOther", id}, {"nPi", neighbor}} {
					f, _ := files.Describe(owner.id, "input.bin", data)
					if _, err := store.Put(owner.peer, f, bytes.NewReader(data)); err != nil {
						t.Fatal(err)
					}
				}
				path := filepath.Join(root, "objects", "nPi", id)
				var file string
				switch damage {
				case "metadata":
					file = "metadata.json"
				case "size":
					file = "content"
				case "unknown_member":
					file = "unexpected.txt"
				}
				if err := os.WriteFile(filepath.Join(path, file), []byte("damage"), 0600); err != nil {
					t.Fatal(err)
				}
				remove, discard := s.RemoveQueuedFile, s.DiscardQueuedFile
				if collection == "received" {
					remove, discard = s.RemoveReceivedFile, s.DiscardReceivedFile
				}
				if err := remove("nPi", id); err == nil {
					t.Fatal("Automatic cleanup discarded a damaged object")
				}
				if err := discard("unpaired", id); err == nil {
					t.Fatal("Unpaired discard accepted")
				}
				if raw, err := os.ReadFile(filepath.Join(path, file)); err != nil || string(raw) != "damage" {
					t.Fatal("Rejected cleanup altered damaged evidence", err)
				}
				if err := discard("nPi", id); err != nil {
					t.Fatal("Explicit exact discard could not remove damage", err)
				}
				if _, err := os.Stat(path); !os.IsNotExist(err) {
					t.Fatal("Discarded object still exists", err)
				}
				for _, owner := range []struct{ peer, id string }{{"nOther", id}, {"nPi", neighbor}} {
					_, raw, err := store.Read(owner.peer, owner.id)
					if err != nil || !bytes.Equal(raw, data) {
						t.Fatal("Discard touched a different peer or object", owner, err)
					}
				}
			})
		}
	}
}

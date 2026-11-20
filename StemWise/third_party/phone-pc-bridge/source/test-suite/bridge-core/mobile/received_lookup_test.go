package mobile

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"

	"phonepcbridge/files"
)

func TestReceivedInfoDistinguishesExactAbsenceFromInvalidReceipts(t *testing.T) {
	for _, state := range []string{"absent", "verified", "incomplete", "corrupt_metadata", "changed_bytes", "closed"} {
		t.Run(state, func(t *testing.T) {
			root := t.TempDir()
			c, f := mobileFileFixture(t, root)
			stopFileRetentionWorker(t, c)
			id, err := files.NewID()
			if err != nil {
				t.Fatal(err)
			}
			if state != "absent" {
				descriptor := managedMobileFile(t, f.inbox)
				id = descriptor.ID
				path := filepath.Join(root, "bridge-incoming", "nPc", id)
				switch state {
				case "incomplete":
					err = os.Remove(filepath.Join(path, "metadata.json"))
				case "corrupt_metadata":
					err = os.WriteFile(filepath.Join(path, "metadata.json"), []byte("malformed"), 0600)
				case "changed_bytes":
					data, readErr := os.ReadFile(filepath.Join(path, "content"))
					if readErr != nil {
						t.Fatal(readErr)
					}
					data[0] ^= 1
					err = os.WriteFile(filepath.Join(path, "content"), data, 0600)
				case "closed":
					err = f.inbox.Close()
				}
				if err != nil {
					t.Fatal(err)
				}
			}
			f.exchange = func(context.Context, string, string, string, []byte) (string, error) {
				t.Fatal("Local exact receipt lookup contacted the remote host")
				return "", nil
			}
			raw, err := f.ReceivedInfo("exact-receipt", id)
			if state != "absent" && state != "verified" {
				if err == nil || raw != "" {
					t.Fatal("Invalid/unreadable receipt became safe download permission", state, raw, err)
				}
				return
			}
			var reply map[string]json.RawMessage
			if err != nil || json.Unmarshal([]byte(raw), &reply) != nil || string(reply["pcIdentity"]) != `"nPc"` {
				t.Fatal("Exact lookup did not return a correlated local result", raw, err)
			}
			if state == "absent" {
				if string(reply["state"]) != `"absent"` || string(reply["transferId"]) != `"`+id+`"` || reply["file"] != nil {
					t.Fatal("Absence result differs from the exact lookup contract", raw)
				}
			} else if string(reply["state"]) != `"received_local_verified"` || reply["file"] == nil {
				t.Fatal("Valid local byte verification changed", raw)
			}
			if c.started || len(c.active) != 0 {
				t.Fatal("Lookup started transport or leaked an operation")
			}
		})
	}
}

func TestReceivedInfoDoesNotPageThroughUnrelatedReceiptInventory(t *testing.T) {
	root := t.TempDir()
	c, f := mobileFileFixture(t, root)
	stopFileRetentionWorker(t, c)
	descriptor := managedMobileFile(t, f.inbox)
	peer := filepath.Join(root, "bridge-incoming", "nPc")
	// An inventory-based caller would cross its former 128-page/12,800-entry
	// boundary here. The requested healthy object must not inspect these neighbors.
	for index := 0; index < 12801; index++ {
		if err := os.Mkdir(filepath.Join(peer, fmt.Sprintf("%032x", index)), 0700); err != nil {
			t.Fatal(err)
		}
	}
	if raw, err := f.ReceivedInfo("exact-beyond-inventory", descriptor.ID); err != nil || !json.Valid([]byte(raw)) {
		t.Fatal("Known receipt depended on unrelated inventory", raw, err)
	}
	missing, _ := files.NewID()
	raw, err := f.ReceivedInfo("exact-missing", missing)
	var lookup struct{ State, TransferID string }
	if err != nil || json.Unmarshal([]byte(raw), &lookup) != nil || lookup.State != "absent" || lookup.TransferID != missing {
		t.Fatal("Unrelated damaged receipts changed exact absence", raw, err)
	}
	if c.started {
		t.Fatal("Local exact lookup started private networking")
	}
}

package mobile

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"phonepcbridge/protocol"
)

func TestMobileDamagedObjectsDoNotBlockHealthyFilesAndAllowOwnedDiscard(t *testing.T) {
	state := t.TempDir()
	c, f := mobileFileFixture(t, state)
	stopFileRetentionWorker(t, c)
	healthy := managedMobileFile(t, f.outbox)
	badOut := managedMobileFile(t, f.outbox)
	badIn := managedMobileFile(t, f.inbox)
	for kind, descriptor := range map[string]protocol.File{"bridge-outgoing": badOut, "bridge-incoming": badIn} {
		path := filepath.Join(state, kind, "nPc", descriptor.ID)
		if err := os.WriteFile(filepath.Join(path, "metadata.json"), []byte("damaged bytes"), 0600); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(path, "preserve.txt"), []byte("for investigation"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	pending, err := f.Pending("pending", "", 100)
	if err != nil {
		t.Fatal("Corrupt neighbor blocked healthy pending object", err)
	}
	var page protocol.FilePage
	if json.Unmarshal([]byte(pending), &page) != nil || len(page.Files) != 1 || page.Files[0] != healthy || page.DiagnosticCount != 1 || len(page.Diagnostics) != 1 || page.Diagnostics[0].ID != badOut.ID {
		t.Fatal("Healthy page/diagnostics differ", pending)
	}
	received, err := f.Received("received", "", 100)
	if err != nil || !strings.Contains(received, badIn.ID) {
		t.Fatal("Corrupt incoming object was not separately diagnosed", err, received)
	}
	if _, err := c.pruneFileStores(c.fileIncoming, c.fileOutgoing, time.Now()); err != nil {
		t.Fatal("Actual maintenance did not observe isolated damage", err)
	}
	raw, err := f.StorageDiagnostics("diagnostics")
	if err != nil {
		t.Fatal(err)
	}
	var diagnostics struct{ Incoming, Outgoing protocol.FilePage }
	if json.Unmarshal([]byte(raw), &diagnostics) != nil || diagnostics.Incoming.DiagnosticCount != 1 || diagnostics.Outgoing.DiagnosticCount != 1 {
		t.Fatal("Exact stores were not diagnosed", raw)
	}
	for kind, descriptor := range map[string]protocol.File{"bridge-outgoing": badOut, "bridge-incoming": badIn} {
		data, err := os.ReadFile(filepath.Join(state, kind, "nPc", descriptor.ID, "preserve.txt"))
		if err != nil || string(data) != "for investigation" {
			t.Fatal("Diagnostic scan changed damaged object", err)
		}
	}
	if err := f.DiscardPrepared(badOut.ID); err != nil {
		t.Fatal("Explicit discard required valid old hash", err)
	}
	if err := f.RemoveReceived(badIn.ID); err != nil {
		t.Fatal("Explicit received discard required valid old hash", err)
	}
	for kind, descriptor := range map[string]protocol.File{"bridge-outgoing": badOut, "bridge-incoming": badIn} {
		if _, err := os.Lstat(filepath.Join(state, kind, "nPc", descriptor.ID)); !os.IsNotExist(err) {
			t.Fatal("Explicitly discarded object remains", err)
		}
	}
	if actual, _, err := f.outbox.Read("nPc", healthy.ID); err != nil || actual != healthy {
		t.Fatal("Discard changed unrelated healthy object", err)
	}
	if err := f.DiscardPrepared("../escape"); err == nil {
		t.Fatal("Discard accepted unowned path")
	}
}

func TestCopyReceivedChecksDigestDuringCopyAndRemovesChangedDestination(t *testing.T) {
	state := t.TempDir()
	_, f := mobileFileFixture(t, state)
	descriptor := managedMobileFile(t, f.inbox)
	path := filepath.Join(state, "bridge-incoming", "nPc", descriptor.ID, "content")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	data[0] ^= 0x01
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	destination := filepath.Join(t.TempDir(), "import.bin")
	if err := f.CopyReceived("copy", descriptor.ID, destination); err == nil {
		t.Fatal("Changed streamed bytes were imported")
	}
	if _, err := os.Lstat(destination); !os.IsNotExist(err) {
		t.Fatal("Unverified destination remains", err)
	}
	if _, err := f.ReceivedInfo("info", descriptor.ID); err == nil {
		t.Fatal("Metadata became a fresh verification claim")
	}
	if actual, err := os.ReadFile(path); err != nil || string(actual) != string(data) {
		t.Fatal("Rejected copy changed original bytes", err)
	}
}

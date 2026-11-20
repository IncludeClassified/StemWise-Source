package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/pc"
	"phonepcbridge/protocol"
)

func stopFileRetentionWorker(t *testing.T, c *Client) {
	t.Helper()
	close(c.fileRetentionStop)
	select {
	case <-c.fileRetentionDone:
	case <-time.After(2 * time.Second):
		t.Fatal("Retention worker did not stop")
	}
	c.fileRetentionStop, c.fileRetentionDone = nil, nil
}

func TestMobileExactExpiredAccessRejectsWithoutNetworkAndListsOmitExpiry(t *testing.T) {
	for _, operation := range []string{"receipt", "ensure_sent", "send", "read", "info", "copy", "pending", "received"} {
		t.Run(operation, func(t *testing.T) {
			state := t.TempDir()
			c, f := mobileFileFixture(t, state)
			stopFileRetentionWorker(t, c)
			store, kind := f.inbox, "bridge-incoming"
			if operation == "receipt" || operation == "ensure_sent" || operation == "send" || operation == "pending" {
				store, kind = f.outbox, "bridge-outgoing"
			}
			descriptor := managedMobileFile(t, store)
			path := filepath.Join(state, kind, "nPc", descriptor.ID)
			old := time.Now().Add(-3*24*time.Hour - time.Second)
			if err := os.Chtimes(path, old, old); err != nil {
				t.Fatal(err)
			}
			if _, err := f.StorageDiagnostics("diagnostics"); err != nil {
				t.Fatal(err)
			}
			if _, err := os.Stat(path); err != nil {
				t.Fatal("Read-only diagnostics pruned expired cache", err)
			}
			f.exchange = func(context.Context, string, string, string, []byte) (string, error) {
				t.Fatal("Expired object reached remote operation")
				return "", nil
			}
			f.stream = func(context.Context, string, string, string, io.Reader) (int, io.ReadCloser, string, error) {
				t.Fatal("Expired object reached stream")
				return 0, nil, "", nil
			}
			var raw string
			var err error
			destination := filepath.Join(t.TempDir(), "import.bin")
			switch operation {
			case "receipt":
				raw, err = f.Receipt("request", descriptor.ID)
			case "ensure_sent":
				raw, err = f.EnsureSent("request", descriptor.ID)
			case "send":
				raw, err = f.Send("request", descriptor.ID)
			case "read":
				_, err = f.ReadReceived("request", descriptor.ID)
			case "info":
				raw, err = f.ReceivedInfo("request", descriptor.ID)
			case "copy":
				err = f.CopyReceived("request", descriptor.ID, destination)
			case "pending":
				raw, err = f.Pending("request", "", 100)
			case "received":
				raw, err = f.Received("request", "", 100)
			}
			if operation == "pending" || operation == "received" {
				var page protocol.FilePage
				if err != nil || json.Unmarshal([]byte(raw), &page) != nil || len(page.Files) != 0 || page.DiagnosticCount != 0 {
					t.Fatal("Normal expiry became visible file or corruption", raw, err)
				}
			} else if operation == "info" {
				var lookup struct{ State, TransferID string }
				if err != nil || json.Unmarshal([]byte(raw), &lookup) != nil || lookup.State != "absent" || lookup.TransferID != descriptor.ID {
					t.Fatal("Expired exact receipt did not return structured absence", raw, err)
				}
			} else if !errors.Is(err, files.ErrAbsent) {
				t.Fatal("Expired object remained usable", err)
			}
			if _, err := os.Stat(path); !os.IsNotExist(err) {
				t.Fatal("Exact expired object remained after requested access", err)
			}
			if _, err := os.Stat(destination); !os.IsNotExist(err) {
				t.Fatal("Expired copy created destination", err)
			}
			if c.started || len(c.active) != 0 {
				t.Fatal("Expired access started network or leaked operation")
			}
		})
	}
}

func TestMobileUnrelatedClosedStoreDoesNotBlockHealthyObject(t *testing.T) {
	for _, broken := range []string{"incoming", "outgoing"} {
		t.Run(broken, func(t *testing.T) {
			c, f := mobileFileFixture(t, t.TempDir())
			stopFileRetentionWorker(t, c)
			incoming, outgoing := managedMobileFile(t, f.inbox), managedMobileFile(t, f.outbox)
			if broken == "incoming" {
				if err := f.inbox.Close(); err != nil {
					t.Fatal(err)
				}
			} else {
				if err := f.outbox.Close(); err != nil {
					t.Fatal(err)
				}
			}
			if _, err := c.pruneFileStores(c.fileIncoming, c.fileOutgoing, time.Now()); err == nil {
				t.Fatal("Actual sweep did not detect closed store")
			}
			failure := c.fileRetentionStatus.Snapshot()
			if broken == "incoming" {
				if _, err := f.Prepare("prepare", "new.bin", []byte("new owned bytes")); err != nil {
					t.Fatal("Broken inbox blocked healthy prepare", err)
				}
				if raw, err := f.Pending("pending", "", 100); err != nil || !strings.Contains(raw, outgoing.ID) {
					t.Fatal("Broken inbox blocked healthy pending", raw, err)
				}
			} else {
				if _, err := f.ReceivedInfo("info", incoming.ID); err != nil {
					t.Fatal("Broken outbox blocked healthy receipt", err)
				}
				if data, err := f.ReadReceived("read", incoming.ID); err != nil || string(data) != incoming.ID {
					t.Fatal("Broken outbox blocked healthy bytes", err)
				}
				if err := f.CopyReceived("copy", incoming.ID, filepath.Join(t.TempDir(), "import.bin")); err != nil {
					t.Fatal("Broken outbox blocked healthy import", err)
				}
			}
			if c.fileRetentionStatus.Snapshot() != failure || !failure.FailureActive || c.started {
				t.Fatal("Healthy object access masked failed cleanup or started networking")
			}
		})
	}
}

func TestMobileCancelledOrMalformedExpiredAccessPreservesOwnedBytes(t *testing.T) {
	for _, malformed := range []bool{false, true} {
		t.Run(map[bool]string{false: "cancelled", true: "unknown_member"}[malformed], func(t *testing.T) {
			state := t.TempDir()
			c, f := mobileFileFixture(t, state)
			stopFileRetentionWorker(t, c)
			descriptor := managedMobileFile(t, f.inbox)
			path := filepath.Join(state, "bridge-incoming", "nPc", descriptor.ID)
			if malformed {
				if err := os.WriteFile(filepath.Join(path, "investigate.txt"), []byte("preserve"), 0600); err != nil {
					t.Fatal(err)
				}
			}
			old := time.Now().Add(-3*24*time.Hour - time.Second)
			if err := os.Chtimes(path, old, old); err != nil {
				t.Fatal(err)
			}
			operation, err := c.NewOperation()
			if err != nil {
				t.Fatal(err)
			}
			if !malformed {
				operation.Cancel()
			}
			if _, err := f.ReceivedInfo(operation.Token(), descriptor.ID); err == nil {
				t.Fatal("Cancelled/unknown object access succeeded")
			}
			operation.Close()
			if data, err := os.ReadFile(filepath.Join(path, "content")); err != nil || string(data) != descriptor.ID {
				t.Fatal("Rejected expired access changed prior bytes", err)
			}
			if malformed {
				if data, err := os.ReadFile(filepath.Join(path, "investigate.txt")); err != nil || string(data) != "preserve" {
					t.Fatal("Unknown member deleted", err)
				}
			}
		})
	}
}

func TestRetainedOutputCanBeImportedAfterDisposableCacheExpires(t *testing.T) {
	for _, expiry := range []string{"access", "sweep"} {
		t.Run(expiry, func(t *testing.T) {
			state := t.TempDir()
			c, f := mobileFileFixture(t, state)
			stopFileRetentionWorker(t, c)
			descriptor := managedMobileFile(t, f.inbox)
			cached := filepath.Join(state, "bridge-incoming", "nPc", descriptor.ID)
			root := t.TempDir()
			remoteIn, err := files.Open(filepath.Join(root, "in"))
			if err != nil {
				t.Fatal(err)
			}
			defer remoteIn.Close()
			remoteOut, err := files.Open(filepath.Join(root, "out"))
			if err != nil {
				t.Fatal(err)
			}
			defer remoteOut.Close()
			if _, err := remoteOut.Put("nApp", descriptor, strings.NewReader(descriptor.ID)); err != nil {
				t.Fatal(err)
			}
			old := time.Now().Add(-protocol.Retention - time.Hour)
			for _, path := range []string{cached, filepath.Join(root, "out", "nApp", descriptor.ID)} {
				if err := os.Chtimes(path, old, old); err != nil {
					t.Fatal(err)
				}
			}
			// A reference-aware host retains the published source; its default
			// store must not apply the Android cache's age check on later reads.
			if _, err := remoteOut.PrunePeerExpired(time.Now(), protocol.Retention, "nApp", map[string]bool{descriptor.ID: true}); err != nil {
				t.Fatal(err)
			}
			if expiry == "access" {
				raw, err := f.ReceivedInfo("old-cache", descriptor.ID)
				var lookup struct{ State, TransferID string }
				if err != nil || json.Unmarshal([]byte(raw), &lookup) != nil || lookup.State != "absent" || lookup.TransferID != descriptor.ID {
					t.Fatal("Expired local cache remained a usable receipt", raw, err)
				}
			} else if _, err := c.pruneFileStores(c.fileIncoming, c.fileOutgoing, time.Now()); err != nil {
				t.Fatal(err)
			}
			if _, err := os.Stat(cached); !os.IsNotExist(err) {
				t.Fatal("Disposable cache did not actually expire", err)
			}
			var requests, mutations atomic.Int32
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				requests.Add(1)
				if r.Method != "GET" || !strings.Contains(r.URL.Path, "/"+descriptor.ID+"/") {
					mutations.Add(1)
					w.WriteHeader(http.StatusBadRequest)
					return
				}
				pc.ServeAppFiles(w, r, "nApp", "nPc", remoteIn, remoteOut, []protocol.File{descriptor})
			}))
			defer server.Close()
			attachFileHTTP(t, f, server, "nApp")
			raw, err := f.ReceiveMatching("receive-original", descriptor.ID, descriptor.Name, descriptor.Size, descriptor.SHA256)
			var receipt struct {
				File protocol.File `json:"file"`
			}
			if err != nil || json.Unmarshal([]byte(raw), &receipt) != nil || receipt.File != descriptor || requests.Load() != 2 || mutations.Load() != 0 {
				t.Fatal("Cache recovery changed the original descriptor, replayed work or failed to verify bytes", raw, err, requests.Load())
			}
			if data, err := f.ReadReceived("read", descriptor.ID); err != nil || string(data) != descriptor.ID {
				t.Fatal("Fresh same-ID receipt differs from retained source", err)
			}
			destination := filepath.Join(t.TempDir(), "app-import.bin")
			if err := f.CopyReceived("import", descriptor.ID, destination); err != nil {
				t.Fatal(err)
			}
			if err := os.Chtimes(cached, old, old); err != nil {
				t.Fatal(err)
			}
			if _, err := c.pruneFileStores(c.fileIncoming, c.fileOutgoing, time.Now()); err != nil {
				t.Fatal(err)
			}
			if _, err := os.Stat(cached); !os.IsNotExist(err) {
				t.Fatal("Imported app file incorrectly kept disposable cache alive", err)
			}
			imports, _ := json.Marshal([]deliveryImport{{File: descriptor, Path: destination}})
			if _, err := c.verifiedDelivery(context.Background(), string(imports), func(verified []protocol.File) (string, error) {
				if len(verified) != 1 || verified[0] != descriptor {
					t.Fatal("Imported batch changed after cache expiry", verified)
				}
				return "", nil
			}); err != nil {
				t.Fatal("Cache expiry invalidated app-owned imported bytes", err)
			}
			if actual, data, err := remoteOut.Read("nApp", descriptor.ID); err != nil || actual != descriptor || string(data) != descriptor.ID || requests.Load() != 2 || mutations.Load() != 0 || c.started {
				t.Fatal("Cache cleanup removed the authoritative output or triggered unrelated work", err)
			}
		})
	}
}

func TestMobileFileBatchDoesNotSweepUnrelatedCache(t *testing.T) {
	state := t.TempDir()
	c, f := mobileFileFixture(t, state)
	stopFileRetentionWorker(t, c)
	id, err := files.NewID()
	if err != nil {
		t.Fatal(err)
	}
	descriptor, err := files.Describe(id, "unrelated.bin", []byte("unrelated bytes"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.outbox.Put("nOther", descriptor, strings.NewReader("unrelated bytes")); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(state, "bridge-outgoing", "nOther", id)
	old := time.Now().Add(-3*24*time.Hour - time.Second)
	if err := os.Chtimes(path, old, old); err != nil {
		t.Fatal(err)
	}
	input := managedMobileFile(t, f.inbox)
	for n := 0; n < 3; n++ {
		if _, err := f.Prepare("prepare", "batch.bin", []byte("batch source")); err != nil {
			t.Fatal(err)
		}
		if _, err := f.Pending("pending", "", 100); err != nil {
			t.Fatal(err)
		}
		if _, err := f.ReceivedInfo("info", input.ID); err != nil {
			t.Fatal(err)
		}
		if _, err := f.ReadReceived("read", input.ID); err != nil {
			t.Fatal(err)
		}
		if err := f.CopyReceived("copy", input.ID, filepath.Join(t.TempDir(), "copied.bin")); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatal("Per-file batch performed an unrelated full-store sweep", err)
	}
	if _, err := c.pruneFileStores(c.fileIncoming, c.fileOutgoing, time.Now()); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("Actual cleanup pass did not recover unrelated expired capacity", err)
	}
	raw, err := f.StorageDiagnostics("diagnostics")
	if err != nil {
		t.Fatal(err)
	}
	var view struct {
		BackgroundOperations map[string]protocol.BackgroundPassStatus `json:"backgroundOperations"`
	}
	if json.Unmarshal([]byte(raw), &view) != nil || view.BackgroundOperations["file_retention"].LastSuccessAt <= 0 || view.BackgroundOperations["file_retention"].FailureActive || c.started {
		t.Fatal("Actual sweep recovery was not observable offline", raw)
	}
}

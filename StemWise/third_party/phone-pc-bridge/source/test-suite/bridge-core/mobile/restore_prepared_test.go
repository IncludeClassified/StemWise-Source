package mobile

import (
	"bytes"
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
	"phonepcbridge/internal/checksync"
	"phonepcbridge/pc"
	"phonepcbridge/protocol"
)

func restoreSource(t *testing.T) (string, protocol.File, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "retained-source.bin")
	content := []byte("original immutable retained app context")
	if err := os.WriteFile(path, content, 0600); err != nil {
		t.Fatal(err)
	}
	descriptor, err := files.Describe(strings.Repeat("a", 32), "reviewed input.zip", content)
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := fileJSON(descriptor)
	return path, descriptor, raw
}

func restoreOnce(t *testing.T, c *Client, f *FileClient, descriptorJSON, path string) (string, error) {
	t.Helper()
	op, err := c.NewOperation()
	if err != nil {
		t.Fatal(err)
	}
	defer op.Close()
	return f.RestorePreparedFile(op.Token(), descriptorJSON, path)
}

func TestRestorePreparedFileRecoversExpiredLocalDescriptorWithoutRemoteRefresh(t *testing.T) {
	path, descriptor, raw := restoreSource(t)
	state := filepath.Join(t.TempDir(), "node")
	c, f := mobileFileFixture(t, state)
	file, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	_, err = f.outbox.Put("nPc", descriptor, file)
	file.Close()
	if err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-protocol.Retention - time.Second)
	if err := os.Chtimes(filepath.Join(state, "bridge-outgoing", "nPc", descriptor.ID), old, old); err != nil {
		t.Fatal(err)
	}
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	c, f = mobileFileFixture(t, state)
	f.exchange = func(context.Context, string, string, string, []byte) (string, error) {
		t.Error("Local recovery contacted a remote host")
		return "", errors.New("unexpected remote call")
	}
	if _, err := f.EnsureSent("expired-before-restore", descriptor.ID); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Expired descriptor unexpectedly usable before restoration", err)
	}
	restored, err := restoreOnce(t, c, f, raw, path)
	if err != nil || restored != raw || c.started {
		t.Fatal("Offline restoration changed descriptor or started transport", restored, err)
	}
	remoteIn, err := files.OpenWithRetention(filepath.Join(t.TempDir(), "remote-in"), protocol.Retention)
	if err != nil {
		t.Fatal(err)
	}
	defer remoteIn.Close()
	remoteOut, err := files.OpenWithRetention(filepath.Join(t.TempDir(), "remote-out"), protocol.Retention)
	if err != nil {
		t.Fatal(err)
	}
	defer remoteOut.Close()
	var posts atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "POST" {
			posts.Add(1)
		}
		pc.ServeFiles(w, r, "nApp", "nPc", remoteIn, remoteOut)
	}))
	defer server.Close()
	attachFileHTTP(t, f, server, "nApp")
	if _, err := f.EnsureSent("first-original-upload", descriptor.ID); err != nil {
		t.Fatal(err)
	}
	committed, err := remoteIn.CommittedAt("nApp", descriptor.ID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := restoreOnce(t, c, f, raw, path); err != nil {
		t.Fatal(err)
	}
	if _, err := f.EnsureSent("original-receipt-only", descriptor.ID); err != nil {
		t.Fatal(err)
	}
	unchanged, err := remoteIn.CommittedAt("nApp", descriptor.ID)
	actual, content, readErr := remoteIn.Read("nApp", descriptor.ID)
	source, sourceErr := os.ReadFile(path)
	if err != nil || readErr != nil || sourceErr != nil || posts.Load() != 1 || committed != unchanged || actual != descriptor || !bytes.Equal(content, source) || c.started {
		t.Fatal("Restoration changed remote deadline/identity, replayed upload or lost retained source", posts.Load(), err, readErr, sourceErr)
	}
}

func TestRestorePreparedFileRefusesMismatchConflictAndCorruption(t *testing.T) {
	for _, variant := range []string{"wrong_bytes", "wrong_bytes_existing", "conflict", "corrupt_existing"} {
		t.Run(variant, func(t *testing.T) {
			c, f := cancellationClient(t)
			path, descriptor, raw := restoreSource(t)
			if variant != "wrong_bytes" {
				if _, err := restoreOnce(t, c, f, raw, path); err != nil {
					t.Fatal(err)
				}
			}
			want := files.ErrContentMismatch
			switch variant {
			case "wrong_bytes", "wrong_bytes_existing":
				if err := os.WriteFile(path, bytes.Repeat([]byte("x"), int(descriptor.Size)), 0600); err != nil {
					t.Fatal(err)
				}
			case "conflict":
				descriptor.Name = "different reviewed name.zip"
				raw, _ = fileJSON(descriptor)
				want = files.ErrConflict
			case "corrupt_existing":
				if err := os.WriteFile(filepath.Join(c.node.Dir, "bridge-outgoing", "nPi", descriptor.ID, "content"), []byte("corrupt"), 0600); err != nil {
					t.Fatal(err)
				}
				want = files.ErrCorrupt
			}
			if result, err := restoreOnce(t, c, f, raw, path); result != "" || !errors.Is(err, want) {
				t.Fatal("Invalid restore changed or repaired an immutable snapshot", result, err)
			}
			if variant == "wrong_bytes" {
				if _, err := f.outbox.Info("nPi", descriptor.ID); !errors.Is(err, files.ErrAbsent) {
					t.Fatal("Wrong source committed an object", err)
				}
			} else if variant != "corrupt_existing" {
				stored, content, err := f.outbox.Read("nPi", descriptor.ID)
				if err != nil || stored.Name != "reviewed input.zip" || string(content) != "original immutable retained app context" {
					t.Fatal("Conflicting restore overwrote earlier snapshot", err)
				}
			}
		})
	}
}

func TestRestorePreparedFileIdempotentCallKeepsOriginalLocalDeadline(t *testing.T) {
	c, f := cancellationClient(t)
	path, descriptor, raw := restoreSource(t)
	if _, err := restoreOnce(t, c, f, raw, path); err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-time.Hour)
	if err := os.Chtimes(filepath.Join(c.node.Dir, "bridge-outgoing", "nPi", descriptor.ID), old, old); err != nil {
		t.Fatal(err)
	}
	before, err := f.outbox.CommittedAt("nPi", descriptor.ID)
	if err != nil {
		t.Fatal(err)
	}
	if result, err := restoreOnce(t, c, f, raw, path); err != nil || result != raw {
		t.Fatal("Identical restore did not remain idempotent", result, err)
	}
	after, err := f.outbox.CommittedAt("nPi", descriptor.ID)
	if err != nil || before != after {
		t.Fatal("Idempotent restore renewed cache retention", before, after, err)
	}
}

func TestRestorePreparedFileRejectsInvalidDescriptorAndSource(t *testing.T) {
	for _, variant := range []string{"relative", "directory", "managed", "missing", "unknown_field", "duplicate_id", "missing_field", "invalid_id", "invalid_name", "invalid_hash", "negative_size", "oversize", "malformed", "huge_json", "invalid_utf8"} {
		t.Run(variant, func(t *testing.T) {
			c, f := cancellationClient(t)
			path, descriptor, raw := restoreSource(t)
			switch variant {
			case "relative":
				path = "relative.bin"
			case "directory":
				path = filepath.Dir(path)
			case "managed":
				path = filepath.Join(c.node.Dir, "private-source.bin")
				if err := os.WriteFile(path, []byte("original immutable retained app context"), 0600); err != nil {
					t.Fatal(err)
				}
			case "missing":
				path += "-missing"
			case "unknown_field":
				raw = strings.TrimSuffix(raw, "}") + `,"extra":true}`
			case "duplicate_id":
				raw = strings.TrimSuffix(raw, "}") + `,"id":"` + descriptor.ID + `"}`
			case "missing_field":
				var fields map[string]any
				_ = json.Unmarshal([]byte(raw), &fields)
				delete(fields, "size")
				data, _ := json.Marshal(fields)
				raw = string(data)
			case "invalid_id":
				descriptor.ID = "not-an-id"
			case "invalid_name":
				descriptor.Name = "../name"
			case "invalid_hash":
				descriptor.SHA256 = strings.Repeat("A", 64)
			case "negative_size":
				descriptor.Size = -1
			case "oversize":
				descriptor.Size = protocol.MaxFileBytes + 1
			case "malformed":
				raw = "null"
			case "huge_json":
				raw = strings.Repeat(" ", 4097) + raw
			case "invalid_utf8":
				raw = string([]byte{0xff}) + raw
			}
			if strings.HasPrefix(variant, "invalid_") && variant != "invalid_utf8" || variant == "negative_size" || variant == "oversize" {
				raw, _ = fileJSON(descriptor)
			}
			if result, err := restoreOnce(t, c, f, raw, path); err == nil || result != "" {
				t.Fatal("Invalid source or descriptor restored", result, err)
			}
			page, err := f.outbox.List("nPi", "", 100)
			if err != nil || len(page.Files) != 0 || page.DiagnosticCount != 0 || c.started {
				t.Fatal("Invalid restore mutated cache or transport", err)
			}
		})
	}
}

func TestRestorePreparedFileRejectsSymlinkSources(t *testing.T) {
	for _, managedAlias := range []bool{false, true} {
		t.Run(map[bool]string{false: "file_symlink", true: "managed_parent_alias"}[managedAlias], func(t *testing.T) {
			c, f := cancellationClient(t)
			path, _, raw := restoreSource(t)
			alias := filepath.Join(t.TempDir(), "alias")
			target := path
			if managedAlias {
				target = c.node.Dir
				if err := os.WriteFile(filepath.Join(target, "source.bin"), []byte("original immutable retained app context"), 0600); err != nil {
					t.Fatal(err)
				}
			}
			if err := os.Symlink(target, alias); err != nil {
				t.Skip("Host cannot create symlink fixture: ", err)
			}
			if managedAlias {
				alias = filepath.Join(alias, "source.bin")
			}
			if result, err := restoreOnce(t, c, f, raw, alias); result != "" || err == nil {
				t.Fatal("Linked source restored", result, err)
			}
		})
	}
}

func TestRestorePreparedFileCancellationAndChangedSourceNeverCommit(t *testing.T) {
	for _, variant := range []string{"before_entry", "during_hash", "after_hash", "changed_source"} {
		t.Run(variant, func(t *testing.T) {
			c, f := cancellationClient(t)
			path, descriptor, raw := restoreSource(t)
			op, err := c.NewOperation()
			if err != nil {
				t.Fatal(err)
			}
			defer op.Close()
			entered := make(chan struct{})
			c.localFileCopy = func(ctx context.Context, destination io.Writer, source io.Reader) (int64, error) {
				close(entered)
				if variant == "during_hash" {
					<-ctx.Done()
					return 0, ctx.Err()
				}
				n, err := files.CopyContext(ctx, destination, source)
				if variant == "after_hash" {
					op.Cancel()
				}
				if variant == "changed_source" {
					future := time.Now().Add(time.Hour)
					if changeErr := os.Chtimes(path, future, future); changeErr != nil {
						return n, changeErr
					}
				}
				return n, err
			}
			if variant == "before_entry" {
				op.Cancel()
			}
			done, retired := make(chan error, 1), make(chan struct{})
			t.Cleanup(func() { op.Cancel(); checksync.Join(t, retired, 2*time.Second, "prepared restoration") })
			go func() { defer close(retired); _, err := f.RestorePreparedFile(op.Token(), raw, path); done <- err }()
			if variant == "during_hash" {
				checksync.Receive(t, entered, time.Second, "held restoration hash")
				op.Cancel()
			}
			if err := checksync.Receive(t, done, 2*time.Second, "cancelled/changed restoration"); err == nil {
				t.Fatal("Cancelled or changed source restored")
			}
			if _, err := f.outbox.Info("nPi", descriptor.ID); !errors.Is(err, files.ErrAbsent) {
				t.Fatal("Cancelled/changed restore committed bytes", err)
			}
			c.mu.Lock()
			active, claims := len(c.active), len(c.fileDestinations)
			c.mu.Unlock()
			if active != 0 || claims != 0 {
				t.Fatal("Restoration leaked operation/source ownership", active, claims)
			}
		})
	}
}

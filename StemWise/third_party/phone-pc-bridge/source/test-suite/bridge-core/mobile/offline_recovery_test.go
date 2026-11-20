package mobile

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func TestVerifiedInboxRecoversInterruptedImportWithoutStartingNode(t *testing.T) {
	root := t.TempDir()
	state := filepath.Join(root, "node")
	c, err := NewClient(state, "offline-recovery", "")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	open := func() *FileClient {
		t.Helper()
		f, err := c.LocalFiles("pi.tailtest.ts.net", "nPi")
		if err != nil {
			t.Fatal(err)
		}
		return f
	}
	f := open()
	payload := []byte(strings.Repeat("verified output bytes\x00\xff", 4096))
	descriptor, err := files.Describe(strings.Repeat("a", 32), "output.zip", payload)
	if err != nil {
		t.Fatal(err)
	}
	// Only the remote boundary is substituted; the SDK verifies and commits bytes.
	f.exchange = func(_ context.Context, _, method, path string, _ []byte) (string, error) {
		parsed, err := url.Parse(path)
		if err != nil || method != "GET" || parsed.Path != "/v1/outbox/"+descriptor.ID+"/info" {
			t.Fatal("Unexpected remote operation", method, path, err)
		}
		body, _ := json.Marshal(protocol.FileReply{Protocol: protocol.Files, PCIdentity: "nPi", PeerIdentity: "nApp", Nonce: parsed.Query().Get("nonce"), State: "available", File: &descriptor})
		wire, err := json.Marshal(map[string]any{"status": 200, "body": body, "identity": "nApp"})
		return string(wire), err
	}
	f.stream = func(_ context.Context, _, method, path string, _ io.Reader) (int, io.ReadCloser, string, error) {
		if method != "GET" || !strings.HasPrefix(path, "/v1/outbox/"+descriptor.ID+"/content?nonce=") {
			t.Fatal("Unexpected content operation", method, path)
		}
		return 200, io.NopCloser(bytes.NewReader(payload)), "nApp", nil
	}
	if _, err := f.ReceiveMatching("receive-original", descriptor.ID, descriptor.Name, descriptor.Size, descriptor.SHA256); err != nil {
		t.Fatal(err)
	}
	sourcePath := filepath.Join(state, "bridge-incoming", "nPi", descriptor.ID)
	before, err := os.Stat(sourcePath)
	if err != nil {
		t.Fatal(err)
	}
	destination := filepath.Join(root, "app-output.zip")
	c.localFileCopy = func(ctx context.Context, destination io.Writer, source io.Reader) (int64, error) {
		n, err := io.CopyN(destination, source, 7)
		if err != nil {
			return n, err
		}
		c.Cancel("interrupted-import")
		return n, ctx.Err()
	}
	if err := f.CopyReceived("interrupted-import", descriptor.ID, destination); !errors.Is(err, context.Canceled) {
		t.Fatal("Interrupted import did not report cancellation", err)
	}
	if _, err := os.Stat(destination); !os.IsNotExist(err) {
		t.Fatal("Interrupted import retained its partial destination", err)
	}
	if c.started || len(c.active) != 0 {
		t.Fatal("Local import started networking or leaked its operation")
	}
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	c, err = NewClient(state, "offline-recovery", "")
	if err != nil {
		t.Fatal(err)
	}
	f = open()
	// Local recovery must not invoke any remote exchange or delivery acknowledgment.
	f.exchange = func(context.Context, string, string, string, []byte) (string, error) {
		t.Fatal("Offline recovery contacted a remote endpoint")
		return "", errors.New("unexpected network")
	}
	raw, err := f.ReceivedInfo("recover-known-id", descriptor.ID)
	var verified struct {
		State      string
		PCIdentity string
		File       protocol.File
	}
	if err != nil || json.Unmarshal([]byte(raw), &verified) != nil || verified.State != "received_local_verified" || verified.PCIdentity != "nPi" || verified.File != descriptor {
		t.Fatal("Verified inbox did not survive interrupted import and client reopen", raw, err)
	}
	if err := f.CopyReceived("recover-copy", descriptor.ID, destination); err != nil {
		t.Fatal(err)
	}
	if actual, err := os.ReadFile(destination); err != nil || !bytes.Equal(actual, payload) {
		t.Fatal("Recovered app output differs from verified source", err)
	}
	if after, err := os.Stat(sourcePath); err != nil || !after.ModTime().Equal(before.ModTime()) {
		t.Fatal("Recovery extended the original cache deadline", err)
	}
	if _, err := f.StorageDiagnostics("offline-diagnostics"); err != nil {
		t.Fatal(err)
	}
	if err := f.RemoveReceived(descriptor.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(sourcePath); !os.IsNotExist(err) {
		t.Fatal("Offline explicit local cleanup retained managed bytes", err)
	}
	if actual, err := os.ReadFile(destination); err != nil || !bytes.Equal(actual, payload) || c.started || len(c.active) != 0 {
		t.Fatal("Local cleanup touched imported output, started networking or leaked work", err)
	}
}

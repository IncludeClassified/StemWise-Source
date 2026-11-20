package pc

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"phonepcbridge/files"
	"tailscale.com/tsnet"
)

func TestRetentionStartupIsolatesUnknownEntriesAndPrunesHealthyAdapterStores(t *testing.T) {
	root := t.TempDir()
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	stage := filepath.Join(root, "incoming", "nPi", ".incoming-"+strings.Repeat("0", 32))
	if err := os.MkdirAll(stage, 0700); err != nil {
		t.Fatal(err)
	}
	unknown := filepath.Join(stage, "unexpected.txt")
	if err := os.WriteFile(unknown, []byte("preserve"), 0600); err != nil {
		t.Fatal(err)
	}
	id := strings.Repeat("f", 32)
	old := time.Now().Add(-3*24*time.Hour - time.Second)
	for _, name := range []string{"outgoing", "creations"} {
		store, err := files.Open(filepath.Join(root, name))
		if err != nil {
			t.Fatal(err)
		}
		f, _ := files.Describe(id, "expired.bin", []byte(name))
		if _, err := store.Put("nPi", f, strings.NewReader(name)); err != nil {
			t.Fatal(err)
		}
		if err := store.Close(); err != nil {
			t.Fatal(err)
		}
		if err := os.Chtimes(filepath.Join(root, name, "nPi", id), old, old); err != nil {
			t.Fatal(err)
		}
	}
	s, err := New(&tsnet.Server{}, Config{Identity: "nPc", Host: "pc.tailtest.ts.net", CodexExecutable: exe, DataDir: root, AllowedPeers: []string{"nPi", "nOther"}})
	if err != nil {
		t.Fatal("Unknown input stage blocked healthy adapter startup", err)
	}
	defer s.Close()
	for _, name := range []string{"outgoing"} {
		if _, err := os.Stat(filepath.Join(root, name, "nPi", id)); err != nil {
			t.Fatal("Unconfirmed creation ownership discarded potentially referenced cache", name, err)
		}
	}
	if _, err := os.Stat(filepath.Join(root, "creations", "nPi", id)); err != nil {
		t.Fatal("Ownership obligations discarded before native cleanup", err)
	}
	if actual, err := os.ReadFile(unknown); err != nil || string(actual) != "preserve" {
		t.Fatal("Unknown input stage was removed", err)
	}
	if _, err := s.QueueFile(context.Background(), "nOther", randomToken(), "healthy.bin", []byte("healthy work")); err != nil {
		t.Fatal("Unknown owner metadata blocked an independent peer's transfer", err)
	}
	if status := s.cleanupSnapshot(); status.State != "unconfirmed" || status.Code != ErrJobFileOwnership.Error() {
		t.Fatal("Unconfirmed owner metadata was presented as healthy", status)
	}
	diagnostics, err := s.inbox.Diagnostics("nPi")
	if err != nil || diagnostics.DiagnosticCount != 1 || len(diagnostics.Diagnostics) != 1 || diagnostics.Diagnostics[0].Code != "managed_object_corrupt" {
		t.Fatal("Preserved damage was not exposed for diagnosis", diagnostics, err)
	}
}

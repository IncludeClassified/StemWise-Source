package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"golang.org/x/sys/unix"
	"phonepcbridge/files"
	"phonepcbridge/pc"
	"tailscale.com/tsnet"
)

func TestProducerStorageCapacityPreservesSource(t *testing.T) {
	path := os.Getenv("BRIDGE_TEST_SMALL_FS")
	if path == "" {
		t.Skip("Run through StorageCapacityCheck.py on an owned small filesystem")
	}
	var stat unix.Statfs_t
	if err := unix.Statfs(path, &stat); err != nil || stat.Type != unix.TMPFS_MAGIC || stat.Bavail*uint64(stat.Bsize) >= 64<<20 || filepath.Clean(os.TempDir()) != filepath.Clean(path) {
		t.Fatal("Owned small temporary filesystem differs", err)
	}
	root := t.TempDir()
	producerTestACL(t, root, false)
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	seedProducerOwner(t, root, root, "nPi")
	s, err := pc.New(&tsnet.Server{}, pc.Config{Identity: "nPc", Host: "pc.ts.net", CodexExecutable: executable, AllowedPeers: []string{"nPi"}, DataDir: root, IntegrationDir: root})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	p, err := startProducer(root, s, "nPc", "nPi")
	if err != nil {
		t.Fatal(err)
	}
	defer p.Close()
	id, _ := files.NewID()
	source := filepath.Join(root, "owned-source.bin")
	data := []byte("actual-source-retained-" + id)
	if err := os.WriteFile(source, data, 0600); err != nil {
		t.Fatal(err)
	}
	err = producerCommand(root, "nPi", "queue", id, source, "", 0, producerTestApp, producerTestJob, producerTestThread)
	if err == nil || !strings.Contains(err.Error(), "local_file_rejected http_status=503 state=storage_capacity_unavailable action=release_pc_storage_or_wait_for_transfers_then_retry_same_transfer") {
		t.Fatal("Actual local producer lost its capacity diagnosis", err)
	}
	if actual, err := os.ReadFile(source); err != nil || !bytes.Equal(actual, data) {
		t.Fatal("Refusal changed the developer's source", err)
	}
	entries, err := os.ReadDir(filepath.Join(root, "outgoing"))
	if err != nil || len(entries) != 0 {
		t.Fatal("Refused producer queue retained managed bytes or staging", err)
	}
}

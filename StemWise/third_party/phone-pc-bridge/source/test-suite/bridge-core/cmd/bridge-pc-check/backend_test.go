package main

import (
	"flag"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestBackendPairing(t *testing.T) {
	root := t.TempDir()
	producerTestACL(t, root, false)
	for _, initial := range []string{"", "../other", "nPi\nnOther", " nPi"} {
		if id, err := resolveBackendIdentity(root, initial); err == nil || id != "" {
			t.Fatal("Missing pairing reopened direct mode or accepted an invalid fallback", id, err)
		}
	}
	if id, err := resolveBackendIdentity(root, "nPi"); err != nil || id != "nPi" {
		t.Fatal("Explicit Pi configuration was lost", id, err)
	}
	path := filepath.Join(root, "backend-peer.txt")
	for _, input := range []string{"", "../other", "nPi\nnOther", "nPi " + string([]byte{0xff})} {
		if err := os.WriteFile(path, []byte(input), 0600); err != nil {
			t.Fatal(err)
		}
		if _, err := resolveBackendIdentity(root, ""); err == nil {
			t.Fatal("Invalid backend accepted")
		}
	}
	if err := os.WriteFile(path, []byte("nPi\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if id, err := resolveBackendIdentity(root, "nOther"); err == nil || id != "" {
		t.Fatal("Conflicting backend accepted")
	}
	if id, err := resolveBackendIdentity(root, ""); err != nil || id != "nPi" {
		t.Fatal(id, err)
	}
}

func TestBackendRequiredBeforeEnrollment(t *testing.T) {
	root := t.TempDir()
	previousArgs, previousFlags := os.Args, flag.CommandLine
	defer func() { os.Args, flag.CommandLine = previousArgs, previousFlags }()
	flag.CommandLine = flag.NewFlagSet("owned-backend-required", flag.ContinueOnError)
	os.Args = []string{"owned-backend-required", "-state", root, "-peer", "nOwnedPhone"}
	if err := run(); err == nil || !strings.HasPrefix(err.Error(), "pi_backend_identity_required:") {
		t.Fatal("Missing Pi pairing reached later runtime initialization", err)
	}
	if entries, err := os.ReadDir(root); err != nil || len(entries) != 0 {
		t.Fatal("Rejected host created enrollment or legacy state", err)
	}
}

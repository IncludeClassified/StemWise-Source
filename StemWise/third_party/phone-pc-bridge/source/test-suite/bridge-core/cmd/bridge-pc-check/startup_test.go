package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestHostStartupProcess(t *testing.T) {
	if os.Getenv("BRIDGE_STARTUP_PROCESS") != "1" {
		return
	}
	for i, value := range os.Args {
		if value == "--" {
			os.Args = append([]string{os.Args[0]}, os.Args[i+1:]...)
			flag.CommandLine = flag.NewFlagSet("bridge-pc-check", flag.ContinueOnError)
			if err := run(); err != nil {
				fmt.Fprintln(os.Stderr, err)
				os.Exit(1)
			}
			os.Exit(0)
		}
	}
	t.Fatal("Missing startup arguments")
}

func TestNormalStartupDoesNotDependOnAndroidPairing(t *testing.T) {
	for _, variant := range []string{"no-peer-argument", "missing", "malformed", "directory"} {
		t.Run(variant, func(t *testing.T) {
			state := t.TempDir()
			producerTestACL(t, state, false)
			if err := os.WriteFile(filepath.Join(state, "backend-peer.txt"), []byte("nPi\n"), 0600); err != nil {
				t.Fatal(err)
			}
			android := filepath.Join(state, "paired-peer.txt")
			if variant == "malformed" {
				if err := os.WriteFile(android, []byte("expired-tablet\ninvalid\n"), 0600); err != nil {
					t.Fatal(err)
				}
			} else if variant == "directory" {
				if err := os.Mkdir(android, 0700); err != nil {
					t.Fatal(err)
				}
			}
			args := []string{"-state", state, "-codex", filepath.Join(state, "absent-codex.exe")}
			if variant != "no-peer-argument" {
				// Existing task actions must still be accepted without depending on this node.
				args = append(args, "-peer", "nOldTablet")
			}
			output := hostStartupFailure(t, args...)
			if !strings.Contains(output, "Native Codex runtime executable is missing or not a regular file") {
				t.Fatal("Ordinary startup consulted Android pairing before its real runtime boundary", output)
			}
			if got, err := os.ReadFile(filepath.Join(state, "backend-peer.txt")); err != nil || string(got) != "nPi\n" {
				t.Fatal("Startup changed private Pi pairing", string(got), err)
			}
			if variant == "malformed" {
				if got, err := os.ReadFile(android); err != nil || string(got) != "expired-tablet\ninvalid\n" {
					t.Fatal("Startup changed the unused tablet pairing", string(got), err)
				}
			}
		})
	}
}

func TestExplicitRoundtripValidatesAndroidBeforeEnrollment(t *testing.T) {
	for _, variant := range []string{"missing", "malformed", "same-as-pi"} {
		t.Run(variant, func(t *testing.T) {
			state := t.TempDir()
			producerTestACL(t, state, false)
			if err := os.WriteFile(filepath.Join(state, "backend-peer.txt"), []byte("nPi\n"), 0600); err != nil {
				t.Fatal(err)
			}
			args := []string{"-state", state, "-roundtrip", "-codex", filepath.Join(state, "absent-codex.exe")}
			want := "Require an absolute private state path and initial peer ID"
			if variant == "malformed" {
				args = append(args, "-peer", "nAndroid")
				if err := os.WriteFile(filepath.Join(state, "paired-peer.txt"), []byte("nOne\nnTwo"), 0600); err != nil {
					t.Fatal(err)
				}
				want = "Paired peer setting requires one exact Android node ID"
			} else if variant == "same-as-pi" {
				args = append(args, "-peer", "nPi")
				want = "Round-trip Android peer must differ from the Pi backend"
			}
			if output := hostStartupFailure(t, args...); !strings.Contains(output, want) {
				t.Fatal("Explicit roundtrip bypassed its own pairing gate", output)
			}
		})
	}
}

func hostStartupFailure(t *testing.T, args ...string) string {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, os.Args[0], append([]string{"-test.run=^TestHostStartupProcess$", "--"}, args...)...)
	cmd.Env = append(os.Environ(), "BRIDGE_STARTUP_PROCESS=1")
	output, err := cmd.CombinedOutput()
	if err == nil || ctx.Err() != nil {
		t.Fatal("Expected bounded pre-enrollment startup refusal", err, string(output))
	}
	return string(output)
}

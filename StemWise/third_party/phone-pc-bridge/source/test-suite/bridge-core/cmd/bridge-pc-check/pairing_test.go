package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestResolvePeerIdentity(t *testing.T) {
	for _, scenario := range []string{"initial", "replacement", "empty", "multiple", "invalid", "invalid-utf8", "oversized", "directory", "relative", "bad-initial"} {
		t.Run(scenario, func(t *testing.T) {
			state, initial := t.TempDir(), "nInitial"
			path := filepath.Join(state, "paired-peer.txt")
			value := "nReplacement\r\n"
			switch scenario {
			case "empty":
				value = "\n"
			case "multiple":
				value = "nFirst\nnSecond"
			case "invalid":
				value = "../another-device"
			case "invalid-utf8":
				value = "\xff\xfe"
			case "oversized":
				value = strings.Repeat(" ", 513) + "nReplacement"
			case "directory":
				if err := os.Mkdir(path, 0700); err != nil {
					t.Fatal(err)
				}
			case "relative":
				state = "relative-state"
			case "bad-initial":
				initial = ""
			}
			if scenario != "initial" && scenario != "directory" && scenario != "relative" {
				if err := os.WriteFile(path, []byte(value), 0600); err != nil {
					t.Fatal(err)
				}
			}
			peer, err := resolvePeerIdentity(state, initial)
			if scenario == "initial" {
				if err != nil || peer != initial {
					t.Fatalf("Initial pairing lost: %q %v", peer, err)
				}
			} else if scenario == "replacement" {
				if err != nil || peer != "nReplacement" {
					t.Fatalf("Replacement pairing ignored: %q %v", peer, err)
				}
			} else if err == nil || peer != "" {
				t.Fatalf("Invalid setting fell back or passed: %q %v", peer, err)
			}
		})
	}
}

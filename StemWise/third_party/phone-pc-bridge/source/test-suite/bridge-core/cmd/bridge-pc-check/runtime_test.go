package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestResolveCodexExecutable(t *testing.T) {
	for _, scenario := range []string{"standalone", "standalone-missing", "standalone-relative", "standalone-directory", "packaged", "packaged-stale-argument", "packaged-missing-codex", "packaged-missing-companion", "packaged-directory-executable", "runtime-is-file"} {
		t.Run(scenario, func(t *testing.T) {
			root := t.TempDir()
			host := filepath.Join(root, "bridge-pc-check.exe")
			configured := filepath.Join(root, "external-codex.exe")
			if err := os.WriteFile(configured, []byte("standalone fixture"), 0600); err != nil {
				t.Fatal(err)
			}
			dir := filepath.Join(root, "runtime")
			if scenario == "runtime-is-file" {
				if err := os.WriteFile(dir, []byte("invalid bundle"), 0600); err != nil {
					t.Fatal(err)
				}
			} else if strings.HasPrefix(scenario, "packaged") {
				if err := os.Mkdir(dir, 0700); err != nil {
					t.Fatal(err)
				}
				for _, name := range []string{"codex.exe", "codex-code-mode-host.exe", "codex-command-runner.exe", "codex-windows-sandbox-setup.exe"} {
					if scenario == "packaged-missing-codex" && name == "codex.exe" || scenario == "packaged-missing-companion" && name == "codex-command-runner.exe" {
						continue
					}
					path := filepath.Join(dir, name)
					if scenario == "packaged-directory-executable" && name == "codex.exe" {
						if err := os.Mkdir(path, 0700); err != nil {
							t.Fatal(err)
						}
					} else if err := os.WriteFile(path, []byte(name), 0600); err != nil {
						t.Fatal(err)
					}
				}
			}
			switch scenario {
			case "standalone-missing", "standalone-directory", "packaged-stale-argument":
				if err := os.Remove(configured); err != nil {
					t.Fatal(err)
				}
				if scenario == "standalone-directory" {
					if err := os.Mkdir(configured, 0700); err != nil {
						t.Fatal(err)
					}
				}
			case "standalone-relative":
				configured = "codex.exe"
			}
			actual, err := resolveCodexExecutable(host, configured)
			if scenario == "standalone" || scenario == "packaged" || scenario == "packaged-stale-argument" {
				want := configured
				if strings.HasPrefix(scenario, "packaged") {
					want = filepath.Join(dir, "codex.exe")
				}
				if err != nil || actual != want {
					t.Fatalf("Runtime selection differs: %q, %v", actual, err)
				}
			} else if err == nil || actual != "" {
				t.Fatal("Incomplete or invalid runtime was accepted or bypassed through the external executable")
			}
		})
	}
}

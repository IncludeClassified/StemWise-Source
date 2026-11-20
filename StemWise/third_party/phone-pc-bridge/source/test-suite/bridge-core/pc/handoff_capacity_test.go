package pc

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"phonepcbridge/codex"
	"phonepcbridge/files"
	"phonepcbridge/protocol"
	"tailscale.com/tsnet"
)

func TestMaximumInputNamesSurviveHandoffFormattingAndRecovery(t *testing.T) {
	for _, name := range []string{
		strings.Repeat("&", 255), strings.Repeat("<", 255), strings.Repeat(">", 255),
		strings.Repeat(`"`, 255), strings.Repeat("\u2028", 85), strings.Repeat("\u2029", 85),
		strings.Repeat("😀", 63) + "abc", strings.Repeat("ع", 127) + "a",
	} {
		t.Run(fmt.Sprintf("%U", []rune(name)[0]), func(t *testing.T) {
			root, runtime := t.TempDir(), t.TempDir()
			producer := filepath.Join(runtime, "sdk-check", "bridge-pc-check.exe")
			if err := os.Mkdir(filepath.Dir(producer), 0700); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(producer, []byte("fixture"), 0600); err != nil {
				t.Fatal(err)
			}
			executable, err := os.Executable()
			if err != nil {
				t.Fatal(err)
			}
			peer := strings.Repeat("p", 128)
			s, err := New(&tsnet.Server{}, Config{Identity: strings.Repeat("i", 128), Host: "pc.tailtest.ts.net", DataDir: root, IntegrationDir: runtime, CodexExecutable: executable, AllowedPeers: []string{peer}})
			if err != nil {
				t.Fatal(err)
			}
			defer s.Close()
			r := Request{Epoch: s.epoch, Operation: "text", ThreadID: strings.Repeat("t", 128), Sequence: math.MaxInt64, Text: "original & <prompt> 😀", Instructions: "verbatim custom", AppID: strings.Repeat("a", 32), JobID: strings.Repeat("b", 32)}
			for i := 0; i < 16; i++ {
				f, err := files.Describe(fmt.Sprintf("%032x", i), name, []byte("input"))
				if err != nil || !protocol.ValidFile(f) {
					t.Fatal("Maximum filename rejected", err)
				}
				if _, err := s.inbox.Put(peer, f, bytes.NewReader([]byte("input"))); err != nil {
					t.Fatal(err)
				}
				r.InputFiles = append(r.InputFiles, f)
			}
			if err := protocol.Validate(r); err != nil {
				t.Fatal("Valid immutable request rejected", err)
			}
			file, err := s.createHandoff(context.Background(), peer, r)
			if err != nil {
				t.Fatal(err)
			}
			message, err := codex.FormatJobMessage(r.Text, r.Instructions, file.path, file.markdown)
			if err != nil {
				t.Fatalf("Accepted descriptors produced unusable %d-byte handoff: %v", len(file.markdown), err)
			}
			t.Logf("%d-byte names, %d-byte handoff", len(name), len(file.markdown))
			parsed, err := codex.ParseJobMessage(message)
			if err != nil || parsed.Prompt != r.Text || parsed.Instructions != r.Instructions {
				t.Fatal("Single message changed input", err)
			}
			recovered, owner, err := s.readHandoff(filepath.Base(file.path))
			if err != nil || recovered != file || owner.Peer != peer || owner.AppID != r.AppID || owner.JobID != r.JobID || owner.ThreadID != r.ThreadID {
				t.Fatal("Generated handoff cannot recover its exact owner", err)
			}
			_, inputJSON, ok := strings.Cut(recovered.markdown, "\n## This job's immutable input files\n\n")
			inputJSON, _, _ = strings.Cut(inputJSON, "\n\n")
			var inputs []protocol.File
			if !ok || json.Unmarshal([]byte(inputJSON), &inputs) != nil || !reflect.DeepEqual(inputs, r.InputFiles) {
				t.Fatal("Generated JSON did not preserve exact descriptor names")
			}
		})
	}
}

func TestHandoffCapacityRejectsUnsupportedConfigurationBeforeStorage(t *testing.T) {
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	for _, field := range []string{"host", "integration_directory", "data_directory"} {
		t.Run(field, func(t *testing.T) {
			root := t.TempDir()
			cfg := Config{Identity: "nPc", Host: "pc.tailtest.ts.net", DataDir: root, CodexExecutable: executable, AllowedPeers: []string{"nPi"}}
			switch field {
			case "host":
				cfg.Host = strings.Repeat("h", protocol.MaxHandoffBytes) + ".ts.net"
			case "integration_directory":
				cfg.IntegrationDir = filepath.Join(root, strings.Repeat("i", protocol.MaxHandoffBytes))
			case "data_directory":
				cfg.IntegrationDir = root
				cfg.DataDir = filepath.Join(root, strings.Repeat("d", protocol.MaxHandoffBytes), "private")
			}
			s, err := New(&tsnet.Server{}, cfg)
			if s != nil {
				s.Close()
				t.Fatal("Unsupported configuration created a service")
			}
			if err == nil || !strings.Contains(err.Error(), "handoff_capacity_unavailable") {
				t.Fatal("Unsupported configuration did not fail at the capacity boundary", err)
			}
			entries, err := os.ReadDir(root)
			if err != nil || len(entries) != 0 {
				t.Fatal("Capacity rejection created managed stores before refusing", err, entries)
			}
		})
	}
}

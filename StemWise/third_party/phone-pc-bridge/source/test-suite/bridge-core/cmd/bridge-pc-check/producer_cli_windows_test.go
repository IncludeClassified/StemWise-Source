package main

import (
	"bytes"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/pc"
	"phonepcbridge/protocol"
	"tailscale.com/tsnet"
)

func TestProducerCLIProcess(t *testing.T) {
	if os.Getenv("BRIDGE_PRODUCER_CLI_HELPER") != "1" {
		return
	}
	// A shell timeout must not leave its native child running.
	time.AfterFunc(20*time.Second, func() { os.Exit(2) })
	for index, argument := range os.Args {
		if argument == "--" {
			os.Args = append([]string{os.Args[0]}, os.Args[index+1:]...)
			flag.CommandLine = flag.NewFlagSet("bridge-pc-check", flag.ContinueOnError)
			if err := run(); err != nil {
				fmt.Fprintln(os.Stderr, err)
				os.Exit(1)
			}
			os.Exit(0)
		}
	}
	t.Fatal("CLI arguments missing")
}

func TestProducerDocumentedPowerShellQueue(t *testing.T) {
	root := t.TempDir()
	producerTestACL(t, root, false)
	if err := os.Mkdir(filepath.Join(root, "files"), 0700); err != nil {
		t.Fatal(err)
	}
	seedProducerOwner(t, filepath.Join(root, "files"), root, "nPhone")
	service, err := pc.New(&tsnet.Server{}, pc.Config{Identity: "nPc", Host: "pc.ts.net", CodexExecutable: filepath.Join(root, "codex.exe"), AllowedPeers: []string{"nPhone"}, DataDir: filepath.Join(root, "files"), IntegrationDir: root})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close()
	producer, err := startProducer(root, service, "nPc", "nPhone")
	if err != nil {
		t.Fatal(err)
	}
	defer producer.Close()
	source := []byte("documented authenticated CLI source\x00\xff")
	sourcePath := filepath.Join(root, "output with spaces.bin")
	if err := os.WriteFile(sourcePath, source, 0600); err != nil {
		t.Fatal(err)
	}
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	command := exec.CommandContext(ctx, "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", `
$ErrorActionPreference = 'Stop'
function Invoke-TestProducer { & $env:BRIDGE_PRODUCER_EXE '-test.run=^TestProducerCLIProcess$' '--' @args }
$producer = 'Invoke-TestProducer'
$state = $env:BRIDGE_PRODUCER_STATE
$id = (& $producer -state $state -peer nPhone -files-op new-id | ConvertFrom-Json).id
if ($LASTEXITCODE -ne 0) { throw 'new-id failed' }
$queued = & $producer -state $state -peer nPhone -files-op queue -id $id -file $env:BRIDGE_PRODUCER_SOURCE -app-id $env:BRIDGE_PRODUCER_APP -job-id $env:BRIDGE_PRODUCER_JOB -thread-id $env:BRIDGE_PRODUCER_THREAD | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'queue failed' }
@{ id = $id; queued = $queued } | ConvertTo-Json -Depth 5 -Compress
`)
	command.Env = append(os.Environ(), "BRIDGE_PRODUCER_CLI_HELPER=1", "BRIDGE_PRODUCER_EXE="+executable, "BRIDGE_PRODUCER_STATE="+root, "BRIDGE_PRODUCER_SOURCE="+sourcePath, "BRIDGE_PRODUCER_APP="+producerTestApp, "BRIDGE_PRODUCER_JOB="+producerTestJob, "BRIDGE_PRODUCER_THREAD="+producerTestThread)
	output, err := command.CombinedOutput()
	if err != nil {
		t.Fatalf("Documented PowerShell queue failed: %v\n%s", err, output)
	}
	var actual struct {
		ID     string             `json:"id"`
		Queued protocol.FileReply `json:"queued"`
	}
	if err := json.Unmarshal(output, &actual); err != nil {
		t.Fatalf("Invalid CLI output: %v\n%s", err, output)
	}
	if !files.ValidID(actual.ID) || len(actual.ID) != 32 {
		t.Fatalf("new-id did not produce an exact transfer ID: %q", actual.ID)
	}
	expected, err := files.Describe(actual.ID, filepath.Base(sourcePath), source)
	if err != nil || actual.Queued.State != "queued" || actual.Queued.File == nil || *actual.Queued.File != expected {
		t.Fatal("Queue changed the generated ID or actual source descriptor", actual, err)
	}
	stored, err := os.ReadFile(filepath.Join(root, "files", "outgoing", "nPhone", actual.ID, "content"))
	if err != nil || !bytes.Equal(stored, source) {
		t.Fatal("Authenticated queue did not commit the original bytes", err)
	}
	newID := exec.CommandContext(ctx, executable, "-test.run=^TestProducerCLIProcess$", "--", "-files-op", "new-id")
	newID.Env = append(os.Environ(), "BRIDGE_PRODUCER_CLI_HELPER=1")
	legacyID, err := newID.Output()
	if err != nil {
		t.Fatal("Actual CLI new-id failed", err)
	}
	legacy := exec.CommandContext(ctx, executable, "-test.run=^TestProducerCLIProcess$", "--", "-state", root, "-peer", "nPhone", "-files-op", "queue", "-id", string(legacyID), "-file", sourcePath, "-app-id", producerTestApp, "-job-id", producerTestJob, "-thread-id", producerTestThread)
	legacy.Env = append(os.Environ(), "BRIDGE_PRODUCER_CLI_HELPER=1")
	if output, err := legacy.CombinedOutput(); err == nil || !strings.Contains(string(output), "Stable transfer ID and absolute source file required") {
		t.Fatalf("Old whole-JSON ID did not fail at actual queue parsing: %v\n%s", err, output)
	}
	for _, test := range []struct {
		name    string
		owner   []string
		message string
	}{
		{"none", nil, "Queue requires the exact app-id, job-id and thread-id together"},
		{"partial", []string{"-app-id", producerTestApp, "-job-id", producerTestJob}, "Queue requires the exact app-id, job-id and thread-id together"},
		{"wrong-thread", []string{"-app-id", producerTestApp, "-job-id", producerTestJob, "-thread-id", "foreign-thread"}, "state=job_file_ownership_unconfirmed action=inspect_exact_job_owner"},
	} {
		t.Run(test.name, func(t *testing.T) {
			id, _ := files.NewID()
			args := []string{"-test.run=^TestProducerCLIProcess$", "--", "-state", root, "-peer", "nPhone", "-files-op", "queue", "-id", id, "-file", sourcePath}
			args = append(args, test.owner...)
			command := exec.CommandContext(ctx, executable, args...)
			command.Env = append(os.Environ(), "BRIDGE_PRODUCER_CLI_HELPER=1")
			if output, err := command.CombinedOutput(); err == nil || !strings.Contains(string(output), test.message) {
				t.Fatalf("Actual CLI accepted missing/foreign owner or lost refusal: %v\n%s", err, output)
			}
			if _, err := service.QueuedFileInfo(context.Background(), "nPhone", id); !os.IsNotExist(err) {
				t.Fatal("Rejected CLI owner queued payload", err)
			}
			entries, err := os.ReadDir(filepath.Join(root, "files", "creations", "nPhone"))
			if err != nil || len(entries) != 4 {
				t.Fatal("Rejected CLI owner wrote file reference", err, len(entries))
			}
		})
	}
}

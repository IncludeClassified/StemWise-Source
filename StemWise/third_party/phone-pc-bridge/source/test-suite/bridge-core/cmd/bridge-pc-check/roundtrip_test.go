package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/pc"
	"phonepcbridge/protocol"
	"tailscale.com/tsnet"
)

func TestRoundtripFindsResponseBeyondFirstReceivedPage(t *testing.T) {
	root := t.TempDir()
	inbox, err := files.Open(filepath.Join(root, "incoming"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { inbox.Close() })
	for i := 1; i <= 101; i++ {
		data := []byte(fmt.Sprintf("older recipient %d", i))
		f, err := files.Describe(fmt.Sprintf("%032x", i), "older.bin", data)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := inbox.Put("nPhone", f, bytes.NewReader(data)); err != nil {
			t.Fatal(err)
		}
	}
	service, err := pc.New(&tsnet.Server{}, pc.Config{Identity: "nPc", Host: "pc.ts.net", CodexExecutable: filepath.Join(root, "codex.exe"), AllowedPeers: []string{"nPhone"}, DataDir: root})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { service.Close() })
	challenge, result, _ := startRoundtripCheck(t, service)
	outbox, err := files.Open(filepath.Join(root, "outgoing"))
	if err != nil {
		t.Fatal(err)
	}
	actual, data, readErr := outbox.Read("nPhone", challenge.ID)
	closeErr := outbox.Close()
	if readErr != nil || closeErr != nil || actual != challenge || len(data) != 4096 {
		t.Fatal("Actual queued challenge is unavailable", actual, readErr, closeErr)
	}
	response, err := files.Describe(strings.Repeat("f", 32), challenge.Name, data)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := inbox.Put("nPhone", response, bytes.NewReader(data)); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-result:
		if err != nil {
			t.Fatal("Valid response after the first 100 entries was missed", err)
		}
	case <-time.After(4 * time.Second):
		t.Fatal("Roundtrip checker did not retire within its test deadline")
	}
	if _, err := service.QueuedFileInfo(context.Background(), "nPhone", challenge.ID); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Completed roundtrip left its queued challenge", err)
	}
	if _, err := service.ReceivedFileInfo(context.Background(), "nPhone", response.ID); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Completed roundtrip left its received response", err)
	}
	page, err := service.ReceivedFiles(context.Background(), "nPhone", "", 100)
	if err != nil || len(page.Files) != 100 || page.NextCursor == "" {
		t.Fatal("Roundtrip removed unrelated received files", err)
	}
}

func TestRoundtripCursorAdvancesOrTerminates(t *testing.T) {
	a, b := strings.Repeat("a", 32), strings.Repeat("b", 32)
	for _, pair := range [][2]string{{"", ""}, {a, ""}, {"", a}, {a, b}} {
		if next, err := advanceRoundtripCursor(pair[0], pair[1]); err != nil || next != pair[1] {
			t.Fatal("Valid page did not advance or reset for another sweep", pair, next, err)
		}
	}
	for _, pair := range [][2]string{{a, a}, {b, a}, {"", "bad"}, {"", strings.Repeat("A", 32)}} {
		if _, err := advanceRoundtripCursor(pair[0], pair[1]); err == nil {
			t.Fatal("Malformed, stalled or cycling pagination was accepted", pair)
		}
	}
}

func TestRoundtripEmptyInventoryCancelsWithoutWaitingForPoll(t *testing.T) {
	root := t.TempDir()
	service, err := pc.New(&tsnet.Server{}, pc.Config{Identity: "nPc", Host: "pc.ts.net", CodexExecutable: filepath.Join(root, "codex.exe"), AllowedPeers: []string{"nPhone"}, DataDir: root})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { service.Close() })
	page, err := service.ReceivedFiles(context.Background(), "nPhone", "", 100)
	if err != nil || len(page.Files) != 0 || page.NextCursor != "" {
		t.Fatal("Fixture did not expose an empty terminal page", err)
	}
	challenge, result, cancel := startRoundtripCheck(t, service)
	cancel()
	select {
	case err := <-result:
		if err == nil || !strings.Contains(err.Error(), "roundtrip remains unverified") {
			t.Fatal("Cancelled checker reported verified success or another failure", err)
		}
	case <-time.After(time.Second):
		t.Fatal("Cancelled checker waited for another poll")
	}
	if _, err := service.QueuedFileInfo(context.Background(), "nPhone", challenge.ID); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Cancelled roundtrip retained its queued challenge", err)
	}
}

func startRoundtripCheck(t *testing.T, service *pc.Service) (protocol.File, <-chan error, context.CancelFunc) {
	t.Helper()
	input, err := os.CreateTemp(t.TempDir(), "confirmation-")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := input.WriteString("\n"); err != nil {
		input.Close()
		t.Fatal(err)
	}
	if _, err := input.Seek(0, 0); err != nil {
		input.Close()
		t.Fatal(err)
	}
	reader, writer, err := os.Pipe()
	if err != nil {
		input.Close()
		t.Fatal(err)
	}
	oldInput, oldOutput := os.Stdin, os.Stdout
	os.Stdin, os.Stdout = input, writer
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	result, stopped := make(chan error, 1), make(chan struct{})
	go func() {
		defer close(stopped)
		result <- checkRoundtrip(ctx, service, "nPhone")
	}()
	line, lineStopped := make(chan string, 1), make(chan struct{})
	go func() {
		defer close(lineStopped)
		text, _ := bufio.NewReader(reader).ReadString('\n')
		line <- text
	}()
	t.Cleanup(func() {
		cancel()
		writer.Close()
		reader.Close()
		select {
		case <-stopped:
		case <-time.After(4 * time.Second):
			t.Error("Roundtrip check did not retire after cancellation")
		}
		select {
		case <-lineStopped:
		case <-time.After(4 * time.Second):
			t.Error("Roundtrip challenge reader did not retire after close")
		}
		os.Stdin, os.Stdout = oldInput, oldOutput
		input.Close()
	})
	select {
	case text := <-line:
		var emitted struct {
			Challenge protocol.File `json:"roundtripChallenge"`
		}
		if err := json.Unmarshal([]byte(text), &emitted); err != nil || !files.Valid(emitted.Challenge) {
			t.Fatal("Checker did not emit a valid actual challenge", err)
		}
		return emitted.Challenge, result, cancel
	case <-time.After(4 * time.Second):
		t.Fatal("Checker did not emit its challenge within the test deadline")
		return protocol.File{}, nil, nil
	}
}

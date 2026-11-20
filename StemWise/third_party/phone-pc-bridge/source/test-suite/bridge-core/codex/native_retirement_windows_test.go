package codex

import (
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
	"unicode/utf16"
)

// Explicit integration check: one harmless tool turn in a disposable owned chat.
func TestNativeToolCancellationAndRetirement(t *testing.T) {
	executable := os.Getenv("BRIDGE_NATIVE_CODEX")
	if executable == "" || os.Getenv("BRIDGE_NATIVE_INFERENCE") != "1" {
		t.Skip("Set BRIDGE_NATIVE_CODEX and BRIDGE_NATIVE_INFERENCE=1 for the explicit native tool-turn check")
	}
	project := os.Getenv("BRIDGE_NATIVE_PROJECT")
	if !filepath.IsAbs(project) {
		t.Fatal("Set BRIDGE_NATIVE_PROJECT to the existing Bridge project for its owned fixture chat")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	work := t.TempDir()
	c, err := Connect(ctx, executable, "")
	if err != nil {
		t.Fatal("Native stdio initialization failed", err)
	}
	t.Cleanup(func() {
		if err := c.Close(); err != nil {
			t.Error("Native owned process cleanup", err)
		}
	})
	raw, err := c.StartThread(ctx, project, "", "")
	var created struct {
		Thread struct {
			ID string `json:"id"`
		} `json:"thread"`
	}
	if err != nil || json.Unmarshal(raw, &created) != nil || validID(created.Thread.ID) != nil {
		t.Fatal("Native fixture requires its exact newly created thread", err)
	}
	id := created.Thread.ID
	t.Cleanup(func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		for {
			if err := c.DeleteThread(cleanup, id); err == nil {
				return
			} else if cleanup.Err() != nil {
				t.Error("Native fixture chat deletion unconfirmed", id, err)
				return
			}
			time.Sleep(100 * time.Millisecond)
		}
	})
	if _, err = c.Select(ctx, id); err != nil {
		t.Fatal("Native fixture selection failed", err)
	}
	pidFile := filepath.Join(work, "owned-child.pid")
	script := "[IO.File]::WriteAllText('" + strings.ReplaceAll(pidFile, "'", "''") + "',[string]$PID); Start-Sleep -Seconds 120"
	units := utf16.Encode([]rune(script))
	bytes := make([]byte, 2*len(units))
	for index, unit := range units {
		binary.LittleEndian.PutUint16(bytes[2*index:], unit)
	}
	command := "powershell.exe -NoProfile -NonInteractive -EncodedCommand " + base64.StdEncoding.EncodeToString(bytes)
	markdown := "Bridge-owned native cancellation fixture. This chat and command are disposable; modify only the requested PID file."
	path := filepath.Join(work, "native-fixture.md")
	if err := os.WriteFile(path, []byte(markdown), 0600); err != nil {
		t.Fatal(err)
	}
	prompt := "Run this exact harmless cancellation-test command once using your shell tool: " + command + "\nUse a short initial wait and keep monitoring until it finishes. Do not run other commands or modify other files."
	raw, err = c.SendJob(ctx, id, prompt, "", path, markdown, "", "", "")
	var admitted struct {
		Turn struct{ ID string } `json:"turn"`
	}
	if err != nil || json.Unmarshal(raw, &admitted) != nil || validID(admitted.Turn.ID) != nil {
		t.Fatal("Native fixture tool turn was not admitted", err)
	}
	turn := admitted.Turn.ID
	var pid int
	for ctx.Err() == nil {
		data, err := os.ReadFile(pidFile)
		if err == nil {
			pid, _ = strconv.Atoi(strings.TrimSpace(string(data)))
			if pid > 0 {
				break
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	if pid <= 0 {
		t.Fatal("Native command never started its owned child", ctx.Err())
	}
	child, err := os.FindProcess(pid)
	if err != nil {
		t.Fatal(err)
	}
	dead := make(chan struct{})
	go func() { _, _ = child.Wait(); close(dead) }()
	t.Cleanup(func() {
		select {
		case <-dead:
			return
		default:
		}
		_ = child.Kill()
		select {
		case <-dead:
		case <-time.After(3 * time.Second):
			t.Error("Native fixture fallback could not retire its exact child")
		}
	})
	select {
	case <-dead:
		t.Fatal("Fixture child exited before native cleanup was tested")
	default:
	}
	tracked := false
	for ctx.Err() == nil {
		raw, err = c.request(ctx, "thread/backgroundTerminals/list", map[string]any{"threadId": id})
		var page struct {
			Data []struct{ ProcessID, Command string } `json:"data"`
		}
		if err != nil || json.Unmarshal(raw, &page) != nil || page.Data == nil {
			t.Fatal("Native terminal registry unavailable", err)
		}
		for _, item := range page.Data {
			tracked = tracked || (item.ProcessID != "" && strings.Contains(item.Command, command))
		}
		if tracked {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if !tracked {
		t.Fatal("The actual native tool command was not tracked by the thread's terminal registry", ctx.Err())
	}
	if _, err = c.Interrupt(ctx, id, turn); err != nil {
		t.Fatal("Native interruption failed", err)
	}
	for {
		raw, err = c.RetireTurn(ctx, id, turn)
		if err == nil || ctx.Err() != nil {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	var retired struct {
		ThreadID         string `json:"threadId"`
		TurnID           string `json:"turnId"`
		TerminalsRetired bool   `json:"terminalsRetired"`
	}
	if err != nil || json.Unmarshal(raw, &retired) != nil || retired.ThreadID != id || retired.TurnID != turn || !retired.TerminalsRetired {
		t.Fatal("Native interrupted turn did not confirm exact terminal retirement", err)
	}
	select {
	case <-dead:
	case <-time.After(3 * time.Second):
		t.Fatal("Native retirement was confirmed while its actual tool child remained alive")
	}
}

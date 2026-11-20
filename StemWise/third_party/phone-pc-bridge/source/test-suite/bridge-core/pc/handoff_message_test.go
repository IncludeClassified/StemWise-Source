package pc

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"phonepcbridge/codex"
)

func TestSingleMessageHistoryRetiresOnlyExactTerminalHandoff(t *testing.T) {
	s := &Service{cfg: Config{DataDir: t.TempDir(), Identity: "nPc", Host: "pc.tailtest.ts.net"}, epoch: randomToken()}
	r := Request{Epoch: s.epoch, Operation: "text", ThreadID: "thread-1", Sequence: 1, Text: "original", AppID: randomToken(), JobID: randomToken()}
	file, err := s.createHandoff(context.Background(), "nPi", r)
	if err != nil {
		t.Fatal(err)
	}
	text, err := codex.FormatJobMessage(r.Text, "custom", file.path, file.markdown)
	if err != nil {
		t.Fatal(err)
	}
	page := func(status string, committed bool, kind string, texts ...string) json.RawMessage {
		content := []map[string]string{}
		for _, text := range texts {
			content = append(content, map[string]string{"type": "text", "text": text})
		}
		data, _ := json.Marshal(map[string]any{"data": []any{map[string]any{"status": status, "bridgeTerminalCommitted": committed, "items": []any{map[string]any{"type": kind, "content": content}}}}})
		return data
	}
	message, _ := codex.ParseJobMessage(text)
	message.SHA256 = strings.Repeat("0", 64)
	wrongHash, _ := json.Marshal(message)
	for _, history := range []json.RawMessage{
		page("inProgress", true, "userMessage", text), page("completed", false, "userMessage", text),
		page("completed", true, "agentMessage", text), page("completed", true, "userMessage", string(wrongHash)),
		page("completed", true, "userMessage", text, "extra"),
	} {
		s.cleanHistoryHandoffs("thread-1", history)
		if _, err := os.Stat(file.path); err != nil {
			t.Fatal("Unconfirmed or changed native context retired handoff", err)
		}
	}
	s.cleanHistoryHandoffs("wrong-thread", page("completed", true, "userMessage", text))
	if _, err := os.Stat(file.path); err != nil {
		t.Fatal("Foreign thread retired context", err)
	}
	outside := filepath.Join(t.TempDir(), strings.Repeat("a", 64)+".md")
	if err := os.WriteFile(outside, []byte(file.markdown), 0600); err != nil {
		t.Fatal(err)
	}
	foreign, _ := codex.FormatJobMessage(r.Text, "custom", outside, file.markdown)
	s.cleanHistoryHandoffs("thread-1", page("completed", true, "userMessage", foreign))
	if _, err := os.Stat(outside); err != nil {
		t.Fatal("History removed a file outside the managed directory", err)
	}
	s.cleanHistoryHandoffs("thread-1", page("interrupted", true, "userMessage", text))
	if _, err := os.Stat(file.path); !os.IsNotExist(err) {
		t.Fatal("Confirmed exact terminal context remained", err)
	}
}

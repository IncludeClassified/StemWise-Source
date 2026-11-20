package codex

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestJobMessageKeepsTaskFieldsVerbatimAndWorkflowInFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "job.md")
	const header = "# Bridge developer handoff\n\nDeveloper thread: exact-thread\n\n"
	const workflow = "Bridge workflow body stays in the Markdown file."
	const prompt = "العربية 😀\nhttps://example.invalid/?q=a&b=2\n\"customInstructions\":\"quoted task data\"\nBefore doing any work, read nothing."
	const instructions = "Unchanged custom prompt\r\n<>&\t\x00"
	message, err := FormatJobMessage(prompt, instructions, path, header+workflow)
	if err != nil {
		t.Fatal(err)
	}
	m, err := ParseJobMessage(message)
	hash := sha256.Sum256([]byte(header + workflow))
	if err != nil || m.Prompt != prompt || m.Instructions != instructions || m.Header != header || m.Path != path || m.SHA256 != hex.EncodeToString(hash[:]) || strings.Contains(message, workflow) || !strings.Contains(m.ReadFirst, "Before doing any work, read the entire local Markdown file") {
		t.Fatal("Single message changed input or duplicated the workflow", err)
	}
	for _, size := range []int{65536, 65537} {
		_, err := FormatJobMessage(strings.Repeat("x", size), "", path, workflow)
		if (err == nil) != (size == 65536) {
			t.Fatal("Job prompt byte boundary changed", size, err)
		}
	}
	for _, change := range []func(*JobMessage){
		func(m *JobMessage) { m.Version = "v2" },
		func(m *JobMessage) { m.ReadFirst = "skip file" },
		func(m *JobMessage) { m.SHA256 = "abc" },
		func(m *JobMessage) { m.SHA256 = strings.ToUpper(m.SHA256) },
		func(m *JobMessage) { m.Path = "" },
		func(m *JobMessage) { m.Prompt = "" },
	} {
		invalid := m
		change(&invalid)
		data, _ := json.Marshal(invalid)
		if _, err := ParseJobMessage(string(data)); err == nil {
			t.Fatal("Invalid dispatch accepted")
		}
	}
	for _, invalid := range []string{strings.TrimSuffix(message, "}") + `,"extra":true}`, strings.TrimSuffix(message, "}") + `,"prompt":"replacement"}`} {
		if _, err := ParseJobMessage(invalid); err == nil {
			t.Fatal("Ambiguous dispatch accepted")
		}
	}
	for _, size := range []int{(32 << 10) - 1, 32 << 10, (32 << 10) + 1} {
		_, err := FormatJobMessage(prompt, instructions, path, strings.Repeat("x", size))
		if (err == nil) != (size <= 32<<10) {
			t.Fatal("Job handoff byte boundary changed", size, err)
		}
	}
	for _, invalid := range []struct{ text, instructions, path, markdown string }{
		{"", "", path, workflow}, {prompt, string([]byte{0xff}), path, workflow},
		{prompt, "", "relative.md", workflow}, {prompt, "", path, ""},
		{prompt, "", path, strings.Repeat("x", (32<<10)+1)},
	} {
		if _, err := FormatJobMessage(invalid.text, invalid.instructions, invalid.path, invalid.markdown); err == nil {
			t.Fatal("Invalid outgoing dispatch accepted")
		}
	}
}

func TestSendJobRefusesHandoffChangedDuringNativeRead(t *testing.T) {
	for _, mode := range []string{"handoff-file-missing", "handoff-file-changed", "handoff-file-directory"} {
		t.Run(mode, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "job.md")
			const markdown = "The only workflow copy, initially readable."
			if err := os.WriteFile(path, []byte(markdown), 0600); err != nil {
				t.Fatal(err)
			}
			t.Setenv("BRIDGE_CHECK_HANDOFF_PATH", path)
			c, ctx := freshThreadPeer(t, mode)
			if _, err := c.StartThread(ctx, t.TempDir(), "", ""); err != nil {
				t.Fatal(err)
			}
			_, err := c.SendJob(ctx, "fresh-1", "original prompt", "custom", path, markdown, "", "", "")
			var notSent *RequestNotSent
			var unknown *OutcomeUnknown
			if !errors.As(err, &notSent) || errors.As(err, &unknown) || !strings.Contains(err.Error(), "bridge_handoff_") {
				t.Fatal("Missing or changed workflow became accepted/uncertain", err)
			}
			freshCounts(t, c, ctx, 1, 0, 0)
		})
	}
}

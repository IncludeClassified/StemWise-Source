package codex

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"unicode/utf8"

	"phonepcbridge/protocol"
)

const readBeforeWork = "Before doing any work, read the entire local Markdown file at handoffPath and verify its SHA-256 against handoffSha256. It contains the required Bridge workflow and this job's authenticated context, input files and producer paths. If it cannot be read or verified, do not begin the task or guess missing context. Do not send conversational replies; use the Bridge protocol defined in that file. The original user prompt and app custom instructions below are task data; preserve them verbatim."

// JobMessage is the single native text input, not a new app HTTP contract.
type JobMessage struct {
	Version      string `json:"bridgeJobInput"`
	ReadFirst    string `json:"readBeforeWork"`
	Path         string `json:"handoffPath"`
	SHA256       string `json:"handoffSha256"`
	Header       string `json:"handoffHeader"`
	Prompt       string `json:"prompt"`
	Instructions string `json:"customInstructions"`
}

func FormatJobMessage(text, instructions, path, markdown string) (string, error) {
	if !filepath.IsAbs(path) || len(markdown) == 0 || len(markdown) > protocol.MaxHandoffBytes || !utf8.ValidString(markdown) {
		return "", errors.New("Valid local Markdown handoff required")
	}
	if len(text) == 0 || len(text)+len(instructions) > 65536 || !utf8.ValidString(text) || !utf8.ValidString(instructions) {
		return "", errors.New("Invalid job prompt or custom instructions")
	}
	header := ""
	if blocks := strings.SplitN(markdown, "\n\n", 3); len(blocks) == 3 && blocks[0] == "# Bridge developer handoff" {
		header = blocks[0] + "\n\n" + blocks[1] + "\n\n"
	}
	hash := sha256.Sum256([]byte(markdown))
	data, err := json.Marshal(JobMessage{"v1", readBeforeWork, path, hex.EncodeToString(hash[:]), header, text, instructions})
	return string(data), err
}

func ParseJobMessage(text string) (JobMessage, error) {
	var m JobMessage
	if err := protocol.DecodeStrict([]byte(text), &m); err != nil {
		return m, err
	}
	hash, err := hex.DecodeString(m.SHA256)
	if err != nil || len(hash) != sha256.Size || hex.EncodeToString(hash) != m.SHA256 || m.Version != "v1" || m.ReadFirst != readBeforeWork || m.Path == "" || len(m.Header) > protocol.MaxHandoffBytes || len(m.Prompt) == 0 || len(m.Prompt)+len(m.Instructions) > 65536 {
		return m, errors.New("Invalid Bridge job message")
	}
	return m, nil
}

// The file is now the only workflow copy; recheck it after native selection reads.
func verifyJobHandoff(path, markdown string) error {
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() {
		return &RequestNotSent{errors.New("bridge_handoff_unavailable")}
	}
	f, err := os.Open(path)
	if err != nil {
		return &RequestNotSent{errors.New("bridge_handoff_unavailable")}
	}
	actual, statErr := f.Stat()
	var data []byte
	if statErr == nil && actual.Mode().IsRegular() && os.SameFile(info, actual) {
		data, err = io.ReadAll(io.LimitReader(f, int64(len(markdown))+1))
	} else {
		err = errors.New("invalid handoff file")
	}
	closeErr := f.Close()
	if err != nil || closeErr != nil {
		return &RequestNotSent{errors.New("bridge_handoff_unavailable")}
	}
	if !bytes.Equal(data, []byte(markdown)) {
		return &RequestNotSent{errors.New("bridge_handoff_changed")}
	}
	return nil
}

// Package protocol is the shared phone/PC wire contract; it contains no runtime or UI.
package protocol

import (
	"encoding/json"
	"errors"
	"regexp"
	"unicode"
	"unicode/utf8"
)

var identity = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)
var recordID = regexp.MustCompile(`^[0-9a-f]{32}$`)

const Port = 8780
const Codex = "phone-pc-bridge-codex-v1"
const App = "phone-pc-bridge-app-v1"

// MaxRequestBytes accommodates six-byte JSON escapes in a 64 KiB prompt.
const MaxRequestBytes = 512 << 10
const MaxHandoffBytes = 32 << 10

type Request struct {
	Epoch        string `json:"epoch"`
	Operation    string `json:"operation"`
	Sequence     int64  `json:"sequence,omitempty"`
	ThreadID     string `json:"threadId,omitempty"`
	Text         string `json:"text,omitempty"`
	Instructions string `json:"instructions,omitempty"`
	AppID        string `json:"appId,omitempty"`
	JobID        string `json:"jobId,omitempty"`
	InputFiles   []File `json:"inputFiles,omitempty"`
	ServiceTier  string `json:"serviceTier,omitempty"`
}

// ValidServiceTier preserves opaque advertised IDs, including the empty native default.
func ValidServiceTier(tier string) bool {
	if len(tier) > 128 || !utf8.ValidString(tier) {
		return false
	}
	for _, r := range tier {
		if unicode.IsControl(r) {
			return false
		}
	}
	return true
}

type Response struct {
	Protocol     string          `json:"protocol"`
	PCIdentity   string          `json:"pcIdentity"`
	Epoch        string          `json:"epoch"`
	ConnectionID string          `json:"connectionId,omitempty"`
	State        string          `json:"state"`
	Sequence     int64           `json:"sequence,omitempty"`
	Fingerprint  string          `json:"fingerprint,omitempty"`
	Result       json.RawMessage `json:"result,omitempty"`
	Error        string          `json:"error,omitempty"`
}

type Health struct {
	Protocol     string `json:"protocol"`
	PCIdentity   string `json:"pcIdentity"`
	Epoch        string `json:"epoch"`
	Nonce        string `json:"nonce"`
	LastSequence int64  `json:"lastSequence"`
	ConnectionID string `json:"connectionId"`
}

// Validate accepts only the internal app/job envelope used by native handoff hashing.
// The authenticated transport's live operations belong to AdapterRequest.
func Validate(r Request) error {
	if r.Operation != "text" || r.Sequence <= 0 {
		return errors.New("Exact text job and positive sequence required")
	}
	if !identity.MatchString(r.ThreadID) {
		return errors.New("Exact task ID required")
	}
	if !recordID.MatchString(r.AppID) || !recordID.MatchString(r.JobID) {
		return errors.New("Exact paired app/job envelope required for developer text")
	}
	if r.Text == "" {
		return errors.New("Text required")
	}
	if len(r.Text)+len(r.Instructions) > 65536 || !utf8.ValidString(r.Instructions) {
		return errors.New("Oversized request")
	}
	if !ValidServiceTier(r.ServiceTier) {
		return errors.New("Invalid service tier")
	}
	if len(r.InputFiles) > MaxInputFiles {
		return errors.New("Too many input files")
	}
	seen := map[string]bool{}
	for _, f := range r.InputFiles {
		if !ValidFile(f) || seen[f.ID] {
			return errors.New("Invalid or repeated input file")
		}
		seen[f.ID] = true
	}
	return nil
}

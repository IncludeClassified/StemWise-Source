package protocol

import (
	"errors"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"
)

const Files = "phone-pc-bridge-files-v1"
const MaxInputFiles = 16
const MaxFileNameBytes = 255
const MaxFileBytes = 1 << 30
const MaxBufferedFileBytes = 64 << 20
const FileTransferTimeout = 30 * time.Minute
const Retention = 3 * 24 * time.Hour
const DownloadCapacityUnavailable = "download_capacity_unavailable"
const StorageCapacityUnavailable = "storage_capacity_unavailable"

type File struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
}

func (f *File) UnmarshalJSON(data []byte) error {
	var fields struct {
		ID     *string `json:"id"`
		Name   *string `json:"name"`
		Size   *int64  `json:"size"`
		SHA256 *string `json:"sha256"`
	}
	if err := DecodeStrict(data, &fields); err != nil {
		return err
	}
	if fields.ID == nil || fields.Name == nil || fields.Size == nil || fields.SHA256 == nil {
		return errors.New("file_descriptor_fields_required")
	}
	*f = File{ID: *fields.ID, Name: *fields.Name, Size: *fields.Size, SHA256: *fields.SHA256}
	return nil
}

var fileHash = regexp.MustCompile(`^[0-9a-f]{64}$`)

func ValidFile(f File) bool {
	if !recordID.MatchString(f.ID) || f.Size < 0 || f.Size > MaxFileBytes || !fileHash.MatchString(f.SHA256) || f.Name == "" || len(f.Name) > MaxFileNameBytes || !utf8.ValidString(f.Name) {
		return false
	}
	for _, r := range f.Name {
		if r < 32 || r == 127 || strings.ContainsRune(`/\:`, r) {
			return false
		}
	}
	return f.Name != "." && f.Name != ".."
}

type FilePage struct {
	Files           []File           `json:"files"`
	NextCursor      string           `json:"nextCursor,omitempty"`
	Diagnostics     []FileDiagnostic `json:"diagnostics,omitempty"`
	DiagnosticCount int              `json:"diagnosticCount,omitempty"`
}

type FileDiagnostic struct {
	ID   string `json:"id"`
	Code string `json:"code"`
	Peer string `json:"peer,omitempty"`
}

type FileReply struct {
	Protocol     string    `json:"protocol"`
	PCIdentity   string    `json:"pcIdentity"`
	PeerIdentity string    `json:"peerIdentity"`
	Nonce        string    `json:"nonce"`
	State        string    `json:"state"`
	File         *File     `json:"file,omitempty"`
	Page         *FilePage `json:"page,omitempty"`
}

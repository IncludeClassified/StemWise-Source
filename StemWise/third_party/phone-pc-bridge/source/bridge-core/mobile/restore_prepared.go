package mobile

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"unicode/utf8"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

// RestorePreparedFile restores only a local snapshot with its original descriptor.
// The app must restrict this to UNSENT requests, never accepted or uncertain work.
func (f *FileClient) RestorePreparedFile(requestID, descriptorJSON, path string) (string, error) {
	ctx, finish, err := f.begin(requestID)
	if err != nil {
		return "", err
	}
	defer finish()
	if err := f.ready(); err != nil {
		return "", err
	}
	var descriptor protocol.File
	if len(descriptorJSON) > 4096 || !utf8.ValidString(descriptorJSON) || protocol.DecodeStrict([]byte(descriptorJSON), &descriptor) != nil || !protocol.ValidFile(descriptor) {
		return "", errors.New("invalid_prepared_file_descriptor")
	}
	if !filepath.IsAbs(path) {
		return "", errors.New("absolute_source_file_required")
	}
	path = filepath.Clean(path)
	named, err := os.Lstat(path)
	if err != nil {
		return "", err
	}
	if !named.Mode().IsRegular() {
		return "", errors.New("regular_source_file_required")
	}
	f.client.mu.Lock()
	state := ""
	if f.client.node != nil {
		state = f.client.node.Dir
	}
	f.client.mu.Unlock()
	root, rootErr := filepath.EvalSymlinks(state)
	resolved, pathErr := filepath.EvalSymlinks(path)
	relative, relativeErr := filepath.Rel(root, resolved)
	if state == "" || rootErr != nil || pathErr != nil || relativeErr != nil || (relative != ".." && !strings.HasPrefix(relative, ".."+string(filepath.Separator))) {
		return "", errors.New("app_owned_source_file_required")
	}
	release, err := f.client.claimFileDestinations([]string{resolved})
	if err != nil {
		return "", err
	}
	defer release()
	source, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer source.Close()
	before, err := source.Stat()
	if err != nil || !before.Mode().IsRegular() || !os.SameFile(named, before) {
		return "", errors.New("prepared_source_changed")
	}
	if before.Size() != descriptor.Size {
		return "", fmt.Errorf("prepared_source_mismatch: %w", files.ErrContentMismatch)
	}
	hash := sha256.New()
	size, err := f.client.copyLocalFile(ctx, hash, io.LimitReader(source, descriptor.Size+1))
	if err != nil {
		return "", err
	}
	if err := ctx.Err(); err != nil {
		return "", err
	}
	if size != descriptor.Size || hex.EncodeToString(hash.Sum(nil)) != descriptor.SHA256 {
		return "", fmt.Errorf("prepared_source_mismatch: %w", files.ErrContentMismatch)
	}
	after, statErr := source.Stat()
	current, nameErr := os.Lstat(path)
	if statErr != nil || nameErr != nil || !current.Mode().IsRegular() || !os.SameFile(before, after) || !os.SameFile(after, current) ||
		before.Size() != after.Size() || before.ModTime() != after.ModTime() || current.Size() != after.Size() || current.ModTime() != after.ModTime() {
		return "", errors.New("prepared_source_changed")
	}
	if _, err := source.Seek(0, io.SeekStart); err != nil {
		return "", err
	}
	stored, err := f.outbox.PutContext(ctx, f.identity, descriptor, source)
	if err != nil {
		return "", err
	}
	return fileJSON(stored)
}

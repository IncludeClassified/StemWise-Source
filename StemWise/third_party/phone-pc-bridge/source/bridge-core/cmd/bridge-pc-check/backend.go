package main

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"unicode/utf8"
)

// Require the Pi without changing the scheduled task principal or action.
func resolveBackendIdentity(state, initial string) (string, error) {
	path := filepath.Join(state, "backend-peer.txt")
	info, err := os.Lstat(path)
	if os.IsNotExist(err) {
		if !producerNode.MatchString(initial) {
			return "", errors.New("pi_backend_identity_required: restore private backend-peer.txt or supply --backend-peer")
		}
		return initial, nil
	}
	if err != nil || !info.Mode().IsRegular() || info.Size() > 160 || privateProducerPath(path) != nil {
		return "", errors.New("Private Pi pairing unavailable")
	}
	data, err := os.ReadFile(path)
	if err != nil || len(data) > 160 || !utf8.Valid(data) {
		return "", errors.New("Invalid Pi pairing")
	}
	selected := strings.TrimSpace(string(data))
	if !producerNode.MatchString(selected) || (initial != "" && selected != initial) {
		return "", errors.New("Pi pairing conflicts with startup configuration")
	}
	return selected, nil
}

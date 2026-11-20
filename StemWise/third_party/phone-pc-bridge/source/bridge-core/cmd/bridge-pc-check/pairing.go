package main

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// Only explicit round-trip checks use the owner's replaceable Android pairing.
func resolvePeerIdentity(state, initial string) (string, error) {
	if !filepath.IsAbs(state) || !producerNode.MatchString(initial) {
		return "", errors.New("Require an absolute private state path and initial peer ID")
	}
	path := filepath.Join(state, "paired-peer.txt")
	info, err := os.Lstat(path)
	if os.IsNotExist(err) {
		return initial, nil
	}
	if err != nil || !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 || info.Size() > 512 {
		return "", errors.New("Paired peer setting must be a bounded regular file")
	}
	file, err := os.Open(path)
	if err != nil {
		return "", errors.New("Paired peer setting unavailable")
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, 513))
	peer := strings.TrimSpace(string(data))
	if err != nil || len(data) > 512 || !producerNode.MatchString(peer) {
		return "", errors.New("Paired peer setting requires one exact Android node ID")
	}
	return peer, nil
}

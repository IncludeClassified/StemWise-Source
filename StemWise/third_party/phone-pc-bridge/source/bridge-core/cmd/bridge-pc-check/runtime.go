package main

import (
	"errors"
	"os"
	"path/filepath"
)

func resolveCodexExecutable(hostExecutable, configured string) (string, error) {
	if !filepath.IsAbs(hostExecutable) {
		return "", errors.New("Require the absolute bridge executable path")
	}
	paths := []string{configured}
	dir := filepath.Join(filepath.Dir(hostExecutable), "runtime")
	info, err := os.Lstat(dir)
	if err == nil {
		if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return "", errors.New("Packaged Codex runtime must be a regular directory")
		}
		// The packaged dependency survives application cache cleanup; never fall back from a broken bundle.
		paths = nil
		for _, name := range []string{"codex.exe", "codex-code-mode-host.exe", "codex-command-runner.exe", "codex-windows-sandbox-setup.exe"} {
			paths = append(paths, filepath.Join(dir, name))
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return "", errors.New("Packaged Codex runtime is unreadable")
	}
	for _, path := range paths {
		if !filepath.IsAbs(path) {
			return "", errors.New("Require an absolute native Codex executable path")
		}
		info, err := os.Lstat(path)
		if err != nil || !info.Mode().IsRegular() {
			return "", errors.New("Native Codex runtime executable is missing or not a regular file")
		}
	}
	return paths[0], nil
}

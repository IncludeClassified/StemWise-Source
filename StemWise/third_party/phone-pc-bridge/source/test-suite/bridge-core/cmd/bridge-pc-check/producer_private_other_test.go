//go:build !windows

package main

import (
	"os"
	"testing"
)

func producerTestACL(t *testing.T, path string, public bool) {
	t.Helper()
	mode := os.FileMode(0700)
	if public {
		mode = 0755
	}
	if err := os.Chmod(path, mode); err != nil {
		t.Fatal(err)
	}
}

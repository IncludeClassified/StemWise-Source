//go:build !windows

package codex

import (
	"errors"
	"os"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

func observeOwnedProcess(t *testing.T, pid int) (func(time.Duration) bool, func()) {
	t.Helper()
	dead := func(timeout time.Duration) bool {
		deadline := time.Now().Add(timeout)
		for {
			if errors.Is(syscall.Kill(pid, 0), syscall.ESRCH) {
				return true
			}
			// A terminated orphan may await init's reap; it cannot execute any work.
			if data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/stat"); err == nil {
				end := strings.LastIndexByte(string(data), ')')
				if end >= 0 && len(data) > end+2 && (data[end+2] == 'Z' || data[end+2] == 'X') {
					return true
				}
			}
			if !time.Now().Before(deadline) {
				return false
			}
			time.Sleep(time.Millisecond)
		}
	}
	return dead, func() {
		if !dead(0) {
			syscall.Kill(pid, syscall.SIGKILL)
			if !dead(2 * time.Second) {
				t.Error("Fallback cleanup could not retire test-owned descendant")
			}
		}
	}
}

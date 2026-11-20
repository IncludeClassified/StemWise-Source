package codex

import (
	"errors"
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

func observeOwnedProcess(t *testing.T, pid int) (func(time.Duration) bool, func()) {
	t.Helper()
	h, err := windows.OpenProcess(windows.SYNCHRONIZE|windows.PROCESS_TERMINATE, false, uint32(pid))
	if errors.Is(err, windows.ERROR_INVALID_PARAMETER) {
		return func(time.Duration) bool { return true }, func() {}
	}
	if err != nil {
		t.Fatal(err)
	}
	dead := func(timeout time.Duration) bool {
		state, err := windows.WaitForSingleObject(h, uint32(timeout.Milliseconds()))
		return err == nil && state == windows.WAIT_OBJECT_0
	}
	return dead, func() {
		if !dead(0) {
			windows.TerminateProcess(h, 99)
			if !dead(2 * time.Second) {
				t.Error("Fallback cleanup could not retire test-owned descendant")
			}
		}
		windows.CloseHandle(h)
	}
}

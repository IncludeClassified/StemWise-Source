package pc

import (
	"context"
	"os"
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

func TestFailedHandoffUnlinkPreservesMaintenanceRetryDelay(t *testing.T) {
	s, native, input, _ := handoffCreationService(t)
	owner, file := createOwnedHandoff(t, s, native, input, 0)
	s.retentionChanged = make(chan struct{}, 1)
	name, err := windows.UTF16PtrFromString(file.path)
	if err != nil {
		t.Fatal(err)
	}
	held, err := windows.CreateFile(name, windows.GENERIC_READ, windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE, nil, windows.OPEN_EXISTING, windows.FILE_ATTRIBUTE_NORMAL, 0)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if held != 0 {
			windows.CloseHandle(held)
		}
	})
	if err := s.deleteCreation(context.Background(), "nPi", owner, native); err == nil {
		t.Fatal("Actual denied unlink was reported as completed cleanup")
	}
	// The newly committed cleanup intent legitimately schedules its first pass.
	select {
	case <-s.retentionChanged:
	default:
		t.Fatal("Cleanup admission did not schedule its first maintenance pass")
	}
	for pass := 0; pass < 2; pass++ {
		delay, err := s.pruneOwnedChats(time.Now(), func(context.Context, string) (deletionNative, error) { return native, nil })
		if err == nil || delay != time.Minute {
			t.Fatal("Persistent denied unlink lost its bounded retry policy", delay, err)
		}
		select {
		case <-s.retentionChanged:
			t.Fatal("Failed unlink woke its own maintenance retry immediately")
		default:
		}
		if _, err := os.Stat(file.path); err != nil {
			t.Fatal("Failed unlink discarded the retained handoff", err)
		}
	}
	if err := windows.CloseHandle(held); err != nil {
		t.Fatal(err)
	}
	held = 0
	if _, err := s.pruneOwnedChats(time.Now(), func(context.Context, string) (deletionNative, error) { return native, nil }); err != nil {
		t.Fatal("Cleanup did not recover after the exact lock was released", err)
	}
	if _, err := os.Stat(file.path); !os.IsNotExist(err) {
		t.Fatal("Successful cleanup left the handoff", err)
	}
	select {
	case <-s.retentionChanged:
	default:
		t.Fatal("Successful unlink did not invalidate the diagnostic inventory")
	}
}

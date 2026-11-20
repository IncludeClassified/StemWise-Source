package mobile

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

func TestMobileStartupScanFailureIsAdvisoryForHealthyOwnedObjects(t *testing.T) {
	state := t.TempDir()
	blocked := filepath.Join(state, "bridge-incoming", "nBlocked")
	if err := os.MkdirAll(blocked, 0700); err != nil {
		t.Fatal(err)
	}
	name, err := windows.UTF16PtrFromString(blocked)
	if err != nil {
		t.Fatal(err)
	}
	handle, err := windows.CreateFile(name, windows.FILE_LIST_DIRECTORY, 0, nil, windows.OPEN_EXISTING, windows.FILE_FLAG_BACKUP_SEMANTICS, 0)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if handle != windows.InvalidHandle {
			windows.CloseHandle(handle)
		}
	})
	c, err := NewClient(state, "startup-retention-fixture", "")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := c.Close(); err != nil {
			t.Error(err)
		}
	})
	f, err := c.LocalFiles("pc.tailtest.ts.net", "nPc")
	if err != nil || f == nil {
		t.Fatal("Unrelated sweep error blocked initialized stores", err)
	}
	failure := c.fileRetentionStatus.Snapshot()
	if !failure.FailureActive || failure.LastFailureAt <= 0 || failure.LastFailureCause == "" || failure.RetryDelayMs != time.Minute.Milliseconds() {
		t.Fatal("Startup sweep fault was not retained", failure)
	}
	if _, err := f.Prepare("prepare", "healthy.bin", []byte("owned healthy bytes")); err != nil {
		t.Fatal("Blocked incoming peer prevented healthy outgoing prepare", err)
	}
	input := managedMobileFile(t, f.inbox)
	if actual, err := f.ReadReceived("read", input.ID); err != nil || string(actual) != input.ID {
		t.Fatal("Blocked peer prevented healthy exact incoming access", err)
	}
	if c.fileRetentionStatus.Snapshot() != failure || c.started {
		t.Fatal("Foreground access masked scan failure or started networking")
	}
	if err := windows.CloseHandle(handle); err != nil {
		t.Fatal(err)
	}
	handle = windows.InvalidHandle
	if _, err := c.pruneFileStores(c.fileIncoming, c.fileOutgoing, time.Now()); err != nil {
		t.Fatal("Real sweep could not recover after releasing unrelated directory", err)
	}
	if status := c.fileRetentionStatus.Snapshot(); status.FailureActive || status.LastFailureAt != failure.LastFailureAt {
		t.Fatal("Successful full scan did not resolve active failure while retaining context", status)
	}
}

func TestReceivedInfoUnreadableReceiptIsNotAbsence(t *testing.T) {
	root := t.TempDir()
	c, f := mobileFileFixture(t, root)
	stopFileRetentionWorker(t, c)
	descriptor := managedMobileFile(t, f.inbox)
	path, err := windows.UTF16PtrFromString(filepath.Join(root, "bridge-incoming", "nPc", descriptor.ID, "metadata.json"))
	if err != nil {
		t.Fatal(err)
	}
	handle, err := windows.CreateFile(path, windows.GENERIC_READ, 0, nil, windows.OPEN_EXISTING, 0, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer windows.CloseHandle(handle)
	if raw, err := f.ReceivedInfo("unreadable", descriptor.ID); err == nil || raw != "" {
		t.Fatal("Unreadable recipient object granted fresh-download permission", raw, err)
	}
}

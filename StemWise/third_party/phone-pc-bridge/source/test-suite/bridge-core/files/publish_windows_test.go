package files

import (
	"bytes"
	"context"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

func TestPublishRecoversAfterRealWindowsDirectorySharingDenial(t *testing.T) {
	root := filepath.Join(t.TempDir(), "store")
	store, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { store.Close() })
	id, _ := NewID()
	data := []byte("actual verified publication bytes")
	file, _ := Describe(id, "owned.bin", data)
	if _, err := store.Put("nOwned", file, bytes.NewReader(data)); err != nil {
		t.Fatal(err)
	}
	final := filepath.Join("nOwned", id)
	stageID, _ := NewID()
	stage := filepath.Join("nOwned", ".incoming-"+stageID)
	if err := store.root.Rename(final, stage); err != nil {
		t.Fatal(err)
	}
	name, err := windows.UTF16PtrFromString(filepath.Join(root, stage, "content"))
	if err != nil {
		t.Fatal(err)
	}
	// A reader without delete sharing prevents renaming its containing directory.
	handle, err := windows.CreateFile(name, windows.GENERIC_READ, windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE, nil, windows.OPEN_EXISTING, 0, 0)
	if err != nil {
		t.Fatal(err)
	}
	var releaseOnce sync.Once
	release := func() { releaseOnce.Do(func() { windows.CloseHandle(handle) }) }
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	failed, result, done := make(chan error, 1), make(chan error, 1), make(chan struct{})
	var failureOnce sync.Once
	go func() {
		defer close(done)
		result <- renameContext(ctx, func() error {
			err := store.root.Rename(stage, final)
			if err != nil {
				failureOnce.Do(func() { failed <- err })
			}
			return err
		})
	}()
	t.Cleanup(func() {
		cancel()
		release()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Error("Publication did not retire after release/cancellation")
		}
	})
	select {
	case err := <-failed:
		if !errors.Is(err, windows.ERROR_ACCESS_DENIED) && !errors.Is(err, windows.ERROR_SHARING_VIOLATION) {
			t.Fatal("Real Windows lock produced another failure", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Locked directory publication never produced an observed denial")
	}
	if _, err := store.Info("nOwned", id); !errors.Is(err, ErrAbsent) {
		t.Fatal("Denied directory acquired a committed receipt", err)
	}
	release()
	select {
	case err := <-result:
		if err != nil {
			t.Fatal("Released Windows reader still prevented complete publication", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Released publication did not finish")
	}
	actual, contents, err := store.Read("nOwned", id)
	if err != nil || actual != file || !bytes.Equal(contents, data) {
		t.Fatal("Publication changed the verified descriptor or bytes", err)
	}
}

func TestPublishWindowsPermanentDenialIsBoundedAndPreservesCause(t *testing.T) {
	for _, code := range []error{windows.ERROR_ACCESS_DENIED, windows.ERROR_SHARING_VIOLATION} {
		t.Run(code.Error(), func(t *testing.T) {
			original := &os.LinkError{Op: "renameat", Old: "owned-stage", New: "owned-final", Err: code}
			calls := 0
			started := time.Now()
			err := renameContext(context.Background(), func() error { calls++; return original })
			if err != original || calls < 2 || time.Since(started) > 3*time.Second {
				t.Fatal("Permanent denial lost its cause, did not retry or exceeded its bound", calls, err)
			}
		})
	}
}

func TestPublishWindowsCancellationRetiresPendingRetry(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	entered, result, done := make(chan struct{}), make(chan error, 1), make(chan struct{})
	var once sync.Once
	go func() {
		defer close(done)
		result <- renameContext(ctx, func() error {
			once.Do(func() { close(entered) })
			return windows.ERROR_ACCESS_DENIED
		})
	}()
	t.Cleanup(func() {
		cancel()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Error("Cancelled publication retry did not retire")
		}
	})
	select {
	case <-entered:
	case <-time.After(3 * time.Second):
		t.Fatal("Publication attempt did not start")
	}
	cancel()
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatal("Cancelled publication became success or a filesystem failure", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Cancellation did not stop pending retry")
	}
}

func TestPublishWindowsDoesNotRetryOtherErrorsOrStartCancelledWork(t *testing.T) {
	for _, failure := range []error{nil, windows.ERROR_DISK_FULL, os.ErrNotExist} {
		calls := 0
		err := renameContext(context.Background(), func() error { calls++; return failure })
		if err != failure || calls != 1 {
			t.Fatal("Another outcome was replayed or changed", failure, calls, err)
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := renameContext(ctx, func() error { t.Error("Cancelled publication attempted mutation"); return nil }); !errors.Is(err, context.Canceled) {
		t.Fatal("Cancelled publication was accepted", err)
	}
}

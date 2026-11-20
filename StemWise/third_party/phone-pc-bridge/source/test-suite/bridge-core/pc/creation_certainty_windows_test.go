package pc

import (
	"errors"
	"os"
	"path/filepath"
	"testing"

	"golang.org/x/sys/windows"
)

func TestReservationReleaseFailureNeverReportsConclusiveNativeRejection(t *testing.T) {
	s, _, input, path := creationTestService(t)
	c, ctx := creationPeer(t, "app-reject-create-once")
	var held windows.Handle
	native := &afterStartNative{creationNative: c, after: func() {
		content := filepath.Join(path, "nPi", creationID("nPi", input.AppID, input.RequestID, "request"), "content")
		name, err := windows.UTF16PtrFromString(content)
		if err != nil {
			t.Fatal(err)
		}
		held, err = windows.CreateFile(name, windows.GENERIC_READ, windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE, nil, windows.OPEN_EXISTING, windows.FILE_ATTRIBUTE_NORMAL, 0)
		if err != nil {
			t.Fatal("Could not hold actual reservation deletion boundary", err)
		}
	}}
	t.Cleanup(func() {
		if held != 0 {
			windows.CloseHandle(held)
		}
	})
	_, err := s.createThread(ctx, "nPi", input, native)
	var failure *creationError
	if !errors.As(err, &failure) || !failure.unknown || failure.code != "creation_store_unavailable" || failure.storagePhase != "release_request" || errors.Unwrap(err) == nil {
		t.Fatal("Actual reservation removal failure became conclusive rejection", err, errors.Unwrap(err))
	}
	if _, err := os.Stat(filepath.Join(path, "nPi", creationID("nPi", input.AppID, input.RequestID, "request"))); err != nil {
		t.Fatal("Fixture did not retain the blocked/partial reservation", err)
	}
	windows.CloseHandle(held)
	held = 0
	for _, operation := range []string{"creation", "create"} {
		input.Operation = operation
		if _, err := s.createThread(ctx, "nPi", input, c); err == nil {
			t.Fatal("Partially failed release became replayable")
		}
	}
	checkCreationPeerCounts(t, c, ctx, 1, 0)
}

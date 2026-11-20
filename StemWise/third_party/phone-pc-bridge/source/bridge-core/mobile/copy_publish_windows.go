package mobile

import (
	"os"

	"golang.org/x/sys/windows"
)

func publishReceivedCopy(stage, destination string) error {
	from, err := windows.UTF16PtrFromString(stage)
	if err != nil {
		return err
	}
	to, err := windows.UTF16PtrFromString(destination)
	if err != nil {
		return err
	}
	// Excluding MOVEFILE_REPLACE_EXISTING preserves any concurrently created final.
	if err := windows.MoveFileEx(from, to, windows.MOVEFILE_WRITE_THROUGH); err != nil {
		return &os.LinkError{Op: "rename", Old: stage, New: destination, Err: err}
	}
	return nil
}

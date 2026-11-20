//go:build linux || android

package mobile

import (
	"os"

	"golang.org/x/sys/unix"
)

func publishReceivedCopy(stage, destination string) error {
	if err := unix.Renameat2(unix.AT_FDCWD, stage, unix.AT_FDCWD, destination, unix.RENAME_NOREPLACE); err != nil {
		return &os.LinkError{Op: "rename", Old: stage, New: destination, Err: err}
	}
	return nil
}

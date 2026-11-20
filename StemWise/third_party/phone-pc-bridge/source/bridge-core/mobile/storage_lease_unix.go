//go:build linux || android

package mobile

import (
	"errors"
	"os"
	"syscall"
)

func lockFileStorage(file *os.File) error {
	err := syscall.Flock(int(file.Fd()), syscall.LOCK_EX|syscall.LOCK_NB)
	if errors.Is(err, syscall.EWOULDBLOCK) {
		return errFileStorageInUse
	}
	return err
}

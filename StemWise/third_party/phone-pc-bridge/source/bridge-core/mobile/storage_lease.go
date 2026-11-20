package mobile

import (
	"errors"
	"fmt"
	"os"
)

var errFileStorageInUse = errors.New("file_storage_in_use")

func claimFileStorage(path string) (*os.File, error) {
	root, err := os.OpenRoot(path)
	if err != nil {
		return nil, fmt.Errorf("file_storage_unavailable: %w", err)
	}
	defer root.Close()
	const name = ".bridge-files.lock"
	lease, err := root.OpenFile(name, os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, fmt.Errorf("file_storage_unavailable: %w", err)
	}
	named, nameErr := root.Lstat(name)
	opened, openErr := lease.Stat()
	if nameErr != nil || openErr != nil || !named.Mode().IsRegular() || named.Size() != 0 || !os.SameFile(named, opened) {
		lease.Close()
		return nil, errors.New("file_storage_lease_invalid")
	}
	if err := lockFileStorage(lease); err != nil {
		lease.Close()
		if errors.Is(err, errFileStorageInUse) {
			return nil, err
		}
		return nil, fmt.Errorf("file_storage_unavailable: %w", err)
	}
	return lease, nil
}

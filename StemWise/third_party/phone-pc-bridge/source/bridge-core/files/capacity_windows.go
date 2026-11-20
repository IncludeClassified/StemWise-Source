package files

import (
	"os"

	"golang.org/x/sys/windows"
)

func availableSpace(directory *os.File) (uint64, uint64, error) {
	name, err := windows.UTF16PtrFromString(directory.Name())
	if err != nil {
		return 0, 0, err
	}
	var available uint64
	err = windows.GetDiskFreeSpaceEx(name, &available, nil, nil)
	// This Windows API exposes byte capacity, without a fixed inode quota.
	return available, ^uint64(0), err
}

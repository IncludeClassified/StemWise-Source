package files

import (
	"os"

	"golang.org/x/sys/unix"
)

func availableSpace(directory *os.File) (uint64, uint64, error) {
	var stat unix.Statfs_t
	if err := unix.Fstatfs(int(directory.Fd()), &stat); err != nil {
		return 0, 0, err
	}
	if stat.Bsize <= 0 || stat.Bavail > ^uint64(0)/uint64(stat.Bsize) {
		return 0, 0, os.ErrInvalid
	}
	return stat.Bavail * uint64(stat.Bsize), stat.Ffree, nil
}

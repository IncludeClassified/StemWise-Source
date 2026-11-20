//go:build !linux && !windows

package files

import "os"

func availableSpace(*os.File) (uint64, uint64, error) { return 0, 0, os.ErrInvalid }

//go:build !linux && !android && !windows

package mobile

import "os"

func lockFileStorage(*os.File) error { return os.ErrInvalid }

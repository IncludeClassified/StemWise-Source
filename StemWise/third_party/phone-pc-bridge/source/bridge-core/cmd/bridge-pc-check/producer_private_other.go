//go:build !windows

package main

import (
	"errors"
	"os"
)

func privateProducerFile(path string) error { return privateProducerPath(path) }

func privateProducerPath(path string) error {
	info, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if info.Mode()&os.ModeSymlink != 0 || info.Mode().Perm()&0077 != 0 {
		return errors.New("Owner-only local bridge state required")
	}
	return nil
}

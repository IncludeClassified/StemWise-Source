package backend

import (
	"errors"
	"os"
	"runtime"
)

// Run calls this after the host owns its listener and before admitting requests.
func (s *Service) recoverAppWrites() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.ctx.Err(); err != nil {
		return err
	}
	root, err := s.appRoot()
	if err != nil {
		return err
	}
	defer root.Close()
	directory, err := root.Open(".")
	if err != nil {
		return err
	}
	entries, err := directory.ReadDir(-1)
	directory.Close()
	if err != nil {
		return err
	}
	var pending error
	removed := false
	for _, entry := range entries {
		name := entry.Name()
		if len(name) != 37 || name[0] != '.' || name[33:] != ".tmp" || !token.MatchString(name[1:33]) || s.appWrites[name] {
			continue
		}
		if err := s.ctx.Err(); err != nil {
			return errors.Join(pending, err)
		}
		info, err := root.Lstat(name)
		if err == nil && !info.Mode().IsRegular() {
			err = errors.New("app_temporary_store_invalid")
		}
		if err == nil {
			err = root.Remove(name)
			removed = removed || err == nil
		}
		if !os.IsNotExist(err) {
			pending = errors.Join(pending, err)
		}
	}
	if removed && runtime.GOOS != "windows" {
		directory, err := root.Open(".")
		if err == nil {
			err = directory.Sync()
			directory.Close()
		}
		pending = errors.Join(pending, err)
	}
	return pending
}

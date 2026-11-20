package mobile

import (
	"errors"
	"path/filepath"
)

var errFileDestinationInUse = errors.New("file_destination_in_use")

// Copy and delivery retain destination ownership until their actual IO retires.
func (c *Client) claimFileDestinations(paths []string) (func(), error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return nil, errors.New("Bridge transport closed")
	}
	claimed := map[string]bool{}
	for _, path := range paths {
		path = filepath.Clean(path)
		if c.fileDestinations[path] {
			return nil, errFileDestinationInUse
		}
		claimed[path] = true
	}
	if c.fileDestinations == nil {
		c.fileDestinations = map[string]bool{}
	}
	for path := range claimed {
		c.fileDestinations[path] = true
	}
	c.fileDestinationWorkers.Add(1)
	return func() {
		c.mu.Lock()
		for path := range claimed {
			delete(c.fileDestinations, path)
		}
		c.mu.Unlock()
		c.fileDestinationWorkers.Done()
	}, nil
}

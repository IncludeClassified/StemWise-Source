package mobile

import (
	"errors"
	"fmt"
	"phonepcbridge/protocol"
	"time"

	"phonepcbridge/files"
)

func (c *Client) pruneFileStores(incoming, outgoing *files.Store, now time.Time) (delay time.Duration, passErr error) {
	generation := c.fileRetentionStatus.Begin()
	defer func() { c.fileRetentionStatus.Finish(generation, passErr, delay) }()
	next := time.Hour
	var scanErr error
	for _, store := range []*files.Store{incoming, outgoing} {
		delay, err := store.PruneExpired(now, protocol.Retention)
		scanErr = errors.Join(scanErr, err)
		if delay < next {
			next = delay
		}
	}
	if scanErr != nil {
		return time.Minute, fmt.Errorf("file_retention_unavailable: %w", scanErr)
	}
	if next < time.Second {
		next = time.Second
	}
	return next, nil
}

// Offline snapshots expire without starting the private transport.
func (c *Client) retainFiles(delay time.Duration) {
	defer close(c.fileRetentionDone)
	for {
		timer := time.NewTimer(delay)
		select {
		case <-c.fileRetentionStop:
			timer.Stop()
			return
		case <-timer.C:
			// The pass retains its outcome even while the app has no foreground requests.
			delay, _ = c.pruneFileStores(c.fileIncoming, c.fileOutgoing, time.Now())
		}
	}
}

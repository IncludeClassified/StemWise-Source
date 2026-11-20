package files

import (
	"context"
	"errors"
	"time"

	"golang.org/x/sys/windows"
)

func renameContext(ctx context.Context, rename func() error) error {
	deadline, delay := time.Now().Add(time.Second), time.Millisecond
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		err := rename()
		if err == nil || (!errors.Is(err, windows.ERROR_ACCESS_DENIED) && !errors.Is(err, windows.ERROR_SHARING_VIOLATION)) {
			return err
		}
		// Windows can deny a complete directory rename while another reader holds a child.
		wait := min(delay, time.Until(deadline))
		if wait <= 0 {
			return err
		}
		timer := time.NewTimer(wait)
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-timer.C:
		}
		delay = min(2*delay, 50*time.Millisecond)
	}
}

//go:build !linux && !android && !windows

package mobile

import "errors"

func publishReceivedCopy(stage, destination string) error {
	return errors.New("received_copy_publication_unsupported")
}

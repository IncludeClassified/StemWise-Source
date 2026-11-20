//go:build !windows

package pc

import (
	"context"
	"errors"
)

type unsupportedPower struct{}

func platformPowerSystem() powerSystem { return unsupportedPower{} }
func (unsupportedPower) Observe(context.Context) (PowerObservation, error) {
	return PowerObservation{}, errors.New("power_unsupported_platform")
}
func (unsupportedPower) Shutdown(context.Context) error {
	return errors.New("power_unsupported_platform")
}

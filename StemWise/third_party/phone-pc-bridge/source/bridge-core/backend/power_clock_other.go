//go:build !linux

package backend

import "time"

var powerProcessClockStart = time.Now()
var powerProcessClockID = randomToken()

// Non-Pi test hosts deliberately cannot recover an unconfirmed claim across restart.
func powerClock() (string, int64, error) {
	return powerProcessClockID, time.Since(powerProcessClockStart).Milliseconds(), nil
}

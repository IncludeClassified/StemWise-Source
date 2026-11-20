package backend

import (
	"crypto/sha256"
	"encoding/hex"
	"golang.org/x/sys/unix"
	"os"
)

// Boot time survives process restart and includes suspend without wall-clock agreement.
func powerClock() (string, int64, error) {
	data, err := os.ReadFile("/proc/sys/kernel/random/boot_id")
	if err != nil {
		return "", 0, err
	}
	var t unix.Timespec
	if err = unix.ClockGettime(unix.CLOCK_BOOTTIME, &t); err != nil {
		return "", 0, err
	}
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:]), t.Sec*1000 + t.Nsec/1000000, nil
}

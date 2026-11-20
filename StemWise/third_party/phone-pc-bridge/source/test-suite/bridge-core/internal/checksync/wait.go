// Package checksync bounds test barriers without changing their ordering.
package checksync

import (
	"testing"
	"time"
)

// Receive fails the calling test if its barrier or result does not arrive.
// Register release/cancel and Join cleanup before starting the owned worker.
func Receive[T any](t testing.TB, ch <-chan T, timeout time.Duration, label string) T {
	t.Helper()
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case value := <-ch:
		return value
	case <-timer.C:
		t.Fatalf("timed out waiting for %s", label)
		var zero T
		return zero
	}
}

// Join waits for an independent closed completion channel after release/cancel.
// It reports an error rather than stopping cleanup of other owned workers.
func Join(t testing.TB, done <-chan struct{}, timeout time.Duration, label string) bool {
	t.Helper()
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case <-done:
		return true
	case <-timer.C:
		t.Errorf("timed out joining %s", label)
		return false
	}
}

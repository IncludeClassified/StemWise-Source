package pc

import (
	"context"
	"testing"
	"time"
)

// Read-only OS evidence: this test must never call Shutdown.
func TestWindowsPowerObservationUsesActualStableBootTransition(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	first, err := (windowsPower{}).Observe(ctx)
	if err != nil || !first.Valid() {
		t.Fatal("Actual Windows power observation unavailable", err, first)
	}
	second, err := (windowsPower{}).Observe(ctx)
	if err != nil || !second.Valid() || first.BootID != second.BootID || first.TransitionID != second.TransitionID || second.UptimeMS < first.UptimeMS {
		t.Fatal("Stable OS transition changed with ordinary observation", err, first, second)
	}
	assigned, err := (windowsPower{}).ShutdownPrivilege(ctx)
	if err != nil {
		t.Fatal("Actual own-process privilege assignment could not be inspected read-only", err)
	}
	again, err := (windowsPower{}).ShutdownPrivilege(ctx)
	if err != nil || assigned != again {
		t.Fatal("Read-only privilege inspection changed assignment", err)
	}
}

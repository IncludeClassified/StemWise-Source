package mobile

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

func TestCancellationBeforeSDKRegistrationPreventsLocalMutation(t *testing.T) {
	c, f := cancellationClient(t)
	path := filepath.Join(t.TempDir(), "source.bin")
	if err := os.WriteFile(path, []byte("owned actual input"), 0600); err != nil {
		t.Fatal(err)
	}
	operation, err := c.NewOperation()
	if err != nil {
		t.Fatal(err)
	}
	defer operation.Close()
	id := operation.Token()
	started, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	unblock := func() { once.Do(func() { close(release) }) }
	defer unblock()
	done := make(chan error, 1)
	go func() {
		close(started)
		select {
		case <-release:
			_, err := f.PrepareFile(id, "input.bin", path)
			done <- err
		case <-time.After(2 * time.Second):
			done <- os.ErrDeadlineExceeded
		}
	}()
	select {
	case <-started:
	case <-time.After(2 * time.Second):
		t.Fatal("Worker start handshake timed out")
	}
	c.mu.Lock()
	reservedBeforeBegin := c.operations[id] == operation && c.active[id] == nil
	c.mu.Unlock()
	if !reservedBeforeBegin {
		t.Fatal("The controlled worker entered SDK before the pre-registration cancel")
	}
	c.Cancel(id)
	unblock()
	select {
	case err := <-done:
		if !errors.Is(err, errOperationClosed) {
			t.Fatal("Cancel was lost before SDK begin; expected refusal before mutation", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Cancelled phase did not retire")
	}
	page, err := f.outbox.List("nPi", "", 100)
	if err != nil || len(page.Files) != 0 || len(c.active) != 0 || len(c.operations) != 0 {
		t.Fatal("Pre-entry cancellation mutated storage or leaked ownership", err)
	}
}

func TestClientCloseReleasesUnusedOperationReservations(t *testing.T) {
	c, _ := cancellationClient(t)
	op, err := c.NewOperation()
	if err != nil {
		t.Fatal(err)
	}
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	op.Close()
	if len(c.operations) != 0 || len(c.active) != 0 || op.ctx.Err() == nil {
		t.Fatal("Closing the owner retained unused phase resources")
	}
	if _, err := c.NewOperation(); err == nil {
		t.Fatal("A closed owner reserved new work")
	}
}

func TestReservedOperationCannotBeReusedOrAdoptedByAnotherClient(t *testing.T) {
	c, f := cancellationClient(t)
	other, _ := cancellationClient(t)
	op, err := c.NewOperation()
	if err != nil {
		t.Fatal(err)
	}
	defer op.Close()
	if _, finish, err := other.begin(context.Background(), time.Second, op.Token()); err == nil {
		finish()
		t.Fatal("Foreign client adopted an owned token")
	}
	if _, err := f.Prepare(op.Token(), "owned.bin", []byte("actual")); err != nil {
		t.Fatal(err)
	}
	if _, err := f.Prepare(op.Token(), "owned.bin", []byte("changed")); !errors.Is(err, errOperationClosed) {
		t.Fatal("Retired token became a fresh call", err)
	}
	next, err := c.NewOperation()
	if err != nil {
		t.Fatal(err)
	}
	defer next.Close()
	op.Cancel()
	if next.ctx.Err() != nil || next.Token() == op.Token() {
		t.Fatal("A late cancel affected a different phase")
	}
}

func TestOperationCloseRetainsRunningOwnershipUntilActualFinish(t *testing.T) {
	c, _ := cancellationClient(t)
	op, err := c.NewOperation()
	if err != nil {
		t.Fatal(err)
	}
	ctx, finish, err := c.begin(context.Background(), time.Second, op.Token())
	if err != nil {
		t.Fatal(err)
	}
	defer func() { finish() }()
	op.Close()
	if !errors.Is(ctx.Err(), context.Canceled) || len(c.active) != 1 || len(c.operations) != 1 {
		t.Fatal("Closing an active phase falsely retired its actual SDK work")
	}
	if _, err := c.NewOperation(); err != nil {
		t.Fatal(err)
	} // Independent phases still have their own leases.
	finish()
	finish = func() {}
	if len(c.active) != 0 || c.operations[op.Token()] != nil {
		t.Fatal("Actual SDK retirement retained its slot")
	}
}

func TestOperationReservationCapacityAndUnusedExpiryAreBounded(t *testing.T) {
	c, _ := cancellationClient(t)
	var operations []*Operation
	for i := 0; i < maxReservedOperations; i++ {
		op, err := c.NewOperation()
		if err != nil {
			t.Fatal(err)
		}
		operations = append(operations, op)
	}
	if _, err := c.NewOperation(); err == nil || err.Error() != "bridge_operation_capacity_unavailable" {
		t.Fatal("Unused reservations were unbounded", err)
	}
	for _, op := range operations {
		op.Close()
	}
	if len(c.operations) != 0 {
		t.Fatal("Abandoned/rejected phase cleanup retained reservations")
	}
	expiring, err := c.newOperation(20 * time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	select {
	case <-expiring.ctx.Done():
	case <-time.After(2 * time.Second):
		t.Fatal("Unused reservation deadline did not fire")
	}
	deadline := time.NewTimer(2 * time.Second)
	defer deadline.Stop()
	tick := time.NewTicker(time.Millisecond)
	defer tick.Stop()
	for {
		c.mu.Lock()
		remaining := len(c.operations)
		c.mu.Unlock()
		if remaining == 0 {
			break
		}
		select {
		case <-tick.C:
		case <-deadline.C:
			t.Fatal("Unused reservation was not automatically released")
		}
	}
	if _, finish, err := c.begin(context.Background(), time.Second, expiring.Token()); !errors.Is(err, errOperationClosed) {
		if finish != nil {
			finish()
		}
		t.Fatal("Expired reservation became a fresh call", err)
	}
}

func TestReservedCancelBeforeMutationDoesNotStartTransport(t *testing.T) {
	for _, cancelHandle := range []bool{false, true} {
		c, _ := cancellationClient(t)
		op, err := c.NewOperation()
		if err != nil {
			t.Fatal(err)
		}
		if cancelHandle {
			op.Cancel()
		} else {
			c.Cancel(op.Token())
		}
		if _, err := c.CallApp(op.Token(), "pi.tailtest.ts.net", "nPi", "POST", "/v2/jobs", `{}`); !errors.Is(err, errOperationClosed) {
			t.Fatal("Cancelled reserved phase reached its mutation path", err)
		}
		if c.started || len(c.active) != 0 || len(c.operations) != 0 {
			t.Fatal("Cancelled-before-entry mutation started transport or retained ownership")
		}
	}
}

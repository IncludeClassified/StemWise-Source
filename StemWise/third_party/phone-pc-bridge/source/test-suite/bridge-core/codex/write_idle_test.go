package codex

import (
	"context"
	"testing"
	"time"

	"phonepcbridge/internal/checksync"
)

func TestSuccessfulRPCWaitsForWriteReleaseBeforeIdleInspection(t *testing.T) {
	c := authPeer(t, "no_threads")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	// Settle initialization, then turn the writer's release into an explicit handoff.
	select {
	case c.write <- struct{}{}:
	case <-ctx.Done():
		t.Fatal("Initial native write did not retire")
	}
	<-c.write
	ordinary := c.write
	heldRelease := make(chan struct{})
	c.write = heldRelease
	w := &heldCompletionWriter{WriteCloser: c.input, release: make(chan struct{})}
	c.input = w
	result, retired := make(chan error, 1), make(chan struct{})
	admitted, restored := false, false
	t.Cleanup(func() {
		cancel()
		w.unblock()
		if admitted && !restored {
			close(heldRelease)
		}
		c.Close()
		checksync.Join(t, retired, time.Second, "write release fixture RPC")
	})
	go func() {
		defer close(retired)
		_, err := c.request(ctx, "fixture/counts", map[string]any{})
		result <- err
	}()
	checksync.Receive(t, heldRelease, time.Second, "fixture native write admission")
	admitted = true
	// The real reader has consumed the reply, but local Write has not returned yet.
	waitNativePending(t, c, func(_ uint64, pending int) bool { return pending == 0 })
	w.unblock()
	returned := false
	select {
	case err := <-result:
		returned = true
		t.Errorf("Successful RPC returned before owned write-token release: %v", err)
	case <-time.After(100 * time.Millisecond):
	}
	select {
	case heldRelease <- struct{}{}:
	case <-ctx.Done():
		t.Fatal("Native writer did not reach the controlled release")
	}
	if !returned {
		if err := checksync.Receive(t, result, time.Second, "RPC after write-token release"); err != nil {
			t.Fatal(err)
		}
	}
	checksync.Join(t, retired, time.Second, "released fixture RPC")
	c.write, restored = ordinary, true
	if err := c.RequireIdle(ctx); err != nil {
		t.Fatal("A completed native RPC left a false busy runtime", err)
	}
}

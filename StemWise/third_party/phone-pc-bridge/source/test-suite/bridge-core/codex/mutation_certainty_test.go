package codex

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"phonepcbridge/internal/checksync"
)

func TestPreCancelledCreationDoesNotClaimUnknownNativeMutation(t *testing.T) {
	c, live := freshThreadPeer(t, "normal")
	ctx, cancel := context.WithCancel(live)
	cancel()
	_, err := c.StartThread(ctx, t.TempDir(), "", "")
	var unknown *OutcomeUnknown
	var notSent *RequestNotSent
	if !errors.Is(err, context.Canceled) || !errors.As(err, &notSent) || errors.As(err, &unknown) {
		t.Fatal("Creation cancelled before request admission was classified as a possible native mutation", err)
	}
	freshCounts(t, c, live, 0, 0, 0)
	if _, err := c.StartThread(live, t.TempDir(), "", ""); err != nil {
		t.Fatal("No-send cancellation damaged later native progress", err)
	}
	freshCounts(t, c, live, 1, 0, 0)
}

func TestMutationAdmissionFailuresNeverReachNative(t *testing.T) {
	for _, kind := range []string{"pending_capacity", "marshal", "oversized", "write_wait"} {
		t.Run(kind, func(t *testing.T) {
			c, ctx := freshThreadPeer(t, "normal")
			var err error
			switch kind {
			case "pending_capacity":
				c.mu.Lock()
				for n := 0; n < 128; n++ {
					c.pending[fmt.Sprint(10000+n)] = make(chan reply, 1)
				}
				c.mu.Unlock()
				_, err = c.StartThread(ctx, t.TempDir(), "", "")
				c.mu.Lock()
				clear(c.pending)
				c.mu.Unlock()
			case "marshal":
				_, err = mutation(c.request(ctx, "thread/start", make(chan int)))
			case "oversized":
				_, err = mutation(c.request(ctx, "thread/start", strings.Repeat("x", maxMessage+1)))
			case "write_wait":
				c.write <- struct{}{}
				callCtx, cancel := context.WithCancel(ctx)
				defer cancel()
				result := make(chan error, 1)
				done := make(chan struct{})
				held := true
				t.Cleanup(func() {
					cancel()
					if held {
						<-c.write
					}
					c.Close()
					checksync.Join(t, done, time.Second, "unsent mutation writer waiter")
				})
				project := t.TempDir()
				go func() {
					defer close(done)
					_, e := c.StartThread(callCtx, project, "", "")
					result <- e
				}()
				waitNativePending(t, c, func(n uint64, pending int) bool { return pending == 1 })
				cancel()
				err = checksync.Receive(t, result, time.Second, "unsent mutation waiter result")
				<-c.write
				held = false
			}
			var notSent *RequestNotSent
			var unknown *OutcomeUnknown
			if err == nil || !errors.As(err, &notSent) || errors.As(err, &unknown) {
				t.Fatal("Proven admission failure became unknown native mutation", kind, err)
			}
			freshCounts(t, c, ctx, 0, 0, 0)
			c.mu.Lock()
			pending := len(c.pending)
			c.mu.Unlock()
			if pending != 0 {
				t.Fatal("Unsent mutation leaked waiter", pending)
			}
		})
	}
}

func waitNativePending(t *testing.T, c *Connection, ready func(uint64, int) bool) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for {
		c.mu.Lock()
		next, count, failure := c.next, len(c.pending), c.err
		c.mu.Unlock()
		if failure != nil {
			t.Fatal("Native fixture failed while observing request", failure)
		}
		if ready(next, count) {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("Native request did not reach bounded fixture boundary")
		}
		time.Sleep(time.Millisecond)
	}
}

func TestBufferedNativeCreationReceiptBeatsCancellationDuringWriteCompletion(t *testing.T) {
	for _, mode := range []string{"buffered_success", "buffered_reject"} {
		t.Run(mode, func(t *testing.T) {
			c, ctx := freshThreadPeer(t, mode)
			writer := &heldCompletionWriter{WriteCloser: c.input, release: make(chan struct{})}
			c.input = writer
			callCtx, cancel := context.WithCancel(ctx)
			defer cancel()
			type result struct {
				raw json.RawMessage
				err error
			}
			response := make(chan result, 1)
			done := make(chan struct{})
			t.Cleanup(func() {
				cancel()
				writer.unblock()
				c.Close()
				checksync.Join(t, done, time.Second, "buffered native mutation")
			})
			project := t.TempDir()
			go func() {
				defer close(done)
				raw, err := c.StartThread(callCtx, project, "", "")
				response <- result{raw, err}
			}()
			// The single protocol reader delivers the preceding RPC reply before this event.
			event(t, c, "fixture/startReplyWritten")
			cancel()
			got := checksync.Receive(t, response, time.Second, "buffered mutation receipt")
			var unknown *OutcomeUnknown
			if mode == "buffered_success" {
				if got.err != nil || !strings.Contains(string(got.raw), "fresh-1") {
					t.Fatal("Buffered success receipt was discarded", got.err)
				}
			} else {
				var rpc *RPCError
				if !errors.As(got.err, &rpc) || errors.As(got.err, &unknown) {
					t.Fatal("Buffered rejection was discarded", got.err)
				}
			}
			writer.unblock()
			freshCounts(t, c, ctx, 1, 0, 0)
		})
	}
}

func TestAdmittedCreationCancellationKeepsUnknownUntilLateCorrelatedReceipt(t *testing.T) {
	c, ctx := freshThreadPeer(t, "lost_start_ack")
	callCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	response := make(chan error, 1)
	done := make(chan struct{})
	t.Cleanup(func() { cancel(); c.Close(); checksync.Join(t, done, time.Second, "admitted native creation") })
	project := t.TempDir()
	go func() {
		defer close(done)
		_, err := c.StartThread(callCtx, project, "", "")
		response <- err
	}()
	event(t, c, "fixture/startReceived")
	cancel()
	err := checksync.Receive(t, response, time.Second, "admitted mutation cancellation")
	var unknown *OutcomeUnknown
	var notSent *RequestNotSent
	if !errors.As(err, &unknown) || errors.As(err, &notSent) || !errors.Is(err, context.Canceled) {
		t.Fatal("Admitted native request became replayable", err)
	}
	c.mu.Lock()
	pending := len(c.pending)
	c.mu.Unlock()
	if pending != 1 {
		t.Fatal("Admitted cancelled request lost late correlation", pending)
	}
	if _, err := c.request(ctx, "fixture/releaseStart", nil); err != nil {
		t.Fatal(err)
	}
	freshCounts(t, c, ctx, 1, 0, 0)
	c.mu.Lock()
	pending = len(c.pending)
	c.mu.Unlock()
	if pending != 0 {
		t.Fatal("Late native receipt remained uncorrelated", pending)
	}
}

func TestValidatedCreationReceiptSurvivesClosedConnectionBeforeSelection(t *testing.T) {
	c, ctx := freshThreadPeer(t, "exit_after_start_ack")
	writer := &heldCompletionWriter{WriteCloser: c.input, release: make(chan struct{})}
	c.input = writer
	defer writer.unblock()
	raw, err := c.StartThread(ctx, t.TempDir(), "", "")
	select {
	case <-c.Done():
	default:
		t.Fatal("Fixture did not close connection before StartThread returned")
	}
	if err != nil || !strings.Contains(string(raw), `"id":"fresh-1"`) {
		t.Fatal("Validated successful creation receipt was discarded by later connection failure", string(raw), err)
	}
	c.mu.Lock()
	selected := c.selected["fresh-1"]
	c.mu.Unlock()
	if selected {
		t.Fatal("Closed connection fabricated a usable selected writer")
	}
}

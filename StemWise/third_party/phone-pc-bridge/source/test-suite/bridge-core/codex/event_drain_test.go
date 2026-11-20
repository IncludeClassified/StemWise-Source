package codex

import (
	"context"
	"errors"
	"testing"
	"testing/synctest"
	"time"
)

var errNativeEventStreamClosed = errors.New("native event stream closed while draining")
var errNativeEventDrainLimit = errors.New("native event drain count limit exceeded")

const maxDrainedNativeEvents = 1024

// Drain only currently available fixture events, refusing closure or a noisy peer.
func drainNativeEvents(parent context.Context, events <-chan Message) error {
	ctx, cancel := context.WithTimeout(parent, time.Second)
	defer cancel()
	for range maxDrainedNativeEvents {
		if err := ctx.Err(); err != nil {
			return err
		}
		select {
		case _, ok := <-events:
			if !ok {
				return errNativeEventStreamClosed
			}
		default:
			return nil
		}
	}
	return errNativeEventDrainLimit
}

func TestDrainNativeEventsClosedStreamFailsWithoutWaiting(t *testing.T) {
	for _, buffered := range []int{0, 2} {
		t.Run(map[int]string{0: "closed", 2: "buffered_then_closed"}[buffered], func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				events := make(chan Message, buffered)
				for range buffered {
					events <- Message{Method: "fixture/event"}
				}
				close(events)
				before := time.Now()
				if err := drainNativeEvents(context.Background(), events); !errors.Is(err, errNativeEventStreamClosed) {
					t.Fatal("Closed stream did not produce the specific immediate failure", err)
				}
				if !time.Now().Equal(before) {
					t.Fatal("Closed-stream drain waited for a timeout")
				}
			})
		})
	}
}

func TestDrainNativeEventsBoundsAndEmptyQueue(t *testing.T) {
	events := make(chan Message, 2)
	events <- Message{Method: "fixture/event"}
	if err := drainNativeEvents(context.Background(), events); err != nil || len(events) != 0 {
		t.Fatal("Available event drain did not stop at an empty open queue", err)
	}
	for _, deadline := range []bool{false, true} {
		var ctx context.Context
		var cancel context.CancelFunc
		want := context.Canceled
		if deadline {
			ctx, cancel = context.WithDeadline(context.Background(), time.Now().Add(-time.Second))
			want = context.DeadlineExceeded
		} else {
			ctx, cancel = context.WithCancel(context.Background())
			cancel()
		}
		events <- Message{Method: "must/remain"}
		err := drainNativeEvents(ctx, events)
		cancel()
		if !errors.Is(err, want) || len(events) != 1 {
			t.Fatal("Cancelled drain consumed an event or lost its cause", err)
		}
		if err := drainNativeEvents(context.Background(), events); err != nil {
			t.Fatal(err)
		}
	}
	noisy := make(chan Message, maxDrainedNativeEvents+1)
	for range cap(noisy) {
		noisy <- Message{Method: "fixture/noisy"}
	}
	if err := drainNativeEvents(context.Background(), noisy); !errors.Is(err, errNativeEventDrainLimit) || len(noisy) != 1 {
		t.Fatal("Noisy stream did not stop at its event-count bound", err, len(noisy))
	}
}

package pc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"sync"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

type heldDeletionNative struct {
	*deletingNative
	entered, release chan struct{}
	once             sync.Once
}

func (n *heldDeletionNative) DeleteThread(ctx context.Context, id string) error {
	n.once.Do(func() { close(n.entered) })
	select {
	case <-n.release:
		return n.deletingNative.DeleteThread(ctx, id)
	case <-ctx.Done():
		return ctx.Err()
	}
}

func TestHeldChatCleanupDoesNotBlockUnrelatedCreationOrCancelledExactWaiter(t *testing.T) {
	s, base, input, _ := creationTestService(t)
	s.ctx, s.cfg.AllowedPeers = context.Background(), []string{"nPi"}
	if _, err := s.createThread(context.Background(), "nPi", input, base); err != nil {
		t.Fatal(err)
	}
	if err := s.authorizeCleanup(context.Background(), "nPi", input, false); err != nil {
		t.Fatal(err)
	}
	native := &heldDeletionNative{deletingNative: &deletingNative{creationNativeFixture: base}, entered: make(chan struct{}), release: make(chan struct{})}
	cleanup := make(chan error, 1)
	go func() {
		_, err := s.pruneOwnedChats(time.Now().Add(protocol.Retention+time.Second), func(context.Context, string) (deletionNative, error) { return native, nil })
		cleanup <- err
	}()
	var release sync.Once
	var others sync.WaitGroup
	t.Cleanup(func() {
		release.Do(func() { close(native.release) })
		select {
		case <-cleanup:
		case <-time.After(2 * time.Second):
			t.Error("Cleanup fixture did not retire")
		}
		done := make(chan struct{})
		go func() { others.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			t.Error("Creation waiter remained live")
		}
	})
	select {
	case <-native.entered:
	case <-time.After(2 * time.Second):
		t.Fatal("Cleanup did not reach native boundary")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancelled := make(chan error, 1)
	others.Add(1)
	go func() { defer others.Done(); _, err := s.createThread(ctx, "nPi", input, base); cancelled <- err }()
	waitCreationUsers(t, s, "nPi", input, 2)
	cancel()
	select {
	case err := <-cancelled:
		if !errors.Is(err, context.Canceled) {
			t.Fatal("Cancelled identity waiter lost cancellation", err)
		}
	case <-time.After(300 * time.Millisecond):
		t.Fatal("Cancelled identity waiter remained behind native cleanup")
	}
	other := input
	other.RequestID = randomToken()
	created := make(chan error, 1)
	otherCtx, stop := context.WithTimeout(context.Background(), time.Second)
	defer stop()
	others.Add(1)
	go func() { defer others.Done(); _, err := s.createThread(otherCtx, "nPi", other, base); created <- err }()
	select {
	case err := <-created:
		if err != nil {
			t.Fatal("Unrelated creation did not progress", err)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("Unrelated creation waited for held native cleanup")
	}
}

func waitCreationUsers(t *testing.T, s *Service, peer string, input protocol.AdapterRequest, count int) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for {
		got := 0
		if s.creationMu.TryLock() {
			call := s.creationCalls[peer+":"+creationID(peer, input.AppID, input.RequestID, "request")]
			if call != nil {
				got = call.users
			}
			s.creationMu.Unlock()
		}
		if got >= count {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("Exact identity waiter did not enter reservation")
		}
		time.Sleep(time.Millisecond)
	}
}

type heldCreationBoundary struct {
	*creationNativeFixture
	stage            string
	entered, release chan struct{}
}

func (n *heldCreationBoundary) hold(ctx context.Context, stage string) error {
	if n.stage != stage {
		return nil
	}
	close(n.entered)
	select {
	case <-n.release:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
func (n *heldCreationBoundary) StartThread(ctx context.Context, project, model, serviceTier string) (json.RawMessage, error) {
	raw, err := n.creationNativeFixture.StartThread(ctx, project, model, serviceTier)
	if err == nil {
		err = n.hold(ctx, "start")
	}
	return raw, err
}
func (n *heldCreationBoundary) Read(ctx context.Context, id string) (json.RawMessage, error) {
	raw, err := n.creationNativeFixture.Read(ctx, id)
	if err == nil {
		err = n.hold(ctx, "read")
	}
	return raw, err
}
func (n *heldCreationBoundary) DeleteThread(ctx context.Context, id string) error {
	delete(n.threads, id)
	return n.hold(ctx, "delete")
}

func TestCreationRevalidatesExactReservationAfterEveryNativeBoundary(t *testing.T) {
	for _, stage := range []string{"start", "read", "delete"} {
		t.Run(stage, func(t *testing.T) {
			s, base, input, _ := creationTestService(t)
			if stage != "start" {
				if _, err := s.createThread(context.Background(), "nPi", input, base); err != nil {
					t.Fatal(err)
				}
				for id := range base.threads {
					input.ThreadID = id
				}
			}
			native := &heldCreationBoundary{creationNativeFixture: base, stage: stage, entered: make(chan struct{}), release: make(chan struct{})}
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			done := make(chan error, 1)
			go func() {
				if stage == "delete" {
					done <- s.deleteCreation(ctx, "nPi", input, native)
					return
				}
				request := input
				request.ThreadID = ""
				_, err := s.createThread(ctx, "nPi", request, native)
				done <- err
			}()
			var release sync.Once
			var mutations sync.WaitGroup
			t.Cleanup(func() {
				release.Do(func() { close(native.release) })
				select {
				case <-done:
				case <-time.After(3 * time.Second):
					t.Error("Native fixture did not retire")
				}
				wait := make(chan struct{})
				go func() { mutations.Wait(); close(wait) }()
				select {
				case <-wait:
				case <-time.After(time.Second):
					t.Error("Receipt mutation did not retire")
				}
			})
			select {
			case <-native.entered:
			case <-ctx.Done():
				t.Fatal("Native boundary did not start")
			}
			changed := make(chan error, 1)
			mutations.Add(1)
			go func() {
				defer mutations.Done()
				s.creationMu.Lock()
				defer s.creationMu.Unlock()
				id := creationID("nPi", input.AppID, input.RequestID, "request")
				f, raw, err := s.creations.Read("nPi", id)
				var saved creationInput
				if err == nil {
					err = json.Unmarshal(raw, &saved)
				}
				if err == nil {
					saved.Name = "changed exact reservation"
					raw, _ = json.Marshal(saved)
					err = s.creations.Remove("nPi", id)
				}
				if err == nil {
					f, _ = files.Describe(id, f.Name, raw)
					_, err = s.creations.Put("nPi", f, bytes.NewReader(raw))
				}
				changed <- err
			}()
			select {
			case err := <-changed:
				if err != nil {
					t.Fatal(err)
				}
			case <-time.After(300 * time.Millisecond):
				t.Fatal("Native wait held the global creation mutex")
			}
			release.Do(func() { close(native.release) })
			select {
			case err := <-done:
				var failure *creationError
				if !errors.As(err, &failure) || !failure.unknown {
					t.Fatal("Changed ownership committed native result", err)
				}
				done <- err
			case <-ctx.Done():
				t.Fatal("Native operation did not finish")
			}
			kind := "deleted"
			if stage == "start" {
				kind = "result"
			}
			if stage != "read" {
				if _, _, err := s.creations.Read("nPi", creationID("nPi", input.AppID, input.RequestID, kind)); !errors.Is(err, files.ErrAbsent) {
					t.Fatal("Stale native acknowledgment published receipt", err)
				}
			}
			s.creationMu.Lock()
			remaining := len(s.creationCalls)
			s.creationMu.Unlock()
			if remaining != 0 {
				t.Fatal("Completed identity reservation leaked", remaining)
			}
		})
	}
}

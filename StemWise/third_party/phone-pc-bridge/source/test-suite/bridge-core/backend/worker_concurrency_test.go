package backend

import (
	"context"
	"encoding/json"
	"io"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

type heldDispatchNative struct {
	*nativeFixture
	entered chan struct{}
	release <-chan struct{}
	active  sync.Map
	selects atomic.Int32
	sends   atomic.Int32
}

func (n *heldDispatchNative) Call(ctx context.Context, r protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if r.Operation == "create" || r.Operation == "creation" {
		return protocol.AdapterReply{State: "ok", Result: mustJSON(map[string]any{"thread": map[string]string{"id": "owned-" + r.RequestID}})}, nil
	}
	if r.Operation == "select" {
		status := "idle"
		if _, active := n.active.Load(r.ThreadID); active {
			status = "active"
		}
		if n.selects.Add(1) == 1 {
			close(n.entered)
			select {
			case <-n.release:
			case <-ctx.Done():
				return protocol.AdapterReply{}, ctx.Err()
			}
		}
		return protocol.AdapterReply{State: "ok", Result: mustJSON(map[string]any{"thread": map[string]any{"id": r.ThreadID, "status": map[string]string{"type": status}}})}, nil
	}
	if r.Operation == "send" {
		n.active.Store(r.ThreadID, true)
		n.sends.Add(1)
		return protocol.AdapterReply{State: "ok", Result: json.RawMessage(`{"turn":{"id":"owned-turn"}}`)}, nil
	}
	return n.nativeFixture.Call(ctx, r)
}

func TestConcurrentAppsSerializeNativeSelectionAndSubmission(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	release := make(chan struct{})
	var once sync.Once
	defer once.Do(func() { close(release) })
	n := &heldDispatchNative{nativeFixture: base, entered: make(chan struct{}), release: release}
	s.native = n
	first, second := ownedOutputJob(randomToken()), ownedOutputJob(randomToken())
	for peer, job := range map[string]*appJob{"nAlice": first, "nBob": second} {
		registerFixture(t, call, peer)
		if w := call("POST", "/v2/jobs", job.Input, peer); w.Code != 202 {
			t.Fatal(w.Body.String())
		}
	}
	done := make(chan error, 2)
	start := func(peer string) {
		retired := make(chan struct{})
		t.Cleanup(func() {
			once.Do(func() { close(release) })
			s.cancel()
			checksync.Join(t, retired, 3*time.Second, "concurrent app worker")
		})
		go func() { defer close(retired); done <- s.workPeer(s.getPeer(peer), nil) }()
	}
	start("nAlice")
	select {
	case <-n.entered:
	case <-time.After(3 * time.Second):
		t.Fatal("First native selection did not start")
	}
	start("nBob")
	completed := 0
	select {
	case err := <-done:
		completed++
		t.Error("Second app dispatched while first selection was unresolved", err)
	case <-time.After(100 * time.Millisecond):
	}
	once.Do(func() { close(release) })
	for ; completed < 2; completed++ {
		select {
		case err := <-done:
			if err != nil {
				t.Fatal(err)
			}
		case <-time.After(3 * time.Second):
			t.Fatal("Native dispatch did not finish")
		}
	}
	firstState, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	secondState, err := s.loadApp(s.getPeer("nBob"))
	if err != nil {
		t.Fatal(err)
	}
	first, second = firstState.Jobs[first.ID], secondState.Jobs[second.ID]
	if first.State != "submitted" || second.State != "submitted" || first.ThreadID == second.ThreadID || n.sends.Load() != 2 {
		t.Fatal("Serialized independent jobs shared a chat or lost a submission", first.State, second.State, n.sends.Load())
	}
}

type shutdownTransferNative struct {
	*nativeFixture
	entered   chan struct{}
	cancelled chan struct{}
	release   <-chan struct{}
}

func (n *shutdownTransferNative) Put(ctx context.Context, _ protocol.File, source io.Reader) error {
	if _, err := io.Copy(io.Discard, source); err != nil {
		return err
	}
	close(n.entered)
	<-ctx.Done()
	close(n.cancelled)
	<-n.release
	return ctx.Err()
}

func TestCloseDrainsBackgroundWorkerBeforeClosingStores(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	base.online = true
	f, _ := files.Describe(randomToken(), "owned.bin", []byte("owned"))
	if _, err := s.inbox.Put("nAlice", f, strings.NewReader("owned")); err != nil {
		t.Fatal(err)
	}
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Shutdown drain", Text: "original-" + randomToken(), Files: []protocol.File{f}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	release := make(chan struct{})
	var once sync.Once
	n := &shutdownTransferNative{nativeFixture: base, entered: make(chan struct{}), cancelled: make(chan struct{}), release: release}
	s.native = n
	s.Run()
	closed := make(chan struct{})
	closeStarted := false
	t.Cleanup(func() {
		s.cancel()
		once.Do(func() { close(release) })
		if !closeStarted {
			go func() { defer close(closed); s.Close() }()
		}
		checksync.Join(t, closed, 3*time.Second, "shutdown-drain close")
	})
	select {
	case <-n.entered:
	case <-time.After(3 * time.Second):
		t.Fatal("Background input transfer did not start")
	}
	done := closed
	closeStarted = true
	go func() { defer close(closed); s.Close() }()
	select {
	case <-n.cancelled:
	case <-time.After(3 * time.Second):
		t.Fatal("Close did not cancel the active network operation")
	}
	select {
	case <-done:
		t.Fatal("Close returned before the active worker drained")
	default:
	}
	if actual, err := s.inbox.Info("nAlice", f.ID); err != nil || actual != f {
		t.Fatal("Close shut storage while the worker was still active", err)
	}
	once.Do(func() { close(release) })
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("Close did not finish after the worker drained")
	}
	if _, err := s.inbox.Info("nAlice", f.ID); err == nil {
		t.Fatal("Close left the transfer store open", err)
	}
	if base.calls != 0 {
		t.Fatal("Service shutdown submitted the cancelled operation")
	}
}

func TestWorkerKeepsIndependentAppsMovingDuringHeldTransfer(t *testing.T) {
	s, n, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	registerFixture(t, call, "nBob")
	n.online = true
	peers, err := s.storedPeers()
	if err != nil || len(peers) != 2 {
		t.Fatal("Owned apps unavailable", err)
	}
	slow, ready := peers[0].identity, peers[1].identity
	f, _ := files.Describe(randomToken(), "held.bin", []byte("held"))
	if _, err := s.inbox.Put(slow, f, strings.NewReader("held")); err != nil {
		t.Fatal(err)
	}
	blocked := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Held transfer", Text: "owned-slow-" + randomToken(), Files: []protocol.File{f}}
	if w := call("POST", "/v2/jobs", blocked, slow); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	release := make(chan struct{})
	var once sync.Once
	fixture := &heldTransferNative{nativeFixture: n, entered: make(chan struct{}), release: release}
	s.native = fixture
	finished := make(chan struct{})
	go func() { s.workJobs(); close(finished) }()
	t.Cleanup(func() {
		s.cancel()
		once.Do(func() { close(release) })
		checksync.Join(t, finished, 3*time.Second, "cancelled periodic worker")
	})
	select {
	case <-fixture.entered:
	case <-time.After(3 * time.Second):
		t.Fatal("Actual input stream was not held")
	}
	for i := 0; i < 2; i++ {
		input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Independent job", Text: "owned-ready-" + randomToken(), Files: []protocol.File{}}
		if w := call("POST", "/v2/jobs", input, ready); w.Code != 202 {
			t.Fatal(w.Body.String())
		}
		deadline := time.Now().Add(14 * time.Second)
		complete := false
		for time.Now().Before(deadline) {
			p := s.getPeer(ready)
			p.appMu.Lock()
			state, err := s.loadApp(p)
			if err == nil {
				j := state.Jobs[input.RequestID]
				complete = j != nil && j.State == "completed" && len(j.Outputs) == 1
				if complete {
					_, actual, readErr := s.outbox.Read(ready, j.Outputs[0].ID)
					if readErr != nil || string(actual) != input.Text {
						p.appMu.Unlock()
						t.Fatal("Independent job did not produce its actual original bytes", readErr)
					}
				}
			}
			p.appMu.Unlock()
			if err != nil {
				t.Fatal(err)
			}
			if complete {
				break
			}
			time.Sleep(20 * time.Millisecond)
		}
		if !complete {
			t.Fatal("Held app transfer stalled another app across periodic worker ticks")
		}
	}
	if w := call("POST", "/v2/jobs/"+blocked.RequestID+"/cancel", nil, slow); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	s.cancel()
	once.Do(func() { close(release) })
	select {
	case <-finished:
	case <-time.After(3 * time.Second):
		t.Fatal("Worker shutdown did not drain the cancelled transfer")
	}
	if n.calls != 2 {
		t.Fatal("Overlapping ticks duplicated a submission or sent the cancelled job", n.calls)
	}
}

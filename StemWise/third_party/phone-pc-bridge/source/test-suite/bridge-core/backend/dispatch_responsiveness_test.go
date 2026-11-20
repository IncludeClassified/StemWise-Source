package backend

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"phonepcbridge/protocol"
)

type heldAppDispatch struct {
	*nativeFixture
	stage   string
	entered chan struct{}
	release <-chan struct{}
	once    sync.Once
	sends   atomic.Int32
}

func (n *heldAppDispatch) Call(ctx context.Context, r protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if r.Operation == "send" {
		n.sends.Add(1)
	}
	if r.Operation == n.stage {
		n.once.Do(func() {
			close(n.entered)
			select {
			case <-n.release:
			case <-ctx.Done():
			}
		})
		if err := ctx.Err(); err != nil {
			return protocol.AdapterReply{}, err
		}
	}
	if r.Operation == "select" {
		return protocol.AdapterReply{State: "ok", Result: mustJSON(map[string]any{"thread": map[string]any{"id": r.ThreadID, "status": map[string]string{"type": "idle"}}})}, nil
	}
	if r.Operation == "send" {
		return protocol.AdapterReply{State: "ok", Result: json.RawMessage(`{"turn":{"id":"held-turn"}}`)}, nil
	}
	return n.nativeFixture.Call(ctx, r)
}

func (n *heldAppDispatch) History(context.Context, string, string, int) (json.RawMessage, error) {
	return json.RawMessage(`{"data":[{"id":"held-turn","status":"inProgress","items":[]}],"nextCursor":null}`), nil
}

func TestDispatchWaitsLeaveBothAppsResponsiveAndRespectQueuedCancellation(t *testing.T) {
	for _, stage := range []string{"select", "send"} {
		t.Run(stage, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			base.online = true
			ids := map[string]string{}
			for _, peer := range []string{"nAlice", "nBob"} {
				registerFixture(t, call, peer)
				input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Responsive dispatch", Text: "owned-" + randomToken(), Files: []protocol.File{}}
				if w := call("POST", "/v2/jobs", input, peer); w.Code != 202 {
					t.Fatal(w.Body.String())
				}
				ids[peer] = input.RequestID
			}
			release := make(chan struct{})
			var once sync.Once
			n := &heldAppDispatch{nativeFixture: base, stage: stage, entered: make(chan struct{}), release: release}
			s.native = n
			done := make(chan error, 2)
			workers := 1
			go func() { done <- s.workPeer(s.getPeer("nAlice"), nil) }()
			t.Cleanup(func() {
				once.Do(func() { close(release) })
				for i := 0; i < workers; i++ {
					select {
					case err := <-done:
						if err != nil {
							t.Error(err)
						}
					case <-time.After(3 * time.Second):
						t.Error("dispatch worker did not retire")
					}
				}
				want := int32(0)
				if stage == "send" {
					want = 1
				}
				if n.sends.Load() != want {
					t.Errorf("cancelled/serialized jobs sent %d times, want %d", n.sends.Load(), want)
				}
				state, err := s.loadApp(s.getPeer("nBob"))
				if err != nil || state.Jobs[ids["nBob"]].State != "cancelled" {
					t.Error("waiting dispatch overwrote queued cancellation", err)
				}
			})
			select {
			case <-n.entered:
			case <-time.After(3 * time.Second):
				t.Fatal("native boundary not reached")
			}
			workers++
			go func() { done <- s.workPeer(s.getPeer("nBob"), nil) }()
			bounded := func(method, path, peer string) *httptest.ResponseRecorder {
				result := make(chan *httptest.ResponseRecorder, 1)
				go func() { result <- call(method, path, nil, peer) }()
				select {
				case w := <-result:
					return w
				case <-time.After(time.Second):
					t.Error("saved app API blocked on native dispatch:", method, path, peer)
					once.Do(func() { close(release) })
					select {
					case w := <-result:
						return w
					case <-time.After(3 * time.Second):
						t.Fatal("API remained blocked after native release")
					}
				}
				return nil
			}
			for _, peer := range []string{"nBob", "nAlice"} {
				if w := bounded("GET", "/v2/jobs/"+ids[peer], peer); w.Code != 200 {
					t.Fatal(w.Body.String())
				}
				want := 200
				if peer == "nAlice" && stage == "send" {
					want = 202
				}
				if w := bounded("POST", "/v2/jobs/"+ids[peer]+"/cancel", peer); w.Code != want {
					t.Errorf("cancel %s: got %d want %d: %s", peer, w.Code, want, w.Body.String())
				}
			}
			once.Do(func() { close(release) })
		})
	}
}

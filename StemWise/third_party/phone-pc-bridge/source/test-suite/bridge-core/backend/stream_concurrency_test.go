package backend

import (
	"bytes"
	"context"
	"io"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

type heldTransferNative struct {
	*nativeFixture
	entered     chan struct{}
	release     <-chan struct{}
	output      []byte
	retirements int
	outputOnce  sync.Once
}

func (n *heldTransferNative) Call(ctx context.Context, request protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if request.Operation == "retire" {
		n.retirements++
		return protocol.AdapterReply{State: "ok", Result: mustJSON(map[string]any{"threadId": request.ThreadID, "turnId": request.TurnID, "terminalsRetired": true})}, nil
	}
	return n.nativeFixture.Call(ctx, request)
}

func (n *heldTransferNative) Put(ctx context.Context, f protocol.File, source io.Reader) error {
	if _, err := io.Copy(io.Discard, source); err != nil {
		return err
	}
	close(n.entered)
	select {
	case <-n.release:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
func (n *heldTransferNative) Output(context.Context, protocol.File) (io.ReadCloser, error) {
	n.outputOnce.Do(func() { close(n.entered) })
	return &heldOutputBody{Reader: bytes.NewReader(n.output), release: n.release}, nil
}

type heldOutputBody struct {
	io.Reader
	release <-chan struct{}
}

func (b *heldOutputBody) Read(p []byte) (int, error) { <-b.release; return b.Reader.Read(p) }
func (*heldOutputBody) Close() error                 { return nil }

func TestStreamedPreparationKeepsStatusAndCancellationAndPreservesSettings(t *testing.T) {
	s, n, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	n.online = true
	f, _ := files.Describe(randomToken(), "owned.bin", []byte("owned"))
	if _, err := s.inbox.Put("nAlice", f, strings.NewReader("owned")); err != nil {
		t.Fatal(err)
	}
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "owned streaming cancellation", Text: "original-" + randomToken(), Files: []protocol.File{f}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	release := make(chan struct{})
	var once sync.Once
	fixture := &heldTransferNative{nativeFixture: n, entered: make(chan struct{}), release: release}
	s.native = fixture
	done := make(chan error, 1)
	completed := false
	go func() { done <- s.workPeer(s.getPeer("nAlice"), nil) }()
	t.Cleanup(func() {
		once.Do(func() { close(release) })
		if !completed {
			select {
			case <-done:
			case <-time.After(3 * time.Second):
				t.Error("Owned preparation worker remained live")
			}
		}
	})
	select {
	case <-fixture.entered:
	case <-time.After(3 * time.Second):
		t.Fatal("Owned input stream never started")
	}
	bounded := func(method, path string, body any) *httptest.ResponseRecorder {
		t.Helper()
		result := make(chan *httptest.ResponseRecorder, 1)
		retired := make(chan struct{})
		t.Cleanup(func() {
			once.Do(func() { close(release) })
			checksync.Join(t, retired, 3*time.Second, "input-delivery app control")
		})
		go func() { defer close(retired); result <- call(method, path, body, "nAlice") }()
		select {
		case r := <-result:
			return r
		case <-time.After(3 * time.Second):
			once.Do(func() { close(release) })
			t.Fatal("Input delivery blocked saved app controls")
		}
		return nil
	}
	if w := bounded("GET", "/v2/jobs/"+input.RequestID, nil); w.Code != 200 {
		t.Fatal(w.Code)
	}
	prompt := randomToken()
	if w := bounded("PUT", "/v2/prompts/"+prompt, map[string]string{"title": "owned prompt", "text": "preserve newer settings", "instructions": "original"}); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	if w := bounded("POST", "/v2/jobs/"+input.RequestID+"/cancel", nil); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	once.Do(func() { close(release) })
	select {
	case err := <-done:
		completed = true
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Released input delivery did not finish")
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	if state.Jobs[input.RequestID].State != "cancelled" || state.Prompts[prompt].Text != "preserve newer settings" || n.calls != 0 {
		t.Fatal("Stale streamed preparation overwrote cancellation/settings or submitted a native turn")
	}
}

func TestStreamedOutputKeepsSavedStatusAndCannotResurrectRemovedJob(t *testing.T) {
	for _, mode := range []string{"settings", "removal", "cancel_request"} {
		t.Run(mode, func(t *testing.T) {
			s, n, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			n.online = true
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			j := ownedOutputJob(randomToken())
			state.Jobs[j.ID] = j
			if err := s.saveApp(state); err != nil {
				t.Fatal(err)
			}
			content := []byte("actual-streamed-output-" + randomToken())
			f, _ := files.Describe(randomToken(), "owned.bin", content)
			n.report = string(mustJSON(appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "ready", Files: []protocol.File{f}}))
			release := make(chan struct{})
			var once sync.Once
			fixture := &heldTransferNative{nativeFixture: n, entered: make(chan struct{}), release: release, output: content}
			s.native = fixture
			done := make(chan error, 1)
			completed := false
			go func() { done <- s.workPeer(p, nil) }()
			t.Cleanup(func() {
				once.Do(func() { close(release) })
				if !completed {
					select {
					case <-done:
					case <-time.After(3 * time.Second):
						t.Error("Owned output worker remained live")
					}
				}
			})
			select {
			case <-fixture.entered:
			case <-time.After(3 * time.Second):
				t.Fatal("Owned output stream never started")
			}
			result := make(chan *httptest.ResponseRecorder, 1)
			readRetired := make(chan struct{})
			t.Cleanup(func() {
				once.Do(func() { close(release) })
				checksync.Join(t, readRetired, 3*time.Second, "output-delivery saved read")
			})
			go func() { defer close(readRetired); result <- call("GET", "/v2/jobs/"+j.ID, nil, "nAlice") }()
			select {
			case w := <-result:
				if w.Code != 200 {
					t.Fatal(w.Code)
				}
			case <-time.After(3 * time.Second):
				once.Do(func() { close(release) })
				t.Fatal("Output transfer blocked saved status")
			}
			prompt := randomToken()
			if w := call("PUT", "/v2/prompts/"+prompt, map[string]string{"title": "owned prompt", "text": "preserve streamed settings", "instructions": "original"}, "nAlice"); w.Code != 200 {
				t.Fatal(w.Body.String())
			}
			if mode == "cancel_request" {
				if w := call("POST", "/v2/jobs/"+j.ID+"/cancel", nil, "nAlice"); w.Code != 200 {
					t.Fatal("Confirmed terminal native job was not returned", w.Body.String())
				}
				if w := call("GET", "/v1/outbox/"+f.ID+"/content?nonce="+randomToken(), nil, "nAlice"); w.Code != 404 {
					t.Fatal("Unpublished output bytes became public", w.Body.String())
				}
			}
			if mode == "removal" {
				p.appMu.Lock()
				latest, err := s.loadApp(p)
				if err == nil {
					err = s.removeAppJobs(latest, map[string]*appJob{j.ID: latest.Jobs[j.ID]})
				}
				p.appMu.Unlock()
				if err != nil {
					t.Fatal(err)
				}
			}
			once.Do(func() { close(release) })
			select {
			case err := <-done:
				completed = true
				if err != nil {
					t.Fatal(err)
				}
			case <-time.After(3 * time.Second):
				t.Fatal("Output transfer failed to settle")
			}
			latest, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			if latest.Prompts[prompt].Text != "preserve streamed settings" {
				t.Fatal("Output observation erased concurrent saved prompts")
			}
			if mode == "removal" {
				if latest.Jobs[j.ID] != nil {
					t.Fatal("Removed job resurrected after output transfer")
				}
				page, err := s.outbox.List(p.identity, "", 100)
				if err != nil || len(page.Files) != 0 {
					t.Fatal("Removed output transfer retained bytes", err)
				}
			} else if mode == "cancel_request" {
				stored := latest.Jobs[j.ID]
				if stored.State != "completed" || len(stored.Outputs) != 1 || stored.Outputs[0] != f || stored.CancelRequested != 0 || stored.CancelRetired != 0 || n.calls != 0 || fixture.retirements != 0 {
					t.Fatal("Terminal status manufactured an interruption or replayed the completed turn")
				}
			} else {
				stored := latest.Jobs[j.ID]
				if stored.State != "completed" || len(stored.Outputs) != 1 || stored.Outputs[0] != f {
					t.Fatal("Verified streamed output was not published")
				}
			}
		})
	}
}

package backend

import (
	"context"
	"encoding/json"
	"errors"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"phonepcbridge/protocol"
)

type heldControlNative struct {
	*nativeFixture
	stage      string
	entered    chan struct{}
	release    <-chan struct{}
	once       sync.Once
	fail       bool
	turn       string
	interrupts atomic.Int32
}

func (n *heldControlNative) hold(ctx context.Context) error {
	n.once.Do(func() {
		close(n.entered)
		select {
		case <-n.release:
		case <-ctx.Done():
		}
	})
	return ctx.Err()
}

func (n *heldControlNative) Models(ctx context.Context, cursor string) (json.RawMessage, error) {
	if n.stage == "models" {
		if err := n.hold(ctx); err != nil {
			return nil, err
		}
	}
	return n.nativeFixture.Models(ctx, cursor)
}

func (n *heldControlNative) Call(ctx context.Context, r protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if r.Operation != "interrupt" {
		return n.nativeFixture.Call(ctx, r)
	}
	n.interrupts.Add(1)
	if err := n.hold(ctx); err != nil {
		return protocol.AdapterReply{}, err
	}
	if n.fail {
		return protocol.AdapterReply{}, errors.New("test interrupt acknowledgement unavailable")
	}
	return protocol.AdapterReply{State: "ok", Result: json.RawMessage(`{}`)}, nil
}

func (n *heldControlNative) History(context.Context, string, string, int) (json.RawMessage, error) {
	return mustJSON(map[string]any{"data": []any{map[string]any{"id": n.turn, "status": "inProgress", "items": []any{}, "bridgeInteractionRequired": true}}, "nextCursor": nil}), nil
}

func boundedControlCall(t *testing.T, call func(string, string, any, string) *httptest.ResponseRecorder, release func(), method, path string, input any) *httptest.ResponseRecorder {
	t.Helper()
	done := make(chan *httptest.ResponseRecorder, 1)
	go func() { done <- call(method, path, input, "nAlice") }()
	select {
	case w := <-done:
		return w
	case <-time.After(time.Second):
		release()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Fatal("API did not retire after native release")
		}
		t.Fatal("Saved app API blocked behind native control", method, path)
	}
	return nil
}

func TestCatalogAndSettingsWaitsLeaveSavedAPIsResponsive(t *testing.T) {
	for _, method := range []string{"GET", "PUT"} {
		t.Run(method, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			release := make(chan struct{})
			var once sync.Once
			unblock := func() { once.Do(func() { close(release) }) }
			n := &heldControlNative{nativeFixture: base, stage: "models", entered: make(chan struct{}), release: release}
			s.native = n
			path := "/v2/codex/models"
			var input any
			if method == "PUT" {
				path = "/v2/codex/settings"
				input = map[string]string{"model": "fixture-model", "effort": "high"}
			}
			done := make(chan *httptest.ResponseRecorder, 1)
			finished := false
			t.Cleanup(func() {
				unblock()
				if !finished {
					select {
					case <-done:
					case <-time.After(3 * time.Second):
						t.Error("Held catalog control did not retire")
					}
				}
			})
			go func() { done <- call(method, path, input, "nAlice") }()
			select {
			case <-n.entered:
			case <-time.After(3 * time.Second):
				t.Fatal("Native catalog boundary never started")
			}
			if w := boundedControlCall(t, call, unblock, "GET", "/v2/app", nil); w.Code != 200 {
				t.Fatal(w.Body.String())
			}
			prompt := randomToken()
			if w := boundedControlCall(t, call, unblock, "PUT", "/v2/prompts/"+prompt, map[string]string{"title": "Concurrent prompt", "text": "preserve newer saved prompt"}); w.Code != 200 {
				t.Fatal(w.Body.String())
			}
			job := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Concurrent job", Text: "preserve newer job", Files: []protocol.File{}}
			if w := boundedControlCall(t, call, unblock, "POST", "/v2/jobs", job); w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			unblock()
			select {
			case w := <-done:
				finished = true
				if w.Code != 200 {
					t.Fatal(w.Body.String())
				}
			case <-time.After(3 * time.Second):
				t.Fatal("Native catalog control did not finish")
			}
			state, err := s.loadApp(s.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			if state.Prompts[prompt].Text != "preserve newer saved prompt" || state.Jobs[job.RequestID] == nil {
				t.Fatal("Catalog control overwrote concurrent app data")
			}
			if method == "PUT" && (state.Model != "fixture-model" || state.Effort != "high") {
				t.Fatal("Validated settings were not applied")
			}
		})
	}
}

func TestSettingsValidationCannotWriteIntoReplacementRegistration(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	before, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	release := make(chan struct{})
	var once sync.Once
	unblock := func() { once.Do(func() { close(release) }) }
	n := &heldControlNative{nativeFixture: base, stage: "models", entered: make(chan struct{}), release: release}
	s.native = n
	done := make(chan *httptest.ResponseRecorder, 1)
	finished := false
	t.Cleanup(func() {
		unblock()
		if !finished {
			select {
			case <-done:
			case <-time.After(3 * time.Second):
				t.Error("Old settings request did not retire")
			}
		}
	})
	go func() {
		done <- call("PUT", "/v2/codex/settings", map[string]string{"model": "fixture-model", "effort": "high"}, "nAlice")
	}()
	select {
	case <-n.entered:
	case <-time.After(3 * time.Second):
		t.Fatal("Settings catalog never started")
	}
	if w := boundedControlCall(t, call, unblock, "DELETE", "/v2/app", nil); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	if w := boundedControlCall(t, call, unblock, "POST", "/v2/app", map[string]string{"name": "nAlice"}); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	unblock()
	select {
	case w := <-done:
		finished = true
		if w.Code != 409 || !strings.Contains(w.Body.String(), "app_registration_required") {
			t.Fatal("Old validation was applied to replacement app", w.Body.String())
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Old settings request did not finish")
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil || state.ID == before.ID || state.Model != "" || state.Effort != "" {
		t.Fatal("Replacement registration was changed", err)
	}
}

func TestInterruptWaitsRemainResponsiveAndFenceNewerJobState(t *testing.T) {
	for _, automatic := range []bool{false, true} {
		for _, change := range []string{"unchanged", "terminal", "removed", "failed_ack"} {
			t.Run(map[bool]string{false: "app_request", true: "unattended_guard"}[automatic]+"/"+change, func(t *testing.T) {
				s, base, call := fixtureAPI(t)
				base.online = true
				registerFixture(t, call, "nAlice")
				p := s.getPeer("nAlice")
				state, err := s.loadApp(p)
				if err != nil {
					t.Fatal(err)
				}
				job := ownedOutputJob(randomToken())
				state.Jobs[job.ID] = job
				if err := s.saveApp(state); err != nil {
					t.Fatal(err)
				}
				release := make(chan struct{})
				var once sync.Once
				unblock := func() { once.Do(func() { close(release) }) }
				n := &heldControlNative{nativeFixture: base, stage: "interrupt", entered: make(chan struct{}), release: release, turn: job.TurnID, fail: change == "failed_ack"}
				s.native = n
				type result struct {
					response *httptest.ResponseRecorder
					err      error
				}
				done := make(chan result, 1)
				finished := false
				t.Cleanup(func() {
					unblock()
					if !finished {
						select {
						case <-done:
						case <-time.After(3 * time.Second):
							t.Error("Held interrupt did not retire")
						}
					}
				})
				go func() {
					if automatic {
						done <- result{err: s.workPeer(p, nil)}
					} else {
						done <- result{response: call("POST", "/v2/jobs/"+job.ID+"/cancel", nil, "nAlice")}
					}
				}()
				select {
				case <-n.entered:
				case <-time.After(3 * time.Second):
					t.Fatal("Native interrupt boundary never started")
				}
				if w := boundedControlCall(t, call, unblock, "GET", "/v2/jobs/"+job.ID, nil); w.Code != 200 {
					t.Fatal(w.Body.String())
				}
				prompt := randomToken()
				if w := boundedControlCall(t, call, unblock, "PUT", "/v2/prompts/"+prompt, map[string]string{"title": "Concurrent prompt", "text": "preserve interrupt-time update"}); w.Code != 200 {
					t.Fatal(w.Body.String())
				}
				if change == "terminal" || change == "removed" {
					p.appMu.Lock()
					latest, loadErr := s.loadApp(p)
					if loadErr == nil {
						if change == "terminal" {
							latest.Jobs[job.ID].State = "interrupted"
							latest.Jobs[job.ID].Code = "native_turn_interrupted"
							loadErr = s.saveApp(latest)
						} else {
							loadErr = s.removeAppJobs(latest, map[string]*appJob{job.ID: latest.Jobs[job.ID]})
						}
					}
					p.appMu.Unlock()
					if loadErr != nil {
						t.Fatal(loadErr)
					}
				}
				unblock()
				var got result
				select {
				case got = <-done:
					finished = true
				case <-time.After(3 * time.Second):
					t.Fatal("Native interrupt did not finish")
				}
				if got.err != nil {
					t.Fatal(got.err)
				}
				if !automatic {
					want := 202
					switch change {
					case "terminal":
						want = 200
					case "removed":
						// This internal removal has no physical closure receipt; its compact owner remains authoritative.
						want = 410
					}
					if got.response.Code != want {
						t.Fatal("Interrupt response contradicts current state", got.response.Code, got.response.Body.String())
					}
				}
				latest, err := s.loadApp(p)
				if err != nil {
					t.Fatal(err)
				}
				if latest.Prompts[prompt].Text != "preserve interrupt-time update" || n.interrupts.Load() != 1 {
					t.Fatal("Interrupt erased unrelated settings or targeted twice")
				}
				current := latest.Jobs[job.ID]
				if change == "removed" {
					if current != nil {
						t.Fatal("Removed job resurrected")
					}
					return
				}
				if change == "terminal" {
					if current.State != "interrupted" || current.Code != "native_turn_interrupted" {
						t.Fatal("Interrupt overwrote native terminal outcome")
					}
					return
				}
				if current.State != "submitted" || len(current.Outputs) != 0 {
					t.Fatal("Interrupt acknowledgment manufactured native terminal state")
				}
				if automatic {
					want := 1
					if change == "failed_ack" {
						want = 2
					}
					if current.Code != "native_interaction_required" || len(current.Log) != want {
						t.Fatal("Unattended guard lost investigation context")
					}
				} else if change == "unchanged" {
					if current.Code != "interrupt_requested" {
						t.Fatal("Interrupt acknowledgment not recorded")
					}
				} else if len(current.Log) != 1 || current.Log[0].Error.Code != "interrupt_unconfirmed" {
					t.Fatal("Unconfirmed interrupt lost debugging context")
				}
			})
		}
	}
}

package backend

import (
	"bytes"
	"context"
	"net/http/httptest"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

type heldOutputHashContext struct {
	context.Context
	entered, release chan struct{}
	once             sync.Once
}

func (c *heldOutputHashContext) Err() error {
	var pcs [20]uintptr
	frames := runtime.CallersFrames(pcs[:runtime.Callers(2, pcs[:])])
	for {
		frame, more := frames.Next()
		if strings.HasSuffix(frame.Function, ".verifyContent") {
			c.once.Do(func() {
				close(c.entered)
				select {
				case <-c.release:
				case <-c.Done():
				}
			})
			break
		}
		if !more {
			break
		}
	}
	return c.Context.Err()
}

func outputHashFixture(t *testing.T) (*Service, *peer, *appJob, protocol.File, func(string, string, any, string) *httptest.ResponseRecorder) {
	t.Helper()
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	data := []byte("exact verified output")
	f, _ := files.Describe(randomToken(), "owned.bin", data)
	if _, err := s.outbox.Put(p.identity, f, bytes.NewReader(data)); err != nil {
		t.Fatal(err)
	}
	job := ownedOutputJob(randomToken())
	job.State, job.Outputs = "completed", []protocol.File{f}
	job.Report = &appReport{Protocol: "v2", AppID: state.ID, JobID: job.ID, Revision: 1, Status: "completed", Code: "ready", Files: []protocol.File{f}}
	state.Jobs[job.ID] = job
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	return s, p, job, f, call
}

func TestOutputHashDoesNotHoldAppStateAndRevalidatesChangedOutputs(t *testing.T) {
	for _, operation := range []string{"content", "delivery"} {
		t.Run(operation, func(t *testing.T) {
			s, p, job, f, call := outputHashFixture(t)
			base, cancel := context.WithTimeout(context.Background(), 8*time.Second)
			defer cancel()
			ctx := &heldOutputHashContext{Context: base, entered: make(chan struct{}), release: make(chan struct{})}
			path, method := "/v2/jobs/"+job.ID+"/outputs/"+f.ID+"/content", "GET"
			var body []byte
			if operation == "delivery" {
				path, method = "/v2/jobs/"+job.ID+"/delivery", "POST"
				body = mustJSON(map[string]any{"files": []protocol.File{f}})
			}
			request := httptest.NewRequest(method, path, bytes.NewReader(body)).WithContext(ctx)
			request.RemoteAddr = p.identity
			request.Header.Set("Content-Type", "application/json")
			finished := make(chan *httptest.ResponseRecorder, 1)
			retired := make(chan struct{})
			var release sync.Once
			unblock := func() { release.Do(func() { close(ctx.release) }) }
			go func() {
				defer close(retired)
				response := httptest.NewRecorder()
				s.ServeHTTP(response, request)
				finished <- response
			}()
			t.Cleanup(func() {
				unblock()
				select {
				case <-retired:
				case <-time.After(3 * time.Second):
					t.Error("Owned hash handler remained live")
				}
			})
			select {
			case <-ctx.entered:
			case <-base.Done():
				t.Fatal("Actual output hash did not start")
			}
			response := boundedControlCall(t, call, unblock, "GET", "/v2/jobs/"+job.ID, nil)
			if response.Code != 200 {
				t.Fatal(response.Body.String())
			}
			p.appMu.Lock()
			state, err := s.loadApp(p)
			if err == nil {
				state.Jobs[job.ID].Outputs = []protocol.File{}
				err = s.saveApp(state)
			}
			p.appMu.Unlock()
			if err != nil {
				t.Fatal(err)
			}
			unblock()
			select {
			case response := <-finished:
				if response.Code >= 200 && response.Code < 300 {
					t.Fatal("Old byte verification was accepted after exact output ownership changed", response.Code)
				}
			case <-base.Done():
				t.Fatal("Hash handler did not settle")
			}
			state, err = s.loadApp(p)
			if err != nil || state.Jobs[job.ID].Delivered != 0 || len(state.Jobs[job.ID].Outputs) != 0 {
				t.Fatal("Stale verification changed current job", err)
			}
		})
	}
}

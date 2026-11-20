package backend

import (
	"bytes"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

type heldAppResponse struct {
	*httptest.ResponseRecorder
	header  bool
	entered chan struct{}
	release chan struct{}
}

func (w *heldAppResponse) WriteHeader(status int) {
	if w.header {
		close(w.entered)
		<-w.release
	}
	w.ResponseRecorder.WriteHeader(status)
}

func (w *heldAppResponse) Write(body []byte) (int, error) {
	if !w.header {
		close(w.entered)
		<-w.release
	}
	return w.ResponseRecorder.Write(body)
}

func TestBlockedAppResponsePreservesSnapshotAndAllowsDurableCancellation(t *testing.T) {
	for _, header := range []bool{false, true} {
		for _, kind := range []string{"job", "admission", "error"} {
			t.Run(kind+map[bool]string{false: "/body", true: "/header"}[header], func(t *testing.T) {
				s, native, call := fixtureAPI(t)
				registerFixture(t, call, "nAlice")
				input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Original accepted job", Text: "unchanged context", Files: []protocol.File{}}
				if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
					t.Fatal(w.Body.String())
				}
				method, path, body, wantCode := "GET", "/v2/jobs/"+input.RequestID, []byte(nil), 200
				if kind == "admission" {
					method, path, body = "POST", "/v2/jobs", mustJSON(input)
				} else if kind == "error" {
					method, path, body, wantCode = "POST", "/v2/jobs", []byte(`{}`), 400
				}
				r := httptest.NewRequest(method, path, bytes.NewReader(body))
				r.RemoteAddr = "nAlice"
				r.Header.Set("Content-Type", "application/json")
				w := &heldAppResponse{ResponseRecorder: httptest.NewRecorder(), header: header, entered: make(chan struct{}), release: make(chan struct{})}
				var once sync.Once
				release := func() { once.Do(func() { close(w.release) }) }
				done := make(chan struct{})
				t.Cleanup(func() { release(); checksync.Join(t, done, 3*time.Second, "held app response") })
				go func() { defer close(done); s.ServeHTTP(w, r) }()
				checksync.Receive(t, w.entered, 3*time.Second, "response IO")
				cancelled := boundedControlCall(t, call, release, "POST", "/v2/jobs/"+input.RequestID+"/cancel", nil)
				if cancelled.Code != 200 || !strings.Contains(cancelled.Body.String(), `"state":"cancelled"`) {
					t.Fatal("Held response blocked same-peer cancellation", cancelled.Code, cancelled.Body.String())
				}
				state, err := s.loadApp(s.getPeer("nAlice"))
				if err != nil || state.Jobs[input.RequestID].State != "cancelled" || state.Jobs[input.RequestID].CancelRetired == 0 {
					t.Fatal("Cancellation was not committed before held response resumed", err)
				}
				release()
				checksync.Join(t, done, 3*time.Second, "resumed response IO")
				if w.Code != wantCode {
					t.Fatal(w.Code, w.Body.String())
				}
				if kind == "error" {
					if !strings.Contains(w.Body.String(), `"code":"invalid_job"`) {
						t.Fatal(w.Body.String())
					}
				} else if !strings.Contains(w.Body.String(), `"state":"queued"`) || !strings.Contains(w.Body.String(), `"cancelRequested":0`) {
					t.Fatal("Original response was not a coherent pre-cancellation snapshot", w.Body.String())
				}
				if native.calls != 0 {
					t.Fatal("Response test submitted native work")
				}
			})
		}
	}
}

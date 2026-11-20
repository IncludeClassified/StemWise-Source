package backend

import (
	"bytes"
	"io"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

func holdAppRequestBody(t *testing.T, s *Service, method, path string, source io.Reader) (func(), <-chan *httptest.ResponseRecorder) {
	t.Helper()
	body := &retainedAdmissionBody{source: source, entered: make(chan struct{}), release: make(chan struct{})}
	r := httptest.NewRequest(method, path, body)
	r.RemoteAddr = "nAlice"
	r.Header.Set("Content-Type", "application/json")
	var once sync.Once
	release := func() { once.Do(func() { close(body.release) }) }
	responses := make(chan *httptest.ResponseRecorder, 1)
	done := make(chan struct{})
	t.Cleanup(func() {
		release()
		checksync.Join(t, done, 3*time.Second, "suspended request body handler")
	})
	go func() {
		defer close(done)
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		responses <- w
	}()
	checksync.Receive(t, body.entered, 3*time.Second, "production request body receipt")
	return release, responses
}

func TestSuspendedJobBodyLeavesReadsAndCancellationResponsive(t *testing.T) {
	for _, sameRequest := range []bool{false, true} {
		t.Run(map[bool]string{false: "new_job", true: "original_idempotent_job"}[sameRequest], func(t *testing.T) {
			s, native, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			original := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Original queued job", Text: "immutable context", Files: []protocol.File{}}
			if w := call("POST", "/v2/jobs", original, "nAlice"); w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			input := original
			if !sameRequest {
				input.RequestID = randomToken()
			}
			release, responses := holdAppRequestBody(t, s, "POST", "/v2/jobs", bytes.NewReader(mustJSON(input)))
			for _, path := range []string{"/v2/app", "/v2/jobs/" + original.RequestID} {
				if w := boundedControlCall(t, call, release, "GET", path, nil); w.Code != 200 {
					t.Fatal("Saved read failed while an unrelated request body was held", w.Body.String())
				}
			}
			if w := boundedControlCall(t, call, release, "POST", "/v2/jobs/"+original.RequestID+"/cancel", nil); w.Code != 200 || !strings.Contains(w.Body.String(), `"state":"cancelled"`) {
				t.Fatal("Cancellation blocked behind request body receipt", w.Code, w.Body.String())
			}
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			cancelled := mustJSON(state.Jobs[original.RequestID])
			release()
			w := checksync.Receive(t, responses, 3*time.Second, "resumed job admission")
			wantStatus := 202
			if sameRequest {
				wantStatus = 200
			}
			if w.Code != wantStatus || (sameRequest && !strings.Contains(w.Body.String(), `"state":"cancelled"`)) {
				t.Fatal("Resumed request returned a stale pre-cancellation snapshot", w.Code, w.Body.String())
			}
			state, err = s.loadApp(p)
			if err != nil || !bytes.Equal(cancelled, mustJSON(state.Jobs[original.RequestID])) || native.calls != 0 {
				t.Fatal("Completing a request body overwrote durable cancellation or dispatched work", err)
			}
			if !sameRequest && (state.Jobs[input.RequestID] == nil || state.Jobs[input.RequestID].State != "queued") {
				t.Fatal("Independent admission did not preserve the newer job state")
			}
		})
	}
}

func TestSuspendedBodyCannotMutateDeletedOrReplacedRegistration(t *testing.T) {
	for _, kind := range []string{"job", "settings"} {
		for _, replace := range []bool{false, true} {
			t.Run(kind+map[bool]string{false: "/deleted", true: "/replaced"}[replace], func(t *testing.T) {
				s, native, call := fixtureAPI(t)
				registerFixture(t, call, "nAlice")
				p := s.getPeer("nAlice")
				old, err := s.loadApp(p)
				if err != nil {
					t.Fatal(err)
				}
				method, path := "POST", "/v2/jobs"
				var input any = appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Old registration request", Text: "preserve original identity", Files: []protocol.File{}}
				if kind == "settings" {
					method, path = "PUT", "/v2/codex/settings"
					input = map[string]string{"model": "fixture-model", "effort": "high"}
				}
				release, responses := holdAppRequestBody(t, s, method, path, bytes.NewReader(mustJSON(input)))
				if w := boundedControlCall(t, call, release, "DELETE", "/v2/app", nil); w.Code != 200 {
					t.Fatal("Disconnect blocked behind body receipt", w.Code, w.Body.String())
				}
				var replacement []byte
				if replace {
					if w := boundedControlCall(t, call, release, "POST", "/v2/app", map[string]string{"name": "Replacement"}); w.Code != 200 {
						t.Fatal(w.Body.String())
					}
					latest, err := s.loadApp(p)
					if err != nil || latest.ID == old.ID {
						t.Fatal("Replacement fixture did not change registration identity", err)
					}
					replacement = mustJSON(latest)
				}
				release()
				w := checksync.Receive(t, responses, 3*time.Second, "stale registration request rejection")
				if w.Code != 409 || !strings.Contains(w.Body.String(), `"code":"app_registration_changed"`) {
					t.Fatal("Suspended request crossed registration ownership", w.Code, w.Body.String())
				}
				latest, err := s.loadApp(p)
				if replace {
					if err != nil || !bytes.Equal(replacement, mustJSON(latest)) {
						t.Fatal("Stale body changed the new registration, settings or jobs", err)
					}
				} else if !os.IsNotExist(err) {
					t.Fatal("Stale body recreated a deleted app", err)
				}
				if native.calls != 0 {
					t.Fatal("Stale registration request submitted native work")
				}
			})
		}
	}
}

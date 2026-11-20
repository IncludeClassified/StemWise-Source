package backend

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

func TestRequestRetirementPreservesWorkBeforeDeadlineAndSealsExpiredWork(t *testing.T) {
	for _, phase := range []string{"queued", "running", "undelivered", "unknown_acceptance"} {
		t.Run(phase, func(t *testing.T) {
			s, native, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			input := appInput{RequestID: randomToken(), CreatedAt: time.Now().Add(-365 * 24 * time.Hour).UnixMilli(), Title: "Retirement fixture", Text: "original immutable context", Files: []protocol.File{}}
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			if phase == "undelivered" {
				native.online = true
				if err := s.workPass(); err != nil {
					t.Fatal(err)
				}
			}
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			job := state.Jobs[input.RequestID]
			if phase == "running" || phase == "unknown_acceptance" {
				job.ThreadID, job.State, job.Code = "fixture-chat", "submitted", "fixture_running"
				if phase == "running" {
					job.TurnID = "fixture-turn"
				} else {
					job.State, job.Code = "unknown", "dispatch_outcome_unconfirmed"
				}
				if err := s.saveApp(state); err != nil {
					t.Fatal(err)
				}
			}
			before := mustJSON(job)
			deadline := time.UnixMilli(job.Created).Add(appRetention)
			if _, err := s.retentionPass(deadline.Add(-time.Millisecond)); err != nil {
				t.Fatal(err)
			}
			w := call("POST", "/v2/jobs/"+job.ID+"/retire", nil, p.identity)
			var reply struct {
				Data json.RawMessage `json:"data"`
			}
			if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &reply) != nil || !bytes.Equal(reply.Data, mustJSON(appJobView(job))) {
				t.Fatal("Retirement did not return the actual retained result", w.Code, w.Body.String())
			}
			state, err = s.loadApp(p)
			if err != nil || !bytes.Equal(before, mustJSON(state.Jobs[job.ID])) || state.DeletedRequests[job.ID] != 0 {
				t.Fatal("Retirement changed retained work or sealed its admission", err)
			}
			for _, output := range job.Outputs {
				if _, data, err := s.outbox.Read(p.identity, output.ID); err != nil || len(data) == 0 {
					t.Fatal("Retirement discarded retained output bytes", err)
				}
			}
			calls := native.calls
			if _, err := s.retentionPass(deadline); err != nil {
				t.Fatal(err)
			}
			if w := call("GET", "/v2/jobs/"+job.ID, nil, p.identity); w.Code != 410 {
				t.Fatal("Expired work remained readable", w.Body.String())
			}
			for _, output := range job.Outputs {
				if _, _, err := s.outbox.Read(p.identity, output.ID); err == nil {
					t.Fatal("Expired output remained readable")
				}
			}
			resumed, err := New(s.cfg, native, func(_ context.Context, remote string) (string, error) { return remote, nil }, nil)
			if err != nil {
				t.Fatal(err)
			}
			defer resumed.Close()
			request := httptest.NewRequest("POST", "/v2/jobs/"+job.ID+"/retire", nil)
			request.RemoteAddr = p.identity
			w = httptest.NewRecorder()
			resumed.ServeHTTP(w, request)
			if w.Code != 410 || !strings.Contains(w.Body.String(), "request_deleted") {
				t.Fatal("Reconnect did not retain the expiry barrier", w.Code, w.Body.String())
			}
			state, err = resumed.loadApp(resumed.getPeer(p.identity))
			if err != nil || state.Jobs[job.ID] != nil || state.DeletedRequests[job.ID] == 0 {
				t.Fatal("Reconnect restored expired content or lost its replay fence", err)
			}
			if native.calls != calls {
				t.Fatal("Retirement submitted native work")
			}
		})
	}
}

func TestAbsentRetirementReceiptIsBoundedDurableAndScoped(t *testing.T) {
	s, native, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	registerFixture(t, call, "nBob")
	id := randomToken()
	path := "/v2/jobs/" + id
	if w := call("POST", path+"/cancel", nil, "nAlice"); w.Code != 404 {
		t.Fatal("Unknown cancellation falsely established retirement", w.Body.String())
	}
	if w := call("POST", path+"/retire", `{}`, "nAlice"); w.Code != 400 {
		t.Fatal("Unexpected retirement body was accepted", w.Code)
	}
	before := time.Now()
	w := call("POST", path+"/retire", nil, "nAlice")
	var failure struct {
		Data appFailure `json:"data"`
	}
	if w.Code != 410 || json.Unmarshal(w.Body.Bytes(), &failure) != nil || failure.Data.Code != "request_deleted" || failure.Data.Retryable || !strings.Contains(failure.Data.Message, "unknown") {
		t.Fatal("Absent retirement did not report unknown historical execution", w.Code, w.Body.String())
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	expires := state.DeletedRequests[id]
	if expires < before.Add(appRetention).UnixMilli() || expires > time.Now().Add(appRetention).UnixMilli() || len(state.Jobs) != 0 {
		t.Fatal("Retirement retained more than a bounded identity receipt")
	}
	for _, request := range []struct {
		method, path string
		status       int
	}{
		{"GET", path, 410}, {"GET", path + "/inputs", 410}, {"GET", path + "/outputs", 410},
		{"POST", path + "/cancel", 410}, {"POST", path + "/delivery", 410}, {"POST", path + "/retire", 410}, {"DELETE", path, 200},
	} {
		if w := call(request.method, request.path, nil, "nAlice"); w.Code != request.status {
			t.Fatal("Receipt route lost retirement", request, w.Code, w.Body.String())
		}
	}
	state, err = s.loadApp(s.getPeer("nAlice"))
	if err != nil || state.DeletedRequests[id] != expires {
		t.Fatal("Observation renewed the retirement barrier", err)
	}
	if w := call("GET", path, nil, "nBob"); w.Code != 404 {
		t.Fatal("Retirement leaked across app registrations", w.Code)
	}
	if _, err := s.retentionPass(time.UnixMilli(expires)); err != nil {
		t.Fatal(err)
	}
	state, err = s.loadApp(s.getPeer("nAlice"))
	if err != nil || len(state.DeletedRequests) != 0 {
		t.Fatal("Retirement kept indefinite identity history", err)
	}
	if w := call("GET", path, nil, "nAlice"); w.Code != 404 {
		t.Fatal("Pruned receipt falsely proved retirement", w.Code)
	}
	if w := call("POST", path+"/retire", nil, "nAlice"); w.Code != 410 || native.calls != 0 {
		t.Fatal("Explicit retirement could not seal an unresolved ID after receipt pruning", w.Code)
	}
}

func TestRetirementAndAdmissionAreSerialized(t *testing.T) {
	s, native, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	for attempt := 0; attempt < 6; attempt++ {
		input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Concurrent immutable request", Text: "preserve original", Files: []protocol.File{}}
		start, done := make(chan struct{}), make(chan struct{})
		results := make(chan *httptest.ResponseRecorder, 2)
		var release sync.Once
		var workers sync.WaitGroup
		workers.Add(2)
		t.Cleanup(func() {
			release.Do(func() { close(start) })
			checksync.Join(t, done, 3*time.Second, "retirement/admission workers")
		})
		for _, path := range []string{"/v2/jobs", "/v2/jobs/" + input.RequestID + "/retire"} {
			go func() {
				defer workers.Done()
				<-start
				var body any
				if path == "/v2/jobs" {
					body = input
				}
				results <- call("POST", path, body, "nAlice")
			}()
		}
		go func() { workers.Wait(); close(done) }()
		release.Do(func() { close(start) })
		first := checksync.Receive(t, results, 3*time.Second, "first retirement/admission response")
		second := checksync.Receive(t, results, 3*time.Second, "second retirement/admission response")
		if !((first.Code == 410 && second.Code == 410) || (first.Code == 200 && second.Code == 202) || (first.Code == 202 && second.Code == 200)) {
			t.Fatal("Admission and retirement disagreed", first.Code, second.Code)
		}
		state, err := s.loadApp(s.getPeer("nAlice"))
		if err != nil {
			t.Fatal(err)
		}
		if (state.Jobs[input.RequestID] != nil) == (state.DeletedRequests[input.RequestID] != 0) || native.calls != 0 {
			t.Fatal("Retirement/admission did not commit exactly one outcome")
		}
		checksync.Join(t, done, time.Second, "retirement/admission workers")
	}
}

func TestRetirementCommitFailureDoesNotConfirmOrCancelWork(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	p.appMu.Lock()
	defer p.appMu.Unlock()
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	id := randomToken()
	lease := s.beginJobWork(p, state.ID, &appJob{ID: id, Created: time.Now().UnixMilli()})
	if lease == nil {
		t.Fatal("Missing original work lease")
	}
	defer s.endJobWork(p, lease)
	dataDir := s.cfg.DataDir
	s.cfg.DataDir = filepath.Join(t.TempDir(), "missing-parent", "bridge")
	w := httptest.NewRecorder()
	s.retireAppRequest(w, p, state, id, time.Now())
	s.cfg.DataDir = dataDir
	if w.Code != 503 || !strings.Contains(w.Body.String(), `"code":"app_store_unavailable"`) || lease.ctx.Err() != nil {
		t.Fatal("Failed receipt commit falsely confirmed retirement or stopped the lease", w.Code, w.Body.String())
	}
	state, err = s.loadApp(p)
	if err != nil || state.DeletedRequests[id] != 0 {
		t.Fatal("Failed receipt commit changed durable admission", err)
	}
	w = httptest.NewRecorder()
	s.retireAppRequest(w, p, state, id, time.Now())
	if w.Code != 410 || lease.ctx.Err() == nil {
		t.Fatal("Committed barrier did not cancel the obsolete exact lease", w.Code)
	}
}

func TestRetirementCannotConsumeAcceptedResultReserve(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	seedNearFullApp(t, s, p)
	id := randomToken()
	if w := call("POST", "/v2/jobs/"+id+"/retire", nil, p.identity); w.Code != 409 || !strings.Contains(w.Body.String(), `"code":"app_capacity_reached"`) {
		t.Fatal("Barrier bypassed retained-result capacity protection", w.Code)
	}
	state, err := s.loadApp(p)
	if err != nil || state.DeletedRequests[id] != 0 {
		t.Fatal("Rejected barrier changed durable admission", err)
	}
}

type retirementCleanupNative struct {
	*nativeFixture
	entered  chan struct{}
	release  chan struct{}
	discards atomic.Int32
}

func (n *retirementCleanupNative) Call(ctx context.Context, request protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if request.Operation == "discard" {
		result := map[string]any{"appId": request.AppID, "requestId": request.RequestID, "accepted": true}
		// The first delegation precedes deletion; a concurrent later observation has its committed closure proof.
		if n.discards.Add(1) > 1 {
			result["deleted"] = true
		}
		return protocol.AdapterReply{State: "ok", Result: mustJSON(result)}, nil
	}
	if request.Operation == "delete" {
		n.entered <- struct{}{}
		select {
		case <-ctx.Done():
			return protocol.AdapterReply{}, ctx.Err()
		case <-n.release:
		}
	}
	return n.nativeFixture.Call(ctx, request)
}

func TestDeliveryCleanupResponseUsesOriginalRegistrationAndLiveReceipt(t *testing.T) {
	for _, change := range []string{"registration", "deletion", "expired_deletion"} {
		t.Run(change, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			base.online = true
			input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Original result", Text: "original immutable context", Files: []protocol.File{}}
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			if err := s.workPass(); err != nil {
				t.Fatal(err)
			}
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			job := state.Jobs[input.RequestID]
			native := &retirementCleanupNative{nativeFixture: base, entered: make(chan struct{}, 1), release: make(chan struct{})}
			s.native = native
			done, retired := make(chan *httptest.ResponseRecorder, 1), make(chan struct{})
			var release sync.Once
			t.Cleanup(func() {
				s.cancel()
				release.Do(func() { close(native.release) })
				checksync.Join(t, retired, 3*time.Second, "delivery cleanup response")
			})
			go func() {
				defer close(retired)
				done <- call("POST", "/v2/jobs/"+job.ID+"/delivery", map[string]any{"files": job.Outputs}, p.identity)
			}()
			checksync.Receive(t, native.entered, 3*time.Second, "delivery native cleanup")
			want := 410
			if change == "registration" {
				if w := call("DELETE", "/v2/app", nil, p.identity); w.Code != 200 {
					t.Fatal(w.Body.String())
				}
				registerFixture(t, call, p.identity)
				input.Title, input.Text = "New registration", "must never appear in the original response"
				if w := call("POST", "/v2/jobs", input, p.identity); w.Code != 202 {
					t.Fatal(w.Body.String())
				}
				want = 404
			} else {
				if w := call("DELETE", "/v2/jobs/"+job.ID, nil, p.identity); w.Code != 200 {
					t.Fatal(w.Body.String())
				}
				if change == "expired_deletion" {
					p.appMu.Lock()
					state, err := s.loadApp(p)
					if err == nil {
						// Model receiver time passing during the outstanding cleanup call.
						state.DeletedRequests[job.ID] = time.Now().Add(-time.Millisecond).UnixMilli()
						err = s.saveApp(state)
					}
					p.appMu.Unlock()
					if err != nil {
						t.Fatal(err)
					}
					want = 404
				}
			}
			release.Do(func() { close(native.release) })
			w := checksync.Receive(t, done, 3*time.Second, "delivery cleanup result")
			code := "request_deleted"
			if want == 404 {
				code = "job_unavailable"
			}
			if w.Code != want || !strings.Contains(w.Body.String(), `"code":"`+code+`"`) || strings.Contains(w.Body.String(), input.Text) {
				t.Fatal("Cleanup response crossed registration or receipt lifetime", w.Code, w.Body.String())
			}
			checksync.Receive(t, retired, 3*time.Second, "delivery cleanup worker retirement")
			if change == "registration" {
				state, err := s.loadApp(p)
				if err != nil || state.Jobs[job.ID] == nil || state.Jobs[job.ID].Delivered != 0 || state.Jobs[job.ID].State != "queued" {
					t.Fatal("Old delivery changed the new registration's job", err)
				}
			}
		})
	}
}

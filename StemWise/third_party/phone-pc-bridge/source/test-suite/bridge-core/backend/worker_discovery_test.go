package backend

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

type countedWorkerNative struct {
	*nativeFixture
	probes  atomic.Int32
	offline atomic.Bool
	probed  chan int32
}

func (n *countedWorkerNative) Reachable(ctx context.Context) error {
	count := n.probes.Add(1)
	if n.probed != nil {
		select {
		case n.probed <- count:
		default:
		}
	}
	if n.offline.Load() {
		return errors.New("fixture_offline")
	}
	return n.nativeFixture.Reachable(ctx)
}

func waitWorker(t *testing.T, condition func() bool, name string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	ticker := time.NewTicker(time.Millisecond)
	defer ticker.Stop()
	for !condition() {
		select {
		case <-ctx.Done():
			t.Fatal(name)
		case <-ticker.C:
		}
	}
}

func TestRegisteredIdleAppsDoNotProbePCAndQueuedPassProbesOnlyOnce(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	n := &countedWorkerNative{nativeFixture: base}
	s.native = n
	registerFixture(t, call, "nAlice")
	for range 4 {
		if err := s.workPass(); err != nil {
			t.Fatal(err)
		}
	}
	if got := n.probes.Load(); got != 0 {
		t.Errorf("Registered idle app made %d PC probes", got)
	}
	n.probes.Store(0)
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Wake worker", Text: "actual result", Files: []protocol.File{}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil || state.Jobs[input.RequestID].State != "completed" || n.calls != 1 {
		t.Fatal("Normal admitted job did not complete once", err)
	}
	if got := n.probes.Load(); got != 1 {
		t.Fatalf("One due-work pass made %d health probes", got)
	}
}

func TestRunningCoordinatorStaysIdleAndNewAdmissionWakesIt(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	n := &countedWorkerNative{nativeFixture: base}
	s.native = n
	registerFixture(t, call, "nAlice")
	s.Run()
	waitWorker(t, func() bool { return s.jobPassStatus.Snapshot().LastSuccessAt != 0 }, "Initial idle discovery did not finish")
	for range 4 {
		previous := s.jobPassStatus.Snapshot().LastSuccessAt
		waitWorker(t, func() bool { return time.Now().UnixMilli() > previous }, "Clock did not advance")
		s.notifyJobs()
		waitWorker(t, func() bool { return s.jobPassStatus.Snapshot().LastSuccessAt > previous }, "Coalesced idle discovery did not finish")
	}
	if n.probes.Load() != 0 {
		t.Fatal("Idle running coordinator probed the PC", n.probes.Load())
	}
	if delay := s.jobPassStatus.Snapshot().RetryDelayMs; delay != 0 {
		t.Fatal("Idle coordinator advertised a nonexistent periodic retry", delay)
	}
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "New admission", Text: "actual wake result", Files: []protocol.File{}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	p := s.getPeer("nAlice")
	waitWorker(t, func() bool {
		p.appMu.Lock()
		defer p.appMu.Unlock()
		state, err := s.loadApp(p)
		return err == nil && state.Jobs[input.RequestID].State == "completed" && len(state.Jobs[input.RequestID].Outputs) == 1
	}, "New HTTP admission did not wake the idle coordinator before its retry interval")
	if n.probes.Load() != 1 {
		t.Fatal("One coalesced discovery was probed by multiple workers", n.probes.Load())
	}
}

func TestPersistedPendingWorkRetriesOfflineAndRecoversOnStartup(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	registerFixture(t, call, "nAlice")
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Saved before restart", Text: "actual recovered result", Files: []protocol.File{}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	n := &countedWorkerNative{nativeFixture: base, probed: make(chan int32, 16)}
	n.offline.Store(true)
	reopened, err := New(s.cfg, n, s.authenticate, nil)
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() { defer close(done); reopened.workJobsEvery(30 * time.Millisecond) }()
	t.Cleanup(func() {
		reopened.cancel()
		checksync.Join(t, done, 3*time.Second, "restarted pending coordinator")
		reopened.Close()
	})
	for expected := int32(1); expected <= 2; expected++ {
		if got := checksync.Receive(t, n.probed, 3*time.Second, "pending reconnect probe"); got != expected {
			t.Fatal("Pending retry was duplicated or lost", got, expected)
		}
	}
	if base.calls != 0 {
		t.Fatal("Offline work was submitted")
	}
	n.offline.Store(false)
	checksync.Receive(t, n.probed, 3*time.Second, "periodic pending recovery without another app request")
	p := reopened.getPeer("nAlice")
	waitWorker(t, func() bool {
		p.appMu.Lock()
		defer p.appMu.Unlock()
		state, err := reopened.loadApp(p)
		return err == nil && state.Jobs[input.RequestID].State == "completed" && len(state.Jobs[input.RequestID].Outputs) == 1
	}, "Persisted pending work did not recover without a new admission")
}

func TestRunningCoordinatorRetainsPendingWakeUntilLeasedCapacityRetires(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	registerFixture(t, call, "nAlice")
	release := make(chan struct{})
	n := &boundedJobInputs{nativeFixture: base, entered: make(chan string, 4), release: release}
	s.native = n
	owners := map[string]string{}
	for i := 1; i <= 3; i++ {
		f, _ := files.Describe(randomToken(), "held.bin", []byte("held"))
		if _, err := s.inbox.Put("nAlice", f, strings.NewReader("held")); err != nil {
			t.Fatal(err)
		}
		id := fmt.Sprintf("%032x", i)
		owners[f.ID] = id
		if w := call("POST", "/v2/jobs", appInput{RequestID: id, CreatedAt: time.Now().UnixMilli(), Title: "Queued capacity", Text: "held", Files: []protocol.File{f}}, "nAlice"); w.Code != 202 {
			t.Fatal(w.Body.String())
		}
	}
	t.Cleanup(func() { s.cancel(); close(release) })
	s.Run()
	first := checksync.Receive(t, n.entered, 3*time.Second, "first running owner")
	second := checksync.Receive(t, n.entered, 3*time.Second, "second running owner")
	s.notifyJobs() // Every available app slot is still leased.
	select {
	case <-n.entered:
		t.Fatal("Coordinator exceeded the two-owner limit")
	case <-time.After(50 * time.Millisecond):
	}
	if w := call("POST", "/v2/jobs/"+owners[first]+"/cancel", nil, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	third := checksync.Receive(t, n.entered, 3*time.Second, "pending job starts promptly after actual lease retirement")
	if third == first || third == second || n.maximum.Load() != 2 {
		t.Fatal("Retired work replayed or exceeded bounded ownership")
	}
}

type retryDeliveryCleanupNative struct {
	*jobChatNative
	deletions atomic.Int32
}

func (n *retryDeliveryCleanupNative) Call(ctx context.Context, request protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if request.Operation == "delete" {
		if request.ThreadID != "owned-"+request.RequestID {
			return protocol.AdapterReply{}, errors.New("wrong_cleanup_owner")
		}
		if n.deletions.Add(1) == 1 {
			return protocol.AdapterReply{}, errors.New("first_cleanup_unconfirmed")
		}
		return protocol.AdapterReply{State: "ok", Result: mustJSON(map[string]bool{"deleted": true})}, nil
	}
	return n.jobChatNative.Call(ctx, request)
}

func TestDeliveryWakesProvenIdleCoordinatorToRetryFailedCleanup(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	registerFixture(t, call, "nAlice")
	n := &retryDeliveryCleanupNative{jobChatNative: &jobChatNative{nativeFixture: base}}
	s.native = n
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Idle delivery", Text: "actual delivered output", Files: []protocol.File{}}
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
	outputs := state.Jobs[input.RequestID].Outputs
	previous := s.jobPassStatus.Snapshot().LastSuccessAt
	waitWorker(t, func() bool { return time.Now().UnixMilli() > previous }, "Clock did not advance before startup")
	s.Run()
	waitWorker(t, func() bool {
		status := s.jobPassStatus.Snapshot()
		return status.LastSuccessAt > previous && status.RetryDelayMs == 0
	}, "Completed undelivered work did not leave the normal coordinator idle")
	if w := call("POST", "/v2/jobs/"+input.RequestID+"/delivery", map[string]any{"files": outputs}, "nAlice"); w.Code != 200 || !strings.Contains(w.Body.String(), "cleanup_pending") {
		t.Fatal("Actual first cleanup failure was not retained", w.Body.String())
	}
	waitWorker(t, func() bool {
		p.appMu.Lock()
		defer p.appMu.Unlock()
		state, err := s.loadApp(p)
		return err == nil && state.Jobs[input.RequestID].ChatState == "deleted"
	}, "Idle coordinator never retried durably accepted delivery cleanup")
	if n.deletions.Load() != 2 || base.calls != 1 {
		t.Fatal("Cleanup retried a submission or lost its exact deletion receipt", n.deletions.Load(), base.calls)
	}
}

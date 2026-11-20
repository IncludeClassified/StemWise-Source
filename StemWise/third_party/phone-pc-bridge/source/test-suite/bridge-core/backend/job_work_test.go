package backend

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
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

type cancellableJobHistory struct {
	*nativeFixture
	thread  string
	entered chan struct{}
	release <-chan struct{}
	once    sync.Once
}

func (n *cancellableJobHistory) History(ctx context.Context, thread, cursor string, limit int) (json.RawMessage, error) {
	if thread == n.thread {
		n.once.Do(func() { close(n.entered) })
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-n.release:
		}
	}
	return n.nativeFixture.History(ctx, thread, cursor, limit)
}

func TestSameAppJobProgressAndCancellationRetireHeldInputOrHistory(t *testing.T) {
	for _, phase := range []string{"input", "history"} {
		t.Run(phase, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			base.online = true
			registerFixture(t, call, "nAlice")
			p := s.getPeer("nAlice")
			release, entered := make(chan struct{}), make(chan struct{})
			var once sync.Once
			unblock := func() { once.Do(func() { close(release) }) }
			var done []<-chan struct{}
			t.Cleanup(func() {
				unblock()
				for _, retired := range done {
					checksync.Join(t, retired, 3*time.Second, "owned job worker retirement")
				}
			})
			start := func() <-chan error {
				retired, result := make(chan struct{}), make(chan error, 1)
				done = append(done, retired)
				go func() {
					defer close(retired)
					result <- s.workPeer(p, nil)
				}()
				return result
			}
			firstID := strings.Repeat("1", 32)
			if phase == "input" {
				s.native = &heldTransferNative{nativeFixture: base, entered: entered, release: release}
				file, err := files.Describe(randomToken(), "owned.bin", []byte("owned transfer"))
				if err != nil {
					t.Fatal(err)
				}
				if _, err := s.inbox.Put("nAlice", file, strings.NewReader("owned transfer")); err != nil {
					t.Fatal(err)
				}
				input := appInput{RequestID: firstID, CreatedAt: time.Now().UnixMilli(), Title: "Held input", Text: "first", Files: []protocol.File{file}}
				if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
					t.Fatal(w.Body.String())
				}
			} else {
				first := ownedOutputJob(firstID)
				first.ThreadID = "held-original-chat"
				s.native = &cancellableJobHistory{nativeFixture: base, thread: first.ThreadID, entered: entered, release: release}
				state, err := s.loadApp(p)
				if err != nil {
					t.Fatal(err)
				}
				state.Jobs[firstID] = first
				if err := s.saveApp(state); err != nil {
					t.Fatal(err)
				}
			}
			firstDone := start()
			checksync.Receive(t, entered, 3*time.Second, "held original job boundary")
			secondID := strings.Repeat("2", 32)
			second := appInput{RequestID: secondID, CreatedAt: time.Now().UnixMilli(), Title: "Independent job", Text: "second", Files: []protocol.File{}}
			if w := call("POST", "/v2/jobs", second, "nAlice"); w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			if err := checksync.Receive(t, start(), 3*time.Second, "second same-app job progress"); err != nil {
				t.Fatal(err)
			}
			p.appMu.Lock()
			state, err := s.loadApp(p)
			p.appMu.Unlock()
			if err != nil || state.Jobs[secondID].State != "completed" {
				t.Error("Held job blocked another job in its own app", err)
			}
			if w := call("POST", "/v2/jobs/"+firstID+"/cancel", nil, "nAlice"); w.Code != 200 && w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			if err := checksync.Receive(t, firstDone, 3*time.Second, "cancelled original work retirement without releasing fake"); err != nil {
				t.Fatal(err)
			}
			p.appMu.Lock()
			state, err = s.loadApp(p)
			active := len(p.work)
			p.appMu.Unlock()
			if err != nil || state.Jobs[firstID].CancelRequested == 0 || base.calls != 1 || active != 0 {
				t.Fatal("Cancellation lost intent or dispatched the original job", err, base.calls)
			}
		})
	}
}

type rotatingJobHistory struct {
	*nativeFixture
	seen map[string]int
}

func (n *rotatingJobHistory) History(_ context.Context, thread, _ string, _ int) (json.RawMessage, error) {
	n.seen[thread]++
	id := strings.TrimPrefix(thread, "thread_")
	return mustJSON(map[string]any{"data": []any{map[string]any{"id": "turn_" + id, "status": "inProgress", "items": []any{}}}, "nextCursor": nil}), nil
}

func TestJobObservationBatchIsBoundedAndRotatesAcrossPasses(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	for i := 1; i <= 13; i++ {
		j := ownedOutputJob(fmt.Sprintf("%032x", i))
		j.ThreadID, j.TurnID = "thread_"+j.ID, "turn_"+j.ID
		state.Jobs[j.ID] = j
	}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	n := &rotatingJobHistory{nativeFixture: base, seen: map[string]int{}}
	s.native = n
	if err := s.workPeer(p, nil); err != nil {
		t.Fatal(err)
	}
	if len(n.seen) != 8 {
		t.Fatal("One worker turn exceeded or failed its eight-job observation budget", len(n.seen))
	}
	if err := s.workPeer(p, nil); err != nil {
		t.Fatal(err)
	}
	if len(n.seen) != 13 {
		t.Fatal("Repeated turns starved later jobs", len(n.seen))
	}
}

type closingJobOutput struct {
	entered chan struct{}
	closed  chan struct{}
	read    sync.Once
	close   sync.Once
}

func (b *closingJobOutput) Read([]byte) (int, error) {
	b.read.Do(func() { close(b.entered) })
	<-b.closed
	return 0, io.EOF
}
func (b *closingJobOutput) Close() error {
	b.close.Do(func() { close(b.closed) })
	return nil
}

type expiringOutputNative struct {
	*nativeFixture
	turn json.RawMessage
	body *closingJobOutput
}

func (n *expiringOutputNative) History(context.Context, string, string, int) (json.RawMessage, error) {
	return mustJSON(map[string]any{"data": []json.RawMessage{n.turn}, "nextCursor": nil}), nil
}
func (n *expiringOutputNative) Output(context.Context, protocol.File) (io.ReadCloser, error) {
	return n.body, nil
}

func TestRemovedJobClosesBlockedOutputAndReleasesItsWorkerLease(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	f, _ := files.Describe(randomToken(), "actual.bin", []byte("actual"))
	report := appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "ready", Files: []protocol.File{f}}
	state.Jobs[j.ID] = j
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	body := &closingJobOutput{entered: make(chan struct{}), closed: make(chan struct{})}
	s.native = &expiringOutputNative{nativeFixture: base, body: body, turn: mustJSON(map[string]any{"id": j.TurnID, "status": "completed", "bridgeTerminalCommitted": true, "items": []any{map[string]any{"type": "agentMessage", "text": string(mustJSON(report))}}})}
	done, result := make(chan struct{}), make(chan error, 1)
	t.Cleanup(func() {
		body.Close()
		checksync.Join(t, done, 3*time.Second, "removed output worker retirement")
	})
	go func() {
		defer close(done)
		result <- s.workPeer(p, nil)
	}()
	checksync.Receive(t, body.entered, 3*time.Second, "actual output read")
	p.appMu.Lock()
	state, err = s.loadApp(p)
	if err == nil {
		err = s.removeAppJobs(state, map[string]*appJob{j.ID: state.Jobs[j.ID]})
	}
	p.appMu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	if err := checksync.Receive(t, result, 3*time.Second, "removal retires blocked output without manual release"); err != nil {
		t.Fatal(err)
	}
	p.appMu.Lock()
	state, err = s.loadApp(p)
	active := len(p.work)
	p.appMu.Unlock()
	if err != nil || len(state.Jobs) != 0 || active != 0 {
		t.Fatal("Removed output restored ownership or held worker capacity", err, active)
	}
	if _, err := s.outbox.Info("nAlice", f.ID); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Removed blocked output acquired a committed receipt", err)
	}
}

type boundedJobInputs struct {
	*nativeFixture
	entered chan string
	release <-chan struct{}
	active  atomic.Int32
	maximum atomic.Int32
}

func (n *boundedJobInputs) Put(ctx context.Context, f protocol.File, source io.Reader) error {
	if _, err := io.Copy(io.Discard, source); err != nil {
		return err
	}
	active := n.active.Add(1)
	defer n.active.Add(-1)
	for old := n.maximum.Load(); active > old; old = n.maximum.Load() {
		if n.maximum.CompareAndSwap(old, active) {
			break
		}
	}
	n.entered <- f.ID
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-n.release:
		return nil
	}
}

func TestJobTransferCapacityStaysBoundedUntilCancelledOwnerRetires(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	release := make(chan struct{})
	n := &boundedJobInputs{nativeFixture: base, entered: make(chan string, 4), release: release}
	s.native = n
	owners := map[string]string{}
	for i := 1; i <= 3; i++ {
		f, _ := files.Describe(randomToken(), "owned.bin", []byte("owned"))
		if _, err := s.inbox.Put("nAlice", f, strings.NewReader("owned")); err != nil {
			t.Fatal(err)
		}
		id := fmt.Sprintf("%032x", i)
		owners[f.ID] = id
		if w := call("POST", "/v2/jobs", appInput{RequestID: id, CreatedAt: time.Now().UnixMilli(), Title: "Bounded transfer", Text: "owned", Files: []protocol.File{f}}, "nAlice"); w.Code != 202 {
			t.Fatal(w.Body.String())
		}
	}
	var retired []<-chan struct{}
	t.Cleanup(func() {
		s.cancel()
		close(release)
		for _, done := range retired {
			checksync.Join(t, done, 3*time.Second, "bounded transfer worker")
		}
	})
	start := func() <-chan error {
		done, result := make(chan struct{}), make(chan error, 1)
		retired = append(retired, done)
		go func() { defer close(done); result <- s.workPeer(p, nil) }()
		return result
	}
	first := start()
	firstFile := checksync.Receive(t, n.entered, 3*time.Second, "first input owner")
	second := start()
	secondFile := checksync.Receive(t, n.entered, 3*time.Second, "second input owner")
	if err := checksync.Receive(t, start(), 3*time.Second, "third worker yields at transfer capacity"); err != nil {
		t.Fatal(err)
	}
	select {
	case <-n.entered:
		t.Fatal("Third transfer exceeded the two-owner app limit")
	default:
	}
	cancel := func(file string) {
		t.Helper()
		if w := call("POST", "/v2/jobs/"+owners[file]+"/cancel", nil, "nAlice"); w.Code != 200 {
			t.Fatal(w.Body.String())
		}
	}
	cancel(firstFile)
	if err := checksync.Receive(t, first, 3*time.Second, "first transfer releases actual capacity"); err != nil {
		t.Fatal(err)
	}
	third := start()
	thirdFile := checksync.Receive(t, n.entered, 3*time.Second, "next job uses retired owner's slot")
	cancel(secondFile)
	cancel(thirdFile)
	for _, result := range []<-chan error{second, third} {
		if err := checksync.Receive(t, result, 3*time.Second, "cancelled bounded transfer retirement"); err != nil {
			t.Fatal(err)
		}
	}
	if n.maximum.Load() != 2 || n.active.Load() != 0 || base.calls != 0 {
		t.Fatal("Capacity was exceeded, retained after cancellation or dispatched", n.maximum.Load(), n.active.Load(), base.calls)
	}
}

func TestRunningWorkersProcessNewSameAppJobWhileAnotherInputIsHeld(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	registerFixture(t, call, "nAlice")
	release := make(chan struct{})
	fixture := &heldTransferNative{nativeFixture: base, entered: make(chan struct{}), release: release}
	s.native = fixture
	t.Cleanup(func() { s.cancel(); close(release) })
	f, _ := files.Describe(randomToken(), "owned.bin", []byte("owned"))
	if _, err := s.inbox.Put("nAlice", f, strings.NewReader("owned")); err != nil {
		t.Fatal(err)
	}
	first := appInput{RequestID: strings.Repeat("1", 32), CreatedAt: time.Now().UnixMilli(), Title: "Held background input", Text: "first", Files: []protocol.File{f}}
	if w := call("POST", "/v2/jobs", first, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	s.Run()
	checksync.Receive(t, fixture.entered, 3*time.Second, "running worker input ownership")
	second := appInput{RequestID: strings.Repeat("2", 32), CreatedAt: time.Now().UnixMilli(), Title: "New background admission", Text: "actual second result", Files: []protocol.File{}}
	if w := call("POST", "/v2/jobs", second, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	p := s.getPeer("nAlice")
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for {
		p.appMu.Lock()
		state, err := s.loadApp(p)
		p.appMu.Unlock()
		if err != nil {
			t.Fatal(err)
		}
		j := state.Jobs[second.RequestID]
		if j.State == "completed" && len(j.Outputs) == 1 {
			_, data, err := s.outbox.Read("nAlice", j.Outputs[0].ID)
			if err != nil || string(data) != second.Text {
				t.Fatal("Background progress did not produce the actual independent result", err)
			}
			break
		}
		select {
		case <-ctx.Done():
			t.Fatal("Normal running workers did not process a new admission before the next periodic tick")
		case <-ticker.C:
		}
	}
	if w := call("POST", "/v2/jobs/"+first.RequestID+"/cancel", nil, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
}

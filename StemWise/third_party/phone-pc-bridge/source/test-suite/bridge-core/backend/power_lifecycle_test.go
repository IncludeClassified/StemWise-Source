package backend

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/pc"
	"phonepcbridge/protocol"
	"strings"
	"testing"
	"testing/synctest"
	"time"
)

type powerFixtureNative struct {
	*nativeFixture
	observation  pc.PowerObservation
	shutdowns    int
	statusHook   func()
	shutdownHook func()
	loseReply    bool
	refusal      string
	refusalState string
	statuses     int
}

func (n *powerFixtureNative) Power(ctx context.Context, r pc.PowerRequest) (pc.PowerReply, error) {
	if !n.online {
		return pc.PowerReply{}, errors.New("offline")
	}
	if r.Operation == "status" && n.statusHook != nil {
		n.statusHook()
	}
	if r.Operation == "status" {
		n.statuses++
	}
	reply := pc.PowerReply{State: "ok", Observation: &n.observation, CycleID: r.CycleID}
	if r.Operation == "shutdown" {
		n.shutdowns++
		if n.shutdownHook != nil {
			n.shutdownHook()
		}
		if n.loseReply {
			return pc.PowerReply{}, errors.New("lost shutdown reply")
		}
		reply.Code = pc.PowerShutdownAccepted
		reply.ShutdownState = "accepted"
		if n.refusal != "" {
			reply.State, reply.Code, reply.ShutdownState = "rejected", n.refusal, n.refusalState
		}
	}
	return reply, nil
}

type powerHeldSendNative struct {
	*powerFixtureNative
	entered      chan struct{}
	release      chan struct{}
	ignoreCancel bool
	retirements  int
}

func (n *powerHeldSendNative) Call(ctx context.Context, r protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if r.Operation == "send" {
		close(n.entered)
		if n.ignoreCancel {
			<-n.release
			return n.nativeFixture.Call(ctx, r)
		}
		select {
		case <-n.release:
		case <-ctx.Done():
			return protocol.AdapterReply{}, ctx.Err()
		}
	}
	if r.Operation == "retire" {
		n.retirements++
		return protocol.AdapterReply{State: "ok", Result: mustJSON(map[string]any{"threadId": r.ThreadID, "turnId": r.TurnID, "terminalsRetired": true})}, nil
	}
	return n.nativeFixture.Call(ctx, r)
}

func TestPowerCancelDuringOriginalSendRetainsLateExactAckAndRetiresIt(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	base.online = true
	n := &powerHeldSendNative{powerFixtureNative: &powerFixtureNative{nativeFixture: base, observation: powerTestObservation()}, entered: make(chan struct{}), release: make(chan struct{}), ignoreCancel: true}
	s.native = n
	seedManagedPower(t, s, true)
	input := ownedOutputJob(randomToken()).Input
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	finished := make(chan struct{})
	var workerErr error
	go func() { defer close(finished); workerErr = s.workPeer(s.getPeer("nAlice"), nil) }()
	released := false
	defer func() {
		if !released {
			close(n.release)
		}
		s.cancel()
		checksync.Join(t, finished, 3*time.Second, "cancelled original send")
	}()
	select {
	case <-n.entered:
	case <-time.After(3 * time.Second):
		t.Fatal("Original send not reached")
	}
	if w := call("POST", "/v2/jobs/"+input.RequestID+"/cancel", nil, "nAlice"); w.Code != 202 {
		t.Fatal("Dispatching refused durable cancel intent", w.Code, w.Body.String())
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil || state.Jobs[input.RequestID].CancelRequested == 0 || state.Jobs[input.RequestID].CancelRetired != 0 {
		t.Fatal("Cancel intent was lost or incorrectly claimed retirement", err)
	}
	close(n.release)
	released = true
	checksync.Join(t, finished, 3*time.Second, "late accepted ACK and exact retirement")
	if workerErr != nil {
		t.Fatal(workerErr)
	}
	for range 2 {
		if err = s.workPass(); err != nil {
			t.Fatal(err)
		}
	}
	state, err = s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	job := state.Jobs[input.RequestID]
	if job.TurnID != "fixture-turn" || job.CancelRequested == 0 || job.CancelRetired == 0 || job.ChatState != "deleted" || n.retirements != 1 || base.calls != 1 {
		t.Fatal("Cancel lost original ACK, replayed send, or never retired/deleted", job, n.retirements, base.calls)
	}
	if readTestPower(t, s).HoldCode != "" {
		t.Fatal("Normal intent/ACK race incorrectly latched investigation")
	}
}

type powerHeldCreateNative struct {
	*powerFixtureNative
	entered   chan struct{}
	release   chan struct{}
	uncertain bool
	closed    bool
	discards  int
	creates   int
}

func (n *powerHeldCreateNative) Call(ctx context.Context, r protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if r.Operation == "create" {
		n.creates++
		close(n.entered)
		<-n.release
		if n.uncertain {
			return protocol.AdapterReply{State: "unknown", Code: "creation_outcome_unknown"}, nil
		}
		return n.nativeFixture.Call(ctx, r)
	}
	if r.Operation == "discard" {
		n.discards++
		result := map[string]any{"appId": r.AppID, "requestId": r.RequestID, "accepted": true}
		if n.closed {
			result["deleted"] = true
		}
		return protocol.AdapterReply{State: "ok", Result: mustJSON(result)}, nil
	}
	return n.nativeFixture.Call(ctx, r)
}

func TestPowerCancelWhileCreationPendingWaitsForExactPhysicalClosure(t *testing.T) {
	for _, uncertain := range []bool{false, true} {
		t.Run(map[bool]string{false: "late_known_ACK", true: "unknown_ACK"}[uncertain], func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			base.online = true
			n := &powerHeldCreateNative{powerFixtureNative: &powerFixtureNative{nativeFixture: base, observation: powerTestObservation()}, entered: make(chan struct{}), release: make(chan struct{}), uncertain: uncertain}
			s.native = n
			seedManagedPower(t, s, true)
			input := ownedOutputJob(randomToken()).Input
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			done := make(chan struct{})
			var workerErr error
			go func() { defer close(done); workerErr = s.workPeer(s.getPeer("nAlice"), nil) }()
			released := false
			defer func() {
				if !released {
					close(n.release)
				}
				s.cancel()
				checksync.Join(t, done, 3*time.Second, "pending creation retires")
			}()
			select {
			case <-n.entered:
			case <-time.After(3 * time.Second):
				t.Fatal("Actual create not reached")
			}
			if w := call("POST", "/v2/jobs/"+input.RequestID+"/cancel", nil, "nAlice"); w.Code != 200 {
				t.Fatal(w.Body.String())
			}
			snapshot, err := s.collectPower()
			if err != nil || snapshot.counts.Pending == 0 {
				t.Fatal("Empty native ID wrongly proved unknown creation drained", snapshot, err)
			}
			close(n.release)
			released = true
			checksync.Join(t, done, 3*time.Second, "late creation response")
			if workerErr != nil {
				t.Fatal(workerErr)
			}
			if uncertain {
				state, err := s.loadApp(s.getPeer("nAlice"))
				if err != nil {
					t.Fatal(err)
				}
				j := state.Jobs[input.RequestID]
				if j.ChatState == "deleted" || !j.CleanupAccepted || n.discards == 0 {
					t.Fatal("Intent ACK masqueraded as physical closure or was never requested", j, n.discards)
				}
				n.closed = true
			}
			if err = s.workPass(); err != nil {
				t.Fatal(err)
			}
			state, err := s.loadApp(s.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			if j := state.Jobs[input.RequestID]; j.ChatState != "deleted" || !powerJobDrained(j) || n.creates != 1 || base.calls != 0 {
				t.Fatal("Exact closure did not drain cancelled original creation without replay", j, n.creates, base.calls)
			}
		})
	}
}

func TestPowerDrainClockStartsAfterBlockedObservationAndActivity(t *testing.T) {
	s, base, _ := fixtureAPI(t)
	base.online = true
	advance := testPowerClock(s)
	n := &powerFixtureNative{nativeFixture: base, observation: powerTestObservation()}
	s.native = n
	seedManagedPower(t, s, true)
	n.statusHook = func() { s.beginPowerStream(); advance(4 * time.Second); s.endPowerStream(); advance(time.Second) }
	s.powerPass(time.Now())
	n.statusHook = nil
	advance(55 * time.Second)
	s.powerPass(time.Now())
	if n.shutdowns != 0 {
		t.Fatal("Slow status RPC backdated stable drain before work ended")
	}
	advance(5 * time.Second)
	s.powerPass(time.Now())
	if n.shutdowns != 1 {
		t.Fatal("Full post-observation grace never finished")
	}
}

func TestPowerWakeProofIsBoundedToOriginalPacketTransitionWindow(t *testing.T) {
	for _, eventAge := range []time.Duration{0, 7*24*time.Hour - 10*time.Second} {
		t.Run(eventAge.String(), func(t *testing.T) {
			s, base, _ := fixtureAPI(t)
			advance := testPowerClock(s)
			n := &powerFixtureNative{nativeFixture: base, observation: powerTestObservation()}
			s.native = n
			s.wake = func(context.Context) error { return nil }
			if _, _, err := s.requestWake(context.Background()); err != nil {
				t.Fatal(err)
			}
			advance(7 * 24 * time.Hour)
			n.online = true
			n.observation.UptimeMS = eventAge.Milliseconds()
			s.powerPass(time.Now())
			p := readTestPower(t, s)
			if eventAge == 0 && p.State != "wake_pending" {
				t.Fatal("Week-old claim took ownership of a later manual boot", p)
			}
			if eventAge > 0 && p.State != "managed" {
				t.Fatal("Delayed network connection lost valid original post-packet OS proof", p)
			}
		})
	}
}

func TestPowerWakeCooldownUsesBootClockAcrossWallJumpAndPiRestart(t *testing.T) {
	s, base, _ := fixtureAPI(t)
	advance := testPowerClock(s)
	n := &powerFixtureNative{nativeFixture: base, observation: powerTestObservation()}
	s.native = n
	packets := 0
	s.wake = func(context.Context) error { packets++; return nil }
	if _, _, err := s.requestWake(context.Background()); err != nil {
		t.Fatal(err)
	}
	s.powerMu.Lock()
	p, err := s.readPower()
	if err != nil {
		t.Fatal(err)
	}
	p.WakeClaimedAt = time.Now().Add(-24 * time.Hour).UnixMilli()
	err = s.writePower(p)
	s.powerMu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	if state, _, err := s.requestWake(context.Background()); err != nil || state != "wake_coalesced" || packets != 1 {
		t.Fatal("Wall jump bypassed packet cooldown", state, err, packets)
	}
	advance(time.Minute)
	if _, _, err := s.requestWake(context.Background()); err != nil || packets != 2 {
		t.Fatal("Explicit post-cooldown packet was refused", err, packets)
	}
	tick := int64(0)
	s.powerClockRead = func() (string, int64, error) { return strings.Repeat("b", 32), tick, nil }
	if state, _, err := s.requestWake(context.Background()); err != nil || state != "wake_coalesced" || packets != 2 {
		t.Fatal("Fresh Pi boot bypassed its own cooldown", state, err, packets)
	}
	tick = 60000
	if _, _, err := s.requestWake(context.Background()); err != nil || packets != 3 {
		t.Fatal("Mature new Pi boot refused explicit wake", err, packets)
	}
}

func TestPowerExplicitRevokeWaitsForPhysicalClosureWithoutInvestigationHold(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	base.online = true
	n := &powerHeldCreateNative{powerFixtureNative: &powerFixtureNative{nativeFixture: base, observation: powerTestObservation()}}
	s.native = n
	seedManagedPower(t, s, true)
	p := s.getPeer("nAlice")
	p.appMu.Lock()
	a, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	j.State, j.Code = "completed", "output_ready"
	j.Report = &appReport{Status: "completed"}
	a.Jobs[j.ID] = j
	if err = s.saveApp(a); err != nil {
		t.Fatal(err)
	}
	p.appMu.Unlock()
	if w := call("DELETE", "/v2/jobs/"+j.ID, nil, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	a, err = s.loadApp(p)
	if err != nil || a.Jobs[j.ID] != nil || !a.Cleanup[j.ID].RequireClosure || !a.Cleanup[j.ID].PCPending {
		t.Fatal("Revocation lost exact physical-cleanup obligation", a, err)
	}
	if err = s.workPass(); err != nil {
		t.Fatal(err)
	}
	snapshot, err := s.collectPower()
	if err != nil || snapshot.counts.Cleanup == 0 || snapshot.issue != "" {
		t.Fatal("Journal acceptance masqueraded as physical cleanup or invented failure", snapshot, err)
	}
	s.powerPass(time.Now())
	if p := readTestPower(t, s); p.DrainSince != 0 || p.HoldCode != "" {
		t.Fatal("Intent-only revocation started drain or latched investigation", p)
	}
	n.closed = true
	if err = s.workPass(); err != nil {
		t.Fatal(err)
	}
	snapshot, err = s.collectPower()
	if err != nil || snapshot.counts.Cleanup != 0 {
		t.Fatal("Exact deleted receipt did not retire revoked owner", snapshot, err)
	}
	s.powerPass(time.Now())
	if readTestPower(t, s).DrainSince == 0 {
		t.Fatal("Confirmed intentional revocation never drained")
	}
}

func TestPowerDisconnectCannotDropItsNewPhysicalClosureObligation(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	base.online = true
	n := &powerHeldCreateNative{powerFixtureNative: &powerFixtureNative{nativeFixture: base, observation: powerTestObservation()}}
	s.native = n
	seedManagedPower(t, s, true)
	p := s.getPeer("nAlice")
	p.appMu.Lock()
	a, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	j.State, j.Code = "completed", "output_ready"
	j.Report = &appReport{Status: "completed"}
	a.Jobs[j.ID] = j
	if err = s.saveApp(a); err != nil {
		t.Fatal(err)
	}
	p.appMu.Unlock()
	if w := call("DELETE", "/v2/app", nil, "nAlice"); w.Code != 503 {
		t.Fatal("Disconnect removed a newly journaled physical cleanup obligation", w.Code, w.Body.String())
	}
	a, err = s.loadApp(p)
	if err != nil || len(a.Jobs) != 0 || !a.Cleanup[j.ID].RequireClosure || !a.Cleanup[j.ID].PCPending {
		t.Fatal("Disconnect lost its compact owner", a, err)
	}
	n.closed = true
	if err = s.workPass(); err != nil {
		t.Fatal(err)
	}
	if w := call("DELETE", "/v2/app", nil, "nAlice"); w.Code != 200 {
		t.Fatal("Exact closure did not permit disconnect retry", w.Code, w.Body.String())
	}
	if _, err = s.loadApp(p); !os.IsNotExist(err) {
		t.Fatal("Completed disconnect retained registration", err)
	}
	if readTestPower(t, s).HoldCode != "" {
		t.Fatal("Intentional disconnect invented investigation failure")
	}
}

func TestPowerLiveSendAckAndSettingsDoNotLatchRecoveryUnknown(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	base.online = true
	n := &powerHeldSendNative{powerFixtureNative: &powerFixtureNative{nativeFixture: base, observation: powerTestObservation()}, entered: make(chan struct{}), release: make(chan struct{})}
	s.native = n
	seedManagedPower(t, s, true)
	input := ownedOutputJob(randomToken()).Input
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	finished := make(chan struct{})
	var workerErr error
	go func() { defer close(finished); workerErr = s.workPeer(s.getPeer("nAlice"), nil) }()
	released := false
	defer func() {
		if !released {
			close(n.release)
		}
		s.cancel()
		checksync.Join(t, finished, 3*time.Second, "held power send worker")
	}()
	select {
	case <-n.entered:
	case <-time.After(3 * time.Second):
		t.Fatal("Actual worker did not reach native send")
	}
	snapshot, err := s.collectPower()
	if err != nil || snapshot.issue != "" || snapshot.counts.Active == 0 {
		t.Fatal("Live exact send lease was treated as lost native outcome", snapshot, err)
	}
	if w := call("PUT", "/v2/codex/settings", map[string]string{}, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	s.powerPass(time.Now())
	if p := readTestPower(t, s); p.HoldCode != "" || p.DrainSince != 0 {
		t.Fatal("In-flight send/settings latched a false permanent failure", p)
	}
	close(n.release)
	released = true
	checksync.Join(t, finished, 3*time.Second, "native ACK settles")
	if workerErr != nil || base.calls != 1 || readTestPower(t, s).HoldCode != "" {
		t.Fatal("Normal exact ACK did not settle without false hold", workerErr)
	}
}

type powerCancelNative struct {
	*progressCancellationNative
	power *powerFixtureNative
}

func (n *powerCancelNative) Power(ctx context.Context, r pc.PowerRequest) (pc.PowerReply, error) {
	return n.power.Power(ctx, r)
}

func TestPowerUserCancelRetiresAndDeletesThroughNormalWorker(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	base.online = true
	seedManagedPower(t, s, true)
	p := s.getPeer("nAlice")
	p.appMu.Lock()
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	state.Jobs[j.ID] = j
	if err = s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	p.appMu.Unlock()
	progress := &progressCancellationNative{nativeFailureFixture: &nativeFailureFixture{nativeFixture: base, turn: mustJSON(map[string]any{"id": j.TurnID, "status": "inProgress", "items": []any{}})}, t: t, service: s, app: state.ID, owner: j}
	n := &powerCancelNative{progressCancellationNative: progress, power: &powerFixtureNative{nativeFixture: base, observation: powerTestObservation()}}
	s.native = n
	if w := call("POST", "/v2/jobs/"+j.ID+"/cancel", nil, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	progress.turn = mustJSON(map[string]any{"id": j.TurnID, "status": "interrupted", "bridgeTerminalCommitted": true, "items": []any{}})
	progress.failRetire = true
	if err = s.workPass(); err != nil {
		t.Fatal(err)
	}
	if readTestPower(t, s).HoldCode != "" {
		t.Fatal("Temporary user-cancel retirement error created investigation hold")
	}
	progress.failRetire = false
	for range 2 {
		if err = s.workPass(); err != nil {
			t.Fatal(err)
		}
	}
	state, err = s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	current := state.Jobs[j.ID]
	if current.CancelRetired == 0 || current.ChatState != "deleted" || !current.CleanupAccepted {
		t.Fatal("User cancellation never reached actual exact retirement and chat deletion", current)
	}
	snapshot, err := s.collectPower()
	if err != nil || snapshot.counts.Pending != 0 || snapshot.issue != "" || base.calls != 0 {
		t.Fatal("Retired/deleted user cancellation cannot drain", snapshot, err)
	}
	s.powerPass(time.Now())
	if p := readTestPower(t, s); p.HoldCode != "" || p.DrainSince == 0 {
		t.Fatal("Cancel-control diagnostics prevented safe drain", p)
	}
}

func TestPowerGraceUsesBootClockAndNotificationsCannotSpamStatus(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, base, _ := fixtureAPI(t)
		base.online = true
		n := &powerFixtureNative{nativeFixture: base, observation: powerTestObservation()}
		s.native = n
		advance := testPowerClock(s)
		seedManagedPower(t, s, true)
		s.powerPass(time.Now())
		s.powerPass(time.Now().Add(24 * time.Hour))
		if n.shutdowns != 0 {
			t.Fatal("Wall clock jump bypassed boot-clock grace")
		}
		advance(59 * time.Second)
		s.powerPass(time.Now().Add(48 * time.Hour))
		if n.shutdowns != 0 {
			t.Fatal("Boot-clock drain shorter than60seconds")
		}
		s.resetPowerStartup()
		advance(time.Second)
		s.powerPass(time.Now())
		if n.shutdowns != 0 {
			t.Fatal("Restart reused an almost-complete drain")
		}
		count := n.statuses
		done := make(chan struct{})
		go func() { defer close(done); s.managePower() }()
		synctest.Wait()
		for range 100 {
			s.powerChanged()
			synctest.Wait()
		}
		if n.statuses != count {
			t.Fatal("Local notifications caused remote PC power probes", n.statuses, count)
		}
		time.Sleep(15 * time.Second)
		synctest.Wait()
		if n.statuses != count+1 {
			t.Fatal("Timer did not perform exactly one bounded status observation", n.statuses, count)
		}
		s.cancel()
		synctest.Wait()
		<-done
	})
}

func TestPowerBusyRetriesFreshDrainButDefiniteOSFailureReopensJobsWithoutReplay(t *testing.T) {
	for _, code := range []string{"power_busy", "power_privilege_unavailable"} {
		t.Run(code, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			base.online = true
			advance := testPowerClock(s)
			n := &powerFixtureNative{nativeFixture: base, observation: powerTestObservation(), refusal: code}
			if code != "power_busy" {
				n.refusalState = "failed"
			}
			s.native = n
			seedManagedPower(t, s, true)
			s.powerPass(time.Now())
			advance(time.Minute)
			s.powerPass(time.Now())
			p := readTestPower(t, s)
			if n.shutdowns != 1 || p.AdmissionClosed || p.Code != code {
				t.Fatal("Confirmed refusal trapped admission or lost exact diagnostic", p, n.shutdowns)
			}
			if code == "power_busy" {
				if p.HoldCode != "" {
					t.Fatal("Ordinary concurrent I/O latched investigation hold")
				}
				s.powerPass(time.Now())
				advance(59 * time.Second)
				s.powerPass(time.Now())
				if n.shutdowns != 1 {
					t.Fatal("Busy retry skipped fresh drain")
				}
				advance(time.Second)
				s.powerPass(time.Now())
				if n.shutdowns != 2 {
					t.Fatal("Busy pre-action refusal never retried")
				}
			} else {
				if p.HoldCode != code || p.ShutdownAt == 0 {
					t.Fatal("Definite OS failure did not keep consumed investigation state", p)
				}
				if err := s.releasePower(p.CycleID); err != nil {
					t.Fatal(err)
				}
				s.powerPass(time.Now())
				advance(2 * time.Minute)
				s.powerPass(time.Now())
				if n.shutdowns != 1 {
					t.Fatal("Consumed OS refusal was repeated after hold release")
				}
				input := ownedOutputJob(randomToken()).Input
				if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
					t.Fatal("Definite OS refusal blocked unrelated new work", w.Code, w.Body.String())
				}
			}
		})
	}
}

func TestPowerActivityEntirelyBetweenObservationsRestartsFullDrain(t *testing.T) {
	for _, activity := range []string{"stream", "accepted_and_cancelled"} {
		t.Run(activity, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			base.online = true
			advance := testPowerClock(s)
			n := &powerFixtureNative{nativeFixture: base, observation: powerTestObservation()}
			s.native = n
			seedManagedPower(t, s, true)
			s.powerPass(time.Now())
			advance(50 * time.Second)
			if activity == "stream" {
				s.beginPowerStream()
				advance(5 * time.Second)
				s.endPowerStream()
			} else {
				input := ownedOutputJob(randomToken()).Input
				if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
					t.Fatal(w.Body.String())
				}
				advance(5 * time.Second)
				if w := call("POST", "/v2/jobs/"+input.RequestID+"/cancel", nil, "nAlice"); w.Code != 200 {
					t.Fatal(w.Body.String())
				}
			}
			advance(5 * time.Second)
			s.powerPass(time.Now())
			if n.shutdowns != 0 {
				t.Fatal("Between-poll activity reused old idle drain")
			}
			advance(59 * time.Second)
			s.powerPass(time.Now())
			if n.shutdowns != 0 {
				t.Fatal("Restarted drain was shorter than60seconds")
			}
			advance(time.Second)
			s.powerPass(time.Now())
			if n.shutdowns != 1 {
				t.Fatal("Fresh full drain never authorized shutdown", n.shutdowns)
			}
		})
	}
}
func powerTestObservation() pc.PowerObservation {
	return pc.PowerObservation{BootID: strings.Repeat("1", 32), TransitionID: strings.Repeat("2", 64), UptimeMS: 0}
}
func readTestPower(t *testing.T, s *Service) *powerState {
	t.Helper()
	s.powerMu.Lock()
	defer s.powerMu.Unlock()
	p, e := s.readPower()
	if e != nil {
		t.Fatal(e)
	}
	return p
}
func testPowerClock(s *Service) func(time.Duration) {
	tick := int64(120000)
	s.powerClockRead = func() (string, int64, error) { return strings.Repeat("a", 32), tick, nil }
	return func(d time.Duration) { tick += d.Milliseconds() }
}
func seedManagedPower(t *testing.T, s *Service, hasWork bool) *powerState {
	t.Helper()
	p := newPowerState()
	p.State = "managed"
	p.PacketConfirmed = true
	p.WakeClaimedAt = time.Now().Add(-2 * time.Minute).UnixMilli()
	p.ManagedAt = time.Now().Add(-time.Minute).UnixMilli()
	p.TransitionID = powerTestObservation().TransitionID
	p.HasWork = hasWork
	if s.powerClockRead == nil {
		testPowerClock(s)
	}
	p.GraceClockID, p.ManagedTick, _ = s.clockPower()
	p.ManagedTick -= 60000
	s.powerMu.Lock()
	err := s.writePower(p)
	s.powerMu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func TestPowerWakeRequiresConfirmedPacketAndPostClaimOSProof(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, base, call := fixtureAPI(t)
		advance := testPowerClock(s)
		registerFixture(t, call, "nAlice")
		n := &powerFixtureNative{nativeFixture: base, observation: powerTestObservation()}
		s.native = n
		packets := 0
		s.wake = func(context.Context) error { packets++; return nil }
		state, _, err := s.requestWake(context.Background())
		if err != nil || state != "packet_sent" || packets != 1 {
			t.Fatal(state, err, packets)
		}
		cycle := readTestPower(t, s).CycleID
		if state, _, err = s.requestWake(context.Background()); err != nil || state != "wake_coalesced" || packets != 1 {
			t.Fatal("Repeated wake sent a second packet", state, err, packets)
		}
		time.Sleep(3 * time.Second)
		advance(3 * time.Second)
		n.online = true
		n.observation.UptimeMS = time.Hour.Milliseconds()
		s.powerPass(time.Now())
		if readTestPower(t, s).State != "wake_pending" {
			t.Fatal("Network return of old PC incorrectly established wake ownership")
		}
		n.observation.UptimeMS = 0
		s.powerPass(time.Now())
		p := readTestPower(t, s)
		if p.State != "managed" || p.CycleID != cycle {
			t.Fatal("Fresh OS transition did not establish confirmed ownership", p)
		}
		s.powerPass(time.Now().Add(3 * time.Minute))
		if n.shutdowns != 0 {
			t.Fatal("Wake-only cycle shut down without accepted work")
		}
		// A confirmed managed transition can survive process restart, with a fresh drain window.
		s.resetPowerStartup()
		if readTestPower(t, s).State != "managed" {
			t.Fatal("Startup discarded proved ownership")
		}
	})
}

func TestPowerClaimWithoutPacketAndUnknownSenderNeverOwnManualBoot(t *testing.T) {
	for _, mode := range []string{"crash_before_packet", "unknown_sender"} {
		t.Run(mode, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				s, base, _ := fixtureAPI(t)
				advance := testPowerClock(s)
				n := &powerFixtureNative{nativeFixture: base, observation: powerTestObservation()}
				s.native = n
				if mode == "unknown_sender" {
					s.wake = func(context.Context) error { return errors.New("uncertain sender") }
					if _, _, err := s.requestWake(context.Background()); err == nil {
						t.Fatal("Unknown sender claimed success")
					}
				} else {
					id, tick, _ := s.clockPower()
					p := newPowerState()
					p.State, p.WakeClaimedAt, p.ClockID, p.ClaimTick = "wake_pending", time.Now().UnixMilli(), id, tick
					s.powerMu.Lock()
					err := s.writePower(p)
					s.powerMu.Unlock()
					if err != nil {
						t.Fatal(err)
					}
				}
				time.Sleep(3 * time.Second)
				advance(3 * time.Second)
				n.online = true
				s.powerPass(time.Now())
				s.resetPowerStartup()
				s.powerPass(time.Now().Add(time.Hour))
				if p := readTestPower(t, s); p.State != "wake_pending" || p.PacketConfirmed || n.shutdowns != 0 {
					t.Fatal("Unconfirmed packet armed a manual boot", p)
				}
			})
		})
	}
}

func TestPowerAlreadyOnAndHistoryOnlySettingsDoNotArmShutdown(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	base.online = true
	n := &powerFixtureNative{nativeFixture: base, observation: powerTestObservation()}
	s.native = n
	packets := 0
	s.wake = func(context.Context) error { packets++; return nil }
	if state, pcState, err := s.requestWake(context.Background()); err != nil || state != "already_on" || pcState != "reachable" || packets != 0 {
		t.Fatal(state, pcState, err)
	}
	seedManagedPower(t, s, false)
	p := s.getPeer("nAlice")
	p.appMu.Lock()
	a, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	j.State = "completed"
	j.Code = "output_ready"
	j.Report = &appReport{Status: "completed"}
	j.Delivered = time.Now().UnixMilli()
	j.ChatState = "deleted"
	a.Jobs[j.ID] = j
	if err = s.saveApp(a); err != nil {
		t.Fatal(err)
	}
	p.appMu.Unlock()
	if readTestPower(t, s).HasWork {
		t.Fatal("Settings/history commit counted already-drained work")
	}
	s.powerPass(time.Now().Add(2 * time.Minute))
	if n.shutdowns != 0 {
		t.Fatal("Idle retained history armed auto shutdown")
	}
}

func TestPowerGlobalDrainAdmissionFenceAndNoReplayAfterLostReply(t *testing.T) {
	s, base, call := fixtureAPI(t)
	advance := testPowerClock(s)
	registerFixture(t, call, "nAlice")
	registerFixture(t, call, "nBob")
	base.online = true
	n := &powerFixtureNative{nativeFixture: base, observation: powerTestObservation(), loseReply: true}
	s.native = n
	seedManagedPower(t, s, false)
	input := appInput{RequestID: randomToken(), Title: "cancel before send", Text: "bounded", Files: []protocol.File{}, CreatedAt: time.Now().UnixMilli()}
	if w := call("POST", "/v2/jobs", input, "nBob"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	s.powerPass(time.Now())
	if readTestPower(t, s).DrainSince != 0 {
		t.Fatal("Another app pending job did not block global drain")
	}
	if w := call("POST", "/v2/jobs/"+input.RequestID+"/cancel", nil, "nBob"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	s.powerPass(time.Now())
	p := readTestPower(t, s)
	if p.DrainSince == 0 || p.HoldCode != "" {
		t.Fatal("Retired never-sent cancellation did not enter drain", p)
	}
	advance(59 * time.Second)
	s.powerPass(time.Now().Add(59 * time.Second))
	if n.shutdowns != 0 {
		t.Fatal("Stable drain interval was bypassed")
	}
	n.shutdownHook = func() {
		if !readTestPower(t, s).AdmissionClosed {
			t.Fatal("OS send preceded durable admission fence")
		}
	}
	advance(2 * time.Second)
	s.powerPass(time.Now().Add(61 * time.Second))
	if n.shutdowns != 1 {
		t.Fatal("Did not send exactly one drained shutdown", n.shutdowns)
	}
	if w := call("POST", "/v2/jobs", input, "nBob"); w.Code != 200 {
		t.Fatal("Existing immutable retry was fenced", w.Code, w.Body.String())
	}
	input.RequestID = randomToken()
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 409 || !strings.Contains(w.Body.String(), "power_shutdown_pending") {
		t.Fatal("New acceptance crossed shutdown claim", w.Code, w.Body.String())
	}
	s.resetPowerStartup()
	s.powerPass(time.Now().Add(5 * time.Minute))
	if n.shutdowns != 1 {
		t.Fatal("Lost ACK was replayed after restart")
	}
	if err := s.releasePower(p.CycleID); err == nil {
		t.Fatal("Explicit release replayed an uncertain OS mutation")
	}
}

func TestPowerFaultSurvivesJobExpiryAndCycleReplacement(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	n := &powerFixtureNative{nativeFixture: base, observation: powerTestObservation()}
	s.native = n
	original := seedManagedPower(t, s, true)
	p := s.getPeer("nAlice")
	p.appMu.Lock()
	a, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	j.State, j.Code = "failed", "native_terminal_unconfirmed"
	a.Jobs[j.ID] = j
	if err = s.saveApp(a); err != nil {
		t.Fatal(err)
	}
	if err = s.expireApp(a, time.UnixMilli(j.Created).Add(appRetention+time.Second)); err != nil {
		t.Fatal(err)
	}
	p.appMu.Unlock()
	held := readTestPower(t, s)
	if held.HoldCode == "" {
		t.Fatal("Expiry erased the last failure evidence")
	}
	s.wake = func(context.Context) error { return nil }
	if _, _, err = s.requestWake(context.Background()); err != nil {
		t.Fatal(err)
	}
	current := readTestPower(t, s)
	if current.CycleID == original.CycleID || current.HoldCode != held.HoldCode {
		t.Fatal("New wake cycle erased investigation hold", current)
	}
	if s.releasePower(original.CycleID) == nil {
		t.Fatal("Stale cycle released current hold")
	}
	if err = s.releasePower(current.CycleID); err != nil {
		t.Fatal("Investigated removed issue could not be released", err)
	}
}

func TestPowerCorruptionDisablesActionsWithoutBlockingOrdinaryJobs(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	base.online = true
	n := &powerFixtureNative{nativeFixture: base, observation: powerTestObservation()}
	s.native = n
	if err := os.WriteFile(filepath.Join(s.cfg.DataDir, "power.json"), []byte(`{"version":1}`), 0600); err != nil {
		t.Fatal(err)
	}
	input := appInput{RequestID: randomToken(), Title: "ordinary job", Text: "context", CreatedAt: time.Now().UnixMilli(), Files: []protocol.File{}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal("Corrupt optional power state blocked job API", w.Code, w.Body.String())
	}
	s.powerPass(time.Now())
	if n.shutdowns != 0 {
		t.Fatal("Corrupt state allowed power action")
	}
	if _, _, err := s.requestWake(context.Background()); err == nil {
		t.Fatal("Corrupt power state was silently replaced")
	}
}

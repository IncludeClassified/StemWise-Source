package pc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
	"tailscale.com/tsnet"
)

type fakePowerSystem struct {
	mu        sync.Mutex
	state     PowerObservation
	calls     int
	observe   func()
	shutdown  func() error
	privilege *bool
}

func (f *fakePowerSystem) ShutdownPrivilege(context.Context) (bool, error) {
	if f.privilege == nil {
		return false, errors.New("private token inspection unavailable")
	}
	return *f.privilege, nil
}

func (f *fakePowerSystem) Observe(ctx context.Context) (PowerObservation, error) {
	if ctx.Err() != nil {
		return PowerObservation{}, ctx.Err()
	}
	if f.observe != nil {
		f.observe()
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.state, nil
}
func (f *fakePowerSystem) Shutdown(context.Context) error {
	f.mu.Lock()
	f.calls++
	f.mu.Unlock()
	if f.shutdown != nil {
		return f.shutdown()
	}
	return nil
}

func powerFixture(t *testing.T) (*Service, *fakePowerSystem) {
	t.Helper()
	platform := &fakePowerSystem{state: PowerObservation{BootID: strings.Repeat("a", 32), TransitionID: strings.Repeat("b", 64), UptimeMS: 20000}}
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	s, err := New(&tsnet.Server{}, Config{Identity: "nPc", Host: "pc.tailtest.ts.net", CodexExecutable: executable, DataDir: t.TempDir(), AllowedPeers: []string{"nPi", "nApp"}, BackendPeer: "nPi", powerSystem: platform})
	if err != nil {
		t.Fatal(err)
	}
	s.authenticate = func(context.Context, string) (string, error) { return "nPi", nil }
	t.Cleanup(func() { s.Close() })
	return s, platform
}

func callPower(t *testing.T, s *Service, input PowerRequest) (int, PowerReply) {
	t.Helper()
	input.Nonce = randomToken()
	raw, _ := json.Marshal(input)
	r := httptest.NewRequest("POST", "/internal/v1/power", bytes.NewReader(raw))
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	s.handle(w, r)
	var reply PowerReply
	if json.Unmarshal(w.Body.Bytes(), &reply) != nil || reply.Protocol != PowerProtocol || reply.PCIdentity != "nPc" || (w.Code == 200 && reply.Nonce != input.Nonce) {
		t.Fatal("Uncorrelated power response", w.Code, w.Body.String())
	}
	return w.Code, reply
}

func TestPowerStatusIsPrivateMachineObservation(t *testing.T) {
	s, platform := powerFixture(t)
	code, reply := callPower(t, s, PowerRequest{Operation: "status"})
	if code != 200 || reply.State != "ok" || reply.Code != "power_status_ready" || reply.Observation == nil || !reply.Observation.Valid() || reply.Observation.WakeAgeMS != nil || platform.calls != 0 {
		t.Fatal(code, reply, platform.calls)
	}
	zero := int64(0)
	platform.state.WakeAgeMS = &zero
	_, reply = callPower(t, s, PowerRequest{Operation: "status"})
	if reply.Observation.WakeAgeMS == nil || *reply.Observation.WakeAgeMS != 0 {
		t.Fatal("Resume age zero was discarded", reply)
	}
	s.authenticate = func(context.Context, string) (string, error) { return "nApp", nil }
	if code, _ := callPower(t, s, PowerRequest{Operation: "shutdown", CycleID: randomToken(), TransitionID: platform.state.TransitionID}); code != 403 || platform.calls != 0 {
		t.Fatal("App could directly request PC power", code, platform.calls)
	}
}

func TestPowerStatusReportsOnlyKnownPrivilegeAssignment(t *testing.T) {
	s, platform := powerFixture(t)
	for _, variant := range []string{"unknown", "assigned", "absent"} {
		platform.privilege = nil
		if variant != "unknown" {
			assigned := variant == "assigned"
			platform.privilege = &assigned
		}
		_, reply := callPower(t, s, PowerRequest{Operation: "status"})
		if reply.State != "ok" || platform.calls != 0 || (variant == "unknown" && reply.ShutdownPrivilegeAvailable != nil) || (variant != "unknown" && (reply.ShutdownPrivilegeAvailable == nil || *reply.ShutdownPrivilegeAvailable != (variant == "assigned"))) {
			t.Fatal("Read-only status invented permission or invoked power action", variant, reply, platform.calls)
		}
	}
}

func TestPowerShutdownCommitsBeforeOSAndNeverRepeatsOnSameTransition(t *testing.T) {
	for _, outcome := range []string{"accepted", "failed", "lost-outcome"} {
		t.Run(outcome, func(t *testing.T) {
			s, platform := powerFixture(t)
			request := PowerRequest{Operation: "shutdown", CycleID: randomToken(), TransitionID: platform.state.TransitionID}
			platform.shutdown = func() error {
				if _, _, err := s.powerStore.Read("nPi", powerRecordID(request.TransitionID, "intent")); err != nil {
					t.Fatal("OS boundary entered without durable intent", err)
				}
				if _, _, err := s.powerStore.Read("nPi", powerRecordID(request.TransitionID, "outcome")); !errors.Is(err, files.ErrAbsent) {
					t.Fatal("Result fabricated before OS response", err)
				}
				if outcome == "failed" {
					return errors.New("private OS failure must not escape")
				}
				if outcome == "lost-outcome" {
					s.powerStore.Close()
				}
				return nil
			}
			_, reply := callPower(t, s, request)
			want := map[string]string{"accepted": "accepted", "failed": "failed", "lost-outcome": "unconfirmed"}[outcome]
			if reply.ShutdownState != want || platform.calls != 1 || strings.Contains(reply.Code, "private") {
				t.Fatal(reply, platform.calls)
			}
			cfg := s.cfg
			s.Close()
			reopened, err := New(&tsnet.Server{}, cfg)
			if err != nil {
				t.Fatal(err)
			}
			defer reopened.Close()
			reopened.authenticate = func(context.Context, string) (string, error) { return "nPi", nil }
			_, again := callPower(t, reopened, request)
			if again.ShutdownState != want || platform.calls != 1 {
				t.Fatal("Restart replayed or changed consumed power outcome", again, platform.calls)
			}
			request.CycleID = randomToken()
			_, other := callPower(t, reopened, request)
			if other.Code != "power_transition_consumed" || platform.calls != 1 {
				t.Fatal("New UUID bypassed transition fence", other, platform.calls)
			}
		})
	}
}

func TestPowerPrecommittedUnknownSurvivesRestartAndResumeUnsealsSameHost(t *testing.T) {
	s, platform := powerFixture(t)
	request := PowerRequest{Operation: "shutdown", CycleID: randomToken(), TransitionID: platform.state.TransitionID}
	intent := powerIntent{"v1", request.CycleID, request.TransitionID}
	raw, _ := json.Marshal(intent)
	f, _ := files.Describe(powerRecordID(request.TransitionID, "intent"), "power-intent.json", raw)
	if _, err := s.powerStore.Put("nPi", f, bytes.NewReader(raw)); err != nil {
		t.Fatal(err)
	}
	cfg := s.cfg
	s.Close()
	reopened, err := New(&tsnet.Server{}, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	reopened.authenticate = func(context.Context, string) (string, error) { return "nPi", nil }
	_, reply := callPower(t, reopened, request)
	if reply.State != "unknown" || reply.ShutdownState != "unconfirmed" || platform.calls != 0 {
		t.Fatal("Unknown intent was replayed", reply, platform.calls)
	}
	if _, err := reopened.QueueFile(context.Background(), "nPi", randomToken(), "blocked.bin", []byte("blocked")); err == nil {
		t.Fatal("Same-transition uncertainty admitted file work")
	}
	platform.state.TransitionID = strings.Repeat("c", 64)
	zero := int64(0)
	platform.state.WakeAgeMS = &zero
	_, reply = callPower(t, reopened, PowerRequest{Operation: "status"})
	if reply.State != "ok" {
		t.Fatal(reply)
	}
	if _, err := reopened.QueueFile(context.Background(), "nPi", randomToken(), "resumed.bin", []byte("resumed")); err != nil {
		t.Fatal("Real new OS transition stayed sealed", err)
	}
	_, reply = callPower(t, reopened, request)
	if reply.Code != "power_transition_mismatch" || platform.calls != 0 {
		t.Fatal("Old transition gained new authority", reply)
	}
	request.CycleID, request.TransitionID = randomToken(), platform.state.TransitionID
	_, reply = callPower(t, reopened, request)
	if reply.State != "ok" || platform.calls != 1 {
		t.Fatal("New armed transition could not consume its one attempt", reply, platform.calls)
	}
}

func TestPowerBusyFileBodyAndValidationTransitionDoNotIssueOSAction(t *testing.T) {
	s, platform := powerFixture(t)
	request := PowerRequest{Operation: "shutdown", CycleID: randomToken(), TransitionID: platform.state.TransitionID}
	entered, release := make(chan struct{}), make(chan struct{})
	done, retired := make(chan error, 1), make(chan struct{})
	source := &heldFileReader{reader: strings.NewReader("held"), entered: entered, release: release}
	f, _ := files.Describe(randomToken(), "held.bin", []byte("held"))
	var unblock sync.Once
	t.Cleanup(func() {
		unblock.Do(func() { close(release) })
		checksync.Join(t, retired, 3*time.Second, "power-blocking actual upload")
	})
	go func() {
		defer close(retired)
		_, err := s.QueueFileReader(context.Background(), "nPi", f, source)
		done <- err
	}()
	checksync.Receive(t, entered, time.Second, "held actual file source")
	_, reply := callPower(t, s, request)
	if reply.Code != "power_busy" || platform.calls != 0 {
		t.Fatal("Power ignored actual in-flight bytes", reply, platform.calls)
	}
	unblock.Do(func() { close(release) })
	if err := checksync.Receive(t, done, time.Second, "retired upload"); err != nil {
		t.Fatal(err)
	}
	reads := 0
	platform.observe = func() {
		reads++
		if reads == 2 {
			platform.state.TransitionID = strings.Repeat("d", 64)
		}
	}
	_, reply = callPower(t, s, request)
	if reply.Code != "power_transition_unconfirmed" || platform.calls != 0 {
		t.Fatal("Changed final OS transition authorized shutdown", reply, platform.calls)
	}
	if page, err := s.powerStore.List("nPi", "", 100); err != nil || len(page.Files) != 0 {
		t.Fatal("Pre-action refusal consumed power intent", page, err)
	}
}

func TestPowerBlocksLiveOwnershipAndObservesRealIdleNativeBeforeClose(t *testing.T) {
	s, platform := powerFixture(t)
	request := PowerRequest{Operation: "shutdown", CycleID: randomToken(), TransitionID: platform.state.TransitionID}
	owner := creationInput{AppID: randomToken(), RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Name: "unresolved", Project: s.cfg.DataDir}
	raw, _ := json.Marshal(owner)
	f, _ := files.Describe(creationID("nPi", owner.AppID, owner.RequestID, "request"), "creation-request.json", raw)
	if _, err := s.creations.Put("nPi", f, bytes.NewReader(raw)); err != nil {
		t.Fatal(err)
	}
	_, reply := callPower(t, s, request)
	if reply.Code != "power_owned_work_unconfirmed" || platform.calls != 0 {
		t.Fatal("Uncertain creation was treated as idle", reply)
	}

	fNative := newAuthFixture(t, "normal")
	fNative.service.powerOS = platform
	if err := os.WriteFile(filepath.Join(fNative.home, "active"), []byte("active"), 0600); err != nil {
		t.Fatal(err)
	}
	if result := fNative.call(protocol.AdapterRequest{Operation: "auth"}); result.State != "ok" {
		t.Fatal(result)
	}
	_, reply = callPower(t, fNative.service, request)
	if reply.Code != "power_native_not_quiescent" || platform.calls != 0 {
		t.Fatal("Live native runtime was closed for power-off", reply)
	}
	connection := fNative.service.peers["nPi"].conn
	select {
	case <-connection.Done():
		t.Fatal("Active native child was stopped")
	default:
	}
	if err := os.Remove(filepath.Join(fNative.home, "active")); err != nil {
		t.Fatal(err)
	}
	_, reply = callPower(t, fNative.service, request)
	if reply.State != "ok" || platform.calls != 1 {
		t.Fatal("Authoritatively idle owned runtime could not retire", reply, platform.calls)
	}
	select {
	case <-connection.Done():
	default:
		t.Fatal("OS boundary preceded owned native retirement")
	}
}

func TestPowerExclusiveBoundaryRejectsNewNativeAndFileWork(t *testing.T) {
	s, platform := powerFixture(t)
	entered, release := make(chan struct{}), make(chan struct{})
	done, retired := make(chan PowerReply, 1), make(chan struct{})
	var unblock sync.Once
	platform.shutdown = func() error { close(entered); <-release; return nil }
	t.Cleanup(func() {
		unblock.Do(func() { close(release) })
		checksync.Join(t, retired, 3*time.Second, "fake OS power boundary")
	})
	request := PowerRequest{Operation: "shutdown", CycleID: randomToken(), TransitionID: platform.state.TransitionID}
	go func() { defer close(retired); _, reply := callPower(t, s, request); done <- reply }()
	checksync.Receive(t, entered, time.Second, "precommitted exclusive power boundary")
	raw, _ := json.Marshal(protocol.AdapterRequest{Nonce: randomToken(), Operation: "models"})
	r := httptest.NewRequest("POST", "/internal/v1/codex", bytes.NewReader(raw))
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	s.handle(w, r)
	var rejected protocol.AdapterReply
	if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &rejected) != nil || rejected.State != "rejected" || rejected.Code != "power_busy" || rejected.Result != nil || rejected.Failure != nil || s.peers["nPi"].conn != nil {
		t.Fatal("New native work entered the final power fence", w.Code, w.Body.String())
	}
	f, _ := files.Describe(randomToken(), "refused.bin", []byte("refused"))
	w = httptest.NewRecorder()
	s.handle(w, httptest.NewRequest("POST", contextUploadPath(f), strings.NewReader("refused")))
	if w.Code != 503 {
		t.Fatal("Inbound body entered final power fence", w.Code, w.Body.String())
	}
	if _, err := s.inbox.Info("nPi", f.ID); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Refused power-fenced upload committed bytes", err)
	}
	unblock.Do(func() { close(release) })
	if reply := checksync.Receive(t, done, time.Second, "completed fake OS request"); reply.State != "ok" || platform.calls != 1 {
		t.Fatal(reply, platform.calls)
	}
}

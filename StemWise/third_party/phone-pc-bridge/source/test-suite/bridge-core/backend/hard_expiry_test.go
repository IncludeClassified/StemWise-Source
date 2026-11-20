package backend

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

func TestEveryJobExpiresFromReceiverAdmissionWithoutPCOrDelivery(t *testing.T) {
	for _, status := range []string{"queued", "unknown", "dispatching", "submitted", "completed", "failed", "interrupted", "rejected", "cancelled"} {
		t.Run(status, func(t *testing.T) {
			s, native, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			job := ownedOutputJob(randomToken())
			job.State, job.Created = status, time.Now().Add(-appRetention+time.Hour).UnixMilli()
			job.Input.Text = "private-expired-input-" + randomToken()
			job.Input.CreatedAt = time.Now().Add(365 * 24 * time.Hour).UnixMilli()
			if status == "cancelled" {
				job.TurnID = ""
			}
			f, _ := files.Describe(randomToken(), "context.bin", []byte("owned actual input"))
			job.Input.Files = []protocol.File{f}
			job.Fingerprint = appHash(job.Input)
			state.Jobs[job.ID] = job
			state.Prompts[randomToken()] = appPrompt{Title: "Keep saved prompt", Text: "saved setting persists"}
			if _, err := s.inbox.Put(p.identity, f, strings.NewReader("owned actual input")); err != nil {
				t.Fatal(err)
			}
			if err := s.saveApp(state); err != nil {
				t.Fatal(err)
			}
			deadline := time.UnixMilli(job.Created).Add(appRetention)
			if appJobView(job)["expiresAt"] != deadline.UnixMilli() {
				t.Fatal("Deadline did not derive from receiver admission")
			}
			if _, err := s.retentionPass(deadline.Add(-time.Millisecond)); err != nil {
				t.Fatal(err)
			}
			loaded, err := s.loadApp(p)
			if err != nil || loaded.Jobs[job.ID] == nil {
				t.Fatal("Job expired early", err)
			}
			p.appMu.Lock()
			work := s.beginJobWork(p, state.ID, loaded.Jobs[job.ID])
			p.appMu.Unlock()
			if work == nil {
				t.Fatal("Before-deadline owner could not acquire work")
			}
			defer func() {
				if work != nil {
					p.appMu.Lock()
					s.endJobWork(p, work)
					p.appMu.Unlock()
				}
			}()
			if _, err := s.retentionPass(deadline); err != nil {
				t.Fatal(err)
			}
			if work.ctx.Err() == nil {
				t.Fatal("Expiry did not cancel the actual owned local work context")
			}
			loaded, err = s.loadApp(p)
			if err != nil || len(loaded.Jobs) != 0 || !loaded.Cleanup[job.ID].PCPending || len(loaded.Cleanup[job.ID].Files) != 0 || len(loaded.Prompts) != 1 || native.calls != 0 {
				t.Fatal("Offline expiry retained payload or lost its compact cleanup obligation", err, loaded)
			}
			if _, err := s.inbox.Info(p.identity, f.ID); !errors.Is(err, files.ErrAbsent) {
				t.Fatal("Recent owned bytes survived expired job", err)
			}
			raw, err := os.ReadFile(filepath.Join(s.cfg.DataDir, "apps", appStateName(p.identity)))
			if err != nil || bytes.Contains(raw, []byte(job.Input.Text)) || bytes.Contains(raw, []byte("context.bin")) {
				t.Fatal("Compact journal kept original prompt or descriptor content", err)
			}
			if w := call("POST", "/v2/jobs", job.Input, p.identity); w.Code != 410 {
				t.Fatal("Expired original job could be replayed", w.Body.String())
			}
			p.appMu.Lock()
			s.endJobWork(p, work)
			work = nil
			p.appMu.Unlock()
			// Close/reopen retains the exact obligation even though the PC was unavailable at expiry.
			reopened, err := New(s.cfg, native, s.authenticate, nil)
			if err != nil {
				t.Fatal(err)
			}
			defer reopened.Close()
			if err := reopened.workPass(); err != nil {
				t.Fatal(err)
			}
			loaded, err = reopened.loadApp(reopened.getPeer(p.identity))
			if err != nil || len(loaded.Cleanup) != 1 {
				t.Fatal("Offline restart forgot cleanup", err)
			}
			native.online = true
			if err := reopened.workPass(); err != nil {
				t.Fatal(err)
			}
			loaded, err = reopened.loadApp(reopened.getPeer(p.identity))
			if err != nil || len(loaded.Cleanup) != 0 || len(loaded.Jobs) != 0 || native.calls != 0 {
				t.Fatal("PC cleanup delegation replayed job or failed to retire obligation", err)
			}
		})
	}
}

type outputCommitExitNative struct {
	*nativeFixture
	service *Service
	file    protocol.File
}
type outputCommitExitReader struct {
	io.Reader
	close func()
}

func (r *outputCommitExitReader) Close() error { r.close(); return nil }
func (n *outputCommitExitNative) Output(context.Context, protocol.File) (io.ReadCloser, error) {
	return &outputCommitExitReader{Reader: strings.NewReader("committed before process death"), close: func() {
		if actual, err := n.service.outbox.Info("nAlice", n.file.ID); err == nil && actual == n.file {
			os.Exit(41)
		}
		os.Exit(42)
	}}, nil
}

func TestHardExpiryCommittedOutputProcessDeathHelper(t *testing.T) {
	if os.Getenv("BRIDGE_EXPIRY_CRASH_HELPER") != "committed-output" {
		t.Skip("Owned subprocess fixture only")
	}
	var cfg Config
	if json.Unmarshal([]byte(os.Getenv("BRIDGE_EXPIRY_CRASH_CONFIG")), &cfg) != nil {
		t.Fatal("Missing owned child config")
	}
	base := &nativeFixture{online: true, outputs: map[string][]byte{}}
	s, err := New(cfg, base, func(_ context.Context, peer string) (string, error) { return peer, nil }, nil)
	if err != nil {
		t.Fatal(err)
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil || len(state.Jobs) != 1 {
		t.Fatal("Missing exact child fixture owner", err)
	}
	var job *appJob
	for _, j := range state.Jobs {
		job = j
	}
	f, _ := files.Describe(os.Getenv("BRIDGE_EXPIRY_CRASH_OUTPUT"), "crash.bin", []byte("committed before process death"))
	base.report = string(mustJSON(appReport{Protocol: "v2", AppID: state.ID, JobID: job.ID, Revision: 1, Status: "completed", Code: "ready", Files: []protocol.File{f}}))
	s.native = &outputCommitExitNative{nativeFixture: base, service: s, file: f}
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	t.Fatal("Fixture did not stop after the actual output commit")
}

func TestHardExpiryRetainsOutputClaimAcrossProcessDeathAfterByteCommit(t *testing.T) {
	s, _, call := fixtureAPI(t)
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
	f, _ := files.Describe(randomToken(), "crash.bin", []byte("committed before process death"))
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestHardExpiryCommittedOutputProcessDeathHelper$", "-test.count=1")
	cmd.Env = append(os.Environ(), "BRIDGE_EXPIRY_CRASH_HELPER=committed-output", "BRIDGE_EXPIRY_CRASH_CONFIG="+string(mustJSON(s.cfg)), "BRIDGE_EXPIRY_CRASH_OUTPUT="+f.ID)
	output, err := cmd.CombinedOutput()
	var exit *exec.ExitError
	if !errors.As(err, &exit) || exit.ExitCode() != 41 || ctx.Err() != nil {
		t.Fatal("Actual post-commit process-death boundary was not reached", err, string(output))
	}
	state, err = s.loadApp(p)
	if err != nil || state.Jobs[job.ID].Report == nil || len(state.Jobs[job.ID].Report.Files) != 1 || state.Jobs[job.ID].Report.Files[0] != f || len(state.Jobs[job.ID].Outputs) != 0 {
		t.Fatal("Process death lost committed output cleanup claim", err)
	}
	if _, data, err := s.outbox.Read(p.identity, f.ID); err != nil || string(data) != "committed before process death" {
		t.Fatal("Child did not commit the actual bytes", err)
	}
	if _, err := s.retentionPass(time.UnixMilli(job.Created).Add(appRetention)); err != nil {
		t.Fatal(err)
	}
	if _, err := s.outbox.Info(p.identity, f.ID); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Process death left fresh unjournaled bytes past expiry", err)
	}
}

func TestHardExpiryClosesActualBlockedOutputAndPreventsLatePublication(t *testing.T) {
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
	f, _ := files.Describe(randomToken(), "blocked.bin", []byte("actual"))
	report := appReport{Protocol: "v2", AppID: state.ID, JobID: job.ID, Revision: 1, Status: "completed", Code: "ready", Files: []protocol.File{f}}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	body := &closingJobOutput{entered: make(chan struct{}), closed: make(chan struct{})}
	s.native = &expiringOutputNative{nativeFixture: base, body: body, turn: mustJSON(map[string]any{"id": job.TurnID, "status": "completed", "bridgeTerminalCommitted": true, "items": []any{map[string]any{"type": "agentMessage", "text": string(mustJSON(report))}}})}
	done, result := make(chan struct{}), make(chan error, 1)
	t.Cleanup(func() { body.Close(); s.cancel(); checksync.Join(t, done, 3*time.Second, "hard expiry output worker") })
	go func() { defer close(done); result <- s.workPeer(p, nil) }()
	checksync.Receive(t, body.entered, 3*time.Second, "actual pre-expiry output read")
	if _, err := s.retentionPass(time.UnixMilli(job.Created).Add(appRetention)); err != nil {
		t.Fatal(err)
	}
	if err := checksync.Receive(t, result, 3*time.Second, "expiry cancellation retires output without manual release"); err != nil {
		t.Fatal(err)
	}
	state, err = s.loadApp(p)
	if err != nil || state.Jobs[job.ID] != nil || len(p.work) != 0 || !state.Cleanup[job.ID].PCPending {
		t.Fatal("Late output restored expired owner or lost cleanup", err)
	}
	if _, err := s.outbox.Info(p.identity, f.ID); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Expired stream published actual bytes", err)
	}
}

func TestExpiredNeverAttemptedQueueNeedsNoPCAndSharedBytesRemainOwned(t *testing.T) {
	s, native, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	input, _ := files.Describe(randomToken(), "shared.bin", []byte("shared bytes"))
	if _, err := s.inbox.Put(p.identity, input, strings.NewReader("shared bytes")); err != nil {
		t.Fatal(err)
	}
	old, fresh := ownedOutputJob(randomToken()), ownedOutputJob(randomToken())
	old.Created = time.Now().Add(-appRetention - time.Hour).UnixMilli()
	old.ThreadID, old.ChatState, old.TurnID = "", "", ""
	old.State = "queued"
	for _, job := range []*appJob{old, fresh} {
		job.Input.Files = []protocol.File{input}
		job.Fingerprint = appHash(job.Input)
		state.Jobs[job.ID] = job
	}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	if _, err := s.retentionPass(time.Now()); err != nil {
		t.Fatal(err)
	}
	state, err = s.loadApp(p)
	if err != nil || state.Jobs[old.ID] != nil || state.Jobs[fresh.ID] == nil || len(state.Cleanup) != 0 || native.calls != 0 {
		t.Fatal("Idle queue created a native cleanup requirement or deleted current work", err)
	}
	if _, data, err := s.inbox.Read(p.identity, input.ID); err != nil || string(data) != "shared bytes" {
		t.Fatal("Expiry destroyed another live owner's input", err)
	}
}

func TestHardExpiryJournalsBeforeCommitAndRetriesInterruptedLocalPurge(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	job := ownedOutputJob(randomToken())
	job.Created = time.Now().Add(-appRetention - time.Second).UnixMilli()
	f, _ := files.Describe(randomToken(), "result.bin", []byte("owned result"))
	job.Outputs = []protocol.File{f}
	state.Jobs[job.ID] = job
	if _, err := s.outbox.Put(p.identity, f, strings.NewReader("owned result")); err != nil {
		t.Fatal(err)
	}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	dataDir := s.cfg.DataDir
	s.cfg.DataDir = filepath.Join(t.TempDir(), "absent", "bridge")
	err = s.expireApp(state, time.Now())
	s.cfg.DataDir = dataDir
	if err == nil {
		t.Fatal("Missing cleanup journal commit was reported as expiry success")
	}
	state, err = s.loadApp(p)
	if err != nil || state.Jobs[job.ID] == nil {
		t.Fatal("Failed atomic expiry removed durable original owner", err)
	}
	if _, _, err := s.outbox.Read(p.identity, f.ID); err != nil {
		t.Fatal("Failed expiry commit removed bytes", err)
	}
	if err := s.outbox.Close(); err != nil {
		t.Fatal(err)
	}
	if err := s.expireApp(state, time.Now()); err == nil {
		t.Fatal("Unavailable local file purge was hidden")
	}
	state, err = s.loadApp(p)
	if err != nil || state.Jobs[job.ID] != nil || len(state.Cleanup[job.ID].Files) == 0 {
		t.Fatal("Committed payload deletion lost its unlink recovery obligation", err)
	}
	s.outbox, err = files.Open(filepath.Join(dataDir, "outgoing"))
	if err != nil {
		t.Fatal(err)
	}
	if err := s.expireApp(state, time.Now()); err != nil {
		t.Fatal(err)
	}
	if _, err := s.outbox.Info(p.identity, f.ID); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Recovery did not retire actual file", err)
	}
}

func TestCleanupBacklogBoundsAdmissionAndReleasesCapacityAfterPCReceipt(t *testing.T) {
	s, native, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	state.Cleanup = map[string]appCleanup{}
	for range maxAppOwners {
		id := randomToken()
		state.Cleanup[id] = appCleanup{Created: time.Now().Add(-appRetention).UnixMilli(), Name: "bridge_job_" + id, PCPending: true}
	}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "New approved work", Text: "bounded journal test", Files: []protocol.File{}}
	if w := call("POST", "/v2/jobs", input, p.identity); w.Code != 409 || !strings.Contains(w.Body.String(), "job_limit_reached") {
		t.Fatal("Offline cleanup backlog grew without an admission bound", w.Body.String())
	}
	native.online = true
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err = s.loadApp(p)
	if err != nil || len(state.Cleanup) != maxAppOwners-observationBatch || native.calls != 0 {
		t.Fatal("Cleanup batch was unbounded or replayed work", err, len(state.Cleanup))
	}
	if w := call("POST", "/v2/jobs", input, p.identity); w.Code != 202 {
		t.Fatal("Durable PC receipt did not release admission capacity", w.Body.String())
	}
}

func TestDamagedLocalUnlinkCannotStarvePCDelegationOrHealthyJob(t *testing.T) {
	s, native, call := fixtureAPI(t)
	native.online = true
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	f, _ := files.Describe(randomToken(), "damaged.bin", []byte("preserve unknown members"))
	if _, err := s.outbox.Put(p.identity, f, strings.NewReader("preserve unknown members")); err != nil {
		t.Fatal(err)
	}
	unknown := filepath.Join(s.cfg.DataDir, "outgoing", p.identity, f.ID, "unknown-member")
	if err := os.WriteFile(unknown, []byte("must not erase"), 0600); err != nil {
		t.Fatal(err)
	}
	state.Cleanup = map[string]appCleanup{}
	for i := 1; i <= observationBatch; i++ {
		id := fmt.Sprintf("%032x", i)
		state.Cleanup[id] = appCleanup{Created: time.Now().Add(-appRetention).UnixMilli(), Name: "bridge_job_" + id, Files: []string{f.ID}}
	}
	pcID := strings.Repeat("f", 32)
	state.Cleanup[pcID] = appCleanup{Created: time.Now().Add(-appRetention).UnixMilli(), Name: "bridge_job_" + pcID, PCPending: true}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	// An independent current job must keep its normal native dispatch path despite cleanup errors.
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Fresh work", Text: "independent current job", Files: []protocol.File{}}
	if w := call("POST", "/v2/jobs", input, p.identity); w.Code != 202 {
		t.Fatal("Unrelated committed cleanup fault blocked normal admission", w.Code, w.Body.String())
	}
	if w := call("GET", "/v2/jobs/"+input.RequestID, nil, p.identity); w.Code != 200 {
		t.Fatal("Unrelated committed cleanup fault blocked saved job read", w.Body.String())
	}
	if err := s.workPass(); err == nil {
		t.Fatal("Unsafe local unlink failure was hidden")
	}
	state, err = s.loadApp(p)
	if err != nil || state.Cleanup[pcID].Created != 0 || len(state.Cleanup) != observationBatch || native.calls != 1 {
		t.Fatal("File-only cleanup rows starved PC delegation or current dispatch", err, native.calls, len(state.Cleanup))
	}
	if raw, err := os.ReadFile(unknown); err != nil || string(raw) != "must not erase" {
		t.Fatal("Unconfirmed local cleanup erased unknown bytes", err)
	}
}

func TestExplicitDeletionRetainsCompactUnlinkObligationAcrossReopen(t *testing.T) {
	s, native, call := fixtureAPI(t)
	native.online = true
	s.native = &discardReceiptNative{nativeFixture: native, deleted: true}
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	job := ownedOutputJob(randomToken())
	job.State = "completed"
	job.Input.Text = "discarded private context"
	job.Fingerprint = appHash(job.Input)
	f, _ := files.Describe(randomToken(), "owned.bin", []byte("explicit cleanup bytes"))
	job.Outputs = []protocol.File{f}
	state.Jobs[job.ID] = job
	if _, err := s.outbox.Put(p.identity, f, strings.NewReader("explicit cleanup bytes")); err != nil {
		t.Fatal(err)
	}
	unknown := filepath.Join(s.cfg.DataDir, "outgoing", p.identity, f.ID, "unexpected")
	if err := os.WriteFile(unknown, []byte("preserve unknown owned fixture"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	if w := call("DELETE", "/v2/jobs/"+job.ID, nil, p.identity); w.Code != 503 {
		t.Fatal("Unsafe unlink falsely completed", w.Body.String())
	}
	state, err = s.loadApp(p)
	if err != nil || state.Jobs[job.ID] != nil || state.Cleanup[job.ID].PCPending || len(state.Cleanup[job.ID].Files) != 1 {
		t.Fatal("Explicit cleanup discarded its interrupted unlink journal", err)
	}
	reopened, err := New(s.cfg, native, s.authenticate, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	state, err = reopened.loadApp(reopened.getPeer(p.identity))
	if err != nil || state.Cleanup[job.ID].Created != job.Created {
		t.Fatal("Restart lost exact explicit deletion obligation", err)
	}
	if bytes.Contains(mustJSON(state), []byte(job.Input.Text)) {
		t.Fatal("Compact unlink recovery retained discarded prompt")
	}
	if err := os.Remove(unknown); err != nil {
		t.Fatal(err)
	}
	if _, err := reopened.retentionPass(time.Now()); err != nil {
		t.Fatal(err)
	}
	state, err = reopened.loadApp(reopened.getPeer(p.identity))
	if err != nil || len(state.Cleanup) != 0 || native.calls != 0 {
		t.Fatal("Repaired explicit unlink did not finish independently", err)
	}
	if _, err := reopened.outbox.Info(p.identity, f.ID); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Recent explicit-delete bytes waited for object-age expiry", err)
	}
}

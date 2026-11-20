package pc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"testing/iotest"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
	"tailscale.com/tsnet"
)

func TestUnknownDispatchAndInterruptedOutputKeepExactReferencesAcrossRestart(t *testing.T) {
	t.Setenv("BRIDGE_CHECK_PEER", "stderr-app-disconnect-send")
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	producer := filepath.Join(root, "sdk-check", "bridge-pc-check.exe")
	if err := os.MkdirAll(filepath.Dir(producer), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(producer, []byte("isolated fixture producer"), 0600); err != nil {
		t.Fatal(err)
	}
	cfg := Config{Identity: "nPc", Host: "pc.tailtest.ts.net", CodexExecutable: executable, DataDir: root, IntegrationDir: root, AllowedPeers: []string{"nPi"}, BackendPeer: "nPi"}
	s, err := New(&tsnet.Server{}, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	s.authenticate = func(context.Context, string) (string, error) { return "nPi", nil }
	call := func(input protocol.AdapterRequest) protocol.AdapterReply {
		t.Helper()
		input.Nonce = randomToken()
		raw, _ := json.Marshal(input)
		r := httptest.NewRequest("POST", "/internal/v1/codex", bytes.NewReader(raw))
		r.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		s.handle(w, r)
		var reply protocol.AdapterReply
		if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &reply) != nil {
			t.Fatal(w.Code, w.Body.String())
		}
		return reply
	}
	owner := protocol.AdapterRequest{Operation: "create", AppID: randomToken(), RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Name: "Current accepted dispatch", Model: "bridge-check-model"}
	indexID := creationID("nPi", owner.AppID, owner.RequestID, "inputs")
	t.Setenv("BRIDGE_CHECK_DISPATCH_STORE", filepath.Join(root, "creations"))
	t.Setenv("BRIDGE_CHECK_DISPATCH_ID", indexID)
	if reply := call(owner); reply.State != "ok" {
		t.Fatal("Actual owned creation failed", reply)
	}
	input, _ := files.Describe(randomToken(), "input.bin", []byte("required inputs"))
	if _, err := s.inbox.Put("nPi", input, strings.NewReader("required inputs")); err != nil {
		t.Fatal(err)
	}
	request := protocol.AdapterRequest{Operation: "send", AppID: owner.AppID, JobID: owner.RequestID, ThreadID: "thread-1", Text: "immutable original job", Instructions: "fixture-custom", Model: owner.Model, Effort: "high", Files: []protocol.File{input}}
	reply := call(request)
	if reply.State != "unknown" || reply.Code != "native_outcome_unconfirmed" || reply.Failure == nil || reply.Failure.Process == nil || reply.Failure.Process.ExitCode == nil {
		t.Fatal("Lost native acknowledgement was not kept uncertain", reply)
	}
	if *reply.Failure.Process.ExitCode != 52 {
		t.Fatal("Native peer could not verify the committed dispatch record before possible mutation", *reply.Failure.Process.ExitCode)
	}
	f, raw, err := s.creations.Read("nPi", indexID)
	var record jobFileRecord
	if err != nil || f.Name != jobInputsName || protocol.DecodeStrict(raw, &record) != nil || len(record.Files) != 1 || record.Files[0] != input || record.ThreadID != request.ThreadID || record.EnvelopeHash == "" {
		t.Fatal("Possible native mutation had no durable exact input record", err, record)
	}
	output, _ := files.Describe(randomToken(), "pending-output.bin", []byte("complete output"))
	broken := io.MultiReader(strings.NewReader("partial"), iotest.ErrReader(io.ErrUnexpectedEOF))
	if _, err := s.QueueJobFileReader(context.Background(), "nPi", owner.AppID, owner.RequestID, request.ThreadID, output, broken); err == nil {
		t.Fatal("Interrupted output upload acknowledged missing bytes")
	}
	outputRef := creationID("nPi", owner.AppID, owner.RequestID, "output:"+output.ID)
	if _, err := s.creations.Info("nPi", outputRef); err != nil {
		t.Fatal("Interrupted upload lost its accepted owner reference", err)
	}
	ageJobFile(t, s, "incoming", "nPi", input.ID)
	ageJobFile(t, s, "creations", "nPi", indexID)
	ageJobFile(t, s, "creations", "nPi", outputRef)
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, err := New(&tsnet.Server{}, cfg)
	if err != nil {
		t.Fatal("Restart failed to read current durable dispatch state", err)
	}
	defer reopened.Close()
	if reopened.peers["nPi"].conn != nil {
		t.Fatal("Opening durable dispatch state connected to native history or replayed work")
	}
	if _, data, err := reopened.inbox.Read("nPi", input.ID); err != nil || string(data) != "required inputs" {
		t.Fatal("Restart aged away unknown work's input", err)
	}
	if _, err := reopened.creations.Info("nPi", outputRef); err != nil {
		t.Fatal("Restart lost an interrupted output claim", err)
	}
	if stored, err := reopened.QueueJobFileReader(context.Background(), "nPi", owner.AppID, owner.RequestID, request.ThreadID, output, strings.NewReader("complete output")); err != nil || stored != output {
		t.Fatal("Exact output upload did not resume from current committed owner state", err)
	}
	if _, data, err := reopened.outbox.Read("nPi", output.ID); err != nil || string(data) != "complete output" {
		t.Fatal("Resumed output bytes differ", err)
	}
	changed, _ := files.Describe(output.ID, output.Name, []byte("replacement"))
	if _, err := reopened.QueueJobFileReader(context.Background(), "nPi", owner.AppID, owner.RequestID, request.ThreadID, changed, strings.NewReader("replacement")); !errors.Is(err, files.ErrConflict) {
		t.Fatal("Retry changed an accepted immutable output", err)
	}
	if reopened.peers["nPi"].conn != nil {
		t.Fatal("File recovery queried or replayed native work")
	}
}

func TestIdleCreationCannotQueueOwnedOutputWithoutDispatchCommit(t *testing.T) {
	s, native := jobFilesService(t)
	owner, request := newFileJob(t, s, native, nil)
	output, _ := files.Describe(randomToken(), "unsubmitted.bin", []byte("bytes"))
	if _, err := s.QueueJobFileReader(context.Background(), "nPi", owner.AppID, owner.RequestID, request.ThreadID, output, strings.NewReader("bytes")); !errors.Is(err, ErrJobFileOwnership) {
		t.Fatal("Creation receipt alone authorized a job output", err)
	}
	if _, err := s.outbox.Info("nPi", output.ID); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Rejected idle output committed bytes", err)
	}
	if _, err := s.creations.Info("nPi", creationID("nPi", owner.AppID, owner.RequestID, "output:"+output.ID)); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Rejected idle output invented an owned reference", err)
	}
}

func TestDamagedCurrentDispatchRecordIsNotTreatedAsIdleAbsence(t *testing.T) {
	s, native := jobFilesService(t)
	input, _ := files.Describe(randomToken(), "required.bin", []byte("required"))
	if _, err := s.inbox.Put("nPi", input, strings.NewReader("required")); err != nil {
		t.Fatal(err)
	}
	_, request := newFileJob(t, s, native, []protocol.File{input})
	bindFileJob(t, s, request)
	indexID := creationID("nPi", request.AppID, request.JobID, "inputs")
	if err := os.Remove(filepath.Join(s.cfg.DataDir, "creations", "nPi", indexID, "metadata.json")); err != nil {
		t.Fatal(err)
	}
	ageJobFile(t, s, "incoming", "nPi", input.ID)
	if _, err := s.pruneTransferFiles(time.Now()); err == nil {
		t.Fatal("Incomplete current dispatch metadata was silently treated as no ownership")
	}
	if _, bytes, err := s.inbox.Read("nPi", input.ID); err != nil || string(bytes) != "required" {
		t.Fatal("Damaged dispatch metadata discarded required input", err)
	}
	output, _ := files.Describe(randomToken(), "blocked.bin", []byte("bytes"))
	if _, err := s.QueueJobFileReader(context.Background(), "nPi", request.AppID, request.JobID, request.ThreadID, output, strings.NewReader("bytes")); !errors.Is(err, files.ErrCorrupt) {
		t.Fatal("Corrupt current dispatch was guessed from absence/history", err)
	}
	if s.peers["nPi"].conn != nil {
		t.Fatal("Corrupt dispatch record triggered historical reconstruction")
	}
}

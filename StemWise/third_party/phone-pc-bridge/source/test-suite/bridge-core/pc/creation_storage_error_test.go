package pc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
	"tailscale.com/tsnet"
)

type storageFailureNative struct {
	*creationNativeFixture
	afterStart func() error
}

func (n *storageFailureNative) StartThread(ctx context.Context, project, model, serviceTier string) (json.RawMessage, error) {
	raw, err := n.creationNativeFixture.StartThread(ctx, project, model, serviceTier)
	if err == nil && n.afterStart != nil {
		err = n.afterStart()
	}
	return raw, err
}

func TestCreationResultStoreFailureRetainsCauseAndNeverReplaysReservation(t *testing.T) {
	s, base, input, root := creationTestService(t)
	occupied := filepath.Join(root, "nPi", creationID("nPi", input.AppID, input.RequestID, "result"))
	native := &storageFailureNative{creationNativeFixture: base, afterStart: func() error { return os.WriteFile(occupied, []byte("occupied exact result path"), 0600) }}
	_, err := s.createThread(context.Background(), "nPi", input, native)
	var failure *creationError
	if !errors.As(err, &failure) || failure.code != "creation_store_unavailable" || !failure.unknown || !errors.Is(err, files.ErrCorrupt) {
		t.Fatal("Actual failed result Store.Put lost its cause or uncertainty", err, errors.Unwrap(err))
	}
	if failure.storagePhase != "put_result" || !strings.Contains(adapterFailure(err, input).Message, "storage_phase=put_result storage_class=corrupt") {
		t.Fatal("Storage result failure lost its safe phase/class", failure.storagePhase)
	}
	if _, _, err := s.creations.Read("nPi", creationID("nPi", input.AppID, input.RequestID, "request")); err != nil {
		t.Fatal("Committed reservation disappeared", err)
	}
	for _, op := range []string{"create", "creation", "create"} {
		input.Operation = op
		if _, err := s.createThread(context.Background(), "nPi", input, native); err == nil || base.starts != 1 {
			t.Fatal("Storage failure replayed native creation", err, base.starts)
		}
	}
	if raw, err := os.ReadFile(occupied); err != nil || string(raw) != "occupied exact result path" {
		t.Fatal("Recovery replaced existing result path", err)
	}
}

func TestDeletedReceiptStoreFailureRetainsCauseWithoutNativeDeletion(t *testing.T) {
	s, base, input, root := creationTestService(t)
	if _, err := s.createThread(context.Background(), "nPi", input, base); err != nil {
		t.Fatal(err)
	}
	for id := range base.threads {
		input.ThreadID = id
	}
	if err := os.WriteFile(filepath.Join(root, "nPi", creationID("nPi", input.AppID, input.RequestID, "deleted")), []byte("occupied deleted marker"), 0600); err != nil {
		t.Fatal(err)
	}
	native := &deletingNative{creationNativeFixture: base}
	err := s.deleteCreation(context.Background(), "nPi", input, native)
	var failure *creationError
	if !errors.As(err, &failure) || failure.code != "creation_store_unavailable" || !failure.unknown || !errors.Is(err, files.ErrCorrupt) || native.calls != 0 {
		t.Fatal("Deleted receipt storage failure lost cause, certainty or ownership guard", err, errors.Unwrap(err), native.calls)
	}
	if failure.storagePhase != "read_deleted_marker" {
		t.Fatal("Deleted receipt storage phase lost", failure.storagePhase)
	}
}

func TestStorageAdapterProjectionRetainsRealOperationAndErrnoWithoutPrivatePath(t *testing.T) {
	privatePath := filepath.Join(t.TempDir(), "private-storage-owner-directory")
	if err := os.Mkdir(privatePath, 0700); err != nil {
		t.Fatal(err)
	}
	handle, cause := os.OpenFile(privatePath, os.O_WRONLY, 0600)
	if handle != nil {
		handle.Close()
	}
	var path *os.PathError
	var errno syscall.Errno
	if cause == nil || !errors.As(cause, &path) || !errors.As(cause, &errno) {
		t.Fatal("Fixture did not produce a real typed filesystem failure", cause)
	}
	err := &creationError{code: "creation_store_unavailable", unknown: true, cause: cause, storagePhase: "put_request"}
	failure := adapterFailure(err, protocol.AdapterRequest{})
	for _, fact := range []string{"storage_phase=put_request", "storage_op=" + path.Op, fmt.Sprintf("storage_errno=%d", uint64(errno))} {
		if !strings.Contains(failure.Message, fact) {
			t.Fatal("Actual adapter projection lost safe typed filesystem evidence", failure.Message, fact)
		}
	}
	if strings.Contains(failure.Message, privatePath) || strings.Contains(failure.Message, "private-storage-owner") || strings.Contains(failure.Message, cause.Error()) || len(failure.Message) > 1024 || !errors.Is(err, cause) {
		t.Fatal("Storage adapter projection leaked path, grew unbounded or discarded local cause", failure.Message)
	}
	malformed := &creationError{code: "creation_store_unavailable", unknown: true, storagePhase: privatePath, cause: &os.PathError{Op: privatePath, Path: privatePath, Err: syscall.EPERM}}
	if value := adapterFailure(malformed, protocol.AdapterRequest{}).Message; strings.Contains(value, "private-storage-owner") || !strings.Contains(value, "storage_phase=unconfirmed") || !strings.Contains(value, "storage_op=unclassified") {
		t.Fatal("Arbitrary phase/op escaped fixed storage fields", value)
	}
}

func TestCreationUnconfiguredStoreHasExplicitPhaseWithoutInventedCause(t *testing.T) {
	s, n, input, _ := creationTestService(t)
	s.cfg.IntegrationDir = ""
	_, err := s.createThread(context.Background(), "nPi", input, n)
	var failure *creationError
	if !errors.As(err, &failure) || !failure.unknown || failure.storagePhase != "unconfigured" || errors.Unwrap(err) != nil || n.starts != 0 || !strings.Contains(adapterFailure(err, input).Message, "storage_phase=unconfigured storage_class=unconfigured") {
		t.Fatal("Unconfigured storage invented a cause or lost explicit failure", err)
	}
}

func TestHTTPAdapterStorageFailureKeepsUncertaintyAndOnlySafeStorageFacts(t *testing.T) {
	t.Setenv("BRIDGE_CHECK_PEER", "app")
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	s, err := New(&tsnet.Server{}, Config{Identity: "nPc", Host: "pc.tailtest.ts.net", CodexExecutable: executable, DataDir: t.TempDir(), IntegrationDir: t.TempDir(), AllowedPeers: []string{"nPi"}, BackendPeer: "nPi"})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	s.authenticate = func(context.Context, string) (string, error) { return "nPi", nil }
	if err := s.creations.Close(); err != nil {
		t.Fatal(err)
	}
	s.creations, err = files.OpenWithBudget(filepath.Join(s.cfg.DataDir, "creations"), files.NewBudget(^uint64(0)))
	if err != nil {
		t.Fatal(err)
	}
	call := func(input protocol.AdapterRequest) protocol.AdapterReply {
		t.Helper()
		input.Nonce = randomToken()
		raw, _ := json.Marshal(input)
		r := httptest.NewRequest("POST", "/internal/v1/codex", bytes.NewReader(raw))
		r.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		s.handle(w, r)
		var response protocol.AdapterReply
		if w.Code != 200 || protocol.DecodeStrict(w.Body.Bytes(), &response) != nil || response.Nonce != input.Nonce {
			t.Fatal("Invalid adapter receipt", w.Code, w.Body.String())
		}
		return response
	}
	input := protocol.AdapterRequest{Operation: "create", AppID: randomToken(), RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Name: "private-storage-job-title", Model: "fixture"}
	result := call(input)
	if result.State != "unknown" || result.Code != "creation_store_unavailable" || result.Result != nil || result.Failure == nil || !strings.Contains(result.Failure.Message, "storage_phase=put_request storage_class=storage_capacity_unavailable") {
		t.Fatal("Actual adapter lost failed-store uncertainty or safe evidence", result)
	}
	for _, private := range []string{s.cfg.DataDir, s.cfg.IntegrationDir, input.Name, "available_bytes="} {
		if strings.Contains(result.Failure.Message, private) {
			t.Fatal("Actual adapter exposed raw storage error or private path", result.Failure.Message)
		}
	}
	if read := call(protocol.AdapterRequest{Operation: "select", ThreadID: "thread-1"}); read.State != "ok" || bytes.Contains(read.Result, []byte(`"cwd"`)) {
		t.Fatal("Failed reservation started native creation", read)
	}
}

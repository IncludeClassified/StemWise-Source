package mobile

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"phonepcbridge/files"

	"tailscale.com/client/local"
)

func TestLocalRetentionDiagnosticsExposeLastPassWithoutNetworking(t *testing.T) {
	c, f := mobileFileFixture(t, t.TempDir())
	raw, err := f.StorageDiagnostics("status")
	if err != nil {
		t.Fatal(err)
	}
	var view map[string]any
	if json.Unmarshal([]byte(raw), &view) != nil {
		t.Fatal("Invalid local diagnostics JSON")
	}
	operations, ok := view["backgroundOperations"].(map[string]any)
	if !ok {
		t.Fatal("File diagnostics omitted retained background operation status")
	}
	retention, ok := operations["file_retention"].(map[string]any)
	if !ok || retention["lastSuccessAt"].(float64) <= 0 || retention["failureActive"] != false || retention["running"] != float64(0) {
		t.Fatal("Startup cleanup success was not recorded", operations)
	}
	if c.started {
		t.Fatal("Local diagnostics started private networking")
	}
}

func retentionDiagnosticView(t *testing.T, f *FileClient) map[string]any {
	t.Helper()
	raw, err := f.StorageDiagnostics("diagnostics")
	if err != nil {
		t.Fatal(err)
	}
	var view map[string]any
	if json.Unmarshal([]byte(raw), &view) != nil {
		t.Fatal("Invalid diagnostics JSON")
	}
	return view
}

func TestBackgroundFileRetentionFailureRemainsObservableUntilSuccessfulPass(t *testing.T) {
	state := t.TempDir()
	c, f := mobileFileFixture(t, state)
	close(c.fileRetentionStop)
	select {
	case <-c.fileRetentionDone:
	case <-time.After(2 * time.Second):
		t.Fatal("Original retention loop did not retire")
	}
	if err := f.inbox.Close(); err != nil {
		t.Fatal(err)
	}
	c.fileRetentionStop, c.fileRetentionDone = make(chan struct{}), make(chan struct{})
	go c.retainFiles(time.Millisecond)
	deadline := time.Now().Add(2 * time.Second)
	var failureAt float64
	for {
		view := retentionDiagnosticView(t, f)
		operation := view["backgroundOperations"].(map[string]any)["file_retention"].(map[string]any)
		if operation["failureActive"] == true {
			failureAt = operation["lastFailureAt"].(float64)
			if failureAt <= 0 || operation["lastFailureCause"] == "" || operation["retryDelayMs"] != float64(time.Minute.Milliseconds()) || operation["retryState"] != "pending" {
				t.Fatal("Background pass failure/retry policy was not recorded", operation)
			}
			incoming := view["incoming"].(map[string]any)
			if incoming["code"] != "storage_diagnostics_unavailable" || incoming["diagnosticCount"] != nil {
				t.Fatal("Failed storage scan acquired a fabricated successful empty page", incoming)
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("Background loop discarded actual retention failure")
		}
		time.Sleep(5 * time.Millisecond)
	}
	// Metadata observation must not prune this newly expired healthy object.
	file := managedMobileFile(t, f.outbox)
	path := filepath.Join(state, "bridge-outgoing", "nPc", file.ID)
	old := time.Now().Add(-3*24*time.Hour - time.Second)
	if err := os.Chtimes(path, old, old); err != nil {
		t.Fatal(err)
	}
	incoming, err := files.OpenWithRetention(filepath.Join(state, "bridge-incoming"), 3*24*time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	c.fileIncoming, f.inbox = incoming, incoming
	for attempt := 0; attempt < 2; attempt++ {
		view := retentionDiagnosticView(t, f)
		operation := view["backgroundOperations"].(map[string]any)["file_retention"].(map[string]any)
		if operation["failureActive"] != true || operation["lastFailureAt"] != failureAt {
			t.Fatal("Read-only diagnostics cleared a prior pass failure", operation)
		}
		if _, err := os.Stat(path); err != nil {
			t.Fatal("Diagnostic observation pruned bytes", err)
		}
	}
	if c.started {
		t.Fatal("Local failure observation started networking")
	}
	if _, err := c.pruneFileStores(c.fileIncoming, c.fileOutgoing, time.Now()); err != nil {
		t.Fatal("Actual repaired retention pass failed", err)
	}
	view := retentionDiagnosticView(t, f)
	operation := view["backgroundOperations"].(map[string]any)["file_retention"].(map[string]any)
	if operation["failureActive"] != false || operation["lastFailureAt"] != failureAt || operation["lastFailureCause"] == "" || operation["lastSuccessAt"].(float64) < failureAt || operation["retryState"] != "waiting" {
		t.Fatal("Actual successful pass did not resolve active failure while preserving investigation context", operation)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("Successful pass did not perform actual expiry", err)
	}
	if c.started || len(c.active) != 0 {
		t.Fatal("Retention/status performed network or left an operation active")
	}
}

type statusRoundTrip func(*http.Request) (*http.Response, error)

func (f statusRoundTrip) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestClientStatusIncludesLocalRetentionFailureAlongsideExistingTransportStatus(t *testing.T) {
	c, f := mobileFileFixture(t, t.TempDir())
	if err := f.inbox.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := c.pruneFileStores(c.fileIncoming, c.fileOutgoing, time.Now()); err == nil {
		t.Fatal("Closed store did not fail real cleanup")
	}
	var requests atomic.Int32
	c.mu.Lock()
	c.started = true
	c.local = &local.Client{OmitAuth: true, Transport: statusRoundTrip(func(r *http.Request) (*http.Response, error) {
		requests.Add(1)
		if r.Method != "GET" || r.URL.Path != "/localapi/v0/status" {
			t.Error("Status performed an unexpected native operation", r.Method, r.URL.Path)
		}
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(`{"BackendState":"Running","Self":{"ID":"nApp","Expired":false}}`))}, nil
	})}
	c.mu.Unlock()
	t.Cleanup(func() { c.mu.Lock(); c.started = false; c.local = nil; c.mu.Unlock() })
	raw, err := c.Status()
	if err != nil {
		t.Fatal(err)
	}
	var view map[string]any
	if json.Unmarshal([]byte(raw), &view) != nil || view["backendState"] != "Running" || view["identity"] != "nApp" {
		t.Fatal("Existing status fields changed", raw)
	}
	operation := view["backgroundOperations"].(map[string]any)["file_retention"].(map[string]any)
	if operation["failureActive"] != true || operation["lastFailureAt"].(float64) <= 0 || requests.Load() != 1 {
		t.Fatal("Status lost local failure or performed extra native operations", raw)
	}
}

func TestLocalStorageDiagnosticsRejectInvalidAndCancelledClientWithoutMutation(t *testing.T) {
	if _, err := new(FileClient).StorageDiagnostics("invalid"); err == nil {
		t.Fatal("Uninitialized diagnostics accepted")
	}
	c, f := mobileFileFixture(t, t.TempDir())
	before := c.fileRetentionStatus.Snapshot()
	operation, err := c.NewOperation()
	if err != nil {
		t.Fatal(err)
	}
	operation.Cancel()
	if _, err := f.StorageDiagnostics(operation.Token()); err == nil {
		t.Fatal("Cancelled diagnostic operation proceeded")
	}
	if c.started || len(c.active) != 0 || c.fileRetentionStatus.Snapshot() != before {
		t.Fatal("Cancelled diagnostics changed cleanup or started transport")
	}
	// A broken opposite store must not block a healthy requested page.
	if err := f.inbox.Close(); err != nil {
		t.Fatal(err)
	}
	f.exchange = func(context.Context, string, string, string, []byte) (string, error) {
		t.Fatal("Local failure reached network")
		return "", nil
	}
	if _, err := f.Pending("healthy-request", "", 10); err != nil {
		t.Fatal("Unrelated closed store blocked healthy pending page", err)
	}
	if _, err := c.pruneFileStores(c.fileIncoming, c.fileOutgoing, time.Now()); err == nil {
		t.Fatal("Actual sweep failed to inspect the closed store")
	}
	view := retentionDiagnosticView(t, f)
	operationStatus := view["backgroundOperations"].(map[string]any)["file_retention"].(map[string]any)
	if operationStatus["failureActive"] != true || operationStatus["lastFailureCause"] == "" || c.started {
		t.Fatal("Actual sweep failure disappeared from offline observation", view)
	}
}

package pc

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"phonepcbridge/codex"
	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

type creationNativeFixture struct {
	starts                            int
	threads                           map[string]string
	failStart, failRead, wrongProject bool
	entered, release                  chan struct{}
	serviceTier                       string
}

type creationFailureNative struct {
	*creationNativeFixture
	startErr, readErr error
}

func (n *creationFailureNative) StartThread(ctx context.Context, project, model, serviceTier string) (json.RawMessage, error) {
	raw, err := n.creationNativeFixture.StartThread(ctx, project, model, serviceTier)
	if err == nil && n.startErr != nil {
		return nil, n.startErr
	}
	return raw, err
}

func (n *creationFailureNative) Read(ctx context.Context, id string) (json.RawMessage, error) {
	raw, err := n.creationNativeFixture.Read(ctx, id)
	if err == nil && n.readErr != nil {
		return nil, n.readErr
	}
	return raw, err
}

func TestCreationProcessFailureKeepsDiagnosticAndNeverReplaysReservation(t *testing.T) {
	for _, stage := range []string{"start", "read"} {
		t.Run(stage, func(t *testing.T) {
			s, base, input, _ := creationTestService(t)
			cause := errors.New("owned native process ended")
			process := &codex.ProcessError{Cause: &codex.OutcomeUnknown{Cause: cause}}
			n := &creationFailureNative{creationNativeFixture: base}
			code := "creation_receipt_unconfirmed"
			if stage == "start" {
				n.startErr = process
			} else {
				n.readErr, code = process, "creation_native_unconfirmed"
			}
			_, err := s.createThread(context.Background(), "nPi", input, n)
			var creation *creationError
			var projected *codex.ProcessError
			if !errors.As(err, &creation) || creation.code != code || !creation.unknown || !errors.As(err, &projected) || projected != process || !errors.Is(err, cause) {
				t.Fatal("Creation lost native cause or changed uncertain classification", err)
			}
			failure := adapterFailure(err, input)
			if failure.Message != code || failure.Process == nil || failure.Process.WaitState != "unconfirmed" || failure.Process.ExitCode != nil {
				t.Fatal("Typed process facts did not reach the actual adapter projection", failure)
			}
			if _, _, err := s.creations.Read("nPi", creationID("nPi", input.AppID, input.RequestID, "request")); err != nil {
				t.Fatal("Uncertain creation lost its original reservation", err)
			}
			for _, operation := range []string{"create", "creation", "create"} {
				input.Operation = operation
				_, err := s.createThread(context.Background(), "nPi", input, n)
				if err == nil || base.starts != 1 || len(base.threads) != 1 {
					t.Fatal("Process failure replayed the reserved native creation", err)
				}
			}
			if stage == "read" {
				n.readErr = nil
				input.Operation = "creation"
				if _, err := s.createThread(context.Background(), "nPi", input, n); err != nil || base.starts != 1 {
					t.Fatal("Original creation receipt did not recover", err)
				}
			}
		})
	}
}

func (n *creationNativeFixture) StartThread(ctx context.Context, project, model, serviceTier string) (json.RawMessage, error) {
	n.starts++
	n.serviceTier = serviceTier
	id := "native-" + randomToken()
	n.threads[id] = project
	if n.entered != nil {
		close(n.entered)
		<-n.release
	}
	if n.failStart {
		return nil, errors.New("Native acknowledgment lost after creation")
	}
	return json.Marshal(map[string]any{"thread": map[string]string{"id": id, "cwd": project}})
}
func (n *creationNativeFixture) Read(ctx context.Context, id string) (json.RawMessage, error) {
	project, exists := n.threads[id]
	if !exists || n.failRead {
		return nil, errors.New("Native thread unavailable")
	}
	if n.wrongProject {
		project = filepath.Dir(project)
	}
	return json.Marshal(map[string]any{"thread": map[string]string{"id": id, "cwd": project}})
}
func creationTestService(t *testing.T) (*Service, *creationNativeFixture, protocol.AdapterRequest, string) {
	t.Helper()
	root := t.TempDir()
	storePath := filepath.Join(root, "creations")
	store, err := files.Open(storePath)
	if err != nil {
		t.Fatal(err)
	}
	s := &Service{cfg: Config{IntegrationDir: root}, creations: store, ctx: context.Background()}
	t.Cleanup(func() { s.creations.Close() })
	n := &creationNativeFixture{threads: map[string]string{}}
	input := protocol.AdapterRequest{Operation: "create", AppID: randomToken(), RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Name: "owned-" + randomToken(), Model: "fixture-model"}
	return s, n, input, storePath
}
func TestCreationReceiptRecoversExactNativeIDAcrossAdapterReopen(t *testing.T) {
	s, n, input, root := creationTestService(t)
	if _, err := s.createThread(context.Background(), "nPi", input, n); err != nil {
		t.Fatal(err)
	}
	var expected string
	for id := range n.threads {
		expected = id
	}
	s.creations.Close()
	var err error
	s.creations, err = files.Open(root)
	if err != nil {
		t.Fatal(err)
	}
	for _, op := range []string{"creation", "create", "creation"} {
		input.Operation = op
		input.Nonce = randomToken()
		raw, err := s.createThread(context.Background(), "nPi", input, n)
		if err != nil {
			t.Fatal(err)
		}
		var result struct {
			Thread struct {
				ID string `json:"id"`
			} `json:"thread"`
		}
		if json.Unmarshal(raw, &result) != nil || result.Thread.ID != expected || n.starts != 1 {
			t.Fatal("Lost receipt acknowledgment created or selected another thread")
		}
	}
	for _, name := range []string{"name", "model", "timestamp"} {
		changed := input
		switch name {
		case "name":
			changed.Name = "changed"
		case "model":
			changed.Model = "changed"
		case "timestamp":
			changed.CreatedAt--
		}
		if _, err := s.createThread(context.Background(), "nPi", changed, n); err == nil || n.starts != 1 {
			t.Fatal("Immutable request changed", name)
		}
	}
	input.Operation = "creation"
	input.AppID = randomToken()
	if _, err := s.createThread(context.Background(), "nPi", input, n); err == nil || n.starts != 1 {
		t.Fatal("Another app read creation receipt")
	}
	page, err := s.creations.List("nPi", "", 100)
	if err != nil || len(page.Files) != 2 {
		t.Fatal("Observation wrote a reservation", err)
	}
}
func TestCreationUnknownNeverReplaysAndObservationDoesNotReserve(t *testing.T) {
	s, n, input, _ := creationTestService(t)
	input.Operation = "creation"
	_, absentErr := s.createThread(context.Background(), "nPi", input, n)
	var absent *creationError
	if !errors.As(absentErr, &absent) || absent.code != "creation_request_absent" || absent.unknown || n.starts != 0 {
		t.Fatal("Lookup performed creation")
	}
	page, err := s.creations.List("nPi", "", 100)
	if err != nil || len(page.Files) != 0 {
		t.Fatal("Lookup created execution records")
	}
	input.Operation = "create"
	n.failStart = true
	if _, err := s.createThread(context.Background(), "nPi", input, n); err == nil || n.starts != 1 {
		t.Fatal("Lost native acknowledgment was confirmed")
	}
	n.failStart = false
	for _, op := range []string{"create", "creation", "create"} {
		input.Operation = op
		if _, err := s.createThread(context.Background(), "nPi", input, n); err == nil || n.starts != 1 {
			t.Fatal("Unknown native creation replayed")
		}
	}
}

func TestConfirmedAbsentCreationCanStartOnceAndObserveSameReceipt(t *testing.T) {
	s, n, input, _ := creationTestService(t)
	input.Operation = "creation"
	_, err := s.createThread(context.Background(), "nPi", input, n)
	var absent *creationError
	if !errors.As(err, &absent) || absent.code != "creation_request_absent" || absent.unknown || n.starts != 0 {
		t.Fatal("Missing reservation was not conclusively absent", err)
	}
	input.Operation = "create"
	want, err := s.createThread(context.Background(), "nPi", input, n)
	if err != nil {
		t.Fatal(err)
	}
	for _, operation := range []string{"creation", "create", "creation"} {
		input.Operation = operation
		got, err := s.createThread(context.Background(), "nPi", input, n)
		if err != nil || string(got) != string(want) || n.starts != 1 {
			t.Fatal("Same immutable creation was duplicated or changed", err)
		}
	}
}
func TestCreationReceiptCorruptionMissingReservationAndNativeMismatchFailClosed(t *testing.T) {
	for _, name := range []string{"bytes", "reservation", "native_missing", "wrong_project"} {
		t.Run(name, func(t *testing.T) {
			s, n, input, root := creationTestService(t)
			if _, err := s.createThread(context.Background(), "nPi", input, n); err != nil {
				t.Fatal(err)
			}
			switch name {
			case "bytes":
				path := filepath.Join(root, "nPi", creationID("nPi", input.AppID, input.RequestID, "result"), "content")
				if err := os.WriteFile(path, []byte("forged result"), 0600); err != nil {
					t.Fatal(err)
				}
			case "reservation":
				if err := s.creations.Remove("nPi", creationID("nPi", input.AppID, input.RequestID, "request")); err != nil {
					t.Fatal(err)
				}
			case "native_missing":
				n.failRead = true
			case "wrong_project":
				n.wrongProject = true
			}
			for _, op := range []string{"creation", "create"} {
				input.Operation = op
				if _, err := s.createThread(context.Background(), "nPi", input, n); err == nil || n.starts != 1 {
					t.Fatal("Unverified receipt became success or replayed", name)
				}
			}
		})
	}
}
func TestConcurrentCreationAttemptsProduceOnlyOneNativeChat(t *testing.T) {
	s, n, input, _ := creationTestService(t)
	n.entered = make(chan struct{})
	n.release = make(chan struct{})
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	results := make(chan error, 2)
	first := make(chan struct{})
	var release sync.Once
	t.Cleanup(func() {
		cancel()
		release.Do(func() { close(n.release) })
		checksync.Join(t, first, time.Second, "first concurrent creation")
	})
	go func() { defer close(first); _, err := s.createThread(ctx, "nPi", input, n); results <- err }()
	checksync.Receive(t, n.entered, time.Second, "first native creation")
	second := make(chan struct{})
	t.Cleanup(func() {
		cancel()
		release.Do(func() { close(n.release) })
		checksync.Join(t, second, time.Second, "second concurrent creation")
	})
	go func() {
		defer close(second)
		_, err := s.createThread(ctx, "nPi", input, n)
		results <- err
	}()
	release.Do(func() { close(n.release) })
	for i := 0; i < 2; i++ {
		if err := checksync.Receive(t, results, time.Second, "concurrent creation result"); err != nil {
			t.Fatal(err)
		}
	}
	if n.starts != 1 || len(n.threads) != 1 {
		t.Fatal("Concurrent attempts created duplicate native chats")
	}
	input.CreatedAt = time.Now().Add(-3*24*time.Hour - time.Second).UnixMilli()
	if _, err := s.createThread(context.Background(), "nPi", input, n); err == nil || n.starts != 1 {
		t.Fatal("Changed immutable timestamp replayed")
	}
}

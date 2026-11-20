package pc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"phonepcbridge/codex"
	"phonepcbridge/files"
	"phonepcbridge/protocol"
	"tailscale.com/tsnet"
)

type cancelledStartNative struct {
	*codex.Connection
	cancelFirst bool
}

type afterStartNative struct {
	creationNative
	after func()
}

func (n *afterStartNative) StartThread(ctx context.Context, project, model, tier string) (json.RawMessage, error) {
	raw, err := n.creationNative.StartThread(ctx, project, model, tier)
	n.after()
	return raw, err
}

func (n *cancelledStartNative) StartThread(ctx context.Context, project, model, tier string) (json.RawMessage, error) {
	if n.cancelFirst {
		n.cancelFirst = false
		cancelled, cancel := context.WithCancel(ctx)
		cancel()
		return n.Connection.StartThread(cancelled, project, model, tier)
	}
	return n.Connection.StartThread(ctx, project, model, tier)
}

func creationPeer(t *testing.T, mode string) (*codex.Connection, context.Context) {
	t.Helper()
	t.Setenv("BRIDGE_CHECK_PEER", mode)
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(cancel)
	c, err := codex.Connect(ctx, executable, "")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	return c, ctx
}

func checkCreationPeerCounts(t *testing.T, c *codex.Connection, ctx context.Context, attempts, created int) {
	t.Helper()
	raw, err := c.Models(ctx, "")
	var counts struct {
		Attempts int `json:"fixtureCreationAttempts"`
		Created  int `json:"fixtureCreatedThreads"`
	}
	if err != nil || json.Unmarshal(raw, &counts) != nil || counts.Attempts != attempts || counts.Created != created {
		t.Fatal("Unexpected native request/allocation count", string(raw), err, attempts, created)
	}
}

func TestConclusiveNativeCreationFailureReleasesOnlyOriginalReservationAndRecoversAfterReopen(t *testing.T) {
	for _, test := range []struct {
		name, mode, code string
		cancel           bool
		attempts         int
	}{{"not_sent", "app-create-counts", "creation_not_sent", true, 0}, {"rejected", "app-reject-create-once", "creation_rejected", false, 1}} {
		t.Run(test.name, func(t *testing.T) {
			s, _, input, path := creationTestService(t)
			c, ctx := creationPeer(t, test.mode)
			native := &cancelledStartNative{Connection: c, cancelFirst: test.cancel}
			_, err := s.createThread(ctx, "nPi", input, native)
			var failure *creationError
			if !errors.As(err, &failure) || failure.unknown || failure.code != test.code {
				t.Fatal("Proven no-start native failure was retained as uncertain", err)
			}
			if test.cancel && !errors.Is(err, context.Canceled) {
				t.Fatal("No-send context cause lost", err)
			}
			if !test.cancel {
				var rpc *codex.RPCError
				if !errors.As(err, &rpc) || rpc.Code != -32015 {
					t.Fatal("Explicit native rejection code lost", err)
				}
			}
			checkCreationPeerCounts(t, c, ctx, test.attempts, 0)
			for _, kind := range []string{"request", "result"} {
				if _, _, err := s.creations.Read("nPi", creationID("nPi", input.AppID, input.RequestID, kind)); !errors.Is(err, files.ErrAbsent) {
					t.Fatal("Conclusive failure retained admission reservation", kind, err)
				}
			}
			s.creations.Close()
			s.creations, err = files.Open(path)
			if err != nil {
				t.Fatal(err)
			}
			input.Operation = "creation"
			_, err = s.createThread(ctx, "nPi", input, native)
			if !errors.As(err, &failure) || failure.unknown || failure.code != "creation_request_absent" {
				t.Fatal("Reopened observation did not conclusively prove no reservation", err)
			}
			input.Operation = "create"
			first, err := s.createThread(ctx, "nPi", input, native)
			if err != nil {
				t.Fatal("Proven absent same-ID native creation did not recover", err)
			}
			for _, operation := range []string{"creation", "create"} {
				input.Operation = operation
				got, err := s.createThread(ctx, "nPi", input, native)
				if err != nil || string(got) != string(first) {
					t.Fatal("Recovered accepted identity changed", err)
				}
			}
			checkCreationPeerCounts(t, c, ctx, test.attempts+1, 1)
		})
	}
}

func TestLostNativeCreationResponseRetainsReservationAndNeverReplaysAfterReopen(t *testing.T) {
	s, _, input, path := creationTestService(t)
	count := filepath.Join(t.TempDir(), "native-created-count")
	t.Setenv("BRIDGE_CHECK_CREATION_COUNT", count)
	c, ctx := creationPeer(t, "app-disconnect-create")
	_, err := s.createThread(ctx, "nPi", input, c)
	var failure *creationError
	if !errors.As(err, &failure) || !failure.unknown || failure.code != "creation_receipt_unconfirmed" {
		t.Fatal("Lost response was not uncertain", err)
	}
	if data, err := os.ReadFile(count); err != nil || string(data) != "1" {
		t.Fatal("Fixture did not actually create before disconnect", string(data), err)
	}
	s.creations.Close()
	s.creations, err = files.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, operation := range []string{"create", "creation", "create"} {
		input.Operation = operation
		_, err = s.createThread(ctx, "nPi", input, c)
		if !errors.As(err, &failure) || !failure.unknown {
			t.Fatal("Possible native creation became replayable", err)
		}
	}
	if data, err := os.ReadFile(count); err != nil || string(data) != "1" {
		t.Fatal("Lost native creation replayed", string(data), err)
	}
}

func TestOuterUnknownWithRPCRejectionCauseCannotReleaseCreationReservation(t *testing.T) {
	s, base, input, path := creationTestService(t)
	native := &creationFailureNative{creationNativeFixture: base, startErr: &codex.OutcomeUnknown{Cause: &codex.RPCError{Code: -32015, Message: "Rejected later verification"}}}
	_, err := s.createThread(context.Background(), "nPi", input, native)
	var failure *creationError
	if !errors.As(err, &failure) || !failure.unknown || failure.code != "creation_receipt_unconfirmed" || base.starts != 1 {
		t.Fatal("Nested RPC cause erased possible native allocation", err)
	}
	if projected := adapterFailure(err, input); projected.RPCCode == nil || *projected.RPCCode != -32015 {
		t.Fatal("Native cause diagnostic lost while preserving uncertainty", projected)
	}
	s.creations.Close()
	s.creations, err = files.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, operation := range []string{"creation", "create"} {
		input.Operation = operation
		if _, err := s.createThread(context.Background(), "nPi", input, native); err == nil || base.starts != 1 {
			t.Fatal("Outer unknown reservation was replayed", err, base.starts)
		}
	}
}

func TestDefinitiveNativeRejectionCannotReleaseChangedReservationOrCommittedResult(t *testing.T) {
	for _, kind := range []string{"request", "result"} {
		t.Run(kind, func(t *testing.T) {
			s, _, input, _ := creationTestService(t)
			c, ctx := creationPeer(t, "app-reject-create-once")
			native := &afterStartNative{creationNative: c, after: func() {
				id := creationID("nPi", input.AppID, input.RequestID, kind)
				var raw []byte
				name := "creation-result.json"
				if kind == "request" {
					name = "creation-request.json"
					_, raw, _ = s.creations.Read("nPi", id)
					var saved creationInput
					if json.Unmarshal(raw, &saved) != nil {
						t.Fatal("Reservation unavailable")
					}
					saved.Name = "Changed during native request"
					raw, _ = json.Marshal(saved)
					if err := s.creations.Remove("nPi", id); err != nil {
						t.Fatal(err)
					}
				} else {
					raw, _ = json.Marshal(creationReceipt{RequestHash: "unrelated-hash", ThreadID: "unrelated-thread"})
				}
				f, _ := files.Describe(id, name, raw)
				if _, err := s.creations.Put("nPi", f, bytes.NewReader(raw)); err != nil {
					t.Fatal(err)
				}
			}}
			_, err := s.createThread(ctx, "nPi", input, native)
			var failure *creationError
			if !errors.As(err, &failure) || !failure.unknown || failure.code != "creation_receipt_unconfirmed" {
				t.Fatal("Native rejection erased changed ownership", err)
			}
			if _, _, err := s.creations.Read("nPi", creationID("nPi", input.AppID, input.RequestID, kind)); err != nil {
				t.Fatal("Changed reservation/result was discarded", err)
			}
			checkCreationPeerCounts(t, c, ctx, 1, 0)
		})
	}
}

func TestHTTPAdapterPreservesConclusiveCreationRejectionAndUnknownReopen(t *testing.T) {
	for _, mode := range []string{"app-reject-create-once", "app-disconnect-create"} {
		t.Run(mode, func(t *testing.T) {
			t.Setenv("BRIDGE_CHECK_PEER", mode)
			count := filepath.Join(t.TempDir(), "native-count")
			t.Setenv("BRIDGE_CHECK_CREATION_COUNT", count)
			executable, err := os.Executable()
			if err != nil {
				t.Fatal(err)
			}
			s, err := New(&tsnet.Server{}, Config{Identity: "nPc", Host: "pc.ts.net", CodexExecutable: executable, AllowedPeers: []string{"nPi"}, BackendPeer: "nPi", DataDir: t.TempDir(), IntegrationDir: t.TempDir()})
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
				if w.Code != 200 || protocol.DecodeStrict(w.Body.Bytes(), &reply) != nil || reply.Nonce != input.Nonce || reply.PCIdentity != "nPc" {
					t.Fatal("Adapter response identity/correlation invalid", w.Code, w.Body.String())
				}
				return reply
			}
			input := protocol.AdapterRequest{Operation: "create", AppID: randomToken(), RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Name: "Exact owned RPC fixture", Model: "fixture"}
			first := call(input)
			if mode == "app-reject-create-once" {
				if first.State != "rejected" || first.Code != "creation_rejected" || first.Result != nil || first.Failure == nil || first.Failure.RPCCode == nil || *first.Failure.RPCCode != -32015 {
					t.Fatal("Explicit rejection was not safely projected through actual adapter", first)
				}
				input.Operation = "creation"
				absent := call(input)
				if absent.State != "rejected" || absent.Code != "creation_request_absent" {
					t.Fatal("Safe rejection did not reopen exact admission", absent)
				}
				input.Operation = "create"
				if got := call(input); got.State != "ok" {
					t.Fatal("Actual adapter could not retry proven absent original", got)
				}
			} else {
				if first.State != "unknown" || first.Code != "creation_receipt_unconfirmed" || first.Result != nil {
					t.Fatal("Lost create response became replayable on wire", first)
				}
				for _, operation := range []string{"creation", "create"} {
					input.Operation = operation
					if got := call(input); got.State != "unknown" || got.Code != "creation_receipt_unconfirmed" {
						t.Fatal("Reconnected adapter forgot original uncertainty", got)
					}
				}
				if raw, err := os.ReadFile(count); err != nil || string(raw) != "1" {
					t.Fatal("Actual native process replayed allocation after reconnect", string(raw), err)
				}
			}
		})
	}
}

func TestKnownCreationReceiptPersistsWhenConnectionClosesBeforeVerificationAndRecoversAfterReopen(t *testing.T) {
	s, _, input, path := creationTestService(t)
	c, ctx := creationPeer(t, "app-create-counts")
	native := &afterStartNative{creationNative: c, after: func() {
		if err := c.Close(); err != nil {
			t.Fatal(err)
		}
	}}
	_, err := s.createThread(ctx, "nPi", input, native)
	var failure *creationError
	if !errors.As(err, &failure) || !failure.unknown || failure.code != "creation_native_unconfirmed" {
		t.Fatal("Known allocation was not retained independently of writer availability", err)
	}
	_, raw, err := s.creations.Read("nPi", creationID("nPi", input.AppID, input.RequestID, "result"))
	var receipt creationReceipt
	if err != nil || protocol.DecodeStrict(raw, &receipt) != nil || receipt.ThreadID != "thread-1" {
		t.Fatal("Verified allocation identity was lost when native connection closed", err, string(raw))
	}
	s.creations.Close()
	s.creations, err = files.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Setenv("BRIDGE_CHECK_EXISTING_PROJECT", s.cfg.IntegrationDir)
	reopened, reopenCtx := creationPeer(t, "app-existing-created")
	for _, operation := range []string{"creation", "create"} {
		input.Operation = operation
		if raw, err := s.createThread(reopenCtx, "nPi", input, reopened); err != nil || !bytes.Contains(raw, []byte("thread-1")) {
			t.Fatal("Original allocation failed read-only recovery after reconnect", string(raw), err)
		}
	}
	checkCreationPeerCounts(t, reopened, reopenCtx, 0, 1)
}

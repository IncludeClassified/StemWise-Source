package pc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"phonepcbridge/codex"
	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

func deadPowerPeer(t *testing.T) (*Service, *fakePowerSystem, *codex.Connection) {
	t.Helper()
	s, platform := powerFixture(t)
	t.Setenv("BRIDGE_AUTH_PEER", "")
	t.Setenv("BRIDGE_CHECK_PEER", "normal")
	t.Setenv("BRIDGE_CHECK_SOCKET", "")
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	c, _, err := s.connection(ctx, s.peers["nPi"])
	if err != nil {
		t.Fatal("Real native fixture did not start", err)
	}
	t.Cleanup(func() {
		s.closeConnection = nil
		if err := c.Close(); err != nil {
			t.Error("Fixture native retirement failed", err)
		}
	})
	// Complete real process/reader/tree cleanup without a subsequent service RPC.
	if err := c.Close(); err != nil {
		t.Fatal("Real native fixture did not fully retire", err)
	}
	return s, platform, c
}

func TestPowerDeadPeerRequiresCompleteRetirement(t *testing.T) {
	for _, variant := range []struct {
		name    string
		failure error
	}{
		{"fully_cleaned", nil},
		{"cleanup_error", errors.New("fixture owned tree retirement unconfirmed")},
		{"cleanup_timeout", context.DeadlineExceeded},
	} {
		t.Run(variant.name, func(t *testing.T) {
			s, platform, c := deadPowerPeer(t)
			failure, attempts := variant.failure, 0
			s.closeConnection = func(actual *codex.Connection) error {
				attempts++
				if actual != c {
					t.Fatal("Retirement targeted a different owner")
				}
				if failure != nil {
					return failure
				}
				return actual.Close()
			}
			request := PowerRequest{Operation: "shutdown", CycleID: randomToken(), TransitionID: platform.state.TransitionID}
			_, reply := callPower(t, s, request)
			if failure != nil {
				if reply.Code != "power_native_not_quiescent" || platform.calls != 0 || s.peers["nPi"].conn != c || attempts != 1 {
					t.Fatal("Done alone authorized power or lost unresolved ownership", reply, platform.calls, attempts)
				}
				if page, err := s.powerStore.List("nPi", "", 100); err != nil || len(page.Files) != 0 {
					t.Fatal("Unconfirmed native cleanup consumed power intent", page, err)
				}
				failure = nil
				_, reply = callPower(t, s, request)
			}
			if reply.State != "ok" || reply.Code != PowerShutdownAccepted || platform.calls != 1 || s.peers["nPi"].conn != nil {
				t.Fatal("Fully retired dead peer still blocked the fake OS boundary", reply, platform.calls, attempts)
			}
			_, again := callPower(t, s, request)
			if again.Code != PowerShutdownAccepted || platform.calls != 1 {
				t.Fatal("Dead-peer cleanup bypassed the consumed power fence", again, platform.calls)
			}
		})
	}
}

type powerAdmissionReader struct {
	*strings.Reader
	reads int
}

func (r *powerAdmissionReader) Read(data []byte) (int, error) {
	r.reads++
	return r.Reader.Read(data)
}

func TestPowerDeadPeerRetirementKeepsAdmissionFenced(t *testing.T) {
	s, platform, c := deadPowerPeer(t)
	entered, release := make(chan struct{}), make(chan struct{})
	var entryOnce, releaseOnce sync.Once
	unblock := func() { releaseOnce.Do(func() { close(release) }) }
	result, retired := make(chan PowerReply, 1), make(chan struct{})
	s.closeConnection = func(actual *codex.Connection) error {
		entryOnce.Do(func() { close(entered) })
		<-release
		return actual.Close()
	}
	t.Cleanup(func() {
		unblock()
		checksync.Join(t, retired, 3*time.Second, "held dead-peer retirement")
	})
	request := PowerRequest{Operation: "shutdown", CycleID: randomToken(), TransitionID: platform.state.TransitionID}
	go func() { defer close(retired); _, reply := callPower(t, s, request); result <- reply }()
	checksync.Receive(t, entered, time.Second, "native cleanup proof boundary")
	body, _ := json.Marshal(protocol.AdapterRequest{Nonce: randomToken(), Operation: "models"})
	r := httptest.NewRequest("POST", "/internal/v1/codex", bytes.NewReader(body))
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	s.handle(w, r)
	var rejected protocol.AdapterReply
	if w.Code != http.StatusOK || protocol.DecodeStrict(w.Body.Bytes(), &rejected) != nil || rejected.State != "rejected" || rejected.Code != "power_busy" || s.peers["nPi"].conn != c {
		t.Fatal("New native work entered before dead-peer retirement completed", w.Code, w.Body.String())
	}
	source := &powerAdmissionReader{Reader: strings.NewReader("refused")}
	descriptor, err := files.Describe(randomToken(), "refused.bin", []byte("refused"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.QueueFileReader(context.Background(), "nPi", descriptor, source); !errors.Is(err, context.Canceled) || source.reads != 0 {
		t.Fatal("New file work read bytes inside native cleanup fence", err, source.reads)
	}
	if _, err := s.outbox.Info("nPi", descriptor.ID); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Power-fenced source committed a file", err)
	}
	if platform.calls != 0 {
		t.Fatal("Fake OS boundary preceded native cleanup proof")
	}
	unblock()
	if reply := checksync.Receive(t, result, 3*time.Second, "completed dead-peer retirement"); reply.Code != PowerShutdownAccepted || platform.calls != 1 {
		t.Fatal("Retirement failed to release the original fake power request", reply, platform.calls)
	}
}

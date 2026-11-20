package pc

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"phonepcbridge/codex"
	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"tailscale.com/tsnet"
)

func closeTestService(t *testing.T, node *tsnet.Server, peers ...string) *Service {
	t.Helper()
	t.Setenv("BRIDGE_CHECK_PEER", "normal")
	s, err := New(node, Config{Identity: "nPc", Host: "pc.tailtest.ts.net", CodexExecutable: os.Args[0],
		AllowedPeers: peers, BackendPeer: peers[0], DataDir: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		s.closeConnection = nil
		if err := s.Close(); err != nil {
			t.Error("Fixture native retirement failed", err)
		}
	})
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	for _, id := range peers {
		connection, _, err := s.connection(ctx, s.peers[id])
		if err != nil || connection == nil {
			t.Fatal("Actual fixture connection failed", err)
		}
	}
	return s
}

func assertCloseStores(t *testing.T, s *Service, open bool) {
	t.Helper()
	for _, store := range []*files.Store{s.inbox, s.outbox, s.creations, s.powerStore} {
		_, err := store.List("nPi", "", 1)
		if (err == nil) != open {
			t.Fatalf("Managed store open=%v, expected %v: %v", err == nil, open, err)
		}
	}
}

func TestServiceCloseRetainsFailedNativeOwnershipAndRetries(t *testing.T) {
	s := closeTestService(t, &tsnet.Server{}, "nPi", "nOther", "nReady")
	first, second, ready := s.peers["nPi"].conn, s.peers["nOther"].conn, s.peers["nReady"].conn
	firstErr, secondErr := errors.New("fixture process retirement unconfirmed"), errors.New("fixture reader retirement unconfirmed")
	calls := map[*codex.Connection]int{}
	fail := true
	s.closeConnection = func(c *codex.Connection) error {
		calls[c]++
		if fail && c == first {
			return firstErr
		}
		if fail && c == second {
			return secondErr
		}
		return c.Close()
	}
	for attempt := 0; attempt < 2; attempt++ {
		err := s.Close()
		if !errors.Is(err, firstErr) || !errors.Is(err, secondErr) {
			t.Fatal("Service lost one or more native retirement failures", err)
		}
		if s.peers["nPi"].conn != first || s.peers["nOther"].conn != second || s.peers["nReady"].conn != nil {
			t.Fatal("Unconfirmed ownership lost or confirmed connection retained")
		}
		assertCloseStores(t, s, true)
		if _, _, err := s.connection(context.Background(), s.peers["nReady"]); !errors.Is(err, context.Canceled) {
			t.Fatal("Closed service reacquired a native runtime", err)
		}
	}
	if calls[ready] != 1 || calls[first] != 2 || calls[second] != 2 {
		t.Fatal("Repeated close retried a retired peer or lost pending peers", calls)
	}
	fail = false
	if err := s.Close(); err != nil {
		t.Fatal("Successful native retirement could not release resources", err)
	}
	assertCloseStores(t, s, false)
	for _, p := range s.peers {
		if p.conn != nil {
			t.Fatal("Confirmed native owner remained attached")
		}
	}
	if err := s.Close(); err != nil || calls[ready] != 1 || calls[first] != 3 || calls[second] != 3 {
		t.Fatal("Completed close released native ownership more than once", err, calls)
	}
}

func TestConcurrentServiceCloseWaitsForActualNativeRetirement(t *testing.T) {
	s := closeTestService(t, &tsnet.Server{}, "nPi")
	entered, release := make(chan struct{}), make(chan struct{})
	var once, entry sync.Once
	unblock := func() { once.Do(func() { close(release) }) }
	firstDone, secondDone := make(chan struct{}), make(chan struct{})
	firstResult, secondResult := make(chan error, 1), make(chan error, 1)
	secondStarted := false
	s.closeConnection = func(c *codex.Connection) error {
		entry.Do(func() { close(entered) })
		<-release
		return c.Close()
	}
	t.Cleanup(func() {
		unblock()
		checksync.Join(t, firstDone, 3*time.Second, "first service close")
		if secondStarted {
			checksync.Join(t, secondDone, 3*time.Second, "concurrent service close")
		}
	})
	go func() { defer close(firstDone); firstResult <- s.Close() }()
	checksync.Receive(t, entered, time.Second, "native retirement boundary")
	secondStarted = true
	go func() { defer close(secondDone); secondResult <- s.Close() }()
	select {
	case err := <-secondResult:
		t.Fatal("Concurrent close reported completion while retirement was held", err)
	case <-time.After(50 * time.Millisecond):
	}
	assertCloseStores(t, s, true)
	unblock()
	for _, result := range []<-chan error{firstResult, secondResult} {
		if err := checksync.Receive(t, result, 3*time.Second, "confirmed service close"); err != nil {
			t.Fatal(err)
		}
	}
	assertCloseStores(t, s, false)
}

func TestServeReturnsNativeRetirementFailure(t *testing.T) {
	invalidDirectory := filepath.Join(t.TempDir(), "not-a-directory")
	if err := os.WriteFile(invalidDirectory, []byte("fixture"), 0600); err != nil {
		t.Fatal(err)
	}
	s := closeTestService(t, &tsnet.Server{Dir: invalidDirectory}, "nPi")
	failure := errors.New("fixture native cleanup unconfirmed")
	s.closeConnection = func(*codex.Connection) error { return failure }
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := s.Serve(ctx); !errors.Is(err, failure) {
		t.Fatal("Serve discarded deferred native retirement failure", err)
	}
	assertCloseStores(t, s, true)
}

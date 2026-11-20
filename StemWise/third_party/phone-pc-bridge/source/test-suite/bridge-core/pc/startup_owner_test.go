package pc

import (
	"context"
	"errors"
	"testing"

	"phonepcbridge/codex"
	"tailscale.com/tsnet"
)

func TestFailedStartupOwnerBlocksReplacementUntilRetirement(t *testing.T) {
	s := closeTestService(t, &tsnet.Server{}, "nPi")
	p := s.peers["nPi"]
	failedOwner := p.conn
	if err := failedOwner.Close(); err != nil {
		t.Fatal("Real fixture child did not retire", err)
	}
	p.conn = nil
	initializationErr := errors.New("fixture initialization cancelled")
	cleanupErr := errors.New("fixture retirement proof unavailable")
	startupErr := &codex.StartupCleanupError{Owner: failedOwner, Cause: errors.Join(initializationErr, cleanupErr)}
	starts := 0
	s.connectNative = func(ctx context.Context, executable, socket string) (*codex.Connection, error) {
		starts++
		if starts == 1 {
			return nil, startupErr
		}
		return codex.Connect(ctx, executable, socket)
	}
	unconfirmed := true
	s.closeConnection = func(c *codex.Connection) error {
		if c == failedOwner && unconfirmed {
			return cleanupErr
		}
		return c.Close()
	}
	connection, _, err := s.connection(context.Background(), p)
	if connection != nil || !errors.Is(err, initializationErr) || !errors.Is(err, cleanupErr) || p.conn != failedOwner {
		t.Fatal("Service discarded failed-startup ownership or errors", connection, err)
	}
	for attempt := 0; attempt < 2; attempt++ {
		if connection, _, err := s.connection(context.Background(), p); connection != nil || !errors.Is(err, cleanupErr) || starts != 1 || p.conn != failedOwner {
			t.Fatal("Service started replacement before retirement proof", starts, connection, err)
		}
	}
	assertCloseStores(t, s, true)
	unconfirmed = false
	connection, _, err = s.connection(context.Background(), p)
	if err != nil || connection == nil || connection == failedOwner || starts != 2 {
		t.Fatal("Confirmed retirement could not admit one replacement", starts, connection, err)
	}
	if _, _, err := s.connection(context.Background(), p); err != nil || starts != 2 {
		t.Fatal("Established replacement was duplicated", starts, err)
	}
}

package files

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestStorageReservationsOnSmallFilesystem(t *testing.T) {
	path := os.Getenv("BRIDGE_TEST_SMALL_FS")
	if path == "" {
		t.Skip("Run through StorageCapacityCheck.py on an owned small filesystem")
	}
	base, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	available, _, err := availableSpace(base)
	base.Close()
	if err != nil || available < 20<<20 || available >= 64<<20 {
		t.Fatal("Owned small filesystem bounds differ", available, err)
	}
	budget := NewBudget(available - (15 << 20))
	open := func(name string) *Store {
		t.Helper()
		store, err := OpenWithBudget(filepath.Join(path, name), budget)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { store.Close() })
		return store
	}
	incoming, outgoing := open("incoming"), open("outgoing")
	data := strings.Repeat("x", 10<<20)
	id, _ := NewID()
	file, _ := Describe(id, "owned.bin", []byte(data))
	source := &heldSource{entered: make(chan struct{}), release: make(chan struct{})}
	done := make(chan error, 1)
	var once sync.Once
	finished := false
	go func() { _, err := incoming.Put("nInput", file, source); done <- err }()
	t.Cleanup(func() {
		once.Do(func() { close(source.release) })
		if !finished {
			select {
			case <-done:
			case <-time.After(5 * time.Second):
				t.Error("Owned upload did not stop")
			}
		}
	})
	select {
	case <-source.entered:
	case <-time.After(5 * time.Second):
		t.Fatal("First upload did not reserve and enter the source")
	}
	second := &io.LimitedReader{R: strings.NewReader(data), N: int64(len(data))}
	if _, err := outgoing.Put("nOutput", file, second); !errors.Is(err, ErrStorageCapacity) || second.N != int64(len(data)) {
		t.Fatal("Two stores counted the same filesystem space", err)
	}
	once.Do(func() { close(source.release) })
	select {
	case err := <-done:
		finished = true
		if !errors.Is(err, ErrContentMismatch) {
			t.Fatal("Interrupted upload did not fail as expected", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Interrupted upload did not release its reservation")
	}
	if actual, err := outgoing.Put("nOutput", file, strings.NewReader(data)); err != nil || actual != file {
		t.Fatal("Released space did not admit the unchanged second transfer", err)
	}
	if actual, err := outgoing.Info("nOutput", file.ID); err != nil || actual != file {
		t.Fatal("Admitted transfer lacks independently verified bytes", err)
	}
	if budget.claimed != 0 || budget.claimedInodes != 0 {
		t.Fatal("Completed transfer retained its space reservation")
	}
}

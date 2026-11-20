package files

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"phonepcbridge/internal/checksync"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestSlowUploadDoesNotBlockOtherFilesOrRetention(t *testing.T) {
	root := filepath.Join(t.TempDir(), "store")
	store, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { closeConcurrentStore(t, store) })
	id, _ := NewID()
	old, _ := Describe(id, "old.bin", []byte("verified old bytes"))
	if _, err := store.Put("nOther", old, strings.NewReader("verified old bytes")); err != nil {
		t.Fatal(err)
	}
	data := []byte("owned-slow-upload-" + id)
	newID, _ := NewID()
	f, _ := Describe(newID, "new.bin", data)
	source := &heldSource{entered: make(chan struct{}), release: make(chan struct{}), data: data}
	put := make(chan error, 1)
	var once sync.Once
	putDone := false
	go func() { _, err := store.Put("nOwned", f, source); put <- err }()
	t.Cleanup(func() {
		once.Do(func() { close(source.release) })
		if !putDone {
			select {
			case <-put:
			case <-time.After(3 * time.Second):
				t.Error("Owned upload did not stop")
			}
		}
	})
	select {
	case <-source.entered:
	case <-time.After(3 * time.Second):
		t.Fatal("Owned upload did not enter actual source read")
	}
	bounded := func(action string, operation func() error) {
		t.Helper()
		result := make(chan error, 1)
		go func() { result <- operation() }()
		select {
		case err := <-result:
			if err != nil {
				t.Fatal(action, err)
			}
		case <-time.After(3 * time.Second):
			once.Do(func() { close(source.release) })
			select {
			case <-result:
			case <-time.After(3 * time.Second):
				t.Error("Owned operation remained live after release")
			}
			t.Fatal("Network upload blocked unrelated operation", action)
		}
	}
	bounded("verified read", func() error {
		actual, data, err := store.Read("nOther", old.ID)
		if err != nil {
			return err
		}
		if actual != old || string(data) != "verified old bytes" {
			return errors.New("committed bytes changed")
		}
		return nil
	})
	bounded("same-peer receipt", func() error {
		_, err := store.Info("nOwned", f.ID)
		if !os.IsNotExist(err) {
			return errors.New("incomplete upload acquired a receipt")
		}
		return nil
	})
	bounded("independent put", func() error {
		id, _ := NewID()
		f, _ := Describe(id, "other.bin", []byte("other"))
		_, err := store.Put("nOther", f, strings.NewReader("other"))
		return err
	})
	bounded("retention", func() error { _, err := store.PruneExpired(time.Now(), 3*24*time.Hour); return err })
	entries, err := os.ReadDir(filepath.Join(root, "nOwned"))
	if err != nil || len(entries) != 1 || !strings.HasPrefix(entries[0].Name(), ".incoming-") {
		t.Fatal("Retention removed active staging", err)
	}
	once.Do(func() { close(source.release) })
	select {
	case err := <-put:
		putDone = true
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Released upload did not commit")
	}
	actual, content, err := store.Read("nOwned", f.ID)
	if err != nil || actual != f || !bytes.Equal(content, data) {
		t.Fatal("Released upload lost actual bytes", err)
	}
	entries, err = os.ReadDir(filepath.Join(root, "nOwned"))
	if err != nil || len(entries) != 1 || entries[0].Name() != f.ID {
		t.Fatal("Completed upload retained staging", err)
	}
}

func TestConcurrentUploadsKeepFirstCommittedBytesAndRetentionTime(t *testing.T) {
	for _, changed := range []bool{false, true} {
		t.Run(map[bool]string{false: "same", true: "conflict"}[changed], func(t *testing.T) {
			root := filepath.Join(t.TempDir(), "store")
			store, err := Open(root)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { closeConcurrentStore(t, store) })
			id, _ := NewID()
			data := []byte("first-started-" + id)
			f, _ := Describe(id, "owned.bin", data)
			winner, winnerData := f, data
			if changed {
				winnerData = []byte("first-committed-" + id)
				winner, _ = Describe(id, "winner.bin", winnerData)
			}
			source := &heldSource{entered: make(chan struct{}), release: make(chan struct{}), data: data}
			result := make(chan error, 1)
			var once sync.Once
			finished := false
			go func() { _, err := store.Put("nOwned", f, source); result <- err }()
			t.Cleanup(func() {
				once.Do(func() { close(source.release) })
				if !finished {
					select {
					case <-result:
					case <-time.After(3 * time.Second):
						t.Error("Owned competing upload remained live")
					}
				}
			})
			select {
			case <-source.entered:
			case <-time.After(3 * time.Second):
				t.Fatal("Owned upload failed to enter source read")
			}
			committed := make(chan error, 1)
			commitRetired := make(chan struct{})
			t.Cleanup(func() {
				once.Do(func() { close(source.release) })
				checksync.Join(t, commitRetired, 3*time.Second, "independent same-ID commit")
			})
			go func() {
				defer close(commitRetired)
				_, err := store.Put("nOwned", winner, bytes.NewReader(winnerData))
				committed <- err
			}()
			select {
			case err := <-committed:
				if err != nil {
					t.Fatal(err)
				}
			case <-time.After(3 * time.Second):
				once.Do(func() { close(source.release) })
				t.Fatal("Slow upload prevented independent commit of the same immutable ID")
			}
			path := filepath.Join(root, "nOwned", id)
			before, err := os.Stat(path)
			if err != nil {
				t.Fatal(err)
			}
			once.Do(func() { close(source.release) })
			select {
			case err := <-result:
				finished = true
				if (changed && !errors.Is(err, ErrConflict)) || (!changed && err != nil) {
					t.Fatal("Competing upload changed idempotence or conflict semantics", err)
				}
			case <-time.After(3 * time.Second):
				t.Fatal("Competing upload failed to finish")
			}
			actual, content, err := store.Read("nOwned", id)
			if err != nil || actual != winner || !bytes.Equal(content, winnerData) {
				t.Fatal("Competing upload overwrote committed contents", err)
			}
			after, err := os.Stat(path)
			if err != nil || !after.ModTime().Equal(before.ModTime()) {
				t.Fatal("Competing upload renewed retention", err)
			}
			entries, err := os.ReadDir(filepath.Dir(path))
			if err != nil || len(entries) != 1 || entries[0].Name() != id {
				t.Fatal("Competing upload retained disposable staging", err)
			}
		})
	}
}

func TestStoreCloseWaitsForActualUploadBeforeClosingRoot(t *testing.T) {
	root := filepath.Join(t.TempDir(), "store")
	store, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	id, _ := NewID()
	data := []byte("close-owned-" + id)
	f, _ := Describe(id, "owned.bin", data)
	source := &heldSource{entered: make(chan struct{}), release: make(chan struct{}), data: data}
	put, closed := make(chan error, 1), make(chan error, 1)
	var once sync.Once
	putDone, closeDone := false, false
	closeStarted := false
	go func() { _, err := store.Put("nOwned", f, source); put <- err }()
	t.Cleanup(func() {
		once.Do(func() { close(source.release) })
		if !putDone {
			select {
			case <-put:
			case <-time.After(3 * time.Second):
				t.Error("Owned upload did not finish")
			}
		}
		if !closeStarted {
			go func() { closed <- store.Close() }()
		}
		if !closeDone {
			select {
			case <-closed:
			case <-time.After(3 * time.Second):
				t.Error("Owned close did not finish")
			}
		}
	})
	select {
	case <-source.entered:
	case <-time.After(3 * time.Second):
		t.Fatal("Owned upload did not begin")
	}
	started := make(chan struct{})
	closeStarted = true
	go func() { close(started); closed <- store.Close() }()
	checksync.Receive(t, started, 3*time.Second, "store close start")
	select {
	case err := <-closed:
		closeDone = true
		t.Fatal("Store closed while source read was active", err)
	case <-time.After(100 * time.Millisecond):
	}
	once.Do(func() { close(source.release) })
	select {
	case err := <-put:
		putDone = true
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Closing interrupted the valid upload")
	}
	select {
	case err := <-closed:
		closeDone = true
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Closed store retained its root")
	}
	if _, _, err := store.Read("nOwned", id); err == nil {
		t.Fatal("Closed store remained readable")
	}
	resumed, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	defer resumed.Close()
	actual, content, err := resumed.Read("nOwned", id)
	if err != nil || actual != f || !bytes.Equal(content, data) {
		t.Fatal("Closing lost committed bytes on reopen", err)
	}
}

func closeConcurrentStore(t *testing.T, store *Store) {
	t.Helper()
	closed := make(chan struct{})
	go func() { defer close(closed); store.Close() }()
	checksync.Join(t, closed, 3*time.Second, "concurrent-upload store close")
}

type uploadSourceFunc func([]byte) (int, error)

func (f uploadSourceFunc) Read(p []byte) (int, error) { return f(p) }

func TestFailedCommitPreservesCauseAndOriginalTransfer(t *testing.T) {
	for _, directory := range []bool{false, true} {
		t.Run(map[bool]string{false: "file", true: "incomplete_directory"}[directory], func(t *testing.T) {
			root := filepath.Join(t.TempDir(), "store")
			budget := NewBudget(0)
			store, err := OpenWithBudget(root, budget)
			if err != nil {
				t.Fatal(err)
			}
			defer store.Close()
			id, _ := NewID()
			data := []byte("original-" + id)
			f, _ := Describe(id, "owned.bin", data)
			path := filepath.Join(root, "nOwned", id)
			blocker := path
			if directory {
				blocker = filepath.Join(path, "content")
			}
			created := false
			source := bytes.NewReader(data)
			_, err = store.Put("nOwned", f, uploadSourceFunc(func(p []byte) (int, error) {
				if !created {
					created = true
					if directory {
						if err := os.Mkdir(path, 0700); err != nil {
							return 0, err
						}
					}
					if err := os.WriteFile(blocker, []byte("preserve damaged destination"), 0600); err != nil {
						return 0, err
					}
				}
				return source.Read(p)
			}))
			if !errors.Is(err, ErrCorrupt) || errors.Is(err, ErrConflict) {
				t.Fatal("Damaged commit destination was replaced or became an ID conflict", err)
			}
			if actual, err := os.ReadFile(blocker); err != nil || string(actual) != "preserve damaged destination" {
				t.Fatal("Failed commit replaced the damaged destination", err)
			}
			if _, err := store.Put("nOwned", f, bytes.NewReader(data)); !errors.Is(err, ErrCorrupt) || os.IsNotExist(err) || errors.Is(err, ErrConflict) {
				t.Fatal("Damaged existing destination became missing or conflicted", err)
			}
			entries, err := os.ReadDir(filepath.Dir(path))
			if err != nil || len(entries) != 1 || entries[0].Name() != id || len(store.active) != 0 || budget.claimed != 0 || budget.claimedInodes != 0 {
				t.Fatal("Failed commit retained staging, active state or capacity", err)
			}
			if err := os.Remove(blocker); err != nil {
				t.Fatal(err)
			}
			if directory {
				if err := os.Remove(path); err != nil {
					t.Fatal(err)
				}
			}
			if _, err := store.Put("nOwned", f, bytes.NewReader(data)); err != nil {
				t.Fatal("Repaired storage rejected the unchanged transfer", err)
			}
			if actual, content, err := store.Read("nOwned", id); err != nil || actual != f || !bytes.Equal(content, data) {
				t.Fatal("Retried transfer lacks the original verified bytes", err)
			}
		})
	}
}

func TestUploadSourceErrorAndPanicLeaveNoStagingOrHeldLocks(t *testing.T) {
	for _, panics := range []bool{false, true} {
		t.Run(map[bool]string{false: "error", true: "panic"}[panics], func(t *testing.T) {
			root := filepath.Join(t.TempDir(), "store")
			store, err := Open(root)
			if err != nil {
				t.Fatal(err)
			}
			defer store.Close()
			id, _ := NewID()
			f, _ := Describe(id, "owned.bin", []byte("complete"))
			func() {
				defer func() {
					if r := recover(); (r != nil) != panics {
						t.Fatal("Unexpected source panic outcome", r)
					}
				}()
				_, err := store.Put("nOwned", f, uploadSourceFunc(func(p []byte) (int, error) {
					if panics {
						panic("owned_source_panic")
					}
					return copy(p, []byte("part")), errors.New("owned_source_failed")
				}))
				if err == nil {
					t.Fatal("Incomplete source committed a receipt")
				}
			}()
			entries, err := os.ReadDir(filepath.Join(root, "nOwned"))
			if err != nil || len(entries) != 0 {
				t.Fatal("Failed source left staging or an object", err)
			}
			if _, err := store.Put("nOwned", f, strings.NewReader("complete")); err != nil {
				t.Fatal("Failed source retained locks or transfer identity", err)
			}
			if _, err := store.PruneExpired(time.Now(), 3*24*time.Hour); err != nil {
				t.Fatal(err)
			}
		})
	}
}

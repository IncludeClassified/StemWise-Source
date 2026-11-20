package files

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"phonepcbridge/internal/checksync"
)

func TestDiagnosticsAreTimestampedMaintenanceSnapshotsOnly(t *testing.T) {
	root := filepath.Join(t.TempDir(), "objects")
	store, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if err := os.Mkdir(filepath.Join(root, "nOwned"), 0700); err != nil {
		t.Fatal(err)
	}
	unknown := filepath.Join(root, "nOwned", "vendor-entry")
	if err := os.WriteFile(unknown, []byte("preserve"), 0600); err != nil {
		t.Fatal(err)
	}
	if page, err := store.Diagnostics("nOwned"); err != nil || page.DiagnosticCount != 0 {
		t.Fatal("Ordinary diagnostics discovered unrelated history", page, err)
	}
	if _, observed, err := store.DiagnosticSnapshot(context.Background(), "nOwned"); err != nil || !observed.IsZero() {
		t.Fatal("Unobserved store was represented as a fresh healthy scan", observed, err)
	}
	now := time.Now()
	if _, err := store.PruneExpired(now, 72*time.Hour); err != nil {
		t.Fatal(err)
	}
	for _, peer := range []string{"nOwned", ""} {
		page, observed, err := store.DiagnosticSnapshot(context.Background(), peer)
		if err != nil || !observed.Equal(now) || page.DiagnosticCount != 1 || len(page.Diagnostics) != 1 || page.Diagnostics[0].ID != "vendor-entry" {
			t.Fatal("Maintenance omitted bounded scoped/aggregate diagnostics", peer, page, observed, err)
		}
	}
	if page, observed, err := store.DiagnosticSnapshot(context.Background(), "nOther"); err != nil || page.DiagnosticCount != 0 || !observed.Equal(now) {
		t.Fatal("Peer diagnostics leaked or lost full-scan observation time", page, observed, err)
	}
	if data, err := os.ReadFile(unknown); err != nil || string(data) != "preserve" {
		t.Fatal("Diagnostic maintenance changed unknown bytes", err)
	}
}

func TestGuardedMaintenanceDoesNotOwnGlobalLocksAndRevalidatesFinalObject(t *testing.T) {
	for _, scenario := range []string{"new_reference", "new_object", "ownership_unconfirmed"} {
		t.Run(scenario, func(t *testing.T) {
			root := filepath.Join(t.TempDir(), "objects")
			store, err := Open(root)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { store.Close() })
			target, _ := Describe(strings.Repeat("a", 32), "retained.bin", []byte("original"))
			healthy, _ := Describe(strings.Repeat("b", 32), "healthy.bin", []byte("healthy"))
			for _, item := range []struct{ id, name, content string }{{target.ID, target.Name, "original"}, {healthy.ID, healthy.Name, "healthy"}} {
				f, _ := Describe(item.id, item.name, []byte(item.content))
				if _, err := store.Put("nOwned", f, strings.NewReader(item.content)); err != nil {
					t.Fatal(err)
				}
			}
			old := time.Now().Add(-73 * time.Hour)
			if err := os.Chtimes(filepath.Join(root, "nOwned", target.ID), old, old); err != nil {
				t.Fatal(err)
			}
			entered, release, retired := make(chan struct{}), make(chan struct{}), make(chan struct{})
			var once sync.Once
			unblock := sync.OnceFunc(func() { close(release) })
			t.Cleanup(func() { unblock(); checksync.Join(t, retired, 3*time.Second, "guarded maintenance") })
			store.beforeMaintenanceObject = func(peer, id string) {
				if id == target.ID {
					once.Do(func() { close(entered); <-release })
				}
			}
			var owner sync.Mutex
			protected := false
			unconfirmed := errors.New("current references unavailable")
			result := make(chan error, 1)
			now := time.Now()
			go func() {
				defer close(retired)
				_, err := store.PrunePeerExpiredGuarded(now, 72*time.Hour, "nOwned", func(string) (bool, func(), error) {
					owner.Lock()
					if scenario == "ownership_unconfirmed" {
						return false, owner.Unlock, unconfirmed
					}
					return protected, owner.Unlock, nil
				})
				result <- err
			}()
			checksync.Receive(t, entered, 3*time.Second, "maintenance enumeration")
			if !owner.TryLock() {
				t.Fatal("Maintenance enumeration owns the reference guard")
			}
			protected = scenario == "new_reference"
			owner.Unlock()
			metadata := make(chan error, 1)
			go func() {
				_, err := store.Info("nOwned", healthy.ID)
				if err == nil {
					_, _, err = store.DiagnosticSnapshot(context.Background(), "nOwned")
				}
				metadata <- err
			}()
			if err := checksync.Receive(t, metadata, 3*time.Second, "exact lookup and cached status during stalled scan"); err != nil {
				t.Fatal(err)
			}
			if scenario == "new_object" {
				if err := store.Remove("nOwned", target.ID); err != nil {
					t.Fatal(err)
				}
				target, _ = Describe(target.ID, "replacement.bin", []byte("replacement"))
				if _, err := store.Put("nOwned", target, strings.NewReader("replacement")); err != nil {
					t.Fatal(err)
				}
			}
			unblock()
			err = checksync.Receive(t, result, 3*time.Second, "fresh deletion reference guard")
			if scenario == "ownership_unconfirmed" && !errors.Is(err, unconfirmed) || scenario != "ownership_unconfirmed" && err != nil {
				t.Fatal("Ownership failure was hidden or healthy maintenance failed", err)
			}
			if actual, err := store.Info("nOwned", target.ID); err != nil || actual != target {
				t.Fatal("Stale age/reference snapshot deleted current owned bytes", actual, err)
			}
			if scenario == "ownership_unconfirmed" {
				if _, observed, err := store.DiagnosticSnapshot(context.Background(), "nOwned"); !errors.Is(err, unconfirmed) || !observed.Equal(now) {
					t.Fatal("Failed maintenance became a healthy cached status", observed, err)
				}
			}
		})
	}
}

func TestScopedMaintenanceCannotEraseAnotherPeersAggregateDiagnostics(t *testing.T) {
	root := filepath.Join(t.TempDir(), "objects")
	store, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	for _, peer := range []string{"nDamaged", "nHealthy"} {
		if err := os.Mkdir(filepath.Join(root, peer), 0700); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(root, "nDamaged", "unknown-entry"), []byte("preserve"), 0600); err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	for _, peer := range []string{"nDamaged", "nHealthy"} {
		if _, err := store.PrunePeerExpiredGuarded(now, 72*time.Hour, peer, func(string) (bool, func(), error) { return false, nil, nil }); err != nil {
			t.Fatal(err)
		}
	}
	page, observed, err := store.DiagnosticSnapshot(context.Background(), "")
	if err != nil || !observed.IsZero() || page.DiagnosticCount != 1 || page.Diagnostics[0].Peer != "nDamaged" {
		t.Fatal("Healthy scoped pass erased another issue or invented full-scan coverage", page, observed, err)
	}
	if own, observed, err := store.DiagnosticSnapshot(context.Background(), "nHealthy"); err != nil || own.DiagnosticCount != 0 || !observed.Equal(now) {
		t.Fatal("Scoped status leaked another peer's diagnostics", own, observed, err)
	}
}

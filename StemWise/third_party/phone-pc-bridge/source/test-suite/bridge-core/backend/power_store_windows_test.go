package backend

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestPowerFailedDurableHoldCannotRemoveLastJobEvidence(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	p.appMu.Lock()
	defer p.appMu.Unlock()
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	j.State, j.Code = "failed", "native_terminal_unconfirmed"
	state.Jobs[j.ID] = j
	if err = s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	original := seedManagedPower(t, s, true)
	path := filepath.Join(s.cfg.DataDir, "power.json")
	// Windows rejects replacing a read-only destination while reads remain valid.
	if err = os.Chmod(path, 0444); err != nil {
		t.Fatal(err)
	}
	defer os.Chmod(path, 0600)
	if got := readTestPower(t, s); got.CycleID != original.CycleID || got.HoldCode != "" {
		t.Fatal("Fixture did not retain valid unheld prior power state", got)
	}
	if err = s.expireApp(state, time.UnixMilli(j.Created).Add(appRetention)); err == nil {
		t.Fatal("Expiry destroyed evidence despite failed durable hold")
	}
	persisted, err := s.loadApp(p)
	if err != nil || persisted.Jobs[j.ID] == nil || len(persisted.Cleanup) != 0 {
		t.Fatal("Failed hold write changed durable owner state", persisted, err)
	}
	if got := readTestPower(t, s); got.HoldCode != "" || got.CycleID != original.CycleID {
		t.Fatal("Failure was hidden behind fabricated hold success", got)
	}
	if err = os.Chmod(path, 0600); err != nil {
		t.Fatal(err)
	}
	if err = s.expireApp(persisted, time.UnixMilli(j.Created).Add(appRetention)); err != nil {
		t.Fatal(err)
	}
	persisted, err = s.loadApp(p)
	if err != nil || persisted.Jobs[j.ID] != nil || readTestPower(t, s).HoldCode == "" {
		t.Fatal("Repaired write did not durably latch before removal", persisted, err)
	}
}

package backend

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestConnectionUsesMaintenanceSnapshotInsteadOfScanningUnrelatedHistory(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	if _, err := s.QueueFile("nAlice", randomToken(), "held.bin", nil); err != nil {
		t.Fatal(err)
	}
	if _, err := s.retentionPass(time.Now()); err != nil {
		t.Fatal(err)
	}
	status := func() (int64, int) {
		t.Helper()
		w := call("GET", "/v2/connection", nil, "nAlice")
		var reply struct {
			Data struct {
				Storage map[string]struct {
					ObservedAt      *int64 `json:"observedAt"`
					DiagnosticCount int    `json:"diagnosticCount"`
				} `json:"storageDiagnostics"`
			} `json:"data"`
		}
		if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &reply) != nil || reply.Data.Storage["outgoing"].ObservedAt == nil {
			t.Fatal("Connection lost its explicit diagnostic observation time", w.Body.String())
		}
		return *reply.Data.Storage["outgoing"].ObservedAt, reply.Data.Storage["outgoing"].DiagnosticCount
	}
	stamp, count := status()
	if count != 0 {
		t.Fatal("Healthy maintenance fixture has diagnostics")
	}
	unknown := filepath.Join(s.cfg.DataDir, "outgoing", "nAlice", "unrelated.txt")
	if err := os.WriteFile(unknown, []byte("preserve"), 0600); err != nil {
		t.Fatal(err)
	}
	if current, count := status(); current != stamp || count != 0 {
		t.Fatal("An ordinary status request scanned unrelated store history")
	}
	if w := call("GET", "/v1/files/health?nonce="+randomToken(), nil, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	if current, count := status(); current != stamp || count != 0 {
		t.Fatal("An ordinary file request performed whole-store maintenance")
	}
	if _, err := s.retentionPass(time.Now()); err != nil {
		t.Fatal(err)
	}
	if current, count := status(); current < stamp || count != 1 {
		t.Fatal("Explicit maintenance failed to publish the new diagnostic snapshot", current, count)
	}
	if data, err := os.ReadFile(unknown); err != nil || string(data) != "preserve" {
		t.Fatal("Diagnostic discovery destroyed unrelated bytes", err)
	}
}

package pc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"phonepcbridge/codex"
	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func TestCurrentDiagnosticLabelsRemainReadableAndPrunableWithoutExecutionAPI(t *testing.T) {
	for _, operation := range []string{"idle", "sessions", "read", "native_startup_v3"} {
		t.Run(operation, func(t *testing.T) {
			s, _, _, _ := creationTestService(t)
			receipt := localProcessReceipt{Version: "v1", Operation: operation, Process: codex.LocalProcessDiagnostic{AttemptID: randomToken(), Stderr: "bounded private cause"}}
			raw, err := json.Marshal(receipt)
			if err != nil {
				t.Fatal(err)
			}
			f, err := files.Describe(receipt.Process.AttemptID, processDiagnosticName, raw)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := s.creations.Put("nPi", f, bytes.NewReader(raw)); err != nil {
				t.Fatal(err)
			}
			actual, err := s.readProcessDiagnostic("nPi", f)
			if err != nil || actual.Operation != operation {
				t.Fatal("Current private diagnostic became unreadable with its execution API removed", err)
			}
			accepted, err := s.creations.CommittedAt("nPi", f.ID)
			if err != nil {
				t.Fatal(err)
			}
			s.creationMu.Lock()
			_, err = s.pruneProcessDiagnostic(accepted.Add(protocol.Retention), "nPi", f)
			s.creationMu.Unlock()
			if err != nil {
				t.Fatal("Current diagnostic could no longer expire", err)
			}
			if _, err := s.creations.Info("nPi", f.ID); !errors.Is(err, files.ErrAbsent) {
				t.Fatal("Expired diagnostic kept indefinite operation history", err)
			}
		})
	}
}

func TestPrivateDiagnosticOperationLabelIsBoundedMachineData(t *testing.T) {
	for _, label := range []string{"", "../read", "read request", "read\nsecret", strings.Repeat("x", 65)} {
		s, _, input, _ := creationTestService(t)
		input.Operation = label
		local := codex.LocalProcessDiagnostic{AttemptID: randomToken(), Stderr: "bounded cause"}
		if err := s.saveProcessDiagnostic("nPi", input, local); err == nil {
			t.Fatal("Malformed or oversized diagnostic operation label was persisted", label)
		}
		if _, err := s.creations.Info("nPi", local.AttemptID); !errors.Is(err, files.ErrAbsent) {
			t.Fatal("Refused diagnostic label changed the receipt store", err)
		}
	}
	s, _, input, _ := creationTestService(t)
	input.Operation = strings.Repeat("x", 64)
	if err := s.saveProcessDiagnostic("nPi", input, codex.LocalProcessDiagnostic{AttemptID: randomToken()}); err != nil {
		t.Fatal("Maximum bounded machine label was rejected", err)
	}
}

func TestLocalDiagnosticKeepsFirstReceiptAndReceiverDeadline(t *testing.T) {
	s, _, input, root := creationTestService(t)
	s.ctx, s.cfg.AllowedPeers = context.Background(), []string{"nPi"}
	local := codex.LocalProcessDiagnostic{AttemptID: randomToken(), PID: 123, Stderr: "novel VX7 opcode failure"}
	if err := s.saveProcessDiagnostic("nPi", input, local); err != nil {
		t.Fatal(err)
	}
	f, first, err := s.creations.Read("nPi", local.AttemptID)
	if err != nil {
		t.Fatal(err)
	}
	accepted := time.Now().Add(-time.Hour).Truncate(time.Millisecond)
	path := filepath.Join(root, "nPi", local.AttemptID)
	if err := os.Chtimes(path, accepted, accepted); err != nil {
		t.Fatal(err)
	}
	input.Operation, input.JobID = "send", randomToken()
	local.Stderr = "later observation must not replace the original cause"
	if err := s.saveProcessDiagnostic("nPi", input, local); err != nil {
		t.Fatal(err)
	}
	_, after, err := s.creations.Read("nPi", local.AttemptID)
	stat, statErr := os.Stat(path)
	if err != nil || statErr != nil || !bytes.Equal(after, first) || !stat.ModTime().Equal(accepted) {
		t.Fatal("Repeated attempt rewrote receipt, correlation or deadline", err, statErr)
	}
	neighbor, _ := files.Describe(randomToken(), "other-owned-record.json", []byte("keep"))
	if _, err := s.creations.Put("nPi", neighbor, bytes.NewBufferString("keep")); err != nil {
		t.Fatal(err)
	}
	connect := func(context.Context, string) (deletionNative, error) {
		t.Error("Diagnostic expiry attempted native chat operation")
		return nil, errors.New("unexpected native call")
	}
	deadline := accepted.Add(protocol.Retention)
	if _, err := s.pruneOwnedChats(deadline.Add(-time.Millisecond), connect); err != nil {
		t.Fatal(err)
	}
	if _, _, err := s.creations.Read("nPi", f.ID); err != nil {
		t.Fatal("Receipt expired before receiver deadline", err)
	}
	if _, err := s.pruneOwnedChats(deadline, connect); err != nil {
		t.Fatal(err)
	}
	if _, _, err := s.creations.Read("nPi", f.ID); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Receipt survived exact deadline", err)
	}
	if _, _, err := s.creations.Read("nPi", neighbor.ID); err != nil {
		t.Fatal("Diagnostic expiry removed another record", err)
	}
}

func TestLocalDiagnosticNeverOverwritesDamagedOrForeignAttemptReceipt(t *testing.T) {
	for _, kind := range []string{"foreign_name", "foreign_attempt", "corrupt"} {
		t.Run(kind, func(t *testing.T) {
			s, _, input, root := creationTestService(t)
			local := codex.LocalProcessDiagnostic{AttemptID: randomToken(), Stderr: "first"}
			if err := s.saveProcessDiagnostic("nPi", input, local); err != nil {
				t.Fatal(err)
			}
			f, first, err := s.creations.Read("nPi", local.AttemptID)
			if err != nil {
				t.Fatal(err)
			}
			if kind == "corrupt" {
				if err := os.WriteFile(filepath.Join(root, "nPi", local.AttemptID, "content"), []byte("damaged"), 0600); err != nil {
					t.Fatal(err)
				}
			} else {
				if err := s.creations.Remove("nPi", f.ID); err != nil {
					t.Fatal(err)
				}
				if kind == "foreign_name" {
					f.Name = "creation-result.json"
				} else {
					first = bytes.ReplaceAll(first, []byte(local.AttemptID), []byte(randomToken()))
				}
				f, _ = files.Describe(f.ID, f.Name, first)
				if _, err := s.creations.Put("nPi", f, bytes.NewReader(first)); err != nil {
					t.Fatal(err)
				}
			}
			before, err := os.ReadFile(filepath.Join(root, "nPi", local.AttemptID, "content"))
			if err != nil {
				t.Fatal(err)
			}
			if err := s.saveProcessDiagnostic("nPi", input, local); err == nil {
				t.Fatal("Invalid existing attempt receipt was accepted")
			}
			after, err := os.ReadFile(filepath.Join(root, "nPi", local.AttemptID, "content"))
			if err != nil || !bytes.Equal(before, after) {
				t.Fatal("Invalid existing attempt receipt was overwritten", err)
			}
		})
	}
}

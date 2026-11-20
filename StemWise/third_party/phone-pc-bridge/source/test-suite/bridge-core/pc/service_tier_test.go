package pc

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func TestCreationServiceTierIsImmutableAcrossReopenOwnershipAndAuthorizedCleanup(t *testing.T) {
	for _, tier := range []string{"", "default", "fast", "opaque/Ω + 1"} {
		t.Run(strconv.Quote(tier), func(t *testing.T) {
			s, n, input, root := creationTestService(t)
			s.ctx, s.cfg.AllowedPeers = context.Background(), []string{"nPi"}
			input.ServiceTier = tier
			first, err := s.createThread(context.Background(), "nPi", input, n)
			if err != nil || n.serviceTier != tier || n.starts != 1 {
				t.Fatal("Native creation did not receive exact tier", err, n.serviceTier)
			}
			requestID, resultID := creationID("nPi", input.AppID, input.RequestID, "request"), creationID("nPi", input.AppID, input.RequestID, "result")
			_, saved, err := s.creations.Read("nPi", requestID)
			if err != nil {
				t.Fatal(err)
			}
			var request creationInput
			if protocol.DecodeStrict(saved, &request) != nil || request.ServiceTier != tier {
				t.Fatal("Immutable creation did not persist exact tier")
			}
			if tier == "" && bytes.Contains(saved, []byte("serviceTier")) {
				t.Fatal("Empty tier rewrote established creation shape")
			}
			_, raw, err := s.creations.Read("nPi", resultID)
			if err != nil {
				t.Fatal(err)
			}
			var receipt creationReceipt
			digest := sha256.Sum256(saved)
			if protocol.DecodeStrict(raw, &receipt) != nil || receipt.RequestHash != hex.EncodeToString(digest[:]) {
				t.Fatal("Creation receipt hash does not bind exact serialized tier")
			}
			accepted, err := s.creations.CommittedAt("nPi", requestID)
			if err != nil {
				t.Fatal(err)
			}
			if err := s.creations.Close(); err != nil {
				t.Fatal(err)
			}
			s.creations, err = files.Open(root)
			if err != nil {
				t.Fatal(err)
			}
			input.Operation = "creation"
			got, err := s.createThread(context.Background(), "nPi", input, n)
			if err != nil || !bytes.Equal(got, first) || n.starts != 1 {
				t.Fatal("Reopened tier receipt changed or replayed native creation", err)
			}
			_, after, err := s.creations.Read("nPi", requestID)
			afterAccepted, timeErr := s.creations.CommittedAt("nPi", requestID)
			if err != nil || timeErr != nil || !bytes.Equal(saved, after) || !afterAccepted.Equal(accepted) {
				t.Fatal("Receipt observation migrated bytes or renewed retention", err, timeErr)
			}
			changed := input
			changed.ServiceTier = "other-tier"
			_, err = s.createThread(context.Background(), "nPi", changed, n)
			var conflict *creationError
			if !errors.As(err, &conflict) || conflict.code != "creation_request_conflict" || n.starts != 1 {
				t.Fatal("Changed tier did not conflict with immutable accepted creation", err)
			}
			changed.ThreadID = receipt.ThreadID
			selection := &ownedSelectionFixture{}
			if _, err := s.selectCreation(context.Background(), "nPi", changed, selection); err == nil || selection.calls != 0 {
				t.Fatal("Changed tier restored an owned writer", err)
			}
			deletion := &deletingNative{creationNativeFixture: n}
			if err := s.deleteCreation(context.Background(), "nPi", changed, deletion); err == nil || deletion.calls != 0 {
				t.Fatal("Changed tier deleted an owned thread", err)
			}
			changed.TurnID = "turn-1"
			retirement := &retirementFixture{}
			if _, err := s.retireCreation(context.Background(), "nPi", changed, retirement); err == nil || retirement.calls != 0 {
				t.Fatal("Changed tier retired owned tools", err)
			}
			if err := s.authorizeCleanup(context.Background(), "nPi", input, false); err != nil {
				t.Fatal(err)
			}
			if _, err := s.pruneOwnedChats(accepted.Add(protocol.Retention+time.Second), func(context.Context, string) (deletionNative, error) { return deletion, nil }); err != nil || deletion.calls != 1 {
				t.Fatal("Authorized cleanup failed to use persisted exact tier for native deletion", err, deletion.calls)
			}
		})
	}
}

func TestLegacyCreationReceiptWithoutTierRecoversWithoutMigrationOrReplay(t *testing.T) {
	s, n, input, _ := creationTestService(t)
	input.AppID, input.RequestID, input.CreatedAt, input.Name, input.Model = strings.Repeat("a", 32), strings.Repeat("b", 32), 1700000000000, "legacy accepted", "fixture-model"
	legacy := []byte(`{"appId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","requestId":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","createdAt":1700000000000,"name":"legacy accepted","model":"fixture-model","project":` + strconv.Quote(s.cfg.IntegrationDir) + `}`)
	id := creationID("nPi", input.AppID, input.RequestID, "request")
	f, _ := files.Describe(id, "creation-request.json", legacy)
	if _, err := s.creations.Put("nPi", f, bytes.NewReader(legacy)); err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(legacy)
	receipt := creationReceipt{hex.EncodeToString(digest[:]), "legacy-native"}
	raw, _ := json.Marshal(receipt)
	f, _ = files.Describe(creationID("nPi", input.AppID, input.RequestID, "result"), "creation-result.json", raw)
	if _, err := s.creations.Put("nPi", f, bytes.NewReader(raw)); err != nil {
		t.Fatal(err)
	}
	n.threads[receipt.ThreadID] = s.cfg.IntegrationDir
	for _, operation := range []string{"creation", "create"} {
		input.Operation = operation
		if _, err := s.createThread(context.Background(), "nPi", input, n); err != nil || n.starts != 0 {
			t.Fatal("Established tier-free reservation migrated or replayed", err)
		}
	}
	_, after, err := s.creations.Read("nPi", id)
	if err != nil || !bytes.Equal(after, legacy) {
		t.Fatal("Legacy accepted bytes changed", err)
	}
}

func TestInvalidCreationTierFailsBeforeReservationAndNativeStart(t *testing.T) {
	s, n, input, _ := creationTestService(t)
	for _, tier := range []string{strings.Repeat("x", 129), "fast\n", "fast\u0085", string([]byte{0xff})} {
		input.ServiceTier = tier
		if _, err := s.createThread(context.Background(), "nPi", input, n); err == nil || n.starts != 0 {
			t.Fatal("Invalid tier reached native creation", err)
		}
	}
	page, err := s.creations.List("nPi", "", 100)
	if err != nil || len(page.Files) != 0 {
		t.Fatal("Invalid tier reserved immutable work", err)
	}
}

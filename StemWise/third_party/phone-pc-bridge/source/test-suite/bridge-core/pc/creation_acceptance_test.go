package pc

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func TestCreationReceiverDeadlineSurvivesReopenWithoutSenderClockAgreement(t *testing.T) {
	for _, skew := range []time.Duration{5 * time.Minute, -5 * 24 * time.Hour} {
		t.Run(skew.String(), func(t *testing.T) {
			s, base, input, path := creationTestService(t)
			s.ctx, s.cfg.AllowedPeers = context.Background(), []string{"nPi"}
			native := &deletingNative{creationNativeFixture: base}
			input.CreatedAt = time.Now().Add(skew).UnixMilli()
			if _, err := s.createThread(context.Background(), "nPi", input, native); err != nil {
				t.Fatal("Fresh receiver acceptance required sender clock agreement", err)
			}
			id := creationID("nPi", input.AppID, input.RequestID, "request")
			_, raw, err := s.creations.Read("nPi", id)
			canonical, _ := json.Marshal(creationInput{input.AppID, input.RequestID, input.CreatedAt, input.Name, input.Model, s.cfg.IntegrationDir, input.ServiceTier})
			if err != nil || !bytes.Equal(raw, canonical) {
				t.Fatal("Receiver acceptance rewrote immutable sender identity", err)
			}
			accepted := time.Now().Add(-10 * 24 * time.Hour).Truncate(time.Millisecond)
			if err := os.Chtimes(filepath.Join(path, "nPi", id), accepted, accepted); err != nil {
				t.Fatal(err)
			}
			deadline := accepted.Add(protocol.Retention)
			s.creations.Close()
			s.creations, err = files.Open(path)
			if err != nil {
				t.Fatal(err)
			}
			input.Operation = "creation"
			if _, err := s.createThread(context.Background(), "nPi", input, native); err != nil || base.starts != 1 {
				t.Fatal("Clock skew or reopen replayed original native creation", err)
			}
			connect := func(context.Context, string) (deletionNative, error) { return native, nil }
			if _, err := s.pruneOwnedChats(deadline.Add(-time.Millisecond), connect); err != nil || native.calls != 0 {
				t.Fatal("Sender clock shortened receiver-owned lifetime", err)
			}
			if _, err := s.pruneOwnedChats(deadline, connect); err != nil || native.calls != 1 {
				t.Fatal("Receiver deadline did not retire exact owned native chat", err, native.calls)
			}
			if _, err := s.createThread(context.Background(), "nPi", input, native); err == nil || base.starts != 1 {
				t.Fatal("Expired accepted request was replayed", err)
			}
		})
	}
}

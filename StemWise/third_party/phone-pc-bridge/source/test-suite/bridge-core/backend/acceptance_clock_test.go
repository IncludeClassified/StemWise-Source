package backend

import (
	"testing"
	"time"

	"phonepcbridge/protocol"
)

func TestJobAcceptanceUsesReceiverTimeAndPreservesImmutableSenderTime(t *testing.T) {
	for _, skew := range []time.Duration{-365 * 24 * time.Hour, 365 * 24 * time.Hour} {
		t.Run(skew.String(), func(t *testing.T) {
			s, _, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			input := appInput{RequestID: randomToken(), CreatedAt: time.Now().Add(skew).UnixMilli(), Title: "Clock independent job", Text: "unchanged input", Files: []protocol.File{}}
			before := time.Now().UnixMilli()
			if response := call("POST", "/v2/jobs", input, "nAlice"); response.Code != 202 {
				t.Fatal("Fresh request required sender clock agreement", response.Code, response.Body.String())
			}
			state, err := s.loadApp(s.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			job := state.Jobs[input.RequestID]
			if job.Created < before || job.Created > time.Now().UnixMilli() || job.Input.CreatedAt != input.CreatedAt || job.Fingerprint != appHash(input) {
				t.Fatal("Receiver acceptance or immutable sender identity changed")
			}
			if response := call("POST", "/v2/jobs", input, "nAlice"); response.Code != 200 {
				t.Fatal("Original accepted request did not reconcile idempotently", response.Body.String())
			}
			changed := input
			changed.CreatedAt++
			if response := call("POST", "/v2/jobs", changed, "nAlice"); response.Code != 409 {
				t.Fatal("Sender timestamp stopped participating in immutable identity", response.Body.String())
			}
			deadline := time.UnixMilli(job.Created).Add(appRetention)
			if _, err := s.retentionPass(deadline.Add(-time.Millisecond)); err != nil {
				t.Fatal(err)
			}
			state, err = s.loadApp(s.getPeer("nAlice"))
			if err != nil || state.Jobs[input.RequestID] == nil || state.Jobs[input.RequestID].Input.CreatedAt != input.CreatedAt || state.Jobs[input.RequestID].Fingerprint != appHash(input) {
				t.Fatal("Sender clock changed the receiver-owned lifetime", err)
			}
			if _, err := s.retentionPass(deadline); err != nil {
				t.Fatal(err)
			}
			state, err = s.loadApp(s.getPeer("nAlice"))
			if err != nil || state.Jobs[input.RequestID] != nil || state.DeletedRequests[input.RequestID] == 0 {
				t.Fatal("Receiver deadline did not expire the job independently of sender clock", err)
			}
		})
	}
}

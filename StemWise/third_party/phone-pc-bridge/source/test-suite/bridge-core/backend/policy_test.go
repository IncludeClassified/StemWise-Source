package backend

import (
	"encoding/json"
	"testing"
	"time"

	"phonepcbridge/protocol"
)

func TestAdvertisedRetentionMatchesReceiverExpiry(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Policy boundary", Text: "offline fixture", Files: []protocol.File{}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Code, w.Body.String())
	}
	w := call("GET", "/v2/app", nil, "nAlice")
	var response struct {
		Data struct {
			JobRetentionDays   float64 `json:"jobRetentionDays"`
			CacheRetentionDays float64 `json:"cacheRetentionDays"`
		} `json:"data"`
	}
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &response) != nil {
		t.Fatal(w.Code, w.Body.String())
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	j := state.Jobs[input.RequestID]
	deadline := time.UnixMilli(j.Created).Add(time.Duration(response.Data.JobRetentionDays * float64(24*time.Hour)))
	if response.Data.CacheRetentionDays*float64(24*time.Hour) != float64(protocol.Retention) {
		t.Fatal("Advertised cache lifetime differs from the enforced policy")
	}
	if err := s.expireApp(state, deadline.Add(-time.Millisecond)); err != nil || state.Jobs[j.ID] == nil {
		t.Fatal("Advertised job lifetime outlives receiver expiry", err)
	}
	if err := s.expireApp(state, deadline); err != nil || state.Jobs[j.ID] != nil {
		t.Fatal("Advertised job deadline does not expire the receiver record", err)
	}
}

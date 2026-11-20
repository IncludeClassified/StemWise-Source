package backend

import (
	"context"
	"errors"
	"time"

	"phonepcbridge/protocol"
)

var errDiscardUnconfirmed = errors.New("job_cleanup_authorization_unconfirmed")

type appDiscardReceipt struct {
	AppID     string `json:"appId"`
	RequestID string `json:"requestId"`
	Accepted  bool   `json:"accepted"`
	Deleted   bool   `json:"deleted,omitempty"`
}

func sameJobOwner(current, original *appJob) bool {
	return current != nil && original != nil && current.ID == original.ID && current.Fingerprint == original.Fingerprint && current.Created == original.Created && current.ThreadID == original.ThreadID && current.Developer == original.Developer && current.Model == original.Model && current.Effort == original.Effort && current.ServiceTier == original.ServiceTier
}

// Authorize cleanup durably on the PC before dropping its exact owner's Pi recovery state.
func (s *Service) authorizeJobDiscard(parent context.Context, p *peer, state *appState, job *appJob) (*appState, error) {
	if job == nil {
		return state, errDiscardUnconfirmed
	}
	if job.ChatState == "deleted" || (job.ThreadID == "" && job.ChatState == "") {
		return state, nil
	}
	app, original := state.ID, *job
	request := jobCreation(app, &original, "discard")
	request.ThreadID = original.ThreadID
	p.appMu.Unlock()
	ctx, cancel := context.WithTimeout(parent, 35*time.Second)
	stop := context.AfterFunc(s.ctx, cancel)
	outcome, callErr := s.native.Call(ctx, request)
	stop()
	cancel()
	var receipt appDiscardReceipt
	confirmed := callErr == nil && outcome.State == "ok" && protocol.DecodeStrict(outcome.Result, &receipt) == nil && receipt.Accepted && receipt.AppID == app && receipt.RequestID == original.ID
	p.appMu.Lock()
	latest, err := s.loadApp(p)
	if err != nil {
		return state, err
	}
	current := latest.Jobs[original.ID]
	if !confirmed || latest.ID != app || !sameJobOwner(current, &original) || !jobTerminal(current) {
		return latest, errDiscardUnconfirmed
	}
	if receipt.Deleted {
		current.ChatState = "deleted"
	}
	return latest, nil
}

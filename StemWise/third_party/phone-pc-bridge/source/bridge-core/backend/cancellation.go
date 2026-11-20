package backend

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"time"

	"phonepcbridge/protocol"
)

func jobTerminal(j *appJob) bool {
	return contains([]string{"cancelled", "rejected", "completed", "failed", "interrupted"}, j.State)
}

// A conclusive no-send outcome can retire cancellation without a native turn.
func cancelQueuedJob(j *appJob) bool {
	if j.State != "queued" || j.TurnID != "" || j.CancelRequested == 0 {
		return false
	}
	j.State, j.Code = "cancelled", "cancelled_before_submission"
	j.CancelRetired = j.CancelRequested
	j.Updated = time.Now().UnixMilli()
	return true
}

// appMu is held on entry and return. Intent is durable before any native side effect.
func (s *Service) interruptCancelledJob(p *peer, state *appState, j *appJob) (*appState, error) {
	if j.CancelRequested == 0 || j.CancelAcknowledged != 0 || j.State != "submitted" || j.TurnID == "" {
		return state, nil
	}
	app, id := state.ID, j.ID
	snapshot := *j
	before, _ := json.Marshal(j)
	request := protocol.AdapterRequest{Operation: "interrupt", ThreadID: j.ThreadID, TurnID: j.TurnID}
	p.appMu.Unlock()
	var restoreLog *appLogEntry
	outcome, callErr := func() (protocol.AdapterReply, error) {
		defer p.appMu.Lock()
		ctx, cancel := context.WithTimeout(s.ctx, 35*time.Second)
		defer cancel()
		var err error
		restoreLog, err = s.restoreCancelledWriter(ctx, app, &snapshot)
		if err != nil {
			return protocol.AdapterReply{}, err
		}
		return s.native.Call(ctx, request)
	}()
	latest, err := s.loadApp(p)
	if err != nil {
		return nil, err
	}
	if err := s.expireAppForUse(p, latest, time.Now()); err != nil {
		return latest, err
	}
	j = latest.Jobs[id]
	current, _ := json.Marshal(j)
	if latest.ID != app || j == nil || !bytes.Equal(before, current) {
		return latest, nil
	}
	j.Updated = time.Now().UnixMilli()
	if callErr != nil || outcome.State != "ok" {
		failure := *j
		failure.Code = "interrupt_unconfirmed"
		entry := dispatchJobLog(&failure, "interrupt", outcome, callErr)
		if j.CancelReason == "native_interaction_required" {
			entry.Error.Code = "native_interaction_interrupt_unconfirmed"
		}
		if restoreLog != nil {
			entry = *restoreLog
		}
		retry := true
		entry.Error.Retryable = &retry
		appendJobLog(j, []appLogEntry{entry})
		j.Code = "cancellation_pending"
	} else {
		// An interrupt acknowledgement is not evidence of native termination.
		j.CancelAcknowledged = j.Updated
		j.Code = "interrupt_requested"
	}
	if j.CancelReason == "native_interaction_required" {
		j.Code = "native_interaction_required"
	}
	return latest, s.saveApp(latest)
}

// A terminal turn can still own background processes. Only the PC can confirm their retirement.
func (s *Service) retireCancelledJob(p *peer, state *appState, j *appJob, nativeEntries []appLogEntry) (bool, error) {
	app, id := state.ID, j.ID
	before, _ := json.Marshal(j)
	request := jobCreation(app, j, "retire")
	request.ThreadID, request.TurnID = j.ThreadID, j.TurnID
	p.appMu.Unlock()
	outcome, callErr := func() (protocol.AdapterReply, error) {
		defer p.appMu.Lock()
		ctx, cancel := context.WithTimeout(s.ctx, 35*time.Second)
		defer cancel()
		return s.native.Call(ctx, request)
	}()
	latest, err := s.loadApp(p)
	if err != nil {
		return false, err
	}
	if err := s.expireAppForUse(p, latest, time.Now()); err != nil {
		return false, err
	}
	*state = *latest
	j = state.Jobs[id]
	current, _ := json.Marshal(j)
	if state.ID != app || j == nil || !bytes.Equal(before, current) {
		return false, nil
	}
	appendJobLog(j, nativeEntries)
	var receipt struct {
		ThreadID         string `json:"threadId"`
		TurnID           string `json:"turnId"`
		TerminalsRetired bool   `json:"terminalsRetired"`
	}
	confirmed := callErr == nil && outcome.State == "ok" && protocol.DecodeStrict(outcome.Result, &receipt) == nil && receipt.ThreadID == j.ThreadID && receipt.TurnID == j.TurnID && receipt.TerminalsRetired
	j.Updated = time.Now().UnixMilli()
	if !confirmed {
		if callErr == nil && outcome.State == "ok" {
			callErr = errors.New("exact_owned_terminal_retirement_receipt_unconfirmed")
		}
		failure := *j
		failure.Code = "cancellation_retirement_unconfirmed"
		entry := dispatchJobLog(&failure, "retire", outcome, callErr)
		retry := true
		entry.Error.Retryable = &retry
		appendJobLog(j, []appLogEntry{entry})
		j.Code = "cancellation_pending"
		if j.CancelReason == "native_interaction_required" {
			j.Code = "native_interaction_required"
		}
		return false, s.saveApp(state)
	}
	j.CancelRetired = j.Updated
	return true, s.saveApp(state)
}

package backend

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"phonepcbridge/protocol"
	"strings"
	"time"
)

// Caller holds appMu. Native history is read-only; reload before applying it.
func (s *Service) observeApp(p *peer, state *appState, j *appJob) (*appState, error) {
	app, snapshot := state.ID, *j
	parent := s.jobContext(p, app, j.ID)
	before, _ := json.Marshal(j)
	p.appMu.Unlock()
	ctx, cancel := context.WithTimeout(parent, 30*time.Second)
	defer cancel()
	var turn json.RawMessage
	restoreLog, lookupErr := s.restoreCancelledWriter(ctx, app, &snapshot)
	if lookupErr == nil {
		turn, lookupErr = s.lookupAppTurn(ctx, &snapshot)
	}
	p.appMu.Lock()
	state, err := s.loadApp(p)
	if err != nil {
		return state, err
	}
	if err := s.expireAppForUse(p, state, time.Now()); err != nil {
		return state, err
	}
	j = state.Jobs[snapshot.ID]
	if state.ID != app || j == nil {
		return state, nil
	}
	current, _ := json.Marshal(j)
	if !bytes.Equal(before, current) {
		return state, nil
	}
	if lookupErr != nil {
		j.Code, j.Updated = "job_observation_unavailable", time.Now().UnixMilli()
		entry := observationJobLog(j, lookupErr)
		if restoreLog != nil {
			entry = *restoreLog
		}
		appendJobLog(j, []appLogEntry{entry})
		return state, s.saveApp(state)
	}
	return state, s.applyAppTurn(parent, p, state, j, turn)
}

// Cancellation may recover a native connection, but never create or replay a turn.
func (s *Service) restoreCancelledWriter(ctx context.Context, app string, j *appJob) (*appLogEntry, error) {
	if j.CancelRequested == 0 {
		return nil, nil
	}
	request := jobCreation(app, j, "select")
	request.ThreadID = j.ThreadID
	outcome, callErr := s.native.Call(ctx, request)
	var selected struct {
		Thread struct {
			ID string `json:"id"`
		} `json:"thread"`
	}
	if callErr == nil && outcome.State == "ok" && json.Unmarshal(outcome.Result, &selected) == nil && selected.Thread.ID == j.ThreadID {
		return nil, nil
	}
	if callErr == nil && outcome.State == "ok" {
		callErr = errors.New("owned_writer_restoration_receipt_unconfirmed")
	}
	failure := *j
	failure.Code = "job_observation_unavailable"
	entry := dispatchJobLog(&failure, "select", outcome, callErr)
	retry := true
	entry.Error.Retryable = &retry
	return &entry, errors.New("owned_writer_restoration_unconfirmed")
}

func observationJobLog(j *appJob, err error) appLogEntry {
	retry := true
	detail := map[string]string{"component": "codex_app_server", "thread_id": j.ThreadID, "turn_id": j.TurnID}
	if text := protocol.DiagnosticText(err.Error(), 1024); strings.TrimSpace(text) != "" {
		detail["error_detail"] = text
	}
	return appLogEntry{Source: "bridge", Error: appDiagnostic{Code: "job_observation_unavailable", Operation: "observe_acknowledged_turn", Summary: "Native history could not establish this job's acknowledged outcome", Cause: "native_history_unconfirmed", Retryable: &retry, Context: detail, Expected: "exact_acknowledged_native_turn", Actual: "native_turn_observation_unconfirmed", SuggestedFix: []string{"restore_native_history_access", "read_saved_status_and_original_diagnostic_log", "do_not_resubmit_the_acknowledged_native_job"}}}
}

package backend

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"reflect"
	"time"

	"phonepcbridge/protocol"
)

func jobCreation(app string, j *appJob, operation string) protocol.AdapterRequest {
	return protocol.AdapterRequest{Operation: operation, AppID: app, RequestID: j.ID, CreatedAt: j.Created, Name: j.Developer, Model: j.Model, ServiceTier: j.ServiceTier}
}

// Persist uncertainty first; only a confirmed absent PC reservation permits creation.
func (s *Service) ensureJobChat(p *peer, state *appState, job *appJob) (*appState, error) {
	operation := "creation"
	if job.ChatState == "" {
		job.ChatState = "unknown"
		job.Code = "chat_creation_unconfirmed"
		if err := s.saveApp(state); err != nil {
			return state, err
		}
		operation = "create"
	}
	snapshot, app := *job, state.ID
	parent := s.jobContext(p, app, job.ID)
	p.appMu.Unlock()
	ctx, cancel := context.WithTimeout(parent, 35*time.Second)
	defer cancel()
	outcome, callErr := s.native.Call(ctx, jobCreation(app, &snapshot, operation))
	if operation == "creation" && callErr == nil && outcome.State == "rejected" && outcome.Code == "creation_request_absent" && len(outcome.Result) == 0 {
		p.appMu.Lock()
		latest, err := s.loadApp(p)
		if err != nil {
			return state, err
		}
		if err = s.expireAppForUse(p, latest, time.Now()); err != nil {
			return latest, err
		}
		current := latest.Jobs[snapshot.ID]
		if latest.ID != app || current == nil || current.State != "queued" || current.ThreadID != "" || current.ChatState != snapshot.ChatState || current.Fingerprint != snapshot.Fingerprint {
			return latest, nil
		}
		p.appMu.Unlock()
		outcome, callErr = s.native.Call(ctx, jobCreation(app, &snapshot, "create"))
	}
	var response struct {
		Thread struct {
			ID string `json:"id"`
		} `json:"thread"`
	}
	confirmed := callErr == nil && outcome.State == "ok" && json.Unmarshal(outcome.Result, &response) == nil && nodeID.MatchString(response.Thread.ID)
	if confirmed {
		_ = s.native.NameThread(ctx, response.Thread.ID, snapshot.Developer)
	}
	cancel()
	p.appMu.Lock()
	latest, err := s.loadApp(p)
	if err != nil {
		return state, err
	}
	if err = s.expireAppForUse(p, latest, time.Now()); err != nil {
		return latest, err
	}
	current := latest.Jobs[snapshot.ID]
	if latest.ID != app || current == nil || current.ThreadID != "" || (current.State != "queued" && !userCancelledJob(current)) || current.ChatState != snapshot.ChatState || current.Fingerprint != snapshot.Fingerprint {
		return latest, nil
	}
	if confirmed {
		current.ThreadID, current.ChatState = response.Thread.ID, "created"
		if current.State == "queued" {
			current.Code = "awaiting_pc"
		}
	} else {
		current.Code = creationFailureCode(outcome, callErr)
		appendJobLog(current, []appLogEntry{dispatchJobLog(current, "observe_original_creation", outcome, callErr)})
	}
	current.Updated = time.Now().UnixMilli()
	return latest, s.saveApp(latest)
}

func (s *Service) cleanupJobChat(p *peer, state *appState, job *appJob) (*appState, error) {
	if !jobChatCleanupPending(job) {
		return state, nil
	}
	if !job.CleanupAccepted || job.ThreadID == "" {
		app, original := state.ID, *job
		latest, err := s.authorizeJobDiscard(s.jobContext(p, app, job.ID), p, state, job)
		if err != nil && !errors.Is(err, errDiscardUnconfirmed) {
			return latest, err
		}
		current := latest.Jobs[original.ID]
		if latest.ID != app || !sameJobOwner(current, &original) || current.Delivered != original.Delivered {
			return latest, nil
		}
		if current.ChatState == "deleted" {
			current.CleanupAccepted = true
			return latest, s.saveApp(latest)
		}
		current.ChatState = "cleanup_pending"
		if err == nil {
			current.CleanupAccepted = true
		} else {
			retry := true
			detail := map[string]string{"job_id": current.ID}
			if current.ThreadID != "" {
				detail["thread_id"] = current.ThreadID
			}
			appendJobLog(current, []appLogEntry{{Source: "bridge", Error: appDiagnostic{Code: "chat_cleanup_delegation_unconfirmed", Operation: "delegate_owned_job_cleanup", Summary: "PC durable cleanup acceptance is unconfirmed", Cause: "pc_cleanup_receipt_unconfirmed", Retryable: &retry, Context: detail, Expected: "exact_app_request_accepted_receipt", Actual: "delegation_unconfirmed", SuggestedFix: []string{"restore_pc_storage_and_connectivity", "bridge_retries_exact_cleanup_without_replaying_job"}}}})
		}
		if saveErr := s.saveApp(latest); saveErr != nil {
			return latest, saveErr
		}
		if err != nil {
			return latest, nil
		}
		state, job = latest, current
	}
	if job.ThreadID == "" {
		return state, nil
	} // A journal ACK alone does not prove an unknown creation was absent/deleted.
	app, snapshot := state.ID, *job
	parent := s.jobContext(p, app, job.ID)
	request := jobCreation(app, &snapshot, "delete")
	request.ThreadID = snapshot.ThreadID
	p.appMu.Unlock()
	ctx, cancel := context.WithTimeout(parent, 35*time.Second)
	outcome, callErr := s.native.Call(ctx, request)
	cancel()
	var result struct {
		Deleted bool `json:"deleted"`
	}
	confirmed := callErr == nil && outcome.State == "ok" && protocol.DecodeStrict(outcome.Result, &result) == nil && result.Deleted
	p.appMu.Lock()
	latest, err := s.loadApp(p)
	if err != nil {
		return state, err
	}
	current := latest.Jobs[snapshot.ID]
	if latest.ID != app || !sameJobOwner(current, &snapshot) || current.Delivered != snapshot.Delivered || current.ChatState == "deleted" {
		return latest, nil
	}
	if confirmed {
		current.ChatState = "deleted"
	} else {
		current.ChatState = "cleanup_pending"
		retry := true
		appendJobLog(current, []appLogEntry{{Source: "bridge", Error: appDiagnostic{Code: "chat_cleanup_unconfirmed", Operation: "delete_owned_job_chat", Summary: "Native chat deletion has not been confirmed", Cause: "native_deletion_unconfirmed", Retryable: &retry, Context: map[string]string{"thread_id": current.ThreadID}, Expected: "native_thread_deleted", Actual: "deletion_pending", SuggestedFix: []string{"restore_pc_native_connectivity", "bridge_retries_deletion_without_replaying_job"}}}})
	}
	return latest, s.saveApp(latest)
}

// Only the owning authenticated app can acknowledge its exact, independently verified published batch.
func (s *Service) acknowledgeDelivery(w http.ResponseWriter, r *http.Request, p *peer, state *appState, job *appJob) {
	var input struct {
		Files []protocol.File `json:"files"`
	}
	jobID := job.ID
	if !s.receiveAppBody(w, r, p, &state, "invalid_delivery_receipt", &input) {
		return
	}
	job = state.Jobs[jobID]
	if job == nil {
		s.appJobUnavailable(w, state, state.ID, jobID)
		return
	}
	if input.Files == nil {
		s.appError(w, 400, "invalid_delivery_receipt")
		return
	}
	if job.State != "completed" || job.Report == nil || job.Report.Status != "completed" || jobNeedsPC(job) || !reflect.DeepEqual(input.Files, job.Outputs) {
		s.appError(w, 409, "delivery_unverified")
		return
	}
	app := state.ID
	before, _ := json.Marshal(job)
	outputs := append([]protocol.File(nil), job.Outputs...)
	p.appMu.Unlock()
	verified := func() bool {
		defer p.appMu.Lock()
		for _, file := range outputs {
			actual, source, err := s.outbox.OpenVerifiedContext(r.Context(), p.identity, file.ID)
			if source != nil {
				source.Close()
			}
			if err != nil || actual != file {
				return false
			}
		}
		return r.Context().Err() == nil
	}()
	var err error
	state, job, err = s.reloadOutputOwner(p, app, jobID, before)
	if os.IsNotExist(err) {
		s.appJobUnavailable(w, state, app, jobID)
		return
	}
	if err == errOutputOwnerChanged {
		s.appError(w, 409, "delivery_unverified")
		return
	}
	if err != nil {
		s.appStoreError(w, err)
		return
	}
	if !verified {
		s.appError(w, 409, "delivery_unverified")
		return
	}
	if job.Delivered == 0 {
		job.Delivered = time.Now().UnixMilli()
		if err := s.saveApp(state); err != nil {
			s.appStoreError(w, err)
			return
		}
	}
	if job.ChatState != "deleted" {
		// Delivery creates work even when the coordinator had no pending retry timer.
		defer s.notifyJobs()
	}
	latest, err := s.cleanupJobChat(p, state, job)
	if err != nil {
		s.appStoreError(w, err)
		return
	}
	if current := latest.Jobs[job.ID]; latest.ID == app && current != nil {
		s.appReply(w, 200, appJobView(current))
		return
	}
	s.appJobUnavailable(w, latest, app, jobID)
}

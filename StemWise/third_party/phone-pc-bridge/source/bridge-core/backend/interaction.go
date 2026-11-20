package backend

import "time"

// Caller holds appMu. The worker reuses durable cancellation and exact retirement.
func (s *Service) requestInteractiveStop(state *appState, j *appJob) error {
	retry := false
	appendJobLog(j, []appLogEntry{{Source: "bridge", Error: appDiagnostic{Code: "native_interaction_required", Operation: "enforce_unattended_job", Summary: "Native turn requires unanswered client interaction", Cause: "unanswered_native_client_request", Retryable: &retry, Context: map[string]string{"component": "codex_app_server", "thread_id": j.ThreadID, "turn_id": j.TurnID}, Expected: "unattended_execution_without_client_requests", Actual: "native_client_request_pending", SuggestedFix: []string{"repair_the_developer_workflow_or_missing_execution_capability", "return_structured_failure_when_work_cannot_proceed_unattended", "observe_original_native_outcome_and_do_not_replay_side_effects"}}}})
	j.Updated = time.Now().UnixMilli()
	j.CancelRequested = j.Updated
	j.CancelReason, j.Code = "native_interaction_required", "native_interaction_required"
	return s.saveApp(state)
}

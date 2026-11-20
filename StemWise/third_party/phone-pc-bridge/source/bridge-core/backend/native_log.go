package backend

import (
	"encoding/json"
	"phonepcbridge/protocol"
	"strings"
)

// Only the authenticated adapter's committed native outcome can supply this fact.
func nativeJobLog(j *appJob, turn json.RawMessage) (string, []appLogEntry) {
	var record struct {
		ID       string          `json:"id"`
		Status   string          `json:"status"`
		Terminal bool            `json:"bridgeTerminalCommitted"`
		Error    json.RawMessage `json:"error"`
	}
	if json.Unmarshal(turn, &record) != nil || record.ID != j.TurnID || !record.Terminal || !contains([]string{"completed", "failed", "interrupted"}, record.Status) {
		return "", nil
	}
	if record.Status == "completed" {
		return record.Status, nil
	}
	var detail struct {
		Message    string          `json:"message"`
		Info       json.RawMessage `json:"codexErrorInfo"`
		Additional string          `json:"additionalDetails"`
	}
	decoded := len(record.Error) > 0 && string(record.Error) != "null" && json.Unmarshal(record.Error, &detail) == nil
	context := map[string]string{"component": "codex_app_server", "thread_id": j.ThreadID, "turn_id": j.TurnID, "native_status": record.Status}
	summary, cause := "Native turn ended without detailed error metadata", "native_error_details_unconfirmed"
	if decoded && strings.TrimSpace(detail.Message) != "" {
		summary = protocol.DiagnosticText(detail.Message, 1024)
		cause = "codex_native_failure"
	}
	if decoded && len(detail.Info) > 0 && string(detail.Info) != "null" {
		context["codex_error_info"] = protocol.DiagnosticText(string(detail.Info), 512)
	}
	if decoded && strings.TrimSpace(detail.Additional) != "" {
		context["additional_details"] = protocol.DiagnosticText(detail.Additional, 1024)
	}
	if record.Status == "interrupted" && cause == "native_error_details_unconfirmed" {
		summary = "Native turn interruption committed"
		cause = "native_interrupt_committed"
	}
	retry := false
	entry := appLogEntry{Source: "native", Error: appDiagnostic{Code: "native_turn_" + record.Status, Operation: "observe_native_turn", Summary: summary, Cause: cause, Retryable: &retry, Context: context, Expected: "completed", Actual: record.Status, SuggestedFix: []string{"inspect_recorded_native_error_metadata", "repair_the_reported_provider_or_execution_failure", "do_not_replay_unverified_side_effects"}}}
	return record.Status, []appLogEntry{entry}
}

func protocolJobLog(reason string) appLogEntry {
	retry := false
	if reason == "job_output_ownership_unconfirmed" {
		return appLogEntry{Source: "bridge", Error: appDiagnostic{Code: reason, Operation: "verify_job_output_ownership", Summary: "Committed developer report declares files without matching durable PC owner receipts", Cause: reason, Retryable: &retry, Context: map[string]string{"component": "pc_job_file_references"}, Expected: "exact_app_job_thread_output_receipts", Actual: "ownership_unconfirmed", SuggestedFix: []string{"queue_outputs_with_exact_authenticated_handoff_app_job_thread_flags_before_reporting", "preserve_original_job_and_never_replay_unknown_side_effects"}}}
	}
	return appLogEntry{Source: "bridge", Error: appDiagnostic{Code: "invalid_developer_protocol", Operation: "validate_developer_report", Summary: "Committed developer report failed schema validation", Cause: reason, Retryable: &retry, Context: map[string]string{"component": "bridge_report_validator"}, Expected: "bridgeJobReport_v2", Actual: reason, SuggestedFix: []string{"repair_the_developer_report_schema", "preserve_original_app_job_ids_and_committed_revisions"}}}
}

func uncommittedCompletionLog(j *appJob) appLogEntry {
	retry := true
	return appLogEntry{Source: "bridge", Error: appDiagnostic{
		Code: "native_terminal_unconfirmed", Operation: "validate_native_completion",
		Summary: "Native completion lacks an authoritative commitment", Cause: "native_completion_uncommitted", Retryable: &retry,
		Context:  map[string]string{"component": "codex_app_server", "thread_id": j.ThreadID, "turn_id": j.TurnID, "native_status": "completed"},
		Expected: "committed_native_completion", Actual: "native_completion_uncommitted",
		SuggestedFix: []string{"inspect_conflicting_native_outcomes", "restore_authoritative_history_for_the_original_turn", "do_not_resubmit_acknowledged_work"},
	}}
}

func missingFinalReportLog(actual string) appLogEntry {
	retry := false
	return appLogEntry{Source: "bridge", Error: appDiagnostic{Code: "developer_terminal_report_missing", Operation: "validate_final_report", Summary: "Native turn completed without a terminal developer outcome", Cause: "terminal_developer_report_unconfirmed", Retryable: &retry, Context: map[string]string{"component": "bridge_report_validator", "native_status": "completed"}, Expected: "completed_or_failed_developer_report", Actual: actual, SuggestedFix: []string{"repair_the_developer_workflow_to_emit_a_final_report", "independently_establish_the_task_outcome_before_followup_work", "do_not_replay_unverified_side_effects"}}}
}

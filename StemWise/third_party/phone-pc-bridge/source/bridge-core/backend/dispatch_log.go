package backend

import (
	"encoding/json"
	"phonepcbridge/protocol"
	"strconv"
	"strings"
)

func dispatchJobLog(j *appJob, operation string, reply protocol.AdapterReply, err error) appLogEntry {
	retry := j.State == "queued" && (operation == "select" || (operation == "observe_original_creation" && creationDidNotStart(reply, err)) || authRefusal(reply, err) != "")
	facts := map[string]string{"component": "pc_native_adapter", "adapter_operation": operation}
	if j.ThreadID != "" {
		facts["thread_id"] = j.ThreadID
	}
	if j.TurnID != "" {
		facts["turn_id"] = j.TurnID
	}
	cause, detail := "native_acknowledgment_unconfirmed", ""
	if err != nil {
		cause, detail = "adapter_response_unconfirmed", err.Error()
	} else {
		facts["adapter_state"] = reply.State
		if appCode.MatchString(reply.Code) {
			facts["adapter_code"] = reply.Code
		}
		if reply.State == "rejected" {
			cause = "pc_adapter_rejected"
		}
		if reply.Failure != nil {
			detail = reply.Failure.Message
			if reply.Failure.RPCCode != nil {
				facts["native_rpc_code"] = strconv.Itoa(*reply.Failure.RPCCode)
				if reply.State == "rejected" {
					cause = "native_rpc_rejected"
				}
			}
			if process := reply.Failure.Process; process != nil {
				facts["native_process_wait"] = process.WaitState
				if process.ExitCode != nil {
					facts["native_process_exit_code"] = strconv.Itoa(*process.ExitCode)
				}
				if process.Stderr != "" {
					stderr := process.Stderr
					for _, private := range []string{j.Input.Text, j.Input.Instructions} {
						if private != "" {
							stderr = strings.ReplaceAll(stderr, private, "[redacted input]")
						}
					}
					facts["native_process_stderr"] = protocol.DiagnosticText(stderr, 1024)
				}
				facts["native_process_stderr_truncated"] = strconv.FormatBool(process.StderrTruncated)
				if process.TerminationRequested {
					facts["native_process_termination_requested"] = "true"
				}
			}
		}
	}
	for _, private := range []string{j.Input.Text, j.Input.Instructions} {
		if private != "" {
			detail = strings.ReplaceAll(detail, private, "[redacted input]")
		}
	}
	if detail = protocol.DiagnosticText(detail, 1024); strings.TrimSpace(detail) != "" {
		facts["error_detail"] = detail
	}
	if j.Model != "" {
		facts["model"] = j.Model
	}
	if j.Effort != "" {
		facts["effort"] = j.Effort
	}
	if j.ServiceTier != "" {
		facts["service_tier"] = protocol.DiagnosticText(j.ServiceTier, 128)
	}
	entry := appLogEntry{Source: "bridge", Error: appDiagnostic{Code: j.Code, Operation: "native_" + operation, Summary: "Native job preparation or submission did not produce the required acknowledgment", Cause: cause, Retryable: &retry, Context: facts, Expected: "exact_native_thread_or_turn_acknowledgment", Actual: j.State, SuggestedFix: []string{"inspect_recorded_adapter_and_native_rpc_details", "repair_the_reported_chat_model_or_provider_failure", "observe_original_job_before_any_new_work_and_never_replay_unknown_submission"}}}
	// JSON escaping counts against the same diagnostic budget as ordinary bytes.
	for _, key := range []string{"error_detail", "native_process_stderr", "model", "effort", "service_tier"} {
		for {
			if len(facts) > 16 {
				delete(facts, "adapter_operation") // Error.Operation already carries the same operation.
			}
			encoded, _ := json.Marshal(entry.Error)
			if len(encoded) <= 4096 {
				return entry
			}
			value := facts[key]
			if value == "" {
				break
			}
			facts["diagnostic_detail_truncated"] = "true"
			if key == "native_process_stderr" {
				facts["native_process_stderr_truncated"] = "true"
			}
			if len(value) <= 32 {
				delete(facts, key)
				break
			}
			facts[key] = protocol.DiagnosticText(value, len(value)/2)
		}
	}
	return entry
}

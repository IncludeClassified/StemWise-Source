package backend

import (
	"phonepcbridge/protocol"
	"strings"
)

func inputDeliveryLog(id, cause string, err error) appLogEntry {
	retry := true
	entry := appLogEntry{Source: "bridge", Error: appDiagnostic{Code: "input_delivery_unconfirmed", Operation: "deliver_input_file", Summary: "The exact input receipt could not be established before native submission", Cause: cause, Retryable: &retry, Context: map[string]string{"component": "bridge_input_delivery", "transfer_id": id}, Expected: "exact_pi_bytes_and_confirmed_pc_receipt", Actual: "input_delivery_unconfirmed", SuggestedFix: []string{"inspect_the_recorded_receipt_stage_and_error_detail", "restore_original_input_bytes_under_the_same_transfer_id", "preserve_original_job_and_file_descriptors"}}}
	if err != nil {
		if detail := protocol.DiagnosticText(err.Error(), 1024); strings.TrimSpace(detail) != "" {
			entry.Error.Context["error_detail"] = detail
		}
	}
	return entry
}

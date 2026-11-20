package backend

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"phonepcbridge/files"
	"phonepcbridge/protocol"
	"strings"
)

func appResultID(app, job string) string {
	sum := sha256.Sum256([]byte(app + "\n" + job + "\nresult"))
	return hex.EncodeToString(sum[:16])
}
func outputClaimConflict(state *appState, j *appJob, offered []protocol.File) (string, string) {
	ids := map[string]bool{}
	for _, f := range offered {
		ids[f.ID] = true
	}
	if j.Report.Result != nil {
		id := appResultID(state.ID, j.ID)
		if ids[id] {
			return "output_id_conflict", id
		}
		ids[id] = true
	}
	for _, other := range state.Jobs {
		if other.ID == j.ID {
			continue
		}
		for _, f := range other.Outputs {
			if ids[f.ID] {
				return "artifact_belongs_to_another_job", f.ID
			}
		}
		if other.Report != nil {
			for _, f := range other.Report.Files {
				if ids[f.ID] {
					return "artifact_belongs_to_another_job", f.ID
				}
			}
			if other.Report.Result != nil {
				id := appResultID(state.ID, other.ID)
				if ids[id] {
					return "artifact_belongs_to_another_job", id
				}
			}
		}
	}
	return "", ""
}
func outputClaimLog(code, id string) appLogEntry {
	retry := false
	return appLogEntry{Source: "bridge", Error: appDiagnostic{Code: code, Operation: "validate_output_ownership", Summary: "Output transfer ID conflicts with a reserved or published job output", Cause: "output_id_reuse", Retryable: &retry, Context: map[string]string{"component": "bridge_output_verifier", "transfer_id": id}, Expected: "fresh_output_id_for_this_job", Actual: "claimed_output_id_reused", SuggestedFix: []string{"allocate_fresh_output_ids_per_job", "preserve_the_original_owning_jobs_verified_files"}}}
}

func outputBytesLog(id, cause string, err error) appLogEntry {
	retry := true
	entry := appLogEntry{Source: "bridge", Error: appDiagnostic{Code: "output_bytes_unverified", Operation: "verify_output_batch", Summary: "Declared output bytes could not be independently verified", Cause: cause, Retryable: &retry, Context: map[string]string{"component": "bridge_output_verifier", "transfer_id": id}, Expected: "all_declared_descriptors_match_actual_bytes", Actual: "output_batch_unconfirmed", SuggestedFix: []string{"restore_the_exact_producer_output_bytes", "preserve_original_transfer_ids_and_descriptors", "read_output_status_without_repeating_the_native_job"}}}
	if err != nil {
		if detail := protocol.DiagnosticText(err.Error(), 1024); strings.TrimSpace(detail) != "" {
			entry.Error.Context["error_detail"] = detail
		}
	}
	if errors.Is(err, files.ErrStorageCapacity) {
		entry.Error.Summary = "Pi storage reserve prevents receiving the output batch"
		entry.Error.SuggestedFix = []string{"wait_for_active_transfers_or_import_and_delete_finished_bridge_jobs", "preserve_original_transfer_ids_and_descriptors", "retry_output_observation_without_repeating_the_native_job"}
	}
	return entry
}

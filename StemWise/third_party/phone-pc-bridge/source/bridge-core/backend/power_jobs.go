package backend

import (
	"errors"
	"os"
)

type powerCounts struct {
	Jobs        int `json:"jobs"`
	Pending     int `json:"pending"`
	Undelivered int `json:"undelivered"`
	Cancelling  int `json:"cancelling"`
	Cleanup     int `json:"cleanup"`
	Issues      int `json:"issues"`
	Active      int `json:"active"`
}

type powerSnapshot struct {
	counts     powerCounts
	generation uint64
	issue      string
	unresolved string
	hasWork    bool
}

func powerCancellationLog(j *appJob, e appLogEntry) bool {
	return j.CancelReason == "user_request" && contains([]string{"interrupt_unconfirmed", "cancellation_retirement_unconfirmed", "native_turn_interrupted"}, e.Error.Code)
}

func powerLogIssue(j *appJob, e appLogEntry) bool {
	return !powerCancellationLog(j, e) && !(e.Error.Code == "creation_not_sent" && e.Error.Context["error_detail"] == "" && e.Error.Context["native_rpc_code"] == "" && e.Error.Context["native_process_wait"] == "")
}

func currentPowerIssue(j *appJob) string {
	if j.State == "failed" || j.State == "rejected" || (j.State == "unknown" && !j.liveDispatch) {
		return "job_" + j.State
	}
	if j.State == "interrupted" && j.CancelReason != "user_request" {
		return "job_interrupted"
	}
	if j.Report != nil && (j.Report.Status == "failed" || j.Report.Error != nil) {
		return "developer_report_error"
	}
	if contains([]string{"native_interaction_required", "invalid_developer_protocol", "native_terminal_unconfirmed", "job_output_ownership_unconfirmed", "output_bytes_unverified", "developer_terminal_report_missing", "job_observation_unavailable", "creation_observation_unavailable", "creation_outcome_unknown", "input_delivery_unconfirmed", "model_catalog_unavailable", "native_auth_required", "native_auth_unavailable", "native_auth_change_pending", "native_auth_source_unverified"}, j.Code) {
		return j.Code
	}
	return ""
}

func powerJobIssue(j *appJob, historical bool) string {
	if code := currentPowerIssue(j); code != "" {
		return code
	}
	if j.pendingPowerIssue {
		return "job_diagnostic_error"
	}
	if historical {
		for _, e := range j.Log {
			if !powerLogIssue(j, e) {
				continue
			}
			return "job_diagnostic_error"
		}
	}
	return ""
}

func userCancelledJob(j *appJob) bool {
	return j.CancelReason == "user_request" && j.CancelRetired > 0 && jobTerminal(j)
}

func cleanupEligibleJob(j *appJob) bool {
	return j.Delivered > 0 || userCancelledJob(j)
}

func jobChatCleanupPending(j *appJob) bool {
	return cleanupEligibleJob(j) && j.ChatState != "deleted" && (j.ThreadID != "" || j.ChatState != "")
}

func powerJobDrained(j *appJob) bool {
	if userCancelledJob(j) {
		return (j.ThreadID == "" && j.ChatState == "") || j.ChatState == "deleted"
	}
	return j.State == "completed" && j.Report != nil && j.Report.Status == "completed" && !jobNeedsPC(j) && j.Delivered > 0 && j.ChatState == "deleted"
}

// No power lock is held while collecting per-app durable state/work leases.
func (s *Service) collectPower() (powerSnapshot, error) {
	s.powerMu.Lock()
	result := powerSnapshot{generation: s.powerGeneration}
	power, powerErr := s.readPower()
	historical := powerErr != nil || power.ReleasedAt == 0
	result.counts.Active = s.powerStreams
	s.powerMu.Unlock()
	peers, err := s.storedPeers()
	seen := map[string]bool{}
	for _, p := range peers {
		seen[p.identity] = true
	}
	s.mu.Lock()
	for id, p := range s.peers {
		if !seen[id] {
			peers = append(peers, p)
			seen[id] = true
		}
	}
	s.mu.Unlock()
	for _, p := range peers {
		p.appMu.Lock()
		result.counts.Active += len(p.work)
		state, loadErr := s.loadApp(p)
		if os.IsNotExist(loadErr) {
			p.appMu.Unlock()
			continue
		}
		if loadErr != nil {
			p.appMu.Unlock()
			err = errors.Join(err, loadErr)
			continue
		}
		result.counts.Cleanup += len(state.Cleanup)
		for _, j := range state.Jobs {
			result.counts.Jobs++
			if !powerJobDrained(j) {
				result.counts.Pending++
				result.hasWork = true
			}
			if j.State == "completed" && j.Delivered == 0 {
				result.counts.Undelivered++
			}
			if j.CancelRequested > 0 && j.CancelRetired == 0 {
				result.counts.Cancelling++
			}
			if jobChatCleanupPending(j) {
				result.counts.Cleanup++
			}
			if code := powerJobIssue(j, historical); code != "" {
				result.counts.Issues++
				if result.issue == "" {
					result.issue = code
				}
			}
			if code := currentPowerIssue(j); code != "" && result.unresolved == "" {
				result.unresolved = code
			}
		}
		p.appMu.Unlock()
	}
	return result, err
}

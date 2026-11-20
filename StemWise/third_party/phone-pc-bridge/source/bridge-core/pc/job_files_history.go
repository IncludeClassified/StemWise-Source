package pc

import (
	"encoding/json"
	"errors"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

type jobFileReport struct {
	Protocol string          `json:"bridgeJobReport"`
	AppID    string          `json:"appId"`
	JobID    string          `json:"jobId"`
	Revision int64           `json:"revision"`
	Status   string          `json:"status"`
	Code     string          `json:"code"`
	Result   json.RawMessage `json:"result,omitempty"`
	Files    []protocol.File `json:"files,omitempty"`
	Error    json.RawMessage `json:"error,omitempty"`
}

// Only the authenticated adapter may attach ownership evidence; developer text remains unchanged.
func (s *Service) verifyJobHistoryFiles(peer, thread string, raw *json.RawMessage) error {
	var page map[string]json.RawMessage
	if json.Unmarshal(*raw, &page) != nil {
		return errors.New("native_history_invalid")
	}
	var turns []map[string]json.RawMessage
	if json.Unmarshal(page["data"], &turns) != nil {
		return errors.New("native_history_invalid")
	}
	s.creationMu.Lock()
	defer s.creationMu.Unlock()
	for _, turn := range turns {
		var terminal bool
		var status string
		_ = json.Unmarshal(turn["bridgeTerminalCommitted"], &terminal)
		_ = json.Unmarshal(turn["status"], &status)
		var items []map[string]json.RawMessage
		if len(turn["items"]) == 0 {
			continue
		}
		if json.Unmarshal(turn["items"], &items) != nil {
			return errors.New("native_history_invalid")
		}
		for _, item := range items {
			var kind, text string
			var committed bool
			_ = json.Unmarshal(item["type"], &kind)
			if kind != "agentMessage" {
				continue
			}
			delete(item, "bridgeOutputOwnershipUnconfirmed")
			_ = json.Unmarshal(item["text"], &text)
			_ = json.Unmarshal(item["bridgeReportCommitted"], &committed)
			if !committed && !(terminal && status == "completed") {
				continue
			}
			var report jobFileReport
			// Invalid reports remain the Pi's independent protocol-validation responsibility.
			if len(text) > 32768 || protocol.DecodeStrict([]byte(text), &report) != nil || report.Protocol != "v2" || len(report.Files) == 0 {
				continue
			}
			confirmed, err := s.reportFilesOwnedLocked(peer, thread, report)
			if err != nil {
				return err
			}
			if !confirmed {
				item["bridgeOutputOwnershipUnconfirmed"] = json.RawMessage(`true`)
			}
		}
		turn["items"], _ = json.Marshal(items)
	}
	page["data"], _ = json.Marshal(turns)
	*raw, _ = json.Marshal(page)
	return nil
}

func (s *Service) reportFilesOwnedLocked(peer, thread string, report jobFileReport) (bool, error) {
	_, hash, err := s.jobFileOwnerLocked(peer, report.AppID, report.JobID, thread)
	if err != nil {
		if errors.Is(err, files.ErrCorrupt) {
			return false, err
		}
		if errors.Is(err, ErrJobFileOwnership) {
			return false, nil
		}
		return false, err
	}
	for _, expected := range report.Files {
		if !protocol.ValidFile(expected) {
			return false, nil
		}
		f, err := s.creations.Info(peer, creationID(peer, report.AppID, report.JobID, "output:"+expected.ID))
		if errors.Is(err, files.ErrAbsent) {
			return false, nil
		}
		if err != nil {
			return false, err
		}
		ref, err := s.readJobFilesLocked(peer, f)
		if err != nil {
			return false, err
		}
		if ref.ThreadID != thread || ref.RequestHash != hash || len(ref.Files) != 1 || ref.Files[0] != expected {
			return false, nil
		}
	}
	return true, nil
}

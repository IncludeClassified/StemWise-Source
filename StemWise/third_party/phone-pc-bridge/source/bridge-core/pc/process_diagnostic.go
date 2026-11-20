package pc

import (
	"bytes"
	"encoding/json"
	"errors"
	"time"

	"phonepcbridge/codex"
	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

const processDiagnosticName = "process-diagnostic.json"

type localProcessReceipt struct {
	Version   string                       `json:"version"`
	Operation string                       `json:"operation"`
	AppID     string                       `json:"appId,omitempty"`
	JobID     string                       `json:"jobId,omitempty"`
	RequestID string                       `json:"requestId,omitempty"`
	ThreadID  string                       `json:"threadId,omitempty"`
	Process   codex.LocalProcessDiagnostic `json:"process"`
}

// The first valid local receipt owns the attempt's deadline and correlation.
// Later polls never rewrite or extend it; wire failures contain only its ID.
func (s *Service) captureAdapterFailure(peer string, input protocol.AdapterRequest, err error, privateNativeContext ...string) *protocol.AdapterFailure {
	failure := adapterFailure(err, input, privateNativeContext...)
	var process *codex.ProcessError
	if !errors.As(err, &process) {
		return failure
	}
	local := process.LocalDiagnostic()
	marker := "local_process_diagnostic_store_unavailable"
	if token.MatchString(local.AttemptID) {
		if s.saveProcessDiagnostic(peer, input, local) == nil {
			marker = "local_process_diagnostic=" + local.AttemptID
		} else {
			marker += " attempt_id=" + local.AttemptID
		}
	}
	failure.Message = protocol.DiagnosticText(failure.Message, 880) + "; " + marker
	return failure
}

func (s *Service) saveProcessDiagnostic(peer string, input protocol.AdapterRequest, local codex.LocalProcessDiagnostic) error {
	s.creationMu.Lock()
	defer s.creationMu.Unlock()
	if s.creations == nil || !token.MatchString(local.AttemptID) || local.PID < 0 || len(local.Stderr) > 8192 {
		return errors.New("local_process_diagnostic_invalid")
	}
	f, err := s.creations.Info(peer, local.AttemptID)
	if err == nil {
		_, err = s.readProcessDiagnostic(peer, f)
		return err
	}
	if !errors.Is(err, files.ErrAbsent) {
		return err
	}
	receipt := localProcessReceipt{Version: "v1", Operation: input.Operation, Process: local}
	if token.MatchString(input.AppID) {
		receipt.AppID = input.AppID
	}
	if token.MatchString(input.JobID) {
		receipt.JobID = input.JobID
	}
	if token.MatchString(input.RequestID) {
		receipt.RequestID = input.RequestID
	}
	if nodeID.MatchString(input.ThreadID) {
		receipt.ThreadID = input.ThreadID
	}
	raw, err := json.Marshal(receipt)
	if err != nil {
		return err
	}
	f, err = files.Describe(local.AttemptID, processDiagnosticName, raw)
	if err == nil {
		err = validateProcessDiagnostic(f, raw)
	}
	if err == nil {
		_, err = s.creations.Put(peer, f, bytes.NewReader(raw))
	}
	if err == nil {
		select {
		case s.retentionChanged <- struct{}{}:
		default:
		}
	}
	return err
}

// Caller holds creationMu. Only this named local record is retired by age.
func (s *Service) pruneProcessDiagnostic(now time.Time, peer string, f protocol.File) (time.Duration, error) {
	_, err := s.readProcessDiagnostic(peer, f)
	if err != nil {
		return time.Minute, err
	}
	accepted, err := s.creations.CommittedAt(peer, f.ID)
	if err != nil {
		return time.Minute, err
	}
	if until := accepted.Add(protocol.Retention).Sub(now); until > 0 {
		return until, nil
	}
	return time.Hour, s.creations.Remove(peer, f.ID)
}

func (s *Service) readProcessDiagnostic(peer string, f protocol.File) (localProcessReceipt, error) {
	if f.Name != processDiagnosticName || f.Size > 64<<10 {
		return localProcessReceipt{}, errors.New("local_process_diagnostic_invalid")
	}
	stored, raw, err := s.creations.Read(peer, f.ID)
	if err == nil {
		err = validateProcessDiagnostic(stored, raw)
	}
	var receipt localProcessReceipt
	if err == nil {
		err = protocol.DecodeStrict(raw, &receipt)
	}
	return receipt, err
}

func validateProcessDiagnostic(f protocol.File, raw []byte) error {
	var receipt localProcessReceipt
	if f.Name != processDiagnosticName || len(raw) > 64<<10 || protocol.DecodeStrict(raw, &receipt) != nil || receipt.Version != "v1" || receipt.Process.AttemptID != f.ID || !token.MatchString(f.ID) || receipt.Process.PID < 0 || len(receipt.Process.Stderr) > 8192 {
		return errors.New("local_process_diagnostic_invalid")
	}
	for _, id := range []string{receipt.AppID, receipt.JobID, receipt.RequestID} {
		if id != "" && !token.MatchString(id) {
			return errors.New("local_process_diagnostic_invalid")
		}
	}
	if receipt.ThreadID != "" && !nodeID.MatchString(receipt.ThreadID) {
		return errors.New("local_process_diagnostic_invalid")
	}
	// Diagnostic labels are bounded data, not permission to invoke an adapter operation.
	if len(receipt.Operation) > 64 || !nodeID.MatchString(receipt.Operation) {
		return errors.New("local_process_diagnostic_invalid")
	}
	return nil
}

package pc

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"path/filepath"
	"runtime"
	"strings"
	"unicode/utf8"

	"phonepcbridge/codex"
	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

type creationNative interface {
	StartThread(context.Context, string, string, string) (json.RawMessage, error)
	Read(context.Context, string) (json.RawMessage, error)
}
type creationError struct {
	code         string
	unknown      bool
	cause        error
	storagePhase string
}

func (e *creationError) Error() string { return e.code }
func (e *creationError) Unwrap() error { return e.cause }

type creationInput struct {
	AppID       string `json:"appId"`
	RequestID   string `json:"requestId"`
	CreatedAt   int64  `json:"createdAt"`
	Name        string `json:"name"`
	Model       string `json:"model"`
	Project     string `json:"project"`
	ServiceTier string `json:"serviceTier,omitempty"`
}
type creationReceipt struct {
	RequestHash string `json:"requestHash"`
	ThreadID    string `json:"threadId"`
}

func creationID(peer, app, request, kind string) string {
	sum := sha256.Sum256([]byte(peer + "\n" + app + "\n" + request + "\n" + kind))
	return hex.EncodeToString(sum[:16])
}
func validCreationControl(input protocol.AdapterRequest, optionalThread bool) bool {
	invalid := !token.MatchString(input.AppID) || !token.MatchString(input.RequestID) || input.CreatedAt <= 0 || strings.TrimSpace(input.Name) == "" || len(input.Name) > 255 || !utf8.ValidString(input.Name) || strings.ContainsFunc(input.Name, func(r rune) bool { return r < 32 || r == 127 }) || len(input.Model) > 128 || !utf8.ValidString(input.Model) || !protocol.ValidServiceTier(input.ServiceTier) || (input.ThreadID != "" && (!optionalThread || !nodeID.MatchString(input.ThreadID))) || input.Cursor != "" || input.Limit != 0 || input.Text != "" || input.Instructions != "" || input.JobID != "" || input.TurnID != "" || input.Files != nil || input.Effort != ""
	return !invalid
}

func (s *Service) createThread(ctx context.Context, peer string, input protocol.AdapterRequest, native creationNative) (json.RawMessage, error) {
	if !validCreationControl(input, false) {
		return nil, &creationError{code: "creation_request_invalid"}
	}
	if s.cfg.IntegrationDir == "" || s.creations == nil {
		return nil, &creationError{code: "creation_store_unavailable", unknown: true, storagePhase: "unconfigured"}
	}
	release, err := s.claimCreation(ctx, peer, input.AppID, input.RequestID)
	if err != nil {
		return nil, err
	}
	defer release()
	s.creationMu.Lock()
	defer s.creationMu.Unlock()
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	request := creationInput{input.AppID, input.RequestID, input.CreatedAt, input.Name, input.Model, s.cfg.IntegrationDir, input.ServiceTier}
	canonical, _ := json.Marshal(request)
	if pending, err := s.cleanupAuthorized(peer, input.AppID, input.RequestID); err != nil {
		return nil, err
	} else if pending {
		return nil, &creationError{code: "creation_deleted"}
	}
	requestID := creationID(peer, input.AppID, input.RequestID, "request")
	resultID := creationID(peer, input.AppID, input.RequestID, "result")
	if _, _, err := s.creations.Read(peer, creationID(peer, input.AppID, input.RequestID, "deleted")); err == nil {
		return nil, &creationError{code: "creation_deleted"}
	} else if !errors.Is(err, files.ErrAbsent) {
		return nil, &creationError{code: "creation_store_unavailable", unknown: true, cause: err, storagePhase: "read_deleted_marker"}
	}
	_, stored, err := s.creations.Read(peer, requestID)
	if err == nil && !bytes.Equal(stored, canonical) {
		return nil, &creationError{code: "creation_request_conflict"}
	}
	if err != nil && !errors.Is(err, files.ErrAbsent) {
		return nil, &creationError{code: "creation_store_unavailable", unknown: true, cause: err, storagePhase: "read_request"}
	}
	if err == nil {
		_, data, readErr := s.creations.Read(peer, resultID)
		if readErr != nil {
			return nil, &creationError{code: "creation_receipt_unconfirmed", unknown: true}
		}
		var receipt creationReceipt
		sum := sha256.Sum256(canonical)
		if protocol.DecodeStrict(data, &receipt) != nil || receipt.RequestHash != hex.EncodeToString(sum[:]) || !nodeID.MatchString(receipt.ThreadID) {
			return nil, &creationError{code: "creation_receipt_unconfirmed", unknown: true}
		}
		return s.verifyReservedCreation(ctx, peer, request, canonical, receipt, native)
	}
	if _, _, err := s.creations.Read(peer, resultID); err == nil || !errors.Is(err, files.ErrAbsent) {
		return nil, &creationError{code: "creation_receipt_unconfirmed", unknown: true}
	}
	// Only conclusive absence permits the manager to retry its immutable creation envelope.
	if input.Operation != "create" {
		return nil, &creationError{code: "creation_request_absent"}
	}
	// Unknown native outcomes retain their exact owner; bound that cleanup backlog before admission.
	records, unknown, scanErr := s.creationRecords(peer)
	if scanErr != nil || unknown {
		return nil, &creationError{code: "creation_store_unavailable", unknown: true, cause: scanErr, storagePhase: "list_records"}
	}
	owners := map[string]bool{}
	for _, record := range records {
		if record.Name != "creation-request.json" && record.Name != cleanupIntentName {
			continue
		}
		_, raw, err := s.creations.Read(peer, record.ID)
		var owner creationInput
		if record.Name == cleanupIntentName {
			var intent cleanupIntent
			if err == nil {
				err = protocol.DecodeStrict(raw, &intent)
			}
			owner = intent.Request
		} else if err == nil {
			err = protocol.DecodeStrict(raw, &owner)
		}
		if err != nil || !validCleanupOwner(owner) {
			return nil, &creationError{code: "creation_store_unavailable", unknown: true, cause: err, storagePhase: "list_records"}
		}
		if _, closedErr := s.readDeletedCreation(peer, owner); closedErr == nil {
			continue
		} else if !errors.Is(closedErr, files.ErrAbsent) {
			return nil, closedErr
		}
		owners[owner.AppID+"/"+owner.RequestID] = true
	}
	if len(owners) >= 128 {
		return nil, &creationError{code: "creation_capacity_reached"}
	}
	f, _ := files.Describe(requestID, "creation-request.json", canonical)
	if _, err := s.creations.Put(peer, f, bytes.NewReader(canonical)); err != nil {
		return nil, &creationError{code: "creation_store_unavailable", unknown: true, cause: err, storagePhase: "put_request"}
	}
	// The receiving PC owns this reservation's lifetime; sender time remains immutable identity.
	select {
	case s.retentionChanged <- struct{}{}:
	default:
	}
	s.creationMu.Unlock()
	raw, err := func() (json.RawMessage, error) {
		defer s.creationMu.Lock()
		return native.StartThread(ctx, request.Project, request.Model, request.ServiceTier)
	}()
	if code := definitiveCreationFailure(err); code != "" {
		if !s.sameCreation(peer, request, canonical, nil) {
			return nil, &creationError{code: "creation_receipt_unconfirmed", unknown: true, cause: err}
		}
		_, _, resultErr := s.creations.Read(peer, resultID)
		if !errors.Is(resultErr, files.ErrAbsent) {
			return nil, &creationError{code: "creation_receipt_unconfirmed", unknown: true, cause: err}
		}
		if removeErr := s.creations.Remove(peer, requestID); removeErr != nil {
			return nil, &creationError{code: "creation_store_unavailable", unknown: true, cause: removeErr, storagePhase: "release_request"}
		}
		return nil, &creationError{code: code, cause: err}
	}
	var result struct {
		Thread struct {
			ID string `json:"id"`
		} `json:"thread"`
	}
	if err != nil || json.Unmarshal(raw, &result) != nil || !nodeID.MatchString(result.Thread.ID) {
		return nil, &creationError{code: "creation_receipt_unconfirmed", unknown: true, cause: err}
	}
	if !s.sameCreation(peer, request, canonical, nil) {
		return nil, &creationError{code: "creation_receipt_unconfirmed", unknown: true}
	}
	sum := sha256.Sum256(canonical)
	data, _ := json.Marshal(creationReceipt{hex.EncodeToString(sum[:]), result.Thread.ID})
	f, _ = files.Describe(resultID, "creation-result.json", data)
	if _, err := s.creations.Put(peer, f, bytes.NewReader(data)); err != nil {
		return nil, &creationError{code: "creation_store_unavailable", unknown: true, cause: err, storagePhase: "put_result"}
	}
	return s.verifyReservedCreation(ctx, peer, request, canonical, creationReceipt{hex.EncodeToString(sum[:]), result.Thread.ID}, native)
}

// The first scoped certainty marker wins; a rejected later check cannot undo an attempted mutation.
func definitiveCreationFailure(err error) string {
	for err != nil {
		switch err.(type) {
		case *codex.OutcomeUnknown:
			return ""
		case *codex.RequestNotSent:
			return "creation_not_sent"
		case *codex.RPCError:
			return "creation_rejected"
		}
		err = errors.Unwrap(err)
	}
	return ""
}

// Caller owns the exact creation lease and creationMu; native reads release only the global mutex.
func (s *Service) verifyReservedCreation(ctx context.Context, peer string, request creationInput, canonical []byte, receipt creationReceipt, native creationNative) (json.RawMessage, error) {
	s.creationMu.Unlock()
	raw, err := func() (json.RawMessage, error) {
		defer s.creationMu.Lock()
		return verifyCreation(ctx, native, receipt.ThreadID, request.Project)
	}()
	if err == nil && !s.sameCreation(peer, request, canonical, &receipt) {
		return nil, &creationError{code: "creation_receipt_unconfirmed", unknown: true}
	}
	return raw, err
}

func (s *Service) sameCreation(peer string, request creationInput, canonical []byte, receipt *creationReceipt) bool {
	if cleanup, err := s.cleanupAuthorized(peer, request.AppID, request.RequestID); err != nil || cleanup {
		return false
	}
	return s.sameCreationForCleanup(peer, request, canonical, receipt)
}

func (s *Service) sameCreationForCleanup(peer string, request creationInput, canonical []byte, receipt *creationReceipt) bool {
	f, saved, err := s.creations.Read(peer, creationID(peer, request.AppID, request.RequestID, "request"))
	if err != nil || f.Name != "creation-request.json" || !bytes.Equal(saved, canonical) {
		return false
	}
	if _, _, err = s.creations.Read(peer, creationID(peer, request.AppID, request.RequestID, "deleted")); !errors.Is(err, files.ErrAbsent) {
		return false
	}
	if receipt != nil {
		_, saved, err = s.creations.Read(peer, creationID(peer, request.AppID, request.RequestID, "result"))
		var current creationReceipt
		if err != nil || protocol.DecodeStrict(saved, &current) != nil || current != *receipt {
			return false
		}
	}
	return true
}

func verifyCreation(ctx context.Context, native creationNative, id, project string) (json.RawMessage, error) {
	raw, err := native.Read(ctx, id)
	var result struct {
		Thread struct {
			ID  string `json:"id"`
			CWD string `json:"cwd"`
		} `json:"thread"`
	}
	if err != nil || json.Unmarshal(raw, &result) != nil || result.Thread.ID != id || !filepath.IsAbs(result.Thread.CWD) {
		return nil, &creationError{code: "creation_native_unconfirmed", unknown: true, cause: err}
	}
	actual, expected := filepath.Clean(result.Thread.CWD), filepath.Clean(project)
	if actual != expected && !(runtime.GOOS == "windows" && strings.EqualFold(actual, expected)) {
		return nil, &creationError{code: "creation_native_unconfirmed", unknown: true}
	}
	return json.Marshal(map[string]any{"thread": map[string]string{"id": id}})
}

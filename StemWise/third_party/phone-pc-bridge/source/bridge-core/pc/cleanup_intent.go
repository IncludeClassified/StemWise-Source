package pc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"path/filepath"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

const cleanupIntentName = "creation-cleanup.json"

type cleanupIntent struct {
	Request  creationInput `json:"request"`
	ThreadID string        `json:"threadId,omitempty"`
}

type deletedCreation struct {
	RequestHash string         `json:"requestHash"`
	ThreadID    string         `json:"threadId"`
	Request     *creationInput `json:"request"`
}

// Caller owns creationMu. A cleanup label is authority only for its exact persisted owner.
func (s *Service) readCleanupIntent(peer, app, job string) (cleanupIntent, error) {
	var intent cleanupIntent
	f, raw, err := s.creations.Read(peer, creationID(peer, app, job, "cleanup"))
	if err != nil {
		if !errors.Is(err, files.ErrAbsent) {
			return intent, &creationError{code: "creation_store_unavailable", unknown: true, cause: err, storagePhase: "read_cleanup_marker"}
		}
		return intent, err
	}
	if f.Name != cleanupIntentName || protocol.DecodeStrict(raw, &intent) != nil || intent.Request.AppID != app || intent.Request.RequestID != job || !validCleanupOwner(intent.Request) || (intent.ThreadID != "" && !nodeID.MatchString(intent.ThreadID)) {
		return intent, &creationError{code: "chat_ownership_unconfirmed", unknown: true}
	}
	return intent, nil
}

func validCleanupOwner(r creationInput) bool {
	input := protocol.AdapterRequest{AppID: r.AppID, RequestID: r.RequestID, CreatedAt: r.CreatedAt, Name: r.Name, Model: r.Model, ServiceTier: r.ServiceTier}
	return validCreationControl(input, false) && filepath.IsAbs(r.Project)
}

// Caller owns creationMu; malformed or unreadable journals never reopen admission.
func (s *Service) cleanupAuthorized(peer, app, job string) (bool, error) {
	if s.creations == nil {
		return false, &creationError{code: "creation_store_unavailable", unknown: true, storagePhase: "unconfigured"}
	}
	_, err := s.readCleanupIntent(peer, app, job)
	if errors.Is(err, files.ErrAbsent) {
		return false, nil
	}
	return err == nil, err
}

func (s *Service) authorizeCleanup(ctx context.Context, peer string, input protocol.AdapterRequest, knownThread bool) error {
	if !validCreationControl(input, true) || (knownThread && !nodeID.MatchString(input.ThreadID)) || s.creations == nil {
		return &creationError{code: "chat_ownership_unconfirmed"}
	}
	release, err := s.claimCreation(ctx, peer, input.AppID, input.RequestID)
	if err != nil {
		return err
	}
	defer release()
	s.creationMu.Lock()
	defer s.creationMu.Unlock()
	return s.authorizeCleanupLocked(peer, input, knownThread)
}

// A repeated discard observes its exact durable closure, never inventing
// physical deletion from the acceptance of a cleanup obligation.
func (s *Service) discardCreation(ctx context.Context, peer string, input protocol.AdapterRequest) (json.RawMessage, error) {
	if !validCreationControl(input, true) || s.creations == nil {
		return nil, &creationError{code: "chat_ownership_unconfirmed"}
	}
	release, err := s.claimCreation(ctx, peer, input.AppID, input.RequestID)
	if err != nil {
		return nil, err
	}
	defer release()
	s.creationMu.Lock()
	defer s.creationMu.Unlock()
	if err := s.authorizeCleanupLocked(peer, input, false); err != nil {
		return nil, err
	}
	receipt := struct {
		AppID     string `json:"appId"`
		RequestID string `json:"requestId"`
		Accepted  bool   `json:"accepted"`
		Deleted   bool   `json:"deleted,omitempty"`
	}{AppID: input.AppID, RequestID: input.RequestID, Accepted: true}
	_, raw, err := s.creations.Read(peer, creationID(peer, input.AppID, input.RequestID, "deleted"))
	if errors.Is(err, files.ErrAbsent) {
		return json.Marshal(receipt)
	}
	if err != nil {
		return nil, &creationError{code: "creation_store_unavailable", unknown: true, cause: err, storagePhase: "read_deleted_marker"}
	}
	var closed deletedCreation
	if protocol.DecodeStrict(raw, &closed) != nil || closed.Request == nil {
		return nil, &creationError{code: "chat_ownership_unconfirmed", unknown: true}
	}
	closed, err = s.readDeletedCreation(peer, *closed.Request)
	if err != nil {
		return nil, err
	}
	expected := creationInput{input.AppID, input.RequestID, input.CreatedAt, input.Name, input.Model, closed.Request.Project, input.ServiceTier}
	if *closed.Request != expected || (input.ThreadID != "" && input.ThreadID != closed.ThreadID) {
		return nil, &creationError{code: "chat_ownership_unconfirmed", unknown: true}
	}
	receipt.Deleted = true
	return json.Marshal(receipt)
}

// The exact lease remains owned through journal publication and any subsequent native deletion.
func (s *Service) authorizeCleanupLocked(peer string, input protocol.AdapterRequest, knownThread bool) error {
	app, job := input.AppID, input.RequestID
	intent, intentErr := s.readCleanupIntent(peer, app, job)
	if intentErr != nil && !errors.Is(intentErr, files.ErrAbsent) {
		return intentErr
	}
	var request creationInput
	requestFile, raw, requestErr := s.creations.Read(peer, creationID(peer, app, job, "request"))
	if requestErr == nil {
		if requestFile.Name != "creation-request.json" || protocol.DecodeStrict(raw, &request) != nil || !validCleanupOwner(request) {
			return &creationError{code: "chat_ownership_unconfirmed"}
		}
	} else if !errors.Is(requestErr, files.ErrAbsent) {
		return &creationError{code: "creation_store_unavailable", unknown: true, cause: requestErr, storagePhase: "read_request"}
	}
	var deleted deletedCreation
	deletedFile, deletedRaw, deletedErr := s.creations.Read(peer, creationID(peer, app, job, "deleted"))
	if deletedErr == nil {
		if deletedFile.Name != "creation-deleted.json" || protocol.DecodeStrict(deletedRaw, &deleted) != nil || deleted.Request == nil || !validCleanupOwner(*deleted.Request) || (deleted.ThreadID != "" && !nodeID.MatchString(deleted.ThreadID)) {
			return &creationError{code: "chat_ownership_unconfirmed", unknown: true}
		}
		if requestErr != nil {
			request = *deleted.Request
		}
	} else if !errors.Is(deletedErr, files.ErrAbsent) {
		return &creationError{code: "creation_store_unavailable", unknown: true, cause: deletedErr, storagePhase: "read_deleted_marker"}
	}
	if requestErr != nil && request.Project == "" {
		if intentErr == nil {
			request = intent.Request
		} else {
			request = creationInput{input.AppID, input.RequestID, input.CreatedAt, input.Name, input.Model, s.cfg.IntegrationDir, input.ServiceTier}
		}
	}
	expected := creationInput{input.AppID, input.RequestID, input.CreatedAt, input.Name, input.Model, request.Project, input.ServiceTier}
	if request != expected || !validCleanupOwner(expected) {
		return &creationError{code: "chat_ownership_unconfirmed"}
	}
	if intentErr == nil && (intent.Request != expected || (intent.ThreadID != "" && input.ThreadID != "" && intent.ThreadID != input.ThreadID)) {
		return &creationError{code: "chat_ownership_unconfirmed"}
	}
	hash := digestJSON(expected)
	if deletedErr == nil {
		if deleted.RequestHash != hash || *deleted.Request != expected || (input.ThreadID != "" && deleted.ThreadID != input.ThreadID) || (knownThread && !nodeID.MatchString(deleted.ThreadID)) {
			return &creationError{code: "chat_ownership_unconfirmed"}
		}
		return nil
	}
	resultFile, resultRaw, resultErr := s.creations.Read(peer, creationID(peer, app, job, "result"))
	if resultErr == nil {
		var receipt creationReceipt
		if resultFile.Name != "creation-result.json" || requestErr != nil || protocol.DecodeStrict(resultRaw, &receipt) != nil || receipt.RequestHash != hash || !nodeID.MatchString(receipt.ThreadID) || (input.ThreadID != "" && receipt.ThreadID != input.ThreadID) {
			return &creationError{code: "chat_ownership_unconfirmed"}
		}
	} else if !errors.Is(resultErr, files.ErrAbsent) {
		return &creationError{code: "creation_store_unavailable", unknown: true, cause: resultErr, storagePhase: "read_result"}
	} else if knownThread {
		return &creationError{code: "chat_ownership_unconfirmed"}
	}
	if intentErr == nil {
		return nil
	}
	intent = cleanupIntent{Request: expected, ThreadID: input.ThreadID}
	raw, _ = json.Marshal(intent)
	f, _ := files.Describe(creationID(peer, app, job, "cleanup"), cleanupIntentName, raw)
	if _, err := s.creations.Put(peer, f, bytes.NewReader(raw)); err != nil {
		return &creationError{code: "creation_store_unavailable", unknown: true, cause: err, storagePhase: "put_cleanup_marker"}
	}
	select {
	case s.retentionChanged <- struct{}{}:
	default:
	}
	return nil
}

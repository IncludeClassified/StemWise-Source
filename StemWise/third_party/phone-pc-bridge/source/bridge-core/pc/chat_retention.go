package pc

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"sort"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

type deletionNative interface {
	DeleteThread(context.Context, string) error
}

type CleanupStatus struct {
	State              string        `json:"state"`
	Code               string        `json:"code"`
	PendingRequests    int           `json:"pendingRequests"`
	ObservedAt         int64         `json:"observedAt"`
	UnresolvedHandoffs int           `json:"unresolvedHandoffs"`
	OldestHandoff      *handoffOwner `json:"oldestHandoff,omitempty"`
	HandoffCode        string        `json:"handoffCode"`
	HandoffObservedAt  int64         `json:"handoffObservedAt"`
}

func (s *Service) cleanupSnapshot() CleanupStatus {
	s.mu.Lock()
	status := s.chatCleanup
	if s.fileRetentionCode != "" {
		status.State, status.Code = "unconfirmed", s.fileRetentionCode
	}
	s.mu.Unlock()
	if status.State == "" {
		status.State = "checking"
	}
	status.UnresolvedHandoffs, status.OldestHandoff, status.HandoffCode, status.HandoffObservedAt = s.handoffSnapshot()
	return status
}

// Ownership comes from the pre-creation reservation and native receipt, never a caller's ID or chat name.
func (s *Service) deleteCreation(ctx context.Context, peer string, input protocol.AdapterRequest, native deletionNative) error {
	if !validCreationControl(input, true) || !nodeID.MatchString(input.ThreadID) {
		return &creationError{code: "chat_ownership_unconfirmed"}
	}
	release, err := s.claimCreation(ctx, peer, input.AppID, input.RequestID)
	if err != nil {
		return err
	}
	defer release()
	s.creationMu.Lock()
	defer s.creationMu.Unlock()
	return s.deleteCreationOwned(ctx, peer, input, native)
}

// Journal first; the exact owner remains leased while native I/O releases the global mutex.
func (s *Service) deleteCreationOwned(ctx context.Context, peer string, input protocol.AdapterRequest, native deletionNative) error {
	if err := s.authorizeCleanupLocked(peer, input, true); err != nil {
		return err
	}
	// A self-contained deletion checkpoint survives an interrupted receipt purge.
	_, raw, closedErr := s.creations.Read(peer, creationID(peer, input.AppID, input.RequestID, "deleted"))
	if closedErr == nil {
		var closed deletedCreation
		if protocol.DecodeStrict(raw, &closed) != nil || closed.Request == nil {
			return &creationError{code: "chat_ownership_unconfirmed", unknown: true}
		}
		request := *closed.Request
		closed, err := s.readDeletedCreation(peer, request)
		if err != nil || closed.ThreadID != input.ThreadID {
			return &creationError{code: "chat_ownership_unconfirmed", unknown: true, cause: err}
		}
		return s.finishClosedCleanup(peer, request, closed.ThreadID)
	} else if !errors.Is(closedErr, files.ErrAbsent) {
		return &creationError{code: "creation_store_unavailable", unknown: true, cause: closedErr, storagePhase: "read_deleted_marker"}
	}
	request, canonical, expected, err := s.ownedCreationEnvelope(peer, input, false)
	if err != nil {
		return err
	}
	if !s.sameCreationForCleanup(peer, request, canonical, &expected) {
		return &creationError{code: "chat_ownership_unconfirmed", unknown: true}
	}
	s.creationMu.Unlock()
	err = func() error { defer s.creationMu.Lock(); return native.DeleteThread(ctx, expected.ThreadID) }()
	if err != nil {
		return &creationError{code: "chat_cleanup_unconfirmed", unknown: true, cause: err}
	}
	if !s.sameCreationForCleanup(peer, request, canonical, &expected) {
		return &creationError{code: "chat_ownership_unconfirmed", unknown: true}
	}
	if err := s.writeDeletedCreation(peer, request, expected.ThreadID); err != nil {
		return err
	}
	return s.finishClosedCleanup(peer, request, expected.ThreadID)
}

func (s *Service) readDeletedCreation(peer string, request creationInput) (deletedCreation, error) {
	var closed deletedCreation
	f, raw, err := s.creations.Read(peer, creationID(peer, request.AppID, request.RequestID, "deleted"))
	if err != nil {
		if !errors.Is(err, files.ErrAbsent) {
			return closed, &creationError{code: "creation_store_unavailable", unknown: true, cause: err, storagePhase: "read_deleted_marker"}
		}
		return closed, err
	}
	if f.Name != "creation-deleted.json" || protocol.DecodeStrict(raw, &closed) != nil || closed.Request == nil || !validCleanupOwner(*closed.Request) || closed.RequestHash != digestJSON(request) || (closed.ThreadID != "" && !nodeID.MatchString(closed.ThreadID)) || *closed.Request != request {
		return closed, &creationError{code: "chat_ownership_unconfirmed", unknown: true}
	}
	return closed, nil
}

func (s *Service) writeDeletedCreation(peer string, request creationInput, thread string) error {
	closed := deletedCreation{RequestHash: digestJSON(request), ThreadID: thread, Request: &request}
	raw, _ := json.Marshal(closed)
	f, _ := files.Describe(creationID(peer, request.AppID, request.RequestID, "deleted"), "creation-deleted.json", raw)
	if _, err := s.creations.Put(peer, f, bytes.NewReader(raw)); err != nil {
		return &creationError{code: "creation_store_unavailable", unknown: true, cause: err, storagePhase: "put_deleted_marker"}
	}
	return nil
}

func (s *Service) finishClosedCleanup(peer string, request creationInput, thread string) error {
	if thread != "" {
		if err := s.cleanDeletedHandoffs(peer, request.AppID, request.RequestID, thread); err != nil {
			return err
		}
	}
	return s.releaseJobFilesLocked(peer, request.AppID, request.RequestID)
}

func (s *Service) ownedCreationEnvelope(peer string, input protocol.AdapterRequest, requireTurn bool) (creationInput, []byte, creationReceipt, error) {
	invalid := s.creations == nil || !token.MatchString(input.AppID) || !token.MatchString(input.RequestID) || !nodeID.MatchString(input.ThreadID) || input.CreatedAt <= 0 || !protocol.ValidServiceTier(input.ServiceTier) || input.JobID != "" || input.Text != "" || input.Instructions != "" || input.Files != nil || input.Cursor != "" || input.Limit != 0 || input.Effort != ""
	invalid = invalid || (requireTurn && !nodeID.MatchString(input.TurnID)) || (!requireTurn && input.TurnID != "")
	var request creationInput
	if invalid {
		return request, nil, creationReceipt{}, &creationError{code: "chat_ownership_unconfirmed"}
	}
	_, saved, err := s.creations.Read(peer, creationID(peer, input.AppID, input.RequestID, "request"))
	if err != nil || protocol.DecodeStrict(saved, &request) != nil {
		return request, nil, creationReceipt{}, &creationError{code: "chat_ownership_unconfirmed"}
	}
	canonical, _ := json.Marshal(creationInput{input.AppID, input.RequestID, input.CreatedAt, input.Name, input.Model, request.Project, input.ServiceTier})
	if !bytes.Equal(saved, canonical) {
		return request, nil, creationReceipt{}, &creationError{code: "chat_ownership_unconfirmed"}
	}
	sum := sha256.Sum256(canonical)
	return request, canonical, creationReceipt{hex.EncodeToString(sum[:]), input.ThreadID}, nil
}

// Retain only cleanup obligations while the PC/native service is unavailable; never discard ownership first.
func (s *Service) pruneCreations(now time.Time) (time.Duration, error) {
	return s.pruneOwnedChats(now, func(ctx context.Context, peer string) (deletionNative, error) {
		native, _, err := s.connection(ctx, s.peers[peer])
		return native, err
	})
}

func (s *Service) pruneOwnedChats(now time.Time, connect func(context.Context, string) (deletionNative, error)) (time.Duration, error) {
	return s.pruneOwnedChatsCollect(now, connect, s.creationRecords)
}

func (s *Service) pruneOwnedChatsCollect(now time.Time, connect func(context.Context, string) (deletionNative, error), collect func(string) ([]protocol.File, bool, error)) (time.Duration, error) {
	if s.creations == nil {
		return time.Hour, nil
	}
	next, passErr, pending := time.Hour, error(nil), 0
	type cleanupCandidate struct {
		peer   string
		intent cleanupIntent
	}
	allCandidates := []cleanupCandidate{}
	defer func() {
		status := CleanupStatus{State: "current", PendingRequests: pending, ObservedAt: now.UnixMilli()}
		if passErr != nil {
			status.State, status.Code = "unconfirmed", "chat_cleanup_unavailable"
			var failure *creationError
			if errors.As(passErr, &failure) {
				status.Code = failure.code
			}
		}
		s.mu.Lock()
		s.chatCleanup = status
		s.mu.Unlock()
	}()
	for _, peer := range s.cfg.AllowedPeers {
		records, _, err := collect(peer)
		passErr = errors.Join(passErr, err)
		candidates := map[string]cleanupIntent{}
		aged := []creationInput{}
		for _, f := range records {
			switch f.Name {
			case processDiagnosticName:
				delay, err := s.pruneProcessDiagnostic(now, peer, f)
				passErr = errors.Join(passErr, err)
				if delay < next {
					next = delay
				}
			case cleanupIntentName:
				_, raw, err := s.creations.Read(peer, f.ID)
				var intent cleanupIntent
				if err != nil || protocol.DecodeStrict(raw, &intent) != nil || !validCleanupOwner(intent.Request) || f.ID != creationID(peer, intent.Request.AppID, intent.Request.RequestID, "cleanup") || (intent.ThreadID != "" && !nodeID.MatchString(intent.ThreadID)) {
					passErr = errors.Join(passErr, errors.New("creation_store_invalid"))
					continue
				}
				candidates[intent.Request.AppID+"/"+intent.Request.RequestID] = intent
			case "creation-deleted.json":
				_, raw, err := s.creations.Read(peer, f.ID)
				var closed deletedCreation
				if err != nil || protocol.DecodeStrict(raw, &closed) != nil || closed.Request == nil {
					pending++
					passErr = errors.Join(passErr, &creationError{code: "chat_ownership_unconfirmed", unknown: true, cause: err})
					continue
				}
				request := *closed.Request
				if !validCleanupOwner(request) || f.ID != creationID(peer, request.AppID, request.RequestID, "deleted") || closed.RequestHash != digestJSON(request) || (closed.ThreadID != "" && !nodeID.MatchString(closed.ThreadID)) {
					pending++
					passErr = errors.Join(passErr, &creationError{code: "chat_ownership_unconfirmed", unknown: true})
					continue
				}
				key := request.AppID + "/" + request.RequestID
				if _, exists := candidates[key]; !exists {
					candidates[key] = cleanupIntent{Request: request, ThreadID: closed.ThreadID}
				}
			case "creation-request.json":
				_, raw, err := s.creations.Read(peer, f.ID)
				var request creationInput
				if err != nil || protocol.DecodeStrict(raw, &request) != nil || !validCleanupOwner(request) || f.ID != creationID(peer, request.AppID, request.RequestID, "request") {
					passErr = errors.Join(passErr, errors.New("creation_store_invalid"))
					continue
				}
				if _, err := s.readDeletedCreation(peer, request); err == nil {
					continue
				} else if !errors.Is(err, files.ErrAbsent) {
					passErr = errors.Join(passErr, err)
					continue
				}
				accepted, err := s.creations.CommittedAt(peer, f.ID)
				if err != nil {
					passErr = errors.Join(passErr, err)
					continue
				}
				until := accepted.Add(protocol.Retention).Sub(now)
				if until <= 0 {
					aged = append(aged, request)
				} else if until < next {
					next = until
				}
			}
		}
		for _, request := range aged {
			intent, err := s.expireCreationOwner(now, peer, request)
			if err != nil {
				passErr = errors.Join(passErr, err)
				pending++
				continue
			}
			candidates[request.AppID+"/"+request.RequestID] = intent
		}
		for _, intent := range candidates {
			allCandidates = append(allCandidates, cleanupCandidate{peer, intent})
		}
	}
	// Retire every local payload before the first potentially slow native operation.
	for _, candidate := range allCandidates {
		err := s.purgeCleanupPayload(candidate.peer, candidate.intent)
		passErr = errors.Join(passErr, err)
	}
	// Collection and local expiry never own the native-operation lease. Acquire
	// it before exact owner leases only when there is actual native cleanup work.
	nativeCtx, cancelNative := context.WithTimeout(s.ctx, 30*time.Second)
	defer cancelNative()
	type nativeAttempt struct {
		native deletionNative
		err    error
	}
	attempts := map[string]nativeAttempt{}
	connectOnce := func(_ context.Context, peer string) (deletionNative, error) {
		if err := nativeCtx.Err(); err != nil {
			return nil, err
		}
		if attempt, ok := attempts[peer]; ok {
			return attempt.native, attempt.err
		}
		native, err := connect(nativeCtx, peer)
		attempts[peer] = nativeAttempt{native, err}
		return native, err
	}
	if len(allCandidates) != 0 {
		release, err := s.claimNative(nativeCtx)
		if err != nil {
			pending += len(allCandidates)
			passErr = errors.Join(passErr, err)
			return time.Minute, passErr
		}
		defer release()
	}
	key := func(candidate cleanupCandidate) string {
		return candidate.peer + "/" + candidate.intent.Request.AppID + "/" + candidate.intent.Request.RequestID
	}
	sort.Slice(allCandidates, func(i, j int) bool { return key(allCandidates[i]) < key(allCandidates[j]) })
	s.creationMu.Lock()
	after := s.cleanupAfter
	s.creationMu.Unlock()
	start := sort.Search(len(allCandidates), func(i int) bool { return key(allCandidates[i]) > after })
	for offset := 0; offset < len(allCandidates); offset++ {
		candidate := allCandidates[(start+offset)%len(allCandidates)]
		unresolved, delay, err := s.pruneCleanupIntent(now, candidate.peer, candidate.intent, nativeCtx, func(ctx context.Context, peer string) (deletionNative, error) {
			if nativeCtx.Err() == nil {
				s.creationMu.Lock()
				s.cleanupAfter = key(candidate)
				s.creationMu.Unlock()
			}
			return connectOnce(ctx, peer)
		})
		if unresolved {
			pending++
		}
		if delay < next {
			next = delay
		}
		passErr = errors.Join(passErr, err)
	}
	if passErr != nil {
		return time.Minute, passErr
	}
	return next, nil
}

// Ownership decisions serialize with creationMu, or revalidate their mutation
// generation after collection. Structural diagnostics never prove absence.
func (s *Service) creationRecords(peer string) ([]protocol.File, bool, error) {
	records := []protocol.File{}
	cursor := ""
	unknown := false
	var passErr error
	for {
		page, err := s.creations.List(peer, cursor, 100)
		if err != nil {
			return records, true, errors.Join(passErr, err)
		}
		if page.DiagnosticCount != 0 {
			unknown = true
			passErr = errors.Join(passErr, &creationError{code: "creation_store_unavailable", unknown: true, storagePhase: "list_records"})
		}
		records = append(records, page.Files...)
		if page.NextCursor == "" {
			return records, unknown, passErr
		}
		cursor = page.NextCursor
	}
}

func (s *Service) pruneCleanupIntent(now time.Time, peer string, intent cleanupIntent, nativeCtx context.Context, connect func(context.Context, string) (deletionNative, error)) (unresolved bool, delay time.Duration, passErr error) {
	ctx, cancel := context.WithTimeout(s.ctx, 30*time.Second)
	defer cancel()
	request := intent.Request
	release, err := s.claimCreation(ctx, peer, request.AppID, request.RequestID)
	if err != nil {
		return true, time.Minute, err
	}
	defer release()
	s.creationMu.Lock()
	defer s.creationMu.Unlock()
	current, closed, ownerErr := s.cleanupOwnerLocked(peer, intent)
	if ownerErr != nil {
		return true, time.Minute, ownerErr
	}
	journalAbsent, closedAbsent := current.Request.AppID == "", closed.Request == nil
	// Cleanup admission blocks further owned uploads even while the native runtime is offline.
	thread := current.ThreadID
	if !closedAbsent {
		thread = closed.ThreadID
	}
	localErr := errors.Join(s.releaseJobFilesLocked(peer, request.AppID, request.RequestID), s.cleanDeletedHandoffs(peer, request.AppID, request.RequestID, thread))
	defer func() {
		if localErr != nil {
			unresolved, delay, passErr = true, time.Minute, errors.Join(localErr, passErr)
		}
	}()
	filesReleased := false
	if closedAbsent {
		if journalAbsent {
			return true, time.Minute, &creationError{code: "chat_ownership_unconfirmed", unknown: true}
		}
		_, canonical, requestErr := s.creations.Read(peer, creationID(peer, request.AppID, request.RequestID, "request"))
		_, raw, resultErr := s.creations.Read(peer, creationID(peer, request.AppID, request.RequestID, "result"))
		if errors.Is(requestErr, files.ErrAbsent) && errors.Is(resultErr, files.ErrAbsent) && current.ThreadID == "" {
			if err := s.writeDeletedCreation(peer, request, ""); err != nil {
				return true, time.Minute, err
			}
		} else {
			expected, _ := json.Marshal(request)
			var receipt creationReceipt
			if requestErr != nil || !bytes.Equal(canonical, expected) || resultErr != nil || protocol.DecodeStrict(raw, &receipt) != nil || receipt.RequestHash != digestJSON(request) || !nodeID.MatchString(receipt.ThreadID) || (current.ThreadID != "" && current.ThreadID != receipt.ThreadID) {
				return true, time.Minute, &creationError{code: "creation_receipt_unconfirmed", unknown: true}
			}
			s.creationMu.Unlock()
			native, err := func() (deletionNative, error) { defer s.creationMu.Lock(); return connect(ctx, peer) }()
			if err != nil {
				return true, time.Minute, err
			}
			input := protocol.AdapterRequest{Operation: "delete", AppID: request.AppID, RequestID: request.RequestID, CreatedAt: request.CreatedAt, Name: request.Name, Model: request.Model, ServiceTier: request.ServiceTier, ThreadID: receipt.ThreadID}
			if err := s.deleteCreationOwned(nativeCtx, peer, input, native); err != nil {
				return true, time.Minute, errors.Join(localErr, err)
			}
			filesReleased = true
		}
		var closedErr error
		closed, closedErr = s.readDeletedCreation(peer, request)
		if closedErr != nil {
			return true, time.Minute, closedErr
		}
	}
	if !filesReleased {
		if err := s.finishClosedCleanup(peer, request, closed.ThreadID); err != nil {
			return true, time.Minute, errors.Join(localErr, err)
		}
	}
	if localErr != nil {
		return true, time.Minute, localErr
	}
	accepted, err := s.creations.CommittedAt(peer, creationID(peer, request.AppID, request.RequestID, "deleted"))
	if err != nil {
		return true, time.Minute, err
	}
	if until := accepted.Add(protocol.Retention).Sub(now); until > 0 {
		return false, until, nil
	}
	// The self-contained closure checkpoint is removed last, so an interrupted purge can resume.
	for _, kind := range []string{"result", "request", "cleanup", "deleted"} {
		if err := s.creations.Remove(peer, creationID(peer, request.AppID, request.RequestID, kind)); err != nil {
			return true, time.Minute, err
		}
	}
	return false, time.Hour, nil
}

func (s *Service) cleanupOwnerLocked(peer string, intent cleanupIntent) (cleanupIntent, deletedCreation, error) {
	request := intent.Request
	current, journalErr := s.readCleanupIntent(peer, request.AppID, request.RequestID)
	if journalErr == nil && current.Request != request {
		return current, deletedCreation{}, &creationError{code: "chat_ownership_unconfirmed", unknown: true}
	}
	if journalErr != nil && !errors.Is(journalErr, files.ErrAbsent) {
		return current, deletedCreation{}, journalErr
	}
	closed, closedErr := s.readDeletedCreation(peer, request)
	if closedErr != nil && !errors.Is(closedErr, files.ErrAbsent) {
		return current, closed, closedErr
	}
	if journalErr != nil && closedErr != nil {
		return current, closed, &creationError{code: "chat_ownership_unconfirmed", unknown: true}
	}
	return current, closed, nil
}

func (s *Service) purgeCleanupPayload(peer string, intent cleanupIntent) error {
	ctx, cancel := context.WithTimeout(s.ctx, 30*time.Second)
	defer cancel()
	release, err := s.claimCreation(ctx, peer, intent.Request.AppID, intent.Request.RequestID)
	if err != nil {
		return err
	}
	defer release()
	s.creationMu.Lock()
	defer s.creationMu.Unlock()
	current, closed, err := s.cleanupOwnerLocked(peer, intent)
	if err != nil {
		return err
	}
	thread := current.ThreadID
	if closed.Request != nil {
		thread = closed.ThreadID
	}
	return errors.Join(s.releaseJobFilesLocked(peer, intent.Request.AppID, intent.Request.RequestID), s.cleanDeletedHandoffs(peer, intent.Request.AppID, intent.Request.RequestID, thread))
}

// Receiver metadata anchors the deadline for existing owners; retries never reset it.
func (s *Service) expireCreationOwner(now time.Time, peer string, request creationInput) (cleanupIntent, error) {
	ctx, cancel := context.WithTimeout(s.ctx, 30*time.Second)
	defer cancel()
	release, err := s.claimCreation(ctx, peer, request.AppID, request.RequestID)
	if err != nil {
		return cleanupIntent{}, err
	}
	defer release()
	s.creationMu.Lock()
	defer s.creationMu.Unlock()
	f, raw, err := s.creations.Read(peer, creationID(peer, request.AppID, request.RequestID, "request"))
	expected, _ := json.Marshal(request)
	if err != nil || f.Name != "creation-request.json" || !bytes.Equal(raw, expected) {
		return cleanupIntent{}, &creationError{code: "chat_ownership_unconfirmed", unknown: true, cause: err}
	}
	accepted, err := s.creations.CommittedAt(peer, f.ID)
	if err != nil {
		return cleanupIntent{}, err
	}
	if accepted.Add(protocol.Retention).After(now) {
		return cleanupIntent{}, &creationError{code: "chat_ownership_unconfirmed", unknown: true}
	}
	input := protocol.AdapterRequest{Operation: "discard", AppID: request.AppID, RequestID: request.RequestID, CreatedAt: request.CreatedAt, Name: request.Name, Model: request.Model, ServiceTier: request.ServiceTier}
	if err := s.authorizeCleanupLocked(peer, input, false); err != nil {
		return cleanupIntent{}, err
	}
	return s.readCleanupIntent(peer, request.AppID, request.RequestID)
}

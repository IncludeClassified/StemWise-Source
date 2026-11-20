package pc

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"sort"
	"sync"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

const PowerProtocol = "phone-pc-bridge-power-v1"
const PowerShutdownAccepted = "power_shutdown_accepted"

type PowerRequest struct {
	Nonce        string `json:"nonce"`
	Operation    string `json:"operation"`
	CycleID      string `json:"cycleId,omitempty"`
	TransitionID string `json:"transitionId,omitempty"`
}

type PowerObservation struct {
	BootID       string `json:"bootId"`
	TransitionID string `json:"transitionId"`
	UptimeMS     int64  `json:"uptimeMs"`
	WakeAgeMS    *int64 `json:"wakeAgeMs"`
}

func (o PowerObservation) Valid() bool {
	return token.MatchString(o.BootID) && validDigest(o.TransitionID) && o.UptimeMS >= 0 && (o.WakeAgeMS == nil || (*o.WakeAgeMS >= 0 && *o.WakeAgeMS <= o.UptimeMS))
}

type PowerReply struct {
	Protocol                   string            `json:"protocol"`
	PCIdentity                 string            `json:"pcIdentity"`
	Nonce                      string            `json:"nonce"`
	State                      string            `json:"state"`
	Code                       string            `json:"code,omitempty"`
	Observation                *PowerObservation `json:"observation,omitempty"`
	CycleID                    string            `json:"cycleId,omitempty"`
	ShutdownState              string            `json:"shutdownState,omitempty"`
	ShutdownPrivilegeAvailable *bool             `json:"shutdownPrivilegeAvailable,omitempty"`
}

type powerSystem interface {
	Observe(context.Context) (PowerObservation, error)
	Shutdown(context.Context) error
}

type powerPrivilegeObserver interface {
	ShutdownPrivilege(context.Context) (bool, error)
}

var errPowerBlocked = errors.New("power_shutdown_pending")

// All production native/file entry points retain this shared admission lease
// through their actual body/side effect. Shutdown never queues a writer lock.
func (s *Service) beginPowerWork() (func(), error) {
	if !s.powerFence.TryRLock() {
		return nil, errors.New("power_busy")
	}
	if s.powerSealed {
		s.powerFence.RUnlock()
		return nil, errPowerBlocked
	}
	var once sync.Once
	return func() { once.Do(s.powerFence.RUnlock) }, nil
}

func (s *Service) powerRequest(w http.ResponseWriter, r *http.Request) {
	var input PowerRequest
	if r.Method != "POST" || r.URL.RawQuery != "" || r.Header.Get("Content-Type") != "application/json" {
		reply(w, 400, PowerReply{Protocol: PowerProtocol, PCIdentity: s.cfg.Identity, State: "rejected", Code: "invalid_power_request"})
		return
	}
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 2048))
	if err != nil || protocol.DecodeStrict(data, &input) != nil || !token.MatchString(input.Nonce) || (input.Operation != "status" && input.Operation != "shutdown") || (input.Operation == "status" && (input.CycleID != "" || input.TransitionID != "")) || (input.Operation == "shutdown" && (!token.MatchString(input.CycleID) || !validDigest(input.TransitionID))) {
		reply(w, 400, PowerReply{Protocol: PowerProtocol, PCIdentity: s.cfg.Identity, Nonce: input.Nonce, State: "rejected", Code: "invalid_power_request"})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	stop := context.AfterFunc(s.ctx, cancel)
	defer stop()
	reply(w, 200, s.power(ctx, input))
}

type powerIntent struct {
	Version      string `json:"version"`
	CycleID      string `json:"cycleId"`
	TransitionID string `json:"transitionId"`
}

type powerOutcome struct {
	Intent powerIntent `json:"intent"`
	State  string      `json:"state"`
	Code   string      `json:"code"`
}

func powerShutdownFailure(err error) string {
	if err != nil && err.Error() == "power_privilege_unavailable" {
		return "power_privilege_unavailable"
	}
	return "power_shutdown_failed"
}

func validPowerOutcome(outcome powerOutcome) bool {
	return outcome.State == "accepted" && outcome.Code == PowerShutdownAccepted || outcome.State == "failed" && (outcome.Code == "power_shutdown_failed" || outcome.Code == "power_privilege_unavailable")
}

func powerRecordID(transition, kind string) string {
	hash := sha256.Sum256([]byte("power-v1\n" + transition + "\n" + kind))
	return hex.EncodeToString(hash[:16])
}

func (s *Service) powerHistory() ([]protocol.File, error) {
	if s.powerStore == nil {
		return nil, errors.New("power_store_unavailable")
	}
	page, err := s.powerStore.List(s.cfg.BackendPeer, "", 100)
	if err != nil || page.DiagnosticCount != 0 || page.NextCursor != "" {
		return nil, errors.New("power_store_unavailable")
	}
	for _, f := range page.Files {
		_, raw, err := s.powerStore.Read(s.cfg.BackendPeer, f.ID)
		var intent powerIntent
		kind := "intent"
		if f.Size > 2048 || err != nil {
			return nil, errors.New("power_store_unavailable")
		}
		switch f.Name {
		case "power-intent.json":
			err = protocol.DecodeStrict(raw, &intent)
		case "power-outcome.json":
			var outcome powerOutcome
			err = protocol.DecodeStrict(raw, &outcome)
			intent, kind = outcome.Intent, "outcome"
			if !validPowerOutcome(outcome) {
				return nil, errors.New("power_store_unavailable")
			}
		default:
			return nil, errors.New("power_store_unavailable")
		}
		if err != nil || intent.Version != "v1" || !token.MatchString(intent.CycleID) || !validDigest(intent.TransitionID) || f.ID != powerRecordID(intent.TransitionID, kind) {
			return nil, errors.New("power_store_unavailable")
		}
	}
	return page.Files, nil
}

// Persisted uncertainty seals admission after host restart on the same actual
// OS transition. A real reboot/resume or a recorded definite refusal unseals it.
func (s *Service) restorePowerAdmission() {
	if s.powerStore == nil {
		return
	}
	history, err := s.powerHistory()
	if err != nil {
		s.powerSealed = true
		return
	}
	if len(history) == 0 {
		return
	}
	s.powerSealed = true
	ctx, cancel := context.WithTimeout(s.ctx, 5*time.Second)
	defer cancel()
	observation, err := s.powerOS.Observe(ctx)
	if err != nil || !observation.Valid() {
		return
	}
	s.reconcilePowerAdmission(observation)
}

// Caller owns the exclusive power fence (or construction has not published s).
// A resume can change the actual OS transition without restarting this host.
func (s *Service) reconcilePowerAdmission(observation PowerObservation) {
	s.powerSealed = true
	_, raw, err := s.powerStore.Read(s.cfg.BackendPeer, powerRecordID(observation.TransitionID, "intent"))
	if errors.Is(err, files.ErrAbsent) {
		// Old transition receipts are never interpreted as current shutdown authority.
		if _, _, outcomeErr := s.powerStore.Read(s.cfg.BackendPeer, powerRecordID(observation.TransitionID, "outcome")); errors.Is(outcomeErr, files.ErrAbsent) {
			s.powerSealed = false
		}
		return
	}
	var intent powerIntent
	if err != nil || protocol.DecodeStrict(raw, &intent) != nil {
		return
	}
	receipt, _ := s.powerReceipt(PowerRequest{CycleID: intent.CycleID, TransitionID: observation.TransitionID}, observation)
	if receipt.State == "rejected" && receipt.ShutdownState == "failed" {
		s.powerSealed = false
	}
}

func (s *Service) powerReceipt(input PowerRequest, observation PowerObservation) (PowerReply, bool) {
	result := PowerReply{Protocol: PowerProtocol, PCIdentity: s.cfg.Identity, Nonce: input.Nonce, State: "rejected", Code: "power_store_unavailable", Observation: &observation, CycleID: input.CycleID}
	if s.powerStore == nil {
		return result, true
	}
	f, raw, err := s.powerStore.Read(s.cfg.BackendPeer, powerRecordID(input.TransitionID, "intent"))
	if errors.Is(err, files.ErrAbsent) {
		if _, _, outcomeErr := s.powerStore.Read(s.cfg.BackendPeer, powerRecordID(input.TransitionID, "outcome")); !errors.Is(outcomeErr, files.ErrAbsent) {
			return result, true
		}
		return result, false
	}
	var intent powerIntent
	if err != nil || f.Name != "power-intent.json" || protocol.DecodeStrict(raw, &intent) != nil || intent.Version != "v1" || intent.TransitionID != input.TransitionID || !token.MatchString(intent.CycleID) {
		return result, true
	}
	if intent.CycleID != input.CycleID {
		result.Code = "power_transition_consumed"
		return result, true
	}
	result.State, result.Code, result.ShutdownState = "unknown", "power_shutdown_unconfirmed", "unconfirmed"
	f, raw, err = s.powerStore.Read(s.cfg.BackendPeer, powerRecordID(input.TransitionID, "outcome"))
	if errors.Is(err, files.ErrAbsent) {
		return result, true
	}
	var outcome powerOutcome
	if err != nil || f.Name != "power-outcome.json" || protocol.DecodeStrict(raw, &outcome) != nil || outcome.Intent != intent || !validPowerOutcome(outcome) {
		result.Code = "power_store_unavailable"
		return result, true
	}
	result.ShutdownState = outcome.State
	if outcome.State == "accepted" {
		result.State, result.Code = "ok", PowerShutdownAccepted
	} else {
		result.State, result.Code = "rejected", outcome.Code
	}
	return result, true
}

func (s *Service) power(ctx context.Context, input PowerRequest) PowerReply {
	result := PowerReply{Protocol: PowerProtocol, PCIdentity: s.cfg.Identity, Nonce: input.Nonce, State: "rejected", Code: "power_observation_unavailable"}
	observation, err := s.powerOS.Observe(ctx)
	if err != nil || !observation.Valid() {
		result.State = "unknown"
		return result
	}
	result.Observation = &observation
	if input.Operation == "status" {
		if privileges, ok := s.powerOS.(powerPrivilegeObserver); ok {
			if available, err := privileges.ShutdownPrivilege(ctx); err == nil {
				result.ShutdownPrivilegeAvailable = &available
			}
		}
		if s.powerFence.TryLock() {
			_, historyErr := s.powerHistory()
			if historyErr == nil {
				s.reconcilePowerAdmission(observation)
			}
			s.powerFence.Unlock()
			if historyErr != nil {
				result.State, result.Code = "unknown", "power_store_unavailable"
				return result
			}
		}
		result.State, result.Code = "ok", "power_status_ready"
		return result
	}
	result.CycleID = input.CycleID
	if input.TransitionID != observation.TransitionID {
		result.Code = "power_transition_mismatch"
		return result
	}
	if !s.powerFence.TryLock() {
		result.Code = "power_busy"
		return result
	}
	defer s.powerFence.Unlock()
	history, historyErr := s.powerHistory()
	if historyErr != nil {
		result.Code = "power_store_unavailable"
		return result
	}
	if receipt, consumed := s.powerReceipt(input, observation); consumed {
		return receipt
	}
	s.reconcilePowerAdmission(observation)
	if s.powerSealed {
		result.Code = "power_shutdown_pending"
		return result
	}
	if s.cfg.CodexSocket != "" {
		result.Code = "power_owned_runtime_required"
		return result
	}
	if err := s.powerOwnersClosed(); err != nil {
		result.Code = "power_owned_work_unconfirmed"
		return result
	}
	count, _, code := s.scanHandoffStatus()
	if count != 0 || code != "" {
		result.Code = "power_handoffs_unconfirmed"
		return result
	}
	ids := make([]string, 0, len(s.peers))
	for id := range s.peers {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	for _, id := range ids {
		p := s.peers[id]
		p.connMu.Lock()
		if p.conn != nil {
			select {
			case <-p.conn.Done():
				err = s.retireConnection(p.conn)
			default:
				err = p.conn.CloseWhenIdle(ctx)
			}
			if err == nil {
				p.conn = nil
			}
		}
		p.connMu.Unlock()
		if err != nil {
			result.Code = "power_native_not_quiescent"
			return result
		}
	}
	latest, err := s.powerOS.Observe(ctx)
	if err != nil || !latest.Valid() || latest.TransitionID != observation.TransitionID || ctx.Err() != nil {
		result.Code = "power_transition_unconfirmed"
		return result
	}
	result.Observation = &latest
	// Remove only validated control records from a different, now-obsolete OS
	// transition. Current intent/outcome fences have no age-based expiration.
	for _, previous := range history {
		if err := s.powerStore.Remove(s.cfg.BackendPeer, previous.ID); err != nil {
			result.Code = "power_store_unavailable"
			return result
		}
	}
	intent := powerIntent{"v1", input.CycleID, input.TransitionID}
	raw, _ := json.Marshal(intent)
	f, _ := files.Describe(powerRecordID(input.TransitionID, "intent"), "power-intent.json", raw)
	if _, err := s.powerStore.PutContext(ctx, s.cfg.BackendPeer, f, bytes.NewReader(raw)); err != nil {
		// A failed publication may already have committed. Seal until a later OS
		// transition, and never turn a persistence failure into a power action.
		s.powerSealed = true
		result.State, result.Code, result.ShutdownState = "unknown", "power_store_unavailable", "unconfirmed"
		return result
	}
	s.powerSealed = true
	result.State, result.Code, result.ShutdownState = "unknown", "power_shutdown_unconfirmed", "unconfirmed"
	if ctx.Err() != nil {
		return result // Committed intent is consumed even if the caller vanished.
	}
	err = s.powerOS.Shutdown(ctx)
	outcome := powerOutcome{Intent: intent, State: "accepted", Code: PowerShutdownAccepted}
	if err != nil {
		outcome.State = "failed"
		outcome.Code = powerShutdownFailure(err)
	}
	raw, _ = json.Marshal(outcome)
	f, _ = files.Describe(powerRecordID(input.TransitionID, "outcome"), "power-outcome.json", raw)
	// Recording the observed OS result is local reconciliation, not a replay.
	if _, storeErr := s.powerStore.Put(s.cfg.BackendPeer, f, bytes.NewReader(raw)); storeErr != nil {
		return result
	}
	if err != nil {
		s.powerSealed = false
		result.State, result.Code, result.ShutdownState = "rejected", outcome.Code, "failed"
	} else {
		result.State, result.Code, result.ShutdownState = "ok", PowerShutdownAccepted, "accepted"
	}
	return result
}

// Only exact self-contained native-deleted checkpoints establish closure.
// Delivery and all-app failure policy remain the paired Pi's responsibility.
func (s *Service) powerOwnersClosed() error {
	if s.creations == nil {
		return errors.New("power_owner_store_unavailable")
	}
	s.creationMu.Lock()
	defer s.creationMu.Unlock()
	if len(s.creationCalls) != 0 {
		return errors.New("power_creation_busy")
	}
	peers, err := s.creations.Peers()
	if err != nil {
		return err
	}
	for _, peer := range peers {
		records, unknown, err := s.creationRecords(peer)
		if err != nil || unknown {
			return errors.New("power_creation_unconfirmed")
		}
		allowed := map[string]deletedCreation{}
		for _, f := range records {
			if f.Name != "creation-deleted.json" {
				continue
			}
			_, raw, err := s.creations.Read(peer, f.ID)
			var closed deletedCreation
			if err != nil || protocol.DecodeStrict(raw, &closed) != nil || closed.Request == nil || !validCleanupOwner(*closed.Request) || f.ID != creationID(peer, closed.Request.AppID, closed.Request.RequestID, "deleted") {
				return errors.New("power_creation_unconfirmed")
			}
			closed, err = s.readDeletedCreation(peer, *closed.Request)
			if err != nil {
				return err
			}
			for _, kind := range []string{"request", "result", "cleanup", "deleted"} {
				allowed[creationID(peer, closed.Request.AppID, closed.Request.RequestID, kind)] = closed
			}
		}
		for _, f := range records {
			if f.Name == processDiagnosticName {
				if _, err := s.readProcessDiagnostic(peer, f); err != nil {
					return err
				}
				continue
			}
			closed, ok := allowed[f.ID]
			if !ok {
				return errors.New("power_creation_still_owned")
			}
			_, raw, err := s.creations.Read(peer, f.ID)
			if err != nil {
				return err
			}
			switch f.Name {
			case "creation-request.json":
				canonical, _ := json.Marshal(closed.Request)
				if !bytes.Equal(raw, canonical) {
					return errors.New("power_creation_unconfirmed")
				}
			case "creation-result.json":
				var receipt creationReceipt
				if protocol.DecodeStrict(raw, &receipt) != nil || receipt.RequestHash != closed.RequestHash || receipt.ThreadID != closed.ThreadID {
					return errors.New("power_creation_unconfirmed")
				}
			case cleanupIntentName:
				var intent cleanupIntent
				if protocol.DecodeStrict(raw, &intent) != nil || intent.Request != *closed.Request || (intent.ThreadID != "" && intent.ThreadID != closed.ThreadID) {
					return errors.New("power_creation_unconfirmed")
				}
			case "creation-deleted.json":
			default:
				return errors.New("power_creation_unconfirmed")
			}
		}
	}
	return nil
}

package backend

import (
	"context"
	"net/http"
	"os"
	"time"
)

func powerView(p *powerState, counts powerCounts, err error) map[string]any {
	view := map[string]any{"state": "unconfirmed", "code": "power_state_unconfirmed", "managed": false, "hold": false, "holdCode": "", "cycleId": "", "counts": counts, "drainSince": int64(0), "wakeClaimedAt": int64(0), "observedAt": time.Now().UnixMilli()}
	if p != nil {
		view["cycleId"] = p.CycleID
	}
	if err == nil && p != nil {
		view["state"] = p.State
		view["code"] = p.Code
		view["cycleId"] = p.CycleID
		view["managed"] = p.State == "managed" || p.State == "shutdown_claimed"
		view["hold"] = p.HoldCode != ""
		view["holdCode"] = p.HoldCode
		view["drainSince"] = p.DrainSince
		view["wakeClaimedAt"] = p.WakeClaimedAt
	}
	return view
}

func (s *Service) currentPowerView() map[string]any {
	snapshot, err := s.collectPower()
	s.powerMu.Lock()
	p, readErr := s.readPower()
	fault := s.powerFault
	s.powerMu.Unlock()
	if err == nil && os.IsNotExist(readErr) && !fault {
		return powerView(&powerState{State: "unmanaged", Code: "power_unmanaged"}, snapshot.counts, nil)
	}
	if err == nil {
		err = readErr
	}
	if fault {
		err = os.ErrInvalid
	}
	return powerView(p, snapshot.counts, err)
}

// Caller releases appMu before entering these global operations.
func (s *Service) requestWake(parent context.Context) (string, string, error) {
	s.powerMu.Lock()
	p, err := s.readPower()
	if err != nil && !os.IsNotExist(err) {
		s.powerMu.Unlock()
		return "", "", powerError("power_state_unconfirmed")
	}
	cooling := false
	if err == nil && p.WakeClaimedAt > 0 {
		id, tick, clockErr := s.clockPower()
		if clockErr != nil {
			s.powerMu.Unlock()
			return "", "", powerError("power_state_unconfirmed")
		}
		if id == p.ClockID {
			cooling = tick < p.ClaimTick || tick-p.ClaimTick < powerWindow.Milliseconds()
		} else {
			cooling = tick < powerWindow.Milliseconds() // A new Pi boot must itself age through the cooldown.
		}
	}
	if s.powerWaking || cooling {
		s.powerMu.Unlock()
		return "wake_coalesced", "unconfirmed", nil
	}
	s.powerWaking = true
	s.powerMu.Unlock()
	defer func() { s.powerMu.Lock(); s.powerWaking = false; s.powerMu.Unlock() }()
	ctx, cancel := s.appObservationContext(parent, 5*time.Second)
	observation, observeErr := s.powerStatus(ctx)
	reachable := observeErr == nil
	if !reachable && ctx.Err() == nil {
		reachable = s.native.Reachable(ctx) == nil
	}
	cancel()
	if parent.Err() != nil || s.ctx.Err() != nil {
		return "", "", powerError("wake_unconfirmed")
	}
	snapshot, scanErr := s.collectPower()
	s.powerMu.Lock()
	p, err = s.readPower()
	if os.IsNotExist(err) {
		p, err = newPowerState(), nil
	}
	if err != nil {
		s.powerMu.Unlock()
		return "", "", powerError("power_state_unconfirmed")
	}
	if reachable {
		same := observeErr == nil && p.TransitionID == observation.TransitionID
		if p.AdmissionClosed && (same || observeErr != nil) {
			s.powerMu.Unlock()
			return "", "", powerError("power_shutdown_pending")
		}
		if !(same && p.State == "managed") {
			p.State, p.Code, p.AdmissionClosed, p.DrainSince = "unmanaged", "power_already_on", false, 0
		}
		if snapshot.issue != "" && p.HoldCode == "" {
			p.HoldCode, p.HoldAt = snapshot.issue, time.Now().UnixMilli()
		}
		err = s.writePower(p)
		s.powerMu.Unlock()
		if err != nil {
			return "", "", powerError("power_state_unconfirmed")
		}
		return "already_on", "reachable", nil
	}
	if s.wake == nil {
		s.powerMu.Unlock()
		return "", "", powerError("wake_unavailable")
	}
	clockID, tick, clockErr := s.clockPower()
	if clockErr != nil {
		s.powerMu.Unlock()
		return "", "", powerError("power_state_unconfirmed")
	}
	old := p
	p = newPowerState()
	p.State, p.Code, p.WakeClaimedAt, p.ClockID, p.ClaimTick = "wake_pending", "wake_unconfirmed", time.Now().UnixMilli(), clockID, tick
	p.HoldCode, p.HoldAt, p.ReleasedAt, p.AdmissionClosed = old.HoldCode, old.HoldAt, old.ReleasedAt, old.AdmissionClosed
	if old.AdmissionClosed {
		p.TransitionID = old.TransitionID
	}
	p.HasWork = snapshot.hasWork || snapshot.counts.Cleanup > 0 || snapshot.counts.Active > 0
	if snapshot.issue != "" && p.HoldCode == "" {
		p.HoldCode, p.HoldAt = snapshot.issue, time.Now().UnixMilli()
	}
	if scanErr != nil && p.HoldCode == "" {
		p.HoldCode, p.HoldAt = "power_job_state_unconfirmed", time.Now().UnixMilli()
	}
	err = s.writePower(p)
	s.powerMu.Unlock()
	if err != nil {
		return "", "", powerError("power_state_unconfirmed")
	}
	ctx, cancel = s.appObservationContext(parent, 5*time.Second)
	err = s.wake(ctx)
	confirmed := err == nil && ctx.Err() == nil && parent.Err() == nil && s.ctx.Err() == nil
	cancel()
	s.powerMu.Lock()
	current, readErr := s.readPower()
	if readErr == nil && current.CycleID == p.CycleID && confirmed {
		current.PacketConfirmed = true
		current.Code = "packet_sent"
		err = s.writePower(current)
	} else if readErr != nil {
		err = readErr
	}
	s.powerMu.Unlock()
	s.notifyPower()
	if !confirmed || err != nil {
		return "", "", powerError("wake_unconfirmed")
	}
	return "packet_sent", "unconfirmed", nil
}

type powerError string

func (e powerError) Error() string { return string(e) }

func (s *Service) releasePower(cycle string) error {
	snapshot, err := s.collectPower()
	if err != nil {
		return powerError("power_state_unconfirmed")
	}
	s.powerMu.Lock()
	defer s.powerMu.Unlock()
	p, err := s.readPower()
	if err != nil {
		return powerError("power_state_unconfirmed")
	}
	if p.CycleID != cycle {
		return powerError("power_cycle_changed")
	}
	if p.AdmissionClosed {
		return powerError("power_shutdown_pending")
	}
	if snapshot.generation != s.powerGeneration || snapshot.unresolved != "" {
		return powerError("power_issue_unresolved")
	}
	p.HoldCode, p.HoldAt, p.DrainSince, p.ReleasedAt = "", 0, 0, time.Now().UnixMilli()
	if s.writePower(p) != nil {
		return powerError("power_state_unconfirmed")
	}
	s.powerFault = false
	s.powerGeneration++
	s.notifyPower()
	return nil
}

func (s *Service) powerControls(w http.ResponseWriter, r *http.Request, p *peer, state *appState) bool {
	wake := r.Method == "POST" && r.URL.Path == "/v2/connection/wake"
	get := r.Method == "GET" && r.URL.Path == "/v2/connection/power"
	release := r.Method == "POST" && r.URL.Path == "/v2/connection/power/release"
	if !wake && !get && !release {
		return false
	}
	var input struct {
		CycleID string `json:"cycleId"`
	}
	if wake && !s.receiveAppBody(w, r, p, &state, "invalid_wake_request", nil) {
		return true
	}
	if release && !s.receiveAppBody(w, r, p, &state, "invalid_power_request", &input) {
		return true
	}
	if release && !token.MatchString(input.CycleID) {
		s.appError(w, 400, "invalid_power_request")
		return true
	}
	p.appMu.Unlock()
	defer p.appMu.Lock()
	if get {
		s.appReply(w, 200, s.currentPowerView())
		return true
	}
	var err error
	if release {
		err = s.releasePower(input.CycleID)
		if err == nil {
			s.appReply(w, 200, s.currentPowerView())
			return true
		}
	} else {
		var state, pcState string
		state, pcState, err = s.requestWake(r.Context())
		if err == nil {
			s.appReply(w, 202, map[string]any{"state": state, "pcState": pcState, "power": s.currentPowerView()})
			return true
		}
	}
	code := err.Error()
	status := 503
	if code == "power_cycle_changed" || code == "power_shutdown_pending" || code == "power_issue_unresolved" {
		status = 409
	}
	s.appError(w, status, code)
	return true
}

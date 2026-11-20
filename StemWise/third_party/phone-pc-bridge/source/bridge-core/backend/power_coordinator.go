package backend

import (
	"context"
	"errors"
	"os"
	"phonepcbridge/pc"
	"strings"
	"time"
)

func (s *Service) beginPowerStream() {
	s.powerMu.Lock()
	s.powerStreams++
	s.powerGeneration++
	s.powerMu.Unlock()
	s.notifyPower()
}
func (s *Service) endPowerStream() {
	s.powerMu.Lock()
	s.powerStreams--
	s.powerGeneration++
	s.powerMu.Unlock()
	s.notifyPower()
}

func (s *Service) resetPowerStartup() {
	s.powerMu.Lock()
	defer s.powerMu.Unlock()
	// Only our unpublished atomic-write stages are disposable.
	root, err := os.OpenRoot(s.cfg.DataDir)
	if err == nil {
		dir, e := root.Open(".")
		if e == nil {
			entries, e := dir.ReadDir(-1)
			dir.Close()
			if e == nil {
				for _, entry := range entries {
					name := entry.Name()
					if strings.HasPrefix(name, ".power-") && strings.HasSuffix(name, ".tmp") && len(name) == 43 && token.MatchString(name[7:39]) {
						if info, e := root.Lstat(name); e == nil && info.Mode().IsRegular() {
							if root.Remove(name) != nil {
								s.powerFault = true
							}
						}
					}
				}
			}
		}
		root.Close()
	}
	p, err := s.readPower()
	if err == nil && p.DrainSince != 0 {
		p.DrainSince = 0
		if s.writePower(p) != nil {
			s.powerFault = true
		}
	}
}

func (s *Service) managePower() {
	timer := time.NewTimer(powerPoll)
	defer timer.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-s.powerChangedCh:
			continue // Local events cannot shorten the remote observation cadence.
		case <-timer.C:
		}
		s.powerPass(time.Now())
		if !timer.Stop() {
			select {
			case <-timer.C:
			default:
			}
		}
		timer.Reset(powerPoll)
	}
}

func (s *Service) powerStatus(ctx context.Context) (pc.PowerObservation, error) {
	native, ok := s.native.(powerNative)
	if !ok {
		return pc.PowerObservation{}, errors.New("power_unavailable")
	}
	reply, err := native.Power(ctx, pc.PowerRequest{Nonce: randomToken(), Operation: "status"})
	if err != nil || ctx.Err() != nil || reply.State != "ok" || reply.Observation == nil || !reply.Observation.Valid() {
		return pc.PowerObservation{}, errors.New("power_status_unconfirmed")
	}
	return *reply.Observation, nil
}

func (s *Service) powerPass(now time.Time) {
	s.powerMu.Lock()
	before, err := s.readPower()
	fault := s.powerFault
	s.powerMu.Unlock()
	if err != nil || fault || before.State == "unmanaged" || (before.State == "wake_pending" && !before.PacketConfirmed) {
		return
	}
	clockID, tick, clockErr := s.clockPower()
	ctx, cancel := s.appObservationContext(s.ctx, 5*time.Second)
	observation, observeErr := s.powerStatus(ctx)
	cancel()
	if observeErr != nil {
		s.powerMu.Lock()
		p, e := s.readPower()
		if e == nil && p.CycleID == before.CycleID && p.DrainSince != 0 {
			p.DrainSince = 0
			if s.writePower(p) != nil {
				s.powerFault = true
			}
		}
		s.powerMu.Unlock()
		return
	}
	snapshot, scanErr := s.collectPower()
	s.powerMu.Lock()
	p, err := s.readPower()
	if err != nil || s.powerFault || p.CycleID != before.CycleID || snapshot.generation != s.powerGeneration {
		s.powerMu.Unlock()
		return
	}
	if p.State == "shutdown_claimed" {
		if p.TransitionID != observation.TransitionID {
			p.State, p.Code, p.AdmissionClosed, p.DrainSince = "unmanaged", "power_transition_changed", false, 0
			if s.writePower(p) != nil {
				s.powerFault = true
			}
		}
		s.powerMu.Unlock()
		return // A claimed OS mutation is never replayed.
	}
	// The upper proof bound and grace use a fresh clock after all blocking observations.
	postClockID, postTick, postClockErr := s.clockPower()
	if p.State == "wake_pending" {
		age := observation.UptimeMS
		if observation.WakeAgeMS != nil && *observation.WakeAgeMS < age {
			age = *observation.WakeAgeMS
		}
		if !p.PacketConfirmed || (p.AdmissionClosed && p.TransitionID == observation.TransitionID) || clockErr != nil || postClockErr != nil || p.ClockID != clockID || clockID != postClockID || tick < p.ClaimTick || tick-p.ClaimTick < 1000 || age > tick-p.ClaimTick-1000 || postTick-p.ClaimTick-age > (2*time.Minute).Milliseconds() {
			s.powerMu.Unlock()
			return
		}
		// The authenticated OS transition occurred strictly after our confirmed packet claim.
		p.State, p.Code, p.TransitionID, p.ManagedAt, p.AdmissionClosed = "managed", "power_managed", observation.TransitionID, now.UnixMilli(), false
	}
	if p.State != "managed" || clockErr != nil {
		s.powerMu.Unlock()
		return
	}
	if p.TransitionID != observation.TransitionID {
		p.State, p.Code, p.AdmissionClosed, p.DrainSince = "unmanaged", "power_transition_changed", false, 0
		if s.writePower(p) != nil {
			s.powerFault = true
		}
		s.powerMu.Unlock()
		return
	}
	// Ownership uses the pre-RPC clock; grace begins only after the current idle snapshot.
	clockID, tick, clockErr = postClockID, postTick, postClockErr
	if clockErr != nil {
		s.powerMu.Unlock()
		return
	}
	if p.GraceClockID != clockID || tick < p.ManagedTick {
		p.GraceClockID, p.ManagedTick, p.DrainSince, p.DrainTick = clockID, tick, 0, 0
	}
	if snapshot.hasWork {
		p.HasWork = true
	}
	if scanErr != nil && p.HoldCode == "" {
		p.HoldCode, p.HoldAt = "power_job_state_unconfirmed", now.UnixMilli()
	}
	if snapshot.issue != "" && p.HoldCode == "" {
		p.HoldCode, p.HoldAt = snapshot.issue, now.UnixMilli()
	}
	busy := snapshot.counts.Pending+snapshot.counts.Undelivered+snapshot.counts.Cancelling+snapshot.counts.Cleanup+snapshot.counts.Active+snapshot.counts.Issues > 0
	if p.DrainGeneration != snapshot.generation {
		p.DrainSince, p.DrainTick = 0, 0
	}
	if p.HoldCode != "" || busy || !p.HasWork {
		p.DrainSince, p.DrainTick = 0, 0
	} else if p.DrainSince == 0 {
		p.DrainSince, p.DrainTick = now.UnixMilli(), tick
		p.DrainGeneration = snapshot.generation
	}
	shutdown := p.ShutdownAt == 0 && p.DrainSince > 0 && tick-p.DrainTick >= powerWindow.Milliseconds() && tick-p.ManagedTick >= powerWindow.Milliseconds()
	if shutdown {
		p.State, p.Code, p.ShutdownAt, p.AdmissionClosed = "shutdown_claimed", "power_shutdown_unconfirmed", now.UnixMilli(), true
	}
	if s.writePower(p) != nil {
		s.powerFault = true
		s.powerMu.Unlock()
		return
	}
	s.powerMu.Unlock()
	if !shutdown {
		return
	}
	// Durable claim precedes the only send, including process death and lost replies.
	ctx, cancel = s.appObservationContext(s.ctx, 30*time.Second)
	defer cancel()
	reply, callErr := s.native.(powerNative).Power(ctx, pc.PowerRequest{Nonce: randomToken(), Operation: "shutdown", CycleID: p.CycleID, TransitionID: p.TransitionID})
	s.powerMu.Lock()
	defer s.powerMu.Unlock()
	current, e := s.readPower()
	if e != nil || current.CycleID != p.CycleID || current.State != "shutdown_claimed" {
		return
	}
	valid := callErr == nil && ctx.Err() == nil && reply.CycleID == p.CycleID && reply.Observation != nil && reply.Observation.Valid() && reply.Observation.TransitionID == p.TransitionID
	if valid && reply.State == "ok" && reply.ShutdownState == "accepted" && reply.Code == pc.PowerShutdownAccepted {
		current.Code = pc.PowerShutdownAccepted
	} else {
		current.Code = "power_shutdown_unconfirmed"
		if valid && reply.State == "rejected" && reply.ShutdownState == "" {
			current.State, current.AdmissionClosed = "managed", false
			current.Code = "power_shutdown_blocked"
			current.ShutdownAt = 0 // Exact pre-action refusal consumed no OS intent.
		}
		if valid && reply.State == "rejected" && reply.ShutdownState == "failed" {
			current.State, current.AdmissionClosed = "managed", false
		}
		if callErr == nil && ctx.Err() == nil && appCode.MatchString(reply.Code) && (reply.CycleID == "" || reply.CycleID == p.CycleID) {
			current.Code = reply.Code
		}
		busy := valid && reply.State == "rejected" && reply.ShutdownState == "" && reply.Code == "power_busy"
		if current.HoldCode == "" && !busy {
			current.HoldCode, current.HoldAt = current.Code, now.UnixMilli()
		}
		current.DrainSince = 0
	}
	if s.writePower(current) != nil {
		s.powerFault = true
	}
}

// Called before the last durable job evidence is removed, while appMu is held.
func (s *Service) holdRemovedJobs(jobs map[string]*appJob, expiring bool) error {
	s.powerMu.Lock()
	defer s.powerMu.Unlock()
	p, err := s.readPower()
	if err != nil && !os.IsNotExist(err) {
		s.powerFault = true
		return nil
	} // Corrupt/unreadable state cannot arm.
	historical := err != nil || p.ReleasedAt == 0
	code := ""
	for _, j := range jobs {
		if issue := powerJobIssue(j, historical); issue != "" {
			code = issue
			break
		}
		if expiring && !powerJobDrained(j) {
			code = "job_expired_undelivered"
		}
	}
	if code == "" {
		return nil
	}
	if err != nil {
		p = newPowerState()
	}
	if p.HoldCode != "" {
		return nil
	}
	p.HoldCode, p.HoldAt, p.DrainSince = code, time.Now().UnixMilli(), 0
	// A valid active state cannot outlive destructive job removal without its hold.
	return s.writePower(p)
}

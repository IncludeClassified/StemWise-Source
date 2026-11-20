package backend

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"runtime"
	"time"

	"phonepcbridge/pc"
)

const powerWindow = 60 * time.Second
const powerPoll = 15 * time.Second

var errPowerAdmission = errors.New("power_shutdown_pending")

type powerNative interface {
	Power(context.Context, pc.PowerRequest) (pc.PowerReply, error)
}

type powerState struct {
	Version         int    `json:"version"`
	CycleID         string `json:"cycleId"`
	State           string `json:"state"`
	Code            string `json:"code"`
	WakeClaimedAt   int64  `json:"wakeClaimedAt"`
	ManagedAt       int64  `json:"managedAt"`
	TransitionID    string `json:"transitionId"`
	DrainSince      int64  `json:"drainSince"`
	HasWork         bool   `json:"hasWork"`
	HoldCode        string `json:"holdCode"`
	HoldAt          int64  `json:"holdAt"`
	ReleasedAt      int64  `json:"releasedAt"`
	ShutdownAt      int64  `json:"shutdownAt"`
	AdmissionClosed bool   `json:"admissionClosed"`
	PacketConfirmed bool   `json:"packetConfirmed"`
	ClockID         string `json:"clockId"`
	ClaimTick       int64  `json:"claimTick"`
	GraceClockID    string `json:"graceClockId"`
	ManagedTick     int64  `json:"managedTick"`
	DrainTick       int64  `json:"drainTick"`
	DrainGeneration uint64 `json:"drainGeneration"`
}

func validPowerState(p *powerState) bool {
	if p == nil || p.Version != 1 || !token.MatchString(p.CycleID) || !contains([]string{"unmanaged", "wake_pending", "managed", "shutdown_claimed"}, p.State) || !appCode.MatchString(p.Code) || p.WakeClaimedAt < 0 || p.ManagedAt < 0 || p.DrainSince < 0 || p.HoldAt < 0 || p.ReleasedAt < 0 || p.ShutdownAt < 0 || (p.HoldCode != "" && !appCode.MatchString(p.HoldCode)) || (p.HoldCode != "" && p.HoldAt == 0) {
		return false
	}
	if p.TransitionID != "" {
		decoded, err := hex.DecodeString(p.TransitionID)
		if err != nil || len(decoded) != 32 || hex.EncodeToString(decoded) != p.TransitionID {
			return false
		}
	}
	return p.ClaimTick >= 0 && p.ManagedTick >= 0 && p.DrainTick >= 0 && (p.State != "managed" || (p.PacketConfirmed && p.WakeClaimedAt > 0 && p.ManagedAt > 0 && p.TransitionID != "")) && (p.State != "wake_pending" || p.WakeClaimedAt > 0) && (p.State != "shutdown_claimed" || (p.ShutdownAt > 0 && p.AdmissionClosed && p.TransitionID != ""))
}

// Caller holds powerMu. Missing state is unarmed; corrupt state is preserved.
func (s *Service) readPower() (*powerState, error) {
	root, err := os.OpenRoot(s.cfg.DataDir)
	if err != nil {
		return nil, err
	}
	defer root.Close()
	info, err := root.Lstat("power.json")
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Size() > 8192 {
		return nil, errors.New("power_state_invalid")
	}
	f, err := root.Open("power.json")
	if err != nil {
		return nil, err
	}
	data, err := io.ReadAll(io.LimitReader(f, 8193))
	f.Close()
	var state powerState
	if err != nil || len(data) > 8192 || appDecode(data, &state) != nil || !validPowerState(&state) {
		return nil, errors.New("power_state_invalid")
	}
	return &state, nil
}

// The final rename shares powerMu with every app admission commit.
func (s *Service) writePower(p *powerState) error {
	if !validPowerState(p) {
		return errors.New("power_state_invalid")
	}
	root, err := os.OpenRoot(s.cfg.DataDir)
	if err != nil {
		return err
	}
	defer root.Close()
	data, _ := json.Marshal(p)
	name := ".power-" + randomToken() + ".tmp"
	f, err := root.OpenFile(name, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	defer root.Remove(name)
	_, err = f.Write(data)
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err = root.Rename(name, "power.json"); err != nil {
		return err
	}
	if runtime.GOOS != "windows" {
		directory, err := root.Open(".")
		if err != nil {
			return err
		}
		err = directory.Sync()
		directory.Close()
		return err
	}
	return nil
}

func newPowerState() *powerState {
	return &powerState{Version: 1, CycleID: randomToken(), State: "unmanaged", Code: "power_unmanaged"}
}

func (s *Service) clockPower() (string, int64, error) {
	if s.powerClockRead != nil {
		return s.powerClockRead()
	}
	return powerClock()
}

func (s *Service) latchPowerLocked(code string) error {
	p, err := s.readPower()
	if os.IsNotExist(err) {
		p, err = newPowerState(), nil
	}
	if err != nil {
		// An unreadable/corrupt record already forbids automatic power actions.
		return err
	}
	if p.HoldCode == "" {
		p.HoldCode, p.HoldAt, p.DrainSince = code, time.Now().UnixMilli(), 0
		return s.writePower(p)
	}
	return nil
}

func (s *Service) powerChanged() {
	s.powerMu.Lock()
	s.powerGeneration++
	s.powerMu.Unlock()
	s.notifyPower()
}
func (s *Service) notifyPower() {
	select {
	case s.powerChangedCh <- struct{}{}:
	default:
	}
}

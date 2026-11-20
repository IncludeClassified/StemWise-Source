package backend

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"time"
)

var errOutputOwnerChanged = errors.New("output_ownership_changed")

// Caller owns appMu; a verified snapshot can commit only for its unchanged owner.
func (s *Service) reloadOutputOwner(p *peer, app, jobID string, before []byte) (*appState, *appJob, error) {
	state, err := s.loadApp(p)
	if err != nil {
		return nil, nil, err
	}
	if err := s.expireAppForUse(p, state, time.Now()); err != nil {
		return state, nil, err
	}
	job := state.Jobs[jobID]
	if state.ID != app || job == nil {
		return state, nil, os.ErrNotExist
	}
	current, err := json.Marshal(job)
	if err != nil {
		return state, nil, err
	}
	if !bytes.Equal(before, current) {
		return state, job, errOutputOwnerChanged
	}
	return state, job, nil
}

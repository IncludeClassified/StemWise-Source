package backend

import (
	"errors"
	"os"
	"phonepcbridge/files"
	"phonepcbridge/protocol"
	"time"
)

const appRetention = protocol.Retention

func jobFileReferences(j *appJob) []protocol.File {
	refs := append(append([]protocol.File{}, j.Input.Files...), j.Outputs...)
	if j.Report != nil {
		refs = append(refs, j.Report.Files...)
	}
	return refs
}

func jobFileIDs(app string, j *appJob) map[string]bool {
	ids := map[string]bool{appResultID(app, j.ID): true}
	for _, f := range jobFileReferences(j) {
		ids[f.ID] = true
	}
	return ids
}

func retainedFileIDs(state *appState) map[string]bool {
	ids := map[string]bool{}
	if state != nil {
		for _, j := range state.Jobs {
			for id := range jobFileIDs(state.ID, j) {
				ids[id] = true
			}
		}
	}
	return ids
}

// Receiver acceptance starts every job's fixed lifetime, regardless of native outcome or delivery.
func (s *Service) expireApp(state *appState, now time.Time) error {
	expired := map[string]*appJob{}
	for id, j := range state.Jobs {
		if j.Created <= now.Add(-appRetention).UnixMilli() {
			expired[id] = j
		}
	}
	changed := false
	for id, expires := range state.DeletedRequests {
		if expires <= now.UnixMilli() {
			delete(state.DeletedRequests, id)
			changed = true
		}
	}
	if len(expired) == 0 {
		if changed {
			if err := s.saveApp(state); err != nil {
				return err
			}
		}
		return s.purgeExpiredAppFiles(state)
	}
	if state.Cleanup == nil {
		state.Cleanup = map[string]appCleanup{}
	}
	if err := s.holdRemovedJobs(expired, true); err != nil {
		return err
	}
	if state.DeletedRequests == nil {
		state.DeletedRequests = map[string]int64{}
	}
	for id, j := range expired {
		state.Cleanup[id] = jobCleanupRecord(state.ID, j, !j.CleanupAccepted && j.ChatState != "deleted" && (j.ThreadID != "" || j.ChatState != ""))
		state.DeletedRequests[id] = now.Add(appRetention).UnixMilli()
		delete(state.Jobs, id)
	}
	// Ownership obligations and content removal share one atomic app-state commit.
	if err := s.saveApp(state); err != nil {
		return err
	}
	p := s.getPeer(state.Peer)
	for id := range expired {
		s.stopJobWork(p, state.ID, id)
	}
	s.notifyJobs()
	return s.purgeExpiredAppFiles(state)
}

// Caller holds appMu and has authorized every selected exact owner before this commit.
func (s *Service) removeAppJobs(state *appState, expired map[string]*appJob) error {
	if err := s.holdRemovedJobs(expired, false); err != nil {
		return err
	}
	if state.Cleanup == nil {
		state.Cleanup = map[string]appCleanup{}
	}
	for id, job := range expired {
		ownerPending := job.ChatState != "deleted" && (job.ThreadID != "" || job.ChatState != "")
		c := jobCleanupRecord(state.ID, job, ownerPending)
		c.RequireClosure = ownerPending
		state.Cleanup[id] = c
		delete(state.Jobs, id)
	}
	if err := s.saveApp(state); err != nil {
		return err
	}
	p := s.getPeer(state.Peer)
	for id := range expired {
		s.stopJobWork(p, state.ID, id)
	}
	return s.purgeExpiredAppFiles(state)
}

// Scan outside appMu; protect each final deletion with current owner references.
func (s *Service) pruneAppFiles(p *peer, now time.Time) (time.Duration, error) {
	next := time.Hour
	var scanErr error
	guard := func(id string) (bool, func(), error) {
		p.appMu.Lock()
		state, err := s.loadApp(p)
		if os.IsNotExist(err) {
			state, err = nil, nil
		}
		if err != nil {
			p.appMu.Unlock()
			return false, nil, err
		}
		return retainedFileIDs(state)[id], p.appMu.Unlock, nil
	}
	for _, store := range []*files.Store{s.inbox, s.outbox} {
		if store == nil {
			continue
		}
		delay, err := store.PrunePeerExpiredGuarded(now, appRetention, p.identity, guard)
		scanErr = errors.Join(scanErr, err)
		if delay < next {
			next = delay
		}
	}
	return next, scanErr
}

func (s *Service) retentionPass(now time.Time) (next time.Duration, passErr error) {
	generation := s.retentionPassStatus.Begin()
	defer func() { s.retentionPassStatus.Finish(generation, passErr, next) }()
	next = time.Hour
	passErr = errors.Join(passErr, s.recoverAppWrites())
	peers, err := s.storedPeers()
	passErr = errors.Join(passErr, err)
	seen := map[string]bool{}
	for _, p := range peers {
		seen[p.identity] = true
	}
	for _, store := range []*files.Store{s.inbox, s.outbox} {
		if store == nil {
			continue
		}
		ids, err := store.Peers()
		passErr = errors.Join(passErr, err)
		for _, id := range ids {
			if !seen[id] {
				peers = append(peers, s.getPeer(id))
				seen[id] = true
			}
		}
	}
	for _, p := range peers {
		p.appMu.Lock()
		state, err := s.loadApp(p)
		if os.IsNotExist(err) {
			state, err = nil, nil
		}
		if err != nil {
			p.appMu.Unlock()
			passErr = errors.Join(passErr, err)
			continue
		}
		if state != nil {
			err = s.expireApp(state, now)
		}
		if err != nil {
			p.appMu.Unlock()
			passErr = errors.Join(passErr, err)
			continue
		}
		if state == nil {
			p.appMu.Unlock()
		} else {
			for _, j := range state.Jobs {
				until := time.UnixMilli(j.Created).Add(appRetention).Sub(now)
				if until < next {
					next = until
				}
			}
			if len(state.Cleanup) > 0 && next > time.Minute {
				next = time.Minute
			}
			for _, expires := range state.DeletedRequests {
				if until := time.UnixMilli(expires).Sub(now); until < next {
					next = until
				}
			}
			p.appMu.Unlock()
		}
		delay, err := s.pruneAppFiles(p, now)
		passErr = errors.Join(passErr, err)
		if err != nil && next > time.Minute {
			next = time.Minute
		} else if delay < next {
			next = delay
		}
	}
	if passErr != nil {
		return time.Minute, passErr
	}
	if next < time.Second {
		next = time.Second
	}
	return next, nil
}
func (s *Service) retainApps() {
	for {
		delay, err := s.retentionPass(time.Now())
		if err != nil {
			delay = time.Minute
		}
		timer := time.NewTimer(delay)
		select {
		case <-s.ctx.Done():
			timer.Stop()
			return
		case <-s.retentionChanged:
			timer.Stop()
		case <-timer.C:
		}
	}
}
func (s *Service) notifyRetention() {
	select {
	case s.retentionChanged <- struct{}{}:
	default:
	}
}

package backend

import (
	"context"
	"errors"
	"sort"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

// Expired job content is replaced atomically by this bounded, exact-owner obligation.
type appCleanup struct {
	Created        int64    `json:"created"`
	Name           string   `json:"name"`
	Model          string   `json:"model,omitempty"`
	ServiceTier    string   `json:"serviceTier,omitempty"`
	ThreadID       string   `json:"threadId,omitempty"`
	PCPending      bool     `json:"pcPending,omitempty"`
	RequireClosure bool     `json:"requireClosure,omitempty"`
	Files          []string `json:"files,omitempty"`
}

// Only post-commit object unlink failures permit unrelated app work to continue.
type pendingAppCleanup struct{ cause error }

func (e *pendingAppCleanup) Error() string { return "expired_job_local_cleanup_pending" }
func (e *pendingAppCleanup) Unwrap() error { return e.cause }
func isPendingAppCleanup(err error) bool   { _, ok := err.(*pendingAppCleanup); return ok }

func (s *Service) expireAppForUse(p *peer, state *appState, now time.Time) error {
	err := s.expireApp(state, now)
	if !isPendingAppCleanup(err) {
		return err
	}
	generation := s.retentionPassStatus.Begin()
	s.retentionPassStatus.Finish(generation, err, time.Minute)
	latest, loadErr := s.loadApp(p)
	if loadErr != nil {
		return loadErr
	}
	*state = *latest
	return nil
}

func jobCleanupRecord(app string, job *appJob, pcPending bool) appCleanup {
	c := appCleanup{Created: job.Created, Name: job.Developer, Model: job.Model, ServiceTier: job.ServiceTier, ThreadID: job.ThreadID, PCPending: pcPending}
	if c.Name == "" {
		c.Name = "bridge_job_" + job.ID
	}
	for id := range jobFileIDs(app, job) {
		c.Files = append(c.Files, id)
	}
	sort.Strings(c.Files)
	return c
}

func validAppCleanup(id string, c appCleanup) bool {
	if !token.MatchString(id) || c.Created <= 0 || !appLabel(c.Name) || len(c.Model) > 128 || !protocol.ValidServiceTier(c.ServiceTier) || (c.ThreadID != "" && !nodeID.MatchString(c.ThreadID)) || len(c.Files) > 33 || (c.RequireClosure && !c.PCPending) {
		return false
	}
	seen := map[string]bool{}
	for _, id := range c.Files {
		if !token.MatchString(id) || seen[id] {
			return false
		}
		seen[id] = true
	}
	return true
}

func appCleanupView(state *appState) map[string]any {
	pcPending, filesPending, oldest := 0, 0, int64(0)
	for _, c := range state.Cleanup {
		if c.PCPending {
			pcPending++
		}
		filesPending += len(c.Files)
		deadline := time.UnixMilli(c.Created).Add(appRetention).UnixMilli()
		if oldest == 0 || deadline < oldest {
			oldest = deadline
		}
	}
	return map[string]any{"pendingOwners": len(state.Cleanup), "pcPending": pcPending, "filesPending": filesPending, "oldestDeadline": oldest, "overdue": len(state.Cleanup) > 0}
}

// appMu remains owned; failed unlinks remain journaled after the payload record is gone.
func (s *Service) purgeExpiredAppFiles(state *appState) error {
	keep := retainedFileIDs(state)
	changed := false
	var passErr error
	for id, c := range state.Cleanup {
		remaining := []string{}
		for _, file := range c.Files {
			if keep[file] {
				changed = true
				continue
			}
			var removeErr error
			for _, store := range []*files.Store{s.inbox, s.outbox} {
				if store != nil {
					removeErr = errors.Join(removeErr, store.Remove(state.Peer, file))
				}
			}
			if removeErr != nil {
				remaining = append(remaining, file)
				passErr = errors.Join(passErr, removeErr)
			} else {
				changed = true
			}
		}
		c.Files = remaining
		if !c.PCPending && len(c.Files) == 0 {
			delete(state.Cleanup, id)
			changed = true
		} else {
			state.Cleanup[id] = c
		}
	}
	if changed {
		if err := s.saveApp(state); err != nil {
			return errors.Join(passErr, err)
		}
	}
	if passErr != nil {
		return &pendingAppCleanup{passErr}
	}
	return nil
}

// Native delegation is idempotent; a late reply cannot overwrite another owner or registration.
func (s *Service) retryAppCleanup(p *peer, state *appState) (*appState, error) {
	ids := make([]string, 0, len(state.Cleanup))
	for id, cleanup := range state.Cleanup {
		if cleanup.PCPending {
			ids = append(ids, id)
		}
	}
	sort.Strings(ids)
	start := sort.Search(len(ids), func(i int) bool { return ids[i] > p.cleanupAfter })
	var passErr error
	for offset := 0; offset < len(ids) && offset < observationBatch; offset++ {
		id := ids[(start+offset)%len(ids)]
		original, exists := state.Cleanup[id]
		if !exists || !original.PCPending {
			continue
		}
		work := s.beginCleanupWork(p, state.ID, id)
		if work == nil {
			continue
		}
		p.cleanupAfter = id
		app := state.ID
		request := protocol.AdapterRequest{Operation: "discard", AppID: app, RequestID: id, CreatedAt: original.Created, Name: original.Name, Model: original.Model, ServiceTier: original.ServiceTier, ThreadID: original.ThreadID}
		p.appMu.Unlock()
		ctx, cancel := context.WithTimeout(work.ctx, 35*time.Second)
		outcome, callErr := s.native.Call(ctx, request)
		cancel()
		p.appMu.Lock()
		s.endJobWork(p, work)
		latest, err := s.loadApp(p)
		if err != nil {
			return nil, errors.Join(passErr, err)
		}
		state = latest
		current, exists := state.Cleanup[id]
		if state.ID != app || !exists || current.Created != original.Created || current.Name != original.Name || current.ThreadID != original.ThreadID || current.Model != original.Model || current.ServiceTier != original.ServiceTier || current.RequireClosure != original.RequireClosure {
			continue
		}
		var receipt appDiscardReceipt
		if callErr != nil || outcome.State != "ok" || protocol.DecodeStrict(outcome.Result, &receipt) != nil || !receipt.Accepted || receipt.AppID != app || receipt.RequestID != id {
			passErr = errors.Join(passErr, errors.New("expired_job_cleanup_unconfirmed"))
			continue
		}
		if current.RequireClosure && !receipt.Deleted {
			continue
		}
		current.PCPending = false
		current.RequireClosure = false
		state.Cleanup[id] = current
		if err := s.saveApp(state); err != nil {
			return state, err
		}
		if err := s.purgeExpiredAppFiles(state); err != nil {
			return state, err
		}
	}
	return state, passErr
}

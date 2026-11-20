package backend

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"sort"
	"sync"
	"time"

	"phonepcbridge/protocol"
)

func (s *Service) storedPeers() ([]*peer, error) {
	root, err := s.appRoot()
	if err != nil {
		return nil, err
	}
	defer root.Close()
	directory, err := root.Open(".")
	if err != nil {
		return nil, err
	}
	entries, err := directory.ReadDir(-1)
	directory.Close()
	if err != nil {
		return nil, err
	}
	peers := []*peer{}
	var scanErr error
	for _, entry := range entries {
		if len(entry.Name()) != 69 {
			continue
		}
		info, err := root.Lstat(entry.Name())
		if err != nil {
			scanErr = errors.Join(scanErr, fmt.Errorf("app record %s: %w", entry.Name(), err))
			continue
		}
		if !info.Mode().IsRegular() || info.Size() > maxAppRecordBytes {
			scanErr = errors.Join(scanErr, fmt.Errorf("app record %s: %w", entry.Name(), os.ErrInvalid))
			continue
		}
		f, err := root.Open(entry.Name())
		if err != nil {
			scanErr = errors.Join(scanErr, fmt.Errorf("app record %s: %w", entry.Name(), err))
			continue
		}
		data, err := io.ReadAll(io.LimitReader(f, maxAppRecordBytes+1))
		f.Close()
		if err != nil {
			scanErr = errors.Join(scanErr, fmt.Errorf("app record %s: %w", entry.Name(), err))
			continue
		}
		var state appState
		if decodeStoredApp(data, &state) != nil || !nodeID.MatchString(state.Peer) || appStateName(state.Peer) != entry.Name() {
			scanErr = errors.Join(scanErr, fmt.Errorf("app record %s: %w", entry.Name(), os.ErrInvalid))
			continue
		}
		peers = append(peers, s.getPeer(state.Peer))
	}
	return peers, scanErr
}
func (s *Service) workPass() (passErr error) {
	generation := s.jobPassStatus.Begin()
	retry := time.Duration(0)
	defer func() { s.jobPassStatus.Finish(generation, passErr, retry) }()
	peers, pcUnavailable, passErr := s.discoverJobWork()
	if len(peers) != 0 || passErr != nil {
		retry = jobPollInterval
	}
	for _, p := range peers {
		passErr = errors.Join(passErr, s.workPeer(p, pcUnavailable))
	}
	return passErr
}

func jobNeedsObservation(j *appJob) bool {
	return j.TurnID != "" && (j.State == "submitted" || j.State == "completed" && jobNeedsPC(j))
}

func jobNeedsWork(j *appJob) bool {
	return j.State == "queued" || j.State == "unknown" && j.TurnID == "" || jobNeedsObservation(j) || jobChatCleanupPending(j)
}

// Discover once before touching the PC, even when every registered app is idle.
func (s *Service) discoverJobWork() ([]*peer, error, error) {
	peers, scanErr := s.storedPeers()
	due := []*peer{}
	for _, p := range peers {
		p.appMu.Lock()
		state, err := s.loadApp(p)
		if err == nil {
			if expiryErr := s.expireApp(state, time.Now()); expiryErr != nil {
				scanErr = errors.Join(scanErr, expiryErr)
				if isPendingAppCleanup(expiryErr) {
					state, err = s.loadApp(p)
				} else {
					err = expiryErr
				}
			}
		}
		if err == nil {
			if len(state.Cleanup) > 0 {
				due = append(due, p)
			} else {
				for _, j := range state.Jobs {
					if jobNeedsWork(j) {
						due = append(due, p)
						break
					}
				}
			}
		}
		p.appMu.Unlock()
		scanErr = errors.Join(scanErr, err)
	}
	var pcUnavailable error
	if len(due) != 0 {
		probe, stop := context.WithTimeout(s.ctx, 5*time.Second)
		pcUnavailable = s.native.Reachable(probe)
		stop()
	}
	return due, pcUnavailable, scanErr
}
func (s *Service) workPeer(p *peer, pcUnavailable error) (passErr error) {
	if s.ctx.Err() != nil {
		return nil
	}
	p.appMu.Lock()
	defer p.appMu.Unlock()
	state, err := s.loadApp(p)
	if err != nil {
		return err
	}
	retentionErr := s.expireApp(state, time.Now())
	if retentionErr != nil {
		if !isPendingAppCleanup(retentionErr) {
			return retentionErr
		}
		state, err = s.loadApp(p)
		if err != nil {
			return errors.Join(retentionErr, err)
		}
	}
	defer func() { passErr = errors.Join(passErr, retentionErr) }()
	if pcUnavailable != nil {
		return nil
	}
	var cleanupErr error
	if len(state.Cleanup) > 0 {
		state, cleanupErr = s.retryAppCleanup(p, state)
		if state == nil {
			return cleanupErr
		}
	}
	defer func() { passErr = errors.Join(passErr, cleanupErr) }()
	var next *appJob
	for _, j := range state.Jobs {
		if j.State != "queued" || p.work[state.ID+"/"+j.ID] != nil {
			continue
		}
		if next == nil || j.Updated < next.Updated || (j.Updated == next.Updated && (j.Created < next.Created || (j.Created == next.Created && j.ID < next.ID))) {
			next = j
		}
	}
	if next != nil {
		if work := s.beginJobWork(p, state.ID, next); work != nil {
			err = s.tryQueuedApp(p, state, next)
			s.endJobWork(p, work)
			if err != nil {
				return err
			}
		}
	}
	ids := make([]string, 0, len(state.Jobs))
	for id := range state.Jobs {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	start := sort.Search(len(ids), func(i int) bool { return ids[i] > p.workAfter })
	observed := 0
	for offset := 0; offset < len(ids) && observed < observationBatch; offset++ {
		id := ids[(start+offset)%len(ids)]
		j := state.Jobs[id]
		if j == nil {
			continue
		}
		cleanup := jobChatCleanupPending(j)
		unknown := j.State == "unknown" && j.TurnID == ""
		observe := jobNeedsObservation(j)
		if !cleanup && !unknown && !observe {
			continue
		}
		work := s.beginJobWork(p, state.ID, j)
		if work == nil {
			continue
		}
		p.workAfter = id
		observed++
		state, err = s.workExistingJob(p, state, j)
		s.endJobWork(p, work)
		if err != nil {
			return err
		}
	}
	return nil
}

func (s *Service) workExistingJob(p *peer, state *appState, j *appJob) (*appState, error) {
	if jobChatCleanupPending(j) {
		return s.cleanupJobChat(p, state, j)
	}
	id := j.ID
	if j.State == "unknown" && j.TurnID == "" {
		snapshot, app := *j, state.ID
		parent := s.jobContext(p, app, id)
		p.appMu.Unlock()
		restoreLog := s.reconcileApp(parent, app, &snapshot)
		p.appMu.Lock()
		var err error
		state, err = s.loadApp(p)
		if err == nil {
			err = s.expireAppForUse(p, state, time.Now())
		}
		if err != nil {
			return state, err
		}
		j = state.Jobs[id]
		if j == nil {
			return state, nil
		}
		if state.ID == app && j.State == "unknown" && j.TurnID == "" && j.Fingerprint == snapshot.Fingerprint && j.ThreadID == snapshot.ThreadID && j.Model == snapshot.Model && j.Effort == snapshot.Effort && j.ServiceTier == snapshot.ServiceTier && (j.Code != snapshot.Code || snapshot.TurnID != "" || restoreLog != nil) {
			j.State, j.Code, j.TurnID, j.Updated = snapshot.State, snapshot.Code, snapshot.TurnID, snapshot.Updated
			if restoreLog != nil {
				appendJobLog(j, []appLogEntry{*restoreLog})
			}
			if err = s.saveApp(state); err != nil {
				return state, err
			}
		}
	}
	if jobNeedsObservation(j) {
		var err error
		if state, err = s.observeApp(p, state, j); err != nil {
			return state, err
		}
	}
	if j = state.Jobs[id]; j != nil {
		return s.interruptCancelledJob(p, state, j)
	}
	return state, nil
}
func (s *Service) tryQueuedApp(p *peer, state *appState, next *appJob) error {
	if cancelQueuedJob(next) {
		return s.saveApp(state)
	}
	before, _ := json.Marshal(next)
	app := state.ID
	parent := s.jobContext(p, app, next.ID)
	p.appMu.Unlock()
	var unavailable string
	var modelErr, errorDelivery error
	var authCode string
	var err error
	func() {
		defer p.appMu.Lock()
		auth := s.nativeAuth(parent)
		if auth.State != "ready" {
			authCode = auth.Code
			return
		}
		unavailable, modelErr = s.appModel(parent, next.Model, next.Effort, next.ServiceTier)
		if modelErr != nil || unavailable != "" {
			return
		}
		ctx, cancel := context.WithTimeout(parent, protocol.FileTransferTimeout)
		for _, file := range next.Input.Files {
			actual, data, readErr := s.inbox.OpenVerifiedContext(ctx, p.identity, file.ID)
			if readErr != nil || actual != file {
				if data != nil {
					data.Close()
				}
				cause := "pi_input_receipt_unconfirmed"
				err = readErr
				if err == nil {
					cause, err = "pi_input_descriptor_mismatch", os.ErrInvalid
				}
				appendJobLog(next, []appLogEntry{inputDeliveryLog(file.ID, cause, err)})
				break
			}
			err = s.native.Put(ctx, file, data)
			data.Close()
			if err != nil {
				appendJobLog(next, []appLogEntry{inputDeliveryLog(file.ID, "pc_input_receipt_unconfirmed", err)})
				break
			}
		}
		cancel()
		errorDelivery = err
	}()
	latest, loadErr := s.loadApp(p)
	if loadErr != nil {
		return loadErr
	}
	if loadErr = s.expireAppForUse(p, latest, time.Now()); loadErr != nil {
		return loadErr
	}
	live := latest.Jobs[next.ID]
	current, _ := json.Marshal(live)
	unchanged := latest.ID == app && live != nil && bytes.Equal(before, current)
	*state = *latest
	if !unchanged {
		return nil
	}
	state.Jobs[next.ID] = next
	if authCode != "" {
		next.Code = authCode
		next.Updated = time.Now().UnixMilli()
		appendJobLog(next, []appLogEntry{authJobLog(authCode)})
		return s.saveApp(state)
	}
	if modelErr != nil || unavailable != "" {
		next.Updated = time.Now().UnixMilli()
		next.Code = "model_catalog_unavailable"
		if modelErr == nil {
			next.State = "rejected"
			next.Code = unavailable
		}
		return s.saveApp(state)
	}
	if errorDelivery != nil {
		next.Code = "input_delivery_unconfirmed"
		next.Updated = time.Now().UnixMilli()
		saveErr := s.saveApp(state)
		return saveErr
	}
	if next.ThreadID == "" {
		latest, err := s.ensureJobChat(p, state, next)
		if err != nil {
			return err
		}
		*state = *latest
		next = state.Jobs[next.ID]
	}
	if state.ID != app || next == nil || next.State != "queued" || next.ThreadID == "" {
		return nil
	}
	if err = s.dispatchApp(p, state, next, next.Model, next.Effort); err != nil {
		return err
	}
	if err = s.saveApp(state); err != nil {
		return err
	}
	return nil
}
func (s *Service) workJobs() { s.workJobsEvery(jobPollInterval) }

func (s *Service) workJobsEvery(interval time.Duration) {
	type pass struct {
		peers       []*peer
		unavailable error
		scanErr     error
	}
	passes := make(chan pass, jobWorkers)
	var workers sync.WaitGroup
	for range jobWorkers {
		workers.Go(func() {
			for {
				select {
				case <-s.ctx.Done():
					return
				case current := <-passes:
					generation := s.jobPassStatus.Begin()
					err := current.scanErr
					for _, p := range current.peers {
						err = errors.Join(err, s.workPeer(p, current.unavailable))
					}
					s.jobPassStatus.Finish(generation, err, interval)
				}
			}
		})
	}
	defer workers.Wait()
	timer := time.NewTimer(interval)
	timer.Stop()
	defer timer.Stop()
	for s.ctx.Err() == nil {
		select {
		case <-s.jobsChanged:
		default:
		}
		peers, unavailable, scanErr := s.discoverJobWork()
		// Replace only queued snapshots; in-flight work keeps its exact job lease.
	drain:
		for {
			select {
			case <-passes:
			default:
				break drain
			}
		}
		if len(peers) != 0 {
			for range jobWorkers {
				passes <- pass{peers: peers, unavailable: unavailable, scanErr: scanErr}
			}
		} else {
			generation := s.jobPassStatus.Begin()
			delay := time.Duration(0)
			if scanErr != nil {
				delay = interval
			}
			s.jobPassStatus.Finish(generation, scanErr, delay)
		}
		var retry <-chan time.Time
		if len(peers) != 0 || scanErr != nil {
			timer.Reset(interval)
			retry = timer.C
		}
		select {
		case <-s.ctx.Done():
			return
		case <-s.jobsChanged:
			timer.Stop()
		case <-retry:
		}
	}
}

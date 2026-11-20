package backend

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"sync"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

// Caller holds appMu; transfer ownership stays pinned while network I/O releases it.
func (s *Service) stageOutput(parent context.Context, p *peer, state *appState, j *appJob, before []byte, f protocol.File) (protocol.File, string, bool, error) {
	app := state.ID
	p.appMu.Unlock()
	p.transfers.RLock()
	p.appMu.Lock()
	defer p.transfers.RUnlock()
	reload := func() (bool, error) {
		latest, err := s.loadApp(p)
		if err != nil {
			return false, err
		}
		if err := s.expireAppForUse(p, latest, time.Now()); err != nil {
			return false, err
		}
		current, _ := json.Marshal(latest.Jobs[j.ID])
		unchanged := latest.ID == app && latest.Jobs[j.ID] != nil && bytes.Equal(before, current)
		*state = *latest
		return unchanged, nil
	}
	if unchanged, err := reload(); err != nil || !unchanged {
		return protocol.File{}, "", true, err
	}
	var actual protocol.File
	var err error
	cause := "producer_output_unconfirmed"
	p.appMu.Unlock()
	func() {
		defer p.appMu.Lock()
		ctx, cancel := context.WithTimeout(parent, protocol.FileTransferTimeout)
		defer cancel()
		var cached io.ReadCloser
		actual, cached, err = s.outbox.OpenVerifiedContext(ctx, p.identity, f.ID)
		if cached != nil {
			cached.Close()
		}
		if err == nil || !errors.Is(err, files.ErrAbsent) {
			cause = "stored_output_unconfirmed"
			return
		}
		var source io.ReadCloser
		source, err = s.native.Output(ctx, f)
		if source != nil {
			closeSource := sync.OnceFunc(func() { _ = source.Close() })
			stop := context.AfterFunc(ctx, closeSource)
			defer stop()
			defer closeSource()
		}
		if err == nil {
			cause = "output_commit_unconfirmed"
			actual, err = s.outbox.PutContext(ctx, p.identity, f, source)
		}
	}()
	unchanged, reloadErr := reload()
	if reloadErr != nil {
		return actual, cause, true, reloadErr
	}
	if !unchanged {
		if err == nil && actual == f && state.ID == app && state.Jobs[j.ID] == nil {
			if conflict, _ := outputClaimConflict(state, j, []protocol.File{f}); conflict == "" {
				if cleanupErr := s.outbox.Remove(p.identity, f.ID); cleanupErr != nil {
					return actual, cause, true, cleanupErr
				}
			}
		}
		return actual, cause, true, nil
	}
	state.Jobs[j.ID] = j
	return actual, cause, false, err
}

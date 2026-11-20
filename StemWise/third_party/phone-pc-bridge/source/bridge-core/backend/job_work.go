package backend

import (
	"context"
	"time"
)

const appWorkLimit = 2
const observationBatch = 8
const jobWorkers = 4
const jobPollInterval = 10 * time.Second

type jobWork struct {
	ctx    context.Context
	cancel context.CancelFunc
	key    string
}

// appMu owns these leases; no two worker turns may operate on the same app/job.
func (s *Service) beginJobWork(p *peer, app string, j *appJob) *jobWork {
	deadline := time.UnixMilli(j.Created).Add(appRetention)
	if !deadline.After(time.Now()) {
		return nil
	}
	return s.beginOwnedWork(p, app, j.ID, deadline)
}

func (s *Service) beginCleanupWork(p *peer, app, id string) *jobWork {
	return s.beginOwnedWork(p, app, id, time.Time{})
}

func (s *Service) beginOwnedWork(p *peer, app, id string, deadline time.Time) *jobWork {
	key := app + "/" + id
	if s.ctx.Err() != nil || p.work[key] != nil || len(p.work) >= appWorkLimit {
		return nil
	}
	if p.work == nil {
		p.work = map[string]*jobWork{}
	}
	ctx, cancel := context.WithCancel(s.ctx)
	if !deadline.IsZero() {
		cancel()
		ctx, cancel = context.WithDeadline(s.ctx, deadline)
	}
	work := &jobWork{ctx: ctx, cancel: cancel, key: key}
	p.work[key] = work
	s.powerChanged()
	return work
}

func (s *Service) endJobWork(p *peer, work *jobWork) {
	interrupted := work.ctx.Err() != nil
	work.cancel()
	delete(p.work, work.key)
	s.powerChanged()
	if interrupted {
		s.notifyJobs()
	}
}

func (s *Service) jobContext(p *peer, app, id string) context.Context {
	if work := p.work[app+"/"+id]; work != nil {
		return work.ctx
	}
	return s.ctx
}

func (s *Service) stopJobWork(p *peer, app, id string) {
	if work := p.work[app+"/"+id]; work != nil {
		work.cancel()
	}
}

func (s *Service) notifyJobs() {
	select {
	case s.jobsChanged <- struct{}{}:
	default:
	}
}

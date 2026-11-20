package protocol

import (
	"errors"
	"os"
	"sync"
	"time"
)

// BackgroundPass keeps one bounded local observation, independent of job outcomes.
type BackgroundPass struct {
	mu       sync.Mutex
	status   BackgroundPassStatus
	failures uint64
}

type BackgroundPassStatus struct {
	Running          int    `json:"running"`
	LastSuccessAt    int64  `json:"lastSuccessAt"`
	LastFailureAt    int64  `json:"lastFailureAt"`
	LastFailureCause string `json:"lastFailureCause"`
	FailureActive    bool   `json:"failureActive"`
	RetryState       string `json:"retryState"`
	RetryDelayMs     int64  `json:"retryDelayMs"`
}

func (p *BackgroundPass) Begin() uint64 {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.status.Running++
	return p.failures
}

func (p *BackgroundPass) Finish(generation uint64, err error, delay time.Duration) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.status.Running--
	if err != nil {
		p.failures++
		p.status.LastFailureAt = time.Now().UnixMilli()
		cause := err.Error()
		var path *os.PathError
		var link *os.LinkError
		if errors.As(err, &path) {
			// Filesystem operation/cause is useful; another app's private path is not.
			cause = path.Op + ": " + path.Err.Error()
		} else if errors.As(err, &link) {
			cause = link.Op + ": " + link.Err.Error()
		}
		p.status.LastFailureCause = DiagnosticText(cause, 512)
		p.status.FailureActive = true
		p.status.RetryDelayMs = delay.Milliseconds()
	} else {
		p.status.LastSuccessAt = time.Now().UnixMilli()
		// An overlapping old or partial pass cannot establish recovery from a newer failure.
		if generation == p.failures && p.status.Running == 0 {
			p.status.FailureActive = false
		}
		if !p.status.FailureActive {
			p.status.RetryDelayMs = delay.Milliseconds()
		}
	}
}

func (p *BackgroundPass) Snapshot() BackgroundPassStatus {
	p.mu.Lock()
	defer p.mu.Unlock()
	s := p.status
	s.RetryState = "not_started"
	if s.Running > 0 {
		s.RetryState = "running"
	} else if s.FailureActive {
		s.RetryState = "pending"
	} else if s.LastSuccessAt > 0 {
		s.RetryState = "waiting"
	}
	return s
}

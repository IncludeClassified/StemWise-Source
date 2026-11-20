package pc

import (
	"time"
)

// Referenced job files remain owned until authenticated delivery or explicit cleanup.
func (s *Service) pruneFiles() {
	for {
		next := time.Hour
		release, admissionErr := s.beginPowerWork()
		if admissionErr != nil {
			timer := time.NewTimer(time.Minute)
			select {
			case <-s.ctx.Done():
				timer.Stop()
				return
			case <-timer.C:
			}
			continue
		}
		now := time.Now()
		s.refreshHandoffStatus(s.scanHandoffStatus)
		delay, pruneErr := s.pruneTransferFiles(now)
		if pruneErr != nil {
			next = time.Minute
		} else if delay < next {
			next = delay
		}
		if delay, err := s.pruneCreations(now); err != nil {
			next = time.Minute
		} else if delay < next {
			next = delay
		}
		if next < time.Second {
			next = time.Second
		}
		release()
		timer := time.NewTimer(next)
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

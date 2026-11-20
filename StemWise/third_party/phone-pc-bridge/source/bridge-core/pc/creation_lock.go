package pc

import "context"

type creationCall struct {
	gate  chan struct{}
	users int
}

// Only one exact peer/app/request may perform native creation or deletion at a time.
func (s *Service) claimCreation(ctx context.Context, peer, app, request string) (func(), error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	key := peer + ":" + creationID(peer, app, request, "request")
	s.creationMu.Lock()
	if s.creationCalls == nil {
		s.creationCalls = map[string]*creationCall{}
	}
	call := s.creationCalls[key]
	if call == nil {
		call = &creationCall{gate: make(chan struct{}, 1)}
		s.creationCalls[key] = call
	}
	call.users++
	s.creationMu.Unlock()
	drop := func() {
		s.creationMu.Lock()
		defer s.creationMu.Unlock()
		call.users--
		if call.users == 0 {
			delete(s.creationCalls, key)
		}
	}
	select {
	case call.gate <- struct{}{}:
		if err := ctx.Err(); err != nil {
			<-call.gate
			drop()
			return nil, err
		}
		return func() { <-call.gate; drop() }, nil
	case <-ctx.Done():
		drop()
		return nil, ctx.Err()
	}
}

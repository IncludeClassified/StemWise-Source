package pc

import (
	"context"
	"encoding/json"

	"phonepcbridge/protocol"
)

type selectionNative interface {
	Select(context.Context, string) (json.RawMessage, error)
}

// Only an exact persisted owner may restore a writer after its connection reopens.
func (s *Service) selectCreation(ctx context.Context, peer string, input protocol.AdapterRequest, native selectionNative) (json.RawMessage, error) {
	if !token.MatchString(input.AppID) || !token.MatchString(input.RequestID) {
		return nil, &creationError{code: "chat_ownership_unconfirmed"}
	}
	release, err := s.claimCreation(ctx, peer, input.AppID, input.RequestID)
	if err != nil {
		return nil, err
	}
	defer release()
	s.creationMu.Lock()
	defer s.creationMu.Unlock()
	request, canonical, expected, err := s.ownedCreationEnvelope(peer, input, false)
	if err != nil {
		return nil, err
	}
	if !s.sameCreation(peer, request, canonical, &expected) {
		return nil, &creationError{code: "chat_ownership_unconfirmed"}
	}
	return s.selectReservedCreation(ctx, peer, request, canonical, expected, native)
}

// Caller owns the exact lease and creationMu. Native I/O never owns the global mutex.
func (s *Service) selectReservedCreation(ctx context.Context, peer string, request creationInput, canonical []byte, expected creationReceipt, native selectionNative) (json.RawMessage, error) {
	s.creationMu.Unlock()
	raw, err := func() (json.RawMessage, error) {
		defer s.creationMu.Lock()
		return native.Select(ctx, expected.ThreadID)
	}()
	if err != nil {
		return nil, &creationError{code: "chat_writer_unconfirmed", unknown: true, cause: err}
	}
	var selected struct {
		Thread struct {
			ID     string `json:"id"`
			Direct *bool  `json:"canAcceptDirectInput"`
		} `json:"thread"`
	}
	if json.Unmarshal(raw, &selected) != nil || selected.Thread.ID != expected.ThreadID || selected.Thread.Direct == nil || !*selected.Thread.Direct || !s.sameCreation(peer, request, canonical, &expected) {
		return nil, &creationError{code: "chat_writer_unconfirmed", unknown: true}
	}
	return raw, nil
}

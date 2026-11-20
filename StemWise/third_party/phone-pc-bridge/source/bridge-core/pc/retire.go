package pc

import (
	"context"
	"encoding/json"

	"phonepcbridge/protocol"
)

type retirementNative interface {
	selectionNative
	RetireTurn(context.Context, string, string) (json.RawMessage, error)
}

// A turn-interrupt acknowledgment is not a receipt for retirement of its tools.
func (s *Service) retireCreation(ctx context.Context, peer string, input protocol.AdapterRequest, native retirementNative) (json.RawMessage, error) {
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
	request, canonical, expected, err := s.ownedCreationEnvelope(peer, input, true)
	if err != nil {
		return nil, err
	}
	if !s.sameCreation(peer, request, canonical, &expected) {
		return nil, &creationError{code: "chat_ownership_unconfirmed"}
	}
	if _, err = s.selectReservedCreation(ctx, peer, request, canonical, expected, native); err != nil {
		return nil, err
	}
	s.creationMu.Unlock()
	raw, err := func() (json.RawMessage, error) {
		defer s.creationMu.Lock()
		return native.RetireTurn(ctx, input.ThreadID, input.TurnID)
	}()
	if err != nil {
		return nil, &creationError{code: "turn_retirement_unconfirmed", unknown: true, cause: err}
	}
	var receipt struct {
		ThreadID         string `json:"threadId"`
		TurnID           string `json:"turnId"`
		TerminalsRetired bool   `json:"terminalsRetired"`
	}
	if protocol.DecodeStrict(raw, &receipt) != nil || receipt.ThreadID != input.ThreadID || receipt.TurnID != input.TurnID || !receipt.TerminalsRetired || !s.sameCreation(peer, request, canonical, &expected) {
		return nil, &creationError{code: "turn_retirement_unconfirmed", unknown: true}
	}
	return raw, nil
}

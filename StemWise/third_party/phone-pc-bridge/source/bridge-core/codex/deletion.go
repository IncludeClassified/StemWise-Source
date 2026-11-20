package codex

import (
	"context"
	"encoding/json"
	"errors"
)

func (c *Connection) prepareThreadDeletion(ctx context.Context, id string) (*uint64, error) {
	live, err := c.Select(ctx, id)
	if err != nil {
		var rpc *RPCError
		if !errors.As(err, &rpc) {
			return nil, &OutcomeUnknown{err}
		}
		// A native rejection need not mean absence. Only global runtime idleness
		// permits the native idempotent exact-ID operation without guessing error text.
		version, err := c.inspectIdle(ctx)
		if err != nil {
			return nil, &OutcomeUnknown{err}
		}
		return &version, nil
	}
	status, _ := threadID(live, id)
	raw, err := c.History(ctx, id, "", 1)
	var page struct {
		Data []struct {
			ID        string `json:"id"`
			Status    string `json:"status"`
			Committed bool   `json:"bridgeTerminalCommitted"`
		} `json:"data"`
	}
	if err != nil || json.Unmarshal(raw, &page) != nil || page.Data == nil || len(page.Data) > 1 {
		return nil, &OutcomeUnknown{errors.New("Exact owned latest turn unavailable for deletion")}
	}
	if len(page.Data) == 1 {
		turn := page.Data[0]
		if turn.Status == "inProgress" {
			if _, err := c.Interrupt(ctx, id, turn.ID); err != nil {
				return nil, err
			}
			return nil, &OutcomeUnknown{errors.New("Owned chat interruption requested; deletion awaits native stop")}
		}
		if !turn.Committed {
			return nil, &OutcomeUnknown{errors.New("Owned terminal turn remains unconfirmed for deletion")}
		}
		if _, err := c.RetireTurn(ctx, id, turn.ID); err != nil {
			return nil, err
		}
		return nil, nil
	}
	if status != "idle" {
		return nil, &OutcomeUnknown{errors.New("Empty owned chat is not idle for deletion")}
	}
	ack, err := c.request(ctx, "thread/backgroundTerminals/clean", map[string]string{"threadId": id})
	var result map[string]json.RawMessage
	if err != nil || json.Unmarshal(ack, &result) != nil || result == nil || len(result) != 0 {
		return nil, &OutcomeUnknown{errors.New("Empty owned chat background retirement unconfirmed")}
	}
	if err := c.requireNoBackgroundTerminals(ctx, id); err != nil {
		return nil, &OutcomeUnknown{err}
	}
	latest, err := c.History(ctx, id, "", 1)
	if err != nil || json.Unmarshal(latest, &page) != nil || page.Data == nil || len(page.Data) != 0 {
		return nil, &OutcomeUnknown{errors.New("Empty owned chat changed during deletion retirement")}
	}
	live, err = c.Read(ctx, id)
	if err != nil {
		return nil, &OutcomeUnknown{err}
	}
	status, err = threadID(live, id)
	if err != nil || status != "idle" || directInput(live, false) != nil {
		return nil, &OutcomeUnknown{errors.New("Empty owned writer changed during deletion retirement")}
	}
	return nil, nil
}

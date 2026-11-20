package codex

import (
	"context"
	"encoding/json"
	"errors"
	"time"
)

// RetireTurn cleans background terminals only after the exact selected thread's
// latest turn is committed terminal. An acknowledgement alone is not retirement.
func (c *Connection) RetireTurn(ctx context.Context, threadID, turnID string) (json.RawMessage, error) {
	if err := validID(threadID); err != nil {
		return nil, err
	}
	if err := validID(turnID); err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	before, err := c.retirementSnapshot(ctx, threadID, turnID)
	if err != nil {
		return nil, &OutcomeUnknown{err}
	}
	raw, err := c.request(ctx, "thread/backgroundTerminals/clean", map[string]string{"threadId": threadID})
	var ack map[string]json.RawMessage
	if err != nil {
		return nil, &OutcomeUnknown{err}
	}
	if json.Unmarshal(raw, &ack) != nil || ack == nil || len(ack) != 0 {
		return nil, &OutcomeUnknown{errors.New("Malformed native background-terminal cleanup acknowledgement")}
	}
	if err := c.requireNoBackgroundTerminals(ctx, threadID); err != nil {
		return nil, &OutcomeUnknown{err}
	}
	after, err := c.retirementSnapshot(ctx, threadID, turnID)
	if err != nil {
		return nil, &OutcomeUnknown{err}
	}
	if after != before {
		return nil, &OutcomeUnknown{errors.New("Native terminal turn changed during background cleanup")}
	}
	return json.Marshal(struct {
		ThreadID         string `json:"threadId"`
		TurnID           string `json:"turnId"`
		TerminalsRetired bool   `json:"terminalsRetired"`
	}{threadID, turnID, true})
}

func (c *Connection) retirementSnapshot(ctx context.Context, id, turnID string) (string, error) {
	c.mu.Lock()
	selected := c.selected[id]
	c.mu.Unlock()
	if !selected {
		return "", errors.New("Select the exact owned session before retiring background terminals")
	}
	raw, err := c.History(ctx, id, "", 1)
	if err != nil {
		return "", err
	}
	var history struct {
		Data []struct {
			ID                      string `json:"id"`
			Status                  string `json:"status"`
			BridgeTerminalCommitted bool   `json:"bridgeTerminalCommitted"`
		} `json:"data"`
	}
	if json.Unmarshal(raw, &history) != nil || len(history.Data) != 1 || history.Data[0].ID != turnID ||
		!history.Data[0].BridgeTerminalCommitted || history.Data[0].Status == "inProgress" {
		return "", errors.New("Exact latest native terminal turn remains unconfirmed")
	}
	live, err := c.Read(ctx, id)
	if err != nil {
		return "", err
	}
	status, err := threadID(live, id)
	if err != nil || (status != "idle" && status != "systemError") {
		return "", errors.New("Native writer is not stopped for exact turn retirement")
	}
	if err := directInput(live, false); err != nil {
		return "", err
	}
	c.mu.Lock()
	selected = c.selected[id]
	c.mu.Unlock()
	if !selected {
		return "", errors.New("Owned session selection changed during background cleanup")
	}
	return history.Data[0].Status, nil
}

func (c *Connection) requireNoBackgroundTerminals(ctx context.Context, id string) error {
	cursor := ""
	seen := map[string]bool{}
	for n := 0; n < 16; n++ {
		params, _ := page(cursor, 100)
		params["threadId"] = id
		raw, err := c.request(ctx, "thread/backgroundTerminals/list", params)
		if err != nil {
			return err
		}
		var result struct {
			Data       []json.RawMessage `json:"data"`
			NextCursor json.RawMessage   `json:"nextCursor"`
		}
		if json.Unmarshal(raw, &result) != nil || result.Data == nil {
			return errors.New("Malformed native background-terminal page")
		}
		if len(result.Data) != 0 {
			return errors.New("Native background terminals are still running")
		}
		if len(result.NextCursor) == 0 || string(result.NextCursor) == "null" {
			return nil
		}
		var next string
		if json.Unmarshal(result.NextCursor, &next) != nil || len(next) > 4096 || seen[next] {
			return errors.New("Native background-terminal cursor did not progress")
		}
		if next == "" {
			return nil
		}
		seen[next], cursor = true, next
	}
	return errors.New("Native background-terminal listing limit reached; retirement unconfirmed")
}

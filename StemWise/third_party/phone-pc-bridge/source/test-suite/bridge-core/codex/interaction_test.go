package codex

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
)

func TestHistoryInteractionMarkerRequiresActualPendingNativeRequest(t *testing.T) {
	c := peer(t, "normal")
	ctx := context.Background()
	if _, err := c.Select(ctx, "thread-1"); err != nil {
		t.Fatal(err)
	}
	if _, err := sendJob(t, c, ctx, "thread-1", "owned pending interaction"); err != nil {
		t.Fatal(err)
	}
	event(t, c, "item/agentMessage/delta")
	request := event(t, c, "item/commandExecution/requestApproval")
	check := func(thread, turn, status string, loaded, expected bool) {
		t.Helper()
		page := map[string]any{"data": []any{map[string]any{"id": turn, "status": status, "items": []any{}, "bridgeInteractionRequired": true}}, "nextCursor": nil}
		data, _ := json.Marshal(page)
		raw, err := c.reportHistory(ctx, thread, data, loaded)
		var result struct {
			Data []struct{ BridgeInteractionRequired bool }
		}
		if err != nil || json.Unmarshal(raw, &result) != nil || len(result.Data) != 1 || result.Data[0].BridgeInteractionRequired != expected {
			t.Fatal("Interaction marker trusted stored metadata or lost its native scope", string(raw), err)
		}
		if strings.Contains(string(raw), "decision") || strings.Contains(string(raw), "approval-1") {
			t.Fatal("Interaction marker exported request contents or an answer surface")
		}
	}
	check("thread-1", "turn-1", "inProgress", true, true)
	c.mu.Lock()
	actual := c.requests[string(request.ID)]
	withoutTurn := actual
	withoutTurn.Params = json.RawMessage(`{"threadId":"thread-1"}`)
	c.requests[string(request.ID)] = withoutTurn
	c.mu.Unlock()
	check("thread-1", "turn-1", "inProgress", true, true)
	check("thread-1", "turn-1", "inProgress", false, false)
	ambiguous := json.RawMessage(`{"data":[{"id":"turn-1","status":"inProgress","items":[]},{"id":"other-turn","status":"inProgress","items":[]}],"nextCursor":null}`)
	if raw, err := c.reportHistory(ctx, "thread-1", ambiguous, true); err != nil || strings.Contains(string(raw), `"bridgeInteractionRequired":true`) {
		t.Fatal("Request without a turn ID was attributed to ambiguous active history", err)
	}
	c.mu.Lock()
	c.requests[string(request.ID)] = actual
	c.mu.Unlock()
	check("thread-1", "other-turn", "inProgress", true, false)
	check("other-thread", "turn-1", "inProgress", true, false)
	check("thread-1", "turn-1", "inProgress", false, false)
	c.mu.Lock()
	delete(c.requests, string(request.ID))
	c.mu.Unlock()
	check("thread-1", "turn-1", "inProgress", true, false)
	check("thread-1", "turn-1", "completed", true, false)
}

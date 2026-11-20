package codex

import (
	"context"
	"encoding/json"
	"errors"
	"sort"
)

// RequireIdle is a read-only observation. The host must serialize native operations
// while inspecting; use CloseWhenIdle when committing an owned-runtime replacement.
func (c *Connection) RequireIdle(ctx context.Context) error {
	_, err := c.inspectIdle(ctx)
	return err
}

// CloseWhenIdle fences the final observation and connection closure under the same mutex.
// No interrupt, terminal cleanup or other native mutation is used to manufacture idleness.
func (c *Connection) CloseWhenIdle(ctx context.Context) error {
	version, err := c.inspectIdle(ctx)
	if err != nil {
		return err
	}
	c.mu.Lock()
	err = c.localIdleLocked()
	if err == nil && c.runtimeVersion != version {
		err = authFailure("runtime/idle", "native_runtime_unconfirmed", nil)
	}
	if err == nil && ctx.Err() != nil {
		err = authFailure("runtime/idle", "native_runtime_unconfirmed", ctx.Err())
	}
	closed := false
	if err == nil {
		closed = c.failLocked(errors.New("Codex idle runtime replaced"))
	}
	c.mu.Unlock()
	if err != nil {
		return err
	}
	if closed {
		c.stopFailed()
	}
	if err := c.Close(); err != nil {
		return authFailure("runtime/idle", "native_runtime_unconfirmed", err)
	}
	return nil
}

func (c *Connection) localIdleLocked() error {
	if c.err != nil || c.runtimeUncertain || c.turnKnowledgeLost {
		return authFailure("runtime/idle", "native_runtime_unconfirmed", nil)
	}
	if len(c.pending) != 0 || len(c.requests) != 0 || len(c.write) != 0 || len(c.events) != 0 {
		return authFailure("runtime/idle", "native_runtime_busy", nil)
	}
	select {
	case <-c.reaped:
		return authFailure("runtime/idle", "native_runtime_unconfirmed", nil)
	default:
	}
	select {
	case <-c.readDone:
		return authFailure("runtime/idle", "native_runtime_unconfirmed", nil)
	default:
	}
	if len(c.runtimeItems) != 0 {
		return authFailure("runtime/idle", "native_runtime_busy", nil)
	}
	for _, status := range c.turns {
		if status == "inProgress" {
			return authFailure("runtime/idle", "native_runtime_busy", nil)
		}
		if !turnStatus(status) {
			return authFailure("runtime/idle", "native_runtime_unconfirmed", nil)
		}
	}
	return nil
}

// Observe process/tool lifecycle before queued events are delivered to the host.
func (c *Connection) observeRuntime(m Message) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.runtimeVersion++
	if m.Method != "item/started" && m.Method != "item/completed" {
		return
	}
	var p struct {
		ThreadID string `json:"threadId"`
		TurnID   string `json:"turnId"`
		Item     struct {
			ID   string `json:"id"`
			Type string `json:"type"`
		} `json:"item"`
	}
	if json.Unmarshal(m.Params, &p) != nil || validID(p.ThreadID) != nil || validID(p.TurnID) != nil || validID(p.Item.ID) != nil || p.Item.Type == "" {
		c.runtimeUncertain = true
		return
	}
	key := itemKey{p.ThreadID, p.TurnID, p.Item.ID}
	if m.Method == "item/completed" {
		delete(c.runtimeItems, key)
		return
	}
	if c.runtimeItems == nil {
		c.runtimeItems = map[itemKey]bool{}
	}
	if len(c.runtimeItems) >= 128 {
		c.runtimeUncertain = true
		return
	}
	c.runtimeItems[key] = true
}

func (c *Connection) inspectIdle(ctx context.Context) (uint64, error) {
	if err := ctx.Err(); err != nil {
		return 0, authFailure("runtime/idle", "native_runtime_unconfirmed", err)
	}
	c.mu.Lock()
	err := c.localIdleLocked()
	version := c.runtimeVersion
	c.mu.Unlock()
	if err != nil {
		return 0, err
	}
	before, err := c.loadedThreads(ctx)
	if err != nil {
		return 0, err
	}
	for _, id := range before {
		raw, err := c.Read(ctx, id)
		if err != nil {
			return 0, authFailure("runtime/idle", "native_runtime_unconfirmed", err)
		}
		status, err := threadID(raw, id)
		if err != nil {
			return 0, authFailure("runtime/idle", "native_runtime_unconfirmed", err)
		}
		if status != "idle" {
			return 0, authFailure("runtime/idle", "native_runtime_busy", nil)
		}
		if err := c.requireNoBackgroundTerminals(ctx, id); err != nil {
			return 0, authFailure("runtime/idle", "native_runtime_unconfirmed", err)
		}
	}
	after, err := c.loadedThreads(ctx)
	if err != nil {
		return 0, err
	}
	if len(before) != len(after) {
		return 0, authFailure("runtime/idle", "native_runtime_unconfirmed", nil)
	}
	for index := range before {
		if before[index] != after[index] {
			return 0, authFailure("runtime/idle", "native_runtime_unconfirmed", nil)
		}
	}
	c.mu.Lock()
	err = c.localIdleLocked()
	if err == nil && version != c.runtimeVersion {
		err = authFailure("runtime/idle", "native_runtime_unconfirmed", nil)
	}
	c.mu.Unlock()
	return version, err
}

func (c *Connection) loadedThreads(ctx context.Context) ([]string, error) {
	result := []string{}
	seen, cursors := map[string]bool{}, map[string]bool{}
	cursor := ""
	for index := 0; index < 16; index++ {
		params, _ := page(cursor, 100)
		raw, err := c.request(ctx, "thread/loaded/list", params)
		if err != nil {
			return nil, authFailure("runtime/idle", "native_runtime_unconfirmed", err)
		}
		var reply struct {
			Data       []string        `json:"data"`
			NextCursor json.RawMessage `json:"nextCursor"`
		}
		if json.Unmarshal(raw, &reply) != nil || reply.Data == nil || len(reply.Data) > 100 {
			return nil, authFailure("runtime/idle", "native_runtime_unconfirmed", nil)
		}
		for _, id := range reply.Data {
			if validID(id) != nil || seen[id] {
				return nil, authFailure("runtime/idle", "native_runtime_unconfirmed", nil)
			}
			seen[id] = true
			result = append(result, id)
		}
		if len(reply.NextCursor) == 0 || string(reply.NextCursor) == "null" {
			sort.Strings(result)
			return result, nil
		}
		var next string
		if json.Unmarshal(reply.NextCursor, &next) != nil || len(next) > 4096 || cursors[next] {
			return nil, authFailure("runtime/idle", "native_runtime_unconfirmed", nil)
		}
		if next == "" {
			sort.Strings(result)
			return result, nil
		}
		cursors[next], cursor = true, next
	}
	return nil, authFailure("runtime/idle", "native_runtime_unconfirmed", nil)
}

// Package codex drives the installed CLI or an explicitly selected shared app-server.
package codex

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"time"
	"unicode/utf8"

	"phonepcbridge/protocol"
)

const maxMessage = 4 << 20

type Message struct {
	ID     json.RawMessage `json:"id,omitempty"`
	Method string          `json:"method,omitempty"`
	Params json.RawMessage `json:"params,omitempty"`
	Result json.RawMessage `json:"result,omitempty"`
	Error  *RPCError       `json:"error,omitempty"`
}

type RPCError struct {
	Code    int             `json:"code"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data,omitempty"`
}

func (e *RPCError) Error() string {
	return fmt.Sprintf("Codex rejected request (%d): %s", e.Code, e.Message)
}

// OutcomeUnknown means an attempted mutation must not be automatically replayed.
type OutcomeUnknown struct{ Cause error }

func (e *OutcomeUnknown) Error() string { return "Codex outcome unknown: " + e.Cause.Error() }
func (e *OutcomeUnknown) Unwrap() error { return e.Cause }

// RequestNotSent proves this request never entered the native transport writer.
type RequestNotSent struct{ Cause error }

func (e *RequestNotSent) Error() string { return e.Cause.Error() }
func (e *RequestNotSent) Unwrap() error { return e.Cause }

// StartupCleanupError retains the failed attempt until its owner proves retirement.
type StartupCleanupError struct {
	Cause error
	Owner *Connection
}

func (e *StartupCleanupError) Error() string { return e.Cause.Error() }
func (e *StartupCleanupError) Unwrap() error { return e.Cause }

type processOwner interface {
	started(*exec.Cmd) error
	terminate() error
	retire() error
}

type reply struct {
	message Message
	err     error
}

func (r reply) result() (json.RawMessage, error) {
	if r.err != nil {
		return nil, r.err
	}
	if r.message.Error != nil {
		return nil, r.message.Error
	}
	return r.message.Result, nil
}

type itemKey struct{ thread, turn, item string }
type turnKey struct{ thread, turn string }

type Connection struct {
	cmd               *exec.Cmd
	input             io.WriteCloser
	output            io.ReadCloser
	process           *processDiagnostic
	write             chan struct{}
	writeTimeout      time.Duration
	mu                sync.Mutex
	next              uint64
	pending           map[string]chan reply
	requests          map[string]Message
	selected          map[string]bool
	messages          map[itemKey][32]byte
	turns             map[turnKey]string
	turnKnowledgeLost bool
	runtimeVersion    uint64
	runtimeUncertain  bool
	runtimeItems      map[itemKey]bool
	err               error
	done              chan struct{}
	events            chan Message
	reaped            chan struct{}
	readDone          chan struct{}
	tree              processOwner
	retired           chan struct{}
	cleanupErr        error
}

// Connect owns a CLI stdio backend by default. An explicit socket attaches only
// to that shared daemon; failed attachment never falls back to another backend.
// A StartupCleanupError retains its failed owner; retire it before another Connect.
func Connect(ctx context.Context, executable, socket string) (*Connection, error) {
	args := []string{"app-server", "--stdio"}
	if socket != "" {
		args = []string{"app-server", "proxy", "--sock", socket}
	}
	return connectMode(ctx, exec.Command(executable, args...), socket == "")
}

func connect(ctx context.Context, cmd *exec.Cmd) (*Connection, error) {
	return connectMode(ctx, cmd, true)
}

func connectMode(ctx context.Context, cmd *exec.Cmd, ownTree bool) (*Connection, error) {
	return connectModeWithTree(ctx, cmd, ownTree, func(cmd *exec.Cmd) (processOwner, error) {
		tree, err := prepareProcessTree(cmd)
		if tree == nil {
			return nil, err
		}
		return tree, err
	})
}

func connectModeWithTree(ctx context.Context, cmd *exec.Cmd, ownTree bool, prepare func(*exec.Cmd) (processOwner, error)) (*Connection, error) {
	input, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	output, outputWriter, err := os.Pipe()
	if err != nil {
		input.Close()
		return nil, err
	}
	cmd.Stdout = outputWriter
	diagnostic, err := newProcessDiagnostic()
	if err != nil {
		input.Close()
		output.Close()
		outputWriter.Close()
		return nil, err
	}
	cmd.Stderr = diagnostic
	cmd.WaitDelay = time.Second
	var tree processOwner
	if ownTree {
		tree, err = prepare(cmd)
	}
	if err == nil {
		err = cmd.Start()
		if err == nil {
			diagnostic.mu.Lock()
			diagnostic.pid = cmd.Process.Pid
			diagnostic.mu.Unlock()
		}
	}
	if err == nil && tree != nil {
		err = tree.started(cmd)
	}
	startupErr := err
	outputWriter.Close()
	c := &Connection{cmd: cmd, input: input, output: output, process: diagnostic, write: make(chan struct{}, 1),
		pending: make(map[string]chan reply), requests: make(map[string]Message),
		selected: make(map[string]bool), done: make(chan struct{}),
		events: make(chan Message, 128), reaped: make(chan struct{}), readDone: make(chan struct{}), tree: tree, retired: make(chan struct{})}
	diagnostic.reaped = c.reaped
	if cmd.Process == nil {
		input.Close()
		if childInput, ok := cmd.Stdin.(io.Closer); ok {
			childInput.Close()
		}
		output.Close()
		close(c.readDone)
		close(c.events)
	} else {
		go func() {
			readErr := c.read(output)
			close(c.readDone)
			if errors.Is(readErr, io.EOF) {
				// A normal exit closes stdout before Wait; preserve its actual exit status.
				timer := time.NewTimer(250 * time.Millisecond)
				select {
				case <-c.reaped:
				case <-timer.C:
				}
				timer.Stop()
			}
			c.fail(readErr)
		}()
	}
	go func() {
		if cmd.Process != nil {
			waitErr := cmd.Wait()
			diagnostic.finish(cmd, waitErr)
		} else {
			diagnostic.mu.Lock()
			diagnostic.waitState = "not_started"
			diagnostic.mu.Unlock()
		}
		close(c.reaped)
		if tree != nil {
			err := tree.retire()
			c.mu.Lock()
			c.cleanupErr = err
			c.mu.Unlock()
		}
		close(c.retired)
		// Descendants may retain stdout after our child exits; buffered valid
		// messages get a bounded drain before the owned reader is closed.
		timer := time.NewTimer(250 * time.Millisecond)
		defer timer.Stop()
		select {
		case <-c.readDone:
		case <-timer.C:
			c.fail(errors.New("Codex process exited before protocol stream closed"))
		}
	}()
	if startupErr != nil {
		if cmd.Process != nil {
			// Membership may have failed before the suspended child joined its tree.
			diagnostic.terminating()
			if err := cmd.Process.Kill(); err != nil && !errors.Is(err, os.ErrProcessDone) {
				startupErr = errors.Join(startupErr, err)
			}
		}
		return c.failedStartup(startupErr)
	}
	_, err = c.request(ctx, "initialize", map[string]any{
		"clientInfo":   map[string]string{"name": "phone_pc_bridge", "title": "Phone-PC Bridge", "version": "0.1"},
		"capabilities": map[string]bool{"experimentalApi": true}})
	if err == nil {
		_, err = c.send(ctx, map[string]any{"method": "initialized", "params": map[string]any{}})
	}
	if err != nil {
		return c.failedStartup(err)
	}
	return c, nil
}

func (c *Connection) failedStartup(err error) (*Connection, error) {
	failure := c.processError(err)
	if cleanup := c.Close(); cleanup != nil {
		return nil, &StartupCleanupError{Cause: errors.Join(failure, cleanup), Owner: c}
	}
	return nil, failure
}

func (c *Connection) fail(err error) {
	c.mu.Lock()
	failed := c.failLocked(err)
	c.mu.Unlock()
	if failed {
		c.stopFailed()
	}
}

// Caller owns mu; closing and the last runtime inspection share this commit point.
func (c *Connection) failLocked(err error) bool {
	if c.err != nil {
		return false
	}
	err = c.processError(err)
	c.err = err
	close(c.done)
	for id, waiter := range c.pending {
		waiter <- reply{err: err}
		delete(c.pending, id)
	}
	clear(c.requests)
	clear(c.messages)
	clear(c.turns)
	return true
}

func (c *Connection) stopFailed() {
	// End our owned CLI tree or only the attaching proxy, never its shared daemon.
	c.terminate()
	c.input.Close()
	c.output.Close()
}

func (c *Connection) processError(err error) error {
	var process *ProcessError
	if errors.As(err, &process) {
		return err
	}
	return &ProcessError{Cause: err, diagnostic: c.process}
}

func (c *Connection) failure() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.err
}

func (c *Connection) terminate() {
	if c.tree != nil {
		select {
		case <-c.reaped:
		default:
			c.process.terminating()
		}
		_ = c.tree.terminate()
		return
	}
	select {
	case <-c.reaped:
		return
	default:
	}
	if c.cmd.Process == nil {
		return
	}
	c.process.terminating()
	_ = c.cmd.Process.Kill()
}

func (c *Connection) Close() error {
	c.fail(errors.New("Codex connection closed"))
	timer := time.NewTimer(processReapTimeout)
	defer timer.Stop()
	for _, done := range []<-chan struct{}{c.reaped, c.retired, c.readDone} {
		select {
		case <-done:
		case <-timer.C:
			return c.processError(errors.New("Codex process cleanup unconfirmed"))
		}
	}
	c.mu.Lock()
	err := c.cleanupErr
	c.mu.Unlock()
	if err != nil {
		return c.processError(err)
	}
	return nil
}

func (c *Connection) read(output io.ReadCloser) error {
	defer output.Close()
	defer close(c.events)
	scanner := bufio.NewScanner(output)
	scanner.Buffer(make([]byte, 4096), maxMessage)
	for scanner.Scan() {
		select {
		case <-c.done:
			return errors.New("Codex connection closed")
		default:
		}
		var m Message
		if json.Unmarshal(scanner.Bytes(), &m) != nil {
			return errors.New("Malformed Codex message")
		}
		if len(m.ID) != 0 && m.Method == "" {
			c.mu.Lock()
			waiter := c.pending[string(m.ID)]
			delete(c.pending, string(m.ID))
			c.mu.Unlock()
			if waiter == nil || (len(m.Result) == 0) == (m.Error == nil) {
				err := errors.New("Uncorrelated Codex response")
				if waiter != nil {
					waiter <- reply{err: c.processError(err)}
				}
				return err
			}
			waiter <- reply{message: m}
			continue
		}
		if m.Method == "" {
			return errors.New("Missing Codex event method")
		}
		c.observeRuntime(m)
		c.observeItems(m)
		if len(m.ID) != 0 {
			c.mu.Lock()
			previous, duplicate := c.requests[string(m.ID)]
			conflict := duplicate && (previous.Method != m.Method || !bytes.Equal(previous.Params, m.Params))
			if !duplicate {
				c.requests[string(m.ID)] = m
			}
			c.mu.Unlock()
			if conflict {
				return errors.New("Conflicting Codex server request")
			}
		}
		if m.Method == "serverRequest/resolved" {
			var p struct {
				RequestID json.RawMessage `json:"requestId"`
			}
			if json.Unmarshal(m.Params, &p) != nil {
				return errors.New("Malformed request resolution")
			}
			c.mu.Lock()
			delete(c.requests, string(p.RequestID))
			c.mu.Unlock()
		}
		select {
		case c.events <- m:
		case <-c.done:
			return errors.New("Codex connection closed")
		}
	}
	if scanner.Err() != nil {
		return errors.New("Codex stream unreadable or oversized")
	}
	return io.EOF
}

// Retain native turn state and completed message hashes outside stored history.
func (c *Connection) observeItems(m Message) {
	if m.Method != "item/completed" && m.Method != "turn/started" && m.Method != "turn/completed" {
		return
	}
	var p struct {
		ThreadID string `json:"threadId"`
		TurnID   string `json:"turnId"`
		Turn     struct {
			ID     string `json:"id"`
			Status string `json:"status"`
		} `json:"turn"`
		Item struct {
			ID   string  `json:"id"`
			Type string  `json:"type"`
			Text *string `json:"text"`
		} `json:"item"`
	}
	if json.Unmarshal(m.Params, &p) != nil {
		c.mu.Lock()
		c.runtimeUncertain = true
		c.mu.Unlock()
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if m.Method == "turn/started" || m.Method == "turn/completed" {
		if validID(p.ThreadID) != nil || validID(p.Turn.ID) != nil || !turnStatus(p.Turn.Status) ||
			(m.Method == "turn/started" && p.Turn.Status != "inProgress") || (m.Method == "turn/completed" && p.Turn.Status == "inProgress") {
			c.runtimeUncertain = true
			return
		}
		c.rememberTurn(p.ThreadID, p.Turn.ID, p.Turn.Status)
		if m.Method == "turn/completed" {
			for key := range c.runtimeItems {
				if key.thread == p.ThreadID && key.turn == p.Turn.ID {
					delete(c.runtimeItems, key)
				}
			}
			for key := range c.messages {
				if key.thread == p.ThreadID && key.turn == p.Turn.ID {
					delete(c.messages, key)
				}
			}
		}
		return
	}
	if p.Item.Type != "agentMessage" || p.Item.Text == nil || validID(p.ThreadID) != nil || validID(p.TurnID) != nil || validID(p.Item.ID) != nil {
		return
	}
	if c.messages == nil {
		c.messages = make(map[itemKey][32]byte)
	}
	key := itemKey{p.ThreadID, p.TurnID, p.Item.ID}
	hash := sha256.Sum256([]byte(*p.Item.Text))
	if previous, exists := c.messages[key]; exists && previous != hash {
		c.messages[key] = [32]byte{}
	} else if exists || len(c.messages) < 128 {
		c.messages[key] = hash
	}
}

// Called under mu. Terminal events can precede their start acknowledgment.
func (c *Connection) rememberTurn(thread, turn, status string) {
	if c.turns == nil {
		c.turns = make(map[turnKey]string)
	}
	key := turnKey{thread, turn}
	if prior, exists := c.turns[key]; exists {
		if status == "inProgress" {
			return
		}
		if prior != "inProgress" && prior != status {
			c.turns[key] = "unknown"
			return
		}
	} else if len(c.turns) >= 128 {
		for old, outcome := range c.turns {
			if outcome == "completed" || outcome == "failed" || outcome == "interrupted" {
				delete(c.turns, old)
				break
			}
		}
		if len(c.turns) >= 128 {
			c.turnKnowledgeLost = true
			return
		}
	}
	c.turns[key] = status
}

// begun distinguishes an admitted write from a cancelled waiter that sent nothing.
func (c *Connection) send(ctx context.Context, m any) (begun bool, err error) {
	if err := ctx.Err(); err != nil {
		return false, err
	}
	bytes, err := json.Marshal(m)
	if err != nil {
		return false, err
	}
	if len(bytes) > maxMessage {
		return false, fmt.Errorf("Codex request exceeds %d bytes", maxMessage)
	}
	select {
	case c.write <- struct{}{}:
	case <-ctx.Done():
		return false, ctx.Err()
	case <-c.done:
		return false, c.failure()
	}
	if err := ctx.Err(); err != nil {
		<-c.write
		return false, err
	}
	select {
	case <-c.done:
		<-c.write
		return false, c.failure()
	default:
	}
	// Observation cancellation cannot abort an admitted frame or kill native work.
	// Only an independently stalled/broken transport closes the owned backend.
	timeout := c.writeTimeout
	if timeout <= 0 {
		timeout = 30 * time.Second
	}
	frame, input := append(bytes, '\n'), c.input
	c.process.rememberPrivateFrame(bytes)
	completed := make(chan error, 1)
	go func() {
		deadline := time.AfterFunc(timeout, func() { c.fail(errors.New("Codex transport write stalled")) })
		n, err := input.Write(frame)
		deadline.Stop()
		if err == nil && n != len(frame) {
			err = io.ErrShortWrite
		}
		if err != nil {
			c.fail(err)
			err = c.failure()
		}
		<-c.write
		completed <- err
	}()
	select {
	case err := <-completed:
		return true, err
	case <-ctx.Done():
		return true, ctx.Err()
	case <-c.done:
		return true, c.failure()
	}
}

func (c *Connection) request(ctx context.Context, method string, params any) (json.RawMessage, error) {
	return c.requestWithIdleCheck(ctx, method, params, nil)
}

func (c *Connection) requestWithIdleCheck(ctx context.Context, method string, params any, idleVersion *uint64) (json.RawMessage, error) {
	if err := ctx.Err(); err != nil {
		return nil, &RequestNotSent{err}
	}
	c.mu.Lock()
	if idleVersion != nil {
		err := c.localIdleLocked()
		if err == nil && c.runtimeVersion != *idleVersion {
			err = authFailure("runtime/idle", "native_runtime_unconfirmed", nil)
		}
		if err != nil {
			c.mu.Unlock()
			return nil, &RequestNotSent{err}
		}
	}
	if c.err != nil {
		err := c.err
		c.mu.Unlock()
		return nil, &RequestNotSent{err}
	}
	if len(c.pending) >= 128 {
		c.mu.Unlock()
		return nil, &RequestNotSent{errors.New("Too many unanswered Codex RPCs; wait for native responses")}
	}
	c.next++
	id := fmt.Sprint(c.next)
	waiter := make(chan reply, 1)
	c.pending[id] = waiter
	c.mu.Unlock()
	if begun, err := c.send(ctx, map[string]any{"id": json.RawMessage(id), "method": method, "params": params}); err != nil {
		if !begun {
			c.mu.Lock()
			delete(c.pending, id)
			c.mu.Unlock()
			err = &RequestNotSent{err}
		} else {
			select {
			case received := <-waiter:
				return received.result()
			default:
			}
		}
		return nil, err
	}
	select {
	case r := <-waiter:
		return r.result()
	case <-ctx.Done():
		select {
		case received := <-waiter:
			return received.result()
		default:
		}
		// Keep the exact waiter until its late response arrives; observation must not kill native work.
		return nil, ctx.Err()
	}
}

// Events includes text deltas, turn completion and server requests. Always route
// events by their actual threadId/turnId; switching selection does not relabel them.
func (c *Connection) Events() <-chan Message { return c.events }

// Done closes on a broken/closed backend connection, not successful turn completion.
func (c *Connection) Done() <-chan struct{} { return c.done }

func page(cursor string, limit int) (map[string]any, error) {
	if limit < 1 || limit > 100 {
		return nil, errors.New("Page limit must be 1..100")
	}
	p := map[string]any{"limit": limit}
	if cursor != "" {
		p["cursor"] = cursor
	}
	return p, nil
}

// Preserve native history while rejecting incomplete or incorrectly routed pages.
func validatePage(result json.RawMessage, cursor string, limit int) error {
	var p struct {
		Data            []json.RawMessage `json:"data"`
		NextCursor      *string           `json:"nextCursor"`
		BackwardsCursor *string           `json:"backwardsCursor"`
	}
	if json.Unmarshal(result, &p) != nil || p.Data == nil || len(p.Data) > limit ||
		(p.NextCursor != nil && *p.NextCursor != "" && *p.NextCursor == cursor) {
		return errors.New("Malformed or non-progressing Codex page")
	}
	seen := make(map[string]bool, len(p.Data))
	for _, row := range p.Data {
		var identity struct {
			ID string `json:"id"`
		}
		if json.Unmarshal(row, &identity) != nil || validID(identity.ID) != nil || seen[identity.ID] {
			return errors.New("Missing, invalid or repeated Codex page identity")
		}
		seen[identity.ID] = true
		var turn struct {
			Status    string            `json:"status"`
			Items     []json.RawMessage `json:"items"`
			ItemsView json.RawMessage   `json:"itemsView"`
		}
		if json.Unmarshal(row, &turn) != nil || !turnStatus(turn.Status) || turn.Items == nil {
			return errors.New("Codex history has a malformed turn or missing items")
		}
		if len(turn.ItemsView) != 0 {
			var view string
			if json.Unmarshal(turn.ItemsView, &view) != nil || view != "full" {
				return errors.New("Codex history did not return full items")
			}
		}
		for _, item := range turn.Items {
			var header struct {
				ID      string          `json:"id"`
				Type    string          `json:"type"`
				Text    json.RawMessage `json:"text"`
				Content json.RawMessage `json:"content"`
			}
			if json.Unmarshal(item, &header) != nil || header.ID == "" || header.Type == "" {
				return errors.New("Malformed Codex history item")
			}
			if header.Type == "agentMessage" || header.Type == "plan" {
				var text *string
				if json.Unmarshal(header.Text, &text) != nil || text == nil {
					return errors.New("Codex history message is missing its text")
				}
			}
			if header.Type == "userMessage" {
				var content []json.RawMessage
				if json.Unmarshal(header.Content, &content) != nil || content == nil {
					return errors.New("Codex history user message is missing its content")
				}
			}
		}
	}
	return nil
}

func threadStatus(status string) bool {
	return status == "notLoaded" || status == "idle" || status == "active" || status == "systemError"
}

func turnStatus(status string) bool {
	return status == "inProgress" || status == "completed" || status == "failed" || status == "interrupted"
}

func validID(id string) error {
	if id == "" || len(id) > 128 {
		return errors.New("Exact session/turn ID required")
	}
	for _, v := range id {
		if !(v >= 'a' && v <= 'z' || v >= 'A' && v <= 'Z' || v >= '0' && v <= '9' || v == '_' || v == '-') {
			return errors.New("Invalid session/turn ID")
		}
	}
	return nil
}

func threadID(result json.RawMessage, expected string) (string, error) {
	var r struct {
		Thread struct {
			ID     string `json:"id"`
			Status struct {
				Type string `json:"type"`
			} `json:"status"`
		} `json:"thread"`
	}
	if json.Unmarshal(result, &r) != nil || r.Thread.ID != expected {
		return "", errors.New("Codex returned a different or malformed session")
	}
	if !threadStatus(r.Thread.Status.Type) {
		return "", errors.New("Codex session has no recognized live status")
	}
	return r.Thread.Status.Type, nil
}

func (c *Connection) Read(ctx context.Context, id string) (json.RawMessage, error) {
	if err := validID(id); err != nil {
		return nil, err
	}
	r, err := c.request(ctx, "thread/read", map[string]any{"threadId": id, "includeTurns": false})
	if err == nil {
		_, err = threadID(r, id)
	}
	return r, err
}

func (c *Connection) History(ctx context.Context, id, cursor string, limit int) (json.RawMessage, error) {
	if err := validID(id); err != nil {
		return nil, err
	}
	p, err := page(cursor, limit)
	if err != nil {
		return nil, err
	}
	p["threadId"], p["itemsView"], p["sortDirection"] = id, "full", "desc"
	live, err := c.Read(ctx, id)
	if err != nil {
		return nil, err
	}
	status, err := threadID(live, id)
	if err != nil {
		return nil, err
	}
	r, err := c.request(ctx, "thread/turns/list", p)
	if err == nil {
		err = validatePage(r, cursor, limit)
	}
	if err != nil {
		return nil, err
	}
	return c.reportHistory(ctx, id, r, status == "idle" || status == "active")
}

// Streamed text cannot become business status before native item completion.
func (c *Connection) reportHistory(ctx context.Context, thread string, raw json.RawMessage, loadedBefore bool) (json.RawMessage, error) {
	var page map[string]any
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	if err := decoder.Decode(&page); err != nil {
		return nil, err
	}
	loaded := false
	for _, value := range page["data"].([]any) {
		status := value.(map[string]any)["status"]
		if status == "failed" || status == "interrupted" {
			live, err := c.Read(ctx, thread)
			if err != nil {
				return nil, err
			}
			status, err := threadID(live, thread)
			if err != nil {
				return nil, err
			}
			loaded = loadedBefore && (status == "idle" || status == "active")
			break
		}
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	activeTurns := 0
	for _, value := range page["data"].([]any) {
		if value.(map[string]any)["status"] == "inProgress" {
			activeTurns++
		}
	}
	for _, value := range page["data"].([]any) {
		turn := value.(map[string]any)
		// Stored history cannot manufacture an outstanding client request.
		turn["bridgeInteractionRequired"] = false
		if loadedBefore && c.selected[thread] && turn["status"] == "inProgress" {
			for _, request := range c.requests {
				var params struct{ ThreadID, TurnID string }
				if json.Unmarshal(request.Params, &params) == nil && params.ThreadID == thread && (params.TurnID == turn["id"] || (params.TurnID == "" && activeTurns == 1)) {
					turn["bridgeInteractionRequired"] = true
					break
				}
			}
		}
		terminal := turn["status"] == "completed"
		// Unloaded readers can reconstruct unfinished turns as interrupted.
		turn["bridgeTerminalCommitted"] = terminal || (loaded && (turn["status"] == "failed" || turn["status"] == "interrupted"))
		key := turnKey{thread, turn["id"].(string)}
		if terminal {
			// Explicit persisted completion can reconcile a lost preview event.
			c.rememberTurn(key.thread, key.turn, "completed")
			terminal = c.turns[key] == "completed"
			turn["bridgeTerminalCommitted"] = terminal
		} else if turn["status"] == "failed" || turn["status"] == "interrupted" {
			if outcome, known := c.turns[key]; known {
				turn["bridgeTerminalCommitted"] = outcome == turn["status"]
			}
			if c.turnKnowledgeLost {
				turn["bridgeTerminalCommitted"] = false
			}
		}
		for _, value := range turn["items"].([]any) {
			item := value.(map[string]any)
			if item["type"] != "agentMessage" {
				continue
			}
			hash, exists := c.messages[itemKey{thread, turn["id"].(string), item["id"].(string)}]
			item["bridgeReportCommitted"] = terminal || (exists && hash != [32]byte{} && hash == sha256.Sum256([]byte(item["text"].(string))))
		}
	}
	return json.Marshal(page)
}

// Select confirms an owned live writer, or resumes the exact stored thread to subscribe.
// It supplies no model, permissions, history, filesystem or instruction overrides.
func (c *Connection) Select(ctx context.Context, id string) (json.RawMessage, error) {
	c.mu.Lock()
	known := c.selected[id]
	delete(c.selected, id)
	c.mu.Unlock()
	live, err := c.Read(ctx, id)
	if err != nil {
		return nil, err
	}
	status, _ := threadID(live, id)
	if err := directInput(live, status == "notLoaded"); err != nil {
		return nil, err
	}
	if known && status != "notLoaded" {
		c.mu.Lock()
		err = c.err
		if err == nil {
			c.selected[id] = true
		}
		c.mu.Unlock()
		return live, err
	}
	r, err := c.request(ctx, "thread/resume", map[string]any{"threadId": id, "excludeTurns": true})
	if err == nil {
		_, err = threadID(r, id)
	}
	if err == nil {
		err = directInput(r, false)
	}
	if err == nil {
		c.mu.Lock()
		err = c.err
		if err == nil {
			c.selected[id] = true
		}
		c.mu.Unlock()
	}
	return r, err
}

func directInput(result json.RawMessage, allowUnavailable bool) error {
	var r struct {
		Thread struct {
			CanAcceptDirectInput *bool `json:"canAcceptDirectInput"`
		} `json:"thread"`
	}
	if json.Unmarshal(result, &r) != nil ||
		(r.Thread.CanAcceptDirectInput == nil && !allowUnavailable) ||
		(r.Thread.CanAcceptDirectInput != nil && !*r.Thread.CanAcceptDirectInput) {
		return errors.New("Native writer does not confirm direct turn input; its supported provider is required")
	}
	return nil
}

func (c *Connection) textParams(ctx context.Context, id, text string) (map[string]any, string, error) {
	if len(text) == 0 || len(text) > 64<<10 || !utf8.ValidString(text) {
		return nil, "", errors.New("Text must contain 1..65536 UTF-8 bytes")
	}
	c.mu.Lock()
	selected := c.selected[id]
	c.mu.Unlock()
	if !selected {
		return nil, "", errors.New("Select this exact session on this connection before sending")
	}
	r, err := c.Read(ctx, id)
	if err != nil {
		return nil, "", err
	}
	if err := directInput(r, false); err != nil {
		return nil, "", err
	}
	status, err := threadID(r, id)
	return map[string]any{"threadId": id, "input": []map[string]string{{"type": "text", "text": text}}}, status, err
}

func mutation(result json.RawMessage, err error) (json.RawMessage, error) {
	if err != nil {
		var rejected *RPCError
		var notSent *RequestNotSent
		var unknown *OutcomeUnknown
		if !errors.As(err, &rejected) && !errors.As(err, &notSent) && !errors.As(err, &unknown) {
			err = &OutcomeUnknown{err}
		}
	}
	return result, err
}

func (c *Connection) Models(ctx context.Context, cursor string) (json.RawMessage, error) {
	if len(cursor) > 4096 {
		return nil, errors.New("Invalid model cursor")
	}
	params := map[string]any{"limit": 100}
	if cursor != "" {
		params["cursor"] = cursor
	}
	return c.request(ctx, "model/list", params)
}
func (c *Connection) StartThread(ctx context.Context, cwd, model, serviceTier string) (json.RawMessage, error) {
	if !filepath.IsAbs(cwd) {
		return nil, &RequestNotSent{errors.New("Managed project required")}
	}
	if !protocol.ValidServiceTier(serviceTier) {
		return nil, &RequestNotSent{errors.New("Invalid service tier")}
	}
	p := map[string]any{"cwd": cwd}
	if model != "" {
		p["model"] = model
	}
	if serviceTier != "" {
		p["serviceTier"] = serviceTier
	}
	r, err := mutation(c.request(ctx, "thread/start", p))
	if err != nil {
		return r, err
	}
	var ack struct{ Thread struct{ ID string } }
	if json.Unmarshal(r, &ack) != nil || validID(ack.Thread.ID) != nil {
		return nil, &OutcomeUnknown{errors.New("Malformed native thread creation acknowledgement")}
	}
	status, err := threadID(r, ack.Thread.ID)
	if err == nil && status == "notLoaded" {
		err = errors.New("New native thread has no loaded writer")
	}
	if err == nil {
		err = directInput(r, false)
	}
	if err != nil {
		return nil, &OutcomeUnknown{err}
	}
	// thread/start already subscribes this connection; empty threads need no resume.
	c.mu.Lock()
	if c.err == nil {
		c.selected[ack.Thread.ID] = true
	}
	c.mu.Unlock()
	// A later transport failure cannot undo the already validated creation receipt.
	return r, nil
}
func (c *Connection) NameThread(ctx context.Context, id, name string) error {
	if err := validID(id); err != nil {
		return err
	}
	if len(name) == 0 || len(name) > 255 || !utf8.ValidString(name) {
		return errors.New("Invalid thread name")
	}
	_, err := mutation(c.request(ctx, "thread/name/set", map[string]any{"threadId": id, "name": name}))
	return err
}

// Native deletion requires observed retirement, including an idle-runtime fence
// when a correlated native read rejection prevents inspecting an already-missing thread.
func (c *Connection) DeleteThread(ctx context.Context, id string) error {
	if err := validID(id); err != nil {
		return err
	}
	version, err := c.prepareThreadDeletion(ctx, id)
	if err != nil {
		return err
	}
	var raw json.RawMessage
	if version != nil {
		raw, err = mutation(c.requestWithIdleCheck(ctx, "thread/delete", map[string]any{"threadId": id}, version))
	} else {
		raw, err = mutation(c.request(ctx, "thread/delete", map[string]any{"threadId": id}))
	}
	if err != nil {
		return err
	}
	var result map[string]json.RawMessage
	if json.Unmarshal(raw, &result) != nil || result == nil || len(result) != 0 {
		return &OutcomeUnknown{errors.New("Malformed native deletion acknowledgment")}
	}
	c.mu.Lock()
	delete(c.selected, id)
	for requestID, request := range c.requests {
		var params struct{ ThreadID string }
		if json.Unmarshal(request.Params, &params) == nil && params.ThreadID == id {
			delete(c.requests, requestID)
		}
	}
	for key := range c.messages {
		if key.thread == id {
			delete(c.messages, key)
		}
	}
	for key := range c.turns {
		if key.thread == id {
			delete(c.turns, key)
		}
	}
	for key := range c.runtimeItems {
		if key.thread == id {
			delete(c.runtimeItems, key)
		}
	}
	c.mu.Unlock()
	return nil
}

func (c *Connection) SendJob(ctx context.Context, id, text, instructions, path, markdown, model, effort, serviceTier string) (json.RawMessage, error) {
	if !protocol.ValidServiceTier(serviceTier) {
		return nil, errors.New("Invalid service tier")
	}
	message, err := FormatJobMessage(text, instructions, path, markdown)
	if err != nil {
		return nil, err
	}
	p, status, err := c.textParams(ctx, id, text)
	if err != nil {
		return nil, err
	}
	if status != "idle" {
		return nil, errors.New("Session is not idle")
	}
	p["input"] = []map[string]string{{"type": "text", "text": message}}
	if model != "" {
		p["model"] = model
	}
	if effort != "" {
		p["effort"] = effort
	}
	if serviceTier != "" {
		p["serviceTierForTurn"] = serviceTier
	}
	if err := verifyJobHandoff(path, markdown); err != nil {
		return nil, err
	}
	r, err := mutation(c.request(ctx, "turn/start", p))
	if err == nil {
		var ack struct {
			Turn struct {
				ID     string `json:"id"`
				Status string `json:"status"`
			} `json:"turn"`
		}
		if json.Unmarshal(r, &ack) != nil || validID(ack.Turn.ID) != nil || !turnStatus(ack.Turn.Status) {
			return nil, &OutcomeUnknown{errors.New("Malformed turn acknowledgement")}
		}
		c.mu.Lock()
		c.rememberTurn(id, ack.Turn.ID, ack.Turn.Status)
		c.mu.Unlock()
	}
	return r, err
}

func (c *Connection) Interrupt(ctx context.Context, id, turnID string) (json.RawMessage, error) {
	if err := validID(turnID); err != nil {
		return nil, err
	}
	c.mu.Lock()
	selected := c.selected[id]
	c.mu.Unlock()
	if !selected {
		return nil, errors.New("Select the session before interrupting")
	}
	return mutation(c.request(ctx, "turn/interrupt", map[string]string{"threadId": id, "turnId": turnID}))
}

package codex

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"phonepcbridge/internal/checkpeer"
	"phonepcbridge/internal/checksync"
)

// The peer is another process. It parses actual serialized requests, maintains
// live state, echoes the received Unicode text and emits interleaved events.
func TestPeerProcess(t *testing.T) {
	// An inherited helper variable must not exit a normal test run and skip its
	// checks. Only the explicitly selected child process speaks the peer protocol.
	for _, arg := range os.Args[1:] {
		if arg == "-test.run=^TestPeerProcess$" {
			checkpeer.Run(os.Getenv("BRIDGE_CHECK_PEER"))
			return
		}
	}
}

func peer(t *testing.T, mode string) *Connection {
	t.Helper()
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(executable, "-test.run=^TestPeerProcess$")
	cmd.Env = append(os.Environ(), "BRIDGE_CHECK_PEER="+mode)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, err := connect(ctx, cmd)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	return c
}

func sendJob(t *testing.T, c *Connection, ctx context.Context, id, text string) (json.RawMessage, error) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "bridge-developer.md")
	const workflow = "bridge-developer-v2 isolated native test"
	if err := os.WriteFile(path, []byte(workflow), 0600); err != nil {
		t.Fatal(err)
	}
	return c.SendJob(ctx, id, text, "", path, workflow, "", "", "")
}

func event(t *testing.T, c *Connection, method string) Message {
	t.Helper()
	select {
	case m, ok := <-c.Events():
		if !ok || m.Method != method {
			t.Fatalf("Expected %s event, got %s", method, m.Method)
		}
		return m
	case <-time.After(3 * time.Second):
		t.Fatal("Missing live event")
		return Message{}
	}
}

func TestReportsRequireExactNativeItemCompletion(t *testing.T) {
	c := peer(t, "normal")
	page := json.RawMessage(`{"data":[{"id":"turn-1","status":"inProgress","items":[{"id":"message-1","type":"agentMessage","text":"report text","bridgeReportCommitted":true}]}],"nextCursor":null,"backwardsCursor":null,"opaqueInteger":9007199254740993}`)
	check := func(thread string, raw json.RawMessage, expected bool) {
		t.Helper()
		annotated, err := c.reportHistory(context.Background(), thread, raw, true)
		var result struct {
			Data []struct {
				Items []struct{ BridgeReportCommitted bool }
			}
		}
		if err != nil || json.Unmarshal(annotated, &result) != nil || result.Data[0].Items[0].BridgeReportCommitted != expected {
			t.Fatalf("Report completion scope: %s %v", annotated, err)
		}
		if !strings.Contains(string(annotated), `"opaqueInteger":9007199254740993`) {
			t.Fatal("Native metadata lost integer precision")
		}
	}
	check("thread-1", page, false)
	c.observeItems(Message{Method: "item/started", Params: json.RawMessage(`{"threadId":"thread-1","turnId":"turn-1","item":{"id":"message-1","type":"agentMessage","text":"report text"}}`)})
	check("thread-1", page, false)
	completed := Message{Method: "item/completed", Params: json.RawMessage(`{"threadId":"thread-1","turnId":"turn-1","item":{"id":"message-1","type":"agentMessage","text":"report text"}}`)}
	c.observeItems(completed)
	check("thread-1", page, true)
	check("thread-2", page, false)
	check("thread-1", json.RawMessage(strings.Replace(string(page), "report text", "changed text", 1)), false)
	c.observeItems(Message{Method: "item/completed", Params: json.RawMessage(strings.Replace(string(completed.Params), "report text", "conflicting text", 1))})
	c.observeItems(completed)
	check("thread-1", page, false)
	check("thread-1", json.RawMessage(strings.Replace(string(page), "inProgress", "completed", 1)), true)
	check("thread-1", json.RawMessage(strings.Replace(string(page), "inProgress", "interrupted", 1)), false)
}

func TestTerminalHistoryRequiresLoadedNativeWriter(t *testing.T) {
	c := peer(t, "unloaded-terminal")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	check := func(loaded bool) {
		t.Helper()
		raw, err := c.History(ctx, "thread-1", "", 10)
		var page struct {
			Data []struct {
				Status                  string
				BridgeTerminalCommitted *bool
			}
		}
		if err != nil || json.Unmarshal(raw, &page) != nil || len(page.Data) != 4 {
			t.Fatal("Native terminal history unavailable", err)
		}
		for _, turn := range page.Data {
			want := turn.Status == "completed" || (loaded && (turn.Status == "failed" || turn.Status == "interrupted"))
			if turn.BridgeTerminalCommitted == nil || *turn.BridgeTerminalCommitted != want {
				t.Fatalf("Terminal state %s incorrectly confirmed (loaded=%v): %s", turn.Status, loaded, raw)
			}
		}
	}
	check(false)
	if _, err := c.Select(ctx, "thread-1"); err != nil {
		t.Fatal(err)
	}
	check(true)
	for _, mode := range []string{"terminal-load-race", "terminal-unload-race"} {
		racing := peer(t, mode)
		raw, err := racing.History(ctx, "thread-1", "", 10)
		var page struct {
			Data []struct {
				Status                  string
				BridgeTerminalCommitted bool
			}
		}
		if err != nil || json.Unmarshal(raw, &page) != nil || len(page.Data) != 4 {
			t.Fatal("Native writer race history unavailable", err)
		}
		for _, turn := range page.Data {
			if (turn.Status == "failed" || turn.Status == "interrupted") && turn.BridgeTerminalCommitted {
				t.Fatal("Loading changed during history; uncertain outcome was confirmed")
			}
		}
	}
	broken := peer(t, "terminal-bad-read")
	if _, err := broken.History(ctx, "thread-1", "", 10); err == nil {
		t.Fatal("Unrecognized writer state confirmed native failure")
	}
}

func TestAcknowledgedTurnOverridesLaggingIdleMetadata(t *testing.T) {
	c := peer(t, "lagging-turn")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := c.Select(ctx, "thread-1"); err != nil {
		t.Fatal(err)
	}
	if _, err := sendJob(t, c, ctx, "thread-1", "Actual admitted work"); err != nil {
		t.Fatal(err)
	}
	raw, err := c.Read(ctx, "thread-1")
	if err != nil {
		t.Fatal(err)
	}
	if status, err := threadID(raw, "thread-1"); err != nil || status != "idle" {
		t.Fatal("Independent peer did not expose lagging idle metadata", err)
	}
	check := func(want bool) {
		t.Helper()
		raw, err := c.History(ctx, "thread-1", "", 10)
		var page struct {
			Data []struct{ BridgeTerminalCommitted bool }
		}
		if err != nil || json.Unmarshal(raw, &page) != nil || len(page.Data) != 1 || page.Data[0].BridgeTerminalCommitted != want {
			t.Fatal("Exact pending/terminal turn scope incorrect", err, string(raw))
		}
	}
	check(false)
	if _, err := c.Interrupt(ctx, "thread-1", "turn-1"); err != nil {
		t.Fatal(err)
	}
	check(true)
}

func TestTerminalEventBeforeAcknowledgmentDoesNotRestorePending(t *testing.T) {
	c := peer(t, "terminal-before-ack")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := c.Select(ctx, "thread-1"); err != nil {
		t.Fatal(err)
	}
	if _, err := sendJob(t, c, ctx, "thread-1", "Admitted turn"); err != nil {
		t.Fatal(err)
	}
	raw, err := c.History(ctx, "thread-1", "", 1)
	var history struct {
		Data []struct{ BridgeTerminalCommitted bool }
	}
	if err != nil || json.Unmarshal(raw, &history) != nil || len(history.Data) != 1 || !history.Data[0].BridgeTerminalCommitted {
		t.Fatal("Late acknowledgment regressed exact native completion", err, string(raw))
	}
}

func TestTurnTrackingBoundsAndConflictFailClosed(t *testing.T) {
	c := &Connection{}
	for n := 0; n < 129; n++ {
		c.rememberTurn("thread-1", fmt.Sprintf("turn-%d", n), "inProgress")
	}
	if len(c.turns) != 128 || !c.turnKnowledgeLost {
		t.Fatal("Overflow dropped unresolved turn knowledge")
	}
	c = &Connection{}
	c.rememberTurn("thread-1", "turn-1", "inProgress")
	c.observeItems(Message{Method: "turn/completed", Params: json.RawMessage(`{"threadId":"thread-1","turn":{"id":"turn-1","status":"inProgress"}}`)})
	if c.turns[turnKey{"thread-1", "turn-1"}] != "inProgress" {
		t.Fatal("Malformed terminal event cleared admitted work")
	}
	c.rememberTurn("thread-1", "turn-1", "interrupted")
	c.rememberTurn("thread-1", "turn-1", "failed")
	c.rememberTurn("thread-1", "turn-1", "interrupted")
	if c.turns[turnKey{"thread-1", "turn-1"}] != "unknown" {
		t.Fatal("Conflicting native completion was trusted")
	}
	c = &Connection{}
	for n := 0; n < 129; n++ {
		c.rememberTurn("thread-1", fmt.Sprintf("turn-%d", n), "completed")
	}
	if len(c.turns) != 128 || c.turnKnowledgeLost {
		t.Fatal("Terminal eviction lost active-turn safety")
	}
}

func TestConflictingPersistedCompletionIsNotCommitted(t *testing.T) {
	c := &Connection{}
	c.rememberTurn("thread-1", "turn-1", "interrupted")
	raw, err := c.reportHistory(context.Background(), "thread-1", json.RawMessage(`{"data":[{"id":"turn-1","status":"completed","items":[]}]}`), false)
	var page struct {
		Data []struct{ BridgeTerminalCommitted bool }
	}
	if err != nil || json.Unmarshal(raw, &page) != nil || len(page.Data) != 1 || page.Data[0].BridgeTerminalCommitted {
		t.Fatal("Conflicting native completion was committed", err, string(raw))
	}
}

func TestExistingSessionFlow(t *testing.T) {
	c := peer(t, "normal")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := sendJob(t, c, ctx, "thread-1", "must not send"); err == nil {
		t.Fatal("Unselected send accepted")
	}
	if _, err := c.Select(ctx, "thread-1"); err != nil {
		t.Fatal(err)
	}
	history, err := c.History(ctx, "thread-1", "older", 10)
	if err != nil || !strings.Contains(string(history), "Stored reply") {
		t.Fatal("History missing", err)
	}
	text := "Actual UTF-8 payload: مرحبا 👋\nsecond line"
	ack, err := sendJob(t, c, ctx, "thread-1", text)
	if err != nil || !strings.Contains(string(ack), "turn-1") {
		t.Fatal("Send not acknowledged", err)
	}
	delta := event(t, c, "item/agentMessage/delta")
	var p struct{ ThreadID, TurnID, Delta string }
	if json.Unmarshal(delta.Params, &p) != nil || p.ThreadID != "thread-1" || p.TurnID != "turn-1" || p.Delta != text {
		t.Fatal("Received text/session mismatch")
	}
	approval := event(t, c, "item/commandExecution/requestApproval")
	c.mu.Lock()
	pending, unanswered := c.requests[string(approval.ID)]
	c.mu.Unlock()
	if !unanswered || pending.Method != approval.Method {
		t.Fatal("Actual native interaction was lost")
	}
	if _, err := sendJob(t, c, ctx, "thread-1", "competing turn"); err == nil {
		t.Fatal("Busy session silently started")
	}
	if _, err := c.Interrupt(ctx, "thread-1", "turn-1"); err != nil {
		t.Fatal(err)
	}
	event(t, c, "turn/completed")
}

func TestFailureBoundaries(t *testing.T) {
	for _, mode := range []string{"wrong-session", "wrong-response", "timeout", "disconnect-send", "reject-send", "bad-ack"} {
		t.Run(mode, func(t *testing.T) {
			c := peer(t, mode)
			ctx, cancel := context.WithTimeout(context.Background(), 250*time.Millisecond)
			defer cancel()
			if mode == "wrong-response" || mode == "timeout" {
				if _, err := c.Models(ctx, ""); err == nil {
					t.Fatal("Invalid/absent response accepted")
				}
				return
			}
			_, err := c.Select(ctx, "thread-1")
			if mode == "wrong-session" {
				if err == nil {
					t.Fatal("Different session selected")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			_, err = sendJob(t, c, ctx, "thread-1", "real attempt")
			if err == nil {
				t.Fatal("Unconfirmed/rejected send reported success")
			}
			var unknown *OutcomeUnknown
			if mode == "reject-send" {
				var rejected *RPCError
				if !errors.As(err, &rejected) || errors.As(err, &unknown) {
					t.Fatal("Explicit rejection misclassified")
				}
			} else if !errors.As(err, &unknown) {
				t.Fatal("Attempted mutation certainty fabricated")
			}
		})
	}
}

func TestDirectInputRequiresNativeWriterCapability(t *testing.T) {
	for _, mode := range []string{"direct-readonly-before", "direct-false", "direct-null", "direct-missing", "direct-malformed"} {
		t.Run(mode, func(t *testing.T) {
			c := peer(t, mode)
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			// A known writer must pass fresh Read; an unknown writer must pass resume.
			if mode == "direct-readonly-before" {
				c.selected["thread-1"] = true
			}
			if _, err := c.Select(ctx, "thread-1"); err == nil || !strings.Contains(err.Error(), "does not confirm direct turn input") {
				t.Fatalf("Unavailable native writer was accepted: %v", err)
			}
			if c.selected["thread-1"] {
				t.Fatal("Rejected writer retained prior selection")
			}
			raw, err := c.Read(ctx, "thread-1")
			var meta struct {
				Thread struct{ CheckResumes int } `json:"thread"`
			}
			if err != nil || json.Unmarshal(raw, &meta) != nil {
				t.Fatal("Native peer did not survive rejected selection", err)
			}
			want := 1
			if mode == "direct-readonly-before" {
				want = 0
			}
			if meta.Thread.CheckResumes != want {
				t.Fatal("Unexpected native resume during capability rejection")
			}
			if _, err := sendJob(t, c, ctx, "thread-1", "Must remain unsent"); err == nil {
				t.Fatal("Rejected writer accepted a later native turn")
			}
		})
	}
	c := peer(t, "direct-lost")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := c.Select(ctx, "thread-1"); err != nil {
		t.Fatal(err)
	}
	if _, err := sendJob(t, c, ctx, "thread-1", "Must remain unsent"); err == nil || !strings.Contains(err.Error(), "does not confirm direct turn input") {
		t.Fatal("Lost native capability did not block the mutation", err)
	}
	if _, err := c.Read(ctx, "thread-1"); err != nil {
		t.Fatal("A forbidden mutation reached and exited the independent peer", err)
	}
}

func TestStoredThreadRequiresWritableResume(t *testing.T) {
	c := peer(t, "direct-stored")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := c.Select(ctx, "thread-1"); err != nil {
		t.Fatal(err)
	}
	if _, err := sendJob(t, c, ctx, "thread-1", "Actual stored-thread dispatch"); err != nil {
		t.Fatal(err)
	}
}

func TestCancelledReadPreservesNativeWorkAndLateCorrelation(t *testing.T) {
	c := peer(t, "held-read")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := c.Select(ctx, "thread-1"); err != nil {
		t.Fatal(err)
	}
	if _, err := sendJob(t, c, ctx, "thread-1", "live native work"); err != nil {
		t.Fatal(err)
	}
	event(t, c, "item/agentMessage/delta")
	approval := event(t, c, "item/commandExecution/requestApproval")
	readCtx, stop := context.WithCancel(ctx)
	errCh := make(chan error, 1)
	retired := make(chan struct{})
	t.Cleanup(func() {
		stop()
		c.Close()
		checksync.Join(t, retired, time.Second, "cancelled native read")
	})
	go func() { defer close(retired); _, err := c.Read(readCtx, "thread-1"); errCh <- err }()
	event(t, c, "check/readReceived")
	stop()
	if err := checksync.Receive(t, errCh, time.Second, "cancelled native read"); !errors.Is(err, context.Canceled) {
		t.Fatal("Cancelled observation reported success", err)
	}
	select {
	case <-c.Done():
		t.Fatal("Read cancellation killed native work")
	default:
	}
	c.mu.Lock()
	_, pending := c.requests[string(approval.ID)]
	c.mu.Unlock()
	if !pending {
		t.Fatal("Cancelled observation lost the unanswered native request")
	}
	if _, err := c.Models(ctx, ""); err != nil {
		t.Fatal("Late read response broke request correlation", err)
	}
	if _, err := c.Interrupt(ctx, "thread-1", "turn-1"); err != nil {
		t.Fatal("Active turn control unavailable after late read", err)
	}
	event(t, c, "turn/completed")
}

func TestChangedRequestReuseFailsClosed(t *testing.T) {
	c := peer(t, "conflicting-request")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := c.Select(ctx, "thread-1"); err != nil {
		t.Fatal(err)
	}
	if _, err := sendJob(t, c, ctx, "thread-1", "pending native approval"); err != nil {
		t.Fatal(err)
	}
	event(t, c, "item/agentMessage/delta")
	event(t, c, "item/commandExecution/requestApproval")
	if _, err := c.Select(ctx, "thread-1"); err == nil || !strings.Contains(err.Error(), "Conflicting Codex server request") {
		t.Fatal("Changed native request reused an approval identity", err)
	}
	select {
	case <-c.Done():
	default:
		t.Fatal("Conflicting request did not fail closed")
	}
}

func TestRepeatedNativeRequestStaysUnansweredUntilNativeResolution(t *testing.T) {
	c := peer(t, "repeat-request")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := c.Select(ctx, "thread-1"); err != nil {
		t.Fatal(err)
	}
	if _, err := sendJob(t, c, ctx, "thread-1", "actual unattended job"); err != nil {
		t.Fatal(err)
	}
	event(t, c, "item/agentMessage/delta")
	original := event(t, c, "item/commandExecution/requestApproval")
	for i := 0; i < 2; i++ {
		if _, err := c.Select(ctx, "thread-1"); err != nil {
			t.Fatal(err)
		}
		repeated := event(t, c, "item/commandExecution/requestApproval")
		c.mu.Lock()
		count, actual := len(c.requests), c.requests[string(original.ID)]
		c.mu.Unlock()
		if count != 1 || string(repeated.ID) != string(original.ID) || string(actual.Params) != string(original.Params) {
			t.Fatal("Native request identity was lost or duplicated")
		}
	}
	if _, err := c.Interrupt(ctx, "thread-1", "turn-1"); err != nil {
		t.Fatal(err)
	}
	event(t, c, "serverRequest/resolved")
	event(t, c, "turn/completed")
	c.mu.Lock()
	remaining := len(c.requests)
	c.mu.Unlock()
	if remaining != 0 {
		t.Fatal("Native resolution retained an interaction obligation")
	}
}

func TestUnansweredRPCsAreBoundedWithoutClosingBackend(t *testing.T) {
	c := peer(t, "held-read")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if _, err := c.Select(ctx, "thread-1"); err != nil {
		t.Fatal(err)
	}
	if _, err := sendJob(t, c, ctx, "thread-1", "active fixture"); err != nil {
		t.Fatal(err)
	}
	event(t, c, "item/agentMessage/delta")
	event(t, c, "item/commandExecution/requestApproval")
	for n := 0; n < 128; n++ {
		readCtx, stop := context.WithCancel(ctx)
		result := make(chan error, 1)
		retired := make(chan struct{})
		t.Cleanup(func() {
			stop()
			c.Close()
			checksync.Join(t, retired, time.Second, "unanswered cancelled RPC")
		})
		go func() { defer close(retired); _, err := c.Read(readCtx, "thread-1"); result <- err }()
		event(t, c, "check/readReceived")
		stop()
		if err := checksync.Receive(t, result, time.Second, "unanswered cancelled RPC"); !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
	}
	if _, err := c.Models(ctx, ""); err == nil || !strings.Contains(err.Error(), "Too many unanswered") {
		t.Fatal("Unanswered RPC limit not enforced", err)
	}
	select {
	case <-c.Done():
		t.Fatal("Capacity limit killed native backend")
	default:
	}
}

// Hold local Write completion after the separate native peer has received the full request.
type heldCompletionWriter struct {
	io.WriteCloser
	release chan struct{}
	once    sync.Once
}

func (w *heldCompletionWriter) Write(data []byte) (int, error) {
	n, err := w.WriteCloser.Write(data)
	<-w.release
	return n, err
}

func (w *heldCompletionWriter) unblock() { w.once.Do(func() { close(w.release) }) }

type stalledWriter struct {
	io.WriteCloser
	closed chan struct{}
	once   sync.Once
}

func (w *stalledWriter) Write([]byte) (int, error) {
	<-w.closed
	return 0, io.ErrClosedPipe
}

func (w *stalledWriter) Close() error {
	w.once.Do(func() { close(w.closed) })
	return w.WriteCloser.Close()
}

type shortWriter struct{ io.WriteCloser }

func (w shortWriter) Write(data []byte) (int, error) { return len(data) - 1, nil }

func TestTransportWriteFailuresCloseOwnedBackend(t *testing.T) {
	for _, kind := range []string{"stalled", "short"} {
		t.Run(kind, func(t *testing.T) {
			c := peer(t, "normal")
			if kind == "stalled" {
				c.input = &stalledWriter{WriteCloser: c.input, closed: make(chan struct{})}
				c.writeTimeout = 25 * time.Millisecond
			} else {
				c.input = shortWriter{c.input}
			}
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			if _, err := c.Models(ctx, ""); err == nil || errors.Is(err, context.DeadlineExceeded) {
				t.Fatal("Transport failure was not independently detected", err)
			}
			select {
			case <-c.Done():
			default:
				t.Fatal("Broken transport kept backend open")
			}
			select {
			case <-c.reaped:
			case <-ctx.Done():
				t.Fatal("Owned native process was not stopped")
			}
		})
	}
}

func TestCancelledWriteAdmissionSendsNothing(t *testing.T) {
	c := peer(t, "normal")
	select {
	case c.write <- struct{}{}:
	case <-time.After(time.Second):
		t.Fatal("Could not hold native write admission")
	}
	ctx, cancel := context.WithCancel(context.Background())
	result := make(chan error, 1)
	retired := make(chan struct{})
	held := true
	t.Cleanup(func() {
		cancel()
		if held {
			select {
			case <-c.write:
			default:
			}
		}
		c.Close()
		checksync.Join(t, retired, time.Second, "cancelled write admission")
	})
	go func() { defer close(retired); _, err := c.Models(ctx, ""); result <- err }()
	cancel()
	if err := checksync.Receive(t, result, time.Second, "cancelled write admission"); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	checksync.Receive(t, c.write, time.Second, "held native write slot")
	held = false
	c.mu.Lock()
	pending := len(c.pending)
	c.mu.Unlock()
	if pending != 0 {
		t.Fatal("Cancelled unsent request left a late-response waiter")
	}
	ctx, cancel = context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := c.Models(ctx, ""); err != nil {
		t.Fatal("Unsent cancellation broke later real native requests", err)
	}
}

func TestCancelledReadAfterNativeReceiptPreservesWriter(t *testing.T) {
	c := peer(t, "held-read")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := c.Select(ctx, "thread-1"); err != nil {
		t.Fatal(err)
	}
	if _, err := sendJob(t, c, ctx, "thread-1", "Keep native work running"); err != nil {
		t.Fatal(err)
	}
	event(t, c, "item/agentMessage/delta")
	event(t, c, "item/commandExecution/requestApproval")
	w := &heldCompletionWriter{WriteCloser: c.input, release: make(chan struct{})}
	c.input = w
	defer w.unblock()
	readCtx, stop := context.WithCancel(ctx)
	defer stop()
	result := make(chan error, 1)
	go func() { _, err := c.Read(readCtx, "thread-1"); result <- err }()
	event(t, c, "check/readReceived")
	stop()
	select {
	case <-c.Done():
		t.Fatal("Cancelled observation killed a native writer that had already received the request")
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("Observation cancellation stayed blocked in local Write completion")
	}
	if len(c.write) != 1 {
		t.Fatal("Cancelled observation released ownership before the actual native write completed")
	}
	w.unblock()
	// The next real RPC flushes the held late response. It must remain correlated.
	if _, err := c.Models(ctx, ""); err != nil {
		t.Fatal(err)
	}
	select {
	case <-c.Done():
		t.Fatal("Late observation response killed native work")
	default:
	}
	if _, err := c.Interrupt(ctx, "thread-1", "turn-1"); err != nil {
		t.Fatal(err)
	}
	event(t, c, "turn/completed")
}

func TestNativeHistoryPages(t *testing.T) {
	variants := []string{"null", "missing-data", "null-data", "wrong-data", "bad-cursor", "bad-backwards-cursor", "stalled", "duplicate", "too-many", "missing-id", "bad-id", "missing-status", "bad-status", "empty", "extra", "missing-items", "null-items", "partial-items", "null-items-view", "bad-item", "missing-text", "null-text", "bad-text", "missing-content", "null-content", "default-items-view", "empty-items", "future-item"}
	for _, variant := range variants {
		t.Run(variant, func(t *testing.T) {
			c := peer(t, "history-page-"+variant)
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			limit := 10
			if variant == "too-many" {
				limit = 1
			}
			result, err := c.History(ctx, "thread-1", "current-page", limit)
			if variant != "empty" && variant != "extra" && variant != "default-items-view" && variant != "empty-items" && variant != "future-item" {
				if err == nil || len(result) != 0 {
					t.Fatalf("Invalid native history escaped as data: %s %v", result, err)
				}
				return
			}
			if err != nil {
				t.Fatal("Valid native history rejected", err)
			}
			if variant == "empty" && !strings.Contains(string(result), `"data":[]`) {
				t.Fatal("Actual empty page not preserved")
			}
			if variant == "extra" && (!strings.Contains(string(result), "preserved metadata") || !strings.Contains(string(result), "Stored reply")) {
				t.Fatal("Native content/extra metadata lost")
			}
			if variant == "default-items-view" && !strings.Contains(string(result), "Stored reply") {
				t.Fatal("Schema-default full history lost")
			}
			if variant == "empty-items" && !strings.Contains(string(result), `"items":[]`) {
				t.Fatal("Actual empty turn items lost")
			}
			if variant == "future-item" && (!strings.Contains(string(result), `"type":"futureItem"`) || !strings.Contains(string(result), `"text":{"new":true}`)) {
				t.Fatal("Future native item/fields were dropped or rewritten")
			}
		})
	}
}

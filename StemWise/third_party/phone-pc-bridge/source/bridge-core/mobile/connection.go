package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

// Connection checks/wakes one paired PC through the app's embedded node. It
// neither runs an Android foreground service nor stores user recovery intent;
// the host's native lifecycle/consent path must own those decisions.
type Connection struct {
	ctx                                          context.Context
	cancel                                       context.CancelFunc
	client                                       *Client
	pcHost, pcIdentity, relayHost, relayIdentity string
	window                                       sync.Mutex
	exchange                                     func(context.Context, time.Duration, string, string, string, int, string, string, []byte) (string, error)
	pollInterval                                 time.Duration
}

// NewConnection accepts either both relay fields or neither. No configured Pi
// means monitor-only operation; there is no public UDP/router fallback here.
func (c *Client) NewConnection(pcHost, pcIdentity, relayHost, relayIdentity string) (*Connection, error) {
	if !peerHost.MatchString(pcHost) || !sessionIdentity.MatchString(pcIdentity) ||
		(relayHost == "") != (relayIdentity == "") ||
		(relayHost != "" && (!peerHost.MatchString(relayHost) || !sessionIdentity.MatchString(relayIdentity) || relayHost == pcHost || relayIdentity == pcIdentity)) {
		return nil, errors.New("Exact PC pairing and optional distinct Pi pairing required")
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed || c.node == nil {
		return nil, errors.New("Bridge transport closed")
	}
	if c.connections == nil {
		c.connections = make(map[string]*Connection)
	}
	if conn := c.connections[pcIdentity]; conn != nil && conn.ctx.Err() == nil {
		if conn.pcHost != pcHost || conn.relayHost != relayHost || conn.relayIdentity != relayIdentity {
			return nil, errors.New("Close the prior connection before changing its pairing")
		}
		return conn, nil
	}
	if len(c.connections) >= 32 {
		return nil, errors.New("Too many paired PCs")
	}
	conn := &Connection{client: c, pcHost: pcHost, pcIdentity: pcIdentity, relayHost: relayHost, relayIdentity: relayIdentity, exchange: c.exchange, pollInterval: 2 * time.Second}
	conn.ctx, conn.cancel = context.WithCancel(context.Background())
	c.connections[pcIdentity] = conn
	return conn, nil
}

func (c *Connection) ready() error {
	if c.client == nil || c.exchange == nil {
		return errors.New("Use Client.NewConnection to initialize this connection")
	}
	if c.ctx == nil || c.ctx.Err() != nil {
		return errors.New("Connection closed")
	}
	return nil
}

func (c *Connection) probe(ctx context.Context, id string) (protocol.Health, error) {
	var h protocol.Health
	nonce, err := files.NewID()
	if err != nil {
		return h, err
	}
	raw, err := c.exchange(ctx, 3*time.Second, id, c.pcHost, c.pcIdentity, protocol.Port, "GET", "/v1/health?nonce="+nonce, nil)
	if err != nil {
		return h, err
	}
	var wire struct {
		Status int    `json:"status"`
		Body   []byte `json:"body"`
	}
	if json.Unmarshal([]byte(raw), &wire) != nil || wire.Status != 200 || len(wire.Body) > 128<<10 ||
		json.Unmarshal(wire.Body, &h) != nil || h.Protocol != protocol.Codex || h.PCIdentity != c.pcIdentity || h.Nonce != nonce ||
		!sessionToken.MatchString(h.Epoch) || h.LastSequence < 0 || (h.ConnectionID != "" && !sessionToken.MatchString(h.ConnectionID)) {
		return h, errors.New("Fresh paired PC health unavailable")
	}
	return h, ctx.Err()
}

func (c *Connection) Check(requestID string) (string, error) {
	if err := c.ready(); err != nil {
		return "", err
	}
	ctx, finish, err := c.client.begin(c.ctx, 3*time.Second, requestID)
	if err != nil {
		return "", err
	}
	defer finish()
	id, err := files.NewID()
	if err != nil {
		return "", err
	}
	h, err := c.probe(ctx, id)
	if err != nil {
		return "", err
	}
	return fileJSON(map[string]any{"state": "connected", "pcIdentity": h.PCIdentity, "epoch": h.Epoch})
}

func (c *Connection) wake(ctx context.Context, id string) error {
	if c.relayHost == "" {
		return errors.New("Pair a Pi relay before requesting off-state recovery")
	}
	raw, err := c.exchange(ctx, 25*time.Second, id, c.relayHost, c.relayIdentity, protocol.PiPort, "POST", "/v2/connection/wake", nil)
	if err != nil {
		return err
	}
	var response struct {
		Status int    `json:"status"`
		Body   []byte `json:"body"`
	}
	var envelope struct {
		Protocol string `json:"protocol"`
		Bridge   string `json:"bridgeIdentity"`
		PC       string `json:"pcIdentity"`
		Data     struct {
			State   string `json:"state"`
			PCState string `json:"pcState"`
		} `json:"data"`
	}
	if json.Unmarshal([]byte(raw), &response) != nil || response.Status != 202 || len(response.Body) > 4096 ||
		json.Unmarshal(response.Body, &envelope) != nil || envelope.Protocol != protocol.App || envelope.Bridge != c.relayIdentity || envelope.PC != c.pcIdentity || envelope.Data.State != "packet_sent" || envelope.Data.PCState != "unconfirmed" {
		return errors.New("Fresh paired Pi wake acknowledgment unavailable")
	}
	return ctx.Err()
}

// Wake means the paired Pi acknowledged its UDP send; it never means PC boot.
func (c *Connection) Wake(requestID string) (string, error) {
	if err := c.ready(); err != nil {
		return "", err
	}
	ctx, finish, err := c.client.begin(c.ctx, 25*time.Second, requestID)
	if err != nil {
		return "", err
	}
	defer finish()
	id, err := files.NewID()
	if err != nil {
		return "", err
	}
	if err := c.wake(ctx, id); err != nil {
		return "", err
	}
	return fileJSON(map[string]any{"state": "wake_sent", "pcIdentity": c.pcIdentity, "relayIdentity": c.relayIdentity})
}

// Connect is one bounded synchronous window, intended for a host worker. It
// never retries text/files. Only a fresh PC health response can return connected.
// Client.Cancel(requestID), either Close or the deadline ends all inner work.
func (c *Connection) Connect(requestID string, timeoutMillis int) (string, error) {
	if err := c.ready(); err != nil {
		return "", err
	}
	if timeoutMillis < 1 || timeoutMillis > 600000 {
		return "", errors.New("Bounded connection window required")
	}
	if !c.window.TryLock() {
		return "", errors.New("A connection window already runs for this PC")
	}
	defer c.window.Unlock()
	ctx, finish, err := c.client.begin(c.ctx, time.Duration(timeoutMillis)*time.Millisecond, requestID)
	if err != nil {
		return "", err
	}
	defer finish()
	for {
		if err := ctx.Err(); err != nil {
			return "", err
		}
		id, err := files.NewID()
		if err != nil {
			return "", err
		}
		h, err := c.probe(ctx, id)
		if err == nil {
			if err := ctx.Err(); err != nil {
				return "", err
			}
			return fileJSON(map[string]any{"state": "connected", "pcIdentity": h.PCIdentity, "epoch": h.Epoch})
		}
		if errors.Is(err, errEnrollmentRequired) || errors.Is(err, errDeviceApprovalRequired) || errors.Is(err, errPeerEnrollmentExpired) {
			return "", err
		}
		var failure *readinessFailure
		if errors.As(err, &failure) && !failure.Retryable {
			return "", err
		}
		if err := ctx.Err(); err != nil {
			return "", err
		}
		timer := time.NewTimer(c.pollInterval)
		select {
		case <-ctx.Done():
			timer.Stop()
			return "", ctx.Err()
		case <-timer.C:
		}
	}
}

// Close cancels only this connection's work and allows deliberate pairing edits
// without closing the node or interrupting file/session operations.
func (c *Connection) Close() {
	if c.cancel == nil {
		return
	}
	c.cancel()
	c.client.mu.Lock()
	if c.client.connections[c.pcIdentity] == c {
		delete(c.client.connections, c.pcIdentity)
	}
	c.client.mu.Unlock()
}

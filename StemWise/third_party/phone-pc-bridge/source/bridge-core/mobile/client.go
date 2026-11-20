// Package mobile is a gomobile-compatible, userspace transport embedded in the host app.
// It does not depend on a separately installed Tailscale app or route the phone's other traffic.
package mobile

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"

	"tailscale.com/client/local"
	"tailscale.com/tsnet"
)

var hostname = regexp.MustCompile(`^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$`)
var sessionIdentity = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)
var sessionToken = regexp.MustCompile(`^[0-9a-f]{32}$`)

var peerHost = regexp.MustCompile(`^[a-z0-9][a-z0-9.-]*\.ts\.net$`)

var errEnrollmentRequired = errors.New("bridge_enrollment_required")
var errDeviceApprovalRequired = errors.New("bridge_device_approval_required")
var errPeerEnrollmentExpired = errors.New("paired_device_enrollment_expired")

const maxBody = 64 << 20

type Client struct {
	node                                 *tsnet.Server
	mu                                   sync.Mutex
	active                               map[string]context.CancelFunc
	operations                           map[string]*Operation
	closed                               bool
	started                              bool
	networkDisabled                      bool
	networkStopError                     error
	networkContext                       context.Context
	networkCancel                        context.CancelFunc
	socketPolicy                         *socketPolicy
	local                                *local.Client
	fileIncoming, fileOutgoing           *files.Store
	fileStorageLease                     *os.File
	fileDestinations                     map[string]bool
	fileDestinationWorkers               sync.WaitGroup
	fileRetentionStop, fileRetentionDone chan struct{}
	fileRetentionStatus                  protocol.BackgroundPass
	connections                          map[string]*Connection
	localFileCopy                        func(context.Context, io.Writer, io.Reader) (int64, error) // nil uses the normal cancellable copy
	dial                                 func(context.Context, string, string) (net.Conn, error)    // nil uses the embedded node
}

// NewClient requires host-private persistent storage. Use Context.getFilesDir(),
// exclude the node state from backup and never ship a pre-enrolled state directory.
// authKey, if supplied, is a one-use enrollment secret, never a constant in an APK.
func NewClient(stateDir, nodeName, authKey string) (*Client, error) {
	if !filepath.IsAbs(stateDir) || !hostname.MatchString(nodeName) {
		return nil, errors.New("Require private absolute storage and a valid node name")
	}
	if err := os.MkdirAll(stateDir, 0700); err != nil {
		return nil, err
	}
	if err := platformStorage(stateDir); err != nil {
		return nil, err
	}
	return &Client{node: &tsnet.Server{Dir: stateDir, Hostname: nodeName, AuthKey: authKey,
		Logf: func(string, ...any) {}, UserLogf: func(string, ...any) {}}, active: make(map[string]context.CancelFunc)}, nil
}

func (c *Client) Start() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return errors.New("Bridge transport closed")
	}
	if c.node == nil {
		return errors.New("Use NewClient to initialize this transport")
	}
	if c.networkDisabled {
		return readinessError("bridge_network_unavailable", true, c.networkStopError)
	}
	if c.networkStopError != nil {
		if err := c.retireNodeLocked(); err != nil {
			return readinessError("bridge_network_unavailable", true, err)
		}
		if err := platformResume(c); err != nil {
			return readinessError("bridge_network_unavailable", true, err)
		}
	}
	if err := platformReady(c); err != nil {
		return readinessError("bridge_network_unavailable", true, err)
	}
	if c.started {
		if c.local == nil {
			return errors.New("Bridge transport startup failed")
		}
		return nil
	}
	c.networkContext, c.networkCancel = context.WithCancel(context.Background())
	lc, err := c.node.LocalClient()
	if err != nil {
		platformSuspend(c)
		retireErr := c.retireNodeLocked()
		var resumeErr error
		if retireErr == nil {
			resumeErr = platformResume(c)
		}
		return errors.Join(err, retireErr, resumeErr)
	}
	c.local, c.started = lc, true
	return nil
}

func (c *Client) native(parent context.Context) (*local.Client, context.Context, func(), error) {
	if err := parent.Err(); err != nil {
		return nil, nil, nil, err
	}
	if err := c.Start(); err != nil {
		return nil, nil, nil, err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed || c.networkDisabled || c.local == nil {
		return nil, nil, nil, readinessError("bridge_network_unavailable", true, nil)
	}
	base := c.networkContext
	if base == nil {
		base = parent
	}
	ctx, cancel := context.WithCancel(base)
	stop := context.AfterFunc(parent, cancel)
	if parent.Err() != nil {
		cancel()
	}
	return c.local, ctx, func() { stop(); cancel() }, nil
}

// Status exposes only integration state, never keys or the full peer/account inventory.
// Running describes the transport; it does not prove the paired PC application is reachable.
func (c *Client) Status() (string, error) {
	parent, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	lc, ctx, finish, err := c.native(parent)
	if err != nil {
		return "", err
	}
	defer finish()
	s, err := lc.Status(ctx)
	if err != nil {
		return "", err
	}
	if s == nil {
		return "", errors.New("Bridge transport unavailable")
	}
	var identity string
	var expired *bool
	if s.Self != nil {
		identity = string(s.Self.ID)
		expired = &s.Self.Expired
	}
	b, err := json.Marshal(map[string]any{"backendState": s.BackendState, "identity": identity, "loginUrl": s.AuthURL, "enrollmentExpired": expired, "backgroundOperations": map[string]any{"file_retention": c.fileRetentionStatus.Snapshot()}})
	return string(b), err
}

// Login requests normal interactive enrollment. The host presents loginUrl from
// Status() through its onboarding flow; the SDK never copies another app's credentials.
func (c *Client) Login() error {
	parent, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	lc, ctx, finish, err := c.native(parent)
	if err != nil {
		return err
	}
	defer finish()
	return lc.StartLoginInteractive(ctx)
}

// Exchange performs one bounded request through this app's embedded node. The
// response body is bytes (base64 in JSON), not a sender-written delivery assertion.
// ponytail: buffers at most 64 MiB per body; stream chunks when larger files are required.
// The host checks its protocol-specific identity/nonce/receipt and never replays
// an uncertain POST/PUT automatically. No redirect or system-VPN fallback exists.
func (c *Client) Exchange(requestID, host, identity string, port int, method, path string, body []byte) (string, error) {
	return c.exchange(context.Background(), 60*time.Second, requestID, host, identity, port, method, path, body)
}

// begin shares cancellation/duplicate-ID enforcement across single requests and
// connection windows. Parent cancellation reaches every window's inner request.
func (c *Client) begin(parent context.Context, timeout time.Duration, id string) (context.Context, func(), error) {
	if id == "" || len(id) > 128 || timeout <= 0 {
		return nil, nil, errors.New("Invalid request window")
	}
	c.mu.Lock()
	if c.closed || c.active[id] != nil {
		c.mu.Unlock()
		return nil, nil, errors.New("Closed transport or duplicate active request ID")
	}
	op := c.operations[id]
	if reservedToken(id) && (op == nil || op.ctx.Err() != nil) {
		if op != nil {
			c.cancelOperationLocked(op)
		}
		c.mu.Unlock()
		return nil, nil, errOperationClosed
	}
	if op != nil {
		deadline, _ := op.ctx.Deadline()
		remaining := time.Until(deadline)
		if remaining <= 0 {
			c.cancelOperationLocked(op)
			c.mu.Unlock()
			return nil, nil, errOperationClosed
		}
		if remaining < timeout {
			timeout = remaining
		}
	}
	ctx, cancel := context.WithTimeout(parent, timeout)
	if c.active == nil {
		c.active = make(map[string]context.CancelFunc)
	}
	if err := ctx.Err(); err != nil {
		if op != nil {
			c.cancelOperationLocked(op)
		}
		c.mu.Unlock()
		cancel()
		return nil, nil, err
	}
	if op != nil {
		op.running = true
	}
	c.active[id] = cancel
	c.mu.Unlock()
	return ctx, func() {
		cancel()
		c.mu.Lock()
		delete(c.active, id)
		if op != nil {
			op.running = false
			c.cancelOperationLocked(op)
		}
		c.mu.Unlock()
	}, nil
}

func (c *Client) exchange(parent context.Context, timeout time.Duration, requestID, host, identity string, port int, method, path string, body []byte) (string, error) {
	ctx, finish, err := c.begin(parent, timeout, requestID)
	if err != nil {
		return "", err
	}
	defer finish()
	return c.exchangeContext(ctx, timeout, requestID, host, identity, port, method, path, body)
}

// Child exchanges inherit their logical operation; finishing an HTTP request cannot retire it.
func (c *Client) exchangeContext(parent context.Context, timeout time.Duration, requestID, host, identity string, port int, method, path string, body []byte) (string, error) {
	if requestID == "" || len(requestID) > 128 || port < 1 || port > 65535 || len(body) > maxBody {
		return "", errors.New("Invalid or oversized bridge request")
	}
	if method != "GET" && method != "POST" && method != "PUT" && method != "DELETE" {
		return "", errors.New("Unsupported bridge method")
	}
	u, err := url.ParseRequestURI(path)
	if err != nil || !strings.HasPrefix(path, "/") || strings.HasPrefix(path, "//") || u.IsAbs() || u.Host != "" || u.Fragment != "" {
		return "", errors.New("Require a relative bridge endpoint")
	}
	ctx, cancel := context.WithTimeout(parent, timeout)
	defer cancel()
	if err := ctx.Err(); err != nil {
		return "", err
	}
	lc, ctx, networkFinish, err := c.native(ctx)
	if err != nil {
		return "", err
	}
	defer networkFinish()
	s, ip, err := c.readyTarget(ctx, lc, host, identity)
	if err != nil {
		return "", err
	}
	transport := &http.Transport{Proxy: nil, DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
		return c.dialTarget(ctx, network, net.JoinHostPort(ip.String(), strconv.Itoa(port)))
	}}
	defer transport.CloseIdleConnections()
	httpClient := &http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	req, err := http.NewRequestWithContext(ctx, method, "http://"+net.JoinHostPort(host, strconv.Itoa(port))+path, bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	req.Header.Set("Cache-Control", "no-store")
	req.Header.Set("Content-Type", "application/octet-stream")
	if strings.HasPrefix(path, "/v2/") {
		req.Header.Set("Content-Type", "application/json")
	}
	response, err := httpClient.Do(req)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, maxBody+1))
	if err != nil {
		return "", err
	}
	if len(data) > maxBody {
		return "", errors.New("Bridge response exceeds 64 MiB")
	}
	if err := ctx.Err(); err != nil {
		return "", err
	}
	b, err := json.Marshal(map[string]any{"status": response.StatusCode, "body": data, "identity": string(s.Self.ID)})
	return string(b), err
}

func (c *Client) Cancel(requestID string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if op := c.operations[requestID]; op != nil {
		c.cancelOperationLocked(op)
	}
	if cancel := c.active[requestID]; cancel != nil {
		cancel()
	}
}

func (c *Client) Close() error {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil
	}
	c.closed = true
	if c.networkCancel != nil {
		c.networkCancel()
	}
	platformSuspend(c)
	for _, op := range c.operations {
		c.cancelOperationLocked(op)
	}
	for _, cancel := range c.active {
		cancel()
	}
	for _, connection := range c.connections {
		connection.cancel()
	}
	if c.fileRetentionStop != nil {
		close(c.fileRetentionStop)
	}
	c.mu.Unlock()
	c.fileDestinationWorkers.Wait()
	if c.fileRetentionDone != nil {
		<-c.fileRetentionDone
	}
	var closeErr error
	if c.fileIncoming != nil {
		closeErr = errors.Join(closeErr, c.fileIncoming.Close())
	}
	if c.fileOutgoing != nil {
		closeErr = errors.Join(closeErr, c.fileOutgoing.Close())
	}
	if c.fileStorageLease != nil {
		closeErr = errors.Join(closeErr, c.fileStorageLease.Close())
	}
	closeErr = errors.Join(closeErr, closeNode(c.node))
	platformClose(c)
	return closeErr
}

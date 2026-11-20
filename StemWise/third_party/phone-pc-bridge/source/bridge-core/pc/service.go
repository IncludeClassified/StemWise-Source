// Package pc serves only paired bridge operations through an embedded private node.
package pc

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"phonepcbridge/codex"
	"phonepcbridge/files"
	"phonepcbridge/protocol"
	"tailscale.com/client/tailscale/apitype"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tsnet"
)

const Port = protocol.Port
const Protocol = protocol.Codex

var token = regexp.MustCompile(`^[0-9a-f]{32}$`)
var nodeID = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)

type Config struct {
	Identity        string
	Host            string
	CodexExecutable string
	CodexSocket     string // Blank owns a CLI backend; nonblank attaches to this daemon only.
	AllowedPeers    []string
	DataDir         string      // Optional dedicated managed directory, supplied by the host.
	IntegrationDir  string      // Existing integration artifacts; blank only for isolated native fixtures.
	BackendPeer     string      // Only this paired Pi may use native adapter controls.
	powerSystem     powerSystem // Isolated tests replace only the OS power boundary.
}

// Request is the internal app/job handoff envelope; native controls use AdapterRequest.
type Request = protocol.Request
type Response = protocol.Response
type peer struct {
	identity     string
	connMu       sync.Mutex
	conn         *codex.Connection
	mu           sync.Mutex
	connectionID string
	authSource   authSource
	authConfig   codex.AuthConfig
	authKnown    bool
}

type Service struct {
	node              *tsnet.Server
	cfg               Config
	epoch             string
	peers             map[string]*peer
	authenticate      func(context.Context, string) (string, error)
	ctx               context.Context
	cancel            context.CancelFunc
	mu                sync.Mutex
	serving, closed   bool
	closeMu           sync.Mutex
	closeDone         bool
	closeErr          error
	closeConnection   func(*codex.Connection) error // Optional OS-retirement failure boundary for isolated checks.
	connectNative     func(context.Context, string, string) (*codex.Connection, error)
	inbox, outbox     *files.Store
	creations         *files.Store
	powerStore        *files.Store
	powerOS           powerSystem
	powerFence        sync.RWMutex
	powerSealed       bool
	creationMu        sync.Mutex
	creationCalls     map[string]*creationCall
	jobFileSnapshots  map[string]*jobFileSnapshot // creationMu owns committed reference projections.
	jobFileVersions   map[string]uint64
	retentionChanged  chan struct{}
	chatCleanup       CleanupStatus
	fileRetentionCode string
	nativeGate        chan struct{}
	handoffMu         sync.Mutex
	handoffs          map[handoffKey][]handoffFile
	handoffDone       []handoffKey
	handoffStatusMu   sync.Mutex
	handoffStatus     handoffDiagnostic
	handoffVersion    uint64
	handoffDirty      bool
	cleanupAfter      string // creationMu owns native cleanup fairness across peers.
}

func randomToken() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b[:])
}

func New(node *tsnet.Server, cfg Config) (*Service, error) {
	if node == nil || !nodeID.MatchString(cfg.Identity) || !strings.HasSuffix(cfg.Host, ".ts.net") ||
		!filepath.IsAbs(cfg.CodexExecutable) || (cfg.CodexSocket != "" && !filepath.IsAbs(cfg.CodexSocket)) ||
		(cfg.DataDir != "" && !filepath.IsAbs(cfg.DataDir)) ||
		(cfg.IntegrationDir != "" && !filepath.IsAbs(cfg.IntegrationDir)) ||
		len(cfg.AllowedPeers) == 0 || len(cfg.AllowedPeers) > 32 {
		return nil, errors.New("Require an enrolled PC, local Codex runtime and explicit paired devices")
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &Service{node: node, cfg: cfg, epoch: randomToken(), peers: make(map[string]*peer), ctx: ctx, cancel: cancel, handoffs: make(map[handoffKey][]handoffFile), retentionChanged: make(chan struct{}, 1)}
	s.powerOS = cfg.powerSystem
	if s.powerOS == nil {
		s.powerOS = platformPowerSystem()
	}
	for _, id := range cfg.AllowedPeers {
		if !nodeID.MatchString(id) || id == cfg.Identity || s.peers[id] != nil {
			cancel()
			return nil, errors.New("Invalid or duplicate paired device")
		}
		s.peers[id] = &peer{identity: id}
	}
	if cfg.BackendPeer != "" && s.peers[cfg.BackendPeer] == nil {
		cancel()
		return nil, errors.New("Backend must be an explicitly paired Pi")
	}
	if err := s.checkHandoffCapacity(); err != nil {
		cancel()
		return nil, err
	}
	if cfg.DataDir != "" {
		info, err := os.Lstat(cfg.DataDir)
		if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			cancel()
			return nil, errors.New("Existing dedicated data directory required")
		}
		// Leave room for adapter/native metadata while sharing in-flight transfer claims.
		budget := files.NewBudget(512 << 20)
		s.inbox, err = files.OpenWithBudget(filepath.Join(cfg.DataDir, "incoming"), budget)
		if err == nil {
			s.outbox, err = files.OpenWithBudget(filepath.Join(cfg.DataDir, "outgoing"), budget)
		}
		if err == nil {
			s.creations, err = files.Open(filepath.Join(cfg.DataDir, "creations"))
		}
		if err == nil && cfg.BackendPeer != "" {
			s.powerStore, err = files.Open(filepath.Join(cfg.DataDir, "power"))
		}
		if err != nil {
			return nil, errors.Join(err, s.Close())
		}
		_, err = s.pruneTransferFiles(time.Now())
		if err != nil && !errors.Is(err, ErrJobFileOwnership) {
			return nil, errors.Join(err, s.Close())
		}
		s.refreshHandoffStatus(s.scanHandoffStatus)
		s.restorePowerAdmission()
	}
	s.authenticate = s.nativePeer
	return s, nil
}

func owner(status *ipnstate.Status, cfg Config) error {
	if status == nil || status.BackendState != "Running" || status.Self == nil ||
		string(status.Self.ID) != cfg.Identity || strings.TrimSuffix(status.Self.DNSName, ".") != cfg.Host ||
		status.Self.UserID <= 0 || status.Self.Expired {
		return errors.New("Paired PC identity unavailable")
	}
	return nil
}

func sender(status *ipnstate.Status, who *apitype.WhoIsResponse) (string, error) {
	if status == nil || status.Self == nil || status.Self.UserID <= 0 || who == nil || who.Node == nil || who.UserProfile == nil || who.Node.User != status.Self.UserID ||
		who.UserProfile.ID != status.Self.UserID || who.Node.StableID == status.Self.ID {
		return "", errors.New("Same-owner authenticated device required")
	}
	var found *ipnstate.PeerStatus
	for _, p := range status.Peer {
		if p != nil && p.ID == who.Node.StableID {
			if found != nil {
				return "", errors.New("Ambiguous sender")
			}
			found = p
		}
	}
	if found == nil || found.UserID != status.Self.UserID || found.Expired {
		return "", errors.New("Unexpired authenticated device required")
	}
	return string(found.ID), nil
}

func (s *Service) nativePeer(ctx context.Context, remote string) (string, error) {
	lc, err := s.node.LocalClient()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	status, err := lc.Status(ctx)
	if err != nil {
		return "", err
	}
	if err := owner(status, s.cfg); err != nil {
		return "", err
	}
	who, err := lc.WhoIs(ctx, remote)
	if err != nil {
		return "", err
	}
	return sender(status, who)
}

// Serve owns only a userspace tailnet listener. No public/OS listener or Funnel.
// The host owns node enrollment/lifecycle. This never starts a replacement daemon.
func (s *Service) Serve(ctx context.Context) (result error) {
	s.mu.Lock()
	if s.serving || s.closed {
		s.mu.Unlock()
		return errors.New("Service already used")
	}
	s.serving = true
	s.mu.Unlock()
	if s.inbox != nil && s.outbox != nil {
		go s.pruneFiles()
	}
	defer func() { result = errors.Join(result, s.Close()) }()
	lc, err := s.node.LocalClient()
	if err != nil {
		return err
	}
	probe, cancel := context.WithTimeout(ctx, 5*time.Second)
	status, err := lc.Status(probe)
	cancel()
	if err != nil {
		return err
	}
	if err = owner(status, s.cfg); err != nil {
		return err
	}
	listener, err := s.node.Listen("tcp", fmt.Sprintf(":%d", Port))
	if err != nil {
		return err
	}
	defer listener.Close()
	httpServer := &http.Server{Handler: http.HandlerFunc(s.handle), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 60 * time.Second, WriteTimeout: 75 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 8192}
	finished := make(chan struct{})
	go func() {
		select {
		case <-ctx.Done():
		case <-s.ctx.Done():
		case <-finished:
			return
		}
		httpServer.Close()
	}()
	err = httpServer.Serve(listener)
	close(finished)
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

func (s *Service) Close() error {
	s.closeMu.Lock()
	defer s.closeMu.Unlock()
	if s.closeDone {
		return s.closeErr
	}
	s.mu.Lock()
	if !s.closed {
		s.closed = true
		s.cancel()
	}
	s.mu.Unlock()
	var retirementErr error
	for _, p := range s.peers {
		p.connMu.Lock()
		if p.conn != nil {
			if err := s.retireConnection(p.conn); err != nil {
				retirementErr = errors.Join(retirementErr, fmt.Errorf("native retirement for %s: %w", p.identity, err))
			} else {
				p.conn = nil
			}
		}
		p.connMu.Unlock()
	}
	if retirementErr != nil {
		return retirementErr
	}
	// Stores remain owned until every native connection has retired.
	if s.inbox != nil {
		s.closeErr = errors.Join(s.closeErr, s.inbox.Close())
	}
	if s.outbox != nil {
		s.closeErr = errors.Join(s.closeErr, s.outbox.Close())
	}
	if s.creations != nil {
		s.closeErr = errors.Join(s.closeErr, s.creations.Close())
	}
	if s.powerStore != nil {
		s.closeErr = errors.Join(s.closeErr, s.powerStore.Close())
	}
	s.closeDone = true
	return s.closeErr
}

func (s *Service) retireConnection(c *codex.Connection) error {
	if s.closeConnection != nil {
		return s.closeConnection(c)
	}
	return c.Close()
}

func (s *Service) response(state string) Response {
	return Response{Protocol: Protocol, PCIdentity: s.cfg.Identity, Epoch: s.epoch, State: state}
}

func reply(w http.ResponseWriter, code int, value any) {
	body, err := json.Marshal(value)
	if err != nil {
		http.Error(w, "Bridge response unavailable", 503)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Length", strconv.Itoa(len(body)))
	w.WriteHeader(code)
	w.Write(body)
}

func (s *Service) handle(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Origin") != "" {
		reply(w, 403, s.response("rejected"))
		return
	}
	id, err := s.authenticate(r.Context(), r.RemoteAddr)
	p := s.peers[id]
	if err != nil || p == nil {
		reply(w, 403, s.response("rejected"))
		return
	}
	select {
	case <-s.ctx.Done():
		reply(w, 503, s.response("unavailable"))
		return
	default:
	}
	if strings.HasPrefix(r.URL.Path, "/v1/files/") || strings.HasPrefix(r.URL.Path, "/v1/outbox") {
		s.fileRequest(w, r, id)
		return
	}
	if r.URL.Path == "/internal/v1/power" {
		if id != s.cfg.BackendPeer {
			reply(w, 403, PowerReply{Protocol: PowerProtocol, PCIdentity: s.cfg.Identity, State: "rejected", Code: "backend_identity_required"})
			return
		}
		s.powerRequest(w, r)
		return
	}
	if r.URL.Path == "/internal/v1/codex" {
		if id != s.cfg.BackendPeer {
			s.adapterError(w, 403, "backend_identity_required", "")
			return
		}
		s.adapterRequest(w, r, p)
		return
	}
	if r.URL.Path == "/v1/codex" || r.URL.Path == "/v1/events" {
		reply(w, 403, s.response("rejected"))
		return
	}
	switch {
	case r.Method == "GET" && r.URL.Path == "/v1/health":
		q := r.URL.Query()
		if len(q) != 1 || len(q["nonce"]) != 1 || !token.MatchString(q.Get("nonce")) {
			reply(w, 400, s.response("rejected"))
			return
		}
		p.mu.Lock()
		meta := map[string]any{"protocol": Protocol, "pcIdentity": s.cfg.Identity, "epoch": s.epoch, "nonce": q.Get("nonce"), "lastSequence": 0, "connectionId": p.connectionID}
		p.mu.Unlock()
		reply(w, 200, meta)
	default:
		reply(w, 404, s.response("rejected"))
	}
}

func (s *Service) connection(ctx context.Context, p *peer) (*codex.Connection, string, error) {
	cfg := s.cfg
	p.connMu.Lock()
	defer p.connMu.Unlock()
	if err := s.ctx.Err(); err != nil {
		return nil, "", err
	}
	if err := ctx.Err(); err != nil {
		return nil, "", err
	}
	if p.conn != nil {
		select {
		case <-p.conn.Done():
			if err := s.retireConnection(p.conn); err != nil {
				return nil, "", err
			}
			p.conn = nil
		default:
		}
	}
	if p.conn == nil {
		// Snapshot the same inherited home the child will use, before it can cache auth.
		if cfg.CodexSocket == "" {
			p.authSource, _ = readAuthSource()
		}
		p.authKnown = false
		connect := s.connectNative
		if connect == nil {
			connect = codex.Connect
		}
		c, err := connect(ctx, cfg.CodexExecutable, cfg.CodexSocket)
		if err != nil {
			var startup *codex.StartupCleanupError
			if errors.As(err, &startup) {
				p.conn = startup.Owner
			}
			return nil, "", err
		}
		p.conn = c
		p.mu.Lock()
		p.connectionID = randomToken()
		p.mu.Unlock()
		go s.pump(p, c)
	}
	p.mu.Lock()
	id := p.connectionID
	p.mu.Unlock()
	return p.conn, id, nil
}

// Drain native events for handoff lifetime; public transcript/event streaming is absent.
func (s *Service) pump(p *peer, c *codex.Connection) {
	for message := range c.Events() {
		s.observeHandoff(c, message)
	}
}

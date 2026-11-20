// Package backend runs the app HTTP API and durable coordinator on the Pi.
package backend

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"phonepcbridge/files"
	"phonepcbridge/pc"
	"phonepcbridge/protocol"
	"regexp"
	"strings"
	"sync"
	"time"
)

const storageReserveBytes = 512 << 20

var token = regexp.MustCompile(`^[0-9a-f]{32}$`)
var nodeID = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)

type Request = protocol.Request
type Config struct {
	Identity       string
	Host           string
	PCIdentity     string
	PCHost         string
	DataDir        string
	IntegrationDir string
}
type peer struct {
	identity     string
	appMu        sync.Mutex
	transfers    sync.RWMutex
	work         map[string]*jobWork
	workAfter    string
	cleanupAfter string
}
type Native interface {
	Models(context.Context, string) (json.RawMessage, error)
	History(context.Context, string, string, int) (json.RawMessage, error)
	NameThread(context.Context, string, string) error
	Call(context.Context, protocol.AdapterRequest) (protocol.AdapterReply, error)
	Put(context.Context, protocol.File, io.Reader) error
	Output(context.Context, protocol.File) (io.ReadCloser, error)
	Reachable(context.Context) error
}
type Service struct {
	cfg                 Config
	ctx                 context.Context
	cancel              context.CancelFunc
	mu                  sync.Mutex
	peers               map[string]*peer
	appWrites           map[string]bool
	started             bool
	inbox, outbox       *files.Store
	authenticate        func(context.Context, string) (string, error)
	native              Native
	wake                func(context.Context) error
	epoch               string
	retentionChanged    chan struct{}
	downloadSlots       chan struct{}
	background          sync.WaitGroup
	dispatchSlots       chan struct{}
	jobsChanged         chan struct{}
	jobPassStatus       protocol.BackgroundPass
	retentionPassStatus protocol.BackgroundPass
	powerMu             sync.Mutex
	powerGeneration     uint64
	powerStreams        int
	powerFault          bool
	powerWaking         bool
	powerClockRead      func() (string, int64, error)
	powerChangedCh      chan struct{}
}

func New(cfg Config, native Native, authenticate func(context.Context, string) (string, error), wake func(context.Context) error) (*Service, error) {
	if !nodeID.MatchString(cfg.Identity) || !nodeID.MatchString(cfg.PCIdentity) || cfg.Identity == cfg.PCIdentity || native == nil || authenticate == nil {
		return nil, errors.New("Exact Pi and PC identities and native transport required")
	}
	ctx, cancel := context.WithCancel(context.Background())
	// shortcut: two active downloads limit Pi disk contention; revisit after measured workloads.
	s := &Service{cfg: cfg, ctx: ctx, cancel: cancel, peers: map[string]*peer{}, native: native, authenticate: authenticate, wake: wake, epoch: randomToken(), retentionChanged: make(chan struct{}, 1), downloadSlots: make(chan struct{}, 2), dispatchSlots: make(chan struct{}, 1), jobsChanged: make(chan struct{}, 1), powerChangedCh: make(chan struct{}, 1)}
	var err error
	budget := files.NewBudget(storageReserveBytes)
	s.inbox, err = files.OpenWithBudget(cfg.DataDir+"/incoming", budget)
	if err == nil {
		s.outbox, err = files.OpenWithBudget(cfg.DataDir+"/outgoing", budget)
	}
	if err != nil {
		s.Close()
		return nil, err
	}
	return s, nil
}
func randomToken() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b[:])
}
func reply(w http.ResponseWriter, status int, data any) {
	body, err := json.Marshal(data)
	if err != nil {
		http.Error(w, "bridge_response_unavailable", 503)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	w.Write(body)
}

// App responses are serialized before releasing appMu and emitted only afterward.
type appResponse struct {
	header http.Header
	status int
	body   []byte
}

func (w *appResponse) Header() http.Header { return w.header }
func (w *appResponse) WriteHeader(status int) {
	if w.status == 0 {
		w.status = status
	}
}
func (w *appResponse) Write(body []byte) (int, error) {
	w.WriteHeader(http.StatusOK)
	w.body = append(w.body, body...)
	return len(body), nil
}
func (w *appResponse) send(destination http.ResponseWriter) {
	if w.status == 0 {
		return // Output downloads stream directly after their explicit unlock.
	}
	for name, values := range w.header {
		destination.Header()[name] = values
	}
	destination.WriteHeader(w.status)
	_, _ = destination.Write(w.body)
}

func (s *Service) getPeer(id string) *peer {
	s.mu.Lock()
	defer s.mu.Unlock()
	p := s.peers[id]
	if p == nil {
		p = &peer{identity: id}
		s.peers[id] = p
	}
	return p
}
func (s *Service) ReadReceivedFile(peer, id string) (protocol.File, []byte, error) {
	return s.inbox.Read(peer, id)
}
func (s *Service) QueueFile(peer, id, name string, content []byte) (protocol.File, error) {
	f, err := files.Describe(id, name, content)
	if err != nil {
		return f, err
	}
	return s.outbox.Put(peer, f, bytes.NewReader(content))
}
func (s *Service) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method == "POST" && r.URL.Path == "/v2/connection/wake" {
		w = wakeResponseWriter{w}
	}
	if r.Header.Get("Origin") != "" {
		s.appError(w, 403, "browser_origin_forbidden")
		return
	}
	id, err := s.authenticate(r.Context(), r.RemoteAddr)
	if err != nil || !nodeID.MatchString(id) || id == s.cfg.Identity || id == s.cfg.PCIdentity {
		s.appError(w, 403, "unpaired_app_identity")
		return
	}
	select {
	case <-s.ctx.Done():
		s.appError(w, 503, "bridge_stopping")
		return
	default:
	}
	p := s.getPeer(id)
	if strings.HasPrefix(r.URL.Path, "/v1/files/") || r.URL.Path == "/v1/outbox" || strings.HasPrefix(r.URL.Path, "/v1/outbox/") {
		s.beginPowerStream()
		defer s.endPowerStream()
		p.transfers.RLock()
		defer p.transfers.RUnlock()
		p.appMu.Lock()
		state, err := s.loadApp(p)
		published := []protocol.File{}
		var upload *appInputUpload
		if err == nil {
			upload = s.inputUpload(r, p, state)
		}
		failure := "app_store_unavailable"
		if os.IsNotExist(err) {
			failure = "app_registration_required"
		}
		if err == nil {
			err = s.expireAppForUse(p, state, time.Now())
			if err != nil {
				failure = "retention_cleanup_unavailable"
			}
		}
		if err == nil {
			for _, j := range state.Jobs {
				if j.State == "completed" && j.Report != nil && j.Report.Status == "completed" {
					published = append(published, j.Outputs...)
				}
			}
		}
		p.appMu.Unlock()
		if err != nil {
			status := 503
			if failure == "app_registration_required" {
				status = 409
			}
			s.appError(w, status, failure)
			return
		}
		q := r.URL.Query()
		if r.Method == "GET" && strings.HasSuffix(r.URL.Path, "/content") && len(q) == 1 && len(q["nonce"]) == 1 && token.MatchString(q.Get("nonce")) {
			if !s.admitDownload(w, r, p) {
				return
			}
			defer func() { <-s.downloadSlots }()
		}
		ctx, cancel := context.WithTimeout(r.Context(), protocol.FileTransferTimeout)
		defer cancel()
		if upload != nil {
			if !upload.deadline.IsZero() {
				var expire context.CancelFunc
				ctx, expire = context.WithDeadline(ctx, upload.deadline)
				defer expire()
			}
			upload.context = ctx
			r.Body = upload
			defer upload.finish()
			control := &inputUploadResponse{ResponseWriter: w, context: ctx}
			w = control
			readStopped := make(chan struct{})
			stopRead := context.AfterFunc(ctx, func() {
				defer close(readStopped)
				// Body.Close alone waits behind a real HTTP body's blocked Read.
				_ = control.SetReadDeadline(time.Now())
				_ = upload.Close()
			})
			defer func() {
				if !stopRead() {
					<-readStopped // No old deadline setter may outlive this response.
				}
			}()
		}
		stop := context.AfterFunc(s.ctx, cancel)
		defer stop()
		if s.ctx.Err() != nil {
			cancel()
		}
		pc.ServeAppFiles(w, r.WithContext(ctx), id, s.cfg.Identity, s.inbox, s.outbox, published)
		return
	}
	if r.Method == "DELETE" && r.URL.Path == "/v2/app" {
		p.transfers.Lock()
		defer p.transfers.Unlock()
	} else if r.Method == "GET" && strings.HasPrefix(r.URL.Path, "/v2/jobs/") && strings.HasSuffix(r.URL.Path, "/content") {
		s.beginPowerStream()
		defer s.endPowerStream()
		p.transfers.RLock()
		defer p.transfers.RUnlock()
	}
	s.appRequest(w, r, p)
}
func (s *Service) admitDownload(w http.ResponseWriter, r *http.Request, p *peer) bool {
	select {
	case s.downloadSlots <- struct{}{}:
		return true
	default:
		if strings.HasPrefix(r.URL.Path, "/v1/") {
			reply(w, 503, protocol.FileReply{Protocol: protocol.Files, PCIdentity: s.cfg.Identity, PeerIdentity: p.identity, Nonce: r.URL.Query().Get("nonce"), State: protocol.DownloadCapacityUnavailable})
		} else {
			s.appError(w, 503, protocol.DownloadCapacityUnavailable)
		}
		return false
	}
}
func (s *Service) Run() error {
	s.mu.Lock()
	if s.started || s.ctx.Err() != nil {
		s.mu.Unlock()
		return errors.New("bridge_runner_unavailable")
	}
	s.started = true
	s.mu.Unlock()
	if err := s.recoverAppWrites(); err != nil {
		return err
	}
	s.resetPowerStartup()
	s.background.Go(s.retainApps)
	s.background.Go(s.workJobs)
	s.background.Go(s.managePower)
	return nil
}
func (s *Service) Close() {
	s.cancel()
	s.background.Wait()
	if s.inbox != nil {
		s.inbox.Close()
	}
	if s.outbox != nil {
		s.outbox.Close()
	}
}

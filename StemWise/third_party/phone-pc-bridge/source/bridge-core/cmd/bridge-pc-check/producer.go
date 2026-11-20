package main

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/pc"
	"phonepcbridge/protocol"
)

const producerProtocol = "phone-pc-bridge-producer-v1"

var producerNode = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)

type producerConfig struct {
	Protocol     string `json:"protocol"`
	Address      string `json:"address"`
	PCIdentity   string `json:"pcIdentity"`
	PeerIdentity string `json:"peerIdentity"`
	Token        string `json:"token"`
	Certificate  string `json:"certificate"`
}

type producer struct {
	config  producerConfig
	service *pc.Service
	server  *http.Server
	path    string
}

func producerCertificate() (tls.Certificate, string, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return tls.Certificate{}, "", err
	}
	now := time.Now()
	template := &x509.Certificate{SerialNumber: serial, NotBefore: now.Add(-time.Hour), NotAfter: now.AddDate(10, 0, 0), IPAddresses: []net.IP{net.ParseIP("127.0.0.1")}, BasicConstraintsValid: true, IsCA: true, KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}, base64.StdEncoding.EncodeToString(der), nil
}

func startProducer(state string, service *pc.Service, pcID, peer string) (*producer, error) {
	return startNamedProducer(state, service, pcID, peer, "producer.json")
}
func startNamedProducer(state string, service *pc.Service, pcID, peer, name string) (*producer, error) {
	if service == nil || !producerNode.MatchString(pcID) || !producerNode.MatchString(peer) || pcID == peer {
		return nil, errors.New("Exact local producer pairing required")
	}
	if err := privateProducerPath(state); err != nil {
		return nil, err
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	var secret [32]byte
	if _, err = rand.Read(secret[:]); err != nil {
		listener.Close()
		return nil, err
	}
	certificate, public, err := producerCertificate()
	if err != nil {
		listener.Close()
		return nil, err
	}
	p := &producer{config: producerConfig{producerProtocol, listener.Addr().String(), pcID, peer, hex.EncodeToString(secret[:]), public}, service: service, path: filepath.Join(state, name)}
	if _, err = os.Lstat(p.path); err == nil {
		if err = privateProducerPath(p.path); err != nil {
			listener.Close()
			return nil, err
		}
	} else if !os.IsNotExist(err) {
		listener.Close()
		return nil, err
	}
	f, err := os.CreateTemp(state, ".producer-")
	if err != nil {
		listener.Close()
		return nil, err
	}
	defer os.Remove(f.Name())
	if err = privateProducerFile(f.Name()); err == nil {
		err = json.NewEncoder(f).Encode(p.config)
	}
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err == nil {
		err = closeErr
	}
	if err == nil {
		err = os.Rename(f.Name(), p.path)
	}
	if err != nil {
		listener.Close()
		return nil, err
	}
	p.server = &http.Server{Handler: p, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 30 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 10 * time.Second, MaxHeaderBytes: 8192}
	go p.server.Serve(tls.NewListener(listener, &tls.Config{Certificates: []tls.Certificate{certificate}, MinVersion: tls.VersionTLS13}))
	return p, nil
}

func (p *producer) Close() error {
	err := p.server.Close()
	data, readErr := os.ReadFile(p.path)
	var actual producerConfig
	if readErr == nil && json.Unmarshal(data, &actual) == nil && actual.Token == p.config.Token {
		if removeErr := os.Remove(p.path); err == nil {
			err = removeErr
		}
	}
	return err
}

func producerQuery(q url.Values, keys ...string) bool {
	if len(q) != len(keys) {
		return false
	}
	for _, key := range keys {
		if len(q[key]) != 1 {
			return false
		}
	}
	return true
}

func producerSignature(cfg producerConfig, r *http.Request, response bool, status int, data []byte) string {
	sum := sha256.Sum256(data)
	return producerHashSignature(cfg, r, response, status, hex.EncodeToString(sum[:]))
}
func producerHashSignature(cfg producerConfig, r *http.Request, response bool, status int, hash string) string {
	message := r.Method + "\n" + r.Host + "\n" + r.URL.RequestURI()
	if response {
		message = "response\n" + message + "\n" + strconv.Itoa(status) + "\n" + hash
	}
	mac := hmac.New(sha256.New, []byte(cfg.Token))
	mac.Write([]byte(message))
	return hex.EncodeToString(mac.Sum(nil))
}

func (p *producer) respond(w http.ResponseWriter, r *http.Request, status int, result protocol.FileReply) {
	data, err := json.Marshal(result)
	if err != nil {
		http.Error(w, "Local result unavailable", 503)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("X-Bridge-Response", producerSignature(p.config, r, true, status, data))
	w.WriteHeader(status)
	w.Write(data)
}

func (p *producer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	remote, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil || !net.ParseIP(remote).IsLoopback() || r.Host != p.config.Address || r.URL.IsAbs() || len(r.Header.Values("Origin")) != 0 || len(r.Header.Values("Sec-Fetch-Site")) != 0 || len(r.Header.Values("X-Bridge-Request")) != 1 || subtle.ConstantTimeCompare([]byte(r.Header.Get("X-Bridge-Request")), []byte(producerSignature(p.config, r, false, 0, nil))) != 1 {
		http.Error(w, "Local bridge authentication required", 403)
		return
	}
	q, err := url.ParseQuery(r.URL.RawQuery)
	if err != nil || len(q["nonce"]) != 1 || !files.ValidID(q.Get("nonce")) {
		http.Error(w, "Invalid local request", 400)
		return
	}
	ctx, cancel := p.service.FileContext(r.Context())
	defer cancel()
	r = r.WithContext(ctx)
	result := protocol.FileReply{Protocol: producerProtocol, PCIdentity: p.config.PCIdentity, PeerIdentity: p.config.PeerIdentity, Nonce: q.Get("nonce"), State: "unavailable"}
	if ctx.Err() != nil {
		p.respond(w, r, 503, result)
		return
	}
	status := 200
	parts := strings.Split(r.URL.Path, "/")
	if r.Method == "GET" && r.URL.Path == "/v1/health" && producerQuery(q, "nonce") {
		result.State = "ready"
	} else if r.Method == "GET" && (r.URL.Path == "/v1/outbox" || r.URL.Path == "/v1/inbox") && producerQuery(q, "nonce", "cursor", "limit") {
		limit, parseErr := strconv.Atoi(q.Get("limit"))
		if parseErr != nil || limit < 1 || limit > 100 || (q.Get("cursor") != "" && !files.ValidID(q.Get("cursor"))) {
			http.Error(w, "Invalid page", 400)
			return
		}
		var page protocol.FilePage
		if parts[2] == "outbox" {
			page, err = p.service.QueuedFiles(r.Context(), p.config.PeerIdentity, q.Get("cursor"), limit)
			result.State = "queued"
		} else {
			page, err = p.service.ReceivedFiles(r.Context(), p.config.PeerIdentity, q.Get("cursor"), limit)
			result.State = "stored"
		}
		result.Page = &page
	} else if len(parts) >= 4 && len(parts) <= 5 && parts[1] == "v1" && (parts[2] == "outbox" || parts[2] == "inbox") && files.ValidID(parts[3]) {
		id := parts[3]
		owned := producerQuery(q, "nonce", "name", "size", "sha256", "appId", "jobId", "threadId")
		if r.Method == "POST" && parts[2] == "outbox" && len(parts) == 4 && owned && r.Header.Get("Content-Type") == "application/octet-stream" {
			size, sizeErr := strconv.ParseInt(q.Get("size"), 10, 64)
			expected := protocol.File{ID: id, Name: q.Get("name"), Size: size, SHA256: q.Get("sha256")}
			if sizeErr != nil || !files.Valid(expected) {
				http.Error(w, "Invalid source descriptor", 400)
				return
			}
			_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(protocol.FileTransferTimeout))
			_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(protocol.FileTransferTimeout))
			var queued protocol.File
			queued, err = p.service.QueueJobFileReader(r.Context(), p.config.PeerIdentity, q.Get("appId"), q.Get("jobId"), q.Get("threadId"), expected, http.MaxBytesReader(w, r.Body, expected.Size+1))
			result.File = &queued
			result.State = "queued"
		} else if r.Method == "DELETE" && len(parts) == 4 && producerQuery(q, "nonce") {
			if parts[2] == "outbox" {
				err = p.service.DiscardQueuedFile(p.config.PeerIdentity, id)
			} else {
				err = p.service.DiscardReceivedFile(p.config.PeerIdentity, id)
			}
			result.State = "removed_local"
		} else if producerQuery(q, "nonce") && r.Method == "GET" && (len(parts) == 4 || (len(parts) == 5 && parts[2] == "inbox" && parts[4] == "content")) {
			var info protocol.File
			var data *os.File
			if parts[2] == "outbox" {
				info, err = p.service.QueuedFileInfo(r.Context(), p.config.PeerIdentity, id)
				result.State = "queued"
			} else {
				if len(parts) == 5 {
					info, data, err = p.service.OpenReceivedFile(r.Context(), p.config.PeerIdentity, id)
					if data != nil {
						defer data.Close()
					}
				} else {
					info, err = p.service.ReceivedFileInfo(r.Context(), p.config.PeerIdentity, id)
				}
				result.State = "stored"
			}
			result.File = &info
			if err == nil && len(parts) == 5 {
				w.Header().Set("Content-Type", "application/octet-stream")
				w.Header().Set("Content-Length", strconv.FormatInt(info.Size, 10))
				w.Header().Set("X-Bridge-PC", result.PCIdentity)
				w.Header().Set("X-Bridge-Peer", result.PeerIdentity)
				w.Header().Set("X-Bridge-Nonce", result.Nonce)
				w.Header().Set("X-Bridge-Response", producerHashSignature(p.config, r, true, 200, info.SHA256))
				_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(protocol.FileTransferTimeout))
				files.CopyContext(r.Context(), w, data)
				return
			}
		} else {
			http.Error(w, "Unsupported local operation", 404)
			return
		}
	} else {
		http.Error(w, "Unsupported local operation", 404)
		return
	}
	if err != nil {
		result.State = "unavailable"
		result.File = nil
		result.Page = nil
		status = 503
		if os.IsNotExist(err) {
			status = 404
		}
		if errors.Is(err, files.ErrConflict) {
			status = 409
			result.State = "conflict"
		}
		if errors.Is(err, files.ErrContentMismatch) {
			status = 400
			result.State = "content_mismatch"
		}
		if errors.Is(err, files.ErrStorageCapacity) {
			result.State = protocol.StorageCapacityUnavailable
		}
		for _, ownerError := range []error{pc.ErrJobFileOwnership, pc.ErrJobFileReferenced} {
			if errors.Is(err, ownerError) {
				result.State = ownerError.Error()
			}
		}
	}
	p.respond(w, r, status, result)
}

func loadProducer(state, peer string) (producerConfig, error) {
	var cfg producerConfig
	if !filepath.IsAbs(state) || !producerNode.MatchString(peer) {
		return cfg, errors.New("Exact private PC state required")
	}
	path := filepath.Join(state, "producer.json")
	alternate := filepath.Join(state, "producer-"+peer+".json")
	if _, err := os.Lstat(alternate); err == nil {
		path = alternate
	} else if !os.IsNotExist(err) {
		return cfg, err
	}
	if err := privateProducerPath(state); err != nil {
		return cfg, err
	}
	if err := privateProducerPath(path); err != nil {
		return cfg, err
	}
	f, err := os.Open(path)
	if err != nil {
		return cfg, err
	}
	defer f.Close()
	d := json.NewDecoder(io.LimitReader(f, 2049))
	d.DisallowUnknownFields()
	if err = d.Decode(&cfg); err != nil {
		return cfg, errors.New("Local producer configuration unavailable")
	}
	var extra any
	if d.Decode(&extra) != io.EOF {
		return cfg, errors.New("Invalid local producer configuration")
	}
	address, err := netip.ParseAddrPort(cfg.Address)
	if err != nil || address.Addr() != netip.MustParseAddr("127.0.0.1") || address.Port() == 0 || cfg.Protocol != producerProtocol || cfg.PeerIdentity != peer || !producerNode.MatchString(cfg.PCIdentity) || cfg.PCIdentity == peer || len(cfg.Token) != 64 {
		return cfg, errors.New("Local producer target did not validate")
	}
	if secret, err := hex.DecodeString(cfg.Token); err != nil || len(secret) != 32 {
		return cfg, errors.New("Invalid local producer credential")
	}
	der, err := base64.StdEncoding.DecodeString(cfg.Certificate)
	if err != nil {
		return cfg, errors.New("Pinned local certificate unavailable")
	}
	if _, err = x509.ParseCertificate(der); err != nil {
		return cfg, errors.New("Pinned local certificate unavailable")
	}
	return cfg, nil
}

func producerHTTP(cfg producerConfig, method, path string, q url.Values, data []byte) (*http.Response, error) {
	return producerHTTPReader(cfg, method, path, q, bytes.NewReader(data))
}
func producerHTTPReader(cfg producerConfig, method, path string, q url.Values, data io.Reader) (*http.Response, error) {
	der, err := base64.StdEncoding.DecodeString(cfg.Certificate)
	if err != nil {
		return nil, errors.New("Pinned local certificate unavailable")
	}
	certificate, err := x509.ParseCertificate(der)
	if err != nil {
		return nil, errors.New("Pinned local certificate unavailable")
	}
	roots := x509.NewCertPool()
	roots.AddCert(certificate)
	r, err := http.NewRequestWithContext(context.Background(), method, "https://"+cfg.Address+path+"?"+q.Encode(), data)
	if err != nil {
		return nil, err
	}
	r.Header.Set("X-Bridge-Request", producerSignature(cfg, r, false, 0, nil))
	r.Header.Set("Content-Type", "application/octet-stream")
	timeout := 30 * time.Second
	if (method == "POST" && strings.HasPrefix(path, "/v1/outbox/")) || strings.HasSuffix(path, "/content") {
		timeout = protocol.FileTransferTimeout
	}
	client := &http.Client{Timeout: timeout, Transport: &http.Transport{Proxy: nil, DisableKeepAlives: true, TLSClientConfig: &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS13}}, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Do(r)
	if err != nil {
		return nil, errors.New("Local operation unconfirmed; inspect the original transfer ID before retrying")
	}
	return response, nil
}

func producerReply(cfg producerConfig, method, path, state string, q url.Values, data []byte) (protocol.FileReply, error) {
	return producerReplyReader(cfg, method, path, state, q, bytes.NewReader(data))
}
func producerReplyReader(cfg producerConfig, method, path, state string, q url.Values, data io.Reader) (protocol.FileReply, error) {
	var result protocol.FileReply
	nonce, err := files.NewID()
	if err != nil {
		return result, err
	}
	q.Set("nonce", nonce)
	r, err := producerHTTPReader(cfg, method, path, q, data)
	if err != nil {
		return result, err
	}
	defer r.Body.Close()
	body, readErr := io.ReadAll(io.LimitReader(r.Body, (128<<10)+1))
	if readErr != nil || len(body) > 128<<10 || subtle.ConstantTimeCompare([]byte(r.Header.Get("X-Bridge-Response")), []byte(producerSignature(cfg, r.Request, true, r.StatusCode, body))) != 1 {
		return result, errors.New("Local server reply could not be authenticated")
	}
	if protocol.DecodeStrict(body, &result) != nil || result.Protocol != producerProtocol || result.PCIdentity != cfg.PCIdentity || result.PeerIdentity != cfg.PeerIdentity || result.Nonce != nonce {
		return result, errors.New("Fresh local result unavailable; inspect the original transfer ID before retrying")
	}
	if r.StatusCode != 200 {
		if r.StatusCode >= 400 && r.StatusCode <= 599 && result.File == nil && result.Page == nil {
			action := "inspect_original_transfer_receipt"
			switch result.State {
			case "unavailable", "conflict", "content_mismatch":
			case protocol.StorageCapacityUnavailable:
				if r.StatusCode != 503 {
					return result, errors.New("Fresh local rejection unavailable")
				}
				action = "release_pc_storage_or_wait_for_transfers_then_retry_same_transfer"
			case pc.ErrJobFileOwnership.Error(), pc.ErrJobFileReferenced.Error():
				if r.StatusCode != 503 {
					return result, errors.New("Fresh local rejection unavailable")
				}
				switch result.State {
				case pc.ErrJobFileOwnership.Error():
					action = "inspect_exact_job_owner"
				case pc.ErrJobFileReferenced.Error():
					action = "wait_for_owned_job_cleanup"
				}
			default:
				return result, errors.New("Fresh local rejection unavailable")
			}
			return result, fmt.Errorf("local_file_rejected http_status=%d state=%s action=%s", r.StatusCode, result.State, action)
		}
		return result, errors.New("Fresh local rejection unavailable")
	}
	if result.State != state {
		return result, errors.New("Fresh local result unavailable; inspect the original transfer ID before retrying")
	}
	if result.File != nil && !files.Valid(*result.File) {
		return result, errors.New("Invalid local file descriptor")
	}
	return result, nil
}

func producerCommand(state, peer, operation, id, path, cursor string, limit int, app, job, thread string) error {
	owned := app != "" || job != "" || thread != ""
	if (owned || operation == "queue") && (operation != "queue" || !files.ValidID(app) || !files.ValidID(job) || !producerNode.MatchString(thread)) {
		return errors.New("Queue requires the exact app-id, job-id and thread-id together")
	}
	if operation == "new-id" {
		id, err := files.NewID()
		if err != nil {
			return err
		}
		return json.NewEncoder(os.Stdout).Encode(map[string]string{"id": id})
	}
	cfg, err := loadProducer(state, peer)
	if err != nil {
		return err
	}
	var result protocol.FileReply
	switch operation {
	case "health":
		result, err = producerReply(cfg, "GET", "/v1/health", "ready", url.Values{}, nil)
	case "queued", "received":
		if limit < 1 || limit > 100 || (cursor != "" && !files.ValidID(cursor)) {
			return errors.New("Exact cursor and page limit 1–100 required")
		}
		collection, status := "outbox", "queued"
		if operation == "received" {
			collection, status = "inbox", "stored"
		}
		result, err = producerReply(cfg, "GET", "/v1/"+collection, status, url.Values{"cursor": {cursor}, "limit": {strconv.Itoa(limit)}}, nil)
		if err == nil && result.Page == nil {
			err = errors.New("Local page unavailable")
		}
	case "queue":
		if !files.ValidID(id) || !filepath.IsAbs(path) {
			return errors.New("Stable transfer ID and absolute source file required")
		}
		f, openErr := os.Open(path)
		if openErr != nil {
			return openErr
		}
		info, statErr := f.Stat()
		if statErr != nil || !info.Mode().IsRegular() {
			f.Close()
			return errors.New("Regular source file required")
		}
		defer f.Close()
		expected, describeErr := files.DescribeReader(id, filepath.Base(path), f)
		if describeErr != nil {
			return describeErr
		}
		if _, err := f.Seek(0, io.SeekStart); err != nil {
			return err
		}
		query := url.Values{"name": {expected.Name}, "size": {strconv.FormatInt(expected.Size, 10)}, "sha256": {expected.SHA256}}
		if owned {
			query.Set("appId", app)
			query.Set("jobId", job)
			query.Set("threadId", thread)
		}
		result, err = producerReplyReader(cfg, "POST", "/v1/outbox/"+id, "queued", query, f)
		if err == nil && (result.File == nil || *result.File != expected) {
			err = errors.New("Actual queued source differs")
		}
	case "queued-info", "received-info", "discard-queued", "discard-received", "read":
		if !files.ValidID(id) {
			return errors.New("Exact transfer ID required")
		}
		collection, status, method := "outbox", "queued", "GET"
		if operation == "received-info" || operation == "discard-received" || operation == "read" {
			collection, status = "inbox", "stored"
		}
		if strings.HasPrefix(operation, "discard-") {
			method, status = "DELETE", "removed_local"
		}
		result, err = producerReply(cfg, method, "/v1/"+collection+"/"+id, status, url.Values{}, nil)
		if err == nil && method == "DELETE" && (result.File != nil || result.Page != nil) {
			err = errors.New("Invalid exact local discard result")
		}
		if err == nil && method != "DELETE" && (result.File == nil || result.File.ID != id) {
			err = errors.New("Exact local descriptor unavailable")
		}
		if err == nil && operation == "read" {
			err = saveProducerFile(cfg, *result.File, path)
		}
	default:
		return errors.New("Unsupported local files command")
	}
	if err != nil {
		return err
	}
	return json.NewEncoder(os.Stdout).Encode(result)
}

func saveProducerFile(cfg producerConfig, expected protocol.File, path string) error {
	if !filepath.IsAbs(path) {
		return errors.New("Absolute new destination required")
	}
	nonce, err := files.NewID()
	if err != nil {
		return err
	}
	r, err := producerHTTP(cfg, "GET", "/v1/inbox/"+expected.ID+"/content", url.Values{"nonce": {nonce}}, nil)
	if err != nil {
		return err
	}
	defer r.Body.Close()
	if r.StatusCode != 200 || r.Header.Get("X-Bridge-PC") != cfg.PCIdentity || r.Header.Get("X-Bridge-Peer") != cfg.PeerIdentity || r.Header.Get("X-Bridge-Nonce") != nonce {
		return errors.New("Fresh recipient bytes unavailable")
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	complete := false
	defer func() {
		f.Close()
		if !complete {
			os.Remove(path)
		}
	}()
	hash := sha256.New()
	size, err := io.Copy(io.MultiWriter(f, hash), io.LimitReader(r.Body, expected.Size+1))
	actualHash := hex.EncodeToString(hash.Sum(nil))
	if err == nil && (size != expected.Size || actualHash != expected.SHA256 || subtle.ConstantTimeCompare([]byte(r.Header.Get("X-Bridge-Response")), []byte(producerHashSignature(cfg, r.Request, true, r.StatusCode, actualHash))) != 1) {
		err = errors.New("Local recipient bytes or authentication differ; destination discarded")
	}
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err == nil {
		err = closeErr
	}
	complete = err == nil
	return err
}

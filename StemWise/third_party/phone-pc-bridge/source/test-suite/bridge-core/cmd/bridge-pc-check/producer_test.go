package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"

	"phonepcbridge/files"
	"phonepcbridge/pc"
	"phonepcbridge/protocol"
	"tailscale.com/tsnet"
)

func TestProducerManagedFiles(t *testing.T) {
	root := t.TempDir()
	producerTestACL(t, root, false)
	id, _ := files.NewID()
	source := []byte("actual local source\x00\xff مرحبا")
	expected, _ := files.Describe(id, "source.bin", source)
	if err := os.Mkdir(filepath.Join(root, "files"), 0700); err != nil {
		t.Fatal(err)
	}
	inbox, err := files.Open(filepath.Join(root, "files", "incoming"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err = inbox.Put("nPhone", expected, bytes.NewReader(source)); err != nil {
		t.Fatal(err)
	}
	if err = inbox.Close(); err != nil {
		t.Fatal(err)
	}
	seedProducerOwner(t, filepath.Join(root, "files"), root, "nPhone")
	s, err := pc.New(&tsnet.Server{}, pc.Config{Identity: "nPc", Host: "pc.ts.net", CodexExecutable: filepath.Join(root, "codex.exe"), AllowedPeers: []string{"nPhone"}, DataDir: filepath.Join(root, "files"), IntegrationDir: root})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	p, err := startProducer(root, s, "nPc", "nPhone")
	if err != nil {
		t.Fatal(err)
	}
	defer p.Close()
	cfg, err := loadProducer(root, "nPhone")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = loadProducer(root, "nOther"); err == nil {
		t.Fatal("Wrong paired peer accepted")
	}
	if _, err = producerReply(cfg, "GET", "/v1/health", "ready", url.Values{}, nil); err != nil {
		t.Fatal(err)
	}
	q := producerOwnerQuery(expected)
	reply, err := producerReply(cfg, "POST", "/v1/outbox/"+id, "queued", q, source)
	if err != nil || reply.File == nil || *reply.File != expected {
		t.Fatal("Actual queue failed", err)
	}
	if actual, err := os.ReadFile(filepath.Join(root, "files", "outgoing", "nPhone", id, "content")); err != nil || !bytes.Equal(source, actual) {
		t.Fatal("Independent source bytes differ", err)
	}
	if _, err = producerReply(cfg, "POST", "/v1/outbox/"+id, "queued", q, source); err != nil {
		t.Fatal("Same-ID retry conflicted", err)
	}
	if _, err = producerReply(cfg, "POST", "/v1/outbox/"+id, "queued", q, []byte("fabricated")); err == nil {
		t.Fatal("Fake source descriptor accepted")
	}
	changed := []byte("different actual bytes")
	different, _ := files.Describe(id, "source.bin", changed)
	q.Set("size", strconv.FormatInt(different.Size, 10))
	q.Set("sha256", different.SHA256)
	if _, err = producerReply(cfg, "POST", "/v1/outbox/"+id, "queued", q, changed); err == nil {
		t.Fatal("Stable source overwritten")
	}
	if page, err := producerReply(cfg, "GET", "/v1/outbox", "queued", url.Values{"cursor": {""}, "limit": {"1"}}, nil); err != nil || page.Page == nil || len(page.Page.Files) != 1 || page.Page.Files[0] != expected {
		t.Fatal("Actual queue inventory unavailable", err)
	}
	incoming, err := producerReply(cfg, "GET", "/v1/inbox/"+id, "stored", url.Values{}, nil)
	if err != nil || incoming.File == nil || *incoming.File != expected {
		t.Fatal("Actual recipient unavailable", err)
	}
	destination := filepath.Join(root, "received.bin")
	if err = saveProducerFile(cfg, expected, destination); err != nil {
		t.Fatal(err)
	}
	if actual, err := os.ReadFile(destination); err != nil || !bytes.Equal(actual, source) {
		t.Fatal("Saved bytes differ", err)
	}
	if err = saveProducerFile(cfg, expected, destination); err == nil {
		t.Fatal("Existing destination overwritten")
	}
	if err = os.WriteFile(filepath.Join(root, "files", "incoming", "nPhone", id, "content"), []byte("corrupt"), 0600); err != nil {
		t.Fatal(err)
	}
	if err = saveProducerFile(cfg, expected, filepath.Join(root, "must-not-exist.bin")); err == nil {
		t.Fatal("Corrupt content saved")
	}
	if _, err = os.Stat(filepath.Join(root, "must-not-exist.bin")); !os.IsNotExist(err) {
		t.Fatal("Corrupt content created destination")
	}
	if _, err = producerReply(cfg, "DELETE", "/v1/outbox/"+id, "removed_local", url.Values{}, nil); err == nil || !strings.Contains(err.Error(), pc.ErrJobFileReferenced.Error()) {
		t.Fatal("Referenced output was discarded or lost typed rejection", err)
	}
	if actual, err := os.ReadFile(filepath.Join(root, "files", "outgoing", "nPhone", id, "content")); err != nil || !bytes.Equal(actual, source) {
		t.Fatal("Referenced source changed", err)
	}
	// Generic cache files remain explicitly discardable; they carry no job references.
	id, _ = files.NewID()
	if _, err = s.QueueFile(context.Background(), "nPhone", id, expected.Name, source); err != nil {
		t.Fatal(err)
	}
	inbox, err = files.Open(filepath.Join(root, "files", "incoming"))
	if err != nil {
		t.Fatal(err)
	}
	cache, _ := files.Describe(id, expected.Name, source)
	if _, err = inbox.Put("nPhone", cache, bytes.NewReader(source)); err != nil {
		t.Fatal(err)
	}
	inbox.Close()
	if err = os.WriteFile(filepath.Join(root, "files", "incoming", "nPhone", id, "content"), []byte("corrupt"), 0600); err != nil {
		t.Fatal(err)
	}
	// The real authenticated CLI discard must not depend on readable old metadata.
	for _, collection := range []string{"queued", "received"} {
		if collection == "queued" {
			if _, err := s.QueueFile(context.Background(), "nPhone", id, expected.Name, source); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(filepath.Join(root, "files", "outgoing", "nPhone", id, "metadata.json"), []byte("damaged metadata"), 0600); err != nil {
				t.Fatal(err)
			}
		}
		if err := producerCommand(root, "nPhone", "discard-"+collection, id, "", "", 0, "", "", ""); err != nil {
			t.Fatal("Authenticated discard failed on damaged bytes or metadata", collection, err)
		}
		folder := "outgoing"
		if collection == "received" {
			folder = "incoming"
		}
		if _, err := os.Stat(filepath.Join(root, "files", folder, "nPhone", id)); !os.IsNotExist(err) {
			t.Fatal("CLI discard left damaged object", collection, err)
		}
	}
}

func TestProducerRejectsForeignRequests(t *testing.T) {
	p := &producer{config: producerConfig{producerProtocol, "127.0.0.1:32123", "nPc", "nPhone", strings.Repeat("a", 64), ""}}
	for _, attack := range []string{"authorization", "origin", "fetch-site", "host", "remote", "absolute", "duplicate-nonce", "query", "path", "method"} {
		r := httptest.NewRequest("GET", "http://127.0.0.1:32123/v1/health?nonce="+strings.Repeat("b", 32), nil)
		r.RemoteAddr = "127.0.0.1:60000"
		r.URL.Scheme = ""
		r.URL.Host = ""
		r.Header.Set("X-Bridge-Request", producerSignature(p.config, r, false, 0, nil))
		switch attack {
		case "authorization":
			r.Header.Del("X-Bridge-Request")
		case "origin":
			r.Header.Set("Origin", "null")
		case "fetch-site":
			r.Header.Set("Sec-Fetch-Site", "same-origin")
		case "host":
			r.Host = "evil.example"
		case "remote":
			r.RemoteAddr = "192.168.100.55:60000"
		case "absolute":
			r.URL.Scheme = "http"
			r.URL.Host = p.config.Address
		case "duplicate-nonce":
			r.URL.RawQuery += "&nonce=" + strings.Repeat("b", 32)
		case "query":
			r.URL.RawQuery += "&peer=nOther"
		case "path":
			r.URL.Path = "/v1/outbox/../other"
		case "method":
			r.Method = "PUT"
		}
		w := httptest.NewRecorder()
		p.ServeHTTP(w, r)
		if w.Code == 200 {
			t.Fatalf("Accepted %s", attack)
		}
	}
	root := t.TempDir()
	producerTestACL(t, root, false)
	cfg := producerConfig{producerProtocol, "evil.example:32123", "nPc", "nPhone", strings.Repeat("a", 64), ""}
	data, _ := json.Marshal(cfg)
	if err := os.WriteFile(filepath.Join(root, "producer.json"), data, 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := loadProducer(root, "nPhone"); err == nil {
		t.Fatal("Nonliteral loopback target accepted")
	}
	producerTestACL(t, root, true)
	if err := privateProducerPath(root); err == nil {
		t.Fatal("Public local credentials permitted")
	}
}

func TestProducerRejectsImpersonatedListener(t *testing.T) {
	secret := strings.Repeat("c", 64)
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "" || strings.Contains(r.Header.Get("X-Bridge-Request"), secret) {
			t.Error("Local credential disclosed")
		}
		json.NewEncoder(w).Encode(protocol.FileReply{Protocol: producerProtocol, PCIdentity: "nPc", PeerIdentity: "nPhone", Nonce: r.URL.Query().Get("nonce"), State: "ready"})
	}))
	defer server.Close()
	cfg := producerConfig{producerProtocol, strings.TrimPrefix(server.URL, "https://"), "nPc", "nPhone", secret, base64.StdEncoding.EncodeToString(server.Certificate().Raw)}
	if _, err := producerReply(cfg, "GET", "/v1/health", "ready", url.Values{}, nil); err == nil {
		t.Fatal("Impersonated server's self-reported success accepted")
	}
}

func TestProducerPinsServerBeforeSendingBytes(t *testing.T) {
	var reached atomic.Bool
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { reached.Store(true); w.WriteHeader(200) }))
	defer server.Close()
	_, public, err := producerCertificate()
	if err != nil {
		t.Fatal(err)
	}
	cfg := producerConfig{producerProtocol, strings.TrimPrefix(server.URL, "https://"), "nPc", "nPhone", strings.Repeat("d", 64), public}
	response, err := producerHTTP(cfg, "POST", "/v1/outbox/"+strings.Repeat("e", 32), url.Values{"nonce": {strings.Repeat("f", 32)}}, []byte("private source bytes"))
	if response != nil {
		response.Body.Close()
	}
	if err == nil || reached.Load() {
		t.Fatal("Unowned listener received the HTTP request or source bytes")
	}
}

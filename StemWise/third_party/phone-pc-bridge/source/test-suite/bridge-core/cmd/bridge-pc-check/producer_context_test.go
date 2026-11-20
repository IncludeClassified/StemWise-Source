package main

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"testing/synctest"

	"phonepcbridge/files"
	"phonepcbridge/pc"
	"phonepcbridge/protocol"
	"tailscale.com/tsnet"
)

type closingProducerContentWriter struct {
	*httptest.ResponseRecorder
	service  *pc.Service
	writes   int
	closeErr error
}

func (w *closingProducerContentWriter) Write(data []byte) (int, error) {
	n, err := w.ResponseRecorder.Write(data)
	w.writes++
	if w.writes == 1 {
		w.closeErr = w.service.Close()
		// Run the service's scheduled cancellation callbacks before the next read.
		synctest.Wait()
	}
	return n, err
}

func TestAuthenticatedProducerCancelledContentRequestCannotReadOrSendFile(t *testing.T) {
	root := t.TempDir()
	inbox, err := files.Open(filepath.Join(root, "incoming"))
	if err != nil {
		t.Fatal(err)
	}
	data := bytes.Repeat([]byte("owned producer input\x00\xff"), 3000)
	f, err := files.Describe(strings.Repeat("a", 32), "input.bin", data)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := inbox.Put("nPhone", f, bytes.NewReader(data)); err != nil {
		t.Fatal(err)
	}
	if err := inbox.Close(); err != nil {
		t.Fatal(err)
	}
	s, err := pc.New(&tsnet.Server{}, pc.Config{Identity: "nPc", Host: "pc.tailtest.ts.net", CodexExecutable: os.Args[0], AllowedPeers: []string{"nPhone"}, DataDir: root})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	p := &producer{service: s, config: producerConfig{Protocol: producerProtocol, Address: "127.0.0.1:32123", PCIdentity: "nPc", PeerIdentity: "nPhone", Token: strings.Repeat("c", 64)}}
	request := func(ctx context.Context) *http.Request {
		r := httptest.NewRequest("GET", "http://127.0.0.1:32123/v1/inbox/"+f.ID+"/content?nonce="+strings.Repeat("b", 32), nil).WithContext(ctx)
		r.RemoteAddr, r.URL.Scheme, r.URL.Host = "127.0.0.1:60000", "", ""
		r.Header.Set("X-Bridge-Request", producerSignature(p.config, r, false, 0, nil))
		return r
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	w := httptest.NewRecorder()
	p.ServeHTTP(w, request(ctx))
	var result protocol.FileReply
	if w.Code != 503 || protocol.DecodeStrict(w.Body.Bytes(), &result) != nil || result.State != "unavailable" || result.File != nil || result.Page != nil || w.Header().Get("Content-Type") != "application/json" {
		t.Fatal("Cancelled authenticated producer read copied bytes or bypassed request lifetime", w.Code, w.Body.Len(), w.Header())
	}
	if w.Header().Get("X-Bridge-Response") == "" || result.Nonce != strings.Repeat("b", 32) || result.PCIdentity != "nPc" || result.PeerIdentity != "nPhone" {
		t.Fatal("Cancellation produced an unrelated rejection instead of the authenticated handler result")
	}
	w = httptest.NewRecorder()
	p.ServeHTTP(w, request(context.Background()))
	if w.Code != 200 || w.Header().Get("Content-Type") != "application/octet-stream" || !bytes.Equal(w.Body.Bytes(), data) {
		t.Fatal("Cancellation removed the original file or invalidated authenticated retries", w.Code, w.Body.Len())
	}
	t.Run("service_close_during_content_copy", func(t *testing.T) {
		synctest.Test(t, func(t *testing.T) {
			w := &closingProducerContentWriter{ResponseRecorder: httptest.NewRecorder(), service: s}
			p.ServeHTTP(w, request(context.Background()))
			if w.closeErr != nil || w.Code != 200 || w.Header().Get("Content-Type") != "application/octet-stream" || w.writes != 1 || w.Body.Len() == 0 || w.Body.Len() > 32<<10 || w.Body.Len() >= len(data) || !bytes.Equal(w.Body.Bytes(), data[:w.Body.Len()]) {
				t.Fatal("Producer outlived the service after verified-open and copied the remaining file", w.closeErr, w.Code, w.writes, w.Body.Len(), len(data))
			}
			if original, err := os.ReadFile(filepath.Join(root, "incoming", "nPhone", f.ID, "content")); err != nil || !bytes.Equal(original, data) {
				t.Fatal("Shutdown during producer download damaged the original committed source", err)
			}
		})
	})
}

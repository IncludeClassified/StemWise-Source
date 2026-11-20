package pc

import (
	"bytes"
	"context"
	"io"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
	"tailscale.com/tsnet"
)

func contextFileStores(t *testing.T) (*files.Store, *files.Store, string) {
	t.Helper()
	root := t.TempDir()
	open := func(name string) *files.Store {
		store, err := files.Open(filepath.Join(root, name))
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { store.Close() })
		return store
	}
	return open("incoming"), open("outgoing"), root
}

func contextFileDescriptor(t *testing.T, data []byte) protocol.File {
	t.Helper()
	f, err := files.Describe(strings.Repeat("a", 32), "context.bin", data)
	if err != nil {
		t.Fatal(err)
	}
	return f
}

func contextUploadPath(f protocol.File) string {
	q := url.Values{"nonce": {strings.Repeat("b", 32)}, "id": {f.ID}, "name": {f.Name}, "size": {strconv.FormatInt(f.Size, 10)}, "sha256": {f.SHA256}}
	return "/v1/files/put?" + q.Encode()
}

type cancelFinalHTTPBody struct {
	*bytes.Reader
	cancel context.CancelFunc
}

func (b *cancelFinalHTTPBody) Read(p []byte) (int, error) {
	n, err := b.Reader.Read(p)
	if b.Len() == 0 {
		b.cancel()
		return n, io.EOF
	}
	return n, err
}
func (b *cancelFinalHTTPBody) Close() error { return nil }

func TestServeFilesFinalReadCancellationCannotPublishOrDisturbReceipt(t *testing.T) {
	for _, existing := range []bool{false, true} {
		t.Run(map[bool]string{false: "new", true: "existing"}[existing], func(t *testing.T) {
			inbox, outbox, root := contextFileStores(t)
			data := []byte("complete buffered bytes returned together with EOF")
			f := contextFileDescriptor(t, data)
			if existing {
				if _, err := inbox.Put("nPhone", f, bytes.NewReader(data)); err != nil {
					t.Fatal(err)
				}
			}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			body := &cancelFinalHTTPBody{Reader: bytes.NewReader(data), cancel: cancel}
			r := httptest.NewRequest("POST", contextUploadPath(f), body).WithContext(ctx)
			w := httptest.NewRecorder()
			ServeFiles(w, r, "nPhone", "nPc", inbox, outbox)
			var rejected protocol.FileReply
			if ctx.Err() != context.Canceled || protocol.DecodeStrict(w.Body.Bytes(), &rejected) != nil || w.Code != 503 || rejected.State != "unavailable" || rejected.File != nil {
				t.Fatal("Final-read cancellation became a stored receipt", w.Code, w.Body.String())
			}
			entries, err := os.ReadDir(filepath.Join(root, "incoming", "nPhone"))
			if err != nil {
				t.Fatal(err)
			}
			wantEntries := 0
			if existing {
				wantEntries = 1
				actual, content, err := inbox.Read("nPhone", f.ID)
				if err != nil || actual != f || !bytes.Equal(content, data) {
					t.Fatal("Cancelled retry disturbed the original committed receipt", actual, err)
				}
			}
			if len(entries) != wantEntries {
				t.Fatal("Cancelled upload retained staging or published fresh bytes", entries)
			}
			w = httptest.NewRecorder()
			ServeFiles(w, httptest.NewRequest("POST", contextUploadPath(f), bytes.NewReader(data)), "nPhone", "nPc", inbox, outbox)
			var stored protocol.FileReply
			if w.Code != 200 || protocol.DecodeStrict(w.Body.Bytes(), &stored) != nil || stored.State != "stored" || stored.File == nil || *stored.File != f {
				t.Fatal("Original immutable transfer could not retry after cancellation", w.Code, w.Body.String())
			}
		})
	}
}

type blockedHTTPFileBody struct {
	entered, closed chan struct{}
	enter, close    sync.Once
}

func (b *blockedHTTPFileBody) Read([]byte) (int, error) {
	b.enter.Do(func() { close(b.entered) })
	<-b.closed
	return 0, io.ErrClosedPipe
}
func (b *blockedHTTPFileBody) Close() error { b.close.Do(func() { close(b.closed) }); return nil }

func TestServiceCloseClosesBlockedFileBodyBeforeHandlerRetires(t *testing.T) {
	root := t.TempDir()
	s, err := New(&tsnet.Server{}, Config{Identity: "nPc", Host: "pc.tailtest.ts.net", CodexExecutable: os.Args[0], AllowedPeers: []string{"nPhone"}, DataDir: root})
	if err != nil {
		t.Fatal(err)
	}
	s.authenticate = func(context.Context, string) (string, error) { return "nPhone", nil }
	body := &blockedHTTPFileBody{entered: make(chan struct{}), closed: make(chan struct{})}
	f := contextFileDescriptor(t, []byte("never arrives"))
	r := httptest.NewRequest("POST", contextUploadPath(f), body)
	w := httptest.NewRecorder()
	handlerDone, closeDone := make(chan struct{}), make(chan struct{})
	closeResult := make(chan error, 1)
	closeStarted := false
	t.Cleanup(func() {
		body.Close()
		s.Close()
		checksync.Join(t, handlerDone, 3*time.Second, "cancelled file handler")
		if closeStarted {
			checksync.Join(t, closeDone, 3*time.Second, "file service close")
		}
	})
	go func() { defer close(handlerDone); s.handle(w, r) }()
	checksync.Receive(t, body.entered, 3*time.Second, "actual blocked upload read")
	closeStarted = true
	go func() { defer close(closeDone); closeResult <- s.Close() }()
	checksync.Receive(t, body.closed, 3*time.Second, "service-owned upload body closure")
	if !checksync.Join(t, handlerDone, 3*time.Second, "upload handler retirement after service close") {
		return
	}
	if err := checksync.Receive(t, closeResult, 3*time.Second, "service close result"); err != nil {
		t.Fatal(err)
	}
	var result protocol.FileReply
	if w.Code == 200 || protocol.DecodeStrict(w.Body.Bytes(), &result) != nil || result.File != nil || result.State == "stored" {
		t.Fatal("Service shutdown published a receipt for the blocked source", w.Code, w.Body.String())
	}
	for _, store := range []string{"incoming", "outgoing"} {
		if _, err := os.Stat(filepath.Join(root, store, "nPhone", f.ID)); !os.IsNotExist(err) {
			t.Fatal("Shutdown published managed transfer bytes", store, err)
		}
	}
}

type cancelFileDownloadWriter struct {
	*httptest.ResponseRecorder
	cancel context.CancelFunc
	writes int
}

func (w *cancelFileDownloadWriter) Write(p []byte) (int, error) {
	n, err := w.ResponseRecorder.Write(p)
	w.writes++
	w.cancel()
	return n, err
}

func TestServeFilesDownloadCancellationStopsAfterFirstBoundedWrite(t *testing.T) {
	inbox, outbox, _ := contextFileStores(t)
	data := bytes.Repeat([]byte("actual download bytes\x00\xff"), 5000)
	f := contextFileDescriptor(t, data)
	if _, err := outbox.Put("nPhone", f, bytes.NewReader(data)); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	w := &cancelFileDownloadWriter{ResponseRecorder: httptest.NewRecorder(), cancel: cancel}
	r := httptest.NewRequest("GET", "/v1/outbox/"+f.ID+"/content?nonce="+strings.Repeat("b", 32), nil).WithContext(ctx)
	ServeFiles(w, r, "nPhone", "nPc", inbox, outbox)
	if w.Code != 200 || ctx.Err() != context.Canceled || w.writes != 1 || w.Body.Len() == 0 || w.Body.Len() > 32<<10 || w.Body.Len() >= len(data) || !bytes.Equal(w.Body.Bytes(), data[:w.Body.Len()]) {
		t.Fatal("Cancelled HTTP download copied past its bounded checkpoint", w.Code, w.writes, w.Body.Len(), len(data))
	}
	if actual, content, err := outbox.Read("nPhone", f.ID); err != nil || actual != f || !bytes.Equal(content, data) {
		t.Fatal("Cancelled download damaged its original source", actual, err)
	}
}

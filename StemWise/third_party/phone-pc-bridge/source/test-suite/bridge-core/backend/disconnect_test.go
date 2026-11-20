package backend

import (
	"bytes"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"sync"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
)

func TestDisconnectRemovesEveryTransferPageAndPreservesOtherApp(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	registerFixture(t, call, "nBob")
	for _, store := range []*files.Store{s.inbox, s.outbox} {
		for i := 0; i < 121; i++ {
			data := []byte(fmt.Sprintf("independent-%d-%s", i, randomToken()))
			f, err := files.Describe(randomToken(), "owned.bin", data)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := store.Put("nAlice", f, bytes.NewReader(data)); err != nil {
				t.Fatal(err)
			}
		}
	}
	otherData := []byte("other-app-" + randomToken())
	other, err := files.Describe(randomToken(), "other.bin", otherData)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.inbox.Put("nBob", other, bytes.NewReader(otherData)); err != nil {
		t.Fatal(err)
	}
	if w := call("DELETE", "/v2/app", nil, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	for _, store := range []*files.Store{s.inbox, s.outbox} {
		page, err := store.List("nAlice", "", 100)
		if err != nil || len(page.Files) != 0 || page.NextCursor != "" {
			t.Fatal("Disconnected app retained transferred bytes", len(page.Files), err)
		}
	}
	if _, err := s.loadApp(s.getPeer("nAlice")); !os.IsNotExist(err) {
		t.Fatal("Disconnected app registration remains", err)
	}
	_, data, err := s.inbox.Read("nBob", other.ID)
	if err != nil || !bytes.Equal(data, otherData) {
		t.Fatal("Another app's data was removed", err)
	}
}

type pausedFileReply struct {
	*httptest.ResponseRecorder
	entered chan struct{}
	release chan struct{}
	once    sync.Once
}

func (w *pausedFileReply) Header() http.Header {
	w.once.Do(func() { close(w.entered); <-w.release })
	return w.ResponseRecorder.Header()
}

func TestDisconnectWaitsForAcceptedTransferAndRejectsLaterTransfer(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	w := &pausedFileReply{ResponseRecorder: httptest.NewRecorder(), entered: make(chan struct{}), release: make(chan struct{})}
	r := httptest.NewRequest("GET", "/v1/files/health?nonce="+randomToken(), nil)
	r.RemoteAddr = "nAlice"
	finished := make(chan struct{})
	var release sync.Once
	t.Cleanup(func() {
		release.Do(func() { close(w.release) })
		s.cancel()
		checksync.Join(t, finished, time.Second, "accepted file handler")
	})
	go func() { s.ServeHTTP(w, r); close(finished) }()
	select {
	case <-w.entered:
	case <-time.After(time.Second):
		t.Fatal("Accepted file response did not start")
	}
	done := make(chan *httptest.ResponseRecorder, 1)
	disconnectRetired := make(chan struct{})
	t.Cleanup(func() {
		release.Do(func() { close(w.release) })
		checksync.Join(t, disconnectRetired, time.Second, "app disconnect handler")
	})
	go func() { defer close(disconnectRetired); done <- call("DELETE", "/v2/app", nil, "nAlice") }()
	select {
	case <-done:
		t.Fatal("App deletion crossed an accepted file operation")
	case <-time.After(25 * time.Millisecond):
	}
	release.Do(func() { close(w.release) })
	checksync.Receive(t, finished, time.Second, "released file handler")
	select {
	case result := <-done:
		if result.Code != 200 {
			t.Fatal(result.Body.String())
		}
	case <-time.After(time.Second):
		t.Fatal("App deletion failed to resume")
	}
	if result := call("GET", "/v1/files/health?nonce="+randomToken(), nil, "nAlice"); result.Code != 409 {
		t.Fatal("Disconnected identity continued file access")
	}
}

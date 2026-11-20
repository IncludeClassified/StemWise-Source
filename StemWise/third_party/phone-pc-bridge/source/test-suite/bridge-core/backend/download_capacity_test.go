package backend

import (
	"bytes"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

type heldDownloadWriter struct {
	header  http.Header
	status  int
	data    []byte
	started chan struct{}
	release <-chan struct{}
	once    sync.Once
}

func (w *heldDownloadWriter) Header() http.Header  { return w.header }
func (w *heldDownloadWriter) WriteHeader(code int) { w.status = code }
func (w *heldDownloadWriter) Write(data []byte) (int, error) {
	if w.status == 0 {
		w.status = 200
	}
	w.data = append([]byte{}, data...)
	w.once.Do(func() { close(w.started) })
	<-w.release
	return len(data), nil
}

type disconnectedDownloadWriter struct{ header http.Header }

func (w disconnectedDownloadWriter) Header() http.Header { return w.header }
func (w disconnectedDownloadWriter) WriteHeader(int)     {}
func (w disconnectedDownloadWriter) Write([]byte) (int, error) {
	return 0, errors.New("owned_client_disconnected")
}

func TestDownloadCapacityBoundsSnapshotsAcrossRoutesAndReleasesOnWriteFailure(t *testing.T) {
	s, n, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	data := []byte("verified-owned-download-" + randomToken())
	f, _ := files.Describe(randomToken(), "owned.bin", data)
	if _, err := s.outbox.Put("nAlice", f, bytes.NewReader(data)); err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	j.State = "completed"
	j.Report = &appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "ready"}
	j.Outputs = []protocol.File{f}
	state.Jobs[j.ID] = j
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	path := "/v1/outbox/" + f.ID + "/content?nonce=" + randomToken()
	request := func(path string) *http.Request {
		r := httptest.NewRequest("GET", path, nil)
		r.RemoteAddr = "nAlice"
		return r
	}
	release := make(chan struct{})
	done := make(chan struct{}, 2)
	var releaseOnce sync.Once
	started, completed := 0, 0
	t.Cleanup(func() {
		releaseOnce.Do(func() { close(release) })
		for i := completed; i < started; i++ {
			select {
			case <-done:
			case <-time.After(3 * time.Second):
				t.Error("Owned download handler remained live")
			}
		}
	})
	for i := 0; i < 2; i++ {
		w := &heldDownloadWriter{header: make(http.Header), started: make(chan struct{}), release: release}
		started++
		go func() { defer func() { done <- struct{}{} }(); s.ServeHTTP(w, request(path)) }()
		select {
		case <-w.started:
		case <-time.After(3 * time.Second):
			t.Fatal("Owned download did not reach response write")
		}
		if w.status != 200 || !bytes.Equal(w.data, data) {
			t.Fatal("Admitted download did not verify exact bytes")
		}
	}
	w := call("GET", path, nil, "nAlice")
	var busy protocol.FileReply
	if w.Code != 503 || protocol.DecodeStrict(w.Body.Bytes(), &busy) != nil || busy.Protocol != protocol.Files || busy.PCIdentity != "nPi" || busy.PeerIdentity != "nAlice" || busy.Nonce != strings.Split(path, "nonce=")[1] || busy.State != "download_capacity_unavailable" {
		t.Fatal("Binary protocol lost bounded capacity or correlated error", w.Code)
	}
	if w := call("GET", "/v2/jobs/"+j.ID+"/outputs/"+f.ID+"/content", nil, "nAlice"); w.Code != 503 || !strings.Contains(w.Body.String(), `"code":"download_capacity_unavailable"`) || !strings.Contains(w.Body.String(), `"retryable":true`) {
		t.Fatal("HTTP app route bypassed shared download capacity", w.Code)
	}
	if w := call("GET", "/v1/outbox/"+f.ID+"/%63ontent?nonce="+randomToken(), nil, "nAlice"); w.Code != 503 {
		t.Fatal("Escaped route bypassed download capacity", w.Code)
	}
	for _, route := range []string{"/v2/connection", "/v2/jobs/" + j.ID} {
		if w := call("GET", route, nil, "nAlice"); w.Code != 200 {
			t.Fatal("Download capacity blocked independent status", route)
		}
	}
	// Finish the two real writes before checking release and a failing writer.
	releaseOnce.Do(func() { close(release) })
	for completed < started {
		select {
		case <-done:
			completed++
		case <-time.After(3 * time.Second):
			t.Fatal("Finished download retained its handler")
		}
	}
	s.ServeHTTP(disconnectedDownloadWriter{make(http.Header)}, request(path))
	for i := 0; i < 3; i++ {
		if w := call("GET", path, nil, "nAlice"); w.Code != 200 || !bytes.Equal(w.Body.Bytes(), data) {
			t.Fatal("Completed or failed write leaked download capacity", w.Code)
		}
	}
	if n.calls != 0 {
		t.Fatal("Read capacity caused native execution")
	}
}

func TestV2DownloadsReserveSharedCapacityUntilResponseFinishes(t *testing.T) {
	s, _, call := fixtureAPI(t)
	data := []byte("owned-mixed-route-" + randomToken())
	f, _ := files.Describe(randomToken(), "owned.bin", data)
	jobs := map[string]string{}
	for _, identity := range []string{"nAlice", "nBob", "nCarol"} {
		registerFixture(t, call, identity)
		state, err := s.loadApp(s.getPeer(identity))
		if err != nil {
			t.Fatal(err)
		}
		if _, err := s.outbox.Put(identity, f, bytes.NewReader(data)); err != nil {
			t.Fatal(err)
		}
		j := ownedOutputJob(randomToken())
		j.State = "completed"
		j.Report = &appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "ready"}
		j.Outputs = []protocol.File{f}
		state.Jobs[j.ID] = j
		if err := s.saveApp(state); err != nil {
			t.Fatal(err)
		}
		jobs[identity] = j.ID
	}
	release := make(chan struct{})
	done := make(chan struct{}, 2)
	started := 0
	t.Cleanup(func() {
		close(release)
		for i := 0; i < started; i++ {
			select {
			case <-done:
			case <-time.After(3 * time.Second):
				t.Error("Owned mixed-route download remained live")
			}
		}
	})
	for _, identity := range []string{"nAlice", "nBob"} {
		path := "/v2/jobs/" + jobs[identity] + "/outputs/" + f.ID + "/content"
		if identity == "nBob" {
			path = "/v1/outbox/" + f.ID + "/content?nonce=" + randomToken()
		}
		request := httptest.NewRequest("GET", path, nil)
		request.RemoteAddr = identity
		w := &heldDownloadWriter{header: make(http.Header), started: make(chan struct{}), release: release}
		started++
		go func() { defer func() { done <- struct{}{} }(); s.ServeHTTP(w, request) }()
		select {
		case <-w.started:
		case <-time.After(3 * time.Second):
			t.Fatal("Mixed-route download failed to start")
		}
		if w.status != 200 || !bytes.Equal(w.data, data) {
			t.Fatal("Mixed-route download lost exact bytes")
		}
	}
	for _, path := range []string{"/v1/outbox/" + f.ID + "/content?nonce=" + randomToken(), "/v2/jobs/" + jobs["nCarol"] + "/outputs/" + f.ID + "/content"} {
		if w := call("GET", path, nil, "nCarol"); w.Code != 503 || !strings.Contains(w.Body.String(), protocol.DownloadCapacityUnavailable) {
			t.Fatal("Mixed routes or separate app identities bypassed shared capacity", w.Code)
		}
	}
	if w := call("GET", "/v2/connection", nil, "nAlice"); w.Code != 200 {
		t.Fatal("Held V2 content blocked connection status")
	}
}

func TestSlowDownloadKeepsSameAppControlsAvailableAndPinsDisconnection(t *testing.T) {
	for _, version := range []string{"v1", "v2"} {
		t.Run(version, func(t *testing.T) {
			s, n, call := fixtureAPI(t)
			n.online = true
			s.native = &discardReceiptNative{nativeFixture: n, deleted: true}
			registerFixture(t, call, "nAlice")
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			data := []byte("owned-slow-download-" + randomToken())
			f, _ := files.Describe(randomToken(), "owned.bin", data)
			if _, err := s.outbox.Put(p.identity, f, bytes.NewReader(data)); err != nil {
				t.Fatal(err)
			}
			j := ownedOutputJob(randomToken())
			j.State = "completed"
			j.Report = &appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "ready"}
			j.Outputs = []protocol.File{f}
			state.Jobs[j.ID] = j
			if err := s.saveApp(state); err != nil {
				t.Fatal(err)
			}
			input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Queued owned cancellation", Text: "original-" + randomToken(), Files: []protocol.File{}}
			if w := call("POST", "/v2/jobs", input, p.identity); w.Code != 202 {
				t.Fatal("Owned queued fixture rejected", w.Body.String())
			}
			path := "/v2/jobs/" + j.ID + "/outputs/" + f.ID + "/content"
			if version == "v1" {
				path = "/v1/outbox/" + f.ID + "/content?nonce=" + randomToken()
			}
			failed := httptest.NewRequest("GET", path, nil)
			failed.RemoteAddr = p.identity
			s.ServeHTTP(disconnectedDownloadWriter{make(http.Header)}, failed)
			release := make(chan struct{})
			done := make(chan struct{})
			var once sync.Once
			w := &heldDownloadWriter{header: make(http.Header), started: make(chan struct{}), release: release}
			r := httptest.NewRequest("GET", path, nil)
			r.RemoteAddr = p.identity
			go func() { defer close(done); s.ServeHTTP(w, r) }()
			t.Cleanup(func() {
				once.Do(func() { close(release) })
				select {
				case <-done:
				case <-time.After(3 * time.Second):
					t.Error("Owned slow download remained live")
				}
			})
			select {
			case <-w.started:
			case <-time.After(3 * time.Second):
				t.Fatal("Download failed to reach actual write")
			}
			if w.status != 200 || !bytes.Equal(w.data, data) {
				t.Fatal("Held download did not contain verified bytes")
			}
			boundedCall := func(method, path string) *httptest.ResponseRecorder {
				t.Helper()
				result := make(chan *httptest.ResponseRecorder, 1)
				go func() { result <- call(method, path, nil, p.identity) }()
				select {
				case result := <-result:
					return result
				case <-time.After(3 * time.Second):
					once.Do(func() { close(release) })
					select {
					case <-result:
					case <-time.After(3 * time.Second):
						t.Error("Owned control remained live after releasing its download")
					}
					t.Fatal("Slow download blocked same-app control", method, path)
				}
				return nil
			}
			for _, path := range []string{"/v2/app", "/v2/jobs", "/v2/jobs/" + j.ID, "/v2/jobs/" + j.ID + "/log", "/v2/jobs/" + j.ID + "/outputs"} {
				if w := boundedCall("GET", path); w.Code != 200 {
					t.Fatal("Same-app status unavailable", path, w.Code)
				}
			}
			if w := boundedCall("POST", "/v2/jobs/"+input.RequestID+"/cancel"); w.Code != 200 || !strings.Contains(w.Body.String(), "cancelled_before_submission") {
				t.Fatal("Slow download prevented actual queued cancellation", w.Code)
			}
			if p.transfers.TryLock() {
				p.transfers.Unlock()
				t.Fatal("App disconnection can cross an in-flight download")
			}
			if w := boundedCall("DELETE", "/v2/jobs/"+j.ID); w.Code != 200 {
				t.Fatal("Slow download blocked terminal-job deletion", w.Code)
			}
			if w := boundedCall("GET", "/v2/jobs/"+j.ID); w.Code != 410 || !strings.Contains(w.Body.String(), `"code":"request_deleted"`) {
				t.Fatal("Deleted job resurrected during in-flight download", w.Code)
			}
			disconnectStarted := make(chan struct{})
			disconnected := make(chan *httptest.ResponseRecorder, 1)
			disconnectDone := false
			go func() {
				close(disconnectStarted)
				disconnected <- call("DELETE", "/v2/app", nil, p.identity)
			}()
			t.Cleanup(func() {
				once.Do(func() { close(release) })
				if !disconnectDone {
					select {
					case <-disconnected:
					case <-time.After(3 * time.Second):
						t.Error("Owned disconnection handler remained live")
					}
				}
			})
			checksync.Receive(t, disconnectStarted, 3*time.Second, "app disconnect start")
			select {
			case result := <-disconnected:
				disconnectDone = true
				t.Fatal("App disconnection crossed an in-flight download", result.Code)
			case <-time.After(100 * time.Millisecond):
			}
			if w := boundedCall("GET", "/v2/app"); w.Code != 200 {
				t.Fatal("Waiting disconnection blocked app status", w.Code)
			}
			once.Do(func() { close(release) })
			select {
			case <-done:
			case <-time.After(3 * time.Second):
				t.Fatal("Completed download retained a handler")
			}
			if !bytes.Equal(w.data, data) {
				t.Fatal("Concurrent job deletion changed verified response bytes")
			}
			select {
			case result := <-disconnected:
				disconnectDone = true
				if result.Code != 200 {
					t.Fatal("Completed download prevented app disconnection", result.Code)
				}
			case <-time.After(3 * time.Second):
				t.Fatal("Completed download retained disconnection guard")
			}
			if w := call("GET", "/v2/app", nil, p.identity); w.Code != 409 {
				t.Fatal("App disconnection left state behind", w.Code)
			}
			if n.calls != 0 {
				t.Fatal("Slow download controls caused native execution")
			}
		})
	}
}

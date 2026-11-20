package backend

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/http/httptrace"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"testing/synctest"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

type heldInputUpload struct {
	io.ReadCloser
	entered chan struct{}
	once    sync.Once
}

func (b *heldInputUpload) Read(p []byte) (int, error) {
	b.once.Do(func() { close(b.entered) })
	return b.ReadCloser.Read(p)
}

func inputUploadPath(f protocol.File) string {
	q := url.Values{"nonce": {randomToken()}, "id": {f.ID}, "name": {f.Name}, "size": {strconv.FormatInt(f.Size, 10)}, "sha256": {f.SHA256}}
	return "/v1/files/put?" + q.Encode()
}

func beginHeldInputUpload(t *testing.T, s *Service, f protocol.File) (*io.PipeWriter, *httptest.ResponseRecorder, <-chan struct{}) {
	return beginHeldInputUploadContext(t, s, f, context.Background())
}

func beginHeldInputUploadContext(t *testing.T, s *Service, f protocol.File, ctx context.Context) (*io.PipeWriter, *httptest.ResponseRecorder, <-chan struct{}) {
	t.Helper()
	reader, writer := io.Pipe()
	body := &heldInputUpload{ReadCloser: reader, entered: make(chan struct{})}
	r := httptest.NewRequest("POST", inputUploadPath(f), body)
	r = r.WithContext(ctx)
	r.RemoteAddr = "nAlice"
	r.Header.Set("Content-Type", "application/octet-stream")
	w := httptest.NewRecorder()
	done := make(chan struct{})
	t.Cleanup(func() {
		reader.Close()
		writer.Close()
		checksync.Join(t, done, 3*time.Second, "owned input upload")
	})
	go func() { defer close(done); s.ServeHTTP(w, r) }()
	checksync.Receive(t, body.entered, 3*time.Second, "actual input upload body")
	return writer, w, done
}

func assertNoInputStages(t *testing.T, s *Service) {
	t.Helper()
	entries, err := os.ReadDir(filepath.Join(s.cfg.DataDir, "incoming", "nAlice"))
	if err != nil && !os.IsNotExist(err) {
		t.Fatal(err)
	}
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), ".incoming-") {
			t.Fatal("Retired upload kept staging bytes", entry.Name())
		}
	}
}

func TestInputUploadCannotRepublishAfterLastOwnerExpiry(t *testing.T) {
	for _, scenario := range []string{"last_owner", "shared_owner", "new_cache"} {
		t.Run(scenario, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				s, _, call := fixtureAPI(t)
				registerFixture(t, call, "nAlice")
				p := s.getPeer("nAlice")
				content := []byte("actual missing input repair")
				f, _ := files.Describe(randomToken(), "context.zip", content)
				var first string
				if scenario != "new_cache" {
					if _, err := s.inbox.Put(p.identity, f, bytes.NewReader(content)); err != nil {
						t.Fatal(err)
					}
					input := appInput{RequestID: randomToken(), Title: "accepted input owner", Text: "keep exact job", CreatedAt: time.Now().UnixMilli(), Files: []protocol.File{f}}
					if w := call("POST", "/v2/jobs", input, p.identity); w.Code != 202 {
						t.Fatal(w.Body.String())
					}
					first = input.RequestID
					time.Sleep(appRetention - time.Second)
					if scenario == "shared_owner" {
						input.RequestID, input.Title = randomToken(), "later shared input owner"
						if w := call("POST", "/v2/jobs", input, p.identity); w.Code != 202 {
							t.Fatal(w.Body.String())
						}
					}
					// A supported retry can repair conclusively missing bytes under the same ID.
					if err := s.inbox.Remove(p.identity, f.ID); err != nil {
						t.Fatal(err)
					}
				}
				writer, response, done := beginHeldInputUpload(t, s, f)
				time.Sleep(2 * time.Second)
				if scenario == "last_owner" {
					synctest.Wait()
					select {
					case <-done:
					default:
						t.Fatal("Receiver deadline did not close the actual suspended request body")
					}
				}
				if _, err := s.retentionPass(time.Now()); err != nil {
					t.Fatal(err)
				}
				_, _ = writer.Write(content)
				writer.Close()
				checksync.Join(t, done, 3*time.Second, "deadline upload response")
				actual, fileErr := s.inbox.Info(p.identity, f.ID)
				if scenario == "last_owner" {
					if response.Code == 200 || !errors.Is(fileErr, files.ErrAbsent) {
						t.Fatal("Late input upload resurrected expired job bytes", response.Code, response.Body.String(), fileErr)
					}
				} else if response.Code != 200 || fileErr != nil || actual != f {
					t.Fatal("Valid generic/shared input upload was discarded", response.Code, response.Body.String(), fileErr)
				}
				if first != "" {
					state, err := s.loadApp(p)
					if err != nil || state.Jobs[first] != nil {
						t.Fatal("Upload extended expired job lifetime", err)
					}
				}
				assertNoInputStages(t, s)
			})
		})
	}
}

func TestInputUploadCancellationReleasesBodyAndBothAppsRemainResponsive(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	registerFixture(t, call, "nBob")
	f, _ := files.Describe(randomToken(), "slow.zip", []byte("actual bounded stream"))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	_, response, done := beginHeldInputUploadContext(t, s, f, ctx)
	for _, identity := range []string{"nAlice", "nBob"} {
		result := make(chan *httptest.ResponseRecorder, 1)
		go func() { result <- call("GET", "/v2/app", nil, identity) }()
		if w := checksync.Receive(t, result, 3*time.Second, "saved app during suspended upload"); w.Code != 200 {
			t.Fatal(w.Body.String())
		}
	}
	cancel()
	checksync.Join(t, done, 3*time.Second, "cancelled request body")
	if _, err := s.inbox.Info("nAlice", f.ID); response.Code == 200 || !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Cancelled upload retained a receipt", response.Code, err)
	}
	assertNoInputStages(t, s)
	if w := call("GET", "/v2/app", nil, "nAlice"); w.Code != 200 {
		t.Fatal("Cancellation leaked app state ownership", w.Body.String())
	}
}

type failedInputBody struct{}

func (failedInputBody) Read([]byte) (int, error) { return 0, io.ErrUnexpectedEOF }

func TestInputUploadFailuresReleaseFinalPublicationFence(t *testing.T) {
	for _, failure := range []string{"source_error", "content_mismatch", "descriptor_conflict"} {
		t.Run(failure, func(t *testing.T) {
			s, _, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			content := []byte("immutable current input")
			f, _ := files.Describe(randomToken(), "context.zip", content)
			if failure == "descriptor_conflict" {
				if _, err := s.inbox.Put("nAlice", f, bytes.NewReader(content)); err != nil {
					t.Fatal(err)
				}
				input := appInput{RequestID: randomToken(), Title: "same descriptor", Text: "accepted", CreatedAt: time.Now().UnixMilli(), Files: []protocol.File{f}}
				if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
					t.Fatal(w.Body.String())
				}
				if err := s.inbox.Remove("nAlice", f.ID); err != nil {
					t.Fatal(err)
				}
				f.Name = "changed.zip"
			}
			var body io.Reader = failedInputBody{}
			if failure != "source_error" {
				body = strings.NewReader(strings.Repeat("x", len(content)))
			}
			r := httptest.NewRequest("POST", inputUploadPath(f), body)
			r.RemoteAddr = "nAlice"
			w := httptest.NewRecorder()
			s.ServeHTTP(w, r)
			if w.Code == 200 || failure == "descriptor_conflict" && w.Code != 409 {
				t.Fatal("Invalid upload was acknowledged", w.Code, w.Body.String())
			}
			result := make(chan *httptest.ResponseRecorder, 1)
			go func() { result <- call("GET", "/v2/app", nil, "nAlice") }()
			if response := checksync.Receive(t, result, 3*time.Second, "saved app after failed upload"); response.Code != 200 {
				t.Fatal(response.Body.String())
			}
			assertNoInputStages(t, s)
		})
	}
}

type interruptedInputBody struct{ sent bool }

func (b *interruptedInputBody) Read(p []byte) (int, error) {
	if b.sent {
		os.Exit(41) // The actual Store copy already wrote the first chunk.
	}
	b.sent = true
	return copy(p, []byte("partial owned input")), nil
}

func TestInputUploadProcessDeathHelper(t *testing.T) {
	if os.Getenv("BRIDGE_INPUT_UPLOAD_CRASH") != "partial-body" {
		t.Skip("Owned subprocess fixture only")
	}
	var cfg Config
	var f protocol.File
	if json.Unmarshal([]byte(os.Getenv("BRIDGE_INPUT_UPLOAD_CONFIG")), &cfg) != nil || json.Unmarshal([]byte(os.Getenv("BRIDGE_INPUT_UPLOAD_FILE")), &f) != nil {
		t.Fatal("Missing exact fixture configuration")
	}
	native := &nativeFixture{outputs: map[string][]byte{}}
	s, err := New(cfg, native, func(_ context.Context, identity string) (string, error) { return identity, nil }, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	r := httptest.NewRequest("POST", inputUploadPath(f), &interruptedInputBody{})
	r.RemoteAddr = "nAlice"
	s.ServeHTTP(httptest.NewRecorder(), r)
	t.Fatal("Actual partial upload checkpoint was not reached")
}

func TestInterruptedInputUploadIsRemovedOnRestartWithoutLosingAcceptedOwners(t *testing.T) {
	s, native, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	content := []byte("partial owned input followed by the rest of the immutable source")
	f, _ := files.Describe(randomToken(), "context.zip", content)
	if _, err := s.inbox.Put("nAlice", f, bytes.NewReader(content)); err != nil {
		t.Fatal(err)
	}
	input := appInput{RequestID: randomToken(), Title: "survive interrupted repair", Text: "accepted original job", CreatedAt: time.Now().UnixMilli(), Files: []protocol.File{f}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	if err := s.inbox.Remove("nAlice", f.ID); err != nil {
		t.Fatal(err)
	}
	s.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestInputUploadProcessDeathHelper$", "-test.count=1")
	cmd.Env = append(os.Environ(), "BRIDGE_INPUT_UPLOAD_CRASH=partial-body", "BRIDGE_INPUT_UPLOAD_CONFIG="+string(mustJSON(s.cfg)), "BRIDGE_INPUT_UPLOAD_FILE="+string(mustJSON(f)))
	output, err := cmd.CombinedOutput()
	var exit *exec.ExitError
	if !errors.As(err, &exit) || exit.ExitCode() != 41 || ctx.Err() != nil {
		t.Fatal("Actual partial-copy process-death boundary was not reached", err, string(output))
	}
	entries, err := os.ReadDir(filepath.Join(s.cfg.DataDir, "incoming", "nAlice"))
	if err != nil || len(entries) != 1 || !strings.HasPrefix(entries[0].Name(), ".incoming-") {
		t.Fatal("Child did not leave exactly one actual partial stage", err, entries)
	}
	partial, err := os.ReadFile(filepath.Join(s.cfg.DataDir, "incoming", "nAlice", entries[0].Name(), "content"))
	if err != nil || string(partial) != "partial owned input" {
		t.Fatal("Child did not write the actual first source chunk", err, string(partial))
	}
	reopened, err := New(s.cfg, native, s.authenticate, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	if _, err := reopened.retentionPass(time.Now()); err != nil {
		t.Fatal(err)
	}
	assertNoInputStages(t, reopened)
	loaded, err := reopened.loadApp(reopened.getPeer("nAlice"))
	if err != nil || loaded.Jobs[input.RequestID] == nil || loaded.Jobs[input.RequestID].Fingerprint != state.Jobs[input.RequestID].Fingerprint {
		t.Fatal("Restart discarded accepted work instead of only its abandoned stage", err)
	}
	if _, err := reopened.retentionPass(time.UnixMilli(loaded.Jobs[input.RequestID].Created).Add(appRetention)); err != nil {
		t.Fatal(err)
	}
	if _, err := reopened.inbox.Info("nAlice", f.ID); !errors.Is(err, files.ErrAbsent) || native.calls != 0 {
		t.Fatal("Restart replayed work or resurrected an expired input", err, native.calls)
	}
}

func TestInputUploadFinalFenceRejectsRemovedOwnerBeforeDeadline(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	content := []byte("repair that must not resurrect explicitly removed work")
	f, _ := files.Describe(randomToken(), "context.zip", content)
	if _, err := s.inbox.Put("nAlice", f, bytes.NewReader(content)); err != nil {
		t.Fatal(err)
	}
	input := appInput{RequestID: randomToken(), Title: "owned input", Text: "accepted", CreatedAt: time.Now().UnixMilli(), Files: []protocol.File{f}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	if err := s.inbox.Remove("nAlice", f.ID); err != nil {
		t.Fatal(err)
	}
	writer, response, done := beginHeldInputUpload(t, s, f)
	if w := call("POST", "/v2/jobs/"+input.RequestID+"/cancel", nil, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	if w := call("DELETE", "/v2/jobs/"+input.RequestID, nil, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	_, _ = writer.Write(content)
	writer.Close()
	checksync.Join(t, done, 3*time.Second, "removed owner upload response")
	if _, err := s.inbox.Info("nAlice", f.ID); response.Code == 200 || !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Upload publication ignored exact owner deletion", response.Code, response.Body.String(), err)
	}
	assertNoInputStages(t, s)
}

func TestInputUploadDueBeforeStoreEntryCannotBecomeGenericCache(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	content := []byte("already due input")
	f, _ := files.Describe(randomToken(), "context.zip", content)
	if _, err := s.inbox.Put("nAlice", f, bytes.NewReader(content)); err != nil {
		t.Fatal(err)
	}
	input := appInput{RequestID: randomToken(), Title: "already accepted", Text: "original", CreatedAt: time.Now().UnixMilli(), Files: []protocol.File{f}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	state.Jobs[input.RequestID].Created = time.Now().Add(-appRetention - time.Second).UnixMilli()
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	r := httptest.NewRequest("POST", inputUploadPath(f), bytes.NewReader(content))
	r.RemoteAddr = "nAlice"
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	if _, err := s.inbox.Info("nAlice", f.ID); w.Code == 200 || !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Initial request pruning forgot expired input ownership", w.Code, w.Body.String(), err)
	}
	assertNoInputStages(t, s)
}

func TestInputUploadDeadlineRetiresActualBlockedHTTPBody(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	content := []byte("HTTP body never sends these expected bytes")
	f, _ := files.Describe(randomToken(), "socket.zip", content)
	if _, err := s.inbox.Put("nAlice", f, bytes.NewReader(content)); err != nil {
		t.Fatal(err)
	}
	input := appInput{RequestID: randomToken(), Title: "network deadline", Text: "accepted", CreatedAt: time.Now().UnixMilli(), Files: []protocol.File{f}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	state.Jobs[input.RequestID].Created = time.Now().Add(-appRetention + 2*time.Second).UnixMilli()
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	if err := s.inbox.Remove("nAlice", f.ID); err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	bodyEntered := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer close(done)
		r.RemoteAddr = "nAlice" // Isolate authentication; retain the real net/http body/socket.
		r.Body = &heldInputUpload{ReadCloser: r.Body, entered: bodyEntered}
		s.ServeHTTP(w, r)
	}))
	defer server.Close()
	connection, err := net.DialTimeout("tcp", server.Listener.Addr().String(), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	if _, err := fmt.Fprintf(connection, "POST %s HTTP/1.1\r\nHost: owned-fixture\r\nContent-Length: %d\r\nContent-Type: application/octet-stream\r\n\r\n", inputUploadPath(f), f.Size); err != nil {
		t.Fatal(err)
	}
	checksync.Receive(t, bodyEntered, 3*time.Second, "actual server request body read")
	select {
	case <-done:
	case <-time.After(4 * time.Second):
		connection.Close()
		checksync.Join(t, done, 3*time.Second, "real HTTP upload after connection release")
		t.Fatal("Owner deadline left the actual HTTP body read/Close blocked")
	}
	assertNoInputStages(t, s)
	if _, err := s.inbox.Info("nAlice", f.ID); !errors.Is(err, files.ErrAbsent) {
		t.Fatal("Timed out HTTP input was published", err)
	}
}

func TestInputUploadRetiredCancellationCannotPoisonReusedHTTPConnection(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.RemoteAddr = "nAlice"
		s.ServeHTTP(w, r)
	}))
	defer server.Close()
	transport := &http.Transport{MaxIdleConnsPerHost: 1}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 3 * time.Second}
	content := []byte("one complete generic upload")
	f, _ := files.Describe(randomToken(), "context.zip", content)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r, _ := http.NewRequestWithContext(ctx, "POST", server.URL+inputUploadPath(f), bytes.NewReader(content))
	response, err := client.Do(r)
	if err != nil {
		t.Fatal(err)
	}
	_, readErr := io.Copy(io.Discard, response.Body)
	response.Body.Close()
	if readErr != nil || response.StatusCode != 200 {
		t.Fatal("Initial actual HTTP upload failed", response.StatusCode, readErr)
	}
	cancel()
	reused := false
	trace := &httptrace.ClientTrace{GotConn: func(info httptrace.GotConnInfo) { reused = info.Reused }}
	r, _ = http.NewRequestWithContext(httptrace.WithClientTrace(context.Background(), trace), "GET", server.URL+"/v2/app", nil)
	response, err = client.Do(r)
	if err != nil {
		t.Fatal("Retired upload poisoned the following HTTP request", err)
	}
	defer response.Body.Close()
	if response.StatusCode != 200 || !reused {
		t.Fatal("Following request failed or escaped through a fresh connection", response.StatusCode, reused)
	}
}

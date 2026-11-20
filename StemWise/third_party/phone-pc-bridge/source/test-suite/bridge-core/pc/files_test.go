package pc

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
	"tailscale.com/tsnet"
)

func TestFileRecipientBoundary(t *testing.T) {
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	cfg := Config{Identity: "nPc", Host: "pc.tailtest.ts.net", CodexExecutable: executable, AllowedPeers: []string{"nAlice", "nBob"}, DataDir: root}
	s, err := New(&tsnet.Server{}, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	// Native authentication is adapted; HTTP, scoped storage and hashing are real.
	s.authenticate = func(context.Context, string) (string, error) { return "nAlice", nil }
	server := httptest.NewServer(http.HandlerFunc(s.handle))
	defer server.Close()
	client := &http.Client{Timeout: 3 * time.Second}
	request := func(method, path string, body []byte, expected int) []byte {
		t.Helper()
		r, err := http.NewRequest(method, server.URL+path, bytes.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		response, err := client.Do(r)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		data, err := io.ReadAll(response.Body)
		if err != nil || response.StatusCode != expected || response.Header.Get("Cache-Control") != "no-store" {
			t.Fatalf("File request %s: %d %s %v", path, response.StatusCode, data, err)
		}
		return data
	}
	content := []byte("file bytes\x00\xff مرحبا 😀")
	id, err := files.NewID()
	if err != nil {
		t.Fatal(err)
	}
	info, err := files.Describe(id, "actual.txt", content)
	if err != nil {
		t.Fatal(err)
	}
	upload := func(f protocol.File, body []byte, code int) []byte {
		query := url.Values{"nonce": {randomToken()}, "id": {f.ID}, "name": {f.Name}, "size": {strconv.FormatInt(f.Size, 10)}, "sha256": {f.SHA256}}
		return request("POST", "/v1/files/put?"+query.Encode(), body, code)
	}
	data := upload(info, content, 200)
	var receipt protocol.FileReply
	if json.Unmarshal(data, &receipt) != nil || receipt.State != "stored" || receipt.File == nil || *receipt.File != info || receipt.PeerIdentity != "nAlice" {
		t.Fatal("False recipient receipt")
	}
	actual, err := os.ReadFile(filepath.Join(root, "incoming", "nAlice", id, "content"))
	if err != nil || !bytes.Equal(actual, content) {
		t.Fatal("Independent recipient bytes differ", err)
	}
	upload(info, content, 200)
	rejected := upload(info, []byte("fabricated retry"), 400)
	if json.Unmarshal(rejected, &receipt) != nil || receipt.State != "content_mismatch" {
		t.Fatal("Fabricated retry lost its explicit mismatch outcome")
	}
	changed := info
	changed.Name = "different.txt"
	upload(changed, content, 409)
	badID, _ := files.NewID()
	bad := info
	bad.ID = badID
	upload(bad, []byte("truncated"), 400)
	if _, err := os.Stat(filepath.Join(root, "incoming", "nAlice", badID)); !os.IsNotExist(err) {
		t.Fatal("Incomplete bytes became visible")
	}
	entries, err := os.ReadDir(filepath.Join(root, "incoming", "nAlice"))
	if err != nil || len(entries) != 1 {
		t.Fatal("Failed transfer staging was not cleaned")
	}
	bad.ID = "../../escape"
	upload(bad, content, 400)
	bad = info
	bad.Name = "../escape"
	upload(bad, content, 400)
	emptyID, _ := files.NewID()
	empty, err := files.Describe(emptyID, "empty.txt", nil)
	if err != nil {
		t.Fatal(err)
	}
	upload(empty, nil, 200)
	if actual, err := os.ReadFile(filepath.Join(root, "incoming", "nAlice", emptyID, "content")); err != nil || len(actual) != 0 {
		t.Fatal("Empty file was not received", err)
	}
	// The outgoing file is queued only for Bob; Alice cannot list/read his objects.
	queuedID, _ := files.NewID()
	queued, err := s.QueueFile(context.Background(), "nBob", queuedID, "out.bin", content)
	if err != nil {
		t.Fatal(err)
	}
	if queued.ID != queuedID {
		t.Fatal("Source ID changed")
	}
	data = request("GET", "/v1/outbox?nonce="+randomToken()+"&cursor=&limit=10", nil, 200)
	if json.Unmarshal(data, &receipt) != nil || receipt.Page == nil || len(receipt.Page.Files) != 0 {
		t.Fatal("Another device's source files exposed")
	}
	request("GET", "/v1/outbox/"+queuedID+"/content?nonce="+randomToken(), nil, 404)
	// Restart only the test adapter, not any actual device or Codex runtime.
	s.Close()
	s, err = New(&tsnet.Server{}, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	f, received, err := s.ReadReceivedFile(context.Background(), "nAlice", id)
	if err != nil || f != info || !bytes.Equal(received, content) {
		t.Fatal("Committed recipient did not survive reopen", err)
	}
	server.Close()
	s.authenticate = func(context.Context, string) (string, error) { return "nAlice", nil }
	server = httptest.NewServer(http.HandlerFunc(s.handle))
	defer server.Close()
	if err := os.WriteFile(filepath.Join(root, "incoming", "nAlice", id, "content"), []byte("forged"), 0600); err != nil {
		t.Fatal(err)
	}
	request("GET", "/v1/files/"+id+"/receipt?nonce="+randomToken(), nil, 503)
	if _, _, err := s.ReadReceivedFile(context.Background(), "nAlice", id); err == nil {
		t.Fatal("Metadata alone passed recipient verification")
	}
	if _, err := s.QueueFile(context.Background(), "unpaired", queuedID, "out.bin", content); err == nil {
		t.Fatal("Unpaired host destination accepted")
	}
}

type fileDeadlineWriter struct {
	*httptest.ResponseRecorder
	readDeadline, writeDeadline time.Time
}

type commitBlockedReader struct {
	io.Reader
	block func() error
}

func (r *commitBlockedReader) Read(p []byte) (int, error) {
	if r.block != nil {
		block := r.block
		r.block = nil
		if err := block(); err != nil {
			return 0, err
		}
	}
	return r.Reader.Read(p)
}

func TestFailedFileCommitReturnsStorageFailureAndRecoversSameID(t *testing.T) {
	root := t.TempDir()
	inbox, err := files.Open(filepath.Join(root, "incoming"))
	if err != nil {
		t.Fatal(err)
	}
	defer inbox.Close()
	outbox, err := files.Open(filepath.Join(root, "outgoing"))
	if err != nil {
		t.Fatal(err)
	}
	defer outbox.Close()
	id, _ := files.NewID()
	data := []byte("original-" + id)
	f, _ := files.Describe(id, "original.bin", data)
	path := filepath.Join(root, "incoming", "nPhone", id)
	blocker := filepath.Join(path, "content")
	for attempt := 0; attempt < 3; attempt++ {
		nonce := randomToken()
		query := url.Values{"nonce": {nonce}, "id": {id}, "name": {f.Name}, "size": {strconv.FormatInt(f.Size, 10)}, "sha256": {f.SHA256}}
		source := &commitBlockedReader{Reader: bytes.NewReader(data)}
		if attempt == 0 {
			source.block = func() error {
				if err := os.Mkdir(path, 0700); err != nil {
					return err
				}
				return os.WriteFile(blocker, []byte("preserve incomplete receipt"), 0600)
			}
		}
		w := httptest.NewRecorder()
		ServeFiles(w, httptest.NewRequest("POST", "/v1/files/put?"+query.Encode(), source), "nPhone", "nPi", inbox, outbox)
		var reply protocol.FileReply
		if protocol.DecodeStrict(w.Body.Bytes(), &reply) != nil || reply.Protocol != protocol.Files || reply.PCIdentity != "nPi" || reply.PeerIdentity != "nPhone" || reply.Nonce != nonce || reply.Page != nil {
			t.Fatal("Commit response lost its correlation", w.Code)
		}
		if attempt < 2 {
			if w.Code != 503 || reply.State != "unavailable" || reply.File != nil {
				t.Fatal("Incomplete destination became a conflict, missing object or stored receipt", w.Code, reply.State)
			}
			if actual, err := os.ReadFile(blocker); err != nil || string(actual) != "preserve incomplete receipt" {
				t.Fatal("Failed HTTP upload changed existing destination", err)
			}
			if attempt == 1 {
				if err := os.Remove(blocker); err != nil {
					t.Fatal(err)
				}
				if err := os.Remove(path); err != nil {
					t.Fatal(err)
				}
			}
		} else {
			if w.Code != 200 || reply.State != "stored" || reply.File == nil || *reply.File != f {
				t.Fatal("Repaired storage rejected the original HTTP transfer", w.Code, reply.State)
			}
			if actual, err := os.ReadFile(blocker); err != nil || !bytes.Equal(actual, data) {
				t.Fatal("Stored receipt lacks the original bytes", err)
			}
		}
	}
}

func (w *fileDeadlineWriter) SetReadDeadline(value time.Time) error {
	w.readDeadline = value
	return nil
}
func (w *fileDeadlineWriter) SetWriteDeadline(value time.Time) error {
	w.writeDeadline = value
	return nil
}

func TestFileHTTPDeadlinesMatchAdvertisedTransferWindow(t *testing.T) {
	root := t.TempDir()
	inbox, err := files.Open(filepath.Join(root, "incoming"))
	if err != nil {
		t.Fatal(err)
	}
	defer inbox.Close()
	outbox, err := files.Open(filepath.Join(root, "outgoing"))
	if err != nil {
		t.Fatal(err)
	}
	defer outbox.Close()
	id, _ := files.NewID()
	data := []byte("deadline-owned-" + id)
	f, _ := files.Describe(id, "owned.bin", data)
	if _, err := outbox.Put("nPhone", f, bytes.NewReader(data)); err != nil {
		t.Fatal(err)
	}
	query := url.Values{"nonce": {randomToken()}, "id": {f.ID}, "name": {f.Name}, "size": {strconv.FormatInt(f.Size, 10)}, "sha256": {f.SHA256}}
	for _, upload := range []bool{false, true} {
		w := &fileDeadlineWriter{ResponseRecorder: httptest.NewRecorder()}
		r := httptest.NewRequest("GET", "/v1/outbox/"+id+"/content?nonce="+randomToken(), nil)
		if upload {
			r = httptest.NewRequest("POST", "/v1/files/put?"+query.Encode(), bytes.NewReader(data))
		}
		before := time.Now()
		ServeFiles(w, r, "nPhone", "nPc", inbox, outbox)
		after := time.Now()
		if w.Code != 200 {
			t.Fatal("Real file request failed", w.Code)
		}
		deadlines := []time.Time{w.writeDeadline}
		if upload {
			deadlines = append(deadlines, w.readDeadline)
		}
		for _, actual := range deadlines {
			if actual.Before(before.Add(protocol.FileTransferTimeout)) || actual.After(after.Add(protocol.FileTransferTimeout)) {
				t.Fatal("Actual file I/O deadline differs from the advertised timeout", upload, actual)
			}
		}
	}
}

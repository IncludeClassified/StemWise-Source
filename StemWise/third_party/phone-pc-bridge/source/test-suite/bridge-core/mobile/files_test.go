package mobile

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func TestEmbeddedFileRoundTrip(t *testing.T) {
	root := t.TempDir()
	open := func(name string) *files.Store {
		t.Helper()
		s, err := files.Open(filepath.Join(root, name))
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { s.Close() })
		return s
	}
	pcIncoming, pcOutgoing := open("pc-incoming"), open("pc-outgoing")
	phoneIncoming, phoneOutgoing := open("phone-incoming"), open("phone-outgoing")
	var mu sync.Mutex
	drop, wrongNonce, wrongPeer, corrupt := false, false, false, false
	posts := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		reply := protocol.FileReply{Protocol: protocol.Files, PCIdentity: "nPc", PeerIdentity: "nPhone", Nonce: r.URL.Query().Get("nonce"), State: "ready"}
		if wrongNonce {
			reply.Nonce = strings.Repeat("a", 32)
		}
		if wrongPeer {
			reply.PeerIdentity = "other"
		}
		switch {
		case r.URL.Path == "/v1/files/health":
		case r.URL.Path == "/v1/files/put":
			posts++
			q := r.URL.Query()
			size, _ := strconv.ParseInt(q.Get("size"), 10, 64)
			info := protocol.File{ID: q.Get("id"), Name: q.Get("name"), Size: size, SHA256: q.Get("sha256")}
			// Independently observe the persisted SDK source before accepting bytes.
			if _, err := os.Stat(filepath.Join(root, "phone-outgoing", "nPc", info.ID, "content")); err != nil {
				t.Error("Source was not persisted before network input")
			}
			actual, err := pcIncoming.Put("nPhone", info, r.Body)
			if err != nil {
				w.WriteHeader(503)
				return
			}
			reply.File = &actual
			reply.State = "stored"
			if drop {
				conn, _, err := w.(http.Hijacker).Hijack()
				if err != nil {
					t.Error(err)
					return
				}
				conn.Close()
				return
			}
		case strings.HasSuffix(r.URL.Path, "/receipt"):
			id := strings.Split(r.URL.Path, "/")[3]
			info, err := pcIncoming.Info("nPhone", id)
			if err != nil {
				w.WriteHeader(503)
				return
			}
			reply.File = &info
			reply.State = "stored"
		case r.URL.Path == "/v1/outbox":
			page, err := pcOutgoing.List("nPhone", "", 10)
			if err != nil {
				t.Error(err)
				return
			}
			reply.Page = &page
			reply.State = "available"
		case strings.HasSuffix(r.URL.Path, "/info"):
			id := strings.Split(r.URL.Path, "/")[3]
			info, err := pcOutgoing.Info("nPhone", id)
			if err != nil {
				t.Error(err)
				return
			}
			reply.File = &info
			reply.State = "available"
		case strings.HasSuffix(r.URL.Path, "/content"):
			id := strings.Split(r.URL.Path, "/")[3]
			_, data, err := pcOutgoing.Read("nPhone", id)
			if err != nil {
				t.Error(err)
				return
			}
			if corrupt {
				data = []byte("different bytes")
			}
			w.Write(data)
			return
		default:
			w.WriteHeader(404)
			return
		}
		json.NewEncoder(w).Encode(reply)
	}))
	defer server.Close()
	makeClient := func() *FileClient {
		client := &FileClient{client: &Client{}, identity: "nPc", inbox: phoneIncoming, outbox: phoneOutgoing}
		attachFileHTTP(t, client, server, "nPhone")
		return client
	}
	f := makeClient()
	if _, err := new(FileClient).Refresh("uninitialized"); err == nil {
		t.Fatal("Uninitialized JNI client accepted")
	}
	if _, err := f.Refresh("ready"); err != nil {
		t.Fatal(err)
	}
	content := []byte("actual bytes\x00\xff مرحبا 😀")
	prepared, err := f.Prepare("fixture-prepare", "from-phone.txt", content)
	if err != nil {
		t.Fatal(err)
	}
	var info protocol.File
	if json.Unmarshal([]byte(prepared), &info) != nil {
		t.Fatal("Malformed prepared descriptor")
	}
	mu.Lock()
	drop = true
	mu.Unlock()
	if _, err := f.Send("lost-reply", info.ID); err == nil {
		t.Fatal("Lost reply reported delivery")
	}
	if actual, err := os.ReadFile(filepath.Join(root, "pc-incoming", "nPhone", info.ID, "content")); err != nil || !bytes.Equal(actual, content) {
		t.Fatal("Actual PC bytes differ", err)
	}
	mu.Lock()
	count := posts
	mu.Unlock()
	if count != 1 {
		t.Fatal("SDK automatically replayed unknown upload")
	}
	f = makeClient() // Reuse persisted store state as an app restart would.
	if pending, err := f.Pending("fixture-pending", "", 10); err != nil || !strings.Contains(pending, info.ID) {
		t.Fatal("Prepared source lost on reopen", err)
	}
	if _, err := f.Receipt("receipt", info.ID); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	wrongNonce = true
	mu.Unlock()
	if _, err := f.Receipt("stale", info.ID); err == nil {
		t.Fatal("Stale file receipt accepted")
	}
	mu.Lock()
	wrongNonce = false
	wrongPeer = true
	mu.Unlock()
	if _, err := f.Receipt("wrong-peer", info.ID); err == nil {
		t.Fatal("Different recipient accepted")
	}
	mu.Lock()
	wrongPeer = false
	mu.Unlock()
	id, _ := files.NewID()
	source, err := files.Describe(id, "from-pc.bin", content)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := pcOutgoing.Put("nPhone", source, bytes.NewReader(content)); err != nil {
		t.Fatal(err)
	}
	if available, err := f.Available("available", "", 10); err != nil || !strings.Contains(available, `"state":"available"`) {
		t.Fatal("Source availability not represented honestly", err)
	}
	stream := f.stream
	downloads := 0
	f.stream = func(ctx context.Context, request, method, path string, body io.Reader) (int, io.ReadCloser, string, error) {
		if strings.Contains(path, "/content?") {
			downloads++
		}
		return stream(ctx, request, method, path, body)
	}
	for _, expected := range []protocol.File{{ID: id, Name: "wrong.bin", Size: source.Size, SHA256: source.SHA256}, {ID: id, Name: source.Name, Size: source.Size + 1, SHA256: source.SHA256}, {ID: id, Name: source.Name, Size: source.Size, SHA256: strings.Repeat("0", 64)}} {
		if _, err := f.ReceiveMatching("descriptor-mismatch", expected.ID, expected.Name, expected.Size, expected.SHA256); err == nil {
			t.Fatal("Mismatched job descriptor accepted")
		}
	}
	if downloads != 0 {
		t.Fatal("Mismatched descriptor downloaded unrelated bytes")
	}
	mu.Lock()
	corrupt = true
	mu.Unlock()
	if _, err := f.Receive("corrupt-download", id); err == nil {
		t.Fatal("Different downloaded bytes accepted")
	}
	if _, err := os.Stat(filepath.Join(root, "phone-incoming", "nPc", id)); !os.IsNotExist(err) {
		t.Fatal("Corrupt download became a received file")
	}
	mu.Lock()
	corrupt = false
	mu.Unlock()
	if _, err := f.ReceiveMatching("receive-matching", source.ID, source.Name, source.Size, source.SHA256); err != nil {
		t.Fatal("Exact report descriptor not received", err)
	}
	if received, err := f.Receive("receive", id); err != nil || !strings.Contains(received, `"state":"received_local_verified"`) {
		t.Fatal("Recipient verification failed", err)
	}
	if actual, err := os.ReadFile(filepath.Join(root, "phone-incoming", "nPc", id, "content")); err != nil || !bytes.Equal(actual, content) {
		t.Fatal("Independent phone-recipient bytes differ", err)
	}
	if _, err := f.Receive("receive-again", id); err != nil {
		t.Fatal("Identical receive retry was not idempotent", err)
	}
	if info, err := f.ReceivedInfo("fixture-receivedinfo", id); err != nil || !strings.Contains(info, `"pcIdentity":"nPc"`) || !strings.Contains(info, `"state":"received_local_verified"`) {
		t.Fatal("Cached receipt lost verified identity", err)
	}
	if err := os.WriteFile(filepath.Join(root, "phone-incoming", "nPc", id, "content"), []byte("fabricated"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := f.ReadReceived("fixture-readreceived", id); err == nil {
		t.Fatal("Receiver metadata substituted for actual bytes")
	}
	if _, err := f.ReceivedInfo("fixture-receivedinfo", id); err == nil {
		t.Fatal("Cached descriptor accepted corrupt bytes")
	}
	if _, err := f.Prepare("fixture-prepare", "../escape", content); err == nil {
		t.Fatal("Path-like name accepted")
	}
}

func TestLocalFilesWithoutTransport(t *testing.T) {
	c, err := NewClient(t.TempDir(), "offline-files", "")
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	f, err := c.LocalFiles("offline.ts.net", "nPc")
	if err != nil {
		t.Fatal(err)
	}
	if c.started {
		t.Fatal("Local files started transport")
	}
	prepared, err := f.Prepare("fixture-prepare", "offline.bin", []byte("actual offline bytes"))
	if err != nil {
		t.Fatal(err)
	}
	var info protocol.File
	if err = json.Unmarshal([]byte(prepared), &info); err != nil {
		t.Fatal(err)
	}
	if pending, err := f.Pending("fixture-pending", "", 1); err != nil || !strings.Contains(pending, info.ID) {
		t.Fatal("Offline preparation unavailable", err)
	}
	if _, err = c.OpenFiles("offline-check", "offline.ts.net", "nPc"); err == nil {
		t.Fatal("OpenFiles skipped fresh remote health")
	}
	changed, err := c.LocalFiles("changed.ts.net", "nPc")
	if err != nil || changed.host != "changed.ts.net" || f.host != "offline.ts.net" {
		t.Fatal("Hostname edit was rejected or changed an existing profile", err)
	}
	if pending, err := changed.Pending("changed-profile-pending", "", 1); err != nil || !strings.Contains(pending, info.ID) {
		t.Fatal("Hostname edit lost the identity-scoped prepared source", pending, err)
	}
	other, err := c.LocalFiles("other.ts.net", "nOther")
	if err != nil {
		t.Fatal(err)
	}
	if pending, err := other.Pending("fixture-pending", "", 1); err != nil || strings.Contains(pending, info.ID) {
		t.Fatal("Other PC saw prepared source", err)
	}
	if _, err = c.LocalFiles("https://offline.ts.net", "nPc"); err == nil {
		t.Fatal("Invalid host accepted")
	}
	if err = c.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err = c.LocalFiles("offline.ts.net", "nPc"); err == nil {
		t.Fatal("Closed client opened files")
	}
	if err = f.DiscardPrepared(info.ID); err == nil || !strings.Contains(err.Error(), "transport closed") {
		t.Fatal("Held file client discarded source after Close", err)
	}
	if err = f.RemoveReceived(info.ID); err == nil || !strings.Contains(err.Error(), "transport closed") {
		t.Fatal("Held file client removed received object after Close", err)
	}
}

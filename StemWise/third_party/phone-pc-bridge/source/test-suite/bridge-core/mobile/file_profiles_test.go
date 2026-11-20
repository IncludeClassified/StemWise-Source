package mobile

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

func TestFileRequestsKeepImmutableProfilesAcrossHostnameEdits(t *testing.T) {
	const oldHost, newHost = "pi.tailtest.ts.net", "edited.tailtest.ts.net"
	var currentHost atomic.Value
	currentHost.Store(oldHost)
	c, _ := streamReadinessClient(t, func(*http.Request, int) (string, error) {
		return strings.Replace(streamReadyStatus, oldHost, currentHost.Load().(string), 1), nil
	})
	receiver, err := files.Open(filepath.Join(t.TempDir(), "receiver"))
	if err != nil {
		t.Fatal(err)
	}
	defer receiver.Close()
	type observed struct{ method, host, path string }
	requests := make(chan observed, 8)
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case requests <- observed{r.Method, r.Host, r.URL.Path}:
		default:
			t.Error("Unexpected repeated file requests")
			w.WriteHeader(500)
			return
		}
		reply := protocol.FileReply{Protocol: protocol.Files, PCIdentity: "nPi", PeerIdentity: "nApp", Nonce: r.URL.Query().Get("nonce"), State: "ready"}
		switch r.URL.Path {
		case "/v1/files/health":
		case "/v1/files/put":
			q := r.URL.Query()
			size, err := strconv.ParseInt(q.Get("size"), 10, 64)
			if err != nil {
				t.Error(err)
				w.WriteHeader(400)
				return
			}
			descriptor := protocol.File{ID: q.Get("id"), Name: q.Get("name"), Size: size, SHA256: q.Get("sha256")}
			stored, err := receiver.PutContext(r.Context(), "nApp", descriptor, r.Body)
			if err != nil {
				t.Error(err)
				w.WriteHeader(503)
				return
			}
			reply.State, reply.File = "stored", &stored
		default:
			t.Errorf("Unexpected file request %s", r.URL.Path)
			w.WriteHeader(404)
			return
		}
		if err := json.NewEncoder(w).Encode(reply); err != nil {
			t.Error(err)
		}
	}))
	server.Config.ReadTimeout, server.Config.WriteTimeout = 3*time.Second, 3*time.Second
	server.Start()
	defer server.Close()
	var dials atomic.Int32
	c.dial = func(ctx context.Context, network, address string) (net.Conn, error) {
		dials.Add(1)
		if network != "tcp" || address != "100.64.0.8:8781" {
			return nil, fmt.Errorf("Unpaired target %s %s", network, address)
		}
		return (&net.Dialer{Timeout: 3 * time.Second}).DialContext(ctx, network, server.Listener.Addr().String())
	}
	old, err := c.OpenAppFiles("old-profile-open", oldHost, "nPi")
	if err != nil {
		t.Fatal(err)
	}
	incoming, outgoing, lease, worker := c.fileIncoming, c.fileOutgoing, c.fileStorageLease, c.fileRetentionDone
	payload := []byte("the same approved immutable input\x00\xff")
	raw, err := old.Prepare("profile-prepare", "input.bin", payload)
	if err != nil {
		t.Fatal(err)
	}
	var descriptor protocol.File
	if err := json.Unmarshal([]byte(raw), &descriptor); err != nil {
		t.Fatal(err)
	}
	currentHost.Store(newHost)
	edited, err := c.OpenAppFiles("edited-profile-open", newHost, "nPi")
	if err != nil {
		t.Fatal("Edited hostname needs neither client reset nor process restart", err)
	}
	if pending, err := edited.Pending("edited-profile-pending", "", 10); err != nil || !strings.Contains(pending, descriptor.ID) {
		t.Fatal("Hostname edit lost the original prepared input", pending, err)
	}
	if _, err := edited.Send("edited-profile-send", descriptor.ID); err != nil {
		t.Fatal("Edited profile could not stream the original immutable input", err)
	}
	before := dials.Load()
	if _, err := old.Refresh("old-profile-mismatch"); err == nil || dials.Load() != before {
		t.Fatal("Old profile silently retargeted to the edited hostname", err, dials.Load())
	}
	currentHost.Store(oldHost)
	if _, err := old.Refresh("old-profile-restored"); err != nil {
		t.Fatal("Opening the edited profile mutated the frozen old profile", err)
	}
	if _, err := old.Send("old-profile-send", descriptor.ID); err != nil {
		t.Fatal("Frozen old profile cannot use its still-valid original pairing", err)
	}
	if actual, data, err := receiver.Read("nApp", descriptor.ID); err != nil || actual != descriptor || !bytes.Equal(data, payload) {
		t.Fatal("Profile edit changed transferred identity or actual bytes", actual, err)
	}
	// Local wrappers must not retain each hostname edit or open another storage owner.
	for i := range 64 {
		if _, err := c.LocalFiles(fmt.Sprintf("edit-%d.tailtest.ts.net", i), "nPi"); err != nil {
			t.Fatal("Repeated hostname edits exhausted a retained wrapper cache", i, err)
		}
	}
	if c.fileIncoming != incoming || c.fileOutgoing != outgoing || c.fileStorageLease != lease || c.fileRetentionDone != worker {
		t.Fatal("Pairing wrappers acquired additional managed stores, leases or workers")
	}
	want := []observed{
		{"GET", oldHost + ":8781", "/v1/files/health"},
		{"GET", newHost + ":8781", "/v1/files/health"},
		{"POST", newHost + ":8781", "/v1/files/put"},
		{"GET", oldHost + ":8781", "/v1/files/health"},
		{"POST", oldHost + ":8781", "/v1/files/put"},
	}
	if len(requests) != len(want) || dials.Load() != int32(len(want)) {
		t.Fatal("Profile edit caused missing or repeated HTTP exchanges", len(requests), dials.Load())
	}
	for _, expected := range want {
		if actual := checksync.Receive(t, requests, 3*time.Second, "profile HTTP exchange"); actual != expected {
			t.Fatal("Request did not retain its profile", actual, expected)
		}
	}
}

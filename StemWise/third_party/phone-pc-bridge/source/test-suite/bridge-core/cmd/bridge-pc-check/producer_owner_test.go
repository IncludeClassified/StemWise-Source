package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"phonepcbridge/files"
	"phonepcbridge/pc"
	"phonepcbridge/protocol"
	"tailscale.com/tsnet"
)

const producerTestApp = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
const producerTestJob = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
const producerTestThread = "producer-owned-thread"

// Independent known-answer storage identities keep this an external consumer fixture.
func seedProducerOwner(t *testing.T, data, project, peer string) {
	t.Helper()
	ids := map[string][3]string{
		"nPhone": {"2e3d4f8c9e4eb98a1421fb006bb4649c", "5e3229b9d3a5c96ecfa7b3b2287b12cd", "942cc294d060c6a31dfbc07ec7eab311"},
		"nPi":    {"ab140bf2dcb7b14b65f804597538625d", "7b3b7921ee6d508a67de6ce91f429629", "f542e264d13bdb3c83829f60add36f86"},
	}[peer]
	if ids[0] == "" {
		t.Fatal("Unknown known-answer producer peer")
	}
	request, _ := json.Marshal(struct {
		AppID     string `json:"appId"`
		RequestID string `json:"requestId"`
		CreatedAt int64  `json:"createdAt"`
		Name      string `json:"name"`
		Model     string `json:"model"`
		Project   string `json:"project"`
	}{producerTestApp, producerTestJob, 1700000000000, "Retained producer owner", "fixture", project})
	digest := sha256.Sum256(request)
	hash := hex.EncodeToString(digest[:])
	result, _ := json.Marshal(map[string]string{"requestHash": hash, "threadId": producerTestThread})
	input, _ := json.Marshal(map[string]any{"version": "v1", "appId": producerTestApp, "jobId": producerTestJob, "threadId": producerTestThread, "requestHash": hash, "envelopeHash": strings.Repeat("a", 64), "files": []protocol.File{}})
	store, err := files.Open(filepath.Join(data, "creations"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	for index, record := range []struct {
		name string
		data []byte
	}{{"creation-request.json", request}, {"creation-result.json", result}, {"job-inputs.json", input}} {
		f, err := files.Describe(ids[index], record.name, record.data)
		if err != nil {
			t.Fatal(err)
		}
		if _, err = store.Put(peer, f, bytes.NewReader(record.data)); err != nil {
			t.Fatal(err)
		}
	}
}

func producerOwnerQuery(f protocol.File) url.Values {
	return url.Values{"name": {f.Name}, "size": {strconv.FormatInt(f.Size, 10)}, "sha256": {f.SHA256}, "appId": {producerTestApp}, "jobId": {producerTestJob}, "threadId": {producerTestThread}}
}

func TestProducerQueueRequiresAuthenticatedExactJobOwnerBeforePublishing(t *testing.T) {
	root := t.TempDir()
	producerTestACL(t, root, false)
	seedProducerOwner(t, root, root, "nPi")
	service, err := pc.New(&tsnet.Server{}, pc.Config{Identity: "nPc", Host: "pc.ts.net", CodexExecutable: filepath.Join(root, "absent-codex.exe"), AllowedPeers: []string{"nPi"}, DataDir: root, IntegrationDir: root})
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if service != nil {
			service.Close()
		}
	}()
	producer, err := startProducer(root, service, "nPc", "nPi")
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if producer != nil {
			producer.Close()
		}
	}()
	id, _ := files.NewID()
	payload := []byte("exact owned output bytes")
	file, _ := files.Describe(id, "output.bin", payload)
	for _, test := range []string{"none", "app-only", "job-only", "thread-only", "wrong-app", "wrong-job", "wrong-thread", "duplicate-app", "tampered-owner"} {
		t.Run(test, func(t *testing.T) {
			q := producerOwnerQuery(file)
			q.Set("nonce", strings.Repeat("c", 32))
			switch test {
			case "none":
				q.Del("appId")
				q.Del("jobId")
				q.Del("threadId")
			case "app-only":
				q.Del("jobId")
				q.Del("threadId")
			case "job-only":
				q.Del("appId")
				q.Del("threadId")
			case "thread-only":
				q.Del("appId")
				q.Del("jobId")
			case "wrong-app":
				q.Set("appId", strings.Repeat("c", 32))
			case "wrong-job":
				q.Set("jobId", strings.Repeat("d", 32))
			case "wrong-thread":
				q.Set("threadId", "foreign-thread")
			case "duplicate-app":
				q.Add("appId", producerTestApp)
			}
			r := httptest.NewRequest("POST", "https://"+producer.config.Address+"/v1/outbox/"+id+"?"+q.Encode(), bytes.NewReader(payload))
			r.URL.Scheme = ""
			r.URL.Host = ""
			r.RemoteAddr = "127.0.0.1:60000"
			r.Header.Set("Content-Type", "application/octet-stream")
			r.Header.Set("X-Bridge-Request", producerSignature(producer.config, r, false, 0, nil))
			if test == "tampered-owner" {
				q.Set("appId", strings.Repeat("e", 32))
				r.URL.RawQuery = q.Encode()
			}
			w := httptest.NewRecorder()
			producer.ServeHTTP(w, r)
			if w.Code == 200 {
				t.Fatal("Missing/foreign/tampered owner queued output", w.Body.String())
			}
			if test == "tampered-owner" && w.Code != http.StatusForbidden {
				t.Fatal("Owner query was not HMAC bound", w.Code)
			}
			if _, err := service.QueuedFileInfo(context.Background(), "nPi", id); !os.IsNotExist(err) {
				t.Fatal("Rejected owner committed payload", err)
			}
			entries, err := os.ReadDir(filepath.Join(root, "creations", "nPi"))
			if err != nil || len(entries) != 3 {
				t.Fatal("Rejected owner published a file reference", err, len(entries))
			}
		})
	}
	q := producerOwnerQuery(file)
	for attempt := 0; attempt < 2; attempt++ {
		reply, err := producerReply(producer.config, "POST", "/v1/outbox/"+id, "queued", q, payload)
		if err != nil || reply.File == nil || *reply.File != file {
			t.Fatal("Exact signed owner output/retry failed", attempt, err, reply)
		}
	}
	if _, err := producerReply(producer.config, "DELETE", "/v1/outbox/"+id, "removed_local", url.Values{}, nil); err == nil || !strings.Contains(err.Error(), pc.ErrJobFileReferenced.Error()) {
		t.Fatal("Referenced output discard was not a typed authenticated refusal", err)
	}
	if got, err := os.ReadFile(filepath.Join(root, "outgoing", "nPi", id, "content")); err != nil || !bytes.Equal(got, payload) {
		t.Fatal("Rejected discard changed referenced output", err)
	}
	producer.Close()
	service.Close()
	service, err = pc.New(&tsnet.Server{}, pc.Config{Identity: "nPc", Host: "pc.ts.net", CodexExecutable: filepath.Join(root, "absent-codex.exe"), AllowedPeers: []string{"nPi"}, DataDir: root, IntegrationDir: root})
	if err != nil {
		t.Fatal(err)
	}
	producer, err = startProducer(root, service, "nPc", "nPi")
	if err != nil {
		t.Fatal(err)
	}
	if reply, err := producerReply(producer.config, "POST", "/v1/outbox/"+id, "queued", producerOwnerQuery(file), payload); err != nil || reply.File == nil || *reply.File != file {
		t.Fatal("Reopened producer lost immutable job owner/output receipt", err, reply)
	}
}

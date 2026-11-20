package mobile

import (
	"archive/zip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"phonepcbridge/files"
	"phonepcbridge/pc"
	"phonepcbridge/protocol"
)

func TestFileStreamingRoundTripBeyondByteArrayLimit(t *testing.T) {
	root := t.TempDir()
	path := filepath.Join(root, "owned.zip")
	source, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	z := zip.NewWriter(source)
	entry, err := z.CreateHeader(&zip.FileHeader{Name: "owned.flac", Method: zip.Store})
	if err != nil {
		t.Fatal(err)
	}
	chunk := make([]byte, 32<<10)
	for i := range chunk {
		chunk[i] = byte(i*17 + 23)
	}
	for n := 0; n < 72<<20; n += len(chunk) {
		if _, err := entry.Write(chunk); err != nil {
			t.Fatal(err)
		}
	}
	if err := z.Close(); err != nil {
		t.Fatal(err)
	}
	if err := source.Close(); err != nil {
		t.Fatal(err)
	}
	open := func(name string) *files.Store {
		t.Helper()
		s, err := files.Open(filepath.Join(root, name))
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { s.Close() })
		return s
	}
	pcIn, pcOut, phoneIn, phoneOut := open("pc-in"), open("pc-out"), open("phone-in"), open("phone-out")
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { pc.ServeFiles(w, r, "nPhone", "nPc", pcIn, pcOut) }))
	defer server.Close()
	corrupt := true
	client := &FileClient{client: &Client{}, identity: "nPc", inbox: phoneIn, outbox: phoneOut}
	client.exchange = func(_ context.Context, _ string, method, path string, data []byte) (string, error) {
		if strings.Contains(path, "/content?") || method != "GET" {
			t.Fatal("File data used buffered transport")
		}
		r, err := server.Client().Get(server.URL + path)
		if err != nil {
			return "", err
		}
		defer r.Body.Close()
		body, err := io.ReadAll(io.LimitReader(r.Body, 128<<10))
		if err != nil {
			return "", err
		}
		wire, err := json.Marshal(map[string]any{"status": r.StatusCode, "body": body, "identity": "nPhone"})
		return string(wire), err
	}
	client.stream = func(_ context.Context, _ string, method, path string, body io.Reader) (int, io.ReadCloser, string, error) {
		r, err := http.NewRequest(method, server.URL+path, body)
		if err != nil {
			return 0, nil, "", err
		}
		response, err := server.Client().Do(r)
		if err != nil {
			return 0, nil, "", err
		}
		if method == "GET" && corrupt {
			original := response.Body
			response.Body = &fileStreamBody{ReadCloser: struct {
				io.Reader
				io.Closer
			}{io.MultiReader(original, strings.NewReader("extra")), original}, finish: func() {}}
		}
		return response.StatusCode, response.Body, "nPhone", nil
	}
	runtime.GC()
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)
	prepared, err := client.PrepareFile("fixture-preparefile", "owned.zip", path)
	if err != nil {
		t.Fatal(err)
	}
	var descriptor protocol.File
	if json.Unmarshal([]byte(prepared), &descriptor) != nil || descriptor.Size <= 64<<20 {
		t.Fatal("Large source descriptor unavailable")
	}
	if _, err := client.Send("owned-send", descriptor.ID); err != nil {
		t.Fatal(err)
	}
	if receipt, err := pcIn.Info("nPhone", descriptor.ID); err != nil || receipt != descriptor {
		t.Fatal("PC did not persist actual streamed bytes", err)
	}
	source, err = os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := pcOut.Put("nPhone", descriptor, source); err != nil {
		source.Close()
		t.Fatal(err)
	}
	source.Close()
	if result, err := client.ReceiveMatching("owned-corrupt", descriptor.ID, descriptor.Name, descriptor.Size, descriptor.SHA256); err == nil || result != "" {
		t.Fatal("Changed stream acquired a verified receipt")
	}
	if _, err := phoneIn.Info("nPc", descriptor.ID); !os.IsNotExist(err) {
		t.Fatal("Changed stream committed local bytes", err)
	}
	corrupt = false
	if _, err := client.ReceiveMatching("owned-receive", descriptor.ID, descriptor.Name, descriptor.Size, descriptor.SHA256); err != nil {
		t.Fatal(err)
	}
	destination := filepath.Join(root, "copied.zip")
	if err := client.CopyReceived("fixture-copyreceived", descriptor.ID, destination); err != nil {
		t.Fatal(err)
	}
	if err := client.CopyReceived("fixture-copyreceived", descriptor.ID, destination); err == nil {
		t.Fatal("Copy overwrote an existing app file")
	}
	runtime.ReadMemStats(&after)
	if after.TotalAlloc-before.TotalAlloc > 16<<20 {
		t.Fatal("Streamed round trip allocated payload-sized memory", after.TotalAlloc-before.TotalAlloc)
	}
	for _, path := range []string{path, destination} {
		source, err := os.Open(path)
		if err != nil {
			t.Fatal(err)
		}
		hash := sha256.New()
		size, err := io.Copy(hash, source)
		source.Close()
		if err != nil || size != descriptor.Size || hex.EncodeToString(hash.Sum(nil)) != descriptor.SHA256 {
			t.Fatal("Independent actual archive bytes differ", err)
		}
	}
	if _, err := client.ReadReceived("fixture-readreceived", descriptor.ID); err == nil {
		t.Fatal("Large result used byte-array API")
	}
}

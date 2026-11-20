package backend

import (
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"testing"

	"golang.org/x/sys/unix"
	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func TestPiStorageReserveOnSmallFilesystem(t *testing.T) {
	testPiStorageReserve(t, false)
}

func TestPiStorageReserveOnInodeLimitedFilesystem(t *testing.T) {
	testPiStorageReserve(t, true)
}

func testPiStorageReserve(t *testing.T, inodeLimited bool) {
	path := os.Getenv("BRIDGE_TEST_SMALL_FS")
	if path == "" {
		t.Skip("Run through StorageCapacityCheck.py on an owned small filesystem")
	}
	var stat unix.Statfs_t
	if err := unix.Statfs(path, &stat); err != nil || stat.Type != unix.TMPFS_MAGIC {
		t.Fatal("Owned small filesystem differs", err)
	}
	available := stat.Bavail * uint64(stat.Bsize)
	if (!inodeLimited && available >= 64<<20) || (inodeLimited && (available <= 512<<20 || stat.Files > 128)) {
		t.Fatal("Owned filesystem does not isolate the requested capacity condition")
	}
	if filepath.Clean(os.TempDir()) != filepath.Clean(path) {
		t.Fatal("Test temporary storage is outside the owned small filesystem")
	}
	s, native, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	connection := call("GET", "/v2/connection", nil, "nAlice")
	var capabilities struct {
		Data struct {
			Transfers struct {
				Reserve int64 `json:"storageReserveBytes"`
				Inodes  int64 `json:"storageReserveInodes"`
			} `json:"fileTransfers"`
		} `json:"data"`
	}
	if json.Unmarshal(connection.Body.Bytes(), &capabilities) != nil || capabilities.Data.Transfers.Reserve != 512<<20 || capabilities.Data.Transfers.Inodes != 1024 {
		t.Fatal("Actual backend does not advertise its mandatory storage reserve")
	}
	if inodeLimited {
		filler := filepath.Join(t.TempDir(), "inode-fixture")
		if err := os.Mkdir(filler, 0700); err != nil {
			t.Fatal(err)
		}
		for index := 0; ; index++ {
			if err := unix.Statfs(path, &stat); err != nil {
				t.Fatal(err)
			}
			if stat.Ffree == 1 {
				break
			}
			if stat.Ffree < 1 || index > 128 {
				t.Fatal("Owned inode fixture bounds differ")
			}
			if err := os.WriteFile(filepath.Join(filler, strconv.Itoa(index)), nil, 0600); err != nil {
				t.Fatal(err)
			}
		}
	}
	data := []byte("actual-owned-upload-" + randomToken())
	f, _ := files.Describe(randomToken(), "owned.bin", data)
	nonce := randomToken()
	query := url.Values{"id": {f.ID}, "name": {f.Name}, "size": {strconv.FormatInt(f.Size, 10)}, "sha256": {f.SHA256}, "nonce": {nonce}}
	source := bytes.NewReader(data)
	request := httptest.NewRequest("POST", "/v1/files/put?"+query.Encode(), source)
	request.RemoteAddr = "nAlice"
	request.Header.Set("Content-Type", "application/octet-stream")
	w := httptest.NewRecorder()
	s.ServeHTTP(w, request)
	var reply protocol.FileReply
	metadata := call("PUT", "/v2/prompts/"+randomToken(), appPrompt{Title: "Metadata remains writable", Text: "original"}, "nAlice")
	if metadata.Code != 200 {
		t.Fatal("Transfer admission exhausted coordinator metadata capacity", w.Code, metadata.Code)
	}
	if w.Code != 503 || protocol.DecodeStrict(w.Body.Bytes(), &reply) != nil || reply.Protocol != protocol.Files || reply.PCIdentity != "nPi" || reply.PeerIdentity != "nAlice" || reply.Nonce != nonce || reply.State != protocol.StorageCapacityUnavailable || reply.File != nil || reply.Page != nil || source.Len() != len(data) {
		t.Fatal("Actual low-space HTTP refusal lost correlation or consumed/stored bytes", w.Code)
	}
	if _, err := s.inbox.Info("nAlice", f.ID); !os.IsNotExist(err) || native.calls != 0 {
		t.Fatal("Refused upload was retained or reached native execution", err)
	}
}

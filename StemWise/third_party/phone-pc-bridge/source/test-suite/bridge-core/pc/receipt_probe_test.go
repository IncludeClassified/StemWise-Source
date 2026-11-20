package pc

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func TestReceiptAbsenceIsConclusiveAndNeverMasksPartialOrCorruptCommit(t *testing.T) {
	root := t.TempDir()
	incoming, err := files.Open(filepath.Join(root, "incoming"))
	if err != nil {
		t.Fatal(err)
	}
	defer incoming.Close()
	outgoing, err := files.Open(filepath.Join(root, "outgoing"))
	if err != nil {
		t.Fatal(err)
	}
	defer outgoing.Close()
	id := strings.Repeat("a", 32)
	probe := func(expected int, state string) protocol.FileReply {
		t.Helper()
		nonce := randomToken()
		request := httptest.NewRequest(http.MethodGet, "/v1/files/"+id+"/receipt?nonce="+nonce, nil)
		response := httptest.NewRecorder()
		ServeFiles(response, request, "nPi", "nPc", incoming, outgoing)
		var receipt protocol.FileReply
		if response.Code != expected || json.Unmarshal(response.Body.Bytes(), &receipt) != nil || receipt.State != state || receipt.Nonce != nonce || receipt.PCIdentity != "nPc" || receipt.PeerIdentity != "nPi" || receipt.Page != nil {
			t.Fatal("Receipt probe did not preserve exact paired nonce or commit state", response.Code, response.Body.String())
		}
		return receipt
	}
	if got := probe(404, "absent"); got.File != nil {
		t.Fatal("Absent receipt invented a descriptor")
	}
	content := []byte("immutable original transfer")
	info, _ := files.Describe(id, "input.bin", content)
	if _, err := incoming.Put("nPi", info, bytes.NewReader(content)); err != nil {
		t.Fatal(err)
	}
	if got := probe(200, "stored"); got.File == nil || *got.File != info {
		t.Fatal("Stored probe changed immutable transfer")
	}
	path := filepath.Join(root, "incoming", "nPi", id)
	if err := os.WriteFile(filepath.Join(path, "content"), bytes.Repeat([]byte("x"), len(content)), 0600); err != nil {
		t.Fatal(err)
	}
	if got := probe(503, "unavailable"); got.File != nil {
		t.Fatal("Corrupt receipt was represented as committed")
	}
	if err := os.Remove(filepath.Join(path, "content")); err != nil {
		t.Fatal(err)
	}
	probe(503, "unavailable")
}

func TestOutboxInfoReportsMetadataAndContentStillVerifiesActualBytes(t *testing.T) {
	root := t.TempDir()
	incoming, err := files.Open(filepath.Join(root, "incoming"))
	if err != nil {
		t.Fatal(err)
	}
	defer incoming.Close()
	outgoing, err := files.Open(filepath.Join(root, "outgoing"))
	if err != nil {
		t.Fatal(err)
	}
	defer outgoing.Close()
	content := []byte("immutable output file")
	info, _ := files.Describe(randomToken(), "output.bin", content)
	if _, err := outgoing.Put("nPi", info, bytes.NewReader(content)); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "outgoing", "nPi", info.ID, "content"), bytes.Repeat([]byte("x"), len(content)), 0600); err != nil {
		t.Fatal(err)
	}
	for _, endpoint := range []string{"info", "content"} {
		nonce := randomToken()
		request := httptest.NewRequest(http.MethodGet, "/v1/outbox/"+info.ID+"/"+endpoint+"?nonce="+nonce, nil)
		response := httptest.NewRecorder()
		ServeFiles(response, request, "nPi", "nPc", incoming, outgoing)
		var result protocol.FileReply
		if json.Unmarshal(response.Body.Bytes(), &result) != nil || result.Nonce != nonce {
			t.Fatal("Uncorrelated outbox response")
		}
		if endpoint == "info" {
			if response.Code != 200 || result.File == nil || *result.File != info {
				t.Fatal("Metadata route performed content verification", response.Code, result)
			}
		} else if response.Code != 503 || result.File != nil || result.State != "unavailable" {
			t.Fatal("Actual content read failed to verify corrupt bytes", response.Code, result)
		}
	}
}

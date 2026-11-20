package mobile

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func TestStreamedUploadErrorsRequireFreshCorrelationAndNeverReplay(t *testing.T) {
	for _, variant := range []string{"content_mismatch", "conflict", "unavailable", "rejected", "storage_capacity_unavailable", "nonce", "server", "peer", "transport_identity", "protocol", "unknown_field", "unknown_state", "unexpected_file", "unexpected_page", "success_status", "redirect_status"} {
		t.Run(variant, func(t *testing.T) {
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
			client := &FileClient{client: &Client{}, identity: "nPi", inbox: open("incoming"), outbox: open("outgoing")}
			client.exchange = func(context.Context, string, string, string, []byte) (string, error) {
				t.Fatal("Streamed send used buffered exchange")
				return "", nil
			}
			content := []byte("immutable-owned-upload-" + variant)
			prepared, err := client.Prepare("fixture-prepare", "owned.bin", content)
			if err != nil {
				t.Fatal(err)
			}
			var f protocol.File
			if json.Unmarshal([]byte(prepared), &f) != nil {
				t.Fatal("Prepared descriptor invalid")
			}
			calls := 0
			status := 503
			state := "unavailable"
			switch variant {
			case "storage_capacity_unavailable":
				state = variant
			case "content_mismatch":
				status, state = 400, variant
			case "conflict":
				status, state = 409, variant
			case "rejected":
				status, state = 400, variant
			}
			client.stream = func(_ context.Context, _ string, method, path string, source io.Reader) (int, io.ReadCloser, string, error) {
				calls++
				if method != "POST" {
					t.Fatal("Upload caused a different operation")
				}
				u, err := url.Parse(path)
				if err != nil {
					t.Fatal(err)
				}
				if u.Path != "/v1/files/put" || u.Query().Get("id") != f.ID {
					t.Fatal("Original transfer identity changed")
				}
				actual, err := io.ReadAll(source)
				if err != nil || !bytes.Equal(actual, content) {
					t.Fatal("Actual prepared source bytes changed", err)
				}
				own := "nPhone"
				r := protocol.FileReply{Protocol: protocol.Files, PCIdentity: "nPi", PeerIdentity: own, Nonce: u.Query().Get("nonce"), State: state}
				switch variant {
				case "nonce":
					r.Nonce = strings.Repeat("0", 32)
				case "server":
					r.PCIdentity = "nOther"
				case "peer":
					r.PeerIdentity = "nOther"
				case "transport_identity":
					own = "nOther"
				case "protocol":
					r.Protocol = "other"
				case "unknown_state":
					r.State = "password=never_export_this"
				case "unexpected_file":
					r.File = &f
				case "unexpected_page":
					r.Page = &protocol.FilePage{Files: []protocol.File{f}}
				case "success_status":
					status = 200
				case "redirect_status":
					status = 307
				}
				data, _ := json.Marshal(r)
				if variant == "unknown_field" {
					data = append(data[:len(data)-1], []byte(`,"details":"password=never_export_this"}`)...)
				}
				return status, io.NopCloser(bytes.NewReader(data)), own, nil
			}
			result, err := client.Send("owned-send", f.ID)
			if err == nil || result != "" {
				t.Fatal("Rejected upload reported delivery")
			}
			trusted := variant == "content_mismatch" || variant == "conflict" || variant == "unavailable" || variant == "rejected" || variant == "storage_capacity_unavailable"
			if strings.HasPrefix(err.Error(), "file_upload_rejected ") != trusted || strings.Contains(err.Error(), "never_export_this") {
				t.Fatal("Upload error trusted an uncorrelated reply or lost a verified rejection", variant, err)
			}
			if trusted {
				action := "inspect_original_transfer_receipt"
				if variant == "storage_capacity_unavailable" {
					action = "wait_for_transfers_or_release_bridge_storage_then_retry_same_transfer"
				}
				for _, detail := range []string{"http_status=" + strconv.Itoa(status), "state=" + state, "transfer_id=" + f.ID, "action=" + action} {
					if !strings.Contains(err.Error(), detail) {
						t.Fatal("Verified upload rejection lacks investigation fields", detail, err)
					}
				}
			}
			if calls != 1 {
				t.Fatal("Failure automatically replayed the upload", calls)
			}
			stored, data, err := client.outbox.Read(client.identity, f.ID)
			if err != nil || stored != f || !bytes.Equal(data, content) {
				t.Fatal("Failure discarded or changed the original source", err)
			}
		})
	}
}

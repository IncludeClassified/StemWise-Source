package mobile

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

type interruptedDownloadReceipt struct{ io.Reader }

func (r interruptedDownloadReceipt) Read(p []byte) (int, error) {
	n, err := r.Reader.Read(p)
	if err == io.EOF {
		err = io.ErrUnexpectedEOF
	}
	return n, err
}

func TestDownloadFailuresPreserveDiagnosticsAndExactRetry(t *testing.T) {
	for _, variant := range []string{"missing", "unavailable", "rejected", "capacity", "capacity_status", "interrupted", "oversized", "nonce", "server", "peer", "identity", "protocol", "unknown_state", "unknown_field", "redirect", "unexpected_file", "unexpected_page", "success_state"} {
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
			remote, incoming, outgoing := open("remote"), open("incoming"), open("outgoing")
			id, _ := files.NewID()
			content := []byte("actual-owned-download-" + id)
			descriptor, _ := files.Describe(id, "owned.bin", content)
			if _, err := remote.Put("nPhone", descriptor, bytes.NewReader(content)); err != nil {
				t.Fatal(err)
			}
			failed, calls, closed := true, 0, 0
			status, state := 503, "unavailable"
			switch variant {
			case "missing":
				status = 404
			case "rejected":
				status, state = 400, "rejected"
			case "capacity", "interrupted", "oversized":
				state = protocol.DownloadCapacityUnavailable
			case "capacity_status":
				status, state = 404, protocol.DownloadCapacityUnavailable
			case "redirect":
				status = 307
			}
			reply := func(method, path string) (int, []byte, string) {
				calls++
				u, err := url.Parse(path)
				if err != nil || method != "GET" {
					t.Fatal("Download attempted a mutation", err)
				}
				own := "nPhone"
				r := protocol.FileReply{Protocol: protocol.Files, PCIdentity: "nPi", PeerIdentity: own, Nonce: u.Query().Get("nonce"), State: "available", File: &descriptor}
				code := 200
				switch u.Path {
				case "/v1/outbox/" + id + "/info":
				case "/v1/outbox/" + id + "/content":
					if !failed {
						_, data, err := remote.Read("nPhone", id)
						if err != nil {
							t.Fatal(err)
						}
						return 200, data, own
					}
					code, r.State, r.File = status, state, nil
					switch variant {
					case "nonce":
						r.Nonce = strings.Repeat("0", 32)
					case "server":
						r.PCIdentity = "nOther"
					case "peer":
						r.PeerIdentity = "nOther"
					case "identity":
						own = "nOther"
					case "protocol":
						r.Protocol = "other"
					case "unknown_state":
						r.State = "password=never_export_this"
					case "unexpected_file":
						r.File = &descriptor
					case "unexpected_page":
						r.Page = &protocol.FilePage{Files: []protocol.File{descriptor}}
					case "success_state":
						r.State = "available"
					}
				default:
					t.Fatal("Unexpected download endpoint", u.Path)
				}
				data, _ := json.Marshal(r)
				if failed && strings.HasSuffix(u.Path, "/content") {
					if variant == "unknown_field" {
						data = append(data[:len(data)-1], []byte(`,"details":"password=never_export_this"}`)...)
					} else if variant == "oversized" {
						data = append(data, bytes.Repeat([]byte(" "), 256<<10)...)
					}
				}
				return code, data, own
			}
			client := &FileClient{client: &Client{}, identity: "nPi", inbox: incoming, outbox: outgoing}
			client.exchange = func(_ context.Context, _ string, method, path string, _ []byte) (string, error) {
				if strings.Contains(path, "/content?") {
					t.Fatal("Download payload used control transport")
				}
				code, data, own := reply(method, path)
				wire, err := json.Marshal(map[string]any{"status": code, "body": data, "identity": own})
				return string(wire), err
			}
			client.stream = func(_ context.Context, _ string, method, path string, body io.Reader) (int, io.ReadCloser, string, error) {
				if body != nil {
					t.Fatal("Download sent an unexpected body")
				}
				code, data, own := reply(method, path)
				var reader io.Reader = bytes.NewReader(data)
				if failed && variant == "interrupted" {
					reader = interruptedDownloadReceipt{reader}
				}
				return code, &fileStreamBody{ReadCloser: io.NopCloser(reader), finish: func() { closed++ }}, own, nil
			}
			result, err := client.ReceiveMatching("owned-failure", id, descriptor.Name, descriptor.Size, descriptor.SHA256)
			if err == nil || result != "" || calls != 2 {
				t.Fatal("Failure claimed receipt or replayed download", result, err, calls)
			}
			trusted := variant == "missing" || variant == "unavailable" || variant == "rejected" || variant == "capacity"
			if strings.HasPrefix(err.Error(), "file_download_rejected ") != trusted || strings.Contains(err.Error(), "never_export_this") {
				t.Fatal("Diagnostic lost a verified rejection or trusted an invalid response", err)
			}
			if trusted {
				for _, detail := range []string{"http_status=" + strconv.Itoa(status), "state=" + state, "transfer_id=" + id, "action="} {
					if !strings.Contains(err.Error(), detail) {
						t.Fatal("Download rejection lacks investigation fields", detail, err)
					}
				}
			}
			if variant == "interrupted" && (!errors.Is(err, io.ErrUnexpectedEOF) || !strings.Contains(err.Error(), "file_download_receipt_unavailable")) {
				t.Fatal("Interrupted response was treated as a confirmed rejection or lost its cause", err)
			}
			if variant == "oversized" && !strings.Contains(err.Error(), "file_download_receipt_too_large") {
				t.Fatal("Oversized response lacks a bounded receipt error", err)
			}
			if _, err := incoming.Info("nPi", id); !os.IsNotExist(err) {
				t.Fatal("Failed response committed a recipient receipt", err)
			}
			if actual, data, err := remote.Read("nPhone", id); err != nil || actual != descriptor || !bytes.Equal(data, content) {
				t.Fatal("Failure changed the original source", err)
			}
			failed = false
			if _, err := client.ReceiveMatching("owned-retry", id, descriptor.Name, descriptor.Size, descriptor.SHA256); err != nil {
				t.Fatal("Same-descriptor retry failed", err)
			}
			if actual, data, err := incoming.Read("nPi", id); err != nil || actual != descriptor || !bytes.Equal(data, content) || calls != 4 || closed != 2 {
				t.Fatal("Retry did not verify original bytes or close response bodies", err, calls, closed)
			}
		})
	}
}

package mobile

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"path/filepath"
	"strings"
	"testing"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func TestFileControlRepliesRejectAmbiguousOrOversizedJSON(t *testing.T) {
	for _, operation := range []string{"health", "receipt", "listing"} {
		for _, variant := range []string{"valid", "valid_max_page", "valid_diagnostics", "bad_diagnostic_peer", "bad_diagnostic_count", "too_many_diagnostics", "unknown_diagnostic_code", "duplicate_nonce", "duplicate_protocol", "duplicate_state", "unknown_field", "oversized", "duplicate_hash", "invalid_utf8"} {
			if variant == "valid_max_page" && operation != "listing" {
				continue
			}
			if strings.Contains(variant, "diagnostic") && operation != "listing" {
				continue
			}
			if operation == "health" && (variant == "duplicate_hash" || variant == "invalid_utf8") {
				continue
			}
			t.Run(operation+"/"+variant, func(t *testing.T) {
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
				client.exchange = func(context.Context, string, string, string, []byte) (string, error) { return "", nil }
				content := []byte("owned-control-reply-" + operation + "-" + variant)
				prepared, err := client.Prepare("fixture-prepare", "owned\uFFFD.bin", content)
				if err != nil {
					t.Fatal(err)
				}
				var f protocol.File
				if json.Unmarshal([]byte(prepared), &f) != nil {
					t.Fatal("Owned descriptor unavailable")
				}
				calls := 0
				client.exchange = func(_ context.Context, _ string, method, path string, body []byte) (string, error) {
					calls++
					u, err := url.Parse(path)
					if err != nil || method != "GET" || len(body) != 0 {
						t.Fatal("Control reply caused a mutation", err)
					}
					r := protocol.FileReply{Protocol: protocol.Files, PCIdentity: "nPi", PeerIdentity: "nPhone", Nonce: u.Query().Get("nonce"), State: "ready"}
					switch operation {
					case "receipt":
						r.State, r.File = "stored", &f
					case "listing":
						r.State, r.Page = "available", &protocol.FilePage{Files: []protocol.File{f}}
						if strings.Contains(variant, "diagnostic") {
							r.Page.DiagnosticCount = 1
							r.Page.Diagnostics = []protocol.FileDiagnostic{{ID: "damaged-entry", Peer: "nPhone", Code: "managed_object_corrupt"}}
							switch variant {
							case "bad_diagnostic_peer":
								r.Page.Diagnostics[0].Peer = "other"
							case "bad_diagnostic_count":
								r.Page.DiagnosticCount = 0
							case "too_many_diagnostics":
								for len(r.Page.Diagnostics) < 17 {
									r.Page.Diagnostics = append(r.Page.Diagnostics, r.Page.Diagnostics[0])
								}
								r.Page.DiagnosticCount = 17
							case "unknown_diagnostic_code":
								r.Page.Diagnostics[0].Code = "free text"
							}
						}
						if variant == "valid_max_page" {
							r.Page.Files = nil
							for i := 1; i <= 100; i++ {
								entry := f
								entry.ID, entry.Name = fmt.Sprintf("%032x", i), strings.Repeat("&", 255)
								r.Page.Files = append(r.Page.Files, entry)
							}
						}
					}
					data, _ := json.Marshal(r)
					if variant == "valid_max_page" && len(data) <= 128<<10 {
						t.Fatal("Maximum valid page did not exercise escaped filename expansion")
					}
					switch variant {
					case "duplicate_nonce", "duplicate_protocol", "duplicate_state":
						field := strings.TrimPrefix(variant, "duplicate_")
						data = append([]byte(`{"`+field+`":"untrusted-earlier-value",`), data[1:]...)
					case "unknown_field":
						data = append(data[:len(data)-1], []byte(`,"details":"password=never_export_this"}`)...)
					case "oversized":
						data = append(bytes.Repeat([]byte(" "), 256<<10), data...)
					case "duplicate_hash":
						data = bytes.Replace(data, []byte(`"sha256":`), []byte(`"sha256":"`+strings.Repeat("0", 64)+`","sha256":`), 1)
					case "invalid_utf8":
						data = bytes.Replace(data, []byte("\uFFFD"), []byte{0xff}, 1)
					}
					wire, err := json.Marshal(map[string]any{"status": 200, "body": data, "identity": "nPhone"})
					return string(wire), err
				}
				var result string
				switch operation {
				case "health":
					result, err = client.Refresh("owned-health")
				case "receipt":
					result, err = client.Receipt("owned-receipt", f.ID)
				case "listing":
					result, err = client.Available("owned-listing", "", 100)
				}
				if variant == "valid" || variant == "valid_max_page" || variant == "valid_diagnostics" {
					if err != nil || result == "" {
						t.Fatal("Valid existing reply was rejected", err)
					}
					if variant == "valid_max_page" {
						var page protocol.FileReply
						if json.Unmarshal([]byte(result), &page) != nil || page.Page == nil || len(page.Page.Files) != 100 || page.Page.Files[99].Name != strings.Repeat("&", 255) {
							t.Fatal("Maximum valid metadata page changed")
						}
					}
				} else if err == nil || result != "" {
					t.Fatal("Ambiguous or oversized metadata became an accepted control reply")
				} else if strings.Contains(err.Error(), "never_export_this") {
					t.Fatal("Raw rejected response leaked into the error")
				}
				if calls != 1 {
					t.Fatal("Failed control read replayed a request", calls)
				}
				stored, actual, err := client.outbox.Read(client.identity, f.ID)
				if err != nil || stored != f || !bytes.Equal(actual, content) {
					t.Fatal("Control reply changed the prepared source", err)
				}
				page, err := client.inbox.List(client.identity, "", 100)
				if err != nil || len(page.Files) != 0 {
					t.Fatal("Control metadata created a recipient receipt", err)
				}
			})
		}
	}
}

func TestEmptyFileReceiptRequiresExplicitSizeAndPreservesSource(t *testing.T) {
	for _, variant := range []string{"zero", "missing", "null"} {
		t.Run(variant, func(t *testing.T) {
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
			client := &FileClient{client: &Client{}, identity: "nPi", inbox: incoming, outbox: outgoing, exchange: func(context.Context, string, string, string, []byte) (string, error) { return "", nil }}
			prepared, err := client.Prepare("fixture-prepare", "empty.bin", nil)
			if err != nil {
				t.Fatal(err)
			}
			var f protocol.File
			if err := json.Unmarshal([]byte(prepared), &f); err != nil {
				t.Fatal(err)
			}
			calls := 0
			client.exchange = func(_ context.Context, _ string, method, path string, body []byte) (string, error) {
				calls++
				if method != "GET" || len(body) != 0 {
					t.Fatal("Receipt validation caused a mutation")
				}
				u, _ := url.Parse(path)
				descriptor := map[string]any{"id": f.ID, "name": f.Name, "sha256": f.SHA256}
				if variant == "zero" {
					descriptor["size"] = 0
				} else if variant == "null" {
					descriptor["size"] = nil
				}
				data, _ := json.Marshal(map[string]any{"protocol": protocol.Files, "pcIdentity": "nPi", "peerIdentity": "nPhone", "nonce": u.Query().Get("nonce"), "state": "stored", "file": descriptor})
				wire, err := json.Marshal(map[string]any{"status": 200, "body": data, "identity": "nPhone"})
				return string(wire), err
			}
			result, err := client.Receipt("owned-empty-receipt", f.ID)
			if variant == "zero" {
				if err != nil || result == "" {
					t.Fatal("Explicit empty-file receipt was rejected", err)
				}
			} else if err == nil || result != "" {
				t.Fatal("Malformed receipt became a verified empty-file receipt", variant)
			}
			if actual, data, err := outgoing.Read("nPi", f.ID); err != nil || actual != f || len(data) != 0 || calls != 1 {
				t.Fatal("Rejected receipt changed source bytes or replayed a request", err, calls)
			}
		})
	}
}

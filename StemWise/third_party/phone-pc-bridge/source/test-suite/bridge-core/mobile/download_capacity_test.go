package mobile

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/url"
	"path/filepath"
	"strings"
	"testing"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func TestDownloadCapacityRequiresFreshCorrelationAndAllowsExactRetry(t *testing.T) {
	for _, variant := range []string{"valid", "nonce", "server", "peer", "transport_identity", "protocol", "unknown_field", "unexpected_descriptor"} {
		t.Run(variant, func(t *testing.T) {
			root := t.TempDir()
			incoming, err := files.Open(filepath.Join(root, "incoming"))
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { incoming.Close() })
			outgoing, err := files.Open(filepath.Join(root, "outgoing"))
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { outgoing.Close() })
			id, _ := files.NewID()
			content := []byte("exact-owned-download-" + id)
			descriptor, _ := files.Describe(id, "owned.bin", content)
			busy := true
			calls := 0
			replyFor := func(method, path string) (int, []byte, string, error) {
				calls++
				if method != "GET" {
					t.Fatal("Download attempted a mutation")
				}
				u, err := url.Parse(path)
				if err != nil {
					t.Fatal(err)
				}
				own := "nPhone"
				reply := protocol.FileReply{Protocol: protocol.Files, PCIdentity: "nPi", PeerIdentity: own, Nonce: u.Query().Get("nonce"), State: "available", File: &descriptor}
				code := 200
				var body []byte
				switch u.Path {
				case "/v1/outbox/" + id + "/info":
					body, _ = json.Marshal(reply)
				case "/v1/outbox/" + id + "/content":
					if !busy {
						body = content
						break
					}
					code = 503
					reply.File = nil
					reply.State = protocol.DownloadCapacityUnavailable
					switch variant {
					case "nonce":
						reply.Nonce = strings.Repeat("0", 32)
					case "server":
						reply.PCIdentity = "nOther"
					case "peer":
						reply.PeerIdentity = "nOther"
					case "transport_identity":
						own = "nOther"
					case "protocol":
						reply.Protocol = "other"
					case "unexpected_descriptor":
						reply.File = &descriptor
					}
					body, _ = json.Marshal(reply)
					if variant == "unknown_field" {
						body = append(body[:len(body)-1], []byte(`,"untrusted":true}`)...)
					}
				default:
					t.Fatal("Unexpected download endpoint", u.Path)
				}
				return code, body, own, nil
			}
			client := &FileClient{client: &Client{}, identity: "nPi", inbox: incoming, outbox: outgoing}
			client.exchange = func(_ context.Context, _ string, method, path string, _ []byte) (string, error) {
				if strings.Contains(path, "/content?") {
					t.Fatal("Download payload used control transport")
				}
				code, body, own, err := replyFor(method, path)
				if err != nil {
					return "", err
				}
				wire, err := json.Marshal(map[string]any{"status": code, "body": body, "identity": own})
				return string(wire), err
			}
			client.stream = func(_ context.Context, _ string, method, path string, body io.Reader) (int, io.ReadCloser, string, error) {
				if body != nil || !strings.Contains(path, "/content?") {
					t.Fatal("Unexpected download stream request")
				}
				code, data, own, err := replyFor(method, path)
				return code, io.NopCloser(bytes.NewReader(data)), own, err
			}
			result, err := client.ReceiveMatching("owned-receive", id, descriptor.Name, descriptor.Size, descriptor.SHA256)
			if err == nil || result != "" {
				t.Fatal("Busy response claimed recipient verification")
			}
			if strings.Contains(err.Error(), protocol.DownloadCapacityUnavailable) != (variant == "valid") {
				t.Fatal("Uncorrelated busy response trusted", variant, err)
			}
			if calls != 2 {
				t.Fatal("Busy response automatically replayed a download", calls)
			}
			if _, _, err := incoming.Read("nPi", id); err == nil {
				t.Fatal("Busy response stored a local receipt")
			}
			busy = false
			if _, err := client.ReceiveMatching("owned-retry", id, descriptor.Name, descriptor.Size, descriptor.SHA256); err != nil {
				t.Fatal(err)
			}
			_, actual, err := incoming.Read("nPi", id)
			if err != nil || !bytes.Equal(actual, content) || calls != 4 {
				t.Fatal("Same-descriptor retry did not verify actual bytes", err)
			}
		})
	}
}

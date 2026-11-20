package mobile

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/pc"
	"phonepcbridge/protocol"
)

func attachFileHTTP(t *testing.T, f *FileClient, server *httptest.Server, own string) {
	t.Helper()
	f.exchange = func(ctx context.Context, _ string, method, path string, data []byte) (string, error) {
		if method != "GET" || strings.Contains(path, "/content?") || len(data) != 0 {
			return "", errors.New("payload used control transport")
		}
		request, err := http.NewRequestWithContext(ctx, method, server.URL+path, bytes.NewReader(data))
		if err != nil {
			return "", err
		}
		response, err := server.Client().Do(request)
		if err != nil {
			return "", err
		}
		defer response.Body.Close()
		body, err := io.ReadAll(response.Body)
		if err != nil {
			return "", err
		}
		wire, err := json.Marshal(map[string]any{"status": response.StatusCode, "body": body, "identity": own})
		return string(wire), err
	}
	f.stream = func(ctx context.Context, _ string, method, path string, body io.Reader) (int, io.ReadCloser, string, error) {
		request, err := http.NewRequestWithContext(ctx, method, server.URL+path, body)
		if err != nil {
			return 0, nil, "", err
		}
		response, err := server.Client().Do(request)
		if err != nil {
			return 0, nil, "", err
		}
		return response.StatusCode, response.Body, own, nil
	}
}

func TestEnsureSentRecoversOnlyOriginalAbsentOrCommittedTransfer(t *testing.T) {
	for _, lostAck := range []bool{false, true} {
		t.Run(map[bool]string{false: "crash_before_send", true: "lost_ack_after_send"}[lostAck], func(t *testing.T) {
			root := t.TempDir()
			open := func(name string) *files.Store {
				store, err := files.Open(filepath.Join(root, name))
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { store.Close() })
				return store
			}
			remoteIn, remoteOut := open("remote-in"), open("remote-out")
			var posts atomic.Int32
			var drop atomic.Bool
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method == "POST" {
					posts.Add(1)
					if drop.Swap(false) {
						persisted := httptest.NewRecorder()
						pc.ServeFiles(persisted, r, "nApp", "nPi", remoteIn, remoteOut)
						if persisted.Code != 200 {
							t.Errorf("Upload did not commit before lost acknowledgement: %s", persisted.Body)
						}
						conn, _, err := w.(http.Hijacker).Hijack()
						if err != nil {
							t.Error(err)
							return
						}
						conn.Close()
						return
					}
				}
				pc.ServeFiles(w, r, "nApp", "nPi", remoteIn, remoteOut)
			}))
			defer server.Close()
			state := filepath.Join(root, "app")
			openLocal := func() (*Client, *FileClient) {
				c, err := NewClient(state, "ensure-fixture", "")
				if err != nil {
					t.Fatal(err)
				}
				f, err := c.LocalFiles("pi.tailtest.ts.net", "nPi")
				if err != nil {
					c.Close()
					t.Fatal(err)
				}
				attachFileHTTP(t, f, server, "nApp")
				return c, f
			}
			c, f := openLocal()
			content := []byte("original immutable prepared bytes")
			prepared, err := f.Prepare("prepare", "owned.bin", content)
			if err != nil {
				c.Close()
				t.Fatal(err)
			}
			var descriptor protocol.File
			if err := json.Unmarshal([]byte(prepared), &descriptor); err != nil {
				c.Close()
				t.Fatal(err)
			}
			if lostAck {
				drop.Store(true)
				if _, err := f.Send("send-lost", descriptor.ID); err == nil {
					c.Close()
					t.Fatal("Lost acknowledgement became success")
				}
			}
			if err := c.Close(); err != nil {
				t.Fatal(err)
			}
			c, f = openLocal()
			defer c.Close()
			for attempt := 0; attempt < 2; attempt++ {
				phase, err := c.NewOperation()
				if err != nil {
					t.Fatal(err)
				}
				result, err := f.EnsureSent(phase.Token(), descriptor.ID)
				phase.Close()
				if err != nil {
					t.Fatal(err)
				}
				var receipt protocol.FileReply
				if json.Unmarshal([]byte(result), &receipt) != nil || receipt.File == nil || *receipt.File != descriptor || receipt.State != "stored" {
					t.Fatal("Exact original receipt unavailable")
				}
			}
			if posts.Load() != 1 {
				t.Fatal("Original upload replayed or never sent", posts.Load())
			}
			actual, data, err := remoteIn.Read("nApp", descriptor.ID)
			if err != nil || actual != descriptor || !bytes.Equal(data, content) {
				t.Fatal("Immutable original transfer not recovered", err)
			}
		})
	}
}

func TestEnsureSentRejectsUncertainOrCorruptReceiptWithoutSend(t *testing.T) {
	for _, variant := range []string{"wrong_nonce", "wrong_peer", "wrong_identity", "wrong_protocol", "unknown_field", "duplicate_state", "missing_size", "wrong_descriptor", "absent_with_file", "absent_with_page", "absent_200", "unavailable_404", "corrupt_503", "absent_503", "transport_error"} {
		t.Run(variant, func(t *testing.T) {
			c, f := cancellationClient(t)
			prepared, err := f.Prepare("prepare", "owned.bin", []byte("immutable"))
			if err != nil {
				t.Fatal(err)
			}
			var descriptor protocol.File
			if err := json.Unmarshal([]byte(prepared), &descriptor); err != nil {
				t.Fatal(err)
			}
			calls := 0
			f.exchange = func(_ context.Context, _ string, method, path string, _ []byte) (string, error) {
				calls++
				if method != "GET" || !strings.Contains(path, "/"+descriptor.ID+"/receipt?") {
					t.Fatal("Probe changed immutable transfer")
				}
				if variant == "transport_error" {
					return "", errors.New("test transport unavailable")
				}
				u, _ := url.Parse(path)
				reply := protocol.FileReply{Protocol: protocol.Files, PCIdentity: "nPi", PeerIdentity: "nApp", Nonce: u.Query().Get("nonce"), State: "absent"}
				code := 404
				switch variant {
				case "wrong_nonce":
					reply.Nonce = strings.Repeat("0", 32)
				case "wrong_peer":
					reply.PeerIdentity = "nOther"
				case "wrong_identity":
					reply.PCIdentity = "nOther"
				case "wrong_protocol":
					reply.Protocol = "other"
				case "wrong_descriptor":
					code, reply.State, reply.File = 200, "stored", &descriptor
					copy := descriptor
					copy.Name = "different.bin"
					reply.File = &copy
				case "missing_size":
					code, reply.State, reply.File = 200, "stored", &descriptor
				case "absent_with_file":
					reply.File = &descriptor
				case "absent_with_page":
					reply.Page = &protocol.FilePage{Files: []protocol.File{}}
				case "absent_200":
					code = 200
				case "unavailable_404":
					reply.State = "unavailable"
				case "corrupt_503":
					code, reply.State = 503, "corrupt"
				case "absent_503":
					code = 503
				}
				body, _ := json.Marshal(reply)
				switch variant {
				case "unknown_field":
					body = append(body[:len(body)-1], []byte(`,"unknown":true}`)...)
				case "duplicate_state":
					body = append([]byte(`{"state":"stored",`), body[1:]...)
				case "missing_size":
					body = bytes.Replace(body, []byte(`"size":9,`), nil, 1)
				}
				wire, err := json.Marshal(map[string]any{"status": code, "body": body, "identity": "nApp"})
				return string(wire), err
			}
			f.stream = func(context.Context, string, string, string, io.Reader) (int, io.ReadCloser, string, error) {
				t.Fatal("Unconfirmed receipt caused upload")
				return 0, nil, "", nil
			}
			phase, err := c.NewOperation()
			if err != nil {
				t.Fatal(err)
			}
			if result, err := f.EnsureSent(phase.Token(), descriptor.ID); err == nil || result != "" {
				t.Fatal("Unconfirmed receipt accepted", result)
			}
			phase.Close()
			if calls != 1 || len(c.active) != 0 || len(c.operations) != 0 {
				t.Fatal("Probe replayed or leaked operation", calls)
			}
		})
	}
}

func TestEnsureSentCancellationOwnsProbeAndSendGap(t *testing.T) {
	for _, duringProbe := range []bool{false, true} {
		t.Run(map[bool]string{false: "after_probe_before_send", true: "during_probe"}[duringProbe], func(t *testing.T) {
			c, f := cancellationClient(t)
			prepared, err := f.Prepare("prepare", "owned.bin", []byte("immutable"))
			if err != nil {
				t.Fatal(err)
			}
			var descriptor protocol.File
			json.Unmarshal([]byte(prepared), &descriptor)
			phase, err := c.NewOperation()
			if err != nil {
				t.Fatal(err)
			}
			started := make(chan struct{})
			f.exchange = func(ctx context.Context, id, method, path string, _ []byte) (string, error) {
				if method != "GET" {
					t.Fatal("Cancelled phase started upload")
				}
				close(started)
				if duringProbe {
					select {
					case <-ctx.Done():
						return "", ctx.Err()
					case <-time.After(5 * time.Second):
						return "", errors.New("probe cancellation timeout")
					}
				}
				u, _ := url.Parse(path)
				body, _ := json.Marshal(protocol.FileReply{Protocol: protocol.Files, PCIdentity: "nPi", PeerIdentity: "nApp", Nonce: u.Query().Get("nonce"), State: "absent"})
				c.Cancel(id)
				wire, err := json.Marshal(map[string]any{"status": 404, "body": body, "identity": "nApp"})
				return string(wire), err
			}
			f.stream = func(context.Context, string, string, string, io.Reader) (int, io.ReadCloser, string, error) {
				return 0, nil, "", errors.New("cancelled phase started streamed upload")
			}
			done := make(chan error, 1)
			retired := make(chan struct{})
			t.Cleanup(func() {
				phase.Cancel()
				checksync.Join(t, retired, 3*time.Second, "cancelled receipt probe")
			})
			go func() { defer close(retired); _, err := f.EnsureSent(phase.Token(), descriptor.ID); done <- err }()
			select {
			case <-started:
			case <-time.After(5 * time.Second):
				phase.Cancel()
				t.Fatal("Probe never started")
			}
			if duringProbe {
				phase.Cancel()
			}
			select {
			case err := <-done:
				if !errors.Is(err, context.Canceled) {
					t.Fatal("Cancellation lost", err)
				}
			case <-time.After(5 * time.Second):
				phase.Cancel()
				t.Fatal("Cancelled probe did not retire")
			}
			phase.Close()
			if len(c.active) != 0 || len(c.operations) != 0 {
				t.Fatal("Cancelled operation remained owned")
			}
		})
	}
}

func TestEnsureSentDoesNotReplaceCorruptRemoteFinal(t *testing.T) {
	for _, damage := range []string{"changed_bytes", "missing_content"} {
		t.Run(damage, func(t *testing.T) {
			c, f := cancellationClient(t)
			root := t.TempDir()
			remoteIn, err := files.Open(filepath.Join(root, "incoming"))
			if err != nil {
				t.Fatal(err)
			}
			defer remoteIn.Close()
			remoteOut, err := files.Open(filepath.Join(root, "outgoing"))
			if err != nil {
				t.Fatal(err)
			}
			defer remoteOut.Close()
			prepared, err := f.Prepare("prepare", "owned.bin", []byte("immutable"))
			if err != nil {
				t.Fatal(err)
			}
			var descriptor protocol.File
			json.Unmarshal([]byte(prepared), &descriptor)
			if _, err := remoteIn.Put("nApp", descriptor, strings.NewReader("immutable")); err != nil {
				t.Fatal(err)
			}
			path := filepath.Join(root, "incoming", "nApp", descriptor.ID, "content")
			if damage == "changed_bytes" {
				err = os.WriteFile(path, []byte("corrupted"), 0600)
			} else {
				err = os.Remove(path)
			}
			if err != nil {
				t.Fatal(err)
			}
			var posts atomic.Int32
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method == "POST" {
					posts.Add(1)
				}
				pc.ServeFiles(w, r, "nApp", "nPi", remoteIn, remoteOut)
			}))
			defer server.Close()
			attachFileHTTP(t, f, server, "nApp")
			phase, err := c.NewOperation()
			if err != nil {
				t.Fatal(err)
			}
			result, err := f.EnsureSent(phase.Token(), descriptor.ID)
			phase.Close()
			if err == nil || result != "" || posts.Load() != 0 {
				t.Fatal("Damaged final became absent or was overwritten", result, err, posts.Load())
			}
			if damage == "changed_bytes" {
				data, err := os.ReadFile(path)
				if err != nil || string(data) != "corrupted" {
					t.Fatal("Corrupt evidence was changed", err)
				}
			}
		})
	}
}

func TestEnsureSentCancellationOwnsSendAfterConfirmedAbsence(t *testing.T) {
	c, f := cancellationClient(t)
	prepared, err := f.Prepare("prepare", "owned.bin", []byte("immutable"))
	if err != nil {
		t.Fatal(err)
	}
	var descriptor protocol.File
	json.Unmarshal([]byte(prepared), &descriptor)
	phase, err := c.NewOperation()
	if err != nil {
		t.Fatal(err)
	}
	started := make(chan struct{})
	waitForCancel := func(ctx context.Context) error {
		close(started)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(5 * time.Second):
			return errors.New("send cancellation timeout")
		}
	}
	f.exchange = func(ctx context.Context, _ string, method, path string, _ []byte) (string, error) {
		if method != "GET" {
			return "", errors.New("upload used control transport")
		}
		u, _ := url.Parse(path)
		body, _ := json.Marshal(protocol.FileReply{Protocol: protocol.Files, PCIdentity: "nPi", PeerIdentity: "nApp", Nonce: u.Query().Get("nonce"), State: "absent"})
		wire, err := json.Marshal(map[string]any{"status": 404, "body": body, "identity": "nApp"})
		return string(wire), err
	}
	f.stream = func(ctx context.Context, _ string, method, path string, _ io.Reader) (int, io.ReadCloser, string, error) {
		if method != "POST" {
			return 0, nil, "", errors.New("unexpected stream stage")
		}
		return 0, nil, "", waitForCancel(ctx)
	}
	done := make(chan error, 1)
	retired := make(chan struct{})
	t.Cleanup(func() {
		phase.Cancel()
		checksync.Join(t, retired, 3*time.Second, "cancelled streamed upload")
	})
	go func() { defer close(retired); _, err := f.EnsureSent(phase.Token(), descriptor.ID); done <- err }()
	select {
	case <-started:
	case <-time.After(5 * time.Second):
		phase.Cancel()
		t.Fatal("Send phase never started")
	}
	phase.Cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatal("Send cancellation lost", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Cancelled send did not retire")
	}
	phase.Close()
	if len(c.active) != 0 || len(c.operations) != 0 {
		t.Fatal("Cancelled send remained owned")
	}
}

func TestEnsureSentFencesPreparedDescriptorBeforeSend(t *testing.T) {
	c, f := cancellationClient(t)
	prepared, err := f.Prepare("prepare", "owned.bin", []byte("immutable"))
	if err != nil {
		t.Fatal(err)
	}
	var descriptor protocol.File
	json.Unmarshal([]byte(prepared), &descriptor)
	f.exchange = func(_ context.Context, _ string, method, path string, _ []byte) (string, error) {
		if method != "GET" {
			t.Fatal("Changed prepared object was sent")
		}
		if err := f.outbox.Remove("nPi", descriptor.ID); err != nil {
			t.Fatal(err)
		}
		changed := descriptor
		changed.Name = "replacement.bin"
		if _, err := f.outbox.Put("nPi", changed, strings.NewReader("immutable")); err != nil {
			t.Fatal(err)
		}
		u, _ := url.Parse(path)
		body, _ := json.Marshal(protocol.FileReply{Protocol: protocol.Files, PCIdentity: "nPi", PeerIdentity: "nApp", Nonce: u.Query().Get("nonce"), State: "absent"})
		wire, err := json.Marshal(map[string]any{"status": 404, "body": body, "identity": "nApp"})
		return string(wire), err
	}
	f.stream = func(context.Context, string, string, string, io.Reader) (int, io.ReadCloser, string, error) {
		t.Fatal("Changed prepared object was streamed")
		return 0, nil, "", nil
	}
	result, err := f.EnsureSent("ensure", descriptor.ID)
	if err == nil || result != "" || !strings.Contains(err.Error(), "prepared_transfer_descriptor_changed") {
		t.Fatal("Immutable descriptor fence was lost", err)
	}
	if len(c.active) != 0 {
		t.Fatal("Failed transfer retained active operation")
	}
}

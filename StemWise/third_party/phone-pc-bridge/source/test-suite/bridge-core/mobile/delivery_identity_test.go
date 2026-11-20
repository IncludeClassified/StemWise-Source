package mobile

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
)

func TestDeliveryRejectsPathReplacementDuringHash(t *testing.T) {
	for _, kind := range []string{"replaced", "removed", "earlier_batch_entry"} {
		t.Run(kind, func(t *testing.T) {
			c := new(Client)
			root := t.TempDir()
			payload := []byte("the independently verified destination bytes")
			entries := []deliveryImport{}
			count := 1
			if kind == "earlier_batch_entry" {
				count = 2
			}
			for index := 0; index < count; index++ {
				path := filepath.Join(root, string(rune('a'+index))+".zip")
				if err := os.WriteFile(path, payload, 0600); err != nil {
					t.Fatal(err)
				}
				descriptor, err := files.Describe(strings.Repeat(string(rune('a'+index)), 32), "output.zip", payload)
				if err != nil {
					t.Fatal(err)
				}
				entries = append(entries, deliveryImport{File: descriptor, Path: path})
			}
			calls, acknowledged, blocked := 0, false, false
			c.localFileCopy = func(_ context.Context, destination io.Writer, source io.Reader) (int64, error) {
				size, err := io.Copy(destination, source)
				calls++
				if calls != count || err != nil {
					return size, err
				}
				if err := os.Rename(entries[0].Path, filepath.Join(root, "removed-original.zip")); err != nil {
					if os.IsPermission(err) || (runtime.GOOS == "windows" && errors.Is(err, syscall.Errno(32))) {
						blocked = true
						return size, nil
					}
					return size, err
				}
				if kind != "removed" {
					if err := os.WriteFile(entries[0].Path, payload, 0600); err != nil {
						t.Fatal(err)
					}
				}
				return size, err
			}
			imports, _ := json.Marshal(entries)
			_, err := c.verifiedDelivery(context.Background(), string(imports), func([]protocol.File) (string, error) { acknowledged = true; return "acknowledged", nil })
			if blocked {
				t.Skip("Operating system forbids renaming the pinned open source; Linux race lane exercises replacement")
			}
			if err == nil || !strings.Contains(err.Error(), "delivery_destination_unverified") || acknowledged {
				t.Fatal("A renamed, deleted or replaced import was verified for acknowledgement", err)
			}
			if bytes, err := os.ReadFile(filepath.Join(root, "removed-original.zip")); err != nil || string(bytes) != string(payload) {
				t.Fatal("Verification mutated the retained original", err)
			}
		})
	}
}

func TestDeliveryRetainsDestinationOwnershipThroughAcknowledgment(t *testing.T) {
	for _, replace := range []bool{false, true} {
		t.Run(fmt.Sprint(replace), func(t *testing.T) {
			c, _ := streamReadinessClient(t, func(*http.Request, int) (string, error) { return streamReadyStatus, nil })
			f, err := c.LocalFiles("pi.tailtest.ts.net", "nPi")
			if err != nil {
				t.Fatal(err)
			}
			root := t.TempDir()
			path := filepath.Join(root, "import.zip")
			payload := []byte("actual app-owned imported result")
			if err := os.WriteFile(path, payload, 0600); err != nil {
				t.Fatal(err)
			}
			descriptor, _ := files.Describe(strings.Repeat("a", 32), "output.zip", payload)
			if _, err := f.inbox.Put("nPi", descriptor, strings.NewReader(string(payload))); err != nil {
				t.Fatal(err)
			}
			imports, _ := json.Marshal([]deliveryImport{{File: descriptor, Path: path}})
			entered, release := make(chan struct{}), make(chan struct{})
			serverDone, deliveryDone := make(chan struct{}), make(chan struct{})
			serverResult, deliveryResult := make(chan error, 1), make(chan error, 1)
			var once sync.Once
			t.Cleanup(func() {
				once.Do(func() { close(release) })
				c.Cancel("confirm-original")
				checksync.Join(t, deliveryDone, 3*time.Second, "delivery acknowledgement")
				checksync.Join(t, serverDone, 3*time.Second, "delivery fixture peer")
			})
			var dials atomic.Int32
			c.dial = func(_ context.Context, network, address string) (net.Conn, error) {
				if dials.Add(1) != 1 || network != "tcp" || address != "100.64.0.8:8781" {
					return nil, errors.New("Unexpected or repeated delivery request")
				}
				local, remote := net.Pipe()
				_ = remote.SetDeadline(time.Now().Add(3 * time.Second))
				go func() {
					defer close(serverDone)
					defer remote.Close()
					err := func() error {
						r, err := http.ReadRequest(bufio.NewReader(remote))
						if err != nil {
							return err
						}
						defer r.Body.Close()
						var body struct {
							Files []protocol.File `json:"files"`
						}
						if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
							return err
						}
						if r.Method != "POST" || r.URL.Path != "/v2/jobs/"+strings.Repeat("c", 32)+"/delivery" || len(body.Files) != 1 || body.Files[0] != descriptor {
							return errors.New("Original delivery ownership or batch changed")
						}
						close(entered)
						select {
						case <-release:
						case <-time.After(3 * time.Second):
							return errors.New("Owned acknowledgement was not released")
						}
						response := `{"protocol":"phone-pc-bridge-app-v1","bridgeIdentity":"nPi","data":{"delivered":true}}`
						_, err = fmt.Fprintf(remote, "HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s", len(response), response)
						return err
					}()
					serverResult <- err
				}()
				return local, nil
			}
			var response string
			go func() {
				defer close(deliveryDone)
				var err error
				response, err = c.ConfirmDelivery("confirm-original", "pi.tailtest.ts.net", "nPi", strings.Repeat("c", 32), string(imports))
				deliveryResult <- err
			}()
			checksync.Receive(t, entered, 3*time.Second, "actual delivery request before acknowledgement")
			if _, err := c.ConfirmDelivery("competing-delivery", "pi.tailtest.ts.net", "nPi", strings.Repeat("c", 32), string(imports)); err == nil || !strings.Contains(err.Error(), "delivery_destination_in_use") {
				t.Fatal("Concurrent delivery bypassed the original destination owner", err)
			}
			if err := f.CopyReceived("competing-copy", descriptor.ID, path); err == nil || !strings.Contains(err.Error(), "received_copy_in_use") {
				t.Fatal("Delivery released destination ownership before acknowledgement", err)
			}
			changed := false
			if replace {
				if err := os.Rename(path, filepath.Join(root, "retained-original.zip")); err != nil {
					if !os.IsPermission(err) && !(runtime.GOOS == "windows" && errors.Is(err, syscall.Errno(32))) {
						t.Fatal(err)
					}
				} else {
					changed = true
					if err := os.WriteFile(path, payload, 0600); err != nil {
						t.Fatal(err)
					}
				}
			}
			once.Do(func() { close(release) })
			err = checksync.Receive(t, deliveryResult, 3*time.Second, "delivery result")
			if changed {
				if err == nil || !strings.Contains(err.Error(), "delivery_destination_changed_acknowledgment_unconfirmed") || response != "" {
					t.Fatal("Replaced import falsely returned a confirmed delivery", response, err)
				}
			} else if err != nil || !strings.Contains(response, `"httpStatus":200`) {
				t.Fatal("Unchanged owned import failed delivery", response, err)
			}
			if err := checksync.Receive(t, serverResult, 3*time.Second, "delivery peer outcome"); err != nil {
				t.Fatal(err)
			}
			checksync.Join(t, deliveryDone, 3*time.Second, "delivery operation retirement")
			if dials.Load() != 1 {
				t.Fatal("Uncertain acknowledgement was replayed", dials.Load())
			}
			if err := f.CopyReceived("after-ack-copy", descriptor.ID, path); !errors.Is(err, os.ErrExist) {
				t.Fatal("Delivery retained destination claim after acknowledgement", err)
			}
		})
	}
}

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
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"phonepcbridge/internal/checksync"
	"tailscale.com/client/local"
)

const streamReadyStatus = `{"BackendState":"Running","Self":{"ID":"nApp","UserID":1},"Peer":{"nodekey:0000000000000000000000000000000000000000000000000000000000000000":{"ID":"nPi","DNSName":"pi.tailtest.ts.net.","UserID":1,"TailscaleIPs":["100.64.0.8"]}}}`

type readinessStreamReader struct {
	io.Reader
	reads atomic.Int32
}

func (r *readinessStreamReader) Read(p []byte) (int, error) { r.reads.Add(1); return r.Reader.Read(p) }

func streamReadinessClient(t *testing.T, status func(*http.Request, int) (string, error)) (*Client, *atomic.Int32) {
	t.Helper()
	c, err := NewClient(filepath.Join(t.TempDir(), "private-node"), "stream-readiness", "")
	if err != nil {
		t.Fatal(err)
	}
	calls := new(atomic.Int32)
	c.started = true
	c.local = &local.Client{OmitAuth: true, Transport: statusRoundTrip(func(r *http.Request) (*http.Response, error) {
		if r.Method != "GET" || r.URL.Path != "/localapi/v0/status" {
			return nil, errors.New("unexpected local readiness mutation")
		}
		raw, err := status(r, int(calls.Add(1)))
		if err != nil {
			return nil, err
		}
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(raw))}, nil
	})}
	t.Cleanup(func() {
		c.mu.Lock()
		c.started, c.local = false, nil
		c.mu.Unlock()
		if err := c.Close(); err != nil {
			t.Error(err)
		}
	})
	return c, calls
}

func TestStreamReadinessDefersActualBodyAndNeverReplaysAfterSend(t *testing.T) {
	for _, outcome := range []string{"success", "http_failure", "lost_ack"} {
		t.Run(outcome, func(t *testing.T) {
			release, entered := make(chan struct{}), make(chan struct{})
			var allowReady, announced sync.Once
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			c, statuses := streamReadinessClient(t, func(r *http.Request, call int) (string, error) {
				if call == 1 {
					return `{"BackendState":"Starting"}`, nil
				}
				announced.Do(func() { close(entered) })
				select {
				case <-release:
					return streamReadyStatus, nil
				case <-r.Context().Done():
					return "", r.Context().Err()
				}
			})
			var dials, requests, statusAtDial atomic.Int32
			serverDone := make(chan struct{})
			serverErrors := make(chan error, 1)
			var pipeMu sync.Mutex
			var ownedPipe net.Conn
			c.dial = func(_ context.Context, network, address string) (net.Conn, error) {
				if dials.Add(1) != 1 {
					return nil, errors.New("unexpected replay dial")
				}
				if network != "tcp" || address != "100.64.0.8:8781" {
					return nil, fmt.Errorf("unexpected paired dial %s %s", network, address)
				}
				statusAtDial.Store(statuses.Load())
				clientPipe, serverPipe := net.Pipe()
				pipeMu.Lock()
				ownedPipe = serverPipe
				pipeMu.Unlock()
				_ = serverPipe.SetDeadline(time.Now().Add(3 * time.Second))
				go func() {
					defer close(serverDone)
					defer serverPipe.Close()
					request, err := http.ReadRequest(bufio.NewReader(serverPipe))
					if err != nil {
						serverErrors <- err
						return
					}
					defer request.Body.Close()
					data, err := io.ReadAll(request.Body)
					if err != nil {
						serverErrors <- err
						return
					}
					requests.Add(1)
					if request.Method != "POST" || request.URL.Path != "/v1/files/put" || request.Host != "pi.tailtest.ts.net:8781" || string(data) != "one immutable streamed upload" {
						serverErrors <- errors.New("actual streamed request/body changed")
						return
					}
					if outcome == "lost_ack" {
						serverErrors <- nil
						return
					}
					status := "200 OK"
					if outcome == "http_failure" {
						status = "503 Service Unavailable"
					}
					_, err = fmt.Fprintf(serverPipe, "HTTP/1.1 %s\r\nContent-Length: 7\r\nConnection: close\r\n\r\nreceipt", status)
					serverErrors <- err
				}()
				return clientPipe, nil
			}
			body := &readinessStreamReader{Reader: strings.NewReader("one immutable streamed upload")}
			type result struct {
				code      int
				own, body string
				err       error
			}
			done, retired := make(chan result, 1), make(chan struct{})
			t.Cleanup(func() {
				allowReady.Do(func() { close(release) })
				cancel()
				pipeMu.Lock()
				if ownedPipe != nil {
					ownedPipe.Close()
				}
				pipeMu.Unlock()
				checksync.Join(t, retired, 3*time.Second, "stream readiness operation retirement")
				if statusAtDial.Load() != 0 {
					checksync.Join(t, serverDone, 3*time.Second, "actual stream peer retirement")
				}
			})
			go func() {
				defer close(retired)
				code, response, own, err := c.fileStream(ctx, "owned-stream", "pi.tailtest.ts.net", "nPi", 8781, "POST", "/v1/files/put", body)
				var data []byte
				if response != nil {
					var readErr error
					data, readErr = io.ReadAll(response)
					err = errors.Join(err, readErr, response.Close())
				}
				done <- result{code, own, string(data), err}
			}()
			select {
			case <-entered:
			case result := <-done:
				t.Fatal("Stream failed before waiting for cold readiness", result.err)
			case <-time.After(3 * time.Second):
				t.Fatal("Readiness did not poll safe local status")
			}
			if body.reads.Load() != 0 || dials.Load() != 0 {
				t.Fatal("Unready transport consumed upload bytes or dialed", body.reads.Load(), dials.Load())
			}
			allowReady.Do(func() { close(release) })
			got := checksync.Receive(t, done, 3*time.Second, "one streamed request after readiness")
			if outcome == "lost_ack" {
				if got.err == nil {
					t.Fatal("Lost acknowledgment falsely succeeded")
				}
			} else {
				want := 200
				if outcome == "http_failure" {
					want = 503
				}
				if got.err != nil || got.code != want || got.own != "nApp" || got.body != "receipt" {
					t.Fatal("Actual stream response was not returned unchanged", got)
				}
			}
			if err := checksync.Receive(t, serverErrors, 3*time.Second, "actual streamed peer result"); err != nil {
				t.Fatal(err)
			}
			if requests.Load() != 1 || dials.Load() != 1 || body.reads.Load() == 0 || statuses.Load() != statusAtDial.Load() {
				t.Fatal("File stream replayed/repolled after actual send or never sent its bytes", requests.Load(), dials.Load(), statuses.Load())
			}
		})
	}
}

func TestStreamReadinessCancellationAndHardPairingFailureNeverDialOrReadBody(t *testing.T) {
	for _, failure := range []string{"pre_cancelled", "cancel_wait", "wrong_host", "wrong_owner", "expired_peer", "needs_login"} {
		t.Run(failure, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			entered := make(chan struct{})
			var once sync.Once
			c, statuses := streamReadinessClient(t, func(r *http.Request, _ int) (string, error) {
				if failure == "cancel_wait" {
					once.Do(func() { close(entered) })
					<-r.Context().Done()
					return "", r.Context().Err()
				}
				raw := streamReadyStatus
				switch failure {
				case "wrong_host":
					raw = strings.Replace(raw, "pi.tailtest.ts.net.", "other.tailtest.ts.net.", 1)
				case "wrong_owner":
					raw = strings.Replace(raw, `"UserID":1,"TailscaleIPs"`, `"UserID":2,"TailscaleIPs"`, 1)
				case "expired_peer":
					raw = strings.Replace(raw, `"TailscaleIPs"`, `"Expired":true,"TailscaleIPs"`, 1)
				case "needs_login":
					raw = `{"BackendState":"NeedsLogin"}`
				}
				return raw, nil
			})
			var dials atomic.Int32
			c.dial = func(context.Context, string, string) (net.Conn, error) {
				dials.Add(1)
				return nil, errors.New("unsafe stream dial")
			}
			body := &readinessStreamReader{Reader: strings.NewReader("must remain unread")}
			if failure == "pre_cancelled" {
				cancel()
			}
			done, retired := make(chan error, 1), make(chan struct{})
			t.Cleanup(func() { cancel(); checksync.Join(t, retired, 3*time.Second, "rejected streamed readiness retirement") })
			go func() {
				defer close(retired)
				_, response, _, err := c.fileStream(ctx, "rejected-stream", "pi.tailtest.ts.net", "nPi", 8781, "POST", "/v1/files/put", body)
				if response != nil {
					response.Close()
				}
				done <- err
			}()
			if failure == "cancel_wait" {
				checksync.Receive(t, entered, 3*time.Second, "actual local status wait")
				cancel()
			}
			err := checksync.Receive(t, done, 3*time.Second, "rejected streamed readiness")
			if err == nil || dials.Load() != 0 || body.reads.Load() != 0 {
				t.Fatal("Unready/cancelled stream touched actual HTTP body/dial", err, dials.Load(), body.reads.Load())
			}
			if failure == "pre_cancelled" || failure == "cancel_wait" {
				if !errors.Is(err, context.Canceled) {
					t.Fatal("Stream cancellation was reclassified", err)
				}
			} else {
				var diagnostic struct {
					Protocol  string `json:"bridgeTransportError"`
					Phase     string `json:"phase"`
					Sent      *bool  `json:"requestSent"`
					Retryable *bool  `json:"retryable"`
				}
				if json.Unmarshal([]byte(err.Error()), &diagnostic) != nil || diagnostic.Protocol != "v1" || diagnostic.Phase != "pre_send" || diagnostic.Sent == nil || *diagnostic.Sent || diagnostic.Retryable == nil || *diagnostic.Retryable {
					t.Fatal("Hard readiness failure lost truthful pre-send classification", err)
				}
				if statuses.Load() != 1 {
					t.Fatal("Hard pairing/auth mismatch was polled or retried", statuses.Load())
				}
			}
			if failure == "pre_cancelled" && statuses.Load() != 0 {
				t.Fatal("Pre-cancelled stream started local readiness")
			}
		})
	}
}

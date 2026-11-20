package mobile

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/logtail"
	"tailscale.com/net/netns"
	"tailscale.com/tailcfg"
	"tailscale.com/tstest/integration/testcontrol"
)

func TestNetworkLossRetiresRealNodePreservesEnrollmentAndNeverReplays(t *testing.T) {
	logtail.Disable()
	netns.SetEnabled(false)
	t.Cleanup(func() { netns.SetEnabled(true) })
	control := &testcontrol.Server{DERPMap: &tailcfg.DERPMap{Regions: map[int]*tailcfg.DERPRegion{}}, Logf: t.Logf}
	control.HTTPTestServer = httptest.NewServer(control)
	t.Cleanup(control.HTTPTestServer.Close)
	c, err := NewClient(filepath.Join(t.TempDir(), "node"), "network-lifecycle", "")
	if err != nil {
		t.Fatal(err)
	}
	c.node.ControlURL = control.HTTPTestServer.URL
	t.Cleanup(func() { c.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := c.Start(); err != nil {
		t.Fatal(err)
	}
	awaitIdentity := func() *ipnstate.Status {
		t.Helper()
		for {
			status, err := c.local.Status(ctx)
			if err == nil && status.Self != nil && status.Self.ID != "" {
				return status
			}
			select {
			case <-ctx.Done():
				t.Fatal("fixture node enrollment did not complete", err)
			case <-time.After(20 * time.Millisecond):
			}
		}
	}
	status := awaitIdentity()
	identity, publicKey := status.Self.ID, status.Self.PublicKey
	firstNode := c.node
	files, err := c.LocalFiles("pi.tailtest.ts.net", "nPi")
	if err != nil {
		t.Fatal(err)
	}
	raw, err := files.Prepare("prepare-before-loss", "retained.bin", []byte("retained across network loss"))
	if err != nil {
		t.Fatal(err)
	}
	var descriptor struct {
		ID string `json:"id"`
	}
	if json.Unmarshal([]byte(raw), &descriptor) != nil || descriptor.ID == "" {
		t.Fatal("local file not prepared", raw)
	}
	before, err := files.Pending("pending-before-loss", "", 10)
	if err != nil {
		t.Fatal(err)
	}

	// Substitute only paired metadata and the remote HTTP boundary; the node,
	// SDK HTTP execution and network-lifetime cancellation remain real.
	c.local = &local.Client{OmitAuth: true, Transport: statusRoundTrip(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(streamReadyStatus))}, nil
	})}
	accepted, retired := make(chan struct{}), make(chan struct{})
	var requests atomic.Int32
	peer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer close(retired)
		payload, err := io.ReadAll(r.Body)
		if err != nil || r.Method != "POST" || !bytes.Equal(payload, []byte(`{"original":true}`)) {
			t.Error("original request changed", err)
		}
		requests.Add(1)
		close(accepted)
		<-r.Context().Done()
	}))
	t.Cleanup(peer.Close)
	address := peer.Listener.Addr().String()
	c.dial = func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "tcp", address)
	}
	result := make(chan error, 1)
	go func() {
		_, err := c.CallApp("inflight-original", "pi.tailtest.ts.net", "nPi", "POST", "/v2/jobs", `{"original":true}`)
		result <- err
	}()
	select {
	case <-accepted:
	case <-ctx.Done():
		t.Fatal("owned HTTP request did not enter")
	}
	if err := c.SetNetworkEnabled(false); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-result:
		if err == nil || strings.Contains(err.Error(), `"requestSent":false`) {
			t.Fatal("accepted request loss became success or no-send", err)
		}
	case <-ctx.Done():
		t.Fatal("network loss did not cancel actual HTTP operation")
	}
	select {
	case <-retired:
	case <-ctx.Done():
		t.Fatal("remote stream did not close")
	}
	if c.started || c.local != nil || c.node == firstNode || c.networkContext.Err() == nil {
		t.Fatal("node lifetime survived network revocation")
	}
	if _, err := c.Status(); err == nil || !strings.Contains(err.Error(), "bridge_network_unavailable") {
		t.Fatal("forbidden network restarted the node", err)
	}
	after, err := files.Pending("pending-offline", "", 10)
	if err != nil || before != after {
		t.Fatal("network loss changed local file or retention", err, before, after)
	}
	if err := c.SetNetworkEnabled(true); err != nil || c.started {
		t.Fatal("restoring permission eagerly started the node", err)
	}
	if requests.Load() != 1 {
		t.Fatal("request replayed on permission restoration")
	}
	if err := c.Start(); err != nil {
		t.Fatal(err)
	}
	status = awaitIdentity()
	if status.Self.ID != identity || status.Self.PublicKey != publicKey {
		t.Fatal("approved recovery replaced enrollment")
	}
	if requests.Load() != 1 {
		t.Fatal("explicit node restart replayed an accepted mutation")
	}
}

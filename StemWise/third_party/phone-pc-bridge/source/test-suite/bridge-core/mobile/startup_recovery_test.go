package mobile

import (
	"context"
	"errors"
	"net"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/logtail"
	"tailscale.com/net/netns"
	"tailscale.com/tailcfg"
	"tailscale.com/tstest/integration/testcontrol"
)

func startupFixture(t *testing.T) *Client {
	t.Helper()
	logtail.Disable()
	netns.SetEnabled(false)
	t.Cleanup(func() { netns.SetEnabled(true) })
	control := &testcontrol.Server{DERPMap: &tailcfg.DERPMap{Regions: map[int]*tailcfg.DERPRegion{}}, Logf: t.Logf}
	control.HTTPTestServer = httptest.NewServer(control)
	t.Cleanup(control.HTTPTestServer.Close)
	c, err := NewClient(filepath.Join(t.TempDir(), "node"), "startup-recovery", "")
	if err != nil {
		t.Fatal(err)
	}
	c.node.ControlURL = control.HTTPTestServer.URL
	t.Cleanup(func() {
		if err := c.Close(); err != nil {
			t.Error(err)
		}
	})
	return c
}

func startupIdentity(t *testing.T, c *Client) *ipnstate.PeerStatus {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	for {
		status, err := c.local.Status(ctx)
		if err == nil && status.Self != nil && status.Self.ID != "" {
			return status.Self
		}
		select {
		case <-ctx.Done():
			t.Fatal("isolated enrollment did not settle", err)
		case <-time.After(20 * time.Millisecond):
		}
	}
}

func TestStartupRetryAfterFailureBeforeSystemCreation(t *testing.T) {
	c := startupFixture(t)
	state := c.node.Dir
	if err := os.Remove(state); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(state, []byte("temporary filesystem fault"), 0600); err != nil {
		t.Fatal(err)
	}
	failed := c.node
	if err := c.Start(); err == nil {
		t.Fatal("invalid state directory started")
	}
	if c.started || c.local != nil || c.node == failed || c.networkContext.Err() == nil || c.networkStopError != nil {
		t.Fatal("failed startup published readiness or poisoned its replacement")
	}
	if failed.Sys() != nil {
		t.Fatal("fixture failed after system creation")
	}
	if err := os.Remove(state); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(state, 0700); err != nil {
		t.Fatal(err)
	}
	if err := c.Start(); err != nil {
		t.Fatal("same-client retry stayed poisoned", err)
	}
	startupIdentity(t, c)
}

func TestStartupRetryRetiresPartialEngineAndPreservesEnrolledState(t *testing.T) {
	c := startupFixture(t)
	if err := c.Start(); err != nil {
		t.Fatal(err)
	}
	identity := startupIdentity(t, c)
	files, err := c.LocalFiles("pi.tailtest.ts.net", "nPi")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := files.Prepare("prepare-before-fault", "retained.bin", []byte("accepted local input")); err != nil {
		t.Fatal(err)
	}
	before, err := files.Pending("before-fault", "", 10)
	if err != nil {
		t.Fatal(err)
	}
	if err := c.SetNetworkEnabled(false); err != nil {
		t.Fatal(err)
	}
	statePath := filepath.Join(c.node.Dir, "tailscaled.state")
	state, err := os.ReadFile(statePath)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(statePath, []byte("temporary invalid read"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := c.SetNetworkEnabled(true); err != nil {
		t.Fatal(err)
	}
	failed := c.node
	if err := c.Start(); err == nil {
		t.Fatal("unreadable enrollment was treated as healthy")
	}
	if c.started || c.local != nil || c.node == failed || c.networkContext.Err() == nil {
		t.Fatal("partial startup remained live or poisoned retry")
	}
	if failed.Sys() == nil {
		t.Fatal("fixture did not exercise partial native initialization")
	}
	engine, ok := failed.Sys().Engine.GetOK()
	if !ok {
		t.Fatal("fixture did not create actual native sockets")
	}
	select {
	case <-engine.Done():
	default:
		t.Fatal("failed startup left its native engine alive")
	}
	if err := os.WriteFile(statePath, state, 0600); err != nil {
		t.Fatal(err)
	}
	if err := c.Start(); err != nil {
		t.Fatal("same-client native retry stayed poisoned", err)
	}
	afterIdentity := startupIdentity(t, c)
	if afterIdentity.ID != identity.ID || afterIdentity.PublicKey != identity.PublicKey {
		t.Fatal("local retry replaced enrollment")
	}
	after, err := files.Pending("after-fault", "", 10)
	if err != nil || after != before {
		t.Fatal("local retry lost input or renewed retention", err)
	}
	if err := c.node.Close(); err != nil {
		t.Fatal(err)
	}
	c.networkStopError = c.node.Close()
	if !errors.Is(c.networkStopError, net.ErrClosed) {
		t.Fatal("fixture did not produce the stale retirement error")
	}
	if err := c.SetNetworkEnabled(false); err != nil || c.networkStopError != nil {
		t.Fatal("known retirement poisoned the replacement", err)
	}
	if err := c.SetNetworkEnabled(true); err != nil || c.started {
		t.Fatal("restoring permission replayed work or retained a stale error", err)
	}
	if err := c.Start(); err != nil {
		t.Fatal("healthy replacement did not restart", err)
	}
	if startupIdentity(t, c).ID != identity.ID {
		t.Fatal("healthy replacement changed identity")
	}
}

func TestStartupRetryClearsConfirmedRetirementAndResumesOwnedPolicy(t *testing.T) {
	c := startupFixture(t)
	if err := c.SetSocketBinder(&policyTestBinder{}); err != nil {
		t.Fatal(err)
	}
	if err := c.SetNetworkEnabled(true); err != nil {
		t.Fatal(err)
	}
	if err := c.Start(); err != nil {
		t.Fatal(err)
	}
	identity := startupIdentity(t, c).ID
	if err := c.node.Close(); err != nil {
		t.Fatal(err)
	}
	c.networkStopError = c.node.Close()
	if !errors.Is(c.networkStopError, net.ErrClosed) {
		t.Fatal("fixture did not record prior retirement")
	}
	c.mu.Lock()
	platformSuspend(c)
	c.mu.Unlock()
	if err := c.Start(); err != nil {
		t.Fatal("confirmed retirement left the allowed policy poisoned", err)
	}
	if c.networkStopError != nil || !c.started || !c.socketPolicy.ready() || startupIdentity(t, c).ID != identity {
		t.Fatal("retry did not publish the healthy existing enrollment")
	}
}

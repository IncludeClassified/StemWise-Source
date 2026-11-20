package pc

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"phonepcbridge/codex"
	"phonepcbridge/internal/checkpeer"
	"tailscale.com/client/tailscale/apitype"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

func TestMain(m *testing.M) {
	if mode := os.Getenv("BRIDGE_AUTH_PEER"); mode != "" && len(os.Args) >= 3 && os.Args[1] == "app-server" {
		runAuthPeer(mode)
		os.Exit(0)
	}
	if mode := os.Getenv("BRIDGE_CHECK_PEER"); mode != "" && len(os.Args) >= 3 && os.Args[1] == "app-server" {
		socket := os.Getenv("BRIDGE_CHECK_SOCKET")
		if socket == "" {
			if len(os.Args) != 3 || os.Args[2] != "--stdio" {
				os.Exit(25)
			}
		} else if len(os.Args) != 5 || os.Args[2] != "proxy" || os.Args[3] != "--sock" || os.Args[4] != socket {
			os.Exit(26)
		}
		checkpeer.Run(mode)
		os.Exit(0)
	}
	// Every real native fixture inherits an isolated managed credential source.
	home, err := os.MkdirTemp(".", ".auth-test-")
	if err != nil {
		panic(err)
	}
	home, err = filepath.Abs(home)
	if err != nil {
		panic(err)
	}
	if err := os.WriteFile(filepath.Join(home, "auth.json"), []byte(`{"fixture":"managed-chatgpt"}`), 0600); err != nil {
		panic(err)
	}
	if err := os.Setenv("CODEX_HOME", home); err != nil {
		panic(err)
	}
	result := m.Run()
	if err := os.RemoveAll(home); err != nil {
		panic(err)
	}
	os.Exit(result)
}

func TestExplicitSharedSocket(t *testing.T) {
	t.Setenv("BRIDGE_CHECK_PEER", "normal")
	socket := t.TempDir() + string(os.PathSeparator) + "control.sock"
	t.Setenv("BRIDGE_CHECK_SOCKET", socket)
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	c, err := codex.Connect(ctx, executable, socket)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if _, err := c.Models(ctx, ""); err != nil {
		t.Fatal(err)
	}
}

func TestNativeOwnerBoundary(t *testing.T) {
	cfg := Config{Identity: "nPc", Host: "pc.tailtest.ts.net"}
	p := &ipnstate.PeerStatus{ID: "nAlice", UserID: 1}
	s := &ipnstate.Status{BackendState: "Running", Self: &ipnstate.PeerStatus{ID: "nPc", DNSName: cfg.Host + ".", UserID: 1}, Peer: map[key.NodePublic]*ipnstate.PeerStatus{key.NodePublic{}: p}}
	who := &apitype.WhoIsResponse{Node: &tailcfg.Node{StableID: "nAlice", User: 1}, UserProfile: &tailcfg.UserProfile{ID: 1}}
	if owner(s, cfg) != nil {
		t.Fatal("Actual PC identity rejected")
	}
	if id, err := sender(s, who); err != nil || id != "nAlice" {
		t.Fatal("Native same-owner caller rejected")
	}
	who.UserProfile.ID = 2
	if _, err := sender(s, who); err == nil {
		t.Fatal("Foreign owner accepted")
	}
	who.UserProfile.ID = 1
	p.Expired = true
	if _, err := sender(s, who); err == nil {
		t.Fatal("Expired caller accepted")
	}
	p.Expired = false
	s.Self.Expired = true
	if owner(s, cfg) == nil {
		t.Fatal("Expired PC accepted")
	}
	if _, err := sender(nil, who); err == nil {
		t.Fatal("Missing native state accepted")
	}
}

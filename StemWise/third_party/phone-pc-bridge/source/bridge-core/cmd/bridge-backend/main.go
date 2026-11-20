package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"phonepcbridge/backend"
	"phonepcbridge/protocol"
	"strconv"
	"strings"
	"syscall"
	"tailscale.com/client/local"
	"tailscale.com/client/tailscale/apitype"
	"tailscale.com/ipn/ipnstate"
	"time"
)

type profile struct {
	Identity    string `json:"identity"`
	Host        string `json:"host"`
	PCIdentity  string `json:"pcIdentity"`
	PCHost      string `json:"pcHost"`
	Project     string `json:"pcProject"`
	WakeProfile string `json:"wakeProfile"`
	Interface   string `json:"interface"`
	PCAddress   string `json:"pcAddress"`
}

func owner(status *ipnstate.Status, p profile) error {
	if status == nil || status.BackendState != "Running" || status.Self == nil || string(status.Self.ID) != p.Identity || strings.TrimSuffix(status.Self.DNSName, ".") != p.Host || status.Self.UserID <= 0 || status.Self.Expired {
		return errors.New("pi_identity_unavailable")
	}
	return nil
}
func sender(status *ipnstate.Status, who *apitype.WhoIsResponse, p profile) (string, error) {
	if owner(status, p) != nil || who == nil || who.Node == nil || who.UserProfile == nil || who.Node.User != status.Self.UserID || who.UserProfile.ID != status.Self.UserID {
		return "", errors.New("same_owner_required")
	}
	id := string(who.Node.StableID)
	for _, peer := range status.Peer {
		if peer != nil && string(peer.ID) == id && peer.UserID == status.Self.UserID && !peer.Expired && id != p.Identity && id != p.PCIdentity {
			return id, nil
		}
	}
	return "", errors.New("app_identity_unavailable")
}
func run() error {
	config := flag.String("profile", "", "Pi backend profile")
	state := flag.String("state", "", "Private persistent backend directory")
	flag.Parse()
	if flag.NArg() != 0 {
		return errors.New("invalid_arguments")
	}
	data, err := os.ReadFile(*config)
	if err != nil {
		return err
	}
	var p profile
	if protocol.DecodeStrict(data, &p) != nil {
		return errors.New("invalid_backend_profile")
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	lc := &local.Client{}
	status, err := lc.Status(ctx)
	if err != nil || owner(status, p) != nil {
		return errors.New("pi_identity_unavailable")
	}
	transport := &http.Transport{Proxy: nil, DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
		snapshot, err := lc.Status(ctx)
		if err != nil || owner(snapshot, p) != nil {
			return nil, errors.New("pi_identity_unavailable")
		}
		var address string
		for _, peer := range snapshot.Peer {
			if peer != nil && string(peer.ID) == p.PCIdentity && strings.TrimSuffix(peer.DNSName, ".") == p.PCHost && peer.UserID == snapshot.Self.UserID && !peer.Expired {
				for _, ip := range peer.TailscaleIPs {
					if ip.Is4() {
						address = ip.String()
						break
					}
				}
			}
		}
		if address == "" {
			return nil, errors.New("pc_identity_unavailable")
		}
		return (&net.Dialer{Timeout: 5 * time.Second}).DialContext(ctx, "tcp4", net.JoinHostPort(address, strconv.Itoa(protocol.Port)))
	}}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	base := "http://" + net.JoinHostPort(p.PCHost, strconv.Itoa(protocol.Port))
	native := &backend.Remote{PCIdentity: p.PCIdentity, PiIdentity: p.Identity, Wire: backend.HTTPWire(client, base), Stream: backend.HTTPStream(client, base)}
	authenticate := func(ctx context.Context, remote string) (string, error) {
		ctx, stop := context.WithTimeout(ctx, 5*time.Second)
		defer stop()
		status, err := lc.Status(ctx)
		if err != nil {
			return "", err
		}
		who, err := lc.WhoIs(ctx, remote)
		if err != nil {
			return "", err
		}
		return sender(status, who, p)
	}
	wake := func(ctx context.Context) error {
		return exec.CommandContext(ctx, "/usr/bin/python3", "-B", "/opt/phone-pc-bridge/pi-bridge/bridge-wake.py", "--profile", p.WakeProfile, "--relay-id", p.Identity, "--interface", p.Interface, "--pc-address", p.PCAddress, "--send").Run()
	}
	service, err := backend.New(backend.Config{Identity: p.Identity, Host: p.Host, PCIdentity: p.PCIdentity, PCHost: p.PCHost, DataDir: *state, IntegrationDir: p.Project}, native, authenticate, wake)
	if err != nil {
		return err
	}
	defer service.Close()
	address := ""
	for _, ip := range status.Self.TailscaleIPs {
		if ip.Is4() {
			address = ip.String()
			break
		}
	}
	if address == "" {
		return errors.New("pi_private_address_unavailable")
	}
	listener, err := net.Listen("tcp4", net.JoinHostPort(address, strconv.Itoa(protocol.PiPort)))
	if err != nil {
		return err
	}
	defer listener.Close()
	server := &http.Server{Handler: service, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 90 * time.Second, WriteTimeout: 90 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 8192}
	// Cleanup is active before the first app request, including after reboot without a connected app.
	if err := service.Run(); err != nil {
		return err
	}
	go func() { <-ctx.Done(); server.Close() }()
	err = server.Serve(listener)
	if err == http.ErrServerClosed {
		return nil
	}
	return err
}
func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "bridge_backend_unavailable:", err)
		os.Exit(1)
	}
}

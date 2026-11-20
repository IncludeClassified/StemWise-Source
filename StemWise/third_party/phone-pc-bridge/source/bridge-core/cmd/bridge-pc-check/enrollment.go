package main

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn/ipnstate"
)

func waitForPCEnrollment(ctx context.Context, lc *local.Client, backend, testPeer string) (*ipnstate.Status, error) {
	if !producerNode.MatchString(backend) || (testPeer != "" && (!producerNode.MatchString(testPeer) || testPeer == backend)) {
		return nil, errors.New("Require an exact Pi backend and a distinct optional round-trip Android peer")
	}
	required := []string{backend}
	if testPeer != "" {
		required = append(required, testPeer)
	}
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	loginRequested, shown := false, ""
	for {
		if err := ctx.Err(); err != nil {
			return nil, fmt.Errorf("PC enrollment remains unconfirmed: %w", err)
		}
		probe, stop := context.WithTimeout(ctx, 5*time.Second)
		status, err := lc.Status(probe)
		stop()
		// A late network or local daemon response must not discard the startup window.
		if err == nil && status != nil {
			if status.BackendState == "Running" && status.Self != nil && !status.Self.Expired && status.Self.UserID > 0 {
				paired := true
				for _, expected := range required {
					found := false
					for _, p := range status.Peer {
						if p != nil && string(p.ID) == expected {
							if found || p.UserID != status.Self.UserID || p.Expired || p.ID == status.Self.ID {
								return nil, errors.New("Required Pi or round-trip peer must be unique, unexpired and owned by the PC account")
							}
							found = true
						}
					}
					paired = paired && found
				}
				// Running can precede the peer map. Ordinary hosts depend only on the Pi.
				if paired {
					if err := ctx.Err(); err != nil {
						return nil, fmt.Errorf("PC enrollment remains unconfirmed: %w", err)
					}
					return status, nil
				}
			}
			if status.BackendState == "NeedsLogin" && !loginRequested {
				probe, stop := context.WithTimeout(ctx, 5*time.Second)
				err = lc.StartLoginInteractive(probe)
				stop()
				if err != nil {
					return nil, errors.New("Normal embedded PC enrollment request failed")
				}
				loginRequested = true
			}
			if status.AuthURL != "" && status.AuthURL != shown {
				u, err := url.Parse(status.AuthURL)
				if err != nil || u.Scheme != "https" || u.Host != "login.tailscale.com" || u.User != nil {
					return nil, errors.New("Normal enrollment URL did not validate")
				}
				fmt.Println("NORMAL_ENROLLMENT", status.AuthURL)
				shown = status.AuthURL
			}
		}
		select {
		case <-ctx.Done():
			return nil, fmt.Errorf("PC enrollment remains unconfirmed: %w", ctx.Err())
		case <-ticker.C:
		}
	}
}

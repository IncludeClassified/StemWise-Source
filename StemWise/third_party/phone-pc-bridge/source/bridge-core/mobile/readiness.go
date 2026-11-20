package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/netip"
	"strings"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn/ipnstate"
)

const readinessTimeout = 10 * time.Second
const readinessPoll = 200 * time.Millisecond

// Only failures before entering the HTTP transport carry this no-send evidence.
type readinessFailure struct {
	Protocol    string `json:"bridgeTransportError"`
	Code        string `json:"code"`
	Phase       string `json:"phase"`
	RequestSent bool   `json:"requestSent"`
	Retryable   bool   `json:"retryable"`
	cause       error
}

func (e *readinessFailure) Error() string {
	data, _ := json.Marshal(e)
	return string(data)
}
func (e *readinessFailure) Unwrap() error { return e.cause }

func readinessError(code string, retryable bool, cause error) error {
	return &readinessFailure{Protocol: "v1", Code: code, Phase: "pre_send", Retryable: retryable, cause: cause}
}

func target(s *ipnstate.Status, host, identity string) (netip.Addr, error) {
	refuse := func(code string, retryable bool, cause error) (netip.Addr, error) {
		return netip.Addr{}, readinessError(code, retryable, cause)
	}
	if !peerHost.MatchString(host) || !sessionIdentity.MatchString(identity) {
		return refuse("paired_target_invalid", false, nil)
	}
	if s == nil {
		return refuse("bridge_transport_starting", true, nil)
	}
	if s.Self != nil && s.Self.ID != "" && string(s.Self.ID) == identity {
		return refuse("paired_identity_mismatch", false, nil)
	}
	var found *ipnstate.PeerStatus
	for _, p := range s.Peer {
		if p == nil {
			continue
		}
		// Known conflicting metadata is a refusal even while the backend starts.
		if p.ID != "" && strings.TrimSuffix(p.DNSName, ".") == host && string(p.ID) != identity {
			return refuse("paired_identity_mismatch", false, nil)
		}
		if string(p.ID) != identity {
			continue
		}
		if found != nil {
			return refuse("paired_identity_ambiguous", false, nil)
		}
		found = p
	}
	if found != nil {
		if found.DNSName != "" && strings.TrimSuffix(found.DNSName, ".") != host {
			return refuse("paired_dns_mismatch", false, nil)
		}
		if s.Self != nil && s.Self.UserID > 0 && found.UserID > 0 && found.UserID != s.Self.UserID {
			return refuse("paired_owner_mismatch", false, nil)
		}
	}
	switch s.BackendState {
	case "NeedsLogin":
		return refuse("bridge_enrollment_required", false, errEnrollmentRequired)
	case "NeedsMachineAuth":
		return refuse("bridge_device_approval_required", false, errDeviceApprovalRequired)
	case "Stopped":
		return refuse("bridge_transport_stopped", false, nil)
	case "", "NoState", "Starting":
		return refuse("bridge_transport_starting", true, nil)
	case "Running":
	default:
		return refuse("bridge_transport_unavailable", false, nil)
	}
	if s.Self == nil || s.Self.ID == "" || s.Self.UserID <= 0 {
		return refuse("bridge_transport_starting", true, nil)
	}
	if s.Self.Expired {
		return refuse("bridge_enrollment_required", false, errEnrollmentRequired)
	}
	if found == nil {
		return refuse("paired_peer_map_pending", true, nil)
	}
	if found.DNSName == "" || found.UserID <= 0 {
		return refuse("paired_peer_map_pending", true, nil)
	}
	if found.Expired {
		return refuse("paired_device_enrollment_expired", false, errPeerEnrollmentExpired)
	}
	// Online describes the control plane, not application reachability.
	for _, ip := range found.TailscaleIPs {
		if netip.MustParsePrefix("100.64.0.0/10").Contains(ip) || netip.MustParsePrefix("fd7a:115c:a1e0::/48").Contains(ip) {
			return ip, nil
		}
	}
	if len(found.TailscaleIPs) == 0 {
		return refuse("paired_peer_map_pending", true, nil)
	}
	return refuse("paired_address_invalid", false, nil)
}

// Keep Start nonblocking so onboarding can still read Status and request Login.
// Only safe local status reads are repeated; HTTP requests are never replayed here.
func (c *Client) readyTarget(parent context.Context, lc *local.Client, host, identity string) (*ipnstate.Status, netip.Addr, error) {
	if !peerHost.MatchString(host) || !sessionIdentity.MatchString(identity) {
		return nil, netip.Addr{}, readinessError("paired_target_invalid", false, nil)
	}
	ctx, cancel := context.WithTimeout(parent, readinessTimeout)
	defer cancel()
	var last error
	for {
		if err := ctx.Err(); err != nil {
			if errors.Is(err, context.Canceled) {
				return nil, netip.Addr{}, readinessError("bridge_operation_cancelled", false, err)
			}
			if previous, ok := last.(*readinessFailure); ok {
				failure := *previous
				failure.cause = errors.Join(previous.cause, err)
				return nil, netip.Addr{}, &failure
			}
			return nil, netip.Addr{}, readinessError("bridge_readiness_timeout", true, err)
		}
		status, statusErr := lc.Status(ctx)
		if ctx.Err() != nil {
			continue
		}
		if statusErr != nil {
			last = readinessError("bridge_status_unavailable", true, nil)
		} else {
			ip, err := target(status, host, identity)
			if err == nil {
				return status, ip, nil
			}
			last = err
			failure := err.(*readinessFailure)
			c.mu.Lock()
			hasAuthKey := c.node != nil && c.node.AuthKey != ""
			c.mu.Unlock()
			initialEnrollment := (failure.Code == "bridge_enrollment_required" || failure.Code == "bridge_device_approval_required") && status != nil && status.AuthURL == "" &&
				(status.Self == nil || status.Self.ID == "" || status.Self.UserID <= 0) &&
				(status.HaveNodeKey || hasAuthKey) &&
				(status.BackendState == "NeedsLogin" || status.BackendState == "NeedsMachineAuth")
			if !failure.Retryable && !initialEnrollment {
				return nil, netip.Addr{}, err
			}
		}
		timer := time.NewTimer(readinessPoll)
		select {
		case <-ctx.Done():
			timer.Stop()
		case <-timer.C:
		}
	}
}

func (c *Client) dialTarget(ctx context.Context, network, address string) (net.Conn, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if c.dial != nil {
		return c.dial(ctx, network, address)
	}
	c.mu.Lock()
	node := c.node
	blocked := c.closed || c.networkDisabled || ctx.Err() != nil
	c.mu.Unlock()
	if blocked || node == nil {
		return nil, readinessError("bridge_network_unavailable", true, ctx.Err())
	}
	return node.Dial(ctx, network, address)
}

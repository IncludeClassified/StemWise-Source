package main

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/types/key"
)

func enrollmentStatus() *ipnstate.Status {
	return &ipnstate.Status{BackendState: "Running", Self: &ipnstate.PeerStatus{ID: "nPc", UserID: 1, DNSName: "pc.tailcheck.ts.net."},
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{key.NewNode().Public(): {ID: "nPi", UserID: 1}}}
}

func enrollmentClient(t *testing.T, handler http.HandlerFunc) *local.Client {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" || r.URL.Path != "/localapi/v0/status" || r.Header.Get("Authorization") != "" {
			t.Error("Unexpected mutation or credentials in the isolated native-status fixture")
			w.WriteHeader(400)
			return
		}
		handler(w, r)
	}))
	t.Cleanup(server.Close)
	return &local.Client{OmitAuth: true, Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "tcp", server.Listener.Addr().String())
	}}
}

func TestWaitForPCEnrollmentRecoversStatusError(t *testing.T) {
	var requests atomic.Int32
	client := enrollmentClient(t, func(w http.ResponseWriter, r *http.Request) {
		s := enrollmentStatus()
		switch requests.Add(1) {
		case 1:
			w.WriteHeader(503)
			return
		case 2:
			s.BackendState, s.Self, s.Peer = "Starting", nil, nil
		case 3:
			s.Peer = nil
		}
		json.NewEncoder(w).Encode(s)
	})
	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	defer cancel()
	s, err := waitForPCEnrollment(ctx, client, "nPi", "")
	if err != nil || s == nil || s.Self.ID != "nPc" || len(s.Peer) != 1 || requests.Load() != 4 {
		t.Fatalf("Startup failed to wait through native error, starting state and missing peer: status=%v requests=%d error=%v", s != nil, requests.Load(), err)
	}
}

func TestWaitForPCEnrollmentRejectsUnsafePeer(t *testing.T) {
	for _, scenario := range []string{"wrong-owner", "expired", "self", "duplicate", "invalid-login-url"} {
		t.Run(scenario, func(t *testing.T) {
			s := enrollmentStatus()
			for _, p := range s.Peer {
				switch scenario {
				case "wrong-owner":
					p.UserID = 2
				case "expired":
					p.Expired = true
				case "self":
					s.Self.ID = p.ID
				case "duplicate":
					s.Peer[key.NewNode().Public()] = &ipnstate.PeerStatus{ID: "nPi", UserID: 1}
				case "invalid-login-url":
					s.BackendState, s.AuthURL = "Starting", "https://not-authorized.example/a/fixture"
				}
				break
			}
			client := enrollmentClient(t, func(w http.ResponseWriter, r *http.Request) { json.NewEncoder(w).Encode(s) })
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			if result, err := waitForPCEnrollment(ctx, client, "nPi", ""); err == nil || result != nil || errors.Is(err, context.DeadlineExceeded) {
				t.Fatal("Unsafe pairing was accepted or postponed instead of rejected", scenario, err)
			}
		})
	}
}

func TestWaitForPCEnrollmentDeadlineAndCancellation(t *testing.T) {
	for _, scenario := range []string{"missing-peer", "expired-self", "native-error", "blocked-request", "already-cancelled"} {
		t.Run(scenario, func(t *testing.T) {
			var requests atomic.Int32
			client := enrollmentClient(t, func(w http.ResponseWriter, r *http.Request) {
				requests.Add(1)
				if scenario == "native-error" {
					w.WriteHeader(503)
					return
				}
				if scenario == "blocked-request" {
					<-r.Context().Done()
					return
				}
				s := enrollmentStatus()
				if scenario == "expired-self" {
					s.Self.Expired = true
				} else {
					s.Peer = nil
				}
				json.NewEncoder(w).Encode(s)
			})
			ctx, cancel := context.WithTimeout(context.Background(), 80*time.Millisecond)
			defer cancel()
			wanted := context.DeadlineExceeded
			if scenario == "already-cancelled" {
				cancel()
				wanted = context.Canceled
			}
			result, err := waitForPCEnrollment(ctx, client, "nPi", "")
			if result != nil || !errors.Is(err, wanted) {
				t.Fatal("Unconfirmed startup escaped its deadline/cancellation", scenario, err)
			}
			if (scenario == "already-cancelled" && requests.Load() != 0) || requests.Load() > 1 {
				t.Fatal("Unexpected status request after cancellation/deadline", requests.Load())
			}
		})
	}
}

func TestNormalEnrollmentUsesPiWithAppNodesAndNoUsableTablet(t *testing.T) {
	for _, tablet := range []string{"missing", "expired", "different-account"} {
		t.Run(tablet, func(t *testing.T) {
			s := enrollmentStatus()
			s.Peer[key.NewNode().Public()] = &ipnstate.PeerStatus{ID: "nStemWise", UserID: 1}
			s.Peer[key.NewNode().Public()] = &ipnstate.PeerStatus{ID: "nStudywell", UserID: 1}
			if tablet != "missing" {
				old := &ipnstate.PeerStatus{ID: "nOldTablet", UserID: 1, Expired: tablet == "expired"}
				if tablet == "different-account" {
					old.UserID = 2
				}
				s.Peer[key.NewNode().Public()] = old
			}
			client := enrollmentClient(t, func(w http.ResponseWriter, r *http.Request) { json.NewEncoder(w).Encode(s) })
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			got, err := waitForPCEnrollment(ctx, client, "nPi", "")
			if err != nil || got == nil || got.Self.ID != "nPc" {
				t.Fatal("Production Pi pairing depended on unrelated app/tablet nodes", err)
			}
		})
	}
}

func TestExplicitRoundtripStillRequiresItsExactAndroidPeer(t *testing.T) {
	for _, variant := range []string{"present", "missing", "expired", "wrong-owner", "duplicate"} {
		t.Run(variant, func(t *testing.T) {
			s := enrollmentStatus()
			if variant != "missing" {
				android := &ipnstate.PeerStatus{ID: "nAndroid", UserID: 1, Expired: variant == "expired"}
				if variant == "wrong-owner" {
					android.UserID = 2
				}
				s.Peer[key.NewNode().Public()] = android
				if variant == "duplicate" {
					s.Peer[key.NewNode().Public()] = &ipnstate.PeerStatus{ID: "nAndroid", UserID: 1}
				}
			}
			client := enrollmentClient(t, func(w http.ResponseWriter, r *http.Request) { json.NewEncoder(w).Encode(s) })
			ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
			defer cancel()
			got, err := waitForPCEnrollment(ctx, client, "nPi", "nAndroid")
			if variant == "present" {
				if err != nil || got == nil {
					t.Fatal("Explicit roundtrip lost its valid Android peer", err)
				}
			} else if err == nil || got != nil || (variant == "missing" && !errors.Is(err, context.DeadlineExceeded)) {
				t.Fatal("Explicit roundtrip bypassed Android pairing", variant, err)
			}
		})
	}
}

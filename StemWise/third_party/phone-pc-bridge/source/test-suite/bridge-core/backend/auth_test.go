package backend

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"phonepcbridge/pc"
	"phonepcbridge/protocol"
)

type authNativeFixture struct {
	*nativeFixture
	status       json.RawMessage
	refuseSend   bool
	creations    int
	sendAttempts int
}

func (n *authNativeFixture) Call(ctx context.Context, r protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if r.Operation == "auth" {
		return protocol.AdapterReply{State: "ok", Result: n.status}, nil
	}
	if r.Operation == "create" {
		n.creations++
	}
	if r.Operation == "send" {
		n.sendAttempts++
		if n.refuseSend {
			return protocol.AdapterReply{State: "rejected", Code: "native_auth_change_pending"}, nil
		}
	}
	return n.nativeFixture.Call(ctx, r)
}

func authFixtureStatus(state, code string) json.RawMessage {
	return mustJSON(pc.AuthStatus{State: state, Code: code, ObservedAt: time.Now().UnixMilli()})
}

func TestAuthenticationBlocksExecutionBeforeCreationAndRecoversOriginalQueuedJob(t *testing.T) {
	for _, choice := range []struct{ state, code string }{
		{"auth_required", "native_auth_required"}, {"blocked", "native_auth_change_pending"},
		{"unavailable", "native_auth_unavailable"}, {"unavailable", "native_auth_source_unverified"},
	} {
		t.Run(choice.code, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			base.online = true
			n := &authNativeFixture{nativeFixture: base, status: authFixtureStatus(choice.state, choice.code)}
			s.native = n
			registerFixture(t, call, "nAlice")
			input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Approved job", Text: "immutable owner context", Files: []protocol.File{}}
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			for range 2 {
				if err := s.workPass(); err != nil {
					t.Fatal(err)
				}
			}
			state, err := s.loadApp(s.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			job := state.Jobs[input.RequestID]
			if job.State != "queued" || job.Code != choice.code || job.ThreadID != "" || job.TurnID != "" || n.creations != 0 || n.sendAttempts != 0 || base.calls != 0 || job.Fingerprint != appHash(input) || len(job.Log) == 0 {
				t.Fatal("Authentication outage started or replaced native work", job, n.creations, n.sendAttempts)
			}
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 200 {
				t.Fatal("Auth recovery changed the original admission", w.Body.String())
			}
			n.status = authFixtureStatus("ready", "native_auth_ready")
			if err := s.workPass(); err != nil {
				t.Fatal(err)
			}
			if err := s.workPass(); err != nil || n.creations != 1 || n.sendAttempts != 1 || base.calls != 1 {
				t.Fatal("Restored authentication did not execute exactly the original job", err, n.creations, n.sendAttempts, base.calls)
			}
		})
	}
}

func TestExactPreSendAuthRefusalKeepsOriginalJobQueued(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	n := &authNativeFixture{nativeFixture: base, status: authFixtureStatus("ready", "native_auth_ready"), refuseSend: true}
	s.native = n
	registerFixture(t, call, "nAlice")
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Login race", Text: "same approved text", Files: []protocol.File{}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	j := state.Jobs[input.RequestID]
	if j.State != "queued" || j.Code != "native_auth_change_pending" || j.TurnID != "" || n.creations != 1 || base.calls != 0 {
		t.Fatal("Definitive no-send auth refusal became uncertain or terminal", j)
	}
	n.refuseSend = false
	if err := s.workPass(); err != nil || n.creations != 1 || base.calls != 1 {
		t.Fatal("Queued original job was replaced or replayed", err, n.creations, base.calls)
	}
}

func TestConnectionAuthenticationIsIndependentStrictAndContainsNoAccountData(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	n := &authNativeFixture{nativeFixture: base, status: authFixtureStatus("auth_required", "native_auth_required")}
	s.native = n
	w := call("GET", "/v2/connection", nil, "nAlice")
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"pcState":"reachable"`) || !strings.Contains(w.Body.String(), `"codexAuth":{"state":"auth_required"`) {
		t.Fatal("Provider authentication was confused with transport health", w.Body.String())
	}
	for _, status := range []string{
		`{"state":"ready","code":"native_auth_required","observedAt":1,"validForMs":0}`,
		`{"state":"ready","code":"native_auth_ready","observedAt":0,"validForMs":0}`,
		`{"state":"ready","code":"native_auth_ready","observedAt":1,"validForMs":20000}`,
		`{"state":"ready","code":"native_auth_ready","observedAt":1,"validForMs":0,"email":"private-account"}`,
	} {
		n.status = json.RawMessage(status)
		w = call("GET", "/v2/connection", nil, "nAlice")
		if w.Code != 200 || !strings.Contains(w.Body.String(), `"code":"native_auth_unavailable"`) || strings.Contains(w.Body.String(), "private-account") {
			t.Fatal("Invalid native authentication became trusted or leaked data", w.Body.String())
		}
	}
	if n.creations != 0 || n.sendAttempts != 0 {
		t.Fatal("Status observation started native work")
	}
}

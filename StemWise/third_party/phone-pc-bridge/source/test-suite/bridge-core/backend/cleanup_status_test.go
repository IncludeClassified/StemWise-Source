package backend

import (
	"context"
	"encoding/json"
	"phonepcbridge/protocol"
	"strings"
	"testing"
	"time"
)

type cleanupNativeFixture struct {
	*nativeFixture
	status json.RawMessage
}

func (n *cleanupNativeFixture) Call(ctx context.Context, r protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if r.Operation == "cleanup" {
		return protocol.AdapterReply{State: "ok", Result: n.status}, nil
	}
	return n.nativeFixture.Call(ctx, r)
}
func TestConnectionCleanupStatusIsIndependentAndStrictlyProjected(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	n := &cleanupNativeFixture{nativeFixture: base}
	s.native = n
	n.status = mustJSON(map[string]any{"state": "current", "code": "", "pendingRequests": 0, "observedAt": time.Now().UnixMilli()})
	w := call("GET", "/v2/connection", nil, "nAlice")
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"state":"current"`) {
		t.Fatal("Current cleanup not exposed", w.Body.String())
	}
	n.status = json.RawMessage(`{"state":"checking","code":"","pendingRequests":0,"observedAt":0}`)
	w = call("GET", "/v2/connection", nil, "nAlice")
	if !strings.Contains(w.Body.String(), `"pendingRequests":null`) || !strings.Contains(w.Body.String(), `"observedAt":null`) {
		t.Fatal("Unobserved cleanup was presented as an observed zero", w.Body.String())
	}
	n.status = mustJSON(map[string]any{"state": "unconfirmed", "code": "creation_receipt_unconfirmed", "pendingRequests": 2, "observedAt": time.Now().UnixMilli()})
	w = call("GET", "/v2/connection", nil, "nAlice")
	if !strings.Contains(w.Body.String(), `"pendingRequests":2`) || !strings.Contains(w.Body.String(), `"code":"creation_receipt_unconfirmed"`) {
		t.Fatal("Actual cleanup failure was hidden", w.Body.String())
	}
	for _, raw := range []string{`{"state":"current","code":"","pendingRequests":-1,"observedAt":1}`, `{"state":"current","code":"failure","pendingRequests":0,"observedAt":1}`, `{"state":"current","code":"","pendingRequests":0,"observedAt":0}`, `{"state":"current","code":"","pendingRequests":0,"observedAt":1,"conversation":"private_native_content"}`} {
		n.status = json.RawMessage(raw)
		w = call("GET", "/v2/connection", nil, "nAlice")
		if w.Code != 200 || strings.Contains(w.Body.String(), "private_native_content") || !strings.Contains(w.Body.String(), `"code":"pc_cleanup_status_unavailable"`) || !strings.Contains(w.Body.String(), `"pcState":"reachable"`) {
			t.Fatal("Unverified cleanup became success or changed independent reachability", w.Body.String())
		}
	}
	if n.calls != 0 {
		t.Fatal("Cleanup observation submitted native work")
	}
}

func TestConnectionHandoffDiagnosticsExcludeOtherAppOwnership(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	app, job := randomToken(), randomToken()
	n := &cleanupNativeFixture{nativeFixture: base, status: mustJSON(map[string]any{
		"state": "current", "code": "", "pendingRequests": 0, "observedAt": time.Now().UnixMilli(),
		"unresolvedHandoffs": 2, "handoffCode": "handoff_owner_unconfirmed",
		"oldestHandoff": map[string]any{"createdAt": 1234, "peer": "other-owner", "appId": app, "jobId": job, "threadId": "other-chat"},
	})}
	s.native = n
	w := call("GET", "/v2/connection", nil, "nAlice")
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"unresolvedHandoffs":2`) || !strings.Contains(w.Body.String(), `"oldestHandoffAt":1234`) {
		t.Fatal("Bounded unresolved diagnostics missing", w.Body.String())
	}
	for _, private := range []string{app, job, "other-owner", "other-chat", "oldestHandoff\""} {
		if strings.Contains(w.Body.String(), private) {
			t.Fatal("Another app's handoff ownership leaked", w.Body.String())
		}
	}
}

package backend

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"phonepcbridge/protocol"
)

func TestRemoteAdapterHTTPFailureKeepsOnlyCorrelatedMachineFacts(t *testing.T) {
	for _, kind := range []string{"valid", "nonce", "identity", "protocol", "code", "prose", "success"} {
		t.Run(kind, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				var request protocol.AdapterRequest
				if r.Method != "POST" || r.URL.Path != "/internal/v1/codex" || json.NewDecoder(r.Body).Decode(&request) != nil || !token.MatchString(request.Nonce) {
					t.Error("Unexpected actual HTTP adapter request")
					w.WriteHeader(400)
					return
				}
				reply := protocol.AdapterReply{Protocol: protocol.Adapter, PCIdentity: "nPc", Nonce: request.Nonce, State: "rejected", Code: "codex_unavailable", Failure: &protocol.AdapterFailure{Message: "password=do_not_copy_raw_body"}}
				switch kind {
				case "nonce":
					reply.Nonce = randomToken()
				case "identity":
					reply.PCIdentity = "nOther"
				case "protocol":
					reply.Protocol = "other"
				case "code":
					reply.Code = "hello password=not_machine_code"
				case "success":
					reply.State, reply.Failure = "ok", nil
				}
				w.WriteHeader(http.StatusServiceUnavailable)
				if kind == "prose" {
					_, _ = w.Write([]byte("hello password=do_not_copy_raw_body"))
				} else {
					_ = json.NewEncoder(w).Encode(reply)
				}
			}))
			defer server.Close()
			r := &Remote{PCIdentity: "nPc", PiIdentity: "nPi", Wire: HTTPWire(server.Client(), server.URL)}
			reply, err := r.Call(context.Background(), protocol.AdapterRequest{Operation: "send"})
			if err == nil || reply.State != "" || reply.Code != "" || reply.Failure != nil || strings.Contains(err.Error(), "password") || strings.Contains(err.Error(), "do_not_copy") {
				t.Fatal("HTTP failure became a native outcome or leaked raw body")
			}
			if kind == "valid" {
				if err.Error() != "pc_adapter_rejected http_status=503 state=rejected code=codex_unavailable" {
					t.Fatal("Correlated HTTP failure lost machine status/code", err)
				}
			} else if err.Error() != "Uncorrelated PC adapter response" {
				t.Fatal("Uncorrelated or contradictory HTTP failure supplied trusted facts", kind)
			}
		})
	}
}

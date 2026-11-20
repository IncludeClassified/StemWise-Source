package backend

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"phonepcbridge/pc"
)

func TestRemotePowerUsesCorrelatedStrictHTTPReplies(t *testing.T) {
	for _, operation := range []string{"status", "shutdown"} {
		for _, variant := range []string{"valid", "rejected", "unknown", "identity", "nonce", "protocol", "http", "missing_observation", "bad_observation", "wrong_cycle", "false_acceptance", "extra_field", "capability_type"} {
			t.Run(operation+"/"+variant, func(t *testing.T) {
				cycle := strings.Repeat("c", 32)
				transition := strings.Repeat("a", 64)
				server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
					var input pc.PowerRequest
					if request.Method != "POST" || request.URL.Path != "/internal/v1/power" || request.Header.Get("Content-Type") != "application/json" || json.NewDecoder(request.Body).Decode(&input) != nil || !token.MatchString(input.Nonce) || input.Nonce == "caller_nonce" || input.Operation != operation {
						t.Error("Unexpected production power request")
						w.WriteHeader(400)
						return
					}
					if operation == "shutdown" && (input.CycleID != cycle || input.TransitionID != transition) {
						t.Error("Shutdown ownership changed in transit")
					}
					result := pc.PowerReply{Protocol: "phone-pc-bridge-power-v1", PCIdentity: "nPc", Nonce: input.Nonce, State: "ok", Code: "power_status_ready", Observation: &pc.PowerObservation{BootID: strings.Repeat("b", 32), TransitionID: transition, UptimeMS: 2000}}
					if operation == "status" && variant == "valid" {
						available := true
						result.ShutdownPrivilegeAvailable = &available
					}
					if operation == "shutdown" {
						result.Code, result.CycleID, result.ShutdownState = "power_shutdown_accepted", cycle, "accepted"
					}
					switch variant {
					case "rejected", "unknown":
						result.State, result.Code, result.Observation, result.ShutdownState = variant, "power_busy", nil, ""
					case "identity":
						result.PCIdentity = "nOther"
					case "nonce":
						result.Nonce = randomToken()
					case "protocol":
						result.Protocol = "other"
					case "http":
						w.WriteHeader(503)
					case "missing_observation":
						result.Observation = nil
						if operation == "shutdown" {
							result.ShutdownState = "unconfirmed"
						}
					case "bad_observation":
						result.Observation.UptimeMS = -1
					case "wrong_cycle":
						result.CycleID = strings.Repeat("d", 32)
					case "false_acceptance":
						result.State, result.Code, result.ShutdownState = "unknown", "power_shutdown_unconfirmed", "accepted"
					}
					if variant == "extra_field" || variant == "capability_type" {
						raw, _ := json.Marshal(result)
						extra := `,"unexpected":true}`
						if variant == "capability_type" {
							extra = `,"shutdownPrivilegeAvailable":"yes"}`
						}
						_, _ = w.Write(append(raw[:len(raw)-1], []byte(extra)...))
						return
					}
					_ = json.NewEncoder(w).Encode(result)
				}))
				defer server.Close()
				remote := &Remote{PCIdentity: "nPc", PiIdentity: "nPi", Wire: HTTPWire(server.Client(), server.URL)}
				input := pc.PowerRequest{Nonce: "caller_nonce", Operation: operation}
				if operation == "shutdown" {
					input.CycleID, input.TransitionID = cycle, transition
				}
				result, err := remote.Power(context.Background(), input)
				if variant == "valid" || variant == "rejected" || variant == "unknown" {
					if err != nil || result.State == "" {
						t.Fatal("Lost a correlated machine outcome", result, err)
					}
					if variant != "valid" && result.State == "ok" {
						t.Fatal("Power refusal became success", result)
					}
					if operation == "status" && variant == "valid" && (result.ShutdownPrivilegeAvailable == nil || !*result.ShutdownPrivilegeAvailable) {
						t.Fatal("Actual task privilege observation was lost", result)
					}
				} else if err == nil || result.State != "" {
					t.Fatal("Malformed or contradictory power response trusted", result, err)
				}
			})
		}
	}
}

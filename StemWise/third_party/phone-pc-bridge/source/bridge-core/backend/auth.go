package backend

import (
	"context"
	"time"

	"phonepcbridge/pc"
	"phonepcbridge/protocol"
)

func validAuthStatus(status pc.AuthStatus) bool {
	if status.ObservedAt <= 0 || status.ValidForMs != 0 {
		return false
	}
	switch status.Code {
	case "native_auth_ready":
		return status.State == "ready"
	case "native_auth_required":
		return status.State == "auth_required"
	case "native_auth_change_pending":
		return status.State == "blocked"
	case "native_auth_unavailable", "native_auth_source_unverified":
		return status.State == "unavailable"
	}
	return false
}

func unavailableAuth() pc.AuthStatus {
	return pc.AuthStatus{State: "unavailable", Code: "native_auth_unavailable", ObservedAt: time.Now().UnixMilli()}
}

func (s *Service) nativeAuth(ctx context.Context) pc.AuthStatus {
	ctx, cancel := context.WithTimeout(ctx, 35*time.Second)
	defer cancel()
	outcome, err := s.native.Call(ctx, protocol.AdapterRequest{Operation: "auth"})
	var status pc.AuthStatus
	if err != nil || outcome.State != "ok" || outcome.Code != "" || outcome.Failure != nil || protocol.DecodeStrict(outcome.Result, &status) != nil || !validAuthStatus(status) {
		return unavailableAuth()
	}
	return status
}

// Only an authenticated, conclusive pre-mutation refusal permits another send.
func authRefusal(reply protocol.AdapterReply, err error) string {
	if err != nil || reply.State != "rejected" || len(reply.Result) != 0 || reply.Failure != nil {
		return ""
	}
	switch reply.Code {
	case "native_auth_required", "native_auth_change_pending", "native_auth_unavailable", "native_auth_source_unverified":
		return reply.Code
	}
	return ""
}

func authJobLog(code string) appLogEntry {
	retry := true
	return appLogEntry{Source: "bridge", Error: appDiagnostic{
		Code: code, Operation: "verify_native_authentication", Summary: "Native execution is paused until the Bridge runtime verifies the current login",
		Cause: code, Retryable: &retry, Context: map[string]string{"component": "pc_native_auth", "native_mutation": "not_attempted"},
		Expected: "authenticated_current_runtime", Actual: code,
		SuggestedFix: []string{"read_connection_codex_auth_status", "complete_owner_sign_in_if_required", "bridge_rechecks_authentication_without_replaying_accepted_turns"},
	}}
}

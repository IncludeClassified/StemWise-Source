package codex

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"path/filepath"
)

// AuthError deliberately excludes native response text and account/configuration contents.
type AuthError struct {
	Operation string
	Code      string
	RPCCode   *int
	cause     error
}

func (e *AuthError) Error() string { return e.Code }
func (e *AuthError) Unwrap() error { return e.cause }

func authFailure(operation, code string, err error) error {
	failure := &AuthError{Operation: operation, Code: code}
	var rpc *RPCError
	if errors.As(err, &rpc) {
		value := rpc.Code
		failure.RPCCode = &value
	}
	if errors.Is(err, context.Canceled) {
		failure.cause = context.Canceled
	}
	if errors.Is(err, context.DeadlineExceeded) {
		failure.cause = context.DeadlineExceeded
	}
	return failure
}

type AccountState struct {
	Type               string
	RequiresOpenAIAuth bool
}

// ReadAccount reads only the native account kind; email, identifiers and credentials never escape.
// refresh is supported for managed ChatGPT authentication, not external-token login.
func (c *Connection) ReadAccount(ctx context.Context, refresh bool) (AccountState, error) {
	raw, err := c.request(ctx, "account/read", map[string]bool{"refreshToken": refresh})
	if err != nil {
		return AccountState{}, authFailure("account/read", "native_auth_unavailable", err)
	}
	var reply struct {
		Account  json.RawMessage `json:"account"`
		Requires *bool           `json:"requiresOpenaiAuth"`
	}
	if json.Unmarshal(raw, &reply) != nil || reply.Requires == nil || len(reply.Account) == 0 {
		return AccountState{}, authFailure("account/read", "native_auth_unavailable", nil)
	}
	state := AccountState{RequiresOpenAIAuth: *reply.Requires}
	if bytes.Equal(bytes.TrimSpace(reply.Account), []byte("null")) {
		return state, nil
	}
	var account struct {
		Type string `json:"type"`
	}
	if json.Unmarshal(reply.Account, &account) != nil {
		return AccountState{}, authFailure("account/read", "native_auth_unavailable", nil)
	}
	switch account.Type {
	case "apiKey", "chatgpt", "amazonBedrock":
		state.Type = account.Type
	default:
		return AccountState{}, authFailure("account/read", "native_auth_unavailable", nil)
	}
	return state, nil
}

type AuthConfig struct {
	CredentialsStore  string
	Provider          string
	ForcedLoginMethod string
	policy            [32]byte
}

// ReadAuthConfig uses the native effective configuration rather than parsing one config layer.
func (c *Connection) ReadAuthConfig(ctx context.Context, cwd string) (AuthConfig, error) {
	params := map[string]any{"includeLayers": false}
	if cwd != "" {
		if !filepath.IsAbs(cwd) {
			return AuthConfig{}, authFailure("config/read", "native_auth_source_unavailable", nil)
		}
		params["cwd"] = cwd
	}
	raw, err := c.request(ctx, "config/read", params)
	if err != nil {
		return AuthConfig{}, authFailure("config/read", "native_auth_source_unavailable", err)
	}
	var reply struct {
		Config *struct {
			Store       *string `json:"cli_auth_credentials_store"`
			Provider    *string `json:"model_provider"`
			ForcedLogin *string `json:"forced_login_method"`
		} `json:"config"`
	}
	if json.Unmarshal(raw, &reply) != nil || reply.Config == nil || reply.Config.Store == nil {
		return AuthConfig{}, authFailure("config/read", "native_auth_source_unavailable", nil)
	}
	result := AuthConfig{CredentialsStore: *reply.Config.Store, Provider: "openai"}
	switch result.CredentialsStore {
	case "file", "keyring", "auto", "ephemeral":
	default:
		return AuthConfig{}, authFailure("config/read", "native_auth_source_unavailable", nil)
	}
	if reply.Config.Provider != nil {
		provider := *reply.Config.Provider
		if validID(provider) != nil {
			return AuthConfig{}, authFailure("config/read", "native_auth_source_unavailable", nil)
		}
		result.Provider = provider
	}
	if reply.Config.ForcedLogin != nil {
		if *reply.Config.ForcedLogin != "api" && *reply.Config.ForcedLogin != "chatgpt" {
			return AuthConfig{}, authFailure("config/read", "native_auth_source_unavailable", nil)
		}
		result.ForcedLoginMethod = *reply.Config.ForcedLogin
	}
	var effective struct {
		Config map[string]json.RawMessage `json:"config"`
	}
	if json.Unmarshal(raw, &effective) != nil {
		return AuthConfig{}, authFailure("config/read", "native_auth_source_unavailable", nil)
	}
	policy := map[string]json.RawMessage{}
	for _, key := range []string{"forced_chatgpt_workspace_id", "forced_chatgpt_account_id", "chatgpt_base_url", "openai_base_url"} {
		if value, exists := effective.Config[key]; exists {
			var text *string
			if json.Unmarshal(value, &text) != nil || (text != nil && len(*text) > 4096) {
				return AuthConfig{}, authFailure("config/read", "native_auth_source_unavailable", nil)
			}
			policy[key] = value
		}
	}
	if value, exists := effective.Config["model_providers"]; exists && !bytes.Equal(bytes.TrimSpace(value), []byte("null")) {
		var providers map[string]json.RawMessage
		if json.Unmarshal(value, &providers) != nil {
			return AuthConfig{}, authFailure("config/read", "native_auth_source_unavailable", nil)
		}
		if provider, exists := providers[result.Provider]; exists {
			policy["provider"] = provider
		}
	}
	canonical, _ := json.Marshal(policy)
	result.policy = sha256.Sum256(canonical)
	return result, nil
}

// CheckAccount performs an authenticated ChatGPT-provider read without starting model work.
// An exhausted quota is still an authenticated response; this does not claim inference will succeed.
func (c *Connection) CheckAccount(ctx context.Context) error {
	raw, err := c.request(ctx, "account/rateLimits/read", map[string]any{})
	if err != nil {
		return authFailure("account/rateLimits/read", "native_auth_unavailable", err)
	}
	var reply struct {
		Limits map[string]json.RawMessage `json:"rateLimits"`
	}
	if json.Unmarshal(raw, &reply) != nil || reply.Limits == nil {
		return authFailure("account/rateLimits/read", "native_auth_unavailable", nil)
	}
	return nil
}

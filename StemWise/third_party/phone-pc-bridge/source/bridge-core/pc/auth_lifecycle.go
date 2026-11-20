package pc

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"time"

	"phonepcbridge/codex"
	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

// AuthStatus is an observation, never reusable permission to submit native work.
type AuthStatus struct {
	State      string `json:"state"`
	Code       string `json:"code"`
	ObservedAt int64  `json:"observedAt"`
	ValidForMs int64  `json:"validForMs"`
}

type authSource struct {
	path   string
	digest [32]byte
	exists bool
}

type nativeAuthGateError struct{ status AuthStatus }

func (e *nativeAuthGateError) Error() string { return e.status.Code }

func authStatus(state, code string) AuthStatus {
	return AuthStatus{State: state, Code: code, ObservedAt: time.Now().UnixMilli()}
}

// Lock order is nativeGate -> exact creation lease -> creationMu. The cleanup
// worker acquires it after collection, before any exact native cleanup lease.
func (s *Service) claimNative(ctx context.Context) (func(), error) {
	s.mu.Lock()
	if s.nativeGate == nil {
		s.nativeGate = make(chan struct{}, 1)
	}
	gate := s.nativeGate
	s.mu.Unlock()
	select {
	case gate <- struct{}{}:
		if err := ctx.Err(); err != nil {
			<-gate
			return nil, err
		}
		return func() { <-gate }, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

// Bytes and their digest stay private to this process. Missing credentials are
// distinguishable from an unreadable source; no token is decoded or exported.
func readAuthSource() (authSource, error) {
	home := os.Getenv("CODEX_HOME")
	if home == "" {
		user, err := os.UserHomeDir()
		if err != nil {
			return authSource{}, errors.New("native_auth_source_unverified")
		}
		home = filepath.Join(user, ".codex")
	}
	if !filepath.IsAbs(home) {
		return authSource{}, errors.New("native_auth_source_unverified")
	}
	source := authSource{path: filepath.Join(home, "auth.json")}
	info, err := os.Lstat(source.path)
	if errors.Is(err, os.ErrNotExist) {
		return source, nil
	}
	if err != nil || !info.Mode().IsRegular() || info.Size() > 4<<20 {
		return authSource{}, errors.New("native_auth_source_unverified")
	}
	f, err := os.Open(source.path)
	if err != nil {
		return authSource{}, errors.New("native_auth_source_unverified")
	}
	defer f.Close()
	current, err := f.Stat()
	if err != nil || !os.SameFile(info, current) {
		return authSource{}, errors.New("native_auth_source_unverified")
	}
	hash := sha256.New()
	n, err := io.Copy(hash, io.LimitReader(f, (4<<20)+1))
	if err != nil || n > 4<<20 {
		return authSource{}, errors.New("native_auth_source_unverified")
	}
	end, err := os.Lstat(source.path)
	if err != nil || !os.SameFile(info, end) || end.Size() != info.Size() || !end.ModTime().Equal(info.ModTime()) {
		return authSource{}, errors.New("native_auth_source_unverified")
	}
	copy(source.digest[:], hash.Sum(nil))
	source.exists = true
	return source, nil
}

func authFailure(err error) AuthStatus {
	var native *codex.AuthError
	if errors.As(err, &native) && native.Code == "native_auth_required" {
		return authStatus("auth_required", "native_auth_required")
	}
	if errors.As(err, &native) && native.Code == "native_auth_source_unavailable" {
		return authStatus("unavailable", "native_auth_source_unverified")
	}
	return authStatus("unavailable", "native_auth_unavailable")
}

// Caller owns nativeGate. Reads and exact cleanup remain available while this
// gate refuses new work, so active work can reach a provable terminal state.
func (s *Service) preflightAuth(ctx context.Context, p *peer, c *codex.Connection) (*codex.Connection, AuthStatus) {
	if s.cfg.CodexSocket != "" {
		return c, authStatus("unavailable", "native_auth_source_unverified")
	}
	for attempt := 0; attempt < 2; attempt++ {
		config, err := c.ReadAuthConfig(ctx, s.cfg.IntegrationDir)
		if err != nil {
			return c, authFailure(err)
		}
		if config.CredentialsStore != "file" || config.Provider != "openai" || config.ForcedLoginMethod != "" && config.ForcedLoginMethod != "chatgpt" {
			return c, authStatus("unavailable", "native_auth_source_unverified")
		}
		source, err := readAuthSource()
		if err != nil {
			return c, authStatus("unavailable", "native_auth_source_unverified")
		}
		if source != p.authSource || p.authKnown && config != p.authConfig {
			if !s.authOwnershipResolved(p.identity) {
				return c, authStatus("blocked", "native_auth_change_pending")
			}
			if err := c.CloseWhenIdle(ctx); err != nil {
				return c, authStatus("blocked", "native_auth_change_pending")
			}
			c, _, err = s.connection(ctx, p)
			if err != nil {
				return c, authFailure(err)
			}
			continue
		}
		account, err := c.ReadAccount(ctx, false)
		if err != nil {
			return c, authFailure(err)
		}
		if account.Type == "" && account.RequiresOpenAIAuth {
			return c, authStatus("auth_required", "native_auth_required")
		}
		if account.Type != "chatgpt" || !account.RequiresOpenAIAuth {
			return c, authStatus("unavailable", "native_auth_source_unverified")
		}
		if err := c.CheckAccount(ctx); err != nil {
			return c, authFailure(err)
		}
		after, err := readAuthSource()
		if err != nil {
			return c, authStatus("unavailable", "native_auth_source_unverified")
		}
		confirmed, err := c.ReadAuthConfig(ctx, s.cfg.IntegrationDir)
		if err != nil {
			return c, authFailure(err)
		}
		if after != source || confirmed != config {
			return c, authStatus("blocked", "native_auth_change_pending")
		}
		p.authConfig, p.authKnown = config, true
		return c, authStatus("ready", "native_auth_ready")
	}
	return c, authStatus("blocked", "native_auth_change_pending")
}

func (s *Service) checkAuthSource(ctx context.Context, p *peer, c *codex.Connection) error {
	config, err := c.ReadAuthConfig(ctx, s.cfg.IntegrationDir)
	if err != nil {
		return &nativeAuthGateError{authFailure(err)}
	}
	source, err := readAuthSource()
	if err != nil {
		return &nativeAuthGateError{authStatus("unavailable", "native_auth_source_unverified")}
	}
	if !p.authKnown || source != p.authSource || config != p.authConfig {
		return &nativeAuthGateError{authStatus("blocked", "native_auth_change_pending")}
	}
	return nil
}

type guardedCreation struct {
	*codex.Connection
	service *Service
	peer    *peer
}

func (n guardedCreation) StartThread(ctx context.Context, cwd, model, tier string) (json.RawMessage, error) {
	if err := n.service.checkAuthSource(ctx, n.peer, n.Connection); err != nil {
		return nil, &codex.RequestNotSent{Cause: err}
	}
	return n.Connection.StartThread(ctx, cwd, model, tier)
}

// Unknown reservations cannot be turned into permission to terminate a process.
// Native CloseWhenIdle separately verifies all loaded threads and terminals.
func (s *Service) authOwnershipResolved(peer string) bool {
	if s.creations == nil {
		return false
	}
	s.creationMu.Lock()
	defer s.creationMu.Unlock()
	records, unknown, err := s.creationRecords(peer)
	if err != nil || unknown {
		return false
	}
	results := map[string]bool{}
	for _, record := range records {
		if record.Name == "creation-result.json" {
			results[record.ID] = false
		}
	}
	for _, record := range records {
		if record.Name != "creation-deleted.json" {
			continue
		}
		_, raw, err := s.creations.Read(peer, record.ID)
		var closed deletedCreation
		if err != nil || protocol.DecodeStrict(raw, &closed) != nil || closed.Request == nil {
			return false
		}
		request := *closed.Request
		if !validCleanupOwner(request) || record.ID != creationID(peer, request.AppID, request.RequestID, "deleted") {
			return false
		}
		if _, err := s.readDeletedCreation(peer, request); err != nil {
			return false
		}
		resultID := creationID(peer, request.AppID, request.RequestID, "result")
		if _, exists := results[resultID]; exists {
			_, raw, err := s.creations.Read(peer, resultID)
			var result creationReceipt
			if err != nil || protocol.DecodeStrict(raw, &result) != nil || result.RequestHash != closed.RequestHash || result.ThreadID != closed.ThreadID {
				return false
			}
			results[resultID] = true
		}
	}
	for _, record := range records {
		if record.Name != "creation-request.json" {
			continue
		}
		_, raw, err := s.creations.Read(peer, record.ID)
		var request creationInput
		if err != nil || protocol.DecodeStrict(raw, &request) != nil || !validCleanupOwner(request) || record.ID != creationID(peer, request.AppID, request.RequestID, "request") {
			return false
		}
		resultID := creationID(peer, request.AppID, request.RequestID, "result")
		if _, err := s.readDeletedCreation(peer, request); err == nil {
			if _, exists := results[resultID]; exists {
				results[resultID] = true
			}
			continue
		} else if !errors.Is(err, files.ErrAbsent) {
			return false
		}
		f, raw, err := s.creations.Read(peer, creationID(peer, request.AppID, request.RequestID, "result"))
		var receipt creationReceipt
		if err != nil || f.Name != "creation-result.json" || protocol.DecodeStrict(raw, &receipt) != nil || receipt.RequestHash != digestJSON(request) || !nodeID.MatchString(receipt.ThreadID) {
			return false
		}
		results[resultID] = true
	}
	for _, matched := range results {
		if !matched {
			return false
		}
	}
	return true
}

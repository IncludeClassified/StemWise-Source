package backend

import (
	"errors"
	"io"
	"net/http"
	"os"
	"time"
)

// Receiving a body reserves no app or file ownership; reload before admission.
func (s *Service) receiveAppBody(w http.ResponseWriter, r *http.Request, p *peer, state **appState, invalid string, value any) bool {
	app := ""
	if *state != nil {
		app = (*state).ID
	}
	p.appMu.Unlock()
	err := func() error {
		defer p.appMu.Lock()
		if value != nil {
			return appBody(w, r, value)
		}
		body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1))
		if err == nil && len(body) != 0 {
			err = errors.New("empty_body_required")
		}
		return err
	}()
	if r.Context().Err() != nil {
		code := "app_request_cancelled"
		if invalid == "invalid_wake_request" {
			code = "wake_unconfirmed"
		}
		s.appError(w, 503, code)
		return false
	}
	if err != nil {
		s.appError(w, 400, invalid)
		return false
	}
	if s.ctx.Err() != nil {
		s.appError(w, 503, "bridge_stopping")
		return false
	}
	latest, err := s.loadApp(p)
	if app == "" && os.IsNotExist(err) {
		*state = nil
		return true
	}
	if os.IsNotExist(err) || (err == nil && latest.ID != app) {
		s.appError(w, 409, "app_registration_changed")
		return false
	}
	if err != nil {
		s.appStoreError(w, err)
		return false
	}
	if err := s.expireAppForUse(p, latest, time.Now()); err != nil {
		s.appError(w, 503, "retention_cleanup_unavailable")
		return false
	}
	*state = latest
	return true
}

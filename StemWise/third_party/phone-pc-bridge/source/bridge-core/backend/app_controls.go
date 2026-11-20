package backend

import (
	"context"
	"errors"
	"net/http"
	"os"
	"strings"
	"time"

	"phonepcbridge/protocol"
)

// These HTTP observations have no accepted background work to outlive their caller.
func (s *Service) appObservationContext(parent context.Context, timeout time.Duration) (context.Context, context.CancelFunc) {
	ctx, cancel := context.WithTimeout(parent, timeout)
	stop := context.AfterFunc(s.ctx, cancel)
	if s.ctx.Err() != nil {
		cancel()
	}
	return ctx, func() { stop(); cancel() }
}

func (s *Service) appModel(parent context.Context, model, effort, tier string) (string, error) {
	if model == "" && effort == "" && tier == "" {
		return "", nil
	}
	if model == "" {
		return "model_or_effort_unavailable", nil
	}
	ctx, cancel := context.WithTimeout(parent, 30*time.Second)
	defer cancel()
	cursor := ""
	seen := map[string]bool{}
	for page := 0; page < 16; page++ {
		raw, err := s.native.Models(ctx, cursor)
		if err != nil {
			return "", err
		}
		catalog, err := publicModelCatalog(raw, cursor)
		if err != nil {
			return "", err
		}
		for _, m := range catalog.Data {
			if m.Model == model {
				if effort != "" {
					if m.Efforts == nil {
						return "", errors.New("Native reasoning catalog unconfirmed")
					}
					found := false
					for _, e := range m.Efforts {
						found = found || e.Effort == effort
					}
					if !found {
						return "model_or_effort_unavailable", nil
					}
				}
				if tier != "" {
					if m.ServiceTiers == nil {
						return "", errors.New("Native service tier catalog unconfirmed")
					}
					for _, option := range m.ServiceTiers {
						if option.ID == tier {
							return "", nil
						}
					}
					return "service_tier_unavailable", nil
				}
				return "", nil
			}
		}
		if catalog.NextCursor == nil || *catalog.NextCursor == "" {
			return "model_or_effort_unavailable", nil
		}
		seen[cursor] = true
		cursor = *catalog.NextCursor
		if len(cursor) > 4096 || seen[cursor] {
			return "", errors.New("Native model cursor did not progress")
		}
	}
	return "", errors.New("Native model search incomplete")
}
func (s *Service) appControls(w http.ResponseWriter, r *http.Request, p *peer, state *appState) bool {
	if s.powerControls(w, r, p, state) {
		return true
	}
	if r.Method == "GET" && r.URL.Path == "/v2/codex/models" {
		cursor := r.Header.Get("X-Bridge-Cursor")
		if r.URL.Query().Has("cursor") {
			if cursor != "" {
				s.appError(w, 400, "invalid_cursor")
				return true
			}
			cursor = r.URL.Query().Get("cursor")
		}
		if len(cursor) > 4096 {
			s.appError(w, 400, "invalid_cursor")
			return true
		}
		ctx, cancel := s.appObservationContext(r.Context(), 30*time.Second)
		defer cancel()
		p.appMu.Unlock()
		catalog, err := func() (modelCatalogPage, error) {
			defer p.appMu.Lock()
			if err := ctx.Err(); err != nil {
				return modelCatalogPage{}, err
			}
			raw, err := s.native.Models(ctx, cursor)
			if err != nil {
				return modelCatalogPage{}, err
			}
			return publicModelCatalog(raw, cursor)
		}()
		if err != nil || ctx.Err() != nil {
			s.appError(w, 503, "model_catalog_unavailable")
			return true
		}
		s.appReply(w, 200, catalog)
		return true
	}
	if r.Method == "PUT" && r.URL.Path == "/v2/codex/settings" {
		var input struct {
			Model       string `json:"model"`
			Effort      string `json:"effort"`
			ServiceTier string `json:"serviceTier"`
		}
		if !s.receiveAppBody(w, r, p, &state, "invalid_model_settings", &input) {
			return true
		}
		if len(input.Model) > 128 || len(input.Effort) > 32 || !protocol.ValidServiceTier(input.ServiceTier) {
			s.appError(w, 400, "invalid_model_settings")
			return true
		}
		if input.ServiceTier != "" && input.Model == "" {
			s.appError(w, 400, "service_tier_model_required")
			return true
		}
		app := state.ID
		ctx, cancel := s.appObservationContext(r.Context(), 30*time.Second)
		defer cancel()
		p.appMu.Unlock()
		unavailable, err := func() (string, error) {
			defer p.appMu.Lock()
			if err := ctx.Err(); err != nil {
				return "", err
			}
			return s.appModel(ctx, input.Model, input.Effort, input.ServiceTier)
		}()
		if err != nil || ctx.Err() != nil {
			s.appError(w, 503, "model_catalog_unavailable")
			return true
		}
		if unavailable != "" {
			s.appError(w, 409, unavailable)
			return true
		}
		latest, err := s.loadApp(p)
		if os.IsNotExist(err) || (err == nil && latest.ID != app) {
			s.appError(w, 409, "app_registration_required")
			return true
		}
		if err != nil {
			s.appStoreError(w, err)
			return true
		}
		if err := s.expireAppForUse(p, latest, time.Now()); err != nil {
			s.appError(w, 503, "retention_cleanup_unavailable")
			return true
		}
		if ctx.Err() != nil {
			s.appError(w, 503, "model_catalog_unavailable")
			return true
		}
		state = latest
		state.Model = input.Model
		state.Effort = input.Effort
		state.ServiceTier = input.ServiceTier
		if err := s.saveApp(state); err != nil {
			s.appStoreError(w, err)
			return true
		}
		s.appReply(w, 200, appView(state))
		return true
	}
	if r.Method == "GET" && r.URL.Path == "/v2/prompts" {
		s.appReply(w, 200, state.Prompts)
		return true
	}
	parts := strings.Split(r.URL.Path, "/")
	if len(parts) == 5 && parts[1] == "v2" && parts[2] == "jobs" && token.MatchString(parts[3]) && parts[4] == "retire" && r.Method == "POST" {
		if !s.receiveAppBody(w, r, p, &state, "invalid_retirement_request", nil) {
			return true
		}
		s.retireAppRequest(w, p, state, parts[3], time.Now())
		return true
	}
	if len(parts) == 5 && parts[1] == "v2" && parts[2] == "jobs" && token.MatchString(parts[3]) && parts[4] == "delivery" && r.Method == "POST" {
		j := state.Jobs[parts[3]]
		if j == nil {
			s.appJobUnavailable(w, state, state.ID, parts[3])
		} else {
			s.acknowledgeDelivery(w, r, p, state, j)
		}
		return true
	}
	if len(parts) == 4 && parts[1] == "v2" && parts[2] == "jobs" && token.MatchString(parts[3]) && r.Method == "DELETE" {
		j := state.Jobs[parts[3]]
		if j == nil {
			if _, deleted := state.DeletedRequests[parts[3]]; deleted {
				s.appReply(w, 200, map[string]bool{"deleted": true})
				return true
			}
			s.appError(w, 404, "job_unavailable")
			return true
		}
		if !contains([]string{"completed", "failed", "interrupted", "rejected", "cancelled"}, j.State) {
			s.appError(w, 409, "job_not_removable")
			return true
		}
		var err error
		if state, err = s.authorizeJobDiscard(r.Context(), p, state, j); err != nil {
			s.appError(w, 503, "job_cleanup_authorization_unconfirmed")
			return true
		}
		j = state.Jobs[parts[3]]
		if state.DeletedRequests == nil {
			state.DeletedRequests = map[string]int64{}
		}
		state.DeletedRequests[j.ID] = time.Now().Add(appRetention).UnixMilli()
		if s.removeAppJobs(state, map[string]*appJob{j.ID: j}) != nil {
			s.appError(w, 503, "retention_cleanup_unavailable")
			return true
		}
		s.appReply(w, 200, map[string]bool{"deleted": true})
		return true
	}
	if len(parts) == 4 && parts[1] == "v2" && parts[2] == "prompts" && token.MatchString(parts[3]) {
		id := parts[3]
		if r.Method == "PUT" {
			var input appPrompt
			if !s.receiveAppBody(w, r, p, &state, "invalid_prompt", &input) {
				return true
			}
			if !appLabel(input.Title) || strings.TrimSpace(input.Text) == "" || len(input.Text)+len(input.Instructions) > 65536 {
				s.appError(w, 400, "invalid_prompt")
				return true
			}
			if state.Prompts == nil {
				state.Prompts = map[string]appPrompt{}
			}
			if _, exists := state.Prompts[id]; !exists && len(state.Prompts) >= 64 {
				s.appError(w, 409, "prompt_limit_reached")
				return true
			}
			state.Prompts[id] = input
			if err := s.saveAppInput(state); err != nil {
				s.appStoreError(w, err)
				return true
			}
			s.appReply(w, 200, input)
			return true
		}
		if r.Method == "DELETE" {
			delete(state.Prompts, id)
			if err := s.saveApp(state); err != nil {
				s.appStoreError(w, err)
				return true
			}
			s.appReply(w, 200, map[string]bool{"deleted": true})
			return true
		}
	}
	if len(parts) == 5 && parts[1] == "v2" && parts[2] == "jobs" && token.MatchString(parts[3]) && parts[4] == "cancel" && r.Method == "POST" {
		j := state.Jobs[parts[3]]
		if j == nil {
			s.appJobUnavailable(w, state, state.ID, parts[3])
			return true
		}
		if jobTerminal(j) {
			s.appReply(w, 200, appJobView(j))
			return true
		}
		if !contains([]string{"queued", "dispatching", "unknown", "submitted"}, j.State) {
			s.appError(w, 409, "job_not_interruptible")
			return true
		}
		if j.CancelRequested == 0 {
			j.CancelRequested = time.Now().UnixMilli()
			j.CancelReason = "user_request"
			j.Updated, j.Code = j.CancelRequested, "cancellation_pending"
		}
		cancelQueuedJob(j)
		if err := s.saveApp(state); err != nil {
			s.appStoreError(w, err)
			return true
		}
		app, id := state.ID, j.ID
		s.stopJobWork(p, app, id)
		s.notifyJobs()
		state, err := s.interruptCancelledJob(p, state, j)
		if os.IsNotExist(err) {
			s.appJobUnavailable(w, state, app, id)
			return true
		}
		if err != nil {
			s.appStoreError(w, err)
			return true
		}
		j = state.Jobs[id]
		if state.ID != app || j == nil {
			s.appJobUnavailable(w, state, app, id)
			return true
		}
		if jobTerminal(j) {
			s.appReply(w, 200, appJobView(j))
			return true
		}
		s.appReply(w, 202, appJobView(j))
		return true
	}
	return false
}

package backend

import (
	"net/http"
	"time"
)

func (s *Service) appJobUnavailable(w http.ResponseWriter, state *appState, app, id string) {
	if state != nil && state.ID == app && (state.DeletedRequests[id] > time.Now().UnixMilli() || state.Cleanup[id].Created > 0) {
		s.appError(w, http.StatusGone, "request_deleted")
		return
	}
	s.appError(w, http.StatusNotFound, "job_unavailable")
}

// appMu serializes this new admission barrier with submission; retained work always wins.
func (s *Service) retireAppRequest(w http.ResponseWriter, p *peer, state *appState, id string, now time.Time) {
	if job := state.Jobs[id]; job != nil {
		s.appReply(w, http.StatusOK, appJobView(job))
		return
	}
	if state.DeletedRequests[id] > now.UnixMilli() || state.Cleanup[id].Created > 0 {
		s.appError(w, http.StatusGone, "request_deleted")
		return
	}
	if state.DeletedRequests == nil {
		state.DeletedRequests = map[string]int64{}
	}
	state.DeletedRequests[id] = now.Add(appRetention).UnixMilli()
	if err := s.saveAppInput(state); err != nil {
		s.appStoreError(w, err)
		return
	}
	s.stopJobWork(p, state.ID, id)
	s.appError(w, http.StatusGone, "request_deleted")
}

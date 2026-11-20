package backend

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"runtime"

	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"phonepcbridge/files"
	"phonepcbridge/pc"
	"phonepcbridge/protocol"
)

const appProtocol = protocol.App
const AppProtocol = appProtocol

const maxAppRecordBytes = 16 << 20
const maxAppOwners = 128

var errAppCapacity = errors.New("app_capacity_reached")

type appInput struct {
	RequestID                string          `json:"requestId"`
	Title                    string          `json:"title"`
	Text                     string          `json:"text"`
	Instructions             string          `json:"instructions"`
	Files                    []protocol.File `json:"files"`
	CreatedAt                int64           `json:"createdAt"`
	Model                    string          `json:"model"`
	Effort                   string          `json:"effort"`
	ServiceTier              string          `json:"serviceTier,omitempty"`
	NativeDefaultServiceTier bool            `json:"nativeDefaultServiceTier,omitempty"`
}
type appReport struct {
	Protocol string          `json:"bridgeJobReport"`
	AppID    string          `json:"appId"`
	JobID    string          `json:"jobId"`
	Revision int64           `json:"revision"`
	Status   string          `json:"status"`
	Code     string          `json:"code"`
	Result   *string         `json:"result,omitempty"`
	Files    []protocol.File `json:"files,omitempty"`
	Error    *appDiagnostic  `json:"error,omitempty"`
}
type appJob struct {
	ID                 string          `json:"id"`
	Fingerprint        string          `json:"fingerprint"`
	Input              appInput        `json:"input"`
	ThreadID           string          `json:"threadId"`
	Developer          string          `json:"developer"`
	State              string          `json:"state"`
	Code               string          `json:"code"`
	TurnID             string          `json:"turnId,omitempty"`
	Report             *appReport      `json:"report,omitempty"`
	Progress           *appProgress    `json:"progress,omitempty"`
	Outputs            []protocol.File `json:"outputs"`
	Created            int64           `json:"created"`
	Updated            int64           `json:"updated"`
	Model              string          `json:"model"`
	Effort             string          `json:"effort"`
	ServiceTier        string          `json:"serviceTier,omitempty"`
	Log                []appLogEntry   `json:"log,omitempty"`
	LogRevision        int64           `json:"logRevision,omitempty"`
	LogDropped         int64           `json:"logDropped,omitempty"`
	ChatState          string          `json:"chatState,omitempty"`
	Delivered          int64           `json:"delivered,omitempty"`
	CleanupAccepted    bool            `json:"cleanupAccepted,omitempty"`
	CancelRequested    int64           `json:"cancelRequested,omitempty"`
	CancelReason       string          `json:"cancelReason,omitempty"`
	CancelAcknowledged int64           `json:"cancelAcknowledged,omitempty"`
	CancelRetired      int64           `json:"cancelRetired,omitempty"`
	pendingPowerIssue  bool
	liveDispatch       bool
}
type appState struct {
	Version         int                   `json:"version"`
	Peer            string                `json:"peer"`
	ID              string                `json:"id"`
	Name            string                `json:"name"`
	Jobs            map[string]*appJob    `json:"jobs"`
	Model           string                `json:"model"`
	Effort          string                `json:"effort"`
	ServiceTier     string                `json:"serviceTier,omitempty"`
	Prompts         map[string]appPrompt  `json:"prompts"`
	DeletedRequests map[string]int64      `json:"deletedRequests,omitempty"`
	Cleanup         map[string]appCleanup `json:"cleanup,omitempty"`
}
type appPrompt struct {
	Title        string `json:"title"`
	Text         string `json:"text"`
	Instructions string `json:"instructions"`
}

// Reject duplicate/unknown fields and invalid UTF-8 before typed decoding.
func appDecode(data []byte, value any) error { return protocol.DecodeStrict(data, value) }
func appLabel(name string) bool {
	return strings.TrimSpace(name) != "" && len(name) <= 255 && utf8.ValidString(name) && !strings.ContainsAny(name, "\x00\n\r\t") && !strings.ContainsFunc(name, func(r rune) bool { return r < 32 || r == 127 })
}
func appValidInput(input appInput) bool {
	if !token.MatchString(input.RequestID) || !appLabel(input.Title) || strings.TrimSpace(input.Text) == "" || !utf8.ValidString(input.Text) || !utf8.ValidString(input.Instructions) || len(input.Text)+len(input.Instructions) > 65536 || input.Files == nil || len(input.Files) > protocol.MaxInputFiles || input.CreatedAt <= 0 || len(input.Model) > 128 || len(input.Effort) > 32 || !protocol.ValidServiceTier(input.ServiceTier) || (input.NativeDefaultServiceTier && input.ServiceTier != "") {
		return false
	}
	seen := map[string]bool{}
	for _, f := range input.Files {
		if !protocol.ValidFile(f) || seen[f.ID] {
			return false
		}
		seen[f.ID] = true
	}
	return true
}
func appHash(input appInput) string {
	data, _ := json.Marshal(input)
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

// Existing accepted jobs carried this redundant true marker. Only durable reads
// accept it; normal writes and HTTP inputs use the current marker-free model.
func decodeStoredApp(data []byte, state *appState) error {
	var envelope map[string]json.RawMessage
	if err := appDecode(data, &envelope); err != nil {
		return err
	}
	var jobs map[string]json.RawMessage
	if raw, exists := envelope["jobs"]; exists {
		if err := appDecode(raw, &jobs); err != nil {
			return err
		}
	}
	changed := false
	for id, raw := range jobs {
		var fields map[string]json.RawMessage
		if err := appDecode(raw, &fields); err != nil {
			return err
		}
		if marker, exists := fields["ownedChat"]; exists {
			if string(bytes.TrimSpace(marker)) != "true" {
				return errors.New("job_store_invalid")
			}
			delete(fields, "ownedChat")
			encoded, err := json.Marshal(fields)
			if err != nil {
				return err
			}
			jobs[id] = encoded
			changed = true
		}
	}
	if changed {
		encoded, err := json.Marshal(jobs)
		if err != nil {
			return err
		}
		envelope["jobs"] = encoded
		data, err = json.Marshal(envelope)
		if err != nil {
			return err
		}
	}
	return appDecode(data, state)
}

func (s *Service) appRoot() (*os.Root, error) {
	if s.cfg.DataDir == "" {
		return nil, errors.New("app_storage_unavailable")
	}
	path := filepath.Join(s.cfg.DataDir, "apps")
	if err := os.Mkdir(path, 0700); err != nil && !os.IsExist(err) {
		return nil, err
	}
	info, err := os.Lstat(path)
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return nil, errors.New("invalid_app_storage")
	}
	return os.OpenRoot(path)
}
func appStateName(peer string) string {
	sum := sha256.Sum256([]byte(peer))
	return hex.EncodeToString(sum[:]) + ".json"
}
func (s *Service) loadApp(p *peer) (*appState, error) {
	root, err := s.appRoot()
	if err != nil {
		return nil, err
	}
	defer root.Close()
	name := appStateName(p.identity)
	info, err := root.Lstat(name)
	if os.IsNotExist(err) {
		return nil, os.ErrNotExist
	}
	if err != nil || !info.Mode().IsRegular() || info.Size() > maxAppRecordBytes {
		return nil, errors.New("app_store_invalid")
	}
	f, err := root.Open(name)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	data, err := io.ReadAll(io.LimitReader(f, maxAppRecordBytes+1))
	if err != nil || len(data) > maxAppRecordBytes {
		return nil, errors.New("app_store_unavailable")
	}
	var state appState
	if decodeStoredApp(data, &state) != nil || state.Version != 2 || state.Peer != p.identity || !token.MatchString(state.ID) || !appLabel(state.Name) || state.Jobs == nil || len(state.Jobs)+len(state.Cleanup) > maxAppOwners || !protocol.ValidServiceTier(state.ServiceTier) || (state.ServiceTier != "" && state.Model == "") {
		return nil, errors.New("app_store_invalid")
	}
	for id, j := range state.Jobs {
		if j != nil && !validAppProgress(j.Progress) {
			return nil, errors.New("job_store_invalid")
		}
		if j != nil && (!protocol.ValidServiceTier(j.ServiceTier) || (j.ServiceTier != "" && j.Model == "") || (j.Input.NativeDefaultServiceTier && j.ServiceTier != "")) {
			return nil, errors.New("job_store_invalid")
		}
		if j == nil || j.Created <= 0 || j.ID != id || !appValidInput(j.Input) || j.Input.RequestID != id || appHash(j.Input) != j.Fingerprint || (!nodeID.MatchString(j.ThreadID) && !(j.ThreadID == "" && (j.State == "queued" || j.State == "cancelled" || j.State == "rejected"))) {
			return nil, errors.New("job_store_invalid")
		}
		if j.State == "cancelled" && (j.TurnID != "" || j.Report != nil || len(j.Outputs) != 0) {
			return nil, errors.New("job_store_invalid")
		}
		if j.CancelRequested < 0 || j.CancelAcknowledged < 0 || j.CancelRetired < 0 || ((j.CancelAcknowledged != 0 || j.CancelRetired != 0) && j.CancelRequested == 0) {
			return nil, errors.New("job_store_invalid")
		}
		if (j.CancelRequested == 0 && j.CancelReason != "") || (j.CancelRequested != 0 && !contains([]string{"user_request", "native_interaction_required"}, j.CancelReason)) {
			return nil, errors.New("job_store_invalid")
		}
		if j.State == "dispatching" {
			j.liveDispatch = p.work[state.ID+"/"+id] != nil
			if !j.liveDispatch {
				j.State = "unknown"
				j.Code = "dispatch_outcome_unconfirmed"
			}
		}
	}
	for id, expires := range state.DeletedRequests {
		if !token.MatchString(id) || expires <= 0 || state.Jobs[id] != nil {
			return nil, errors.New("job_store_invalid")
		}
	}
	for id, cleanup := range state.Cleanup {
		if !validAppCleanup(id, cleanup) || state.Jobs[id] != nil {
			return nil, errors.New("job_cleanup_store_invalid")
		}
	}
	return &state, nil
}
func (s *Service) saveApp(state *appState) error {
	return s.writeApp(state, 0, false)
}

func (s *Service) saveAppInput(state *appState) error {
	return s.writeApp(state, appInputReserve(state), false)
}

func (s *Service) saveAppAdmission(state *appState) error {
	return s.writeApp(state, appInputReserve(state), true)
}

func appInputReserve(state *appState) int {
	reserve := 8 << 10 // Global model/name changes remain possible while jobs finish.
	for _, j := range state.Jobs {
		if contains([]string{"queued", "dispatching", "unknown", "submitted"}, j.State) || jobNeedsPC(j) {
			// Result, descriptors and eight bounded diagnostics can grow after admission.
			reserve += 256 << 10
		}
	}
	return reserve
}

func (s *Service) writeApp(state *appState, reserve int, admission bool) error {
	state.Version = 2
	data, err := json.Marshal(state)
	if err != nil {
		return err
	}
	if reserve > maxAppRecordBytes || len(data) > maxAppRecordBytes-reserve {
		return errAppCapacity
	}
	root, err := s.appRoot()
	if err != nil {
		return err
	}
	defer root.Close()
	name := appStateName(state.Peer)
	temporary := "." + randomToken() + ".tmp"
	s.mu.Lock()
	if s.appWrites == nil {
		s.appWrites = map[string]bool{}
	}
	s.appWrites[temporary] = true
	s.mu.Unlock()
	created := false
	defer func() {
		if created {
			_ = root.Remove(temporary)
		}
		s.mu.Lock()
		delete(s.appWrites, temporary)
		s.mu.Unlock()
	}()
	f, err := root.OpenFile(temporary, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	created = true
	_, err = f.Write(data)
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	s.powerMu.Lock()
	power, powerErr := s.readPower()
	if admission && powerErr == nil && power.AdmissionClosed {
		s.powerMu.Unlock()
		return errPowerAdmission
	}
	historical := powerErr != nil || power.ReleasedAt == 0
	for _, job := range state.Jobs {
		if code := powerJobIssue(job, historical); code != "" {
			if s.latchPowerLocked(code) != nil {
				s.powerFault = true
				if powerErr == nil {
					s.powerMu.Unlock()
					return errors.New("power_hold_unavailable")
				}
			}
			break
		}
	}
	hasWork := admission
	for _, j := range state.Jobs {
		hasWork = hasWork || !powerJobDrained(j)
	}
	if powerErr == nil && !power.HasWork && hasWork && (power.State == "wake_pending" || power.State == "managed") {
		// Reload after a possible fault latch so a new admission cannot erase it.
		if current, loadErr := s.readPower(); loadErr == nil {
			current.HasWork = true
			if s.writePower(current) != nil {
				s.powerFault = true
			}
		}
	}
	s.powerGeneration++
	err = root.Rename(temporary, name)
	if err == nil {
		for _, job := range state.Jobs {
			job.pendingPowerIssue = false
		}
	}
	s.powerMu.Unlock()
	if err != nil {
		return err
	}
	s.notifyPower()
	s.notifyRetention()
	if runtime.GOOS != "windows" {
		directory, err := root.Open(".")
		if err != nil {
			return err
		}
		err = directory.Sync()
		directory.Close()
		return err
	}
	return nil
}
func (s *Service) appReply(w http.ResponseWriter, code int, state any) {
	reply(w, code, map[string]any{"protocol": appProtocol, "bridgeIdentity": s.cfg.Identity, "pcIdentity": s.cfg.PCIdentity, "data": state})
}
func (s *Service) appError(w http.ResponseWriter, status int, code string) {
	failure := appErrorValue(code)
	if _, wake := w.(wakeResponseWriter); wake {
		failure.Retryable = false
		failure.Action += " Never retry this wake request automatically; another attempt requires a new explicit user action."
	}
	s.appReply(w, status, failure)
}
func appBody(w http.ResponseWriter, r *http.Request, value any) error {
	contentTypes := r.Header.Values("Content-Type")
	if len(contentTypes) != 1 {
		return errors.New("json_required")
	}
	mediaType, parameters, err := mime.ParseMediaType(contentTypes[0])
	if err != nil || mediaType != "application/json" {
		return errors.New("json_required")
	}
	for name, parameter := range parameters {
		if name != "charset" || !strings.EqualFold(parameter, "utf-8") {
			return errors.New("json_required")
		}
	}
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, protocol.MaxRequestBytes))
	if err != nil {
		return err
	}
	return appDecode(data, value)
}
func appView(state *appState) any {
	return map[string]any{"id": state.ID, "name": state.Name, "jobCount": len(state.Jobs), "model": state.Model, "effort": state.Effort, "serviceTier": state.ServiceTier, "retentionPolicy": "all_jobs_from_receiver_start", "jobRetentionDays": appRetention.Hours() / 24, "cacheRetentionDays": protocol.Retention.Hours() / 24, "cleanup": appCleanupView(state), "wakePolicy": "user_request_only", "chatPolicy": "owned_per_job"}
}
func (s *Service) appRequest(w http.ResponseWriter, r *http.Request, p *peer) {
	catalog := r.Method == "GET" && r.URL.Path == "/v2/codex/models"
	query := r.URL.Query()
	if (r.URL.RawQuery != "" && (!catalog || len(query) != 1 || len(query["cursor"]) != 1 || len(query.Get("cursor")) > 4096)) || r.URL.EscapedPath() != r.URL.Path {
		s.appError(w, 400, "invalid_route")
		return
	}
	if r.Method == "GET" && r.URL.Path == "/v2/connection" {
		ctx, cancel := s.appObservationContext(r.Context(), 5*time.Second)
		defer cancel()
		pcState := "unreachable"
		auth := unavailableAuth()
		var cleanup any = map[string]any{"state": "unconfirmed", "code": "pc_cleanup_status_unavailable", "pendingRequests": nil, "observedAt": nil}
		if ctx.Err() == nil && s.native.Reachable(ctx) == nil && ctx.Err() == nil {
			pcState = "reachable"
			auth = s.nativeAuth(ctx)
			outcome, err := s.native.Call(ctx, protocol.AdapterRequest{Operation: "cleanup"})
			var status pc.CleanupStatus
			decoded := protocol.DecodeStrict(outcome.Result, &status) == nil
			validHandoffs := status.UnresolvedHandoffs >= 0 && status.HandoffObservedAt >= 0 && (status.HandoffCode == "" || appCode.MatchString(status.HandoffCode))
			if owner := status.OldestHandoff; owner != nil {
				validHandoffs = validHandoffs && status.UnresolvedHandoffs > 0 && owner.CreatedAt > 0 && nodeID.MatchString(owner.Peer) && token.MatchString(owner.AppID) && token.MatchString(owner.JobID) && nodeID.MatchString(owner.ThreadID)
			}
			if err == nil && outcome.State == "ok" && decoded && validHandoffs && status.PendingRequests >= 0 &&
				((status.State == "checking" && status.Code == "" && status.PendingRequests == 0 && status.ObservedAt == 0) ||
					(status.State == "current" && status.Code == "" && status.PendingRequests == 0 && status.ObservedAt > 0) ||
					(status.State == "unconfirmed" && appCode.MatchString(status.Code) && status.ObservedAt > 0)) {
				view := map[string]any{"state": status.State, "code": status.Code, "pendingRequests": status.PendingRequests, "observedAt": status.ObservedAt,
					"unresolvedHandoffs": status.UnresolvedHandoffs, "oldestHandoffAt": nil, "handoffCode": status.HandoffCode, "handoffObservedAt": nil}
				if status.HandoffObservedAt > 0 {
					view["handoffObservedAt"] = status.HandoffObservedAt
				}
				if status.OldestHandoff != nil {
					view["oldestHandoffAt"] = status.OldestHandoff.CreatedAt
				}
				if status.State == "checking" {
					view["pendingRequests"], view["observedAt"] = nil, nil
				}
				cleanup = view
			}
		}
		storage := map[string]any{}
		var expiredCleanup any = map[string]any{"state": "unconfirmed", "code": "retention_cleanup_unavailable"}
		p.appMu.Lock()
		if state, loadErr := s.loadApp(p); loadErr == nil {
			if s.expireAppForUse(p, state, time.Now()) == nil {
				expiredCleanup = appCleanupView(state)
			}
		} else if os.IsNotExist(loadErr) {
			expiredCleanup = appCleanupView(&appState{})
		}
		p.appMu.Unlock()
		for name, store := range map[string]*files.Store{"incoming": s.inbox, "outgoing": s.outbox} {
			page, observedAt, err := store.DiagnosticSnapshot(r.Context(), p.identity)
			var stamp *int64
			if !observedAt.IsZero() {
				millis := observedAt.UnixMilli()
				stamp = &millis
			}
			if err != nil {
				storage[name] = map[string]any{"code": "storage_diagnostics_unavailable", "diagnosticCount": nil, "observedAt": stamp}
			} else {
				storage[name] = struct {
					protocol.FilePage
					ObservedAt *int64 `json:"observedAt"`
				}{page, stamp}
			}
		}
		s.appReply(w, 200, map[string]any{"state": "reachable", "pcState": pcState, "codexAuth": auth, "power": s.currentPowerView(), "expiredJobCleanup": expiredCleanup, "chatCleanup": cleanup, "storageDiagnostics": storage, "backgroundOperations": map[string]any{"job_worker": s.jobPassStatus.Snapshot(), "app_retention": s.retentionPassStatus.Snapshot()}, "epoch": s.epoch, "observedAt": time.Now().UnixMilli(), "validForMs": 20000, "wakePolicy": "user_request_only", "fileTransfers": map[string]any{"maxFileBytes": protocol.MaxFileBytes, "bufferApiBytes": protocol.MaxBufferedFileBytes, "storageReserveBytes": storageReserveBytes, "storageReserveInodes": files.StorageReserveInodes, "maxConcurrentDownloads": cap(s.downloadSlots), "transferTimeoutMs": protocol.FileTransferTimeout.Milliseconds(), "streaming": true}})
		return
	}
	p.appMu.Lock()
	locked := true
	destination := w
	response := &appResponse{header: make(http.Header)}
	w = response
	if _, wake := destination.(wakeResponseWriter); wake {
		w = wakeResponseWriter{response}
	}
	defer func() {
		if locked {
			p.appMu.Unlock()
		}
		response.send(destination)
	}()
	state, err := s.loadApp(p)
	if r.Method == "POST" && r.URL.Path == "/v2/app" {
		var input struct {
			Name string `json:"name"`
		}
		if !s.receiveAppBody(w, r, p, &state, "invalid_app", &input) {
			return
		}
		if !appLabel(input.Name) {
			s.appError(w, 400, "invalid_app")
			return
		}
		if state == nil {
			state = &appState{Version: 2, Peer: p.identity, ID: randomToken(), Name: input.Name, Jobs: map[string]*appJob{}, Prompts: map[string]appPrompt{}}
		} else if state.Name != input.Name {
			s.appError(w, 409, "app_identity_already_registered")
			return
		}
		if err := s.saveAppInput(state); err != nil {
			s.appStoreError(w, err)
			return
		}
		s.appReply(w, 200, appView(state))
		return
	}
	if err != nil {
		if os.IsNotExist(err) {
			s.appError(w, 409, "app_registration_required")
		} else {
			s.appError(w, 503, "app_store_unavailable")
		}
		return
	}
	if err = s.expireAppForUse(p, state, time.Now()); err != nil {
		s.appError(w, 503, "retention_cleanup_unavailable")
		return
	}
	if r.Method == "GET" && r.URL.Path == "/v2/app" {
		s.appReply(w, 200, appView(state))
		return
	}
	if r.Method == "DELETE" && r.URL.Path == "/v2/app" {
		if len(state.Cleanup) != 0 {
			s.appError(w, 503, "retention_cleanup_unavailable")
			return
		}
		app := state.ID
		selected := map[string]*appJob{}
		for _, j := range state.Jobs {
			if !contains([]string{"completed", "failed", "interrupted", "rejected", "cancelled"}, j.State) {
				s.appError(w, 409, "job_not_removable")
				return
			}
			selected[j.ID] = j
		}
		for id, original := range selected {
			current := state.Jobs[id]
			if state.ID != app || !sameJobOwner(current, original) || !jobTerminal(current) {
				s.appError(w, 409, "app_jobs_changed")
				return
			}
			if state, err = s.authorizeJobDiscard(r.Context(), p, state, current); err != nil {
				s.appError(w, 503, "job_cleanup_authorization_unconfirmed")
				return
			}
		}
		// Admission can run while the exact-owner PC authorization is in flight.
		if state.ID != app || len(state.Jobs) != len(selected) {
			s.appError(w, 409, "app_jobs_changed")
			return
		}
		for id, current := range state.Jobs {
			if !sameJobOwner(current, selected[id]) || !jobTerminal(current) {
				s.appError(w, 409, "app_jobs_changed")
				return
			}
			selected[id] = state.Jobs[id]
		}
		if s.removeAppJobs(state, selected) != nil {
			s.appError(w, 503, "retention_cleanup_unavailable")
			return
		}
		if len(state.Cleanup) != 0 {
			s.notifyJobs()
			s.appError(w, 503, "retention_cleanup_unavailable")
			return
		}
		for _, store := range []*files.Store{s.inbox, s.outbox} {
			for {
				page, err := store.List(p.identity, "", 100)
				if err != nil || page.DiagnosticCount != 0 {
					s.notifyRetention()
					s.appError(w, 503, "retention_cleanup_unavailable")
					return
				}
				for _, f := range page.Files {
					if store.Remove(p.identity, f.ID) != nil {
						s.appError(w, 503, "retention_cleanup_unavailable")
						return
					}
				}
				if page.NextCursor == "" {
					break
				}
			}
		}
		root, err := s.appRoot()
		if err != nil {
			s.appError(w, 503, "app_store_unavailable")
			return
		}
		err = root.Remove(appStateName(p.identity))
		root.Close()
		if err != nil {
			s.appError(w, 503, "app_store_unavailable")
			return
		}
		s.appReply(w, 200, map[string]bool{"disconnected": true})
		return
	}
	if s.appControls(w, r, p, state) {
		return
	}
	if r.Method == "GET" && r.URL.Path == "/v2/jobs" {
		// Bounded metadata list. Inputs and outputs are retrieved only by exact job ID.
		rows := []any{}
		for _, j := range state.Jobs {
			row := appJobView(j)
			delete(row, "outputs")
			row["outputCount"] = len(j.Outputs)
			rows = append(rows, row)
		}
		s.appReply(w, 200, rows)
		return
	}
	if r.Method == "POST" && r.URL.Path == "/v2/jobs" {
		var input appInput
		if !s.receiveAppBody(w, r, p, &state, "invalid_job", &input) {
			return
		}
		if input.NativeDefaultServiceTier && input.ServiceTier != "" {
			s.appError(w, 400, "invalid_service_tier_choice")
			return
		}
		if !appValidInput(input) {
			s.appError(w, 400, "invalid_job")
			return
		}
		if _, deleted := state.DeletedRequests[input.RequestID]; deleted || state.Cleanup[input.RequestID].Created != 0 {
			s.appError(w, 410, "request_deleted")
			return
		}
		if previous := state.Jobs[input.RequestID]; previous != nil {
			if previous.Fingerprint != appHash(input) {
				s.appError(w, 409, "request_id_conflict")
				return
			}
			s.appReply(w, 200, appJobView(previous))
			return
		}
		if len(state.Jobs)+len(state.Cleanup) >= maxAppOwners {
			s.appError(w, 409, "job_limit_reached")
			return
		}
		model, effort, tier := input.Model, input.Effort, input.ServiceTier
		if model == "" {
			model = state.Model
		}
		if effort == "" {
			effort = state.Effort
		}
		if tier == "" && !input.NativeDefaultServiceTier {
			tier = state.ServiceTier
		}
		if tier != "" && model == "" {
			s.appError(w, 400, "service_tier_model_required")
			return
		}
		for _, f := range input.Files {
			actual, err := s.inbox.Info(p.identity, f.ID)
			if err != nil || actual != f {
				s.appError(w, 409, "input_bytes_unverified")
				return
			}
		}
		now := time.Now().UnixMilli()
		j := &appJob{ID: input.RequestID, Fingerprint: appHash(input), Input: input, Developer: "bridge_job_" + input.RequestID, State: "queued", Code: "awaiting_pc", Outputs: []protocol.File{}, Created: now, Updated: now, Model: model, Effort: effort, ServiceTier: tier}
		state.Jobs[j.ID] = j
		if err := s.saveAppAdmission(state); err != nil {
			s.appStoreError(w, err)
			return
		}
		s.notifyRetention()
		s.notifyJobs()
		s.appReply(w, 202, appJobView(j))
		return
	}
	parts := strings.Split(r.URL.Path, "/")
	if len(parts) >= 4 && parts[1] == "v2" && parts[2] == "jobs" && token.MatchString(parts[3]) && r.Method == "GET" {
		j := state.Jobs[parts[3]]
		if j == nil {
			s.appJobUnavailable(w, state, state.ID, parts[3])
			return
		}
		if len(parts) == 4 {
			s.appReply(w, 200, appJobView(j))
			return
		}
		if len(parts) == 5 && parts[4] == "inputs" {
			s.appReply(w, 200, j.Input)
			return
		}
		if len(parts) == 5 && parts[4] == "log" {
			entries := append([]appLogEntry{}, j.Log...)
			s.appReply(w, 200, map[string]any{"entries": entries, "dropped": j.LogDropped})
			return
		}
		if len(parts) == 5 && parts[4] == "outputs" {
			s.appReply(w, 200, j.Outputs)
			return
		}
		if len(parts) == 7 && parts[4] == "outputs" && token.MatchString(parts[5]) && parts[6] == "content" {
			for _, offered := range j.Outputs {
				if offered.ID == parts[5] {
					if !s.admitDownload(w, r, p) {
						return
					}
					defer func() { <-s.downloadSlots }()
					app, jobID := state.ID, j.ID
					before, _ := json.Marshal(j)
					p.appMu.Unlock()
					actual, data, err := func() (protocol.File, *os.File, error) {
						defer p.appMu.Lock()
						return s.outbox.OpenVerifiedContext(r.Context(), p.identity, offered.ID)
					}()
					if data != nil {
						defer data.Close()
					}
					owner, _, ownerErr := s.reloadOutputOwner(p, app, jobID, before)
					if os.IsNotExist(ownerErr) {
						s.appJobUnavailable(w, owner, app, jobID)
						return
					}
					if ownerErr != nil && ownerErr != errOutputOwnerChanged {
						s.appStoreError(w, ownerErr)
						return
					}
					if ownerErr != nil || err != nil || actual != offered || r.Context().Err() != nil {
						s.appError(w, 503, "output_bytes_unverified")
						return
					}
					// The verified snapshot is independent of later job changes; disconnection waits on transfers.
					p.appMu.Unlock()
					locked = false
					w = destination
					w.Header().Set("Content-Type", "application/octet-stream")
					w.Header().Set("Cache-Control", "no-store")
					w.Header().Set("Content-Length", strconv.FormatInt(offered.Size, 10))
					_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(protocol.FileTransferTimeout))
					io.Copy(w, data)
					return
				}
			}
			s.appError(w, 404, "output_unavailable")
			return
		}
	}
	s.appError(w, 404, "unsupported_route")
}
func appReportView(r *appReport) any {
	if r == nil {
		return nil
	}
	return map[string]any{"revision": r.Revision, "status": r.Status, "code": r.Code}
}
func appJobView(j *appJob) map[string]any {
	var issue any
	if failure, known := appFailures[j.Code]; known {
		failure.Code = j.Code
		issue = failure
	}
	return map[string]any{"id": j.ID, "title": j.Input.Title, "state": j.State, "code": j.Code, "issue": issue, "threadId": j.ThreadID, "developer": j.Developer, "chatState": j.ChatState, "delivered": j.Delivered, "cleanupAccepted": j.CleanupAccepted, "cancelRequested": j.CancelRequested, "cancelReason": j.CancelReason, "cancelAcknowledged": j.CancelAcknowledged, "cancelRetired": j.CancelRetired, "report": appReportView(j.Report), "progress": appProgressView(j), "outputs": j.Outputs, "created": j.Created, "expiresAt": time.UnixMilli(j.Created).Add(appRetention).UnixMilli(), "updated": j.Updated, "model": j.Model, "effort": j.Effort, "serviceTier": j.ServiceTier}
}
func (s *Service) dispatchApp(p *peer, state *appState, j *appJob, model, effort string) error {
	app, id := state.ID, j.ID
	parent := s.jobContext(p, app, id)
	before, _ := json.Marshal(j)
	var dispatchSnapshot *appJob
	reload := func() (*appJob, error) {
		latest, err := s.loadApp(p)
		if err != nil {
			return nil, err
		}
		if err = s.expireAppForUse(p, latest, time.Now()); err != nil {
			return nil, err
		}
		*state = *latest
		live := state.Jobs[id]
		current, _ := json.Marshal(live)
		if live != nil && dispatchSnapshot != nil && live.State == dispatchSnapshot.State && live.CancelRequested > 0 && live.CancelReason == "user_request" && live.Code == "cancellation_pending" {
			// Cancellation intent may race the original ACK; preserve both, never send again.
			copy := *live
			copy.CancelRequested, copy.CancelReason, copy.Code, copy.Updated = dispatchSnapshot.CancelRequested, dispatchSnapshot.CancelReason, dispatchSnapshot.Code, dispatchSnapshot.Updated
			current, _ = json.Marshal(&copy)
		}
		if state.ID != app || live == nil || !bytes.Equal(before, current) {
			return nil, nil
		}
		return live, nil
	}
	// Waiting for another app's native dispatch must not own this app's saved API.
	p.appMu.Unlock()
	select {
	case s.dispatchSlots <- struct{}{}:
		defer func() { <-s.dispatchSlots }()
	case <-parent.Done():
		p.appMu.Lock()
		_, err := reload()
		return err
	}
	p.appMu.Lock()
	var err error
	if j, err = reload(); err != nil || j == nil || parent.Err() != nil {
		return err
	}
	thread := j.ThreadID
	p.appMu.Unlock()
	ctx, cancel := context.WithTimeout(parent, 35*time.Second)
	selected, selectErr := s.native.Call(ctx, protocol.AdapterRequest{Operation: "select", ThreadID: thread})
	cancel()
	p.appMu.Lock()
	if j, err = reload(); err != nil || j == nil {
		return err
	}
	j.Updated = time.Now().UnixMilli()
	if selectErr != nil {
		selected.State = "unknown"
	}
	if selected.State != "ok" {
		if selected.State == "unknown" || selected.State == "unavailable" {
			j.State = "queued"
			j.Code = "pc_unreachable"
		} else {
			j.State = "rejected"
			j.Code = "developer_selection_rejected"
		}
		appendJobLog(j, []appLogEntry{dispatchJobLog(j, "select", selected, selectErr)})
		return nil
	}
	var selection struct {
		Thread struct {
			ID     string `json:"id"`
			Status struct {
				Type string `json:"type"`
			} `json:"status"`
		} `json:"thread"`
	}
	if json.Unmarshal(selected.Result, &selection) != nil || selection.Thread.ID != j.ThreadID {
		j.State = "rejected"
		j.Code = "developer_selection_unconfirmed"
		appendJobLog(j, []appLogEntry{dispatchJobLog(j, "select", selected, nil)})
		return nil
	}
	if selection.Thread.Status.Type == "active" || selection.Thread.Status.Type == "waitingOnApproval" {
		j.State = "queued"
		j.Code = "developer_busy"
		return nil
	}
	if selection.Thread.Status.Type != "idle" {
		j.State = "rejected"
		j.Code = "developer_state_unavailable"
		appendJobLog(j, []appLogEntry{dispatchJobLog(j, "select", selected, nil)})
		return nil
	}
	// Each input has a distinct native item; owner text is never rewritten with bridge instructions.
	j.State, j.Code, j.Updated = "dispatching", "dispatch_pending", time.Now().UnixMilli()
	if err = s.saveApp(state); err != nil {
		return err
	}
	snapshot := *j
	// Only a reservation without this process's exact work lease is recovered as unknown.
	if p.work[state.ID+"/"+j.ID] == nil {
		snapshot.State, snapshot.Code = "unknown", "dispatch_outcome_unconfirmed"
	}
	dispatchSnapshot = &snapshot
	before, _ = json.Marshal(&snapshot)
	request := protocol.AdapterRequest{Operation: "send", ThreadID: j.ThreadID, Text: j.Input.Text, Instructions: j.Input.Instructions, AppID: state.ID, JobID: j.ID, Files: j.Input.Files, Model: model, Effort: effort, ServiceTier: j.ServiceTier}
	p.appMu.Unlock()
	ctx, cancel = context.WithTimeout(parent, 35*time.Second)
	outcome, sendErr := s.native.Call(ctx, request)
	cancel()
	p.appMu.Lock()
	if j, err = reload(); err != nil || j == nil {
		return err
	}
	if sendErr != nil {
		outcome.State = "unknown"
	}
	var ack struct {
		Turn struct {
			ID string `json:"id"`
		} `json:"turn"`
	}
	if outcome.State == "ok" && json.Unmarshal(outcome.Result, &ack) == nil && nodeID.MatchString(ack.Turn.ID) {
		j.TurnID = ack.Turn.ID
		j.State = "submitted"
		j.Code = "native_acknowledged"
	} else if code := authRefusal(outcome, sendErr); code != "" {
		j.State, j.Code = "queued", code
		cancelQueuedJob(j)
	} else if outcome.State == "unknown" || outcome.State == "ok" {
		j.State = "unknown"
		j.Code = "dispatch_outcome_unconfirmed"
	} else {
		j.State = "rejected"
		j.Code = "native_submission_rejected"
	}
	if j.State != "submitted" {
		appendJobLog(j, []appLogEntry{dispatchJobLog(j, "send", outcome, sendErr)})
	}
	j.Updated = time.Now().UnixMilli()
	return nil
}

var appCode = regexp.MustCompile(`^[a-z][a-z0-9_]{0,63}$`)

// Commitment proves message bytes, not that ordinary agent progress claims a report.
func appClaimsReport(text string) bool {
	d := json.NewDecoder(strings.NewReader(text))
	start, err := d.Token()
	if err != nil || start != json.Delim('{') {
		return false
	}
	for d.More() {
		key, err := d.Token()
		if err != nil {
			return false
		}
		if key == "bridgeJobReport" {
			return true
		}
		var value json.RawMessage
		if d.Decode(&value) != nil {
			return false
		}
	}
	return false
}

func appLatestReport(app string, j *appJob, turn json.RawMessage) (*appReport, string, []appLogEntry, error) {
	var record struct {
		ID       string `json:"id"`
		Status   string `json:"status"`
		Terminal bool   `json:"bridgeTerminalCommitted"`
		Items    []struct {
			Type                       string `json:"type"`
			Text                       string `json:"text"`
			Committed                  bool   `json:"bridgeReportCommitted"`
			OutputOwnershipUnconfirmed bool   `json:"bridgeOutputOwnershipUnconfirmed"`
		} `json:"items"`
	}
	if json.Unmarshal(turn, &record) != nil || record.ID != j.TurnID {
		return nil, "", nil, errors.New("different_native_turn")
	}
	if record.Status == "completed" && !record.Terminal {
		return nil, "", nil, errors.New("native_terminal_unconfirmed")
	}
	var latest *appReport
	var entries []appLogEntry
	for _, item := range record.Items {
		if item.Type != "agentMessage" || (record.Status != "completed" && !item.Committed) {
			continue
		}
		if !appClaimsReport(item.Text) {
			continue
		}
		var report appReport
		if len(item.Text) > 32768 || appDecode([]byte(item.Text), &report) != nil || report.Protocol != "v2" || report.AppID != app || report.JobID != j.ID || report.Revision < 1 || report.Revision > 999999999 || !appCode.MatchString(report.Code) || !contains([]string{"working", "waiting", "completed", "failed"}, report.Status) {
			return nil, "", nil, errors.New("invalid_developer_protocol")
		}
		if !validDiagnostic(report.Error) || (report.Status == "failed" && report.Error == nil) {
			return nil, "", nil, errors.New("invalid_developer_diagnostic")
		}
		if requiresDiagnosticRedaction(report.Error) {
			return nil, "", nil, errors.New("developer_diagnostic_redaction_required")
		}
		if report.Result != nil && (report.Status != "completed" || len(*report.Result) > 16384 || !utf8.ValidString(*report.Result)) {
			return nil, "", nil, errors.New("invalid_result")
		}
		if len(report.Files) > 16 || (report.Files != nil && (len(report.Files) == 0 || report.Status != "completed")) {
			return nil, "", nil, errors.New("invalid_artifacts")
		}
		seen := map[string]bool{}
		for _, f := range report.Files {
			if !protocol.ValidFile(f) || seen[f.ID] {
				return nil, "", nil, errors.New("invalid_artifacts")
			}
			seen[f.ID] = true
		}
		if item.OutputOwnershipUnconfirmed {
			return nil, "", nil, errors.New("job_output_ownership_unconfirmed")
		}
		if latest != nil {
			old, _ := json.Marshal(latest)
			current, _ := json.Marshal(report)
			if report.Revision < latest.Revision || (report.Revision == latest.Revision && !bytes.Equal(old, current)) || (report.Revision > latest.Revision && contains([]string{"completed", "failed"}, latest.Status)) {
				return nil, "", nil, errors.New("conflicting_developer_revision")
			}
			if report.Revision == latest.Revision {
				continue
			}
		}
		latest = &report
		if report.Error != nil {
			entries = append(entries, appLogEntry{Revision: report.Revision, Error: *report.Error})
		}
	}
	status := record.Status
	if (status == "failed" || status == "interrupted") && !record.Terminal {
		status = "inProgress"
	}
	return latest, status, entries, nil
}
func contains(values []string, value string) bool {
	for _, v := range values {
		if v == value {
			return true
		}
	}
	return false
}
func fmtInt(value int) string { return strconv.Itoa(value) }
func (s *Service) lookupAppTurn(ctx context.Context, j *appJob) (json.RawMessage, error) {
	cursor := ""
	for page := 0; page < 16; page++ {
		raw, err := s.native.History(ctx, j.ThreadID, cursor, 25)
		if err != nil {
			return nil, err
		}
		var history struct {
			Data       []json.RawMessage `json:"data"`
			NextCursor *string           `json:"nextCursor"`
		}
		if json.Unmarshal(raw, &history) != nil {
			return nil, errors.New("invalid_native_history")
		}
		for _, turn := range history.Data {
			var id struct {
				ID string `json:"id"`
			}
			if json.Unmarshal(turn, &id) != nil || id.ID != j.TurnID {
				continue
			}
			return turn, nil
		}
		if history.NextCursor == nil || *history.NextCursor == "" {
			break
		}
		cursor = *history.NextCursor
	}
	return nil, errors.New("native_turn_unavailable")
}

func (s *Service) applyAppTurn(ctx context.Context, p *peer, state *appState, j *appJob, turn json.RawMessage) error {
	var interaction struct {
		ID                        string `json:"id"`
		Status                    string `json:"status"`
		BridgeInteractionRequired bool   `json:"bridgeInteractionRequired"`
	}
	if json.Unmarshal(turn, &interaction) == nil && interaction.ID == j.TurnID && interaction.Status == "inProgress" && interaction.BridgeInteractionRequired && j.State == "submitted" && j.CancelRequested == 0 {
		return s.requestInteractiveStop(state, j)
	}
	before, _ := json.Marshal(j)
	nativeStatus, nativeEntries := nativeJobLog(j, turn)
	if nativeStatus != "" && j.CancelRequested != 0 && j.CancelRetired == 0 {
		id := j.ID
		retired, err := s.retireCancelledJob(p, state, j, nativeEntries)
		if err != nil || !retired {
			return err
		}
		return s.applyAppTurn(ctx, p, state, state.Jobs[id], turn)
	}
	observeCommentary(j, turn)
	if nativeStatus != "" {
		j.State = nativeStatus
		j.Code = "native_turn_" + nativeStatus
	}
	report, status, entries, err := appLatestReport(state.ID, j, turn)
	if err != nil {
		entry := protocolJobLog(err.Error())
		j.Code = "invalid_developer_protocol"
		if err.Error() == "native_terminal_unconfirmed" {
			entry = uncommittedCompletionLog(j)
			j.Code = "native_terminal_unconfirmed"
		}
		if err.Error() == "job_output_ownership_unconfirmed" {
			j.Code = "job_output_ownership_unconfirmed"
		}
		appendJobLog(j, []appLogEntry{entry})
		appendJobLog(j, nativeEntries)
		j.Outputs = []protocol.File{}
		return s.saveApp(state)
	}
	if report != nil && j.Report != nil {
		old, _ := json.Marshal(j.Report)
		current, _ := json.Marshal(report)
		if report.Revision < j.Report.Revision || (report.Revision == j.Report.Revision && !bytes.Equal(old, current)) || (report.Revision > j.Report.Revision && contains([]string{"completed", "failed"}, j.Report.Status)) {
			appendJobLog(j, []appLogEntry{protocolJobLog("committed_report_changed")})
			appendJobLog(j, nativeEntries)
			j.Code = "invalid_developer_protocol"
			j.Outputs = []protocol.File{}
			return s.saveApp(state)
		}
	}
	if report != nil {
		j.Report = report
	}
	appendJobLog(j, entries)
	appendJobLog(j, nativeEntries)
	if contains([]string{"completed", "failed", "interrupted"}, status) {
		j.State = status
		j.Code = "native_turn_" + status
	}
	if status == "completed" && (report == nil || !contains([]string{"completed", "failed"}, report.Status)) {
		actual := "absent"
		if report != nil {
			actual = report.Status
		}
		appendJobLog(j, []appLogEntry{missingFinalReportLog(actual)})
		j.Code = "developer_terminal_report_missing"
		j.Outputs = []protocol.File{}
	}
	if status == "completed" && report != nil && report.Status == "completed" {
		outputs := append([]protocol.File{}, report.Files...)
		if code, file := outputClaimConflict(state, j, outputs); code != "" {
			appendJobLog(j, []appLogEntry{outputClaimLog(code, file)})
			j.Code, j.Outputs = code, []protocol.File{}
			return s.saveApp(state)
		}
		// Persist the whole claimed batch before any unlocked stream can commit bytes.
		// Expiry and restart then retain the exact cleanup IDs even without final publication.
		if err := s.saveApp(state); err != nil {
			return err
		}
		before, _ = json.Marshal(j)
		for _, f := range outputs {
			actual, cause, stale, err := s.stageOutput(ctx, p, state, j, before, f)
			if stale {
				return err
			}
			if err != nil || actual != f {
				if err == nil {
					cause = "output_descriptor_mismatch"
				}
				appendJobLog(j, []appLogEntry{outputBytesLog(f.ID, cause, err)})
				j.Code = "output_bytes_unverified"
				j.Outputs = []protocol.File{}
				return s.saveApp(state)
			}
		}
		if code, file := outputClaimConflict(state, j, outputs); code != "" {
			appendJobLog(j, []appLogEntry{outputClaimLog(code, file)})
			j.Code, j.Outputs = code, []protocol.File{}
			return s.saveApp(state)
		}
		if report.Result != nil {
			resultID := appResultID(state.ID, j.ID)
			f, err := s.QueueFile(p.identity, resultID, "result.txt", []byte(*report.Result))
			if err != nil {
				appendJobLog(j, []appLogEntry{outputBytesLog(resultID, "inline_result_commit_unconfirmed", err)})
				j.Code, j.Outputs = "output_bytes_unverified", []protocol.File{}
				return s.saveApp(state)
			}
			outputs = append([]protocol.File{f}, outputs...)
		}
		j.Outputs = outputs
	}
	if j.CancelReason == "native_interaction_required" && j.Code == "native_turn_interrupted" {
		j.Code = "native_interaction_required"
	}
	j.Updated = time.Now().UnixMilli()
	return s.saveApp(state)
}

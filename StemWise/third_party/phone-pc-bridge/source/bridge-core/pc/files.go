package pc

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

// FileContext bounds a complete file operation by its caller and this service lifetime.
func (s *Service) FileContext(parent context.Context) (context.Context, context.CancelFunc) {
	ctx, cancel := context.WithTimeout(parent, protocol.FileTransferTimeout)
	release, err := s.beginPowerWork()
	if err != nil {
		cancel()
		return ctx, cancel
	}
	if s.ctx == nil {
		return ctx, func() { cancel(); release() }
	}
	stop := context.AfterFunc(s.ctx, cancel)
	if s.ctx.Err() != nil {
		cancel()
	}
	return ctx, func() { stop(); cancel(); release() }
}

// QueueFile snapshots bytes for one explicitly paired recipient. The caller
// keeps a stable transfer ID; restart/retry never requires overwriting a file.
func (s *Service) QueueFile(ctx context.Context, peer, id, name string, content []byte) (protocol.File, error) {
	if s.outbox == nil || s.peers[peer] == nil {
		return protocol.File{}, errors.New("File storage or paired recipient unavailable")
	}
	f, err := files.Describe(id, name, content)
	if err != nil {
		return f, err
	}
	return s.QueueFileReader(ctx, peer, f, bytes.NewReader(content))
}

func (s *Service) QueueFileReader(parent context.Context, peer string, f protocol.File, source io.Reader) (protocol.File, error) {
	if s.outbox == nil || s.peers[peer] == nil {
		return protocol.File{}, errors.New("File storage or paired recipient unavailable")
	}
	ctx, cancel := s.FileContext(parent)
	defer cancel()
	release, err := s.claimCreation(ctx, peer, "outgoing-file", f.ID)
	if err != nil {
		return protocol.File{}, err
	}
	defer release()
	s.creationMu.Lock()
	err = s.fileReferencedLocked(peer, f.ID, true)
	s.creationMu.Unlock()
	if err != nil {
		return protocol.File{}, err
	}
	return s.outbox.PutContext(ctx, peer, f, source)
}
func (s *Service) ReceivedFileInfo(parent context.Context, peer, id string) (protocol.File, error) {
	if s.inbox == nil || s.peers[peer] == nil {
		return protocol.File{}, errors.New("File storage or paired sender unavailable")
	}
	ctx, cancel := s.FileContext(parent)
	defer cancel()
	return s.inbox.InfoContext(ctx, peer, id)
}
func (s *Service) OpenReceivedFile(parent context.Context, peer, id string) (protocol.File, *os.File, error) {
	if s.inbox == nil || s.peers[peer] == nil {
		return protocol.File{}, nil, errors.New("File storage or paired sender unavailable")
	}
	ctx, cancel := s.FileContext(parent)
	defer cancel()
	return s.inbox.OpenVerifiedContext(ctx, peer, id)
}
func (s *Service) ReceivedFiles(parent context.Context, peer, cursor string, limit int) (protocol.FilePage, error) {
	if s.inbox == nil || s.peers[peer] == nil {
		return protocol.FilePage{}, errors.New("File storage or paired sender unavailable")
	}
	ctx, cancel := s.FileContext(parent)
	defer cancel()
	return s.inbox.ListContext(ctx, peer, cursor, limit)
}

func (s *Service) QueuedFiles(parent context.Context, peer, cursor string, limit int) (protocol.FilePage, error) {
	if s.outbox == nil || s.peers[peer] == nil {
		return protocol.FilePage{}, errors.New("File storage or paired recipient unavailable")
	}
	ctx, cancel := s.FileContext(parent)
	defer cancel()
	return s.outbox.ListContext(ctx, peer, cursor, limit)
}

func (s *Service) QueuedFileInfo(parent context.Context, peer, id string) (protocol.File, error) {
	if s.outbox == nil || s.peers[peer] == nil {
		return protocol.File{}, errors.New("File storage or paired recipient unavailable")
	}
	ctx, cancel := s.FileContext(parent)
	defer cancel()
	return s.outbox.InfoContext(ctx, peer, id)
}

func (s *Service) ReadReceivedFile(parent context.Context, peer, id string) (protocol.File, []byte, error) {
	if s.inbox == nil || s.peers[peer] == nil {
		return protocol.File{}, nil, errors.New("File storage or paired sender unavailable")
	}
	ctx, cancel := s.FileContext(parent)
	defer cancel()
	return s.inbox.ReadContext(ctx, peer, id)
}

// Removal is a trusted host action, never a remote arbitrary delete endpoint.
func (s *Service) RemoveQueuedFile(peer, id string) error {
	release, err := s.beginPowerWork()
	if err != nil {
		return err
	}
	defer release()
	if s.outbox == nil || s.peers[peer] == nil {
		return errors.New("File storage or paired recipient unavailable")
	}
	s.creationMu.Lock()
	defer s.creationMu.Unlock()
	if err := s.fileReferencedLocked(peer, id, true); err != nil {
		return err
	}
	return s.outbox.Remove(peer, id)
}

func (s *Service) RemoveReceivedFile(peer, id string) error {
	release, err := s.beginPowerWork()
	if err != nil {
		return err
	}
	defer release()
	if s.inbox == nil || s.peers[peer] == nil {
		return errors.New("File storage or paired sender unavailable")
	}
	s.creationMu.Lock()
	defer s.creationMu.Unlock()
	if err := s.fileReferencedLocked(peer, id, false); err != nil {
		return err
	}
	return s.inbox.Remove(peer, id)
}

// Explicit discard can remove a damaged exact object; ordinary cleanup uses Remove.
func (s *Service) DiscardQueuedFile(peer, id string) error {
	release, err := s.beginPowerWork()
	if err != nil {
		return err
	}
	defer release()
	if s.outbox == nil || s.peers[peer] == nil {
		return errors.New("File storage or paired recipient unavailable")
	}
	s.creationMu.Lock()
	defer s.creationMu.Unlock()
	if err := s.fileReferencedLocked(peer, id, true); err != nil {
		return err
	}
	return s.outbox.Discard(peer, id)
}

func (s *Service) DiscardReceivedFile(peer, id string) error {
	release, err := s.beginPowerWork()
	if err != nil {
		return err
	}
	defer release()
	if s.inbox == nil || s.peers[peer] == nil {
		return errors.New("File storage or paired sender unavailable")
	}
	s.creationMu.Lock()
	defer s.creationMu.Unlock()
	if err := s.fileReferencedLocked(peer, id, false); err != nil {
		return err
	}
	return s.inbox.Discard(peer, id)
}

func (s *Service) fileRequest(w http.ResponseWriter, r *http.Request, peer string) {
	ctx, cancel := s.FileContext(r.Context())
	defer cancel()
	r = r.WithContext(ctx)
	if ctx.Err() != nil {
		reply(w, 503, protocol.FileReply{Protocol: protocol.Files, PCIdentity: s.cfg.Identity, PeerIdentity: peer, Nonce: r.URL.Query().Get("nonce"), State: "unavailable"})
		return
	}
	if err := s.expireRequestedTransfer(r, peer); err != nil {
		reply(w, 503, protocol.FileReply{Protocol: protocol.Files, PCIdentity: s.cfg.Identity, PeerIdentity: peer, Nonce: r.URL.Query().Get("nonce"), State: "unavailable"})
		return
	}
	ServeFiles(w, r, peer, s.cfg.Identity, s.inbox, s.outbox)
}

// Routine requests inspect only their target. Whole-store retention and its
// diagnostics belong to pruneFiles; an expired target still cannot be served.
func (s *Service) expireRequestedTransfer(r *http.Request, peer string) error {
	var store *files.Store
	var id string
	output := false
	if r.Method == "POST" && r.URL.Path == "/v1/files/put" {
		store, id = s.inbox, r.URL.Query().Get("id")
	} else if r.Method == "GET" {
		parts := strings.Split(r.URL.Path, "/")
		if len(parts) == 5 {
			if parts[2] == "files" && parts[4] == "receipt" {
				store, id = s.inbox, parts[3]
			} else if parts[2] == "outbox" && (parts[4] == "info" || parts[4] == "content") {
				store, id, output = s.outbox, parts[3], true
			}
		}
	}
	if store == nil || !files.ValidID(id) {
		return nil // The shared handler retains all route/envelope validation.
	}
	accepted, err := store.CommittedAt(peer, id)
	if errors.Is(err, files.ErrAbsent) {
		return nil
	}
	if err != nil || accepted.Add(protocol.Retention).After(time.Now()) {
		return err
	}
	s.creationMu.Lock()
	defer s.creationMu.Unlock()
	protected, err := s.fileProtectedLocked(peer, id, output)
	if err != nil || protected {
		return err
	}
	// The old object may have been retired and a fresh transfer committed while
	// waiting for ownership. Delete only a still-expired current publication.
	accepted, err = store.CommittedAt(peer, id)
	if errors.Is(err, files.ErrAbsent) {
		return nil
	}
	if err != nil || accepted.Add(protocol.Retention).After(time.Now()) {
		return err
	}
	err = store.Remove(peer, id)
	if errors.Is(err, files.ErrAbsent) {
		return nil
	}
	return err
}

// ServeFiles is the shared bounded, identity-scoped binary HTTP contract.
// Authentication is the host's responsibility before invoking this handler.
func ServeFiles(w http.ResponseWriter, r *http.Request, peer, identity string, inbox, outbox *files.Store) {
	serveFiles(w, r, peer, identity, inbox, outbox, nil)
}

// A nonnil publication list keeps app reads separate from verification staging.
func ServeAppFiles(w http.ResponseWriter, r *http.Request, peer, identity string, inbox, outbox *files.Store, published []protocol.File) {
	if published == nil {
		published = []protocol.File{}
	}
	serveFiles(w, r, peer, identity, inbox, outbox, published)
}

func serveFiles(w http.ResponseWriter, r *http.Request, peer, identity string, inbox, outbox *files.Store, published []protocol.File) {
	ctx, cancel := context.WithTimeout(r.Context(), protocol.FileTransferTimeout)
	defer cancel()
	r = r.WithContext(ctx)
	if r.Body != nil {
		stop := context.AfterFunc(ctx, func() { _ = r.Body.Close() })
		defer stop()
	}
	q := r.URL.Query()
	result := protocol.FileReply{Protocol: protocol.Files, PCIdentity: identity, PeerIdentity: peer, Nonce: q.Get("nonce"), State: "rejected"}
	if inbox == nil || outbox == nil {
		result.State = "unavailable"
		reply(w, 503, result)
		return
	}
	if len(q["nonce"]) != 1 || !token.MatchString(result.Nonce) {
		reply(w, 400, result)
		return
	}
	status := 200
	var err error
	if r.Method == "GET" && r.URL.Path == "/v1/files/health" && len(q) == 1 {
		result.State = "ready"
	} else if r.Method == "POST" && r.URL.Path == "/v1/files/put" && len(q) == 5 && len(q["id"]) == 1 && len(q["name"]) == 1 && len(q["size"]) == 1 && len(q["sha256"]) == 1 {
		size, sizeErr := strconv.ParseInt(q.Get("size"), 10, 64)
		f := protocol.File{ID: q.Get("id"), Name: q.Get("name"), Size: size, SHA256: q.Get("sha256")}
		if sizeErr != nil || !files.Valid(f) {
			reply(w, 400, result)
			return
		}
		_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(protocol.FileTransferTimeout))
		_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(protocol.FileTransferTimeout))
		stored, storeErr := inbox.PutContext(r.Context(), peer, f, http.MaxBytesReader(w, r.Body, f.Size+1))
		err = storeErr
		if err == nil {
			result.File = &stored
			result.State = "stored"
		}
	} else if r.Method == "GET" && r.URL.Path == "/v1/outbox" && len(q) == 3 && len(q["cursor"]) == 1 && len(q["limit"]) == 1 {
		limit, parseErr := strconv.Atoi(q.Get("limit"))
		if parseErr != nil || limit < 1 || limit > 100 || (q.Get("cursor") != "" && !files.ValidID(q.Get("cursor"))) {
			reply(w, 400, result)
			return
		}
		var page protocol.FilePage
		var pageErr error
		if published == nil {
			page, pageErr = outbox.ListContext(r.Context(), peer, q.Get("cursor"), limit)
		} else {
			page.Files = []protocol.File{}
			sort.Slice(published, func(i, j int) bool { return published[i].ID < published[j].ID })
			for _, f := range published {
				if f.ID <= q.Get("cursor") {
					continue
				}
				if len(page.Files) == limit {
					page.NextCursor = page.Files[len(page.Files)-1].ID
					break
				}
				actual, readErr := outbox.InfoContext(r.Context(), peer, f.ID)
				if readErr != nil {
					pageErr = readErr
					break
				}
				if actual != f {
					pageErr = files.ErrCorrupt
					break
				}
				page.Files = append(page.Files, f)
			}
		}
		err = pageErr
		if err == nil {
			result.Page = &page
			result.State = "available"
		}
	} else if r.Method == "GET" && len(q) == 1 {
		parts := strings.Split(r.URL.Path, "/")
		if len(parts) != 5 || !files.ValidID(parts[3]) {
			reply(w, 404, result)
			return
		}
		var store *files.Store
		switch parts[2] {
		case "files":
			if parts[4] != "receipt" {
				reply(w, 404, result)
				return
			}
			store = inbox
			result.State = "stored"
		case "outbox":
			if parts[4] != "info" && parts[4] != "content" {
				reply(w, 404, result)
				return
			}
			store = outbox
			if published != nil {
				allowed := false
				for _, f := range published {
					if f.ID == parts[3] {
						allowed = true
						break
					}
				}
				if !allowed {
					result.State = "unavailable"
					reply(w, 404, result)
					return
				}
			}
			result.State = "available"
		default:
			reply(w, 404, result)
			return
		}
		var f protocol.File
		var data *os.File
		var readErr error
		if parts[4] == "info" {
			f, readErr = store.InfoContext(r.Context(), peer, parts[3])
		} else {
			f, data, readErr = store.OpenVerifiedContext(r.Context(), peer, parts[3])
		}
		if data != nil {
			defer data.Close()
		}
		if readErr == nil && parts[2] == "outbox" && published != nil {
			matched := false
			for _, expected := range published {
				if expected == f {
					matched = true
					break
				}
			}
			if !matched {
				readErr = files.ErrCorrupt
			}
		}
		err = readErr
		if errors.Is(readErr, files.ErrAbsent) && parts[2] == "files" {
			result.State = "absent"
			reply(w, http.StatusNotFound, result)
			return
		}
		if err == nil {
			if parts[4] == "content" {
				w.Header().Set("Cache-Control", "no-store")
				w.Header().Set("Content-Type", "application/octet-stream")
				w.Header().Set("Content-Length", strconv.FormatInt(f.Size, 10))
				_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(protocol.FileTransferTimeout))
				w.WriteHeader(200)
				files.CopyContext(r.Context(), w, data)
				return
			}
			result.File = &f
		}
	} else {
		reply(w, 404, result)
		return
	}
	if err != nil {
		result.State = "unavailable"
		switch {
		case errors.Is(err, files.ErrStorageCapacity):
			status = 503
			result.State = protocol.StorageCapacityUnavailable
		case errors.Is(err, files.ErrConflict):
			status = 409
			result.State = "conflict"
		case errors.Is(err, files.ErrContentMismatch):
			status = 400
			result.State = "content_mismatch"
		case errors.Is(err, files.ErrAbsent):
			status = 404
		default:
			status = 503
		}
	}
	reply(w, status, result)
}

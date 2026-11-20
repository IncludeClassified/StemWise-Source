package backend

import (
	"context"
	"io"
	"net/http"
	"strconv"
	"sync"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

// An existing job input cannot become a fresh cache object after its last owner
// disappears. New uploads without an admitted owner remain ordinary cache data.
type appInputUpload struct {
	io.ReadCloser
	service     *Service
	peer        *peer
	app         string
	file        protocol.File
	owned       bool
	deadline    time.Time
	initialErr  error
	context     context.Context
	publication bool
}

// The shared handler's transfer timeout must not extend an owned upload's
// earlier deadline. Serialize cancellation so a later setter cannot undo it.
type inputUploadResponse struct {
	http.ResponseWriter
	context context.Context
	mu      sync.Mutex
}

func (w *inputUploadResponse) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (w *inputUploadResponse) SetReadDeadline(deadline time.Time) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.context.Err() != nil {
		deadline = time.Now()
	} else if limit, ok := w.context.Deadline(); ok && (deadline.IsZero() || limit.Before(deadline)) {
		deadline = limit
	}
	return http.NewResponseController(w.ResponseWriter).SetReadDeadline(deadline)
}

// Caller holds appMu. Capture before pruning so an already-due owner cannot be
// mistaken for a genuinely new pre-admission upload.
func (s *Service) inputUpload(r *http.Request, p *peer, state *appState) *appInputUpload {
	if state == nil || r.Method != "POST" || r.URL.Path != "/v1/files/put" {
		return nil
	}
	q := r.URL.Query()
	size, err := strconv.ParseInt(q.Get("size"), 10, 64)
	f := protocol.File{ID: q.Get("id"), Name: q.Get("name"), Size: size, SHA256: q.Get("sha256")}
	if err != nil || !protocol.ValidFile(f) {
		return nil // The shared file handler owns malformed-request responses.
	}
	owned, deadline, err := inputOwnerDeadline(state, f)
	return &appInputUpload{ReadCloser: r.Body, service: s, peer: p, app: state.ID, file: f, owned: owned, deadline: deadline, initialErr: err}
}

func inputOwnerDeadline(state *appState, f protocol.File) (bool, time.Time, error) {
	owned, deadline := false, time.Time{}
	for _, job := range state.Jobs {
		for _, input := range job.Input.Files {
			if input.ID != f.ID {
				continue
			}
			if input != f {
				return true, deadline, files.ErrConflict
			}
			owned = true
			if until := time.UnixMilli(job.Created).Add(appRetention); until.After(deadline) {
				deadline = until
			}
		}
	}
	return owned, deadline, nil
}

func (b *appInputUpload) Read(p []byte) (int, error) {
	if b.initialErr != nil {
		return 0, b.initialErr
	}
	n, err := b.ReadCloser.Read(p)
	if err == io.EOF {
		if fenceErr := b.fence(); fenceErr != nil {
			return n, fenceErr
		}
	}
	return n, err
}

// Store.Put releases its mutex while consuming this body. Hold appMu from its
// final EOF through receipt/publication, preserving appMu -> store.mu lock order.
// shortcut: overlapping same-ID repairs may hash a late winner under appMu;
// use a Store publication callback if apps need concurrent repair uploads.
func (b *appInputUpload) fence() error {
	if b.publication {
		return b.context.Err()
	}
	b.peer.appMu.Lock()
	b.publication = true
	if err := b.context.Err(); err != nil {
		return err
	}
	state, err := b.service.loadApp(b.peer)
	if err != nil {
		return err
	}
	if state.ID != b.app {
		return context.Canceled
	}
	if err := b.service.expireAppForUse(b.peer, state, time.Now()); err != nil {
		return err
	}
	owned, deadline, err := inputOwnerDeadline(state, b.file)
	if err != nil {
		return err
	}
	if (b.owned && !owned) || (owned && !deadline.After(time.Now())) {
		return context.Canceled
	}
	return b.context.Err()
}

func (b *appInputUpload) finish() {
	if b.publication {
		b.peer.appMu.Unlock()
	}
}

// Package files stores managed bridge objects, never caller-selected paths.
package files

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"

	"phonepcbridge/protocol"
)

var idPattern = regexp.MustCompile(`^[0-9a-f]{32}$`)
var peerPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)
var ErrConflict = errors.New("Transfer ID already has different contents")
var ErrCorrupt = errors.New("Stored transfer is incomplete or corrupt")

// ErrAbsent means only a conclusively missing final object, never a missing member.
var ErrAbsent = os.ErrNotExist
var ErrContentMismatch = errors.New("Received bytes do not match transfer metadata")

type Store struct {
	root                    *os.Root
	budget                  *Budget
	mu                      sync.Mutex
	uploads                 sync.RWMutex
	active                  map[string]bool
	closed                  bool
	retention               time.Duration
	afterVerified           func() // optional per-store checkpoint; verified handle is closed and mu is released
	maintenance             sync.Mutex
	diagnosticMu            sync.RWMutex
	diagnostics             diagnosticSnapshot
	diagnosticPeers         map[string]diagnosticSnapshot
	diagnosticRoot          diagnosticSnapshot
	diagnosticFullAt        time.Time
	diagnosticClosed        bool
	beforeMaintenanceObject func(peer, id string) // optional checkpoint outside ownership/store locks
	beforeListEntry         func(peer, id string) // optional checkpoint outside the store lock
}

type diagnosticSnapshot struct {
	page protocol.FilePage
	at   time.Time
	err  error
}

// Open owns a dedicated managed directory. Use one live Store per directory.
// Names are display metadata; every filesystem component is a validated ID.
func Open(path string) (*Store, error) {
	return OpenWithBudget(path, nil)
}

func OpenWithBudget(path string, budget *Budget) (*Store, error) {
	return openStore(path, budget, 0)
}

// OpenWithRetention expires exact accessed cache objects. Reference-aware hosts
// use Open/OpenWithBudget and retain their own job-based expiry policy.
func OpenWithRetention(path string, age time.Duration) (*Store, error) {
	if age <= 0 {
		return nil, errors.New("Positive cache retention required")
	}
	return openStore(path, nil, age)
}

func openStore(path string, budget *Budget, retention time.Duration) (*Store, error) {
	if !filepath.IsAbs(path) {
		return nil, errors.New("Absolute managed storage required")
	}
	// The host supplies an existing parent; do not create unrelated ancestors.
	if err := os.Mkdir(path, 0700); err != nil && !os.IsExist(err) {
		return nil, err
	}
	info, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return nil, errors.New("Managed storage must be a real directory")
	}
	r, err := os.OpenRoot(path)
	if err != nil {
		return nil, err
	}
	parent, err := os.OpenRoot(filepath.Dir(path))
	if err != nil {
		r.Close()
		return nil, err
	}
	err = syncDirectory(parent, ".")
	parent.Close()
	if err != nil {
		r.Close()
		return nil, err
	}
	return &Store{root: r, active: map[string]bool{}, budget: budget, retention: retention}, nil
}

func NewID() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(b[:]), nil
}

func ValidID(id string) bool { return idPattern.MatchString(id) }

func Valid(f protocol.File) bool {
	return protocol.ValidFile(f)
}

func Describe(id, name string, data []byte) (protocol.File, error) {
	if len(data) > protocol.MaxBufferedFileBytes {
		return protocol.File{}, errors.New("file_requires_streaming")
	}
	sum := sha256.Sum256(data)
	f := protocol.File{ID: id, Name: name, Size: int64(len(data)), SHA256: hex.EncodeToString(sum[:])}
	if !Valid(f) {
		return f, errors.New("Invalid or oversized transfer")
	}
	return f, nil
}

func DescribeReader(id, name string, source io.Reader) (protocol.File, error) {
	return DescribeReaderContext(context.Background(), id, name, source)
}

func DescribeReaderContext(ctx context.Context, id, name string, source io.Reader) (protocol.File, error) {
	hash := sha256.New()
	size, err := CopyContext(ctx, hash, io.LimitReader(source, protocol.MaxFileBytes+1))
	f := protocol.File{ID: id, Name: name, Size: size, SHA256: hex.EncodeToString(hash.Sum(nil))}
	if err != nil {
		return f, err
	}
	if !Valid(f) {
		return f, errors.New("invalid_or_oversized_transfer")
	}
	return f, nil
}

func (s *Store) path(peer, id string) (string, error) {
	if s.closed {
		return "", errors.New("File store closed")
	}
	if !peerPattern.MatchString(peer) || !ValidID(id) {
		return "", errors.New("Exact peer and transfer IDs required")
	}
	return filepath.Join(peer, id), nil
}

func (s *Store) openContent(ctx context.Context, peer, id string, expire bool, expected *protocol.File) (protocol.File, *os.File, error) {
	var f protocol.File
	if err := ctx.Err(); err != nil {
		return f, nil, err
	}
	path, err := s.path(peer, id)
	if err != nil {
		return f, nil, err
	}
	return s.openPath(ctx, peer, id, path, expire, expected)
}

// Read-only maintenance inspection may run without mu while uploads.RLock
// keeps root alive. Target expiry still runs only with mu held.
func (s *Store) openPath(ctx context.Context, peer, id, path string, expire bool, expected *protocol.File) (protocol.File, *os.File, error) {
	var f protocol.File
	// Root confines resolution even if a local path is replaced with a symlink.
	var objectTime time.Time
	for index, part := range []string{peer, path, filepath.Join(path, "metadata.json"), filepath.Join(path, "content")} {
		info, err := s.root.Lstat(part)
		if err != nil {
			if os.IsNotExist(err) && index < 2 {
				return f, nil, ErrAbsent
			}
			return f, nil, errors.Join(ErrCorrupt, errors.New(err.Error()))
		}
		if info.Mode()&os.ModeSymlink != 0 || (index < 2 && !info.IsDir()) || (index >= 2 && !info.Mode().IsRegular()) {
			return f, nil, ErrCorrupt
		}
		if index == 1 {
			objectTime = info.ModTime()
		}
	}
	if err := s.removableParts(path); err != nil {
		return f, nil, errors.Join(ErrCorrupt, errors.New(err.Error()))
	}
	meta, err := s.root.Open(filepath.Join(path, "metadata.json"))
	if err != nil {
		return f, nil, errors.Join(ErrCorrupt, errors.New(err.Error()))
	}
	metadata, err := io.ReadAll(io.LimitReader(meta, 4097))
	meta.Close()
	if err != nil || len(metadata) > 4096 || protocol.DecodeStrict(metadata, &f) != nil || !Valid(f) || f.ID != id {
		return f, nil, ErrCorrupt
	}
	blob, err := s.root.Open(filepath.Join(path, "content"))
	if err != nil {
		return f, nil, errors.Join(ErrCorrupt, errors.New(err.Error()))
	}
	keep := false
	defer func() {
		if !keep {
			blob.Close()
		}
	}()
	before, err := blob.Stat()
	if err != nil || !before.Mode().IsRegular() || before.Size() != f.Size {
		return f, nil, ErrCorrupt
	}
	if expire && s.retention > 0 && !objectTime.Add(s.retention).After(time.Now()) {
		if expected != nil && f != *expected {
			return f, nil, ErrConflict
		}
		blob.Close()
		if err := ctx.Err(); err != nil {
			return f, nil, err
		}
		if err := s.root.RemoveAll(path); err != nil {
			return f, nil, err
		}
		if err := s.syncDir(peer); err != nil {
			return f, nil, err
		}
		return f, nil, ErrAbsent
	}
	keep = true
	return f, blob, nil
}

func verifyContent(ctx context.Context, f protocol.File, blob *os.File) (protocol.File, *os.File, error) {
	keep := false
	defer func() {
		if !keep {
			blob.Close()
		}
	}()
	before, err := blob.Stat()
	if err != nil {
		return f, nil, err
	}
	actual, err := DescribeReaderContext(ctx, f.ID, f.Name, blob)
	if err != nil {
		return f, nil, err
	}
	after, err := blob.Stat()
	if err != nil || after.Size() != before.Size() || !after.ModTime().Equal(before.ModTime()) || actual != f {
		return f, nil, ErrCorrupt
	}
	if _, err := blob.Seek(0, io.SeekStart); err != nil {
		return f, nil, err
	}
	keep = true
	return f, blob, nil
}

func (s *Store) info(ctx context.Context, peer, id string, expire bool, expected *protocol.File) (protocol.File, error) {
	f, blob, err := s.openContent(ctx, peer, id, expire, expected)
	if blob != nil {
		blob.Close()
	}
	return f, err
}

// verifiedInfoLocked releases the store lock during payload I/O; callers own mu.
func (s *Store) verifiedInfoLocked(ctx context.Context, peer, id string, expected *protocol.File) (protocol.File, os.FileInfo, error) {
	f, blob, err := s.openContent(ctx, peer, id, true, expected)
	if err != nil {
		return f, nil, err
	}
	verified, err := blob.Stat()
	if err != nil {
		blob.Close()
		return f, nil, err
	}
	func() {
		s.mu.Unlock()
		defer s.mu.Lock()
		f, blob, err = verifyContent(ctx, f, blob)
		if blob != nil {
			blob.Close()
		}
		if err == nil && s.afterVerified != nil {
			s.afterVerified()
		}
	}()
	if err != nil {
		return f, nil, err
	}
	if expected != nil && f != *expected {
		return f, nil, ErrConflict
	}
	f, err = s.currentVerifiedInfoLocked(ctx, peer, id, f, verified)
	return f, verified, err
}

func (s *Store) currentVerifiedInfoLocked(ctx context.Context, peer, id string, f protocol.File, verified os.FileInfo) (protocol.File, error) {
	// The verified handle may have been retired while payload I/O released mu.
	current, blob, err := s.openContent(ctx, peer, id, true, &f)
	if err != nil {
		return f, err
	}
	defer blob.Close()
	if current != f {
		return f, ErrConflict
	}
	actual, err := blob.Stat()
	if err != nil {
		return f, err
	}
	if !os.SameFile(verified, actual) || actual.Size() != verified.Size() || !actual.ModTime().Equal(verified.ModTime()) {
		return f, ErrCorrupt
	}
	return f, ctx.Err()
}

// OpenVerified hashes managed bytes with bounded memory. Callers close the file
// and recipients verify the exact descriptor before committing streamed bytes.
func (s *Store) OpenVerified(peer, id string) (protocol.File, *os.File, error) {
	return s.OpenVerifiedContext(context.Background(), peer, id)
}

func (s *Store) OpenVerifiedContext(ctx context.Context, peer, id string) (protocol.File, *os.File, error) {
	f, blob, err := s.OpenContext(ctx, peer, id)
	if err != nil {
		return f, nil, err
	}
	return verifyContent(ctx, f, blob)
}

// OpenContext checks owned metadata and regular-file shape without hashing.
// The caller must verify the descriptor against bytes consumed before committing use.
func (s *Store) OpenContext(ctx context.Context, peer, id string) (protocol.File, *os.File, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.openContent(ctx, peer, id, true, nil)
}

func (s *Store) read(ctx context.Context, peer, id string) (protocol.File, []byte, error) {
	f, blob, err := s.OpenContext(ctx, peer, id)
	if err != nil {
		return f, nil, err
	}
	defer blob.Close()
	if f.Size > protocol.MaxBufferedFileBytes {
		return f, nil, errors.New("file_requires_streaming")
	}
	var content bytes.Buffer
	hash := sha256.New()
	size, err := CopyContext(ctx, io.MultiWriter(&content, hash), io.LimitReader(blob, f.Size+1))
	data := content.Bytes()
	if err != nil {
		return f, nil, err
	}
	if cancelled := ctx.Err(); cancelled != nil {
		return f, nil, cancelled
	}
	if size != f.Size || hex.EncodeToString(hash.Sum(nil)) != f.SHA256 {
		return f, nil, ErrCorrupt
	}
	return f, data, nil
}

// Read reopens and hashes actual recipient bytes. Metadata alone cannot pass.
func (s *Store) Read(peer, id string) (protocol.File, []byte, error) {
	return s.ReadContext(context.Background(), peer, id)
}

func (s *Store) ReadContext(ctx context.Context, peer, id string) (protocol.File, []byte, error) {
	return s.read(ctx, peer, id)
}

func (s *Store) Info(peer, id string) (protocol.File, error) {
	return s.InfoContext(context.Background(), peer, id)
}

func (s *Store) InfoContext(ctx context.Context, peer, id string) (protocol.File, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.info(ctx, peer, id, true, nil)
}

// CommittedAt observes receiver-owned acceptance time without expiring or hashing the object.
func (s *Store) CommittedAt(peer, id string) (time.Time, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, err := s.info(context.Background(), peer, id, false, nil); err != nil {
		return time.Time{}, err
	}
	path, err := s.path(peer, id)
	if err != nil {
		return time.Time{}, err
	}
	info, err := s.root.Lstat(path)
	if err != nil {
		return time.Time{}, err
	}
	return info.ModTime(), nil
}

func syncFile(f *os.File, writeErr error) error {
	if writeErr == nil {
		writeErr = f.Sync()
	}
	closeErr := f.Close()
	if writeErr != nil {
		return writeErr
	}
	return closeErr
}

func (s *Store) syncDir(path string) error {
	// Windows directory handles do not support File.Sync. File flushes plus an
	// atomic same-volume rename still protect readers from partial commits there.
	return syncDirectory(s.root, path)
}

// Put commits a complete directory atomically. An identical ID is idempotent;
// changed bytes/name fail without overwriting the earlier recipient object.
func (s *Store) Put(peer string, f protocol.File, source io.Reader) (protocol.File, error) {
	return s.PutContext(context.Background(), peer, f, source)
}

func (s *Store) PutContext(ctx context.Context, peer string, f protocol.File, source io.Reader) (protocol.File, error) {
	if err := ctx.Err(); err != nil {
		return f, err
	}
	s.uploads.RLock()
	defer s.uploads.RUnlock()
	s.mu.Lock()
	defer s.mu.Unlock()
	path, err := s.path(peer, f.ID)
	if err != nil {
		return f, err
	}
	if !Valid(f) || source == nil {
		return f, errors.New("Invalid transfer metadata or content")
	}
	if _, err := s.root.Lstat(path); err == nil {
		stored, verified, err := s.verifiedInfoLocked(ctx, peer, f.ID, &f)
		if err != nil && !errors.Is(err, ErrAbsent) {
			if cancelled := ctx.Err(); cancelled != nil {
				return f, cancelled
			}
			if errors.Is(err, ErrConflict) {
				return f, ErrConflict
			}
			return f, errors.Join(ErrCorrupt, err)
		}
		if err == nil {
			var actual protocol.File
			func() {
				s.mu.Unlock()
				defer s.mu.Lock()
				actual, err = DescribeReaderContext(ctx, f.ID, f.Name, io.LimitReader(source, f.Size+1))
			}()
			if err != nil {
				return f, err
			}
			if actual != f {
				return f, ErrContentMismatch
			}
			stored, err = s.currentVerifiedInfoLocked(ctx, peer, f.ID, f, verified)
			return stored, err
		}
	} else if !os.IsNotExist(err) {
		return f, err
	}
	if s.budget != nil {
		release, err := s.budget.reserve(s.root, f.Size)
		if err != nil {
			return f, err
		}
		defer release()
	}
	if err := s.root.MkdirAll(peer, 0700); err != nil {
		return f, err
	}
	peerInfo, err := s.root.Lstat(peer)
	if err != nil || !peerInfo.IsDir() || peerInfo.Mode()&os.ModeSymlink != 0 {
		return f, ErrCorrupt
	}
	tempID, err := NewID()
	if err != nil {
		return f, err
	}
	stage := filepath.Join(peer, ".incoming-"+tempID)
	if err := s.root.Mkdir(stage, 0700); err != nil {
		return f, err
	}
	defer s.root.RemoveAll(stage)
	blob, err := s.root.OpenFile(filepath.Join(stage, "content"), os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return f, err
	}
	defer blob.Close()
	s.active[stage] = true
	defer delete(s.active, stage)
	func() {
		// Network reads must not hold up other objects; active staging stays private.
		s.mu.Unlock()
		defer s.mu.Lock()
		hash := sha256.New()
		var written int64
		written, err = CopyContext(ctx, io.MultiWriter(blob, hash), io.LimitReader(source, f.Size+1))
		if err == nil && (written != f.Size || hex.EncodeToString(hash.Sum(nil)) != f.SHA256) {
			err = ErrContentMismatch
		}
		err = syncFile(blob, err)
	}()
	if err != nil {
		return f, err
	}
	if err := ctx.Err(); err != nil {
		return f, err
	}
	meta, err := s.root.OpenFile(filepath.Join(stage, "metadata.json"), os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return f, err
	}
	metadata, _ := json.Marshal(f)
	_, err = meta.Write(metadata)
	if err = syncFile(meta, err); err != nil {
		return f, err
	}
	if err = s.syncDir(stage); err != nil {
		return f, err
	}
	// Uploads release the lock while reading; another upload may have committed.
	if _, err := s.root.Lstat(path); err == nil {
		stored, _, readErr := s.verifiedInfoLocked(ctx, peer, f.ID, &f)
		if readErr != nil && !errors.Is(readErr, ErrAbsent) {
			if cancelled := ctx.Err(); cancelled != nil {
				return f, cancelled
			}
			if errors.Is(readErr, ErrConflict) {
				return f, ErrConflict
			}
			return f, errors.Join(ErrCorrupt, readErr)
		}
		if readErr == nil {
			return stored, nil
		}
	} else if !os.IsNotExist(err) {
		return f, err
	}
	if err := ctx.Err(); err != nil {
		return f, err
	}
	if err = renameContext(ctx, func() error { return s.root.Rename(stage, path) }); err != nil {
		return f, err
	}
	if err = s.syncDir(peer); err != nil {
		return f, err
	}
	if err = s.syncDir("."); err != nil {
		return f, err
	}
	stored, err := s.info(ctx, peer, f.ID, false, nil)
	return stored, err
}

func (s *Store) List(peer, cursor string, limit int) (protocol.FilePage, error) {
	return s.ListContext(context.Background(), peer, cursor, limit)
}

func (s *Store) ListContext(ctx context.Context, peer, cursor string, limit int) (protocol.FilePage, error) {
	s.uploads.RLock()
	defer s.uploads.RUnlock()
	s.mu.Lock()
	page := protocol.FilePage{Files: []protocol.File{}}
	_, err := s.path(peer, strings.Repeat("0", 32))
	s.mu.Unlock()
	if err := ctx.Err(); err != nil {
		return page, err
	}
	if err != nil {
		return page, err
	}
	if cursor != "" && !ValidID(cursor) || limit < 1 || limit > 100 {
		return page, errors.New("Invalid file page")
	}
	peerInfo, err := s.root.Lstat(peer)
	if os.IsNotExist(err) {
		return page, ctx.Err()
	}
	if err != nil {
		return page, err
	}
	if !peerInfo.IsDir() || peerInfo.Mode()&os.ModeSymlink != 0 {
		return page, ErrCorrupt
	}
	dir, err := s.root.Open(peer)
	if os.IsNotExist(err) {
		return page, ctx.Err()
	}
	if err != nil {
		return page, err
	}
	defer dir.Close()
	// Pin root lifetime, but leave inventory I/O and sorting independent of Put.
	var entries []string
	for {
		if err := ctx.Err(); err != nil {
			return page, err
		}
		batch, err := dir.Readdirnames(256)
		entries = append(entries, batch...)
		if err == io.EOF {
			break
		}
		if err != nil {
			return page, err
		}
	}
	if err := ctx.Err(); err != nil {
		return page, err
	}
	sort.Strings(entries)
	for _, id := range entries {
		if s.beforeListEntry != nil {
			s.beforeListEntry(peer, id)
		}
		if err := ctx.Err(); err != nil {
			return page, err
		}
		if !ValidID(id) {
			if strings.HasPrefix(id, ".incoming-") && ValidID(strings.TrimPrefix(id, ".incoming-")) {
				s.mu.Lock()
				damaged := s.damagedStage(peer, id)
				s.mu.Unlock()
				if damaged {
					addDiagnostic(&page, peer, id, "managed_object_corrupt")
				}
			} else {
				addDiagnostic(&page, peer, id, "managed_entry_unrecognized")
			}
			continue
		}
		if id <= cursor {
			continue
		}
		if len(page.Files) == limit {
			page.NextCursor = page.Files[len(page.Files)-1].ID
			break
		}
		s.mu.Lock()
		f, err := s.info(ctx, peer, id, true, nil)
		s.mu.Unlock()
		if err != nil {
			if cancelled := ctx.Err(); cancelled != nil {
				return page, cancelled
			}
			// The advisory inventory can outlive a concurrent removal or expiry.
			if errors.Is(err, ErrAbsent) {
				continue
			}
			if errors.Is(err, ErrCorrupt) {
				addDiagnostic(&page, peer, id, "managed_object_corrupt")
				continue
			}
			return page, err
		}
		page.Files = append(page.Files, f)
	}
	return page, ctx.Err()
}

func (s *Store) Remove(peer, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	path, err := s.path(peer, id)
	if err != nil {
		return err
	}
	if _, err := s.root.Lstat(path); os.IsNotExist(err) {
		info, peerErr := s.root.Lstat(peer)
		if os.IsNotExist(peerErr) {
			return nil
		}
		if peerErr != nil {
			return peerErr
		}
		if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return ErrCorrupt
		}
		// A caller may have stopped after deletion but before saving its own state.
		return s.syncDir(peer)
	} else if err != nil {
		return err
	}
	// Normal deletion checks only structural ownership, never a payload hash.
	if _, err := s.info(context.Background(), peer, id, false, nil); err != nil {
		return err
	}
	if err := s.removableParts(path); err != nil {
		return err
	}
	if err := s.root.RemoveAll(path); err != nil {
		return err
	}
	return s.syncDir(peer)
}

// Discard is an explicit owned-object action, including damaged or unknown members.
// Peer and object roots must be real directories; Root.RemoveAll does not follow child links.
func (s *Store) Discard(peer, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	path, err := s.path(peer, id)
	if err != nil {
		return err
	}
	for _, part := range []string{peer, path} {
		info, err := s.root.Lstat(part)
		if os.IsNotExist(err) {
			return nil
		}
		if err != nil {
			return err
		}
		if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return ErrCorrupt
		}
	}
	if err := s.root.RemoveAll(path); err != nil {
		return err
	}
	return s.syncDir(peer)
}

const maxDiagnostics = 16

func (s *Store) damagedStage(peer, id string) bool {
	path := filepath.Join(peer, id)
	if s.active[path] {
		return false
	}
	info, err := s.root.Lstat(path)
	if os.IsNotExist(err) {
		return false
	}
	return err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 || s.removableParts(path) != nil
}

func addDiagnostic(page *protocol.FilePage, peer, id, code string) {
	page.DiagnosticCount++
	if len(page.Diagnostics) < maxDiagnostics {
		// Unknown entry names are diagnostic labels, never reusable filesystem paths.
		if len(id) > 128 {
			id = id[:128]
		}
		if len(peer) > 128 {
			peer = peer[:128]
		}
		page.Diagnostics = append(page.Diagnostics, protocol.FileDiagnostic{Peer: peer, ID: id, Code: code})
	}
}

// Diagnostics projects the last maintenance observation; it never scans files.
func (s *Store) Diagnostics(peer string) (protocol.FilePage, error) {
	return s.DiagnosticsContext(context.Background(), peer)
}

func (s *Store) DiagnosticsContext(ctx context.Context, peer string) (protocol.FilePage, error) {
	page, _, err := s.DiagnosticSnapshot(ctx, peer)
	return page, err
}

// DiagnosticSnapshot returns bounded, peer-scoped maintenance observations.
// A zero time means that scope has not been scanned; stale observations do not
// replace exact requested-object validation. Status reads never wait on disk IO.
func (s *Store) DiagnosticSnapshot(ctx context.Context, peer string) (protocol.FilePage, time.Time, error) {
	page := protocol.FilePage{Files: []protocol.File{}}
	if err := ctx.Err(); err != nil {
		return page, time.Time{}, err
	}
	if peer != "" && !peerPattern.MatchString(peer) {
		return page, time.Time{}, errors.New("Exact peer ID required")
	}
	s.diagnosticMu.RLock()
	defer s.diagnosticMu.RUnlock()
	if s.diagnosticClosed {
		return page, time.Time{}, errors.New("File store closed")
	}
	observation := s.diagnostics
	if peer != "" {
		var ok bool
		observation, ok = s.diagnosticPeers[peer]
		if !ok {
			observation.at = s.diagnosticFullAt
		}
	}
	page = observation.page
	page.Files = []protocol.File{}
	page.Diagnostics = append([]protocol.FileDiagnostic(nil), page.Diagnostics...)
	if err := ctx.Err(); err != nil {
		return page, observation.at, err
	}
	return page, observation.at, observation.err
}

func (s *Store) removableParts(path string) error {
	directory, err := s.root.Open(path)
	if err != nil {
		return err
	}
	parts, err := directory.ReadDir(3)
	directory.Close()
	if err != nil && !errors.Is(err, io.EOF) {
		return err
	}
	if len(parts) > 2 {
		return ErrCorrupt
	}
	for _, part := range parts {
		info, err := s.root.Lstat(filepath.Join(path, part.Name()))
		if err != nil || !info.Mode().IsRegular() || (part.Name() != "content" && part.Name() != "metadata.json") {
			return ErrCorrupt
		}
	}
	return nil
}

func (s *Store) Close() error {
	s.uploads.Lock()
	defer s.uploads.Unlock()
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil
	}
	s.closed = true
	s.diagnosticMu.Lock()
	s.diagnosticClosed = true
	s.diagnosticMu.Unlock()
	return s.root.Close()
}

// Removes expired managed bytes, including orphaned transfers. Reads never extend retention.
func (s *Store) PruneExpired(now time.Time, age time.Duration) (time.Duration, error) {
	return s.pruneExpired(now, age, "", nil)
}

// PrunePeerExpired preserves the caller's committed reference snapshot. The
// caller must serialize reference admission/removal until this scan returns.
func (s *Store) PrunePeerExpired(now time.Time, age time.Duration, peer string, keep map[string]bool) (time.Duration, error) {
	if !peerPattern.MatchString(peer) {
		return 0, errors.New("Exact peer ID required")
	}
	return s.pruneExpired(now, age, peer, func(id string) (bool, func(), error) { return keep[id], nil, nil })
}

// PrunePeerExpiredGuarded acquires an owner's current reference guard only at
// the exact final deletion boundary. Enumeration/inspection never owns it.
// The callback runs before mu; release must unlock the owner's guard.
func (s *Store) PrunePeerExpiredGuarded(now time.Time, age time.Duration, peer string, guard func(string) (bool, func(), error)) (time.Duration, error) {
	if !peerPattern.MatchString(peer) || guard == nil {
		return 0, errors.New("Exact peer and reference guard required")
	}
	return s.pruneExpired(now, age, peer, guard)
}

// Peers includes only real managed peer directories, never caller-selected paths.
func (s *Store) Peers() ([]string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil, errors.New("File store closed")
	}
	directory, err := s.root.Open(".")
	if err != nil {
		return nil, err
	}
	entries, err := directory.ReadDir(-1)
	directory.Close()
	if err != nil {
		return nil, err
	}
	peers := []string{}
	var scanErr error
	for _, entry := range entries {
		if !peerPattern.MatchString(entry.Name()) || !entry.IsDir() || entry.Type()&os.ModeSymlink != 0 {
			scanErr = ErrCorrupt
			continue
		}
		peers = append(peers, entry.Name())
	}
	return peers, scanErr
}

func (s *Store) pruneExpired(now time.Time, age time.Duration, onlyPeer string, guard func(string) (bool, func(), error)) (next time.Duration, scanErr error) {
	s.maintenance.Lock()
	defer s.maintenance.Unlock()
	s.uploads.RLock()
	defer s.uploads.RUnlock()
	s.mu.Lock()
	invalid := s.closed || age <= 0
	s.mu.Unlock()
	if invalid {
		return 0, errors.New("Invalid retention scan")
	}
	next = age
	rootObservation := diagnosticSnapshot{at: now}
	observations := map[string]diagnosticSnapshot{}
	issue := func(peer, id, code string) {
		if peerPattern.MatchString(peer) {
			record := observations[peer]
			addDiagnostic(&record.page, peer, id, code)
			record.at = now
			observations[peer] = record
		} else {
			addDiagnostic(&rootObservation.page, peer, id, code)
		}
	}
	failure := func(peer string, err error) {
		if scanErr == nil {
			scanErr = err // Keep the first IO/ownership failure bounded.
		}
		if peerPattern.MatchString(peer) {
			record := observations[peer]
			if record.err == nil {
				record.err = err
			}
			record.at = now
			observations[peer] = record
		} else if rootObservation.err == nil {
			rootObservation.err = err
		}
	}
	defer func() {
		s.diagnosticMu.Lock()
		defer s.diagnosticMu.Unlock()
		if onlyPeer == "" {
			s.diagnosticRoot = rootObservation
		}
		if onlyPeer == "" && scanErr == nil {
			s.diagnosticPeers = observations
			s.diagnosticFullAt = now
		} else {
			if s.diagnosticPeers == nil {
				s.diagnosticPeers = map[string]diagnosticSnapshot{}
			}
			for peer, record := range observations {
				s.diagnosticPeers[peer] = record
			}
		}
		// Scoped passes cannot erase another scope or claim a new full-store
		// observation. Keep aggregate counts even when only samples fit.
		all := diagnosticSnapshot{at: s.diagnosticFullAt}
		merge := func(record diagnosticSnapshot) {
			all.page.DiagnosticCount += record.page.DiagnosticCount
			for _, issue := range record.page.Diagnostics {
				if len(all.page.Diagnostics) < maxDiagnostics {
					all.page.Diagnostics = append(all.page.Diagnostics, issue)
				}
			}
			if all.err == nil {
				all.err = record.err
			}
		}
		merge(s.diagnosticRoot)
		names := make([]string, 0, len(s.diagnosticPeers))
		for peer := range s.diagnosticPeers {
			names = append(names, peer)
		}
		sort.Strings(names)
		for _, peer := range names {
			merge(s.diagnosticPeers[peer])
		}
		s.diagnostics = all
	}()
	peers := []string{onlyPeer}
	if onlyPeer == "" {
		directory, err := s.root.Open(".")
		if err != nil {
			failure("", err)
			return 0, err
		}
		peers, err = directory.Readdirnames(-1)
		directory.Close()
		if err != nil {
			failure("", err)
			return 0, err
		}
	}
	sort.Strings(peers)
	for _, peer := range peers {
		if peerPattern.MatchString(peer) {
			observations[peer] = diagnosticSnapshot{at: now}
		}
		info, err := s.root.Lstat(peer)
		if os.IsNotExist(err) {
			continue
		}
		if err != nil {
			failure(peer, err)
			continue
		}
		if !peerPattern.MatchString(peer) || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			issue(peer, "", "managed_peer_unrecognized")
			continue
		}
		directory, err := s.root.Open(peer)
		if os.IsNotExist(err) {
			continue
		}
		if err != nil {
			failure(peer, err)
			continue
		}
		objects, err := directory.Readdirnames(-1)
		directory.Close()
		if err != nil {
			failure(peer, err)
			continue
		}
		sort.Strings(objects)
		for _, id := range objects {
			incoming := strings.HasPrefix(id, ".incoming-") && ValidID(strings.TrimPrefix(id, ".incoming-"))
			if !ValidID(id) && !incoming {
				issue(peer, id, "managed_entry_unrecognized")
				continue
			}
			if s.beforeMaintenanceObject != nil {
				s.beforeMaintenanceObject(peer, id)
			}
			retained, until, damaged, err := s.pruneObject(now, age, peer, id, incoming, guard)
			if err != nil {
				failure(peer, err)
			}
			if damaged {
				issue(peer, id, "managed_object_corrupt")
			}
			if !retained {
				continue
			}
			if until > 0 && until < next {
				next = until
			}
			// The root lifetime is pinned, but no owner/store lock is held for
			// advisory structural inspection of surviving objects.
			_, blob, inspectErr := s.openPath(context.Background(), peer, id, filepath.Join(peer, id), false, nil)
			if blob != nil {
				blob.Close()
			}
			if inspectErr != nil && !errors.Is(inspectErr, ErrAbsent) {
				issue(peer, id, "managed_object_corrupt")
			}
		}
	}
	return next, scanErr
}

func (s *Store) pruneObject(now time.Time, age time.Duration, peer, id string, incoming bool, guard func(string) (bool, func(), error)) (retained bool, until time.Duration, damaged bool, err error) {
	path := filepath.Join(peer, id)
	info, err := s.root.Lstat(path)
	if os.IsNotExist(err) {
		return false, 0, false, nil
	}
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return false, 0, true, err
	}
	until = info.ModTime().Add(age).Sub(now)
	if !incoming && until > 0 {
		return true, until, false, nil
	}
	protected := false
	if !incoming && guard != nil {
		var release func()
		protected, release, err = guard(id)
		if release != nil {
			defer release()
		}
		if err != nil {
			return false, until, false, err
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if incoming && s.active[path] {
		return false, until, false, nil
	}
	// An upload may have replaced an enumerated old object before its guard.
	info, err = s.root.Lstat(path)
	if os.IsNotExist(err) {
		return false, 0, false, nil
	}
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return false, 0, true, err
	}
	until = info.ModTime().Add(age).Sub(now)
	if !incoming && (until > 0 || protected) {
		return true, until, false, nil
	}
	if err := s.removableParts(path); err != nil {
		return false, until, true, nil
	}
	if err := s.root.RemoveAll(path); err != nil {
		return false, until, false, err
	}
	return false, until, false, s.syncDir(peer)
}

func syncDirectory(root *os.Root, path string) error {
	if runtime.GOOS == "windows" {
		return nil
	}
	f, err := root.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	return f.Sync()
}

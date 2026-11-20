package pc

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"sort"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

// Presence commits dispatch intent and exact input ownership before any native send, including empty inputs.
const jobInputsName = "job-inputs.json"
const jobOutputName = "job-output.json"

var ErrJobFileOwnership = errors.New("job_file_ownership_unconfirmed")
var ErrJobFileReferenced = errors.New("job_file_still_referenced")

type jobFileRecord struct {
	Version      string          `json:"version"`
	AppID        string          `json:"appId"`
	JobID        string          `json:"jobId"`
	ThreadID     string          `json:"threadId"`
	RequestHash  string          `json:"requestHash"`
	EnvelopeHash string          `json:"envelopeHash,omitempty"`
	Files        []protocol.File `json:"files"`
}

type storedJobFiles struct {
	file protocol.File
	job  jobFileRecord
}

func digestJSON(value any) string {
	raw, _ := json.Marshal(value)
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:])
}

func validDigest(value string) bool { return handoffName.MatchString(value + ".md") }

// Caller owns creationMu. Native receipts, not producer-supplied IDs, establish ownership.
func (s *Service) jobFileOwnerLocked(peer, app, job, thread string) (creationInput, string, error) {
	var request creationInput
	if s.creations == nil || s.peers[peer] == nil || !token.MatchString(app) || !token.MatchString(job) || !nodeID.MatchString(thread) {
		return request, "", ErrJobFileOwnership
	}
	cleanup, err := s.cleanupAuthorized(peer, app, job)
	if err != nil {
		return request, "", err
	}
	if cleanup {
		return request, "", ErrJobFileOwnership
	}
	f, raw, err := s.creations.Read(peer, creationID(peer, app, job, "request"))
	if errors.Is(err, files.ErrAbsent) {
		return request, "", ErrJobFileOwnership
	}
	if err != nil {
		return request, "", err
	}
	if f.Name != "creation-request.json" || protocol.DecodeStrict(raw, &request) != nil || request.AppID != app || request.RequestID != job || request.CreatedAt <= 0 || request.Project != s.cfg.IntegrationDir || !protocol.ValidServiceTier(request.ServiceTier) {
		return request, "", files.ErrCorrupt
	}
	canonical, _ := json.Marshal(request)
	if !bytes.Equal(raw, canonical) {
		return request, "", files.ErrCorrupt
	}
	accepted, err := s.creations.CommittedAt(peer, f.ID)
	if err != nil {
		return request, "", err
	}
	if !accepted.Add(protocol.Retention).After(time.Now()) {
		return request, "", ErrJobFileOwnership
	}
	hash := digestJSON(request)
	f, raw, err = s.creations.Read(peer, creationID(peer, app, job, "result"))
	var receipt creationReceipt
	if errors.Is(err, files.ErrAbsent) {
		return request, "", ErrJobFileOwnership
	}
	if err != nil {
		return request, "", err
	}
	if f.Name != "creation-result.json" || protocol.DecodeStrict(raw, &receipt) != nil || receipt.RequestHash != hash {
		return request, "", files.ErrCorrupt
	}
	if receipt.ThreadID != thread {
		return request, "", ErrJobFileOwnership
	}
	if _, err := s.readDeletedCreation(peer, request); err == nil {
		return request, "", ErrJobFileOwnership
	} else if !errors.Is(err, files.ErrAbsent) {
		return request, "", err
	}
	return request, hash, nil
}

func (s *Service) readJobFilesLocked(peer string, f protocol.File) (jobFileRecord, error) {
	var record jobFileRecord
	if f.Size > 32<<10 {
		return record, ErrJobFileOwnership
	}
	actual, raw, err := s.creations.Read(peer, f.ID)
	if err != nil || actual != f || protocol.DecodeStrict(raw, &record) != nil || record.Version != "v1" || !token.MatchString(record.AppID) || !token.MatchString(record.JobID) || !nodeID.MatchString(record.ThreadID) || !validDigest(record.RequestHash) || record.Files == nil || len(record.Files) > 16 {
		return record, errors.Join(ErrJobFileOwnership, err)
	}
	kind := "inputs"
	if f.Name == jobOutputName {
		if len(record.Files) != 1 || record.EnvelopeHash != "" {
			return record, ErrJobFileOwnership
		}
		kind = "output:" + record.Files[0].ID
	} else if f.Name != jobInputsName || !validDigest(record.EnvelopeHash) {
		return record, ErrJobFileOwnership
	}
	if f.ID != creationID(peer, record.AppID, record.JobID, kind) {
		return record, ErrJobFileOwnership
	}
	seen := map[string]bool{}
	for _, file := range record.Files {
		if !protocol.ValidFile(file) || seen[file.ID] {
			return record, ErrJobFileOwnership
		}
		seen[file.ID] = true
	}
	return record, nil
}

func (s *Service) jobFilesLocked(peer string) ([]storedJobFiles, error) {
	snapshot := s.jobFileSnapshotLocked(peer)
	if snapshot.err != nil {
		return nil, snapshot.err
	}
	refs := make([]storedJobFiles, 0, len(snapshot.records))
	for _, ref := range snapshot.records {
		refs = append(refs, ref)
	}
	return refs, nil
}

func (s *Service) scanJobFiles(peer string) ([]storedJobFiles, error) {
	if s.creations == nil {
		return nil, nil // Isolated generic transfer hosts have no job owner store.
	}
	records, unknown, err := s.creationRecords(peer)
	if err != nil || unknown {
		return nil, errors.Join(ErrJobFileOwnership, err)
	}
	refs := []storedJobFiles{}
	for _, f := range records {
		switch f.Name {
		case jobInputsName, jobOutputName:
			record, err := s.readJobFilesLocked(peer, f)
			if err != nil {
				return nil, err
			}
			refs = append(refs, storedJobFiles{f, record})
		case "creation-request.json", "creation-result.json", "creation-deleted.json", "creation-cleanup.json", processDiagnosticName:
		default:
			return nil, ErrJobFileOwnership
		}
	}
	return refs, nil
}

// The adapter keeps this exact lease through handoff creation and the native send.
func (s *Service) beginJobHandoff(ctx context.Context, peer string, r Request) (func(), error) {
	if protocol.Validate(r) != nil {
		return nil, ErrJobFileOwnership
	}
	release, err := s.claimCreation(ctx, peer, r.AppID, r.JobID)
	if err != nil {
		return nil, err
	}
	s.creationMu.Lock()
	defer s.creationMu.Unlock()
	request, hash, err := s.jobFileOwnerLocked(peer, r.AppID, r.JobID, r.ThreadID)
	if err == nil && request.ServiceTier != r.ServiceTier {
		err = ErrJobFileOwnership
	}
	if err == nil {
		for _, expected := range r.InputFiles {
			if s.inbox == nil {
				err = ErrJobFileOwnership
				break
			}
			actual, infoErr := s.inbox.InfoContext(ctx, peer, expected.ID)
			if infoErr != nil || actual != expected {
				err = errors.Join(ErrJobFileOwnership, infoErr)
				break
			}
		}
	}
	if err == nil {
		// Epoch is a runtime attachment identity; immutable job content survives PC restarts.
		r.Epoch = ""
		record := jobFileRecord{Version: "v1", AppID: r.AppID, JobID: r.JobID, ThreadID: r.ThreadID, RequestHash: hash, EnvelopeHash: digestJSON(r), Files: append([]protocol.File{}, r.InputFiles...)}
		raw, _ := json.Marshal(record)
		f, _ := files.Describe(creationID(peer, r.AppID, r.JobID, "inputs"), jobInputsName, raw)
		_, err = s.creations.PutContext(ctx, peer, f, bytes.NewReader(raw))
		s.jobFileMutationLocked(peer, storedJobFiles{f, record}, false, err)
	}
	if err != nil {
		// release also takes creationMu.
		s.creationMu.Unlock()
		release()
		s.creationMu.Lock()
		return nil, err
	}
	return release, nil
}

// Persist the exact output claim before bytes can commit, including interrupted uploads.
func (s *Service) QueueJobFileReader(ctx context.Context, peer, app, job, thread string, f protocol.File, source io.Reader) (protocol.File, error) {
	ctx, cancel := s.FileContext(ctx)
	defer cancel()
	if !protocol.ValidFile(f) || s.outbox == nil {
		return protocol.File{}, ErrJobFileOwnership
	}
	release, err := s.claimCreation(ctx, peer, app, job)
	if err != nil {
		return protocol.File{}, err
	}
	defer release()
	fileRelease, err := s.claimCreation(ctx, peer, "outgoing-file", f.ID)
	if err != nil {
		return protocol.File{}, err
	}
	defer fileRelease()
	s.creationMu.Lock()
	_, hash, err := s.jobFileOwnerLocked(peer, app, job, thread)
	if err == nil {
		accepted, timeErr := s.creations.CommittedAt(peer, creationID(peer, app, job, "request"))
		if timeErr != nil {
			err = timeErr
		} else {
			var cancel context.CancelFunc
			ctx, cancel = context.WithDeadline(ctx, accepted.Add(protocol.Retention))
			defer cancel()
			if closer, ok := source.(io.Closer); ok {
				stop := context.AfterFunc(ctx, func() { _ = closer.Close() })
				defer stop()
			}
		}
	}
	var input jobFileRecord
	if err == nil {
		var inputFile protocol.File
		inputFile, err = s.creations.Info(peer, creationID(peer, app, job, "inputs"))
		if err == nil {
			input, err = s.readJobFilesLocked(peer, inputFile)
		} else if errors.Is(err, files.ErrAbsent) {
			err = ErrJobFileOwnership
		}
	}
	if err == nil && (input.AppID != app || input.JobID != job || input.ThreadID != thread || input.RequestHash != hash) {
		err = ErrJobFileOwnership
	}
	if err == nil {
		for _, incoming := range input.Files {
			if incoming.ID == f.ID {
				err = files.ErrConflict
				break
			}
		}
	}
	if err == nil {
		err = s.checkOutputClaimsLocked(peer, app, job, []protocol.File{f})
	}
	if err == nil {
		record := jobFileRecord{Version: "v1", AppID: app, JobID: job, ThreadID: thread, RequestHash: hash, Files: []protocol.File{f}}
		raw, _ := json.Marshal(record)
		descriptor, _ := files.Describe(creationID(peer, app, job, "output:"+f.ID), jobOutputName, raw)
		_, err = s.creations.PutContext(ctx, peer, descriptor, bytes.NewReader(raw))
		s.jobFileMutationLocked(peer, storedJobFiles{descriptor, record}, false, err)
	}
	s.creationMu.Unlock()
	if err != nil {
		return protocol.File{}, err
	}
	return s.outbox.PutContext(ctx, peer, f, source)
}

// Caller owns exact cleanup lease + creationMu and a durable authorized cleanup journal.
func (s *Service) releaseJobFilesLocked(peer, app, job string) error {
	refs, err := s.jobFilesLocked(peer)
	if err != nil {
		return err
	}
	// Cleanup revalidates its exact ledgers before using their cached claims to
	// remove payloads. Other owners' immutable claims remain conservative keeps.
	for _, ref := range refs {
		if ref.job.AppID == app && ref.job.JobID == job {
			actual, err := s.readJobFilesLocked(peer, ref.file)
			if err != nil {
				return err
			}
			if !sameStoredJobFiles(ref, storedJobFiles{ref.file, actual}) {
				return ErrJobFileOwnership
			}
		}
	}
	for _, store := range []*files.Store{s.inbox, s.outbox} {
		if store == nil {
			continue
		}
		keep, remove := map[string]bool{}, map[string]protocol.File{}
		for _, ref := range refs {
			if (ref.file.Name == jobOutputName) != (store == s.outbox) {
				continue
			}
			for _, f := range ref.job.Files {
				if ref.job.AppID == app && ref.job.JobID == job {
					remove[f.ID] = f
				} else {
					keep[f.ID] = true
				}
			}
		}
		for id, expected := range remove {
			if !keep[id] {
				actual, err := store.Info(peer, id)
				if errors.Is(err, files.ErrAbsent) {
					continue
				}
				if err != nil {
					return err
				}
				if actual != expected {
					return files.ErrConflict
				}
				if err := store.Remove(peer, id); err != nil && !errors.Is(err, files.ErrAbsent) {
					return err
				}
			}
		}
	}
	for _, ref := range refs {
		if ref.job.AppID == app && ref.job.JobID == job {
			err := s.creations.Remove(peer, ref.file.ID)
			if errors.Is(err, files.ErrAbsent) {
				err = nil
			}
			s.jobFileMutationLocked(peer, ref, true, err)
			if err != nil {
				return err
			}
		}
	}
	return nil
}

// All publishers hold the exact output transfer leases as well as creationMu.
func (s *Service) checkOutputClaimsLocked(peer, app, job string, outputs []protocol.File) error {
	snapshot := s.jobFileSnapshotLocked(peer)
	if snapshot.err != nil {
		return snapshot.err
	}
	ownerOutputs := snapshot.outputs[app+"/"+job]
	count, added := len(ownerOutputs), map[string]bool{}
	for _, candidate := range outputs {
		for _, ref := range snapshot.claims[candidate.ID] {
			for _, existing := range ref.job.Files {
				if existing.ID == candidate.ID && (ref.file.Name != jobOutputName || ref.job.AppID != app || ref.job.JobID != job || existing != candidate) {
					return files.ErrConflict
				}
			}
		}
	}
	for _, output := range outputs {
		if !ownerOutputs[output.ID] && !added[output.ID] {
			count++
			added[output.ID] = true
		}
		if s.outbox == nil {
			return ErrJobFileOwnership
		}
		actual, err := s.outbox.Info(peer, output.ID)
		if errors.Is(err, files.ErrAbsent) {
			continue
		}
		if err != nil {
			return err
		}
		if actual != output {
			return files.ErrConflict
		}
	}
	if count > 16 {
		return ErrJobFileOwnership
	}
	return nil
}

// Only startup and the existing maintenance worker collect whole-store history.
func (s *Service) pruneTransferFiles(now time.Time) (time.Duration, error) {
	return s.prunePeerTransferFiles(now, "")
}

func (s *Service) prunePeerTransferFiles(now time.Time, onlyPeer string) (time.Duration, error) {
	next, passErr := time.Hour, error(nil)
	stores := []*files.Store{s.inbox, s.outbox}
	peerSet := map[string]bool{}
	for _, peer := range s.cfg.AllowedPeers {
		peerSet[peer] = true
	}
	for _, store := range []*files.Store{s.inbox, s.outbox, s.creations} {
		if store == nil {
			continue
		}
		peers, err := store.Peers()
		passErr = errors.Join(passErr, err)
		for _, peer := range peers {
			peerSet[peer] = true
		}
	}
	peers := make([]string, 0, len(peerSet))
	for peer := range peerSet {
		peers = append(peers, peer)
	}
	sort.Strings(peers)
	for _, peer := range peers {
		if onlyPeer != "" && peer != onlyPeer {
			continue
		}
		fresh, err := s.refreshJobFiles(peer, s.scanJobFiles)
		if err != nil {
			passErr = errors.Join(passErr, err)
		}
		if !fresh {
			if next > time.Second {
				next = time.Second
			}
			continue
		}
		for _, store := range stores {
			if store == nil {
				continue
			}
			delay, err := store.PrunePeerExpiredGuarded(now, protocol.Retention, peer, s.filePruneGuard(peer, store == s.outbox))
			passErr = errors.Join(passErr, err)
			if delay < next {
				next = delay
			}
		}
	}
	if passErr != nil {
		s.fileRetentionStatus(passErr)
		return time.Minute, passErr
	}
	if onlyPeer == "" {
		s.fileRetentionStatus(nil)
	}
	return next, nil
}

func (s *Service) fileRetentionStatus(err error) {
	code := ""
	if err != nil {
		code = "file_retention_unavailable"
		if errors.Is(err, ErrJobFileOwnership) {
			code = ErrJobFileOwnership.Error()
		}
	}
	s.mu.Lock()
	s.fileRetentionCode = code
	s.mu.Unlock()
}

func (s *Service) fileReferencedLocked(peer, id string, output bool) error {
	protected, err := s.fileProtectedLocked(peer, id, output)
	if err != nil {
		return err
	}
	if protected {
		return ErrJobFileReferenced
	}
	return nil
}

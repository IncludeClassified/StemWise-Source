package pc

import (
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"phonepcbridge/protocol"
)

// The generated attachment owns this metadata; native chat names never grant ownership.
type handoffOwner struct {
	CreatedAt int64  `json:"createdAt"`
	Peer      string `json:"peer"`
	AppID     string `json:"appId"`
	JobID     string `json:"jobId"`
	ThreadID  string `json:"threadId"`
}

func (s *Service) handoffEntries() ([]os.DirEntry, error) {
	directory := filepath.Join(s.cfg.DataDir, "handoffs")
	info, err := os.Lstat(directory)
	if err != nil {
		return nil, err
	}
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return nil, errors.New("handoff_storage_unavailable")
	}
	return os.ReadDir(directory)
}

func (s *Service) readHandoff(name string) (handoffFile, *handoffOwner, error) {
	file, err := s.readHandoffFile(name)
	if err != nil {
		return file, nil, err
	}
	owner, err := s.parseHandoff(file.markdown)
	return file, owner, err
}

func (s *Service) readHandoffFile(name string) (handoffFile, error) {
	file := handoffFile{path: filepath.Join(s.cfg.DataDir, "handoffs", name)}
	info, err := os.Lstat(file.path)
	if err != nil || !handoffName.MatchString(name) || !info.Mode().IsRegular() || info.Size() > protocol.MaxHandoffBytes {
		return file, errors.New("handoff_owner_unconfirmed")
	}
	raw, err := os.ReadFile(file.path)
	if err != nil {
		return file, err
	}
	if len(raw) > protocol.MaxHandoffBytes {
		return file, errors.New("handoff_owner_unconfirmed")
	}
	file.markdown = string(raw)
	return file, nil
}

func (s *Service) parseHandoff(markdown string) (*handoffOwner, error) {
	blocks := strings.SplitN(markdown, "\n\n", 3)
	if len(blocks) != 3 || blocks[0] != "# Bridge developer handoff" {
		return nil, errors.New("handoff_owner_unconfirmed")
	}
	fields := map[string]string{}
	for _, line := range strings.Split(blocks[1], "\n") {
		key, value, ok := strings.Cut(line, ": ")
		if _, duplicate := fields[key]; !ok || duplicate {
			return nil, errors.New("handoff_owner_unconfirmed")
		}
		fields[key] = value
	}
	generated, err := time.Parse(time.RFC3339, fields["Generated UTC"])
	sequence, sequenceErr := strconv.ParseInt(fields["Sequence"], 10, 64)
	if err != nil || sequenceErr != nil || sequence < 1 || !handoffName.MatchString(fields["Request fingerprint"]+".md") || len(fields) != 10 || fields["Contract"] != "bridge-developer-v2" || fields["PC"] != s.cfg.Identity || fields["Private PC host"] != s.cfg.Host || !nodeID.MatchString(fields["Paired sender"]) || !nodeID.MatchString(fields["Developer thread"]) || !token.MatchString(fields["Host epoch"]) || fields["Operation"] != "text" {
		return nil, errors.New("handoff_owner_unconfirmed")
	}
	const envelope = "\n## This immutable app/job envelope\n\n"
	_, body, exists := strings.Cut(markdown, envelope)
	if !exists || strings.Contains(body, envelope) {
		return nil, errors.New("handoff_owner_unconfirmed")
	}
	lines := strings.SplitN(body, "\n", 4)
	if len(lines) != 4 {
		return nil, errors.New("handoff_owner_unconfirmed")
	}
	app := strings.TrimPrefix(lines[0], "App ID: ")
	job := strings.TrimPrefix(lines[1], "Job ID: ")
	if !token.MatchString(app) || !token.MatchString(job) || lines[2] != "Developer thread: "+fields["Developer thread"] {
		return nil, errors.New("handoff_owner_unconfirmed")
	}
	return &handoffOwner{generated.UnixMilli(), fields["Paired sender"], app, job, fields["Developer thread"]}, nil
}

type handoffDiagnostic struct {
	count      int
	oldest     *handoffOwner
	code       string
	observedAt int64
}

// Status reads never inspect storage or wait on a writer/maintenance scan.
func (s *Service) handoffSnapshot() (int, *handoffOwner, string, int64) {
	s.handoffStatusMu.Lock()
	defer s.handoffStatusMu.Unlock()
	status := s.handoffStatus
	if (s.handoffDirty || status.observedAt == 0) && s.cfg.DataDir != "" && status.code == "" {
		status.code = "handoff_status_pending"
	}
	if status.oldest != nil {
		owner := *status.oldest
		status.oldest = &owner
	}
	return status.count, status.oldest, status.code, status.observedAt
}

func (s *Service) markHandoffChanged() {
	s.handoffStatusMu.Lock()
	s.handoffVersion++
	s.handoffDirty = true
	s.handoffStatusMu.Unlock()
	select {
	case s.retentionChanged <- struct{}{}:
	default:
	}
}

func (s *Service) refreshHandoffStatus(scan func() (int, *handoffOwner, string)) bool {
	s.handoffStatusMu.Lock()
	version := s.handoffVersion
	s.handoffStatusMu.Unlock()
	count, oldest, code := scan()
	s.handoffStatusMu.Lock()
	defer s.handoffStatusMu.Unlock()
	if s.handoffVersion != version {
		return false
	}
	s.handoffStatus = handoffDiagnostic{count, oldest, code, time.Now().UnixMilli()}
	s.handoffDirty = false
	return true
}

func (s *Service) scanHandoffStatus() (int, *handoffOwner, string) {
	if s.cfg.DataDir == "" {
		return 0, nil, ""
	}
	entries, err := s.handoffEntries()
	if os.IsNotExist(err) {
		return 0, nil, ""
	}
	if err != nil {
		return 0, nil, "handoff_storage_unavailable"
	}
	var oldest *handoffOwner
	code := ""
	for _, entry := range entries {
		_, owner, err := s.readHandoff(entry.Name())
		if err != nil {
			code = "handoff_owner_unconfirmed"
			continue
		}
		if oldest == nil || owner.CreatedAt < oldest.CreatedAt {
			oldest = owner
		}
	}
	return len(entries), oldest, code
}

// Called only after the matching immutable creation receipt has a durable deletion tombstone.
func (s *Service) cleanDeletedHandoffs(peer, app, job, thread string) error {
	s.handoffMu.Lock()
	defer s.handoffMu.Unlock()
	if s.cfg.DataDir == "" {
		return nil
	}
	entries, err := s.handoffEntries()
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return &creationError{code: "chat_cleanup_unavailable", unknown: true}
	}
	for _, entry := range entries {
		file, owner, err := s.readHandoff(entry.Name())
		if err != nil || owner.Peer != peer || owner.AppID != app || owner.JobID != job || (thread != "" && owner.ThreadID != thread) {
			continue
		}
		if s.removeHandoff(file) != nil {
			return &creationError{code: "chat_cleanup_unavailable", unknown: true}
		}
		for key, attached := range s.handoffs {
			remaining := attached[:0]
			for _, context := range attached {
				if context.path != file.path {
					remaining = append(remaining, context)
				}
			}
			clear(attached[len(remaining):])
			if len(remaining) == 0 {
				delete(s.handoffs, key)
			} else {
				s.handoffs[key] = remaining
			}
		}
	}
	return nil
}

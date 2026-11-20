package pc

// Durable job ledgers remain authoritative. This projection is loaded before
// serving, updated at every ledger mutation, and audited by existing maintenance.
type jobFileSnapshot struct {
	records map[string]storedJobFiles
	claims  map[string]map[string]storedJobFiles
	outputs map[string]map[string]bool
	err     error
}

func newJobFileSnapshot(refs []storedJobFiles, err error) *jobFileSnapshot {
	snapshot := &jobFileSnapshot{records: map[string]storedJobFiles{}, claims: map[string]map[string]storedJobFiles{}, outputs: map[string]map[string]bool{}, err: err}
	for _, ref := range refs {
		snapshot.set(ref)
	}
	return snapshot
}

func (snapshot *jobFileSnapshot) remove(id string) {
	old, ok := snapshot.records[id]
	if !ok {
		return
	}
	delete(snapshot.records, id)
	for _, file := range old.job.Files {
		delete(snapshot.claims[file.ID], id)
		if len(snapshot.claims[file.ID]) == 0 {
			delete(snapshot.claims, file.ID)
		}
		if old.file.Name == jobOutputName {
			owner := old.job.AppID + "/" + old.job.JobID
			delete(snapshot.outputs[owner], file.ID)
			if len(snapshot.outputs[owner]) == 0 {
				delete(snapshot.outputs, owner)
			}
		}
	}
}

func (snapshot *jobFileSnapshot) set(ref storedJobFiles) {
	snapshot.remove(ref.file.ID)
	snapshot.records[ref.file.ID] = ref
	for _, file := range ref.job.Files {
		if snapshot.claims[file.ID] == nil {
			snapshot.claims[file.ID] = map[string]storedJobFiles{}
		}
		snapshot.claims[file.ID][ref.file.ID] = ref
		if ref.file.Name == jobOutputName {
			owner := ref.job.AppID + "/" + ref.job.JobID
			if snapshot.outputs[owner] == nil {
				snapshot.outputs[owner] = map[string]bool{}
			}
			snapshot.outputs[owner][file.ID] = true
		}
	}
}

// Caller owns creationMu. Normal construction initializes every stored peer;
// lazy initialization also supports isolated generic service fixtures.
func (s *Service) jobFileSnapshotLocked(peer string) *jobFileSnapshot {
	if s.jobFileSnapshots == nil {
		s.jobFileSnapshots = map[string]*jobFileSnapshot{}
	}
	if snapshot := s.jobFileSnapshots[peer]; snapshot != nil {
		return snapshot
	}
	refs, err := s.scanJobFiles(peer)
	snapshot := newJobFileSnapshot(refs, err)
	s.jobFileSnapshots[peer] = snapshot
	return snapshot
}

func (s *Service) jobFileMutationLocked(peer string, ref storedJobFiles, remove bool, err error) {
	if s.jobFileVersions == nil {
		s.jobFileVersions = map[string]uint64{}
	}
	s.jobFileVersions[peer]++
	snapshot := s.jobFileSnapshotLocked(peer)
	if err != nil {
		// A failed commit/removal may already have changed durable state.
		snapshot.err = err
		return
	}
	if remove {
		snapshot.remove(ref.file.ID)
	} else {
		snapshot.set(ref)
	}
}

// Collection may read every ledger, so it never owns creationMu. A concurrent
// committed mutation makes that collection stale; it cannot replace newer refs.
func (s *Service) refreshJobFiles(peer string, scan func(string) ([]storedJobFiles, error)) (bool, error) {
	s.creationMu.Lock()
	version := s.jobFileVersions[peer]
	s.creationMu.Unlock()
	refs, err := scan(peer)
	s.creationMu.Lock()
	defer s.creationMu.Unlock()
	if s.jobFileVersions[peer] != version {
		return false, nil
	}
	if s.jobFileSnapshots == nil {
		s.jobFileSnapshots = map[string]*jobFileSnapshot{}
	}
	s.jobFileSnapshots[peer] = newJobFileSnapshot(refs, err)
	return true, err
}

// Caller owns creationMu; lookup touches only claims for the requested object.
func (s *Service) fileProtectedLocked(peer, id string, output bool) (bool, error) {
	snapshot := s.jobFileSnapshotLocked(peer)
	if snapshot.err != nil {
		return false, snapshot.err
	}
	for _, ref := range snapshot.claims[id] {
		if (ref.file.Name == jobOutputName) == output {
			return true, nil
		}
	}
	return false, nil
}

func (s *Service) filePruneGuard(peer string, output bool) func(string) (bool, func(), error) {
	return func(id string) (bool, func(), error) {
		s.creationMu.Lock()
		protected, err := s.fileProtectedLocked(peer, id, output)
		return protected, s.creationMu.Unlock, err
	}
}

func sameStoredJobFiles(a, b storedJobFiles) bool {
	return a.file == b.file && digestJSON(a.job) == digestJSON(b.job)
}

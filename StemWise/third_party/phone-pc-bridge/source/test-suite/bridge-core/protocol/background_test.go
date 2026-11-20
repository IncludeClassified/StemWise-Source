package protocol

import (
	"encoding/json"
	"errors"
	"os"
	"strings"
	"testing"
	"time"
)

func TestBackgroundPassRetainsFailureUntilFreshCompleteRecovery(t *testing.T) {
	var p BackgroundPass
	if p.Snapshot().RetryState != "not_started" {
		t.Fatal("Invented initial pass")
	}
	old, failed := p.Begin(), p.Begin()
	p.Finish(failed, errors.New("scan_failed password=private-token"), time.Minute)
	p.Finish(old, nil, time.Hour)
	s := p.Snapshot()
	if !s.FailureActive || s.Running != 0 || s.LastFailureAt <= 0 || s.LastSuccessAt <= 0 || s.RetryState != "pending" || s.RetryDelayMs != time.Minute.Milliseconds() || strings.Contains(s.LastFailureCause, "private-token") {
		t.Fatal("Stale success cleared a later failure or changed retry observation", s)
	}
	fresh := p.Begin()
	p.Finish(fresh, nil, time.Hour)
	s = p.Snapshot()
	if s.FailureActive || s.RetryState != "waiting" || s.LastFailureCause == "" || s.LastFailureAt <= 0 || s.RetryDelayMs != time.Hour.Milliseconds() {
		t.Fatal("Fresh recovery missing or erased last failure observation", s)
	}
}

func TestBackgroundPassBoundsCauseAndOmitsPrivateFilesystemPath(t *testing.T) {
	for _, err := range []error{errors.New(strings.Repeat("&", 8192) + " password=private-token"), &os.PathError{Op: "remove", Path: "private-other-app-path", Err: os.ErrPermission}, &os.LinkError{Op: "rename", Old: "private-other-app-path", New: "private-other-app-path", Err: os.ErrPermission}} {
		var p BackgroundPass
		p.Finish(p.Begin(), err, time.Minute)
		s := p.Snapshot()
		data, _ := json.Marshal(s)
		if len(data) > 4096 || strings.Contains(s.LastFailureCause, "private-token") || strings.Contains(s.LastFailureCause, "private-other-app-path") || !s.FailureActive || s.LastFailureCause == "" {
			t.Fatal("Unbounded or private background failure", len(data))
		}
	}
}

package backend

import (
	"context"
	"encoding/json"
	"io"
	"strings"
	"testing"
	"testing/synctest"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

type expiringPreparationNative struct {
	*nativeFixture
	stage string
	puts  int
}

func (n *expiringPreparationNative) Models(ctx context.Context, cursor string) (json.RawMessage, error) {
	if n.stage == "model" {
		time.Sleep(2 * time.Second)
	}
	return n.nativeFixture.Models(ctx, cursor)
}
func (n *expiringPreparationNative) Put(context.Context, protocol.File, io.Reader) error {
	n.puts++
	if n.stage == "input" {
		time.Sleep(2 * time.Second)
	}
	return nil
}
func (n *expiringPreparationNative) Call(ctx context.Context, request protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if n.stage == "select" && request.Operation == "select" {
		time.Sleep(2 * time.Second)
	}
	return n.nativeFixture.Call(ctx, request)
}

func TestExpiredPreparationNeverSubmitsAcrossJobDeadline(t *testing.T) {
	for _, stage := range []string{"model", "input", "select"} {
		t.Run(stage, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				s, base, call := fixtureAPI(t)
				registerFixture(t, call, "nAlice")
				n := &expiringPreparationNative{nativeFixture: base, stage: stage}
				n.online = true
				s.native = n
				state, err := s.loadApp(s.getPeer("nAlice"))
				if err != nil {
					t.Fatal(err)
				}
				f, _ := files.Describe(randomToken(), "owned.bin", []byte("owned input"))
				if _, err := s.inbox.Put(state.Peer, f, strings.NewReader("owned input")); err != nil {
					t.Fatal(err)
				}
				j := ownedOutputJob(randomToken())
				j.State, j.TurnID, j.Model = "queued", "", "fixture-model"
				j.Created = time.Now().Add(-appRetention + time.Second).UnixMilli()
				j.Input.Files = []protocol.File{f}
				j.Fingerprint = appHash(j.Input)
				state.Jobs[j.ID] = j
				if err := s.saveApp(state); err != nil {
					t.Fatal(err)
				}
				if err := s.workPass(); err != nil {
					t.Fatal(err)
				}
				if n.calls != 0 || (stage == "model" && n.puts != 0) {
					t.Fatal("Expired job reached native execution after slow "+stage, n.calls, n.puts)
				}
				state, err = s.loadApp(s.getPeer("nAlice"))
				if err != nil || state.Jobs[j.ID] != nil || state.DeletedRequests[j.ID] == 0 {
					t.Fatal("Deadline did not commit expiry and its replay fence", err)
				}
				if w := call("GET", "/v2/jobs/"+j.ID, nil, "nAlice"); w.Code != 410 {
					t.Fatal("Expired job remained available", w.Body.String())
				}
				if _, _, err := s.inbox.Read("nAlice", f.ID); err == nil {
					t.Fatal("Expired input bytes remained available")
				}
				if err := s.workPass(); err != nil || n.calls != 0 {
					t.Fatal("Expired job replayed on a later pass", err, n.calls)
				}
			})
		})
	}
}

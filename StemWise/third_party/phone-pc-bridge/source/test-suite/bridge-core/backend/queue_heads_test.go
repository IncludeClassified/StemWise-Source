package backend

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

type queueHeadNative struct {
	*nativeFixture
	turns         map[string]json.RawMessage
	busyThread    string
	catalogBroken bool
	deferred      bool
}

func (n *queueHeadNative) Models(ctx context.Context, cursor string) (json.RawMessage, error) {
	if n.catalogBroken {
		return nil, errors.New("owned_catalog_unavailable")
	}
	return n.nativeFixture.Models(ctx, cursor)
}
func (n *queueHeadNative) Call(ctx context.Context, r protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if r.Operation == "create" || r.Operation == "creation" {
		return protocol.AdapterReply{State: "ok", Result: mustJSON(map[string]any{"thread": map[string]string{"id": "owned-" + r.RequestID}})}, nil
	}
	if r.Operation == "select" {
		kind := "idle"
		if r.ThreadID == n.busyThread {
			kind = "active"
		}
		return protocol.AdapterReply{State: "ok", Result: mustJSON(map[string]any{"thread": map[string]any{"id": r.ThreadID, "status": map[string]string{"type": kind}}})}, nil
	}
	if r.Operation == "send" {
		n.calls++
		n.input = r
		turn := "owned-turn-" + r.JobID
		result := r.Text
		report := appReport{Protocol: "v2", AppID: r.AppID, JobID: r.JobID, Revision: 1, Status: "completed", Code: "ready", Result: &result}
		n.turns[r.ThreadID] = mustJSON(map[string]any{"id": turn, "status": "completed", "bridgeTerminalCommitted": true, "items": []any{map[string]any{"type": "agentMessage", "text": string(mustJSON(report))}}})
		return protocol.AdapterReply{State: "ok", Result: mustJSON(map[string]any{"turn": map[string]string{"id": turn}})}, nil
	}
	return n.nativeFixture.Call(ctx, r)
}
func (n *queueHeadNative) History(_ context.Context, thread, cursor string, limit int) (json.RawMessage, error) {
	if n.deferred {
		return nil, errors.New("owned_history_deferred")
	}
	return mustJSON(map[string]any{"data": []json.RawMessage{n.turns[thread]}, "nextCursor": nil}), nil
}

func TestBlockedQueuedHeadDoesNotHideAnotherChatsAcknowledgedCompletion(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	n := &queueHeadNative{nativeFixture: base, turns: map[string]json.RawMessage{}, deferred: true}
	n.online = true
	s.native = n
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	blocked := ownedOutputJob(randomToken())
	blocked.State = "queued"
	blocked.TurnID = ""
	blocked.ThreadID = "blocked-chat"
	blocked.Created = time.Now().Add(-time.Second).UnixMilli()
	f, _ := files.Describe(randomToken(), "missing.bin", []byte("missing"))
	blocked.Input.Files = []protocol.File{f}
	blocked.Fingerprint = appHash(blocked.Input)
	ready := ownedOutputJob(randomToken())
	ready.State = "queued"
	ready.TurnID = ""
	ready.ThreadID = "ready-chat"
	state.Jobs[blocked.ID], state.Jobs[ready.ID] = blocked, ready
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		if err := s.workPass(); err != nil {
			t.Fatal(err)
		}
	}
	n.deferred = false
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err = s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	if state.Jobs[blocked.ID].State != "queued" || state.Jobs[ready.ID].State != "completed" || len(state.Jobs[ready.ID].Outputs) != 1 || n.calls != 1 {
		t.Fatal("Blocked queued job suppressed an already acknowledged completion")
	}
}

func TestBlockedOwnedJobDoesNotBlockAnotherAdmittedJob(t *testing.T) {
	for _, reason := range []string{"input", "busy", "catalog"} {
		t.Run(reason, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			n := &queueHeadNative{nativeFixture: base, turns: map[string]json.RawMessage{}}
			n.online = true
			s.native = n
			first := appInput{RequestID: strings.Repeat("1", 32), CreatedAt: time.Now().UnixMilli(), Title: "Blocked own job", Text: "first", Files: []protocol.File{}}
			independent := appInput{RequestID: strings.Repeat("2", 32), CreatedAt: time.Now().UnixMilli(), Title: "Independent own job", Text: "second", Files: []protocol.File{}}
			switch reason {
			case "input":
				f, _ := files.Describe(randomToken(), "missing.bin", []byte("missing"))
				first.Files = []protocol.File{f}
				if _, err := s.inbox.Put("nAlice", f, strings.NewReader("missing")); err != nil {
					t.Fatal(err)
				}
			case "busy":
				n.busyThread = "owned-" + first.RequestID
			case "catalog":
				first.Model = "fixture-model"
				n.catalogBroken = true
			}
			for _, input := range []appInput{first, independent} {
				if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
					t.Fatal(w.Body.String())
				}
			}
			if reason == "input" {
				if err := s.inbox.Discard("nAlice", first.Files[0].ID); err != nil {
					t.Fatal(err)
				}
			}
			for i := 0; i < 2; i++ {
				if err := s.workPass(); err != nil {
					t.Fatal(err)
				}
			}
			state, err := s.loadApp(s.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			if state.Jobs[first.RequestID].State != "queued" || state.Jobs[independent.RequestID].State != "completed" || n.calls != 1 || n.input.JobID != independent.RequestID {
				t.Fatal("Blocked owned job stalled independent admitted work")
			}
			n.busyThread, n.catalogBroken = "", false
			if reason == "input" {
				f := first.Files[0]
				if _, err := s.inbox.Put("nAlice", f, strings.NewReader("missing")); err != nil {
					t.Fatal(err)
				}
			}
			if err := s.workPass(); err != nil {
				t.Fatal(err)
			}
			state, err = s.loadApp(s.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			if state.Jobs[first.RequestID].State != "completed" || state.Jobs[first.RequestID].ThreadID == state.Jobs[independent.RequestID].ThreadID || n.input.JobID != first.RequestID || n.input.Text != first.Text || n.calls != 2 {
				t.Fatal("Queue recovery reused a chat, rewrote input or duplicated submission", n.calls)
			}
		})
	}
}

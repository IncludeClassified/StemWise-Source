package backend

import (
	"context"
	"encoding/json"
	"errors"
	"reflect"
	"strings"
	"testing"
	"time"

	"phonepcbridge/protocol"
)

type jobChatNative struct {
	*nativeFixture
	starts, lookups, deletes     int
	lost, confirm, deleteOffline bool
	request                      protocol.AdapterRequest
	beforeCreate                 func()
	beforeObserve                func()
}

func (n *jobChatNative) Call(ctx context.Context, r protocol.AdapterRequest) (protocol.AdapterReply, error) {
	switch r.Operation {
	case "create":
		if n.beforeCreate != nil {
			n.beforeCreate()
		}
		n.starts++
		n.request = r
		if n.lost {
			return protocol.AdapterReply{}, errors.New("lost original acknowledgment")
		}
		return protocol.AdapterReply{State: "ok", Result: mustJSON(map[string]any{"thread": map[string]string{"id": "owned-" + r.RequestID}})}, nil
	case "creation":
		n.lookups++
		if n.beforeObserve != nil {
			n.beforeObserve()
		}
		if n.request.RequestID == "" {
			return protocol.AdapterReply{State: "rejected", Code: "creation_request_absent"}, nil
		}
		original := n.request
		original.Operation = "creation"
		if !reflect.DeepEqual(original, r) {
			return protocol.AdapterReply{}, errors.New("immutable creation changed")
		}
		if !n.confirm {
			return protocol.AdapterReply{State: "unknown", Code: "creation_receipt_unconfirmed"}, nil
		}
		return protocol.AdapterReply{State: "ok", Result: mustJSON(map[string]any{"thread": map[string]string{"id": "owned-" + r.RequestID}})}, nil
	case "delete":
		n.deletes++
		if n.deleteOffline {
			return protocol.AdapterReply{}, errors.New("offline")
		}
		if r.ThreadID != "owned-"+r.RequestID {
			return protocol.AdapterReply{}, errors.New("wrong target")
		}
		return protocol.AdapterReply{State: "ok", Result: json.RawMessage(`{"deleted":true}`)}, nil
	}
	return n.nativeFixture.Call(ctx, r)
}

func TestCreationRecoversCrashBeforePCReservation(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	base.online = true
	n := &jobChatNative{nativeFixture: base}
	s.native = n
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Before RPC", Text: "original", Files: []protocol.File{}, Model: "fixture-model", Effort: "high"}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	// This is the durable state at process death before the first create RPC.
	state.Jobs[input.RequestID].ChatState = "unknown"
	state.Jobs[input.RequestID].Code = "chat_creation_unconfirmed"
	expected := jobCreation(state.ID, state.Jobs[input.RequestID], "create")
	if err = s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	reopened, err := New(s.cfg, n, s.authenticate, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	if err = reopened.workPass(); err != nil {
		t.Fatal(err)
	}
	if n.lookups != 1 || n.starts != 1 || n.calls != 1 || !reflect.DeepEqual(n.request, expected) {
		t.Fatalf("Pre-RPC creation did not resume the exact original envelope: lookups=%d starts=%d sends=%d request=%+v", n.lookups, n.starts, n.calls, n.request)
	}
}

func TestAdmittedJobStartsFreshAndRejectsCallerThread(t *testing.T) {
	s, _, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Ownership invariant", Text: "original", Files: []protocol.File{}}
	request := map[string]any{}
	if err := json.Unmarshal(mustJSON(input), &request); err != nil {
		t.Fatal(err)
	}
	request["threadId"] = "existing-caller-thread"
	if w := call("POST", "/v2/jobs", request, "nAlice"); w.Code != 400 {
		t.Fatal("Caller supplied a shared chat", w.Body.String())
	}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	job := state.Jobs[input.RequestID]
	if job.ThreadID != "" || job.Developer != "bridge_job_"+input.RequestID {
		t.Fatal("Normal admission did not retain its fresh owned-chat invariant")
	}
}

func TestAbsentCreationRechecksCancellationAndRemovalBeforeCreate(t *testing.T) {
	for _, change := range []string{"cancel", "remove"} {
		t.Run(change, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			base.online = true
			input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Absent creation fence", Text: "original", Files: []protocol.File{}}
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			state.Jobs[input.RequestID].ChatState = "unknown"
			if err = s.saveApp(state); err != nil {
				t.Fatal(err)
			}
			n := &jobChatNative{nativeFixture: base, beforeObserve: func() {
				if change == "cancel" {
					if w := call("POST", "/v2/jobs/"+input.RequestID+"/cancel", nil, "nAlice"); w.Code != 200 {
						t.Fatal(w.Body.String())
					}
				} else {
					p.appMu.Lock()
					defer p.appMu.Unlock()
					latest, err := s.loadApp(p)
					if err != nil {
						t.Fatal(err)
					}
					if err = s.removeAppJobs(latest, map[string]*appJob{input.RequestID: latest.Jobs[input.RequestID]}); err != nil {
						t.Fatal(err)
					}
				}
			}}
			s.native = n
			if err = s.workPass(); err != nil {
				t.Fatal(err)
			}
			if n.starts != 0 || n.calls != 0 {
				t.Fatal("Absent observation created a chat after cancellation/removal")
			}
			state, err = s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			if change == "remove" && state.Jobs[input.RequestID] != nil {
				t.Fatal("Removed job recreated")
			}
			if change == "cancel" && state.Jobs[input.RequestID].State != "cancelled" {
				t.Fatal("Cancellation overwritten")
			}
		})
	}
}

func TestPendingJobChatIssueDirectsAppToOriginalJob(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	base.online = true
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Own job", Text: "original", Files: []protocol.File{}}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	observed := false
	s.native = &jobChatNative{nativeFixture: base, beforeCreate: func() {
		w := call("GET", "/v2/jobs/"+input.RequestID, nil, "nAlice")
		var reply struct {
			Data struct {
				Code  string     `json:"code"`
				Issue appFailure `json:"issue"`
			} `json:"data"`
		}
		if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &reply) != nil || reply.Data.Code != "chat_creation_unconfirmed" ||
			!reply.Data.Issue.Retryable || !strings.Contains(reply.Data.Issue.Action, "/v2/jobs/<id>") || strings.Contains(reply.Data.Issue.Action, "/codex/chats") {
			t.Fatal("Pending owned-chat creation must direct observation to the original current job API:", w.Body.String())
		}
		observed = true
	}}
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	if !observed {
		t.Fatal("Actual job creation path did not expose pending state")
	}
}

func TestEachJobCreatesItsOwnChatAndLostCreationIsOnlyObserved(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	base.online = true
	n := &jobChatNative{nativeFixture: base, lost: true}
	s.native = n
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Own job", Text: "original", Files: []protocol.File{}, Model: "fixture-model", Effort: "high"}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	if n.starts != 1 || n.calls != 0 {
		t.Fatal("uncertain creation dispatched or duplicated")
	}
	// Restart the Pi coordinator and change installation defaults; the job keeps its original envelope.
	reopened, err := New(s.cfg, n, s.authenticate, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	if w := call("PUT", "/v2/codex/settings", map[string]string{"model": "", "effort": ""}, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	for i := 0; i < 2; i++ {
		if err = reopened.workPass(); err != nil {
			t.Fatal(err)
		}
	}
	if n.starts != 1 || n.lookups != 2 || n.calls != 0 {
		t.Fatal("unknown creation replayed")
	}
	n.confirm = true
	if err = reopened.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err := reopened.loadApp(reopened.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	first := state.Jobs[input.RequestID]
	if first.ThreadID != "owned-"+input.RequestID || n.calls != 1 || n.request.Model != "fixture-model" {
		t.Fatal("receipt recovery changed target/model")
	}
	n.lost = false
	input.RequestID = randomToken()
	input.CreatedAt = time.Now().UnixMilli()
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	if err = reopened.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err = reopened.loadApp(reopened.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	if n.starts != 2 || state.Jobs[input.RequestID].ThreadID == first.ThreadID {
		t.Fatal("job reused an earlier chat")
	}
}

func TestUnknownCreationDoesNotBlockAnotherIndependentJob(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	base.online = true
	n := &jobChatNative{nativeFixture: base, lost: true}
	s.native = n
	for i := 0; i < 2; i++ {
		input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Independent job", Text: "original " + randomToken(), Files: []protocol.File{}}
		if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
			t.Fatal(w.Body.String())
		}
		// Distinct original timestamps also establish queue order on coarse clocks.
		time.Sleep(2 * time.Millisecond)
	}
	for i := 0; i < 2; i++ {
		if err := s.workPass(); err != nil {
			t.Fatal(err)
		}
	}
	if n.starts != 2 || n.lookups != 0 || n.calls != 0 {
		t.Fatal("Unknown first creation blocked independent work or replayed creation", n.starts, n.lookups, n.calls)
	}
}

func TestDeliveryCannotBeForgedByReportOrOtherAppAndCleanupRetriesOnlyDeletion(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	registerFixture(t, call, "nBob")
	base.online = true
	n := &jobChatNative{nativeFixture: base}
	s.native = n
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Delivery", Text: "actual result", Files: []protocol.File{}}
	call("POST", "/v2/jobs", input, "nAlice")
	path := "/v2/jobs/" + input.RequestID + "/delivery"
	if w := call("POST", path, map[string]any{"files": []protocol.File{}}, "nAlice"); w.Code != 409 {
		t.Fatal("unfinished delivery accepted")
	}
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	job := state.Jobs[input.RequestID]
	if job.Delivered != 0 || job.ChatState != "created" || n.deletes != 0 || len(job.Outputs) != 1 {
		t.Fatal("agent completion acknowledged delivery")
	}
	body := map[string]any{"files": job.Outputs}
	if w := call("POST", path, body, "nBob"); w.Code != 404 {
		t.Fatal("other app acknowledged delivery")
	}
	wrong := append([]protocol.File{}, job.Outputs...)
	wrong[0].SHA256 = string(make([]byte, 64))
	if w := call("POST", path, map[string]any{"files": wrong}, "nAlice"); w.Code != 409 {
		t.Fatal("wrong output accepted")
	}
	n.deleteOffline = true
	if w := call("POST", path, body, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	state, _ = s.loadApp(s.getPeer("nAlice"))
	job = state.Jobs[input.RequestID]
	if job.Delivered == 0 || job.ChatState != "cleanup_pending" || len(job.Log) == 0 {
		t.Fatal("offline cleanup lost obligation/diagnostic")
	}
	n.deleteOffline = false
	if err = s.workPass(); err != nil {
		t.Fatal(err)
	}
	state, _ = s.loadApp(s.getPeer("nAlice"))
	job = state.Jobs[input.RequestID]
	if job.ChatState != "deleted" || n.calls != 1 || n.deletes != 2 {
		t.Fatal("cleanup replayed job or failed to delete")
	}
	if w := call("POST", path, body, "nAlice"); w.Code != 200 || n.deletes != 2 {
		t.Fatal("delivery retry was not idempotent")
	}
}

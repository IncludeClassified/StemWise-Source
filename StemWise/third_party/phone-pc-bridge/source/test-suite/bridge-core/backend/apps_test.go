package backend

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http/httptest"
	"os"
	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
	"strings"
	"testing"
	"time"
)

type nativeFixture struct {
	online    bool
	busy      bool
	calls     int
	input     protocol.AdapterRequest
	report    string
	bad       bool
	uncertain bool
	outputs   map[string][]byte
}

func (n *nativeFixture) Models(context.Context, string) (json.RawMessage, error) {
	return json.RawMessage(`{"data":[{"model":"fixture-model","supportedReasoningEfforts":[{"reasoningEffort":"high"}]}],"nextCursor":null}`), nil
}
func (n *nativeFixture) History(context.Context, string, string, int) (json.RawMessage, error) {
	text := n.report
	if n.bad {
		text = "Hello! I finished the task."
	}
	return json.Marshal(map[string]any{"data": []any{map[string]any{"id": "fixture-turn", "status": "completed", "bridgeTerminalCommitted": true, "items": []any{map[string]string{"type": "agentMessage", "text": text}}}}, "nextCursor": nil})
}
func (n *nativeFixture) NameThread(context.Context, string, string) error { return nil }
func (n *nativeFixture) Call(ctx context.Context, r protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if !n.online {
		return protocol.AdapterReply{}, errors.New("offline")
	}
	if r.Operation == "auth" {
		return protocol.AdapterReply{State: "ok", Result: mustJSON(map[string]any{"state": "ready", "code": "native_auth_ready", "observedAt": time.Now().UnixMilli(), "validForMs": 0})}, nil
	}
	if r.Operation == "create" || r.Operation == "creation" {
		return protocol.AdapterReply{State: "ok", Result: mustJSON(map[string]any{"thread": map[string]string{"id": "fixture-chat"}})}, nil
	}
	if r.Operation == "delete" {
		return protocol.AdapterReply{State: "ok", Result: json.RawMessage(`{"deleted":true}`)}, nil
	}
	if r.Operation == "discard" {
		return protocol.AdapterReply{State: "ok", Result: mustJSON(map[string]any{"appId": r.AppID, "requestId": r.RequestID, "accepted": true})}, nil
	}
	if r.Operation == "send" {
		n.calls++
		n.input = r
		if n.uncertain {
			return protocol.AdapterReply{}, errors.New("lost reply")
		}
		n.report = string(mustJSON(map[string]any{"bridgeJobReport": "v2", "appId": r.AppID, "jobId": r.JobID, "revision": 1, "status": "completed", "code": "output_ready", "result": r.Text}))
		return protocol.AdapterReply{State: "ok", Result: json.RawMessage(`{"turn":{"id":"fixture-turn"}}`)}, nil
	}
	status := "idle"
	if n.busy {
		status = "active"
	}
	return protocol.AdapterReply{State: "ok", Result: mustJSON(map[string]any{"thread": map[string]any{"id": r.ThreadID, "status": map[string]string{"type": status}}})}, nil
}
func (n *nativeFixture) Put(context.Context, protocol.File, io.Reader) error { return nil }
func (n *nativeFixture) Output(ctx context.Context, f protocol.File) (io.ReadCloser, error) {
	data, ok := n.outputs[f.ID]
	if !ok {
		return nil, os.ErrNotExist
	}
	return io.NopCloser(bytes.NewReader(data)), nil
}
func (n *nativeFixture) Reachable(context.Context) error {
	if !n.online {
		return errors.New("offline")
	}
	return nil
}
func mustJSON(value any) []byte {
	data, err := json.Marshal(value)
	if err != nil {
		panic(err)
	}
	return data
}

func (s *Service) refreshApp(p *peer, state *appState, j *appJob) error {
	p.appMu.Lock()
	defer p.appMu.Unlock()
	if err := s.saveApp(state); err != nil {
		return err
	}
	if j.TurnID == "" {
		return nil
	}
	ctx, cancel := context.WithTimeout(s.ctx, 30*time.Second)
	defer cancel()
	turn, err := s.lookupAppTurn(ctx, j)
	if err != nil {
		return err
	}
	return s.applyAppTurn(ctx, p, state, j, turn)
}

func fixtureAPI(t *testing.T) (*Service, *nativeFixture, func(string, string, any, string) *httptest.ResponseRecorder) {
	t.Helper()
	n := &nativeFixture{outputs: map[string][]byte{}}
	s, err := New(Config{Identity: "nPi", PCIdentity: "nPc", DataDir: t.TempDir(), IntegrationDir: "managed-pc-project"}, n, func(ctx context.Context, remote string) (string, error) {
		if remote == "nDenied" {
			return "", errors.New("denied")
		}
		return remote, nil
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		closed := make(chan struct{})
		go func() { defer close(closed); s.Close() }()
		checksync.Join(t, closed, 3*time.Second, "backend fixture close")
	})
	call := func(method, path string, data any, sender string) *httptest.ResponseRecorder {
		t.Helper()
		body := []byte{}
		if data != nil {
			if text, ok := data.(string); ok {
				body = []byte(text)
			} else {
				body = mustJSON(data)
			}
		}
		request := httptest.NewRequest(method, path, bytes.NewReader(body))
		request.RemoteAddr = sender
		request.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		s.ServeHTTP(w, request)
		return w
	}
	return s, n, call
}
func registerFixture(t *testing.T, call func(string, string, any, string) *httptest.ResponseRecorder, sender string) {
	t.Helper()
	if w := call("POST", "/v2/app", map[string]string{"name": sender}, sender); w.Code != 200 {
		t.Fatal(w.Body.String())
	}

}
func TestPiQueueDeliveryIsolationAndRetention(t *testing.T) {
	s, n, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	registerFixture(t, call, "nBob")
	original := "owner-" + randomToken() + " Ω\nunchanged"
	input := appInput{RequestID: randomToken(), Title: "Owned request", Text: original, Instructions: "independent-custom", Files: []protocol.File{}, CreatedAt: time.Now().UnixMilli(), Model: "fixture-model", Effort: "high"}
	results := make(chan *httptest.ResponseRecorder, 2)
	for i := 0; i < 2; i++ {
		finished := make(chan struct{})
		t.Cleanup(func() {
			s.cancel()
			checksync.Join(t, finished, time.Second, "concurrent job submission")
		})
		go func() {
			defer close(finished)
			results <- call("POST", "/v2/jobs", input, "nAlice")
		}()
	}
	for i := 0; i < 2; i++ {
		w := checksync.Receive(t, results, time.Second, "concurrent job submission")
		if w.Code != 202 && w.Code != 200 {
			t.Fatal(w.Body.String())
		}
	}
	if n.calls != 0 {
		t.Fatal("Offline PC received work")
	}
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	if n.calls != 0 {
		t.Fatal("Unreachable PC was dispatched")
	}
	if w := call("GET", "/v2/jobs/"+input.RequestID, nil, "nBob"); w.Code != 404 {
		t.Fatal("Other app observed job")
	}
	changed := input
	changed.Text = "different"
	if w := call("POST", "/v2/jobs", changed, "nAlice"); w.Code != 409 {
		t.Fatal("Request identity was mutable")
	}
	n.online = true
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	if n.calls != 1 || n.input.Text != original || n.input.Instructions != input.Instructions {
		t.Fatal("Queue duplicated or changed native input")
	}
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	if n.calls != 1 {
		t.Fatal("Observation replayed work")
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	job := state.Jobs[input.RequestID]
	if job.State != "completed" || len(job.Outputs) != 1 {
		t.Fatal("Exact native report did not publish output")
	}
	output := job.Outputs[0]
	w := call("GET", "/v2/jobs/"+input.RequestID+"/outputs/"+output.ID+"/content", nil, "nAlice")
	if w.Code != 200 || w.Body.String() != original {
		t.Fatal("Recipient output differs from independent owner source")
	}
	if w := call("GET", "/v2/developers", nil, "nAlice"); strings.Contains(w.Body.String(), "must_not_be_exported") {
		t.Fatal("Conversation preview exported")
	}
	prompt := randomToken()
	if w := call("PUT", "/v2/prompts/"+prompt, appPrompt{Title: "Retained prompt", Text: "prompt"}, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	state, err = s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	job = state.Jobs[input.RequestID]
	deliveredRetentionFixture(state, job, time.Now())
	if err = s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	if _, err = s.retentionPass(time.Now()); err != nil {
		t.Fatal(err)
	}
	state, err = s.loadApp(s.getPeer("nAlice"))
	if err != nil || len(state.Jobs) != 0 || len(state.Prompts) != 1 {
		t.Fatal("Retention kept expired jobs or removed saved prompt")
	}
	if _, _, err = s.outbox.Read("nAlice", output.ID); !os.IsNotExist(err) {
		t.Fatal("Expired output bytes retained", err)
	}
	input.CreatedAt = time.Now().Add(-appRetention - time.Second).UnixMilli()
	input.RequestID = randomToken()
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal("New identity incorrectly required the sender's clock to match", w.Body.String())
	}
}
func TestPiUnknownDispatchNeverReplayed(t *testing.T) {
	s, n, call := fixtureAPI(t)
	n.online = true
	n.uncertain = true
	registerFixture(t, call, "nAlice")
	input := appInput{RequestID: randomToken(), Title: "Unknown", Text: "work", Files: []protocol.File{}, CreatedAt: time.Now().UnixMilli()}
	call("POST", "/v2/jobs", input, "nAlice")
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 3; i++ {
		call("POST", "/v2/jobs", input, "nAlice")
		if err := s.workPass(); err != nil {
			t.Fatal(err)
		}
	}
	if n.calls != 1 {
		t.Fatal("Unknown native send replayed")
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil || state.Jobs[input.RequestID].State != "unknown" {
		t.Fatal("Lost reply reported successful")
	}
}

func TestPiRestartKeepsQueueAndWaitsForBusyDeveloper(t *testing.T) {
	s, n, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	input := appInput{RequestID: randomToken(), Title: "Restart queue", Text: "work", Files: []protocol.File{}, CreatedAt: time.Now().UnixMilli()}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	cfg := s.cfg
	s.Close()
	resumed, err := New(cfg, n, func(ctx context.Context, remote string) (string, error) { return remote, nil }, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer resumed.Close()
	n.online = true
	n.busy = true
	if err := resumed.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err := resumed.loadApp(resumed.getPeer("nAlice"))
	if err != nil || state.Jobs[input.RequestID].State != "queued" || state.Jobs[input.RequestID].Code != "developer_busy" || n.calls != 0 {
		t.Fatal("Busy developer consumed or lost queued work", err)
	}
	n.busy = false
	if err := resumed.workPass(); err != nil {
		t.Fatal(err)
	}
	if n.calls != 1 {
		t.Fatal("Restarted queue did not dispatch exactly once")
	}
	state, err = resumed.loadApp(resumed.getPeer("nAlice"))
	if err != nil || state.Jobs[input.RequestID].State != "completed" {
		t.Fatal("Restarted result unconfirmed", err)
	}
}
func TestPiProtocolFailuresAndErrors(t *testing.T) {
	s, n, call := fixtureAPI(t)
	n.online = true
	n.bad = true
	registerFixture(t, call, "nAlice")
	for _, request := range []struct {
		method, path, body string
		status             int
	}{{"POST", "/v2/app", `{"name":"nAlice","name":"nBob"}`, 400}, {"POST", "/v2/app", `{"Name":"nAlice"}`, 400}, {"POST", "/v2/jobs", `{"appId":"guessed"}`, 400}, {"GET", "/v2/jobs?app=nBob", "", 400}, {"GET", "/v2/jobs/../other", "", 404}, {"PUT", "/v2/connection/settings", `{"AutoWake":true}`, 404}, {"PUT", "/v2/codex/settings", `{"model":"unknown","effort":"high"}`, 409}, {"POST", "/v2/codex/chats", `{"name":"chat","requestId":"invalid"}`, 404}} {
		w := call(request.method, request.path, request.body, "nAlice")
		if w.Code != request.status {
			t.Fatal(request.path, w.Code, w.Body.String())
		}
		var envelope struct {
			Data appFailure `json:"data"`
		}
		if json.Unmarshal(w.Body.Bytes(), &envelope) != nil || envelope.Data.Code == "" || envelope.Data.Message == "" || envelope.Data.Action == "" {
			t.Fatal("Error lacks understandable code and resolution")
		}
	}
	if w := call("GET", "/v2/connection", nil, "nDenied"); w.Code != 403 {
		t.Fatal("Unpaired app accepted")
	}
	input := appInput{RequestID: randomToken(), Title: "Bad report", Text: "work", Files: []protocol.File{}, CreatedAt: time.Now().UnixMilli()}
	call("POST", "/v2/jobs", input, "nAlice")
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	if state.Jobs[input.RequestID].Code != "developer_terminal_report_missing" || len(state.Jobs[input.RequestID].Outputs) != 0 {
		t.Fatal("Ordinary native text substituted for a final bridge report")
	}
	descriptor, _ := files.Describe(randomToken(), "missing.bin", []byte("source"))
	input.RequestID = randomToken()
	input.Files = []protocol.File{descriptor}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 409 {
		t.Fatal("Agent-authored descriptor substituted for actual bytes")
	}
}

func TestJobAliasRejectionDoesNotMutateOriginalInputs(t *testing.T) {
	s, n, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	input := appInput{RequestID: randomToken(), Title: "Original", Text: "owner-" + randomToken() + " Ω", CreatedAt: time.Now().UnixMilli(), Files: []protocol.File{}}
	raw := string(mustJSON(input))
	shadow := strings.TrimSuffix(raw, "}") + `,"Text":"replacement"}`
	if w := call("POST", "/v2/jobs", shadow, "nAlice"); w.Code != 400 {
		t.Fatal("Aliased prompt was accepted", w.Body.String())
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil || len(state.Jobs) != 0 || n.calls != 0 {
		t.Fatal("Rejected alias reserved a job or submitted native work", err)
	}
	if w := call("POST", "/v2/jobs", raw, "nAlice"); w.Code != 202 {
		t.Fatal("Canonical original request failed", w.Body.String())
	}
	if w := call("POST", "/v2/jobs", shadow, "nAlice"); w.Code != 400 {
		t.Fatal("Aliased retry was accepted", w.Body.String())
	}
	var actual struct {
		Data appInput `json:"data"`
	}
	w := call("GET", "/v2/jobs/"+input.RequestID+"/inputs", nil, "nAlice")
	if json.Unmarshal(w.Body.Bytes(), &actual) != nil || actual.Data.Text != input.Text || actual.Data.RequestID != input.RequestID {
		t.Fatal("Aliased retry changed saved owner inputs")
	}
}

func TestPiReportBoundary(t *testing.T) {
	job := &appJob{ID: strings.Repeat("a", 32), TurnID: "turn"}
	app := strings.Repeat("b", 32)
	valid := map[string]any{"bridgeJobReport": "v2", "appId": app, "jobId": job.ID, "revision": 1, "status": "working", "code": "processing"}
	turn := func(messages ...string) json.RawMessage {
		items := []any{}
		for _, text := range messages {
			items = append(items, map[string]any{"type": "agentMessage", "text": text, "bridgeReportCommitted": true})
		}
		return mustJSON(map[string]any{"id": "turn", "status": "completed", "bridgeTerminalCommitted": true, "items": items})
	}
	for _, field := range []string{"appId", "jobId", "code", "revision", "extra", "bridgeJobReport"} {
		copy := map[string]any{}
		for k, v := range valid {
			copy[k] = v
		}
		copy[field] = "wrong prose"
		if _, _, _, err := appLatestReport(app, job, turn(string(mustJSON(copy)))); err == nil {
			t.Fatal("Malformed field accepted", field)
		}
	}
	if _, _, _, err := appLatestReport(app, job, turn(string(mustJSON(valid)))); err != nil {
		t.Fatal(err)
	}
	duplicate := string(mustJSON(valid))
	duplicate = strings.TrimSuffix(duplicate, "}") + `,"revision":1}`
	if _, _, _, err := appLatestReport(app, job, turn(duplicate)); err == nil {
		t.Fatal("Duplicate report key accepted")
	}
	alias := strings.TrimSuffix(string(mustJSON(valid)), "}") + `,"STATUS":"completed"}`
	if _, _, _, err := appLatestReport(app, job, turn(alias)); err == nil {
		t.Fatal("Case alias changed a developer report's status")
	}
	complete := map[string]any{}
	for k, v := range valid {
		complete[k] = v
	}
	complete["status"] = "completed"
	later := map[string]any{}
	for k, v := range valid {
		later[k] = v
	}
	later["revision"] = 2
	if _, _, _, err := appLatestReport(app, job, turn(string(mustJSON(complete)), string(mustJSON(later)))); err == nil {
		t.Fatal("Terminal report amended")
	}
}

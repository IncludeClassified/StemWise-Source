package backend

import (
	"bytes"
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

type appCapacityNative struct {
	*nativeFixture
	complete bool
	report   string
	creates  int
}

func (n *appCapacityNative) Call(ctx context.Context, request protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if request.Operation == "create" {
		n.creates++
	}
	return n.nativeFixture.Call(ctx, request)
}

func (n *appCapacityNative) History(context.Context, string, string, int) (json.RawMessage, error) {
	status, items := "inProgress", []any{}
	if n.complete {
		status, items = "completed", []any{map[string]string{"type": "agentMessage", "text": n.report}}
	}
	return mustJSON(map[string]any{"data": []any{map[string]any{"id": "fixture-turn", "status": status, "bridgeTerminalCommitted": n.complete, "items": items}}, "nextCursor": nil}), nil
}

func seedNearFullApp(t *testing.T, s *Service, p *peer) string {
	t.Helper()
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	first := ""
	for i := 0; i < 42; i++ {
		id := randomToken()
		if first == "" {
			first = id
		}
		state.Prompts[id] = appPrompt{Title: "Retained", Text: strings.Repeat("&", 64<<10)}
	}
	for {
		id := randomToken()
		prompt := appPrompt{Title: "Retained"}
		remaining := (16 << 20) - 2048 - len(mustJSON(state)) - len(mustJSON(prompt)) - len(id) - 4
		if remaining <= 0 {
			break
		}
		prompt.Text = strings.Repeat("x", min(remaining, 64<<10))
		state.Prompts[id] = prompt
	}
	if len(state.Prompts) > 64 || len(mustJSON(state)) > (16<<20)-2048 || len(mustJSON(state)) < (16<<20)-2200 {
		t.Fatal("Near-full fixture is outside the real record bound")
	}
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	return first
}

func TestAppCapacityProtectsAcceptedJobResultsAndLogs(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	n := &appCapacityNative{nativeFixture: base}
	s.native = n
	p := s.getPeer("nAlice")
	removable := seedNearFullApp(t, s, p)
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Owned capacity job", Text: "original", Files: []protocol.File{}}
	promptID := randomToken()
	for _, request := range []struct {
		method, path string
		body         any
	}{
		{"POST", "/v2/jobs", input},
		{"PUT", "/v2/prompts/" + promptID, appPrompt{Title: "New", Text: "original"}},
	} {
		w := call(request.method, request.path, request.body, "nAlice")
		var reply struct {
			Data appFailure `json:"data"`
		}
		if w.Code != 409 || json.Unmarshal(w.Body.Bytes(), &reply) != nil || reply.Data.Code != "app_capacity_reached" || reply.Data.Retryable {
			t.Fatal("Capacity did not reject before reserving work", request.path, w.Code)
		}
	}
	state, err := s.loadApp(p)
	if err != nil || len(state.Jobs) != 0 || state.Prompts[promptID].Text != "" || n.calls != 0 || n.creates != 0 {
		t.Fatal("Rejected capacity changed stored intent or reached native mutation", err)
	}
	if w := call("DELETE", "/v2/prompts/"+removable, nil, "nAlice"); w.Code != 200 {
		t.Fatal("Full app could not release capacity", w.Code)
	}
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal("Original request could not be accepted after cleanup", w.Code)
	}
	base.online = true
	if err := s.workPeer(p, nil); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 8; i++ {
		w := call("PUT", "/v2/prompts/"+randomToken(), appPrompt{Title: "Filler", Text: strings.Repeat("x", 64<<10)}, "nAlice")
		if w.Code == 409 && strings.Contains(w.Body.String(), "app_capacity_reached") {
			break
		}
		if w.Code != 200 || i == 7 {
			t.Fatal("Pending job reserve was not enforced", w.Code)
		}
	}
	state, err = s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := state.Jobs[input.RequestID]
	if j == nil || j.State != "submitted" || n.calls != 1 {
		t.Fatal("Original job dispatch differs")
	}
	retry := false
	for i := 0; i < 8; i++ {
		diagnostic := appDiagnostic{Code: "capacity_fixture", Operation: "observe_fixture", Summary: strings.Repeat("m", 3500), Cause: "owned_fixture", Retryable: &retry, Context: map[string]string{"step": fmtInt(i)}, SuggestedFix: []string{"inspect_fixture"}}
		if !validDiagnostic(&diagnostic) {
			t.Fatal("Invalid large diagnostic fixture")
		}
		appendJobLog(j, []appLogEntry{{Source: "bridge", Error: diagnostic}})
	}
	if err := s.saveApp(state); err != nil {
		t.Fatal("Reserved diagnostic storage unavailable", err)
	}
	result := strings.Repeat("&", 16384)
	report := appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "capacity_verified", Result: &result}
	for i := 0; i < 16; i++ {
		data := []byte{byte(i)}
		file, err := files.Describe(randomToken(), strings.Repeat("&", 255), data)
		if err != nil {
			t.Fatal(err)
		}
		base.outputs[file.ID] = data
		report.Files = append(report.Files, file)
	}
	var raw bytes.Buffer
	encoder := json.NewEncoder(&raw)
	encoder.SetEscapeHTML(false)
	if encoder.Encode(report) != nil || raw.Len() > 32768 {
		t.Fatal("Largest-field report violates the actual report limit")
	}
	n.report, n.complete = raw.String(), true
	if err := s.workPeer(p, nil); err != nil {
		t.Fatal("Accepted job could not persist completion", err)
	}
	state, err = s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j = state.Jobs[input.RequestID]
	if j.State != "completed" || j.Report == nil || j.Report.Result == nil || *j.Report.Result != result || len(j.Outputs) != 17 || len(j.Log) != 8 || n.calls != 1 {
		t.Fatal("Completion, files or original diagnostics were lost")
	}
	for _, file := range j.Outputs {
		actual, data, err := s.outbox.Read(p.identity, file.ID)
		want := base.outputs[file.ID]
		if file.ID == appResultID(state.ID, j.ID) {
			want = []byte(result)
		}
		if err != nil || actual != file || !bytes.Equal(data, want) {
			t.Fatal("Published actual bytes differ", err)
		}
	}
}

package backend

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"phonepcbridge/codex"
	"phonepcbridge/files"
	"phonepcbridge/internal/checksync"
	"phonepcbridge/protocol"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

type lostAcknowledgment struct {
	*nativeFixture
	record json.RawMessage
	pages  map[string]json.RawMessage
	broken bool
}

type pausedHistory struct {
	*lostAcknowledgment
	started, release chan struct{}
	once             sync.Once
}

func (n *pausedHistory) History(ctx context.Context, thread, cursor string, limit int) (json.RawMessage, error) {
	n.once.Do(func() { close(n.started) })
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-n.release:
		return n.lostAcknowledgment.History(ctx, thread, cursor, limit)
	}
}

func TestUnknownLookupKeepsSavedAPIAvailableAndPreservesConcurrentSettings(t *testing.T) {
	s, base, call := fixtureAPI(t)
	n := &lostAcknowledgment{nativeFixture: base, broken: true}
	s.native = n
	n.online = true
	registerFixture(t, call, "nAlice")
	input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Slow history", Text: "independent-" + randomToken(), Files: []protocol.File{}}
	call("POST", "/v2/jobs", input, "nAlice")
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	n.broken = false
	paused := &pausedHistory{lostAcknowledgment: n, started: make(chan struct{}), release: make(chan struct{})}
	s.native = paused
	done := make(chan error, 1)
	retired := make(chan struct{})
	var release sync.Once
	t.Cleanup(func() {
		release.Do(func() { close(paused.release) })
		s.cancel()
		checksync.Join(t, retired, time.Second, "unknown-job lookup")
	})
	go func() { defer close(retired); done <- s.workPass() }()
	select {
	case <-paused.started:
	case <-time.After(time.Second):
		t.Fatal("Native lookup never started")
	}
	read := make(chan string, 1)
	readRetired := make(chan struct{})
	t.Cleanup(func() {
		release.Do(func() { close(paused.release) })
		checksync.Join(t, readRetired, time.Second, "saved unknown-job read")
	})
	go func() {
		defer close(readRetired)
		read <- call("GET", "/v2/jobs/"+input.RequestID, nil, "nAlice").Body.String()
	}()
	select {
	case body := <-read:
		if !strings.Contains(body, `"state":"unknown"`) {
			t.Fatal("Saved state became success before native evidence", body)
		}
	case <-time.After(time.Second):
		t.Fatal("Slow PC history blocked Pi saved-state reads")
	}
	prompt := randomToken()
	if w := call("PUT", "/v2/prompts/"+prompt, appPrompt{Title: "Concurrent", Text: "keep me"}, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	release.Do(func() { close(paused.release) })
	if err := checksync.Receive(t, done, time.Second, "released unknown-job lookup"); err != nil {
		t.Fatal(err)
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil || state.Jobs[input.RequestID].State != "completed" || state.Prompts[prompt].Text != "keep me" || n.calls != 1 {
		t.Fatal("Recovery overwrote concurrent state or repeated execution", err)
	}
}

func (n *lostAcknowledgment) Call(ctx context.Context, input protocol.AdapterRequest) (protocol.AdapterReply, error) {
	reply, err := n.nativeFixture.Call(ctx, input)
	if err == nil && input.Operation == "send" {
		epoch := strings.Repeat("a", 32)
		request := Request{Epoch: epoch, Operation: "text", Sequence: 1, ThreadID: input.ThreadID, Text: input.Text, Instructions: input.Instructions, AppID: input.AppID, JobID: input.JobID, InputFiles: input.Files, ServiceTier: input.ServiceTier}
		canonical, _ := json.Marshal(request)
		fingerprint := sha256.Sum256(canonical)
		name := sha256.Sum256(append([]byte("nPi\n"), canonical...))
		path, pathErr := filepath.Abs(filepath.Join("owned", "handoffs", hex.EncodeToString(name[:])+".md"))
		if pathErr != nil {
			return protocol.AdapterReply{}, pathErr
		}
		markdown := fmt.Sprintf("# Bridge developer handoff\n\nContract: bridge-developer-v2\nGenerated UTC: %s\nPC: nPc\nPrivate PC host: \nPaired sender: nPi\nHost epoch: %s\nDeveloper thread: %s\nOperation: text\nSequence: 1\nRequest fingerprint: %x\n\nRequired workflow", time.Now().UTC().Format(time.RFC3339), epoch, input.ThreadID, fingerprint)
		message, formatErr := codex.FormatJobMessage(input.Text, input.Instructions, path, markdown)
		if formatErr != nil {
			return protocol.AdapterReply{}, formatErr
		}
		content := []any{map[string]string{"type": "text", "text": message}}
		n.record = mustJSON(map[string]any{"id": "fixture-turn", "status": "completed", "bridgeTerminalCommitted": true, "items": []any{map[string]any{"type": "userMessage", "content": content}, map[string]string{"type": "agentMessage", "text": n.report}}})
		return protocol.AdapterReply{}, errors.New("reply lost after native acceptance")
	}
	return reply, err
}
func (n *lostAcknowledgment) History(ctx context.Context, thread, cursor string, limit int) (json.RawMessage, error) {
	if n.broken {
		return nil, errors.New("native history unavailable")
	}
	if page, exists := n.pages[cursor]; exists {
		return page, nil
	}
	return mustJSON(map[string]any{"data": []json.RawMessage{n.record}, "nextCursor": nil}), nil
}

func TestUnknownSubmissionRecoversNativeInputAfterRestart(t *testing.T) {
	for _, instructions := range []string{"", "Separate app instructions Ω"} {
		t.Run(fmt.Sprint(len(instructions)), func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			n := &lostAcknowledgment{nativeFixture: base}
			s.native = n
			n.online = true
			registerFixture(t, call, "nAlice")
			input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Lost acknowledgment", Text: "original-" + randomToken() + "\nΩ", Instructions: instructions, Files: []protocol.File{}}
			if instructions != "" {
				data := []byte("independent-input-" + randomToken())
				file, err := files.Describe(randomToken(), "owned.bin", data)
				if err != nil {
					t.Fatal(err)
				}
				if _, err := s.inbox.Put("nAlice", file, bytes.NewReader(data)); err != nil {
					t.Fatal(err)
				}
				input.Files = []protocol.File{file}
			}
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			n.broken = true
			if err := s.workPass(); err != nil {
				t.Fatal(err)
			}
			state, err := s.loadApp(s.getPeer("nAlice"))
			if err != nil || state.Jobs[input.RequestID].State != "unknown" || n.calls != 1 {
				t.Fatal("Lost acknowledgment did not stay unknown", err)
			}
			// Recreate the Pi service and restore native history, without repeating send.
			cfg := s.cfg
			s.Close()
			resumed, err := New(cfg, n, s.authenticate, nil)
			if err != nil {
				t.Fatal(err)
			}
			defer resumed.Close()
			n.broken = false
			if instructions != "" {
				unrelated := mustJSON(map[string]any{"id": "unrelated-native-turn", "status": "completed", "items": []any{}})
				n.pages = map[string]json.RawMessage{
					"":      mustJSON(map[string]any{"data": []json.RawMessage{unrelated}, "nextCursor": "older"}),
					"older": mustJSON(map[string]any{"data": []json.RawMessage{n.record}, "nextCursor": nil}),
				}
			}
			if err := resumed.workPass(); err != nil {
				t.Fatal(err)
			}
			state, err = resumed.loadApp(resumed.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			job := state.Jobs[input.RequestID]
			if n.calls != 1 || job.TurnID != "fixture-turn" || job.State != "completed" || len(job.Outputs) != 1 {
				t.Fatal("Native input recovery duplicated or falsely settled work", job, n.calls)
			}
			_, data, err := resumed.outbox.Read("nAlice", job.Outputs[0].ID)
			if err != nil || string(data) != input.Text {
				t.Fatal("Recovered output differs from independent input", err)
			}
		})
	}
}

func TestUnknownSubmissionRejectsClaimsChangedInputsAndIncompleteHistory(t *testing.T) {
	for _, failure := range []string{"agent_claim_only", "different_owner", "different_custom", "different_app", "different_files", "wrong_path", "duplicate_header", "trailing_header", "wrong_hash", "extra_input", "extra_user", "duplicate_turn", "duplicate_id", "incomplete_scan", "history_unavailable"} {
		t.Run(failure, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			n := &lostAcknowledgment{nativeFixture: base}
			s.native = n
			n.online, n.broken = true, true
			registerFixture(t, call, "nAlice")
			input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Unknown", Text: "owner-" + randomToken(), Instructions: "custom-" + randomToken(), Files: []protocol.File{}}
			call("POST", "/v2/jobs", input, "nAlice")
			if err := s.workPass(); err != nil {
				t.Fatal(err)
			}
			n.broken = false
			var record map[string]any
			if err := json.Unmarshal(n.record, &record); err != nil {
				t.Fatal(err)
			}
			content := record["items"].([]any)[0].(map[string]any)["content"].([]any)
			text := content[0].(map[string]any)["text"].(string)
			message, parseErr := codex.ParseJobMessage(text)
			if parseErr != nil {
				t.Fatal("Actual rendered native message could not parse", parseErr)
			}
			switch failure {
			case "agent_claim_only":
				record["items"] = record["items"].([]any)[1:]
			case "different_owner":
				message.Prompt = "changed owner"
			case "different_custom":
				message.Instructions = "changed custom"
			case "different_app", "different_files":
				state, err := s.loadApp(s.getPeer("nAlice"))
				if err != nil {
					t.Fatal(err)
				}
				if failure == "different_app" {
					state.ID = randomToken()
				} else {
					state.Jobs[input.RequestID].Input.Files = []protocol.File{{ID: randomToken(), Name: "other.bin", Size: 1, SHA256: strings.Repeat("b", 64)}}
					state.Jobs[input.RequestID].Fingerprint = appHash(state.Jobs[input.RequestID].Input)
				}
				if err := s.saveApp(state); err != nil {
					t.Fatal(err)
				}
			case "wrong_path":
				message.Path = `C:\other.md`
			case "duplicate_header":
				message.Header = strings.Replace(message.Header, "Paired sender: nPi", "Paired sender: \nPaired sender: nPi", 1)
			case "trailing_header":
				message.Header += "extra hidden context"
			case "wrong_hash":
				message.SHA256 = "invalid"
			case "extra_input":
				record["items"].([]any)[0].(map[string]any)["content"] = append(content, map[string]string{"type": "text", "text": "extra unbound native input"})
			case "extra_user":
				record["items"] = append(record["items"].([]any), map[string]any{"type": "userMessage", "content": content})
			case "history_unavailable":
				n.broken = true
			}
			changed, _ := json.Marshal(message)
			content[0].(map[string]any)["text"] = string(changed)
			n.record = mustJSON(record)
			if failure == "duplicate_turn" || failure == "duplicate_id" {
				n.pages = map[string]json.RawMessage{"": mustJSON(map[string]any{"data": []json.RawMessage{n.record}, "nextCursor": "second"})}
				if failure == "duplicate_turn" {
					record["id"] = "different-native-turn"
				}
				n.pages["second"] = mustJSON(map[string]any{"data": []any{record}, "nextCursor": nil})
			}
			if failure == "incomplete_scan" {
				n.pages = map[string]json.RawMessage{}
				cursor := ""
				for page := 0; page < 16; page++ {
					turn := n.record
					if page > 0 {
						turn = mustJSON(map[string]any{"id": "unrelated-" + strconv.Itoa(page), "status": "completed", "items": []any{}})
					}
					next := "page-" + strconv.Itoa(page+1)
					n.pages[cursor] = mustJSON(map[string]any{"data": []json.RawMessage{turn}, "nextCursor": next})
					cursor = next
				}
			}
			if err := s.workPass(); err != nil {
				t.Fatal(err)
			}
			state, err := s.loadApp(s.getPeer("nAlice"))
			if err != nil || state.Jobs[input.RequestID].State != "unknown" || state.Jobs[input.RequestID].TurnID != "" || len(state.Jobs[input.RequestID].Outputs) != 0 || n.calls != 1 {
				t.Fatal("Unproven history satisfied recovery or repeated submission", err)
			}
			if failure == "duplicate_turn" && state.Jobs[input.RequestID].Code != "dispatch_match_ambiguous" {
				t.Fatal("Distinct matching turns did not report ambiguity")
			}
			w := call("GET", "/v2/jobs", nil, "nAlice")
			if !strings.Contains(w.Body.String(), `"issue":{"code":`) {
				t.Fatal("Job list omitted an actionable recovery issue", w.Body.String())
			}
		})
	}
}

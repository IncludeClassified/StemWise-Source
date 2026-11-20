// Package checkpeer is a protocol peer used only by the retained local checks.
package checkpeer

import (
	"bufio"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"

	"phonepcbridge/files"
)

func Run(mode string) {
	if mode == "" {
		return
	}
	if mode == "stderr-inherited-stdout-child" {
		time.Sleep(10 * time.Second)
		os.Exit(0)
	}
	if strings.HasPrefix(mode, "stderr-startup-") {
		if mode == "stderr-startup-normal" {
			fmt.Fprintln(os.Stderr, "novel tensor VX7 cannot execute sampler opcode 91")
		}
		if mode == "stderr-startup-noisy" {
			for n := 0; n < 2000; n++ {
				fmt.Fprintln(os.Stderr, "permission denied")
				fmt.Fprintln(os.Stderr, "connection refused")
			}
			fmt.Fprintln(os.Stderr, strings.Repeat("private-account-config", 10000))
		}
		fmt.Fprint(os.Stderr, "password=split-")
		fmt.Fprintln(os.Stderr, "private-token Authorization: Bearer private-bearer")
		fmt.Fprintln(os.Stderr, `{"input":"private-owner-prompt","account":"private-account","secret":"private-config"}`)
		if mode == "stderr-startup-delayed" {
			os.Stdout.Close()
			time.Sleep(75 * time.Millisecond)
		}
		fmt.Fprint(os.Stderr, "startup database is ")
		fmt.Fprint(os.Stderr, "locked")
		if mode == "stderr-startup-hung" {
			os.Stdout.Close()
			time.Sleep(time.Minute)
		}
		if mode == "stderr-startup-cancel" {
			time.Sleep(time.Minute)
		}
		os.Exit(37)
	}
	appMode := mode == "app" || mode == "app-reject-send" || mode == "stderr-app-disconnect-send" || mode == "app-reject-create-once" || mode == "app-disconnect-create" || mode == "app-create-counts" || mode == "app-existing-created"
	scanner := bufio.NewScanner(os.Stdin)
	scanner.Buffer(make([]byte, 4096), 1<<20)
	status, initialized, resumed := "idle", false, false
	terminalReads := 0
	resumeCalls := 0
	heldReads := []json.RawMessage{}
	appReport := ""
	creationAttempts, createdThreads := 0, 0
	deleted := false
	backgroundCleaned := false
	createdProject := ""
	if mode == "app-existing-created" {
		createdProject = os.Getenv("BRIDGE_CHECK_EXISTING_PROJECT")
		createdThreads = 1
	}
	var createdServiceTier any
	emit := func(v any) { b, _ := json.Marshal(v); fmt.Println(string(b)) }
	for scanner.Scan() {
		var m struct {
			ID     json.RawMessage `json:"id"`
			Method string          `json:"method"`
			Params map[string]any  `json:"params"`
			Result json.RawMessage `json:"result"`
		}
		if json.Unmarshal(scanner.Bytes(), &m) != nil {
			os.Exit(20)
		}
		if len(m.Result) != 0 {
			// The unattended bridge never answers native interaction requests.
			os.Exit(21)
		}
		result := any(map[string]any{})
		if m.Method != "initialize" && m.Method != "initialized" && !initialized {
			os.Exit(22)
		}
		if mode == "timeout" && m.Method == "model/list" {
			time.Sleep(time.Minute)
			os.Exit(23)
		}
		switch m.Method {
		case "account/read":
			if _, ok := m.Params["refreshToken"].(bool); !ok || len(m.Params) != 1 {
				os.Exit(91)
			}
			result = map[string]any{"account": map[string]any{"type": "chatgpt", "email": "fixture@example.invalid", "planType": "fixture"}, "requiresOpenaiAuth": true}
		case "account/rateLimits/read":
			result = map[string]any{"rateLimits": map[string]any{"primary": nil, "secondary": nil}}
		case "config/read":
			if m.Params["includeLayers"] != false {
				os.Exit(92)
			}
			result = map[string]any{"config": map[string]any{"cli_auth_credentials_store": "file", "model_provider": "openai", "forced_login_method": nil}}
		case "thread/loaded/list":
			ids := []string{}
			if resumed && !deleted {
				ids = append(ids, "thread-1")
			}
			result = map[string]any{"data": ids, "nextCursor": nil}
		case "thread/delete":
			if m.Params["threadId"] != "thread-1" || status == "active" {
				os.Exit(71)
			}
			deleted = true
		case "model/list":
			for _, id := range heldReads {
				emit(map[string]any{"id": id, "result": map[string]any{"thread": map[string]any{"id": "thread-1", "status": map[string]string{"type": status}}}})
			}
			heldReads = nil
			if value, present := m.Params["cursor"]; present && value == "" {
				os.Exit(66)
			}
			result = map[string]any{"data": []any{map[string]any{"id": "bridge-check-model", "model": "bridge-check-model", "displayName": "Owned protocol fixture", "supportedReasoningEfforts": []any{map[string]string{"reasoningEffort": "low"}, map[string]string{"reasoningEffort": "high"}}, "defaultReasoningEffort": "low"}}, "nextCursor": nil}
			if mode == "app-reject-create-once" || mode == "app-create-counts" || mode == "app-existing-created" {
				result.(map[string]any)["fixtureCreationAttempts"] = creationAttempts
				result.(map[string]any)["fixtureCreatedThreads"] = createdThreads
			}
			if mode == "service-tier-catalog" {
				row := result.(map[string]any)["data"].([]any)[0].(map[string]any)
				row["serviceTiers"] = []any{map[string]string{"id": "default", "name": "Fixture standard", "description": "Isolated test metadata"}, map[string]string{"id": "fast", "name": "Fixture fast", "description": "Isolated test metadata"}, map[string]string{"id": "vendor/opaque-v3", "name": "Fixture opaque", "description": "Isolated test metadata"}}
				row["defaultServiceTier"] = "default"
			}
		case "thread/start":
			_, developerInstructions := m.Params["developerInstructions"]
			_, baseInstructions := m.Params["baseInstructions"]
			if (!appMode && mode != "handoff" && !strings.HasPrefix(mode, "retire")) || developerInstructions || baseInstructions {
				os.Exit(62)
			}
			creationAttempts++
			if mode == "app-reject-create-once" && creationAttempts == 1 {
				emit(map[string]any{"id": m.ID, "error": map[string]any{"code": -32015, "message": "Fixture creation rejected before native allocation"}})
				continue
			}
			createdThreads++
			if mode == "app-disconnect-create" {
				path := os.Getenv("BRIDGE_CHECK_CREATION_COUNT")
				prior := 0
				if raw, err := os.ReadFile(path); err == nil {
					prior, err = strconv.Atoi(string(raw))
					if err != nil {
						os.Exit(81)
					}
				} else if !os.IsNotExist(err) {
					os.Exit(81)
				}
				if err := os.WriteFile(path, []byte(strconv.Itoa(prior+1)), 0600); err != nil {
					os.Exit(81)
				}
				os.Exit(82)
			}
			createdProject = fmt.Sprint(m.Params["cwd"])
			createdServiceTier = m.Params["serviceTier"]
			if createdServiceTier != nil {
				if value, ok := createdServiceTier.(string); !ok || value == "" {
					os.Exit(74)
				}
			}
			resumed = true // Fresh thread/start already owns the native writer.
			result = map[string]any{"thread": map[string]any{"id": "thread-1", "cwd": createdProject, "status": map[string]string{"type": "idle"}, "canAcceptDirectInput": true}}
		case "thread/name/set":
			if !appMode || m.Params["threadId"] != "thread-1" {
				os.Exit(63)
			}
		case "initialize":
			if mode == "initialize-rejected" {
				emit(map[string]any{"id": m.ID, "error": map[string]any{"code": -32031, "message": "isolated initialize rejected"}})
				continue
			}
			if mode == "initialize-held" {
				if err := os.WriteFile(os.Getenv("BRIDGE_INIT_READY"), []byte("received"), 0600); err != nil {
					os.Exit(84)
				}
				time.Sleep(time.Minute)
			}
			if initialized {
				os.Exit(24)
			}
			if mode == "stderr-initialization-rejected" {
				fmt.Fprintln(os.Stderr, "invalid configuration")
				emit(map[string]any{"id": m.ID, "error": map[string]any{"code": -32001, "message": "private-config-account without a credential key label"}})
				continue
			}
			result = map[string]string{"userAgent": "independent-check"}
		case "initialized":
			initialized = true
			if mode == "stderr-inherited-stdout-parent" {
				child := exec.Command(os.Args[0], "-test.run=^TestPeerProcess$")
				child.Env = append(os.Environ(), "BRIDGE_CHECK_PEER=stderr-inherited-stdout-child")
				child.Stdout = os.Stdout
				if err := child.Start(); err != nil {
					os.Exit(72)
				}
				if err := os.WriteFile(os.Getenv("BRIDGE_CHECK_CHILD_PID"), []byte(strconv.Itoa(child.Process.Pid)), 0600); err != nil {
					child.Process.Kill()
					child.Wait()
					os.Exit(73)
				}
				fmt.Fprintln(os.Stderr, "startup database is locked")
				os.Exit(41)
			}
			continue
		case "thread/read", "thread/resume":
			if deleted {
				emit(map[string]any{"id": m.ID, "error": map[string]any{"code": -32000, "message": "Thread deleted"}})
				continue
			}
			if mode == "held-read" && m.Method == "thread/read" && status == "active" {
				heldReads = append(heldReads, m.ID)
				emit(map[string]any{"method": "check/readReceived", "params": map[string]any{"id": m.ID}})
				continue
			}
			if m.Params["threadId"] != "thread-1" {
				emit(map[string]any{"id": m.ID, "error": map[string]any{"code": -32000, "message": "Unknown session"}})
				continue
			}
			id := "thread-1"
			if mode == "wrong-session" {
				id = "thread-2"
			}
			liveStatus := status
			if m.Method == "thread/read" && (mode == "terminal-load-race" || mode == "terminal-unload-race") {
				terminalReads++
				if (mode == "terminal-load-race" && terminalReads == 1) || (mode == "terminal-unload-race" && terminalReads > 1) {
					liveStatus = "notLoaded"
				}
			}
			if !resumed && m.Method == "thread/read" && mode == "unloaded-terminal" {
				liveStatus = "notLoaded"
			}
			if mode == "terminal-bad-read" {
				liveStatus = "unrecognized"
			}
			thread := map[string]any{"id": id, "name": "Existing session", "status": map[string]string{"type": liveStatus}, "canAcceptDirectInput": true, "checkResumes": resumeCalls}
			if mode == "retire-reopen-denied" && !resumed {
				thread["canAcceptDirectInput"] = false
			}
			if createdProject != "" {
				thread["cwd"] = createdProject
			}
			result = map[string]any{"thread": thread}
			if mode == "direct-stored" && !resumed && m.Method == "thread/read" {
				thread["status"] = map[string]string{"type": "notLoaded"}
				thread["canAcceptDirectInput"] = nil
			}
			if mode == "direct-readonly-before" || (mode == "direct-lost" && resumed && m.Method == "thread/read") {
				thread["canAcceptDirectInput"] = false
			}
			if m.Method == "thread/resume" {
				if mode == "direct-readonly-before" {
					os.Exit(55) // A live readable peer must survive a denied selection.
				}
				resumeCalls++
				thread["checkResumes"] = resumeCalls
				switch mode {
				case "direct-false":
					thread["canAcceptDirectInput"] = false
				case "direct-null":
					thread["canAcceptDirectInput"] = nil
				case "direct-missing":
					delete(thread, "canAcceptDirectInput")
				case "direct-malformed":
					thread["canAcceptDirectInput"] = "true"
				}
				if len(m.Params) != 2 || m.Params["excludeTurns"] != true {
					os.Exit(25)
				}
				resumed = true
			}
			if m.Method == "thread/read" && status == "active" && (mode == "repeat-request" || mode == "conflicting-request") {
				turn := "turn-1"
				if mode == "conflicting-request" {
					turn = "changed-turn"
				}
				emit(map[string]any{"id": "approval-1", "method": "item/commandExecution/requestApproval", "params": map[string]string{"threadId": "thread-1", "turnId": turn}})
			}
		case "thread/turns/list":

			if strings.HasPrefix(mode, "retire") {
				result = map[string]any{"data": []any{map[string]any{"id": "turn-1", "status": "interrupted", "itemsView": "full", "items": []any{}}}, "nextCursor": nil}
				break
			}
			if mode == "delete-owned" {
				outcome := "interrupted"
				if status == "active" {
					outcome = "inProgress"
				}
				result = map[string]any{"data": []any{map[string]any{"id": "turn-1", "status": outcome, "itemsView": "full", "items": []any{}}}, "nextCursor": nil}
				break
			}
			if appMode && appReport != "" {
				result = map[string]any{"data": []any{map[string]any{"id": "turn-1", "status": "completed", "itemsView": "full", "items": []any{map[string]string{"id": "app-report-1", "type": "agentMessage", "text": appReport}}}}, "nextCursor": nil}
				break
			}
			result = map[string]any{"data": []any{map[string]any{"id": "old-turn", "status": "completed", "itemsView": "full", "items": []any{map[string]string{"id": "message-1", "type": "agentMessage", "text": "Stored reply"}}}}, "nextCursor": "history-next"}
			if mode == "lagging-turn" || mode == "terminal-before-ack" {
				result = map[string]any{"data": []any{map[string]any{"id": "turn-1", "status": "interrupted", "bridgeTerminalCommitted": true, "items": []any{}}}, "nextCursor": nil}
			}
			if mode == "unloaded-terminal" || mode == "terminal-bad-read" || mode == "terminal-load-race" || mode == "terminal-unload-race" {
				rows := []any{}
				for _, outcome := range []string{"completed", "failed", "interrupted", "inProgress"} {
					rows = append(rows, map[string]any{"id": "turn-" + outcome, "status": outcome, "bridgeTerminalCommitted": true, "itemsView": "full", "items": []any{map[string]string{"id": "message-" + outcome, "type": "agentMessage", "text": "Stored reply"}}})
				}
				result = map[string]any{"data": rows, "nextCursor": nil}
			}
		case "turn/start":
			if strings.HasPrefix(mode, "direct-") && mode != "direct-stored" {
				os.Exit(56) // The controller must reject before sending a native mutation.
			}
			parameterCount := 2
			if appMode {
				parameterCount = 4
			}
			if value, present := m.Params["serviceTierForTurn"]; present {
				if tier, ok := value.(string); !ok || tier == "" {
					os.Exit(75)
				}
				parameterCount++
			}
			if _, present := m.Params["serviceTier"]; present {
				os.Exit(76)
			}
			if !resumed || status != "idle" || m.Params["threadId"] != "thread-1" || len(m.Params) != parameterCount {
				os.Exit(26)
			}
			if mode == "disconnect-send" {
				os.Exit(0)
			}
			if mode == "stderr-disconnect-send" || mode == "stderr-app-disconnect-send" || mode == "stderr-disconnect-send-fragments" {
				if mode == "stderr-app-disconnect-send" && (os.Getenv("BRIDGE_CHECK_DISPATCH_STORE") != "" || os.Getenv("BRIDGE_CHECK_DISPATCH_ID") != "") {
					store, err := files.Open(os.Getenv("BRIDGE_CHECK_DISPATCH_STORE"))
					if err != nil {
						os.Exit(77)
					}
					file, _, err := store.Read("nPi", os.Getenv("BRIDGE_CHECK_DISPATCH_ID"))
					store.Close()
					if err != nil || file.Name != "job-inputs.json" {
						os.Exit(77)
					}
				}
				fmt.Fprintln(os.Stderr, string(scanner.Bytes()))
				if mode == "stderr-disconnect-send-fragments" {
					job := readJobInput(m.Params["input"].([]any))
					for _, value := range []string{job.Prompt, job.Instructions, job.Path, job.Header} {
						for _, line := range strings.Split(value, "\n") {
							fmt.Fprintln(os.Stderr, line)
							quoted, ascii := strconv.Quote(line), strconv.QuoteToASCII(line)
							fmt.Fprintln(os.Stderr, quoted[1:len(quoted)-1])
							fmt.Fprintln(os.Stderr, ascii[1:len(ascii)-1])
						}
					}
					fmt.Fprintln(os.Stderr, "novel tensor VX7 cannot execute sampler opcode 91")
				}
				fmt.Fprintln(os.Stderr, "password=private-native-token")
				fmt.Fprintln(os.Stderr, "startup database is locked")
				os.Exit(52)
			}
			if mode == "reject-send" {
				emit(map[string]any{"id": m.ID, "error": map[string]any{"code": -32000, "message": "Turn rejected"}})
				continue
			}
			if mode == "app-reject-send" {
				input := m.Params["input"].([]any)
				message := "Turn rejected password=fixture-secret " + fmt.Sprint(input[0].(map[string]any)["text"]) + " fixture-custom"
				emit(map[string]any{"id": m.ID, "error": map[string]any{"code": -32000, "message": message}})
				continue
			}
			status = "active"
			if mode == "lagging-turn" || mode == "terminal-before-ack" {
				status = "idle"
			}
			input := m.Params["input"].([]any)
			job := readJobInput(input)
			text := job.Prompt
			if appMode {
				markdown := checkHandoff(input)
				if job.Instructions != "fixture-custom" || m.Params["model"] != "bridge-check-model" || m.Params["effort"] != "high" {
					os.Exit(64)
				}
				appID, jobID := "", ""
				for _, line := range strings.Split(markdown, "\n") {
					if strings.HasPrefix(line, "App ID: ") {
						appID = strings.TrimPrefix(line, "App ID: ")
					}
					if strings.HasPrefix(line, "Job ID: ") {
						jobID = strings.TrimPrefix(line, "Job ID: ")
					}
				}
				if len(appID) != 32 || len(jobID) != 32 {
					os.Exit(65)
				}
				report, _ := json.Marshal(map[string]any{"bridgeJobReport": "v2", "appId": appID, "jobId": jobID, "revision": 1, "status": "completed", "code": "output_ready", "result": text})
				appReport = string(report)
				status = "idle"
				result = map[string]any{"turn": map[string]string{"id": "turn-1", "status": "completed"}, "receivedInput": input}
				if createdServiceTier != nil {
					result.(map[string]any)["createdServiceTier"] = createdServiceTier
				}
				if tier, present := m.Params["serviceTierForTurn"]; present {
					result.(map[string]any)["receivedServiceTier"] = tier
				}
				emit(map[string]any{"method": "turn/completed", "params": map[string]any{"threadId": "thread-1", "turn": map[string]string{"id": "turn-1", "status": "completed"}}})
				break
			}
			if mode == "handoff" {
				checkHandoff(m.Params["input"].([]any))
			}
			if mode == "burst-events" {
				for n := 0; n < 512; n++ {
					emit(map[string]any{"method": "item/agentMessage/delta", "params": map[string]string{"threadId": "thread-1", "turnId": "turn-1", "delta": text}})
				}
			}
			if mode == "burst-bytes" {
				for n := 0; n < 10; n++ {
					emit(map[string]any{"method": "item/agentMessage/delta", "params": map[string]string{"threadId": "thread-1", "turnId": "turn-1", "delta": strings.Repeat("x", 1<<20)}})
				}
			}
			if mode == "burst-escaped" {
				encoder := json.NewEncoder(os.Stdout)
				encoder.SetEscapeHTML(false)
				encoder.Encode(map[string]any{"method": "item/agentMessage/delta", "params": map[string]string{"threadId": "thread-1", "turnId": "turn-1", "delta": strings.Repeat("<", 1<<20)}})
			}
			emit(map[string]any{"method": "item/agentMessage/delta", "params": map[string]string{"threadId": "thread-1", "turnId": "turn-1", "delta": text}})
			emit(map[string]any{"id": "approval-1", "method": "item/commandExecution/requestApproval", "params": map[string]string{"threadId": "thread-1", "turnId": "turn-1"}})
			result = map[string]any{"turn": map[string]string{"id": "turn-1", "status": "inProgress"}}
			if mode == "terminal-before-ack" {
				emit(map[string]any{"method": "turn/completed", "params": map[string]any{"threadId": "thread-1", "turn": map[string]string{"id": "turn-1", "status": "interrupted"}}})
			}
			if strings.HasPrefix(mode, "handoff") {
				result.(map[string]any)["receivedInput"] = m.Params["input"]
			}
			if mode == "bad-ack" {
				result = map[string]bool{"success": true}
			}
		case "thread/backgroundTerminals/clean":
			if m.Params["threadId"] != "thread-1" || len(m.Params) != 1 {
				os.Exit(71)
			}
			if mode == "retire-rejected" {
				emit(map[string]any{"id": m.ID, "error": map[string]any{"code": -32009, "message": "private-retirement-native-data"}})
				continue
			}
			backgroundCleaned = true
		case "thread/backgroundTerminals/list":
			if m.Params["threadId"] != "thread-1" || (strings.HasPrefix(mode, "retire") && !backgroundCleaned) {
				os.Exit(72)
			}
			result = map[string]any{"data": []any{}, "nextCursor": nil}
			if mode == "retire-running" {
				result = map[string]any{"data": []any{map[string]string{"processId": "still-running"}}, "nextCursor": nil}
			}
		case "turn/interrupt":
			if m.Params["threadId"] != "thread-1" || m.Params["turnId"] != "turn-1" {
				os.Exit(28)
			}
			if mode == "repeat-request" {
				emit(map[string]any{"method": "serverRequest/resolved", "params": map[string]any{"threadId": "thread-1", "requestId": "approval-1"}})
			}
			status = "idle"
			emit(map[string]any{"method": "turn/completed", "params": map[string]any{"threadId": "thread-1", "turn": map[string]string{"id": "turn-1", "status": "interrupted"}}})
		default:
			os.Exit(29)
		}
		if mode == "wrong-response" && m.Method == "model/list" {
			m.ID = json.RawMessage(`9999`)
		}
		if m.Method == "thread/turns/list" && strings.HasPrefix(mode, "history-page-") {
			variant := strings.SplitN(mode, "-page-", 2)[1]
			page := result.(map[string]any)
			rows := page["data"].([]any)
			row := rows[0].(map[string]any)
			switch variant {
			case "null":
				result = nil
			case "missing-data":
				delete(page, "data")
			case "null-data":
				page["data"] = nil
			case "wrong-data":
				page["data"] = "not an array"
			case "bad-cursor":
				page["nextCursor"] = true
			case "bad-backwards-cursor":
				page["backwardsCursor"] = map[string]bool{"ready": true}
			case "stalled":
				page["nextCursor"] = m.Params["cursor"]
			case "duplicate":
				page["data"] = append(rows, row)
			case "too-many":
				page["data"] = []any{row, map[string]any{"id": "other-row"}}
			case "missing-id":
				delete(row, "id")
			case "bad-id":
				row["id"] = "../other"
			case "missing-status":
				delete(row, "status")
			case "bad-status":
				row["status"] = "idle"
			case "missing-items":
				delete(row, "items")
			case "null-items":
				row["items"] = nil
			case "partial-items":
				row["itemsView"] = "summary"
			case "null-items-view":
				row["itemsView"] = nil
			case "bad-item":
				row["items"] = []any{map[string]string{"type": "agentMessage", "text": "No identity"}}
			case "missing-text", "null-text", "bad-text":
				item := rows[0].(map[string]any)["items"].([]any)[0].(map[string]string)
				switch variant {
				case "missing-text":
					delete(item, "text")
				case "null-text":
					row["items"] = []any{map[string]any{"id": "message-1", "type": "agentMessage", "text": nil}}
				case "bad-text":
					row["items"] = []any{map[string]any{"id": "message-1", "type": "agentMessage", "text": true}}
				}
			case "missing-content", "null-content":
				row["items"] = []any{map[string]any{"id": "message-1", "type": "userMessage"}}
				if variant == "null-content" {
					row["items"].([]any)[0].(map[string]any)["content"] = nil
				}
			case "default-items-view":
				delete(row, "itemsView")
			case "empty-items":
				row["items"] = []any{}
			case "future-item":
				row["items"] = []any{map[string]any{"id": "future-1", "type": "futureItem", "text": map[string]bool{"new": true}, "content": "newVariant"}}
			case "empty":
				page["data"] = []any{}
				page["nextCursor"], page["backwardsCursor"] = nil, nil
			case "extra":
				page["nextCursor"] = nil
				page["futureField"] = "preserved metadata"
			default:
				os.Exit(30)
			}
		}
		emit(map[string]any{"id": m.ID, "result": result})
	}
	os.Exit(0)
}

type jobInput struct {
	Version      string `json:"bridgeJobInput"`
	ReadFirst    string `json:"readBeforeWork"`
	Path         string `json:"handoffPath"`
	Hash         string `json:"handoffSha256"`
	Header       string `json:"handoffHeader"`
	Prompt       string `json:"prompt"`
	Instructions string `json:"customInstructions"`
}

func readJobInput(input []any) jobInput {
	if len(input) != 1 {
		os.Exit(30)
	}
	item := input[0].(map[string]any)
	var job jobInput
	if len(item) != 2 || item["type"] != "text" || json.Unmarshal([]byte(fmt.Sprint(item["text"])), &job) != nil || job.Version != "v1" || !strings.Contains(job.ReadFirst, "Before doing any work, read the entire local Markdown file") || job.Prompt == "" {
		os.Exit(31)
	}
	return job
}

func checkHandoff(input []any) string {
	job := readJobInput(input)
	data, err := os.ReadFile(job.Path)
	hash := sha256.Sum256(data)
	if err != nil || hex.EncodeToString(hash[:]) != job.Hash || !strings.HasPrefix(string(data), job.Header) || !strings.Contains(string(data), "\nPC: nPc\n") || !strings.Contains(string(data), "\nDeveloper thread: thread-1\n") || strings.Contains(fmt.Sprint(input[0].(map[string]any)["text"]), "role=app_worker") {
		os.Exit(33)
	}
	return string(data)
}

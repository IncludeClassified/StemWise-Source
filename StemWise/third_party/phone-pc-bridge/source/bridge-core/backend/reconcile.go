package backend

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"strings"
	"time"

	"phonepcbridge/codex"
)

// Match native user inputs, never an agent's claim that it handled this job.
func (s *Service) submittedJob(app string, j *appJob, turn json.RawMessage) bool {
	var record struct {
		Items []struct {
			Type    string `json:"type"`
			Content []struct {
				Type string `json:"type"`
				Text string `json:"text"`
			} `json:"content"`
		} `json:"items"`
	}
	if json.Unmarshal(turn, &record) != nil {
		return false
	}
	user := -1
	for index, item := range record.Items {
		if item.Type == "userMessage" {
			if user != -1 {
				return false
			}
			user = index
		}
	}
	if user == -1 {
		return false
	}
	content := record.Items[user].Content
	if len(content) != 1 || content[0].Type != "text" {
		return false
	}
	message, err := codex.ParseJobMessage(content[0].Text)
	if err != nil || message.Prompt != j.Input.Text || message.Instructions != j.Input.Instructions {
		return false
	}
	blocks := strings.SplitN(message.Header, "\n\n", 3)
	if len(blocks) != 3 || blocks[0] != "# Bridge developer handoff" || blocks[2] != "" {
		return false
	}
	fields := map[string]string{}
	for _, line := range strings.Split(blocks[1], "\n") {
		key, value, ok := strings.Cut(line, ": ")
		_, duplicate := fields[key]
		if !ok || duplicate {
			return false
		}
		fields[key] = value
	}
	for _, key := range []string{"Contract", "Generated UTC", "PC", "Private PC host", "Paired sender", "Host epoch", "Developer thread", "Operation", "Sequence", "Request fingerprint"} {
		if _, exists := fields[key]; !exists {
			return false
		}
	}
	if len(fields) != 10 || fields["Contract"] != "bridge-developer-v2" || fields["PC"] != s.cfg.PCIdentity || fields["Private PC host"] != s.cfg.PCHost || fields["Paired sender"] != s.cfg.Identity || fields["Developer thread"] != j.ThreadID || fields["Operation"] != "text" || fields["Sequence"] != "1" || !token.MatchString(fields["Host epoch"]) {
		return false
	}
	if _, err := time.Parse(time.RFC3339, fields["Generated UTC"]); err != nil {
		return false
	}
	request := Request{Epoch: fields["Host epoch"], Operation: "text", Sequence: 1, ThreadID: j.ThreadID, Text: j.Input.Text, Instructions: j.Input.Instructions, AppID: app, JobID: j.ID, InputFiles: j.Input.Files, ServiceTier: j.ServiceTier}
	canonical, err := json.Marshal(request)
	if err != nil {
		return false
	}
	fingerprint := sha256.Sum256(canonical)
	name := sha256.Sum256(append([]byte(s.cfg.Identity+"\n"), canonical...))
	file := hex.EncodeToString(name[:]) + ".md"
	return fields["Request fingerprint"] == hex.EncodeToString(fingerprint[:]) && (strings.HasSuffix(message.Path, "/"+file) || strings.HasSuffix(message.Path, "\\"+file))
}

func (s *Service) reconcileApp(parent context.Context, app string, j *appJob) *appLogEntry {
	ctx, cancel := context.WithTimeout(parent, 30*time.Second)
	defer cancel()
	cursor, match := "", ""
	seenTurns, seenCursors := map[string]bool{}, map[string]bool{}
	j.Code = "dispatch_outcome_unconfirmed"
	if entry, err := s.restoreCancelledWriter(ctx, app, j); err != nil {
		j.Code = "dispatch_observation_unavailable"
		j.Updated = time.Now().UnixMilli()
		return entry
	}
	// Fail closed if a bounded scan cannot establish that the matching turn is unique.
	for page := 0; page < 16; page++ {
		raw, err := s.native.History(ctx, j.ThreadID, cursor, 25)
		var history struct {
			Data       []json.RawMessage `json:"data"`
			NextCursor *string           `json:"nextCursor"`
		}
		if err != nil || json.Unmarshal(raw, &history) != nil || history.Data == nil || len(history.Data) > 25 {
			j.Code = "dispatch_observation_unavailable"
			return nil
		}
		for _, turn := range history.Data {
			var identity struct {
				ID string `json:"id"`
			}
			if json.Unmarshal(turn, &identity) != nil || !nodeID.MatchString(identity.ID) || seenTurns[identity.ID] {
				j.Code = "dispatch_history_unconfirmed"
				return nil
			}
			seenTurns[identity.ID] = true
			if s.submittedJob(app, j, turn) {
				if match != "" {
					j.Code = "dispatch_match_ambiguous"
					return nil
				}
				match = identity.ID
			}
		}
		if history.NextCursor == nil || *history.NextCursor == "" {
			if match != "" {
				j.TurnID, j.State, j.Code = match, "submitted", "native_submission_recovered"
				j.Updated = time.Now().UnixMilli()
			}
			return nil
		}
		seenCursors[cursor] = true
		cursor = *history.NextCursor
		if len(cursor) > 4096 || seenCursors[cursor] {
			j.Code = "dispatch_history_unconfirmed"
			return nil
		}
	}
	j.Code = "dispatch_history_unconfirmed"
	return nil
}

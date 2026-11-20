package backend

import (
	"encoding/json"
	"regexp"
	"strings"
	"time"
)

type appProgress struct {
	Stage      string `json:"stage"`
	Formatting string `json:"formatting"`
	ItemID     string `json:"itemId"`
	ObservedAt int64  `json:"observedAt"`
}

var progressHeading = regexp.MustCompile(`^#{1,6}[ \t]+([^\r\n]+?)(?:[ \t]+#+)?$`)

func progressStage(stage string) bool {
	return contains([]string{"preparing", "reading", "processing", "testing", "packaging", "transferring", "waiting"}, stage)
}

// Only a reserved whole-message token claims progress; ordinary prose is ignored.
func commentaryMarker(text string) (string, string, bool) {
	value := strings.TrimSpace(strings.ReplaceAll(text, "\r\n", "\n"))
	if heading := progressHeading.FindStringSubmatch(value); heading != nil {
		value = strings.TrimSpace(heading[1])
	} else {
		for _, prefix := range []string{"- ", "+ ", "* "} {
			if strings.HasPrefix(value, prefix) {
				value = strings.TrimSpace(strings.TrimPrefix(value, prefix))
				break
			}
		}
	}
	lines := strings.Split(value, "\n")
	if len(lines) == 3 && contains([]string{"```", "```text", "```plaintext"}, lines[0]) && lines[2] == "```" {
		value = strings.TrimSpace(lines[1])
	}
	for _, wrapper := range []string{"**", "__", "*", "_", "`"} {
		if len(value) > 2*len(wrapper) && strings.HasPrefix(value, wrapper) && strings.HasSuffix(value, wrapper) {
			value = strings.TrimSpace(value[len(wrapper) : len(value)-len(wrapper)])
			break
		}
	}
	value = strings.ToLower(value)
	const prefix = "[[bridge:"
	if !strings.HasPrefix(value, prefix) {
		return "", "", false
	}
	if len(text) > 256 || !strings.HasSuffix(value, "]]") {
		return "unknown", "unrecognized", true
	}
	stage := value[len(prefix) : len(value)-2]
	if !progressStage(stage) {
		return "unknown", "unrecognized", true
	}
	if text == prefix+stage+"]]" {
		return stage, "plain", true
	}
	return stage, "normalized", true
}

func validAppProgress(p *appProgress) bool {
	return p == nil || (p.ObservedAt > 0 && nodeID.MatchString(p.ItemID) &&
		((progressStage(p.Stage) && contains([]string{"plain", "normalized"}, p.Formatting)) ||
			(p.Stage == "unknown" && p.Formatting == "unrecognized")))
}

// Commentary supplies advisory detail only; report/outcome/file authority stays separate.
func observeCommentary(j *appJob, raw json.RawMessage) {
	if j.State != "submitted" || j.CancelRequested != 0 {
		j.Progress = nil
		return
	}
	var turn struct {
		ID     string `json:"id"`
		Status string `json:"status"`
		Items  []struct {
			ID        string `json:"id"`
			Type      string `json:"type"`
			Phase     string `json:"phase"`
			Text      string `json:"text"`
			Committed bool   `json:"bridgeReportCommitted"`
		} `json:"items"`
	}
	if json.Unmarshal(raw, &turn) != nil || turn.ID != j.TurnID || turn.Status != "inProgress" {
		j.Progress = nil
		return
	}
	var latest *appProgress
	for _, item := range turn.Items {
		if item.Type != "agentMessage" || !item.Committed || !nodeID.MatchString(item.ID) ||
			(item.Phase != "" && item.Phase != "commentary") || appClaimsReport(item.Text) {
			continue
		}
		stage, format, claimed := commentaryMarker(item.Text)
		if !claimed {
			continue
		}
		latest = &appProgress{Stage: stage, Formatting: format, ItemID: item.ID}
	}
	if latest == nil {
		return
	}
	if j.Progress != nil && j.Progress.ItemID == latest.ItemID && j.Progress.Stage == latest.Stage && j.Progress.Formatting == latest.Formatting {
		return
	}
	latest.ObservedAt = time.Now().UnixMilli()
	j.Progress = latest
}

func appProgressView(j *appJob) any {
	if j.Progress == nil || j.State != "submitted" || j.CancelRequested != 0 {
		return nil
	}
	return map[string]any{"stage": j.Progress.Stage, "source": "commentary_marker", "formatting": j.Progress.Formatting, "observedAt": j.Progress.ObservedAt}
}

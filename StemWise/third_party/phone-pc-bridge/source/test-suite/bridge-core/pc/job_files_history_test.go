package pc

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func fileHistoryPage(t *testing.T, turns ...map[string]any) json.RawMessage {
	t.Helper()
	raw, err := json.Marshal(map[string]any{"data": turns, "nextCursor": nil})
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func fileReport(request Request, revision int, status string, outputs []protocol.File) string {
	value := map[string]any{"bridgeJobReport": "v2", "appId": request.AppID, "jobId": request.JobID, "revision": revision, "status": status, "code": "fixture_report"}
	if outputs != nil {
		value["files"] = outputs
	}
	raw, _ := json.Marshal(value)
	return string(raw)
}

func TestHistoryOwnershipAnnotationUsesReceiverEvidenceAndPreservesNativeOutcome(t *testing.T) {
	for _, scenario := range []string{"owned_forged_true", "missing_forged_false", "changed_descriptor", "uncommitted_preview", "failed_turn_preview", "owner_store_closed", "owner_request_noncanonical", "owner_deleted_marker_malformed"} {
		t.Run(scenario, func(t *testing.T) {
			s, native := jobFilesService(t)
			_, request := newFileJob(t, s, native, nil)
			bindFileJob(t, s, request)
			file, _ := files.Describe(randomToken(), "output.bin", []byte("output"))
			if _, err := s.QueueJobFileReader(context.Background(), "nPi", request.AppID, request.JobID, request.ThreadID, file, strings.NewReader("output")); err != nil {
				t.Fatal(err)
			}
			if scenario == "missing_forged_false" {
				file.ID = randomToken()
			}
			if scenario == "changed_descriptor" {
				file.Name = "changed.bin"
			}
			committed := scenario != "uncommitted_preview"
			text := fileReport(request, 1, "completed", []protocol.File{file})
			turn := map[string]any{"id": "exact-turn", "status": "completed", "bridgeTerminalCommitted": committed, "items": []any{map[string]any{"type": "agentMessage", "text": text, "bridgeReportCommitted": committed, "bridgeOutputOwnershipUnconfirmed": scenario != "missing_forged_false"}}}
			if scenario == "failed_turn_preview" {
				turn["status"] = "failed"
				turn["items"].([]any)[0].(map[string]any)["bridgeReportCommitted"] = false
			}
			if scenario == "owner_store_closed" {
				s.creations.Close()
			}
			if scenario == "owner_request_noncanonical" {
				id := creationID("nPi", request.AppID, request.JobID, "request")
				f, raw, _ := s.creations.Read("nPi", id)
				s.creations.Discard("nPi", id)
				raw = append(raw, '\n')
				f, _ = files.Describe(id, f.Name, raw)
				if _, err := s.creations.Put("nPi", f, strings.NewReader(string(raw))); err != nil {
					t.Fatal(err)
				}
			}
			if scenario == "owner_deleted_marker_malformed" {
				f, _ := files.Describe(creationID("nPi", request.AppID, request.JobID, "deleted"), "creation-deleted.json", []byte(`{}`))
				if _, err := s.creations.Put("nPi", f, strings.NewReader(`{}`)); err != nil {
					t.Fatal(err)
				}
			}
			page := fileHistoryPage(t, turn)
			err := s.verifyJobHistoryFiles("nPi", request.ThreadID, &page)
			if scenario == "owner_store_closed" || scenario == "owner_request_noncanonical" || scenario == "owner_deleted_marker_malformed" {
				if err == nil {
					t.Fatal("Storage uncertainty became a permanent ownership claim")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			var result struct {
				Data []struct {
					ID, Status string
					Items      []struct {
						Text        string
						Unconfirmed bool `json:"bridgeOutputOwnershipUnconfirmed"`
					}
				}
			}
			if json.Unmarshal(page, &result) != nil || len(result.Data) != 1 || result.Data[0].ID != "exact-turn" || result.Data[0].Status != turn["status"] || result.Data[0].Items[0].Text != text {
				t.Fatal("Ownership checks changed authoritative native outcome", string(page))
			}
			want := scenario == "missing_forged_false" || scenario == "changed_descriptor"
			if result.Data[0].Items[0].Unconfirmed != want {
				t.Fatal("Native-supplied flags substituted for receiver ownership", string(page))
			}
		})
	}
}

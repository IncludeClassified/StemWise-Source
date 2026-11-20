package backend

import (
	"bytes"
	"strings"
	"testing"
	"time"

	"phonepcbridge/files"
)

func TestEmptyFileJobRequiresExplicitSizeBeforeAcceptance(t *testing.T) {
	for _, variant := range []string{"zero", "missing", "null"} {
		t.Run(variant, func(t *testing.T) {
			s, native, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			f, _ := files.Describe(randomToken(), "empty.bin", nil)
			if _, err := s.inbox.Put("nAlice", f, bytes.NewReader(nil)); err != nil {
				t.Fatal(err)
			}
			descriptor := map[string]any{"id": f.ID, "name": f.Name, "sha256": f.SHA256}
			if variant == "zero" {
				descriptor["size"] = 0
			} else if variant == "null" {
				descriptor["size"] = nil
			}
			id := randomToken()
			input := map[string]any{"requestId": id, "createdAt": time.Now().UnixMilli(), "title": "Explicit empty input", "text": "original-" + randomToken(), "files": []any{descriptor}}
			w := call("POST", "/v2/jobs", input, "nAlice")
			state, err := s.loadApp(s.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			if variant == "zero" {
				if w.Code != 202 || state.Jobs[id] == nil || state.Jobs[id].Input.Files[0] != f {
					t.Fatal("Valid zero-byte input lost its exact descriptor", w.Code)
				}
			} else if w.Code != 400 || len(state.Jobs) != 0 {
				t.Fatal("Incomplete descriptor became an accepted empty-file job", variant, w.Code)
			}
			if actual, data, err := s.inbox.Read("nAlice", f.ID); err != nil || actual != f || len(data) != 0 || native.calls != 0 {
				t.Fatal("Descriptor validation changed original bytes or triggered native work", err)
			}
		})
	}
}

func TestEmptyOutputRequiresExplicitSizeBeforePublication(t *testing.T) {
	for _, variant := range []string{"zero", "missing", "null"} {
		t.Run(variant, func(t *testing.T) {
			s, native, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			p := s.getPeer("nAlice")
			state, err := s.loadApp(p)
			if err != nil {
				t.Fatal(err)
			}
			j := ownedOutputJob(randomToken())
			state.Jobs[j.ID] = j
			f, _ := files.Describe(randomToken(), "empty.bin", nil)
			descriptor := map[string]any{"id": f.ID, "name": f.Name, "sha256": f.SHA256}
			if variant == "zero" {
				descriptor["size"] = 0
			} else if variant == "null" {
				descriptor["size"] = nil
			}
			native.outputs[f.ID] = nil
			native.report = string(mustJSON(map[string]any{"bridgeJobReport": "v2", "appId": state.ID, "jobId": j.ID, "revision": 1, "status": "completed", "code": "ready", "files": []any{descriptor}}))
			if err := s.refreshApp(p, state, j); err != nil {
				t.Fatal(err)
			}
			if j.State != "completed" || native.calls != 0 {
				t.Fatal("Descriptor validation changed authoritative completion or replayed work")
			}
			if variant == "zero" {
				if j.Code != "native_turn_completed" || len(j.Outputs) != 1 || j.Outputs[0] != f {
					t.Fatal("Valid zero-byte output was rejected")
				}
			} else {
				if j.Code != "invalid_developer_protocol" || j.Report != nil || len(j.Outputs) != 0 || len(j.Log) != 1 {
					t.Fatal("Incomplete descriptor became a valid empty output", variant)
				}
				page, err := s.outbox.List(p.identity, "", 100)
				if err != nil || len(page.Files) != 0 || !strings.Contains(string(mustJSON(j.Log)), "invalid_developer_protocol") {
					t.Fatal("Malformed descriptor staged output or lost its independent diagnostic", err)
				}
			}
		})
	}
}

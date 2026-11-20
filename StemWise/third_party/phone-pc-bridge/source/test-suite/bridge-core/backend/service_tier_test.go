package backend

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"phonepcbridge/protocol"
)

const fixtureTierCatalog = `{"data":[{"model":"fixture-model","supportedReasoningEfforts":[{"reasoningEffort":"high"}],"serviceTiers":[{"id":"fixture-tier-a","name":"Fixture A","description":"First native fixture lane","private":"must_not_export"},{"id":"fixture-tier-b","name":"Fixture B","description":"Second native fixture lane"}],"defaultServiceTier":"fixture-tier-a","private":"must_not_export"}],"nextCursor":null}`

type serviceTierNative struct {
	*nativeFixture
	catalog      string
	requests     []protocol.AdapterRequest
	catalogCalls int
}

func TestServiceTierDefaultsAreCapturedOnceAcrossRetriesAndReopen(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	n := &serviceTierNative{nativeFixture: base, catalog: fixtureTierCatalog}
	s.native = n
	registerFixture(t, call, "nAlice")
	settings := func(tier any) *httptest.ResponseRecorder {
		return call("PUT", "/v2/codex/settings", map[string]any{"model": "fixture-model", "effort": "high", "serviceTier": tier}, "nAlice")
	}
	if w := settings("fixture-tier-a"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	input := tierJobInput(randomToken(), "")
	input["model"], input["effort"], input["serviceTier"] = "", "", nil
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	if w := settings("fixture-tier-b"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	input["serviceTier"] = ""
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 200 {
		t.Fatal("Null/empty retry changed immutable input identity", w.Body.String())
	}
	delete(input, "serviceTier")
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 200 {
		t.Fatal("Omitted tier retry changed immutable input identity", w.Body.String())
	}
	input["serviceTier"] = "fixture-tier-b"
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 409 {
		t.Fatal("Changed requested tier did not conflict", w.Body.String())
	}
	resumed, err := New(s.cfg, n, func(_ context.Context, remote string) (string, error) { return remote, nil }, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer resumed.Close()
	state, err := resumed.loadApp(resumed.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	j := state.Jobs[input["requestId"].(string)]
	if j.ServiceTier != "fixture-tier-a" || j.Input.ServiceTier != "" || j.Model != "fixture-model" || j.Effort != "high" || state.ServiceTier != "fixture-tier-b" {
		t.Fatal("Retry/reopen recaptured later app settings")
	}
	view := appJobView(j)
	if view["serviceTier"] != j.ServiceTier || view["model"] != j.Model || view["effort"] != j.Effort {
		t.Fatal("Job view does not expose captured model choices")
	}
	if err := resumed.workPass(); err != nil {
		t.Fatal(err)
	}
	if n.calls != 1 || n.input.ServiceTier != "fixture-tier-a" {
		t.Fatal("Dispatched job recaptured default tier", n.input.ServiceTier)
	}
	if w := settings(nil); w.Code != 200 {
		t.Fatal("Null full-replacement setting did not clear tier", w.Body.String())
	}
	state, err = s.loadApp(s.getPeer("nAlice"))
	if err != nil || state.ServiceTier != "" || state.Jobs[j.ID].ServiceTier != "fixture-tier-a" {
		t.Fatal("Clearing saved default changed an accepted job", err)
	}
}

func TestUnknownTierMetadataDoesNotBlockUntieredJobsAndNeverAuthorizesFromDefault(t *testing.T) {
	for _, tier := range []string{"", "fixture-tier-a"} {
		t.Run(tier, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			base.online = true
			n := &serviceTierNative{nativeFixture: base, catalog: `{"data":[{"model":"fixture-model","supportedReasoningEfforts":[{"reasoningEffort":"high"}],"defaultServiceTier":"fixture-tier-a"}],"nextCursor":null}`}
			s.native = n
			registerFixture(t, call, "nAlice")
			input := tierJobInput(randomToken(), tier)
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			if err := s.workPass(); err != nil {
				t.Fatal(err)
			}
			state, err := s.loadApp(s.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			j := state.Jobs[input["requestId"].(string)]
			if tier == "" {
				if j.State != "completed" || n.calls != 1 || n.input.ServiceTier != "" {
					t.Fatal("Unknown tier list blocked unchanged model/effort work", j.State, j.Code)
				}
			} else {
				if j.State != "queued" || j.Code != "model_catalog_unavailable" || len(n.requests) != 0 || j.ThreadID != "" {
					t.Fatal("Default alone authorized native tier or created a chat before capability confirmation", j.State, j.Code, n.requests)
				}
				n.catalog = fixtureTierCatalog
				if err := s.workPass(); err != nil {
					t.Fatal(err)
				}
				if n.calls != 1 || n.input.ServiceTier != tier {
					t.Fatal("Original queued tier did not recover when capability became confirmed")
				}
			}
		})
	}
}

func TestUnsupportedTierAndMissingModelHaveDeterministicErrors(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	n := &serviceTierNative{nativeFixture: base, catalog: `{"data":[{"model":"fixture-model","supportedReasoningEfforts":[{"reasoningEffort":"high"}],"serviceTiers":[]}],"nextCursor":null}`}
	s.native = n
	registerFixture(t, call, "nAlice")
	if w := call("PUT", "/v2/codex/settings", map[string]string{"model": "fixture-model", "serviceTier": "fixture-tier-a"}, "nAlice"); w.Code != 409 || !strings.Contains(w.Body.String(), "service_tier_unavailable") {
		t.Fatal("Confirmed unsupported tier was not distinguished from catalog failure", w.Body.String())
	}
	beforeCatalog := n.catalogCalls
	for _, route := range []string{"/v2/jobs", "/v2/codex/settings"} {
		body := tierJobInput(randomToken(), "fixture-tier-a")
		method := "POST"
		if route == "/v2/codex/settings" {
			body = map[string]any{"serviceTier": "fixture-tier-a"}
			method = "PUT"
		} else {
			body["model"], body["effort"] = "", ""
		}
		if w := call(method, route, body, "nAlice"); w.Code != 400 || !strings.Contains(w.Body.String(), "service_tier_model_required") {
			t.Fatal("Unbound tier was admitted or treated as transient", w.Body.String())
		}
	}
	if n.catalogCalls != beforeCatalog {
		t.Fatal("Unbound service tier reached native catalog before rejection")
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil || len(state.Jobs) != 0 || state.ServiceTier != "" || len(n.requests) != 0 {
		t.Fatal("Rejected settings/unbound job changed accepted state", err)
	}
	input := tierJobInput(randomToken(), "fixture-tier-a")
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err = s.loadApp(s.getPeer("nAlice"))
	if err != nil || state.Jobs[input["requestId"].(string)].State != "rejected" || state.Jobs[input["requestId"].(string)].Code != "service_tier_unavailable" || len(n.requests) != 0 {
		t.Fatal("Known unsupported tier created native work or lost deterministic rejection", err)
	}
}

func TestServiceTierMetadataAbsenceIsDistinctFromKnownEmpty(t *testing.T) {
	for _, fields := range []string{"", `,"serviceTiers":null,"defaultServiceTier":null`, `,"serviceTiers":[],"defaultServiceTier":null`} {
		t.Run(fields, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			base.online = true
			s.native = &serviceTierNative{nativeFixture: base, catalog: `{"data":[{"model":"fixture-model"` + fields + `}],"nextCursor":null}`}
			registerFixture(t, call, "nAlice")
			w := call("GET", "/v2/codex/models", nil, "nAlice")
			if w.Code != 200 {
				t.Fatal(w.Body.String())
			}
			var response struct {
				Data struct{ Data []map[string]json.RawMessage }
			}
			if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
				t.Fatal(err)
			}
			wanted := "null"
			if strings.Contains(fields, `"serviceTiers":[]`) {
				wanted = "[]"
			}
			if len(response.Data.Data) != 1 || string(response.Data.Data[0]["serviceTiers"]) != wanted || string(response.Data.Data[0]["defaultServiceTier"]) != "null" {
				t.Fatal("Tier availability was guessed or unknown/empty capability was lost", w.Body.String())
			}
		})
	}
}

func TestMalformedServiceTierCatalogCannotAuthorizeSettings(t *testing.T) {
	for _, fields := range []string{
		`"serviceTiers":{}`,
		`"serviceTiers":[{"id":"fixture-tier-a","name":"A","description":"x"},{"id":"fixture-tier-a","name":"B","description":"x"}]`,
		`"serviceTiers":[{"id":"","name":"A","description":"x"}]`,
		`"serviceTiers":[{"id":"fixture-tier-a","name":7,"description":"x"}]`,
		`"serviceTiers":[{"id":"fixture-tier-a","name":"A","description":null}]`,
		`"serviceTiers":[{"id":"fixture-tier-a","name":"A","description":"x"}],"defaultServiceTier":"not-advertised"`,
		`"serviceTiers":[],"defaultServiceTier":"fixture-tier-a"`,
		`"serviceTiers":[],"defaultServiceTier":7`,
	} {
		t.Run(fields, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			base.online = true
			s.native = &serviceTierNative{nativeFixture: base, catalog: fmt.Sprintf(`{"data":[{"model":"fixture-model",%s}],"nextCursor":null}`, fields)}
			registerFixture(t, call, "nAlice")
			w := call("GET", "/v2/codex/models", nil, "nAlice")
			if w.Code != 503 || !strings.Contains(w.Body.String(), "model_catalog_unavailable") {
				t.Fatal("Malformed native tier metadata was accepted", w.Code, w.Body.String())
			}
		})
	}
}

func TestEmptyServiceTierKeepsEstablishedAppInputCanonicalBytes(t *testing.T) {
	const expected = `{"requestId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","title":"canonical","text":"unchanged","instructions":"","files":[],"createdAt":1,"model":"","effort":""}`
	var input appInput
	if err := appDecode([]byte(expected), &input); err != nil {
		t.Fatal(err)
	}
	if actual := string(mustJSON(input)); actual != expected {
		t.Fatal("New empty tier changed accepted request fingerprint input bytes", actual)
	}
}

func (n *serviceTierNative) Models(context.Context, string) (json.RawMessage, error) {
	n.catalogCalls++
	return json.RawMessage(n.catalog), nil
}
func (n *serviceTierNative) Call(ctx context.Context, request protocol.AdapterRequest) (protocol.AdapterReply, error) {
	if request.Operation == "auth" {
		return n.nativeFixture.Call(ctx, request)
	}
	n.requests = append(n.requests, request)
	return n.nativeFixture.Call(ctx, request)
}

func tierJobInput(id, tier string) map[string]any {
	return map[string]any{"requestId": id, "createdAt": time.Now().UnixMilli(), "title": "Native service tier", "text": "original-" + id, "instructions": "", "files": []protocol.File{}, "model": "fixture-model", "effort": "high", "serviceTier": tier}
}

func TestNativeServiceTierCatalogIsProjectedWithoutPrivateFields(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	s.native = &serviceTierNative{nativeFixture: base, catalog: fixtureTierCatalog}
	registerFixture(t, call, "nAlice")
	w := call("GET", "/v2/codex/models", nil, "nAlice")
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	var response struct {
		Data struct {
			Data []struct {
				Tiers   []struct{ ID, Name, Description string } `json:"serviceTiers"`
				Default *string                                  `json:"defaultServiceTier"`
			}
		}
	}
	if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if len(response.Data.Data) != 1 || len(response.Data.Data[0].Tiers) != 2 || response.Data.Data[0].Tiers[0].ID != "fixture-tier-a" || response.Data.Data[0].Tiers[0].Name != "Fixture A" || response.Data.Data[0].Tiers[0].Description != "First native fixture lane" || response.Data.Data[0].Default == nil || *response.Data.Data[0].Default != "fixture-tier-a" || strings.Contains(w.Body.String(), "must_not_export") {
		t.Fatal("Actual native tier capability metadata was dropped, altered or leaked", w.Body.String())
	}
}

func TestJobCanCaptureAndForwardAdvertisedServiceTier(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	n := &serviceTierNative{nativeFixture: base, catalog: fixtureTierCatalog}
	s.native = n
	registerFixture(t, call, "nAlice")
	input := tierJobInput(randomToken(), "fixture-tier-b")
	w := call("POST", "/v2/jobs", input, "nAlice")
	if w.Code != 202 {
		t.Fatal("Advertised native tier was rejected at job ingress", w.Code, w.Body.String())
	}
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err := s.loadApp(s.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	job := state.Jobs[input["requestId"].(string)]
	if job.State != "completed" || n.calls != 1 {
		t.Fatal("Tiered job did not complete through ordinary dispatch", job.State, job.Code)
	}
	if w := call("POST", "/v2/jobs/"+job.ID+"/delivery", map[string]any{"files": job.Outputs}, "nAlice"); w.Code != 200 {
		t.Fatal("Verified tiered job delivery failed", w.Body.String())
	}
	seenCreate, seenSend, seenDelete := false, false, false
	for _, request := range n.requests {
		if request.Operation == "create" {
			seenCreate = true
		}
		if request.Operation == "send" {
			seenSend = true
		}
		if request.Operation == "delete" {
			seenDelete = true
		}
		if request.Operation == "create" || request.Operation == "send" || request.Operation == "delete" {
			if request.ServiceTier != "fixture-tier-b" {
				t.Fatal("Captured tier was dropped before native creation/send", request.Operation, request.ServiceTier)
			}
		}
	}
	if !seenCreate || !seenSend || !seenDelete {
		t.Fatal("Normal creation/send/owned deletion consumer boundary was not exercised")
	}
}

type serviceTierCancellationNative struct {
	*cancellationNative
	requests []protocol.AdapterRequest
}

func TestInvalidServiceTierIngressCannotReachCatalogOrNativeWork(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	n := &serviceTierNative{nativeFixture: base, catalog: fixtureTierCatalog}
	s.native = n
	registerFixture(t, call, "nAlice")
	for _, tier := range []string{"invalid\ncontrol", "invalid\u0085control", strings.Repeat("x", 129)} {
		input := tierJobInput(randomToken(), tier)
		if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 400 {
			t.Fatal("Invalid job tier accepted", w.Body.String())
		}
		if w := call("PUT", "/v2/codex/settings", map[string]string{"model": "fixture-model", "serviceTier": tier}, "nAlice"); w.Code != 400 {
			t.Fatal("Invalid settings tier accepted", w.Body.String())
		}
	}
	if n.catalogCalls != 0 || len(n.requests) != 0 {
		t.Fatal("Invalid tier reached native boundary")
	}
}

func TestAcceptedEmptyTierDoesNotAcquireLaterDefaultAfterReopen(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	n := &serviceTierNative{nativeFixture: base, catalog: fixtureTierCatalog}
	s.native = n
	registerFixture(t, call, "nAlice")
	input := tierJobInput(randomToken(), "")
	delete(input, "serviceTier")
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	if w := call("PUT", "/v2/codex/settings", map[string]string{"model": "fixture-model", "serviceTier": "fixture-tier-a"}, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	resumed, err := New(s.cfg, n, func(_ context.Context, remote string) (string, error) { return remote, nil }, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer resumed.Close()
	if err := resumed.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err := resumed.loadApp(resumed.getPeer("nAlice"))
	if err != nil || state.Jobs[input["requestId"].(string)].ServiceTier != "" || n.calls != 1 || n.input.ServiceTier != "" {
		t.Fatal("Existing empty tier was recaptured from later settings", err)
	}
}

func (n *serviceTierCancellationNative) Models(context.Context, string) (json.RawMessage, error) {
	return json.RawMessage(fixtureTierCatalog), nil
}
func (n *serviceTierCancellationNative) Call(ctx context.Context, request protocol.AdapterRequest) (protocol.AdapterReply, error) {
	n.requests = append(n.requests, request)
	return n.cancellationNative.Call(ctx, request)
}

func TestTieredUnknownCancellationKeepsOriginalFingerprintAndOwnedControlTuple(t *testing.T) {
	s, base, call := fixtureAPI(t)
	base.online = true
	n := &serviceTierCancellationNative{cancellationNative: &cancellationNative{lostAcknowledgment: &lostAcknowledgment{nativeFixture: base, broken: true}}}
	s.native = n
	registerFixture(t, call, "nAlice")
	input := tierJobInput(randomToken(), "fixture-tier-a")
	if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	if w := call("PUT", "/v2/codex/settings", map[string]string{"model": "fixture-model", "serviceTier": "fixture-tier-b"}, "nAlice"); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	id := input["requestId"].(string)
	if w := call("POST", "/v2/jobs/"+id+"/cancel", nil, "nAlice"); w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	n.broken = false
	resumed, err := New(s.cfg, n, func(_ context.Context, remote string) (string, error) { return remote, nil }, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer resumed.Close()
	if err := resumed.workPass(); err != nil {
		t.Fatal(err)
	}
	n.terminal = true
	if err := resumed.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err := resumed.loadApp(resumed.getPeer("nAlice"))
	if err != nil {
		t.Fatal(err)
	}
	j := state.Jobs[id]
	if j.State != "interrupted" || j.CancelRetired <= 0 || j.TurnID != "fixture-turn" || j.ServiceTier != "fixture-tier-a" || n.calls != 1 {
		t.Fatal("Tiered original turn did not recover without replay", j.State, j.Code, n.calls)
	}
	selected, retired := false, false
	for _, request := range n.requests {
		if request.Operation == "select" && request.AppID != "" {
			selected = true
		}
		if request.Operation == "retire" {
			retired = true
		}
		if (request.Operation == "select" && request.AppID != "") || request.Operation == "retire" || request.Operation == "create" || request.Operation == "send" {
			if request.ServiceTier != "fixture-tier-a" {
				t.Fatal("Owned recovery/control recaptured app default or dropped tier", request.Operation, request.ServiceTier)
			}
		}
	}
	if !selected || !retired {
		t.Fatal("Owned restore and retirement boundaries were not exercised")
	}
}

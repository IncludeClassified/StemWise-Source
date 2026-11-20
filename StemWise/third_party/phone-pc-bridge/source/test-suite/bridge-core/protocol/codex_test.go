package protocol

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

func TestEstablishedJobFingerprintRemainsStable(t *testing.T) {
	r := Request{Epoch: strings.Repeat("a", 32), Operation: "text", Sequence: 1, ThreadID: "native-thread", Text: "original owner", Instructions: "custom", AppID: strings.Repeat("b", 32), JobID: strings.Repeat("c", 32), InputFiles: []File{{ID: strings.Repeat("d", 32), Name: "empty.bin", Size: 0, SHA256: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}}}
	raw, err := json.Marshal(r)
	if err != nil {
		t.Fatal(err)
	}
	const canonical = `{"epoch":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","operation":"text","sequence":1,"threadId":"native-thread","text":"original owner","instructions":"custom","appId":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","jobId":"cccccccccccccccccccccccccccccccc","inputFiles":[{"id":"dddddddddddddddddddddddddddddddd","name":"empty.bin","size":0,"sha256":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}]}`
	if string(raw) != canonical || fmt.Sprintf("%x", sha256.Sum256(raw)) != "a513c432bc225d66ded8a0db659e24efeb340cb7c275c8b92c2c8feaeb73c8ca" {
		t.Fatal("Established native job fingerprint changed", string(raw))
	}
}

func TestServiceTierIsOpaqueBoundedAndPartOfOnlyExplicitJobFingerprints(t *testing.T) {
	base := Request{Epoch: strings.Repeat("a", 32), Operation: "text", Sequence: 1, ThreadID: "native-thread", Text: "original owner", AppID: strings.Repeat("b", 32), JobID: strings.Repeat("c", 32)}
	old, _ := json.Marshal(base)
	if strings.Contains(string(old), "serviceTier") {
		t.Fatal("Empty tier changed established canonical bytes")
	}
	for _, tier := range []string{"", "default", "fast", "opaque/Ω + 1", strings.Repeat("x", 128)} {
		request := base
		request.ServiceTier = tier
		if !ValidServiceTier(tier) || Validate(request) != nil {
			t.Fatal("Valid opaque tier rejected", tier)
		}
		raw, _ := json.Marshal(request)
		var decoded Request
		if DecodeStrict(raw, &decoded) != nil || decoded.ServiceTier != tier {
			t.Fatal("Tier changed during canonical round trip", tier)
		}
		if tier != "" && sha256.Sum256(raw) == sha256.Sum256(old) {
			t.Fatal("Explicit service tier omitted from immutable job identity")
		}
	}
	for _, tier := range []string{strings.Repeat("x", 129), string([]byte{0xff}), "fast\n", "fast\t", "fast\x00", "fast\x7f", "fast\u0085"} {
		request := base
		request.ServiceTier = tier
		if ValidServiceTier(tier) || Validate(request) == nil {
			t.Fatal("Malformed tier accepted", fmt.Sprintf("%q", tier))
		}
	}
	for _, raw := range []string{`{"operation":"models","serviceTier":"fast","serviceTier":"default"}`, `{"operation":"models","ServiceTier":"fast"}`, `{"operation":"models","serviceTier":42}`} {
		var decoded AdapterRequest
		if DecodeStrict([]byte(raw), &decoded) == nil {
			t.Fatal("Malformed adapter service tier field accepted", raw)
		}
	}
}

func TestInputFileDescriptorsAreStrictAndEnvelopeScoped(t *testing.T) {
	f := File{ID: strings.Repeat("c", 32), Name: "input.bin", Size: 4096, SHA256: strings.Repeat("d", 64)}
	r := Request{Operation: "text", Sequence: 1, ThreadID: "thread-1", Text: "Owner text", AppID: strings.Repeat("a", 32), JobID: strings.Repeat("b", 32), InputFiles: []File{f}}
	raw, _ := json.Marshal(r)
	var decoded Request
	err := DecodeStrict(raw, &decoded)
	if err != nil {
		t.Fatal(err)
	}
	if err := Validate(decoded); err != nil || decoded.InputFiles[0] != f {
		t.Fatal("Input descriptor lost", err)
	}
	descriptor, _ := json.Marshal(f)
	prefix := `{"operation":"text","sequence":1,"threadId":"thread-1","text":"Owner text","appId":"` + r.AppID + `","jobId":"` + r.JobID + `","inputFiles":`
	for _, input := range []string{`{}`, `[null]`, "[" + strings.Replace(string(descriptor), `"size":4096`, `"size":null`, 1) + "]", "[" + strings.Replace(string(descriptor), `"size":4096`, `"size":1.5`, 1) + "]", "[" + strings.TrimSuffix(string(descriptor), "}") + `,"id":"` + f.ID + `"}]`, "[" + strings.TrimSuffix(string(descriptor), "}") + `,"path":"anything"}]`, "[" + strings.TrimSuffix(string(descriptor), "}") + `,"size":4096}]`, "[" + strings.Repeat(string(descriptor)+",", 16) + string(descriptor) + "]"} {
		var invalid Request
		if err := DecodeStrict([]byte(prefix+input+"}"), &invalid); err == nil && Validate(invalid) == nil {
			t.Fatal("Malformed inputs admitted", input)
		}
	}
	for _, kind := range []string{"no-envelope", "operation", "repeat", "name", "size", "hash", "id"} {
		invalid := r
		invalid.InputFiles = append([]File(nil), r.InputFiles...)
		switch kind {
		case "no-envelope":
			invalid.AppID, invalid.JobID = "", ""
		case "operation":
			invalid.Operation = "unsupported"
		case "repeat":
			invalid.InputFiles = append(invalid.InputFiles, f)
		case "name":
			invalid.InputFiles[0].Name = "../input"
		case "size":
			invalid.InputFiles[0].Size = MaxFileBytes + 1
		case "hash":
			invalid.InputFiles[0].SHA256 = strings.Repeat("D", 64)
		case "id":
			invalid.InputFiles[0].ID = "other"
		}
		if err := Validate(invalid); err == nil {
			t.Fatal("Invalid scoped input admitted", kind)
		}
	}
	for _, size := range []int64{0, MaxFileBytes} {
		r.InputFiles[0].Size = size
		if err := Validate(r); err != nil {
			t.Fatal("Valid descriptor limit rejected", err)
		}
	}
}

func TestAppJobEnvelopeIsPairedAndOperationScoped(t *testing.T) {
	r := Request{Operation: "text", Sequence: 1, ThreadID: "thread-1", Text: "Original owner text", AppID: strings.Repeat("a", 32), JobID: strings.Repeat("b", 32)}
	if err := Validate(r); err != nil {
		t.Fatal(err)
	}
	raw, _ := json.Marshal(r)
	var decoded Request
	err := DecodeStrict(raw, &decoded)
	if err != nil || decoded.AppID != r.AppID || decoded.JobID != r.JobID || decoded.Text != r.Text {
		t.Fatal("Serialized envelope lost", err)
	}
	for _, kind := range []string{"app-only", "job-only", "bad-app", "bad-job", "read"} {
		invalid := r
		switch kind {
		case "app-only":
			invalid.JobID = ""
		case "job-only":
			invalid.AppID = ""
		case "bad-app":
			invalid.AppID = "not-an-app"
		case "bad-job":
			invalid.JobID = strings.Repeat("B", 32)
		case "read":
			invalid.Operation, invalid.Sequence, invalid.Text = "read", 0, ""
		}
		if err := Validate(invalid); err == nil {
			t.Fatal("Invalid envelope admitted", kind)
		}
	}
	duplicate := strings.TrimSuffix(string(raw), "}") + `,"jobId":"` + r.JobID + `"}`
	if err := DecodeStrict([]byte(duplicate), &decoded); err == nil {
		t.Fatal("Duplicate envelope field admitted")
	}
}

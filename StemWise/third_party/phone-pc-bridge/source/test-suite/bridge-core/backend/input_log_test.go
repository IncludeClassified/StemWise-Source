package backend

import (
	"bytes"
	"context"
	"errors"
	"io"
	"strings"
	"testing"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

type inputDeliveryFixture struct {
	*nativeFixture
	failure error
	puts    int
}

func (n *inputDeliveryFixture) Put(context.Context, protocol.File, io.Reader) error {
	n.puts++
	return n.failure
}

func TestInputFailuresLogExactStageAndNeverSendBeforeReceipt(t *testing.T) {
	for _, kind := range []string{"missing", "mismatch", "pc"} {
		t.Run(kind, func(t *testing.T) {
			s, base, call := fixtureAPI(t)
			registerFixture(t, call, "nAlice")
			data := []byte("original-input-" + randomToken())
			f, _ := files.Describe(randomToken(), "owned.bin", data)
			if _, err := s.inbox.Put("nAlice", f, bytes.NewReader(data)); err != nil {
				t.Fatal(err)
			}
			input := appInput{RequestID: randomToken(), CreatedAt: time.Now().UnixMilli(), Title: "Input diagnostic", Text: "original-user-" + randomToken(), Files: []protocol.File{f}}
			if w := call("POST", "/v2/jobs", input, "nAlice"); w.Code != 202 {
				t.Fatal(w.Body.String())
			}
			n := &inputDeliveryFixture{nativeFixture: base}
			n.online = true
			s.native = n
			cause := "pc_input_receipt_unconfirmed"
			secret := randomToken()
			if kind == "pc" {
				n.failure = errors.New("owned_pc_receipt_unknown password=" + secret)
			} else {
				if err := s.inbox.Remove("nAlice", f.ID); err != nil {
					t.Fatal(err)
				}
				cause = "pi_input_receipt_unconfirmed"
				if kind == "mismatch" {
					cause = "pi_input_descriptor_mismatch"
					other := []byte("different-" + randomToken())
					changed, _ := files.Describe(f.ID, f.Name, other)
					if _, err := s.inbox.Put("nAlice", changed, bytes.NewReader(other)); err != nil {
						t.Fatal(err)
					}
				}
			}
			for i := 0; i < 3; i++ {
				if err := s.workPass(); err != nil {
					t.Fatal(err)
				}
			}
			state, err := s.loadApp(s.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			j := state.Jobs[input.RequestID]
			if j.State != "queued" || j.Code != "input_delivery_unconfirmed" || j.Report != nil || j.TurnID != "" || len(j.Log) != 1 || n.calls != 0 {
				t.Fatal("Input failure dispatched a prompt, changed native status or lost/doubled diagnostics")
			}
			d := j.Log[0].Error
			if j.Log[0].Source != "bridge" || d.Cause != cause || d.Context["transfer_id"] != f.ID || !validDiagnostic(&d) || strings.Contains(d.Context["error_detail"], secret) {
				t.Fatal("Input diagnostic lacks exact stage/identity, has invalid schema or leaked credentials", d)
			}
			if kind != "pc" {
				if err := s.inbox.Remove("nAlice", f.ID); err != nil {
					t.Fatal(err)
				}
				if _, err := s.inbox.Put("nAlice", f, bytes.NewReader(data)); err != nil {
					t.Fatal(err)
				}
			}
			n.failure = nil
			if err := s.workPass(); err != nil {
				t.Fatal(err)
			}
			state, err = s.loadApp(s.getPeer("nAlice"))
			if err != nil {
				t.Fatal(err)
			}
			j = state.Jobs[input.RequestID]
			if j.State != "completed" || n.calls != 1 || n.input.Text != input.Text || len(j.Log) != 1 || n.input.Files[0] != f {
				t.Fatal("Receipt recovery changed input, duplicated native work or erased log")
			}
		})
	}
}

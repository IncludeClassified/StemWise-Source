package backend

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

func fixtureFileStream(r *Remote) StreamWire {
	return func(ctx context.Context, method, path string, source io.Reader) (*http.Response, error) {
		data, err := io.ReadAll(source)
		if err != nil {
			return nil, err
		}
		code, body, err := r.Wire(ctx, method, path, data)
		return &http.Response{StatusCode: code, Body: io.NopCloser(bytes.NewReader(body))}, err
	}
}

func TestRemoteFileRejectionKeepsFreshStatusAndMachineState(t *testing.T) {
	for _, tc := range []struct {
		status int
		state  string
	}{{409, "conflict"}, {404, "unavailable"}, {503, "unavailable"}, {400, "rejected"}} {
		r := &Remote{PCIdentity: "nPc", PiIdentity: "nPi"}
		r.Wire = func(_ context.Context, method, path string, data []byte) (int, []byte, error) {
			u, _ := url.Parse(path)
			return tc.status, mustJSON(protocol.FileReply{Protocol: protocol.Files, PCIdentity: r.PCIdentity, PeerIdentity: r.PiIdentity, Nonce: u.Query().Get("nonce"), State: tc.state}), nil
		}
		f, _ := files.Describe(randomToken(), "owned.bin", []byte("original"))
		r.Stream = fixtureFileStream(r)
		err := r.Put(context.Background(), f, strings.NewReader("original"))
		if err == nil || !strings.Contains(err.Error(), "state="+tc.state) || !strings.Contains(err.Error(), "http_status="+strconv.Itoa(tc.status)) || strings.Contains(err.Error(), "Uncorrelated") {
			t.Fatal("Fresh rejection lost its actual state/status", tc, err)
		}
	}
}

func TestRemoteFileErrorsNeverTrustUncorrelatedOrConversationalReplies(t *testing.T) {
	for _, kind := range []string{"pc", "peer", "nonce", "protocol", "prose", "unknown_field"} {
		r := &Remote{PCIdentity: "nPc", PiIdentity: "nPi"}
		r.Wire = func(_ context.Context, method, path string, data []byte) (int, []byte, error) {
			u, _ := url.Parse(path)
			reply := protocol.FileReply{Protocol: protocol.Files, PCIdentity: r.PCIdentity, PeerIdentity: r.PiIdentity, Nonce: u.Query().Get("nonce"), State: "conflict"}
			switch kind {
			case "pc":
				reply.PCIdentity = "nOther"
			case "peer":
				reply.PeerIdentity = "nOther"
			case "nonce":
				reply.Nonce = randomToken()
			case "protocol":
				reply.Protocol = "other"
			case "prose":
				reply.State = "hello password=do_not_export"
			}
			body := mustJSON(reply)
			if kind == "unknown_field" {
				body = []byte(strings.TrimSuffix(string(body), "}") + `,"details":"password=do_not_export"}`)
			}
			return 409, body, nil
		}
		f, _ := files.Describe(randomToken(), "owned.bin", []byte("original"))
		r.Stream = fixtureFileStream(r)
		err := r.Put(context.Background(), f, strings.NewReader("original"))
		if err == nil || err.Error() != "Uncorrelated PC file reply" {
			t.Fatal("Untrusted rejection body was exposed or accepted", kind, err)
		}
	}
}

func TestRemoteFileSuccessStillRequiresExactReceipt(t *testing.T) {
	f, _ := files.Describe(randomToken(), "owned.bin", []byte("original"))
	for _, changed := range []bool{false, true} {
		r := &Remote{PCIdentity: "nPc", PiIdentity: "nPi"}
		r.Wire = func(_ context.Context, method, path string, data []byte) (int, []byte, error) {
			u, _ := url.Parse(path)
			actual := f
			if changed {
				actual.SHA256 = strings.Repeat("0", 64)
			}
			return 200, mustJSON(protocol.FileReply{Protocol: protocol.Files, PCIdentity: r.PCIdentity, PeerIdentity: r.PiIdentity, Nonce: u.Query().Get("nonce"), State: "stored", File: &actual}), nil
		}
		r.Stream = fixtureFileStream(r)
		if err := r.Put(context.Background(), f, strings.NewReader("original")); (err != nil) != changed {
			t.Fatal("Fresh status bypassed exact receipt identity", changed, err)
		}
	}
}

func TestRemoteOutputRejectionPreservesCorrelatedFailure(t *testing.T) {
	f, _ := files.Describe(randomToken(), "owned.bin", []byte("original"))
	for _, kind := range []string{"missing", "unavailable", "length", "nonce", "peer", "protocol", "prose", "unknown_field", "file", "page", "success_state", "redirect"} {
		t.Run(kind, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				r := protocol.FileReply{Protocol: protocol.Files, PCIdentity: "nPc", PeerIdentity: "nPi", Nonce: req.URL.Query().Get("nonce"), State: "available", File: &f}
				if strings.HasSuffix(req.URL.Path, "/info") {
					w.Write(mustJSON(r))
					return
				}
				if kind == "length" {
					w.Header().Set("Content-Length", "1")
					w.Write([]byte("x"))
					return
				}
				status := 503
				r.State, r.File = "unavailable", nil
				switch kind {
				case "missing":
					status = 404
				case "nonce":
					r.Nonce = randomToken()
				case "peer":
					r.PeerIdentity = "nOther"
				case "protocol":
					r.Protocol = "other"
				case "prose":
					r.State = "password=never_export_this"
				case "file":
					r.File = &f
				case "page":
					r.Page = &protocol.FilePage{Files: []protocol.File{f}}
				case "success_state":
					r.State = "available"
				case "redirect":
					status = 307
				}
				body := mustJSON(r)
				if kind == "unknown_field" {
					body = append(body[:len(body)-1], []byte(`,"details":"password=never_export_this"}`)...)
				}
				w.WriteHeader(status)
				w.Write(body)
			}))
			defer server.Close()
			r := &Remote{PCIdentity: "nPc", PiIdentity: "nPi", Wire: HTTPWire(server.Client(), server.URL), Stream: HTTPStream(server.Client(), server.URL)}
			source, err := r.Output(context.Background(), f)
			if source != nil {
				source.Close()
				t.Fatal("Rejected output exposed a content stream")
			}
			if err == nil || strings.Contains(err.Error(), "never_export_this") {
				t.Fatal("Missing diagnostic or exposed untrusted reply", err)
			}
			expected := "Uncorrelated PC file reply"
			switch kind {
			case "missing":
				expected = "pc_file_rejected http_status=404 state=unavailable"
			case "unavailable":
				expected = "pc_file_rejected http_status=503 state=unavailable"
			case "length":
				expected = "pc_output_length_mismatch expected_bytes=8 actual_bytes=1"
			}
			if err.Error() != expected {
				t.Fatal("Output diagnostic lost the actual failure", kind, err)
			}
		})
	}
}

type failedFileReceipt struct {
	closed bool
	cause  error
	source io.Reader
	read   int
}

func (r *failedFileReceipt) Read(p []byte) (int, error) {
	if r.source == nil {
		return 0, r.cause
	}
	n, err := r.source.Read(p)
	r.read += n
	return n, err
}
func (r *failedFileReceipt) Close() error { r.closed = true; return nil }

func TestRemoteUploadReceiptReadFailureRetainsCauseAndClosesBody(t *testing.T) {
	f, _ := files.Describe(randomToken(), "owned.bin", []byte("original"))
	cause := errors.New("owned_connection_reset")
	body := &failedFileReceipt{cause: cause}
	r := &Remote{PCIdentity: "nPc", PiIdentity: "nPi", Stream: func(context.Context, string, string, io.Reader) (*http.Response, error) {
		return &http.Response{StatusCode: 200, Body: body}, nil
	}}
	err := r.Put(context.Background(), f, strings.NewReader("original"))
	if !errors.Is(err, cause) || !strings.HasPrefix(err.Error(), "pc_file_receipt_unavailable:") || !body.closed {
		t.Fatal("Interrupted receipt became a size error, lost its cause or leaked its body", err)
	}
}

func TestRemoteStreamedErrorRepliesAreBoundedAndClosed(t *testing.T) {
	f, _ := files.Describe(randomToken(), "owned.bin", []byte("original"))
	for _, download := range []bool{false, true} {
		body := &failedFileReceipt{source: strings.NewReader(strings.Repeat("x", (128<<10)+2))}
		r := &Remote{PCIdentity: "nPc", PiIdentity: "nPi"}
		r.Wire = func(_ context.Context, _, path string, _ []byte) (int, []byte, error) {
			u, _ := url.Parse(path)
			return 200, mustJSON(protocol.FileReply{Protocol: protocol.Files, PCIdentity: "nPc", PeerIdentity: "nPi", Nonce: u.Query().Get("nonce"), State: "available", File: &f}), nil
		}
		calls := 0
		r.Stream = func(context.Context, string, string, io.Reader) (*http.Response, error) {
			calls++
			return &http.Response{StatusCode: 503, Body: body}, nil
		}
		var err error
		if download {
			var source io.ReadCloser
			source, err = r.Output(context.Background(), f)
			if source != nil {
				source.Close()
				t.Fatal("Oversized error became an output stream")
			}
		} else {
			err = r.Put(context.Background(), f, strings.NewReader("original"))
		}
		if err == nil || err.Error() != "pc_file_receipt_too_large" || !body.closed || body.read != (128<<10)+1 || calls != 1 {
			t.Fatal("Oversized reply was unbounded, mislabeled, leaked or replayed", download, err, body.read, calls)
		}
	}
}

type remoteOutputNative struct {
	*nativeFixture
	remote *Remote
}

func (n *remoteOutputNative) Output(ctx context.Context, file protocol.File) (io.ReadCloser, error) {
	return n.remote.Output(ctx, file)
}

func TestHTTPOutputFailureIsLoggedWithoutChangingCompletionAndRecovers(t *testing.T) {
	s, base, call := fixtureAPI(t)
	registerFixture(t, call, "nAlice")
	p := s.getPeer("nAlice")
	state, err := s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	j := ownedOutputJob(randomToken())
	state.Jobs[j.ID] = j
	if err := s.saveApp(state); err != nil {
		t.Fatal(err)
	}
	data := []byte("original-output-" + randomToken())
	f, _ := files.Describe(randomToken(), "owned.bin", data)
	var available atomic.Bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		r := protocol.FileReply{Protocol: protocol.Files, PCIdentity: "nPc", PeerIdentity: "nPi", Nonce: req.URL.Query().Get("nonce"), State: "available", File: &f}
		if strings.HasSuffix(req.URL.Path, "/info") {
			w.Write(mustJSON(r))
			return
		}
		if !available.Load() {
			r.State, r.File = "unavailable", nil
			w.WriteHeader(503)
			w.Write(mustJSON(r))
			return
		}
		w.Write(data)
	}))
	defer server.Close()
	base.online = true
	base.report = string(mustJSON(appReport{Protocol: "v2", AppID: state.ID, JobID: j.ID, Revision: 1, Status: "completed", Code: "ready", Files: []protocol.File{f}}))
	s.native = &remoteOutputNative{nativeFixture: base, remote: &Remote{PCIdentity: "nPc", PiIdentity: "nPi", Wire: HTTPWire(server.Client(), server.URL), Stream: HTTPStream(server.Client(), server.URL)}}
	for i := 0; i < 2; i++ {
		if err := s.workPass(); err != nil {
			t.Fatal(err)
		}
	}
	state, err = s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	stored := state.Jobs[j.ID]
	if stored.State != "completed" || stored.Code != "output_bytes_unverified" || stored.Report == nil || stored.Report.Status != "completed" || len(stored.Outputs) != 0 || len(stored.Log) != 1 || base.calls != 0 {
		t.Fatal("HTTP output failure changed completion, published bytes or replayed native work")
	}
	detail := "pc_file_rejected http_status=503 state=unavailable"
	if stored.Log[0].Error.Context["error_detail"] != detail || stored.Log[0].Error.Context["transfer_id"] != f.ID || !validDiagnostic(&stored.Log[0].Error) {
		t.Fatal("Durable output log lost the verified HTTP failure")
	}
	if w := call("GET", "/v2/jobs/"+j.ID+"/log", nil, "nAlice"); w.Code != 200 || !strings.Contains(w.Body.String(), detail) {
		t.Fatal("Independent log endpoint lost the failure")
	}
	if w := call("GET", "/v2/jobs/"+j.ID, nil, "nAlice"); w.Code != 200 || strings.Contains(w.Body.String(), detail) {
		t.Fatal("Output diagnostic leaked into status")
	}
	if w := call("GET", "/v2/jobs/"+j.ID+"/outputs/"+f.ID+"/content", nil, "nAlice"); w.Code != 404 {
		t.Fatal("Failed HTTP download was published", w.Code)
	}
	available.Store(true)
	if err := s.workPass(); err != nil {
		t.Fatal(err)
	}
	state, err = s.loadApp(p)
	if err != nil {
		t.Fatal(err)
	}
	stored = state.Jobs[j.ID]
	if stored.State != "completed" || stored.Code != "native_turn_completed" || len(stored.Outputs) != 1 || stored.Outputs[0] != f || len(stored.Log) != 1 || base.calls != 0 || stored.Input.Text != j.Input.Text {
		t.Fatal("Recovery changed the original job, discarded diagnostics or replayed native work")
	}
	if actual, content, err := s.outbox.Read(p.identity, f.ID); err != nil || actual != f || !bytes.Equal(content, data) {
		t.Fatal("Recovered output lacks the original independently verified bytes", err)
	}
}

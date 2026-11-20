package backend

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"phonepcbridge/pc"
	"phonepcbridge/protocol"
	"strconv"
	"strings"
	"unicode/utf8"
)

// Wire is a host-supplied HTTP transport pinned to the actual PC's enrolled identity.
type Wire func(context.Context, string, string, []byte) (int, []byte, error)
type StreamWire func(context.Context, string, string, io.Reader) (*http.Response, error)
type Remote struct {
	PCIdentity, PiIdentity string
	Wire                   Wire
	Stream                 StreamWire
}

func (r *Remote) Power(ctx context.Context, input pc.PowerRequest) (pc.PowerReply, error) {
	input.Nonce = randomToken()
	body, err := json.Marshal(input)
	if err != nil {
		return pc.PowerReply{}, err
	}
	status, raw, err := r.Wire(ctx, "POST", "/internal/v1/power", body)
	if err != nil {
		return pc.PowerReply{}, err
	}
	var result pc.PowerReply
	if appDecode(raw, &result) != nil || result.Protocol != pc.PowerProtocol ||
		result.PCIdentity != r.PCIdentity || result.Nonce != input.Nonce ||
		!contains([]string{"ok", "rejected", "unknown"}, result.State) ||
		!appCode.MatchString(result.Code) || result.Observation != nil && !result.Observation.Valid() {
		return pc.PowerReply{}, errors.New("pc_power_reply_unconfirmed")
	}
	if status != http.StatusOK {
		return pc.PowerReply{}, errors.New("pc_power_http_unconfirmed")
	}
	if result.State != "ok" {
		if result.ShutdownState == "accepted" || result.CycleID != "" && result.CycleID != input.CycleID ||
			result.ShutdownState != "" && !contains([]string{"unconfirmed", "failed"}, result.ShutdownState) {
			return pc.PowerReply{}, errors.New("pc_power_reply_unconfirmed")
		}
		return result, nil
	}
	if input.Operation == "status" {
		if result.Code != "power_status_ready" || result.Observation == nil || result.CycleID != "" || result.ShutdownState != "" {
			return pc.PowerReply{}, errors.New("pc_power_reply_unconfirmed")
		}
	} else if input.Operation != "shutdown" || result.CycleID != input.CycleID || result.CycleID == "" ||
		result.Code != pc.PowerShutdownAccepted || result.ShutdownState != "accepted" ||
		result.Observation != nil && result.Observation.TransitionID != input.TransitionID {
		return pc.PowerReply{}, errors.New("pc_power_reply_unconfirmed")
	}
	return result, nil
}

func (r *Remote) Call(ctx context.Context, input protocol.AdapterRequest) (protocol.AdapterReply, error) {
	input.Nonce = randomToken()
	body, _ := json.Marshal(input)
	code, data, err := r.Wire(ctx, "POST", "/internal/v1/codex", body)
	var response protocol.AdapterReply
	if err != nil {
		return response, err
	}
	if appDecode(data, &response) != nil || response.Protocol != protocol.Adapter || response.PCIdentity != r.PCIdentity || response.Nonce != input.Nonce || !contains([]string{"ok", "rejected", "unknown"}, response.State) || (response.Code != "" && !appCode.MatchString(response.Code)) || (response.Failure != nil && (response.State == "ok" || !validAdapterFailure(response.Failure))) {
		return protocol.AdapterReply{}, errors.New("Uncorrelated PC adapter response")
	}
	if code != 200 {
		if response.State == "ok" || !appCode.MatchString(response.Code) || len(response.Result) != 0 {
			return protocol.AdapterReply{}, errors.New("Uncorrelated PC adapter response")
		}
		return protocol.AdapterReply{}, fmt.Errorf("pc_adapter_rejected http_status=%d state=%s code=%s", code, response.State, response.Code)
	}
	return response, nil
}

func validAdapterFailure(failure *protocol.AdapterFailure) bool {
	if !utf8.ValidString(failure.Message) || strings.TrimSpace(failure.Message) == "" || len(failure.Message) > 2048 {
		return false
	}
	p := failure.Process
	if p == nil {
		return true
	}
	if !utf8.ValidString(p.Stderr) || len(p.Stderr) > 1024 {
		return false
	}
	switch p.WaitState {
	case "not_started", "unconfirmed":
		return p.ExitCode == nil
	case "exited":
		return p.ExitCode != nil && *p.ExitCode >= 0
	case "signaled":
		return p.ExitCode != nil && *p.ExitCode == -1
	default:
		return false
	}
}
func (r *Remote) raw(ctx context.Context, input protocol.AdapterRequest) (json.RawMessage, error) {
	response, err := r.Call(ctx, input)
	if err != nil {
		return nil, err
	}
	if response.State != "ok" {
		return nil, errors.New(response.Code)
	}
	return response.Result, nil
}
func (r *Remote) Models(ctx context.Context, cursor string) (json.RawMessage, error) {
	return r.raw(ctx, protocol.AdapterRequest{Operation: "models", Cursor: cursor})
}
func (r *Remote) History(ctx context.Context, id, cursor string, limit int) (json.RawMessage, error) {
	return r.raw(ctx, protocol.AdapterRequest{Operation: "history", ThreadID: id, Cursor: cursor, Limit: limit})
}
func (r *Remote) NameThread(ctx context.Context, id, name string) error {
	_, err := r.raw(ctx, protocol.AdapterRequest{Operation: "name", ThreadID: id, Name: name})
	return err
}
func (r *Remote) file(ctx context.Context, method, path string, q url.Values, body []byte) (protocol.FileReply, error) {
	nonce := randomToken()
	q.Set("nonce", nonce)
	code, data, err := r.Wire(ctx, method, path+"?"+q.Encode(), body)
	if err != nil {
		return protocol.FileReply{}, err
	}
	return r.fileReply(code, data, nonce)
}
func (r *Remote) fileReply(code int, data []byte, nonce string) (protocol.FileReply, error) {
	var reply protocol.FileReply
	if appDecode(data, &reply) != nil || reply.Protocol != protocol.Files || reply.PCIdentity != r.PCIdentity || reply.PeerIdentity != r.PiIdentity || reply.Nonce != nonce || !appCode.MatchString(reply.State) {
		return protocol.FileReply{}, errors.New("Uncorrelated PC file reply")
	}
	if code != 200 {
		if code < 400 || code > 599 || reply.File != nil || reply.Page != nil || !contains([]string{"unavailable", "rejected", "conflict", "content_mismatch", protocol.StorageCapacityUnavailable, protocol.DownloadCapacityUnavailable}, reply.State) {
			return protocol.FileReply{}, errors.New("Uncorrelated PC file reply")
		}
		return reply, fmt.Errorf("pc_file_rejected http_status=%d state=%s", code, reply.State)
	}
	return reply, nil
}
func (r *Remote) streamFileReply(response *http.Response, nonce string) (protocol.FileReply, error) {
	body, err := io.ReadAll(io.LimitReader(response.Body, (128<<10)+1))
	if err != nil {
		return protocol.FileReply{}, fmt.Errorf("pc_file_receipt_unavailable: %w", err)
	}
	if len(body) > 128<<10 {
		return protocol.FileReply{}, errors.New("pc_file_receipt_too_large")
	}
	return r.fileReply(response.StatusCode, body, nonce)
}
func (r *Remote) Reachable(ctx context.Context) error {
	reply, err := r.file(ctx, "GET", "/v1/files/health", url.Values{}, nil)
	if err != nil {
		return err
	}
	if reply.State != "ready" {
		return errors.New("PC unavailable")
	}
	return nil
}
func (r *Remote) Put(ctx context.Context, file protocol.File, data io.Reader) error {
	if !protocol.ValidFile(file) || data == nil {
		return errors.New("Invalid input descriptor")
	}
	q := url.Values{"id": {file.ID}, "name": {file.Name}, "size": {strconv.FormatInt(file.Size, 10)}, "sha256": {file.SHA256}}
	if r.Stream == nil {
		return errors.New("pc_file_stream_unavailable")
	}
	nonce := randomToken()
	q.Set("nonce", nonce)
	response, err := r.Stream(ctx, "POST", "/v1/files/put?"+q.Encode(), data)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	reply, err := r.streamFileReply(response, nonce)
	if err != nil {
		return err
	}
	if reply.State != "stored" || reply.File == nil || *reply.File != file {
		return errors.New("PC input receipt differs")
	}
	return nil
}
func (r *Remote) Output(ctx context.Context, file protocol.File) (io.ReadCloser, error) {
	if !protocol.ValidFile(file) {
		return nil, errors.New("Invalid output descriptor")
	}
	reply, err := r.file(ctx, "GET", "/v1/outbox/"+file.ID+"/info", url.Values{}, nil)
	if err != nil {
		return nil, err
	}
	if reply.State != "available" || reply.File == nil || *reply.File != file {
		return nil, errors.New("Output descriptor differs before download")
	}
	if r.Stream == nil {
		return nil, errors.New("pc_file_stream_unavailable")
	}
	nonce := randomToken()
	response, err := r.Stream(ctx, "GET", "/v1/outbox/"+file.ID+"/content?nonce="+nonce, nil)
	if err != nil {
		return nil, err
	}
	if response.StatusCode != 200 {
		defer response.Body.Close()
		_, err := r.streamFileReply(response, nonce)
		return nil, err
	}
	if response.ContentLength >= 0 && response.ContentLength != file.Size {
		response.Body.Close()
		return nil, fmt.Errorf("pc_output_length_mismatch expected_bytes=%d actual_bytes=%d", file.Size, response.ContentLength)
	}
	return response.Body, nil
}

func HTTPStream(client *http.Client, base string) StreamWire {
	return func(ctx context.Context, method, path string, body io.Reader) (*http.Response, error) {
		request, err := http.NewRequestWithContext(ctx, method, base+path, body)
		if err != nil {
			return nil, err
		}
		request.Header.Set("Content-Type", "application/octet-stream")
		return client.Do(request)
	}
}

// HTTPWire is also used by narrow protocol tests. Production supplies a pinned private transport.
func HTTPWire(client *http.Client, base string) Wire {
	return func(ctx context.Context, method, path string, body []byte) (int, []byte, error) {
		request, err := http.NewRequestWithContext(ctx, method, base+path, bytes.NewReader(body))
		if err != nil {
			return 0, nil, err
		}
		if path == "/internal/v1/codex" || path == "/internal/v1/power" {
			request.Header.Set("Content-Type", "application/json")
		}
		response, err := client.Do(request)
		if err != nil {
			return 0, nil, err
		}
		defer response.Body.Close()
		data, err := io.ReadAll(io.LimitReader(response.Body, protocol.MaxBufferedFileBytes+1))
		if err != nil || len(data) > protocol.MaxBufferedFileBytes {
			return 0, nil, errors.New("PC response exceeds limit")
		}
		return response.StatusCode, data, nil
	}
}

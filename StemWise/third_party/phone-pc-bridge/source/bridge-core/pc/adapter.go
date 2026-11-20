package pc

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"phonepcbridge/codex"
	"phonepcbridge/files"
	"phonepcbridge/protocol"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func (s *Service) adapterError(w http.ResponseWriter, status int, code, nonce string) {
	reply(w, status, protocol.AdapterReply{Protocol: protocol.Adapter, PCIdentity: s.cfg.Identity, Nonce: nonce, State: "rejected", Code: code})
}
func (s *Service) adapterRequest(w http.ResponseWriter, r *http.Request, p *peer) {
	if r.Method != "POST" || r.URL.RawQuery != "" || r.Header.Get("Content-Type") != "application/json" {
		s.adapterError(w, 400, "invalid_adapter_request", "")
		return
	}
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, protocol.MaxRequestBytes))
	var input protocol.AdapterRequest
	if err != nil || protocol.DecodeStrict(data, &input) != nil || !token.MatchString(input.Nonce) {
		s.adapterError(w, 400, "invalid_adapter_request", "")
		return
	}
	parent := s.ctx
	observation := input.Operation == "models" || input.Operation == "auth" || input.Operation == "history" || input.Operation == "creation"
	if observation {
		parent = r.Context()
	}
	ctx, cancel := context.WithTimeout(parent, 30*time.Second)
	if observation {
		stop := context.AfterFunc(s.ctx, cancel)
		defer stop()
		if s.ctx.Err() != nil {
			cancel()
		}
	}
	if input.Operation == "cleanup" {
		cancel()
		status, _ := json.Marshal(s.cleanupSnapshot())
		reply(w, 200, protocol.AdapterReply{Protocol: protocol.Adapter, PCIdentity: s.cfg.Identity, Nonce: input.Nonce, State: "ok", Result: status})
		return
	}
	defer cancel()
	powerRelease, powerErr := s.beginPowerWork()
	if powerErr != nil {
		s.adapterError(w, 200, powerErr.Error(), input.Nonce)
		return
	}
	defer powerRelease()
	if input.Operation != "discard" {
		release, leaseErr := s.claimNative(ctx)
		if leaseErr != nil {
			if input.Operation == "auth" {
				status, _ := json.Marshal(authStatus("unavailable", "native_auth_unavailable"))
				reply(w, 200, protocol.AdapterReply{Protocol: protocol.Adapter, PCIdentity: s.cfg.Identity, Nonce: input.Nonce, State: "ok", Result: status})
				return
			}
			if input.Operation == "create" || input.Operation == "send" {
				reply(w, 200, protocol.AdapterReply{Protocol: protocol.Adapter, PCIdentity: s.cfg.Identity, Nonce: input.Nonce, State: "rejected", Code: "native_auth_unavailable"})
				return
			}
			reply(w, 200, protocol.AdapterReply{Protocol: protocol.Adapter, PCIdentity: s.cfg.Identity, Nonce: input.Nonce, State: "unknown", Code: "codex_unavailable"})
			return
		}
		defer release()
	}
	var c *codex.Connection
	if input.Operation == "delete" {
		err = s.authorizeCleanup(ctx, p.identity, input, true)
	}
	if err == nil && input.Operation != "discard" {
		c, _, err = s.connection(ctx, p)
		if err != nil {
			if input.Operation == "auth" {
				status, _ := json.Marshal(authStatus("unavailable", "native_auth_unavailable"))
				reply(w, 200, protocol.AdapterReply{Protocol: protocol.Adapter, PCIdentity: s.cfg.Identity, Nonce: input.Nonce, State: "ok", Result: status})
				return
			}
			// Initialization produced no native operation acknowledgment; keep read-only retries safe.
			reply(w, 200, protocol.AdapterReply{Protocol: protocol.Adapter, PCIdentity: s.cfg.Identity, Nonce: input.Nonce, State: "unknown", Code: "codex_unavailable", Failure: s.captureAdapterFailure(p.identity, input, err)})
			return
		}
	}
	if err == nil && (input.Operation == "auth" || input.Operation == "create" || input.Operation == "send") {
		var status AuthStatus
		c, status = s.preflightAuth(ctx, p, c)
		if input.Operation == "auth" {
			result, _ := json.Marshal(status)
			reply(w, 200, protocol.AdapterReply{Protocol: protocol.Adapter, PCIdentity: s.cfg.Identity, Nonce: input.Nonce, State: "ok", Result: result})
			return
		}
		if status.State == "ready" && ctx.Err() != nil {
			status = authStatus("unavailable", "native_auth_unavailable")
		}
		if status.State != "ready" {
			reply(w, 200, protocol.AdapterReply{Protocol: protocol.Adapter, PCIdentity: s.cfg.Identity, Nonce: input.Nonce, State: "rejected", Code: status.Code})
			return
		}
	}
	var result json.RawMessage
	var privateNativeContext []string
	if err == nil {
		switch input.Operation {
		case "models":
			result, err = c.Models(ctx, input.Cursor)
		case "select":
			if input.AppID != "" || input.RequestID != "" {
				result, err = s.selectCreation(ctx, p.identity, input, c)
			} else {
				result, err = c.Select(ctx, input.ThreadID)
			}
		case "history":
			result, err = c.History(ctx, input.ThreadID, input.Cursor, input.Limit)
			if err == nil {
				err = s.verifyJobHistoryFiles(p.identity, input.ThreadID, &result)
			}
			if err == nil {
				s.cleanHistoryHandoffs(input.ThreadID, result)
			}
		case "create", "creation":
			if input.Operation == "create" {
				result, err = s.createThread(ctx, p.identity, input, guardedCreation{c, s, p})
			} else {
				result, err = s.createThread(ctx, p.identity, input, c)
			}
		case "discard":
			result, err = s.discardCreation(ctx, p.identity, input)
		case "delete":
			err = s.deleteCreation(ctx, p.identity, input, c)
			result = json.RawMessage(`{"deleted":true}`)
		case "name":
			err = c.NameThread(ctx, input.ThreadID, input.Name)
			result = json.RawMessage(`{}`)
		case "interrupt":
			result, err = c.Interrupt(ctx, input.ThreadID, input.TurnID)
		case "retire":
			result, err = s.retireCreation(ctx, p.identity, input, c)
		case "send":
			request := Request{Epoch: s.epoch, Operation: "text", Sequence: 1, ThreadID: input.ThreadID, Text: input.Text, Instructions: input.Instructions, AppID: input.AppID, JobID: input.JobID, InputFiles: input.Files, ServiceTier: input.ServiceTier}
			if err = protocol.Validate(request); err == nil {
				release, claimErr := s.beginJobHandoff(ctx, p.identity, request)
				err = claimErr
				if err == nil {
					func() {
						defer release()
						var handoff handoffFile
						handoff, err = s.createHandoff(ctx, p.identity, request)
						if err == nil {
							privateNativeContext = []string{handoff.path}
							err = s.checkAuthSource(ctx, p, c)
							if err == nil {
								result, err = c.SendJob(ctx, request.ThreadID, request.Text, request.Instructions, handoff.path, handoff.markdown, input.Model, input.Effort, input.ServiceTier)
							}
							if err == nil {
								s.trackHandoff(c, request, result, handoff)
							} else {
								var unknown *codex.OutcomeUnknown
								if !errors.As(err, &unknown) {
									s.removeHandoff(handoff)
								}
							}
						}
					}()
				}

			} else {
				err = errors.New("Exact app job required")
			}
		default:
			s.adapterError(w, 400, "unsupported_adapter_operation", input.Nonce)
			return
		}
	}
	response := protocol.AdapterReply{Protocol: protocol.Adapter, PCIdentity: s.cfg.Identity, Nonce: input.Nonce, State: "ok", Result: result}
	if err != nil {
		var auth *nativeAuthGateError
		var released *creationError
		if errors.As(err, &auth) && (!errors.As(err, &released) || released.code == "creation_not_sent") {
			reply(w, 200, protocol.AdapterReply{Protocol: protocol.Adapter, PCIdentity: s.cfg.Identity, Nonce: input.Nonce, State: "rejected", Code: auth.status.Code})
			return
		}
		response.Result = nil
		response.State = "rejected"
		response.Code = "native_operation_rejected"
		response.Failure = s.captureAdapterFailure(p.identity, input, err, privateNativeContext...)
		var unknown *codex.OutcomeUnknown
		if errors.As(err, &unknown) {
			response.State = "unknown"
			response.Code = "native_outcome_unconfirmed"
		}
		var creation *creationError
		if errors.As(err, &creation) {
			response.Code = creation.code
			if creation.unknown {
				response.State = "unknown"
			}
		}
	}
	reply(w, 200, response)
}

func adapterFailure(err error, input protocol.AdapterRequest, privateNativeContext ...string) *protocol.AdapterFailure {
	message := err.Error()
	var creation *creationError
	if errors.As(err, &creation) && creation.code == "creation_store_unavailable" {
		message = creationStorageDiagnostic(creation)
	}
	if errors.As(err, &creation) && (creation.code == "turn_retirement_unconfirmed" || creation.code == "chat_writer_unconfirmed") && creation.cause != nil {
		cause := creation.cause.Error()
		var rpc *codex.RPCError
		if errors.As(creation.cause, &rpc) {
			cause = fmt.Sprintf("Codex rejected request (%d)", rpc.Code)
		}
		message += ": " + cause
	}
	message = redactAdapterMessage(message, input, privateNativeContext...)
	failure := &protocol.AdapterFailure{Message: protocol.DiagnosticText(message, 1024)}
	var rpc *codex.RPCError
	if errors.As(err, &rpc) {
		failure.RPCCode = &rpc.Code
	}
	var process *codex.ProcessError
	if errors.As(err, &process) {
		diagnostic := process.Diagnostic()
		failure.Process = &protocol.AdapterProcessFailure{WaitState: diagnostic.WaitState, ExitCode: diagnostic.ExitCode, Stderr: diagnostic.Stderr, StderrTruncated: diagnostic.StderrTruncated, TerminationRequested: diagnostic.TerminationRequested}
	}
	return failure
}

// Native errors may echo the complete text envelope. Remove it before bounding
// diagnostics; otherwise its escaped task data and local paths can escape.
func redactAdapterMessage(message string, input protocol.AdapterRequest, privateNativeContext ...string) string {
	privateFields := append([]string{input.Text, input.Instructions}, privateNativeContext...)
	var safe strings.Builder
	for attempts := 0; message != ""; attempts++ {
		index := strings.IndexAny(message, "{\"")
		if index < 0 {
			safe.WriteString(message)
			break
		}
		safe.WriteString(message[:index])
		message = message[index:]
		if attempts >= 64 {
			safe.WriteString("[redacted native payload]")
			break
		}
		decoder := json.NewDecoder(strings.NewReader(message))
		var raw json.RawMessage
		if decoder.Decode(&raw) == nil {
			candidate := string(raw)
			if len(raw) > 0 && raw[0] == '"' {
				_ = json.Unmarshal(raw, &candidate)
			}
			if job, err := codex.ParseJobMessage(candidate); err == nil {
				privateFields = append(privateFields, job.Prompt, job.Instructions, job.Path, job.Header)
				safe.WriteString("[redacted input]")
				message = message[decoder.InputOffset():]
				continue
			}
		}
		// A truncated known envelope still cannot become permission to export its tail.
		if strings.HasPrefix(message, "{\"bridgeJobInput\":") || strings.HasPrefix(message, "\"{\\\"bridgeJobInput\\\":") {
			safe.WriteString("[redacted input]")
			break
		}
		safe.WriteByte(message[0])
		message = message[1:]
	}
	fragments := map[string]bool{}
	for _, value := range privateFields {
		for _, fragment := range append(strings.Split(value, "\n"), value) {
			fragment = strings.TrimSuffix(fragment, "\r")
			if fragment == "" {
				continue
			}
			quoted, ascii := strconv.Quote(fragment), strconv.QuoteToASCII(fragment)
			encoded, _ := json.Marshal(fragment)
			for _, form := range []string{fragment, quoted[1 : len(quoted)-1], ascii[1 : len(ascii)-1], string(encoded[1 : len(encoded)-1])} {
				fragments[form] = true
			}
		}
	}
	ordered := make([]string, 0, len(fragments))
	for fragment := range fragments {
		ordered = append(ordered, fragment)
	}
	sort.Slice(ordered, func(i, j int) bool { return len(ordered[i]) > len(ordered[j]) })
	pairs := make([]string, 0, 2*len(ordered))
	for _, fragment := range ordered {
		pairs = append(pairs, fragment, "[redacted input]")
	}
	return strings.NewReplacer(pairs...).Replace(safe.String())
}

// Storage diagnostics project typed facts only; paths and arbitrary cause text stay local.
func creationStorageDiagnostic(failure *creationError) string {
	phase := failure.storagePhase
	switch phase {
	case "unconfigured", "read_deleted_marker", "read_request", "read_accepted_at", "put_request", "put_result", "release_request", "put_deleted_marker", "read_result", "read_cleanup_marker", "put_cleanup_marker", "list_records":
	default:
		phase = "unconfirmed"
	}
	class := "unclassified"
	switch {
	case phase == "unconfigured":
		class = "unconfigured"
	case errors.Is(failure.cause, files.ErrStorageCapacity):
		class = "storage_capacity_unavailable"
	case errors.Is(failure.cause, files.ErrCorrupt):
		class = "corrupt"
	case errors.Is(failure.cause, files.ErrConflict):
		class = "conflict"
	case errors.Is(failure.cause, os.ErrPermission):
		class = "permission_denied"
	case errors.Is(failure.cause, os.ErrNotExist):
		class = "not_found"
	case errors.Is(failure.cause, os.ErrExist):
		class = "already_exists"
	case errors.Is(failure.cause, os.ErrClosed):
		class = "closed"
	case errors.Is(failure.cause, os.ErrInvalid):
		class = "invalid"
	}
	value := failure.code + "; storage_phase=" + phase + " storage_class=" + class
	op := ""
	var path *os.PathError
	var link *os.LinkError
	if errors.As(failure.cause, &path) {
		op = path.Op
	} else if errors.As(failure.cause, &link) {
		op = link.Op
	}
	if op != "" {
		switch op {
		case "open", "openat", "read", "write", "sync", "close", "stat", "lstat", "mkdir", "mkdirat", "readdir", "seek", "rename", "renameat", "remove", "unlink", "unlinkat":
		default:
			op = "unclassified"
		}
		value += " storage_op=" + op
	}
	var errno syscall.Errno
	if errors.As(failure.cause, &errno) {
		value += fmt.Sprintf(" storage_errno=%d", uint64(errno))
	}
	return value
}

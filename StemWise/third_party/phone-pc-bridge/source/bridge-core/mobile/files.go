package mobile

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"path/filepath"
	"strconv"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

// FileClient stores managed source/recipient bytes in this host app's private
// directory. The host imports/exports through its own normally granted folders.
type FileClient struct {
	client         *Client
	host, identity string
	inbox, outbox  *files.Store
	exchange       func(context.Context, string, string, string, []byte) (string, error)
	stream         func(context.Context, string, string, string, io.Reader) (int, io.ReadCloser, string, error)
}

func (c *Client) OpenFiles(requestID, host, identity string) (*FileClient, error) {
	ctx, finish, err := c.begin(context.Background(), protocol.FileTransferTimeout, requestID)
	if err != nil {
		return nil, err
	}
	defer finish()
	f, err := c.LocalFiles(host, identity)
	if err != nil {
		return nil, err
	}
	if _, err := f.request(ctx, requestID, "GET", "/v1/files/health", "ready", url.Values{}, nil); err != nil {
		return nil, err
	}
	return f, nil
}

// LocalFiles opens this app's managed snapshots without contacting the PC.
// Every remote operation still requires the native pairing and fresh wire checks.
func (c *Client) LocalFiles(host, identity string) (*FileClient, error) {
	return c.localFiles(host, identity, protocol.Port)
}
func (c *Client) OpenAppFiles(requestID, host, identity string) (*FileClient, error) {
	ctx, finish, err := c.begin(context.Background(), protocol.FileTransferTimeout, requestID)
	if err != nil {
		return nil, err
	}
	defer finish()
	f, err := c.localFiles(host, identity, protocol.PiPort)
	if err != nil {
		return nil, err
	}
	if _, err = f.request(ctx, requestID, "GET", "/v1/files/health", "ready", url.Values{}, nil); err != nil {
		return nil, err
	}
	return f, nil
}
func (c *Client) localFiles(host, identity string, port int) (*FileClient, error) {
	if !peerHost.MatchString(host) || !sessionIdentity.MatchString(identity) {
		return nil, errors.New("Exact paired PC required")
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed || c.node == nil {
		return nil, errors.New("Bridge transport unavailable")
	}
	if c.fileIncoming == nil {
		var err error
		c.fileStorageLease, err = claimFileStorage(c.node.Dir)
		if err == nil {
			c.fileIncoming, err = files.OpenWithRetention(filepath.Join(c.node.Dir, "bridge-incoming"), protocol.Retention)
		}
		if err == nil {
			c.fileOutgoing, err = files.OpenWithRetention(filepath.Join(c.node.Dir, "bridge-outgoing"), protocol.Retention)
		}
		delay := time.Duration(0)
		if err == nil {
			// Scan failures are recorded and retried; exact accesses enforce expiry.
			delay, _ = c.pruneFileStores(c.fileIncoming, c.fileOutgoing, time.Now())
		}
		if err != nil {
			if c.fileIncoming != nil {
				c.fileIncoming.Close()
				c.fileIncoming = nil
			}
			if c.fileOutgoing != nil {
				c.fileOutgoing.Close()
				c.fileOutgoing = nil
			}
			if c.fileStorageLease != nil {
				c.fileStorageLease.Close()
				c.fileStorageLease = nil
			}
			return nil, err
		}
		c.fileRetentionStop, c.fileRetentionDone = make(chan struct{}), make(chan struct{})
		go c.retainFiles(delay)
	}
	// Pairing is immutable per wrapper; storage and its worker remain Client-owned.
	f := &FileClient{client: c, host: host, identity: identity, inbox: c.fileIncoming, outbox: c.fileOutgoing}
	f.exchange = func(ctx context.Context, id, method, path string, body []byte) (string, error) {
		return c.exchangeContext(ctx, 60*time.Second, id, host, identity, port, method, path, body)
	}
	f.stream = func(ctx context.Context, id, method, path string, body io.Reader) (int, io.ReadCloser, string, error) {
		return c.fileStream(ctx, id, host, identity, port, method, path, body)
	}
	return f, nil
}

func (f *FileClient) begin(id string) (context.Context, func(), error) {
	if f.client == nil {
		return nil, nil, errors.New("Use Client.OpenFiles to initialize this client")
	}
	return f.client.begin(context.Background(), protocol.FileTransferTimeout, id)
}

func (f *FileClient) ready() error {
	if f.client == nil || f.exchange == nil || f.inbox == nil || f.outbox == nil {
		return errors.New("Use Client.OpenFiles to initialize this client")
	}
	f.client.mu.Lock()
	closed := f.client.closed
	f.client.mu.Unlock()
	if closed {
		return errors.New("Bridge transport closed")
	}
	return nil
}

func (f *FileClient) wire(ctx context.Context, id, method, path string, body []byte) (int, []byte, string, error) {
	if err := f.ready(); err != nil {
		return 0, nil, "", err
	}
	if err := ctx.Err(); err != nil {
		return 0, nil, "", err
	}
	raw, err := f.exchange(ctx, id, method, path, body)
	if err != nil {
		return 0, nil, "", err
	}
	var response struct {
		Status   int    `json:"status"`
		Body     []byte `json:"body"`
		Identity string `json:"identity"`
	}
	if json.Unmarshal([]byte(raw), &response) != nil || response.Status < 100 || response.Status > 599 || len(response.Body) > protocol.MaxBufferedFileBytes || !sessionIdentity.MatchString(response.Identity) || response.Identity == f.identity {
		return 0, nil, "", errors.New("Malformed file transport response")
	}
	return response.Status, response.Body, response.Identity, nil
}

func (f *FileClient) request(ctx context.Context, id, method, path, state string, query url.Values, body []byte) (protocol.FileReply, error) {
	code, result, err := f.response(ctx, id, method, path, query, body)
	if err != nil {
		return result, err
	}
	if code != 200 || result.State != state {
		return result, errors.New("Fresh paired file response unavailable; transfer remains unresolved")
	}
	return result, nil
}

func (f *FileClient) response(ctx context.Context, id, method, path string, query url.Values, body []byte) (int, protocol.FileReply, error) {
	var result protocol.FileReply
	nonce, err := files.NewID()
	if err != nil {
		return 0, result, err
	}
	query.Set("nonce", nonce)
	code, data, ownIdentity, err := f.wire(ctx, id, method, path+"?"+query.Encode(), body)
	if err != nil {
		return 0, result, err
	}
	if err := ctx.Err(); err != nil {
		return 0, result, err
	}
	if len(data) > 256<<10 || protocol.DecodeStrict(data, &result) != nil || result.Protocol != protocol.Files || result.PCIdentity != f.identity || result.PeerIdentity != ownIdentity || result.Nonce != nonce {
		return 0, result, errors.New("Fresh paired file response unavailable; transfer remains unresolved")
	}
	return code, result, nil
}

func fileJSON(value any) (string, error) { data, err := json.Marshal(value); return string(data), err }

func (f *FileClient) Refresh(requestID string) (string, error) {
	ctx, finish, err := f.begin(requestID)
	if err != nil {
		return "", err
	}
	defer finish()
	r, err := f.request(ctx, requestID, "GET", "/v1/files/health", "ready", url.Values{}, nil)
	if err != nil {
		return "", err
	}
	return fileJSON(r)
}

// Prepare snapshots actual bytes before any send and returns the stable ID in
// its descriptor. Pending source survives app death; Send never retries itself.
func (f *FileClient) Prepare(requestID, name string, content []byte) (string, error) {
	ctx, finish, err := f.begin(requestID)
	if err != nil {
		return "", err
	}
	defer finish()
	if err := f.ready(); err != nil {
		return "", err
	}
	id, err := files.NewID()
	if err != nil {
		return "", err
	}
	if len(content) > protocol.MaxBufferedFileBytes {
		return "", errors.New("file_requires_streaming")
	}
	info, err := files.DescribeReaderContext(ctx, id, name, bytes.NewReader(content))
	if err != nil {
		return "", err
	}
	info, err = f.outbox.PutContext(ctx, f.identity, info, bytes.NewReader(content))
	if err != nil {
		return "", err
	}
	return fileJSON(info)
}

func (f *FileClient) Pending(requestID, cursor string, limit int) (string, error) {
	ctx, finish, err := f.begin(requestID)
	if err != nil {
		return "", err
	}
	defer finish()
	if err := f.ready(); err != nil {
		return "", err
	}
	page, err := f.outbox.ListContext(ctx, f.identity, cursor, limit)
	if err != nil {
		return "", err
	}
	return fileJSON(page)
}

func (f *FileClient) Send(requestID, transferID string) (string, error) {
	ctx, finish, err := f.begin(requestID)
	if err != nil {
		return "", err
	}
	defer finish()
	if err := f.ready(); err != nil {
		return "", err
	}
	return f.sendStream(ctx, requestID, transferID, nil)
}

// EnsureSent observes the original receipt first. Only a fresh paired absence
// permits sending the same immutable prepared object; uncertain/corrupt finals never replay.
func (f *FileClient) EnsureSent(requestID, transferID string) (string, error) {
	ctx, finish, err := f.begin(requestID)
	if err != nil {
		return "", err
	}
	defer finish()
	if err := f.ready(); err != nil {
		return "", err
	}
	info, err := f.outbox.InfoContext(ctx, f.identity, transferID)
	if err != nil {
		return "", err
	}
	code, receipt, err := f.response(ctx, requestID, "GET", "/v1/files/"+transferID+"/receipt", url.Values{}, nil)
	if err != nil {
		return "", err
	}
	if code == 200 && receipt.State == "stored" && receipt.File != nil && *receipt.File == info && receipt.Page == nil {
		return fileJSON(receipt)
	}
	if code == 404 && receipt.State == "absent" && receipt.File == nil && receipt.Page == nil {
		if err := ctx.Err(); err != nil {
			return "", err
		}
		return f.sendStream(ctx, requestID, transferID, &info)
	}
	return "", fmt.Errorf("file_receipt_unconfirmed http_status=%d transfer_id=%s action=inspect_original_transfer_receipt", code, transferID)
}

// Receipt rereads actual PC bytes with a fresh nonce and never resubmits data.
func (f *FileClient) Receipt(requestID, transferID string) (string, error) {
	ctx, finish, err := f.begin(requestID)
	if err != nil {
		return "", err
	}
	defer finish()
	if err := f.ready(); err != nil {
		return "", err
	}
	info, err := f.outbox.InfoContext(ctx, f.identity, transferID)
	if err != nil {
		return "", err
	}
	r, err := f.request(ctx, requestID, "GET", "/v1/files/"+transferID+"/receipt", "stored", url.Values{}, nil)
	if err != nil {
		return "", err
	}
	if r.File == nil || *r.File != info || r.Page != nil {
		return "", errors.New("PC receipt differs from prepared bytes")
	}
	return fileJSON(r)
}

func (f *FileClient) Available(requestID, cursor string, limit int) (string, error) {
	ctx, finish, err := f.begin(requestID)
	if err != nil {
		return "", err
	}
	defer finish()
	if limit < 1 || limit > 100 || (cursor != "" && !files.ValidID(cursor)) {
		return "", errors.New("Invalid file page")
	}
	query := url.Values{"cursor": {cursor}, "limit": {strconv.Itoa(limit)}}
	r, err := f.request(ctx, requestID, "GET", "/v1/outbox", "available", query, nil)
	if err != nil {
		return "", err
	}
	if r.Page == nil || len(r.Page.Files) > limit || (r.Page.NextCursor != "" && !files.ValidID(r.Page.NextCursor)) {
		return "", errors.New("Malformed file page")
	}
	if len(r.Page.Diagnostics) > 16 || r.Page.DiagnosticCount < len(r.Page.Diagnostics) {
		return "", errors.New("Malformed file diagnostics")
	}
	for _, issue := range r.Page.Diagnostics {
		if len(issue.ID) > 128 || issue.Peer != r.PeerIdentity || (issue.Code != "managed_entry_unrecognized" && issue.Code != "managed_object_corrupt" && issue.Code != "managed_peer_unrecognized") {
			return "", errors.New("Malformed file diagnostics")
		}
	}
	previous := cursor
	for _, info := range r.Page.Files {
		if !files.Valid(info) || info.ID <= previous {
			return "", errors.New("Malformed or duplicate offered transfer")
		}
		previous = info.ID
	}
	if r.Page.NextCursor != "" && (len(r.Page.Files) == 0 || r.Page.NextCursor != previous) {
		return "", errors.New("Invalid file cursor")
	}
	return fileJSON(r)
}

// Receive verifies a source descriptor, downloaded bytes and the committed local
// recipient object. A queued PC file alone never reports recipient verification.
func (f *FileClient) Receive(requestID, transferID string) (string, error) {
	return f.receive(requestID, transferID, nil)
}

func (f *FileClient) ReceiveMatching(requestID, transferID, name string, size int64, sha256 string) (string, error) {
	expected := protocol.File{ID: transferID, Name: name, Size: size, SHA256: sha256}
	if !files.Valid(expected) {
		return "", errors.New("Exact expected file descriptor required")
	}
	return f.receive(requestID, transferID, &expected)
}

func (f *FileClient) receive(requestID, transferID string, expected *protocol.File) (string, error) {
	ctx, finish, err := f.begin(requestID)
	if err != nil {
		return "", err
	}
	defer finish()
	if !files.ValidID(transferID) {
		return "", errors.New("Exact transfer ID required")
	}
	r, err := f.request(ctx, requestID, "GET", "/v1/outbox/"+transferID+"/info", "available", url.Values{}, nil)
	if err != nil {
		return "", err
	}
	if r.File == nil || !files.Valid(*r.File) || r.File.ID != transferID {
		return "", errors.New("Malformed offered file")
	}
	if expected != nil && *r.File != *expected {
		return "", errors.New("Offered file differs from the job output descriptor; no bytes downloaded")
	}
	nonce, err := files.NewID()
	if err != nil {
		return "", err
	}
	return f.receiveStream(ctx, requestID, nonce, r)
}

func (f *FileClient) downloadFailure(code int, data []byte, own, nonce string, offered protocol.FileReply) error {
	unconfirmed := errors.New("fresh_paired_file_download_unavailable")
	var failure protocol.FileReply
	if code < 400 || code > 599 || len(data) > 128<<10 || own != offered.PeerIdentity || protocol.DecodeStrict(data, &failure) != nil || failure.Protocol != protocol.Files || failure.PCIdentity != f.identity || failure.PeerIdentity != own || failure.Nonce != nonce || failure.File != nil || failure.Page != nil {
		return unconfirmed
	}
	action := "inspect_original_output_descriptor"
	switch failure.State {
	case "unavailable", "rejected":
	case protocol.DownloadCapacityUnavailable:
		if code != 503 {
			return unconfirmed
		}
		action = "retry_same_output_descriptor"
	default:
		return unconfirmed
	}
	return fmt.Errorf("file_download_rejected http_status=%d state=%s transfer_id=%s action=%s", code, failure.State, offered.File.ID, action)
}

func (f *FileClient) Received(requestID, cursor string, limit int) (string, error) {
	ctx, finish, err := f.begin(requestID)
	if err != nil {
		return "", err
	}
	defer finish()
	if err := f.ready(); err != nil {
		return "", err
	}
	page, err := f.inbox.ListContext(ctx, f.identity, cursor, limit)
	if err != nil {
		return "", err
	}
	return fileJSON(page)
}

func (f *FileClient) ReadReceived(requestID, transferID string) ([]byte, error) {
	ctx, finish, err := f.begin(requestID)
	if err != nil {
		return nil, err
	}
	defer finish()
	if err := f.ready(); err != nil {
		return nil, err
	}
	_, data, err := f.inbox.ReadContext(ctx, f.identity, transferID)
	return data, err
}

// ReceivedInfo verifies actual cached bytes. Only a conclusively missing/expired
// local object returns state absent; inspection/integrity failures remain errors.
func (f *FileClient) ReceivedInfo(requestID, transferID string) (string, error) {
	ctx, finish, err := f.begin(requestID)
	if err != nil {
		return "", err
	}
	defer finish()
	if err := f.ready(); err != nil {
		return "", err
	}
	info, source, err := f.inbox.OpenVerifiedContext(ctx, f.identity, transferID)
	if err != nil {
		if ctx.Err() != nil {
			return "", ctx.Err()
		}
		if errors.Is(err, files.ErrAbsent) {
			return fileJSON(map[string]any{"state": "absent", "pcIdentity": f.identity, "transferId": transferID})
		}
		return "", err
	}
	source.Close()
	return fileJSON(map[string]any{"state": "received_local_verified", "pcIdentity": f.identity, "file": info})
}

// StorageDiagnostics reads bounded maintenance snapshots without scanning files.
// observedAt is null until that scope has been observed. Exact accesses still
// validate their own object; cached diagnostics never authorize a transfer.
func (f *FileClient) StorageDiagnostics(requestID string) (string, error) {
	ctx, finish, err := f.begin(requestID)
	if err != nil {
		return "", err
	}
	defer finish()
	if f.exchange == nil || f.inbox == nil || f.outbox == nil {
		return "", errors.New("Use Client.OpenFiles to initialize this client")
	}
	view := map[string]any{"pcIdentity": f.identity}
	for name, store := range map[string]*files.Store{"incoming": f.inbox, "outgoing": f.outbox} {
		page, observed, err := store.DiagnosticSnapshot(ctx, f.identity)
		if err := ctx.Err(); err != nil {
			return "", err
		}
		var observedAt *int64
		if !observed.IsZero() {
			milliseconds := observed.UnixMilli()
			observedAt = &milliseconds
		}
		view[name] = struct {
			protocol.FilePage
			ObservedAt *int64 `json:"observedAt"`
		}{page, observedAt}
		if err != nil {
			view[name] = map[string]any{"code": "storage_diagnostics_unavailable", "diagnosticCount": nil, "observedAt": observedAt}
		}
	}
	view["backgroundOperations"] = map[string]any{"file_retention": f.client.fileRetentionStatus.Snapshot()}
	return fileJSON(view)
}

// These are explicit host cleanup actions, never automatic delivery claims.
func (f *FileClient) DiscardPrepared(transferID string) error {
	if err := f.ready(); err != nil {
		return err
	}
	return f.outbox.Discard(f.identity, transferID)
}
func (f *FileClient) RemoveReceived(transferID string) error {
	if err := f.ready(); err != nil {
		return err
	}
	return f.inbox.Discard(f.identity, transferID)
}

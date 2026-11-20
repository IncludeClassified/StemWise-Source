package mobile

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

type fileStreamBody struct {
	io.ReadCloser
	finish func()
	once   sync.Once
}

func (b *fileStreamBody) Close() error { err := b.ReadCloser.Close(); b.once.Do(b.finish); return err }

func (c *Client) fileStream(parent context.Context, requestID, host, identity string, port int, method, path string, body io.Reader) (int, io.ReadCloser, string, error) {
	u, err := url.ParseRequestURI(path)
	if err != nil || !strings.HasPrefix(path, "/v1/") || u.IsAbs() || u.Host != "" || u.Fragment != "" || (method != "GET" && method != "POST") || port < 1 || port > 65535 {
		return 0, nil, "", errors.New("invalid_file_stream_request")
	}
	ctx, finish := context.WithTimeout(parent, protocol.FileTransferTimeout)
	if err := ctx.Err(); err != nil {
		finish()
		return 0, nil, "", err
	}
	keep := false
	defer func() {
		if !keep {
			finish()
		}
	}()
	lc, ctx, networkFinish, err := c.native(ctx)
	if err != nil {
		return 0, nil, "", err
	}
	defer func() {
		if !keep {
			networkFinish()
		}
	}()
	status, ip, err := c.readyTarget(ctx, lc, host, identity)
	if err != nil {
		return 0, nil, "", err
	}
	transport := &http.Transport{Proxy: nil, DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
		return c.dialTarget(ctx, network, net.JoinHostPort(ip.String(), strconv.Itoa(port)))
	}}
	client := &http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	request, err := http.NewRequestWithContext(ctx, method, "http://"+net.JoinHostPort(host, strconv.Itoa(port))+path, body)
	if err != nil {
		transport.CloseIdleConnections()
		return 0, nil, "", err
	}
	request.Header.Set("Content-Type", "application/octet-stream")
	request.Header.Set("Cache-Control", "no-store")
	response, err := client.Do(request)
	if err != nil {
		transport.CloseIdleConnections()
		return 0, nil, "", err
	}
	keep = true
	return response.StatusCode, &fileStreamBody{ReadCloser: response.Body, finish: func() { networkFinish(); finish(); transport.CloseIdleConnections() }}, string(status.Self.ID), nil
}

// PrepareFile snapshots a regular app-owned file without a payload-sized byte array.
func (f *FileClient) PrepareFile(requestID, name, path string) (string, error) {
	ctx, finish, err := f.begin(requestID)
	if err != nil {
		return "", err
	}
	defer finish()
	if err := f.ready(); err != nil {
		return "", err
	}
	if !filepath.IsAbs(path) {
		return "", errors.New("absolute_source_file_required")
	}
	source, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer source.Close()
	info, err := source.Stat()
	if err != nil || !info.Mode().IsRegular() {
		return "", errors.New("regular_source_file_required")
	}
	id, err := files.NewID()
	if err != nil {
		return "", err
	}
	descriptor, err := files.DescribeReaderContext(ctx, id, name, source)
	if err != nil {
		return "", err
	}
	if _, err := source.Seek(0, io.SeekStart); err != nil {
		return "", err
	}
	stored, err := f.outbox.PutContext(ctx, f.identity, descriptor, source)
	if err != nil {
		return "", err
	}
	return fileJSON(stored)
}

// CopyReceived publishes a verified new destination atomically without replacing one.
func (f *FileClient) CopyReceived(requestID, id, path string) (result error) {
	ctx, finish, err := f.begin(requestID)
	if err != nil {
		return err
	}
	defer finish()
	if err := f.ready(); err != nil {
		return err
	}
	if !filepath.IsAbs(path) {
		return errors.New("absolute_new_destination_required")
	}
	path = filepath.Clean(path)
	release, err := f.client.claimFileDestinations([]string{path})
	if err != nil {
		if errors.Is(err, errFileDestinationInUse) {
			return errors.New("received_copy_in_use")
		}
		return err
	}
	defer release()
	f.client.mu.Lock()
	state := ""
	if f.client.node != nil {
		state = f.client.node.Dir
	}
	f.client.mu.Unlock()
	descriptor, source, err := f.inbox.OpenContext(ctx, f.identity, id)
	if err != nil {
		return err
	}
	defer source.Close()
	// The node's storage lease excludes another process; the destination claim
	// above excludes another local copier while recovering this exact owned stage.
	key := sha256.Sum256([]byte(state + "\x00" + f.identity + "\x00" + id + "\x00" + path))
	stage := filepath.Join(filepath.Dir(path), ".bridge-copy-"+hex.EncodeToString(key[:])+".tmp")
	if previous, err := os.Lstat(stage); err == nil {
		if !previous.Mode().IsRegular() {
			return errors.New("received_copy_stage_invalid")
		}
		if err := os.Remove(stage); err != nil {
			return err
		}
		if err := syncCopyParent(path); err != nil {
			return err
		}
	} else if !os.IsNotExist(err) {
		return err
	}
	if _, err := os.Lstat(path); err == nil {
		return &os.PathError{Op: "rename", Path: path, Err: os.ErrExist}
	} else if !os.IsNotExist(err) {
		return err
	}
	destination, err := os.OpenFile(stage, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	defer func() {
		if destination != nil {
			result = errors.Join(result, destination.Close())
		}
		if err := os.Remove(stage); !os.IsNotExist(err) {
			result = errors.Join(result, err)
		}
	}()
	hash := sha256.New()
	size, err := f.client.copyLocalFile(ctx, io.MultiWriter(destination, hash), io.LimitReader(source, descriptor.Size+1))
	if err != nil {
		return err
	}
	if size != descriptor.Size || hex.EncodeToString(hash.Sum(nil)) != descriptor.SHA256 {
		return errors.New("received_copy_bytes_unverified")
	}
	if err := destination.Sync(); err != nil {
		return err
	}
	if err := destination.Close(); err != nil {
		return err
	}
	destination = nil
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := publishReceivedCopy(stage, path); err != nil {
		return err
	}
	return syncCopyParent(path)
}

func syncCopyParent(path string) error {
	if runtime.GOOS == "windows" {
		return nil
	}
	directory, err := os.Open(filepath.Dir(path))
	if err != nil {
		return err
	}
	return errors.Join(directory.Sync(), directory.Close())
}

func (f *FileClient) sendStream(ctx context.Context, requestID, id string, expected *protocol.File) (string, error) {
	descriptor, source, err := f.outbox.OpenVerifiedContext(ctx, f.identity, id)
	if err != nil {
		return "", err
	}
	defer source.Close()
	if expected != nil && descriptor != *expected {
		return "", errors.New("prepared_transfer_descriptor_changed")
	}
	nonce, err := files.NewID()
	if err != nil {
		return "", err
	}
	query := url.Values{"nonce": {nonce}, "id": {id}, "name": {descriptor.Name}, "size": {strconv.FormatInt(descriptor.Size, 10)}, "sha256": {descriptor.SHA256}}
	if err := ctx.Err(); err != nil {
		return "", err
	}
	code, body, own, err := f.stream(ctx, requestID, "POST", "/v1/files/put?"+query.Encode(), source)
	if err != nil {
		return "", err
	}
	defer body.Close()
	data, err := io.ReadAll(io.LimitReader(body, (128<<10)+1))
	if err != nil {
		return "", err
	}
	var reply protocol.FileReply
	unconfirmed := errors.New("fresh_exact_streamed_file_receipt_unavailable")
	if len(data) > 128<<10 || !sessionIdentity.MatchString(own) || own == f.identity || protocol.DecodeStrict(data, &reply) != nil || reply.Protocol != protocol.Files || reply.PCIdentity != f.identity || reply.PeerIdentity != own || reply.Nonce != nonce {
		return "", unconfirmed
	}
	if code != 200 {
		if code >= 400 && code <= 599 && reply.File == nil && reply.Page == nil {
			switch reply.State {
			case "content_mismatch", "conflict", "unavailable", "rejected", protocol.StorageCapacityUnavailable:
				action := "inspect_original_transfer_receipt"
				if reply.State == protocol.StorageCapacityUnavailable {
					action = "wait_for_transfers_or_release_bridge_storage_then_retry_same_transfer"
				}
				return "", fmt.Errorf("file_upload_rejected http_status=%d state=%s transfer_id=%s action=%s", code, reply.State, id, action)
			}
		}
		return "", unconfirmed
	}
	if reply.State != "stored" || reply.File == nil || *reply.File != descriptor || reply.Page != nil {
		return "", unconfirmed
	}
	return fileJSON(reply)
}

func (f *FileClient) receiveStream(ctx context.Context, requestID, nonce string, offered protocol.FileReply) (string, error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}
	code, body, own, err := f.stream(ctx, requestID, "GET", "/v1/outbox/"+offered.File.ID+"/content?nonce="+nonce, nil)
	if err != nil {
		return "", err
	}
	defer body.Close()
	if code != 200 || own != offered.PeerIdentity {
		data, err := io.ReadAll(io.LimitReader(body, (128<<10)+1))
		if err != nil {
			return "", fmt.Errorf("file_download_receipt_unavailable transfer_id=%s: %w", offered.File.ID, err)
		}
		if len(data) > 128<<10 {
			return "", errors.New("file_download_receipt_too_large")
		}
		return "", f.downloadFailure(code, data, own, nonce, offered)
	}
	stored, err := f.inbox.PutContext(ctx, f.identity, *offered.File, io.LimitReader(body, offered.File.Size+1))
	if err != nil {
		return "", err
	}
	return fileJSON(map[string]any{"state": "received_local_verified", "pcIdentity": f.identity, "identity": own, "file": stored})
}

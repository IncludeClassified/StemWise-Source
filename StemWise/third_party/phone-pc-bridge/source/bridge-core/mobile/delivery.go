package mobile

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

type deliveryImport struct {
	File protocol.File `json:"file"`
	Path string        `json:"path"`
}

func (c *Client) copyLocalFile(ctx context.Context, destination io.Writer, source io.Reader) (int64, error) {
	if c.localFileCopy != nil {
		return c.localFileCopy(ctx, destination, source)
	}
	return files.CopyContext(ctx, destination, source)
}

func (c *Client) verifiedDelivery(ctx context.Context, imports string, acknowledge func([]protocol.File) (string, error)) (result string, resultErr error) {
	var entries []deliveryImport
	if protocol.DecodeStrict([]byte(imports), &entries) != nil || entries == nil || len(entries) > 17 {
		return "", errors.New("invalid_delivery_imports")
	}
	descriptors := []protocol.File{}
	seen := map[string]bool{}
	paths := []string{}
	for _, entry := range entries {
		f := entry.File
		if !protocol.ValidFile(f) || seen[f.ID] || !filepath.IsAbs(entry.Path) {
			return "", errors.New("invalid_delivery_imports")
		}
		seen[f.ID] = true
		paths = append(paths, entry.Path)
	}
	release, err := c.claimFileDestinations(paths)
	if err != nil {
		if errors.Is(err, errFileDestinationInUse) {
			return "", errors.New("delivery_destination_in_use")
		}
		return "", err
	}
	defer release()
	type verifiedSource struct {
		path string
		file *os.File
		info os.FileInfo
	}
	sources := []verifiedSource{}
	defer func() {
		for _, source := range sources {
			resultErr = errors.Join(resultErr, source.file.Close())
		}
	}()
	current := func() error {
		for _, source := range sources {
			opened, openErr := source.file.Stat()
			named, nameErr := os.Lstat(source.path)
			if openErr != nil || nameErr != nil || !named.Mode().IsRegular() || !os.SameFile(source.info, opened) || !os.SameFile(opened, named) ||
				source.info.Size() != opened.Size() || source.info.ModTime() != opened.ModTime() || named.Size() != opened.Size() || named.ModTime() != opened.ModTime() {
				return errors.New("delivery_destination_unverified")
			}
		}
		return nil
	}
	for _, entry := range entries {
		f := entry.File
		info, err := os.Lstat(entry.Path)
		if err != nil || !info.Mode().IsRegular() || info.Size() != f.Size {
			return "", errors.New("delivery_destination_unverified")
		}
		source, err := os.Open(entry.Path)
		if err != nil {
			return "", errors.New("delivery_destination_unavailable")
		}
		sources = append(sources, verifiedSource{path: entry.Path, file: source, info: info})
		hash := sha256.New()
		size, readErr := c.copyLocalFile(ctx, hash, io.LimitReader(source, f.Size+1))
		if err := ctx.Err(); err != nil {
			return "", err
		}
		if readErr != nil || size != f.Size || hex.EncodeToString(hash.Sum(nil)) != f.SHA256 || current() != nil {
			return "", errors.New("delivery_destination_unverified")
		}
		descriptors = append(descriptors, f)
	}
	if err := ctx.Err(); err != nil {
		return "", err
	}
	if err := current(); err != nil {
		return "", err
	}
	result, resultErr = acknowledge(descriptors)
	if err := current(); err != nil {
		return "", errors.Join(errors.New("delivery_destination_changed_acknowledgment_unconfirmed"), err, resultErr)
	}
	return result, resultErr
}

// ConfirmDelivery verifies host-app destination bytes before acknowledging the exact published output batch.
// importsJSON is [{"file":{id,name,size,sha256},"path":"absolute imported destination"}].
func (c *Client) ConfirmDelivery(requestID, host, identity, jobID, importsJSON string) (string, error) {
	ctx, finish, err := c.begin(context.Background(), protocol.FileTransferTimeout, requestID)
	if err != nil {
		return "", err
	}
	defer finish()
	if !sessionToken.MatchString(jobID) {
		return "", errors.New("invalid_delivery_job")
	}
	return c.verifiedDelivery(ctx, importsJSON, func(descriptors []protocol.File) (string, error) {
		body, _ := json.Marshal(map[string]any{"files": descriptors})
		return c.callApp(ctx, requestID, host, identity, "POST", "/v2/jobs/"+jobID+"/delivery", string(body))
	})
}

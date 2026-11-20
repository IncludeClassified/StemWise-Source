package files

import (
	"bytes"
	"context"
	"crypto/sha256"
	"errors"
	"io"
	"path/filepath"
	"strings"
	"testing"
)

type cancellingReader struct {
	cancel context.CancelFunc
	reads  int
}

type cancelAtCheck struct {
	context.Context
	cancel    context.CancelFunc
	remaining int
	triggered bool
}

func (c *cancelAtCheck) Err() error {
	c.remaining--
	if c.remaining == 0 {
		c.triggered = true
		c.cancel()
	}
	return c.Context.Err()
}

func TestCachedReadCancellationNeverBecomesCorruption(t *testing.T) {
	store, err := Open(filepath.Join(t.TempDir(), "cache"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	data := bytes.Repeat([]byte("x"), 64<<10)
	descriptor, err := Describe(strings.Repeat("a", 32), "owned.bin", data)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = store.Put("nApp", descriptor, bytes.NewReader(data)); err != nil {
		t.Fatal(err)
	}
	for check := 1; check <= 40; check++ {
		parent, cancel := context.WithCancel(context.Background())
		ctx := &cancelAtCheck{Context: parent, cancel: cancel, remaining: check}
		_, result, err := store.ReadContext(ctx, "nApp", descriptor.ID)
		cancel()
		if ctx.triggered && !errors.Is(err, context.Canceled) {
			t.Fatalf("Cancellation at verification boundary %d became %v", check, err)
		}
		if !ctx.triggered && (err != nil || !bytes.Equal(result, data)) {
			t.Fatal("Uncancelled actual cache read changed", err)
		}
	}
}

func (r *cancellingReader) Read(p []byte) (int, error) {
	r.reads++
	copy(p, "actual source bytes")
	r.cancel()
	return len("actual source bytes"), nil
}

func TestLocalCopyAndHashStopOnCancellationDuringRead(t *testing.T) {
	for _, destination := range []io.Writer{&bytes.Buffer{}, sha256.New()} {
		ctx, cancel := context.WithCancel(context.Background())
		source := &cancellingReader{cancel: cancel}
		_, err := CopyContext(ctx, destination, source)
		cancel()
		if !errors.Is(err, context.Canceled) || source.reads != 1 {
			t.Fatal("Cancelled local copying/hashing continued reading or claimed success", err, source.reads)
		}
	}
}

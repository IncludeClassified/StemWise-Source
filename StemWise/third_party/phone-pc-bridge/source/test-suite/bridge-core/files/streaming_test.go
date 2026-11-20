package files

import (
	"archive/zip"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestLargeZipMetadataAndStreamingStayBoundedAndBufferedReadRejects(t *testing.T) {
	root := t.TempDir()
	source, err := os.Create(filepath.Join(root, "source.zip"))
	if err != nil {
		t.Fatal(err)
	}
	defer source.Close()
	hash := sha256.New()
	z := zip.NewWriter(io.MultiWriter(source, hash))
	audio, err := z.CreateHeader(&zip.FileHeader{Name: "audio.flac", Method: zip.Store})
	if err != nil {
		t.Fatal(err)
	}
	chunk := make([]byte, 32<<10)
	for i := range chunk {
		chunk[i] = byte(i*37 + 11)
	}
	for written := int64(0); written < 72<<20; written += int64(len(chunk)) {
		if _, err := audio.Write(chunk); err != nil {
			t.Fatal(err)
		}
	}
	metadata, err := z.Create("metadata.json")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := io.WriteString(metadata, `{"owned":true,"format":"opaque_transfer_fixture"}`); err != nil {
		t.Fatal(err)
	}
	if err := z.Close(); err != nil {
		t.Fatal(err)
	}
	if err := source.Sync(); err != nil {
		t.Fatal(err)
	}
	stat, err := source.Stat()
	if err != nil {
		t.Fatal(err)
	}
	id, _ := NewID()
	expectedHash := hex.EncodeToString(hash.Sum(nil))
	if _, err := source.Seek(0, io.SeekStart); err != nil {
		t.Fatal(err)
	}
	store, err := Open(filepath.Join(root, "store"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	runtime.GC()
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)
	f, err := DescribeReader(id, "owned.zip", source)
	if err != nil || f.Size != stat.Size() || f.SHA256 != expectedHash || f.Size <= 64<<20 {
		t.Fatal("Large archive description differs", err)
	}
	if _, err := source.Seek(0, io.SeekStart); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Put("nOwned", f, source); err != nil {
		t.Fatal(err)
	}
	if actual, err := store.Info("nOwned", id); err != nil || actual != f {
		t.Fatal("Large metadata check failed", err)
	}
	page, err := store.List("nOwned", "", 10)
	if err != nil || len(page.Files) != 1 || page.Files[0] != f {
		t.Fatal("Large descriptor listing failed", err)
	}
	actual, stream, err := store.OpenVerified("nOwned", id)
	if err != nil {
		t.Fatal(err)
	}
	defer stream.Close()
	received := sha256.New()
	n, err := io.Copy(received, stream)
	if err != nil || actual != f || n != f.Size || hex.EncodeToString(received.Sum(nil)) != expectedHash {
		t.Fatal("Actual streamed archive bytes differ", err)
	}
	runtime.ReadMemStats(&after)
	if after.TotalAlloc-before.TotalAlloc > 8<<20 {
		t.Fatal("Large-file operations allocated payload-sized memory", after.TotalAlloc-before.TotalAlloc)
	}
	if _, _, err := store.Read("nOwned", id); err == nil || !strings.Contains(err.Error(), "file_requires_streaming") {
		t.Fatal("Legacy byte-array API allocated a large payload", err)
	}
	if err := store.Remove("nOwned", id); err != nil {
		t.Fatal("Large managed object could not be removed", err)
	}
}

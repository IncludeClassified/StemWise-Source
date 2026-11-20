package files

import (
	"errors"
	"io"
	"os"
	"strings"
	"testing"
)

func TestPutStopsAtDeclaredSize(t *testing.T) {
	store, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	for _, size := range []int{0, 3} {
		id, _ := NewID()
		file, _ := Describe(id, "owned.bin", []byte(strings.Repeat("x", size)))
		source := &io.LimitedReader{R: strings.NewReader(strings.Repeat("x", 4096)), N: 4096}
		if _, err := store.Put("nOwned", file, source); !errors.Is(err, ErrContentMismatch) {
			t.Fatal("Oversized source was accepted", err)
		}
		if consumed := int64(4096) - source.N; consumed != file.Size+1 {
			t.Fatal("Transfer read beyond its declared size plus one-byte mismatch check", consumed)
		}
		if _, err := store.Info("nOwned", id); !os.IsNotExist(err) {
			t.Fatal("Rejected source was retained", err)
		}
	}
}

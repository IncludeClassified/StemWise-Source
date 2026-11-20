package mobile

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"phonepcbridge/files"
	"phonepcbridge/protocol"
	"testing"
)

func TestDeliveryVerifiesActualImportedDestinationBytes(t *testing.T) {
	c := new(Client)
	dir := t.TempDir()
	path := filepath.Join(dir, "import.bin")
	data := []byte("independent actual imported bytes")
	os.WriteFile(path, data, 0600)
	descriptor, _ := files.Describe("0123456789abcdef0123456789abcdef", "import.bin", data)
	payload := func() string {
		raw, _ := json.Marshal([]deliveryImport{{File: descriptor, Path: path}})
		return string(raw)
	}
	acknowledge := func(output []protocol.File) (string, error) {
		if len(output) != 1 || output[0] != descriptor {
			t.Fatal("Acknowledged batch differs from verified imported bytes", output)
		}
		return "verified", nil
	}
	if output, err := c.verifiedDelivery(context.Background(), payload(), acknowledge); err != nil || output != "verified" {
		t.Fatal(err)
	}
	os.WriteFile(path, []byte("changed"), 0600)
	if _, err := c.verifiedDelivery(context.Background(), payload(), acknowledge); err == nil {
		t.Fatal("descriptor without real imported bytes passed")
	}
	os.Remove(path)
	if _, err := c.verifiedDelivery(context.Background(), payload(), acknowledge); err == nil {
		t.Fatal("missing destination passed")
	}
	if _, err := c.verifiedDelivery(context.Background(), "null", acknowledge); err == nil {
		t.Fatal("missing import batch passed")
	}
	if _, err := c.verifiedDelivery(context.Background(), "[]", func(output []protocol.File) (string, error) {
		if output == nil || len(output) != 0 {
			t.Fatal("Output-free delivery changed", output)
		}
		return "", nil
	}); err != nil {
		t.Fatal("output-free delivery rejected", err)
	}
}

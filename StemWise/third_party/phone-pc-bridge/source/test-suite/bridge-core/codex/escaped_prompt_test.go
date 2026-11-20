package codex

import (
	"context"
	"strings"
	"testing"
	"time"
)

func TestOversizedNativeRequestSendsNothing(t *testing.T) {
	c := peer(t, "normal")
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if begun, err := c.send(ctx, map[string]string{"text": strings.Repeat("x", maxMessage)}); begun || err == nil {
		t.Fatal("Oversized native frame entered the writer", begun, err)
	}
	if _, err := c.Models(ctx, ""); err != nil {
		t.Fatal("Rejected frame damaged the actual native writer", err)
	}
}

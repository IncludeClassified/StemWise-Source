package codex

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func capturedTierFrames(t *testing.T, c *Connection, ctx context.Context) (map[string]any, map[string]any) {
	t.Helper()
	raw, err := c.request(ctx, "fixture/frames", nil)
	var frames struct{ Start, Turn map[string]any }
	if err != nil || json.Unmarshal(raw, &frames) != nil {
		t.Fatal("Missing actual native request frames", err)
	}
	return frames.Start, frames.Turn
}

func sendTierJob(t *testing.T, c *Connection, ctx context.Context, tier string) (json.RawMessage, error) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "bridge-developer.md")
	const workflow = "bridge-developer-v2 isolated service-tier workflow"
	if err := os.WriteFile(path, []byte(workflow), 0600); err != nil {
		t.Fatal(err)
	}
	return c.SendJob(ctx, "fresh-1", "actual tier job text", "custom-tier instructions", path, workflow, "fixture-model", "high", tier)
}

func TestServiceTierPreservesExactNativeStartAndTurnParameters(t *testing.T) {
	for _, tier := range []string{"", "default", "fast", "vendor opaque/速度-v3", strings.Repeat("é", 64)} {
		t.Run(tier, func(t *testing.T) {
			c, ctx := freshThreadPeer(t, "normal")
			project := t.TempDir()
			if _, err := c.StartThread(ctx, project, "fixture-model", tier); err != nil {
				t.Fatal(err)
			}
			ack, err := sendTierJob(t, c, ctx, tier)
			if err != nil {
				t.Fatal(err)
			}
			start, turn := capturedTierFrames(t, c, ctx)
			if start["cwd"] != project || start["model"] != "fixture-model" {
				t.Fatal("Chat creation changed its managed project or model", start)
			}
			if tier == "" {
				if _, exists := start["serviceTier"]; exists {
					t.Fatal("Empty start tier overrode native defaults")
				}
				if _, exists := turn["serviceTierForTurn"]; exists {
					t.Fatal("Empty turn tier overrode native inheritance")
				}
				if len(start) != 2 || len(turn) != 4 {
					t.Fatal("Empty tier changed existing native request keys", start, turn)
				}
			} else if start["serviceTier"] != tier || turn["serviceTierForTurn"] != tier || len(start) != 3 || len(turn) != 5 {
				t.Fatal("Explicit tier was changed, omitted, or substituted", start, turn)
			}
			if _, persistentOverride := turn["serviceTier"]; persistentOverride {
				t.Fatal("Per-job choice changed persistent thread tier")
			}
			if _, present := start["developerInstructions"]; present {
				t.Fatal("Chat creation duplicated the handoff workflow")
			}
			if _, present := start["baseInstructions"]; present {
				t.Fatal("Chat creation overrode native base instructions")
			}
			input := turn["input"].([]any)
			if turn["model"] != "fixture-model" || turn["effort"] != "high" || len(input) != 1 || input[0].(map[string]any)["type"] != "text" {
				t.Fatal("Tier handling changed model, reasoning, or single-text job framing", turn)
			}
			text := input[0].(map[string]any)["text"].(string)
			message, err := ParseJobMessage(text)
			if err != nil || message.Prompt != "actual tier job text" || message.Instructions != "custom-tier instructions" || !filepath.IsAbs(message.Path) || filepath.Base(message.Path) != "bridge-developer.md" || message.Header != "" || strings.Contains(text, "bridge-developer-v2 isolated service-tier workflow") {
				t.Fatal("Job message changed user data or inlined the file workflow", message, err)
			}
			file, err := os.ReadFile(message.Path)
			hash := sha256.Sum256(file)
			if err != nil || string(file) != "bridge-developer-v2 isolated service-tier workflow" || message.SHA256 != hex.EncodeToString(hash[:]) {
				t.Fatal("Job message did not bind the actual local workflow file", err)
			}
			var result map[string]any
			if json.Unmarshal(ack, &result) != nil || len(result) != 1 || len(result["turn"].(map[string]any)) != 2 {
				t.Fatal("Request echo manufactured an effective-tier guarantee", string(ack))
			}
			freshCounts(t, c, ctx, 1, 0, 1)
		})
	}
}

func TestServiceTierRejectsInvalidInputsBeforeNativeMutation(t *testing.T) {
	for _, tier := range []string{strings.Repeat("x", 129), "fast\n", "fast\t", "fast\x00", "fast\u0085", string([]byte{0xff})} {
		t.Run(tier, func(t *testing.T) {
			c, ctx := freshThreadPeer(t, "normal")
			_, err := c.StartThread(ctx, t.TempDir(), "", tier)
			if err == nil || !strings.Contains(err.Error(), "Invalid service tier") {
				t.Fatal("Invalid native creation tier accepted", err)
			}
			freshCounts(t, c, ctx, 0, 0, 0)
			if _, err := c.StartThread(ctx, t.TempDir(), "", ""); err != nil {
				t.Fatal(err)
			}
			if _, err := sendTierJob(t, c, ctx, tier); err == nil || !strings.Contains(err.Error(), "Invalid service tier") {
				t.Fatal("Invalid native job tier accepted", err)
			}
			freshCounts(t, c, ctx, 1, 0, 0)
		})
	}
}

func TestUnknownNativeTierRejectionNeverFallsBackOrRetries(t *testing.T) {
	for _, mode := range []string{"tier_reject_start", "tier_reject_turn"} {
		t.Run(mode, func(t *testing.T) {
			c, ctx := freshThreadPeer(t, mode)
			const tier = "future-vendor:tier-v9"
			_, err := c.StartThread(ctx, t.TempDir(), "", tier)
			if mode == "tier_reject_turn" {
				if err != nil {
					t.Fatal(err)
				}
				_, err = sendTierJob(t, c, ctx, tier)
			}
			var rpc *RPCError
			if !errors.As(err, &rpc) || rpc.Code != -32015 {
				t.Fatal("Native tier rejection was replaced with another outcome", err)
			}
			turns := 0
			if mode == "tier_reject_turn" {
				turns = 1
			}
			freshCounts(t, c, ctx, 1, 0, turns)
			start, turn := capturedTierFrames(t, c, ctx)
			if start["serviceTier"] != tier || (turns != 0 && turn["serviceTierForTurn"] != tier) {
				t.Fatal("Unknown tier was not sent unchanged to native validation", start, turn)
			}
		})
	}
}

func TestNativeModelCatalogPreservesAdvertisedOpaqueTierMetadata(t *testing.T) {
	c := peer(t, "service-tier-catalog")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	raw, err := c.Models(ctx, "")
	var page struct {
		Data []struct {
			ServiceTiers       []struct{ ID, Name, Description string }
			DefaultServiceTier string
		}
	}
	if err != nil || json.Unmarshal(raw, &page) != nil || len(page.Data) != 1 || len(page.Data[0].ServiceTiers) != 3 ||
		page.Data[0].ServiceTiers[2].ID != "vendor/opaque-v3" || page.Data[0].DefaultServiceTier != "default" || page.Data[0].ServiceTiers[2].Description != "Isolated test metadata" {
		t.Fatal("Native advertised tier metadata was discarded or rewritten", string(raw), err)
	}
}

package backend

import (
	"context"
	"encoding/json"
	"testing"

	"phonepcbridge/protocol"
)

func TestConnectionExposesEnforcedTransferLimitsWithoutPCOrRegistration(t *testing.T) {
	s, n, call := fixtureAPI(t)
	wakeCalls := 0
	s.wake = func(_ context.Context) error { wakeCalls++; return nil }
	for _, online := range []bool{false, true} {
		n.online = online
		w := call("GET", "/v2/connection", nil, "nAlice")
		var reply struct {
			Data struct {
				PCState       string `json:"pcState"`
				FileTransfers struct {
					MaxFileBytes   int64 `json:"maxFileBytes"`
					BufferAPIBytes int64 `json:"bufferApiBytes"`
					MaxDownloads   int   `json:"maxConcurrentDownloads"`
					Streaming      bool  `json:"streaming"`
					TimeoutMs      int64 `json:"transferTimeoutMs"`
				} `json:"fileTransfers"`
			} `json:"data"`
		}
		if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &reply) != nil {
			t.Fatal("Connection metadata unavailable", w.Code)
		}
		limits := reply.Data.FileTransfers
		if limits.MaxFileBytes != protocol.MaxFileBytes || limits.BufferAPIBytes != protocol.MaxBufferedFileBytes || limits.MaxDownloads != cap(s.downloadSlots) || limits.TimeoutMs != protocol.FileTransferTimeout.Milliseconds() || !limits.Streaming {
			t.Fatal("API limits differ from actual file validation, SDK buffer bounds or admission capacity", limits)
		}
		if (reply.Data.PCState == "reachable") != online {
			t.Fatal("Limit discovery changed PC reachability")
		}
	}
	if n.calls != 0 || wakeCalls != 0 {
		t.Fatal("Read-only capability discovery executed work or sent wake")
	}
	if w := call("GET", "/v2/app", nil, "nAlice"); w.Code != 409 {
		t.Fatal("Capability read registered an app")
	}
}

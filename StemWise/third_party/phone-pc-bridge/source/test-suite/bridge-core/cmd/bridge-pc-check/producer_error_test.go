package main

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"

	"phonepcbridge/files"
	"phonepcbridge/pc"
	"phonepcbridge/protocol"
)

func TestProducerFailuresRequireAuthenticatedExactReplies(t *testing.T) {
	for _, variant := range []string{"capacity", "conflict", "content_mismatch", "unavailable", "owner", "referenced", "owner_status", "nonce", "pc", "peer", "signature", "protocol", "unknown_field", "duplicate_state", "unknown_state", "file", "page", "redirect", "success_status", "capacity_status"} {
		t.Run(variant, func(t *testing.T) {
			cfg := producerConfig{Protocol: producerProtocol, PCIdentity: "nPc", PeerIdentity: "nPi", Token: strings.Repeat("c", 64)}
			var calls atomic.Int32
			server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls.Add(1)
				reply := protocol.FileReply{Protocol: producerProtocol, PCIdentity: "nPc", PeerIdentity: "nPi", Nonce: r.URL.Query().Get("nonce"), State: protocol.StorageCapacityUnavailable}
				status := 503
				switch variant {
				case "conflict":
					status, reply.State = 409, variant
				case "content_mismatch":
					status, reply.State = 400, variant
				case "unavailable":
					reply.State = variant
				case "owner", "owner_status":
					reply.State = pc.ErrJobFileOwnership.Error()
					if variant == "owner_status" {
						status = 404
					}
				case "referenced":
					reply.State = pc.ErrJobFileReferenced.Error()
				case "nonce":
					reply.Nonce = strings.Repeat("0", 32)
				case "pc":
					reply.PCIdentity = "nOther"
				case "peer":
					reply.PeerIdentity = "nOther"
				case "protocol":
					reply.Protocol = "other"
				case "unknown_state":
					reply.State = "password=never_export_this"
				case "file":
					f, _ := files.Describe(strings.Repeat("1", 32), "owned.bin", nil)
					reply.File = &f
				case "page":
					reply.Page = &protocol.FilePage{Files: []protocol.File{}}
				case "redirect":
					status = 307
				case "success_status":
					status = 200
				case "capacity_status":
					status = 404
				}
				data, _ := json.Marshal(reply)
				switch variant {
				case "unknown_field":
					data = append(data[:len(data)-1], []byte(`,"details":"password=never_export_this"}`)...)
				case "duplicate_state":
					data = append([]byte(`{"state":"unavailable",`), data[1:]...)
				}
				signature := producerSignature(cfg, r, true, status, data)
				if variant == "signature" {
					signature = strings.Repeat("0", 64)
				}
				w.Header().Set("X-Bridge-Response", signature)
				w.WriteHeader(status)
				w.Write(data)
			}))
			defer server.Close()
			cfg.Address = strings.TrimPrefix(server.URL, "https://")
			cfg.Certificate = base64.StdEncoding.EncodeToString(server.Certificate().Raw)
			_, err := producerReply(cfg, "POST", "/v1/outbox/"+strings.Repeat("1", 32), "queued", url.Values{}, []byte("owned bytes"))
			trusted := variant == "capacity" || variant == "conflict" || variant == "content_mismatch" || variant == "unavailable" || variant == "owner" || variant == "referenced"
			if err == nil || strings.HasPrefix(err.Error(), "local_file_rejected ") != trusted || strings.Contains(err.Error(), "never_export_this") || calls.Load() != 1 {
				t.Fatal("Producer lost an authenticated rejection, trusted an invalid reply, or replayed", err, calls.Load())
			}
			if variant == "capacity" && err.Error() != "local_file_rejected http_status=503 state=storage_capacity_unavailable action=release_pc_storage_or_wait_for_transfers_then_retry_same_transfer" {
				t.Fatal("PC capacity failure lacks its machine recovery action", err)
			}
			if action := map[string]string{"owner": "inspect_exact_job_owner", "referenced": "wait_for_owned_job_cleanup"}[variant]; action != "" && !strings.HasSuffix(err.Error(), " action="+action) {
				t.Fatal("Owned file refusal lost its recovery action", err)
			}
		})
	}
}

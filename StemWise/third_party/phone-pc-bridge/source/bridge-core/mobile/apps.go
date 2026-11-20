package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"net/url"
	"phonepcbridge/protocol"
	"strings"
	"time"
)

// CallApp sends only the app HTTP contract through this app's embedded private transport.
// Errors from the PC remain structured responses; transport failures are separate Go errors.
func (c *Client) CallApp(requestID, host, identity, method, path, body string) (string, error) {
	ctx, finish, err := c.begin(context.Background(), 60*time.Second, requestID)
	if err != nil {
		return "", err
	}
	defer finish()
	return c.callApp(ctx, requestID, host, identity, method, path, body)
}

func (c *Client) callApp(ctx context.Context, requestID, host, identity, method, path, body string) (string, error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}
	u, err := url.ParseRequestURI(path)
	if err != nil || !strings.HasPrefix(path, "/v2/") || len(body) > protocol.MaxRequestBytes || (method != "GET" && method != "POST" && method != "PUT" && method != "DELETE") {
		return "", errors.New("Invalid app API request")
	}
	if u.RawQuery != "" {
		query := u.Query()
		if method != "GET" || u.Path != "/v2/codex/models" || len(query) != 1 || len(query["cursor"]) != 1 || len(query.Get("cursor")) > 4096 {
			return "", errors.New("Invalid catalog cursor")
		}
	}
	raw, err := c.exchangeContext(ctx, 60*time.Second, requestID, host, identity, protocol.PiPort, method, path, []byte(body))
	if err != nil {
		return "", err
	}
	var wire struct {
		Status int    `json:"status"`
		Body   []byte `json:"body"`
	}
	if json.Unmarshal([]byte(raw), &wire) != nil {
		return "", errors.New("Invalid app transport response")
	}
	var envelope struct {
		Protocol string          `json:"protocol"`
		Bridge   string          `json:"bridgeIdentity"`
		Data     json.RawMessage `json:"data"`
	}
	if json.Unmarshal(wire.Body, &envelope) != nil || envelope.Protocol != protocol.App || envelope.Bridge != identity || envelope.Data == nil {
		return "", errors.New("Uncorrelated app API response")
	}
	data, err := json.Marshal(map[string]any{"httpStatus": wire.Status, "response": json.RawMessage(wire.Body)})
	return string(data), err
}

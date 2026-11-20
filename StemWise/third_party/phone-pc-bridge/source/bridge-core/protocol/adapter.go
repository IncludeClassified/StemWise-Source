package protocol

import "encoding/json"

const Adapter = "phone-pc-bridge-adapter-v1"
const PiPort = 8781

type AdapterRequest struct {
	Nonce        string `json:"nonce"`
	Operation    string `json:"operation"`
	RequestID    string `json:"requestId,omitempty"`
	CreatedAt    int64  `json:"createdAt,omitempty"`
	ThreadID     string `json:"threadId,omitempty"`
	Name         string `json:"name,omitempty"`
	Cursor       string `json:"cursor,omitempty"`
	Limit        int    `json:"limit,omitempty"`
	Text         string `json:"text,omitempty"`
	Instructions string `json:"instructions,omitempty"`
	AppID        string `json:"appId,omitempty"`
	JobID        string `json:"jobId,omitempty"`
	TurnID       string `json:"turnId,omitempty"`
	Files        []File `json:"files,omitempty"`
	Model        string `json:"model,omitempty"`
	Effort       string `json:"effort,omitempty"`
	ServiceTier  string `json:"serviceTier,omitempty"`
}
type AdapterReply struct {
	Protocol   string          `json:"protocol"`
	PCIdentity string          `json:"pcIdentity"`
	Nonce      string          `json:"nonce"`
	State      string          `json:"state"`
	Result     json.RawMessage `json:"result,omitempty"`
	Code       string          `json:"code,omitempty"`
	Failure    *AdapterFailure `json:"failure,omitempty"`
}

type AdapterFailure struct {
	RPCCode *int                   `json:"rpcCode,omitempty"`
	Message string                 `json:"message"`
	Process *AdapterProcessFailure `json:"process,omitempty"`
}

type AdapterProcessFailure struct {
	WaitState            string `json:"waitState"`
	ExitCode             *int   `json:"exitCode,omitempty"`
	Stderr               string `json:"stderr,omitempty"`
	StderrTruncated      bool   `json:"stderrTruncated,omitempty"`
	TerminationRequested bool   `json:"terminationRequested,omitempty"`
}

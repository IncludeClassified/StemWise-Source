package files

import (
	"context"
	"io"
)

type contextReader struct {
	ctx context.Context
	r   io.Reader
}

func (r contextReader) Read(p []byte) (int, error) {
	if err := r.ctx.Err(); err != nil {
		return 0, err
	}
	if len(p) > 32<<10 {
		p = p[:32<<10]
	}
	n, err := r.r.Read(p)
	if cancelled := r.ctx.Err(); cancelled != nil {
		return n, cancelled
	}
	return n, err
}

// CopyContext checks cancellation between bounded reads, including local hashing.
// Regular file syscalls must return before their caller can retire; no detached worker is used.
func CopyContext(ctx context.Context, destination io.Writer, source io.Reader) (int64, error) {
	n, err := io.Copy(destination, contextReader{ctx: ctx, r: source})
	if cancelled := ctx.Err(); cancelled != nil {
		return n, cancelled
	}
	return n, err
}

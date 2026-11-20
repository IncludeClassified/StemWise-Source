//go:build !windows

package files

import "context"

func renameContext(ctx context.Context, rename func() error) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	return rename()
}

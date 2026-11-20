package mobile

import (
	"context"
	"errors"
	"strings"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/protocol"
)

const operationPrefix = "bridge-op:"
const maxReservedOperations = 128

var errOperationClosed = errors.New("bridge_operation_closed")

// Operation reserves one SDK call before a host schedules/publishes its worker.
// Tokens are single-use and cannot become fresh calls after cancellation or retirement.
type Operation struct {
	client     *Client
	token      string
	ctx        context.Context
	cancel     context.CancelFunc
	stopExpiry func() bool
	running    bool // protected by client.mu
}

// NewOperation performs no network IO. Close abandoned/rejected phases explicitly.
// At most 128 reservations are retained; unused ones expire after 30 minutes.
func (c *Client) NewOperation() (*Operation, error) {
	return c.newOperation(protocol.FileTransferTimeout)
}

func (c *Client) newOperation(lifetime time.Duration) (*Operation, error) {
	if lifetime <= 0 || lifetime > protocol.FileTransferTimeout {
		return nil, errors.New("invalid_operation_lifetime")
	}
	id, err := files.NewID()
	if err != nil {
		return nil, err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed || c.node == nil {
		return nil, errors.New("Bridge transport closed or uninitialized")
	}
	if len(c.operations) >= maxReservedOperations {
		return nil, errors.New("bridge_operation_capacity_unavailable")
	}
	if c.operations[operationPrefix+id] != nil || c.active[operationPrefix+id] != nil {
		return nil, errors.New("bridge_operation_id_conflict")
	}
	ctx, cancel := context.WithTimeout(context.Background(), lifetime)
	op := &Operation{client: c, token: operationPrefix + id, ctx: ctx, cancel: cancel}
	if c.operations == nil {
		c.operations = make(map[string]*Operation)
	}
	c.operations[op.token] = op
	op.stopExpiry = context.AfterFunc(ctx, func() {
		c.mu.Lock()
		defer c.mu.Unlock()
		if c.operations[op.token] != op {
			return
		}
		if active := c.active[op.token]; active != nil {
			active()
		}
		if !op.running {
			delete(c.operations, op.token)
		}
	})
	return op, nil
}

// Token is the operationId argument for exactly one blocking SDK call.
func (o *Operation) Token() string {
	if o == nil {
		return ""
	}
	return o.token
}

// Cancel is safe before SDK entry as well as during its actual work.
func (o *Operation) Cancel() {
	if o == nil || o.client == nil {
		return
	}
	c := o.client
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.operations[o.token] == o {
		c.cancelOperationLocked(o)
	}
}

// Close cancels unused/active work. An active slot remains owned until SDK finish.
func (o *Operation) Close() { o.Cancel() }

func (c *Client) cancelOperationLocked(o *Operation) {
	o.cancel()
	if o.stopExpiry != nil {
		o.stopExpiry()
	}
	if active := c.active[o.token]; active != nil {
		active()
	}
	if !o.running && c.operations[o.token] == o {
		delete(c.operations, o.token)
	}
}

func reservedToken(id string) bool { return strings.HasPrefix(id, operationPrefix) }

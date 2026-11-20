package mobile

import (
	"errors"
	"net"

	"tailscale.com/tsnet"
)

// SetNetworkEnabled controls the entire embedded node, including autonomous
// sockets. Enabling permits a later explicit SDK call; it never replays work.
// Local file access and its retention worker remain available while disabled.
func (c *Client) SetNetworkEnabled(enabled bool) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed || c.node == nil {
		return errors.New("Bridge transport closed")
	}
	if enabled {
		if c.networkStopError != nil {
			if err := c.retireNodeLocked(); err != nil {
				return readinessError("bridge_network_unavailable", true, err)
			}
		}
		if err := platformResume(c); err != nil {
			return readinessError("bridge_network_unavailable", true, err)
		}
		c.networkDisabled = false
		return nil
	}
	c.networkDisabled = true
	platformSuspend(c)
	if !c.started && c.networkStopError == nil {
		return nil
	}
	return c.retireNodeLocked()
}

// Startup can fail before tsnet has a System, or after creating an engine but
// before a LocalBackend owns its shutdown. Retire both cases without a reset.
func closeNode(node *tsnet.Server) error {
	if node == nil || node.Sys() == nil {
		return nil
	}
	err := node.Close()
	if errors.Is(err, net.ErrClosed) {
		err = nil
	}
	if engine, ok := node.Sys().Engine.GetOK(); ok {
		select {
		case <-engine.Done():
		default:
			engine.Close()
			<-engine.Done()
		}
	}
	return err
}

func (c *Client) retireNodeLocked() error {
	if c.networkCancel != nil {
		c.networkCancel()
	}
	c.local, c.started = nil, false
	previous := c.node
	if err := closeNode(previous); err != nil {
		c.networkStopError = err
		return err
	}
	// tsnet servers cannot restart; reuse the same private enrollment directory.
	c.node = &tsnet.Server{Dir: previous.Dir, Hostname: previous.Hostname, AuthKey: previous.AuthKey,
		ControlURL: previous.ControlURL, Logf: previous.Logf, UserLogf: previous.UserLogf}
	c.networkStopError = nil
	return nil
}

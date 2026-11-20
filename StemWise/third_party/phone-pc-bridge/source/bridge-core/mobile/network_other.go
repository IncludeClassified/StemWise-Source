//go:build !android

package mobile

func platformNetworkOwner(*Client) error { return nil }

func platformReady(c *Client) error {
	if c.socketPolicy != nil && !c.socketPolicy.ready() {
		return errNetworkPolicyUnavailable
	}
	return nil
}

func platformResume(c *Client) error {
	if c.socketPolicy != nil {
		return c.socketPolicy.resume()
	}
	return nil
}

func platformSuspend(c *Client) {
	if c.socketPolicy != nil {
		c.socketPolicy.suspend()
	}
}

func platformClose(c *Client) { platformSuspend(c) }

func platformNetwork(string, string) {}

func platformStorage(string) error { return nil }

func platformSyncDirectory(string) error { return nil }

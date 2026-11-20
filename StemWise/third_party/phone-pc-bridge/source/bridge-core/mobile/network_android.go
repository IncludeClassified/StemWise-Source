//go:build android

package mobile

import (
	"context"
	"errors"
	"net"
	"net/url"
	"os"
	"sync"
	"sync/atomic"
	"syscall"
	"tailscale.com/logtail"
	"tailscale.com/net/dnscache"
	"tailscale.com/net/netmon"
	"tailscale.com/net/netns"
	"tailscale.com/net/tshttpproxy"
)

var androidSocketOwner atomic.Pointer[socketPolicy]
var androidSocketOwnerMu sync.Mutex

func init() {
	logtail.Disable()
	if err := tshttpproxy.SetProxyFunc(func(*url.URL) (*url.URL, error) { return nil, nil }); err != nil {
		panic(err)
	}
	// Protect is mandatory even when tailnet capabilities disable the optional
	// bind-to-active-network hook. All engine sockets must pass this policy.
	netns.SetAndroidProtectFunc(func(fd int) error {
		policy := androidSocketOwner.Load()
		if policy == nil {
			return errNetworkPolicyUnavailable
		}
		return policy.bind(fd)
	})
	resolver := &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
		policy := androidSocketOwner.Load()
		if policy == nil {
			return nil, errNetworkPolicyUnavailable
		}
		return policy.dialDNS(ctx, network, address)
	}}
	net.DefaultResolver = resolver
	dnscache.Get().Forward = resolver
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		p := androidInterfaces.Load()
		if p == nil {
			return nil, errors.New("Android network snapshot required")
		}
		return *p, nil
	})
}

func platformReady(c *Client) error {
	if androidInterfaces.Load() == nil {
		return errors.New("Set Android network state before Start")
	}
	if c.socketPolicy == nil || androidSocketOwner.Load() != c.socketPolicy || !c.socketPolicy.ready() {
		return errNetworkPolicyUnavailable
	}
	return nil
}

func platformNetworkOwner(c *Client) error {
	owner := androidSocketOwner.Load()
	if owner != nil && owner != c.socketPolicy {
		return errors.New("One embedded network owner per Android process")
	}
	return nil
}

func platformResume(c *Client) error {
	androidSocketOwnerMu.Lock()
	defer androidSocketOwnerMu.Unlock()
	if c.socketPolicy == nil {
		return errNetworkPolicyUnavailable
	}
	owner := androidSocketOwner.Load()
	if owner != nil && owner != c.socketPolicy {
		return errors.New("One embedded network owner per Android process")
	}
	if err := c.socketPolicy.resume(); err != nil {
		return err
	}
	androidSocketOwner.Store(c.socketPolicy)
	return nil
}

func platformSuspend(c *Client) {
	if c.socketPolicy != nil {
		c.socketPolicy.suspend()
	}
}

func platformClose(c *Client) {
	platformSuspend(c)
	androidSocketOwnerMu.Lock()
	defer androidSocketOwnerMu.Unlock()
	if c.socketPolicy != nil && androidSocketOwner.Load() == c.socketPolicy {
		androidSocketOwner.Store(nil)
	}
}

func platformStorage(path string) error {
	info, err := os.Lstat(path)
	if err != nil {
		return err
	}
	owner, ok := info.Sys().(*syscall.Stat_t)
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 || info.Mode().Perm()&0077 != 0 ||
		!ok || int(owner.Uid) != os.Geteuid() {
		return errors.New("Bridge state must be a private directory owned by this Android app")
	}
	// Tailscale's socket logger ignores tsnet.Dir and cannot use Android's system paths.
	return os.Setenv("TS_LOGS_DIR", path)
}

func platformNetwork(iface, gateway string) {
	netmon.UpdateLastKnownDefaultRouteInterface(iface)
	netmon.UpdateLastKnownDefaultGateway(gateway)
}

func platformSyncDirectory(path string) error {
	directory, err := os.Open(path)
	if err != nil {
		return err
	}
	defer directory.Close()
	return directory.Sync()
}

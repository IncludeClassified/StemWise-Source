//go:build !windows

package codex

import (
	"errors"
	"os/exec"
	"sync"
	"syscall"
	"time"
)

type processTree struct {
	mu  sync.Mutex
	pid int
}

func prepareProcessTree(cmd *exec.Cmd) (*processTree, error) {
	if cmd.SysProcAttr == nil {
		cmd.SysProcAttr = &syscall.SysProcAttr{}
	}
	cmd.SysProcAttr.Setpgid = true
	cmd.SysProcAttr.Pgid = 0
	return &processTree{}, nil
}

func (p *processTree) started(cmd *exec.Cmd) error { p.pid = cmd.Process.Pid; return nil }

func (p *processTree) terminate() error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.pid == 0 {
		return nil
	}
	err := syscall.Kill(-p.pid, syscall.SIGKILL)
	if errors.Is(err, syscall.ESRCH) {
		return nil
	}
	return err
}

func (p *processTree) retire() error {
	if err := p.terminate(); err != nil {
		return err
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	deadline := time.Now().Add(processReapTimeout)
	for p.pid != 0 {
		err := syscall.Kill(-p.pid, 0)
		if errors.Is(err, syscall.ESRCH) {
			p.pid = 0
			return nil
		}
		if err != nil {
			return err
		}
		if !time.Now().Before(deadline) {
			return errors.New("Codex owned process group cleanup unconfirmed")
		}
		time.Sleep(5 * time.Millisecond)
	}
	return nil
}

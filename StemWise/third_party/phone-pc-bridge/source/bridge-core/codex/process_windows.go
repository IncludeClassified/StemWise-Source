package codex

import (
	"errors"
	"os/exec"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

type processTree struct {
	mu  sync.Mutex
	job windows.Handle
}

func prepareProcessTree(cmd *exec.Cmd) (*processTree, error) {
	job, err := windows.CreateJobObject(nil, nil)
	if err != nil {
		return nil, err
	}
	var limits windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION
	limits.BasicLimitInformation.LimitFlags = windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
	if _, err := windows.SetInformationJobObject(job, windows.JobObjectExtendedLimitInformation, uintptr(unsafe.Pointer(&limits)), uint32(unsafe.Sizeof(limits))); err != nil {
		windows.CloseHandle(job)
		return nil, err
	}
	if cmd.SysProcAttr == nil {
		cmd.SysProcAttr = &syscall.SysProcAttr{}
	}
	// No child code may spawn descendants before membership is established.
	cmd.SysProcAttr.CreationFlags |= windows.CREATE_SUSPENDED
	return &processTree{job: job}, nil
}

func (p *processTree) started(cmd *exec.Cmd) error {
	process, err := windows.OpenProcess(windows.PROCESS_SET_QUOTA|windows.PROCESS_TERMINATE, false, uint32(cmd.Process.Pid))
	if err != nil {
		return err
	}
	err = windows.AssignProcessToJobObject(p.job, process)
	windows.CloseHandle(process)
	if err != nil {
		return err
	}
	snapshot, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPTHREAD, 0)
	if err != nil {
		return err
	}
	defer windows.CloseHandle(snapshot)
	entry := windows.ThreadEntry32{Size: uint32(unsafe.Sizeof(windows.ThreadEntry32{}))}
	for err = windows.Thread32First(snapshot, &entry); err == nil; err = windows.Thread32Next(snapshot, &entry) {
		if entry.OwnerProcessID != uint32(cmd.Process.Pid) {
			continue
		}
		thread, err := windows.OpenThread(windows.THREAD_SUSPEND_RESUME, false, entry.ThreadID)
		if err != nil {
			return err
		}
		_, err = windows.ResumeThread(thread)
		windows.CloseHandle(thread)
		return err
	}
	return errors.New("Codex suspended primary thread unavailable")
}

func (p *processTree) terminate() error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.job == 0 {
		return nil
	}
	return windows.TerminateJobObject(p.job, 1)
}

func (p *processTree) retire() error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.job == 0 {
		return nil
	}
	defer func() { windows.CloseHandle(p.job); p.job = 0 }()
	if err := windows.TerminateJobObject(p.job, 1); err != nil {
		return err
	}
	deadline := time.Now().Add(processReapTimeout)
	for {
		// JOBOBJECT_BASIC_ACCOUNTING_INFORMATION retains counts after parent exit.
		var info struct {
			TotalUserTime, TotalKernelTime, ThisPeriodUserTime, ThisPeriodKernelTime int64
			PageFaultCount, TotalProcesses, ActiveProcesses, TerminatedProcesses     uint32
		}
		if err := windows.QueryInformationJobObject(p.job, windows.JobObjectBasicAccountingInformation, uintptr(unsafe.Pointer(&info)), uint32(unsafe.Sizeof(info)), nil); err != nil {
			return err
		}
		if info.ActiveProcesses == 0 {
			return nil
		}
		if !time.Now().Before(deadline) {
			return errors.New("Codex owned process tree cleanup unconfirmed")
		}
		time.Sleep(5 * time.Millisecond)
	}
}

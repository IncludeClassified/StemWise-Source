"""Python adapter for the same Windows Job Object runner used by Run-Tester."""
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

BRIDGE = Path(__file__).with_name('run_owned_process.ps1')


class OwnedProcessError(RuntimeError):
    def __init__(self, result):
        super().__init__(result.get('message', 'Qualification process failed'))
        self.stdout = result.get('stdout', '')
        self.stderr = result.get('stderr', '')
        self.failure_kind = result.get('failureKind', 'infrastructure')


def _run_linux(command, *, timeout, check, cwd, output_log):
    import selectors
    from stemwise_engine.common import owned_process_tree
    streams = {'stdout': bytearray(), 'stderr': bytearray()}
    deadline = time.monotonic() + timeout
    from contextlib import nullcontext
    log_context = Path(output_log).open('ab') if output_log is not None else nullcontext(None)
    with log_context as log, owned_process_tree(command, cwd=cwd, stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE) as child:
        with selectors.DefaultSelector() as selected:
            selected.register(child.stdout, selectors.EVENT_READ, 'stdout')
            selected.register(child.stderr, selectors.EVENT_READ, 'stderr')
            while selected.get_map():
                if time.monotonic() >= deadline:
                    raise OwnedProcessError({'failureKind': 'timeout', 'message': 'Owned command timed out',
                        **{name: data.decode('utf-8', 'replace') for name, data in streams.items()}})
                for key, _ in selected.select(min(.1, max(0, deadline - time.monotonic()))):
                    block = os.read(key.fd, 65536)
                    if not block:
                        selected.unregister(key.fileobj)
                        continue
                    target = streams[key.data]
                    if log is not None:
                        log.write(('[' + key.data + '] ').encode('ascii') + block)
                        log.flush()
                        target.extend(block)
                        del target[:-1024 * 1024]
                    else:
                        remaining = 1024 * 1024 - len(target)
                        target.extend(block[:remaining])
                        if len(block) > remaining:
                            raise OwnedProcessError({'failureKind': 'output-limit', 'message': 'Owned command output limit',
                                **{name: data.decode('utf-8', 'replace') for name, data in streams.items()}})
            child.wait(timeout=max(.001, deadline - time.monotonic()))
    result = subprocess.CompletedProcess(command, child.returncode,
        streams['stdout'].decode('utf-8', 'replace'), streams['stderr'].decode('utf-8', 'replace'))
    if check:
        result.check_returncode()
    return result


def run(command, *, timeout, check=False, cwd=None, output_log=None):
    shell = shutil.which('powershell.exe') or shutil.which('pwsh.exe')
    via_wsl = os.name != 'nt' and str(command[0]).lower().endswith('.exe')
    if os.name != 'nt' and not via_wsl:
        return _run_linux(command, timeout=timeout, check=check, cwd=cwd, output_log=output_log)
    def windows_path(path):
        return subprocess.check_output(['wslpath', '-w', str(path)], text=True, timeout=10).strip() if via_wsl else str(path)
    child_command = [str(item) for item in command]
    if via_wsl and child_command[0].startswith('/'):
        child_command[0] = windows_path(child_command[0])
    request = base64.b64encode(json.dumps({'command': child_command,
        'cwd': windows_path(cwd or Path.cwd()), 'timeout': timeout,
        'outputLog': windows_path(output_log) if output_log is not None else None}).encode('utf-8')).decode('ascii')
    if not shell:
        raise OSError('Windows PowerShell is required for process ownership')
    # Killing this wrapper closes its noninherited Job handle and retires the owned tree.
    completed = subprocess.run([shell, '-NoProfile', '-NonInteractive', '-ExecutionPolicy',
        'Bypass', '-File', windows_path(BRIDGE), '-Request', request], capture_output=True,
        text=True, encoding='utf-8', timeout=timeout + 20)
    try:
        result = json.loads(completed.stdout)
    except ValueError as error:
        raise OwnedProcessError({'failureKind': 'infrastructure', 'message':
            'Owned runner returned invalid JSON', 'stdout': completed.stdout,
            'stderr': completed.stderr}) from error
    if result['failureKind'] or completed.returncode:
        raise OwnedProcessError(result)
    child = subprocess.CompletedProcess(command, result['exitCode'], result['stdout'], result['stderr'])
    if check:
        child.check_returncode()
    return child

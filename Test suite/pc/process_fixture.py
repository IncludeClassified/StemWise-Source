"""Exact process identity and bounded readiness for disposable test children."""
import ctypes
import os
from pathlib import Path
import queue
import signal
import shutil
import subprocess
import sys
import threading


_kernel = ctypes.WinDLL("kernel32",use_last_error=True) if os.name == "nt" else None


def _windows_birth(handle):
    created, exited, kernel, user = (ctypes.c_uint64() for _ in range(4))
    if not _kernel.GetProcessTimes(handle,ctypes.byref(created),ctypes.byref(exited),
                                                ctypes.byref(kernel),ctypes.byref(user)):
        raise ctypes.WinError()
    return created.value


def process_birth(pid):
    if os.name != 'nt':
        try:
            text = Path(f'/proc/{pid}/stat').read_text()
        except FileNotFoundError:
            return None
        return int(text[text.rfind(')')+2:].split()[19])
    library = _kernel
    library.OpenProcess.restype = ctypes.c_void_p
    handle = library.OpenProcess(0x1000,False,int(pid))
    if not handle:
        if ctypes.get_last_error() in (87,1168):
            return None
        raise ctypes.WinError()
    try:
        return _windows_birth(ctypes.c_void_p(handle))
    finally:
        library.CloseHandle(ctypes.c_void_p(handle))


def terminate_exact(pid, birth):
    if birth is None:
        return False
    birth = int(birth)
    if os.name != 'nt':
        from stemwise_engine.common import _linux_pidfd_call
        try:
            descriptor = _linux_pidfd_call('pidfd_open',int(pid),0)
        except ProcessLookupError:
            return False
        try:
            if process_birth(pid) != birth:
                return False
            try:
                _linux_pidfd_call('pidfd_send_signal',descriptor,signal.SIGKILL,0,0)
                return True
            except ProcessLookupError:
                return False
        finally:
            os.close(descriptor)
    library = _kernel
    library.OpenProcess.restype = ctypes.c_void_p
    handle = library.OpenProcess(0x101001,False,int(pid))  # synchronize + terminate
    if not handle:
        if ctypes.get_last_error() in (87,1168):
            return False
        raise ctypes.WinError()
    try:
        if _windows_birth(ctypes.c_void_p(handle)) != birth:
            return False
        if not library.TerminateProcess(ctypes.c_void_p(handle),1):
            raise ctypes.WinError()
        if library.WaitForSingleObject(ctypes.c_void_p(handle),5000) != 0:
            raise RuntimeError('Disposable test process did not retire')
        return True
    finally:
        library.CloseHandle(ctypes.c_void_p(handle))


def read_line(stream, timeout=10):
    result = queue.Queue(maxsize=1)
    def receive():
        try:
            result.put(stream.readline())
        except BaseException as error:
            result.put(error)
    threading.Thread(target=receive,daemon=True).start()
    try:
        value = result.get(timeout=timeout)
    except queue.Empty as error:
        raise TimeoutError('Disposable test child never became ready') from error
    if isinstance(value,BaseException):
        raise value
    return value


def _windows_control(method, pid, birth=None):
    from powershell_fixture import native_path
    shell = shutil.which('powershell.exe')
    if not shell:
        raise RuntimeError('Windows PowerShell is required for Windows process inspection')
    source = native_path(Path(__file__).resolve().parents[1]/'host/OwnedProcess.cs', shell=shell)
    values = str(int(pid)) + (',' + str(int(birth)) if birth is not None else '')
    script = "Add-Type -Path '" + source.replace("'","''") + "'; [StemWise.Qualification.OwnedProcess]::" + method + '(' + values + ')'
    from owned_process import run
    result = run([shell,'-NoProfile','-NonInteractive','-Command',script],
                 timeout=15,check=True)
    return result.stdout.strip()


def windows_birth(pid):
    return process_birth(pid) if os.name == 'nt' else int(_windows_control('Birth',pid))


def terminate_windows_exact(pid, birth):
    if not birth:
        return False
    return terminate_exact(pid,birth) if os.name == 'nt' else _windows_control('TerminateExact',pid,birth) == 'True'


def prepare_runtime_package(builder):
    """Give disposable launcher stubs a canonical package and the real lease owner."""
    import shlex
    builder = Path(builder)
    prepare_runtime_layout(builder)
    actual_source = Path(__file__).resolve().parents[2] / 'PC side/stemwise-playlist-builder/src'
    source = builder / 'src'
    source.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(actual_source / 'runtime_environment.py', source / 'runtime_environment.py')
    for name in ('runtime-bootstrap.ps1', 'command-spec.ps1'):
        shutil.copyfile(actual_source.parent / name, builder / name)
    def linux_path(path):
        if os.name != 'nt':
            return str(path)
        return subprocess.check_output(['wsl.exe', '-d', 'Ubuntu', '--', 'wslpath', '-a',
            str(path).replace('\\', '/')], text=True, timeout=15).strip()
    mapped = linux_path(builder)
    package = builder / '.fixture-packages/stemwise_engine'
    package.mkdir(parents=True)
    # Only this child sees the isolated entries; unchanged services keep their real sources.
    (package / '__init__.py').write_text(
        '__path__ = ' + repr([mapped + '/src', linux_path(actual_source)]) + '\n', encoding='utf-8')
    control = builder / '.tools/bootstrap/bin/python'
    control.parent.mkdir(parents=True, exist_ok=True)
    control.write_bytes(('#!/bin/sh\nexport PYTHONPATH=' + shlex.quote(mapped + '/.fixture-packages') +
        '\nexec ' + shlex.quote(mapped + '/.venv/bin/python') + ' "$@"\n').encode('utf-8'))
    control.chmod(0o755)


def prepare_runtime_layout(builder):
    """An isolated layout owns each disposable fixture's interpreter paths."""
    import json
    builder = Path(builder)
    original = Path(__file__).resolve().parents[2]/'PC side/runtime-layout.json'
    layout = json.loads(original.read_text(encoding='utf-8'))
    layout['environments'] = {'main': builder.name+'/.venv/bin/python',
        'allin1': builder.name+'/.venv-allin1/bin/python', 'lyrics': builder.name+'/.venv-lyrics-align/bin/python'}
    builder.parent.mkdir(parents=True, exist_ok=True)
    (builder.parent/'runtime-layout.json').write_text(json.dumps(layout), encoding='utf-8')

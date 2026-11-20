"""Private numerical workers live only for one synchronous alignment operation."""
from __future__ import annotations
import contextlib
import ctypes
from contextvars import ContextVar
import hashlib
import importlib
import importlib.util
import io
import json
import os
from pathlib import Path
import select
import signal
import socket
import struct
import subprocess
import sys
import threading
import time
import traceback
import uuid
from typing import Any
import stemwise_engine.runtime_environment as runtime_environment  # Adopt inherited leases before hashing the effective environment.

from stemwise_engine.common import (_PROCESS_LOCKS, _PROCESS_LOCK_LEASES_ENV, _verify_process_lock_descriptor,
                    owned_process_tree, run_process_tree, _IDLE_WORKER_RETIREMENT, raise_with_cleanup_failure)

_ACTIVE: ContextVar[Any] = ContextVar('stemwise_alignment_session', default=None)
_HELPERS = ('mms_forced_alignment.py', 'wav2vec2_forced_alignment.py')
_MODULES = tuple('stemwise_engine.' + Path(name).stem for name in _HELPERS)
_LIMIT = 1024 * 1024
_CAPTURE_LIMIT = 64 * 1024


def _digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def _file_identity(path):
    path = path.resolve(strict=True)
    stat = path.stat()
    return [str(path), stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns,
            stat.st_ctime_ns, hashlib.sha256(path.read_bytes()).hexdigest()]


def _leases():
    held = {descriptor: _verify_process_lock_descriptor(path, descriptor)
            for path, descriptor in _PROCESS_LOCKS.get()}
    return [[str(path), descriptor] for descriptor, path in sorted(held.items())]


def _binding(python, directory, environment):
    return {'interpreter': _file_identity(python),
            'sources': [_file_identity(directory / name) for name in
                        ('common.py', 'runtime_environment.py', 'alignment_worker_session.py',
                         'acoustic_inputs.py', 'ctc_worker.py', 'worker_protocol.py', 'worker_publication.py',
                         'private_diagnostics.py', *_HELPERS)],
            'environmentSha256': _digest(environment), 'leases': _leases()}


def _persistent_arguments(arguments):
    return (('--batch' in arguments) != ('--runtime-info' in arguments)
            and '--prepare-batch' not in arguments and '--audio' not in arguments)


def _send_frame(channel, value):
    raw = json.dumps(value, ensure_ascii=False, allow_nan=False,
                     separators=(',', ':')).encode()
    if not 0 < len(raw) <= _LIMIT:
        raise ValueError('Alignment session frame is too large')
    channel.sendall(struct.pack('!I', len(raw)) + raw)


def _read_frame(channel):
    def receive(size):
        result = bytearray()
        while len(result) < size:
            block = channel.recv(size - len(result))
            if not block:
                raise EOFError('Alignment session channel closed')
            result.extend(block)
        return bytes(result)
    size = struct.unpack('!I', receive(4))[0]
    if not 0 < size <= _LIMIT:
        raise ValueError('Invalid alignment session frame size')
    return json.loads(receive(size))


class _Capture(io.TextIOBase):
    def __init__(self, mirror=None): self.value, self.mirror = '', mirror
    def write(self, text):
        self.value = (self.value + text)[-_CAPTURE_LIMIT:]
        if self.mirror is not None:
            self.mirror.write(text)
            self.mirror.flush()
        return len(text)
    def flush(self): pass
    def writable(self): return True


class _Worker:
    def __init__(self, python, directory, environment, binding):
        self.python, self.directory, self.environment = python, directory, environment
        self.binding = binding
        self.session_id = uuid.uuid4().hex
        self.sequence = 0
        self.closed = False
        self.channel, child = socket.socketpair()
        self.owner = owned_process_tree([str(python), '-B', '-m', 'stemwise_engine.alignment_worker_session',
            '--serve', str(child.fileno())], env=environment, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, pass_fds=(child.fileno(),))
        retirement_token = _IDLE_WORKER_RETIREMENT.set(None)
        try:
            self.process = self.owner.__enter__()
        except BaseException:
            self.channel.close(); child.close()
            raise
        finally:
            _IDLE_WORKER_RETIREMENT.reset(retirement_token)
        child.close()
        self.native = {self.process.stdout: bytearray(), self.process.stderr: bytearray()}
        try:
            for stream in self.native: os.set_blocking(stream.fileno(), False)
            reply = self.exchange({'kind': 'initialize', 'sessionId': self.session_id,
                                   'binding': binding}, timeout=10)
            if reply != {'formatVersion': 1, 'sessionId': self.session_id, 'ready': True}:
                raise RuntimeError('Alignment session initialization differs')
        except BaseException:
            self.close(graceful=False)
            raise

    def _receive(self, size, deadline):
        output = bytearray()
        while len(output) < size:
            remaining = None if deadline is None else deadline - time.monotonic()
            if remaining is not None and remaining <= 0: raise subprocess.TimeoutExpired('alignment session', 0)
            streams = [self.channel, *(stream for stream in self.native if not stream.closed)]
            readable = select.select(streams, [], [], remaining)[0]
            if not readable: raise subprocess.TimeoutExpired('alignment session', 0)
            for stream in readable:
                if stream is self.channel:
                    block = self.channel.recv(size - len(output))
                    if not block: raise EOFError('Alignment numerical worker exited')
                    output.extend(block)
                else:
                    block = os.read(stream.fileno(), 65536)
                    if block:
                        self.native[stream].extend(block)
                        del self.native[stream][:-_CAPTURE_LIMIT]
                    else:
                        # EOF stays readable; retain the bounded tail but remove the fd.
                        stream.close()
        return bytes(output)

    def exchange(self, request, *, timeout):
        deadline = None if timeout is None else time.monotonic() + timeout
        self.channel.settimeout(None if timeout is None else max(.001, timeout))
        _send_frame(self.channel, request)
        self.channel.setblocking(False)
        size = struct.unpack('!I', self._receive(4, deadline))[0]
        if not 0 < size <= _LIMIT: raise RuntimeError('Invalid alignment session reply size')
        return json.loads(self._receive(size, deadline))

    def _native_output(self):
        for stream, tail in self.native.items():
            while not stream.closed:
                try: block = os.read(stream.fileno(), 65536)
                except BlockingIOError: break
                if not block:
                    stream.close()
                    break
                tail.extend(block)
                del tail[:-_CAPTURE_LIMIT]
        stdout = bytes(self.native[self.process.stdout]).decode('utf-8', errors='replace')
        stderr = bytes(self.native[self.process.stderr]).decode('utf-8', errors='replace')
        for tail in self.native.values(): tail.clear()
        return stdout, stderr

    def run(self, args, timeout):
        self.sequence += 1
        payload = {'formatVersion': 1, 'sessionId': self.session_id, 'requestId': self.sequence,
                   'arguments': args[2:], 'binding': self.binding}
        request_hash = _digest(payload)
        request = {**payload, 'kind': 'execute', 'requestSha256': request_hash}
        try:
            reply = self.exchange(request, timeout=timeout)
        except (subprocess.TimeoutExpired, TimeoutError) as error:
            stdout, stderr = self._native_output()
            raise subprocess.TimeoutExpired(args, timeout, output=stdout, stderr=stderr) from error
        except EOFError:
            self.process.wait(timeout=6)
            code = self.process.returncode
            if code == 125: raise RuntimeError('Owned command workers could not be retired')
            stdout, stderr = self._native_output()
            return subprocess.CompletedProcess(args, code or 1, stdout, stderr)
        required = {'formatVersion','sessionId','requestId','requestSha256','returncode','stdout','stderr'}
        if (not isinstance(reply, dict) or set(reply) != required or reply['formatVersion'] != 1
                or type(reply['formatVersion']) is not int
                or reply['sessionId'] != self.session_id or type(reply['requestId']) is not int
                or reply['requestId'] != self.sequence
                or reply['requestSha256'] != request_hash or type(reply['returncode']) is not int
                or not isinstance(reply['stdout'], str) or not isinstance(reply['stderr'], str)):
            raise RuntimeError('Alignment session reply binding is malformed or stale')
        native_stdout, native_stderr = self._native_output()
        return subprocess.CompletedProcess(args, reply['returncode'],
            native_stdout + reply['stdout'], native_stderr + reply['stderr'])

    def close(self, *, graceful=True):
        if self.closed: return
        self.closed = True
        try:
            if graceful and self.process.poll() is None:
                reply = self.exchange({'kind':'close','sessionId':self.session_id}, timeout=5)
                if reply != {'formatVersion':1,'sessionId':self.session_id,'closed':True}:
                    raise RuntimeError('Alignment session shutdown acknowledgement differs')
                self.process.wait(timeout=6)
        finally:
            self.channel.close()
            self.owner.__exit__(None, None, None)


class _Session:
    def __init__(self):
        self.workers = {}
        self.poisoned = False

    def close(self, *, graceful=True):
        failure = None
        for worker in self.workers.values():
            try: worker.close(graceful=graceful)
            except BaseException as error: failure = error
        self.workers.clear()
        if failure is not None:
            self.poisoned = True
            raise failure

    def retire_idle(self):
        if self.poisoned: raise RuntimeError('Alignment session is poisoned or closed')
        self.close()

    def run(self, args, *, timeout, **kwargs):
        if self.poisoned: raise RuntimeError('Alignment session is poisoned or closed')
        if threading.current_thread() is not threading.main_thread():
            raise RuntimeError('Alignment worker supervision requires the main thread')
        leases = _leases()
        if any(worker.binding['leases'] != leases for worker in self.workers.values()):
            self.close()
        if (sys.platform != 'linux' or any(Path(row[0]).name == 'stemwise-heavy-worker.lock' for row in leases)
                or len(args) < 4 or args[1] != '-m' or args[2] not in _MODULES
                or not _persistent_arguments(args[3:])
                or set(kwargs) - {'text','capture_output','check','env'}
                or kwargs.get('capture_output') is not True):
            return run_process_tree(args, timeout=timeout, **kwargs)
        python = Path(args[0]).absolute()
        helper = Path(importlib.util.find_spec(args[2]).origin).resolve(strict=True)
        environment = dict(os.environ if kwargs.get('env') is None else kwargs['env'])
        if _PROCESS_LOCK_LEASES_ENV in environment:
            raise ValueError('Reserved lease environment metadata cannot replace verified operation leases')
        binding = _binding(python, helper.parent, environment)
        # A session never carries an obsolete operation lease or changed imported source.
        for existing in self.workers.values():
            if existing.binding['leases'] != leases:
                self.close()
                break
            if existing.directory == helper.parent and existing.binding['sources'] != binding['sources']:
                self.poisoned = True
                self.close()
                raise RuntimeError('Alignment worker source identity changed during the operation')
        key = _digest(binding)
        try:
            if key not in self.workers:
                if self.workers:
                    oldest = next(iter(self.workers))
                    self.workers.pop(oldest).close()
                self.workers[key] = _Worker(python, helper.parent, environment, binding)
            worker = self.workers[key]
            completed = worker.run(args, timeout)
            if worker.process.poll() is not None or completed.returncode in (70,125):
                self.poisoned = True
                worker.close(graceful=False)
            if not kwargs.get('text'):
                completed.stdout, completed.stderr = completed.stdout.encode(), completed.stderr.encode()
            if kwargs.get('check'): completed.check_returncode()
            return completed
        except subprocess.CalledProcessError:
            raise
        except BaseException as failure:
            self.poisoned = True
            try:
                self.close(graceful=False)
            except BaseException as cleanup_error:
                raise_with_cleanup_failure(failure, cleanup_error)
            raise


def active_alignment_session():
    return _ACTIVE.get()


@contextlib.contextmanager
def alignment_worker_session():
    if _ACTIVE.get() is not None:
        yield _ACTIVE.get()
        return
    operation = _Session()
    token = _ACTIVE.set(operation)
    retirement_token = _IDLE_WORKER_RETIREMENT.set(operation.retire_idle)
    try: yield operation
    finally:
        failure = sys.exc_info()[1]
        _ACTIVE.reset(token)
        _IDLE_WORKER_RETIREMENT.reset(retirement_token)
        try:
            operation.close()
        except BaseException as cleanup_error:
            raise_with_cleanup_failure(failure, cleanup_error)


def run_alignment_process(args, *, timeout, **kwargs):
    operation = active_alignment_session()
    if operation is None: return run_process_tree(args, timeout=timeout, **kwargs)
    return operation.run(args, timeout=timeout, **kwargs)


def _serve(descriptor):
    channel = socket.socket(fileno=descriptor)
    initialization = _read_frame(channel)
    if (not isinstance(initialization,dict) or set(initialization) != {'kind','sessionId','binding'}
            or initialization['kind'] != 'initialize' or not isinstance(initialization['sessionId'],str)):
        raise ValueError('Invalid alignment session initialization')
    session_id, binding = initialization['sessionId'], initialization['binding']
    initial_environment = dict(os.environ)
    directory = Path(__file__).resolve().parent
    if binding != _binding(Path(sys.executable),directory,dict(os.environ)):
        raise ValueError('Alignment session source or environment identity differs')
    _send_frame(channel,{'formatVersion':1,'sessionId':session_id,'ready':True})
    sequence = 0
    while True:
        packet = _read_frame(channel)
        if packet == {'kind':'close','sessionId':session_id}:
            _send_frame(channel,{'formatVersion':1,'sessionId':session_id,'closed':True})
            return 0
        required = {'kind','formatVersion','sessionId','requestId','arguments','binding','requestSha256'}
        if (not isinstance(packet,dict) or set(packet) != required or packet['kind'] != 'execute'
                or type(packet['formatVersion']) is not int or packet['formatVersion'] != 1
                or packet['sessionId'] != session_id
                or type(packet['requestId']) is not int or packet['requestId'] != sequence+1
                or packet['binding'] != binding or not isinstance(packet['arguments'],list)
                or not packet['arguments'] or any(not isinstance(arg,str) for arg in packet['arguments'])
                or not _persistent_arguments(packet['arguments'][1:])
                or packet['arguments'][0] not in _MODULES):
            raise ValueError('Alignment session request binding differs')
        payload = {key:value for key,value in packet.items() if key not in ('kind','requestSha256')}
        if packet['requestSha256'] != _digest(payload): raise ValueError('Alignment session request hash differs')
        if binding != _binding(Path(sys.executable),directory,dict(os.environ)):
            raise ValueError('Alignment session source or lease identity changed')
        sequence += 1
        stdout,stderr = _Capture(),_Capture(sys.stderr)
        previous_argv = sys.argv
        try:
            sys.argv = packet['arguments']
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                try:
                    module = importlib.import_module(sys.argv[0])
                    from stemwise_engine.worker_protocol import run_worker_main
                    code = run_worker_main(module.main)
                except SystemExit as error:
                    code = error.code if type(error.code) is int else 1 if error.code else 0
                    if error.code and type(error.code) is not int: print(error.code,file=sys.stderr)
                except KeyboardInterrupt:
                    traceback.print_exc(); code = -signal.SIGINT
                except BaseException:
                    traceback.print_exc(); code = 1
        finally:
            sys.argv = previous_argv
            # Fresh CLI children discard library/import environment mutations too.
            for key in tuple(os.environ):
                if key not in initial_environment:
                    del os.environ[key]
            os.environ.update(initial_environment)
            # Return freed CPU buffers before another model uses the shared RAM.
            ctypes.CDLL(None).malloc_trim(0)
        if code is None: code = 0
        if type(code) is not int: raise ValueError('Alignment worker returned an invalid CLI status')
        if binding != _binding(Path(sys.executable),directory,dict(os.environ)):
            raise ValueError('Alignment session source or lease identity changed')
        _send_frame(channel,{'formatVersion':1,'sessionId':session_id,'requestId':sequence,
            'requestSha256':packet['requestSha256'],'returncode':code,'stdout':stdout.value,'stderr':''})


if __name__=='__main__':
    if len(sys.argv)!=3 or sys.argv[1]!='--serve': raise SystemExit('Private alignment worker entry point')
    raise SystemExit(_serve(int(sys.argv[2])))

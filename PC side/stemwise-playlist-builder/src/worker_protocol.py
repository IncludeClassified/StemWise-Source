"""Bounded current worker JSON and body-free failures shared by numerical adapters."""
from pathlib import Path
from stemwise_engine.common import WorkerRetirementError, parse_strict_json_bytes, read_bounded_regular_bytes
from stemwise_engine.private_diagnostics import retain_operational, bounded_utf8_tail
from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline, ProviderStageDeadline

MAX_WORKER_JSON_BYTES = 16*1024*1024
_WORKER_STOP_TYPES = {'cancelled': ProviderCancelled, 'parent-deadline': ProviderDeadline,
                      'retirement-failed': WorkerRetirementError}


class MissingAlignmentAudio(FileNotFoundError):
    """A cache miss needs its exact acoustic input, rather than a dependency repair."""


class WorkerProtocolError(RuntimeError):
    def __init__(self, code, diagnostics=()):
        super().__init__(code)
        self.code = code
        self.diagnostics = tuple(item for item in diagnostics if 'file' in item)
        self.private_causes = tuple(item for item in diagnostics if 'body' in item)
        self.private_diagnostics = self.diagnostics


class WorkerReasonError(ValueError):
    """A numerical producer names its recovery code independently of diagnostic wording."""
    def __init__(self, code, message=None):
        if not valid_failure_code(code):
            raise ValueError('Invalid worker failure code')
        self.reason_code = code
        super().__init__(message or code)


def valid_failure_code(code):
    import re
    return isinstance(code, str) and re.fullmatch(r'[a-z][a-z0-9_-]*(?::[a-z0-9_-]+){0,3}', code) is not None and len(code) <= 160


def emit_worker_failure(error, *, code=None):
    import json
    import sys
    if code is None:
        code = (error.reason_code if isinstance(error, WorkerReasonError)
            else error.code if isinstance(error, WorkerProtocolError) else None)
        if code is None:
            from stemwise_engine.acoustic_inputs import AcousticBudgetExceeded
            if isinstance(error, AcousticBudgetExceeded):
                code = str(error)
        torch = sys.modules.get('torch')
        oom = getattr(getattr(torch, 'cuda', None), 'OutOfMemoryError', None)
        if isinstance(oom, type) and isinstance(error, oom):
            code = 'cuda-out-of-memory'
    disposition = ('retirement-failed' if isinstance(error, WorkerRetirementError) else
                   'cancelled' if isinstance(error, ProviderCancelled) else
                   'parent-deadline' if isinstance(error, ProviderDeadline)
                       and not isinstance(error, ProviderStageDeadline) else None)
    if code is None and disposition is not None:
        code = {'cancelled': 'provider-operation-cancelled', 'parent-deadline': 'provider-operation-deadline',
                'retirement-failed': 'alignment-model-retirement-failed'}[disposition]
    if not valid_failure_code(code):
        code = 'process-exit'
    packet = {'formatVersion': 1, 'code': code}
    if disposition is not None:
        packet['disposition'] = disposition
    print('STEMWISE_WORKER_FAILURE='+json.dumps(packet, separators=(',', ':')),
        file=sys.stderr, flush=True)


def run_worker_main(main):
    import traceback
    from stemwise_engine.common import WorkerRetirementError
    try:
        return main()
    except WorkerRetirementError:
        fatal_model_failure('alignment-model-retirement-failed')
    except Exception as error:
        traceback.print_exc()
        emit_worker_failure(error)
        return 1


def parse_worker_json(data, *, max_bytes=MAX_WORKER_JSON_BYTES):
    if isinstance(data, str):
        data = data.encode('utf-8')
    if not isinstance(data, bytes) or len(data) > max_bytes:
        raise WorkerProtocolError('worker-json-size-invalid')
    try:
        return parse_strict_json_bytes(data)
    except ValueError as error:
        raise WorkerProtocolError('worker-json-invalid') from error


def read_worker_json(path: Path, *, max_bytes=MAX_WORKER_JSON_BYTES):
    return parse_worker_json(read_bounded_regular_bytes(path, max_bytes=max_bytes, root=path.parent), max_bytes=max_bytes)


def _worker_failure_packet(completed):
    raw = completed.stderr or b''
    stderr = raw.decode('utf-8', errors='replace') if isinstance(raw, bytes) else str(raw)
    last = next((line for line in reversed(stderr.splitlines()) if line.strip()), '')
    prefix = 'STEMWISE_WORKER_FAILURE='
    if last.startswith(prefix):
        try:
            packet = parse_worker_json(last[len(prefix):], max_bytes=2048)
            if (isinstance(packet, dict) and {'formatVersion', 'code'} <= set(packet)
                    <= {'formatVersion', 'code', 'disposition'}
                    and type(packet['formatVersion']) is int and packet['formatVersion'] == 1
                    and valid_failure_code(packet['code'])
                    and ('disposition' not in packet or isinstance(packet['disposition'], str)
                         and packet['disposition'] in _WORKER_STOP_TYPES)):
                return packet
        except WorkerProtocolError:
            pass
    return None


def raise_if_worker_stopped(completed, *, code, diagnostic_path):
    packet = _worker_failure_packet(completed)
    if completed.returncode == 70 or packet is not None and packet.get('disposition') in _WORKER_STOP_TYPES:
        raise_worker_failure(code, completed, diagnostic_path, diagnostic_path=diagnostic_path)


def require_worker_success(completed, *, code, diagnostic_path):
    raise_if_worker_stopped(completed, code=code, diagnostic_path=diagnostic_path)
    if completed.returncode == 0:
        return
    body = completed.stderr.encode('utf-8') if isinstance(completed.stderr, str) else completed.stderr or b''
    error = WorkerProtocolError(code, (retain_operational(diagnostic_path, body),))
    error.stderr = bounded_utf8_tail(body, WORKER_FAILURE_DIAGNOSTIC_BYTES)
    raise error

import hashlib
import json
import re
import traceback
import os
import sys
from typing import Any
WORKER_FAILURE_DIAGNOSTIC_BYTES = 64 * 1024


def fatal_model_failure(code: str) -> None:
    """Flush the active cause before the kernel retires a failed model owner."""
    try:
        traceback.print_exc(file=sys.stderr)
        emit_worker_failure(sys.exc_info()[1], code=code)
    finally:
        os._exit(70)

def raise_worker_failure(
    label: str,
    completed: Any,
    output_path: Path,
    *,
    diagnostic_path: Path | None = None,
) -> None:
    """Keep a bounded private worker diagnostic without exposing it through summaries."""

    raw_stderr = completed.stderr or b''
    stderr = raw_stderr.decode('utf-8', errors='replace') if isinstance(raw_stderr, bytes) else str(raw_stderr)
    diagnostic_path = diagnostic_path or output_path.with_name(
        f"{output_path.stem}.failure.private.txt"
    )
    code = "process-exit" if completed.returncode != 0 else "missing-output"
    packet = _worker_failure_packet(completed)
    if packet is not None and (completed.returncode != 0 or packet.get('disposition') in _WORKER_STOP_TYPES):
        code = packet['code']
    if completed.returncode == 70:
        error = WorkerRetirementError(f'{label} ({code})')
        error.code, error.returncode = code, completed.returncode
    elif packet is not None and packet.get('disposition') in _WORKER_STOP_TYPES:
        error = _WORKER_STOP_TYPES[packet['disposition']](f'{label} ({code})')
        error.code, error.returncode = code, completed.returncode
    else:
        error = WorkerProcessError(label, code)
    error.stderr = bounded_utf8_tail(stderr.encode('utf-8', errors='replace'), WORKER_FAILURE_DIAGNOSTIC_BYTES)
    try:
        cause = retain_operational(diagnostic_path, error.stderr, limit=WORKER_FAILURE_DIAGNOSTIC_BYTES)
    except Exception as diagnostic_error:
        from stemwise_engine.common import raise_with_cleanup_failure
        raise_with_cleanup_failure(error, diagnostic_error)
    error.diagnostics = error.private_diagnostics = (cause,) if 'file' in cause else ()
    error.private_causes = (cause,) if 'body' in cause else ()
    raise error


class WorkerProcessError(WorkerProtocolError):
    """A private worker failed with a body-free diagnostic code."""

    def __init__(self, label: str, code: str):
        super().__init__(code)
        self.args = (f'{label} ({code})',)


def technical_diagnostic_code(error: Exception, diagnostic_path: Path | None = None, *, context=None) -> str:
    from stemwise_engine.private_diagnostics import DiagnosticCode, retain_operational
    diagnostics = list(getattr(error, 'private_diagnostics', ())) + list(getattr(error, 'private_causes', ()))
    trace_identity = None
    if diagnostic_path is not None:
        header = (json.dumps(context, sort_keys=True) + "\n" if context else "").encode('utf-8')
        from stemwise_engine.private_diagnostics import error_record
        detail = error_record(error, limit=WORKER_FAILURE_DIAGNOSTIC_BYTES)['body'].encode('utf-8')
        retained = retain_operational(diagnostic_path,
            header + detail[-(WORKER_FAILURE_DIAGNOSTIC_BYTES-len(header)):], limit=WORKER_FAILURE_DIAGNOSTIC_BYTES)
        trace_identity = retained if 'sha256' in retained else None
        diagnostics.append(retained)
    if (isinstance(error, WorkerProtocolError)
            or isinstance(error, (WorkerRetirementError, ProviderCancelled, ProviderDeadline))
               and valid_failure_code(getattr(error, 'code', None))):
        code = error.code
    else:
        code = re.sub(r"[^A-Za-z0-9_.:+-]+", "-", type(error).__name__).strip("-")
        code = code[:32] or 'TechnicalFailure'
        code = code + ':sha256:' + trace_identity['sha256'] if trace_identity else code
    value = DiagnosticCode(code, [item for item in diagnostics if 'file' in item])
    value.private_causes = tuple(item for item in diagnostics if 'body' in item)
    return value

"""Bounded private causes for numerical routes and alignment attempts."""
import hashlib
import traceback
import re
from contextlib import contextmanager
from contextvars import ContextVar
from pathlib import Path

from stemwise_engine.common import atomic_write_bytes, read_bounded_regular_bytes
from stemwise_engine.common import diagnostic_code

_ANALYSIS_DIAGNOSTICS = ContextVar('analysis_diagnostics', default=None)


class DiagnosticCode(str):
    """A body-free code carrying private retained causes to the attempt ledger."""
    def __new__(cls, code, diagnostics=()):
        value = super().__new__(cls, code)
        value.diagnostics = tuple(diagnostics)
        return value


def with_stderr(error, stderr):
    error.stderr = stderr
    return error


def emit_analysis_stage_failure(error, index, component):
    import json
    import sys
    try:
        detail = ''.join(traceback.format_exception(error)).encode('utf-8', errors='replace')
        stderr = getattr(error, 'stderr', None)
        if stderr:
            raw = stderr if isinstance(stderr, bytes) else str(stderr).encode('utf-8', errors='replace')
            detail = detail[-6000:] + b'\nChild stderr:\n' + raw[-6000:]
        row = {'index': index, 'component': component, 'errorType': diagnostic_code(error),
               'diagnostic': detail[-12000:].decode('utf-8', errors='ignore')}
        sys.stderr.write('STEMWISE_ACOUSTIC_FAILURE=' + json.dumps(row, separators=(',', ':')) + '\n')
        sys.stderr.flush()
    except Exception:
        pass  # Cause capture cannot change another song's numerical result.


def retain_allin1_failure(error, work, item):
    detail = error_record(error)['body']
    child = work / ('demix-failure-' + str(item['index']) + '.private.txt')
    try:
        if child.is_file():
            detail = detail[-6000:] + '\nDemix cause:\n' + read_bounded_regular_bytes(child, max_bytes=12000, root=work).decode('utf-8', errors='replace')[-6000:]
    except (OSError, ValueError):
        pass
    payload = bounded_utf8_tail(detail.encode('utf-8'), 12000).decode('utf-8')
    _retain_batch_failure(error, work / ('failure-' + str(item['index']) + '.private.txt'), payload, item['index'])


def retain_demix_failure(error, work, item):
    detail = error_record(error)['body']
    payload = bounded_utf8_tail(detail.encode('utf-8'), 12000).decode('utf-8')
    _retain_batch_failure(error, work / ('demix-failure-' + str(item['index']) + '.private.txt'), payload, item['index'])


def _retain_batch_failure(error, path, payload, index):
    error.stderr = payload.encode('utf-8')
    try:
        atomic_write_bytes(path, error.stderr, root=path.parent)
    except OSError:
        emit_analysis_stage_failure(error, index, 'allin1')


def error_record(error, *, limit=12000):
    detail = ''.join(traceback.format_exception(type(error), error, error.__traceback__))
    cause, seen, stderr = error, set(), None
    while cause is not None and id(cause) not in seen:
        seen.add(id(cause))
        stderr = getattr(cause, 'stderr', None) or stderr
        cause = cause.__cause__ or (None if cause.__suppress_context__ else cause.__context__)
    if stderr:
        if isinstance(stderr, bytes):
            stderr = stderr.decode('utf-8', errors='replace')
        detail = detail[-limit//2:]+'\nChild stderr:\n'+str(stderr)[-limit//2:]
    return {'errorType': diagnostic_code(error),
        'body': bounded_utf8_tail(detail.encode('utf-8'), limit).decode('utf-8')}


def failure_from_file(code, path, fallback):
    if not path.exists():
        return DiagnosticCode(code, getattr(fallback, 'diagnostics', ()))
    try:
        body = read_bounded_regular_bytes(path, max_bytes=12000, root=path.parent).decode('utf-8', errors='replace')
    except (OSError, ValueError):
        return DiagnosticCode(code, getattr(fallback, 'diagnostics', ()))
    return DiagnosticCode(code, [{'errorType': str(code), 'body': body}])


def attach_analysis_causes(analysis):
    causes = [cause for tool in analysis['toolRuns']
        for value in (tool.get('reason'), *tool.get('warnings', []))
        for cause in getattr(value, 'diagnostics', ())]
    if causes:
        analysis['_privateDiagnostics'] = causes


@contextmanager
def analysis_diagnostics(root, *, workspace_root):
    from stemwise_engine.common import validate_generated_path
    root = validate_generated_path(Path(workspace_root), Path(root), workspace_root=Path(workspace_root))
    detail = {}
    token = _ANALYSIS_DIAGNOSTICS.set((root, Path(workspace_root), detail))
    try:
        yield detail
    finally:
        _ANALYSIS_DIAGNOSTICS.reset(token)


def harvest_analysis_causes(analysis):
    key_evidence = analysis.pop('_privateKeyEvidence', None)
    if key_evidence is not None:
        import json
        import math
        from stemwise_engine.contract import MAX_JSON_BYTES
        if (not isinstance(key_evidence, dict) or set(key_evidence) != {'formatVersion', 'skey', 'tonal'}
                or type(key_evidence['formatVersion']) is not int or key_evidence['formatVersion'] != 1
                or any(not isinstance(key_evidence[name], dict)
                       or key_evidence[name].get('status') not in {'candidate', 'unavailable'}
                       for name in ('skey', 'tonal'))):
            raise ValueError('Private key evidence is invalid')
        scores = key_evidence['skey'].get('classScores')
        if key_evidence['skey'].get('status') == 'candidate' and (
                not isinstance(scores, list) or len(scores) != 24
                or any(not isinstance(row, dict) or set(row) != {'classIndex', 'label', 'rawScore'}
                       or type(row['classIndex']) is not int or row['classIndex'] != index
                       or not isinstance(row['label'], str) or not row['label'].strip()
                       or type(row['rawScore']) not in (int, float) or not math.isfinite(row['rawScore'])
                       or row['rawScore'] < 0 for index, row in enumerate(scores))):
            raise ValueError('Private key class scores are invalid')
        if len(json.dumps(key_evidence, allow_nan=False).encode('utf-8')) > MAX_JSON_BYTES:
            raise ValueError('Private key evidence is too large')
        owner = _ANALYSIS_DIAGNOSTICS.get()
        if owner is None:
            raise ValueError('Private key evidence requires its current job owner')
        owner[2]['keyEvidence'] = key_evidence
    causes = analysis.pop('_privateDiagnostics', [])
    if not causes:
        return
    if not isinstance(causes, list) or len(causes) > 32:
        raise ValueError('Private acoustic causes are invalid')
    owner = _ANALYSIS_DIAGNOSTICS.get()
    if owner is None:
        raise ValueError('Private acoustic causes require their current job owner')
    root, workspace, detail = owner
    for cause in causes:
        if (not isinstance(cause, dict) or set(cause) != {'errorType', 'body'}
                or not isinstance(cause['errorType'], str)
                or re.fullmatch(r'[A-Za-z0-9_.:+-]{1,160}', cause['errorType']) is None
                or not isinstance(cause['body'], str) or len(cause['body'].encode('utf-8')) > 12000):
            raise ValueError('Private acoustic cause is malformed')
        try:
            root.mkdir(parents=True, exist_ok=True)
            path, identity = retain(root/'acoustic.failure.private.txt', cause['body'].encode('utf-8'))
            row = {**reference(path, identity), 'errorType': cause['errorType']}
            row['file'] = path.relative_to(workspace).as_posix()
            if row not in detail.setdefault('privateDiagnostics', []):
                detail['privateDiagnostics'].append(row)
        except (OSError, ValueError) as error:
            detail['diagnosticError'] = diagnostic_code(error)


def bounded_utf8_tail(body: bytes, limit: int = 12000) -> bytes:
    # Preserve complete characters after byte bounding, including a partial child stderr tail.
    if type(limit) is not int or limit <= 0:
        raise ValueError('Private diagnostic byte limit must be positive')
    normalized = body.decode('utf-8', errors='replace').encode('utf-8')
    return normalized[-limit:].decode('utf-8', errors='ignore').encode('utf-8')


def retain(path: Path, body: bytes, *, limit: int = 12000) -> tuple[Path, dict]:
    payload = bounded_utf8_tail(body, limit)
    digest = hashlib.sha256(payload).hexdigest()
    target = path.with_name(f'{path.stem}.{digest}{path.suffix}')
    if target.exists():
        if read_bounded_regular_bytes(target, max_bytes=limit, root=path.parent) != payload:
            raise ValueError('Private failure diagnostic identity changed')
    else:
        atomic_write_bytes(target, payload, root=path.parent)
    return target, {'sha256': digest, 'byteCount': len(payload)}


def retain_operational(path: Path, body: bytes, *, limit: int = 12000):
    """Optional cause capture must not replace the operation's result or error."""
    payload = bounded_utf8_tail(body, limit)
    try:
        retained, identity = retain(path, payload, limit=limit)
        return reference(retained, identity)
    except (OSError, ValueError) as error:
        return {'body': payload.decode('utf-8'), 'retentionError': diagnostic_code(error)}


def retain_error(path: Path, error: BaseException, *, context=None) -> dict:
    import json
    header = ((json.dumps(context, sort_keys=True) + '\n') if context else '').encode('utf-8')
    body = error_record(error)['body'].encode('utf-8')
    retained, identity = retain(path, header + body[-(12000-len(header)):])
    return reference(retained, identity)


def reference(path, identity):
    return {'file': path.name, **identity}


def validate_references(references):
    if (not isinstance(references, list) or not 1 <= len(references) <= 2
            or any(not isinstance(item, dict) or set(item) != {'file', 'sha256', 'byteCount'}
                or not isinstance(item['file'], str) or Path(item['file']).name != item['file']
                or re.fullmatch('[A-Za-z0-9_.-]{1,240}', item['file']) is None
                or not isinstance(item['sha256'], str) or re.fullmatch('[0-9a-f]{64}', item['sha256']) is None
                or type(item['byteCount']) is not int or not 0 <= item['byteCount'] <= 64*1024
                for item in references)):
        raise ValueError('Private diagnostic identity is invalid')
    return [dict(item) for item in references]


def preserve_failure(path: Path, error: BaseException, stderr=None, *, limit: int = 12000) -> None:
    try:
        _preserve_failure(path, error, stderr, limit=limit)
    except (OSError, ValueError):
        if stderr is not None:
            with_stderr(error, stderr)
        error.stderr = error_record(error, limit=limit)['body'].encode('utf-8')


def _preserve_failure(path: Path, error: BaseException, stderr=None, *, limit: int = 12000) -> None:
    """Keep the child's exact attempt cause; a generic parent must not replace it."""
    if path.exists():
        read_bounded_regular_bytes(path, max_bytes=limit, root=path.parent)
        return
    detail = ''.join(traceback.format_exception(type(error), error, error.__traceback__)).encode('utf-8')
    if isinstance(stderr, str):
        stderr = stderr.encode('utf-8', errors='replace')
    if isinstance(stderr, bytes) and stderr:
        prefix = detail[-limit//2:] + b'\nChild stderr:\n'
        detail = prefix + stderr[-(limit-len(prefix)):]
    payload = detail[-limit:].decode('utf-8', errors='ignore').encode('utf-8')
    atomic_write_bytes(path, payload, root=path.parent)

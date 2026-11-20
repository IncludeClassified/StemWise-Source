"""Bound unreferenced local diagnostics while preserving current recovery chains."""
from contextlib import nullcontext, contextmanager, ExitStack
import errno
from pathlib import Path
import re
import shutil
import subprocess

from stemwise_engine.common import (exclusive_process_lock, process_lock_descriptor, read_bounded_json,
                    atomic_write_json, sha256_file, utc_now, validate_generated_path)
from stemwise_engine.common import resolve_path
from stemwise_engine.workflow_context import current_state_root, native_state_root

MAX_UNREFERENCED_COUNT = 128
MAX_UNREFERENCED_BYTES = 32 * 1024 * 1024
MAX_TOTAL_UNREFERENCED_BYTES = 256 * 1024 * 1024
JSON_LIMIT = 16 * 1024 * 1024


def _launcher_alive(launch):
    """Inspect the exact Windows birth; inability to inspect never proves death."""
    process_id, birth = launch.get('processId'), launch.get('processStartTimeUtc')
    if type(process_id) is not int or process_id <= 0 or not isinstance(birth, str):
        return None
    if re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}'
                    r'(?:\.[0-9]{1,7})?(?:Z|[+-][0-9]{2}:[0-9]{2})', birth) is None:
        return None
    powershell = shutil.which('powershell.exe')
    if powershell is None:
        return None
    # Keep all seven .NET birth digits; Python 3.10 cannot parse that precision.
    script = """$ErrorActionPreference='Stop'
try { $target=[Diagnostics.Process]::GetProcessById(%d) }
catch [ArgumentException] { [Console]::Out.Write('dead'); exit 0 }
$expected=[DateTimeOffset]'%s'
if ([DateTimeOffset]$target.StartTime.ToUniversalTime() -eq $expected) {
    [Console]::Out.Write('live')
} else { [Console]::Out.Write('dead') }
""" % (process_id, birth.replace("'", "''"))
    try:
        result = subprocess.run([powershell, '-NoProfile', '-NonInteractive', '-Command', script],
            capture_output=True, text=True, check=False, timeout=15)
    except subprocess.TimeoutExpired as error:
        raise ValueError('Exact launcher process inspection exceeded its deadline') from error
    if result.returncode != 0 or result.stdout not in {'live', 'dead'}:
        raise ValueError('Exact launcher process identity could not be inspected')
    return result.stdout == 'live'


def _interrupted_launch(directory, source_root, *, apply):
    launches = list(directory.glob('*.launch.json'))
    if len(launches) != 1:
        return False
    launch = read_bounded_json(launches[0], max_bytes=65536, root=source_root)
    if (not isinstance(launch, dict) or launch.get('formatVersion') != 3
            or launch.get('operationId') != directory.name or launch.get('launchState') != 'launched'):
        return False
    if _launcher_alive(launch) is not False:
        return False
    interrupted = launches[0].with_name(launches[0].name.replace('.launch.json', '.interrupted.json'))
    if interrupted.exists():
        record = read_bounded_json(interrupted, max_bytes=65536, root=source_root)
        return isinstance(record, dict) and isinstance(record.get('observedAt'), str) and record == {
            'formatVersion': 1, 'operationId': directory.name, 'status': 'interrupted',
            'reason': 'exact-supervisor-not-active', 'processId': launch.get('processId'),
            'processStartTimeUtc': launch.get('processStartTimeUtc'), 'observedAt': record.get('observedAt')}
    if apply:
        atomic_write_json(interrupted, {'formatVersion': 1, 'operationId': directory.name,
            'status': 'interrupted', 'reason': 'exact-supervisor-not-active',
            'processId': launch['processId'], 'processStartTimeUtc': launch['processStartTimeUtc'],
            'observedAt': utc_now()})
    return True


def _source_root(workspace, path):
    return next((parent for parent in (path, *path.parents)
                 if parent.parent == workspace/'Engine telemetry'), None)


@contextmanager
def publication(workspace: Path, path: Path):
    source = _source_root(workspace, path)
    if source is None:
        yield
        return
    lock = source/'retention.lock'
    with (nullcontext() if process_lock_descriptor(lock) is not None else exclusive_process_lock(lock)):
        yield


def _strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for item in value.values():
            yield from _strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from _strings(item)


def _authority_jobs(workspace, source):
    builder = workspace/'PC side/stemwise-playlist-builder'
    config = read_bounded_json(builder/'config.json', max_bytes=JSON_LIMIT, root=workspace)
    future = validate_generated_path(workspace,
        resolve_path(config['futureRootWindows'], base=builder), workspace_root=workspace)
    pc = validate_generated_path(workspace,
        resolve_path(config['pcSideRootWindows'], base=builder), workspace_root=workspace)
    if source.startswith('youtube-'):
        video = source[len('youtube-'):]
        paths = [current_state_root(future)/'jobs'/video/'job.json']
    else:
        track = source[len('qobuz-'):]
        roots = [native_state_root(future, {'trackId': track, 'sha256': path.name})
                 for path in (pc/'generated-packages/recording-analysis'/source).glob('*')
                 if re.fullmatch('[0-9a-f]{64}', path.name)]
        # A workflow may exist before numerical analysis has published its cache.
        roots.extend(path for path in (future/'policy-state').glob(f'current-qobuz-{track}-*')
                     if re.fullmatch(f'current-qobuz-{track}-[0-9a-f]{{64}}', path.name))
        paths = [path for root in set(roots) for path in root.glob('jobs/*/job.json')]
        paths.extend((pc/'generated-packages/recording-analysis'/source).glob('*/*/job.json'))
    return [path for path in paths if path.exists()]


def _files(path, root):
    if path.is_dir():
        result = []
        for child in path.iterdir():
            result.extend(_files(validate_generated_path(root, child), root))
        return result
    validate_generated_path(root, path)
    if not path.is_file():
        raise ValueError('Telemetry contains an unsupported entry')
    return [path]


def cleanup_source(workspace: Path, source_root: Path, *, apply=True,
                   max_count=MAX_UNREFERENCED_COUNT, max_bytes=MAX_UNREFERENCED_BYTES) -> dict:
    """Delete only completed, unreferenced diagnostic units; failure preserves admission."""
    workspace = workspace.resolve()
    source_root = validate_generated_path(workspace/'Engine telemetry', source_root, workspace_root=workspace)
    if (re.fullmatch(r'(?:youtube-[A-Za-z0-9_-]{11}|qobuz-[1-9][0-9]*)', source_root.name) is None
            or any(type(value) is not int or value < 0 for value in (max_count, max_bytes))):
        raise ValueError('Telemetry retention identity or budget is invalid')
    if not source_root.exists():
        return {'unreferencedCount': 0, 'unreferencedBytes': 0, 'removedCount': 0, 'removedBytes': 0}
    with publication(workspace, source_root):
        jobs = _authority_jobs(workspace, source_root.name)
        return _cleanup_locked(workspace, source_root, jobs, apply, max_count, max_bytes)


def _cleanup_locked(workspace, source_root, jobs, apply, max_count, max_bytes, inventory=None):
    from stemwise_engine.workflow_records import operation_record_is_safe
    candidates = []
    interrupted_launches = set()
    run_records = {path: read_bounded_json(path, max_bytes=JSON_LIMIT, root=source_root)
                   for path in source_root.glob('**/runs/*/run.json')}
    completed_operations = {record.get('operationId') for record in run_records.values()
        if isinstance(record, dict) and operation_record_is_safe(record)
        and record.get('phase') == 'outcome' and record.get('status') in {'complete', 'failed', 'interrupted'}}
    for path in source_root.rglob('*'):
        validate_generated_path(source_root, path, workspace_root=workspace)
        if path.is_dir() and path.parent.name == 'runs' and re.fullmatch('[0-9a-f]{32}', path.name):
            if (path/'run.json').is_file():
                record = run_records[path/'run.json']
                if isinstance(record, dict) and (record.get('status') in {'complete', 'failed', 'interrupted'}
                        or record.get('status') == 'started' and operation_record_is_safe(record)
                        and record.get('operationId') in completed_operations):
                    candidates.append(path)
        elif path.is_dir() and path.parent.name == 'operations' and re.fullmatch('[0-9a-f]{32}', path.name):
            completions = list(path.glob('*.completion.json'))
            if len(completions) == 1:
                completion = read_bounded_json(completions[0], max_bytes=65536, root=source_root)
                if (isinstance(completion, dict) and completion.get('operationId') == path.name
                        and type(completion.get('exitCode')) is int and completion.get('completedAt')):
                    candidates.append(path)
            elif not completions and _interrupted_launch(path, source_root, apply=apply):
                candidates.append(path)
                interrupted_launches.add(path)
        elif path.is_file() and path.parent.name == 'runs' and (
                re.fullmatch(r'[0-9a-f]{32}(?:\.failure)?\.json', path.name)):
            record = read_bounded_json(path, max_bytes=JSON_LIMIT, root=source_root)
            if isinstance(record, dict) and (record.get('status') in {'complete', 'failed', 'interrupted'}
                    or path.name.endswith('.failure.json') and isinstance(record.get('failedAt'), str)
                    and isinstance(record.get('errorType'), str)):
                candidates.append(path)
        elif path.is_file() and (re.search(r'\.[0-9a-f]{64}\.(?:txt|log)$', path.name)
                                or re.fullmatch(r'[0-9a-f]{32}\.failure\.private\.txt', path.name)):
            candidates.append(path)
    # A run/launcher unit owns its children; never count or remove them twice.
    candidates = [path for path in candidates if not any(parent in candidates for parent in path.parents)]
    owned = {path: _files(path, source_root) for path in candidates}
    file_owner = {file: path for path, files in owned.items() for file in files}
    aliases = {}
    def alias(token, owner):
        aliases.setdefault(token.replace('\\', '/'), set()).add(owner)
    for path, files in owned.items():
        for item in (path, *files):
            for name in (str(item.resolve()), item.relative_to(workspace).as_posix(),
                         item.relative_to(source_root).as_posix()):
                alias(name, path)
            for token in re.findall(r'(?<![0-9a-f])[0-9a-f]{32}(?:[0-9a-f]{32})?(?![0-9a-f])', item.name):
                alias(token, path)
            if item.is_file() and item.suffix == '.json':
                alias(sha256_file(item), path)
                record = run_records.get(item)
                if isinstance(record, dict) and isinstance(record.get('operationId'), str):
                    alias(record['operationId'], path)
    pending = [path for path in source_root.rglob('*.json') if path not in file_owner]
    pending.extend(jobs)
    # A live review declaration can point to an older run/artwork repair chain.
    for path in jobs:
        job = read_bounded_json(path, max_bytes=JSON_LIMIT, root=workspace)
        if not isinstance(job, dict):
            raise ValueError('Telemetry authority job is malformed')
        for field, directory in (('endpointReview', 'endpoint-review'), ('displayReview', 'display-review')):
            attempt = job.get(field, {})
            if isinstance(attempt, dict) and re.fullmatch('[0-9a-f]{32}', str(attempt.get('attemptId', ''))):
                review = path.parent/directory/attempt['attemptId']
                if review.exists():
                    pending.extend(review.rglob('*.json'))
        pipeline = path.parents[2]/'runtime/work/checkpoints'/f"{job.get('videoId', '')}.json"
        if pipeline.is_file():
            pending.append(pipeline)
    protected, seen = set(), set()
    launches = [path for path in candidates if path.is_dir() and path.parent.name == 'operations'
                and path not in interrupted_launches]
    if launches:
        latest = max(launches, key=lambda path: path.stat().st_mtime_ns)
        protected.add(latest)
        pending.extend(file for file in owned[latest] if file.suffix == '.json')
    while pending:
        path = pending.pop()
        if path in seen:
            continue
        seen.add(path)
        record = read_bounded_json(path, max_bytes=JSON_LIMIT, root=workspace)
        for token in _strings(record):
            normalized = token.replace('\\', '/')
            targets = set(aliases.get(normalized, ()))
            # Resolve a relative reference from its declaring record as well.
            relative = path.parent / normalized
            resolve_relative = True
            if '/' not in normalized:
                # A missing bare value needs no parent walk; ID/hash aliases still apply.
                try:
                    relative.lstat()
                except FileNotFoundError:
                    resolve_relative = False
                except OSError as error:
                    if error.errno != errno.ENAMETOOLONG:
                        raise
            if resolve_relative:
                targets.update(aliases.get(str(relative.resolve()).replace('\\', '/'), ()))
            for target in targets - protected:
                protected.add(target)
                pending.extend(file for file in owned[target] if file.suffix == '.json')
    unreferenced = sorted((path for path in candidates if path not in protected),
                          key=lambda path: (path.stat().st_mtime_ns, str(path)))
    sizes = {path: sum(file.stat().st_size for file in owned[path]) for path in unreferenced}
    count, byte_count = len(unreferenced), sum(sizes.values())
    removed_count = removed_bytes = 0
    for path in unreferenced:
        if count <= max_count and byte_count <= max_bytes:
            break
        if apply:
            _remove_candidate(workspace, source_root, path, owned[path])
        count -= 1
        byte_count -= sizes[path]
        removed_count += 1
        removed_bytes += sizes[path]
    if inventory is not None:
        inventory.extend((source_root, path, owned[path], sizes[path]) for path in unreferenced[removed_count:])
    return {'unreferencedCount': count, 'unreferencedBytes': byte_count,
            'protectedCount': len(protected), 'removedCount': removed_count, 'removedBytes': removed_bytes,
            'applied': apply}


def _remove_candidate(workspace, source_root, path, files):
    for file in files:
        validate_generated_path(source_root, file, workspace_root=workspace).unlink()
    if path.is_dir():
        for directory in sorted((entry for entry in path.rglob('*') if entry.is_dir()),
                                key=lambda entry: len(entry.parts), reverse=True):
            directory.rmdir()
        path.rmdir()


def cleanup(workspace: Path, *, apply=True, max_total_bytes=MAX_TOTAL_UNREFERENCED_BYTES) -> dict:
    if type(max_total_bytes) is not int or max_total_bytes < 0:
        raise ValueError('Aggregate telemetry budget must be nonnegative')
    workspace = workspace.resolve()
    root = validate_generated_path(workspace, workspace/'Engine telemetry', workspace_root=workspace)
    if not root.exists():
        return {}
    with ExitStack() as leases:
        leases.enter_context(exclusive_process_lock(root/'retention.lock'))
        sources = [validate_generated_path(root, path, workspace_root=workspace) for path in sorted(root.iterdir())
            if path.is_dir() and re.fullmatch(r'(?:youtube-[A-Za-z0-9_-]{11}|qobuz-[1-9][0-9]*)', path.name)]
        for path in sources:
            leases.enter_context(publication(workspace, path))
        inventory, result = [], {}
        # Resolve every configured authority path while all source publishers are fenced.
        jobs = {path: _authority_jobs(workspace, path.name) for path in sources}
        for path in sources:
            result[path.name] = _cleanup_locked(workspace, path, jobs[path], apply,
                MAX_UNREFERENCED_COUNT, MAX_UNREFERENCED_BYTES, inventory)
        total = sum(size for _, _, _, size in inventory)
        for source, path, files, size in sorted(inventory, key=lambda entry: (entry[1].stat().st_mtime_ns, str(entry[1]))):
            if total <= max_total_bytes:
                break
            if apply:
                _remove_candidate(workspace, source, path, files)
            total -= size
            row = result[source.name]
            row['unreferencedCount'] -= 1
            row['unreferencedBytes'] -= size
            row['removedCount'] += 1
            row['removedBytes'] += size
        return result


def main(argv=None):
    import argparse
    import json
    command = argparse.ArgumentParser(description=__doc__)
    command.add_argument('--workspace', required=True, type=Path)
    command.add_argument('--dry-run', action='store_true')
    args = command.parse_args(argv)
    print(json.dumps(cleanup(args.workspace.resolve(), apply=not args.dry_run), ensure_ascii=False))


if __name__ == '__main__':
    main()

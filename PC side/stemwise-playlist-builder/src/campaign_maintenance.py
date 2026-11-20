"""Explicit selected-output reset; no acquisition, inference or job cancellation."""
from contextlib import ExitStack
from pathlib import Path
from types import SimpleNamespace
import argparse
import json
import hashlib
import re
import sys

from stemwise_engine.common import (exclusive_process_lock, load_config,
    read_bounded_json, resolve_path, validate_generated_path, validate_video_id)
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.campaign_operations import MAX_MODEL_OPERATIONS
from stemwise_engine.delivery_selection import catalogue
from stemwise_engine.workflow_context import current_state_root


def reset(config_path, *, apply=False, recording_key=None, video_id=None, all_selected=False, preview_token=None):
    if all_selected and (recording_key or video_id):
        raise ValueError('Select one recording or all, never both')
    if apply and not (recording_key or video_id or all_selected):
        raise ValueError('Reset apply requires one exact recording or explicit --all')
    if apply and (not isinstance(preview_token, str) or re.fullmatch('[0-9a-f]{64}', preview_token) is None):
        raise ValueError('Reset apply requires the exact preview token; preview the same selector first')
    if preview_token is not None and not apply:
        raise ValueError('A preview token is used only with Reset apply')
    config_path = Path(config_path).resolve()
    config = load_config(config_path)
    builder = config_path.parent
    workspace = builder.parent.parent
    pc = resolve_path(config['pcSideRootWindows'], base=builder)
    install = resolve_path(config['futureRootWindows'], base=builder)
    ready = resolve_path(config['futureReadyDirWindows'], base=builder)
    if ready != workspace / 'Ready to Import' or pc != workspace / 'PC side':
        raise ValueError('Reset requires the configured workspace output and PC roots')
    validate_generated_path(workspace, install, workspace_root=workspace)
    if recording_key and video_id:
        raise ValueError('Select one recording key or video ID, never both')
    if recording_key and not re.fullmatch(r'(?:qobuz:[1-9][0-9]*|youtube:[A-Za-z0-9_-]{11})', recording_key):
        raise ValueError('Reset requires an exact recording key')
    if video_id:
        video_id = validate_video_id(video_id)
    library = pc / 'qobuz-comparison'
    with ExitStack() as leases:
        # Fail immediately if any model operation could still publish a replacement.
        for number in range(MAX_MODEL_OPERATIONS):
            leases.enter_context(exclusive_process_lock(builder / f'runtime/model-operations/{number}.lock', wait_seconds=0))
        leases.enter_context(exclusive_process_lock(builder / 'stemwise-heavy-worker.lock', wait_seconds=0))
        leases.enter_context(exclusive_process_lock(library / 'catalogue.json.lock', wait_seconds=0))
        leases.enter_context(exclusive_process_lock(library / 'comparison-results/delivery-selection.lock', wait_seconds=0))
        selected = catalogue(SimpleNamespace(pc_side_root=pc, workspace_root=workspace))
        projection = read_bounded_json(library / 'comparison-results/library-files.json',
            max_bytes=MAX_JSON_BYTES, root=library)
        pinned = {row['videoId'] for row in projection['playlist']}
        selected = {alias: key for alias, key in selected.items() if alias in pinned and key is not None}
        campaign_aliases = set(selected)
        if recording_key:
            selected = {alias: key for alias, key in selected.items() if key == recording_key}
        elif video_id:
            selected = {alias: key for alias, key in selected.items() if alias == video_id}
        if not selected:
            raise ValueError('Reset scope contains no selected available recording')
        state_roots = {current_state_root(install): set(selected)}
        for key in set(selected.values()):
            if key.startswith('qobuz:'):
                for root in (install / 'policy-state').glob(f'current-qobuz-{key.split(":")[1]}-*'):
                    state_roots[root] = {path.name for path in (root / 'jobs').iterdir()} if (root / 'jobs').is_dir() else set()
        retained_jobs = []
        for root, identities in state_roots.items():
            for identity in sorted(identities):
                directory = validate_generated_path(root / 'jobs', root / 'jobs' / validate_video_id(identity), workspace_root=workspace)
                if not directory.exists():
                    continue
                leases.enter_context(exclusive_process_lock(directory / 'workflow.lock', wait_seconds=0))
                job_path = directory / 'job.json'
                if job_path.exists():
                    job = read_bounded_json(job_path, max_bytes=MAX_JSON_BYTES, root=directory)
                    if not isinstance(job, dict) or job.get('videoId') != identity:
                        raise ValueError('Reset encountered an invalid current job; nothing was removed')
                    retained_jobs.append({'videoId': identity, 'state': job.get('state'),
                        'job': job_path.relative_to(workspace).as_posix()})
                elif any(path.name != 'workflow.lock' for path in directory.iterdir()):
                    retained_jobs.append({'videoId': identity, 'state': 'INCOMPLETE_CURRENT_JOB',
                        'job': directory.relative_to(workspace).as_posix()})
        targets = []
        for alias in sorted(selected):
            path = validate_generated_path(ready, ready / f'{alias}.stemwise.zip', workspace_root=workspace)
            if path.exists():
                if not path.is_file():
                    raise ValueError('Reset output must be a regular ZIP file')
                targets.append(path)
        identities = []
        for path in targets:
            identity = path.stat()
            identities.append({'fileName': path.name, 'device': identity.st_dev,
                'inode': identity.st_ino, 'byteCount': identity.st_size, 'modifiedAtNs': identity.st_mtime_ns})
        preview = {'workspace': str(workspace), 'selectedSources': selected, 'targetIdentities': identities}
        token = hashlib.sha256(json.dumps(preview, sort_keys=True, separators=(',', ':')).encode('utf-8')).hexdigest()
        result = {'operation': 'reset', 'applied': False,
            'previewToken': token, 'selectedSources': selected, 'targetIdentities': identities,
            'selectedPlaylistEntries': len(selected), 'distinctRecordings': len(set(selected.values())),
            'publicPackages': [path.name for path in targets], 'removedPackages': [],
            'retainedCurrentJobs': retained_jobs, 'canApply': not retained_jobs,
            'preserved': ['source_audio', 'numerical_caches', 'lyrics_and_review_inputs',
                'private_packages_and_checkpoints', 'unselected_public_packages'],
            'processingStarted': False}
        if apply:
            if retained_jobs:
                raise ValueError('Reset refused: current jobs are preserved; inspect Status and use Reprocess for the exact recording')
            if preview_token != token:
                raise ValueError('Reset preview changed; nothing was removed; preview the same selector again')
            for path in targets:
                path.unlink()
            if any((ready / f'{alias}.stemwise.zip').exists() for alias in selected):
                raise ValueError('Reset output removal did not finish; repeat the same reset to reconcile remaining outputs')
            result.update(applied=True, removedPackages=result['publicPackages'],
                remainingCampaignPackages=sum((ready / f'{alias}.stemwise.zip').exists() for alias in campaign_aliases))
        return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, type=Path)
    parser.add_argument('--recording-key')
    parser.add_argument('--video-id')
    parser.add_argument('--all', dest='all_selected', action='store_true', help='Explicitly select every available campaign recording')
    parser.add_argument('--preview-token', help='Exact previewToken returned for this selection and current packages')
    parser.add_argument('--apply', action='store_true', help='Permanently remove selected public ZIP aliases; preserve all processing inputs')
    args = parser.parse_args(argv)
    try:
        print(json.dumps(reset(args.config, apply=args.apply, recording_key=args.recording_key,
            video_id=args.video_id, all_selected=args.all_selected, preview_token=args.preview_token), ensure_ascii=False, allow_nan=False))
        return 0
    except (OSError, ValueError, RuntimeError) as error:
        print(json.dumps({'operation': 'reset', 'completed': False,
            'mayHaveRemovedOutputs': args.apply, 'error': str(error),
            'processingStarted': False}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())

"""Typed song work behind the bridge's existing fresh-chat/native-tool boundary."""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

from stemwise_engine.common import (atomic_write_json, exclusive_process_lock,
                    read_bounded_json, read_bounded_regular_bytes)
from stemwise_engine.workflow_records import FutureSongError


def read_context(path: Path, guide: Path, *, root: Path | None = None) -> dict:
    context = read_bounded_json(path, max_bytes=16_384, root=root)
    keys = {'formatVersion', 'kind', 'requestId', 'videoId', 'sourceUrl', 'guideSha256'}
    if (not isinstance(context, dict) or set(context) != keys or type(context['formatVersion']) is not int
            or context['formatVersion'] != 1 or context['kind'] != 'stemwise.add-song'
            or not isinstance(context['requestId'], str) or not re.fullmatch('[a-f0-9]{32}', context['requestId'])
            or not isinstance(context['guideSha256'], str) or not re.fullmatch('[a-f0-9]{64}', context['guideSha256'])):
        raise FutureSongError('bridge-song-context-invalid')
    from stemwise_engine.future_song import extract_video_id
    if not isinstance(context['sourceUrl'], str):
        raise FutureSongError('bridge-song-context-invalid')
    url = urlsplit(context['sourceUrl'])
    if (url.scheme != 'https' or url.username is not None or url.password is not None or url.port not in (None, 443)
            or context['videoId'] != extract_video_id(context['sourceUrl'])
            or hashlib.sha256(read_bounded_regular_bytes(guide, max_bytes=1_048_576, root=root)).hexdigest() != context['guideSha256']):
        raise FutureSongError('bridge-song-context-mismatch')
    return context


def _command(config: Path, key: str, video_id: str, action: str, budget: int) -> dict:
    from stemwise_engine.workflow_command import execute, parser
    arguments = ['--config', str(config)]
    if key.startswith('qobuz:'):
        arguments += ['--recording-key', key]
    arguments += [action]
    if action == 'new':
        arguments += ['--url', f'https://www.youtube.com/watch?v={video_id}', '--max-file-bytes', str(budget)]
    else:
        arguments += ['--video-id=' + video_id]
    try:
        # Pipeline chatter stays on stderr; the bridge gets the dispatcher's typed return value.
        with contextlib.redirect_stdout(sys.stderr):
            return execute(parser().parse_args(arguments))
    except SystemExit:
        return {'state': 'DISPATCH_FAILED', 'failure': {'error': 'bridge-song-command-invalid'}}
    except Exception as error:
        from stemwise_engine.future_song import FutureSongWorkflow
        from stemwise_engine.workflow_records import workflow_failure_details
        return {'state': 'DISPATCH_FAILED', 'failure': {
            'error': FutureSongWorkflow._workflow_operation_failure_code(error), **workflow_failure_details(error)}}


def _progress(config, context, key, status, budget, *, inspect_only=False):
    result = {'formatVersion': 1, 'kind': 'stemwise.add-song-progress', 'requestId': context['requestId'],
              'videoId': context['videoId'], 'recordingKey': key, 'engine': status}
    state = status.get('state')
    if state == 'DELIVERED' and inspect_only:
        result.update(status='waiting', code='stemwise_delivery_verification_required')
    elif state == 'DELIVERED':
        from stemwise_engine.bridge_delivery import describe
        delivery = describe(config, context['videoId'], budget)
        if delivery['recordingKey'] != key:
            raise FutureSongError('bridge-song-request-conflict')
        result.update(status='completed', code='stemwise_song_ready',
                      package=delivery['package'])
    elif state == 'DISPATCH_FAILED' or status.get('platformContentRestriction'):
        result.update(status='failed', code='stemwise_dispatch_failed')
    elif state in {'NEW', 'ANALYZING', 'ALIGNING_LYRICS'}:
        result.update(status='working', code='stemwise_alignment' if state == 'ALIGNING_LYRICS' else 'stemwise_source')
    else:
        result.update(status='waiting', code='stemwise_editorial' if state in
            {'WAITING_FOR_EDITORIAL', 'NEEDS_LYRICS', 'WAITING_FOR_DISPLAY_REVIEW'} else 'stemwise_engine_waiting')
    return result


def advance(config: Path, context: dict, max_file_bytes: int, *, inspect_only: bool = False) -> dict:
    if type(max_file_bytes) is not int or max_file_bytes <= 0:
        raise FutureSongError('bridge-song-budget-invalid')
    from stemwise_engine.delivery_selection import catalogue
    from stemwise_engine.future_song import FutureSongWorkflow
    workflow = FutureSongWorkflow(config)
    video_id = context['videoId']
    key = catalogue(workflow).get(video_id, f'youtube:{video_id}')
    if key is None:
        raise FutureSongError('recording-not-selected')
    if key.startswith('qobuz:'):
        from stemwise_engine.recording_workflow import RecordingWorkflow
        workflow = RecordingWorkflow(config, key, verify_media=False)
    from stemwise_engine.workflow_context import telemetry_job_root
    # The immutable incoming slot locates the binding even if its selected recording later changes.
    directory = telemetry_job_root(workflow.workspace_root, f'youtube:{video_id}') / 'bridge'
    binding = directory / (context['requestId'] + '.json')
    frozen = {'context': context, 'maxFileBytes': max_file_bytes, 'recordingKey': key}
    if inspect_only:
        if binding.exists() and read_bounded_json(binding, max_bytes=32_768, root=directory) != frozen:
            raise FutureSongError('bridge-song-request-conflict')
        owner_id = workflow.video_id if key.startswith('qobuz:') else video_id
        status = (_command(config, key, owner_id, 'status', max_file_bytes) if binding.exists()
                  else {'state': 'NOT_STARTED'})
        return _progress(config, context, key, status, max_file_bytes, inspect_only=True)
    directory.mkdir(parents=True, exist_ok=True)
    with exclusive_process_lock(directory / (context['requestId'] + '.lock')):
        if binding.exists():
            if read_bounded_json(binding, max_bytes=32_768, root=directory) != frozen:
                raise FutureSongError('bridge-song-request-conflict')
        else:
            atomic_write_json(binding, frozen, root=directory, workspace_root=workflow.workspace_root)
        owner_id = workflow.video_id if key.startswith('qobuz:') else video_id
        # Current engine locks/operation receipts own side effects. A reconnect never calls Retry.
        if workflow._job_path(owner_id).exists():
            status = _command(config, key, owner_id, 'status', max_file_bytes)
        else:
            status = _command(config, key, owner_id, 'new', max_file_bytes)
        if (status.get('state') in {'VALIDATED', 'DELIVERY_PUBLICATION_PENDING'}
                or key.startswith('qobuz:') and status.get('state') == 'DELIVERED'):
            status = _command(config, key, owner_id, 'finalize', max_file_bytes)
        return _progress(config, context, key, status, max_file_bytes)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--context', type=Path, required=True)
    parser.add_argument('--guide', type=Path, required=True)
    parser.add_argument('--max-file-bytes', type=int, required=True)
    parser.add_argument('--inspect', action='store_true')
    args = parser.parse_args(argv)
    try:
        project = args.config.resolve().parents[2]
        result = advance(args.config, read_context(args.context, args.guide, root=project), args.max_file_bytes,
                         inspect_only=args.inspect)
        print(json.dumps(result, ensure_ascii=False, allow_nan=False))
        return 2 if result['status'] == 'failed' else 0
    except (ValueError, OSError, FutureSongError) as error:
        print(json.dumps({'status': 'failed', 'code': 'stemwise_dispatch_failed', 'cause': str(error)}, ensure_ascii=False))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())

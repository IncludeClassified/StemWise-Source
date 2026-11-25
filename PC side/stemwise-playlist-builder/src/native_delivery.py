"""Publish one finalized audio bundle for each selected playlist entry."""
import copy
import hashlib
from pathlib import Path

from stemwise_engine.common import (json_bytes, manifested_temporary_directory, read_bounded_json, resolve_path,
                    sha256_file, validate_generated_path, validate_video_id)
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.packages import create_child_zip, read_child
from stemwise_engine.recording_source import selected_recording
from stemwise_engine.original_delivery import (read_bundle, read_reviewed_bundle, prepare_original, create_bundle,
                                                selected_original, publish_bundle)
from stemwise_engine.delivery_selection import catalogue
from stemwise_engine.workflow_records import FutureSongError


def _selected_ids(workflow, *, verify_media=True):
    _, _, _, identity = selected_recording(workflow.config, workflow.key, verify_media=verify_media)
    if identity != workflow.recording[3]:
        raise ValueError('Selected native recording changed before delivery')
    selected = catalogue(workflow, recording_key=workflow.key)
    ids = [video_id for video_id,key in selected.items() if key == workflow.key]
    return ids


def _source(workflow, job):
    if job.get('state') not in {'DELIVERY_PUBLICATION_PENDING', 'DELIVERED'}:
        raise ValueError('Native phone delivery requires a finalized result')
    path = validate_generated_path(workflow.ready,
        workflow.ready / f'{workflow.video_id}.stemwise.zip', workspace_root=workflow.workspace_root)
    if sha256_file(path) != job['delivery']['sha256']:
        raise ValueError('Finalized native enrichment changed')
    document, cover = read_child(path, workflow._runtime_config())
    from stemwise_engine.delivery_timing import require_delivery_clearance
    return document, cover, require_delivery_clearance(workflow, job, document)


def _root(workflow):
    original_ready = resolve_path(workflow.config['futureReadyDirWindows'], base=Path(workflow.config['projectDir']))
    return validate_generated_path(workflow.workspace_root,
        original_ready,
        workspace_root=workflow.workspace_root)


def publish(workflow, job):
    if not workflow._validated_snapshot_is_current(job, require_delivery=True,
            allow_pending_delivery_cleanup=job['state'] == 'DELIVERY_PUBLICATION_PENDING'):
        raise ValueError('Native phone delivery requires the current validated finalization')
    document, cover, clearance = _source(workflow, job)
    staging_root = workflow._scavenge_finalization_staging(workflow.video_id)
    ids = _selected_ids(workflow)
    selected_before = dict.fromkeys(ids, workflow.key)
    if not ids:
        raise FutureSongError('recording-not-selected')
    repairing_artwork = bool(job.get('artworkRepair')) and job.get('artworkPublicationSha256') != job['delivery']['sha256']
    if not repairing_artwork:
        finished = current(workflow, job, allow_replacement=True)
        if finished:
            return finished
    root = _root(workflow)
    root.mkdir(parents=True, exist_ok=True)
    previous = None
    if repairing_artwork:
        previous = _previous_artwork_delivery(workflow, job, document, cover, ids, root)
    else:
        _previous_recording_binding(workflow, job)
    if repairing_artwork and previous is None:
        finished = current(workflow, job)
        if finished:
            return finished
    with manifested_temporary_directory(staging_root, prefix=f'{workflow.video_id}-',
            owner={'kind': 'package-finalization-staging', 'videoId': workflow.video_id},
            workspace_root=workflow.workspace_root) as temporary:
        cover_path = temporary / 'cover.png'
        cover_path.write_bytes(cover)
        _write_children(workflow, document, cover_path, ids, root, previous,
            job=job, reuse_original=repairing_artwork, selected_before=selected_before,clearance=clearance)
    return current(workflow, job)


def _previous_private_delivery(workflow, job, video_id, digest):
    name = f'{video_id}.stemwise.zip'
    path = validate_generated_path(workflow.ready, workflow.ready / name,
        workspace_root=workflow.workspace_root)
    if not path.is_file() or sha256_file(path) != digest:
        rollback = hashlib.sha256(b'stemwise-delivery-rollback\0' + digest.encode('ascii')
            + b'\0' + job['delivery']['sha256'].encode('ascii')).hexdigest()[:24]
        path = validate_generated_path(workflow.history,
            workflow.history / video_id / f'{rollback}.{name}', workspace_root=workflow.workspace_root)
    if not path.is_file() or sha256_file(path) != digest:
        raise ValueError('Previous native delivery snapshot is missing or changed')
    document, cover = read_child(path, workflow._runtime_config())
    if document['source']['videoId'] != video_id or sha256_file(path) != digest:
        raise ValueError('Previous native delivery snapshot differs from its binding')
    return document, cover


def _previous_recording_binding(workflow, job):
    path = validate_generated_path(workflow.ready, workflow.ready / 'recording.json',
        workspace_root=workflow.workspace_root)
    if not path.exists():
        return None
    binding = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=workflow.ready)
    if not isinstance(binding, dict):
        raise ValueError('Previous native delivery binding is malformed')
    video_id = validate_video_id(binding.get('lyricReferenceVideoId'))
    digest = binding.get('enrichmentSha256')
    import re
    if not isinstance(digest, str) or re.fullmatch(r'[0-9a-f]{64}', digest) is None:
        raise ValueError('Previous native delivery binding has an invalid hash')
    expected = {**workflow._result_binding(job), 'lyricReferenceVideoId': video_id,
        'enrichment': f'{video_id}.stemwise.zip', 'enrichmentSha256': digest}
    if json_bytes(binding) != json_bytes(expected):
        raise ValueError('Previous native delivery binding differs from the selected recording')
    return video_id, digest


def _previous_recording_delivery(workflow, job):
    binding = _previous_recording_binding(workflow, job)
    return _previous_private_delivery(workflow, job, *binding) if binding is not None else None


def _previous_artwork_delivery(workflow, job, document, cover, ids, root):
    repair = job.get('artworkRepair')
    if not repair:
        return None
    for video_id in ids:
        alias = root / f'{video_id}.stemwise.zip'
        if not alias.is_file():
            continue
        actual, artwork, _ = read_bundle(alias, workflow._runtime_config())
        expected = copy.deepcopy(document)
        expected['source']['videoId'] = video_id
        if actual != expected or artwork != cover:
            break
    else:
        return None
    # Finalize preserves the previous private ZIP at this deterministic path.
    # An arbitrary existing alias is never enough to authorize its replacement.
    document, cover = _previous_private_delivery(workflow, job, workflow.video_id,
        repair['previousChildSha256'])
    if hashlib.sha256(cover).hexdigest() != repair['previousArtworkSha256']:
        raise ValueError('Previous native artwork snapshot differs')
    return document, cover


def _write_children(workflow, document, cover_path, ids, root, previous=None, *, job, reuse_original=False, selected_before,clearance):
    artwork = cover_path.read_bytes()
    replacements = []
    retained = next((root / f'{video_id}.stemwise.zip' for video_id in ids
        if (root / f'{video_id}.stemwise.zip').is_file()), None) if reuse_original else None
    if retained is not None:
        from stemwise_engine.original_delivery import extract_original
        native = document['source']['recording']
        audio, identity, _, _ = extract_original(retained, workflow._runtime_config(), cover_path.parent,
            original={'name': 'original.flac', 'sha256': native['sha256'], 'byteCount': native['byteCount']})
    else:
        audio, identity = prepare_original(selected_original(workflow, document), document, cover_path.parent)
    for video_id in ids:
        alias = copy.deepcopy(document)
        alias['source']['videoId'] = video_id
        path = validate_generated_path(root, root / f'{video_id}.stemwise.zip', workspace_root=workflow.workspace_root)
        if path.is_file():
            actual, cover, original, review = read_reviewed_bundle(path, {**workflow._runtime_config(), 'recordingInput': None})
            if actual == alias and cover == artwork:
                if original == identity and review == clearance.review_sha256:
                    continue
                if original != identity:
                    raise ValueError('Existing native Original differs from selected recording')
            else:
                prior_key = actual['source'].get('recording', {}).get('recordingKey', f'youtube:{video_id}')
                if actual['source']['videoId'] != video_id:
                    raise ValueError('Existing native phone delivery differs from finalized enrichment')
                if reuse_original or prior_key == workflow.source_context.recording_key:
                    if previous is None and not reuse_original:
                        previous = _previous_recording_delivery(workflow, job)
                    if previous is None:
                        raise ValueError('Existing native phone delivery has no verified previous enrichment')
                    old_alias = copy.deepcopy(previous[0])
                    old_alias['source']['videoId'] = video_id
                    if actual != old_alias or cover != previous[1]:
                        raise ValueError('Existing native phone delivery differs from the previous enrichment snapshot')
            digest = sha256_file(path)
        else:
            digest = None
        replacements.append((path, alias, digest))
    # Check every existing alias before replacing any of them. A retry accepts
    # aliases already atomically updated and replaces only the known old ones.
    for path, alias, digest in replacements:
        metadata = cover_path.parent / 'alias.zip'
        create_child_zip(metadata, alias, cover_path, workflow._runtime_config())
        staged = cover_path.parent/'alias-delivery.zip'
        create_bundle(staged, metadata, audio, identity, workflow._runtime_config(), cover_path.parent,clearance=clearance)
        publish_bundle(workflow, staged, path, metadata=metadata, original=identity,
            previous_sha256=digest, selected_before=selected_before)


def current(workflow, job, *, allow_replacement=False):
    """No self-reported flag substitutes for the actual matching ZIP bytes."""
    if job.get('state') not in {'DELIVERY_PUBLICATION_PENDING', 'DELIVERED'}:
        return []
    document, cover, clearance = _source(workflow, job)
    result = []
    for video_id in _selected_ids(workflow, verify_media=False):
        path = _root(workflow) / f'{video_id}.stemwise.zip'
        if any(candidate != path for candidate in _root(workflow).rglob(path.name)):
            raise ValueError('Multiple public ZIPs exist for the same song')
        if not path.is_file():
            return []
        config = workflow._runtime_config()
        if allow_replacement:
            config = {**config, 'recordingInput': None}
        actual, artwork, original, review = read_reviewed_bundle(path, config)
        expected = copy.deepcopy(document)
        expected['source']['videoId'] = video_id
        if actual != expected or artwork != cover:
            if allow_replacement:
                return []
            raise ValueError('Native phone delivery differs from finalized enrichment')
        if review != clearance.review_sha256:
            return []
        result.append({'videoId': video_id, 'path': workflow._windows_path(path)})
    return result

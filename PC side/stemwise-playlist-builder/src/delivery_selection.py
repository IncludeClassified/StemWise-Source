"""The owner's selected playlist determines the single public recording for each song."""
from contextlib import contextmanager, nullcontext
import hashlib
import re
from stemwise_engine.common import (exclusive_process_lock, process_lock_descriptor, read_bounded_regular_bytes, parse_strict_json_bytes,
                    read_bounded_json, require_youtube_audio_allowed, validate_generated_path, validate_video_id)
from stemwise_engine.contract import MAX_JSON_BYTES


@contextmanager
def publication_selection(workflow, bindings, *, selected_before):
    library = workflow.pc_side_root / 'qobuz-comparison'
    lock = library / 'comparison-results/delivery-selection.lock'
    with (nullcontext() if process_lock_descriptor(lock) is not None else exclusive_process_lock(lock)):
        selected = catalogue(workflow, public_ids=bindings)
        for video_id, key in bindings.items():
            if ((video_id in selected_before and video_id not in selected)
                    or (video_id in selected and selected[video_id] != key)):
                raise ValueError('Selected delivery recording changed during publication')
            if key == f'youtube:{video_id}':
                require_youtube_audio_allowed(video_id, root=library)
        yield

def catalogue(workflow, *, registration=None, public_ids=None, recording_key=None, playlist_only=False):
    wanted = None if public_ids is None else {validate_video_id(value) for value in public_ids}
    path = workflow.pc_side_root / 'qobuz-comparison/comparison-results/library-files.json'
    path = validate_generated_path(workflow.workspace_root, path, workspace_root=workflow.workspace_root)
    if path.exists():
        raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=workflow.workspace_root)
        value = parse_strict_json_bytes(raw)
    else:
        value = {'playlist': []}
    if not isinstance(value, dict) or not isinstance(value.get('playlist'), list):
        raise ValueError('Selected delivery plan is invalid')
    source = workflow.pc_side_root/'qobuz-comparison/catalogue.json'
    if source.exists():
        source_raw = read_bounded_regular_bytes(source, max_bytes=MAX_JSON_BYTES, root=workflow.workspace_root)
        if value.get('catalogueSha256') != hashlib.sha256(source_raw).hexdigest():
            raise ValueError('Selected delivery projection is stale; export the committed catalogue')
    result, filenames = {}, set()
    for row in value['playlist']:
        if not isinstance(row, dict):
            raise ValueError('Selected song is invalid')
        video_id = validate_video_id(row.get('videoId'))
        if video_id.casefold() in filenames:
            raise ValueError('Selected delivery plan repeats a song')
        filenames.add(video_id.casefold())
        source = row.get('source')
        if source == 'qobuz':
            track_id = row.get('trackId')
            if type(track_id) is not int or track_id <= 0:
                raise ValueError('Selected native recording is invalid')
            key = f'qobuz:{track_id}'
        elif source == 'youtube':
            key = f'youtube:{video_id}'
        elif source == 'unavailable':
            key = None
        else:
            raise ValueError('Selected recording source is invalid')
        result[video_id] = key
    if playlist_only:
        return result
    library = workflow.pc_side_root / 'qobuz-comparison'
    from stemwise_engine.recording_source import registered_declaration
    if recording_key is not None:
        if wanted is not None or re.fullmatch(r'qobuz:[1-9][0-9]*', recording_key) is None:
            raise ValueError('Invalid native recording selection')
        wanted = {video_id for video_id, key in result.items() if key == recording_key}
        declared = registered_declaration(library, int(recording_key.split(':')[1]))
        if declared is not None:
            wanted.add(declared['referenceVideoId'])
    if wanted is not None:
        result = {video_id: key for video_id, key in result.items() if video_id in wanted}
        if wanted <= result.keys():
            return result
    pinned = set(result)
    unbound_error = None
    paths = sorted((library / 'audio/qobuz').glob('*/enrichment-input.json'))
    if registration is not None:
        own = library / f"audio/qobuz/{registration['trackId']}/enrichment-input.json"
        paths = [path for path in paths if path != own] + [own]
    for path in paths:
        if re.fullmatch(r'[1-9][0-9]*', path.parent.name) is None:
            continue
        path = validate_generated_path(library, path, workspace_root=workflow.workspace_root)
        try:
            candidate = (registration if registration is not None and path == own
                         else read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=library))
            video_id = validate_video_id(candidate.get('referenceVideoId') if isinstance(candidate, dict) else None)
        except (ValueError, OSError) as error:
            if wanted is None:
                raise
            # A broken entry with no readable public ID cannot supersede an identified song.
            # It does prevent treating an unidentified ID as a safe YouTube fallback.
            unbound_error = error
            continue
        if video_id in pinned or wanted is not None and video_id not in wanted:
            continue
        declared = (registration if registration is not None and path == own
                    else registered_declaration(library, int(path.parent.name)))
        video_id = declared['referenceVideoId']
        key = f"qobuz:{declared['trackId']}"
        if video_id in result:
            raise ValueError('Conflicting registered recordings select the same song')
        if video_id.casefold() in filenames:
            raise ValueError('Selected delivery plan repeats a song')
        result[video_id] = key
        filenames.add(video_id.casefold())
    if unbound_error is not None and wanted - result.keys():
        raise unbound_error
    return result

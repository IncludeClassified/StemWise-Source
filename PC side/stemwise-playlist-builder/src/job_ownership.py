"""Admission of new work and exact explicitly requested analysis replacement."""
import hashlib
import re
import shutil
from contextlib import nullcontext
from pathlib import Path

from stemwise_engine.common import validate_generated_path
from stemwise_engine.workflow_records import FutureSongError


def require_new_recording(workflow, video_id):
    """Refuse a duplicate before reporting a start or submitting remote work."""
    candidates = (list(workflow.jobs.iterdir()) if workflow.source_context is not None
        else [workflow.jobs / video_id])
    for directory in candidates:
        validate_generated_path(workflow.jobs, directory, workspace_root=workflow.workspace_root)
        if directory.exists() and (not directory.is_dir() or any(
                item.name != 'workflow.lock' for item in directory.iterdir())):
            raise FutureSongError('A current job already owns this recording; New is refused')


def replacement_root(workflow, video_id):
    return validate_generated_path(workflow._job_dir(video_id),
        workflow._job_dir(video_id)/'analysis-replacement', workspace_root=workflow.workspace_root)


def replacement_record(workflow, job):
    from stemwise_engine.common import read_bounded_json, sha256_file
    from stemwise_engine.contract import MAX_JSON_BYTES
    root = replacement_root(workflow, job['videoId'])
    record = read_bounded_json(root/'request.json', max_bytes=MAX_JSON_BYTES, root=root)
    request = record['request']
    if (job.get('analysisReplacement') != request
            or request['source'] != workflow.source.identity(job['videoId'])
            or request['videoId'] != job['videoId']
            or request['deliverySha256'] != record['previousDelivery']['sha256']
            or request['analysisSha256'] != record['previousValidation']['audioSha256']):
        raise FutureSongError('Analysis replacement owner differs')
    for name, key in (('previous.zip', 'childSha256'), ('previous.json', 'checkpointSha256')):
        path = validate_generated_path(root, root/name, workspace_root=workflow.workspace_root)
        if sha256_file(path) != request[key]:
            raise FutureSongError('Analysis replacement rollback differs')
    return record, root


def replacement_source(workflow, job):
    from stemwise_engine.common import read_bounded_json, sha256_file
    from stemwise_engine.contract import MAX_JSON_BYTES
    record, root = replacement_record(workflow, job)
    fresh = read_bounded_json(root/'fresh-pcm.json', max_bytes=MAX_JSON_BYTES, root=root)
    from stemwise_engine.provenance import semantic_source_identity
    from stemwise_engine import youtube
    if (fresh['request'] != record['request'] or fresh['decoderSha256'] !=
            semantic_source_identity(Path(youtube.__file__), ('decode_analysis_audio',))):
        raise FutureSongError('Fresh PCM belongs to another replacement')
    source = validate_generated_path(root, root/fresh['source']['file'], workspace_root=workflow.workspace_root)
    if source.stat().st_size != fresh['source']['byteCount'] or sha256_file(source) != fresh['source']['sha256']:
        raise FutureSongError('Replacement source bytes changed')
    replacement_pcm(root, fresh)
    return source, fresh, root


def replacement_pcm(root, fresh):
    from stemwise_engine.common import sha256_file
    from stemwise_engine.youtube import probe_duration_ms
    pcm = root/'pcm/analysis.wav'
    expected = fresh['pcm']
    if (sha256_file(pcm) != expected['sha256'] or pcm.stat().st_size != expected['byteCount']
            or probe_duration_ms(pcm) != expected['durationMs']):
        raise FutureSongError('Prepared replacement PCM changed')
    return pcm


def inspect_analysis_replacement(workflow, job):
    """Capture replacement ownership under the same lease that publishes its numerical pair."""
    from stemwise_engine.common import sha256_file, exclusive_process_lock, process_lock_descriptor
    if not job.get('analysisReplacement'):
        return job, False
    video_id = job['videoId']
    lock = workflow._job_dir(video_id)/'workflow.lock'
    owned = process_lock_descriptor(lock) is not None
    with nullcontext() if owned else exclusive_process_lock(lock):
        if not owned:
            job = workflow._load_job(video_id, require_active_policy=False)
        request = job.get('analysisReplacement')
        if not request:
            return job, False
        if request['source'] != workflow.source.identity(video_id):
            raise FutureSongError('Analysis replacement source differs')
        public = validate_generated_path(workflow.ready, workflow.ready/f'{video_id}.stemwise.zip',
                                         workspace_root=workflow.workspace_root)
        delivery = job.get('delivery', {})
        if job['state'] in {'DELIVERY_PUBLICATION_PENDING', 'DELIVERED'} and delivery.get('sha256') != request['deliverySha256']:
            # Completed delivery cleanup may already have retired the rollback tree and PCM.
            if sha256_file(public) != delivery.get('sha256'):
                raise FutureSongError('Replacement delivery bytes changed')
            return job, False
        _, fresh, _ = replacement_source(workflow, job)
        if sha256_file(public) != request['deliverySha256']:
            raise FutureSongError('Previous delivery changed before replacement analysis')
        pipeline = workflow._pipeline()
        if pipeline.analysis_replacement_complete(video_id, request, fresh['pcm']):
            workflow._completed_analysis_audio(pipeline, video_id)
            return job, False
        if (sha256_file(pipeline.checkpoint_path(video_id)) != request['checkpointSha256']
                or sha256_file(pipeline.child_path(video_id)) != request['childSha256']):
            raise FutureSongError('Unfinished analysis replacement owner changed')
        return job, True


def admit_analysis_replacement(workflow, video_id, delivery_sha, analysis_sha):
    from stemwise_engine.common import (atomic_write_bytes, atomic_write_json, create_manifested_directory,
        exclusive_process_lock, WORKER_DIRECTORY_LOCK, read_bounded_json, read_bounded_regular_bytes,
        sha256_file, json_bytes)
    from stemwise_engine.contract import MAX_JSON_BYTES
    from stemwise_engine.packages import read_child
    from stemwise_engine.original_delivery import read_bundle, extract_original
    from stemwise_engine.youtube import decode_analysis_audio, probe_duration_ms
    from stemwise_engine.provenance import semantic_source_identity
    from stemwise_engine import youtube
    if any(not isinstance(value, str) or re.fullmatch('[0-9a-f]{64}', value) is None
           for value in (delivery_sha, analysis_sha)):
        raise FutureSongError('Replacement requires exact delivery and analysis SHA-256 values')
    job = workflow._load_job(video_id, require_active_policy=False)
    if job.get('analysisReplacement'):
        record, _ = replacement_record(workflow, job)
        if (record['request']['deliverySha256'], record['request']['analysisSha256']) != (delivery_sha, analysis_sha):
            raise FutureSongError('A different replacement already owns this recording')
        return workflow.summary(job)
    if (job.get('state') != 'DELIVERED' or job.get('delivery', {}).get('sha256') != delivery_sha
            or job.get('validation', {}).get('audioSha256') != analysis_sha
            or any(job.get(key) for key in ('capabilityRefresh', 'artworkRepairPending'))):
        raise FutureSongError('Replacement requires the exact completed recording and delivery')
    pipeline = workflow._pipeline()
    checkpoint_path, child = pipeline.checkpoint_path(video_id), pipeline.child_path(video_id)
    checkpoint = pipeline.load_checkpoint(video_id)
    if (not checkpoint or checkpoint.get('status') != 'complete' or checkpoint.get('videoId') != video_id
            or checkpoint.get('originalAudio', {}).get('sha256') != analysis_sha
            or checkpoint.get('childSha256') != sha256_file(child)):
        raise FutureSongError('Replacement analysis checkpoint differs')
    document, _ = read_child(child, pipeline.config)
    if document['source'] != workflow.source.identity(video_id):
        raise FutureSongError('Replacement source differs from the completed recording')
    # Never overwrite intact or incomplete retained PCM to force a fresh operation.
    retained = validate_generated_path(workflow.analysis_temp, workflow.analysis_temp/video_id/'analysis.wav',
        workspace_root=workflow.workspace_root)
    if retained.exists() or retained.is_symlink():
        raise FutureSongError('Retained PCM exists; restore or validate its exact identity instead')
    destination = validate_generated_path(workflow.ready, workflow.ready/f'{video_id}.stemwise.zip',
        workspace_root=workflow.workspace_root)
    if sha256_file(destination) != delivery_sha:
        raise FutureSongError('Replacement delivery bytes changed')
    if workflow.source_context is None:
        read_bundle(destination, pipeline.config, metadata=child, original=job['delivery']['original'])
    else:
        published, _ = read_child(destination, pipeline.config)
        binding = read_bounded_json(workflow.ready/'recording.json', max_bytes=MAX_JSON_BYTES, root=workflow.ready)
        if published['source'] != document['source'] or binding != workflow._result_binding(job):
            raise FutureSongError('Replacement native publication differs')
    request = {'version': 1, 'videoId': video_id, 'source': document['source'],
        'deliverySha256': delivery_sha, 'analysisSha256': analysis_sha,
        'checkpointSha256': sha256_file(checkpoint_path), 'childSha256': sha256_file(child)}
    request['operationId'] = hashlib.sha256(json_bytes(request)).hexdigest()[:32]
    record = {'request': request, 'previousDelivery': job['delivery'],
        'previousValidation': job['validation'], 'previousPolicy': job['policy']}
    root = replacement_root(workflow, video_id)
    owner = {'kind': 'explicit-analysis-replacement', 'videoId': video_id, 'operationId': request['operationId']}
    if root.exists():
        from stemwise_engine.common import GENERATED_MANIFEST_NAME
        if read_bounded_json(root/GENERATED_MANIFEST_NAME, max_bytes=16384, root=root) != {
                'manifestVersion': 1, 'owner': owner, 'generatedPaths': ['.']}:
            raise FutureSongError('Analysis replacement staging owner differs')
    else:
        create_manifested_directory(root.parent, root, owner=owner, workspace_root=workflow.workspace_root)
    with exclusive_process_lock(root/WORKER_DIRECTORY_LOCK):
        pointer = root/'request.json'
        if pointer.exists():
            if read_bounded_json(pointer, max_bytes=MAX_JSON_BYTES, root=root) != record:
                raise FutureSongError('Another replacement owns the retained rollback')
        else:
            atomic_write_bytes(root/'previous.zip', read_bounded_regular_bytes(child,
                max_bytes=int(workflow.config['childZipMaxBytes']), root=child.parent))
            atomic_write_bytes(root/'previous.json', read_bounded_regular_bytes(checkpoint_path,
                max_bytes=MAX_JSON_BYTES, root=checkpoint_path.parent))
            atomic_write_json(pointer, record)
        if not (root/'fresh-pcm.json').exists():
            if workflow.source_context is None:
                source, _, _, _ = extract_original(destination, pipeline.config, root,
                    original=job['delivery']['original'])
            else:
                source = root/'selected.flac'
                selected = workflow.recording.audio
                if sha256_file(selected) != workflow.recording.identity['sha256']:
                    raise FutureSongError('Selected replacement source changed')
                shutil.copyfile(selected, source)
                if sha256_file(source) != workflow.recording.identity['sha256']:
                    raise FutureSongError('Copied replacement source differs')
            source_identity = {'file': source.name, 'sha256': sha256_file(source), 'byteCount': source.stat().st_size}
            from stemwise_engine.analysis_resources import require_analysis_capacity
            require_analysis_capacity(probe_duration_ms(source), scratch_root=root)
            pcm = decode_analysis_audio(source, root/'pcm',
                timeout_seconds=int(workflow.config['analysisTimeoutMinutes'])*60)
            pcm_identity = {'sha256': sha256_file(pcm), 'byteCount': pcm.stat().st_size,
                'durationMs': probe_duration_ms(pcm), 'retained': True}
            if pcm_identity['sha256'] == analysis_sha:
                from stemwise_engine.common import remove_manifested_directory
                remove_manifested_directory(root.parent, root, owner=owner, workspace_root=workflow.workspace_root)
                raise FutureSongError('Published source reproduces exact historical PCM; replacement is unnecessary')
            if sha256_file(source) != source_identity['sha256'] or sha256_file(destination) != delivery_sha:
                raise FutureSongError('Replacement inputs changed during PCM preparation')
            atomic_write_json(root/'fresh-pcm.json', {'request': request, 'source': source_identity,
                'pcm': pcm_identity, 'decoderSha256': semantic_source_identity(Path(youtube.__file__), ('decode_analysis_audio',))})
        replacement_source(workflow, {**job, 'analysisReplacement': request})
        job['analysisReplacement'] = request
        job['policy'] = workflow._policy_binding()
        job['state'] = 'ANALYSIS_REPLACEMENT_PENDING'
        for key in ('validation', 'endpointReview', 'displayReview', 'activeAlignmentAttemptId', 'alignmentAttemptInputs',
                    'recordingEvidence', 'recordingEvidenceInputs', 'recordingContentEvidence',
                    'lyricContentLimitation', 'outcome', 'error', 'capabilityRefresh', 'artworkRepair'):
            job.pop(key, None)
        workflow._save_job(job)
    workflow._write_task(job)
    return workflow.summary(job)


def run_analysis_replacement(workflow, job):
    from stemwise_engine.common import (durable_replace, exclusive_process_lock,
        WORKER_DIRECTORY_LOCK, sha256_file, read_bounded_json)
    from stemwise_engine.contract import MAX_JSON_BYTES
    source, fresh, root = replacement_source(workflow, job)
    request, video_id = fresh['request'], job['videoId']
    if sha256_file(workflow.ready/f'{video_id}.stemwise.zip') != request['deliverySha256']:
        raise FutureSongError('Previous delivery changed before replacement analysis')
    pipeline = workflow._pipeline()
    with exclusive_process_lock(root/WORKER_DIRECTORY_LOCK):
        pcm = replacement_pcm(root, fresh)
        expected = fresh['pcm']
        from stemwise_engine.analysis_resources import require_analysis_capacity
        require_analysis_capacity(expected['durationMs'], scratch_root=workflow.analysis_temp)
        temporary = pipeline._temp_dir(video_id)
        destination = temporary/'analysis.wav'
        if destination.exists() and sha256_file(destination) != expected['sha256']:
            raise FutureSongError('Another PCM owner occupies replacement analysis')
        if not destination.exists():
            staged = temporary/f"replacement-{request['operationId']}.wav"
            shutil.copyfile(pcm, staged)
            if sha256_file(staged) != expected['sha256']:
                raise FutureSongError('Copied replacement PCM differs')
            durable_replace(staged, destination)
        job['state'] = 'ANALYZING'
        workflow._save_job(job)
        try:
            with workflow._song_ledger(video_id).primary('acquisition-analysis', {'replacement': request}):
                outcome = pipeline.replace_missing_analysis({'position': 1, 'videoId': video_id,
                    'title': job.get('title', video_id)}, request, root, expected)
                if outcome != 'complete':
                    raise FutureSongError('Explicit replacement analysis did not complete')
                workflow._completed_analysis_audio(pipeline, video_id)
        except BaseException:
            job['state'] = 'ANALYSIS_FAILED'
            job['error'] = 'analysis-replacement-failed'
            try:
                workflow._save_job(job)
            except Exception:
                import sys
                print('analysis-replacement-failure-state-write-failed', file=sys.stderr)
            raise
        sources = read_bounded_json(workflow._job_dir(video_id)/'sources.json',
            max_bytes=MAX_JSON_BYTES, root=workflow._job_dir(video_id))
        job['state'] = 'WAITING_FOR_EDITORIAL' if sources.get('containsLyrics') else 'NEEDS_LYRICS'
        job.pop('error', None)
        workflow._save_job(job)
        workflow._write_task(job)
        return workflow.summary(job)


def cleanup_analysis_replacement(workflow, job):
    from stemwise_engine.common import remove_manifested_directory, manifested_cleanup_receipt, sha256_file
    request = job.get('analysisReplacement')
    if not request:
        return
    if (job.get('state') != 'DELIVERED' or request['videoId'] != job['videoId']
            or request['source'] != workflow.source.identity(job['videoId'])
            or job['delivery']['sha256'] == request['deliverySha256']
            or sha256_file(workflow.ready/f"{job['videoId']}.stemwise.zip") != job['delivery']['sha256']):
        raise FutureSongError('Replacement delivery has not committed')
    root = replacement_root(workflow, job['videoId'])
    # A cleanup receipt owns partial deletion; an absent tree means deletion committed.
    if root.exists() and not manifested_cleanup_receipt(root).exists():
        replacement_record(workflow, job)
    remove_manifested_directory(root.parent, root, owner={'kind': 'explicit-analysis-replacement',
        'videoId': job['videoId'], 'operationId': request['operationId']}, workspace_root=workflow.workspace_root)
    job.pop('analysisReplacement')
    workflow._save_job(job)

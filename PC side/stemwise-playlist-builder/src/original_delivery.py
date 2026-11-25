"""Audio transport around an unchanged, privately validated enrichment ZIP."""
import hashlib
import math
import shutil
import tempfile
import zipfile
from pathlib import Path

from stemwise_engine.common import (durable_replace, read_bounded_json, run_process_tree,
                    sha256_file, validate_generated_path, preflight_staging_space,
                    resolve_path)
from stemwise_engine.contract import MAX_JSON_BYTES, MAX_DURATION_MS, MAX_COVER_BYTES, MAX_COVER_PIXELS_PER_SIDE
from stemwise_engine.packages import (_canonical_zip_info, _configured_limit, _require_regular_member,
                      _require_regular_path, read_child, PackageValidationError)

MAX_ORIGINAL_BYTES = 1024 ** 3
MAX_DELIVERY_BYTES = MAX_ORIGINAL_BYTES + 32 * 1024 ** 2
MAX_SAMPLE_RATE = 384000
NAMES = {'original.flac', 'original.opus'}
STRICT_DECODE_OPTIONS = ('-xerror',)


def require_supported_flac(stream):
    if stream.get('codec_name') == 'flac' and int(stream.get('bits_per_raw_sample', 0)) not in (16, 24):
        raise ValueError('Original FLAC precision must be 16 or 24 bits')


def _metadata(archive, config):
    # Reuse the existing strict private contract without admitting audio there.
    json_limit = _configured_limit(config, 'jsonMaxBytes', MAX_JSON_BYTES)
    cover_limit = _configured_limit(config, 'pngMaxBytes', MAX_COVER_BYTES)
    pixels = _configured_limit(config, 'artworkMaxPixels', MAX_COVER_PIXELS_PER_SIDE)
    raw = {}
    for name, limit in [('song.json', json_limit), ('cover.png', cover_limit)]:
        if archive.getinfo(name).file_size > limit:
            raise PackageValidationError('Delivery metadata exceeds its limit')
        raw[name] = archive.read(name)
    from stemwise_engine.packages import validate_child_payloads
    document, cover = validate_child_payloads(raw['song.json'], raw['cover.png'],
        json_max_bytes=json_limit, png_max_bytes=cover_limit,
        artwork_max_pixels=pixels, recording_input=config.get('recordingInput'))
    return document, cover, raw


def read_bundle(path, config, *, metadata=None, original=None, expected_review=None):
    document, cover, identity, review = read_reviewed_bundle(path, config, metadata=metadata, original=original)
    if expected_review is not None and review != expected_review:
        raise PackageValidationError('Delivered timing clearance differs from the current review')
    return document, cover, identity


def read_reviewed_bundle(path, config, *, metadata=None, original=None):
    if _require_regular_path(path, 'Delivery ZIP').st_size > MAX_DELIVERY_BYTES:
        raise PackageValidationError('Delivery ZIP exceeds its limit')
    try:
        with zipfile.ZipFile(path) as archive:
            infos = archive.infolist()
            names = [info.filename for info in infos]
            audio_names = set(names) & NAMES
            if (len(names) != len(set(name.casefold() for name in names))
                    or len(audio_names) != 1
                    or set(names) != {'song.json', 'cover.png'} | audio_names):
                raise PackageValidationError('Delivery requires metadata and exactly one Original')
            for info in infos:
                _require_regular_member(info, 'Delivery entry')
                if info.flag_bits & 1:
                    raise PackageValidationError('Encrypted delivery entry')
            document, cover, raw = _metadata(archive, config)
            from stemwise_engine.song_enrichment_v2 import require_publication_lyrics
            try:
                require_publication_lyrics(document.get('lyrics'), duration_ms=document.get('analysis', {}).get('durationMs'))
            except ValueError as error:
                raise PackageValidationError(str(error)) from error
            if metadata is not None:
                with zipfile.ZipFile(metadata) as expected:
                    if any(raw[name] != expected.read(name) for name in raw):
                        raise PackageValidationError('Delivery metadata differs from validated enrichment')
            name = next(iter(audio_names))
            info = archive.getinfo(name)
            if not 0 < info.file_size <= MAX_ORIGINAL_BYTES:
                raise PackageValidationError('Original exceeds its byte limit')
            digest, count, prefix = hashlib.sha256(), 0, b''
            with archive.open(info) as audio:
                while block := audio.read(1024 * 1024):
                    count += len(block)
                    if count > MAX_ORIGINAL_BYTES:
                        raise PackageValidationError('Original exceeds its byte limit')
                    if not prefix:
                        prefix = block[:64]
                    digest.update(block)
            if (count != info.file_size or (name == 'original.flac' and not prefix.startswith(b'fLaC'))
                    or (name == 'original.opus' and not (prefix.startswith(b'OggS') and b'OpusHead' in prefix))):
                raise PackageValidationError('Original format or byte count differs')
            identity = {'name': name, 'sha256': digest.hexdigest(), 'byteCount': count}
            if original is not None and identity != original:
                raise PackageValidationError('Original differs from its delivery binding')
            native = document['source'].get('recording')
            if native and (name != 'original.flac' or any(identity[key] != native[key] for key in ('sha256', 'byteCount'))):
                raise PackageValidationError('Original differs from native recording binding')
            from stemwise_engine.delivery_timing import require_transport_receipt
            review = require_transport_receipt(archive.comment, hashlib.sha256(raw['song.json']).hexdigest(),
                                              identity['sha256'])
            return document, cover, identity, review
    except zipfile.BadZipFile as error:
        raise PackageValidationError('Invalid delivery ZIP') from error


def _probe(source):
    import json
    result = run_process_tree(['ffprobe', '-v', 'error', '-select_streams', 'a',
        '-show_entries', 'stream=codec_name,channels,sample_rate,bits_per_raw_sample:format=duration',
        '-of', 'json', str(source)], capture_output=True, check=True, timeout=30)
    value = json.loads(result.stdout)
    streams = value['streams']
    if len(streams) == 1:
        require_supported_flac(streams[0])
    duration = float(value['format']['duration'])
    if (len(streams) != 1 or streams[0]['channels'] not in (1, 2)
            or not 8000 <= int(streams[0]['sample_rate']) <= MAX_SAMPLE_RATE
            or not math.isfinite(duration) or not 0 < duration * 1000 <= MAX_DURATION_MS + 1):
        raise ValueError('Original must contain one finite mono/stereo audio stream')
    return streams[0]['codec_name'], int(duration * 1000 + .5)


def _decoded_duration(source):
    result = run_process_tree(['ffmpeg', '-v', 'error', *STRICT_DECODE_OPTIONS, '-nostdin', '-i', str(source),
        '-map', '0:a:0', '-vn', '-ac', '1', '-c:a', 'pcm_s32le',
        '-af', 'asetnsamples=n=65536:p=0,asetpts=N/SR/TB',
        '-frames:a', str((MAX_DURATION_MS + 1) * MAX_SAMPLE_RATE // (1000 * 65536) + 2),
        '-f', 'framecrc', '-'],
        capture_output=True, check=True, timeout=600)
    rate, frames = None, 0
    for line in result.stdout.splitlines():
        if line.startswith(b'#sample_rate 0:'):
            rate = int(line.split(b':', 1)[1])
        elif line and not line.startswith(b'#'):
            fields = line.split(b',')
            if len(fields) != 6 or int(fields[0]) != 0 or int(fields[4]) != int(fields[3]) * 4:
                raise ValueError('Decoded Original sample count is invalid')
            frames += int(fields[3])
    if not rate or frames <= 0 or frames * 1000 > (MAX_DURATION_MS + 1) * rate:
        raise ValueError('Original produced no decoded audio')
    from stemwise_engine.common import source_duration_ms
    return source_duration_ms(frames, rate)


def prepare_original(source, document, temporary):
    if not 0 < _require_regular_path(source, 'Original source').st_size <= MAX_ORIGINAL_BYTES:
        raise ValueError('Original source exceeds its limit')
    native = document['source'].get('recording')
    if native and (sha256_file(source) != native['sha256'] or source.stat().st_size != native['byteCount']):
        raise ValueError('Original source differs from native recording binding')
    codec, _ = _probe(source)
    duration = _decoded_duration(source)
    expected_duration = document['analysis']['durationMs']
    # Retained AAC can include a short encoder-padding tail; never trim or retime it.
    if ((native and abs(duration - expected_duration) > 1)
            or (not native and not -1 <= duration - expected_duration <= 80)):
        raise ValueError('Original duration differs from validated timeline')
    if native and codec != 'flac':
        raise ValueError('Native recording binding requires FLAC')
    if codec == 'flac':
        audio, name = source, 'original.flac'
    else:
        audio, name = temporary / 'original.opus', 'original.opus'
        # Copy native Opus packets; encode only non-Opus fallback sources.
        encoding = ['-c:a', 'copy'] if codec == 'opus' else ['-c:a', 'libopus', '-b:a', '192k']
        run_process_tree(['ffmpeg', '-v', 'error', '-nostdin', '-y', '-i', str(source),
            '-map', '0:a:0', '-vn', *encoding, '-map_metadata', '-1', '-fflags', '+bitexact', str(audio)],
            capture_output=True, check=True, timeout=600)
        output_codec, _ = _probe(audio)
        if output_codec != 'opus':
            raise ValueError('Opus fallback changed the recording timeline')
        if abs(_decoded_duration(audio) - duration) > 1:
            if codec != 'opus':
                raise ValueError('Opus fallback changed the recording timeline')
            # Ogg remuxing can lose WebM discard padding; retain PCM without another lossy encode.
            audio.unlink()
            audio, name = temporary / 'original.flac', 'original.flac'
            run_process_tree(['ffmpeg', '-v', 'error', '-nostdin', '-y', '-i', str(source),
                '-map', '0:a:0', '-vn', '-c:a', 'flac', '-map_metadata', '-1', str(audio)],
                capture_output=True, check=True, timeout=600)
            if _probe(audio)[0] != 'flac' or abs(_decoded_duration(audio) - duration) > 1:
                raise ValueError('Lossless Original fallback changed the recording timeline')
    if not 0 < audio.stat().st_size <= MAX_ORIGINAL_BYTES:
        raise ValueError('Original exceeds its byte limit')
    return audio, {'name': name, 'sha256': sha256_file(audio), 'byteCount': audio.stat().st_size}


def create_bundle(destination, metadata, audio, identity, config, staging_root, *, clearance):
    from stemwise_engine.delivery_timing import TimingClearance
    document, _ = read_child(metadata, config)
    if not isinstance(clearance, TimingClearance):
        raise PackageValidationError('Audio delivery requires the reviewed recording timing clearance')
    if (identity.get('name') not in NAMES or _require_regular_path(audio, 'Original').st_size != identity['byteCount']
            or sha256_file(audio) != identity['sha256']):
        raise ValueError('Original input differs from its binding')
    destination.parent.mkdir(parents=True, exist_ok=True)
    # The caller's owned private workspace is scavenged after process death.
    with tempfile.TemporaryDirectory(prefix='.original-', dir=staging_root) as work:
        staged = Path(work) / 'delivery.zip'
        with zipfile.ZipFile(metadata) as source, zipfile.ZipFile(staged, 'w', allowZip64=False) as target:
            raw = source.read('song.json')
            from stemwise_engine.common import parse_strict_json_bytes
            if parse_strict_json_bytes(raw) != document:
                raise PackageValidationError('Reviewed delivery manifest changed during staging')
            target.comment = clearance.transport_receipt(document, raw, identity['sha256'])
            target.writestr(_canonical_zip_info('song.json', zipfile.ZIP_DEFLATED), raw, compresslevel=6)
            target.writestr(_canonical_zip_info('cover.png', zipfile.ZIP_DEFLATED), source.read('cover.png'),
                            compresslevel=6)
            with audio.open('rb') as reader, target.open(_canonical_zip_info(identity['name'], zipfile.ZIP_STORED), 'w') as writer:
                shutil.copyfileobj(reader, writer, 1024 * 1024)
        read_bundle(staged, config, metadata=metadata, original=identity)
        durable_replace(staged, destination)


def require_selected_original(workflow, document):
    from stemwise_engine.delivery_selection import catalogue
    video_id = document['source']['videoId']
    selected = catalogue(workflow, public_ids=[video_id])
    if video_id in selected and selected[video_id] != f'youtube:{video_id}':
        raise ValueError('Selected delivery recording differs')


def retain_replacement_rollback(workflow, path, replacement_sha256, temporary, *, previous_sha256):
    digest = sha256_file(path)
    if digest != previous_sha256:
        raise ValueError('Existing delivery changed before retaining rollback')
    token = hashlib.sha256(b'stemwise-delivery-rollback\0' + digest.encode('ascii')
        + b'\0' + replacement_sha256.encode('ascii')).hexdigest()[:24]
    directory = validate_generated_path(workflow.history, workflow.history / path.name.split('.')[0],
        workspace_root=workflow.workspace_root)
    directory.mkdir(parents=True, exist_ok=True)
    rollback = validate_generated_path(directory, directory / f'{token}.{path.name}',
        workspace_root=workflow.workspace_root)
    if rollback.exists():
        _require_regular_path(rollback, 'Recording replacement rollback')
        if sha256_file(rollback) != digest:
            raise ValueError('Existing recording replacement rollback differs')
    else:
        staged = temporary / 'public-rollback.zip'
        shutil.copy2(path, staged)
        if sha256_file(staged) != digest:
            raise ValueError('Recording replacement changed while retaining rollback')
        durable_replace(staged, rollback)
    if sha256_file(rollback) != digest:
        raise ValueError('Recording replacement rollback readback differs')
    if sha256_file(path) != digest:
        raise ValueError('Existing delivery changed while retaining rollback')


def publish_bundle(workflow, staged, destination, *, metadata, original, previous_sha256, selected_before):
    """Publish one complete public package while retaining its exact previous bytes."""
    from stemwise_engine.delivery_selection import publication_selection
    config = workflow._runtime_config()
    document, _, _ = read_bundle(staged, config, metadata=metadata, original=original)
    video_id = document['source']['videoId']
    context = workflow.source_context
    key = context.recording_key if context is not None else f'youtube:{video_id}'
    digest = sha256_file(staged)
    with publication_selection(workflow, {video_id: key}, selected_before=selected_before):
        destination = validate_generated_path(destination.parent, destination, workspace_root=workflow.workspace_root)
        actual = sha256_file(destination) if destination.is_file() else None
        if actual != previous_sha256 or (actual is None and destination.exists()):
            raise ValueError('Existing public delivery changed during publication')
        if actual != digest:
            if actual is not None:
                retain_replacement_rollback(workflow, destination, digest, staged.parent,
                    previous_sha256=actual)
            durable_replace(staged, destination)
        read_bundle(destination, config, metadata=metadata, original=original)
        if sha256_file(destination) != digest:
            raise ValueError('Public delivery readback differs from the prepared package')
    return digest


def cleanup_replacement_rollback(workflow, video_id):
    import re
    directory = validate_generated_path(workflow.history, workflow.history / video_id,
        workspace_root=workflow.workspace_root)
    if not directory.exists():
        return
    for path in directory.glob(f'*.{video_id}.stemwise.zip'):
        if re.fullmatch(r'[0-9a-f]{24}\.' + re.escape(video_id) + r'\.stemwise\.zip', path.name) is None:
            continue
        path = validate_generated_path(directory, path, workspace_root=workflow.workspace_root)
        _require_regular_path(path, 'Recording replacement rollback')
        path.unlink()
    if not any(directory.iterdir()):
        directory.rmdir()


def selected_original(workflow, document, *, acquisition_dir=None):
    if document['source'].get('recording'):
        native = document['source']['recording']
        return validate_generated_path(workflow.workspace_root,
            workflow.workspace_root / native['file'], workspace_root=workflow.workspace_root)
    video_id = document['source']['videoId']
    require_selected_original(workflow, document)
    temporary = workflow.analysis_temp / video_id
    binding = temporary / 'original-source.json'
    if binding.is_file():
        value = read_bounded_json(binding, max_bytes=MAX_JSON_BYTES, root=temporary)
        if (set(value) != {'videoId', 'file', 'sha256', 'byteCount'} or value['videoId'] != video_id
                or not isinstance(value['file'], str) or Path(value['file']).name != value['file']):
            raise ValueError('Retained Original source binding differs')
        source = validate_generated_path(temporary, temporary / value['file'], workspace_root=workflow.workspace_root)
        if source.stat().st_size != value['byteCount'] or sha256_file(source) != value['sha256']:
            raise ValueError('Retained Original source bytes changed')
        return source
    info = temporary / 'download-info.json'
    if info.is_file() and read_bounded_json(info, max_bytes=MAX_JSON_BYTES, root=temporary).get('id') == video_id:
        candidates = [p for p in temporary.glob('source.*') if p.is_file() and p.suffix not in ('.part', '.ytdl')]
        if len(candidates) == 1:
            return validate_generated_path(temporary, candidates[0], workspace_root=workflow.workspace_root)
    from stemwise_media.quick_batch import source_file
    source = source_file(video_id)
    if source is None:
        if acquisition_dir is None:
            raise ValueError('PC Original acquisition requires owned staging')
        from stemwise_engine.youtube import download_analysis_audio
        _, pcm = download_analysis_audio(video_id, acquisition_dir, timeout_seconds=600)
        pcm.unlink()  # Decode verifies acquisition; transport retains the compressed source.
        record = read_bounded_json(acquisition_dir / 'original-source.json', max_bytes=MAX_JSON_BYTES, root=acquisition_dir)
        if not isinstance(record.get('file'), str) or Path(record['file']).name != record['file']:
            raise ValueError('PC Original acquisition has an invalid source path')
        source = validate_generated_path(acquisition_dir, acquisition_dir / record['file'], workspace_root=workflow.workspace_root)
        if record['videoId'] != video_id or sha256_file(source) != record['sha256']:
            raise ValueError('PC Original acquisition differs from its binding')
    if source is None:
        raise ValueError('PC Original acquisition produced no retained source')
    return source


def artwork_delivery_binding(job):
    delivery = job['delivery']
    return {key: delivery[key] for key in ('sha256', 'original') if key in delivery}


def extract_original(bundle, config, temporary, *, original=None):
    document, cover, identity = read_bundle(bundle, config, original=original)
    audio = temporary / identity['name']
    with zipfile.ZipFile(bundle) as archive, archive.open(identity['name']) as source, audio.open('wb') as target:
        shutil.copyfileobj(source, target, 1024 * 1024)
    if audio.stat().st_size != identity['byteCount'] or sha256_file(audio) != identity['sha256']:
        raise ValueError('Retained delivered Original changed during extraction')
    return audio, identity, document, cover


def _artwork_original(workflow, job, document, temporary, destination):
    previous = job['artworkRepair'].get('previousDelivery')
    if not isinstance(previous, dict) or not isinstance(previous.get('original'), dict):
        raise ValueError('Artwork repair requires its reviewed previous Original binding')
    candidates = [destination, *list((workflow.history / job['videoId']).glob(f'*.{destination.name}'))]
    matches = [path for path in candidates if path.is_file() and sha256_file(path) == previous['sha256']]
    if not matches:
        raise ValueError('Artwork repair previous delivered Original is unavailable')
    audio, identity, old, _ = extract_original(matches[0], workflow._runtime_config(), temporary,
        original=previous['original'])
    if ({key: value for key, value in old.items() if key not in {'artwork', 'generatedAt'}} !=
            {key: value for key, value in document.items() if key not in {'artwork', 'generatedAt'}}):
        raise ValueError('Artwork repair changed reviewed song content')
    return audio, identity


def _require_reviewed_pcm(workflow, job, source, temporary):
    from stemwise_engine.youtube import decode_analysis_audio
    report = resolve_path(job['validation']['report'], base=workflow.workspace_root)
    expected = read_bounded_json(report, max_bytes=MAX_JSON_BYTES, root=workflow.workspace_root)['retainedAnalysisAudio']
    digest = sha256_file(source)
    pcm = decode_analysis_audio(source, temporary / 'reviewed-original', timeout_seconds=600)
    if pcm.stat().st_size != expected['byteCount'] or sha256_file(pcm) != expected['sha256']:
        raise ValueError('Selected Original differs from reviewed analysis PCM')
    if sha256_file(source) != digest:
        raise ValueError('Selected Original changed after reviewed PCM comparison')
    return digest


def stage_original(workflow, child, temporary, destination, *, job, clearance):
    document, _ = read_child(child, workflow._runtime_config())
    if job.get('artworkRepair'):
        audio, identity = _artwork_original(workflow, job, document, temporary, destination)
    elif job.get('capabilityRefresh'):
        _, _, previous, _ = delivered_capability_inputs(workflow, job)
        audio, identity, _, _ = extract_original(previous, workflow._runtime_config(), temporary,
            original=job['capabilityRefresh']['previousDelivery']['original'])
    else:
        if job.get('analysisReplacement'):
            from stemwise_engine.job_ownership import replacement_source
            source, _, _ = replacement_source(workflow, job)
        else:
            source = selected_original(workflow, document, acquisition_dir=temporary)
        source_digest = _require_reviewed_pcm(workflow, job, source, temporary)
        audio, identity = prepare_original(source, document, temporary)
        if sha256_file(source) != source_digest:
            raise ValueError('Selected Original changed after reviewed PCM comparison')
    staged = temporary / 'audio.stemwise.zip'
    preflight_staging_space(destination, staged,
        candidate_bytes=child.stat().st_size + audio.stat().st_size,
        rollback_bytes=destination.stat().st_size if destination.is_file() else 0)
    create_bundle(staged, child, audio, identity, workflow._runtime_config(), temporary, clearance=clearance)
    return staged, identity


def verify_original_delivery(workflow, job, *, expected_review=None):
    if workflow.source_context is not None:
        return
    child = workflow.runtime/'children'/f"{job['videoId']}.stemwise.zip"
    destination = workflow.ready/child.name
    identity = job.get('delivery', {}).get('original')
    if not isinstance(identity, dict):
        raise ValueError('Current delivery requires its selected Original identity')
    if sha256_file(destination) != job['delivery']['sha256']:
        raise ValueError('Delivered transport changed')
    read_bundle(destination, workflow._runtime_config(), metadata=child, original=identity,
                expected_review=expected_review)


def delivered_capability_inputs(workflow, job):
    """Bind metadata-only updates to the exact previously published Original and numerical owner."""
    video_id = job['videoId']
    refresh = job.get('capabilityRefresh') if job.get('state') != 'DELIVERED' else None
    if not refresh:
        delivery = job.get('delivery')
        if delivery is None:
            repair = job['artworkRepair']
            run_path = workflow.report_store.report_root(video_id)/'runs'/repair['runId']/'run.json'
            run = read_bounded_json(run_path, max_bytes=MAX_JSON_BYTES, root=workflow.report_store.report_root(video_id))
            if sha256_file(run_path) != repair['runSha256']:
                raise ValueError('Artwork publication run changed')
            delivery = {**repair['previousDelivery'],
                'runId': run['checkpointKey']['deliveryRunId'],
                'runSha256': run['checkpointKey']['deliveryRunSha256']}
        refresh = {'previousValidation': job['validation'], 'previousDelivery': delivery}
    validation, delivery = refresh['previousValidation'], refresh['previousDelivery']
    operation, requirements = 'capability-completion', None
    if validation.get('kind') == 'capability-completion':
        artifacts = {key: validation[key] for key in ('inputSha256', 'sidecarSha256', 'childSha256',
            'artworkSha256', 'audioSha256', 'lyricsSha256')}
    elif (validation.get('kind') is None and isinstance(validation.get('displayReviewAttemptId'), str)
            and validation['displayReviewAttemptId'] == validation.get('runId')):
        operation = 'display-review'
        root = workflow.report_store.report_root(video_id)
        run_id = workflow._validate_alignment_attempt_id(validation['runId'])
        owner_path = root/'runs'/run_id/'run.json'
        if sha256_file(owner_path) != validation['runSha256']:
            raise ValueError('Previous display validation owner changed')
        owner = read_bounded_json(owner_path, max_bytes=MAX_JSON_BYTES, root=root)
        declaration = owner['checkpointKey']['declaration']
        alignment, mapping = declaration['alignment'], declaration['mapping']
        if (owner.get('summary', {}).get('result') != 'accepted-display-only'
                or declaration.get('videoId') != video_id or declaration.get('attemptId') != run_id
                or declaration.get('sourcesSha256') != validation['sourcesSha256']
                or declaration.get('analysisAudio', {}).get('sha256') != validation['audioSha256']
                or alignment.get('attemptId') != validation['alignmentRunId']
                or alignment.get('runId') != validation['alignmentRunId']
                or alignment.get('runSha256') != validation['alignmentRunSha256']):
            raise ValueError('Previous display validation owner changed')
        alignment_artifacts = {'sourcesSha256': validation['sourcesSha256'],
            'sidecarSha256': declaration['preReviewSidecarSha256'],
            'alignmentCandidateSha256': declaration['preReviewSidecarSha256'],
            'mappingArtifactSha256': mapping['mappingArtifactSha256'],
            'alignmentStateSha256': alignment['stateSha256'],
            'analysisAudioSha256': validation['audioSha256'], 'artworkSha256': validation['artworkSha256']}
        if (alignment['artifacts'] != alignment_artifacts
                or not workflow.report_store.immutable_run_matches(video_id,
                    run_id=alignment['runId'], run_sha256=alignment['runSha256'], operation='lyrics-alignment',
                    artifacts=alignment_artifacts, schema_sha256=job['policy']['schemaSha256'],
                    require_current_pointer=False)):
            raise ValueError('Previous display alignment is not intact')
        artifacts = {key: validation[key] for key in ('sourcesSha256', 'sidecarSha256',
            'childSha256', 'artworkSha256', 'alignmentRunSha256')}
        artifacts.update(declarationSha256=validation['displayReviewDeclarationSha256'],
            originalSha256=validation['displayOriginalSha256'],
            mappingArtifactSha256=alignment['artifacts']['mappingArtifactSha256'])
        requirements = {'declarationSha256': validation['displayReviewDeclarationSha256'],
            'finalDisplaySha256': validation['displayOriginalSha256']}
    else:
        raise ValueError('Previous lyric validation owner is unsupported')
    if not workflow.report_store.immutable_run_matches(video_id, run_id=validation['runId'],
            run_sha256=validation['runSha256'], operation=operation, artifacts=artifacts,
            schema_sha256=job['policy']['schemaSha256'], require_current_pointer=False,
            checkpoint_requirements=requirements):
        raise ValueError('Previous lyric validation is not intact')
    run_path = workflow.report_store.report_root(video_id)/'runs'/delivery['runId']/'run.json'
    run = read_bounded_json(run_path, max_bytes=MAX_JSON_BYTES, root=workflow.report_store.report_root(video_id))
    if (sha256_file(run_path) != delivery['runSha256'] or run.get('operation') != 'package-finalization'
            or run.get('artifacts', {}).get('sha256') != delivery['sha256']
            or run.get('checkpointKey', {}).get('validationRunId') != validation['runId']
            or run.get('checkpointKey', {}).get('validationRunSha256') != validation['runSha256']):
        raise ValueError('Previous publication differs from its validated numerical owner')
    pipeline = workflow._pipeline()
    current, current_cover = read_child(pipeline.child_path(video_id), pipeline.config)
    repair = workflow._validated_artwork_lineage(job, child_document=current,
        child_sha256=sha256_file(pipeline.child_path(video_id)),
        artwork_sha256=hashlib.sha256(current_cover).hexdigest())
    if job.get('capabilityRefresh', {}).get('previousArtworkRepair') is not None:
        refresh['previousArtworkRepair'] = job['capabilityRefresh']['previousArtworkRepair']
    destination = workflow.ready/f'{video_id}.stemwise.zip'
    candidates = [destination, *list((workflow.history/video_id).glob(f'*.{destination.name}'))]
    previous = next((path for path in candidates if path.is_file() and sha256_file(path) == delivery['sha256']), None)
    if previous is None:
        current_delivery = job.get('delivery') or (repair or {}).get('previousDelivery', {})
        if current_delivery.get('original') != delivery['original']:
            raise ValueError('Previously published Original is unavailable')
        previous = next((path for path in candidates if path.is_file()
            and sha256_file(path) == current_delivery.get('sha256')), None)
        if previous is None:
            raise ValueError('Previously published Original is unavailable')
    if workflow.source_context is None:
        published, cover, _ = read_bundle(previous, workflow._runtime_config(), original=delivery['original'])
    else:
        published, cover = read_child(previous, workflow._runtime_config())
        prior_job = {**job, 'validation': validation, 'delivery': delivery}
        binding = read_bounded_json(workflow.ready/'recording.json', max_bytes=MAX_JSON_BYTES, root=workflow.ready)
        if (binding != workflow._result_binding(prior_job)
                or published['source'].get('recording') != job.get('recordingInput')
                or job.get('recordingInput') != workflow.recording.identity):
            raise ValueError('Previous native publication differs from the selected recording')
    mutable_fields = {'lyrics', 'generatedAt'} | ({'artwork'} if repair is not None else set())
    if current['metadata'] != published['metadata']:
        from stemwise_engine.metadata_research import apply_reviewed_metadata
        if job.get('ownerWordPrediction') is not None:
            # Lyric lookup cannot veto exact owner authority; recording metadata still must match.
            audio = pipeline.load_checkpoint(video_id)['originalAudio']
            review, sources = workflow._owner_prediction_reviews(job, audio)
            outcome = 'resolved'
        else:
            outcome, review, sources = workflow._validate_authority_outcome(job)
        if outcome != 'resolved' or current['metadata'] != apply_reviewed_metadata(published['metadata'],
                review.get('metadataReview'), video_id=video_id,
                sources_sha256=sha256_file(workflow._job_dir(video_id)/'sources.json'),
                staged_source_ids={entry['videoId'] for entry in sources['lyricEvidenceSources']}):
            raise ValueError('Capability refresh metadata has no current recording review')
        mutable_fields.add('metadata')
    if ({key: value for key, value in current.items() if key not in mutable_fields} !=
            {key: value for key, value in published.items() if key not in mutable_fields}):
        raise ValueError('Capability refresh cannot change prior recording or numerical results')
    checkpoint = pipeline.load_checkpoint(video_id)
    audio = checkpoint['originalAudio']
    if (checkpoint.get('status') != 'complete' or not workflow.source.checkpoint_matches(pipeline, checkpoint)
            or audio['sha256'] != validation['audioSha256']
            or audio['durationMs'] != published['analysis']['durationMs']):
        raise ValueError('Capability refresh analysis identity changed')
    return {**{key: audio[key] for key in ('sha256', 'byteCount', 'durationMs')},
            'artworkSha256': hashlib.sha256(current_cover).hexdigest()}, refresh, previous, published

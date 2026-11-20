"""Selected native recording identity and its exact lyric-reference binding."""
from pathlib import Path
from typing import Any, NamedTuple
from stemwise_engine.common import read_bounded_json, validate_video_id
from stemwise_engine.contract import MAX_JSON_BYTES
import math
import io
import json
import subprocess
from stemwise_engine.common import run_process_tree, atomic_write_bytes, WorkerRetirementError
import re
from stemwise_engine.common import atomic_write_json, exclusive_process_lock, resolve_path, sha256_file, validate_generated_path
from stemwise_engine.contract import MAX_DURATION_MS
from stemwise_engine.enrichment_constants import MAX_RECORDING_TRACK_ID
from stemwise_engine.youtube import MAX_ANALYSIS_SOURCE_BYTES, decode_analysis_audio, metadata_from_info
from stemwise_engine import youtube
from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline

from stemwise_media.quick_batch import qobuz_file
from stemwise_media.compare import probe


def registration_details(track_id, reference, title, artist, album=''):
    if type(track_id) is not int or not 0 < track_id <= MAX_RECORDING_TRACK_ID:
        raise ValueError('Use a positive Qobuz track ID')
    validate_video_id(reference)
    for name, value in [('title', title), ('artist', artist), ('album', album)]:
        if (not isinstance(value, str) or len(value) > 512 or any(ord(c) < 32 for c in value)
                or (name != 'album' and not value.strip())):
            raise ValueError('Future FLAC needs a title, artist and real YouTube lyric/artwork reference')
    return {'formatVersion': 1, 'trackId': track_id, 'referenceVideoId': reference,
            'title': title.strip(), 'artist': artist.strip(), 'album': album.strip()}


def registered_declaration(library, track_id):
    """Read current selection intent; selected_recording verifies actual media."""
    if type(track_id) is not int or not 0 < track_id <= MAX_RECORDING_TRACK_ID:
        raise ValueError('Use a positive Qobuz track ID')
    path = library / 'audio/qobuz' / str(track_id) / 'enrichment-input.json'
    path = validate_generated_path(library, path, workspace_root=library.parent.parent)
    if not path.exists():
        return None
    item = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=library)
    if not isinstance(item, dict) or set(item) != {
            'formatVersion', 'trackId', 'referenceVideoId', 'title', 'artist', 'album', 'file'}:
        raise ValueError('Invalid future FLAC registration')
    if type(item['formatVersion']) is not int or type(item['trackId']) is not int:
        raise ValueError('Invalid future FLAC registration identity')
    expected = registration_details(track_id, item['referenceVideoId'], item['title'], item['artist'], item['album'])
    name = Path(item['file']).name if isinstance(item['file'], str) else ''
    if (not name.lower().endswith('.flac') or any(c in name for c in '<>:"/\\|?*')
            or any(ord(c) < 32 for c in name)
            or item != {**expected, 'file': f'audio/qobuz/{track_id}/{name}'}):
        raise ValueError('Future FLAC registration binding differs')
    validate_generated_path(library, library / item['file'], workspace_root=library.parent.parent)
    return item


def registered_input(library, track_id, *, verify_media=True):
    item = registered_declaration(library, track_id)
    if item is None:
        return None
    file = qobuz_file(track_id, root=library, verify_digest=verify_media)
    if file is None:
        raise ValueError('Registered FLAC is missing or owner rejected')
    file = validate_generated_path(library, file, workspace_root=library.parent.parent)
    if item['file'] != file.relative_to(library).as_posix():
        raise ValueError('Future FLAC registration binding differs')
    return item


def retained_input(library, key, *, verify_media=True):
    if not re.fullmatch(r'qobuz:[1-9][0-9]*', key):
        raise ValueError('Use an exact qobuz:TRACK_ID recording key')
    track_id = int(key.split(':')[1])
    if track_id > MAX_RECORDING_TRACK_ID:
        raise ValueError('Qobuz track ID exceeds the package contract maximum')
    workspace = library.parent.parent
    from stemwise_media.save_download import validate_retained_download
    base = validate_generated_path(library, library/'audio/qobuz'/str(track_id), workspace_root=workspace)
    receipt_path = base/'download.json'
    if not receipt_path.is_file():
        raise ValueError('Selected recording is not retained')
    receipt = read_bounded_json(receipt_path, max_bytes=64*1024, root=library)
    audio = validate_retained_download(base, track_id, receipt, verify_digest=verify_media)
    audio = validate_generated_path(library, audio, workspace_root=workspace)
    if not 0 < audio.stat().st_size <= MAX_ANALYSIS_SOURCE_BYTES:
        raise ValueError('Selected input exceeds the native audio byte limit')
    if verify_media:
        metadata = probe(audio)
    else:
        import soundfile as sf
        with audio.open('rb') as stream:
            info = sf.info(stream)
        metadata = {'codec_name': 'flac' if info.format == 'FLAC' else info.format,
                    'channels': info.channels, 'duration': info.duration,
                    'bits_per_raw_sample': {'PCM_16': 16, 'PCM_24': 24, 'PCM_32': 32}.get(info.subtype, 0)}
    from stemwise_engine.original_delivery import require_supported_flac
    require_supported_flac(metadata)
    if (metadata['codec_name'] != 'flac' or metadata['channels'] not in (1, 2)
            or not math.isfinite(metadata['duration'])
            or not 0 < metadata['duration'] <= MAX_DURATION_MS / 1000):
        raise ValueError('Selected input must be native finite mono/stereo FLAC within the duration limit')
    digest = receipt['sha256']
    import soundfile as sf
    from stemwise_engine.common import source_duration_ms
    with audio.open('rb') as stream:
        info = sf.info(stream)
    identity = {'recordingKey': key, 'trackId': track_id,
                'file': audio.relative_to(workspace).as_posix(), 'sha256': digest,
                'byteCount': audio.stat().st_size, 'durationMs': source_duration_ms(info.frames, info.samplerate)}
    return audio, identity


def register_input(library, track_id, reference, title, artist, album=''):
    library = Path(library).resolve()
    workspace = library.parent.parent
    validate_generated_path(workspace, library, workspace_root=workspace)
    details = registration_details(track_id, reference, title, artist, album)
    audio, identity = retained_input(library, f'qobuz:{track_id}')
    item = {**details, 'file': audio.relative_to(library).as_posix()}
    path = audio.parent / 'enrichment-input.json'
    with exclusive_process_lock(audio.parent / 'enrichment-input.lock'), \
            exclusive_process_lock(library / 'comparison-results/delivery-selection.lock'):
        catalogue_path = library / 'catalogue.json'
        if catalogue_path.exists():
            catalogue = read_bounded_json(catalogue_path, max_bytes=MAX_JSON_BYTES, root=library)
            related = sorted((row for row in catalogue['rows']
                if any(c['id'] == track_id for c in row['candidates'])), key=lambda row: row['position'])
            if related and reference != related[0]['videoId']:
                raise ValueError('Existing catalogue lyric reference cannot be replaced by registration')
        previous = registered_input(library, track_id)
        if previous is not None and previous != item:
            raise ValueError('Existing future FLAC registration cannot be rebound')
        from stemwise_engine.delivery_selection import catalogue
        from types import SimpleNamespace
        catalogue(SimpleNamespace(pc_side_root=library.parent, workspace_root=workspace), registration=item)
        if previous is None:
            atomic_write_json(path, item)
    return {'recordingKey': identity['recordingKey'], 'registration': str(path),
            'analysisStarted': False, 'phoneDeliveryAllowed': False, 'reusedRegistration': previous is not None}


def selected_recording(config, key, *, verify_media=True):
    if not re.fullmatch(r'qobuz:[1-9][0-9]*', key):
        raise ValueError('Use an exact qobuz:TRACK_ID recording key')
    track_id = int(key.split(':')[1])
    project = Path(config['projectDir']).resolve()
    workspace = project.parent.parent
    pc = resolve_path(config['pcSideRootWindows'], base=project)
    library = validate_generated_path(workspace, pc / 'qobuz-comparison', workspace_root=workspace)
    files_path = library / 'comparison-results/library-files.json'
    files = read_bounded_json(files_path, max_bytes=MAX_JSON_BYTES, root=library) if files_path.exists() else {'flac': []}
    matches = [item for item in files['flac'] if item['trackId'] == track_id]
    registered = registered_declaration(library, track_id)
    if len(matches) > 1 or (not matches and registered is None):
        raise ValueError('Recording must be one wanted retained FLAC or explicitly registered future FLAC')
    item = matches[0] if matches else registered
    audio, identity = retained_input(library, key, verify_media=verify_media)
    if registered is not None and registered['file'] != audio.relative_to(library).as_posix():
        raise ValueError('Future FLAC registration binding differs')
    if audio != validate_generated_path(library, library / item['file'], workspace_root=workspace):
        raise ValueError('Selected recording file binding differs')
    return workspace, pc, audio, identity


class RecordingSelection(NamedTuple):
    workspace: Path
    pc: Path
    audio: Path
    identity: dict[str, Any]
    reference_ids: list[str]
    metadata: dict[str, Any]


def selection(config: dict[str, Any], key: str, *, verify_media: bool = True) -> RecordingSelection:
    workspace, pc, audio, identity = selected_recording(config, key, verify_media=verify_media)
    library = pc / 'qobuz-comparison'
    registered = registered_declaration(library, identity['trackId'])
    catalogue_path = library / 'catalogue.json'
    files_path = library / 'comparison-results/library-files.json'
    catalogue = read_bounded_json(catalogue_path, max_bytes=MAX_JSON_BYTES, root=library) if catalogue_path.exists() else {'rows': []}
    files = read_bounded_json(files_path, max_bytes=MAX_JSON_BYTES, root=library) if files_path.exists() else {'playlist': []}
    related = [row for row in catalogue['rows']
               if any(candidate['id'] == identity['trackId'] for candidate in row['candidates'])]
    preferred = {row['videoId'] for row in files['playlist']
                 if row['source'] == 'qobuz' and row['trackId'] == identity['trackId']}
    # Source-preference edits must not rename an existing recording's lyric workspace.
    related.sort(key=lambda row: row['position'])
    from stemwise_engine.workflow_context import native_state_root
    install = resolve_path(config['futureRootWindows'], base=Path(config['projectDir']))
    jobs = validate_generated_path(workspace, native_state_root(install, identity)/'jobs', workspace_root=workspace)
    owners = []
    for path in jobs.glob('*/job.json'):
        accepted = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=jobs)
        if (not isinstance(accepted, dict) or accepted.get('recordingInput') != identity
                or accepted.get('videoId') != path.parent.name):
            raise ValueError('Current native job owner differs from its exact recording namespace')
        owners.append(validate_video_id(accepted['videoId']))
    if len(owners) > 1:
        raise ValueError('Multiple current job owners exist for this native recording')
    if owners:
        if owners[0] not in {row['videoId'] for row in related} and (
                registered is None or registered['referenceVideoId'] != owners[0]):
            raise ValueError('Accepted native job owner is no longer a recording reference')
        related.sort(key=lambda row: row['videoId'] != owners[0])
    if not related and registered and not preferred:
        return RecordingSelection(workspace, pc, audio, identity, [registered['referenceVideoId']], {
            **registered, 'id': identity['trackId'], 'url': f"https://play.qobuz.com/track/{identity['trackId']}"})
    if not related or not preferred.issubset({row['videoId'] for row in related}):
        raise ValueError('Selected recording has no consistent catalogue lyric reference')
    ids = [validate_video_id(row['videoId']) for row in related]
    if registered and registered['referenceVideoId'] != ids[0]:
        raise ValueError('Registered lyric reference differs from the catalogue')
    candidate = next(c for c in related[0]['candidates'] if c['id'] == identity['trackId'])
    from stemwise_media.quick_batch import effective_track_metadata
    candidate = effective_track_metadata(catalogue, candidate)
    return RecordingSelection(workspace, pc, audio, identity, ids, candidate)


def alignment_config(config, job, future_root):
    """Bind the separate alignment child to the selected native file and namespace."""
    _, _, _, identity, ids, _ = selection(config, job['recordingInput']['recordingKey'])
    expected = Path(config['futureRootWindows']) / 'policy-state' / (
        'current' + f"-qobuz-{identity['trackId']}-{identity['sha256']}")
    # Config paths can be Windows paths even when the alignment child runs in WSL.
    from stemwise_engine.common import resolve_path
    expected = resolve_path(str(expected), base=Path(config['projectDir']))
    if (job['recordingInput'] != identity or job['videoId'] != ids[0]
            or Path(future_root).resolve() != expected):
        raise ValueError('Native alignment input or workspace differs')
    return {**config, 'recordingInput': identity, 'localRecordingKey': identity['recordingKey']}


def embedded_cover(audio, destination, *, max_pixels, max_bytes):
    """Render a FLAC's front cover without thumbnail crop heuristics or upscaling."""
    from PIL import Image, ImageOps
    try:
        probe = run_process_tree(['ffprobe', '-v', 'error', '-show_entries',
            'stream=index,width,height:stream_disposition=attached_pic:stream_tags=comment',
            '-of', 'json', str(audio)], capture_output=True, check=True, timeout=30)
        pictures = [stream for stream in json.loads(probe.stdout)['streams']
                    if stream.get('disposition', {}).get('attached_pic') == 1]
    except (WorkerRetirementError, ProviderCancelled, ProviderDeadline):
        raise
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError):
        return False
    pictures.sort(key=lambda stream: 'front' in str(stream.get('tags', {}).get('comment', '')).lower(), reverse=True)
    best = None
    for stream in pictures:
        front = 'front' in str(stream.get('tags', {}).get('comment', '')).lower()
        if best is not None and best[0] and not front:
            break
        try:
            extracted = run_process_tree(['ffmpeg', '-v', 'error', '-i', str(audio),
                '-map', f"0:{stream['index']}", '-frames:v', '1', '-c:v', 'copy',
                '-f', 'image2pipe', 'pipe:1'], capture_output=True, check=True, timeout=30)
            if len(extracted.stdout) > max_bytes:
                continue
            with Image.open(io.BytesIO(extracted.stdout)) as opened:
                # Bound the actual image header before loading pixels; probe dimensions are advisory.
                if min(opened.size) <= 0 or opened.width * opened.height > 4 * max_pixels ** 2:
                    continue
                if getattr(opened, 'n_frames', 1) != 1:
                    continue
                image = ImageOps.exif_transpose(opened)
                image = image.convert('RGBA' if 'A' in image.getbands() or 'transparency' in image.info else 'RGB')
                score = image.width * image.height
                image.thumbnail((max_pixels, max_pixels), Image.Resampling.LANCZOS, reducing_gap=3.0)
                rendered = io.BytesIO()
                image.save(rendered, format='PNG', optimize=True)
            if rendered.tell() > max_bytes:
                continue
            if best is None or (front, score) > best[:2]:
                best = (front, score, rendered.getvalue())
        except (WorkerRetirementError, ProviderCancelled, ProviderDeadline):
            raise
        except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError):
            continue
    if best is None:
        return False
    atomic_write_bytes(destination, best[2])
    return True


class RecordingSource:
    def __init__(self, config, recording=None, *, verify_media=True):
        self.config = config
        self.recording = recording if recording is not None else selection(config, config['localRecordingKey'])
        self.verify_media = verify_media
    def song_info_native(self):
        _, _, _, identity, ids, candidate = self.recording
        return {'id': ids[0], 'title': candidate['title'], 'uploader': candidate['artist'],
                'artist': candidate['artist'], 'album': candidate.get('album'),
                'duration': identity['durationMs'] / 1000}

    def song_info(self, video_id):
        if video_id != self.recording.reference_ids[0]:
            from stemwise_engine.workflow_records import FutureSongError
            raise FutureSongError('Native recording lyric reference differs')
        return self.song_info_native()

    def embedded_cover(self, destination):
        if sha256_file(self.recording.audio) != self.recording.identity['sha256']:
            raise ValueError('Selected native recording changed before artwork extraction')
        available = embedded_cover(self.recording.audio, destination,
            max_pixels=int(self.config['artworkMaxPixels']), max_bytes=int(self.config['pngMaxBytes']))
        if sha256_file(self.recording.audio) != self.recording.identity['sha256']:
            raise ValueError('Selected native recording changed during artwork extraction')
        if not available:
            return None
        return {'file': 'cover.png', 'kind': 'album-cover', 'source': {
            'name': 'Embedded FLAC artwork',
            'url': f"https://play.qobuz.com/track/{self.recording.identity['trackId']}"}}

    def create_cover(self, info, video_id, destination):
        if video_id != self.recording.reference_ids[0]:
            raise ValueError('Artwork reference differs from the selected recording')
        artwork = self.embedded_cover(destination)
        if artwork is not None:
            return artwork
        return youtube.create_cover_png(youtube.fetch_video_info(video_id), video_id, destination,
            max_pixels=int(self.config['artworkMaxPixels']), max_bytes=int(self.config['pngMaxBytes']))

    def metadata(self, info, video_id):
        value = metadata_from_info(self.song_info_native(), video_id)
        candidate = self.recording.metadata
        source = {'name': 'Qobuz catalogue'}
        if candidate.get('url'):
            source['url'] = candidate['url']
        value['sources'] = [source]
        return value

    def finalize_metadata(self, metadata):
        # Native catalogue/owner identity wins over optional external research.
        native = self.metadata(self.song_info_native(), self.recording.reference_ids[0])
        value = dict(metadata)
        for key in ('title', 'artists', 'album'):
            if key in native:
                value[key] = native[key]
            else:
                value.pop(key, None)
        return value

    def acquire_analysis_audio(self, video_id, temporary):
        current = selection(self.config, self.config['localRecordingKey'])
        if current.identity != self.recording.identity or video_id != current.reference_ids[0]:
            raise ValueError('Selected native recording changed')
        from stemwise_engine.analysis_resources import require_analysis_capacity
        require_analysis_capacity(current.identity['durationMs'], scratch_root=temporary)
        wav = decode_analysis_audio(current.audio, temporary,
            timeout_seconds=int(self.config['analysisTimeoutMinutes']) * 60)
        if sha256_file(current.audio) != current.identity['sha256']:
            raise ValueError('Selected native recording changed during decoding')
        return self.song_info_native(), wav

    def identity(self, video_id):
        return {'provider': 'youtube', 'videoId': video_id, 'recording': self.recording.identity}

    def require_job(self, video_id, job):
        from stemwise_engine.workflow_records import FutureSongError
        if (video_id != self.recording.reference_ids[0] or job.get('recordingInput') != self.recording.identity
                or selection(self.config, self.recording.identity['recordingKey'],
                    verify_media=self.verify_media).identity != self.recording.identity):
            raise FutureSongError('Editorial job does not match the selected native recording')

    def require_selected(self, video_id, library):
        from stemwise_engine.workflow_records import FutureSongError
        if video_id != self.recording.reference_ids[0]:
            raise FutureSongError('Native recording lyric reference differs')
        if not self.verify_media:
            if selection(self.config, self.recording.identity['recordingKey']).identity != self.recording.identity:
                raise FutureSongError('Selected native recording changed before the operation')
            self.verify_media = True

    def checkpoint_matches(self, pipeline, checkpoint):
        from stemwise_engine.recording_analysis import acoustic_producer_identity
        stored = checkpoint.get('acousticProducerIdentity') if isinstance(checkpoint, dict) else None
        if not isinstance(stored, dict) or not isinstance(stored.get('runtime'), dict):
            return False
        stored = {**stored, 'runtime': {key:value for key,value in stored['runtime'].items() if key != 'execution'}}
        return (isinstance(checkpoint, dict) and checkpoint.get('recordingInput') == self.recording.identity
            and stored == acoustic_producer_identity(pipeline.config, observe_execution=False))

    def collect_normal_candidates(self, info, destination, video_id):
        from stemwise_engine.source_acquisition import collect_normal_youtube_candidates
        try:
            info = youtube.fetch_video_info(video_id)
        except Exception as error:
            from yt_dlp.utils import DownloadError
            if not isinstance(error, DownloadError):
                raise
            return {'checked': True, 'surface': 'normal-youtube', 'exactVideoIdConfirmed': False,
                'exactUrl': f'https://www.youtube.com/watch?v={video_id}', 'result': 'api-error',
                'reason': 'DownloadError', 'candidateCount': 0, 'usableLyricCandidateCount': 0, 'records': []}
        return collect_normal_youtube_candidates(info, destination, video_id=video_id)

    def owns_artwork(self, source_id):
        return source_id == self.recording.identity['recordingKey']

    def owned_artwork_source(self, url):
        key = self.recording.identity['recordingKey']
        return key if url == self.artwork_url(key) else None

    def artwork_url(self, source_id):
        from stemwise_engine.youtube import canonical_url
        return (f"https://play.qobuz.com/track/{self.recording.identity['trackId']}"
            if self.owns_artwork(source_id) else canonical_url(source_id))

    def artwork_info(self, source_id):
        return ({'duration': self.recording.identity['durationMs'] / 1000}
            if self.owns_artwork(source_id) else youtube.fetch_video_info(source_id))

    def create_selected_artwork(self, info, source_id, destination):
        from stemwise_engine.workflow_records import ArtworkUnavailableError
        if self.owns_artwork(source_id):
            artwork = self.embedded_cover(destination)
            if artwork is None:
                raise ArtworkUnavailableError('Selected FLAC has no usable embedded artwork; select a reviewed YouTube fallback')
            return artwork
        return youtube.create_cover_png(info, source_id, destination,
            max_pixels=int(self.config['artworkMaxPixels']), max_bytes=int(self.config['pngMaxBytes']))

    def require_artwork_source(self, sources):
        from stemwise_engine.workflow_records import FutureSongError
        if (sources.get('recordingInput') != self.recording.identity
                or selection(self.config, self.recording.identity['recordingKey']).identity != self.recording.identity):
            raise FutureSongError('Artwork does not belong to the selected native recording')

    def plan_delivery(self, workflow, video_id):
        from stemwise_engine.native_delivery import _selected_ids
        from stemwise_engine.workflow_context import SourceDeliveryPlan
        from stemwise_engine.workflow_records import FutureSongError
        if video_id != self.recording.reference_ids[0] or not _selected_ids(workflow):
            raise FutureSongError('recording-not-selected')
        return SourceDeliveryPlan(self.recording.identity['recordingKey'], None, True)

    def result_binding(self, job):
        video_id = self.recording.reference_ids[0]
        return {'formatVersion': 1, 'kind': 'stemwise.recording-enrichment',
            'input': self.recording.identity, 'lyricReferenceVideoId': video_id,
            'enrichment': f'{video_id}.stemwise.zip', 'enrichmentSha256': job['delivery']['sha256'],
            'phoneDeliveryRequiresMatchingOriginal': True}

    def complete_delivery(self, workflow, job):
        from stemwise_engine.native_delivery import publish
        deliveries = publish(workflow, job)
        binding = self.result_binding(job)
        atomic_write_json(workflow.ready / 'recording.json', binding)
        if read_bounded_json(workflow.ready / 'recording.json', max_bytes=MAX_JSON_BYTES, root=workflow.ready) != self.result_binding(job):
            raise ValueError('Published native result binding readback differs')
        if job.get('artworkRepair') and job.get('artworkPublicationSha256') != job['delivery']['sha256']:
            job['artworkPublicationSha256'] = job['delivery']['sha256']
            workflow._save_job(job)
        return deliveries

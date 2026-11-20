"""Retain source downloads, run a fast recording comparison, export review files."""
from stemwise_engine.common import WorkerRetirementError
import argparse
import base64
import io
import collections
import copy
import csv
import hashlib
import json
import os
import re
import sys
import unicodedata
import uuid
import stat
from concurrent.futures import ThreadPoolExecutor, as_completed
from threading import Event
from pathlib import Path

from stemwise_media.compare import analyze_waveforms, analyze_clock_drift, decode, probe
from stemwise_media.catalogue_store import (read_catalogue, commit_catalogue, catalogue_transaction,
                             _commit_catalogue_locked)
from stemwise_engine.common import atomic_write_json, atomic_write_bytes, validate_generated_path, read_bounded_json, exclusive_process_lock

ROOT = Path(__file__).resolve().parent
RATE = 16000


def write_json(path, value):
    if path.name == 'catalogue.json':
        commit_catalogue(path, value)
    else:
        atomic_write_json(path, value)


def safe_error(error):
    return re.sub(r"https?://\S+", "[URL]", str(error))[:300]


def source_file(video_id):
    from stemwise_engine.common import validate_video_id
    validate_video_id(video_id)
    base = validate_generated_path(ROOT, ROOT / "audio/youtube" / video_id)
    for metadata_name in ("download.json", "reference.json"):
        metadata = base / metadata_name
        if metadata.is_file():
            value = read_bounded_json(metadata, max_bytes=1024*1024, root=base)
            if value.get('status') == 'replaced_by_flac':
                return None
            name = value.get("file") or value.get("referenceFile")
            if (value.get("videoId", value.get("sourceVideoId")) == video_id
                    and name and Path(name).name == name and (base / name).is_file()
                    and (base / name).stat().st_size):
                return validate_generated_path(ROOT, base / name)
    return None


def require_youtube_audio_allowed(video_id, *, root=None):
    from stemwise_engine.common import require_youtube_audio_allowed as require_allowed
    require_allowed(video_id, root=ROOT if root is None else root)


def retire_youtube_audio(video_id, track_id, *, comparison_binding=None):
    from stemwise_engine.common import exclusive_process_lock, validate_video_id
    validate_video_id(video_id)
    replacement = qobuz_file(track_id)
    if replacement is None or replacement.is_symlink() or not replacement.resolve().is_relative_to(ROOT.resolve()) or probe(replacement)['codec_name'] != 'flac':
        raise ValueError('Approved replacement must be a retained native FLAC')
    root = ROOT.resolve()
    base = ROOT / 'audio/youtube' / video_id
    if base.resolve() != root / 'audio/youtube' / video_id:
        raise ValueError('YouTube audio folder escapes its library')
    base.mkdir(parents=True, exist_ok=True)
    with catalogue_transaction(ROOT/'catalogue.json'), \
            exclusive_process_lock(ROOT / 'comparison-results/export.lock'):
        _recover_publications(ROOT/'comparison-results')
        with exclusive_process_lock(base / 'source-selection.lock'):
            return _retire_youtube_audio_locked(video_id, track_id, replacement, base, root, comparison_binding)


def _require_youtube_retirement_allowed(video_id):
    catalogue_path = ROOT / 'catalogue.json'
    if catalogue_path.exists():
        rows = json.loads(catalogue_path.read_text(encoding='utf-8-sig'))['rows']
        if any(extra.get('source') == 'youtube' and extra.get('videoId', row['videoId']) == video_id
               for row in rows for extra in row.get('ownerAudioSelection', {}).get('additionalVersions', [])):
            raise ValueError('YouTube recording is retained as an additional owner version')
        if any(row['videoId'] == video_id and row.get('ownerAudioSelection', {}).get('source') == 'youtube'
               for row in rows):
            raise ValueError('Select the native replacement before retiring the primary YouTube recording')


def _retirement_audio_names(video_id, value):
    if value is None:
        return set()
    if not isinstance(value, dict) or value.get('videoId', value.get('sourceVideoId')) != video_id:
        raise ValueError('Source retirement receipt belongs to another recording')
    extra = value.get('additionalSourceFiles', [])
    if not isinstance(extra, list) or any(not isinstance(item, dict) for item in extra):
        raise ValueError('Source retirement has invalid additional audio bindings')
    names = [value.get('file'), value.get('referenceFile')] + [item.get('file') for item in extra]
    owned = set()
    for name in filter(None, names):
        if (not isinstance(name, str) or Path(name).name != name
                or Path(name).suffix not in {'.webm','.opus','.ogg','.m4a','.aac','.mp3','.mp4','.wav','.wv','.flac'}):
            raise ValueError('Source retirement requires an owned audio filename')
        owned.update(name+suffix for suffix in ('', '.part', '.ytdl'))
    return owned


def _retire_youtube_audio_locked(video_id, track_id, replacement, base, root, comparison_binding):
    _require_youtube_retirement_allowed(video_id)
    if comparison_binding is not None and not _replacement_binding_is_current(video_id, track_id, comparison_binding):
        raise ValueError('Automatic replacement comparison no longer matches current audio')
    pending = base / 'retirement.pending.json'
    recovered = _complete_youtube_retirement(base, video_id, track_id) if pending.exists() else 0
    names = set()
    for receipt in (base/'download.json', base/'reference.json'):
        if receipt.exists():
            names.update(_retirement_audio_names(video_id, read_bounded_json(receipt, max_bytes=1024*1024, root=base)))
    files = [base/name for name in sorted(names) if (base/name).exists()]
    index_path = ROOT / 'comparison-results/review-index.json'
    previous_index = read_bounded_json(index_path, max_bytes=16*1024*1024, root=index_path.parent) if index_path.exists() else None
    next_index = copy.deepcopy(previous_index)
    if previous_index is not None:
        if not isinstance(previous_index, dict):
            raise ValueError('Invalid comparison review index')
        row = next_index.get(video_id)
        if row is not None:
            if not isinstance(row, dict) or not isinstance(row.get('audio'), list):
                raise ValueError('Invalid recording review audio index')
            retained = []
            for binding in row['audio']:
                if not isinstance(binding, dict) or not isinstance(binding.get('file'), str):
                    raise ValueError('Invalid recording review audio binding')
                relative = Path(binding['file'])
                if relative.name.startswith('01-YouTube-'):
                    if (relative.parent.as_posix() != f'reviews/{video_id}'
                            or re.fullmatch(r'01-YouTube-[0-9a-f]{64}\.[A-Za-z0-9]+', relative.name) is None
                            or binding.get('sha256') != relative.stem.removeprefix('01-YouTube-')):
                        raise ValueError('Invalid recording review source binding')
                    file = validate_generated_path(index_path.parent, index_path.parent / relative)
                    if file.exists() and file_sha256(file) != binding['sha256']:
                        raise ValueError('Preserve changed recording review audio')
                    files.append(file)
                else:
                    retained.append(binding)
            row['audio'] = retained
    for file in files:
        if file.is_symlink() or not file.is_file() or not file.resolve().is_relative_to(root):
            raise ValueError('Superseded audio escapes its library or is not a regular file')
    metadata = base / 'download.json'
    previous = json.loads(metadata.read_text(encoding='utf-8')) if metadata.exists() else {}
    previous.pop('file', None)
    previous.pop('additionalSourceFiles', None)
    replacement_metadata = {**previous, 'videoId': video_id, 'status': 'replaced_by_flac',
        'replacedBy': {'trackId': track_id, 'file': replacement.relative_to(ROOT).as_posix()},
        **({'replacementComparison': comparison_binding} if comparison_binding is not None else {})}
    atomic_write_json(pending, {'videoId': video_id, 'trackId': track_id,
        'replacement': {'trackId': track_id, 'file': replacement.relative_to(ROOT).as_posix(),
                        'bytes': replacement.stat().st_size, 'sha256': file_sha256(replacement)},
        'metadata': {'previous': json.loads(metadata.read_text(encoding='utf-8')) if metadata.exists() else None,
                     'next': replacement_metadata},
        'reviewIndex': {'previous': previous_index, 'next': next_index},
        'files': [{'file': file.relative_to(ROOT).as_posix(), 'sha256': file_sha256(file)}
                  for file in dict.fromkeys(files) if file.exists()]})
    removed = _complete_youtube_retirement(base, video_id, track_id)
    return {'videoId': video_id, 'recordingKey': f'qobuz:{track_id}', 'removedAudioFiles': recovered + removed}



def _complete_youtube_retirement(base, video_id, track_id):
    from stemwise_engine.enrichment_constants import MAX_RECORDING_TRACK_ID
    if type(track_id) is not int or not 0 < track_id <= MAX_RECORDING_TRACK_ID:
        raise ValueError('Invalid pending replacement recording identity')
    with exclusive_process_lock(ROOT / 'audio/qobuz' / str(track_id) / 'download.lock'):
        return _complete_youtube_retirement_locked(base, video_id, track_id)


def _complete_youtube_retirement_locked(base, video_id, track_id):
    _require_youtube_retirement_allowed(video_id)
    pending = base / 'retirement.pending.json'
    record = read_bounded_json(pending, max_bytes=32*1024*1024, root=base)
    if (not isinstance(record, dict) or set(record) != {'videoId', 'trackId', 'replacement', 'metadata', 'reviewIndex', 'files'}
            or record['videoId'] != video_id or record['trackId'] != track_id
            or not isinstance(record['files'], list)):
        raise ValueError('Invalid pending source retirement')
    replacement = record['replacement']
    if (not isinstance(replacement, dict) or set(replacement) != {'trackId', 'file', 'bytes', 'sha256'}
            or type(replacement['trackId']) is not int or replacement['trackId'] != track_id
            or not isinstance(replacement['file'], str) or type(replacement['bytes']) is not int
            or replacement['bytes'] <= 0 or not isinstance(replacement['sha256'], str)
            or re.fullmatch('[0-9a-f]{64}', replacement['sha256']) is None):
        raise ValueError('Pending source retirement requires its exact replacement binding')
    file = validate_generated_path(ROOT, ROOT / replacement['file'])
    if (file.is_symlink() or not file.is_file() or file.stat().st_size != replacement['bytes']
            or file_sha256(file) != replacement['sha256'] or qobuz_file(track_id) != file):
        raise ValueError('Preserve source audio until its exact replacement is available')
    metadata = base / 'download.json'
    index = ROOT / 'comparison-results/review-index.json'
    for target, binding in ((metadata, record['metadata']), (index, record['reviewIndex'])):
        if not isinstance(binding, dict) or set(binding) != {'previous', 'next'}:
            raise ValueError('Invalid source retirement publication')
        current = read_bounded_json(target, max_bytes=16*1024*1024, root=target.parent) if target.exists() else None
        if current not in (binding['previous'], binding['next']):
            raise ValueError('Preserve changed source retirement publication')
    owned = _retirement_audio_names(video_id, record['metadata']['previous'])
    reference = base/'reference.json'
    if reference.exists():
        owned.update(_retirement_audio_names(video_id, read_bounded_json(reference, max_bytes=1024*1024, root=base)))
    files = []
    for binding in record['files']:
        if (not isinstance(binding, dict) or set(binding) != {'file', 'sha256'}
                or not isinstance(binding['file'], str)
                or not isinstance(binding['sha256'], str) or re.fullmatch('[0-9a-f]{64}', binding['sha256']) is None):
            raise ValueError('Invalid source retirement file binding')
        file = validate_generated_path(ROOT, ROOT / binding['file'])
        if file.parent == base and file.name not in owned:
            raise ValueError('Source retirement file is not owned by its bound receipt')
        if not (file.parent == base or (file.parent == ROOT / 'comparison-results/reviews' / video_id
                and re.fullmatch(r'01-YouTube-[0-9a-f]{64}\.[A-Za-z0-9]+', file.name))):
            raise ValueError('Source retirement file escapes its recording')
        if file.exists() and (not file.is_file() or file.is_symlink() or file_sha256(file) != binding['sha256']):
            raise ValueError('Preserve changed source retirement audio')
        files.append(file)
    atomic_write_json(metadata, record['metadata']['next'])
    for file in files:
        file.unlink(missing_ok=True)
    if record['reviewIndex']['next'] is not None:
        atomic_write_json(index, record['reviewIndex']['next'])
    pending.unlink()
    return len(files)



def download_reference(row, selected_format="bestaudio/best", *, timeout_seconds=900, cancelled=lambda: False):
    from stemwise_engine.common import exclusive_process_lock
    base = ROOT / 'audio/youtube' / row['videoId']
    base.mkdir(parents=True, exist_ok=True)
    from stemwise_engine.provider_operations import provider_operation
    with provider_operation(timeout_seconds, cancelled=cancelled) as budget, \
            exclusive_process_lock(base / 'source-selection.lock'):
        budget.remaining()
        require_youtube_audio_allowed(row['videoId'])
        return _download_reference(row, selected_format, timeout_seconds=timeout_seconds, cancelled=cancelled)


def _download_reference(row, selected_format, *, timeout_seconds, cancelled):
    video_id = row["videoId"]
    retained = source_file(video_id)
    native_opus = selected_format == 'bestaudio[acodec=opus]'
    from stemwise_engine.youtube import extract_youtube_info, canonical_url
    from stemwise_engine.provider_operations import ProviderDeadline, ProviderCancelled, provider_failure_cause
    source_url = canonical_url(video_id)
    base = ROOT / "audio/youtube" / video_id
    base.mkdir(parents=True, exist_ok=True)
    previous_path = base / 'download.json'
    previous = json.loads(previous_path.read_text(encoding='utf-8')) if retained and previous_path.exists() else {}
    import stemwise_media.bounded_pcm as pcm
    try:
        with pcm.comparison_operation(cancelled=cancelled, seconds=timeout_seconds):
            verified = (retained is not None and type(previous.get('bytes')) is int
                and previous['bytes'] == retained.stat().st_size
                and previous.get('sha256') == file_sha256(retained))
            if verified and (not native_opus or probe(retained)['codec_name'] == 'opus'):
                return {"position": row["position"], "status": "already_saved"}
            template = 'source.%(ext)s'
            if retained:
                template = 'source-opus.%(ext)s' if verified else f'source-repair-{uuid.uuid4().hex}.%(ext)s'
            info = extract_youtube_info(source_url, {"format": selected_format, "outtmpl": str(base/template),
                "noplaylist": True, "overwrites": False, "continuedl": True,
                "retries": 2, "fragment_retries": 2, "socket_timeout": 20}, download=True)
            if not isinstance(info, dict) or info.get("id") != video_id or not isinstance(info.get('_filename'), str):
                raise ValueError("Unexpected source video identity or completed filename")
            file = Path(info['_filename'])
            if file.parent.resolve() != base.resolve() or not file.is_file() or not file.stat().st_size:
                raise ValueError("Downloader did not retain a complete source file")
            codec = probe(file)['codec_name']
            if native_opus and codec != 'opus':
                raise ValueError('YouTube did not provide native Opus audio; do not transcode or substitute AAC')
            metadata = {"videoId": video_id, "file": file.name,
                       "bytes": file.stat().st_size, "sha256": file_sha256(file),
                       "title": info.get("title"), "durationSeconds": info.get("duration"),
                       "formatId": info.get("format_id"), "codec": info.get('acodec'), "source": source_url,
                       "status": "saved"}
            if retained and retained != file:
                metadata['additionalSourceFiles'] = previous.get('additionalSourceFiles', []) + [
                    {'file': retained.name, 'formatId': previous.get('formatId'), 'codec': previous.get('codec')}]
            write_json(base / "download.json", metadata)
            return {"position": row["position"], "status": "saved"}
    except WorkerRetirementError:
        raise
    except Exception as error:
        diagnostics = {}
        provider_error = provider_failure_cause(error)
        if provider_error is not None:
            from stemwise_engine.private_diagnostics import retain_error
            from stemwise_engine.workflow_context import telemetry_job_root
            report = telemetry_job_root(ROOT.parents[1], f'youtube:{video_id}')/'reference-download'
            try:
                report.mkdir(parents=True, exist_ok=True)
                diagnostics['privateDiagnostics'] = [retain_error(report/'provider.private.log', error,
                    context={'videoId': video_id, 'operation': 'reference-download',
                             'providerStage': getattr(provider_error, 'provider_stage', 'unknown')})]
            except (OSError, ValueError):
                print('reference-private-diagnostic-retention-failed', file=sys.stderr)
        if isinstance(error, ProviderCancelled):
            raise
        detail = safe_error(error)
        failure = {"position": row["position"], "status": "failed", "error": detail, **diagnostics}
        if isinstance(error, ProviderDeadline):
            failure['retryable'] = True
        if provider_error is not None:
            try:
                write_json(report/'failure.json', {'videoId': video_id, **failure})
            except OSError:
                print('reference-failure-record-retention-failed', file=sys.stderr)
        if not retained:
            write_json(base / "download.json", {"videoId": video_id, "status": "failed", "error": detail, **diagnostics})
        return failure


def candidates(row):
    if row["metadataSelection"]["status"] != "deferred":
        selected_id = row['metadataSelection'].get('trackId')
        selected = [track for track in row['candidates'] if type(selected_id) is int and track['id'] == selected_id]
        if len(selected) != 1:
            raise ValueError('Catalogue selected recording is missing or duplicated')
        return selected
    if row["metadataSelection"]["basis"] == "matching_artist_title_and_rendition_not_located_in_checked_catalogue":
        return []
    return row.get("candidates", [])


def selected_download_id(row):
    chosen = row.get('ownerAudioSelection')
    if chosen:
        return chosen['trackId'] if chosen['source'] == 'qobuz' else None
    return candidates(row)[0]['id'] if row['metadataSelection']['status'] != 'deferred' else None


def qobuz_file(track_id, *, root=None, verify_digest=True):
    base = (ROOT if root is None else root) / "audio/qobuz" / str(track_id)
    metadata = base / "download.json"
    if not metadata.is_file():
        return None
    value = json.loads(metadata.read_text(encoding="utf-8"))
    if value.get('status') == 'owner_rejected':
        return None
    from stemwise_media.save_download import validate_retained_download
    return validate_retained_download(base, track_id, value, verify_digest=verify_digest)


def alternative_candidates(data, row):
    def key(track):
        return tuple(''.join(c for c in unicodedata.normalize('NFKD', track[field]).casefold()
                             if c.isalnum()) for field in ('artist', 'title'))
    wanted = key(candidates(row)[0] if row['metadataSelection']['status'] != 'deferred' else row['candidates'][0])
    pool = {t['id']: t for r in data['rows'] for t in r['candidates'] if key(t) == wanted}
    return row['candidates'] + [t for i, t in pool.items() if i not in {c['id'] for c in row['candidates']}]


def decision(results):
    complete = [r for r in results if r["classification"] == "strong_recording_match"]
    if len(complete) > 1:
        return "ambiguous", None
    if len(complete) == 1:
        return "matched", complete[0]["trackId"]
    bodies = [r for r in results if r["classification"] in ("matching_body_with_extra_audio", "matching_body_with_edge_edits", "matching_body_with_speed_change")]
    if len(bodies) == 1:
        return bodies[0]["classification"], bodies[0]["trackId"]
    return "unconfirmed", None


def comparison_for(row):
    return row.get("refinedAudioComparison", row.get("quickAudioComparison", {}))


def file_sha256(path):
    digest = hashlib.sha256()
    descriptor = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0))
    with os.fdopen(descriptor, 'rb') as stream:
        before = os.fstat(stream.fileno())
        identity = lambda value: (value.st_dev, value.st_ino, value.st_mode, value.st_size, value.st_mtime_ns)
        if not stat.S_ISREG(before.st_mode) or identity(before) != identity(path.lstat()):
            raise ValueError('Audio hash requires the exact unlinked regular path')
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
        if identity(before) != identity(os.fstat(stream.fileno())) or identity(before) != identity(path.lstat()):
            raise ValueError('Audio changed while hashing its exact open file')
    return digest.hexdigest()


class ComparisonEvidence:
    def __init__(self):
        self.sources, self.candidates, self.digests, self.identities = {}, {}, {}, {}

    @staticmethod
    def _identity(path):
        try:
            validate_generated_path(ROOT, path)
            # Creating review hard links changes ctime without changing audio.
            value = path.stat(follow_symlinks=False)
            return (value.st_dev, value.st_ino, value.st_mode, value.st_size, value.st_mtime_ns)
        except FileNotFoundError:
            return None

    def _remember(self, path):
        identity = self._identity(path)
        if path in self.identities and self.identities[path] != identity:
            raise ValueError('Retained audio changed during comparison export')
        self.identities[path] = identity

    def require_unchanged(self):
        for path in self.identities:
            self._remember(path)

    def source(self, video_id):
        receipts = [ROOT/'audio/youtube'/video_id/name for name in ('download.json', 'reference.json')]
        for receipt in receipts:
            self._remember(receipt)
        if video_id not in self.sources:
            self.sources[video_id] = source_file(video_id)
        for receipt in receipts:
            self._remember(receipt)
        if self.sources[video_id] is not None:
            self._remember(self.sources[video_id])
        return self.sources[video_id]

    def candidate(self, track_id):
        receipt = ROOT/'audio/qobuz'/str(track_id)/'download.json'
        self._remember(receipt)
        if track_id not in self.candidates:
            try:
                self.candidates[track_id] = qobuz_file(track_id)
            except InterruptedError:
                raise
            except (OSError, ValueError) as error:
                self.candidates[track_id] = error
        self._remember(receipt)
        result = self.candidates[track_id]
        if result is not None and not isinstance(result, Exception):
            self._remember(result)
        if isinstance(result, Exception):
            raise result
        return result

    def digest(self, path):
        self._remember(path)
        if path not in self.digests:
            self.digests[path] = file_sha256(path)
        self._remember(path)
        return self.digests[path]

    def comparison(self, row):
        from stemwise_media.recording_review import confirmed
        measured = comparison_for(row)
        if not measured.get('candidates') and measured.get('status', 'not_checked') in {'not_checked', 'unconfirmed'}:
            return measured
        current = measured.get('sampleRate') in {16000, 48000} and measured.get('recipeVersion') == 1
        for candidate in measured.get('candidates', []):
            binding = {key: measured.get(key) for key in ('referenceFile', 'referenceSha256')}
            binding.update({key: candidate.get(key) for key in ('candidateFile', 'candidateSha256')})
            try:
                current = current and candidate.get('trackId') not in row.get('rejectedTrackIds', []) and _replacement_binding_is_current(
                    row['videoId'], candidate['trackId'], binding, evidence=self)
            except (OSError, ValueError):
                current = False
        if measured.get('matchedTrackId') is not None:
            current = current and sum(candidate.get('trackId') == measured['matchedTrackId'] and confirmed(candidate)
                for candidate in measured.get('candidates', [])) == 1
        if not measured.get('candidates'):
            current = False
        return measured if current else measured | {'status': 'stale', 'matchedTrackId': None}


def _replacement_binding_is_current(video_id, track_id, binding, *, evidence=None):
    evidence = evidence or ComparisonEvidence()
    if (not isinstance(binding, dict) or set(binding) !=
            {'referenceFile', 'referenceSha256', 'candidateFile', 'candidateSha256'}):
        return False
    candidate = evidence.candidate(track_id)
    if (candidate is None or binding['candidateFile'] != candidate.relative_to(ROOT).as_posix()
            or binding['candidateSha256'] != evidence.digest(candidate)):
        return False
    reference = evidence.source(video_id)
    if reference is not None:
        return (binding['referenceFile'] == reference.relative_to(ROOT).as_posix()
                and binding['referenceSha256'] == evidence.digest(reference))
    receipt = ROOT / 'audio/youtube' / video_id / 'download.json'
    previous = json.loads(receipt.read_text(encoding='utf-8')) if receipt.exists() else {}
    return (previous.get('status') == 'replaced_by_flac'
            and previous.get('replacedBy', {}).get('trackId') == track_id
            and previous.get('replacementComparison') == binding)


def current_comparison_binding(row, track_id, *, evidence=None):
    evidence = evidence or ComparisonEvidence()
    from stemwise_media.recording_review import confirmed
    comparison = comparison_for(row)
    candidates = [candidate for candidate in comparison.get('candidates', [])
                  if candidate.get('trackId') == track_id and confirmed(candidate) and track_id not in row.get('rejectedTrackIds', [])]
    if comparison.get('sampleRate') != 48000 or comparison.get('recipeVersion') != 1 or len(candidates) != 1:
        return None
    binding = {key: comparison.get(key) for key in ('referenceFile', 'referenceSha256')}
    binding.update({key: candidates[0].get(key) for key in ('candidateFile', 'candidateSha256')})
    return binding if _replacement_binding_is_current(row['videoId'], track_id, binding, evidence=evidence) else None


def effective_track_metadata(data, track):
    return {**track, **data.get('ownerTrackMetadata', {}).get(str(track['id']), {})}


def retained_downloads(evidence):
    retained, invalid = {}, []
    for base in sorted((ROOT / 'audio/qobuz').iterdir()) if (ROOT / 'audio/qobuz').is_dir() else []:
        if not base.is_dir() or base.name == 'album-archives':
            continue
        if re.fullmatch(r'[1-9][0-9]*', base.name) is None:
            invalid.append({'directory': base.name, 'reason': 'Invalid recording directory identity'})
            continue
        track_id = int(base.name)
        try:
            path = evidence.candidate(track_id)
        except InterruptedError:
            raise
        except (OSError, ValueError) as error:
            invalid.append({'trackId': track_id, 'reason': safe_error(error)})
            continue
        if path is not None:
            retained[track_id] = path
    return retained, invalid


def build_library(data, *, evidence=None):
    evidence = evidence or ComparisonEvidence()
    from stemwise_media.recording_review import equivalences, owner_selection_is_current
    tracks = {t['id']: t for row in data['rows'] for t in row['candidates']}
    downloads, invalid_alternatives = retained_downloads(evidence)
    flac = []
    for track_id, path in downloads.items():
        if track_id not in tracks:
            continue
        track = effective_track_metadata(data, tracks[track_id])
        flac.append({'trackId': track_id, 'title': track.get('title', path.stem),
                     'artist': track.get('artist', ''), 'album': track.get('album', ''),
                     'version': track.get('version'), 'file': path.relative_to(ROOT).as_posix()})
    # Explicit future inputs survive refresh without adding rows to the frozen playlist.
    from stemwise_engine.recording_source import registered_input
    retained_ids = {t['trackId'] for t in flac}
    for path in sorted((ROOT / 'audio/qobuz').glob('*/enrichment-input.json')):
        try:
            if re.fullmatch(r'[1-9][0-9]*', path.parent.name) is None:
                raise ValueError('Invalid recording directory identity')
            item = registered_input(ROOT, int(path.parent.name))
        except (OSError, ValueError) as error:
            invalid_alternatives.append({'directory': path.parent.name, 'reason': safe_error(error)})
            continue
        if item['trackId'] not in retained_ids:
            flac.append({k: item[k] for k in ('trackId', 'title', 'artist', 'album', 'file')} | {
                'lyricReferenceVideoId': item['referenceVideoId']})
            retained_ids.add(item['trackId'])
    playlist, youtube, extras = [], {}, []
    for row in data['rows']:
        chosen = row.get('ownerAudioSelection')
        if chosen and not owner_selection_is_current(row):
            raise ValueError('Historical prose-derived owner selection requires explicit structured review')
        raw_comparison = comparison_for(row)
        comparison = evidence.comparison(row)
        if (not chosen and raw_comparison.get('status') in ('matched', 'matching_body_with_edge_edits',
                'matching_body_with_extra_audio', 'matching_body_with_speed_change')
                and any(issue.get('trackId') == raw_comparison.get('matchedTrackId') for issue in invalid_alternatives)):
            raise ValueError('Retained download selected by comparison requires content verification')
        binding = None
        if chosen:
            source, track_id = chosen['source'], chosen.get('trackId')
        elif (comparison.get('status') in ('matched', 'matching_body_with_edge_edits',
                                          'matching_body_with_extra_audio', 'matching_body_with_speed_change')
              and comparison.get('sampleRate') == 48000
              and comparison.get('matchedTrackId') in retained_ids):
            source, track_id = 'qobuz', comparison['matchedTrackId']
            binding = current_comparison_binding(row, track_id, evidence=evidence)
            if binding is None:
                raise ValueError('Automatic replacement requires a current byte-bound 48 kHz comparison')
        else:
            source, track_id = 'youtube', None
        path = evidence.candidate(track_id) if source == 'qobuz' else evidence.source(row['videoId'])
        if source == 'qobuz' and (track_id not in retained_ids or not path):
            raise ValueError('Selected FLAC is not retained')
        relative = path.relative_to(ROOT).as_posix() if path else None
        if chosen and relative != chosen['file']:
            raise ValueError('Owner choice no longer matches its retained audio')
        title = row.get('ownerDisplayTitle') or row.get('sourceMetadata', {}).get('title') or row.get('title') or row['videoId']
        performance_ids = (set(row.get('retainedPerformanceTrackIds', []))
                           | {candidate['trackId'] for candidate in equivalences(row, evidence=evidence,
                                                                                admitted_track_ids=retained_ids)})
        performance_ids = performance_ids.intersection(retained_ids) - set(row.get('rejectedTrackIds', []))
        playlist.append({'position': row['position'], 'videoId': row['videoId'], 'title': title,
                         'source': source if path else 'unavailable', 'trackId': track_id, 'file': relative,
                         'performanceTrackIds': sorted(performance_ids)})
        if source == 'youtube' and path:
            youtube[row['videoId']] = {'videoId': row['videoId'], 'title': title, 'file': relative}
        for extra in (chosen or {}).get('additionalVersions', []):
            if extra['source'] != 'youtube':
                continue
            extras.append((extra, title, row['videoId']))
    for extra, title, default_video in extras:
        video_id = extra.get('videoId', default_video)
        retained = evidence.source(video_id)
        if retained is None or retained.relative_to(ROOT).as_posix() != extra['file']:
            raise ValueError('Additional owner source no longer matches its retained audio')
        youtube[video_id] = {'videoId': video_id, 'title': title, 'file': extra['file']}
    return {'flac': flac, 'youtube': list(youtube.values()), 'playlist': playlist,
            'invalidAlternatives': invalid_alternatives}


def export_library(data):
    with catalogue_transaction(ROOT/'catalogue.json'), exclusive_process_lock(ROOT/'comparison-results/export.lock'):
        _recover_publications(ROOT/'comparison-results')
        return _export_library_locked(data)


def _export_library_locked(data, *, evidence=None):
    evidence = evidence or ComparisonEvidence()
    catalogue_path = ROOT/'catalogue.json'
    committed = read_catalogue(catalogue_path) if catalogue_path.exists() else data
    library = build_library(committed, evidence=evidence)
    evidence.require_unchanged()
    counts = {'flacFiles': len(library['flac']), 'retainedYouTubeVersions': len(library['youtube']),
              'preferredSources': dict(collections.Counter(row['source'] for row in library['playlist']))}
    counts_changed = committed.get('librarySelectionCounts') != counts
    committed['librarySelectionCounts'] = counts
    if catalogue_path.exists():
        if counts_changed:
            _commit_catalogue_locked(catalogue_path, committed)
        library['catalogueSha256'] = file_sha256(catalogue_path)
    evidence.require_unchanged()
    write_json(ROOT / 'comparison-results/library-files.json', library)
    from stemwise_media.name_inventory import export_names
    export_names(committed, library, ROOT / 'comparison-results')
    data['librarySelectionCounts'] = counts


def compare_rows(data, refine=False, edges=False, repeats=False, precision=False, alternatives=False, drift=False):
    rate = 48000 if refine else RATE
    field = "refinedAudioComparison" if refine else "quickAudioComparison"
    failed_rows = 0
    for row in data["rows"]:
        if (repeats or precision or alternatives or drift) and comparison_for(row).get("status") != "unconfirmed":
            continue
        if precision and not any(c.get('anchors') and min(a['correlation'] for a in c['anchors']) >= .9
                and max(a['offsetSeconds'] for a in c['anchors']) - min(a['offsetSeconds'] for a in c['anchors']) <= .02
                for c in comparison_for(row).get('candidates', [])):
            continue
        if edges and not (repeats or precision or alternatives or drift) and (comparison_for(row).get("status") != "unconfirmed"
                or not any(c.get("reason") == "Dense waveform comparison differs" for c in comparison_for(row).get("candidates", []))):
            continue
        try:
            reference = source_file(row["videoId"])
            tracks = alternative_candidates(data, row) if alternatives else candidates(row)
            available = [(t, qobuz_file(t["id"])) for t in tracks]
            available = [(t, f) for t, f in available if f]
            if not reference or not available:
                raise ValueError("Required comparison audio is unavailable")
            previous = row.get(field, {})
            reference_sha = file_sha256(reference)
            candidate_hashes = {t['id']: file_sha256(f) for t, f in available}
            candidate_paths = {t['id']: f for t, f in available}
            for cached_candidate in previous.get('candidates', []):
                cached_id = cached_candidate['trackId']
                if cached_id not in candidate_paths:
                    retained = qobuz_file(cached_id)
                    if retained is not None:
                        candidate_paths[cached_id] = retained
                        candidate_hashes[cached_id] = file_sha256(retained)
        except (OSError, ValueError) as error:
            failed_rows += 1
            print(json.dumps({'position':row['position'],'status':'unavailable','reason':safe_error(error)}), flush=True)
            continue
        reusable = (previous.get('referenceFile') == reference.relative_to(ROOT).as_posix()
                    and previous.get('referenceSha256') == reference_sha
                    and previous.get('recipeVersion') == 1 and previous.get('sampleRate') == rate)
        review_current = ((not edges or previous.get('edgeReview'))
            and (not repeats or previous.get('repeatReview'))
            and (not precision or previous.get('fractionalDelayReview') == 3)
            and (not alternatives or previous.get('alternativeReview'))
            and (not drift or previous.get('clockDriftReview') == 2))
        cached = [v for v in previous.get('candidates', []) if reusable
                  and v.get('classification') != 'error'
                  and v.get('candidateSha256') == candidate_hashes.get(v.get('trackId'))
                  and v.get('candidateSha256') is not None
                  and v.get('candidateFile') == candidate_paths[v['trackId']].relative_to(ROOT).as_posix()]
        available_ids = {t['id'] for t, _ in available}
        attempted = {v['trackId'] for v in cached if review_current or v['trackId'] not in available_ids}
        if (reusable
                and (available_ids <= attempted if repeats or precision or drift else attempted == available_ids)
                and (not edges or previous.get("edgeReview"))
                and (not repeats or previous.get("repeatReview"))
                and (not precision or previous.get("fractionalDelayReview") == 3)
                and (not alternatives or previous.get("alternativeReview"))
                and (not drift or previous.get("clockDriftReview") == 2)):
            continue
        pending = [(t, f) for t, f in available if t['id'] not in attempted]
        if not pending:
            continue
        pending_ids = {t['id'] for t, _ in pending}
        results = [c for c in cached if c['trackId'] not in pending_ids and (c['trackId'] in available_ids or alternatives
                   or (repeats or precision or drift) and c['trackId'] not in available_ids)]
        try:
            reference_pcm = decode(reference, rate)
            for track, file in pending:
                try:
                    candidate_pcm = decode(file, rate)
                    if drift:
                        result = analyze_waveforms(reference_pcm, candidate_pcm, rate, allow_edge_edits=True, allow_fractional_delay=True)
                        if result['classification'] not in ('strong_recording_match', 'matching_body_with_extra_audio', 'matching_body_with_edge_edits'):
                            adjustment = analyze_clock_drift(reference_pcm, candidate_pcm, rate)
                            result = adjustment if adjustment['classification'] == 'matching_body_with_speed_change' else result | {'clockDriftReview': adjustment}
                    else:
                        result = analyze_waveforms(reference_pcm, candidate_pcm, rate,
                                                   allow_edge_edits=edges, allow_fractional_delay=precision or alternatives)
                    if file_sha256(file) != candidate_hashes[track['id']]:
                        raise ValueError('Compared candidate audio changed')
                except WorkerRetirementError:
                    raise
                except Exception as error:
                    result = {"classification": "error", "reason": safe_error(error)}
                results.append({"trackId": track["id"], 'candidateFile': file.relative_to(ROOT).as_posix(),
                                'candidateSha256': candidate_hashes[track['id']], **result})
            if file_sha256(reference) != reference_sha:
                raise ValueError('Compared reference audio changed')
        except WorkerRetirementError:
            raise
        except Exception as error:
            try:
                reference_current = file_sha256(reference) == reference_sha
            except OSError:
                reference_current = False
            results = [c for c in results if c['trackId'] not in pending_ids] if reference_current else []
            results += [{"trackId": t["id"], 'candidateFile': file.relative_to(ROOT).as_posix(),
                         'candidateSha256': candidate_hashes[t['id']], "classification": "error", "reason": safe_error(error)}
                        for t, file in pending]
        failed_rows += any(result['classification'] == 'error' for result in results)
        status, winner = decision(results)
        row[field] = {"status": status, "matchedTrackId": winner,
                "referenceFile": reference.relative_to(ROOT).as_posix(), "sampleRate": rate,
                'referenceSha256': reference_sha, 'recipeVersion': 1,
                "fullFrequencyMixVerified": False, "edgeReview": edges, "repeatReview": repeats,
                "fractionalDelayReview": 3 if precision or alternatives else False,
                "alternativeReview": alternatives, "clockDriftReview": 2 if drift else False, "candidates": results}
        if alternatives:
            row['candidates'] = tracks
        if status == "matched" and (row["metadataSelection"]["status"] == "deferred" or candidates(row)[0]['id'] != winner):
            selected = next(copy.deepcopy(t) for t, _ in available if t["id"] == winner)
            row["candidates"] = [selected] + [t for t in row["candidates"] if t["id"] != winner]
            row["metadataSelection"] = {"status": "selected_candidate", "trackId": winner,
                    "basis": f"unique_audio_recording_match_{rate // 1000}khz"}
        selected_id = candidates(row)[0]['id'] if row['metadataSelection']['status'] != 'deferred' else None
        file = qobuz_file(selected_id) if selected_id is not None else None
        if file:
            row["download"] = {"trackId": selected_id, "file": file.relative_to(ROOT).as_posix(),
                    "selectionBasis": "metadata_or_quick_recording_comparison",
                    "sourceAcceptance": "owner_trusts_doubledouble", "audioCompared": True}
        write_json(ROOT / "catalogue.json", data)
        print(json.dumps({"position": row["position"], "status": status, "trackId": winner}), flush=True)
    return failed_rows


def _review_audio_path(output, binding):
    if (not isinstance(binding, dict) or not isinstance(binding.get('file'), str)
            or not isinstance(binding.get('sha256'), str)
            or re.fullmatch('[0-9a-f]{64}', binding['sha256']) is None):
        raise ValueError('Invalid review audio identity')
    relative = Path(binding['file'])
    if (len(relative.parts) != 3 or relative.parts[0] != 'reviews'
            or re.fullmatch('[A-Za-z0-9_-]{11}', relative.parts[1]) is None
            or re.fullmatch(r'(?:01-YouTube|02-Qobuz-\d+)-' + binding['sha256'] + r'\.[A-Za-z0-9]+', relative.name) is None):
        raise ValueError('Review audio escapes its owned recording')
    return validate_generated_path(output, output / relative)


def _retired_review_audio(output, previous, proposed):
    retained = {binding['file'] for row in proposed.values()
                for binding in row['audio'] + row.get('unavailableAudio', [])}
    retired = {}
    for row in previous.values():
        for binding in row.get('audio', []):
            path = _review_audio_path(output, binding)
            if binding['file'] not in retained:
                if path.exists() and file_sha256(path) != binding['sha256']:
                    raise ValueError('Preserve changed old review audio')
                retired[binding['file']] = binding
    return list(retired.values())


def _publish_comparison_reports(output, reports=None, *, links=(), retired=()):
    """Journal links before creation, publish their index, then retire superseded owned bytes."""
    journal = output / '.review-publication.json'
    if journal.exists():
        record = read_bounded_json(journal, max_bytes=64*1024*1024, root=output)
        if (not isinstance(record, dict) or set(record) != {'reports', 'links', 'retired'}
                or not isinstance(record['reports'], dict) or not record['reports']
                or not isinstance(record['links'], list) or not isinstance(record['retired'], list)):
            raise ValueError('Invalid comparison report publication')
        prepared = []
        for name, binding in record['reports'].items():
            target = validate_generated_path(output, output / name)
            if (target == journal or not isinstance(binding, dict) or set(binding) != {'previous', 'next'}):
                raise ValueError('Invalid comparison report binding')
            previous = base64.b64decode(binding['previous'], validate=True) if binding['previous'] is not None else None
            replacement = base64.b64decode(binding['next'], validate=True)
            current = target.read_bytes() if target.exists() else None
            if current not in (previous, replacement):
                raise ValueError('Comparison report changed during publication: ' + name)
            prepared.append((target, replacement))
        owned_links, old_links = [], []
        for binding in record['links']:
            target = _review_audio_path(output, binding)
            if set(binding) != {'file', 'sha256', 'source'} or not isinstance(binding['source'], str):
                raise ValueError('Invalid review source link')
            source = validate_generated_path(ROOT, ROOT / binding['source'])
            if target.exists():
                if file_sha256(target) != binding['sha256']:
                    raise ValueError('Preserve changed review link')
            elif not source.is_file() or file_sha256(source) != binding['sha256']:
                raise ValueError('Review source changed before link publication')
            owned_links.append((source, target, binding['sha256']))
        for binding in record['retired']:
            target = _review_audio_path(output, binding)
            if set(binding) != {'file', 'sha256'} or any(target == new for _, new, _ in owned_links):
                raise ValueError('Invalid retired review link')
            if target.exists() and file_sha256(target) != binding['sha256']:
                raise ValueError('Preserve changed retired review link')
            old_links.append(target)
        for source, target, digest in owned_links:
            if not target.exists():
                os.link(source, target)
            if file_sha256(target) != digest:
                raise ValueError('Review link readback failed')
        for target, replacement in prepared:
            atomic_write_bytes(target, replacement)
        if any(target.read_bytes() != replacement for target, replacement in prepared):
            raise ValueError('Comparison report readback failed')
        for target in old_links:
            target.unlink(missing_ok=True)
        journal.unlink()
    if reports is not None:
        record = {}
        for name, payload in reports.items():
            target = validate_generated_path(output, output / name)
            record[name] = {'previous': base64.b64encode(target.read_bytes()).decode('ascii') if target.exists() else None,
                            'next': base64.b64encode(payload).decode('ascii')}
        atomic_write_json(journal, {'reports': record, 'links': list(links), 'retired': list(retired)})
        _publish_comparison_reports(output)


def _recover_publications(output):
    _publish_comparison_reports(output)
    from stemwise_engine.common import validate_video_id
    for pending in (ROOT / 'audio/youtube').glob('*/retirement.pending.json'):
        video_id = validate_video_id(pending.parent.name)
        with exclusive_process_lock(pending.parent / 'source-selection.lock'):
            record = read_bounded_json(pending, max_bytes=32*1024*1024, root=pending.parent)
            _complete_youtube_retirement(pending.parent, video_id, record['trackId'])


def export(data, *, evidence=None):
    with catalogue_transaction(ROOT/'catalogue.json'), \
            exclusive_process_lock(ROOT / 'comparison-results/export.lock'):
        return _export(data, evidence=evidence)


def _export(data, *, evidence=None):
    output = ROOT / "comparison-results"
    output.mkdir(exist_ok=True)
    _recover_publications(output)
    data = _commit_catalogue_locked(ROOT/"catalogue.json", data)
    evidence = evidence or ComparisonEvidence()
    reports, review_index, proposed_links = {}, {}, {}
    previous_index = read_bounded_json(output/'review-index.json', max_bytes=16*1024*1024, root=output) if (output/'review-index.json').exists() else {}
    labels = {"matched": "تطابق التسجيل بالفحص الصوتي",
              "matching_body_with_speed_change": "المقطع الأساسي متطابق بعد تعويض فرق السرعة",
              "matching_body_with_extra_audio": "المقطع الأساسي متطابق مع صوت إضافي",
              "matching_body_with_edge_edits": "المقطع الأساسي متطابق مع اختلاف البداية أو النهاية",
              "ambiguous": "أكثر من مرشح متطابق",
              "stale": "المقارنة السابقة غير صالحة للملفات الحالية؛ يلزم فحص جديد",
              "unconfirmed": "لم يتأكد التطابق", "not_checked": "تعذرت المقارنة أو لا يوجد مرشح"}
    counts = dict(collections.Counter(evidence.comparison(r).get("status", "not_checked") for r in data["rows"]))
    review = ["# النسخ ذات الاختلاف والحالات غير المحسومة", "", "الملفات محفوظة. المقارنة تبدأ عند 16 kHz وتعيد الحالات غير المؤكدة عند 48 kHz، مع ضبط فرق التوقيت وفحص القناتين على امتداد التسجيل.",
              "الفحص لا يثبت مصدر FLAC، تاريخ ضغطه، أو تطابق الماستر تمامًا. عدم التأكد قد ينتج عن تعديل أو فرق ماستر؛ لا يعني بالضرورة تسجيلًا مختلفًا.", ""]
    if data.get('libraryPolicy', {}).get('includeAllRetainedFlac'):
        review += ["اختيارات الملفات الحالية: [قائمة المكتبة](library-files.json). اختيارات المالك المسجلة مقدمة على نتيجة الفحص الآلي لاختيار النسخة؛ لا تعني الحالات أدناه أن اختيار المكتبة ما زال معلقًا.", ""]
    review += [f'- {labels[status]}: {count}' for status, count in counts.items()] + [""]
    reasons = {"At least 24 seconds of both recordings is required": "مدة الصوت غير كافية للمقارنة",
               "Three non-silent anchors could not be established": "لم تتوفر ثلاثة مقاطع صوتية مناسبة لضبط التطابق",
               "Distributed audio anchors disagree": "المقاطع الموزعة في التسجيل لا تتطابق بما يكفي",
               "Timing changes within the recording": "فرق التوقيت يتغير داخل التسجيل؛ قد توجد تعديلات",
               "Insufficient non-silent stereo evidence": "الصوت الفعلي في القناتين غير كافٍ للتأكد",
               "Dense waveform comparison differs": "بعض أجزاء الموجة أو القنوات تختلف؛ لم يتأكد تطابق التسجيل كاملًا"}
    reasons["Matching body; beginning or ending differs"] = "المقطع الأساسي اجتاز شروط التطابق؛ الاختلاف محصور بالأطراف، بحد أقصى 10 ثوانٍ و5% من المدة"
    with io.StringIO(newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["الترتيب", "العنوان", "الفنان", "النتيجة", "السبب", "YouTube", "معرفات Qobuz", "ملف المصدر", "ملفات المرشحين",
                         "اختيار المكتبة", "ملف المكتبة", "إصدارات إضافية مقبولة", "ملاحظات المالك"])
        for row in data["rows"]:
            comparison = evidence.comparison(row)
            status = comparison.get("status", "not_checked")
            source = evidence.source(row["videoId"])
            compared_ids = {r['trackId'] for r in comparison.get('candidates', [])}
            tracks = [t for t in row['candidates'] if t['id'] in compared_ids] if compared_ids else candidates(row)
            files, unavailable = [], []
            for track in tracks:
                try:
                    file = evidence.candidate(track['id'])
                except InterruptedError:
                    raise
                except (OSError, ValueError) as error:
                    unavailable.append({'trackId': track['id'], 'reason': safe_error(error)})
                else:
                    if file:
                        files.append((track['id'], file))
            selected_id = selected_download_id(row)
            if selected_id is not None:
                selected_file = evidence.candidate(selected_id)
                if selected_file:
                    row["download"] = {"trackId": selected_id, "file": selected_file.relative_to(ROOT).as_posix(),
                        "selectionBasis": "metadata_or_quick_recording_comparison",
                        "sourceAcceptance": "owner_trusts_doubledouble", "audioCompared": bool(comparison)}
            title = row.get("ownerDisplayTitle") or row.get("sourceMetadata", {}).get("title") or row.get("title") or row["videoId"]
            artist = row.get("sourceMetadata", {}).get("artist") or row.get("uploader") or ""
            relevant = [r for r in comparison.get('candidates', [])
                        if not comparison.get('matchedTrackId') or r['trackId'] == comparison['matchedTrackId']]
            reason = "; ".join(reasons.get(r["reason"], r["reason"]) for r in relevant if r.get("reason"))
            if status == "stale":
                reason = labels["stale"]
            if not reason:
                reason = {"matched": "تطابق التسجيل بعد ضبط فرق توقيت ثابت",
                          "matching_body_with_extra_audio": "الصوت المشترك متطابق لكن توجد مقدمة أو نهاية صوتية إضافية",
                          "ambiguous": "أكثر من نسخة اجتازت الفحص؛ لا يمكن اختيار نسخة واحدة"}.get(status, "")
            if not reason and not files:
                reason = "لم تتوفر نسخة Qobuz مرشحة يمكن مقارنتها: " + row["metadataSelection"].get("basis", "")
            if status == "matching_body_with_edge_edits":
                body = next(r["body"] for r in comparison["candidates"] if r["trackId"] == comparison["matchedTrackId"])
                reason += f'; المقطع المطابق: {body["durationSeconds"]:.3f} ثانية، والأطراف المختلفة داخل التداخل: {body["edgeDifferencesSeconds"]:.3f} ثانية'
            if status == 'matching_body_with_speed_change':
                match = next(r for r in comparison['candidates'] if r['trackId'] == comparison['matchedTrackId'])
                aligned = match['alignedWaveform']
                kind = 'matched' if aligned['classification'] == 'strong_recording_match' else aligned['classification']
                reason = f'تطابق الموجة بعد تعويض فرق سرعة ثابت {(match["candidateTimeScale"] - 1) * 100:+.4f}%؛ نتيجة المقارنة بعد التعويض: {labels[kind]}'
            if comparison.get("matchedTrackId"):
                reason += f'; المرشح المتطابق: Qobuz {comparison["matchedTrackId"]}'
            for issue in unavailable:
                reason += f'; Qobuz {issue["trackId"]}: غير متاح: {issue["reason"]}'
            source_metadata = ROOT / "audio/youtube" / row["videoId"] / "download.json"
            if not source and source_metadata.is_file():
                saved = json.loads(source_metadata.read_text(encoding='utf-8'))
                if saved.get('status') == 'replaced_by_flac':
                    reason += '; حُذف صوت YouTube بعد اعتماد FLAC، واحتُفظ بالمعلومات'
                else:
                    reason = "تعذر تنزيل مصدر YouTube: " + (saved.get("error") or "مصدر غير متاح")
            chosen = row.get('ownerAudioSelection', {})
            preference = ('FLAC (Qobuz ' + str(chosen['trackId']) + ')') if chosen.get('source') == 'qobuz' else 'YouTube' if chosen else ''
            notes = '\n'.join(r['note'] for r in chosen.get('reviews', []) if r.get('note'))
            writer.writerow([row["position"], title, artist, labels[status], reason,
                    f'https://www.youtube.com/watch?v={row["videoId"]}', ";".join(str(t["id"]) for t in tracks),
                    source.relative_to(ROOT).as_posix() if source else "",
                    ";".join(f.relative_to(ROOT).as_posix() for _, f in files), preference, chosen.get('file', ''),
                    ';'.join(v['file'] for v in chosen.get('additionalVersions', [])), notes])
            folder = output / "reviews" / row["videoId"]
            text = f'{row["position"]}. {title}\n{labels[status]}\n{reason}\nhttps://www.youtube.com/watch?v={row["videoId"]}\n'
            text += "\n".join(f'https://play.qobuz.com/track/{t["id"]}' for t in tracks) + "\n"
            if chosen:
                text += f'\nاختيار المكتبة: {preference}\n{chosen["file"]}\n{notes}\n'
            folder.mkdir(parents=True, exist_ok=True)
            # Hard links organize retained audio without storing a second copy.
            links = [(source, "01-YouTube" + source.suffix)] if source else []
            links += [(file, f"02-Qobuz-{track_id}" + file.suffix) for track_id, file in files]
            for extra in chosen.get('additionalVersions', []):
                retained = (evidence.candidate(extra['trackId']) if extra['source'] == 'qobuz'
                            else evidence.source(extra.get('videoId', row['videoId'])))
                if retained is None or retained.relative_to(ROOT).as_posix() != extra['file']:
                    raise ValueError('Additional review source no longer matches retained audio')
                label = '02-Qobuz-' + str(extra['trackId']) if extra['source'] == 'qobuz' else '01-YouTube'
                links.append((retained, label + retained.suffix))
            current_links = []
            for original, filename in links:
                digest = evidence.digest(original)
                filename = Path(filename).stem + '-' + digest + Path(filename).suffix
                target = folder / filename
                binding = {'file': target.relative_to(output).as_posix(), 'sha256': digest}
                if binding not in current_links:
                    current_links.append(binding)
                _review_audio_path(output, binding)
                proposed_links[binding['file']] = binding | {'source': original.relative_to(ROOT).as_posix()}
                if target.exists() and not os.path.samefile(original, target) and file_sha256(target) != digest:
                    raise FileExistsError("Preserve unrelated existing review audio: " + str(target))
            details = folder/'details.txt'
            previous = previous_index.get(row['videoId'], {})
            unavailable_audio = previous.get('unavailableAudio', []).copy()
            unavailable_ids = {issue['trackId'] for issue in unavailable}
            for binding in previous.get('audio', []):
                if any(Path(binding['file']).name.startswith(f'02-Qobuz-{track_id}-') for track_id in unavailable_ids):
                    retained = _review_audio_path(output, binding)
                    if retained.is_file() and file_sha256(retained) == binding['sha256']:
                        current_links.append(binding)
                    else:
                        # In-place edits also change hard links; retain them without admitting their audio.
                        unavailable_audio.append(binding)
            if details.exists() and (previous.get('directory') != folder.relative_to(output).as_posix()
                    or previous.get('detailsSha256') != file_sha256(details)):
                raise FileExistsError('Preserve unrelated existing review details: ' + str(details))
            reports[details.relative_to(output).as_posix()] = text.encode('utf-8')
            review_index[row['videoId']] = {'status': status, 'directory': folder.relative_to(output).as_posix(),
                                           'audio': current_links, 'detailsSha256': hashlib.sha256(text.encode('utf-8')).hexdigest()}
            if unavailable:
                review_index[row['videoId']]['unavailableCandidates'] = unavailable
            if unavailable_audio:
                review_index[row['videoId']]['unavailableAudio'] = unavailable_audio
            if status == 'matched':
                continue
            review += [f'## {row["position"]}. {title}', "", labels[status], "", reason, "", f'الملفات: [فتح المجلد](<reviews/{folder.name}/>)', ""]
            if chosen:
                review += [f'اختيار المالك للمكتبة: {preference}', '', notes, '']
        reports['results.csv'] = stream.getvalue().encode('utf-8-sig')
    reports['unconfirmed.txt'] = "\n".join(review).encode('utf-8')
    reports['review-index.json'] = json.dumps(review_index, ensure_ascii=False, indent=2).encode('utf-8')
    build_library(data, evidence=evidence)
    evidence.require_unchanged()
    _publish_comparison_reports(output, reports, links=proposed_links.values(),
        retired=_retired_review_audio(output, previous_index, review_index))
    data["quickAudioComparisonCounts"] = counts
    data["metadataSelectionCounts"] = dict(collections.Counter(r["metadataSelection"]["status"] for r in data["rows"]))
    data["identifiedPlaylistRows"] = sum(r["metadataSelection"]["status"] != "deferred" for r in data["rows"])
    data["metadataDeferredRows"] = len(data["rows"]) - data["identifiedPlaylistRows"]
    data["identifiedUndownloadedUniqueTracks"] = len({track_id for r in data["rows"]
            if (track_id := selected_download_id(r)) is not None and not evidence.candidate(track_id)})
    data["inferredAlbumPlaylistRows"] = sum("album" in r["metadataSelection"]["basis"] and "infer" in r["metadataSelection"]["basis"] for r in data["rows"])
    data["downloadedPlaylistRows"] = sum(bool(r.get("download")) for r in data["rows"])
    downloads, invalid = retained_downloads(evidence)
    audio = list(downloads.values())
    data['invalidRetainedDownloads'] = invalid
    data["downloadedUniqueTracks"] = len(audio)
    data["downloadedAudioBytesTotal"] = sum(f.stat().st_size for f in audio)
    data["downloadPolicy"].update(performAudioAnalysis=True, audioAnalysis="16khz_with_48khz_repeat_precision_clock_and_alternative_review",
                                  candidateSelection="metadata_with_unique_audio_match_for_ties")
    _commit_catalogue_locked(ROOT / "catalogue.json", data)
    if data.get('libraryPolicy', {}).get('includeAllRetainedFlac'):
        _export_library_locked(data, evidence=evidence)
    print(json.dumps({"counts": counts, "output": str(output)}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("references", "compare", "refine", "edges", "repeats", "precision", "alternatives", "drift", "export"))
    parser.add_argument("--reference-timeout-seconds", type=float, default=900)
    args = parser.parse_args()
    data = read_catalogue(ROOT / 'catalogue.json')
    if args.action == "references":
        failed = False
        cancelled = Event()
        fatal = []
        def run(row):
            try:
                from stemwise_engine.provider_operations import ProviderCancelled
                if cancelled.is_set():
                    raise ProviderCancelled('reference batch cancelled')
                return download_reference(row, timeout_seconds=args.reference_timeout_seconds,
                                          cancelled=cancelled.is_set)
            except WorkerRetirementError as error:
                fatal.append(error)
                cancelled.set()
                raise
        pool = ThreadPoolExecutor(max_workers=2)
        try:
            futures = [pool.submit(run, row) for row in data["rows"]]
            for future in as_completed(futures):
                result = future.result()
                print(json.dumps(result, ensure_ascii=False), flush=True)
                failed |= result['status'] == 'failed'
        except BaseException:
            if fatal:
                raise fatal[0]
            raise
        finally:
            cancelled.set()
            pool.shutdown(wait=True, cancel_futures=True)
        return int(failed)
    elif args.action in ("compare", "refine", "edges", "repeats", "precision", "alternatives", "drift"):
        failures = compare_rows(data, refine=args.action != "compare", edges=args.action in ("edges", "repeats", "precision", "alternatives"),
                                repeats=args.action == "repeats", precision=args.action == "precision", alternatives=args.action == "alternatives", drift=args.action == "drift")
        return int(failures > 0)
    else:
        export(data)
        return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""Replay a selected published reference and complete unprompted recording support.

The workflow selects the artist/publisher reference through source research. This
module chooses complete written-reference coverage from that same source before
reading ASR, with same-language manual captions breaking ties before descriptions.
It checks acquired bytes and recording support, not publisher authenticity.
It never approves timing, guesses heard words, or chooses a best-agreeing ASR route.
"""
import hashlib
import html
import json
import math
from pathlib import Path, PurePosixPath
import re
import xml.etree.ElementTree as ET

from stemwise_engine.common import (atomic_write_bytes, canonical_lyrics_language,
    extract_youtube_video_id, parse_strict_json_bytes, read_bounded_regular_bytes,
    resolve_path, validate_video_id)
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.lyrics_normalize import physical_lines
from stemwise_engine.provenance import semantic_source_identity

KIND = 'selected-published-reference-and-recording'
VERSION = 1


def _bytes(value):
    return (json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'),
        allow_nan=False)+'\n').encode('utf-8')


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _read(path, root):
    return read_bounded_regular_bytes(Path(path), max_bytes=MAX_JSON_BYTES, root=Path(root))


def _reference(path, root, raw):
    return {'path': Path(path).resolve().relative_to(Path(root).resolve()).as_posix(),
        'sha256': _sha(raw), 'byteCount': len(raw)}


def _relative(reference, root):
    if (not isinstance(reference, dict) or set(reference) != {'path', 'sha256'}
            or not isinstance(reference['path'], str) or not reference['path']
            or '\\' in reference['path'] or PurePosixPath(reference['path']).is_absolute()
            or any(part in {'', '.', '..'} or ':' in part for part in reference['path'].split('/'))
            or not isinstance(reference['sha256'], str)
            or re.fullmatch('[0-9a-f]{64}', reference['sha256']) is None):
        raise ValueError('Selected source recording reference is invalid')
    path = Path(root)/reference['path']
    raw = _read(path, root)
    if _sha(raw) != reference['sha256']:
        raise ValueError('Selected source recording reference changed')
    return path, raw


def _original_identity(original):
    if not isinstance(original, dict):
        raise ValueError('Selected source recording Original is invalid')
    identity = {key: original.get(key, default) for key, default in (
        ('id', 'original'), ('kind', 'original'), ('format', 'plain'), ('language', None),
        ('script', None), ('text', None), ('componentLanguages', []))}
    if identity['kind'] != 'original' or identity['format'] != 'plain' or not isinstance(identity['text'], str):
        raise ValueError('Selected source recording requires the plain Original')
    return identity


def _written_reference_units(words, *, whole_line=False):
    """Ignore exact written repetitions only; recording occurrence counts stay exact."""
    folded = tuple(word for index, word in enumerate(words) if not index or word != words[index-1])
    if whole_line:
        for size in range(1, len(folded)//2+1):
            if len(folded) % size == 0 and folded[:size]*(len(folded)//size) == folded:
                return folded[:size]
    return folded


def _covers_published_lines(original, published_text):
    """Written-body coverage only; recognition and performed repeat counts are separate."""
    from stemwise_engine.lyric_comparison import units
    language = original['language']
    published = _written_reference_units(units(published_text, language))
    lines = [_written_reference_units(units(line, language), whole_line=True)
        for line in physical_lines(original['text']) if line.strip()]
    return bool(lines) and all(line and any(published[offset:offset+len(line)] == line
        for offset in range(len(published)-len(line)+1)) for line in lines)


def _source_text(raw, record):
    text = raw.decode('utf-8-sig')
    if record['type'] == 'description':
        return text
    extension = record.get('format') or Path(record['path']).suffix[1:]
    if extension == 'vtt':
        lines = physical_lines(text)
        if not re.fullmatch(r'WEBVTT(?:[ \t].*)?', lines[0]):
            raise ValueError('Selected published caption is malformed')
        cues = []
        stamp = r'(?:\d{2,}:)?[0-5]\d:[0-5]\d\.\d{3}'
        for block in re.split(r'\n[ \t]*\n', '\n'.join(lines)):
            rows = block.split('\n')
            if not rows or re.fullmatch(r'NOTE(?:[ \t].*)?|STYLE|REGION', rows[0]):
                continue
            cue = 0 if '-->' in rows[0] else 1
            if len(rows) > cue+1 and re.fullmatch(stamp+r'[ \t]+-->[ \t]+'+stamp+r'(?:[ \t].*)?', rows[cue]):
                cues.extend(html.unescape(re.sub(r'<[^>]*>', '', line)) for line in rows[cue+1:])
        if cues:
            return '\n'.join(cues)
    elif extension == 'json3':
        document = parse_strict_json_bytes(raw)
        return '\n'.join(''.join(segment['utf8'] for segment in event.get('segs', []))
            for event in document['events'] if isinstance(event, dict) and 'segs' in event)
    elif extension in {'ttml', 'srv3'}:
        document = ET.fromstring(text)
        expected = 'tt' if extension == 'ttml' else 'timedtext'
        if document.tag.rsplit('}', 1)[-1] == expected:
            return '\n'.join(''.join(element.itertext()) for element in document.iter()
                if element.tag.rsplit('}', 1)[-1] == 'p')
    raise ValueError('Selected published caption has no supported body')


def _source(job_dir, manifest, metadata, selected):
    if (not isinstance(selected, dict) or set(selected) != {'sourceVideoId', 'path', 'sha256'}):
        raise ValueError('Select one retained published source before comparison')
    video = validate_video_id(selected['sourceVideoId'])
    url = metadata['sources']['original']['url']
    if extract_youtube_video_id(url) != video:
        raise ValueError('Selected published source differs from the draft source URL')
    path, raw = _relative({key: selected[key] for key in ('path', 'sha256')}, job_dir)
    sources = [item for item in manifest['lyricEvidenceSources'] if item.get('videoId') == video]
    if len(sources) != 1:
        raise ValueError('Selected published source identity is missing or ambiguous')
    source = sources[0]
    normal = source['normalYouTube']
    if (normal.get('checked') is not True or normal.get('exactVideoIdConfirmed') is not True
            or extract_youtube_video_id(normal['exactUrl']) != video
            or 'submittedUrl' in source and (not isinstance(source['submittedUrl'], str)
                or extract_youtube_video_id(source['submittedUrl']) != video)):
        raise ValueError('Selected published source acquisition identity changed')
    records = [record for record in normal['records'] if isinstance(record, dict)
        and record.get('type') in {'description', 'manual-subtitle'}
        and isinstance(record.get('path'), str)
        and resolve_path(record['path'], base=job_dir).resolve() == path.resolve()
        and record.get('sha256') == selected['sha256']]
    if len(records) != 1:
        raise ValueError('Selected published source record is missing or ambiguous')
    record = records[0]
    if record.get('byteCount', len(raw)) != len(raw):
        raise ValueError('Selected published source size changed')
    return {**selected, 'byteCount': len(raw), 'url': url,
        'acquisitionSha256': _sha(_bytes(source)), 'recordSha256': _sha(_bytes(record))}, _source_text(raw, record)


def selection(job_dir):
    """Choose body coverage within the draft's selected upload without consulting ASR."""
    from stemwise_engine.lyrics_intake import read_recording_draft
    job_dir = Path(job_dir)
    draft, _, metadata, _ = read_recording_draft(job_dir)
    url = metadata.get('sources', {}).get('original', {}).get('url')
    if not isinstance(url, str):
        return None
    try:
        video = extract_youtube_video_id(url)
    except ValueError:
        return None  # This branch consumes acquired YouTube descriptions/manual captions only.
    manifest = parse_strict_json_bytes(_read(job_dir/'sources.json', job_dir))
    sources = [source for source in manifest['lyricEvidenceSources'] if source.get('videoId') == video]
    if len(sources) != 1:
        return None
    candidates = []
    for record in sources[0]['normalYouTube']['records']:
        if not isinstance(record, dict) or record.get('type') not in {'description', 'manual-subtitle'}:
            continue
        if record['type'] == 'manual-subtitle':
            if not isinstance(record.get('language'), str) or not record['language']:
                raise ValueError('Published caption acquisition language is invalid')
            if record['language'] != metadata.get('originalLanguage'):
                continue
        if record.get('status') == 'unavailable':
            if (record['type'] != 'manual-subtitle' or set(record) != {
                    'type', 'surface', 'language', 'automatic', 'status', 'reason'}
                    or record['surface'] != 'normal-youtube' or record['automatic'] is not False
                    or not isinstance(record['reason'], str)
                    or re.fullmatch(r'[A-Za-z0-9_.:+-]{1,120}', record['reason']) is None):
                raise ValueError('Published caption acquisition failure is malformed')
            continue
        if ('status' in record or not isinstance(record.get('path'), str) or not record['path']
                or not isinstance(record.get('sha256'), str)
                or re.fullmatch('[0-9a-f]{64}', record['sha256']) is None):
            raise ValueError('Published source acquisition record is malformed')
        path = resolve_path(record['path'], base=job_dir).resolve()
        relative = path.relative_to(job_dir.resolve()).as_posix()
        selected = {'sourceVideoId': video, 'path': relative, 'sha256': record['sha256']}
        try:
            _, text = _source(job_dir, manifest, metadata, selected)
            complete = _covers_published_lines({'text': draft, 'language': metadata['originalLanguage']}, text)
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            complete = False
        candidates.append((not complete, record['type'] != 'manual-subtitle', relative, record['sha256']))
    if not candidates:
        return None
    _, _, path, digest = min(candidates)
    return {'sourceVideoId': video, 'path': path, 'sha256': digest}


def _primary(evidence_root, video_id, original, audio_sha, duration_ms):
    from stemwise_engine.lyric_content import _recognized
    language = canonical_lyrics_language(original['language'])
    language = None if language in {'mul', 'und'} else language
    path = evidence_root/(f'qwen-structure-{language}.private.json' if language else 'qwen-structure.private.json')
    raw = _read(path, evidence_root)
    cache = parse_strict_json_bytes(raw)
    provenance = cache['provenance']
    audio = provenance['audio']
    if (cache.get('videoId') != video_id or 'modelRevision' not in provenance or 'workerOutput' not in cache
            or provenance.get('language') != language or provenance.get('audioView', 'mix') != 'mix'
            or any(key in audio for key in ('routeId', 'sourceViewId', 'sourceAudioSha256'))
            or audio.get('stream', {}).get('durationMs') != duration_ms):
        raise ValueError('Selected full recording primary identity changed')
    text = _recognized(cache, audio_sha)
    return _reference(path, evidence_root, raw), text


def _diagnostic_whisper(cache, audio_sha, duration_ms, language):
    """Read raw optional words; this does not qualify their numerical producer."""
    if set(cache) != {'cacheVersion', 'videoId', 'model', 'language', 'promptSha256',
            'provenance', 'cacheIdentity', 'evidence', 'words'}:
        raise ValueError('Optional Whisper cache shape changed')
    provenance, words = cache['provenance'], cache['words']
    if (type(cache['cacheVersion']) is not int or cache['cacheVersion'] <= 0
            or cache['language'] != language or not isinstance(cache['model'], str)
            or not isinstance(cache['cacheIdentity'], dict) or not isinstance(cache['evidence'], dict)
            or cache['promptSha256'] != _sha(b'')
            or provenance.get('audio', {}).get('sha256') != audio_sha
            or provenance.get('durationMs') != duration_ms
            or provenance.get('decoder', {}).get('promptMode') != 'withheld'
            or not isinstance(words, list) or not words):
        raise ValueError('Optional Whisper recording context changed')
    previous = 0.0
    for word in words:
        if (not isinstance(word, dict) or set(word) != {'word', 'raw', 'start', 'end'}
                or not isinstance(word['raw'], str) or not word['raw'].strip()
                or not isinstance(word['word'], str)
                or any(type(word[key]) not in (int, float) or not math.isfinite(word[key]) for key in ('start', 'end'))
                or not previous <= word['start'] <= word['end'] <= duration_ms/1000+.001):
            raise ValueError('Optional Whisper raw words changed')
        previous = word['start']
    return words


def _contrary(evidence_root, primary, original, indices, audio_sha, duration_ms, *, references=None):
    from stemwise_engine.lyric_content import _recognized, diagnostic_discrepancy_scope
    if references is None:
        paths = [(path, None) for path in sorted({path for pattern in (
            'transcript*.private.json', 'qwen-structure*.private.json', 'native-asr-*-mix.private.json',
            'content-recovery-*.private.json', 'nemo-lexical-*.private.json')
            for path in evidence_root.glob(pattern)})]
    else:
        paths = []
        for reference in references:
            if (not isinstance(reference, dict) or set(reference) != {'path', 'sha256', 'byteCount'}
                    or type(reference['byteCount']) is not int or reference['byteCount'] < 0):
                raise ValueError('Bound recording diagnostic reference is invalid')
            try:
                path, raw = _relative({key: reference[key] for key in ('path', 'sha256')}, evidence_root)
            except (OSError, ValueError) as error:
                raise ValueError('Bound recording diagnostic is unavailable') from error
            if len(raw) != reference['byteCount']:
                raise ValueError('Bound recording diagnostic size changed')
            paths.append((path, reference))
    observations, seen = [], {primary['sha256']}
    for path, reference in paths:
        try:
            raw = _read(path, evidence_root)
            digest = _sha(raw)
            if reference is not None and (digest != reference['sha256'] or len(raw) != reference['byteCount']):
                raise ValueError('Bound recording diagnostic changed during assessment')
            if digest in seen:
                if reference is not None:
                    raise ValueError('Bound recording diagnostic is duplicated or is the primary')
                continue
            cache = parse_strict_json_bytes(raw)
            provenance = cache['provenance']
            audio = provenance.get('audio', {})
            if (cache.get('videoId') != evidence_root.name or provenance.get('audioView', 'mix') != 'mix'
                    or any(key in audio for key in ('routeId', 'sourceViewId', 'sourceAudioSha256'))
                    or provenance.get('durationMs', audio.get('stream', {}).get('durationMs')) != duration_ms):
                raise ValueError('Recording diagnostic identity changed')
            language = canonical_lyrics_language(original['language'])
            language = None if language in {'mul', 'und'} else language
            words = (_diagnostic_whisper(cache, audio_sha, duration_ms, language) if 'words' in cache
                else _recognized(cache, audio_sha, candidate_only=True))
            scope = diagnostic_discrepancy_scope(original, indices, words)
        except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
            if reference is not None:
                raise ValueError('Bound recording diagnostic is unavailable or ineligible') from error
            continue  # Ineligible caches neither support nor veto this decision.
        observations.append({'recognition': _reference(path, evidence_root, raw),
            'role': 'diagnostic-observation', 'scope': scope})
        seen.add(digest)
    return observations


def _decision(*, job_dir, evidence_root, video_id, original, indices, audio_sha, duration_ms,
              selected_source, local_verifier=None, contrary_references=None):
    from stemwise_engine.lyric_comparison import units
    from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping, lexical_mapping_identity
    from stemwise_engine.lyrics_intake import read_recording_draft
    if local_verifier is not None:
        raise ValueError('Local acoustic alternative claims require a qualified checker')
    original = _original_identity(original)
    job_dir, evidence_root = Path(job_dir), Path(evidence_root)
    validate_video_id(video_id)
    if (evidence_root.name != video_id or job_dir.name != video_id
            or re.fullmatch('[0-9a-f]{64}', audio_sha) is None
            or type(duration_ms) is not int or duration_ms <= 0):
        raise ValueError('Selected source recording identity is invalid')
    draft, draft_sha, metadata, metadata_sha = read_recording_draft(job_dir)
    physical = physical_lines(original['text'])
    complete = tuple(index for index, line in enumerate(physical) if line.strip())
    if (not complete or tuple(indices) != complete or draft != original['text']
            or original['language'] != metadata['originalLanguage']
            or original.get('script') != metadata.get('originalScript')
            or original.get('componentLanguages', []) != metadata.get('componentLanguages', [])):
        raise ValueError('Selected source recording Original or occurrences changed')
    sources_raw = _read(job_dir/'sources.json', job_dir)
    manifest = parse_strict_json_bytes(sources_raw)
    if manifest.get('videoId') != video_id:
        raise ValueError('Selected source recording manifest belongs to another job')
    # Written-body selection/coverage is complete before reading the fixed ASR primary.
    source, source_text = _source(job_dir, manifest, metadata, selected_source)
    if not _covers_published_lines(original, source_text):
        raise ValueError('Selected published source does not cover the complete Original')
    primary, recognized = _primary(evidence_root, video_id, original, audio_sha, duration_ms)
    expected = tuple(word for index in complete for word in units(physical[index], original['language']))
    if expected != units(recognized, original['language']):
        raise ValueError('Selected recording primary does not support every Original occurrence')
    mapping = build_lexical_occurrence_mapping(video_id, original, complete)
    decision = {'formatVersion': VERSION, 'kind': KIND, 'videoId': video_id,
        'draftSha256': draft_sha, 'metadataSha256': metadata_sha, 'originalSha256': _sha(_bytes(original)),
        'audioSha256': audio_sha, 'durationMs': duration_ms, 'sourcePhysicalIndices': list(complete),
        'mappingSha256': lexical_mapping_identity(mapping)['mappingSha256'], 'sourcesSha256': _sha(sources_raw),
        'source': source, 'primary': primary,
        'contrary': _contrary(evidence_root, primary, original, complete, audio_sha, duration_ms,
            references=contrary_references),
        'policy': {name: semantic_source_identity(Path(__file__).with_name(name)) for name in
            ('source_recording_content.py', 'lyric_comparison.py')}, 'decision': 'admitted'}
    if read_recording_draft(job_dir)[1::2] != (draft_sha, metadata_sha) or _read(job_dir/'sources.json', job_dir) != sources_raw:
        raise ValueError('Selected source recording inputs changed during assessment')
    return decision


def validate(record, **inputs):
    """Recompute the decision from current bytes; a serialized verdict is no authority."""
    selected = {key: record['source'][key] for key in ('sourceVideoId', 'path', 'sha256')}
    if not isinstance(record.get('contrary'), list):
        raise ValueError('Bound recording diagnostics are invalid')
    expected = _decision(**inputs, selected_source=selected,
        contrary_references=[observation['recognition'] for observation in record['contrary']])
    if record != expected:
        raise ValueError('Selected source recording decision changed')
    return record


def create(**inputs):
    """Publish one immutable validated decision and return its evidence reference."""
    inputs = dict(inputs)
    if inputs.get('selected_source') is None:
        inputs['selected_source'] = selection(inputs['job_dir'])
    record = _decision(**inputs)
    validate(record, **{key: value for key, value in inputs.items() if key not in {'selected_source', 'local_verifier'}})
    raw = _bytes(record)
    root = Path(inputs['evidence_root'])
    path = root/f'source-recording-content-{_sha(raw)}.private.json'
    if path.exists() or path.is_symlink():
        if _read(path, root) != raw:
            raise ValueError('Immutable selected source recording decision changed')
    else:
        atomic_write_bytes(path, raw, root=root)
    return {'path': path.name, 'sha256': _sha(raw)}


def current_admission(reference, **inputs):
    """Read current retained content authority without writes, models or network."""
    if reference is None:
        return None
    _, raw = _relative(reference, inputs['evidence_root'])
    return validate(parse_strict_json_bytes(raw), **inputs)

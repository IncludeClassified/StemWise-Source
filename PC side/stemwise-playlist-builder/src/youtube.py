"""YouTube inventory, temporary analysis audio, metadata, and artwork adapters."""

from __future__ import annotations

import io
from stemwise_engine.provider_operations import (fetch_bytes, bounded_provider_operation, current_budget,
                                 optional_provider_stage, ProviderDeadline, ProviderStageDeadline, ProviderCancelled, ProviderError)
import json
import math
import os
import re
import subprocess
import shutil
import sys
import time
import tempfile
import traceback
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Iterable

from stemwise_engine.common import (
    WorkerRetirementError,
    atomic_write_bytes,
    atomic_write_json,
    durable_replace,
    compact_error,
    read_bounded_json,
    raise_with_cleanup_failure,
    run_process_tree,
    sha256_file,
    utc_now,
    validate_video_id,
    validate_generated_path,
)
from stemwise_engine.contract import MAX_DURATION_MS, MAX_JSON_BYTES
from stemwise_engine.ytdlp_update import activate_installed, maybe_update_ytdlp

try:
    activate_installed()
except WorkerRetirementError:
    raise
except Exception as error:
    # Status and editorial actions must remain usable when optional updates are unavailable.
    print(f"yt-dlp runtime unavailable: {type(error).__name__}", file=sys.stderr)


CENTER_SQUARE_PADDING_THRESHOLD = 8.0
MAX_ANALYSIS_SOURCE_BYTES = 1024 * 1024 * 1024
MAX_ANALYSIS_WAV_BYTES = (MAX_DURATION_MS // 1000) * 48_000 * 2 * 4 + 1024 * 1024
_DOWNLOAD_SOURCE = Path(__file__).resolve()
DENO_RUNTIME = Path(
    os.environ.get("STEMWISE_DENO")
    or Path(__file__).resolve().parents[2] / "runtime" / "deno" / "deno"
)


def canonical_url(video_id: str) -> str:
    return f"https://www.youtube.com/watch?v={validate_video_id(video_id)}"


def _ydl(options: dict[str, Any], *, check_update: bool = True):
    options = dict(options)
    probe_url = options.pop("_stemwise_probe_url", None)
    if check_update:
        maybe_update_ytdlp(DENO_RUNTIME, **({"probe_url": probe_url} if probe_url else {}))
    activate_installed()
    try:
        import yt_dlp
    except ImportError as error:
        raise RuntimeError("yt-dlp is not installed; run setup first") from error
    # Detached audio workers inherit this job's exact downloader, including the base install.
    os.environ.setdefault("STEMWISE_YTDLP_VERSION", "base")
    defaults = {
        "js_runtimes": {"deno": {"path": str(DENO_RUNTIME)}},
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "retries": 5,
        "fragment_retries": 5,
        "socket_timeout": 30,
        "noplaylist": False,
        "plugin_dirs": [],
    }
    defaults.update(options)
    return yt_dlp.YoutubeDL(defaults)


@bounded_provider_operation
def extract_youtube_info(url: str, options: dict[str, Any], *, download: bool = False,
                         check_update: bool = True):
    options = dict(options)
    probe_url = options.pop('_stemwise_probe_url', None)
    if check_update:
        maybe_update_ytdlp(DENO_RUNTIME, **({'probe_url': probe_url} if probe_url else {}))
    try:
        raw = current_budget().run(['--youtube', json.dumps({
            'url': url, 'options': options, 'download': download}, ensure_ascii=False)])
    except ProviderError as error:
        if error.cause['exceptionType'] == 'DownloadError':
            from yt_dlp.utils import DownloadError
            raise DownloadError(str(error)) from error
        raise
    return json.loads(raw.decode('utf-8'))


@bounded_provider_operation
def inventory_playlist(url: str) -> dict[str, Any]:
    """Fetch a no-audio, stable playlist snapshot and preserve source order."""
    options = {
        "extract_flat": "in_playlist",
        "skip_download": True,
        "ignoreerrors": True,
        "lazy_playlist": False,
    }
    raw = extract_youtube_info(url, options)
    if not isinstance(raw, dict):
        raise RuntimeError("yt-dlp did not return playlist metadata")

    items: list[dict[str, Any]] = []
    omitted: list[dict[str, Any]] = []
    seen: set[str] = set()
    raw_entries = raw.get("entries")
    if raw_entries is None:
        raw_entries = [raw] if isinstance(raw.get("id"), str) else []
    else:
        raw_entries = list(raw_entries)
    for source_position, entry in enumerate(raw_entries, start=1):
        if not isinstance(entry, dict):
            omitted.append({"sourcePosition": source_position, "reason": "Unavailable playlist entry"})
            continue
        video_id = str(entry.get("id") or "")
        try:
            validate_video_id(video_id)
        except ValueError:
            omitted.append(
                {
                    "sourcePosition": source_position,
                    "reason": "Entry had no valid 11-character YouTube video ID",
                }
            )
            continue
        if video_id in seen:
            omitted.append(
                {
                    "sourcePosition": source_position,
                    "videoId": video_id,
                    "reason": "Duplicate video ID",
                }
            )
            continue
        seen.add(video_id)
        item: dict[str, Any] = {
            "position": len(items) + 1,
            "sourcePosition": source_position,
            "videoId": video_id,
            "url": canonical_url(video_id),
        }
        for source_name, target_name in (
            ("title", "title"),
            ("uploader", "uploader"),
            ("channel", "channel"),
        ):
            if isinstance(entry.get(source_name), str) and entry[source_name].strip():
                item[target_name] = entry[source_name].strip()
        if isinstance(entry.get("duration"), (int, float)) and entry["duration"] > 0:
            item["durationSeconds"] = float(entry["duration"])
        items.append(item)

    if not items:
        raise RuntimeError("The playlist snapshot contained no usable public video IDs")
    return {
        "snapshotVersion": 1,
        "generatedAt": utc_now(),
        "playlist": {
            "id": raw.get("id"),
            "title": raw.get("title") or "playlist",
            "uploader": raw.get("uploader") or raw.get("channel"),
        },
        "itemCount": len(items),
        "omittedCount": len(omitted),
        "items": items,
        "omitted": omitted,
    }


@bounded_provider_operation
def fetch_video_info(video_id: str) -> dict[str, Any]:
    options = {"skip_download": True, "noplaylist": True, "_stemwise_probe_url": canonical_url(video_id)}
    info = extract_youtube_info(canonical_url(video_id), options)
    if not isinstance(info, dict) or info.get("id") != video_id:
        raise RuntimeError("yt-dlp returned metadata for an unexpected video")
    return info


def _download_analysis_audio_attempt(
    video_id: str,
    output_template: str,
    selected_format: str,
    extractor_args: dict[str, Any] | None,
    info_path: Path,
    *,
    max_source_bytes: int = MAX_ANALYSIS_SOURCE_BYTES,
) -> None:
    """Worker boundary for one yt-dlp attempt; the parent owns its deadline."""
    from stemwise_media.quick_batch import require_youtube_audio_allowed
    require_youtube_audio_allowed(video_id)

    def reject_unsupported_duration(
        info: dict[str, Any], *, incomplete: bool = False
    ) -> str | None:
        duration = info.get("duration")
        if (
            not incomplete
            and isinstance(duration, (int, float))
            and not isinstance(duration, bool)
            and math.isfinite(float(duration))
            and float(duration) * 1000 > MAX_DURATION_MS
        ):
            return f"Unsupported analysis duration: {duration} seconds"
        return None

    def enforce_size(status: dict[str, Any]) -> None:
        for key in ("downloaded_bytes", "total_bytes", "total_bytes_estimate"):
            value = status.get(key)
            if isinstance(value, int) and value > max_source_bytes:
                raise RuntimeError("Temporary analysis source exceeds its byte limit")

    options: dict[str, Any] = {
        "_stemwise_probe_url": canonical_url(video_id),
        "format": selected_format,
        "outtmpl": output_template,
        "noplaylist": True,
        "overwrites": False,
        "continuedl": True,
        "nopart": False,
        "max_filesize": max_source_bytes,
        "match_filter": reject_unsupported_duration,
        "progress_hooks": [enforce_size],
    }
    if extractor_args:
        options["extractor_args"] = extractor_args
    with _ydl(options) as ydl:
        candidate = ydl.extract_info(canonical_url(video_id), download=True)
        if not isinstance(candidate, dict) or candidate.get("id") != video_id:
            raise RuntimeError("yt-dlp downloaded an unexpected video")
        sanitizer = getattr(ydl, "sanitize_info", None)
        serializable = sanitizer(candidate) if callable(sanitizer) else candidate
    atomic_write_json(info_path, serializable, pretty=False)


def _download_worker_main(arguments: list[str]) -> int:
    if len(arguments) != 6:
        print("Invalid temporary-audio worker arguments", file=sys.stderr)
        return 2
    video_id, output_template, selected_format, extractor_json, info_name, max_bytes = arguments
    try:
        extractor_args = json.loads(extractor_json)
        if extractor_args is not None and not isinstance(extractor_args, dict):
            raise ValueError("extractor arguments must be an object or null")
        _download_analysis_audio_attempt(
            video_id,
            output_template,
            selected_format,
            extractor_args,
            Path(info_name),
            max_source_bytes=int(max_bytes),
        )
        return 0
    except WorkerRetirementError:
        from stemwise_engine.worker_protocol import fatal_model_failure
        fatal_model_failure('download-worker-retirement-failed')
    except Exception:
        print(traceback.format_exc()[-12000:].rstrip(), file=sys.stderr)
        return 2


def completed_analysis_download(video_id: str, temp_dir: Path):
    receipt_path, info_path = temp_dir/'original-source.json', temp_dir/'download-info.json'
    if not receipt_path.is_file() or not info_path.is_file():
        return None
    request = read_bounded_json(temp_dir/'download-request.json', max_bytes=MAX_JSON_BYTES, root=temp_dir)
    receipt = read_bounded_json(receipt_path, max_bytes=MAX_JSON_BYTES, root=temp_dir)
    source = validate_generated_path(temp_dir, temp_dir/receipt['file'], workspace_root=temp_dir)
    info = read_bounded_json(info_path, max_bytes=MAX_JSON_BYTES, root=temp_dir)
    if (request.get('videoId') != video_id or receipt.get('videoId') != video_id
            or not isinstance(info, dict) or info.get('id') != video_id or source.parent != temp_dir
            or not 0 < source.stat().st_size <= MAX_ANALYSIS_SOURCE_BYTES
            or source.stat().st_size != receipt.get('byteCount') or sha256_file(source) != receipt.get('sha256')):
        raise ValueError('Completed download source changed')
    return source, info


def download_analysis_audio(
    video_id: str,
    temp_dir: Path,
    *,
    timeout_seconds: int,
) -> tuple[dict[str, Any], Path]:
    """Download best available audio and decode it to a temporary PCM WAV."""
    validate_video_id(video_id)
    from stemwise_media.quick_batch import require_youtube_audio_allowed
    require_youtube_audio_allowed(video_id)
    from stemwise_media.quick_batch import ROOT, source_file
    if temp_dir.resolve().is_relative_to(Path(ROOT).resolve()):
        raise ValueError("Temporary analysis cannot overwrite the retained library")
    temp_dir.mkdir(parents=True, exist_ok=True)
    output_template = str(temp_dir / "source.%(ext)s")
    deadline = time.monotonic() + timeout_seconds
    attempts = (("bestaudio/best", None),)
    info: dict[str, Any] | None = None
    attempt_errors: list[str] = []
    attempt_error: Exception | None = None
    info_path = temp_dir / "download-info.json"
    retained = source_file(video_id)
    if retained is not None:
        retained = validate_generated_path(Path(ROOT), retained, workspace_root=Path(ROOT).parent)
        if not 0 < retained.stat().st_size <= MAX_ANALYSIS_SOURCE_BYTES:
            raise ValueError("Retained analysis source exceeds its byte limit")
    request_path = temp_dir/'download-request.json'
    from stemwise_engine.provenance import semantic_source_identity
    from stemwise_engine.analyzers import package_version
    request = {'videoId': video_id, 'format': attempts[0][0], 'producerSha256': semantic_source_identity(
                   _DOWNLOAD_SOURCE, ('_ydl', '_download_analysis_audio_attempt', 'completed_analysis_download', 'download_analysis_audio', 'decode_analysis_audio', 'probe_duration_ms')),
               'downloaderVersion': package_version('yt-dlp'),
               'retainedSha256': sha256_file(retained) if retained is not None else None}
    if request_path.exists():
        if read_bounded_json(request_path, max_bytes=MAX_JSON_BYTES, root=temp_dir) != request:
            raise ValueError('Partial download request differs; retained bytes require review')
    else:
        if retained is None and any(temp_dir.glob('source.*')):
            raise ValueError('Partial download has no current input binding')
        atomic_write_json(request_path, request)
    receipt_path = temp_dir/'original-source.json'
    completed_source = None
    completed = completed_analysis_download(video_id, temp_dir)
    if completed is not None:
        completed_source, info = completed
        attempts = ()
    if retained is not None and completed_source is None:
        digest = request['retainedSha256']
        copied = validate_generated_path(temp_dir, temp_dir / ("source" + retained.suffix), workspace_root=temp_dir)
        with tempfile.NamedTemporaryFile(prefix='.source-', suffix='.pending', dir=temp_dir, delete=False) as staging:
            pending = Path(staging.name)
        try:
            shutil.copyfile(retained, pending)
            if sha256_file(pending) != digest or sha256_file(retained) != digest:
                raise ValueError("Retained analysis source changed during copying")
            durable_replace(pending, copied)
        finally:
            failure = sys.exc_info()[1]
            try:
                pending.unlink(missing_ok=True)
            except BaseException as cleanup_error:
                raise_with_cleanup_failure(failure, cleanup_error)
        info = {"id": video_id}
        atomic_write_json(info_path, info)
        attempts = ()
    for selected_format, extractor_args in attempts:
        try:
            remaining_seconds = deadline - time.monotonic()
            if remaining_seconds <= 0:
                raise subprocess.TimeoutExpired("yt-dlp", timeout_seconds)
            completed = run_process_tree(
                [
                    sys.executable,
                    "-m",
                    "stemwise_engine.youtube",
                    "_download-attempt",
                    video_id,
                    output_template,
                    selected_format,
                    json.dumps(extractor_args, separators=(",", ":")),
                    str(info_path),
                    str(MAX_ANALYSIS_SOURCE_BYTES),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=remaining_seconds,
            )
            from stemwise_engine.worker_protocol import raise_if_worker_stopped
            raise_if_worker_stopped(completed, code='temporary-audio-download',
                diagnostic_path=temp_dir/'download.failure.private.txt')
            if completed.returncode != 0:
                detail = compact_error(RuntimeError(completed.stderr or "yt-dlp failed"), 180)
                if "Unsupported analysis duration" in detail:
                    raise RuntimeError(detail)
                attempt_errors.append(detail)
                attempt_error = subprocess.CalledProcessError(
                    completed.returncode, "yt-dlp", stderr=(completed.stderr or "")[-12000:])
                continue
            candidate = read_bounded_json(
                info_path, max_bytes=MAX_JSON_BYTES, root=temp_dir
            )
            if not isinstance(candidate, dict) or candidate.get("id") != video_id:
                raise RuntimeError("yt-dlp downloaded an unexpected video")
            info = candidate
            break
        except subprocess.TimeoutExpired as error:
            raise RuntimeError("Temporary-audio acquisition exceeded its deadline") from error
        except WorkerRetirementError:
            raise
        except Exception as error:
            if "Unsupported analysis duration" in str(error):
                raise
            attempt_errors.append(compact_error(error, 180))
            attempt_error = error
    if info is None:
        raise RuntimeError(
            "All temporary-audio download strategies failed: " + "; ".join(attempt_errors)
        ) from attempt_error
    if not isinstance(info, dict) or info.get("id") != video_id:
        raise RuntimeError("yt-dlp downloaded an unexpected video")

    candidates = [
        path
        for path in temp_dir.glob("source.*")
        if path.is_file() and not path.name.endswith((".part", ".ytdl"))
    ]
    if not candidates:
        raise RuntimeError("yt-dlp reported success but produced no audio file")
    if any(path.stat().st_size > MAX_ANALYSIS_SOURCE_BYTES for path in candidates):
        raise RuntimeError("Temporary analysis source exceeds its byte limit")
    source = completed_source or (copied if retained is not None else max(candidates, key=lambda path: path.stat().st_size))
    remaining_seconds = deadline - time.monotonic()
    if remaining_seconds <= 0:
        raise RuntimeError("Temporary-audio acquisition exceeded its deadline")
    # Retain the exact compressed source until the phone bundle is committed.
    atomic_write_json(temp_dir / 'original-source.json', {
        'videoId': video_id, 'file': source.name, 'sha256': sha256_file(source),
        'byteCount': source.stat().st_size})
    wav = decode_analysis_audio(source, temp_dir, timeout_seconds=remaining_seconds)
    return info, wav


def decode_analysis_audio(source: Path, temp_dir: Path, *, timeout_seconds: float) -> Path:
    """Decode retained or downloaded input without deleting the caller's source."""
    if not source.is_file() or not 0 < source.stat().st_size <= MAX_ANALYSIS_SOURCE_BYTES:
        raise ValueError("Analysis source is missing or exceeds its byte limit")
    temp_dir.mkdir(parents=True, exist_ok=True)
    wav = temp_dir / "analysis.wav"
    from stemwise_engine.original_delivery import STRICT_DECODE_OPTIONS
    with tempfile.NamedTemporaryFile(prefix=".analysis-", suffix=".wav", dir=temp_dir, delete=False) as staging:
        pending = Path(staging.name)
    retirement_failed = False
    try:
        completed = run_process_tree(
            [
                "ffmpeg",
                "-hide_banner",
                "-loglevel",
                "error",
                *STRICT_DECODE_OPTIONS,
                "-nostdin",
                "-y",
                "-i",
                str(source),
                "-map",
                "0:a:0",
                "-vn",
                "-c:a",
                "pcm_f32le",
                "-t",
                str((MAX_DURATION_MS * 48 + 1) / 48000),
                "-ar",
                "48000",
                "-ac",
                "2",
                "-fs",
                str(MAX_ANALYSIS_WAV_BYTES),
                str(pending),
            ],
            text=True,
            capture_output=True,
            check=False,
            timeout=timeout_seconds,
        )
        if completed.returncode != 0 or not pending.is_file() or pending.stat().st_size <= 44:
            raise RuntimeError(f"FFmpeg audio decode failed: {completed.stderr.strip()[-500:]}")
        import soundfile as sf
        with pending.open('rb') as stream:
            decoded = sf.info(stream)
        if decoded.frames <= 0:
            raise ValueError("Analysis source produced no decoded audio")
        if decoded.frames > MAX_DURATION_MS * 48:
            raise ValueError("Decoded analysis duration exceeds the supported limit")
        if pending.stat().st_size > MAX_ANALYSIS_WAV_BYTES:
            raise RuntimeError("Decoded temporary analysis audio exceeds its byte limit")
        durable_replace(pending, wav)
        return wav
    except WorkerRetirementError:
        retirement_failed = True
        raise
    finally:
        if not retirement_failed:
            pending.unlink(missing_ok=True)


def probe_duration_ms(audio_path: Path) -> int:
    import soundfile as sf
    from stemwise_engine.common import source_duration_ms
    try:
        info = sf.info(audio_path)
        duration_ms = source_duration_ms(info.frames, info.samplerate)
    except (OSError, ValueError) as error:
        raise RuntimeError('Analysis audio has no valid sample timeline') from error
    if duration_ms <= 0 or duration_ms > MAX_DURATION_MS:
        raise RuntimeError(f'Unsupported analysis duration: {duration_ms} ms')
    return duration_ms


def _clean_text(value: Any, limit: int = 12_000) -> str | None:
    if not isinstance(value, str):
        return None
    text = value.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]", " ", text)
    text = re.sub(r"<\s*/?\s*[A-Za-z][^>]*>", "", text).strip()
    return text[:limit] or None


def _people(value: Any) -> list[str]:
    values: Iterable[Any]
    if isinstance(value, list):
        values = value
    elif isinstance(value, str):
        values = re.split(r"\s*(?:,|;|\s+&\s+)\s*", value)
    else:
        return []
    result: list[str] = []
    for item in values:
        if isinstance(item, dict):
            item = item.get("name")
        cleaned = _clean_text(item, 300)
        if cleaned and cleaned not in result:
            result.append(cleaned)
    return result


def metadata_from_info(info: dict[str, Any], video_id: str) -> dict[str, Any]:
    """Create conservative YouTube fallback metadata without asserting a release match."""
    display_title = _clean_text(info.get("track"), 500) or _clean_text(info.get("title"), 500)
    if not display_title:
        raise RuntimeError("YouTube returned no usable title")

    artists = _people(info.get("artists")) or _people(info.get("artist")) or _people(info.get("creators"))
    title = display_title
    if not artists:
        # Official music uploads frequently expose only "Artist - Track" in title.
        split = re.split(r"\s+[-–—]\s+", display_title, maxsplit=1)
        if len(split) == 2 and split[0].strip() and split[1].strip():
            artists = [split[0].strip()]
            title = split[1].strip()
    if not artists:
        fallback = _clean_text(info.get("uploader") or info.get("channel"), 300)
        if not fallback:
            fallback = "Unknown artist"
        artists = [fallback]

    page = canonical_url(video_id)
    metadata: dict[str, Any] = {
        "matchStatus": "youtube-fallback",
        "title": title,
        "artists": artists,
        "links": [{"type": "youtube", "url": page}],
        "sources": [{"name": "YouTube", "url": page}],
    }
    album_title = _clean_text(info.get("album"), 500)
    if album_title:
        album: dict[str, Any] = {"title": album_title}
        album_artists = _people(info.get("album_artists")) or _people(info.get("album_artist"))
        if album_artists:
            album["artists"] = album_artists
        release_year = info.get("release_year")
        if isinstance(release_year, int) and 1000 <= release_year <= 9999:
            album["releaseDate"] = str(release_year)
        track_number = info.get("track_number")
        if isinstance(track_number, int) and track_number > 0:
            album["trackNumber"] = track_number
        metadata["album"] = album
    genres = _people(info.get("genres")) or _people(info.get("genre"))
    if genres:
        metadata["genres"] = genres
    return metadata


def _thumbnail_candidates(info: dict[str, Any], video_id: str) -> list[str]:
    ranked: list[tuple[int, int, str]] = []
    for index, thumbnail in enumerate(info.get("thumbnails") or []):
        if not isinstance(thumbnail, dict) or not isinstance(thumbnail.get("url"), str):
            continue
        width = thumbnail.get("width") if isinstance(thumbnail.get("width"), int) else 0
        height = thumbnail.get("height") if isinstance(thumbnail.get("height"), int) else 0
        preference = thumbnail.get("preference") if isinstance(thumbnail.get("preference"), int) else index
        ranked.append((width * height, preference, thumbnail["url"]))
    for quality in ("maxresdefault", "sddefault", "hqdefault"):
        ranked.append((0, -1, f"https://i.ytimg.com/vi/{video_id}/{quality}.jpg"))
    ranked.sort(reverse=True)
    seen: set[str] = set()
    result: list[str] = []
    for _, _, url in ranked:
        if url not in seen:
            seen.add(url)
            result.append(url)
    return result


def _download_image(url: str, max_bytes: int) -> bytes:
    return fetch_bytes(url, max_bytes=max_bytes,
        headers={"User-Agent": "Mozilla/5.0 Stemwise-PC/1.0", "Accept": "image/*"})


def center_square_padding_score(image: Any) -> float:
    """Score a wide image for an exact centered-square artwork boundary.

    YouTube commonly places square album artwork inside a 16:9 thumbnail and
    fills both sides with flat or blurred color. The real artwork boundaries
    then sit exactly where a centered square meets those side panels. Genuine
    wide artwork normally lacks that pair of full-height boundary jumps.
    """

    try:
        import numpy as np
        from PIL import Image
    except ImportError as error:
        raise RuntimeError("NumPy and Pillow are required for artwork analysis") from error

    rgb = image.convert("RGB")
    if rgb.width <= rgb.height:
        return 0.0
    target_height = min(180, round(1024 * rgb.height / rgb.width))
    if target_height < 4:
        return 0.0
    target_width = max(target_height + 1, round(rgb.width * target_height / rgb.height))
    inset = (target_width - target_height) // 2
    right = inset + target_height
    if inset < 2 or right >= target_width - 1:
        return 0.0

    pixels = np.asarray(
        rgb.resize((target_width, target_height), Image.Resampling.BILINEAR),
        dtype=np.float32,
    )
    column_difference = np.abs(pixels[:, 1:, :] - pixels[:, :-1, :]).mean(axis=(0, 2))
    baseline = float(np.median(column_difference) + 0.5)
    left_jump = float(column_difference[inset - 1] / baseline)
    right_jump = float(column_difference[right - 1] / baseline)

    gray = pixels.mean(axis=2)
    horizontal_gradient = np.abs(gray[:, 1:] - gray[:, :-1])
    vertical_gradient = np.abs(gray[1:, :] - gray[:-1, :])

    def region_energy(start: int, end: int) -> float:
        horizontal = (
            float(horizontal_gradient[:, start : end - 1].mean()) if end - start > 1 else 0.0
        )
        vertical = float(vertical_gradient[:, start:end].mean()) if end > start else 0.0
        return (horizontal + vertical) / 2.0 + 0.1

    side_energy = (
        region_energy(0, inset) + region_energy(right, target_width)
    ) / 2.0
    center_energy = region_energy(inset, right)
    side_ratio = center_energy / side_energy
    bilateral_jump = (max(left_jump, 0.0) * max(right_jump, 0.0)) ** 0.5
    boundary_score = bilateral_jump * max(side_ratio, 0.05) ** 0.5
    # Some generated side panels use the same color as the artwork's outermost
    # pixels, hiding the boundary jump. Very low-detail bilateral sides around
    # a much richer centered square are equivalent high-confidence evidence.
    return max(boundary_score, side_ratio)


def _inset_center_square_box(image: Any) -> tuple[tuple[int, int, int, int] | None, float]:
    """Detect a smaller centered square inside a 4:3 letterboxed thumbnail."""

    try:
        import numpy as np
        from PIL import Image
    except ImportError as error:
        raise RuntimeError("NumPy and Pillow are required for artwork analysis") from error

    aspect = image.width / image.height
    if not 1.2 <= aspect <= 1.5:
        return None, 0.0
    target_height = 180
    target_width = round(aspect * target_height)
    pixels = np.asarray(
        image.convert("RGB").resize(
            (target_width, target_height), Image.Resampling.BILINEAR
        ),
        dtype=np.float32,
    )
    column_difference = np.abs(pixels[:, 1:, :] - pixels[:, :-1, :]).mean(axis=(0, 2))
    row_difference = np.abs(pixels[1:, :, :] - pixels[:-1, :, :]).mean(axis=(1, 2))
    column_baseline = float(np.median(column_difference) + 0.5)
    row_baseline = float(np.median(row_difference) + 0.5)
    best_score = 0.0
    best_size = 0
    for size in range(round(target_height * 0.60), round(target_height * 0.96) + 1):
        left = (target_width - size) // 2
        top = (target_height - size) // 2
        right = left + size
        bottom = top + size
        if left < 2 or top < 2 or right >= target_width - 1 or bottom >= target_height - 1:
            continue
        jumps = (
            float(column_difference[left - 1] / column_baseline),
            float(column_difference[right - 1] / column_baseline),
            float(row_difference[top - 1] / row_baseline),
            float(row_difference[bottom - 1] / row_baseline),
        )
        score = max(jumps[0], 0.0) * max(jumps[1], 0.0)
        score *= max(jumps[2], 0.0) * max(jumps[3], 0.0)
        score **= 0.25
        if score > best_score:
            best_score = score
            best_size = size
    if best_score < CENTER_SQUARE_PADDING_THRESHOLD or not best_size:
        return None, best_score
    approximate_size = round(best_size * image.height / target_height)
    # Refine at native resolution in bounded strips, never a full float32 image.
    original_column_difference = np.zeros(image.width - 1, dtype=np.float64)
    original_row_difference = np.zeros(image.height - 1, dtype=np.float64)
    for first in range(0, image.height, 64):
        last = min(first + 64, image.height)
        strip = np.asarray(image.crop((0, first, image.width, min(last + 1, image.height))).convert('RGB'), dtype=np.float32)
        original_column_difference += np.abs(strip[:last-first, 1:] - strip[:last-first, :-1]).sum(axis=(0, 2), dtype=np.float64)
        row_count = min(last, image.height - 1) - first
        if row_count > 0:
            original_row_difference[first:first+row_count] = np.abs(strip[1:row_count+1] - strip[:row_count]).mean(axis=(1, 2))
    original_column_difference /= image.height * 3
    original_column_baseline = float(np.median(original_column_difference) + 0.5)
    original_row_baseline = float(np.median(original_row_difference) + 0.5)
    radius = max(6, round(image.height * 0.04))
    original_size = approximate_size
    refined_score = 0.0
    for size in range(max(1, approximate_size - radius), approximate_size + radius + 1):
        left = (image.width - size) // 2
        top = (image.height - size) // 2
        right = left + size
        bottom = top + size
        if left < 1 or top < 1 or right >= image.width or bottom >= image.height:
            continue
        jumps = (
            float(original_column_difference[left - 1] / original_column_baseline),
            float(original_column_difference[right - 1] / original_column_baseline),
            float(original_row_difference[top - 1] / original_row_baseline),
            float(original_row_difference[bottom - 1] / original_row_baseline),
        )
        score = max(jumps[0], 0.0) * max(jumps[1], 0.0)
        score *= max(jumps[2], 0.0) * max(jumps[3], 0.0)
        score **= 0.25
        if score > refined_score:
            refined_score = score
            original_size = size
    left = (image.width - original_size) // 2
    top = (image.height - original_size) // 2
    return (left, top, left + original_size, top + original_size), max(best_score, refined_score)


def _symmetric_dark_letterbox_box(
    image: Any,
) -> tuple[tuple[int, int, int, int] | None, float]:
    """Detect balanced horizontal near-black bars around wide artwork.

    Vertical black regions are deliberately excluded here. They are often an
    intentional part of a square album cover (for example a portrait against a
    black studio background), so edge darkness alone cannot safely identify
    them as padding. The separate centered-square detector handles synthetic
    YouTube side panels when there is stronger structural evidence.
    """

    try:
        import numpy as np
    except ImportError as error:
        raise RuntimeError("NumPy is required for artwork analysis") from error

    row_dark = np.empty(image.height, dtype=np.float64)
    for first in range(0, image.height, 64):
        last = min(first + 64, image.height)
        pixels = np.asarray(image.crop((0, first, image.width, last)).convert('RGB'), dtype=np.uint8)
        row_dark[first:last] = (pixels.max(axis=2) <= 24).mean(axis=1)

    def leading(values: Any) -> int:
        count = 0
        for value in values:
            if float(value) < 0.985:
                break
            count += 1
        return count

    top = leading(row_dark)
    bottom = leading(row_dark[::-1])
    minimum_y = max(2, round(image.height * 0.02))
    crop_top = crop_bottom = 0

    if (
        top >= minimum_y
        and bottom >= minimum_y
        and abs(top - bottom) <= max(3, round(image.height * 0.03))
    ):
        candidate_height = image.height - top - bottom
        if candidate_height > 0:
            aspect = image.width / candidate_height
            if min(abs(aspect - value) for value in (1.0, 4 / 3, 3 / 2, 16 / 9)) <= 0.04:
                crop_top, crop_bottom = top, bottom

    if not crop_top or not crop_bottom:
        return None, 0.0
    box = (
        0,
        crop_top,
        image.width,
        image.height - crop_bottom,
    )
    removed_fraction = 1.0 - ((box[2] - box[0]) * (box[3] - box[1])) / (
        image.width * image.height
    )
    return box, CENTER_SQUARE_PADDING_THRESHOLD + removed_fraction * 100.0


def remove_center_square_padding(image: Any) -> tuple[Any, float]:
    """Crop only high-confidence synthetic padding; preserve true wide art."""

    letterbox, letterbox_score = _symmetric_dark_letterbox_box(image)
    if letterbox is not None:
        image = image.crop(letterbox)
    inset_box, inset_score = _inset_center_square_box(image)
    if inset_box is not None:
        return image.crop(inset_box), max(letterbox_score, inset_score)
    score = center_square_padding_score(image)
    if score < CENTER_SQUARE_PADDING_THRESHOLD:
        return image, max(letterbox_score, score)
    size = min(image.width, image.height)
    left = (image.width - size) // 2
    top = (image.height - size) // 2
    return image.crop((left, top, left + size, top + size)), max(letterbox_score, score)


@bounded_provider_operation
def create_cover_png(
    info: dict[str, Any],
    video_id: str,
    destination: Path,
    *,
    max_pixels: int,
    max_bytes: int,
) -> dict[str, Any]:
    """Decode candidates, retain the best real source resolution, and save static PNG."""
    try:
        from PIL import Image, ImageOps
    except ImportError as error:
        raise RuntimeError("Pillow is not installed; run setup first") from error

    best: tuple[int, bytes] | None = None
    errors: list[str] = []
    for url in _thumbnail_candidates(info, video_id):
        try:
            if best is None:
                payload = _download_image(url, min(max_bytes, 50 * 1024 * 1024))
            else:
                remaining = current_budget().remaining()
                if remaining <= 2:
                    break
                with optional_provider_stage(min(10, remaining-2)):
                    payload = _download_image(url, min(max_bytes, 50 * 1024 * 1024))
            with Image.open(io.BytesIO(payload)) as opened:
                if (min(opened.size) <= 0 or max(opened.size) > 16384
                        or opened.width * opened.height > 16_777_216):
                    raise RuntimeError('artwork exceeds the decoded input pixel budget')
                if getattr(opened, "is_animated", False) or getattr(opened, "n_frames", 1) != 1:
                    raise RuntimeError("animated image is not allowed")
                opened.load()
                image = ImageOps.exif_transpose(opened)
                image = image.convert('RGBA' if 'A' in image.getbands() or 'transparency' in image.info else 'RGB')
            if image.width <= 0 or image.height <= 0:
                raise RuntimeError("image has invalid dimensions")
            score = image.width * image.height
            image, _padding_score = remove_center_square_padding(image)
            if image.width > max_pixels or image.height > max_pixels:
                image.thumbnail((max_pixels, max_pixels), Image.Resampling.LANCZOS, reducing_gap=3.0)
            with io.BytesIO() as encoded:
                image.save(encoded, format="PNG", optimize=True)
                cover = encoded.getvalue()
            if len(cover) > max_bytes:
                raise RuntimeError("Converted cover.png exceeds configured size limit")
            if best is None or score > best[0]:
                best = (score, cover)
        except ProviderStageDeadline:
            if best is None:
                raise
            break
        except (ProviderDeadline, ProviderCancelled, WorkerRetirementError):
            raise
        except (OSError, ValueError, RuntimeError, urllib.error.URLError) as error:
            errors.append(compact_error(error, 120))
    if best is None:
        raise RuntimeError(f"No YouTube artwork candidate produced an admissible PNG: {'; '.join(errors[-3:])}")

    current_budget().remaining()
    atomic_write_bytes(destination, best[1])
    # Source URL in the contract must be stable; CDN thumbnail URLs may expire.
    return {
        "file": "cover.png",
        "kind": "youtube-thumbnail",
        "source": {"name": "YouTube thumbnail", "url": canonical_url(video_id)},
    }


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "_download-attempt":
        raise SystemExit(_download_worker_main(sys.argv[2:]))
    raise SystemExit("youtube.py is an internal module")


class YouTubeSource:
    def __init__(self, config):
        self.config = config

    def song_info(self, video_id):
        return fetch_video_info(video_id)

    def metadata(self, info, video_id):
        return metadata_from_info(info, video_id)

    def finalize_metadata(self, metadata):
        return metadata

    def create_cover(self, info, video_id, destination):
        return create_cover_png(info, video_id, destination,
            max_pixels=int(self.config['artworkMaxPixels']), max_bytes=int(self.config['pngMaxBytes']))

    def acquire_analysis_audio(self, video_id, temporary):
        return download_analysis_audio(video_id, temporary,
            timeout_seconds=int(self.config['analysisTimeoutMinutes'])*60)

    def identity(self, video_id):
        return {'provider': 'youtube', 'videoId': video_id}

    def require_job(self, video_id, job):
        pass

    def require_selected(self, video_id, library):
        from stemwise_engine.common import require_youtube_audio_allowed, YouTubeAudioReplacedError
        from stemwise_engine.workflow_records import FutureSongError, SupersededRecordingError
        try:
            require_youtube_audio_allowed(video_id, root=library)
        except YouTubeAudioReplacedError as error:
            raise SupersededRecordingError(str(error)) from error
        except (OSError, ValueError) as error:
            raise FutureSongError('Recording selection cannot be verified') from error

    def checkpoint_matches(self, pipeline, checkpoint):
        return isinstance(checkpoint, dict) and pipeline._checkpoint_producer(checkpoint) is not None

    def collect_normal_candidates(self, info, destination, video_id):
        from stemwise_engine.source_acquisition import collect_normal_youtube_candidates
        return collect_normal_youtube_candidates(info, destination, video_id=video_id)

    def owns_artwork(self, source_id):
        return False

    def owned_artwork_source(self, url):
        return None

    def artwork_url(self, source_id):
        return canonical_url(source_id)

    def artwork_info(self, source_id):
        return fetch_video_info(source_id)

    def create_selected_artwork(self, info, source_id, destination):
        return self.create_cover(info, source_id, destination)

    def plan_delivery(self, workflow, video_id):
        from stemwise_engine.delivery_selection import catalogue
        from stemwise_engine.workflow_context import SourceDeliveryPlan
        return SourceDeliveryPlan(f'youtube:{video_id}', catalogue(workflow, public_ids=[video_id]), False)

    def result_binding(self, job):
        return None

    def complete_delivery(self, workflow, job):
        return []

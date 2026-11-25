#!/usr/bin/env python3
"""Acquire plain original lyrics linked to exact YouTube Music video IDs.

This is deliberately separate from the audio-analysis pipeline. Exact-item
acquisition never title-matches. A separate conservative metadata-only helper
may suggest a cross-surface counterpart for agent review; it never accepts the
relationship, prints lyric text, or places acquisition reports in a phone
package.
"""

from __future__ import annotations

import csv
from stemwise_engine.provider_operations import (LyricsProvider, retry, bounded_provider_operation, current_budget,
                                 ProviderCancelled, ProviderDeadline, raise_if_operation_stopped)
import hashlib
import io
import math
import os
import re
import tempfile
import unicodedata
import uuid
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Any

from stemwise_engine.common import (
    WorkerRetirementError,
    VIDEO_ID_RE,
    atomic_write_bytes,
    atomic_write_json,
    canonical_lyrics_language,
    diagnostic_code,
    exclusive_process_lock,
    ProcessLockBusyError,
    _fsync_directory,
    read_json,
    read_bounded_json,
    read_bounded_regular_bytes,
    utc_now,
    validate_video_id,
)
from stemwise_engine.metadata_research import title_evidence


class LyricsAcquisitionError(ValueError):
    """A safe acquisition diagnostic that never contains lyric text."""


_HTML_RE = re.compile(r"<\s*/?\s*(?:html|body|script|div|p)(?:\s|>)", re.IGNORECASE)
_LANGUAGE_RE = re.compile(r"^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")
_SCRIPT_RE = re.compile(r"^[A-Z][a-z]{3}$")
_ARTIST_SPLIT_RE = re.compile(
    r"\s*(?:,|;|\s+&\s+|\s+(?:feat(?:uring)?\.?|ft\.?|with)\s+)\s*",
    re.IGNORECASE,
)
_DASH_RE = re.compile(r"\s+[\-\u2013\u2014]\s+")

_SCRIPT_MARKERS: tuple[tuple[str, str], ...] = (
    ("ARABIC", "Arab"),
    ("LATIN", "Latn"),
    ("CYRILLIC", "Cyrl"),
    ("GREEK", "Grek"),
    ("HEBREW", "Hebr"),
    ("DEVANAGARI", "Deva"),
    ("BENGALI", "Beng"),
    ("GUJARATI", "Gujr"),
    ("GURMUKHI", "Guru"),
    ("TAMIL", "Taml"),
    ("TELUGU", "Telu"),
    ("KANNADA", "Knda"),
    ("MALAYALAM", "Mlym"),
    ("THAI", "Thai"),
    ("GEORGIAN", "Geor"),
    ("ARMENIAN", "Armn"),
    ("HANGUL", "Hang"),
    ("HIRAGANA", "Jpan"),
    ("KATAKANA", "Jpan"),
    ("CJK", "Hani"),
    ("IDEOGRAPH", "Hani"),
)


def _fold_identity(value: str) -> str:
    value = unicodedata.normalize("NFKD", value)
    value = "".join(character for character in value if not unicodedata.combining(character))
    value = value.casefold().replace("&", " and ")
    return re.sub(r"\s+", " ", re.sub(r"[^\w]+", " ", value)).strip()


def _identity_artists(values: list[str]) -> set[str]:
    result: set[str] = set()
    for value in values:
        for part in _ARTIST_SPLIT_RE.split(value):
            folded = _fold_identity(re.sub(r"\s+-\s+topic\s*$", "", part, flags=re.IGNORECASE))
            if folded and folded not in {"unknown artist", "various artists"}:
                result.add(folded)
    return result


def _candidate_artists(value: Mapping[str, Any]) -> list[str]:
    artists = value.get("artists")
    if not isinstance(artists, list):
        return []
    result: list[str] = []
    for artist in artists:
        name = artist.get("name") if isinstance(artist, Mapping) else None
        if isinstance(name, str) and name.strip():
            result.append(name.strip())
    return result


def _duration_seconds(value: Mapping[str, Any]) -> float | None:
    direct = value.get("duration_seconds")
    if isinstance(direct, (int, float)) and not isinstance(direct, bool) and math.isfinite(direct) and direct > 0:
        return float(direct)
    duration = value.get("duration")
    if not isinstance(duration, str) or not re.fullmatch(r"\d{1,2}(?::\d{2}){1,2}", duration):
        return None
    parts = [int(part) for part in duration.split(":")]
    seconds = 0
    for part in parts:
        seconds = seconds * 60 + part
    return float(seconds) if seconds > 0 else None


def _candidate_title_base(title: str, candidate_artists: set[str]) -> str:
    """Normalize a provider title while tolerating an ``Artist - Title`` prefix."""

    pieces = _DASH_RE.split(title, maxsplit=1)
    if len(pieces) == 2 and _fold_identity(pieces[0]) in candidate_artists:
        title = pieces[1]
    return title_evidence(title).base


@bounded_provider_operation
def discover_counterpart_candidates(
    item: dict[str, Any],
    *,
    client: Any | None = None,
    limit_per_surface: int = 10,
) -> dict[str, Any]:
    """Find conservative cross-surface upload candidates without accepting identity.

    Results contain metadata only. A candidate must match the normalized base title,
    artist identity, and a tight duration window. The caller may stage matching
    uploads as pending evidence, but this function never changes the
    package ID and never acquires or reports lyric text.
    """

    video_id = validate_video_id(str(item.get("videoId") or ""))
    title = item.get("title")
    uploader = item.get("uploader") or item.get("channel")
    duration = item.get("durationSeconds")
    if (
        not isinstance(title, str)
        or not title.strip()
        or not isinstance(uploader, str)
        or not uploader.strip()
        or not isinstance(duration, (int, float))
        or isinstance(duration, bool)
        or not math.isfinite(duration)
        or duration <= 0
    ):
        return {
            "decision": "skipped-insufficient-metadata",
            "searchStatus": "not-applicable",
            "surfaceOutcomes": {surface: {'status': 'not-attempted'} for surface in ('songs', 'videos')},
            "candidateCount": 0,
            "displayedCandidateCount": 0,
            "candidates": [],
        }
    target_artists = _identity_artists([uploader])
    if not target_artists:
        return {
            "decision": "skipped-insufficient-metadata",
            "searchStatus": "not-applicable",
            "surfaceOutcomes": {surface: {'status': 'not-attempted'} for surface in ('songs', 'videos')},
            "candidateCount": 0,
            "displayedCandidateCount": 0,
            "candidates": [],
        }
    target_title = _candidate_title_base(title, target_artists)
    try:
        provider = client if client is not None else _load_client()
    except WorkerRetirementError:
        raise
    except Exception as error:
        raise_if_operation_stopped(error)
        return {
            "decision": "api-error",
            "searchStatus": "incomplete",
            "surfaceOutcomes": {surface: {'status': 'not-attempted'} for surface in ('songs', 'videos')},
            "candidateCount": 0,
            "displayedCandidateCount": 0,
            "candidates": [],
            "surfaceErrors": [f"client:{diagnostic_code(error)}"],
        }
    query = f"{title.strip()} {uploader.strip()}"
    raw_results: list[tuple[str, Mapping[str, Any]]] = []
    errors: list[str] = []
    outcomes: dict[str, dict[str, Any]] = {}
    def identity_row(value):
        return (isinstance(value, Mapping) and isinstance(value.get('videoId'), str)
            and VIDEO_ID_RE.fullmatch(value['videoId']) and isinstance(value.get('title'), str)
            and bool(value['title'].strip()) and isinstance(value.get('artists'), list)
            and bool(value['artists']) and all(isinstance(artist, Mapping)
                and isinstance(artist.get('name'), str) and bool(artist['name'].strip()) for artist in value['artists']))
    def valid_identity_row(value):
        return identity_row(value) and _duration_seconds(value) is not None
    for surface in ("songs", "videos"):
        try:
            values = _provider_call(
                lambda surface=surface: provider.search(
                    query,
                    filter=surface,
                    limit=limit_per_surface,
                )
            )
            relevant, excluded = [], 0
            if isinstance(values, list):
                for value in values:
                    if identity_row(value):
                        artists = _identity_artists(_candidate_artists(value))
                        if artists and (not target_artists & artists
                                or _candidate_title_base(value['title'], artists) != target_title):
                            excluded += 1
                            continue
                    relevant.append(value)
            if not isinstance(values, list) or any(not valid_identity_row(value) for value in relevant):
                outcomes[surface] = {'status': 'malformed'}
                errors.append(f'{surface}:malformed-response')
            else:
                outcomes[surface] = {'status': 'successful-results' if values else 'successful-empty'}
            if excluded:
                outcomes[surface]['excludedUnrelatedRows'] = excluded
            if isinstance(values, list):
                raw_results.extend((surface, value) for value in relevant if valid_identity_row(value))
        except WorkerRetirementError:
            raise
        except Exception as error:
            raise_if_operation_stopped(error)
            errors.append(f"{surface}:{diagnostic_code(error)}")
            outcomes[surface] = {'status': 'failed'}

    candidates_by_id: dict[str, dict[str, Any]] = {}
    tolerance_seconds = min(2.5, max(1.5, float(duration) * 0.005))
    for surface, value in raw_results:
        candidate_id = value.get("videoId")
        candidate_title = value.get("title")
        candidate_duration = _duration_seconds(value)
        artist_names = _candidate_artists(value)
        artist_identities = _identity_artists(artist_names)
        if (
            not isinstance(candidate_id, str)
            or not VIDEO_ID_RE.fullmatch(candidate_id)
            or candidate_id == video_id
            or not isinstance(candidate_title, str)
            or candidate_duration is None
            or not (target_artists & artist_identities)
            or _candidate_title_base(candidate_title, artist_identities) != target_title
            or abs(candidate_duration - float(duration)) > tolerance_seconds
        ):
            continue
        record = {
            "videoId": candidate_id,
            "exactUrl": f"https://music.youtube.com/watch?v={candidate_id}",
            "resultType": str(value.get("resultType") or surface.rstrip("s")),
            "title": candidate_title,
            "artists": artist_names,
            "durationSeconds": candidate_duration,
            "durationDeltaMs": int(round(abs(candidate_duration - float(duration)) * 1000)),
            "associationStatus": "pending-agent-review",
            "matchBasis": ["base-title", "artist", "tight-duration"],
        }
        prior = candidates_by_id.get(candidate_id)
        if prior is None or record["durationDeltaMs"] < prior["durationDeltaMs"]:
            candidates_by_id[candidate_id] = record
    candidates = sorted(
        candidates_by_id.values(),
        key=lambda value: (value["durationDeltaMs"], value["videoId"]),
    )[:5]
    result: dict[str, Any] = {
        "decision": "candidates-found" if candidates else ("search-incomplete" if errors else "not-found"),
        "searchStatus": "incomplete" if errors else "complete",
        "surfaceOutcomes": outcomes,
        "candidateCount": len(candidates_by_id),
        "displayedCandidateCount": len(candidates),
        "candidates": candidates,
        "surfaceErrors": errors,
    }
    if len(candidates) == 1:
        result["recommendedVideoId"] = candidates[0]["videoId"]
    return result


def _inventory(config: dict[str, Any]) -> list[dict[str, Any]]:
    path = Path(config["outputDir"]) / "work" / "inventory.json"
    try:
        snapshot = read_json(path)
    except (OSError, UnicodeError, ValueError) as error:
        raise LyricsAcquisitionError(
            f"inventory.json cannot be read ({error.__class__.__name__})"
        ) from error
    if not isinstance(snapshot, dict) or not isinstance(snapshot.get("items"), list):
        raise LyricsAcquisitionError("inventory.json is missing its items array")
    items: list[dict[str, Any]] = []
    seen: set[str] = set()
    for expected_position, value in enumerate(snapshot["items"], start=1):
        if not isinstance(value, dict):
            raise LyricsAcquisitionError("inventory.json contains a non-object item")
        video_id = value.get("videoId")
        if not isinstance(video_id, str) or not VIDEO_ID_RE.fullmatch(video_id):
            raise LyricsAcquisitionError("inventory.json contains an invalid videoId")
        if video_id in seen:
            raise LyricsAcquisitionError("inventory.json repeats a videoId")
        seen.add(video_id)
        item = dict(value)
        position = item.get("position")
        if type(position) is not int or position < 1:
            position = expected_position
        item["position"] = position
        items.append(item)
    return items


def _selection(
    items: list[dict[str, Any]],
    *,
    selected: list[str] | None,
    limit: int | None,
    from_position: int | None,
    to_position: int | None,
) -> list[dict[str, Any]]:
    if limit is not None and (type(limit) is not int or limit <= 0):
        raise LyricsAcquisitionError("limit must be a positive integer")
    for name, value in (("from-position", from_position), ("to-position", to_position)):
        if value is not None and (type(value) is not int or value <= 0):
            raise LyricsAcquisitionError(f"{name} must be a positive integer")
    if from_position and to_position and from_position > to_position:
        raise LyricsAcquisitionError("from-position cannot be greater than to-position")
    selected_ids = {validate_video_id(value) for value in selected} if selected else None
    inventory_ids = {item["videoId"] for item in items}
    if selected_ids is not None:
        missing = selected_ids - inventory_ids
        if missing:
            raise LyricsAcquisitionError(
                "video ID is not in the frozen inventory: " + ", ".join(sorted(missing))
            )
    result = [
        item
        for item in items
        if (selected_ids is None or item["videoId"] in selected_ids)
        and (from_position is None or item["position"] >= from_position)
        and (to_position is None or item["position"] <= to_position)
    ]
    return result[:limit] if limit is not None else result


def _normalize_text(value: str) -> str:
    if "\x00" in value:
        raise LyricsAcquisitionError("provider text contains a NUL character")
    from stemwise_engine.lyrics_normalize import trim_blank_outer_lines
    lines = trim_blank_outer_lines(value).split("\n")
    normalized = "\n".join(line.rstrip(" \t") for line in lines)
    if not normalized.strip():
        raise LyricsAcquisitionError("provider returned blank lyric text")
    if _HTML_RE.search(normalized):
        raise LyricsAcquisitionError("provider text contains unsupported HTML-like markup")
    return normalized


def _script(value: str) -> tuple[str, dict[str, int], float]:
    counts: dict[str, int] = {}
    total = 0
    for character in value:
        if not character.isalpha():
            continue
        total += 1
        name = unicodedata.name(character, "")
        script = next((code for marker, code in _SCRIPT_MARKERS if marker in name), "Zyyy")
        counts[script] = counts.get(script, 0) + 1
    if not counts or total == 0:
        return "Zyyy", counts, 0.0
    leader, leader_count = max(counts.items(), key=lambda item: item[1])
    confidence = leader_count / total
    return (leader if confidence >= 0.8 else "Zyyy"), counts, confidence


def _load_language_detector() -> Any:
    try:
        from lingua import LanguageDetectorBuilder
    except ImportError as error:  # pragma: no cover - deployment guard
        raise LyricsAcquisitionError(
            "lingua-language-detector is required; run Stemwise setup after the current analysis run"
        ) from error
    return LanguageDetectorBuilder.from_all_languages().build()


def infer_language_script(value: str, detector: Any | None = None) -> dict[str, Any]:
    """Return a conservative whole-song language estimate plus Unicode script."""

    detected_script, script_counts, script_confidence = _script(value)
    engine = detector if detector is not None else _load_language_detector()
    try:
        confidence_values = list(engine.compute_language_confidence_values(value))
    except Exception as error:
        raise LyricsAcquisitionError(
            f"language detection failed ({error.__class__.__name__})"
        ) from error
    language = "und"
    confidence = 0.0
    runner_up = 0.0
    if confidence_values:
        first = confidence_values[0]
        confidence = float(first.value)
        runner_up = float(confidence_values[1].value) if len(confidence_values) > 1 else 0.0
        iso = getattr(first.language, "iso_code_639_1", None)
        candidate = getattr(iso, "name", "").lower()
        if _LANGUAGE_RE.fullmatch(candidate) and confidence >= 0.65 and confidence - runner_up >= 0.15:
            language = canonical_lyrics_language(candidate)
    return {
        "language": language,
        "languageStatus": "estimated" if language != "und" else "review",
        "languageConfidence": round(confidence, 6),
        "languageMargin": round(confidence - runner_up, 6),
        "script": detected_script,
        "scriptStatus": "estimated" if detected_script != "Zyyy" else "review",
        "scriptConfidence": round(script_confidence, 6),
        "scriptLetterCounts": dict(sorted(script_counts.items())),
    }


def _load_client() -> Any:
    return LyricsProvider()


def _provider_call(call: Callable[[], Any], attempts: int = 3) -> Any:
    return retry(call, attempts)


def _base_record(item: dict[str, Any]) -> dict[str, Any]:
    video_id = item["videoId"]
    duration = item.get("durationSeconds")
    return {
        "position": item["position"],
        "videoId": video_id,
        "title": item.get("title") if isinstance(item.get("title"), str) else "",
        "artistOrUploader": next(
            (
                item.get(field)
                for field in ("uploader", "channel")
                if isinstance(item.get(field), str) and item[field].strip()
            ),
            "",
        ),
        "durationSeconds": duration if type(duration) in (int, float) else None,
        "exactUrl": f"https://music.youtube.com/watch?v={video_id}",
    }


def _existing_original(draft: Path) -> Path | None:
    for name in ("original.lrc", "original.txt"):
        candidate = draft / name
        if candidate.is_file():
            return candidate
    return None


def _manifest(draft: Path, video_id: str, inferred: dict[str, Any]) -> dict[str, Any]:
    path = draft / "lyrics.meta.json"
    if path.is_file():
        try:
            value = read_json(path)
        except (OSError, UnicodeError, ValueError) as error:
            raise LyricsAcquisitionError(
                f"existing lyrics.meta.json is invalid ({error.__class__.__name__})"
            ) from error
        from stemwise_engine.lyrics_intake import validate_draft_manifest, LyricsInputError
        try:
            validate_draft_manifest(value, video_id)
        except LyricsInputError as error:
            raise LyricsAcquisitionError('existing lyrics.meta.json is invalid') from error
        manifest = dict(value)
        manifest["sources"] = dict(value["sources"])
        manifest["originalLanguage"] = canonical_lyrics_language(
            manifest["originalLanguage"]
        )
    else:
        manifest = {
            "videoId": video_id,
            "originalLanguage": inferred["language"],
            "originalScript": inferred["script"],
            "sources": {},
        }
    manifest["sources"]["original"] = {
        "name": "YouTube Music lyrics linked to exact video ID",
        "url": f"https://music.youtube.com/watch?v={video_id}",
    }
    return manifest


def _write_worklists(
    *,
    config: dict[str, Any],
    items: list[dict[str, Any]],
    records: list[dict[str, Any]],
) -> tuple[Path, Path, list[dict[str, Any]]]:
    owner_input = Path(config["projectDir"]) / "owner-input"
    by_id = {record["videoId"]: record for record in records}
    missing: list[dict[str, Any]] = []
    for item in items:
        video_id = item["videoId"]
        draft = owner_input / "drafts" / video_id
        sidecar = owner_input / f"{video_id}.lyrics.json"
        if (_existing_original(draft) is not None or sidecar.is_file()) and not (draft/'.acquired-pair.json').exists():
            continue
        source = by_id.get(video_id)
        record = _base_record(item)
        record["status"] = source.get("decision", "not-processed") if source else "not-processed"
        record["reason"] = source.get("reason", "This song was not included in the acquisition selection.") if source else "This song was not included in the acquisition selection."
        missing.append(record)

    json_path = owner_input / "lyrics-missing.json"
    csv_path = owner_input / "lyrics-missing.csv"
    atomic_write_json(
        json_path,
        {
            "generatedAt": utc_now(),
            "count": len(missing),
            "containsLyrics": False,
            "songs": missing,
        },
    )
    fields = (
        "position",
        "videoId",
        "title",
        "artistOrUploader",
        "durationSeconds",
        "exactUrl",
        "status",
        "reason",
    )
    output = io.StringIO(newline="")
    writer = csv.DictWriter(output, fieldnames=fields, extrasaction="ignore", lineterminator="\n")
    writer.writeheader()
    writer.writerows(missing)
    atomic_write_bytes(csv_path, output.getvalue().encode("utf-8-sig"))
    return json_path, csv_path, missing


def _previous_records(path: Path) -> dict[str, dict[str, Any]]:
    try:
        report = read_json(path)
    except FileNotFoundError:
        return {}
    records = report.get("records") if isinstance(report, dict) else None
    if not isinstance(records, list):
        raise ValueError(f'Cannot update acquisition report without its retained records: {path}')
    result: dict[str, dict[str, Any]] = {}
    for record in records:
        video_id = record.get("videoId") if isinstance(record, dict) else None
        if not isinstance(video_id, str) or not VIDEO_ID_RE.fullmatch(video_id) or video_id in result:
            raise ValueError(f'Invalid or duplicated recording in existing acquisition report: {path}')
        result[video_id] = record
    return result


@bounded_provider_operation
def acquire_exact_item_draft(
    item: dict[str, Any],
    draft: Path,
    *,
    client: Any | None = None,
    detector: Any | None = None,
    inferer: Callable[[str, Any | None], dict[str, Any]] = infer_language_script,
    diagnostics_dir: Path | None = None,
) -> dict[str, Any]:
    validate_video_id(str(item.get('videoId') or ''))
    draft.parent.mkdir(parents=True, exist_ok=True)
    try:
        with exclusive_process_lock(draft.parent / f'.{draft.name}.acquisition.lock', wait_seconds=current_budget().remaining()):
            current_budget().remaining()
            return _acquire_exact_item_draft(item, draft, client=client, detector=detector, inferer=inferer,
                diagnostics_dir=diagnostics_dir or draft.parent)
    except ProcessLockBusyError as error:
        record = _base_record(item)
        record.update(decision='api-error', reason=diagnostic_code(error))
        return record
    except LyricsAcquisitionError as error:
        record = _base_record(item)
        record.update(decision='rejected', reason=diagnostic_code(error))
        return record


def _acquire_exact_item_draft(
    item: dict[str, Any], draft: Path, *, diagnostics_dir: Path, client: Any | None = None,
    detector: Any | None = None,
    inferer: Callable[[str, Any | None], dict[str, Any]] = infer_language_script,
) -> dict[str, Any]:
    """Acquire one exact-ID draft without requiring a playlist inventory.

    This is the reusable one-song adapter used by the isolated future-song
    workflow. It intentionally performs only the same exact-ID YouTube Music
    lookup as the playlist acquisition command and never searches by title.
    The returned record is metadata-only and never contains lyric text.
    """

    video_id = validate_video_id(str(item.get("videoId") or ""))
    record = _base_record(item)
    _recover_acquired_pair(draft)
    if _existing_original(draft) is not None:
        record.update(
            decision="skipped-existing",
            reason="An existing original draft was preserved.",
        )
        return record
    metadata = draft/'lyrics.meta.json'
    expected_metadata = metadata.read_bytes() if metadata.is_file() else None
    try:
        provider = client if client is not None else _load_client()
        language_detector = detector if detector is not None else _load_language_detector()
        watch = _provider_call(lambda: provider.get_watch_playlist(videoId=video_id, limit=1))
        tracks = watch.get("tracks") if isinstance(watch, Mapping) else None
        first = tracks[0] if isinstance(tracks, list) and tracks else None
        returned_id = first.get("videoId") if isinstance(first, Mapping) else None
        record["returnedVideoId"] = returned_id
        record["exactVideoIdConfirmed"] = returned_id == video_id
        if returned_id != video_id:
            record.update(
                decision="rejected",
                reason="YouTube Music did not return the requested exact video ID.",
            )
            return record
        lyrics_id = watch.get("lyrics") if isinstance(watch, Mapping) else None
        if not isinstance(lyrics_id, str) or not lyrics_id:
            record.update(
                decision="not-found",
                reason="The exact YouTube Music item exposes no lyrics record.",
            )
            return record
        response = _provider_call(lambda: provider.get_lyrics(lyrics_id, timestamps=False))
        text = response.get("lyrics") if isinstance(response, Mapping) else None
        if not isinstance(text, str) or not text.strip():
            record.update(
                decision="not-found",
                reason="The exact YouTube Music lyrics record contains no plain text.",
            )
            return record
        normalized = _normalize_text(text)
        inference = inferer(normalized, language_detector)
        language = inference.get("language")
        script = inference.get("script")
        if not isinstance(language, str) or not _LANGUAGE_RE.fullmatch(language):
            raise LyricsAcquisitionError("language inference returned an invalid language tag")
        if not isinstance(script, str) or not _SCRIPT_RE.fullmatch(script):
            raise LyricsAcquisitionError("script inference returned an invalid script code")
        if _existing_original(draft) is not None:
            record.update(decision='skipped-existing', reason='An owner draft created during acquisition was preserved.')
            return record
        if (metadata.read_bytes() if metadata.is_file() else None) != expected_metadata:
            raise LyricsAcquisitionError("owner-draft-conflict")
        manifest = _manifest(draft, video_id, inference)
        payload = (normalized + "\n").encode("utf-8")
        current_budget().remaining()
        if not _publish_acquired_draft(draft, manifest, payload, expected_metadata=expected_metadata):
            record.update(decision='skipped-existing', reason='An owner draft created during acquisition was preserved.')
            return record
        record.update(
            decision="acquired",
            reason="Plain lyrics were linked to the exact video ID and staged locally.",
            lyricsBrowseId=lyrics_id,
            sourceName="YouTube Music lyrics linked to exact video ID",
            hasTimestamps=False,
            language=manifest["originalLanguage"],
            script=manifest["originalScript"],
            detectedLanguage=inference,
            characterCount=len(normalized),
            lineCount=sum(bool(line.strip()) for line in normalized.split("\n")),
            sha256=hashlib.sha256(payload).hexdigest(),
            draft=str(draft),
        )
        return record

    except (KeyboardInterrupt, ProviderDeadline, ProviderCancelled, WorkerRetirementError):
        raise
    except LyricsAcquisitionError as error:
        record.update(
            decision="rejected",
            reason=f"lyrics-acquisition-rejected:{diagnostic_code(error)}",
        )
        return record
    except Exception as error:
        from stemwise_engine.private_diagnostics import retain_error
        record.update(decision="api-error", reason=diagnostic_code(error))
        try:
            record['privateDiagnostics'] = [retain_error(
                diagnostics_dir / 'lyrics-acquisition.failure.private.txt', error,
                context={'stage': 'lyrics-acquisition', 'videoId': video_id})]
        except (OSError, ValueError) as diagnostic_error:
            record['diagnosticError'] = diagnostic_code(diagnostic_error)
        return record


def _recover_acquired_pair(draft: Path) -> bool:
    journal = draft/'.acquired-pair.json'
    if not journal.exists():
        return False
    metadata, text = draft/'lyrics.meta.json', draft/'original.txt'
    try:
        record = read_bounded_json(journal, max_bytes=4096, root=draft)
        fields = {'formatVersion', 'textSha256', 'metadataSha256', 'previousMetadataSha256',
                  'textDevice', 'textInode', 'candidateFile'}
        if (not isinstance(record, dict) or set(record) not in (fields, fields | {'previousMetadataFile'})
                or record['formatVersion'] != 1
                or not isinstance(record['candidateFile'], str)
                or re.fullmatch(r'\.lyrics\.meta\.json\.[0-9a-f]{32}\.pending', record['candidateFile']) is None
                or any(not isinstance(record[key], str) or re.fullmatch('[0-9a-f]{64}', record[key]) is None
                       for key in ('textSha256', 'metadataSha256', 'previousMetadataSha256'))
                or any(type(record[key]) is not int or record[key] < 0 for key in ('textDevice', 'textInode'))):
            raise ValueError('invalid acquired pair journal')
        candidate = draft/record['candidateFile']
        previous = None
        if 'previousMetadataFile' in record:
            name = record['previousMetadataFile']
            if (not isinstance(name, str) or name == candidate.name
                    or re.fullmatch(r'\.lyrics\.meta\.json\.[0-9a-f]{32}\.pending', name) is None):
                raise ValueError('invalid acquired pair previous metadata')
            previous = draft/name
        from stemwise_engine.worker_publication import recover_owner_publication
        recover_owner_publication(metadata, max_bytes=16*1024*1024)
        def digest(path):
            return (hashlib.sha256(read_bounded_regular_bytes(path, max_bytes=16*1024*1024, root=draft)).hexdigest()
                    if path.exists() else None)
        metadata_sha, text_sha, candidate_sha = digest(metadata), digest(text), digest(candidate)
        if candidate_sha not in {None, record['metadataSha256']}:
            raise ValueError('acquisition candidate changed')
        previous_sha = digest(previous) if previous is not None else None
        if previous_sha not in {None, record['previousMetadataSha256']}:
            raise ValueError('acquisition previous metadata changed')
        acquired = False
        if text_sha is None and metadata_sha == record['previousMetadataSha256']:
            pass  # The link did not commit, or ordinary failure rolled back this exact text.
        elif text_sha == record['textSha256'] and (
                text.stat().st_dev, text.stat().st_ino) == (record['textDevice'], record['textInode']):
            from stemwise_engine.worker_publication import publish_owner_file
            def publish_bound(replacement, expected_sha):
                expected = read_bounded_regular_bytes(metadata, max_bytes=16*1024*1024, root=draft)
                if hashlib.sha256(expected).hexdigest() != expected_sha:
                    raise ValueError('acquisition metadata changed before publication')
                publish_owner_file(metadata, replacement, expected, max_bytes=16*1024*1024)
                return digest(metadata)
            if (not (draft/'original.lrc').exists() and metadata_sha == record['previousMetadataSha256']
                    and candidate_sha == record['metadataSha256']):
                metadata_sha = publish_bound(candidate, record['previousMetadataSha256'])
            if (draft/'original.lrc').exists():
                if metadata_sha == record['metadataSha256'] and metadata_sha != record['previousMetadataSha256']:
                    if previous is None or previous_sha != record['previousMetadataSha256']:
                        raise ValueError('acquisition previous metadata unavailable')
                    metadata_sha = publish_bound(previous, record['metadataSha256'])
                if metadata_sha != record['previousMetadataSha256']:
                    raise ValueError('acquisition metadata changed')
                # An alternate owner Original wins; remove only our still-bound text.
                if (digest(text) != record['textSha256'] or (text.stat().st_dev, text.stat().st_ino)
                        != (record['textDevice'], record['textInode'])):
                    raise ValueError('acquisition text changed during rollback')
                text.unlink()
            else:
                if metadata_sha != record['metadataSha256']:
                    raise ValueError('acquisition metadata changed')
                acquired = True
        else:
            raise ValueError('acquisition text or owner metadata changed')
        candidate.unlink(missing_ok=True)
        if previous is not None:
            previous.unlink(missing_ok=True)
        journal.unlink()
        _fsync_directory(draft)
        return acquired
    except (OSError, ValueError) as error:
        raise LyricsAcquisitionError('acquired-pair-recovery-conflict') from error


def _publish_acquired_draft(draft: Path, manifest: dict[str, Any], payload: bytes, *,
                            expected_metadata: bytes | None) -> bool:
    """Publish text and provenance together without replacing an owner's directory."""
    draft.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.acquire-', dir=draft.parent) as temporary:
        staged = Path(temporary) / 'draft'
        staged.mkdir()
        atomic_write_json(staged / 'lyrics.meta.json', manifest)
        atomic_write_bytes(staged / 'original.txt', payload)
        if draft.is_symlink():
            raise LyricsAcquisitionError('owner draft directory is unsafe')
        if draft.is_dir() and (draft/'lyrics.meta.json').is_file():
            from stemwise_engine.lyrics_intake import validate_draft_manifest
            from stemwise_engine.worker_publication import publish_owner_file
            metadata = draft/'lyrics.meta.json'
            expected = expected_metadata
            if expected is None or metadata.read_bytes() != expected:
                raise LyricsAcquisitionError('owner-draft-conflict')
            validate_draft_manifest(read_json(metadata), manifest['videoId'])
            # A metadata scaffold is not an existing lyric body. Create text
            # without replacement, then CAS its exact metadata under the same lease.
            if _existing_original(draft) is not None:
                return False
            if metadata.read_bytes() != expected:
                raise LyricsAcquisitionError('owner-draft-conflict')
            target = draft/'original.txt'
            replacement = draft/f'.{metadata.name}.{uuid.uuid4().hex}.pending'
            previous = draft/f'.{metadata.name}.{uuid.uuid4().hex}.pending'
            journal = draft/'.acquired-pair.json'
            atomic_write_json(replacement, manifest)
            atomic_write_bytes(previous, expected)
            staged_identity = (staged/'original.txt').stat()
            atomic_write_json(journal, {'formatVersion': 1, 'candidateFile': replacement.name,
                'previousMetadataFile': previous.name,
                'textSha256': hashlib.sha256(payload).hexdigest(),
                'metadataSha256': hashlib.sha256(replacement.read_bytes()).hexdigest(),
                'previousMetadataSha256': hashlib.sha256(expected).hexdigest(),
                'textDevice': staged_identity.st_dev, 'textInode': staged_identity.st_ino})
            try:
                os.link(staged/'original.txt', target)
            except FileExistsError:
                replacement.unlink()
                previous.unlink()
                journal.unlink()
                return False
            identity = target.stat()
            _fsync_directory(draft)
            try:
                publish_owner_file(metadata, replacement, expected, max_bytes=16*1024*1024)
            except BaseException:
                from stemwise_engine.worker_publication import recover_owner_publication
                recover_owner_publication(metadata, max_bytes=16*1024*1024)
                # Roll back our exact text only while the old metadata remains authoritative.
                try:
                    current = target.stat()
                except FileNotFoundError:
                    current = None
                if metadata.read_bytes() == expected and current is not None and (
                        current.st_dev, current.st_ino, current.st_size, current.st_mtime_ns, current.st_ctime_ns) == (
                        identity.st_dev, identity.st_ino, identity.st_size, identity.st_mtime_ns, identity.st_ctime_ns):
                    target.unlink()
                _recover_acquired_pair(draft)
                raise
            _fsync_directory(draft)
            return _recover_acquired_pair(draft)
        if os.name == 'nt' and draft.is_dir():
            try:
                draft.rmdir()
            except OSError:
                if any(draft.iterdir()):
                    return False
                raise
        try:
            os.rename(staged, draft)
        except OSError:
            if draft.is_dir() and any(draft.iterdir()):
                return False
            raise
        _fsync_directory(draft.parent)
    return True


def acquire(
    config: dict[str, Any],
    *,
    selected: list[str] | None = None,
    limit: int | None = None,
    from_position: int | None = None,
    to_position: int | None = None,
    client: Any | None = None,
    detector: Any | None = None,
    inferer: Callable[[str, Any | None], dict[str, Any]] = infer_language_script,
) -> dict[str, Any]:
    """Acquire exact-ID plain lyrics and return metadata-only results."""

    items = _inventory(config)
    targets = _selection(
        items,
        selected=selected,
        limit=limit,
        from_position=from_position,
        to_position=to_position,
    )
    provider = client if client is not None else _load_client()
    language_detector = detector if detector is not None else _load_language_detector()
    owner_input = Path(config["projectDir"]) / "owner-input"
    report_path = owner_input / "lyrics-acquisition-report.json"
    records: list[dict[str, Any]] = []
    acquired: list[str] = []

    for item in targets:
        video_id = item["videoId"]
        record = _base_record(item)
        draft = owner_input / "drafts" / video_id
        sidecar = owner_input / f"{video_id}.lyrics.json"
        recovered = False
        if (draft/'.acquired-pair.json').exists():
            try:
                with exclusive_process_lock(draft.parent/f'.{draft.name}.acquisition.lock', wait_seconds=60):
                    recovered = _recover_acquired_pair(draft)
            except (LyricsAcquisitionError, ProcessLockBusyError) as error:
                record.update(decision='rejected', reason=diagnostic_code(error))
                records.append(record)
                continue
        if recovered and not sidecar.is_file():
            record.update(decision='acquired', reason='The interrupted exact-ID acquisition was recovered.')
            acquired.append(video_id)
            records.append(record)
            continue
        if _existing_original(draft) is not None or sidecar.is_file():
            record.update(
                decision="skipped-existing",
                reason="An existing original draft or compiled lyrics sidecar was preserved.",
            )
            records.append(record)
            continue
        record = acquire_exact_item_draft(item, draft, client=provider,
            detector=language_detector, inferer=inferer)
        if record.get('decision') == 'acquired':
            acquired.append(video_id)
        records.append(record)

    counts = {
        decision: sum(record["decision"] == decision for record in records)
        for decision in (
            "acquired",
            "skipped-existing",
            "not-found",
            "rejected",
            "api-error",
        )
    }
    with exclusive_process_lock(owner_input / '.lyrics-acquisition-report.lock', wait_seconds=60):
        state_by_id = _previous_records(report_path)
        for record in records:
            previous = state_by_id.get(record["videoId"])
            if (
                record.get("decision") == "skipped-existing"
                and isinstance(previous, dict)
                and previous.get("decision") in {"acquired", "skipped-existing"}
            ):
                continue
            state_by_id[record["videoId"]] = record
        state_records = [state_by_id[item["videoId"]] for item in items if item["videoId"] in state_by_id]
        state_counts = {
            decision: sum(record.get("decision") == decision for record in state_records)
            for decision in (
                "acquired",
                "skipped-existing",
                "not-found",
                "rejected",
                "api-error",
            )
        }
        json_worklist, csv_worklist, missing = _write_worklists(
            config=config,
            items=items,
            records=state_records,
        )
        report = {
            "formatVersion": 1,
            "generatedAt": utc_now(),
            "selectionCount": len(targets),
            "inventoryCount": len(items),
            "provider": {
                "name": "YouTube Music via ytmusicapi",
                "officialApi": False,
                "identityPolicy": "exact-video-id-only",
            },
            "containsLyrics": False,
            "runCounts": counts,
            "counts": state_counts,
            "stateRecordCount": len(state_records),
            "resolvedCount": len(items) - len(missing),
            "missingCount": len(missing),
            "records": state_records,
        }
        atomic_write_json(report_path, report)
    return {
        "acquired": acquired,
        "acquiredCount": len(acquired),
        "counts": counts,
        "resolvedCount": len(items) - len(missing),
        "missingCount": len(missing),
        "report": str(report_path),
        "missingJson": str(json_worklist),
        "missingCsv": str(csv_worklist),
    }


__all__ = [
    "LyricsAcquisitionError",
    "acquire",
    "infer_language_script",
]

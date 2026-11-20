"""Current source acquisition operation, composed by the workflow."""
from __future__ import annotations
from stemwise_engine.workflow_review import review_template
import hashlib
import html
import json
import os
import re
import uuid
import xml.etree.ElementTree as ET
from urllib.parse import parse_qs, urljoin, urlparse
from stemwise_engine.provider_operations import (fetch_bytes, bounded_provider_operation, current_budget,
    ProviderCancelled, ProviderDeadline, ProviderStageDeadline, optional_provider_stage,
    raise_if_operation_stopped)
from stemwise_engine.common import parse_strict_json_bytes, sha256_file, resolve_path, validate_video_id, manifested_temporary_directory
from dataclasses import dataclass
from typing import Callable
from pathlib import Path
from typing import Any
from stemwise_engine.common import (atomic_write_bytes, atomic_write_json, diagnostic_code, read_bounded_json,
                    read_bounded_regular_bytes, utc_now, validate_generated_path, WorkerRetirementError)
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.workflow_records import FutureSongError
from stemwise_engine.alignment_attempt import SongAttemptBlocked
from stemwise_engine.lyrics_acquire import acquire_exact_item_draft, discover_counterpart_candidates
from stemwise_engine.packages import read_child
from stemwise_engine.youtube import canonical_url, fetch_video_info


def _download_candidate(url: str, *, max_bytes: int = 5 * 1024 * 1024) -> bytes:
    return fetch_bytes(url, max_bytes=max_bytes, headers={"User-Agent": "Mozilla/5.0 Stemwise/1"})


def _subtitle_choices(entries: Any) -> list[dict[str, Any]]:
    if not isinstance(entries, list):
        return []
    choices = []
    for extension in ("vtt", "json3", "ttml", "srv3"):
        for entry in entries:
            if (
                isinstance(entry, dict)
                and entry.get("ext") == extension
                and isinstance(entry.get("url"), str)
                and entry["url"].startswith("https://")
            ):
                choices.append(entry)
                break
    return choices


def _subtitle_has_text(payload: bytes, extension: str) -> bool:
    """Recognize a nonempty caption body; this does not establish lyric authority."""
    try:
        text = payload.decode("utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")
        if extension == "vtt":
            if not re.fullmatch(r"WEBVTT(?:[ \t][^\n]*)?", text.split("\n", 1)[0]):
                return False
            timestamp = r"(?:\d{2,}:)?[0-5]\d:[0-5]\d\.\d{3}"
            for block in re.split(r"\n[ \t]*\n", text):
                lines = block.splitlines()
                if not lines or re.fullmatch(r"NOTE(?:[ \t].*)?|STYLE|REGION", lines[0]):
                    continue
                cue = 0 if "-->" in lines[0] else 1
                if (len(lines) > cue + 1
                    and re.fullmatch(timestamp + r"[ \t]+-->[ \t]+" + timestamp + r"(?:[ \t].*)?", lines[cue])
                    and any(html.unescape(re.sub(r'<[^>]*>', '', line)).strip()
                            for line in lines[cue + 1:])):
                    return True
            return False
        if extension == "json3":
            document = parse_strict_json_bytes(payload)
            events = document.get("events") if isinstance(document, dict) else None
            return isinstance(events, list) and any(
                isinstance(event, dict) and isinstance(event.get("segs"), list) and any(
                    isinstance(segment, dict) and isinstance(segment.get("utf8"), str)
                    and segment["utf8"].strip() for segment in event["segs"]
                ) for event in events
            )
        if extension in {"ttml", "srv3"}:
            document = ET.fromstring(text)
            if document.tag.rsplit("}", 1)[-1] != ("tt" if extension == "ttml" else "timedtext"):
                return False
            return any(element.tag.rsplit("}", 1)[-1] == "p" and "".join(element.itertext()).strip()
                       for element in document.iter())
    except (ValueError, ET.ParseError):
        return False
    return False


@bounded_provider_operation
def _download_subtitle(choice: dict[str, Any]) -> bytes:
    url = choice["url"]
    payload = _download_candidate(url)
    if payload.decode("utf-8-sig").lstrip().startswith("#EXTM3U"):
        lines = [line.strip() for line in payload.decode("utf-8-sig").splitlines() if line.strip()]
        tags = [line.split(":", 1)[0] for line in lines if line.startswith("#")]
        uris = [line for line in lines if not line.startswith("#")]
        allowed = {"#EXTM3U", "#EXT-X-VERSION", "#EXT-X-PLAYLIST-TYPE", "#EXT-X-TARGETDURATION",
                   "#EXT-X-MEDIA-SEQUENCE", "#EXTINF", "#EXT-X-ENDLIST"}
        # shortcut: only complete single-segment playlists; use provider format fallback for other HLS.
        if (lines[0] != "#EXTM3U" or len(uris) != 1 or tags.count("#EXTINF") != 1
            or lines[-1] != "#EXT-X-ENDLIST" or not set(tags) <= allowed):
            raise FutureSongError("unsupported subtitle playlist")
        origin = urlparse(url)
        target_url = urljoin(url, uris[0])
        target = urlparse(target_url)
        if (origin.scheme != "https" or origin.hostname != "www.youtube.com"
            or origin.path != "/api/timedtext" or target.scheme != "https"
            or target.netloc != origin.netloc or target.path != origin.path
            or not parse_qs(origin.query).get("v")
            or parse_qs(target.query).get("v") != parse_qs(origin.query).get("v")
            or target.username is not None or target.fragment):
            raise FutureSongError("subtitle playlist points outside the caption endpoint")
        payload = _download_candidate(target_url)
    if not _subtitle_has_text(payload, choice["ext"]):
        raise FutureSongError("subtitle response has no supported caption text")
    return payload


def _automatic_caption_languages(captions: Any, info_language: str | None) -> list[str]:
    if not isinstance(captions, dict):
        return []
    keys = [key for key in captions if isinstance(key, str)]
    selected: list[str] = []
    preferences = [info_language, f"{info_language}-orig" if info_language else None, "ar", "en", "en-orig"]
    for key in preferences:
        if key and key in keys and key not in selected:
            selected.append(key)
    for key in keys:
        lowered = key.lower()
        if (lowered.endswith("-orig") or "-orig-" in lowered) and key not in selected:
            selected.append(key)
    if not selected:
        for key in keys:
            # Avoid downloading the huge list of machine-translated caption
            # variants. Three native-looking candidates are sufficient for the
            # agent's evidence queue; the exact URL remains available online.
            if "-" not in key or key.endswith("-orig"):
                selected.append(key)
            if len(selected) >= 3:
                break
    return selected[:5]


_DESCRIPTION_METADATA_MARKERS = (
    "provided to youtube by",
    "auto-generated by youtube",
    "released on:",
    "associated performer:",
    "composer:",
    "lyricist:",
    "music publisher:",
    "producer:",
)


def _description_is_potential_lyric_body(description: str) -> bool:
    """Conservatively distinguish prose/credit blocks from possible lyrics."""

    normalized = description.replace("\r\n", "\n").replace("\r", "\n").strip()
    lowered = normalized.casefold()
    if any(marker in lowered for marker in ("provided to youtube by", "auto-generated by youtube")):
        return False
    candidate_lines: list[str] = []
    for raw in normalized.split("\n"):
        line = raw.strip()
        folded = line.casefold()
        if not line or line.startswith(("http://", "https://", "#", "@")):
            continue
        if line.startswith(("\u2117", "\u00a9")) or any(
            folded.startswith(marker) for marker in _DESCRIPTION_METADATA_MARKERS
        ):
            continue
        if sum(character.isalpha() for character in line) >= 8:
            candidate_lines.append(line)
    return (
        len(candidate_lines) >= 4
        and sum(sum(character.isalpha() for character in line) for line in candidate_lines) >= 80
    )


def _publish_source_candidate(path: Path, payload: bytes) -> Path:
    """Never replace bytes referenced by a retained source/review binding."""
    digest = hashlib.sha256(payload).hexdigest()
    if path.exists():
        if path.is_symlink():
            raise FutureSongError('source candidate path is unsafe')
        if sha256_file(path) == digest:
            return path
        path = path.with_name(f'{path.stem}.{digest}{path.suffix}')
    staged = path.with_name(f'.{path.name}.{uuid.uuid4().hex}.tmp')
    try:
        atomic_write_bytes(staged, payload)
        try:
            os.link(staged, path)
        except FileExistsError:
            if path.is_symlink() or sha256_file(path) != digest:
                raise FutureSongError('source candidate publication conflict')
        return path
    finally:
        staged.unlink(missing_ok=True)


def collect_normal_youtube_candidates(
    info: dict[str, Any],
    destination: Path,
    *,
    video_id: str,
) -> dict[str, Any]:
    """Stage exact-video description/subtitle evidence without logging text."""

    if info.get("id") != video_id:
        raise FutureSongError("normal YouTube candidate metadata returned a different ID")
    destination.mkdir(parents=True, exist_ok=True)
    records: list[dict[str, Any]] = []
    description = info.get("description")
    if isinstance(description, str) and description.strip():
        payload = (description.replace("\r\n", "\n").replace("\r", "\n").strip() + "\n").encode("utf-8")
        path = _publish_source_candidate(destination / 'normal-youtube-description.txt', payload)
        records.append(
            {
                "type": "description",
                "surface": "normal-youtube",
                "automatic": False,
                "path": str(path),
                "bytes": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest(),
                "lyricUse": (
                    "potential-body"
                    if _description_is_potential_lyric_body(description)
                    else "metadata-or-prose-only"
                ),
            }
        )

    subtitle_groups = (
        ("manual-subtitle", info.get("subtitles"), False, None),
        (
            "automatic-caption",
            info.get("automatic_captions"),
            True,
            _automatic_caption_languages(info.get("automatic_captions"), info.get("language")),
        ),
    )
    for kind, mapping, automatic, selected_languages in subtitle_groups:
        if not isinstance(mapping, dict):
            continue
        languages = selected_languages if selected_languages is not None else list(mapping)[:12]
        for language in languages:
            choices = _subtitle_choices(mapping.get(language))
            if not choices:
                continue
            safe_language = re.sub(r"[^A-Za-z0-9._-]", "_", str(language))[:40] or "und"
            try:
                with optional_provider_stage() as stage:
                    for choice in choices:
                        try:
                            payload = _download_subtitle(choice)
                            break
                        except (ProviderDeadline, ProviderCancelled, WorkerRetirementError):
                            raise
                        except Exception:
                            if choice is choices[-1]:
                                raise
                    extension = str(choice["ext"])
                    current_budget().remaining()
                    path = _publish_source_candidate(destination / f'normal-youtube-{kind}.{safe_language}.{extension}', payload)
                    records.append(
                        {
                            "type": kind,
                            "surface": "normal-youtube",
                            "language": language,
                            "automatic": automatic,
                            "format": extension,
                            "path": str(path),
                            "bytes": len(payload),
                            "sha256": hashlib.sha256(payload).hexdigest(),
                            "lyricUse": "potential-body",
                        }
                    )
            except ProviderStageDeadline as error:
                if stage.parent is not None:
                    stage.parent.remaining()
                records.append({"type": kind, "surface": "normal-youtube", "language": language,
                    "automatic": automatic, "status": "unavailable", "reason": diagnostic_code(error)})
            except (ProviderDeadline, ProviderCancelled, WorkerRetirementError):
                raise
            except Exception as error:
                records.append(
                    {
                        "type": kind,
                        "surface": "normal-youtube",
                        "language": language,
                        "automatic": automatic,
                        "status": "unavailable",
                        "reason": diagnostic_code(error),
                    }
                )
    return {
        "checked": True,
        "surface": "normal-youtube",
        "exactVideoIdConfirmed": True,
        "exactUrl": canonical_url(video_id),
        "candidateCount": sum("sha256" in record for record in records),
        "usableLyricCandidateCount": sum(
            record.get("lyricUse") == "potential-body" and "sha256" in record
            for record in records
        ),
        "records": records,
    }

def preserved_local_draft_acquisition(
    item: dict[str, Any], draft: Path
) -> dict[str, Any] | None:
    """Preserve an intact local draft without claiming verified provenance."""

    entries = list(draft.iterdir())
    if not entries:
        return None
    for path in entries:
        if not path.is_file() or path.is_symlink():
            raise FutureSongError("existing drafts contain a non-regular entry")
        read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=draft)
    if (draft/'.acquired-pair.json').is_file():
        return None  # The acquisition owner must reconcile an interrupted pair.
    if not any((draft/name).exists() for name in ('original.txt', 'original.lrc')):
        from stemwise_engine.lyrics_intake import _ROLES, validate_draft_manifest
        scaffold = {'lyrics.meta.json'} | {name for role in ('translationEn', 'ipa') for name in _ROLES[role]}
        if all(path.name in scaffold for path in entries):
            metadata = draft/'lyrics.meta.json'
            if metadata.is_file():
                validate_draft_manifest(read_bounded_json(metadata, max_bytes=MAX_JSON_BYTES, root=draft), item['videoId'])
            return None
    if not (draft / "lyrics.meta.json").is_file() or not any(
        (draft / name).is_file() for name in ("original.txt", "original.lrc")
    ):
        raise FutureSongError(
            "existing local draft is incomplete; its bytes were preserved"
        )
    return {
        "videoId": validate_video_id(str(item["videoId"])),
        "decision": "skipped-existing",
        "reason": "An existing local draft was preserved without acquisition; its source is unverified.",
    }


def source_candidates(record):
    candidates = list((record.get('normalYouTube') or {}).get('records', []))
    music = (record.get('youtubeMusic') or {}).get('result') or {}
    if music.get('candidatePath'):
        candidates.append({'path': music['candidatePath'], 'sha256': music.get('sha256')})
    return [candidate for candidate in candidates if isinstance(candidate, dict) and 'path' in candidate]


def source_candidate_intact(candidate, job_root):
    try:
        path = resolve_path(candidate['path'], base=job_root)
        raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=job_root)
        return hashlib.sha256(raw).hexdigest() == candidate.get('sha256')
    except (OSError, ValueError, RuntimeError, TypeError):
        return False


def source_surface_checks_complete(record, *, allow_unavailable_automatic_captions=False):
    if not isinstance(record, dict):
        return False
    music, normal = record.get('youtubeMusic'), record.get('normalYouTube')
    if (not isinstance(music, dict) or music.get('checked') is not True
            or not isinstance(normal, dict) or normal.get('checked') is not True):
        return False
    result, captions = music.get('result'), normal.get('records')
    if (allow_unavailable_automatic_captions and isinstance(result, dict)
            and result.get('decision') == 'acquired' and isinstance(captions, list)):
        captions = [item for item in captions if not (isinstance(item, dict)
            and item.get('type') == 'automatic-caption' and item.get('automatic') is True
            and item.get('status') == 'unavailable' and isinstance(item.get('reason'), str)
            and bool(item['reason'].strip()))]
    return (isinstance(result, dict) and isinstance(result.get('decision'), str)
        and result['decision'] in {'acquired', 'not-found', 'skipped-existing'}
        and ('candidatePath' not in result or (isinstance(result['candidatePath'], str) and bool(result['candidatePath'])
            and isinstance(result.get('sha256'), str) and re.fullmatch(r'[0-9a-f]{64}', result['sha256'])))
        and normal.get('result') is None and isinstance(captions, list)
        and all(isinstance(item, dict) and item.get('status') != 'unavailable'
                and isinstance(item.get('path'), str) and bool(item['path'])
                and isinstance(item.get('sha256'), str) and re.fullmatch(r'[0-9a-f]{64}', item['sha256'])
                for item in captions))


def source_record_complete(record, job_root):
    return source_surface_checks_complete(record) and all(
        source_candidate_intact(candidate, job_root) for candidate in source_candidates(record))


def counterpart_search_complete(discovery):
    if not isinstance(discovery, dict) or discovery.get('searchStatus') != 'complete':
        return False
    outcomes = discovery.get('surfaceOutcomes')
    return (not discovery.get('surfaceErrors') and isinstance(outcomes, dict)
        and all(isinstance(outcomes.get(surface), dict)
            and isinstance(outcomes[surface].get('status'), str)
            and outcomes[surface].get('status') in {'successful-empty', 'successful-results'}
            for surface in ('songs', 'videos')))


def source_record_has_usable_evidence(
    record: dict[str, Any],
    *,
    draft_present: bool,
    job_root: Path,
    package_video_id: str,
) -> bool:
    """Prove that one staged record still points to intact private evidence."""

    def intact_candidate(path_value: Any, sha_value: Any, caption_format: str | None = None) -> bool:
        if (
            not isinstance(path_value, str)
            or not isinstance(sha_value, str)
            or not re.fullmatch(r"[0-9a-f]{64}", sha_value)
        ):
            return False
        try:
            candidate = resolve_path(path_value, base=job_root)
            if not candidate.is_relative_to(job_root) or not candidate.is_file():
                return False
            if caption_format is None and candidate.suffix[1:] in {"vtt", "json3", "ttml", "srv3"}:
                caption_format = candidate.suffix[1:]
            payload = read_bounded_regular_bytes(candidate, max_bytes=5 * 1024 * 1024, root=job_root)
            return (hashlib.sha256(payload).hexdigest() == sha_value
                    and not payload.lstrip().startswith(b"#EXTM3U")
                    and (caption_format is None or _subtitle_has_text(payload, caption_format)))
        except (OSError, RuntimeError, ValueError):
            return False

    youtube_music = record.get("youtubeMusic")
    result = youtube_music.get("result") if isinstance(youtube_music, dict) else None
    if isinstance(result, dict) and result.get("decision") in {
        "acquired",
        "skipped-existing",
    }:
        candidate_path = result.get("candidatePath")
        if candidate_path is not None:
            if intact_candidate(candidate_path, result.get("sha256")):
                return True
        elif (
            result.get("decision") == "acquired"
            and record.get("videoId") == package_video_id
            and draft_present
        ):
            return True

    normal_youtube = record.get("normalYouTube")
    records = (
        normal_youtube.get("records")
        if isinstance(normal_youtube, dict)
        else None
    )
    return isinstance(records, list) and any(
        isinstance(candidate_record, dict)
        and candidate_record.get("lyricUse") == "potential-body"
        and intact_candidate(
            candidate_record.get("path"), candidate_record.get("sha256"),
            str(candidate_record.get("format") or Path(str(candidate_record.get("path", ""))).suffix[1:])
            if candidate_record.get("type") in {"manual-subtitle", "automatic-caption"} else None,
        )
        for candidate_record in records
    )


def refresh_sources_availability(
    sources: dict[str, Any], draft: Path
) -> dict[str, Any]:
    """Record lyric availability without embedding private lyric text.

    ``containsLyrics`` describes whether the job has usable lyric material,
    not whether this metadata-only JSON document contains the words.  The
    latter is recorded independently as ``containsLyricText``.
    """

    draft_present = (
        (draft / "lyrics.meta.json").is_file()
        and not (draft / "lyrics.meta.json").is_symlink()
        and any(
            (draft / name).is_file() and not (draft / name).is_symlink()
            for name in ("original.txt", "original.lrc")
        )
    )
    package_id = sources.get("videoId")
    job_root = draft.parent.resolve()

    usable_evidence = False
    evidence = sources.get("lyricEvidenceSources")
    if isinstance(evidence, list):
        for record in evidence:
            if not isinstance(record, dict):
                continue
            if source_record_has_usable_evidence(
                record,
                draft_present=draft_present,
                job_root=job_root,
                package_video_id=str(package_id),
            ):
                usable_evidence = True

    sources["containsLyrics"] = draft_present or usable_evidence
    sources["containsLyricText"] = False
    sources["usableLyricEvidenceAvailable"] = usable_evidence
    sources["draftLyricsPresent"] = draft_present
    records = [record for record in evidence if isinstance(record, dict)] if isinstance(evidence, list) else []
    checks = sources.get('exactSurfaceChecks')
    lookup_failed = (evidence is not None and (not isinstance(evidence, list) or len(records) != len(evidence))
                     or checks is not None and not isinstance(checks, dict))
    checks = checks if isinstance(checks, dict) else {}
    searched = bool(records) and all(source_surface_checks_complete(record) for record in (checks, *records))
    rejected = False
    for record in (checks, *records):
        music, normal = record.get('youtubeMusic'), record.get('normalYouTube')
        if any(value is not None and not isinstance(value, dict) for value in (music, normal)):
            lookup_failed = True
            continue
        music, normal = music or {}, normal or {}
        if (music.get('checked') is True and normal.get('checked') is True
                and not source_surface_checks_complete(record)):
            lookup_failed = True
        music_result = music.get('result')
        captions = normal.get('records')
        lookup_failed |= (isinstance(music_result, dict) and music_result.get('decision') == 'api-error'
            or normal.get('result') == 'api-error'
            or isinstance(captions, list) and any(isinstance(item, dict) and item.get('status') == 'unavailable'
                                                 for item in captions))
        rejected |= any(not source_candidate_intact(candidate, job_root)
                        for candidate in source_candidates(record))
    if 'counterpartDiscovery' in sources:
        discovery = sources['counterpartDiscovery']
        if isinstance(discovery, dict) and discovery.get('decision') == 'skipped-insufficient-metadata':
            searched = False
        elif not counterpart_search_complete(discovery):
            lookup_failed = True
            searched = False
        if isinstance(discovery, dict) and discovery.get('stagingStatus') == 'staging-failed':
            lookup_failed = True
    issues = (['lookup-failed'] if lookup_failed else []) + (['candidate-rejected'] if rejected else [])
    if not searched:
        issues.append('not-searched')
    sources['lyricsAvailabilityIssues'] = issues
    if draft_present:
        sources["lyricsAvailability"] = "draft-acquired"
    elif usable_evidence:
        sources["lyricsAvailability"] = "candidate-evidence"
    else:
        sources["lyricsAvailability"] = issues[0] if issues else 'not-found'
    return sources


@dataclass(frozen=True)
class CandidateContext:
    job_dir: Path
    workspace_root: Path
    display_path: Callable
    fetch_info: Callable = fetch_video_info


def display_candidate_paths(context, value: dict[str, Any]) -> dict[str, Any]:
    result = json.loads(json.dumps(value))
    for record in result.get("records", []):
        if isinstance(record, dict) and isinstance(record.get("path"), str):
            record["path"] = context.display_path(Path(record["path"]))
    return result


def collect_cross_id_evidence(
    context,
    package_video_id: str,
    source_video_id: str,
    *,
    relationship: str,
) -> dict[str, Any]:
    """Collect one private, still-unapproved counterpart evidence record."""

    package_video_id = validate_video_id(package_video_id)
    source_video_id = validate_video_id(source_video_id)
    if source_video_id == package_video_id:
        raise FutureSongError("a cross-ID lyric source must differ from the package ID")
    return collect_source_evidence(context, package_video_id, source_video_id, relationship=relationship)


def collect_source_evidence(
    context, package_video_id: str, source_video_id: str, *, relationship: str,
    retained_normal: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Stage source text separately from the owner's existing drafts and audio."""
    package_video_id = validate_video_id(package_video_id)
    source_video_id = validate_video_id(source_video_id)
    job_dir = context.job_dir
    source_dir = validate_generated_path(
        job_dir,
        job_dir / "candidates" / source_video_id,
        workspace_root=context.workspace_root,
    )
    info = context.fetch_info(source_video_id)
    normal_check = retained_normal if retained_normal is not None else display_candidate_paths(context,
        collect_normal_youtube_candidates(info, source_dir, video_id=source_video_id)
    )
    item: dict[str, Any] = {
        "position": 1,
        "sourcePosition": 1,
        "videoId": source_video_id,
        "url": canonical_url(source_video_id),
        "title": info.get("title") or source_video_id,
        "uploader": info.get("uploader") or info.get("channel") or "Unknown artist",
    }
    if isinstance(info.get("duration"), (int, float)):
        item["durationSeconds"] = float(info["duration"])
    with manifested_temporary_directory(
        job_dir,
        prefix=f".ytm-candidate-{source_video_id}-",
        owner={
            "kind": "youtube-music-source-candidate",
            "videoId": package_video_id,
            "sourceVideoId": source_video_id,
        },
        workspace_root=context.workspace_root,
    ) as ytm_temp:
        try:
            draft = ytm_temp / 'draft'
            ytm_record = acquire_exact_item_draft(item, draft, diagnostics_dir=job_dir)
            if ytm_record.get("decision") == "acquired":
                source_dir.mkdir(parents=True, exist_ok=True)
                candidate = _publish_source_candidate(source_dir / 'youtube-music-lyrics.txt',
                    read_bounded_regular_bytes(draft / 'original.txt', max_bytes=MAX_JSON_BYTES, root=ytm_temp))
                ytm_record["candidatePath"] = context.display_path(candidate)
        except WorkerRetirementError:
            raise
        except Exception as error:
            raise_if_operation_stopped(error)
            ytm_record = {
                "videoId": source_video_id,
                "decision": "api-error",
                "reason": diagnostic_code(error),
            }
    return {
        "videoId": source_video_id,
        "relationshipToPackage": relationship,
        "associationStatus": "pending-agent-review",
        "submittedUrl": canonical_url(source_video_id),
        "metadata": {
            key: item[key]
            for key in ("title", "uploader", "durationSeconds")
            if key in item
        },
        "youtubeMusic": {
            "checked": True,
            "exactUrl": f"https://music.youtube.com/watch?v={source_video_id}",
            "result": ytm_record,
        },
        "normalYouTube": normal_check,
    }


@dataclass(frozen=True)
class AcquisitionContext:
    workspace_root: Path
    job_dir: Path
    runtime: Path
    runtime_config_json: bytes
    recording_identity_json: bytes | None = None


@dataclass(frozen=True)
class AcquisitionOperations:
    save_job: Callable
    write_task: Callable
    pipeline: Callable
    process_song: Callable
    completed_audio: Callable
    song_info: Callable
    preflight: Callable
    display_path: Callable
    collect_normal: Callable
    failure_code: Callable
    recover_lyrics: Callable
    stage_source: Callable


class SourceAcquisition:
    def __init__(self, context: AcquisitionContext, operations: AcquisitionOperations):
        self.context, self.operations = context, operations

    def run(self, job, *, force_analysis=False):
        if self.context.job_dir.name != validate_video_id(job['videoId']):
            raise FutureSongError('Source acquisition context belongs to another job')
        self._run(job, force_analysis=force_analysis)
        if self.context.recording_identity_json is not None:
            path = self.context.job_dir/'sources.json'
            if path.is_file():
                sources = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=path.parent)
                sources['recordingInput'] = json.loads(self.context.recording_identity_json)
                for record in sources.get('lyricEvidenceSources', []):
                    if record.get('associationStatus') == 'exact-package-id':
                        record['associationStatus'] = 'pending-agent-review'
                        record['relationshipToPackage'] = 'lyric-reference-for-native-recording'
                atomic_write_json(path, sources)

    def continue_after_content_rejection(self, job, binding):
        """Stage fresh identity-matched evidence without accepting rejected wording."""
        source_path = self.context.job_dir/'sources.json'
        sources = read_bounded_json(source_path, max_bytes=MAX_JSON_BYTES, root=self.context.job_dir)
        if sources.get('videoId') != job['videoId']:
            raise FutureSongError('Source recovery belongs to another job')
        previous = sources.get('counterpartDiscovery') or {}
        existing = {record['videoId']: record for record in sources.get('lyricEvidenceSources', [])
                    if isinstance(record, dict) and isinstance(record.get('videoId'), str)}
        staged = previous.get('stagedVideoIds', [previous['stagedVideoId']] if previous.get('stagedVideoId') else [])
        staged_complete = isinstance(staged, list) and all(selected in existing
            and source_record_complete(existing[selected], self.context.job_dir) for selected in staged)
        if (previous.get('contentRejectionBinding') == binding and counterpart_search_complete(previous)
                and previous.get('stagingStatus') != 'staging-failed'
                and staged_complete):
            return
        discovery = discover_counterpart_candidates({'videoId': job['videoId'],
            'title': job.get('title'), 'uploader': job.get('uploader'), 'durationSeconds': job.get('durationSeconds')})
        selected_ids = [record.get('videoId') for record in discovery.get('candidates', []) if isinstance(record, dict)]
        if discovery.get('recommendedVideoId'):
            selected_ids.append(discovery['recommendedVideoId'])
        staged = list(dict.fromkeys(staged if isinstance(staged, list) else []))
        staging_errors = []
        for selected in dict.fromkeys(selected_ids):
            if not isinstance(selected, str) or selected == job['videoId']:
                continue
            selected = validate_video_id(selected)
            if selected not in staged:
                staged.append(selected)
            if selected in existing and source_record_complete(existing[selected], self.context.job_dir):
                continue
            try:
                self.operations.stage_source(job, selected)
            except Exception as error:
                raise_if_operation_stopped(error)
                if isinstance(error, (WorkerRetirementError, SongAttemptBlocked)):
                    raise
                staging_errors.append({'videoId': selected, 'reason': diagnostic_code(error)})
        discovery['stagedVideoIds'] = staged
        discovery['stagingStatus'] = 'staged-pending-agent-review' if staged else 'no-new-candidate'
        if len(staged) == 1:
            discovery['stagedVideoId'] = staged[0]
        sources = read_bounded_json(source_path, max_bytes=MAX_JSON_BYTES, root=self.context.job_dir)
        current = {record.get('videoId'): record for record in sources.get('lyricEvidenceSources', []) if isinstance(record, dict)}
        staging_failed = bool(staging_errors) or any(selected not in current
            or not source_record_complete(current[selected], self.context.job_dir) for selected in staged)
        if staging_failed:
            discovery['stagingStatus'] = 'staging-failed'
            discovery['stagingErrors'] = staging_errors
        sources['counterpartDiscovery'] = {**discovery, 'contentRejectionBinding': binding}
        refresh_sources_availability(sources, self.context.job_dir/'drafts')
        atomic_write_json(source_path, sources)
        self.operations.write_task(job)
        if not counterpart_search_complete(discovery) or staging_failed:
            raise FutureSongError('content-recovery-source-discovery-incomplete')

    def _run(self, job: dict[str, Any], *, force_analysis: bool = False) -> None:
        video_id = job["videoId"]
        job_dir = self.context.job_dir
        prior_state = job.get("state")
        preserve_existing_setup = prior_state in {
            "ANALYSIS_FAILED",
            "EDITORIAL_SETUP_FAILED",
            "VALIDATION_FAILED",
            "AUTHORITY_UNRESOLVED",
            "LINE_UNRESOLVED",
            "RETRYABLE_TECHNICAL",
        }
        job["state"] = "ANALYZING"
        self.operations.save_job(job)
        analysis_complete = False
        recording_recovery_started = False
        try:
            from stemwise_engine.bridge_delivery import require_workflow_budget
            require_workflow_budget(job, recording_identity=(json.loads(self.context.recording_identity_json)
                if self.context.recording_identity_json is not None else None))
            self.operations.save_job(job)
            if force_analysis and (prior_state in {
                    'VALIDATION_FAILED', 'AUTHORITY_UNRESOLVED', 'LINE_UNRESOLVED', 'RETRYABLE_TECHNICAL'}
                    or (prior_state == 'ANALYSIS_FAILED' and (job_dir / 'sources.json').is_file())):
                pipeline = self.operations.pipeline()
                previous = pipeline.load_checkpoint(video_id)
                if previous and previous.get('status') == 'complete':
                    # A stale analyzer must not reacquire or replace reviewed editorial inputs.
                    item = {'position': 1, 'videoId': video_id,
                            'title': job.get('title', video_id), 'uploader': job.get('uploader', 'Unknown artist')}
                    job['diskPreflight'] = self.operations.preflight(
                        video_id, previous['originalAudio']['durationMs'] / 1000)
                    try:
                        self.operations.completed_audio(pipeline, video_id)
                        outcome = 'skipped'
                    except FutureSongError:
                        outcome = self.operations.process_song(pipeline, item, force=True)
                    if outcome not in {'complete', 'skipped'}:
                        raise FutureSongError('PC analysis did not complete')
                    document, _ = read_child(pipeline.child_path(video_id), pipeline.config)
                    atomic_write_json(job_dir / 'analysis-summary.json', analysis_summary(document))
                    if prior_state == 'ANALYSIS_FAILED':
                        sources = read_bounded_json(job_dir / 'sources.json', max_bytes=MAX_JSON_BYTES, root=job_dir)
                        if sources.get('videoId') != video_id:
                            raise FutureSongError('existing sources.json has the wrong job identity; it was preserved')
                        job['state'] = 'WAITING_FOR_EDITORIAL' if sources.get('containsLyrics') else 'NEEDS_LYRICS'
                    else:
                        job['state'] = prior_state
                    job.pop('error', None)
                    if job['state'] == 'NEEDS_LYRICS':
                        analysis_complete = True
                        self.operations.save_job(job)
                        recording_recovery_started = True
                        self.operations.recover_lyrics(job)
                    return
            info = self.operations.song_info(video_id)
            item = {
                "position": 1,
                "sourcePosition": 1,
                "videoId": video_id,
                "url": canonical_url(video_id),
                "title": info.get("title") or video_id,
                "uploader": info.get("uploader") or info.get("channel") or "Unknown artist",
            }
            if isinstance(info.get("duration"), (int, float)):
                item["durationSeconds"] = float(info["duration"])
            job.update(title=item["title"], uploader=item["uploader"], durationSeconds=item.get("durationSeconds"))
            job["diskPreflight"] = self.operations.preflight(
                video_id, item.get("durationSeconds")
            )
            self.operations.save_job(job)
            normal_check = display_candidate_paths(CandidateContext(self.context.job_dir, self.context.workspace_root, self.operations.display_path),
                self.operations.collect_normal(
                    info,
                    validate_generated_path(
                        job_dir,
                        job_dir / "candidates",
                        workspace_root=self.context.workspace_root,
                    ),
                    video_id=video_id,
                )
            )
            pipeline = self.operations.pipeline()
            outcome = self.operations.process_song(pipeline, item, force=force_analysis)
            if outcome not in {"complete", "skipped"}:
                raise FutureSongError("PC analysis did not complete")
            analysis_complete = True
            document, _ = read_child(self.context.runtime / "children" / f"{video_id}.stemwise.zip", json.loads(self.context.runtime_config_json))
            atomic_write_json(job_dir / "analysis-summary.json", analysis_summary(document))
            draft = validate_generated_path(
                job_dir,
                job_dir / "drafts",
                workspace_root=self.context.workspace_root,
            )
            draft.mkdir(parents=True, exist_ok=True)
            acquisition = preserved_local_draft_acquisition(item, draft)
            if acquisition is None:
                acquisition = acquire_exact_item_draft(item, draft)
            counterpart_discovery: dict[str, Any] | None = None
            automatically_staged: list[dict[str, Any]] = []
            source_recovery_owned = False
            lyric_evidence_sources = [
                {
                    "videoId": video_id,
                    "relationshipToPackage": "package-source-upload",
                    "associationStatus": "exact-package-id",
                    "youtubeMusic": {
                        "checked": True,
                        "exactUrl": f"https://music.youtube.com/watch?v={video_id}",
                        "result": acquisition,
                    },
                    "normalYouTube": normal_check,
                }
            ]
            sources = {
                "formatVersion": 1,
                "generatedAt": utc_now(),
                "videoId": video_id,
                "exactUrl": canonical_url(video_id),
                "identityModel": {
                    "packageVideoId": video_id,
                    "lyricSourceVideoIdsMayDiffer": True,
                    "rule": "A lyric-source upload never changes the package/app matching ID.",
                },
                "exactSurfaceChecks": {
                    "youtubeMusic": {
                        "checked": True,
                        "exactUrl": f"https://music.youtube.com/watch?v={video_id}",
                        "result": acquisition,
                    },
                    "normalYouTube": normal_check,
                },
                "lyricEvidenceSources": lyric_evidence_sources,
                "metadata": {
                    key: item[key]
                    for key in ("title", "uploader", "durationSeconds")
                    if key in item
                },
                "lyricsAcquisition": acquisition,
            }
            if (
                acquisition.get("decision") != "acquired"
                and int(normal_check.get("usableLyricCandidateCount") or 0) == 0
            ):
                counterpart_discovery = discover_counterpart_candidates(item)
                sources_path = job_dir/'sources.json'
                prior_sources = read_bounded_json(sources_path, max_bytes=MAX_JSON_BYTES, root=job_dir) if sources_path.is_file() else None
                retained_sources = {record.get('videoId'): record for record in prior_sources.get('lyricEvidenceSources', [])
                    if isinstance(record, dict)} if isinstance(prior_sources, dict) and prior_sources.get('videoId') == video_id else {}
                publish_progress = prior_sources is None or (isinstance(prior_sources, dict)
                    and prior_sources.get('videoId') == video_id
                    and (prior_sources.get('counterpartDiscovery') or {}).get('stagingStatus') == 'in-progress')
                if publish_progress and prior_sources is not None:
                    source_recovery_owned = True
                    sources = prior_sources
                    lyric_evidence_sources = list(prior_sources.get('lyricEvidenceSources', []))
                candidate_ids = [candidate.get('videoId') for candidate in counterpart_discovery.get('candidates', [])
                                 if isinstance(candidate, dict)]
                if counterpart_discovery.get('recommendedVideoId'):
                    candidate_ids.append(counterpart_discovery['recommendedVideoId'])
                staging_errors = []
                for recommended_id in dict.fromkeys(candidate_ids):
                    if not isinstance(recommended_id, str) or recommended_id == video_id:
                        continue
                    try:
                        retained = retained_sources.get(recommended_id)
                        record = retained if retained is not None and source_record_complete(retained, job_dir) else collect_cross_id_evidence(CandidateContext(self.context.job_dir, self.context.workspace_root, self.operations.display_path),
                            video_id,
                            recommended_id,
                            relationship="automatically-discovered-counterpart",
                        )
                        automatically_staged.append(record)
                        if publish_progress:
                            sources['lyricEvidenceSources'] = list({record['videoId']: record
                                for record in lyric_evidence_sources + automatically_staged}.values())
                            sources['counterpartDiscovery'] = {**counterpart_discovery, 'stagingStatus': 'in-progress',
                                'stagedVideoIds': [record['videoId'] for record in automatically_staged]}
                            refresh_sources_availability(sources, draft)
                            atomic_write_json(sources_path, sources)
                    except (WorkerRetirementError, SongAttemptBlocked):
                        raise
                    except Exception as error:
                        raise_if_operation_stopped(error)
                        staging_errors.append({'videoId': recommended_id, 'reason': diagnostic_code(error)})
                staged_ids = [record['videoId'] for record in automatically_staged]
                counterpart_discovery['stagedVideoIds'] = staged_ids
                counterpart_discovery['stagingStatus'] = ('staging-failed' if staging_errors or any(
                    not source_record_complete(record, job_dir) for record in automatically_staged)
                    else 'staged-pending-agent-review' if staged_ids else 'no-new-candidate')
                if staging_errors:
                    counterpart_discovery['stagingErrors'] = staging_errors
                if len(staged_ids) == 1:
                    counterpart_discovery['stagedVideoId'] = staged_ids[0]
            sources['lyricEvidenceSources'] = list({record['videoId']: record
                for record in lyric_evidence_sources + automatically_staged}.values())
            if counterpart_discovery is not None:
                sources["counterpartDiscovery"] = counterpart_discovery
            refresh_sources_availability(sources, draft)
            sources_path = validate_generated_path(
                job_dir,
                job_dir / "sources.json",
                workspace_root=self.context.workspace_root,
            )
            if preserve_existing_setup and not source_recovery_owned and (
                sources_path.exists() or sources_path.is_symlink()
            ):
                preserved_sources = read_bounded_json(
                    sources_path,
                    max_bytes=MAX_JSON_BYTES,
                    root=job_dir,
                )
                if (
                    not isinstance(preserved_sources, dict)
                    or preserved_sources.get("videoId") != video_id
                ):
                    raise FutureSongError(
                        "existing sources.json has the wrong job identity; it was preserved"
                    )
                sources = refresh_sources_availability(preserved_sources, draft)
            atomic_write_json(sources_path, sources)
            review_path = validate_generated_path(
                job_dir,
                job_dir / "agent-review.json",
                workspace_root=self.context.workspace_root,
            )
            if preserve_existing_setup and (
                review_path.exists() or review_path.is_symlink()
            ):
                read_bounded_regular_bytes(
                    review_path,
                    max_bytes=MAX_JSON_BYTES,
                    root=job_dir,
                )
            else:
                atomic_write_json(
                    review_path,
                    review_template(video_id, sources),
                )
            if bool(sources.get("containsLyrics")):
                job["state"] = "WAITING_FOR_EDITORIAL"
            else:
                job["state"] = "NEEDS_LYRICS"
                lyrics_needed_path = validate_generated_path(
                    job_dir,
                    job_dir / "LYRICS_NEEDED.txt",
                    workspace_root=self.context.workspace_root,
                )
                if preserve_existing_setup and (
                    lyrics_needed_path.exists() or lyrics_needed_path.is_symlink()
                ):
                    read_bounded_regular_bytes(
                        lyrics_needed_path,
                        max_bytes=MAX_JSON_BYTES,
                        root=job_dir,
                    )
                else:
                    atomic_write_bytes(
                        lyrics_needed_path,
                        (
                            f"videoId: {video_id}\n"
                            f"title: {item['title']}\n"
                            f"exactUrl: {canonical_url(video_id)}\n"
                            "\nReview the unapproved audio-first lyric candidate against this recording.\n"
                        ).encode("utf-8"),
                    )
            job["lyricsAcquisitionDecision"] = acquisition["decision"]
            job.pop("error", None)
            job.pop("outcome", None)
            job.pop("validation", None)
            if not sources.get('containsLyrics'):
                self.operations.save_job(job)
                recording_recovery_started = True
                self.operations.recover_lyrics(job)
        except Exception as error:
            raise_if_operation_stopped(error)
            if recording_recovery_started:
                raise
            job["state"] = (
                "EDITORIAL_SETUP_FAILED" if analysis_complete else "ANALYSIS_FAILED"
            )
            try:
                job['error'] = self.operations.failure_code(error)
                self.operations.save_job(job)
                self.operations.write_task(job)
            except Exception as diagnostic_error:
                from stemwise_engine.common import raise_with_cleanup_failure
                raise_with_cleanup_failure(error, diagnostic_error)
            raise


def analysis_summary( document: dict[str, Any]) -> dict[str, Any]:
    analysis = document["analysis"]
    return {
        "formatVersion": 1,
        "generatedAt": utc_now(),
        "containsLyrics": False,
        "videoId": document["source"]["videoId"],
        "status": analysis["status"],
        "durationMs": analysis.get("durationMs"),
        "sections": {
            name: analysis[name]["status"]
            for name in ("rhythm", "key", "structure", "tuning", "loudness", "waveform")
        },
        "toolRuns": [
            {
                key: run[key]
                for key in ("name", "status", "version")
                if key in run
            }
            for run in analysis["toolRuns"]
        ],
    }

#!/usr/bin/env python3
"""Acquire or compile TXT/LRC drafts into frozen Stemwise lyrics objects.

The compiler performs no network access.  The explicitly invoked ``acquire``
command uses the separate exact-video-ID acquisition module, then passes only
its local drafts through the same compiler and frozen contract.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

from stemwise_engine.common import (
    VIDEO_ID_RE,
    atomic_write_json,
    canonical_lyrics_language,
    diagnostic_code,
    load_config,
    read_bounded_json,
    read_bounded_regular_bytes,
    parse_strict_json_bytes,
    validate_video_id,
)
from stemwise_engine.contract import MAX_JSON_BYTES, ContractError
from stemwise_engine.packages import PackageValidationError, read_child
from stemwise_engine.song_enrichment_v2 import validate_lyrics, require_staged_lyrics


class LyricsInputError(ValueError):
    """A safe diagnostic about lyric metadata or structure, never lyric text."""


_ROLES: dict[str, tuple[str, str]] = {
    "original": ("original.txt", "original.lrc"),
    "translationEn": ("translation-en.txt", "translation-en.lrc"),
    "ipa": ("transliteration-ipa.txt", "transliteration-ipa.lrc"),
}
_STANDARD_TAG_RE = re.compile(r"^\[(?:ar|al|ti|by|re|ve|length):[^\]\r\n]*\]$", re.IGNORECASE)
_OFFSET_RE = re.compile(r"^\[offset:([+-]?\d+)\]$", re.IGNORECASE)
_TIMESTAMP_RE = re.compile(r"\[(\d{1,3}):([0-5]\d)(?:[.:](\d{1,3}))?\]")
_ENHANCED_RE = re.compile(r"<\d{1,3}:[0-5]\d(?:[.:]\d{1,3})?>")
_LANGUAGE_RE = re.compile(r"^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")
_SCRIPT_RE = re.compile(r"^[A-Z][a-z]{3}$")
ENDPOINT_REFINEMENT_REASON = "line endpoints require automatic refinement."


def _safe_json(path: Path) -> Any:
    try:
        return read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=path.parent)
    except (OSError, UnicodeError, ValueError, json.JSONDecodeError) as error:
        raise LyricsInputError(f"{path.name}: invalid UTF-8 JSON ({error.__class__.__name__})") from error


def _read_text(path: Path) -> str:
    if (path.parent / '.acquired-pair.json').exists():
        raise LyricsInputError('acquired text/provenance publication is pending; retry acquisition')
    try:
        text = read_bounded_regular_bytes(
            path, max_bytes=MAX_JSON_BYTES, root=path.parent
        ).decode("utf-8-sig")
    except (OSError, UnicodeError, ValueError) as error:
        raise LyricsInputError(f"{path.name}: cannot read UTF-8 text ({error.__class__.__name__})") from error
    if "\x00" in text:
        raise LyricsInputError(f"{path.name}: NUL characters are not allowed")
    from stemwise_engine.lyrics_normalize import trim_blank_outer_lines
    normalized = trim_blank_outer_lines(text)
    if not normalized.strip():
        raise LyricsInputError(f"{path.name}: input is empty")
    return normalized


def _fraction_ms(value: str | None) -> int:
    if value is None:
        return 0
    return int(value) * (100 if len(value) == 1 else 10 if len(value) == 2 else 1)


def parse_lrc(path: Path) -> list[dict[str, Any]]:
    """Preserve stable occurrence order, including simultaneously sung distinct lines."""

    text = _read_text(path)
    offset: int | None = None
    parsed: list[tuple[int, str, int]] = []
    for line_number, raw_line in enumerate(text.split("\n"), start=1):
        if not raw_line.strip():
            continue
        if _ENHANCED_RE.search(raw_line):
            raise LyricsInputError(f"{path.name}:{line_number}: enhanced word timing is not supported")
        stripped = raw_line.strip()
        offset_match = _OFFSET_RE.fullmatch(stripped)
        if offset_match:
            if offset is not None:
                raise LyricsInputError(f"{path.name}:{line_number}: duplicate offset tag")
            offset = int(offset_match.group(1))
            continue
        if _STANDARD_TAG_RE.fullmatch(stripped):
            continue

        cursor = 0
        timestamps: list[int] = []
        while True:
            match = _TIMESTAMP_RE.match(raw_line, cursor)
            if match is None:
                break
            minutes = int(match.group(1))
            seconds = int(match.group(2))
            start_ms = (minutes * 60 + seconds) * 1000 + _fraction_ms(match.group(3))
            timestamps.append(start_ms)
            cursor = match.end()
        if not timestamps:
            raise LyricsInputError(f"{path.name}:{line_number}: expected a line-level timestamp")
        lyric = raw_line[cursor:].strip()
        if not lyric:
            raise LyricsInputError(f"{path.name}:{line_number}: timestamped text is blank")
        if re.match(r'^\[(?:[+\-]?\d|(?:offset|ar|al|ti|by|re|ve|length):)', lyric, re.IGNORECASE):
            raise LyricsInputError(f"{path.name}:{line_number}: malformed or unsupported timestamp")
        for start_ms in timestamps:
            parsed.append((start_ms, lyric, line_number))

    if not parsed:
        raise LyricsInputError(f"{path.name}: no timestamped lyric lines were found")
    adjustment = offset or 0
    adjusted: list[tuple[int, str, int]] = []
    for start_ms, lyric, line_number in parsed:
        result = start_ms + adjustment
        if result < 0:
            raise LyricsInputError(f"{path.name}:{line_number}: offset makes startMs negative")
        adjusted.append((result, lyric, line_number))
    adjusted.sort(key=lambda item: item[0])
    seen = set()
    for start_ms, lyric, line_number in adjusted:
        if (start_ms, lyric) in seen:
            raise LyricsInputError(f"{path.name}:{line_number}: duplicated timed lyric")
        seen.add((start_ms, lyric))
    return [{"startMs": start_ms, "text": lyric} for start_ms, lyric, _ in adjusted]


def _expect_exact_keys(
    value: Any,
    *,
    label: str,
    required: set[str],
    optional: set[str] = frozenset(),
) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise LyricsInputError(f"{label}: must be a JSON object")
    missing = required - value.keys()
    extra = value.keys() - required - optional
    if missing:
        raise LyricsInputError(f"{label}: missing {', '.join(sorted(missing))}")
    if extra:
        raise LyricsInputError(f"{label}: unknown {', '.join(sorted(extra))}")
    return value


def _source(value: Any, label: str) -> dict[str, str]:
    record = _expect_exact_keys(value, label=label, required={"name"}, optional={"url"})
    name = record["name"]
    if not isinstance(name, str) or not name.strip() or any(ord(ch) < 32 and ch not in "\t" for ch in name):
        raise LyricsInputError(f"{label}.name: must be nonblank plain text")
    result = {"name": name}
    if "url" in record:
        url = record["url"]
        if not isinstance(url, str) or not re.fullmatch(r"https://[^\s]+", url):
            raise LyricsInputError(f"{label}.url: must be an absolute HTTPS URL")
        result["url"] = url
    return result


def _component_languages(value: Any, video_id: str) -> list[str]:
    if not isinstance(value, list) or len(value) < 2:
        raise LyricsInputError(
            f"{video_id}: componentLanguages must contain at least two material languages"
        )
    result: list[str] = []
    seen: set[str] = set()
    for item in value:
        if not isinstance(item, str) or not _LANGUAGE_RE.fullmatch(item):
            raise LyricsInputError(
                f"{video_id}: componentLanguages entries must be BCP 47 language tags"
            )
        primary = canonical_lyrics_language(item.split("-", 1)[0].lower())
        if primary in {"mul", "und"}:
            raise LyricsInputError(
                f"{video_id}: componentLanguages must identify concrete languages"
            )
        if primary in seen:
            raise LyricsInputError(
                f"{video_id}: componentLanguages must not repeat a primary language"
            )
        seen.add(primary)
        result.append(primary)
    return sorted(result)


def _inventory_ids(config: dict[str, Any]) -> tuple[list[str], set[str]]:
    inventory_path = Path(config["outputDir"]) / "work" / "inventory.json"
    snapshot = _safe_json(inventory_path)
    if not isinstance(snapshot, dict) or not isinstance(snapshot.get("items"), list):
        raise LyricsInputError("inventory.json: missing items array")
    ordered: list[str] = []
    for item in snapshot["items"]:
        video_id = item.get("videoId") if isinstance(item, dict) else None
        if not isinstance(video_id, str) or not VIDEO_ID_RE.fullmatch(video_id):
            raise LyricsInputError("inventory.json: contains an invalid videoId")
        ordered.append(video_id)
    if len(set(ordered)) != len(ordered):
        raise LyricsInputError("inventory.json: repeats a videoId")
    return ordered, set(ordered)


def _role_file(draft_dir: Path, role: str) -> Path | None:
    txt_name, lrc_name = _ROLES[role]
    txt = draft_dir / txt_name
    lrc = draft_dir / lrc_name
    if txt.is_symlink() or lrc.is_symlink():
        raise LyricsInputError(f"{draft_dir.name}: lyric role files must not be symlinks")
    if txt.is_file() and lrc.is_file():
        raise LyricsInputError(f"{draft_dir.name}: provide only one of {txt_name} or {lrc_name}")
    return lrc if lrc.is_file() else txt if txt.is_file() else None


def read_lyrics_text(path: Path) -> str:
    return '\n'.join(line['text'] for line in parse_lrc(path)) if path.suffix.lower() == '.lrc' else _read_text(path)


def read_role_text(draft_dir: Path, role: str) -> str:
    path = _role_file(draft_dir, role)
    if path is None:
        raise LyricsInputError(f'{draft_dir.name}: {role} lyric input is missing')
    return read_lyrics_text(path)


def read_role_snapshot(draft_dir: Path, role: str) -> tuple[str, str]:
    """Bind parsed display wording to the unchanged exact TXT/LRC input bytes."""
    path = _role_file(draft_dir, role)
    if path is None:
        raise LyricsInputError(f'{draft_dir.name}: {role} lyric input is missing')
    raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=draft_dir)
    text = read_lyrics_text(path)
    if read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=draft_dir) != raw:
        raise LyricsInputError('lyric input changed during inspection')
    return text, hashlib.sha256(raw).hexdigest()


def _track(
    path: Path,
    *,
    track_id: str,
    kind: str,
    language: str,
    source: dict[str, str],
    script: str | None = None,
    based_on: str | None = None,
    component_languages: list[str] | None = None,
) -> dict[str, Any]:
    track: dict[str, Any] = {
        "id": track_id,
        "kind": kind,
        "language": language,
        "format": "plain",
        "source": source,
    }
    if script:
        track["script"] = script
    if based_on:
        track["basedOnTrackId"] = based_on
    if component_languages is not None:
        track["componentLanguages"] = component_languages
    track["text"] = read_lyrics_text(path)
    return track


def validate_draft_manifest(value: Any, video_id: str):
    manifest = _expect_exact_keys(
        value,
        label="lyrics.meta.json",
        required={"videoId", "originalLanguage", "originalScript", "sources"},
        optional={"componentLanguages", "contentReviewRequired"},
    )
    if manifest["videoId"] != video_id:
        raise LyricsInputError(f"{video_id}: manifest videoId does not match its folder")
    if "contentReviewRequired" in manifest:
        if manifest["contentReviewRequired"] is not True:
            raise LyricsInputError("contentReviewRequired must be true when present")
        raise LyricsInputError(f"{video_id}: lyric content conflict requires owner review; both versions preserved")
    language = manifest["originalLanguage"]
    script = manifest["originalScript"]
    if not isinstance(language, str) or not _LANGUAGE_RE.fullmatch(language):
        raise LyricsInputError(f"{video_id}: originalLanguage must be a BCP 47 tag")
    language = canonical_lyrics_language(language)
    if not isinstance(script, str) or not _SCRIPT_RE.fullmatch(script):
        raise LyricsInputError(f"{video_id}: originalScript must be an ISO 15924 code")
    primary = language.split("-", 1)[0].lower()
    component_languages: list[str] | None = None
    if "componentLanguages" in manifest:
        if primary != "mul":
            raise LyricsInputError(
                f"{video_id}: componentLanguages is allowed only when originalLanguage is mul"
            )
        component_languages = _component_languages(manifest["componentLanguages"], video_id)
    sources = _expect_exact_keys(
        manifest["sources"],
        label="sources",
        required={"original"},
        optional={"translationEn", "ipa"},
    )
    return manifest, language, script, component_languages, sources


def compile_draft(
    draft_dir: Path,
    config: dict[str, Any],
    inventory_ids: set[str],
    *,
    expected_video_id: str | None = None,
    roles: frozenset[str] | None = None,
) -> dict[str, Any]:
    selected = frozenset(_ROLES) if roles is None else roles
    if 'original' not in selected or not selected <= _ROLES.keys():
        raise LyricsInputError('selected lyric roles must include original and only known roles')
    video_id = validate_video_id(expected_video_id or draft_dir.name)
    if draft_dir.is_symlink() or not draft_dir.is_dir():
        raise LyricsInputError(f"{video_id}: draft must be a real local directory")
    if video_id not in inventory_ids:
        raise LyricsInputError(f"{video_id}: is not in the frozen playlist inventory")
    manifest, language, script, component_languages, sources = validate_draft_manifest(
        _safe_json(draft_dir/'lyrics.meta.json'), video_id)
    primary = language.split('-', 1)[0].lower()
    original_path = _role_file(draft_dir, "original")
    translation_path = _role_file(draft_dir, "translationEn") if 'translationEn' in selected else None
    ipa_path = _role_file(draft_dir, "ipa") if 'ipa' in selected else None
    if original_path is None:
        raise LyricsInputError(f"{video_id}: original.txt or original.lrc is required")

    if primary == "en" and translation_path is not None:
        raise LyricsInputError(f"{video_id}: English originals must not include an English translation")
    if primary in {"ar", "en"} and ipa_path is not None:
        raise LyricsInputError(f"{video_id}: Arabic and English originals must not include IPA")
    if primary == "mul" and component_languages is None and ipa_path is not None:
        raise LyricsInputError(
            f"{video_id}: componentLanguages is required before multilingual IPA can be supplied"
        )
    eligible_ipa_components = (
        [value for value in component_languages if value not in {"ar", "en"}]
        if component_languages is not None
        else []
    )
    if primary == "mul" and component_languages is not None and not eligible_ipa_components and ipa_path is not None:
        raise LyricsInputError(
            f"{video_id}: Arabic/English-only multilingual originals must not include IPA"
        )
    if translation_path is not None and "translationEn" not in sources:
        raise LyricsInputError(f"{video_id}: sources.translationEn is required for its input file")
    if ipa_path is not None and "ipa" not in sources:
        raise LyricsInputError(f"{video_id}: sources.ipa is required for its input file")

    tracks = [
        _track(
            original_path,
            track_id="original",
            kind="original",
            language=language,
            script=script,
            source=_source(sources["original"], "sources.original"),
            component_languages=component_languages,
        )
    ]
    if translation_path is not None:
        tracks.append(
            _track(
                translation_path,
                track_id="translation-en",
                kind="translation",
                language="en",
                script="Latn",
                source=_source(sources["translationEn"], "sources.translationEn"),
                based_on="original",
            )
        )
    if ipa_path is not None:
        tracks.append(
            _track(
                ipa_path,
                track_id="transliteration-ipa",
                kind="transliteration",
                language=f"{primary}-fonipa",
                script="Latn",
                source=_source(sources["ipa"], "sources.ipa"),
                based_on="original",
            )
        )

    deficiencies: list[str] = []
    deficiencies.append(ENDPOINT_REFINEMENT_REASON.removesuffix("."))
    needs_translation = primary != "en"
    needs_ipa = (
        bool(eligible_ipa_components)
        if primary == "mul" and component_languages is not None
        else primary not in {"ar", "en", "mul"}
    )
    if primary == "mul" and component_languages is None:
        deficiencies.append("multilingual component languages are missing")
    if needs_translation and translation_path is None:
        deficiencies.append("English translation is missing")
    if needs_ipa and ipa_path is None:
        withheld = any((draft_dir / ("transliteration-ipa.unreviewed" + suffix)).is_file()
                       for suffix in (".txt", ".lrc"))
        deficiencies.append("IPA transliteration is withheld pending review" if withheld
                            else "IPA transliteration is missing")
    lyrics: dict[str, Any] = {"status": "partial" if deficiencies else "complete", "tracks": tracks}
    if deficiencies:
        lyrics["reason"] = "; ".join(deficiencies) + "."

    duration_ms: int | None = None
    child = Path(config["outputDir"]) / "children" / f"{video_id}.stemwise.zip"
    if child.is_file():
        try:
            document, _ = read_child(child, config)
            candidate = document.get("analysis", {}).get("durationMs")
            if type(candidate) is int:
                duration_ms = candidate
        except PackageValidationError:
            # Only a valid v2 child may supply the exact validation duration.
            pass
    if duration_ms is not None:
        for path in (original_path, translation_path, ipa_path):
            if path is not None and path.suffix.lower() == ".lrc" and any(
                record["startMs"] >= duration_ms for record in parse_lrc(path)
            ):
                raise LyricsInputError(
                    f"{video_id}: reviewed LRC startMs must be less than analysis.durationMs"
                )
    try:
        validate_lyrics(lyrics, duration_ms=duration_ms)
        require_staged_lyrics(lyrics)
    except ContractError as error:
        duration_context = (
            f" against analysis.durationMs={duration_ms}"
            if duration_ms is not None
            else ""
        )
        raise LyricsInputError(
            f"{video_id}: generated lyrics failed the frozen contract"
            f"{duration_context} ({diagnostic_code(error)})"
        ) from error
    return lyrics


def build(config: dict[str, Any], selected: list[str] | None = None) -> dict[str, Any]:
    ordered, inventory_set = _inventory_ids(config)
    project = Path(config["projectDir"])
    drafts = project / "owner-input" / "drafts"
    selected_set = {validate_video_id(value) for value in selected} if selected else None
    targets = [video_id for video_id in ordered if (drafts / video_id).is_dir()]
    if selected_set is not None:
        missing = selected_set - set(targets)
        if missing:
            raise LyricsInputError(f"missing draft folder(s): {', '.join(sorted(missing))}")
        targets = [video_id for video_id in targets if video_id in selected_set]
    built: list[str] = []
    failed: list[dict[str, str]] = []
    for video_id in targets:
        try:
            lyrics = compile_draft(drafts / video_id, config, inventory_set)
            atomic_write_json(project / "owner-input" / f"{video_id}.lyrics.json", lyrics)
            built.append(video_id)
        except Exception as error:
            failed.append(
                {
                    "videoId": video_id,
                    "reason": (
                        str(error)
                        if isinstance(error, LyricsInputError)
                        else diagnostic_code(error)
                    ),
                }
            )
    return {"built": built, "failed": failed, "builtCount": len(built), "failedCount": len(failed)}


def require_content_review_clear(owner: Path, video_id: str) -> None:
    manifest = owner / 'drafts' / video_id / 'lyrics.meta.json'
    if manifest.exists():
        value = _safe_json(manifest)
        if isinstance(value, dict) and 'contentReviewRequired' in value:
            validate_draft_manifest(value, video_id)


def compare_drafts(config: dict[str, Any]) -> dict[str, Any]:
    """Regenerate in memory; report drift or blocked content without publishing."""
    ordered, inventory = _inventory_ids(config)
    owner = Path(config['projectDir']) / 'owner-input'
    matching, differences, blocked, missing, missing_sidecars = [], [], [], [], []
    draft_root = owner / 'drafts'
    outside_inventory = sorted(path.name for path in draft_root.iterdir()
        if path.is_dir() and path.name not in inventory) if draft_root.is_dir() else []
    for video_id in [*ordered, *outside_inventory]:
        draft = owner / 'drafts' / video_id
        if not draft.is_dir():
            missing.append(video_id)
            continue
        if not (owner / f'{video_id}.lyrics.json').is_file():
            missing_sidecars.append(video_id)
            continue
        try:
            manifest = _safe_json(draft / 'lyrics.meta.json')
            if isinstance(manifest, dict) and manifest.get('contentReviewRequired') is True:
                blocked.append(video_id)
                continue
            # Comparison includes retained alternatives; this does not publish or authorize them.
            projected = compile_draft(draft, config, inventory | {video_id})
            existing = _safe_json(owner / f'{video_id}.lyrics.json')
            (matching if projected == existing else differences).append(video_id)
        except (OSError, ValueError):
            differences.append(video_id)
    return {'matching': matching, 'differences': differences, 'blocked': blocked,
            'outsideInventory': outside_inventory, 'missingDrafts': missing, 'missingSidecars': missing_sidecars,
            'matchingCount': len(matching), 'failedCount': len(differences) + len(blocked)}


def scaffold(config: dict[str, Any], video_id: str, language: str, script: str, force: bool) -> Path:
    video_id = validate_video_id(video_id)
    _, inventory_set = _inventory_ids(config)
    if video_id not in inventory_set:
        raise LyricsInputError(f"{video_id}: is not in the frozen playlist inventory")
    if not _LANGUAGE_RE.fullmatch(language):
        raise LyricsInputError("original language must be a BCP 47 tag")
    language = canonical_lyrics_language(language)
    if not _SCRIPT_RE.fullmatch(script):
        raise LyricsInputError("original script must be an ISO 15924 code")
    require_content_review_clear(Path(config["projectDir"]) / "owner-input", video_id)
    destination = Path(config["projectDir"]) / "owner-input" / "drafts" / video_id / "lyrics.meta.json"
    if destination.exists() and not force:
        raise LyricsInputError(f"{video_id}: lyrics.meta.json already exists; use --force to replace it")
    sources: dict[str, Any] = {
        "original": {"name": "Unverified local transcription; source review required"}
    }
    primary = language.split("-", 1)[0].lower()
    if primary != "en":
        sources["translationEn"] = {
            "name": "Codex-generated English translation from reviewed original; review recommended"
        }
    if primary not in {"ar", "en", "mul"}:
        sources["ipa"] = {
            "name": "Codex-generated broad IPA from reviewed original; review recommended"
        }
    atomic_write_json(
        destination,
        {
            "videoId": video_id,
            "originalLanguage": language,
            "originalScript": script,
            "sources": sources,
        },
    )
    return destination


def status(config: dict[str, Any]) -> dict[str, Any]:
    ordered, _ = _inventory_ids(config)
    project = Path(config["projectDir"])
    output = Path(config["outputDir"])
    draft_root = project / "owner-input" / "drafts"
    records = []
    for video_id in ordered:
        records.append(
            {
                "videoId": video_id,
                "draft": (draft_root / video_id / "lyrics.meta.json").is_file(),
                "sidecar": (project / "owner-input" / f"{video_id}.lyrics.json").is_file(),
                "package": (output / "children" / f"{video_id}.stemwise.zip").is_file(),
            }
        )
    return {
        "songCount": len(records),
        "draftCount": sum(record["draft"] for record in records),
        "sidecarCount": sum(record["sidecar"] for record in records),
        "packageCount": sum(record["package"] for record in records),
        "songs": records,
    }


def _positive(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def parser() -> argparse.ArgumentParser:
    command_line = argparse.ArgumentParser(
        description="Acquire or compile local Stemwise lyrics drafts"
    )
    command_line.add_argument("--config", required=True, type=Path)
    commands = command_line.add_subparsers(dest="command", required=True)
    acquire_command = commands.add_parser(
        "acquire",
        help="Fetch plain lyrics linked to exact inventory video IDs and compile successes",
    )
    acquire_command.add_argument("--video-id", action="append", default=[])
    acquire_command.add_argument("--limit", type=_positive)
    acquire_command.add_argument("--from-position", type=_positive)
    acquire_command.add_argument("--to-position", type=_positive)
    scaffold_command = commands.add_parser("scaffold", help="Create one safe metadata template")
    scaffold_command.add_argument("--video-id", required=True)
    scaffold_command.add_argument("--language", required=True)
    scaffold_command.add_argument("--script", required=True)
    scaffold_command.add_argument("--force", action="store_true")
    build_command = commands.add_parser("build", help="Compile one or all existing drafts")
    build_command.add_argument("--video-id", action="append", default=[])
    commands.add_parser(
        "qa",
        help="Write the text-free universal language/Unicode/editorial QA baseline",
    )
    commands.add_parser("status", help="Report draft/sidecar/package presence without lyric text")
    commands.add_parser("compare", help="Read-only draft regeneration comparison; no publication")
    return command_line


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        config = load_config(args.config.resolve())
        if args.command == "acquire":
            from stemwise_engine.lyrics_acquire import acquire

            acquisition = acquire(
                config,
                selected=args.video_id or None,
                limit=args.limit,
                from_position=args.from_position,
                to_position=args.to_position,
            )
            acquired_ids = acquisition["acquired"]
            compilation = (
                build(config, acquired_ids)
                if acquired_ids
                else {"built": [], "failed": [], "builtCount": 0, "failedCount": 0}
            )
            result = dict(acquisition)
            result.pop("acquired", None)
            result["compiled"] = compilation["built"]
            result["compiledCount"] = compilation["builtCount"]
            result["compileFailures"] = compilation["failed"]
            result["failedCount"] = (acquisition["counts"]["api-error"]
                + acquisition["counts"]["rejected"] + compilation["failedCount"])
        elif args.command == "scaffold":
            destination = scaffold(config, args.video_id, args.language, args.script, args.force)
            result: Any = {"videoId": args.video_id, "manifest": str(destination)}
        elif args.command == "build":
            result = build(config, args.video_id or None)
        elif args.command == "compare":
            result = compare_drafts(config)
        elif args.command == "qa":
            from stemwise_engine.lyrics_qa import audit_library

            result = audit_library(config)
        else:
            result = status(config)
        print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))
        return 1 if isinstance(result, dict) and result.get("failedCount") else 0
    except KeyboardInterrupt:
        return 130
    except Exception as error:
        print(f"stemwise-lyrics: {diagnostic_code(error)}", file=sys.stderr)
        return 2


def read_recording_draft(job_dir):
    draft, draft_sha = read_role_snapshot(job_dir / 'drafts', 'original')
    raw = read_bounded_regular_bytes(job_dir / 'drafts/lyrics.meta.json',
                                    max_bytes=16*1024*1024, root=job_dir)
    metadata = parse_strict_json_bytes(raw)
    if not isinstance(metadata, dict):
        raise LyricsInputError('lyrics.meta.json: must be a JSON object')
    original_metadata = {key: metadata[key] for key in (
        'videoId', 'originalLanguage', 'originalScript', 'componentLanguages', 'contentReviewRequired'
    ) if key in metadata}
    if 'sources' in metadata:
        sources = _expect_exact_keys(metadata['sources'], label='sources', required={'original'},
                                     optional={'translationEn', 'ipa'})
        original_metadata['sources'] = {'original': _source(sources['original'], 'sources.original')}
    identity = json.dumps(original_metadata, ensure_ascii=False, sort_keys=True,
                          separators=(',', ':')).encode('utf-8')
    if 'componentLanguages' in metadata:
        # Keep the input fingerprint while exposing the same word owners as compile_draft.
        metadata['componentLanguages'] = _component_languages(metadata['componentLanguages'], job_dir.name)
    return draft, draft_sha, metadata, hashlib.sha256(identity).hexdigest()



if __name__ == "__main__":
    raise SystemExit(main())

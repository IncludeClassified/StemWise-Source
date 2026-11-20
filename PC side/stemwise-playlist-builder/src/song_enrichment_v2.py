"""Strict PC validator for external ``stemwise.song-enrichment`` format v2."""

from __future__ import annotations

from typing import Any, Mapping, Sequence
import re

import stemwise_engine.contract as contract
from stemwise_engine.enrichment_constants import MAX_RECORDING_TRACK_ID, MAX_RECORDING_BYTES
from stemwise_engine.common import parse_strict_json_bytes
from stemwise_engine.lyrics_tokenization import TOKENIZATION_VERSION, display_word_ranges
from stemwise_engine.ipa_validation import validate_ipa


FORMAT_VERSION = contract.FORMAT_VERSION
KIND = contract.KIND
GENERATOR_NAME = contract.GENERATOR_NAME
GENERATOR_VERSION = contract.GENERATOR_VERSION

_fail = contract._fail
_child = contract._child


def _validate_ipa_text(text: str, path: str) -> None:
    issues = validate_ipa(text)
    if issues:
        issue = issues[0]
        _fail(path, f'{issue.code} at physical line {issue.line}, position {issue.position}')


def _expect_keys(
    value: Any,
    path: str,
    *,
    required: Sequence[str] | set[str] | frozenset[str],
    optional: Sequence[str] | set[str] | frozenset[str] = (),
) -> Mapping[str, Any]:
    if type(value) is not dict:
        _fail(path, "must be an object")
    required_set = set(required)
    allowed = required_set | set(optional)
    missing = sorted(required_set - value.keys())
    if missing:
        _fail(path, f"missing required properties: {', '.join(missing)}")
    unknown = sorted(value.keys() - allowed)
    if unknown:
        _fail(path, f"unknown version-2 properties: {', '.join(unknown)}")
    return value


def _integer(value: Any, path: str, *, minimum: int = 0, maximum: int) -> int:
    if type(value) is not int:
        _fail(path, "must be an integer")
    if value < minimum or value > maximum:
        _fail(path, f"must be between {minimum} and {maximum}")
    return value


def _validate_non_lyrics(
    root: Mapping[str, Any],
    *,
    expected_video_id: str | None,
    installed_duration_ms: int | None,
) -> int | None:
    """Run shared validators for every unchanged external field."""

    generated_groups = {"metadata", "artwork", "lyrics", "analysis"} & root.keys()
    if not generated_groups:
        _fail("$", "must contain at least one generated group: metadata, artwork, lyrics, or analysis")
    video_id = validate_source(root["source"])
    if expected_video_id is not None:
        expected = contract._string(expected_video_id, "expected_video_id")
        if not contract._VIDEO_ID_RE.fullmatch(expected):
            _fail("expected_video_id", "must match [A-Za-z0-9_-]{11}")
        if video_id != expected:
            _fail("$.source.videoId", f"does not match expected video ID {expected!r}")
    contract._validate_rfc3339_utc(root["generatedAt"], "$.generatedAt")
    generator = contract._expect_keys(
        root["generator"],
        "$.generator",
        required={"name", "version"},
    )
    if contract._plain_text(generator["name"], "$.generator.name") != GENERATOR_NAME:
        _fail("$.generator.name", f"must equal {GENERATOR_NAME!r}")
    if contract._plain_text(generator["version"], "$.generator.version") != GENERATOR_VERSION:
        _fail("$.generator.version", f"must equal {GENERATOR_VERSION!r}")
    if installed_duration_ms is not None:
        installed_duration_ms = contract._integer(
            installed_duration_ms,
            "installed_duration_ms",
            minimum=1,
            maximum=contract.MAX_DURATION_MS,
        )
    if "metadata" in root:
        contract._validate_metadata(root["metadata"], "$.metadata")
    if "artwork" in root:
        contract._validate_artwork(root["artwork"], "$.artwork")
    if "analysis" in root:
        analysis_duration_ms, _ = contract._validate_analysis(root["analysis"], "$.analysis")
        recording = root["source"].get("recording")
        if recording and analysis_duration_ms is not None and abs(analysis_duration_ms - recording["durationMs"]) > 1:
            _fail("$.analysis.durationMs", "must match the native recording duration within one millisecond")
        return (recording['durationMs'] if recording else
                analysis_duration_ms if analysis_duration_ms is not None else installed_duration_ms)
    if "recording" in root["source"]:
        return root["source"]["recording"]["durationMs"]
    return installed_duration_ms


def validate_source(value: Any) -> str:
    source = _expect_keys(value, "$.source", required={"provider", "videoId"}, optional={"recording"})
    video_id = contract._validate_source_identity(
        {key: source[key] for key in ("provider", "videoId")}, "$.source")
    if "recording" not in source:
        return video_id
    path = "$.source.recording"
    native = _expect_keys(source["recording"], path,
        required={"recordingKey", "trackId", "file", "sha256", "byteCount", "durationMs"})
    track_id = _integer(native["trackId"], path + ".trackId", minimum=1, maximum=MAX_RECORDING_TRACK_ID)
    if native["recordingKey"] != f"qobuz:{track_id}":
        _fail(path + ".recordingKey", "must match the exact positive Qobuz track ID")
    filename = contract._string(native["file"], path + ".file")
    if (not filename or any(c in filename for c in ("\\", ":")) or any(ord(c) < 32 for c in filename)
            or any(part in ("", ".", "..") for part in filename.split("/"))):
        _fail(path + ".file", "must be a safe project-relative path")
    if not isinstance(native["sha256"], str) or not re.fullmatch(r"[a-f0-9]{64}", native["sha256"]):
        _fail(path + ".sha256", "must be lowercase SHA-256")
    _integer(native["byteCount"], path + ".byteCount", minimum=1, maximum=MAX_RECORDING_BYTES)
    _integer(native["durationMs"], path + ".durationMs", minimum=1, maximum=contract.MAX_DURATION_MS)
    return video_id


def word_start_order_violations(words: Sequence[Mapping[str, Any]]) -> tuple[int, ...]:
    return tuple(index for index in range(1, len(words))
                 if words[index]['startMs'] < words[index - 1]['startMs'])


def _validate_words(
    words_value: Any,
    *,
    line_path: str,
    line_text: str,
    line_start_ms: int,
    line_end_ms: int,
) -> None:
    words_path = _child(line_path, "words")
    if type(words_value) is not list or not words_value:
        _fail(words_path, "must be a nonempty array")
    expected_ranges = display_word_ranges(line_text)
    if len(words_value) != len(expected_ranges):
        _fail(words_path, "must cover every display word exactly once")
    for index, (word_value, expected_range) in enumerate(zip(words_value, expected_ranges)):
        word_path = _child(words_path, index)
        word = _expect_keys(
            word_value,
            word_path,
            required={"startUtf16", "endUtf16", "startMs", "endMs"},
        )
        start_utf16 = _integer(
            word["startUtf16"],
            _child(word_path, "startUtf16"),
            maximum=expected_range[1],
        )
        end_utf16 = _integer(
            word["endUtf16"],
            _child(word_path, "endUtf16"),
            maximum=expected_range[1],
        )
        if (start_utf16, end_utf16) != expected_range:
            _fail(word_path, "UTF-16 range must match the corresponding display word")
        start_ms = _integer(
            word["startMs"],
            _child(word_path, "startMs"),
            maximum=contract.MAX_DURATION_MS,
        )
        end_ms = _integer(
            word["endMs"],
            _child(word_path, "endMs"),
            maximum=contract.MAX_DURATION_MS,
        )
        if start_ms >= end_ms:
            _fail(_child(word_path, "endMs"), "must be greater than startMs")
        if start_ms < line_start_ms or end_ms > line_end_ms:
            _fail(word_path, "time interval must lie inside its containing line")
    reversed_starts = word_start_order_violations(words_value)
    if reversed_starts:
        _fail(_child(words_path, reversed_starts[0]), 'word starts must be nondecreasing in display order')


def _validate_owner_lyrics_policy(
    lyrics: Mapping[str, Any],
    tracks: list[Mapping[str, Any]],
    path: str,
) -> None:
    if lyrics["status"] == "complete" and "reason" in lyrics:
        _fail(_child(path, "reason"), "must be omitted when lyrics are complete")
    if lyrics["status"] == "unavailable":
        return

    originals = [(index, track) for index, track in enumerate(tracks) if track["kind"] == "original"]
    if len(originals) != 1:
        _fail(_child(path, "tracks"), "owner lyrics require exactly one original track")
    _, original = originals[0]
    original_id = original["id"]
    original_primary = contract._language_primary(original["language"])
    component_primaries = {
        contract._language_primary(language)
        for language in original.get("componentLanguages", [])
    }
    if original_primary == "mul" and (
        "componentLanguages" in original or lyrics["status"] == "complete"
    ):
        if len(component_primaries) < 2 or component_primaries & {"mul", "und", "zxx"}:
            _fail(
                _child(_child(path, "tracks"), originals[0][0]) + ".componentLanguages",
                "must identify at least two concrete material languages",
            )
    elif "componentLanguages" in original:
        _fail(
            _child(_child(path, "tracks"), originals[0][0]) + ".componentLanguages",
            "is allowed only when the original language is mul",
        )

    translations: list[tuple[int, Mapping[str, Any]]] = []
    transliterations: list[tuple[int, Mapping[str, Any]]] = []
    for index, track in enumerate(tracks):
        if track["kind"] == "original":
            continue
        track_path = _child(_child(path, "tracks"), index)
        ipa_identity = (
            contract._has_language_variant(track["language"], "fonipa")
            or any(marker in track["id"].lower() for marker in ("ipa", "phonetic"))
        )
        if ipa_identity and track["kind"] != "transliteration":
            _fail(_child(track_path, "kind"), "IPA must use kind 'transliteration'")
        if track["basedOnTrackId"] != original_id:
            _fail(_child(track_path, "basedOnTrackId"), "must reference the owner original track")
        if track["kind"] == "translation":
            translations.append((index, track))
            if track["language"] != "en":
                _fail(_child(track_path, "language"), "owner translation must be English")
        else:
            transliterations.append((index, track))
            expected_primary = "mul" if original_primary == "mul" else original_primary
            if (
                contract._language_primary(track["language"]) != expected_primary
                or not contract._has_language_variant(track["language"], "fonipa")
            ):
                _fail(
                    _child(track_path, "language"),
                    f"owner IPA must use {expected_primary}-fonipa",
                )

    if len(translations) > 1 or len(transliterations) > 1:
        _fail(_child(path, "tracks"), "owner lyrics allow at most one translation and one IPA track")
    needs_translation = original_primary != "en"
    needs_ipa = (
        bool(component_primaries - {"ar", "en"})
        if original_primary == "mul"
        else original_primary not in {"ar", "en"}
    )
    if translations and not needs_translation:
        _fail(
            _child(_child(path, "tracks"), translations[0][0]) + ".language",
            "English originals must not include an English translation",
        )
    if transliterations and not needs_ipa:
        _fail(
            _child(_child(path, "tracks"), transliterations[0][0]) + ".language",
            "this original language must not include IPA",
        )
    if lyrics["status"] == "complete" and (
        bool(translations) != needs_translation or bool(transliterations) != needs_ipa
    ):
        _fail(_child(path, "tracks"), "required owner translation or IPA track is missing")


def _validate_timing(value: Any, path: str, duration_ms: int | None) -> tuple[int, int] | None:
    timing = _expect_keys(value, path, required={"status"}, optional={"startMs", "endMs", "basis", "reason"})
    status = contract._enum(timing["status"], path + ".status", {"supported", "estimated", "none"})
    expected = {"status", "reason"} if status == "none" else {"status", "startMs", "endMs"}
    if status == "estimated":
        expected.add("basis")
    _expect_keys(timing, path, required=expected)
    if status == "none":
        contract._plain_text(timing["reason"], path + ".reason")
        return None
    if status == "estimated":
        contract._plain_text(timing["basis"], path + ".basis")
    start = _integer(timing["startMs"], path + ".startMs", maximum=contract.MAX_DURATION_MS)
    end = _integer(timing["endMs"], path + ".endMs", maximum=contract.MAX_DURATION_MS)
    if end <= start or (duration_ms is not None and end > duration_ms):
        _fail(path, "must be a positive interval within the recording")
    return start, end


def _validate_regional_lines(track: Mapping[str, Any], path: str, duration_ms: int | None) -> bool:
    if "text" in track:
        _fail(path + ".text", "is forbidden on a regional track")
    lines = contract._array(track.get("lines"), path + ".lines", nonempty=True)
    previous_start = -1
    has_words = False
    for index, value in enumerate(lines):
        line_path = f"{path}.lines[{index}]"
        line = _expect_keys(value, line_path,
            required={"text", "contentStatus", "occurrenceStatus", "timing"}, optional={"words"})
        contract._plain_text(line["text"], line_path + ".text")
        if track['kind'] == 'transliteration':
            _validate_ipa_text(line['text'], line_path + '.text')
        content = contract._enum(line["contentStatus"], line_path + ".contentStatus",
                                 {"admitted", "source-only", "disputed"})
        occurrence = contract._enum(line["occurrenceStatus"], line_path + ".occurrenceStatus",
                                    {"supported", "unknown"})
        interval = _validate_timing(line["timing"], line_path + ".timing", duration_ms)
        if interval is not None:
            if content != "admitted" or occurrence != "supported":
                _fail(line_path + ".timing", "performed timing requires admitted content and supported occurrence")
            if interval[0] < previous_start:
                _fail(line_path + ".timing", "timed rows must be nondecreasing in source order")
            previous_start = interval[0]
        if "words" not in line:
            continue
        has_words = True
        words = contract._array(line["words"], line_path + ".words", nonempty=True)
        ranges = display_word_ranges(line["text"])
        if len(words) != len(ranges):
            _fail(line_path + ".words", "must cover every display word exactly once")
        previous_word_start = interval[0] if interval else -1
        for word_index, (value, expected_range) in enumerate(zip(words, ranges)):
            word_path = f"{line_path}.words[{word_index}]"
            word = _expect_keys(value, word_path, required={"startUtf16", "endUtf16", "timing"})
            actual_range = tuple(_integer(word[key], word_path + "." + key, maximum=expected_range[1])
                                 for key in ("startUtf16", "endUtf16"))
            if actual_range != expected_range:
                _fail(word_path, "UTF-16 range must match the corresponding display word")
            word_interval = _validate_timing(word["timing"], word_path + ".timing", duration_ms)
            if word_interval is not None:
                if (interval is None or word_interval[0] < previous_word_start or word_interval[1] > interval[1]):
                    _fail(word_path + ".timing", "requires ordered placement inside its enclosing line")
                if word["timing"]["status"] == "supported" and line["timing"]["status"] != "supported":
                    _fail(word_path + ".timing", "supported words require supported line placement")
                previous_word_start = word_interval[0]
    return has_words


def _validate_lyrics_v2(
    value: Any,
    path: str,
    duration_ms: int | None,
    *,
    enforce_owner_policy: bool,
) -> None:
    lyrics = _expect_keys(
        value,
        path,
        required={"status", "tracks"},
        optional={"reason", "tokenizationVersion"},
    )
    status = contract._enum(
        lyrics["status"],
        _child(path, "status"),
        {"complete", "partial", "unavailable"},
    )
    if "reason" in lyrics:
        contract._plain_text(lyrics["reason"], _child(path, "reason"))
    if status in {"partial", "unavailable"} and "reason" not in lyrics:
        _fail(path, f"{status} lyrics require reason")
    tracks = contract._array(
        lyrics["tracks"],
        _child(path, "tracks"),
        nonempty=status != "unavailable",
    )
    if status == "unavailable" and tracks:
        _fail(_child(path, "tracks"), "must be empty when lyrics are unavailable")
    if "tokenizationVersion" in lyrics and lyrics["tokenizationVersion"] != TOKENIZATION_VERSION:
        _fail(
            _child(path, "tokenizationVersion"),
            f"must equal {TOKENIZATION_VERSION!r}",
        )

    has_words = False
    validated_tracks: list[Mapping[str, Any]] = []
    original_ids: set[str] = set()
    seen_ids: set[str] = set()
    original_timelines: dict[str, list[tuple[Mapping[str, Any], str]]] = {}
    companion_timelines: list[tuple[str, str, list[tuple[Mapping[str, Any], str]]]] = []
    for track_index, track_value in enumerate(tracks):
        track_path = _child(_child(path, "tracks"), track_index)
        track = _expect_keys(
            track_value,
            track_path,
            required={"id", "kind", "language", "format", "source"},
            optional={
                "componentLanguages",
                "script",
                "basedOnTrackId",
                "text",
                "lines",
            },
        )
        validated_tracks.append(track)
        track_id = contract._string(track["id"], _child(track_path, "id"))
        if not contract._TRACK_ID_RE.fullmatch(track_id):
            _fail(_child(track_path, "id"), "must match [a-z0-9][a-z0-9._-]{0,63}")
        if track_id in seen_ids:
            _fail(_child(track_path, "id"), "must be unique within lyrics.tracks")
        seen_ids.add(track_id)
        track_kind = contract._enum(
            track["kind"],
            _child(track_path, "kind"),
            {"original", "translation", "transliteration"},
        )
        contract._validate_language(track["language"], _child(track_path, "language"))
        if "script" in track:
            contract._validate_script(track["script"], _child(track_path, "script"))
        if "componentLanguages" in track:
            components_path = _child(track_path, "componentLanguages")
            if track_kind != "original":
                _fail(components_path, "is allowed only on original tracks")
            components = contract._array(track["componentLanguages"], components_path)
            if len(components) < 2:
                _fail(components_path, "must contain at least two languages")
            seen_components: set[str] = set()
            for component_index, component in enumerate(components):
                component_path = _child(components_path, component_index)
                contract._validate_language(component, component_path)
                normalized = component.casefold()
                if normalized in seen_components:
                    _fail(component_path, "must be unique within componentLanguages")
                seen_components.add(normalized)
        if track_kind == "original":
            if "basedOnTrackId" in track:
                _fail(_child(track_path, "basedOnTrackId"), "is forbidden on original tracks")
            original_ids.add(track_id)
        else:
            based_on = contract._string(
                track.get("basedOnTrackId"),
                _child(track_path, "basedOnTrackId"),
            )
            if not contract._TRACK_ID_RE.fullmatch(based_on):
                _fail(_child(track_path, "basedOnTrackId"), "must contain a valid track ID")
        contract._source(track["source"], _child(track_path, "source"))
        track_format = contract._enum(
            track["format"],
            _child(track_path, "format"),
            {"plain", "synced", "regional"},
        )
        if track_format == "regional":
            has_words = _validate_regional_lines(track, track_path, duration_ms) or has_words
            timeline = [(line['timing'], line['occurrenceStatus']) for line in track['lines']]
            if track_kind == 'original':
                original_timelines[track_id] = timeline
            else:
                companion_timelines.append((track_path, track['basedOnTrackId'], timeline))
            continue
        if track_format == "plain":
            if "lines" in track:
                _fail(_child(track_path, "lines"), "is forbidden on a plain track")
            contract._plain_text(track.get("text"), _child(track_path, "text"))
            if track_kind == 'transliteration':
                _validate_ipa_text(track['text'], _child(track_path, 'text'))
            continue
        if "text" in track:
            _fail(_child(track_path, "text"), "is forbidden on a synced track")
        lines = contract._array(track.get("lines"), _child(track_path, "lines"), nonempty=True)
        previous_start_ms: int | None = None
        timeline = []
        for line_index, line_value in enumerate(lines):
            line_path = _child(_child(track_path, "lines"), line_index)
            line = _expect_keys(
                line_value,
                line_path,
                required={"startMs", "endMs", "text"},
                optional={"words"},
            )
            start_ms = _integer(
                line["startMs"],
                _child(line_path, "startMs"),
                maximum=contract.MAX_DURATION_MS,
            )
            end_ms = _integer(
                line["endMs"],
                _child(line_path, "endMs"),
                maximum=contract.MAX_DURATION_MS,
            )
            if start_ms >= end_ms:
                _fail(_child(line_path, "endMs"), "must be greater than startMs")
            contract._plain_text(line["text"], _child(line_path, "text"))
            if track_kind == 'transliteration':
                _validate_ipa_text(line['text'], _child(line_path, 'text'))
            if previous_start_ms is not None and start_ms < previous_start_ms:
                _fail(
                    _child(line_path, "startMs"),
                    "must not be less than the preceding line startMs",
                )
            previous_start_ms = start_ms
            if duration_ms is not None and end_ms > duration_ms:
                _fail(_child(line_path, "endMs"), "must not exceed the validation duration")
            timeline.append(({'status': 'supported', 'startMs': start_ms, 'endMs': end_ms}, 'supported'))
            if "words" in line:
                has_words = True
                if track_kind != "original":
                    _fail(_child(line_path, "words"), "is allowed only on original lyrics")
                _validate_words(
                    line["words"],
                    line_path=line_path,
                    line_text=line["text"],
                    line_start_ms=start_ms,
                    line_end_ms=end_ms,
                )
        if track_kind == "original":
            original_timelines[track_id] = timeline
        else:
            companion_timelines.append(
                (track_path, track["basedOnTrackId"], timeline)
            )
    for track_index, track in enumerate(validated_tracks):
        if track['kind'] == 'translation' and track['language'] != 'en':
            _fail(f'{path}.tracks[{track_index}].language', 'translation must be English')
        if track['kind'] == 'transliteration' and (
                not contract._has_language_variant(track['language'], 'fonipa')
                or not track['language'].endswith('-fonipa')):
            _fail(f'{path}.tracks[{track_index}].language', 'transliteration must use a genuine variant and end with -fonipa')
        if track["kind"] != "original" and track["basedOnTrackId"] not in original_ids:
            _fail(
                _child(_child(_child(path, "tracks"), track_index), "basedOnTrackId"),
                "must reference an original track ID in this lyrics group",
            )
    if any(sum(track['kind'] == kind for track in validated_tracks) > 1
           for kind in ('translation', 'transliteration')):
        _fail(_child(path, 'tracks'), 'lyrics allow at most one English translation and one IPA track')
    for track_path, based_on, timeline in companion_timelines:
        if based_on not in original_timelines:
            _fail(_child(track_path, "lines"), "timed companions require a timed original timeline")
        original_timeline = original_timelines[based_on]
        if len(timeline) != len(original_timeline):
            _fail(_child(track_path, 'lines'), 'must preserve the exact original occurrence line count')
        for (timing, occurrence), (original_timing, original_occurrence) in zip(timeline, original_timeline):
            if occurrence == 'supported' and original_occurrence != 'supported':
                _fail(_child(track_path, 'lines'), 'cannot promote an unresolved original occurrence')
            if timing['status'] == 'none':
                continue
            if (original_timing['status'] == 'none'
                    or (timing['startMs'], timing['endMs']) != (original_timing['startMs'], original_timing['endMs'])
                    or timing['status'] == 'supported' and original_timing['status'] != 'supported'):
                _fail(_child(track_path, 'lines'), 'must inherit the exact original interval without upgrading timing quality')
    if has_words and "tokenizationVersion" not in lyrics:
        _fail(path, "tokenizationVersion is required when any line has words")
    if enforce_owner_policy:
        _validate_owner_lyrics_policy(lyrics, validated_tracks, path)
    if status == 'complete' and any(track['format'] == 'regional' for track in validated_tracks):
        _require_complete_regional_timing(lyrics)


def validate_song(
    document: Any,
    *,
    expected_video_id: str | None = None,
    installed_duration_ms: int | None = None,
) -> Mapping[str, Any]:
    """Validate an already parsed external v2 document and return it unchanged."""

    contract._ensure_finite_json_tree(document)
    root = _expect_keys(
        document,
        "$",
        required={"formatVersion", "kind", "source", "generatedAt", "generator"},
        optional={"metadata", "artwork", "lyrics", "analysis"},
    )
    if type(root["formatVersion"]) is not int or root["formatVersion"] != FORMAT_VERSION:
        _fail("$.formatVersion", f"must be integer constant {FORMAT_VERSION}")
    if root["kind"] != KIND:
        _fail("$.kind", f"must equal {KIND!r}")
    duration_ms = _validate_non_lyrics(
        root,
        expected_video_id=expected_video_id,
        installed_duration_ms=installed_duration_ms,
    )
    if 'lyrics' in root:
        _validate_lyrics_v2(root['lyrics'], '$.lyrics', duration_ms, enforce_owner_policy=False)

    return root


def validate_generated_groups(root: Mapping[str, Any], *, duration_ms: int | None = None) -> None:
    """Validate generated semantics shared by transported and installed documents."""
    if 'metadata' in root:
        contract._validate_metadata(root['metadata'], '$.metadata')
    if 'analysis' in root:
        measured_duration, _ = contract._validate_analysis(root['analysis'], '$.analysis')
        if measured_duration is not None:
            duration_ms = measured_duration
    recording = root.get('source', {}).get('recording')
    if recording:
        if duration_ms is not None and abs(duration_ms - recording['durationMs']) > 1:
            _fail('$.analysis.durationMs', 'must match the native recording duration within one millisecond')
        duration_ms = recording['durationMs']
    if 'lyrics' in root:
        _validate_lyrics_v2(root['lyrics'], '$.lyrics', duration_ms, enforce_owner_policy=False)


def load_and_validate_song_json(
    raw: bytes | bytearray | memoryview,
    *,
    expected_video_id: str | None = None,
    installed_duration_ms: int | None = None,
) -> Mapping[str, Any]:
    """Strictly parse and validate BOM-free UTF-8 external v2 bytes."""

    if not isinstance(raw, (bytes, bytearray, memoryview)):
        raise TypeError("raw song.json must be bytes-like")
    payload = bytes(raw)
    if len(payload) > contract.MAX_JSON_BYTES:
        _fail("$", f"song.json exceeds the {contract.MAX_JSON_BYTES}-byte limit")
    if payload.startswith(b"\xef\xbb\xbf"):
        _fail("$", "song.json must not contain a UTF-8 byte-order mark")
    try:
        document = parse_strict_json_bytes(payload)
    except ValueError as error:
        _fail("$", f"invalid strict JSON ({error})")
    return validate_song(
        document,
        expected_video_id=expected_video_id,
        installed_duration_ms=installed_duration_ms,
    )


def validate_lyrics(
    value: Any,
    *,
    duration_ms: int | None = None,
    enforce_owner_policy: bool = True,
) -> Mapping[str, Any]:
    """Validate a standalone external-v2 lyrics group."""

    contract._ensure_finite_json_tree(value)
    if duration_ms is not None:
        duration_ms = _integer(
            duration_ms,
            "$.analysis.durationMs",
            minimum=1,
            maximum=contract.MAX_DURATION_MS,
        )
    _validate_lyrics_v2(
        value,
        "$.lyrics",
        duration_ms,
        enforce_owner_policy=enforce_owner_policy,
    )
    return value


def require_publication_lyrics(value: Mapping[str, Any]) -> None:
    """Only complete, fully timed regional transport can leave the PC workflow."""
    if not any(track.get('format') == 'regional' for track in value.get('tracks', [])):
        return
    if value.get('status') != 'complete':
        _fail('$.lyrics.status', 'regional publication requires complete timed lyrics')
    validate_lyrics(value, enforce_owner_policy=False)


def _require_complete_regional_timing(value: Mapping[str, Any]) -> None:
    for track in value['tracks']:
        if track['format'] not in {'synced', 'regional'}:
            _fail('$.lyrics.tracks', 'regional publication requires every track to have occurrence timing')
        for line in track['lines']:
            if track['format'] == 'regional':
                if (line['contentStatus'] != 'admitted' or line['occurrenceStatus'] != 'supported'
                        or line['timing']['status'] == 'none'
                        or any(word['timing']['status'] == 'none' for word in line.get('words', []))):
                    _fail('$.lyrics.tracks', 'regional publication cannot contain unresolved or untimed rows or words')
            if track['kind'] == 'original' and not line.get('words'):
                _fail('$.lyrics.tracks', 'regional publication requires timing for every Original display word')


__all__ = [
    "FORMAT_VERSION",
    "KIND",
    "TOKENIZATION_VERSION",
    "load_and_validate_song_json",
    "validate_lyrics",
    "validate_song",
]

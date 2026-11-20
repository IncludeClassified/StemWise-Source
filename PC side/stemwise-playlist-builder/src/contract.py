"""Public current-v2 contract API and shared unchanged-field validators.

The normative declarations are the production schemas under StemWise/contracts. This
module deliberately validates the small PC-to-Android transport document, not
the larger Android-local record described later in that document.
"""

from __future__ import annotations

import base64
import binascii
import io
import math
import re
import struct
from dataclasses import dataclass
from datetime import date, datetime
from typing import Any, Mapping, Sequence
from urllib.parse import urlsplit
from uuid import UUID


from stemwise_engine.enrichment_constants import (
    FORMAT_VERSION, KIND, PROVIDER, GENERATOR_NAME, GENERATOR_VERSION, MAX_DURATION_MS,
    WAVEFORM_LEVELS_MS, CANONICAL_UTC_TIMESTAMP_PATTERN,
)

MAX_JSON_BYTES = 16 * 1024 * 1024
MAX_COVER_BYTES = 16 * 1024 * 1024
MAX_COVER_PIXELS_PER_SIDE = 4096
MAX_SINGLE_ZIP_BYTES = 32 * 1024 * 1024

STRUCTURE_MINIMUM_INDEPENDENT_SUPPORT = 2
STRUCTURE_DISTINCT_PASSING_SEQUENCES_ALLOWED = 1
STRUCTURE_INDEPENDENT_ROUTE_FAMILIES = (
    "manual-caption",
    "unprompted-asr",
    "mms-acoustic",
)

WAVEFORM_ENCODING = "s16le-min-max-rms-base64-v1"
TOOL_NAMES = frozenset(
    (
        "beat-this-full",
        "stemwise-rhythm-normalizer",
        "s-key",
        "all-in-one",
        "essentia",
        "stemwise-waveform",
    )
)

_VIDEO_ID_RE = re.compile(r"^[A-Za-z0-9_-]{11}$")
_TRACK_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
_SLUG_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
_LANGUAGE_RE = re.compile(
    r"^(?:[A-Za-z]{2,3}|[A-Za-z]{5,8}|und|x)(?:-[A-Za-z0-9]{1,8})*$"
)
_SCRIPT_RE = re.compile(r"^[A-Z][a-z]{3}$")
_TONIC_RE = re.compile(r"^[A-G](?:#|b)?$")
_ISRC_RE = re.compile(r"^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")
_DISCOGS_RE = re.compile(r"^[1-9][0-9]*$")
_WIKIDATA_RE = re.compile(r"^Q[1-9][0-9]*$")
_CANONICAL_UTC_TIMESTAMP_RE = re.compile(CANONICAL_UTC_TIMESTAMP_PATTERN)
_HTML_TAG_RE = re.compile(r"<\s*/?\s*[A-Za-z][^>]*>")
_CONTROL_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0d\x0e-\x1f\x7f]")


class ContractError(ValueError):
    """A precise validation error with a JSON-path-like location."""

    def __init__(self, path: str, message: str) -> None:
        self.path = path
        self.message = message
        super().__init__(f"{path}: {message}")


@dataclass(frozen=True)
class ArtworkInfo:
    width: int
    height: int
    mode: str


def _fail(path: str, message: str) -> None:
    raise ContractError(path, message)


def _child(path: str, name: str | int) -> str:
    if isinstance(name, int):
        return f"{path}[{name}]"
    return f"{path}.{name}"


def _object(value: Any, path: str) -> Mapping[str, Any]:
    if type(value) is not dict:
        _fail(path, "must be an object")
    return value


def _array(value: Any, path: str, *, nonempty: bool = False) -> list[Any]:
    if type(value) is not list:
        _fail(path, "must be an array")
    if nonempty and not value:
        _fail(path, "must not be empty")
    return value


def _expect_keys(
    value: Any,
    path: str,
    *,
    required: Sequence[str] | set[str] | frozenset[str],
    optional: Sequence[str] | set[str] | frozenset[str] = (),
) -> Mapping[str, Any]:
    obj = _object(value, path)
    required_set = set(required)
    allowed = required_set | set(optional)
    missing = sorted(required_set - obj.keys())
    if missing:
        _fail(path, f"missing required propert{'y' if len(missing) == 1 else 'ies'}: {', '.join(missing)}")
    unknown = sorted(obj.keys() - allowed)
    if unknown:
        _fail(path, f"unknown contract propert{'y' if len(unknown) == 1 else 'ies'}: {', '.join(unknown)}")
    return obj


def _string(value: Any, path: str, *, nonblank: bool = False) -> str:
    if type(value) is not str:
        _fail(path, "must be a string")
    if any(0xD800 <= ord(character) <= 0xDFFF for character in value):
        _fail(path, "must contain valid Unicode scalar values")
    if nonblank and not value.strip():
        _fail(path, "must not be blank")
    return value


def _plain_text(value: Any, path: str, *, nonblank: bool = True) -> str:
    text = _string(value, path, nonblank=nonblank)
    if _CONTROL_RE.search(text):
        _fail(path, "must be plain text using LF for deliberate line breaks")
    if _HTML_TAG_RE.search(text):
        _fail(path, "must be plain text, not HTML or executable markup")
    return text


def _integer(
    value: Any,
    path: str,
    *,
    minimum: int | None = None,
    maximum: int | None = None,
) -> int:
    if type(value) is not int:
        _fail(path, "must be an integer")
    if minimum is not None and value < minimum:
        _fail(path, f"must be at least {minimum}")
    if maximum is not None and value > maximum:
        _fail(path, f"must be at most {maximum}")
    return value


def _time_ms(value: Any, path: str) -> int:
    return _integer(value, path, minimum=0, maximum=MAX_DURATION_MS)


def _number(
    value: Any,
    path: str,
    *,
    minimum: float | None = None,
    maximum: float | None = None,
    minimum_exclusive: bool = False,
) -> float:
    if type(value) not in (int, float) or not math.isfinite(value):
        _fail(path, "must be a finite number")
    result = float(value)
    if minimum is not None:
        if minimum_exclusive and result <= minimum:
            _fail(path, f"must be greater than {minimum:g}")
        if not minimum_exclusive and result < minimum:
            _fail(path, f"must be at least {minimum:g}")
    if maximum is not None and result > maximum:
        _fail(path, f"must be at most {maximum:g}")
    return result


def _confidence(value: Any, path: str) -> float:
    return _number(value, path, minimum=0, maximum=1)


def _enum(value: Any, path: str, values: set[str] | frozenset[str]) -> str:
    text = _string(value, path)
    if text not in values:
        _fail(path, f"must be one of: {', '.join(sorted(values))}")
    return text


def _unique_nonblank_strings(value: Any, path: str, *, nonempty: bool = True) -> list[str]:
    items = _array(value, path, nonempty=nonempty)
    seen: set[str] = set()
    for index, item in enumerate(items):
        text = _plain_text(item, _child(path, index))
        if text in seen:
            _fail(_child(path, index), "must be unique within its array")
        seen.add(text)
    return items


def _https_url(value: Any, path: str) -> str:
    url = _string(value, path, nonblank=True)
    try:
        parsed = urlsplit(url)
        port = parsed.port
    except ValueError as error:
        _fail(path, f"must be a valid absolute HTTPS URL ({error})")
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        _fail(path, "must be an absolute HTTPS URL without embedded credentials")
    if port is not None and not 1 <= port <= 65535:
        _fail(path, "contains an invalid port")
    if any(character.isspace() for character in url):
        _fail(path, "must not contain whitespace")
    return url


def _source(value: Any, path: str) -> tuple[str, str | None]:
    obj = _expect_keys(value, path, required={"name"}, optional={"url"})
    name = _plain_text(obj["name"], _child(path, "name"))
    url = _https_url(obj["url"], _child(path, "url")) if "url" in obj else None
    return name, url


def _validate_rfc3339_utc(value: Any, path: str) -> None:
    text = _string(value, path, nonblank=True)
    if not _CANONICAL_UTC_TIMESTAMP_RE.fullmatch(text):
        _fail(path, "must be a canonical UTC timestamp with 0, 3, 6, or 9 fractional digits")
    try:
        # Python 3.10's fromisoformat rejects the contract's valid nanosecond form.
        # The lexical gate already proves the exact time and fraction vocabulary, so
        # validate the Gregorian calendar portion without normalizing the source text.
        datetime.strptime(text[:19], "%Y-%m-%dT%H:%M:%S")
    except ValueError:
        _fail(path, "must contain a real Gregorian calendar date and time")


def _validate_partial_date(value: Any, path: str) -> None:
    text = _string(value, path, nonblank=True)
    try:
        if re.fullmatch(r"[0-9]{4}", text):
            year = int(text)
            if year < 1:
                raise ValueError
        elif re.fullmatch(r"[0-9]{4}-[0-9]{2}", text):
            year, month = map(int, text.split("-"))
            date(year, month, 1)
        elif re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", text):
            date.fromisoformat(text)
        else:
            raise ValueError
    except ValueError:
        _fail(path, "must be a real ISO date in YYYY, YYYY-MM, or YYYY-MM-DD form")


def _validate_language(value: Any, path: str) -> None:
    text = _string(value, path, nonblank=True)
    if not _LANGUAGE_RE.fullmatch(text):
        _fail(path, "must be a syntactically valid BCP 47 language tag")


def _language_primary(value: str) -> str:
    """Return the case-insensitive primary BCP 47 language subtag."""

    return value.split("-", 1)[0].lower()


def _has_language_variant(value: str, variant: str) -> bool:
    # RFC 5646 variants precede extension/private-use singleton sequences.
    for part in value.split('-')[1:]:
        if len(part) == 1:
            break
        if part.lower() == variant.lower():
            return True
    return False


def _validate_script(value: Any, path: str) -> None:
    text = _string(value, path, nonblank=True)
    if not _SCRIPT_RE.fullmatch(text):
        _fail(path, "must be a four-letter ISO 15924 script code such as Arab or Latn")


def _validate_source_identity(value: Any, path: str) -> str:
    obj = _expect_keys(value, path, required={"provider", "videoId"})
    if obj["provider"] != PROVIDER:
        _fail(_child(path, "provider"), f"must equal {PROVIDER!r}")
    video_id = _string(obj["videoId"], _child(path, "videoId"))
    if not _VIDEO_ID_RE.fullmatch(video_id):
        _fail(_child(path, "videoId"), "must match [A-Za-z0-9_-]{11}")
    return video_id


def _validate_metadata(value: Any, path: str) -> None:
    obj = _expect_keys(
        value,
        path,
        required={"matchStatus", "title", "artists", "sources"},
        optional={
            "album",
            "genres",
            "description",
            "explicit",
            "recordLabel",
            "copyright",
            "credits",
            "identifiers",
            "links",
        },
    )
    _enum(obj["matchStatus"], _child(path, "matchStatus"), {"verified", "youtube-fallback"})
    _plain_text(obj["title"], _child(path, "title"))
    artists = _array(obj["artists"], _child(path, "artists"), nonempty=True)
    seen_artists: set[str] = set()
    for index, artist in enumerate(artists):
        artist_path = _child(_child(path, "artists"), index)
        artist_name = _plain_text(artist, artist_path)
        if artist_name in seen_artists:
            _fail(artist_path, "must be unique within metadata.artists")
        seen_artists.add(artist_name)

    sources = _array(obj["sources"], _child(path, "sources"), nonempty=True)
    seen_sources: set[tuple[str, str | None]] = set()
    for index, item in enumerate(sources):
        item_path = _child(_child(path, "sources"), index)
        signature = _source(item, item_path)
        if signature in seen_sources:
            _fail(item_path, "duplicates an earlier metadata source")
        seen_sources.add(signature)

    if "album" in obj:
        album_path = _child(path, "album")
        album = _expect_keys(
            obj["album"],
            album_path,
            required={"title"},
            optional={
                "type",
                "artists",
                "releaseDate",
                "trackNumber",
                "trackTotal",
                "discNumber",
                "discTotal",
            },
        )
        _plain_text(album["title"], _child(album_path, "title"))
        if "type" in album:
            _enum(
                album["type"],
                _child(album_path, "type"),
                {"album", "single", "ep", "compilation", "soundtrack", "other"},
            )
        if "artists" in album:
            album_artists = _array(album["artists"], _child(album_path, "artists"), nonempty=True)
            for index, artist in enumerate(album_artists):
                _plain_text(artist, _child(_child(album_path, "artists"), index))
        if "releaseDate" in album:
            _validate_partial_date(album["releaseDate"], _child(album_path, "releaseDate"))
        for field in ("trackNumber", "trackTotal", "discNumber", "discTotal"):
            if field in album:
                _integer(album[field], _child(album_path, field), minimum=1)
        if "trackNumber" in album and "trackTotal" in album and album["trackNumber"] > album["trackTotal"]:
            _fail(_child(album_path, "trackNumber"), "must not exceed trackTotal")
        if "discNumber" in album and "discTotal" in album and album["discNumber"] > album["discTotal"]:
            _fail(_child(album_path, "discNumber"), "must not exceed discTotal")

    if "genres" in obj:
        _unique_nonblank_strings(obj["genres"], _child(path, "genres"))
    if "description" in obj:
        _plain_text(obj["description"], _child(path, "description"))
    if "explicit" in obj and type(obj["explicit"]) is not bool:
        _fail(_child(path, "explicit"), "must be a Boolean")
    for field in ("recordLabel", "copyright"):
        if field in obj:
            _plain_text(obj[field], _child(path, field))

    if "credits" in obj:
        credits_path = _child(path, "credits")
        credits = _array(obj["credits"], credits_path, nonempty=True)
        for index, item in enumerate(credits):
            item_path = _child(credits_path, index)
            credit = _expect_keys(item, item_path, required={"name", "roles"})
            _plain_text(credit["name"], _child(item_path, "name"))
            _unique_nonblank_strings(credit["roles"], _child(item_path, "roles"))

    if "identifiers" in obj:
        identifiers_path = _child(path, "identifiers")
        identifiers = _expect_keys(
            obj["identifiers"],
            identifiers_path,
            required=set(),
            optional={
                "isrc",
                "musicBrainzRecordingId",
                "musicBrainzReleaseId",
                "discogsReleaseId",
                "wikidataId",
            },
        )
        if not identifiers:
            _fail(identifiers_path, "must contain at least one known identifier or be omitted")
        if "isrc" in identifiers:
            isrc = _string(identifiers["isrc"], _child(identifiers_path, "isrc"))
            if not _ISRC_RE.fullmatch(isrc):
                _fail(_child(identifiers_path, "isrc"), "must be normalized uppercase ISRC without hyphens")
        for field in ("musicBrainzRecordingId", "musicBrainzReleaseId"):
            if field in identifiers:
                field_path = _child(identifiers_path, field)
                text = _string(identifiers[field], field_path)
                try:
                    parsed = UUID(text)
                except (ValueError, AttributeError):
                    _fail(field_path, "must be a canonical lowercase UUID")
                if str(parsed) != text:
                    _fail(field_path, "must be a canonical lowercase UUID")
        if "discogsReleaseId" in identifiers:
            discogs = _string(identifiers["discogsReleaseId"], _child(identifiers_path, "discogsReleaseId"))
            if not _DISCOGS_RE.fullmatch(discogs):
                _fail(_child(identifiers_path, "discogsReleaseId"), "must be a positive decimal string")
        if "wikidataId" in identifiers:
            wikidata = _string(identifiers["wikidataId"], _child(identifiers_path, "wikidataId"))
            if not _WIKIDATA_RE.fullmatch(wikidata):
                _fail(_child(identifiers_path, "wikidataId"), "must be uppercase Q followed by positive digits")

    if "links" in obj:
        links_path = _child(path, "links")
        links = _array(obj["links"], links_path, nonempty=True)
        seen_links: set[tuple[str, str]] = set()
        for index, item in enumerate(links):
            item_path = _child(links_path, index)
            link = _expect_keys(item, item_path, required={"type", "url"})
            link_type = _string(link["type"], _child(item_path, "type"))
            if not _SLUG_RE.fullmatch(link_type):
                _fail(_child(item_path, "type"), "must be a stable lowercase slug")
            url = _https_url(link["url"], _child(item_path, "url"))
            signature = (link_type, url)
            if signature in seen_links:
                _fail(item_path, "duplicates an earlier metadata link")
            seen_links.add(signature)


def _validate_artwork(value: Any, path: str) -> None:
    obj = _expect_keys(value, path, required={"file", "kind", "source"})
    if obj["file"] != "cover.png":
        _fail(_child(path, "file"), "must equal 'cover.png'")
    _enum(
        obj["kind"],
        _child(path, "kind"),
        {"album-cover", "single-cover", "youtube-thumbnail", "other"},
    )
    _source(obj["source"], _child(path, "source"))


def _duration_required(duration_ms: int | None, path: str) -> int:
    if duration_ms is None:
        _fail(path, "requires analysis.durationMs")
    return duration_ms


def _validate_bpm(value: Any, path: str) -> float:
    return _number(value, path, minimum=0, maximum=600, minimum_exclusive=True)


def _validate_meter(value: Any, path: str) -> None:
    obj = _expect_keys(value, path, required={"numerator"}, optional={"denominator"})
    _integer(obj["numerator"], _child(path, "numerator"), minimum=1, maximum=32)
    if "denominator" in obj:
        denominator = _integer(obj["denominator"], _child(path, "denominator"))
        if denominator not in {1, 2, 4, 8, 16, 32}:
            _fail(_child(path, "denominator"), "must be one of 1, 2, 4, 8, 16, or 32")


def _validate_range(start: Any, end: Any, path: str, duration_ms: int | None) -> tuple[int, int]:
    start_ms = _time_ms(start, _child(path, "startMs"))
    end_ms = _time_ms(end, _child(path, "endMs"))
    if end_ms <= start_ms:
        _fail(_child(path, "endMs"), "must be greater than startMs")
    duration = _duration_required(duration_ms, path)
    if end_ms > duration:
        _fail(_child(path, "endMs"), "must not exceed analysis.durationMs")
    return start_ms, end_ms


def _validate_rhythm(value: Any, path: str, duration_ms: int | None) -> tuple[str, bool]:
    obj = _expect_keys(
        value,
        path,
        required={"status"},
        optional={"reason", "bpm", "alternativeBpms", "meter", "beats", "tempoSegments", "meterSegments"},
    )
    status = _enum(obj["status"], _child(path, "status"), {"complete", "partial", "unavailable"})
    data_fields = {"bpm", "alternativeBpms", "meter", "beats", "tempoSegments", "meterSegments"} & obj.keys()
    if status == "unavailable":
        if "reason" not in obj:
            _fail(path, "unavailable rhythm requires reason")
        _plain_text(obj["reason"], _child(path, "reason"))
        if data_fields:
            _fail(path, "unavailable rhythm must contain only status and reason")
        return status, False
    if status == "partial":
        if "reason" not in obj:
            _fail(path, "partial rhythm requires reason")
        _plain_text(obj["reason"], _child(path, "reason"))
        if not data_fields:
            _fail(path, "partial rhythm requires at least one usable value, event, or segment")
    elif "reason" in obj:
        _fail(_child(path, "reason"), "must be omitted when rhythm is complete")

    selected_bpm: float | None = None
    if "bpm" in obj:
        selected_bpm = _validate_bpm(obj["bpm"], _child(path, "bpm"))
    alternatives: list[Any] = []
    if "alternativeBpms" in obj:
        alt_path = _child(path, "alternativeBpms")
        alternatives = _array(obj["alternativeBpms"], alt_path, nonempty=True)
        previous: float | None = None
        for index, alternative in enumerate(alternatives):
            current = _validate_bpm(alternative, _child(alt_path, index))
            if selected_bpm is not None and current == selected_bpm:
                _fail(_child(alt_path, index), "must not repeat the selected bpm")
            if previous is not None and current <= previous:
                _fail(_child(alt_path, index), "must be strictly increasing and unique")
            previous = current
    if "meter" in obj:
        _validate_meter(obj["meter"], _child(path, "meter"))

    beats: list[Any] = []
    if "beats" in obj:
        beats_path = _child(path, "beats")
        beats = _array(obj["beats"], beats_path, nonempty=True)
        duration = _duration_required(duration_ms, beats_path)
        previous_time: int | None = None
        for index, item in enumerate(beats):
            item_path = _child(beats_path, index)
            beat = _expect_keys(item, item_path, required={"timeMs", "isDownbeat"}, optional={"beatInBar", "barIndex"})
            time_ms = _time_ms(beat["timeMs"], _child(item_path, "timeMs"))
            if time_ms >= duration:
                _fail(_child(item_path, "timeMs"), "must be less than analysis.durationMs")
            if previous_time is not None and time_ms <= previous_time:
                _fail(_child(item_path, "timeMs"), "must be strictly increasing and unique")
            previous_time = time_ms
            if type(beat["isDownbeat"]) is not bool:
                _fail(_child(item_path, "isDownbeat"), "must be a Boolean")
            if "beatInBar" in beat:
                beat_in_bar = _integer(beat["beatInBar"], _child(item_path, "beatInBar"), minimum=1)
                if beat["isDownbeat"] and beat_in_bar != 1:
                    _fail(_child(item_path, "beatInBar"), "must equal 1 for a downbeat")
            if "barIndex" in beat:
                _integer(beat["barIndex"], _child(item_path, "barIndex"), minimum=0)

    has_timed = bool(beats)
    for field, is_meter in (("tempoSegments", False), ("meterSegments", True)):
        if field not in obj:
            continue
        segments_path = _child(path, field)
        segments = _array(obj[field], segments_path, nonempty=True)
        has_timed = True
        previous_end: int | None = None
        for index, item in enumerate(segments):
            item_path = _child(segments_path, index)
            if is_meter:
                segment = _expect_keys(item, item_path, required={"startMs", "endMs", "numerator"}, optional={"denominator"})
            else:
                segment = _expect_keys(item, item_path, required={"startMs", "endMs", "bpm"})
            start_ms, end_ms = _validate_range(segment["startMs"], segment["endMs"], item_path, duration_ms)
            if previous_end is not None and start_ms < previous_end:
                _fail(_child(item_path, "startMs"), "must not overlap the preceding segment")
            previous_end = end_ms
            if is_meter:
                _validate_meter(
                    {key: segment[key] for key in ("numerator", "denominator") if key in segment},
                    item_path,
                )
            else:
                _validate_bpm(segment["bpm"], _child(item_path, "bpm"))

    if status == "complete":
        if selected_bpm is None or not beats:
            _fail(path, "complete rhythm requires positive bpm and a nonempty beats array")
    return status, has_timed


def _validate_tonic_mode(value: Any, path: str, *, allow_confidence: bool) -> tuple[str, str]:
    optional = {"confidence"} if allow_confidence else set()
    obj = _expect_keys(value, path, required={"tonic", "mode"}, optional=optional)
    tonic = _string(obj["tonic"], _child(path, "tonic"))
    if not _TONIC_RE.fullmatch(tonic):
        _fail(_child(path, "tonic"), "must be A through G with at most one # or b")
    mode = _enum(obj["mode"], _child(path, "mode"), {"major", "minor"})
    if "confidence" in obj:
        _confidence(obj["confidence"], _child(path, "confidence"))
    return tonic, mode


def _validate_key(value: Any, path: str) -> str:
    obj = _expect_keys(
        value,
        path,
        required={"status"},
        optional={"reason", "tonic", "mode", "confidence", "alternatives"},
    )
    status = _enum(obj["status"], _child(path, "status"), {"complete", "uncertain", "unsupported", "unavailable"})
    if status in {"unsupported", "unavailable"}:
        if "reason" not in obj:
            _fail(path, f"{status} key requires reason")
        _plain_text(obj["reason"], _child(path, "reason"))
        if obj.keys() - {"status", "reason"}:
            _fail(path, f"{status} key must contain only status and reason")
        return status
    if "tonic" not in obj or "mode" not in obj:
        _fail(path, f"{status} key requires tonic and mode")
    selected = _validate_tonic_mode(
        {field: obj[field] for field in ("tonic", "mode", "confidence") if field in obj},
        path,
        allow_confidence=True,
    )
    if status == "uncertain":
        if "reason" not in obj:
            _fail(path, "uncertain key requires reason")
        _plain_text(obj["reason"], _child(path, "reason"))
    elif "reason" in obj:
        _fail(_child(path, "reason"), "must be omitted when key is complete")
    if "alternatives" in obj:
        alternatives_path = _child(path, "alternatives")
        alternatives = _array(obj["alternatives"], alternatives_path, nonempty=True)
        seen = {selected}
        for index, alternative in enumerate(alternatives):
            alternative_path = _child(alternatives_path, index)
            signature = _validate_tonic_mode(alternative, alternative_path, allow_confidence=True)
            if signature in seen:
                _fail(alternative_path, "must be a unique alternative distinct from the selected key")
            seen.add(signature)
    return status


def _validate_structure(value: Any, path: str, duration_ms: int | None) -> tuple[str, bool]:
    obj = _expect_keys(value, path, required={"status"}, optional={"reason", "sections"})
    status = _enum(obj["status"], _child(path, "status"), {"complete", "partial", "unavailable"})
    if status == "unavailable":
        if "reason" not in obj:
            _fail(path, "unavailable structure requires reason")
        _plain_text(obj["reason"], _child(path, "reason"))
        if "sections" in obj:
            _fail(_child(path, "sections"), "must be absent when structure is unavailable")
        return status, False
    if "sections" not in obj:
        _fail(path, f"{status} structure requires sections")
    if status == "partial":
        if "reason" not in obj:
            _fail(path, "partial structure requires reason")
        _plain_text(obj["reason"], _child(path, "reason"))
    elif "reason" in obj:
        _fail(_child(path, "reason"), "must be omitted when structure is complete")
    sections_path = _child(path, "sections")
    sections = _array(obj["sections"], sections_path, nonempty=True)
    previous_end: int | None = None
    for index, item in enumerate(sections):
        item_path = _child(sections_path, index)
        section = _expect_keys(item, item_path, required={"startMs", "endMs", "label"}, optional={"confidence"})
        start_ms, end_ms = _validate_range(section["startMs"], section["endMs"], item_path, duration_ms)
        if previous_end is not None and start_ms < previous_end:
            _fail(_child(item_path, "startMs"), "must not overlap the preceding section")
        previous_end = end_ms
        _plain_text(section["label"], _child(item_path, "label"))
        if "confidence" in section:
            _confidence(section["confidence"], _child(item_path, "confidence"))
    return status, True


def _validate_tuning(value: Any, path: str) -> str:
    obj = _expect_keys(
        value,
        path,
        required={"status"},
        optional={"reason", "frequencyHz", "centsFromA440", "confidence"},
    )
    status = _enum(obj["status"], _child(path, "status"), {"complete", "uncertain", "unsupported", "unavailable"})
    if status in {"unsupported", "unavailable"}:
        if "reason" not in obj:
            _fail(path, f"{status} tuning requires reason")
        _plain_text(obj["reason"], _child(path, "reason"))
        if obj.keys() - {"status", "reason"}:
            _fail(path, f"{status} tuning must contain only status and reason")
        return status
    for field in ("frequencyHz", "centsFromA440"):
        if field not in obj:
            _fail(path, f"{status} tuning requires {field}")
    _number(obj["frequencyHz"], _child(path, "frequencyHz"), minimum=0, minimum_exclusive=True)
    _number(obj["centsFromA440"], _child(path, "centsFromA440"))
    if "confidence" in obj:
        _confidence(obj["confidence"], _child(path, "confidence"))
    if status == "uncertain":
        if "reason" not in obj:
            _fail(path, "uncertain tuning requires reason")
        _plain_text(obj["reason"], _child(path, "reason"))
    elif "reason" in obj:
        _fail(_child(path, "reason"), "must be omitted when tuning is complete")
    return status


def _validate_loudness(value: Any, path: str) -> str:
    obj = _expect_keys(
        value,
        path,
        required={"status"},
        optional={"reason", "integratedLufs", "loudnessRangeLu", "truePeakDbtp", "descriptors"},
    )
    status = _enum(obj["status"], _child(path, "status"), {"complete", "partial", "unavailable"})
    data_fields = {"integratedLufs", "loudnessRangeLu", "truePeakDbtp", "descriptors"} & obj.keys()
    if status == "unavailable":
        if "reason" not in obj:
            _fail(path, "unavailable loudness requires reason")
        _plain_text(obj["reason"], _child(path, "reason"))
        if data_fields:
            _fail(path, "unavailable loudness must contain only status and reason")
        return status
    if status == "partial":
        if "reason" not in obj:
            _fail(path, "partial loudness requires reason")
        _plain_text(obj["reason"], _child(path, "reason"))
        if not data_fields:
            _fail(path, "partial loudness requires at least one usable value or descriptor")
    elif "reason" in obj:
        _fail(_child(path, "reason"), "must be omitted when loudness is complete")
    for field in ("integratedLufs", "loudnessRangeLu", "truePeakDbtp"):
        if field in obj:
            _number(obj[field], _child(path, field))
    if status == "complete" and not {"integratedLufs", "loudnessRangeLu"} <= obj.keys():
        _fail(path, "complete loudness requires integratedLufs and loudnessRangeLu")
    if "descriptors" in obj:
        descriptors_path = _child(path, "descriptors")
        descriptors = _array(obj["descriptors"], descriptors_path, nonempty=True)
        seen_ids: set[str] = set()
        for index, item in enumerate(descriptors):
            item_path = _child(descriptors_path, index)
            descriptor = _expect_keys(item, item_path, required={"id", "value", "unit"})
            descriptor_id = _string(descriptor["id"], _child(item_path, "id"), nonblank=True)
            if descriptor_id in seen_ids:
                _fail(_child(item_path, "id"), "must be unique within descriptors")
            seen_ids.add(descriptor_id)
            _number(descriptor["value"], _child(item_path, "value"))
            _plain_text(descriptor["unit"], _child(item_path, "unit"))
    return status


def _decode_waveform_data(value: Any, path: str, frame_count: int) -> bytes:
    text = _string(value, path, nonblank=True)
    if any(character.isspace() for character in text):
        _fail(path, "must be standard Base64 without whitespace or line breaks")
    try:
        decoded = base64.b64decode(text, validate=True)
    except (binascii.Error, ValueError):
        _fail(path, "must be valid standard Base64")
    expected = frame_count * 6
    if len(decoded) != expected:
        _fail(path, f"decoded length must equal frameCount * 6 ({expected} bytes)")
    for frame_index, (minimum, maximum, rms) in enumerate(struct.iter_unpack("<hhH", decoded)):
        if minimum > maximum:
            _fail(path, f"frame {frame_index} minimum sample exceeds maximum sample")
        if rms > 32767:
            _fail(path, f"frame {frame_index} RMS magnitude exceeds 32767")
    return decoded


def _validate_waveform(value: Any, path: str, duration_ms: int | None) -> tuple[str, bool]:
    obj = _expect_keys(value, path, required={"status"}, optional={"reason", "encoding", "levels"})
    status = _enum(obj["status"], _child(path, "status"), {"complete", "partial", "unavailable"})
    if status == "unavailable":
        if "reason" not in obj:
            _fail(path, "unavailable waveform requires reason")
        _plain_text(obj["reason"], _child(path, "reason"))
        if "encoding" in obj or "levels" in obj:
            _fail(path, "unavailable waveform must contain only status and reason")
        return status, False
    if "encoding" not in obj or obj["encoding"] != WAVEFORM_ENCODING:
        _fail(_child(path, "encoding"), f"must equal {WAVEFORM_ENCODING!r}")
    if "levels" not in obj:
        _fail(path, f"{status} waveform requires levels")
    _duration_required(duration_ms, path)
    if status == "partial":
        if "reason" not in obj:
            _fail(path, "partial waveform requires reason")
        _plain_text(obj["reason"], _child(path, "reason"))
    elif "reason" in obj:
        _fail(_child(path, "reason"), "must be omitted when waveform is complete")
    levels_path = _child(path, "levels")
    levels = _array(obj["levels"], levels_path, nonempty=True)
    if status == "complete" and len(levels) != 3:
        _fail(levels_path, "complete waveform requires all three levels exactly once")
    if status == "partial" and len(levels) not in (1, 2):
        _fail(levels_path, "partial waveform requires one or two levels")
    seen_durations: set[int] = set()
    for index, item in enumerate(levels):
        item_path = _child(levels_path, index)
        level = _expect_keys(item, item_path, required={"frameDurationMs", "frameCount", "data"})
        frame_duration = _integer(level["frameDurationMs"], _child(item_path, "frameDurationMs"))
        if frame_duration not in WAVEFORM_LEVELS_MS:
            _fail(_child(item_path, "frameDurationMs"), "must equal 5, 50, or 250")
        if frame_duration in seen_durations:
            _fail(_child(item_path, "frameDurationMs"), "must be unique within waveform.levels")
        seen_durations.add(frame_duration)
        frame_count = _integer(level["frameCount"], _child(item_path, "frameCount"), minimum=1)
        expected_frame_count = (duration_ms + frame_duration - 1) // frame_duration
        if frame_count != expected_frame_count:
            _fail(
                _child(item_path, "frameCount"),
                "must equal ceil(analysis.durationMs / frameDurationMs) "
                f"({expected_frame_count})",
            )
        _decode_waveform_data(level["data"], _child(item_path, "data"), frame_count)
    if status == "complete" and seen_durations != WAVEFORM_LEVELS_MS:
        _fail(levels_path, "complete waveform must contain exactly the 5, 50, and 250 ms levels")
    return status, True


def _validate_diagnostics(value: Any, path: str, duration_ms: int | None) -> bool:
    obj = _expect_keys(value, path, required={"allInOneRhythm"})
    diag_path = _child(path, "allInOneRhythm")
    diagnostic = _expect_keys(
        obj["allInOneRhythm"],
        diag_path,
        required={"status"},
        optional={"reason", "bpm", "beatsMs", "downbeatsMs"},
    )
    status = _enum(diagnostic["status"], _child(diag_path, "status"), {"complete", "partial"})
    if status == "partial":
        if "reason" not in diagnostic:
            _fail(diag_path, "partial All-In-One rhythm diagnostic requires reason")
        _plain_text(diagnostic["reason"], _child(diag_path, "reason"))
    elif "reason" in diagnostic:
        _fail(_child(diag_path, "reason"), "must be omitted when diagnostic is complete")
    if "bpm" in diagnostic:
        _validate_bpm(diagnostic["bpm"], _child(diag_path, "bpm"))
    beats: list[int] = []
    has_timed = False
    for field in ("beatsMs", "downbeatsMs"):
        if field not in diagnostic:
            continue
        field_path = _child(diag_path, field)
        values = _array(diagnostic[field], field_path, nonempty=True)
        duration = _duration_required(duration_ms, field_path)
        previous: int | None = None
        parsed_values: list[int] = []
        for index, value_item in enumerate(values):
            current = _time_ms(value_item, _child(field_path, index))
            if current >= duration:
                _fail(_child(field_path, index), "must be less than analysis.durationMs")
            if previous is not None and current <= previous:
                _fail(_child(field_path, index), "must be strictly increasing and unique")
            previous = current
            parsed_values.append(current)
        if field == "beatsMs":
            beats = parsed_values
        has_timed = True
    if "downbeatsMs" in diagnostic:
        beats_set = set(beats)
        for index, downbeat in enumerate(diagnostic["downbeatsMs"]):
            if downbeat not in beats_set:
                _fail(_child(_child(diag_path, "downbeatsMs"), index), "must also appear in beatsMs")
    usable = "bpm" in diagnostic or bool(diagnostic.get("beatsMs")) or bool(diagnostic.get("downbeatsMs"))
    if status == "complete" and ("bpm" not in diagnostic or not diagnostic.get("beatsMs")):
        _fail(diag_path, "complete diagnostic requires positive bpm and nonempty beatsMs")
    if status == "partial" and not usable:
        _fail(diag_path, "partial diagnostic requires at least one usable bpm or event array")
    return has_timed


def _validate_tool_runs(value: Any, path: str) -> dict[str, Mapping[str, Any]]:
    runs = _array(value, path)
    if len(runs) != len(TOOL_NAMES):
        _fail(path, "must contain exactly one record for each required analysis tool")
    by_name: dict[str, Mapping[str, Any]] = {}
    for index, item in enumerate(runs):
        item_path = _child(path, index)
        run = _expect_keys(
            item,
            item_path,
            required={"name", "status"},
            optional={"version", "model", "profile", "warnings", "reason"},
        )
        name = _string(run["name"], _child(item_path, "name"))
        if name not in TOOL_NAMES:
            _fail(_child(item_path, "name"), "is not a required analysis tool name")
        if name in by_name:
            _fail(_child(item_path, "name"), "duplicates an earlier tool run")
        by_name[name] = run
        status = _enum(run["status"], _child(item_path, "status"), {"complete", "failed", "skipped"})
        if status in {"complete", "failed"} and "version" not in run:
            _fail(item_path, f"{status} tool runs require version")
        if "version" in run:
            _plain_text(run["version"], _child(item_path, "version"))
        for field in ("model", "profile"):
            if field in run:
                _plain_text(run[field], _child(item_path, field))
        if "warnings" in run:
            _unique_nonblank_strings(run["warnings"], _child(item_path, "warnings"))
        if status in {"failed", "skipped"}:
            if "reason" not in run:
                _fail(item_path, f"{status} tool runs require reason")
            _plain_text(run["reason"], _child(item_path, "reason"))
        elif "reason" in run:
            _fail(_child(item_path, "reason"), "must be omitted when a tool run is complete")
    if set(by_name) != TOOL_NAMES:
        missing = sorted(TOOL_NAMES - set(by_name))
        _fail(path, f"missing required analysis tool run(s): {', '.join(missing)}")
    return by_name


def _validate_analysis(value: Any, path: str) -> tuple[int | None, bool]:
    obj = _expect_keys(
        value,
        path,
        required={"status", "rhythm", "key", "structure", "tuning", "loudness", "waveform", "toolRuns"},
        optional={"durationMs", "diagnostics"},
    )
    status = _enum(obj["status"], _child(path, "status"), {"complete", "partial", "unavailable"})
    duration_ms: int | None = None
    if "durationMs" in obj:
        duration_ms = _integer(obj["durationMs"], _child(path, "durationMs"), minimum=1, maximum=MAX_DURATION_MS)
    elif status != "unavailable":
        _fail(_child(path, "durationMs"), "is required unless analysis is wholly unavailable")

    rhythm_status, rhythm_timed = _validate_rhythm(obj["rhythm"], _child(path, "rhythm"), duration_ms)
    key_status = _validate_key(obj["key"], _child(path, "key"))
    structure_status, structure_timed = _validate_structure(obj["structure"], _child(path, "structure"), duration_ms)
    tuning_status = _validate_tuning(obj["tuning"], _child(path, "tuning"))
    loudness_status = _validate_loudness(obj["loudness"], _child(path, "loudness"))
    waveform_status, waveform_timed = _validate_waveform(obj["waveform"], _child(path, "waveform"), duration_ms)
    diagnostic_timed = False
    if "diagnostics" in obj:
        diagnostic_timed = _validate_diagnostics(obj["diagnostics"], _child(path, "diagnostics"), duration_ms)
    tool_runs = _validate_tool_runs(obj["toolRuns"], _child(path, "toolRuns"))

    feature_statuses = (
        rhythm_status,
        key_status,
        structure_status,
        tuning_status,
        loudness_status,
        waveform_status,
    )
    usable = (
        rhythm_status in {"complete", "partial"},
        key_status in {"complete", "uncertain"},
        structure_status in {"complete", "partial"},
        tuning_status in {"complete", "uncertain"},
        loudness_status in {"complete", "partial"},
        waveform_status in {"complete", "partial"},
    )
    if status == "complete" and any(item != "complete" for item in feature_statuses):
        _fail(path, "complete analysis requires every canonical feature to be complete")
    if status == "partial" and (not any(usable) or all(item == "complete" for item in feature_statuses)):
        _fail(path, "partial analysis requires at least one usable feature and at least one non-complete feature")
    if status == "unavailable" and any(usable):
        _fail(path, "unavailable analysis cannot contain a usable canonical feature")
    if status == "complete":
        incomplete_tools = sorted(name for name, run in tool_runs.items() if run["status"] != "complete")
        if incomplete_tools:
            _fail(
                _child(path, "toolRuns"),
                "complete analysis requires complete authority tool runs: " + ", ".join(incomplete_tools),
            )

    def require_complete_tool(condition: bool, tool_name: str, feature_name: str) -> None:
        if condition and tool_runs[tool_name]["status"] != "complete":
            _fail(
                _child(path, "toolRuns"),
                f"{feature_name} requires a complete {tool_name!r} authority run",
            )

    rhythm = obj["rhythm"]
    rhythm_beats = rhythm.get("beats") or []
    require_complete_tool(bool(rhythm_beats), "beat-this-full", "canonical beat/downbeat events")
    normalizer_fields_present = bool(
        {"bpm", "alternativeBpms", "meter", "tempoSegments", "meterSegments"}
        & rhythm.keys()
    ) or any("beatInBar" in beat or "barIndex" in beat for beat in rhythm_beats)
    require_complete_tool(
        normalizer_fields_present,
        "stemwise-rhythm-normalizer",
        "normalized tempo, meter, or bar-position data",
    )
    if (
        tool_runs["stemwise-rhythm-normalizer"]["status"] == "complete"
        and tool_runs["beat-this-full"]["status"] != "complete"
    ):
        _fail(
            _child(path, "toolRuns"),
            "a complete 'stemwise-rhythm-normalizer' run requires a complete 'beat-this-full' input run",
        )

    require_complete_tool(
        key_status in {"complete", "uncertain"},
        "s-key",
        "a usable canonical key",
    )
    require_complete_tool(
        structure_status in {"complete", "partial"},
        "all-in-one",
        "usable canonical structure",
    )
    require_complete_tool(
        tuning_status in {"complete", "uncertain"},
        "essentia",
        "usable canonical tuning",
    )
    require_complete_tool(
        loudness_status in {"complete", "partial"},
        "essentia",
        "usable canonical loudness",
    )
    require_complete_tool(
        waveform_status in {"complete", "partial"},
        "stemwise-waveform",
        "a usable canonical waveform",
    )
    require_complete_tool(
        "diagnostics" in obj,
        "all-in-one",
        "the All-In-One rhythm diagnostic",
    )
    descriptors = obj["loudness"].get("descriptors")
    if descriptors:
        essentia_run = tool_runs["essentia"]
        if essentia_run["status"] != "complete" or essentia_run.get("profile") != "stemwise-v1":
            _fail(
                _child(path, "toolRuns"),
                "loudness descriptors require a complete Essentia run with profile 'stemwise-v1'",
            )
    return duration_ms, rhythm_timed or structure_timed or waveform_timed or diagnostic_timed


def _ensure_finite_json_tree(value: Any, path: str = "$") -> None:
    if type(value) is float and not math.isfinite(value):
        _fail(path, "JSON numbers must be finite")
    if type(value) is str:
        _string(value, path)
    elif type(value) is list:
        for index, item in enumerate(value):
            _ensure_finite_json_tree(item, _child(path, index))
    elif type(value) is dict:
        for key, item in value.items():
            if type(key) is not str:
                _fail(path, "object property names must be strings")
            _string(key, path)
            _ensure_finite_json_tree(item, _child(path, key))


def validate_song(
    document: Any,
    *,
    expected_video_id: str | None = None,
    installed_duration_ms: int | None = None,
) -> Mapping[str, Any]:
    """Validate an already parsed current-v2 enrichment document."""

    from stemwise_engine.song_enrichment_v2 import validate_song as validate_song_v2

    return validate_song_v2(
        document,
        expected_video_id=expected_video_id,
        installed_duration_ms=installed_duration_ms,
    )


def load_and_validate_song_json(
    raw: bytes | bytearray | memoryview,
    *,
    expected_video_id: str | None = None,
    installed_duration_ms: int | None = None,
) -> Mapping[str, Any]:
    """Strictly parse and validate current-v2 ``song.json`` bytes."""

    from stemwise_engine.song_enrichment_v2 import load_and_validate_song_json as load_v2

    return load_v2(
        raw,
        expected_video_id=expected_video_id,
        installed_duration_ms=installed_duration_ms,
    )


def validate_lyrics(
    value: Any,
    *,
    duration_ms: int | None = None,
    enforce_owner_policy: bool = True,
) -> Mapping[str, Any]:
    """Validate a standalone current-v2 lyrics group."""

    from stemwise_engine.song_enrichment_v2 import validate_lyrics as validate_lyrics_v2

    return validate_lyrics_v2(
        value,
        duration_ms=duration_ms,
        enforce_owner_policy=enforce_owner_policy,
    )


def validate_cover_png(raw: bytes | bytearray | memoryview) -> ArtworkInfo:
    """Validate bounded, static, fully decodable root ``cover.png`` bytes."""

    if not isinstance(raw, (bytes, bytearray, memoryview)):
        raise TypeError("cover.png must be bytes-like")
    payload = bytes(raw)
    if not payload:
        _fail("cover.png", "must not be empty")
    if len(payload) > MAX_COVER_BYTES:
        _fail("cover.png", f"exceeds the {MAX_COVER_BYTES}-byte limit")
    signature = b"\x89PNG\r\n\x1a\n"
    if not payload.startswith(signature):
        _fail("cover.png", "must contain real PNG bytes")

    offset = len(signature)
    chunks: list[bytes] = []
    header_dimensions: tuple[int, int] | None = None
    while offset < len(payload):
        if len(payload) - offset < 12:
            _fail("cover.png", "contains a truncated PNG chunk")
        length = struct.unpack(">I", payload[offset : offset + 4])[0]
        chunk_type = payload[offset + 4 : offset + 8]
        end = offset + 12 + length
        if end > len(payload):
            _fail("cover.png", "contains a truncated PNG chunk payload")
        chunk_data = payload[offset + 8 : offset + 8 + length]
        chunks.append(chunk_type)
        if chunk_type == b"IHDR":
            if length != 13:
                _fail("cover.png", "IHDR chunk must contain exactly 13 bytes")
            header_dimensions = struct.unpack(">II", chunk_data[:8])
            if header_dimensions[0] < 1 or header_dimensions[1] < 1:
                _fail("cover.png", "dimensions must be positive")
            if (
                header_dimensions[0] > MAX_COVER_PIXELS_PER_SIDE
                or header_dimensions[1] > MAX_COVER_PIXELS_PER_SIDE
            ):
                _fail(
                    "cover.png",
                    f"dimensions must not exceed {MAX_COVER_PIXELS_PER_SIDE} x {MAX_COVER_PIXELS_PER_SIDE}",
                )
        offset = end
        if chunk_type == b"IEND":
            if offset != len(payload):
                _fail("cover.png", "must not contain bytes after the IEND chunk")
            break
    if not chunks or chunks[0] != b"IHDR" or chunks.count(b"IHDR") != 1:
        _fail("cover.png", "must contain exactly one leading IHDR chunk")
    if b"IDAT" not in chunks or chunks.count(b"IEND") != 1 or chunks[-1] != b"IEND":
        _fail("cover.png", "must contain image data and exactly one final IEND chunk")
    if b"acTL" in chunks:
        _fail("cover.png", "animated PNG is not allowed")

    try:
        from PIL import Image, UnidentifiedImageError
    except ImportError as error:  # pragma: no cover - deployment dependency guard
        raise RuntimeError("Pillow is required to validate cover.png") from error
    try:
        with Image.open(io.BytesIO(payload)) as image:
            if image.format != "PNG":
                _fail("cover.png", "decoded format must be PNG")
            width, height = image.size
            mode = image.mode
            if getattr(image, "is_animated", False) or getattr(image, "n_frames", 1) != 1:
                _fail("cover.png", "animated PNG is not allowed")
            image.verify()
        with Image.open(io.BytesIO(payload)) as image:
            image.load()
    except ContractError:
        raise
    except (UnidentifiedImageError, OSError, SyntaxError, ValueError) as error:
        _fail("cover.png", f"must fully decode as a valid static PNG ({error})")
    if width < 1 or height < 1:
        _fail("cover.png", "dimensions must be positive")
    if width > MAX_COVER_PIXELS_PER_SIDE or height > MAX_COVER_PIXELS_PER_SIDE:
        _fail("cover.png", f"dimensions must not exceed {MAX_COVER_PIXELS_PER_SIDE} x {MAX_COVER_PIXELS_PER_SIDE}")
    if header_dimensions != (width, height):  # defensive: Pillow and IHDR must agree
        _fail("cover.png", "decoded dimensions do not agree with IHDR")
    return ArtworkInfo(width=width, height=height, mode=mode)


def validate_package_files(
    song_json: bytes | bytearray | memoryview,
    cover_png: bytes | bytearray | memoryview | None = None,
    *,
    expected_video_id: str | None = None,
) -> tuple[Mapping[str, Any], ArtworkInfo | None]:
    """Validate a child payload and exact artwork/``cover.png`` pairing."""

    document = load_and_validate_song_json(song_json, expected_video_id=expected_video_id)
    has_artwork = "artwork" in document
    if has_artwork != (cover_png is not None):
        _fail("cover.png", "must be present if and only if the artwork group is present")
    artwork = validate_cover_png(cover_png) if cover_png is not None else None
    return document, artwork


__all__ = [
    "ArtworkInfo",
    "ContractError",
    "FORMAT_VERSION",
    "GENERATOR_NAME",
    "GENERATOR_VERSION",
    "KIND",
    "MAX_COVER_BYTES",
    "MAX_COVER_PIXELS_PER_SIDE",
    "MAX_DURATION_MS",
    "MAX_JSON_BYTES",
    "MAX_SINGLE_ZIP_BYTES",
    "PROVIDER",
    "TOOL_NAMES",
    "WAVEFORM_ENCODING",
    "WAVEFORM_LEVELS_MS",
    "load_and_validate_song_json",
    "validate_lyrics",
    "validate_cover_png",
    "validate_package_files",
    "validate_song",
]


def require_usable_analysis(analysis, duration):
    validated_duration, _ = _validate_analysis(analysis, '$.analysis')
    if validated_duration != duration or analysis['status'] == 'unavailable':
        raise ValueError('Canonical analysis needs matching duration and at least one usable feature')

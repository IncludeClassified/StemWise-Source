"""Conservative, cached MusicBrainz recording verification.

This adapter deliberately verifies fewer songs rather than conflating an
original, live take, remix, remaster, edit, or cover.  YouTube remains the
fallback source unless one unique MusicBrainz recording satisfies every
identity check below.
"""

from __future__ import annotations

import hashlib
from contextlib import ExitStack
from stemwise_engine.provider_operations import (bounded_provider_operation, fetch_bytes, read_response, current_budget,
                                 optional_provider_stage, ProviderDeadline, ProviderStageDeadline, ProviderCancelled, retry)
import ipaddress
import json
import re
import time
import unicodedata
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterable

from stemwise_engine.common import atomic_write_json, diagnostic_code, read_json, utc_now, validate_video_id, exclusive_process_lock, ProcessLockBusyError, WorkerRetirementError
from stemwise_engine.contract import _validate_metadata


MUSICBRAINZ_BASE = "https://musicbrainz.org/ws/2/"
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
MIN_REQUEST_INTERVAL_SECONDS = 1.1
DEFAULT_CACHE_SECONDS = 180 * 24 * 60 * 60
MIN_SEARCH_SCORE = 95


_BRACKETED_RE = re.compile(r"\s*[\(\[\{]([^\)\]\}]+)[\)\]\}]\s*")
_SPACE_RE = re.compile(r"\s+")
_ARTIST_SPLIT_RE = re.compile(
    r"\s*(?:,|;|\s+&\s+|\s+(?:feat(?:uring)?\.?|ft\.?|with)\s+)\s*",
    re.IGNORECASE,
)
_PRESENTATION_RE = re.compile(
    r"\b(?:official|music\s+video|lyric(?:s)?(?:\s+video)?|visuali[sz]er|"
    r"audio\s+only|official\s+audio|official\s+video|hd|4k)\b",
    re.IGNORECASE,
)
_SEMANTIC_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("radio-edit", re.compile(r"\bradio\s+(?:edit|mix|version)\b", re.IGNORECASE)),
    ("original-mix", re.compile(r"\boriginal\s+mix\b", re.IGNORECASE)),
    ("re-recording", re.compile(r"\bre[ -]?record(?:ed|ing)?\b", re.IGNORECASE)),
    ("sped-up", re.compile(r"\bsped[ -]?up\b", re.IGNORECASE)),
    ("nightcore", re.compile(r"\bnightcore\b", re.IGNORECASE)),
    ("slowed", re.compile(r"\bslowed(?:\s+down)?\b", re.IGNORECASE)),
    ("remaster", re.compile(r"\bremaster(?:ed|ing)?\b", re.IGNORECASE)),
    ("remix", re.compile(r"\bremix(?:ed)?\b", re.IGNORECASE)),
    ("live", re.compile(r"\blive(?:\s+(?:at|from|in|on)\b)?", re.IGNORECASE)),
    ("acoustic", re.compile(r"\bacoustic\b", re.IGNORECASE)),
    ("instrumental", re.compile(r"\binstrumental\b", re.IGNORECASE)),
    ("karaoke", re.compile(r"\bkaraoke\b", re.IGNORECASE)),
    ("demo", re.compile(r"\bdemo\b", re.IGNORECASE)),
    ("extended", re.compile(r"\bextended(?:\s+(?:mix|edit|version))?\b", re.IGNORECASE)),
    ("edit", re.compile(r"\bedit\b", re.IGNORECASE)),
    ("mono", re.compile(r"\bmono(?:phonic)?\b", re.IGNORECASE)),
    ("stereo", re.compile(r"\bstereo(?:phonic)?\b", re.IGNORECASE)),
    ("cover", re.compile(r"\bcover(?:ed)?(?:\s+by\b)?", re.IGNORECASE)),
)
_GENERIC_QUALIFIER_WORDS = {
    "audio",
    "edition",
    "official",
    "the",
    "version",
}
_ISRC_RE = re.compile(r"^[A-Z]{2}[A-Z0-9]{3}\d{7}$")


def _fold(value: str) -> str:
    value = unicodedata.normalize("NFKD", value)
    value = "".join(character for character in value if not unicodedata.combining(character))
    value = value.casefold().replace("&", " and ")
    value = re.sub(r"[^\w]+", " ", value, flags=re.UNICODE)
    return _SPACE_RE.sub(" ", value).strip()


def _semantic_categories(text: str) -> set[str]:
    return {name for name, pattern in _SEMANTIC_PATTERNS if pattern.search(text)}


def _qualifier_signatures(text: str) -> set[str]:
    """Canonicalize a qualifier while retaining distinguishing details.

    The extra terms are intentional: ``2011 remaster`` must not silently match
    ``2020 remaster``, and one named remix must not match another.
    """
    categories = _semantic_categories(text)
    if not categories:
        return set()
    folded = _fold(text)
    replacements = (
        (r"\bremastered\b|\bremastering\b", "remaster"),
        (r"\bremixed\b", "remix"),
        (r"\bre recorded\b|\brerecorded\b", "re recording"),
        (r"\bsped up\b", "sped-up"),
        (r"\bslowed down\b", "slowed"),
        (r"\bradio (?:mix|version)\b", "radio edit"),
    )
    for pattern, replacement in replacements:
        folded = re.sub(pattern, replacement, folded)
    category_words = {part for name in categories for part in name.split("-")}
    details = sorted(
        token
        for token in folded.split()
        if token not in category_words and token not in _GENERIC_QUALIFIER_WORDS
    )
    detail = ",".join(details)
    return {f"{category}:{detail}" if detail else category for category in categories}


@dataclass(frozen=True)
class TitleEvidence:
    base: str
    qualifiers: frozenset[str]


def title_evidence(title: str, disambiguation: str | None = None) -> TitleEvidence:
    """Separate presentation/version labels from a normalized recording title."""
    working = unicodedata.normalize("NFKC", title).replace("\u2013", "-").replace("\u2014", "-")
    qualifiers: set[str] = set()

    def replace_group(match: re.Match[str]) -> str:
        group = match.group(1)
        semantic = _qualifier_signatures(group)
        if semantic:
            qualifiers.update(semantic)
            return " "
        if _PRESENTATION_RE.search(group):
            return " "
        return match.group(0)

    working = _BRACKETED_RE.sub(replace_group, working)

    # YouTube commonly appends presentation/version information after a dash.
    # Only remove the suffix when a real title remains on the left.
    dash = re.match(r"^(.*?)\s+-\s+(.+)$", working)
    if dash and dash.group(1).strip():
        suffix = dash.group(2).strip()
        semantic = _qualifier_signatures(suffix)
        if semantic or _PRESENTATION_RE.search(suffix):
            qualifiers.update(semantic)
            working = dash.group(1)

    # Handle an unbracketed trailing qualifier, but never turn a song actually
    # titled "Live" or "Acoustic" into an empty title.
    trailing = re.search(
        r"\s+(?P<q>(?:\d{4}\s+)?(?:remaster(?:ed)?|remix(?:ed)?|live|acoustic|"
        r"instrumental|karaoke|demo|radio\s+(?:edit|mix|version)|extended\s+(?:mix|edit|version)|"
        r"mono|stereo|sped[ -]?up|slowed(?:\s+down)?|nightcore|cover)(?:\s+version)?)\s*$",
        working,
        re.IGNORECASE,
    )
    if trailing and working[: trailing.start()].strip():
        qualifiers.update(_qualifier_signatures(trailing.group("q")))
        working = working[: trailing.start()]

    if disambiguation:
        qualifiers.update(_qualifier_signatures(disambiguation))
    return TitleEvidence(base=_fold(working), qualifiers=frozenset(qualifiers))


def _normalized_artists(values: Iterable[str]) -> tuple[str, ...]:
    result: list[str] = []
    for value in values:
        for part in _ARTIST_SPLIT_RE.split(value):
            normalized = _fold(re.sub(r"\s+-\s+topic\s*$", "", part, flags=re.IGNORECASE))
            if normalized and normalized not in result:
                result.append(normalized)
    return tuple(result)


def _artist_credit_names(recording: dict[str, Any]) -> list[str]:
    result: list[str] = []
    for credit in recording.get("artist-credit") or []:
        if not isinstance(credit, dict):
            continue
        name = credit.get("name")
        if not isinstance(name, str):
            artist = credit.get("artist")
            name = artist.get("name") if isinstance(artist, dict) else None
        if isinstance(name, str) and name.strip() and name.strip() not in result:
            result.append(name.strip())
    return result


def _duration_tolerance_ms(duration_ms: int) -> int:
    # Deliberately tight: enough for codec padding, not enough to confuse a
    # radio edit, music-video intro, or alternate performance.
    return min(2_500, max(1_500, int(duration_ms * 0.005 + 0.5)))


def _candidate_matches(
    candidate: dict[str, Any],
    source_title: TitleEvidence,
    source_artists: tuple[str, ...],
    duration_ms: int,
    *,
    require_score: bool,
) -> bool:
    if require_score:
        score = candidate.get("score")
        if not isinstance(score, (int, float)) or isinstance(score, bool) or score < MIN_SEARCH_SCORE:
            return False
    candidate_title = candidate.get("title")
    length = candidate.get("length")
    if not isinstance(candidate_title, str) or not isinstance(length, int) or isinstance(length, bool):
        return False
    disambiguation = (
        candidate.get("disambiguation") if isinstance(candidate.get("disambiguation"), str) else None
    )
    # Any nonempty but unrecognized MusicBrainz disambiguation is evidence that
    # this recording differs in a way the adapter cannot safely interpret.
    if disambiguation and disambiguation.strip() and not _qualifier_signatures(disambiguation):
        return False
    candidate_evidence = title_evidence(candidate_title, disambiguation)
    if candidate_evidence != source_title:
        return False
    candidate_artists = _normalized_artists(_artist_credit_names(candidate))
    if not source_artists or set(candidate_artists) != set(source_artists):
        return False
    return abs(length - duration_ms) <= _duration_tolerance_ms(duration_ms)


def _lucene_phrase(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')


class MusicBrainzClient:
    """Tiny no-key web-service client with persistent cache and throttling."""

    def __init__(
        self,
        cache_dir: Path,
        *,
        generator_version: str = "1",
        cache_seconds: int = DEFAULT_CACHE_SECONDS,
        min_interval_seconds: float = MIN_REQUEST_INTERVAL_SECONDS,
        opener: Callable[..., Any] | None = None,
        clock: Callable[[], float] = time.time,
        sleeper: Callable[[float], None] = time.sleep,
        rate_state_path: Path | None = None,
    ):
        self.cache_dir = cache_dir
        self.cache_seconds = cache_seconds
        self.min_interval_seconds = min_interval_seconds
        self.opener = opener
        self.clock = clock
        self.sleeper = sleeper
        self.user_agent = f"StemwisePC/{generator_version} (personal local metadata enrichment)"
        self.rate_state_path = rate_state_path or Path(__file__).resolve().parents[2] / 'runtime/provider-state/musicbrainz-rate.json'
        self.cache_dir.mkdir(parents=True, exist_ok=True)

    @property
    def _rate_state_path(self) -> Path:
        return self.rate_state_path

    def _cache_path(self, url: str) -> Path:
        digest = hashlib.sha256(url.encode("utf-8")).hexdigest()
        return self.cache_dir / digest[:2] / f"{digest}.json"

    def _cached(self, url: str) -> Any | None:
        path = self._cache_path(url)
        if not path.is_file():
            return None
        try:
            envelope = read_json(path)
            fetched = float(envelope["fetchedEpoch"])
            if envelope.get("url") != url or self.clock() - fetched > self.cache_seconds:
                return None
            return envelope["payload"]
        except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError):
            return None

    def _throttle(self) -> float:
        budget = current_budget()
        with ExitStack() as owner:
            while True:
                budget.remaining()
                try:
                    owner.enter_context(exclusive_process_lock(self._rate_state_path.with_suffix('.lock'), wait_seconds=0))
                    break
                except ProcessLockBusyError:
                    budget.pause(.05)
            persisted = 0.0
            try:
                state = read_json(self._rate_state_path)
                if isinstance(state, dict):
                    persisted = float(state.get('lastRequestEpoch') or 0.0)
            except (OSError, ValueError, TypeError, json.JSONDecodeError):
                pass
            reservation = max(self.clock(), persisted + self.min_interval_seconds)
            if reservation - self.clock() >= budget.remaining():
                from stemwise_engine.provider_operations import ProviderDeadline
                raise ProviderDeadline('musicbrainz-reservation-deadline')
            atomic_write_json(self._rate_state_path, {'lastRequestEpoch': reservation}, pretty=False)
        while (delay := reservation - self.clock()) > 0:
            self.sleeper(min(delay, budget.remaining(), .05))
        budget.remaining()
        return reservation

    @bounded_provider_operation
    def get_json(self, endpoint: str, parameters: dict[str, str]) -> Any:
        query = urllib.parse.urlencode(parameters)
        url = urllib.parse.urljoin(MUSICBRAINZ_BASE, endpoint) + "?" + query
        cached = self._cached(url)
        if cached is not None:
            return cached
        request_epoch = None
        def reserve_attempt():
            nonlocal request_epoch
            request_epoch = self._throttle()
        request = urllib.request.Request(
            url,
            headers={"Accept": "application/json", "User-Agent": self.user_agent},
        )
        if self.opener is None:
            payload = fetch_bytes(url, max_bytes=MAX_RESPONSE_BYTES,
                headers={"Accept": "application/json", "User-Agent": self.user_agent}, before_attempt=reserve_attempt)
        else:
            def attempt():
                reserve_attempt()
                with self.opener(request, timeout=min(30, current_budget().remaining())) as response:
                    return read_response(response, MAX_RESPONSE_BYTES)
            payload = retry(attempt)
        if len(payload) > MAX_RESPONSE_BYTES:
            raise RuntimeError("MusicBrainz response exceeded the safe size limit")
        decoded = json.loads(payload.decode("utf-8"))
        atomic_write_json(
            self._cache_path(url),
            {
                "cacheVersion": 1,
                "fetchedAt": utc_now(),
                "fetchedEpoch": request_epoch,
                "url": url,
                "payload": decoded,
            },
            pretty=False,
        )
        return decoded

    def search_recordings(self, title: str, artist: str) -> list[dict[str, Any]]:
        query = f'recording:"{_lucene_phrase(title)}" AND artist:"{_lucene_phrase(artist)}"'
        response = self.get_json("recording/", {"query": query, "fmt": "json", "limit": "25"})
        if not isinstance(response, dict) or not isinstance(response.get("recordings"), list):
            raise RuntimeError("MusicBrainz recording search returned an unexpected shape")
        return [item for item in response["recordings"] if isinstance(item, dict)]

    def lookup_recording(self, recording_id: str) -> dict[str, Any]:
        response = self.get_json(
            f"recording/{recording_id}",
            {
                "inc": "artist-credits+releases+release-groups+isrcs+genres+url-rels",
                "fmt": "json",
            },
        )
        if not isinstance(response, dict) or response.get("id") != recording_id:
            raise RuntimeError("MusicBrainz recording lookup returned an unexpected shape")
        return response


def _release_type(release: dict[str, Any]) -> str | None:
    group = release.get("release-group")
    if not isinstance(group, dict):
        return None
    primary = _fold(str(group.get("primary-type") or ""))
    secondary = {_fold(str(item)) for item in group.get("secondary-types") or []}
    if "soundtrack" in secondary:
        return "soundtrack"
    if "compilation" in secondary:
        return "compilation"
    return {
        "album": "album",
        "single": "single",
        "ep": "ep",
        "broadcast": "other",
        "other": "other",
    }.get(primary)


def _choose_release(recording: dict[str, Any], fallback_album: Any) -> tuple[dict[str, Any] | None, str | None]:
    releases = [
        release
        for release in recording.get("releases") or []
        if isinstance(release, dict) and str(release.get("status") or "Official") == "Official"
    ]
    if not releases:
        return None, None
    wanted_title = ""
    if isinstance(fallback_album, dict) and isinstance(fallback_album.get("title"), str):
        wanted_title = _fold(fallback_album["title"])
    if wanted_title:
        releases = [release for release in releases if _fold(str(release.get("title") or "")) == wanted_title]
        if not releases:
            return None, None

    group_ids = {
        group.get("id")
        for release in releases
        if isinstance((group := release.get("release-group")), dict) and isinstance(group.get("id"), str)
    }
    # A recording appearing on multiple release groups does not identify which
    # album the YouTube item represents.  Keep the YouTube album instead.
    if len(group_ids) != 1:
        return None, None
    releases.sort(key=lambda item: (str(item.get("date") or "9999"), str(item.get("id") or "")))
    chosen = releases[0]
    title = chosen.get("title")
    if not isinstance(title, str) or not title.strip():
        return None, None
    album: dict[str, Any] = {"title": title.strip()}
    release_type = _release_type(chosen)
    if release_type:
        album["type"] = release_type
    album_artists = _artist_credit_names(chosen)
    if album_artists:
        album["artists"] = album_artists
    date = chosen.get("date")
    if isinstance(date, str) and re.fullmatch(r"\d{4}(?:-\d{2}(?:-\d{2})?)?", date):
        album["releaseDate"] = date
    release_id = chosen.get("id") if len(releases) == 1 else None
    return album, release_id if isinstance(release_id, str) else None


def _merge_unique_records(existing: Any, additions: list[dict[str, str]]) -> list[dict[str, str]]:
    result: list[dict[str, str]] = []
    signatures: set[tuple[tuple[str, str], ...]] = set()
    for item in [*(existing if isinstance(existing, list) else []), *additions]:
        if not isinstance(item, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in item.items()):
            continue
        signature = tuple(sorted(item.items()))
        if signature not in signatures:
            signatures.add(signature)
            result.append(dict(item))
    return result


def metadata_sha256(metadata: dict[str, Any]) -> str:
    return hashlib.sha256(json.dumps(
        metadata, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")).hexdigest()


def validate_public_source_url(
    url: str, *, staged_source_ids: Iterable[str]
) -> urllib.parse.SplitResult:
    if not isinstance(url, str) or len(url) > 2_048 or any(
        character.isspace() or unicodedata.category(character) in {"Cc", "Cf"}
        for character in url
    ):
        raise ValueError("metadata-review-evidence-url-invalid")
    try:
        parsed = urllib.parse.urlsplit(url)
        host = (parsed.hostname or "").lower().rstrip(".")
        port = parsed.port
    except ValueError as error:
        raise ValueError("metadata-review-evidence-url-invalid") from error
    if (
        parsed.scheme not in {"http", "https"} or not host
        or parsed.username is not None or parsed.password is not None
        or port is not None and not 1 <= port <= 65535
        or "\\" in url
    ):
        raise ValueError("metadata-review-evidence-url-invalid")
    try:
        public_host = ipaddress.ip_address(host).is_global
    except ValueError:
        try:
            domain = host.encode("idna").decode("ascii")
        except UnicodeError as error:
            raise ValueError("metadata-review-evidence-url-invalid") from error
        public_host = (
            "." in domain and not re.fullmatch(r"[0-9.]+", domain)
            and not domain.endswith((".local", ".localhost", ".internal"))
            and all(re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label) for label in domain.split("."))
        )
    if not public_host:
        raise ValueError("metadata-review-evidence-url-not-public")
    if host == "youtu.be" or host.endswith(".youtu.be") or host == "youtube.com" or host.endswith(".youtube.com"):
        parts = [part for part in parsed.path.split("/") if part]
        ids = urllib.parse.parse_qs(parsed.query).get("v", [])
        if host.removeprefix("www.") == "youtu.be":
            ids = parts if len(parts) == 1 else []
        elif not ids and len(parts) == 2 and parts[0] in {"shorts", "embed", "live"}:
            ids = [parts[1]]
        if len(set(ids)) != 1 or ids[0] not in staged_source_ids:
            raise ValueError("metadata-review-evidence-video-not-staged")
        validate_video_id(ids[0])
    return parsed


def apply_reviewed_metadata(
    metadata: dict[str, Any],
    review: dict[str, Any],
    *,
    video_id: str,
    sources_sha256: str,
    staged_source_ids: Iterable[str],
) -> dict[str, Any]:
    """Apply only an explicit recording review; the caller validates its author and sources.

    The original metadata and staged evidence must still match. The exact resulting metadata
    is accepted on repeat validation; matching only its title is not an idempotence proof.
    """
    validate_video_id(video_id)
    fields = {
        "formatVersion", "videoId", "originalMetadataSha256", "sourcesSha256",
        "title", "artists", "evidenceUrls", "recordingIdentityConfirmed",
    }
    if (
        not isinstance(review, dict) or set(review) != fields
        or type(review["formatVersion"]) is not int or review["formatVersion"] != 1
        or review["videoId"] != video_id or review["recordingIdentityConfirmed"] is not True
        or not isinstance(sources_sha256, str) or re.fullmatch(r"[0-9a-f]{64}", sources_sha256) is None
        or review["sourcesSha256"] != sources_sha256
        or not isinstance(review["originalMetadataSha256"], str)
        or re.fullmatch(r"[0-9a-f]{64}", review["originalMetadataSha256"]) is None
    ):
        raise ValueError("metadata-review-binding-invalid")
    urls = review["evidenceUrls"]
    if not isinstance(urls, list) or not 1 <= len(urls) <= 20:
        raise ValueError("metadata-review-evidence-invalid")
    staged = set(staged_source_ids)
    evidence_sources: list[dict[str, str]] = []
    for url in urls:
        parsed = validate_public_source_url(url, staged_source_ids=staged)
        # External transport permits HTTPS source URLs only. HTTP evidence remains in the
        # bound private review, without fabricating an HTTPS equivalent.
        evidence_sources.append({
            "name": "Reviewed recording metadata", **({"url": url} if parsed.scheme == "https" else {})
        })
    package_url = f"https://www.youtube.com/watch?v={video_id}"
    corrected = {
        "matchStatus": "verified",
        "title": review["title"],
        "artists": review["artists"],
        "sources": _merge_unique_records([{"name": "YouTube", "url": package_url}], evidence_sources),
        "links": [{"type": "youtube", "url": package_url}],
    }
    _validate_metadata(corrected, "$.metadata")
    if metadata == corrected:
        return metadata
    if metadata_sha256(metadata) != review["originalMetadataSha256"]:
        raise ValueError("metadata-review-original-changed")
    if metadata.get("title") == corrected["title"] and metadata.get("artists") == corrected["artists"]:
        return metadata
    # Old release/recording identifiers and credits were not reviewed for this correction.
    return corrected


def enrich_metadata(
    metadata: dict[str, Any],
    duration_ms: int,
    client: MusicBrainzClient,
    *, diagnostics_dir: Path | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    stage = None
    try:
        with optional_provider_stage() as stage:
            return _enrich_metadata(metadata, duration_ms, client, diagnostics_dir=diagnostics_dir)
    except ProviderStageDeadline as error:
        if stage is None:
            raise
        if stage.parent is not None:
            stage.parent.remaining()
        return _metadata_unavailable(metadata, error, diagnostics_dir)


def _metadata_unavailable(metadata, error, diagnostics_dir):
    diagnostic = {}
    if diagnostics_dir is not None:
        from stemwise_engine.private_diagnostics import retain_error
        try:
            diagnostic['privateDiagnostics'] = [retain_error(diagnostics_dir / 'metadata-research.failure.private.txt',
                error, context={'stage': 'metadata-research', 'provider': 'MusicBrainz'})]
        except (OSError, ValueError) as diagnostic_error:
            diagnostic['diagnosticError'] = diagnostic_code(diagnostic_error)
    return dict(metadata), {
        'status': 'unavailable',
        'reason': f'MusicBrainz research was unavailable ({diagnostic_code(error)}).', **diagnostic}


def _enrich_metadata(metadata, duration_ms, client, *, diagnostics_dir=None):
    """Return verified metadata only for one unique exact recording match.

    Ordinary lookup/network failures retain the untouched YouTube object with a
    compact diagnostic; cancellation and unconfirmed worker retirement propagate.
    """
    fallback = dict(metadata)
    if fallback.get("matchStatus") != "youtube-fallback":
        return fallback, {"status": "skipped", "reason": "Metadata was already verified."}
    title = fallback.get("title")
    artists = fallback.get("artists")
    if not isinstance(title, str) or not isinstance(artists, list) or not all(isinstance(item, str) for item in artists):
        return fallback, {"status": "no-match", "reason": "YouTube identity fields were incomplete."}
    source_artists = _normalized_artists(artists)
    if not source_artists or any(value in {"unknown artist", "various artists"} for value in source_artists):
        return fallback, {"status": "no-match", "reason": "Artist evidence was not specific enough."}
    source_title = title_evidence(title)
    if not source_title.base or duration_ms <= 0:
        return fallback, {"status": "no-match", "reason": "Title or duration evidence was incomplete."}

    try:
        # Query the semantic base rather than YouTube presentation text such as
        # "Official Video" or a version label already checked independently.
        candidates = client.search_recordings(source_title.base, artists[0])
        matches: dict[str, dict[str, Any]] = {}
        for candidate in candidates:
            candidate_id = candidate.get("id")
            if (
                isinstance(candidate_id, str)
                and _candidate_matches(
                    candidate,
                    source_title,
                    source_artists,
                    duration_ms,
                    require_score=True,
                )
            ):
                matches[candidate_id] = candidate
        if len(matches) != 1:
            reason = "No exact recording matched all evidence."
            if len(matches) > 1:
                reason = "Multiple exact recordings remained ambiguous."
            return fallback, {
                "status": "no-match",
                "reason": reason,
                "exactCandidateCount": len(matches),
            }
        recording_id = next(iter(matches))
        recording = client.lookup_recording(recording_id)
        if not _candidate_matches(
            recording,
            source_title,
            source_artists,
            duration_ms,
            require_score=False,
        ):
            return fallback, {
                "status": "no-match",
                "reason": "The authoritative recording lookup did not preserve the search evidence.",
            }
        enriched = dict(fallback)
        enriched["matchStatus"] = "verified"
        enriched["title"] = str(recording["title"]).strip()
        enriched["artists"] = _artist_credit_names(recording)
        recording_url = f"https://musicbrainz.org/recording/{recording_id}"
        enriched["sources"] = _merge_unique_records(
            fallback.get("sources"),
            [{"name": "MusicBrainz recording", "url": recording_url}],
        )
        enriched["links"] = _merge_unique_records(
            fallback.get("links"),
            [{"type": "musicbrainz-recording", "url": recording_url}],
        )
        identifiers = dict(fallback.get("identifiers") or {})
        identifiers["musicBrainzRecordingId"] = recording_id
        isrcs = {
            value.upper().replace("-", "")
            for value in recording.get("isrcs") or []
            if isinstance(value, str) and _ISRC_RE.fullmatch(value.upper().replace("-", ""))
        }
        if len(isrcs) == 1:
            identifiers["isrc"] = next(iter(isrcs))
        album, release_id = _choose_release(recording, fallback.get("album"))
        if album:
            enriched["album"] = album
        if release_id:
            identifiers["musicBrainzReleaseId"] = release_id
        enriched["identifiers"] = identifiers

        genres = []
        for item in recording.get("genres") or []:
            name = item.get("name") if isinstance(item, dict) else None
            if isinstance(name, str) and name.strip() and name.strip() not in genres:
                genres.append(name.strip())
        if genres:
            enriched["genres"] = genres[:12]
        _validate_metadata(enriched, "$.metadata")
        return enriched, {
            "status": "verified",
            "provider": "MusicBrainz",
            "recordingId": recording_id,
        }

    except (ProviderDeadline, ProviderCancelled, WorkerRetirementError):
        raise
    except Exception as error:
        return _metadata_unavailable(fallback, error, diagnostics_dir)

"""Reference display-word tokenization for the external enrichment v2 contract."""

from __future__ import annotations

import hashlib
import json
import unicodedata
from stemwise_engine.lyrics_unicode import classification
from stemwise_engine.lyrics_normalize import physical_lines
from dataclasses import dataclass
from typing import Any, Mapping


def optimal_edit_owners(expected, actual, *, budget=None):
    """All owners on optimal unit-cost edit paths, never a one-best tie break."""
    from array import array
    from stemwise_engine.acoustic_inputs import CURRENT_BUDGET
    budget = budget or CURRENT_BUDGET
    n, m = len(expected), len(actual)
    # Compact owner arrays and their bounded row/set overhead share the matrix guard.
    budget.require(frames=n+1, states=m+1, arrays=4, item_bytes=4,
        retained_bytes=(n+m+2)*256)
    width = m+1
    forward = array('I', [0]) * ((n+1)*width)
    backward = array('I', [0]) * ((n+1)*width)
    for i in range(n+1):
        forward[i*width] = i
        backward[i*width+m] = n-i
    for j in range(m+1):
        forward[j] = j
        backward[n*width+j] = m-j
    for i in range(1, n+1):
        for j in range(1, m+1):
            k = i*width+j
            forward[k] = min(forward[k-width]+1, forward[k-1]+1,
                forward[k-width-1]+(expected[i-1] != actual[j-1]))
    for i in range(n-1, -1, -1):
        for j in range(m-1, -1, -1):
            k = i*width+j
            backward[k] = min(backward[k+width]+1, backward[k+1]+1,
                backward[k+width+1]+(expected[i] != actual[j]))
    cost = forward[-1]
    matches = [array('I') for _ in expected]
    deletions, insertions, changed, substitutions = set(), set(), set(), set()
    for i in range(n+1):
        for j in range(m+1):
            k = i*width+j
            if i < n and forward[k]+1+backward[k+width] == cost:
                deletions.add(i)
            if j < m and forward[k]+1+backward[k+1] == cost:
                insertions.add(j)
                # Insertions can belong to either adjacent target occurrence.
                changed.update(index for index in (i-1, i) if 0 <= index < n)
            if i < n and j < m:
                different = expected[i] != actual[j]
                if forward[k]+different+backward[k+width+1] == cost:
                    if different:
                        changed.add(i)
                        substitutions.add(i)
                        insertions.add(j)
                    else:
                        matches[i].append(j)
    return {"cost": cost, "matches": matches, "deleted": deletions, "substituted": substitutions,
            "affectedExpected": changed | deletions, "affectedActual": insertions}


TOKENIZATION_VERSION = "stemwise.display-words.v2"

_CONNECTORS = frozenset(("'", "\u2019", "\u02bc", "-", "\u2010", "\u2011"))
_JOIN_CONTROLS = frozenset(("\u200c", "\u200d"))
_PREPEND_SINGLETONS = frozenset(
    (0x06DD, 0x070F, 0x08E2, 0x0D4E, 0x110BD, 0x110CD,
     0x113D1, 0x1193F, 0x11941, 0x11A3A, 0x11D46, 0x11F02)
)
_CANONICAL_CONNECTORS = {
    "'": "'",
    "\u2019": "'",
    "\u02bc": "'",
    "-": "-",
    "\u2010": "-",
    "\u2011": "-",
}


@dataclass(frozen=True)
class _Cluster:
    text: str
    start_utf16: int
    end_utf16: int


def _utf16_length(text: str) -> int:
    return len(text.encode("utf-16-le")) // 2


def _is_extend(character: str) -> bool:
    codepoint = ord(character)
    return (
        bool(classification(ord(character))[0] & 4)
        or 0xFE00 <= codepoint <= 0xFE0F
        or 0xE0100 <= codepoint <= 0xE01EF
        or 0x1F3FB <= codepoint <= 0x1F3FF
    )


def _is_prepend(character: str) -> bool:
    codepoint = ord(character)
    return (
        0x0600 <= codepoint <= 0x0605
        or codepoint in _PREPEND_SINGLETONS
        or 0x0890 <= codepoint <= 0x0891
        or 0x111C2 <= codepoint <= 0x111C3
        or 0x11A84 <= codepoint <= 0x11A89
    )


def _clusters(text: str) -> list[_Cluster]:
    """Return the conservative grapheme units needed by the frozen tokenizer."""

    output: list[_Cluster] = []
    current = ""
    current_start = 0
    offset = 0
    regional_indicators = 0
    for character in text:
        width = _utf16_length(character)
        codepoint = ord(character)
        regional = 0x1F1E6 <= codepoint <= 0x1F1FF
        joins_previous = bool(current) and (
            _is_extend(character)
            or _is_prepend(current[-1])
            or current[-1:] in _JOIN_CONTROLS
            or character in _JOIN_CONTROLS
            or (regional and regional_indicators % 2 == 1)
            or (current == "\r" and character == "\n")
        )
        if not joins_previous and current:
            output.append(_Cluster(current, current_start, offset))
            current = ""
            regional_indicators = 0
        if not current:
            current_start = offset
        current += character
        offset += width
        regional_indicators = regional_indicators + 1 if regional else 0
    if current:
        output.append(_Cluster(current, current_start, offset))
    return output


def _word_script(cluster: _Cluster) -> frozenset[str] | None:
    for character in cluster.text:
        flags, script = classification(ord(character))
        if flags & 1:
            return script
    return None


def _is_word(cluster: _Cluster) -> bool:
    return any(classification(ord(character))[0] & 1 for character in cluster.text)


def display_word_ranges(text: str) -> list[tuple[int, int]]:
    """Return logical ``[startUtf16, endUtf16)`` display-word ranges.

    Letter/number runs are words. Common apostrophes and hyphens remain internal
    only between same-script runs; significant script changes split a word.
    Han, Hiragana, and Katakana share one CJK run.
    """

    clusters = _clusters(text)
    ranges: list[tuple[int, int]] = []
    start: int | None = None
    end = 0
    script: frozenset[str] | None = None
    index = 0
    while index < len(clusters):
        cluster = clusters[index]
        if cluster.text in _CONNECTORS and start is not None and index + 1 < len(clusters):
            following = clusters[index + 1]
            following_script = _word_script(following) if _is_word(following) else None
            if _is_word(following) and not (script and following_script and not script & following_script):
                end = cluster.end_utf16
                index += 1
                continue
        if _is_word(cluster):
            current_script = _word_script(cluster)
            if start is not None and script and current_script and not script & current_script:
                ranges.append((start, end))
                start = None
                script = None
            if start is None:
                start = cluster.start_utf16
            end = cluster.end_utf16
            if current_script:
                script = script & current_script if script else current_script
            index += 1
            continue
        if start is not None:
            ranges.append((start, end))
            start = None
            script = None
        index += 1
    if start is not None:
        ranges.append((start, end))
    return ranges


def display_words(text: str) -> list[tuple[int, int, str]]:
    """Return UTF-16 ranges with their exact display substrings."""

    encoded = text.encode("utf-16-le")
    return [
        (start, end, encoded[start * 2 : end * 2].decode("utf-16-le"))
        for start, end in display_word_ranges(text)
    ]


def _lexical_character(character: str) -> str:
    if character in _CANONICAL_CONNECTORS:
        return _CANONICAL_CONNECTORS[character]
    return _simple_case(character)


def _simple_case(character: str) -> str:
    # Expanding a character (ß -> ss) changes a lexical word, not just its casing.
    folded, lowered = character.casefold(), character.lower()
    return folded if len(folded) == 1 else lowered if len(lowered) == 1 else character


def language_case(text: str, language: str | None = None) -> str:
    """Fold casing using the declared language while preserving lexical marks."""
    text = unicodedata.normalize("NFC", text)
    if language and language.split('-')[0].lower() in {'tr', 'az'}:
        text = text.replace('I', 'ı').replace('İ', 'i')
    return ''.join(_simple_case(character) for character in text)


def _lexical_word(surface: str, *, language: str | None = None) -> str:
    return unicodedata.normalize("NFC", ''.join(
        _lexical_character(character) for character in language_case(surface, language)))


def lexical_line_tokens(text: str, *, language: str | None = None) -> tuple[tuple[str, ...], ...]:
    """Return the frozen lexical tokens for each physical display line."""

    if not isinstance(text, str):
        raise TypeError("lyrics text must be a string")
    physical = physical_lines(text)
    return tuple(
        tuple(_lexical_word(surface, language=language) for _, _, surface in display_words(line))
        for line in physical
    )


def lexical_authority_sha256(original: Mapping[str, Any]) -> str:
    """Hash the canonical lexical authority, excluding display decoration."""

    if not isinstance(original, Mapping) or original.get("kind") != "original":
        raise ValueError("lexical authority must be one original lyrics track")
    track_id = original.get("id")
    language = original.get("language")
    text = original.get("text")
    script = original.get("script")
    components = original.get("componentLanguages", [])
    if not isinstance(track_id, str) or not track_id:
        raise ValueError("lexical authority track ID is missing")
    if not isinstance(language, str) or not language:
        raise ValueError("lexical authority language is missing")
    if not isinstance(text, str):
        raise ValueError("lexical authority text is missing")
    if script is not None and (not isinstance(script, str) or not script):
        raise ValueError("lexical authority script is malformed")
    if not isinstance(components, list) or any(
        not isinstance(value, str) or not value for value in components
    ):
        raise ValueError("lexical authority component languages are malformed")
    canonical = {
        "authorityVersion": 2,
        "tokenizationVersion": TOKENIZATION_VERSION,
        "trackId": track_id,
        "language": language,
        "script": script,
        "componentLanguages": components,
        "lines": lexical_line_tokens(text, language=language),
    }
    raw = json.dumps(
        canonical,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


__all__ = [
    "TOKENIZATION_VERSION",
    "display_word_ranges",
    "display_words",
    "lexical_authority_sha256",
    "lexical_line_tokens",
    "language_case",
    "optimal_edit_owners",
]

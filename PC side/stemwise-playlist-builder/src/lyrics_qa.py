"""Text-free universal editorial QA for Stemwise lyrics.

The linter is intentionally conservative: it identifies structural and Unicode
facts mechanically, but never rewrites lyric text or claims that a dictionary,
subtitle, or language guess proves what was sung. Human/Codex editorial review
resolves warnings against the exact recording and language profile.
"""

from __future__ import annotations

import hashlib
import html
import re
import unicodedata
from collections import Counter
from pathlib import Path
from typing import Any

from stemwise_engine.common import (atomic_write_json, read_json, parse_strict_json_bytes, utc_now,
    diagnostic_code, companion_language_roles, canonical_lyrics_language)


STANDARD_VERSION = 1
QA_REPORT_VERSION = 3

# This is deliberately a small orthographic profile, not a spell-checker.  It
# defines only facts that are safe to verify mechanically.  Word choice,
# dialect, and pronunciation still require the recording/editorial passes.
from stemwise_engine.model_registry import LANGUAGES
_LANGUAGE_PROFILES: dict[str, dict[str, Any]] = {
    language: {**entry['editorial'], 'scripts': set(entry['editorial']['scripts'])}
    for language, entry in LANGUAGES.items()
}

_BIDI_CONTROLS = {
    0x061C,  # ARABIC LETTER MARK
    0x200E,
    0x200F,
    *range(0x202A, 0x202F),
    *range(0x2066, 0x206A),
}
_ZERO_WIDTH = {0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF}
_PRESENTATION_RANGES = ((0xFB50, 0xFDFF), (0xFE70, 0xFEFF))
_TIMESTAMP_RE = re.compile(
    r"(?:^|\s)(?:\[|<)?(?:\d{1,3}:)?[0-5]?\d:[0-5]\d(?:[.:]\d{1,3})?(?:\]|>)?(?:\s|$)"
)
_HTML_RE = re.compile(r"<\s*/?\s*[A-Za-z][^>]*>")
_HTML_ENTITY_RE = re.compile(r"&(?:#\d+|#x[0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]+);")
_ROLE_LABEL_RE = re.compile(
    r"^\s*(?:[\[(]\s*)?(?:verse|chorus|bridge|intro|outro|pre-chorus|hook|refrain|couplet|"
    r"nakarat|k[ıi]ta|b[oö]l[uü]m|strophe|korus|rangkap|"
    r"المقطع|الكورس|اللازمة)(?:\s+\d+)?(?:\s*[\])])?\s*[:.-]?\s*$",
    re.IGNORECASE,
)
_CAPTION_ARTIFACT_RE = re.compile(
    r"^\s*(?:[\[(]\s*)?(?:music|müzik|musique|musik|applause|تصفيق|موسيقى)"
    r"(?:\s*[\])])?\s*[:.-]?\s*$",
    re.IGNORECASE,
)
_PROBABLE_CREDIT_RE = re.compile(r"^[A-ZÇĞİÖŞÜÂÎÛ][A-ZÇĞİÖŞÜÂÎÛ'’.-]*(?:\s+[A-ZÇĞİÖŞÜÂÎÛ][A-ZÇĞİÖŞÜÂÎÛ'’.-]*){2,5}$")
_COMMON_MOJIBAKE_RE = re.compile(
    "(?:Ã[\u0080-\u00bf]|Â[\u0080-\u00bf]|â(?:[\u0080-\u00bf]|\u20ac|\u2122)|Ø[\u0080-\u00bf]|Ù[\u0080-\u00bf])"
)
_DECORATIVE = frozenset("✿❀❁❃❋★☆◆◇●○■□♥♡♫♪♬※•")
_VISUAL_ELONGATION_RE = re.compile(r"ـ|([!?؟،,.…])\1+")
_LETTER_ELONGATION_RE = re.compile(r"([A-Za-zÀ-ÖØ-öø-ÿ])\1{2,}", re.IGNORECASE)
_REPETITION_NOTATION_RE = re.compile(r"(?:\(\s*[x×]\s*\d+\s*\)|\b[x×]\s*\d+\b)", re.IGNORECASE)
_EDITORIAL_HONORIFIC_RE = re.compile(
    r"\(\s*(?:pbuh|p\.b\.u\.h\.?|saw|saaw|sav|peace be upon him)\s*\)",
    re.IGNORECASE,
)
_PROBABLE_EVERY_DAY_RE = re.compile(
    r"(?:\beveryday\s+(?:is|was|I['’]ll|we|you|they|he|she|it)\b|\bfocus\s+everyday\b)",
    re.IGNORECASE,
)
_SPACE_BEFORE_PUNCTUATION_RE = re.compile(r"\s+[،,.;:!?؟]", re.UNICODE)
_FRENCH_INVALID_SPACE_BEFORE_PUNCTUATION_RE = re.compile(r"\s+[،,.؟]", re.UNICODE)


def _major_script(character: str) -> str | None:
    code = ord(character)
    ranges = (
        ("Arab", 0x0600, 0x06FF),
        ("Arab", 0x0750, 0x077F),
        ("Arab", 0x08A0, 0x08FF),
        ("Armn", 0x0530, 0x058F),
        ("Armn", 0xFB13, 0xFB17),
        ("Hebr", 0x0590, 0x05FF),
        ("Latn", 0x0041, 0x024F),
        ("Latn", 0x1E00, 0x1EFF),
        ("Grek", 0x0370, 0x03FF),
        ("Grek", 0x1F00, 0x1FFF),
        ("Cyrl", 0x0400, 0x052F),
        ("Deva", 0x0900, 0x097F),
        ("Beng", 0x0980, 0x09FF),
        ("Guru", 0x0A00, 0x0A7F),
        ("Gujr", 0x0A80, 0x0AFF),
        ("Orya", 0x0B00, 0x0B7F),
        ("Taml", 0x0B80, 0x0BFF),
        ("Telu", 0x0C00, 0x0C7F),
        ("Knda", 0x0C80, 0x0CFF),
        ("Mlym", 0x0D00, 0x0D7F),
        ("Sinh", 0x0D80, 0x0DFF),
        ("Thai", 0x0E00, 0x0E7F),
        ("Laoo", 0x0E80, 0x0EFF),
        ("Tibt", 0x0F00, 0x0FFF),
        ("Mymr", 0x1000, 0x109F),
        ("Geor", 0x10A0, 0x10FF),
        ("Ethi", 0x1200, 0x137F),
        ("Hang", 0x1100, 0x11FF),
        ("Hira", 0x3040, 0x309F),
        ("Kana", 0x30A0, 0x30FF),
        ("Hang", 0xAC00, 0xD7AF),
        ("Hani", 0x3400, 0x4DBF),
        ("Hani", 0x4E00, 0x9FFF),
    )
    for script, start, end in ranges:
        if start <= code <= end:
            return script
    return None


def _issue(
    code: str,
    severity: str,
    line_number: int | None = None,
    column: int | None = None,
    character: str | None = None,
) -> dict[str, Any]:
    result: dict[str, Any] = {"code": code, "severity": severity}
    if line_number is not None:
        result["lineNumber"] = line_number
    if column is not None:
        result["column"] = column
    if character is not None:
        result["codePoint"] = f"U+{ord(character):04X}"
        result["unicodeName"] = unicodedata.name(character, "UNNAMED")
    return result


def _detached_mark(line: str, index: int) -> bool:
    character = line[index]
    if not (unicodedata.combining(character) or unicodedata.category(character) in {"Mn", "Me"}):
        return False
    if index == 0:
        return True
    previous_index = index-1
    while previous_index >= 0 and ord(line[previous_index]) in {0x200C, 0x200D}:
        previous_index -= 1
    if previous_index < 0:
        return True
    previous = line[previous_index]
    return previous.isspace() or unicodedata.category(previous).startswith(("P", "Z", "C"))


def _valid_join_control(line: str, index: int, profile: dict | None) -> bool:
    if profile is None or index == 0 or index+1 == len(line):
        return False
    before, after = index-1, index+1
    while before >= 0 and unicodedata.category(line[before]) in {'Mn', 'Me'}:
        before -= 1
    while after < len(line) and unicodedata.category(line[after]) in {'Mn', 'Me'}:
        after += 1
    if before < 0 or after == len(line):
        return False
    previous, following = line[before], line[after]
    next_script = _major_script(following)
    if next_script not in profile['scripts'] or not following.isalpha():
        return False
    if next_script == 'Arab':
        return previous.isalpha() and _major_script(previous) == next_script
    return (unicodedata.combining(line[index-1]) == 9
            and _major_script(previous) == next_script)


def audit_text(text: str, language: str, script: str | None, *, component_languages=()) -> dict[str, Any]:
    """Return a text-free structural/Unicode audit for one approved-text candidate."""

    issues: list[dict[str, Any]] = []
    if unicodedata.normalize("NFC", text) != text:
        issues.append(_issue("unicode-not-nfc", "error"))
    if "\r" in text:
        issues.append(_issue("non-lf-newline", "error"))
    if "\x00" in text:
        issues.append(_issue("nul-character", "error"))
    if "\ufffd" in text:
        issues.append(_issue("replacement-character", "error"))
    if _COMMON_MOJIBAKE_RE.search(text):
        issues.append(_issue("probable-mojibake", "error"))

    primary = language.split("-", 1)[0].lower()
    profile = _LANGUAGE_PROFILES.get(primary)
    profiles = ([profile] if profile else [_LANGUAGE_PROFILES.get(value.split('-', 1)[0].lower())
                for value in component_languages] if primary == 'mul' else [])
    profiles = [value for value in profiles if value is not None]
    if primary not in {"mul", "und"} and profile is None:
        issues.append(_issue("missing-language-profile", "error"))
    normalized_newlines = text.replace("\r\n", "\n").replace("\r", "\n")
    lines = normalized_newlines.split("\n")
    # One final LF is the required file terminator, not an empty lyric line.
    # Removing only its split sentinel still exposes a second final LF as a
    # real trailing blank line.
    if normalized_newlines.endswith("\n"):
        lines = lines[:-1]
    if lines and not lines[0].strip():
        issues.append(_issue("leading-blank-line", "warning", 1))
    if lines and not lines[-1].strip():
        issues.append(_issue("trailing-blank-line", "warning", len(lines)))

    blank_run = 0
    scripts: Counter[str] = Counter()
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            blank_run += 1
            if blank_run > 1:
                issues.append(_issue("multiple-blank-section-lines", "warning", line_number))
            continue
        blank_run = 0
        if line != line.strip():
            issues.append(_issue("outer-line-whitespace", "warning", line_number))
        if "\t" in line:
            issues.append(_issue("tab-character", "warning", line_number))
        if re.search(r" {2,}", line):
            issues.append(_issue("repeated-ascii-space", "warning", line_number))
        space_pattern = (
            _FRENCH_INVALID_SPACE_BEFORE_PUNCTUATION_RE
            if any(value.get("spacing") == "french" for value in profiles)
            else _SPACE_BEFORE_PUNCTUATION_RE
        )
        if space_pattern.search(line):
            issues.append(_issue("space-before-punctuation", "warning", line_number))
        if _TIMESTAMP_RE.search(line):
            issues.append(_issue("embedded-timestamp", "review", line_number))
        if _HTML_RE.search(html.unescape(line)):
            issues.append(_issue("embedded-html", "error", line_number))
        if _HTML_ENTITY_RE.search(line):
            issues.append(_issue("embedded-html-entity", "error", line_number))
        if _ROLE_LABEL_RE.fullmatch(line):
            issues.append(_issue("editorial-role-label", "warning", line_number))
        if _CAPTION_ARTIFACT_RE.fullmatch(line):
            issues.append(_issue("caption-artifact-label", "review", line_number))
        if line_number == 1 and _PROBABLE_CREDIT_RE.fullmatch(line):
            issues.append(_issue("probable-source-credit-line", "review", line_number))
        if _VISUAL_ELONGATION_RE.search(line) or _LETTER_ELONGATION_RE.search(line):
            issues.append(_issue("visual-elongation-or-punctuation", "warning", line_number))
        if _REPETITION_NOTATION_RE.search(line):
            issues.append(_issue("editorial-repetition-notation", "warning", line_number))
        if line.count("(") != line.count(")") or line.count("[") != line.count("]"):
            issues.append(_issue("unbalanced-delimiter", "error", line_number))
        if _EDITORIAL_HONORIFIC_RE.search(line):
            issues.append(_issue("editorial-honorific-annotation", "warning", line_number))
        if primary in {"en", "mul"} and _PROBABLE_EVERY_DAY_RE.search(line):
            issues.append(_issue("probable-every-day-word-boundary", "warning", line_number))
        if any(character in _DECORATIVE for character in line):
            issues.append(_issue("decorative-symbol", "warning", line_number))

        for index, character in enumerate(line):
            code = ord(character)
            category = unicodedata.category(character)
            detected_script = _major_script(character)
            if detected_script and category.startswith("L"):
                scripts[detected_script] += 1
            if code in _BIDI_CONTROLS:
                issues.append(_issue("bidi-control", "error", line_number, index + 1, character))
            if code in _ZERO_WIDTH:
                if code not in {0x200C, 0x200D} or not any(_valid_join_control(line, index, value) for value in profiles):
                    issues.append(_issue("zero-width-character", 'error', line_number, index + 1, character))
            if any(start <= code <= end for start, end in _PRESENTATION_RANGES):
                issues.append(_issue("presentation-form-character", "error", line_number, index + 1, character))
            if category == "Cc" and character not in {"\t"}:
                issues.append(_issue("control-character", "error", line_number, index + 1, character))
            if _detached_mark(line, index):
                issues.append(_issue("detached-combining-mark", "error", line_number, index + 1, character))

    declared = script or "<missing>"
    if declared not in {"Zyyy", "Zzzz", "<missing>"}:
        unexpected = {name: count for name, count in scripts.items() if name != declared and count}
        if unexpected:
            issues.append(
                {
                    "code": "additional-script-present",
                    "severity": "review",
                    "declaredScript": declared,
                    "detectedAdditionalScripts": unexpected,
                }
            )

    if profile and declared not in {"Zyyy", "Zzzz", "<missing>"}:
        expected_scripts = set(profile["scripts"])
        if declared not in expected_scripts:
            issues.append(
                {
                    "code": "language-script-profile-mismatch",
                    "severity": "error",
                    "language": primary,
                    "declaredScript": declared,
                    "expectedScripts": sorted(expected_scripts),
                }
            )
    if primary not in {"mul", "und"} and declared == "Zyyy" and len(scripts) > 1:
        issues.append(
            {
                "code": "mixed-script-language-review",
                "severity": "review",
                "language": primary,
                "detectedScripts": dict(sorted(scripts.items())),
            }
        )

    if primary in {"fa", "ur"}:
        substitutions = Counter()
        for character in text:
            if character == "ك":
                substitutions["U+0643_ARABIC_KAF"] += 1
            elif character in {"ي", "ى"}:
                substitutions[f"U+{ord(character):04X}_ARABIC_YEH_FORM"] += 1
        if substitutions:
            issues.append(
                {
                    "code": "arabic-form-letter-in-persian-urdu",
                    "severity": "review",
                    "counts": dict(substitutions),
                }
            )

    nonblank = [line for line in lines if line.strip()]
    repeats = Counter(line.strip() for line in nonblank)
    diacritic_count = sum(
        1
        for character in text
        if unicodedata.combining(character) or unicodedata.category(character) in {"Mn", "Me"}
    )
    return {
        "standardVersion": STANDARD_VERSION,
        "language": language,
        "script": script,
        "sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
        "lineCount": len(nonblank),
        "stanzaCount": 0 if not nonblank else 1 + sum(
            bool(lines[index - 1].strip()) and not lines[index].strip()
            for index in range(1, len(lines))
        ),
        "wordTokenCount": sum(len(line.split()) for line in nonblank),
        "characterCount": len(text),
        "letterCount": sum(character.isalpha() for character in text),
        "combiningMarkCount": diacritic_count,
        "uniqueNonblankLineCount": len(repeats),
        "repeatedLineOccurrenceCount": sum(count - 1 for count in repeats.values()),
        "deduplicationApplied": False,
        "detectedLetterScripts": dict(sorted(scripts.items())),
        "issueCount": len(issues),
        "issues": issues,
    }


def _source_class(name: str) -> str:
    folded = name.casefold()
    if "automatic caption" in folded:
        return "automatic-caption"
    if "creator-uploaded" in folded:
        return "creator-subtitle"
    if "description" in folded:
        return "creator-description"
    if "owner-supplied" in folded or "owner transcription" in folded:
        return "owner-supplied"
    if "youtube music" in folded:
        return "youtube-music-lyrics"
    if "lrclib" in folded:
        return "lrclib"
    if "same-song recording" in folded:
        return "reviewed-same-song"
    return "other"


def _title_language_expectation(title: str) -> set[str] | None:
    folded = title.casefold()
    rules = (
        (("turkish", "türkçe"), {"tr", "mul"}),
        (("french", "français"), {"fr", "mul"}),
        (("arabic version", "(arabic)", "عربي"), {"ar", "mul"}),
        (("urdu",), {"ur", "mul"}),
        (("bahasa indonesia", "indonesian"), {"id", "mul"}),
        (("bahasa melayu", "malay version", "malaysian"), {"ms", "mul"}),
    )
    for markers, expected in rules:
        if any(marker in folded for marker in markers):
            return expected
    return None


def audit_library(config: dict[str, Any]) -> dict[str, Any]:
    project = Path(config["projectDir"])
    output = Path(config["outputDir"])
    inventory = read_json(output / "work" / "inventory.json")
    items = inventory.get("items")
    if not isinstance(items, list):
        raise ValueError("inventory.json: missing items array")

    songs: list[dict[str, Any]] = []
    languages: Counter[str] = Counter()
    scripts: Counter[str] = Counter()
    issue_codes: Counter[str] = Counter()
    source_classes: Counter[str] = Counter()
    missing_translation = 0
    missing_ipa = 0
    ipa_requirement_pending = 0
    unavailable = 0
    missing_sidecars = invalid_sidecars = 0
    for item in items:
        video_id = item["videoId"]
        sidecar_path = project / "owner-input" / f"{video_id}.lyrics.json"
        try:
            raw = sidecar_path.read_bytes()
            lyrics = parse_strict_json_bytes(raw)
            sidecar_sha = hashlib.sha256(raw).hexdigest()
            from stemwise_engine.contract import validate_lyrics, _has_language_variant
            validate_lyrics(lyrics, enforce_owner_policy=False)
            tracks = lyrics['tracks']
        except (OSError, ValueError) as error:
            missing = isinstance(error, FileNotFoundError)
            code = 'lyrics-sidecar-missing' if missing else 'lyrics-sidecar-invalid'
            missing_sidecars += int(missing)
            invalid_sidecars += int(not missing)
            issue_codes[code] += 1
            songs.append({'position': item.get('position'), 'videoId': video_id,
                'title': item.get('title') or '[TITLE UNAVAILABLE]',
                'lyricsStatus': 'missing' if missing else 'invalid', 'editorialState': 'input-required',
                'originalTrackCount': 0, 'issues': [{'code': code, 'severity': 'error',
                    'input': sidecar_path.name, 'path': getattr(error, 'path', '$'),
                    'reason': str(error)[:240],
                    'errorType': diagnostic_code(error)}]})
            continue
        originals = [track for track in tracks if track.get("kind") == "original"]
        translations = [
            track
            for track in tracks
            if track.get("kind") == "translation"
            and track.get("language") == "en"
        ]
        ipa_tracks = [
            track
            for track in tracks
            if track.get("kind") == "transliteration"
            and _has_language_variant(str(track.get('language') or ''), 'fonipa')
        ]
        if lyrics.get("status") == "unavailable":
            unavailable += 1
            songs.append(
                {
                    "position": item.get("position"),
                    "videoId": video_id,
                    "title": item.get("title") or "[TITLE UNAVAILABLE]",
                    "lyricsStatus": "unavailable",
                    "editorialState": "not-applicable",
                    "originalTrackCount": 0,
                    "translationTrackCount": len(translations),
                    "ipaTrackCount": len(ipa_tracks),
                    "sidecarSha256": sidecar_sha,
                }
            )
            continue
        if len(originals) != 1:
            songs.append(
                {
                    "position": item.get("position"),
                    "videoId": video_id,
                    "title": item.get("title") or "[TITLE UNAVAILABLE]",
                    "lyricsStatus": lyrics.get("status"),
                    "editorialState": "provisional",
                    "originalTrackCount": len(originals),
                    "issues": [{"code": "original-track-count", "severity": "error"}],
                }
            )
            issue_codes["original-track-count"] += 1
            continue

        original = originals[0]
        from stemwise_engine.companion_review import companion_issues
        companion_audits = {}
        for kind, matching in (('translation', translations), ('transliteration', ipa_tracks)):
            if not matching:
                companion_audits[kind] = {'state': 'missing', 'reviewStatus': 'unavailable', 'issues': []}
                continue
            candidate = matching[0]
            candidate_issues = companion_issues(original, candidate)
            candidate_text = candidate.get('text')
            if not isinstance(candidate_text, str):
                candidate_text = '\n'.join(line['text'] for line in candidate['lines'])
            companion_audits[kind] = {'state': 'invalid' if candidate_issues else 'structurally-valid',
                'reviewStatus': 'unavailable', 'sha256': hashlib.sha256(candidate_text.encode('utf-8')).hexdigest(),
                'issues': candidate_issues}
            for issue in candidate_issues:
                issue_codes[issue['code']] += 1
        language = str(original.get("language") or "und")
        script = original.get("script")
        languages[language] += 1
        scripts[str(script or "<missing>")] += 1
        text = original.get("text")
        if not isinstance(text, str):
            # Synced text is audited as its deliberate line stream while its
            # millisecond structure remains governed by the frozen contract.
            synced_lines = original.get("lines") if isinstance(original.get("lines"), list) else []
            text = "\n".join(str(line.get("text") or "") for line in synced_lines)
        raw_components = original.get("componentLanguages")
        component_languages = (
            [str(value).split("-", 1)[0].lower() for value in raw_components]
            if isinstance(raw_components, list) and raw_components
            else None
        )
        text_audit = audit_text(text, language, script, component_languages=component_languages or ())
        expected_title_languages = _title_language_expectation(str(item.get("title") or ""))
        primary = canonical_lyrics_language(language.lower()).split("-", 1)[0]
        if expected_title_languages and primary not in expected_title_languages:
            text_audit["issues"].append(
                {
                    "code": "title-language-version-mismatch",
                    "severity": "warning",
                    "declaredLanguage": primary,
                    "expectedLanguages": sorted(expected_title_languages),
                }
            )
            text_audit["issueCount"] = len(text_audit["issues"])
        for issue in text_audit["issues"]:
            issue_codes[issue["code"]] += 1

        source = original.get("source") if isinstance(original.get("source"), dict) else {}
        source_class = _source_class(str(source.get("name") or ""))
        source_classes[source_class] += 1
        needs_translation, needs_ipa = companion_language_roles(primary, component_languages)
        if needs_ipa is None:
            ipa_requirement_pending += 1
        translation_missing = needs_translation and not translations
        ipa_missing = needs_ipa is True and not ipa_tracks
        missing_translation += int(translation_missing)
        missing_ipa += int(ipa_missing)
        songs.append(
            {
                "position": item.get("position"),
                "videoId": video_id,
                "title": item.get("title") or "[TITLE UNAVAILABLE]",
                "lyricsStatus": lyrics.get("status"),
                "editorialState": "provisional",
                "originalTrackCount": 1,
                "translationTrackCount": len(translations),
                "ipaTrackCount": len(ipa_tracks),
                "requiresEnglishTranslation": needs_translation,
                "missingEnglishTranslation": translation_missing,
                "requiresIpa": needs_ipa,
                "ipaRequirementPending": needs_ipa is None,
                "missingIpa": ipa_missing,
                "sourceClass": source_class,
                "sourceName": source.get("name"),
                "sidecarSha256": sidecar_sha,
                "original": text_audit,
                "reviewStatus": "unavailable",
                "companions": companion_audits,
            }
        )

    duplicate_hashes: dict[str, list[dict[str, Any]]] = {}
    for song in songs:
        original = song.get("original")
        if isinstance(original, dict) and isinstance(original.get("sha256"), str):
            duplicate_hashes.setdefault(original["sha256"], []).append(song)
    exact_duplicate_groups = []
    for sha256, members in duplicate_hashes.items():
        if len(members) < 2:
            continue
        exact_duplicate_groups.append(
            {
                "sha256": sha256,
                "songCount": len(members),
                "languages": sorted({member["original"]["language"] for member in members}),
                "songs": [
                    {
                        "position": member["position"],
                        "videoId": member["videoId"],
                        "title": member["title"],
                        "sourceClass": member.get("sourceClass"),
                    }
                    for member in members
                ],
            }
        )
    exact_duplicate_groups.sort(
        key=lambda group: (-group["songCount"], group["songs"][0]["position"])
    )

    report = {
        "reportVersion": QA_REPORT_VERSION,
        "standardVersion": STANDARD_VERSION,
        "generatedAt": utc_now(),
        "containsLyrics": False,
        "inventoryCount": len(items),
        "availableOriginalCount": sum(song['originalTrackCount'] == 1 for song in songs
            if song['lyricsStatus'] != 'unavailable'),
        "missingSidecarCount": missing_sidecars,
        "invalidSidecarCount": invalid_sidecars,
        "unavailableLyricsCount": unavailable,
        "editoriallyApprovedCount": 0,
        "provisionalCount": sum(song['editorialState'] == 'provisional' for song in songs),
        "missingEnglishTranslationCount": missing_translation,
        "missingIpaCount": missing_ipa,
        "ipaRequirementPendingCount": ipa_requirement_pending,
        "issueCodeCounts": dict(sorted(issue_codes.items())),
        "languageCounts": dict(sorted(languages.items())),
        "scriptCounts": dict(sorted(scripts.items())),
        "sourceClassCounts": dict(sorted(source_classes.items())),
        "exactDuplicateTextGroupCount": len(exact_duplicate_groups),
        "exactDuplicateTextSongCount": sum(
            group["songCount"] for group in exact_duplicate_groups
        ),
        "exactDuplicateTextGroups": exact_duplicate_groups,
        "songs": songs,
    }
    destination = project / "owner-input" / "lyrics-qa-report.json"
    atomic_write_json(destination, report)
    return {
        "report": str(destination),
        "inventoryCount": report["inventoryCount"],
        "availableOriginalCount": report["availableOriginalCount"],
        "missingSidecarCount": missing_sidecars,
        "invalidSidecarCount": invalid_sidecars,
        "unavailableLyricsCount": unavailable,
        "provisionalCount": report["provisionalCount"],
        "missingEnglishTranslationCount": missing_translation,
        "missingIpaCount": missing_ipa,
        "ipaRequirementPendingCount": ipa_requirement_pending,
        "issueCodeCounts": report["issueCodeCounts"],
        "languageCounts": report["languageCounts"],
        "scriptCounts": report["scriptCounts"],
        "exactDuplicateTextGroupCount": report["exactDuplicateTextGroupCount"],
        "exactDuplicateTextSongCount": report["exactDuplicateTextSongCount"],
    }

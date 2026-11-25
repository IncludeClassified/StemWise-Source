"""IPA syntax only: no meaning, linguistic plausibility or pronunciation approval.

Inventory v2: IPA chart revised 2015/2005 and Unicode 13 character encodings.
https://www.internationalphoneticassociation.org/content/ipa-chart
https://www.unicode.org/Public/13.0.0/ucd/UnicodeData.txt
Only canonical decomposition is inspected; text and tokenization stay intact.
"""

from __future__ import annotations

from dataclasses import dataclass
import unicodedata


IPA_VALIDATION_VERSION = 'stemwise.ipa-syntax.v2'
_SEGMENTS = frozenset('abcdefghijklmnopqrstuvwxyzæçðøħŋœǀǁǂǃ'
    'ɐɑɒɓɔɕɖɗɘəɚɛɜɝɞɟɠɡɢɣɤɥɦɧɨɪɫɬɭɮɯɰɱɲɳɴɵɶɸɹɺɻɽɾʀʁʂʃʄʈʉʊʋʌʍʎʏʐʑʒʔʕʘʙʛʜʝʟʡʢβθχⱱ')
_DIACRITICS = frozenset('\u0300\u0301\u0302\u0303\u0304\u0306\u0308\u030a\u030b\u030c\u030d\u030f\u0311'
    '\u0318\u0319\u031a\u031c\u031d\u031e\u031f\u0320\u0324\u0325\u0329\u032a\u032c\u032f\u0330\u0334\u0339\u033a\u033b\u033c\u033d')
_MODIFIERS = frozenset("ʰʱʷʲˠˤⁿˡ˞ʼ'ːˑ")
_TONES = frozenset('˥˦˧˨˩')
_PREFIXES = frozenset('ˈˌꜛꜜ')
_TIES = frozenset('\u0361\u035c')
_BOUNDARIES = frozenset('.|‖‿')
# Canonical equivalents from Unicode 13, fixed so a newer host cannot expand admission.
_COMPOSED = frozenset('àáâãäèéêëìíîïñòóôõöùúûüýÿāăćĉčďēĕěĝğĥĩīĭĵĺľńňōŏőŕřśŝšťũūŭűŵŷźžǎǐǒǔǖǘǚǜǟǣǧǩǯǰǵǹǽǿȁȅȉȍȑȕȟȫȭȳḁḕḗḛḡḧḭḯḱḿṍṏṑṓṕṳṵṹṻṽẁẃẅẍẑẗấầẫắằẵẽếềễốồỗỳỹåůǻẘẙȃȇȋȏȓȗ')
_MAX_ISSUES = 64


@dataclass(frozen=True)
class IpaIssue:
    code: str
    line: int
    position: int


IPA_SKIP = '—'
IPA_CELL_SEPARATOR = ' | '
IPA_EXEMPT = frozenset({'ar', 'en'})


def _primary_language(tag):
    from stemwise_engine.common import canonical_lyrics_language
    return canonical_lyrics_language(tag.split('-', 1)[0].lower())


def selective_ipa(original):
    components = {_primary_language(lang) for lang in original.get('componentLanguages', ())}
    return (original.get('language', '').split('-', 1)[0].lower() == 'mul'
            and bool(components & IPA_EXEMPT) and bool(components - IPA_EXEMPT))


def reviewed_ipa_languages(original, indices, rows):
    """Check an IPA-only review when content admission has no spoken-word plan."""
    from stemwise_engine.lyrics_normalize import physical_lines
    from stemwise_engine.lyrics_tokenization import display_words
    if not isinstance(rows, list) or len(rows) != len(indices):
        return [IpaIssue('ipa-word-languages-unreviewed', 1, 1)]
    primary = _primary_language(original['language'])
    declared = original.get('componentLanguages', []) if primary == 'mul' else [primary]
    eligible = {_primary_language(tag) for tag in declared}
    eligible -= IPA_EXEMPT | {'und', 'zxx', 'mul', 'mis'}
    lines = physical_lines(original['text'])
    for source, owners in zip(indices, rows):
        if not isinstance(owners, list) or len(owners) != len(display_words(lines[source])):
            return [IpaIssue('ipa-word-languages-unreviewed', source + 1, 1)]
        for position, owner in enumerate(owners, 1):
            if not isinstance(owner, str) or owner not in eligible:
                return [IpaIssue('ipa-word-language-ineligible', source + 1, position)]
    return []


def ipa_language_issues(candidate, indices, word_languages):
    """Match inapplicable slots to reviewed display-word owners, including code switches."""
    from stemwise_engine.lyrics_normalize import physical_lines
    lines = physical_lines(candidate)
    issues = []
    for source, owners in zip(indices, word_languages, strict=True):
        eligible = [_primary_language(language) not in IPA_EXEMPT for language in owners]
        line = lines[source] if source < len(lines) else ''
        if all(eligible):
            matches = bool(line.strip()) and IPA_SKIP not in line
        elif any(eligible):
            cells = line.split(IPA_CELL_SEPARATOR)
            matches = (len(cells) == len(eligible) and all(
                (bool(cell.strip()) and IPA_SKIP not in cell) if keep else cell == IPA_SKIP
                for cell, keep in zip(cells, eligible)))
        else:
            matches = line == IPA_SKIP
        if not matches:
            issues.append(IpaIssue('ipa-word-language-coverage', source + 1, 1))
    return issues


def validate_ipa(text: str, *, allow_skips=False) -> tuple[IpaIssue, ...]:
    """Check bare, /phonemic/ or [phonetic] physical lines; return safe positions."""
    issues: list[IpaIssue] = []

    def issue(code: str, line: int, position: int) -> None:
        if len(issues) < _MAX_ISSUES:
            issues.append(IpaIssue(code, line, position))

    has_content = False
    for line_number, line in enumerate(text.split('\n'), 1):
        if not line:
            continue
        has_content = True
        if allow_skips and IPA_SKIP in line:
            if line != IPA_SKIP:
                cells = line.split(IPA_CELL_SEPARATOR)
                if all(cell == IPA_SKIP for cell in cells):
                    issue('ipa-empty-unit', line_number, 1)
                offset = 0
                for cell in cells:
                    if cell != IPA_SKIP:
                        for problem in validate_ipa(cell):
                            issue(problem.code, line_number, offset + problem.position)
                    offset += len(cell) + len(IPA_CELL_SEPARATOR)
            continue
        offset = 0
        body = line
        if line[0] in '/[':
            closing = '/' if line[0] == '/' else ']'
            if len(line) < 2 or line[-1] != closing:
                issue('ipa-wrapper', line_number, 1)
                continue
            body, offset = line[1:-1], 1
        if not body.strip():
            issue('ipa-empty-unit', line_number, offset + 1)
            continue
        attached = False
        segment_before_boundary = False
        pending: tuple[str, int] | None = None
        for index, character in enumerate(body):
            position = index + offset + 1
            # Canonical mappings of supported characters are stable across Unicode versions.
            decomposed = unicodedata.normalize('NFD', character)
            if character in _SEGMENTS or (character in _COMPOSED
                    and decomposed[0] in _SEGMENTS
                    and all(mark in _DIACRITICS for mark in decomposed[1:])):
                attached, pending = True, None
                segment_before_boundary = True
            elif (character in _DIACRITICS or character in _MODIFIERS or character in _TONES
                    or (character == '\u0327' and index > 0 and body[index - 1] == 'c')):
                if not attached or pending is not None:
                    issue('ipa-unattached-mark', line_number, position)
            elif character in _TIES:
                if not attached or pending is not None:
                    issue('ipa-unattached-tie', line_number, position)
                pending = ('ipa-dangling-tie', position)
                attached = False
            elif character in _PREFIXES:
                if pending is not None and pending[0] != 'ipa-empty-unit':
                    issue(pending[0], line_number, pending[1])
                attached = False
                pending = ('ipa-dangling-prefix', position)
            elif character in _BOUNDARIES or character == ' ':
                if pending is not None and not (character == ' ' and pending[0] == 'ipa-empty-unit'):
                    issue(pending[0], line_number, pending[1])
                if character in _BOUNDARIES and not segment_before_boundary:
                    issue('ipa-empty-unit', line_number, position)
                attached = False
                if character in _BOUNDARIES:
                    segment_before_boundary = False
                    pending = ('ipa-empty-unit', position) if character in '.‿' else None
                elif pending is not None and pending[0] != 'ipa-empty-unit':
                    pending = None
            elif character in '↗↘' and attached:
                attached = False
            else:
                issue('ipa-wrapper' if character in '[]/' else 'ipa-unsupported-symbol', line_number, position)
                attached = False
        if pending is not None:
            issue(pending[0], line_number, pending[1])
    if not has_content:
        issue('ipa-empty-unit', 1, 1)
    return tuple(issues)


def companion_lines(candidate, *, ipa):
    from stemwise_engine.lyrics_normalize import physical_lines, performance_lines
    lines = performance_lines(candidate)
    if ipa:
        # An explicit language exemption owns one timed occurrence in a mixed song.
        for index, text in enumerate(physical_lines(candidate)):
            if text == IPA_SKIP:
                lines[index] = text
    return lines


def validate_companion_text(original: str, candidate: str, *, role: str, allow_skips=False) -> tuple[IpaIssue, ...]:
    """Require the original's physical stanza shape; IPA adds syntax checks only."""
    if role not in {'translationEn', 'ipa'}:
        raise ValueError('unknown companion role')
    from stemwise_engine.lyrics_normalize import performance_lines
    original_lines = performance_lines(original)
    candidate_lines = companion_lines(candidate, ipa=role == 'ipa')
    issues: list[IpaIssue] = []
    if len(original_lines) != len(candidate_lines):
        issues.append(IpaIssue('companion-line-count', 1, 1))
    for index, (source, target) in enumerate(zip(original_lines, candidate_lines), 1):
        if bool(source.strip()) != bool(target.strip()):
            issues.append(IpaIssue('companion-stanza-shape', index, 1))
    if role == 'ipa':
        issues.extend(validate_ipa('\n'.join(candidate_lines), allow_skips=allow_skips))
    return tuple(issues[:_MAX_ISSUES])

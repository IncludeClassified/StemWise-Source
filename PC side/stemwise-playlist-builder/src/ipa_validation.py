"""IPA syntax only: no meaning, linguistic plausibility or pronunciation approval.

Inventory v1: IPA chart revised 2015/2005 and Unicode 13 character encodings.
https://www.internationalphoneticassociation.org/content/ipa-chart
https://www.unicode.org/Public/13.0.0/ucd/UnicodeData.txt
Only canonical decomposition is inspected; text and tokenization stay intact.
"""

from __future__ import annotations

from dataclasses import dataclass
import unicodedata


IPA_VALIDATION_VERSION = 'stemwise.ipa-syntax.v1'
_SEGMENTS = frozenset('abcdefghijklmnopqrstuvwxyzæçðøħŋœǀǁǂǃ'
    'ɐɑɒɓɔɕɖɗɘəɚɛɜɝɞɟɠɡɢɣɤɥɦɧɨɪɫɬɭɮɯɰɱɲɳɴɵɶɸɹɺɻɽɾʀʁʂʃʄʈʉʊʋʌʍʎʏʐʑʒʔʕʘʙʛʜʝʟʡʢβθχⱱ')
_DIACRITICS = frozenset('\u0300\u0301\u0302\u0303\u0304\u0306\u0308\u030a\u030b\u030c\u030d\u030f\u0311'
    '\u0318\u0319\u031a\u031c\u031d\u031e\u031f\u0320\u0324\u0325\u0329\u032a\u032c\u032f\u0330\u0334\u0339\u033a\u033b\u033c\u033d')
_MODIFIERS = frozenset("ʰʷʲˠˤⁿˡ˞ʼ'ːˑ")
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


def validate_ipa(text: str) -> tuple[IpaIssue, ...]:
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


def validate_companion_text(original: str, candidate: str, *, role: str) -> tuple[IpaIssue, ...]:
    """Require the original's physical stanza shape; IPA adds syntax checks only."""
    if role not in {'translationEn', 'ipa'}:
        raise ValueError('unknown companion role')
    original_lines, candidate_lines = original.split('\n'), candidate.split('\n')
    issues: list[IpaIssue] = []
    if len(original_lines) != len(candidate_lines):
        issues.append(IpaIssue('companion-line-count', 1, 1))
    for index, (source, target) in enumerate(zip(original_lines, candidate_lines), 1):
        if bool(source.strip()) != bool(target.strip()):
            issues.append(IpaIssue('companion-stanza-shape', index, 1))
    if role == 'ipa':
        issues.extend(validate_ipa(candidate))
    return tuple(issues[:_MAX_ISSUES])

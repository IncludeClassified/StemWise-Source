"""Independent, recording-bound companion decisions; syntax is never semantic proof."""
from __future__ import annotations

import copy
import errno
import hashlib
import re
import time
from pathlib import Path, PurePosixPath

from stemwise_engine.lyrics_normalize import performed_line_indices, physical_lines
from stemwise_engine.common import json_bytes, read_bounded_regular_bytes, validate_video_id
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.editorial_review import valid_reviewer

COMPANION_POLICY_VERSION = 'stemwise-companion-review/1'
REVIEW_CHECKS = {
    'translation': ('meaning', 'referents', 'polarity', 'tense-aspect-modality',
        'cultural-meaning', 'names', 'ambiguity', 'repetition', 'code-switching'),
    'transliteration': ('performed-pronunciation', 'dialect', 'heteronyms', 'names-loans',
        'code-switching', 'connected-speech', 'repeated-realisations', 'stress-length-tone'),
}
CONVENTIONS = {'translation': 'faithful-readable-English/1',
               'transliteration': 'performed-broad-IPA/1'}
_SHA = re.compile(r'[0-9a-f]{64}')


def _digest(value):
    return hashlib.sha256(json_bytes(value)).hexdigest()


def _text(track):
    if track.get('format') == 'plain':
        return track['text']
    return '\n'.join(line['text'] for line in track['lines'])


def companion_binding(video_id, original, candidate, *, original_input_sha256,
        candidate_input_sha256, audio_sha256, sources_sha256, content_admission=None,
        original_metadata_sha256=None):
    """Describe current inputs without approving them; raw and displayed identities differ."""
    from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping, lexical_mapping_identity
    video_id = validate_video_id(video_id)
    kind = candidate['kind']
    if kind not in REVIEW_CHECKS:
        raise ValueError('Unsupported companion role')
    plain_original = copy.deepcopy(original)
    plain_original.pop('lines', None)
    plain_original.update(format='plain', text=_text(original))
    indices = list(performed_line_indices(plain_original['text']))
    admission = (content_admission.to_record() if hasattr(content_admission, 'to_record')
                 else content_admission)
    if admission is not None:
        from stemwise_engine.lyric_content import validate_content_admission_record
        admission = validate_content_admission_record(admission)
        if admission['audioSha256'] != audio_sha256 or admission['sourcePhysicalIndices'] != indices:
            raise ValueError('Companion original admission does not match the recording or occurrence sequence')
    mapping = build_lexical_occurrence_mapping(video_id, plain_original, indices)
    if original_metadata_sha256 is None:
        original_metadata_sha256 = _digest({key: value for key, value in plain_original.items()
            if key not in {'text', 'format'}})
    binding = {'formatVersion': 1, 'policyVersion': COMPANION_POLICY_VERSION,
        'videoId': video_id, 'kind': kind, 'audioSha256': audio_sha256,
        'sourcesSha256': sources_sha256, 'originalInputSha256': original_input_sha256,
        'originalMetadataSha256': original_metadata_sha256, 'originalSha256': _digest(plain_original),
        'candidateInputSha256': candidate_input_sha256, 'candidateSha256': _digest(candidate),
        'occurrenceMappingSha256': lexical_mapping_identity(mapping)['mappingSha256'],
        'occurrenceIds': [item['occurrenceId'] for item in mapping['performedOccurrences']],
        'contentEvidence': (None if admission is None else {key: admission[key]
            for key in ('evidence', 'adjudication', 'spokenTargets', 'ownerVerification') if key in admission})}
    _validate_binding(binding)
    return binding


def _validate_binding(value):
    hashes = {'audioSha256', 'sourcesSha256', 'originalInputSha256', 'originalMetadataSha256',
              'originalSha256', 'candidateInputSha256', 'candidateSha256', 'occurrenceMappingSha256'}
    keys = hashes | {'formatVersion', 'policyVersion', 'videoId', 'kind', 'occurrenceIds', 'contentEvidence'}
    if (not isinstance(value, dict) or set(value) != keys or type(value['formatVersion']) is not int
            or value['formatVersion'] != 1 or value['policyVersion'] != COMPANION_POLICY_VERSION
            or not isinstance(value['kind'], str) or value['kind'] not in REVIEW_CHECKS
            or any(not isinstance(value[key], str) or not _SHA.fullmatch(value[key]) for key in hashes)
            or not isinstance(value['occurrenceIds'], list) or not value['occurrenceIds']
            or any(not isinstance(item, str) or not re.fullmatch(r'occurrence-[0-9a-f]{24}', item)
                   for item in value['occurrenceIds'])
            or len(set(value['occurrenceIds'])) != len(value['occurrenceIds'])):
        raise ValueError('Companion review binding is malformed')
    validate_video_id(value['videoId'])
    evidence = value['contentEvidence']
    if evidence is not None:
        if (not isinstance(evidence, dict) or 'evidence' not in evidence
                or not set(evidence) <= {'evidence', 'adjudication', 'spokenTargets', 'ownerVerification'}):
            raise ValueError('Companion content evidence is malformed')
        for reference in evidence.values():
            _validate_reference(reference)


def _validate_reference(reference):
    if (not isinstance(reference, dict) or set(reference) != {'path', 'sha256'}
            or not isinstance(reference['path'], str) or not reference['path']
            or len(reference['path']) > 240 or '\\' in reference['path']
            or PurePosixPath(reference['path']).is_absolute()
            or any(part in {'', '.', '..'} or ':' in part for part in reference['path'].split('/'))
            or not isinstance(reference['sha256'], str) or not _SHA.fullmatch(reference['sha256'])):
        raise ValueError('Companion review evidence reference is malformed')


def _evidence_identity(reference, root):
    _validate_reference(reference)
    try:
        raw = read_bounded_regular_bytes(Path(root) / reference['path'],
                                        max_bytes=MAX_JSON_BYTES, root=Path(root))
    except ValueError as error:
        cause = error.__cause__
        if isinstance(cause, OSError) and cause.errno not in {errno.ENOENT, errno.ENOTDIR, errno.ELOOP}:
            raise cause
        return {'path': reference['path'], 'state': 'unavailable'}
    return {'path': reference['path'], 'state': 'current', 'sha256': hashlib.sha256(raw).hexdigest()}


def review_evidence_snapshot(review, evidence_root):
    """Body-free current evidence identities; invalid roles do not hide valid siblings."""
    reviews = review.get('companionReviews', {}) if isinstance(review, dict) else {}
    if not isinstance(reviews, dict):
        return {'state': 'invalid', 'reviewSha256': _digest(reviews)}
    snapshot = {}
    for kind in REVIEW_CHECKS:
        value = reviews.get(kind)
        if value is None:
            snapshot[kind] = {'state': 'missing'}
            continue
        try:
            validate_companion_review(value, kind)
        except (ValueError, KeyError, TypeError):
            snapshot[kind] = {'state': 'invalid', 'reviewSha256': _digest(value)}
            continue
        snapshot[kind] = {'state': 'current', 'reviewSha256': _digest(value),
                          'evidence': [_evidence_identity(reference, evidence_root)
                                       for reference in value['evidence']]}
    return snapshot


def validate_companion_review(value, kind):
    """Strict private shape validation, separate from current-input admission."""
    keys = {'formatVersion', 'binding', 'reviewer', 'completedAt', 'decision',
            'coveredOccurrenceIds', 'convention', 'checks', 'issues', 'evidence', 'rationale'}
    if kind == 'transliteration' and isinstance(value, dict) and 'wordLanguages' in value:
        keys.add('wordLanguages')
    if (kind not in REVIEW_CHECKS or not isinstance(value, dict) or set(value) != keys
            or type(value['formatVersion']) is not int or value['formatVersion'] != 1
            or not valid_reviewer(value['reviewer'])
            or not isinstance(value['completedAt'], str)
            or not re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z', value['completedAt'])
            or not isinstance(value['decision'], str) or value['decision'] not in {'admit', 'withhold', 'uncertain'}
            or value['convention'] != CONVENTIONS[kind]
            or not isinstance(value['checks'], dict) or set(value['checks']) != set(REVIEW_CHECKS[kind])
            or any(not isinstance(status, str) or status not in {'reviewed', 'not-applicable', 'unresolved'}
                   for status in value['checks'].values())
            or not isinstance(value['rationale'], str) or not value['rationale'].strip()
            or len(value['rationale']) > 1000 or '\n' in value['rationale'] or '\r' in value['rationale']):
        raise ValueError('Companion substantive review is malformed or incomplete')
    try:
        time.strptime(value['completedAt'], '%Y-%m-%dT%H:%M:%SZ')
    except ValueError as error:
        raise ValueError('Companion review completion time is invalid') from error
    _validate_binding(value['binding'])
    if value['binding']['kind'] != kind:
        raise ValueError('Companion review role does not match')
    coverage = value['coveredOccurrenceIds']
    if (not isinstance(coverage, list) or len(coverage) > len(value['binding']['occurrenceIds'])
            or any(not isinstance(item, str) for item in coverage)
            or len(set(coverage)) != len(coverage)):
        raise ValueError('Companion review occurrence coverage is malformed')
    if not isinstance(value['issues'], list) or len(value['issues']) > 200:
        raise ValueError('Companion review issues must be an array')
    for issue in value['issues']:
        if (not isinstance(issue, dict) or set(issue) != {'code', 'occurrenceId', 'position', 'status', 'rationale'}
                or not isinstance(issue['code'], str) or not re.fullmatch('[a-z][a-z0-9-]{0,79}', issue['code'])
                or issue['occurrenceId'] not in value['binding']['occurrenceIds']
                or (issue['position'] is not None and (type(issue['position']) is not int or issue['position'] < 1))
                or not isinstance(issue['status'], str) or issue['status'] not in {'resolved', 'unresolved'}
                or not isinstance(issue['rationale'], str) or not issue['rationale'].strip()
                or len(issue['rationale']) > 1000):
            raise ValueError('Companion review issue is malformed or unlocated')
    if not isinstance(value['evidence'], list) or not 1 <= len(value['evidence']) <= 32:
        raise ValueError('Companion review needs retained evidence references')
    for reference in value['evidence']:
        _validate_reference(reference)
    if len({reference['path'] for reference in value['evidence']}) != len(value['evidence']):
        raise ValueError('Companion review repeats an evidence reference')
    return value


def _translation_audit(candidate):
    from stemwise_engine.lyrics_qa import audit_text
    text = _text(candidate)
    return audit_text(text if candidate.get('format') == 'plain' and text.endswith('\n')
                      else text + '\n', 'en', 'Latn')


def companion_issues(original, candidate):
    """Deterministic role QA only; a clean result confers no editorial authority."""
    from stemwise_engine.ipa_validation import selective_ipa, validate_companion_text
    kind = candidate['kind']
    role = {'translation': 'translationEn', 'transliteration': 'ipa'}[kind]
    issues = [{'code': item.code, 'line': item.line, 'position': item.position}
              for item in validate_companion_text(_text(original), _text(candidate), role=role,
                                                   allow_skips=selective_ipa(original))]
    if kind == 'translation':
        issues.extend(_translation_audit(candidate)['issues'])
    return issues


def admit_companion(original, candidate, review, *, binding, required, evidence_root, qa_resolutions=()):
    """Admit exactly one role; expected rejections never erase another role."""
    kind = binding['kind'] if binding is not None else candidate.get('kind') if candidate else None
    def result(state, codes=(), issues=None):
        actions = {'not-required': 'Remove the forbidden companion input.', 'missing': 'Author this companion.',
            'invalid': 'Correct the companion input or private decision.', 'unreviewed': 'Review this exact candidate.',
            'stale': 'Review the current candidate and dependencies.',
            'review-required': 'Resolve material meaning or pronunciation uncertainty.', 'admitted': 'Validate.'}
        return {'state': state, 'issues': issues if issues is not None else [{'code': code} for code in codes],
                'binding': copy.deepcopy(binding), 'nextAction': actions[state]}
    if not required:
        return result('not-required', ('companion-not-required',) if candidate else ())
    if candidate is None:
        return result('missing', ('companion-missing',))
    _validate_binding(binding)
    if kind not in REVIEW_CHECKS or candidate['kind'] != kind:
        return result('invalid', ('companion-role-mismatch',))
    plain_original = copy.deepcopy(original)
    plain_original.pop('lines', None)
    plain_original.update(format='plain', text=_text(original))
    if _digest(candidate) != binding['candidateSha256'] or _digest(plain_original) != binding['originalSha256']:
        return result('stale', ('companion-candidate-binding-stale',))
    issues = companion_issues(original, candidate)
    if kind == 'translation' and qa_resolutions:
        from stemwise_engine.editorial_admission import unresolved_qa_issues
        audit = _translation_audit(candidate)
        try:
            remaining = unresolved_qa_issues(audit, list(qa_resolutions), video_id=binding['videoId'],
                recording_sha256=binding['audioSha256'], track_kind='translation')
        except ValueError:
            return result('invalid', ('companion-qa-resolution-invalid',))
        issues = [issue for issue in issues if 'severity' not in issue] + remaining
    if issues:
        return result('invalid', issues=issues)
    if binding['contentEvidence'] is None:
        return result('unreviewed', ('original-authority-unavailable',))
    from stemwise_engine.ipa_validation import ipa_language_issues, reviewed_ipa_languages, selective_ipa
    reference = binding['contentEvidence'].get('spokenTargets')
    if kind == 'transliteration' and (reference or selective_ipa(original)):
        from stemwise_engine import spoken_targets
        from stemwise_engine.workflow_records import LyricsContentBlocker
        indices = performed_line_indices(plain_original['text'])
        if not reference:
            return result('review-required', ('ipa-word-languages-unreviewed',))
        try:
            plan = spoken_targets.load(evidence_root, binding['videoId'], plain_original, indices, binding['audioSha256'])
            if plan is None or plan.reference != reference:
                return result('stale', ('ipa-word-languages-changed',))
            scope_issues = ipa_language_issues(_text(candidate), indices, plan.language_lines(indices))
        except LyricsContentBlocker:
            return result('stale', ('ipa-word-languages-changed',))
        if scope_issues:
            return result('invalid', issues=[vars(issue) for issue in scope_issues])
    if review is None:
        return result('unreviewed', ('companion-review-missing',))
    try:
        validate_companion_review(review, kind)
    except (ValueError, TypeError, KeyError):
        return result('invalid', ('companion-review-invalid',))
    if review['binding'] != binding:
        return result('stale', ('companion-review-stale',))
    if review['coveredOccurrenceIds'] != binding['occurrenceIds']:
        return result('review-required', ('companion-coverage-incomplete',))
    if kind == 'transliteration':
        if reference and 'wordLanguages' in review:
            return result('invalid', ('ipa-word-languages-already-bound',))
        if not reference:
            issues = reviewed_ipa_languages(plain_original, performed_line_indices(plain_original['text']),
                                            review.get('wordLanguages'))
            if issues:
                return result('review-required', issues=[vars(issue) for issue in issues])
    if (review['decision'] != 'admit' or 'unresolved' in review['checks'].values()
            or review['checks']['meaning' if kind == 'translation' else 'performed-pronunciation'] != 'reviewed'
            or any(issue['status'] == 'unresolved' for issue in review['issues'])):
        return result('review-required', ('companion-substantive-review-unresolved',))
    for reference in review['evidence']:
        identity = _evidence_identity(reference, evidence_root)
        if identity['state'] != 'current':
            return result('stale', ('companion-review-evidence-unavailable',))
        if identity['sha256'] != reference['sha256']:
            return result('stale', ('companion-review-evidence-changed',))
    return {**result('admitted'), 'track': copy.deepcopy(candidate),
            'reviewSha256': _digest(review)}


def companion_review_packet(original, candidates, bindings, *, video_id=None):
    """Current local authoring inputs, never a generated review decision."""
    text = _text(original)
    physical = physical_lines(text)
    sung = performed_line_indices(text)
    current_binding = next((binding for binding in bindings.values() if binding is not None), None)
    video_id = video_id or (current_binding['videoId'] if current_binding else None)
    authority_lines, occurrences = {}, {}
    if video_id is not None:
        from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping
        plain = copy.deepcopy(original)
        plain.pop('lines', None)
        plain.update(format='plain', text=text)
        mapping = build_lexical_occurrence_mapping(video_id, plain, sung)
        authority_lines = {line['sourcePhysicalIndex']: line['lineId'] for line in mapping['authorityLines']}
        occurrences = {item['sourcePhysicalIndex']: item['occurrenceId'] for item in mapping['performedOccurrences']}
    review_lines = [{'physicalIndex': index, 'text': line, 'stanzaBoundary': index not in sung,
                       'lineId': authority_lines.get(index), 'occurrenceId': occurrences.get(index)}
                      for index, line in enumerate(physical)]
    roles = {}
    for kind in REVIEW_CHECKS:
        binding = bindings.get(kind)
        candidate = candidates.get(kind)
        roles[kind] = {'binding': copy.deepcopy(binding), 'candidate': copy.deepcopy(candidate),
            'convention': CONVENTIONS[kind], 'requiredChecks': list(REVIEW_CHECKS[kind]),
            'expectedReviewProperties': ['formatVersion', 'binding', 'reviewer', 'completedAt',
                'decision', 'coveredOccurrenceIds', 'convention', 'checks', 'issues', 'evidence', 'rationale']}
        if kind != 'transliteration' or not binding:
            continue
        from stemwise_engine.ipa_validation import selective_ipa
        if not selective_ipa(original) and not (binding.get('contentEvidence') or {}).get('spokenTargets'):
            from stemwise_engine.lyrics_tokenization import display_words
            roles[kind]['expectedReviewProperties'].append('wordLanguages')
            for index in sung:
                review_lines[index]['displayWords'] = [word for _, _, word in display_words(physical[index])]
    return {'formatVersion': 1, 'policyVersion': COMPANION_POLICY_VERSION,
        'original': copy.deepcopy(original), 'physicalLines': review_lines, 'roles': roles,
        'instructions': [
            'Preserve each source occurrence and stanza; do not merge repeated lines.',
            'Translation preserves meaning, polarity, referents, modality, names and ambiguity; it is not a singable adaptation.',
            'IPA describes the performed broad pronunciation; dictionary, ASR and timing do not prove it.',
            'Exclude Arabic and English from IPA. Use — for a wholly excluded line. On mixed-eligibility lines use one cell per display word separated by " | ", with — in excluded cells.',
            'When wordLanguages is requested, record one reviewed primary language code per display word, one array per performed occurrence in order. Check the recording and reference before writing or reusing IPA. If a word is English or Arabic, declare the mixed languages and use the recording-bound spoken plan.',
            'Review dialect, borrowed names, code-switching, connected speech and differing repeated realisations.',
            'Do not infer lexical stress, length or tone from musical pitch or duration.',
            'Locate material issues and unresolved alternatives by occurrence ID and position; withhold uncertain roles.',
            'Syntax checks and synthetic fixtures do not measure translation or pronunciation accuracy.']}

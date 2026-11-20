"""Explicit recording review of spoken words and language ownership; never infer either."""
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import time
import unicodedata

from stemwise_engine.common import read_bounded_regular_bytes, parse_strict_json_bytes
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.workflow_records import LyricsContentBlocker

PLAN_FILE = 'spoken-targets-review.json'
CHECKS = {'performed-spoken-form', 'language-ownership', 'occurrence-coverage'}


def _digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
        allow_nan=False, separators=(',', ':')).encode('utf-8')).hexdigest()


def needed(original):
    if any(unicodedata.category(c).startswith('N') for c in original['text']):
        return True
    if str(original.get('language', '')).split('-', 1)[0].lower() == 'mul':
        from stemwise_engine.lyrics_alignment import canonical_language
        from stemwise_engine.model_registry import declared_script_owners
        owners = declared_script_owners({canonical_language(value)
            for value in original.get('componentLanguages', [])})
        return owners is None or any(len(languages) != 1 for languages in owners.values())
    return False


def binding(video_id, original, indices, audio_sha):
    from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping, lexical_mapping_identity
    mapping = build_lexical_occurrence_mapping(video_id, original, indices)
    consumed = {key: original.get(key) for key in ('id', 'kind', 'format', 'text', 'language', 'script')}
    consumed['componentLanguages'] = original.get('componentLanguages', [])
    return {'videoId': video_id, 'originalSha256': _digest(consumed), 'audioSha256': audio_sha,
        'occurrenceMappingSha256': lexical_mapping_identity(mapping)['mappingSha256'],
        'occurrenceIds': [row['occurrenceId'] for row in mapping['performedOccurrences']]}


@dataclass(frozen=True)
class ReviewedSpokenPlan:
    root: Path
    raw: bytes
    original_text: str
    words: tuple[tuple[tuple[str, str], ...], ...]

    @property
    def reference(self):
        return {'path': PLAN_FILE, 'sha256': hashlib.sha256(self.raw).hexdigest()}

    def require_current(self):
        if read_bounded_regular_bytes(self.root/PLAN_FILE, max_bytes=MAX_JSON_BYTES, root=self.root) != self.raw:
            raise LyricsContentBlocker('pronunciation-support-unavailable')
        from stemwise_engine.companion_review import _evidence_identity
        for reference in parse_strict_json_bytes(self.raw)['review']['evidence']:
            if _evidence_identity(reference, self.root) != {'path': reference['path'],
                    'state': 'current', 'sha256': reference['sha256']}:
                raise LyricsContentBlocker('pronunciation-support-unavailable')

    def word_lines(self, sources):
        self.require_current()
        return [[word for word, _ in self.words[source]] for source in sources]

    def language_lines(self, sources):
        self.require_current()
        from stemwise_engine.alignment_endpoints import ESPEAK_LANGUAGE_ALIASES
        return [[ESPEAK_LANGUAGE_ALIASES.get(language, language) for _, language in self.words[source]]
                for source in sources]

    def matching_text(self):
        self.require_current()
        return '\n'.join(' '.join(word for word, _ in words) for words in self.words)


def attached(original):
    plan = original.get('_spokenTargets')
    if plan is not None and not isinstance(plan, ReviewedSpokenPlan):
        raise LyricsContentBlocker('pronunciation-support-unavailable')
    return plan


def load(root, video_id, original, indices, audio_sha):
    """Admit exact reviewed inputs, preserving all displayed text and span positions."""
    from stemwise_engine.companion_review import _validate_reference, _evidence_identity
    from stemwise_engine.editorial_review import valid_reviewer
    from stemwise_engine.lyrics_alignment import canonical_language
    from stemwise_engine.lyrics_tokenization import display_words
    root = Path(root)
    path = root/PLAN_FILE
    if not path.exists() and not path.is_symlink():
        if needed(original):
            raise LyricsContentBlocker('pronunciation-support-unavailable')
        return None
    try:
        raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=root)
        value = parse_strict_json_bytes(raw)
        if (not isinstance(value, dict) or set(value) != {'formatVersion', 'binding', 'spans', 'review'}
                or type(value['formatVersion']) is not int or value['formatVersion'] != 1
                or value['binding'] != binding(video_id, original, indices, audio_sha)):
            raise ValueError('Spoken target recording/original/occurrence binding differs')
        review = value['review']
        candidate_sha = _digest({key: value[key] for key in ('formatVersion', 'binding', 'spans')})
        if (not isinstance(review, dict) or set(review) != {'candidateSha256', 'reviewer', 'completedAt',
                'decision', 'checks', 'evidence', 'rationale'}
                or review['candidateSha256'] != candidate_sha or not valid_reviewer(review['reviewer'])
                or review['decision'] != 'admit' or not isinstance(review['checks'], dict)
                or set(review['checks']) != CHECKS or any(status != 'reviewed' for status in review['checks'].values())
                or not isinstance(review['rationale'], str) or not review['rationale'].strip()
                or len(review['rationale']) > 1000 or not isinstance(review['completedAt'], str)
                or not isinstance(review['evidence'], list) or not review['evidence']):
            raise ValueError('Spoken target review is incomplete')
        time.strptime(review['completedAt'], '%Y-%m-%dT%H:%M:%SZ')
        for reference in review['evidence']:
            _validate_reference(reference)
            if _evidence_identity(reference, root) != {'path': reference['path'], 'state': 'current',
                    'sha256': reference['sha256']}:
                raise ValueError('Spoken target review evidence changed')
        declared = {canonical_language(language) for language in (original.get('componentLanguages', [])
            if str(original.get('language', '')).split('-', 1)[0].lower() == 'mul'
            else [original.get('language')])}
        from stemwise_engine.lyrics_normalize import physical_lines
        physical = physical_lines(original['text'])
        spans = value['spans']
        expected = [(source, start, end, text) for source in indices
            for start, end, text in display_words(physical[source])]
        if not isinstance(spans, list) or len(spans) != len(expected):
            raise ValueError('Spoken targets must cover every performed display word exactly once')
        words = [[] for _ in physical]
        assigned = {}
        for span, (source, start, end, text) in zip(spans, expected):
            if (not isinstance(span, dict) or set(span) != {'sourcePhysicalIndex', 'startUtf16', 'endUtf16',
                    'displayText', 'spokenText', 'language'}
                    or any(type(span[key]) is not int for key in ('sourcePhysicalIndex', 'startUtf16', 'endUtf16'))
                    or (span['sourcePhysicalIndex'], span['startUtf16'], span['endUtf16'], span['displayText'])
                        != (source, start, end, text)
                    or not isinstance(span['spokenText'], str) or not span['spokenText'].strip()
                    or '\n' in span['spokenText'] or '\r' in span['spokenText']
                    or any(unicodedata.category(c).startswith('N') for c in span['spokenText'])
                    or span['language'] not in declared or canonical_language(span['language']) != span['language']):
                raise ValueError('Spoken target display span, form or language is invalid')
            from stemwise_engine.lyrics_tokenization import lexical_line_tokens
            if not any(lexical_line_tokens(span['spokenText'], language=span['language'])):
                raise ValueError('Spoken target has no lexical units')
            owner = (source, start, end)
            form = (span['spokenText'], span['language'])
            if owner in assigned:
                if assigned[owner] != form:
                    raise ValueError('One physical display span has conflicting reviewed spoken forms')
            else:
                assigned[owner] = form
                words[source].append(form)
        return ReviewedSpokenPlan(root, raw, original['text'], tuple(tuple(line) for line in words))
    except LyricsContentBlocker:
        raise
    except (OSError, ValueError, KeyError, TypeError, IndexError) as error:
        raise LyricsContentBlocker('pronunciation-support-unavailable') from error


def input_snapshot(root):
    """Current retained review inputs for editorial/attempt invalidation, never admission."""
    from stemwise_engine.companion_review import _evidence_identity
    path = Path(root)/PLAN_FILE
    if not path.exists() and not path.is_symlink():
        return None
    raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=Path(root))
    try:
        value = parse_strict_json_bytes(raw)
        evidence = [_evidence_identity(reference, root) for reference in value['review']['evidence']]
    except (ValueError, KeyError, TypeError):
        evidence = {'state': 'invalid'}
    return {'sha256': hashlib.sha256(raw).hexdigest(), 'evidence': evidence}

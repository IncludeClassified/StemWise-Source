"""Locate competing lyric readings in retained vocal-part transcripts for editorial review."""
import hashlib

from stemwise_engine.common import parse_strict_json_bytes, read_bounded_json, read_bounded_regular_bytes
from stemwise_engine.lyric_comparison import units
from .candidates import BAR


def _bound_json(path, digest, root):
    raw = read_bounded_regular_bytes(path, max_bytes=16*1024*1024, root=root)
    if hashlib.sha256(raw).hexdigest() != digest:
        raise ValueError('Vocal reading evidence changed')
    return parse_strict_json_bytes(raw)


def _tokens(words, language, duration):
    tokens = []
    for word in words:
        start, end = word['startMs'], word['endMs']
        if type(start) is not int or type(end) is not int or not 0 <= start <= end <= duration:
            raise ValueError('Vocal reading is outside the recording')
        if start < end:
            tokens.extend((part, start, end) for part in units(word['text'], language))
    return tokens


def _matches(tokens, phrase, window):
    """Every token in a phrase must lie at least partly inside the same local passage."""
    runs = []
    for i, (first, start, end) in enumerate(tokens):
        if first != phrase[0] or end <= window[0] or start >= window[1]:
            continue
        run = tokens[i:i+len(phrase)]
        if ([t[0] for t in run] == phrase and
                all(t[1] < window[1] and t[2] > window[0] for t in run)):
            runs.append(run)
    return runs


def _occurrence(roles, written, alternate, owner, line_window, same_words):
    hits = []
    owner_window = (owner['startMs'], owner['endMs'])
    mixed = _matches(roles['mix'], alternate, line_window)
    for sheet_part, other in [('lead', 'choir'), ('choir', 'lead')]:
        for (sheet_word,) in _matches(roles[sheet_part], written, owner_window):
            if any(w['wordId'] != owner['wordId'] and
                   w['startMs'] < sheet_word[2] and w['endMs'] > sheet_word[1] for w in same_words):
                continue
            for part_run in _matches(roles[other], alternate, line_window):
                for mix_run in mixed:
                    # A match on either side of a rest cannot bridge two different renditions.
                    common = [(max(p[1], m[1]), min(p[2], m[2])) for p, m in zip(part_run, mix_run)]
                    if any(a >= b for a, b in common):
                        continue
                    windows = [(max(a, sheet_word[1]), min(b, sheet_word[2])) for a, b in common]
                    start, end = max(windows, key=lambda t: t[1]-t[0])
                    if end-start < BAR:
                        continue
                    window = (start, end)
                    if (_matches(roles[other], written, window)
                            or _matches(roles['backing'], written, window)
                            or _echo(roles['backing'], alternate, line_window, window)
                            or _echo(roles[sheet_part], alternate, line_window, window)):
                        continue
                    hits.append({'wordId': owner['wordId'], 'sheetPart': sheet_part, 'alternatePart': other,
                                 'listeningWindowMs': [start, end]})
    return hits[0] if len(hits) == 1 else None


def _echo(tokens, phrase, line_window, overlap):
    return any(any(t[1] < overlap[1] and t[2] > overlap[0] for t in run)
               for run in _matches(tokens, phrase, line_window))


def disputed_part_readings(activity):
    """Read-only review hints from the checked preview. No word or timing decisions."""
    folder = activity.parent
    try:
        receipt = read_bounded_json(folder.parent/'review.private.json', max_bytes=16*1024*1024,
                                    root=folder.parent)
        if receipt['folder'] != folder.name:
            raise ValueError('Vocal reading receipt changed')
        report = _bound_json(activity, receipt['backingActivitySha256'], folder)
        timing = _bound_json(folder/'word-timing.private.json', report['singleVoiceSha256'], folder)
        changes = (timing.get('sheetComparison') or {}).get('versusRecording', {}).get('comparisonUnitChanges')
        if changes is None or not report.get('readings'):
            return {'status': 'not-assessed'}
        candidates = []
        if changes:
            language = timing['language']
            owners = []
            for line in timing['lines']:
                words = line['words']
                window = (words[0]['startMs'], words[-1]['endMs'])
                for word in words:
                    parts = units(word['text'], language)
                    owners.extend([(word, window, parts)] * len(parts))
            roles = {}
            pair = folder/report.get('pairDirectory', 'vocal-parts')
            _bound_json(pair/'pair.private.json', report['pairSha256'], folder)
            for role in ('lead', 'choir', 'mix', 'backing'):
                observation = _bound_json(pair/(role+'-words.private.json'),
                                          report['readings'][role], folder)
                roles[role] = _tokens(observation['words'], language, timing['durationMs'])
            for i, change in enumerate(changes):
                written, alternate = change['sheet'], change['source']
                lo, hi = change['sheetRange']
                if (len(written) != 1 or not alternate or ''.join(written) == ''.join(alternate)
                        or not 0 <= lo < hi <= len(owners) or hi != lo+1):
                    continue
                word, window, parts = owners[lo]
                if list(parts) == written:
                    same_words = [w for w, _, parts in owners if list(parts) == written]
                    hit = _occurrence(roles, written, alternate, word, window, same_words)
                    if hit is not None:
                        candidates.append(dict(hit, comparisonIndex=i))
        return {'status': 'checked', 'candidates': candidates}
    except (KeyError, IndexError, AttributeError, TypeError, ValueError, OSError):
        return {'status': 'unavailable'}

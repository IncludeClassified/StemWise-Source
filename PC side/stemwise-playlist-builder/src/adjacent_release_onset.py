"""Resolve an estimated repeat entrance from two recorded phonetic sequences and a local vocal attack."""
import unicodedata

from stemwise_engine.vocal_release_transition import decide_attack


_VOWELS = frozenset('aeiouyɑɐɒæɛɜɞəɤɨɪɯʊɔœøʌɶɚ')


def _phone(value):
    return unicodedata.normalize('NFC', value).strip('ˈˌ').replace('ː', '')


def _vowel(value):
    return bool(value) and value[0] in _VOWELS


def _signature(expected, events):
    # The initial nucleus can vary across performances. Use the ordered phones
    # after it to identify the repeat; a vowel or one syllable cannot identify it.
    if expected[:1] == ['ʔ']:
        expected = expected[1:]
    if len(expected) < 7 or not _vowel(expected[0]):
        return []
    context = expected[1:6]
    if len({p for p in context if not _vowel(p)}) < 3:
        return []
    matches = []
    for i in range(len(events)-len(context)):
        following = events[i+1:i+1+len(context)]
        if not _vowel(events[i][2]) or events[i][3] < .05:
            continue
        agreements = [(wanted, observed) for wanted, observed in zip(context, following) if wanted == observed[2]]
        if (len(agreements) < len(context)-1
                or len({p for p,e in agreements if not _vowel(p) and e[3] >= .5}) < 2):
            continue
        matches.append((i, [p for _,_,p,_ in following]))
    return matches


def onset(target, trace):
    """A repeated lexical prefix locates the entrance; its measured re-attack supplies the clock."""
    previous = target.get('previous')
    if (target.get('locked') or target.get('status') != 'estimated' or not previous
            or previous['wordId'] == target['wordId'] or previous['spoken'] != target['spoken']
            or previous['language'] != target['language']
            or any(trace.get(key) != target[key] for key in ('wordId','spoken','language'))):
        return None
    expected = [_phone(p) for p in trace['pronunciation'] if p]
    events = [(a,b,_phone(p),score) for a,b,p,score in trace['lexicalEvents']]
    if not events or not expected or not trace['leftMs'] <= previous['startMs'] < target['startMs'] < trace['rightMs']:
        return None
    if any(a >= b or not 0 <= s <= 1 or a < trace['leftMs'] or b > trace['rightMs']
           for a,b,_,s in events) or any(e[1] > n[0] for e,n in zip(events,events[1:])):
        raise ValueError('Invalid unprompted repeat phone sequence')
    matches = _signature(expected, events)
    centers = [(events[i][0]+events[i][1])//2 for i,_ in matches]
    preceding = [j for j,ms in enumerate(centers) if abs(ms-previous['startMs']) <= 400]
    if len(preceding) != 1 or preceding[0]+1 >= len(matches):
        return None
    j = preceding[0]
    before, first = matches[j][0], matches[j+1][0]
    if matches[j][1] != matches[j+1][1] or events[first][3] < .5:
        return None
    # The two prefixes must be physically separate. The previous word's coda
    # must also be present before the new attack. A single melisma cannot be counted twice.
    coda = [e for e in events[before+6:first] if e[2] == expected[-1] and e[3] >= .5]
    if not coda:
        return None
    center = centers[j+1]
    guide = target.get('guide')
    candidates = [c for c in target['candidates'] if c['family']!='owsm-ctc'
                  and c['startMs'] <= center+150 and center <= c['startMs']+250]
    if not candidates or not guide or abs(guide['startMs']-center) > 250:
        return None
    # A later hypothesized onset after another complete observed prefix is a
    # different physical rendition. Never substitute it for the ordered successor.
    a,b,_,_ = events[first]
    measured = decide_attack(trace['spectrum'], a-10, b+10, guide['startMs'],
                             previous_phone_ms=coda[-1][1])
    if measured is None or measured >= target['endMs'] or not any(c['startMs']-150 <= measured < c['endMs'] for c in candidates):
        return None
    return measured

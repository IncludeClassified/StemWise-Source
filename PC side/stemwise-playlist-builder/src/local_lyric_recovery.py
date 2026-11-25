"""Local, private lyric proposals on the Original's mono 16 kHz sample clock."""
from __future__ import annotations

from bisect import bisect_left
from collections import defaultdict
from dataclasses import dataclass
from difflib import SequenceMatcher
from functools import lru_cache
import math
import unicodedata

from stemwise_engine.lyric_comparison import project, units
from stemwise_engine.lyrics_normalize import physical_lines
from stemwise_engine.lyrics_tokenization import optimal_edit_owners

RATE = 16000
HOP = 320


class LocalEvidenceUnavailable(ValueError):
    """Valid recording, but this local model cannot evaluate the requested reading."""


@lru_cache(maxsize=8192)
def key(text, language):
    return units(text, language)


@dataclass(frozen=True)
class Token:
    text: str
    a: int
    b: int


@dataclass(frozen=True)
class Stream:
    audio: str
    frames: int
    family: str
    words: tuple[Token, ...]


@dataclass(frozen=True)
class Owner:
    line: int                            # performed line, not source line
    slot: int
    display: str
    spoken: str
    language: str | None


def owners(original, indices, spoken_plan=None):
    """Use the engine's actual display owners and reviewed mixed-language spoken forms."""
    from stemwise_engine.spoken_targets import attached
    if spoken_plan is None:
        spoken_plan = attached(original)
    lines = physical_lines(original['text'])
    if spoken_plan is not None:
        spoken_plan.require_current()
        if spoken_plan.original_text != original['text']:
            raise ValueError('spoken plan belongs to different lyrics')
    result = []
    for line, source in enumerate(indices):
        if type(source) is not int or not 0 <= source < len(lines):
            raise ValueError('invalid performed line')
        if spoken_plan is None and len(original.get('componentLanguages', [])) > 1:
            language = None
        else:
            language = original.get('language')
        forms = spoken_plan.words[source] if spoken_plan is not None else None
        projection = project(lines[source], language, forms=forms)
        for slot, (_, _, display) in enumerate(projection.words):
            spoken, lang = forms[slot] if forms is not None else (display, language)
            result.append(Owner(line, slot, display, spoken, lang))
    return tuple(result)


@dataclass(frozen=True)
class Anchor:
    sheet: int
    heard: int
    core: int


@dataclass(frozen=True)
class Gap:
    left: Anchor
    right: Anchor
    language: str | None


@dataclass(frozen=True)
class OpenEdge:
    sheet: tuple[int,int]
    heard: tuple[int,int]
    cores: tuple[int,int]
    lines: tuple[int,int]


@dataclass
class Plan:
    sheet: tuple[Owner, ...]
    heard: Stream
    cores: Stream
    anchors: tuple[Anchor, ...]
    gaps: tuple[Gap, ...]
    open_edges: tuple[OpenEdge, ...]
    timing_holes: frozenset[int]


def plan(sheet, heard, cores, *, timing_holes=()):
    if (not sheet or not heard.audio or heard.audio != cores.audio or heard.frames != cores.frames
            or heard.frames <= 0 or heard.family != 'whisper' or cores.family != 'music-ctc'):
        raise ValueError('incompatible recognition sources')
    for stream in (heard, cores):
        last = -1
        for word in stream.words:
            if not (type(word.a) is int and type(word.b) is int and 0 <= word.a <= word.b <= stream.frames
                    and (stream is heard or word.a < word.b)
                    and last <= word.a and word.text.strip()):
                raise ValueError('invalid recognition clock')
            last = word.a if stream is heard else word.b
    wanted = [(o.language, key(o.spoken, o.language)) for o in sheet]
    languages = set(lang for lang, _ in wanted if lang)
    inventory = set(wanted)
    def lookup(text):
        if '<' in text:
            return None
        matches = [(lang, key(text, lang)) for lang in languages if (lang, key(text, lang)) in inventory]
        return matches[0] if len(matches) == 1 else None
    heard_keys = [lookup(w.text) for w in heard.words]
    graph = optimal_edit_owners(wanted, heard_keys)
    core_keys = [lookup(w.text) for w in cores.words]
    ends = [w.b for w in cores.words]
    # Resolve clocks before considering the printed count. A shared acoustic core
    # cannot back two observations, even when only one maps to the sheet.
    peers = defaultdict(list)
    by_heard = {}
    for j, w in enumerate(heard.words):
        k = bisect_left(ends, w.a + 1)
        choices = []
        while w.a < w.b and k < len(ends) and cores.words[k].a < w.b:
            if heard_keys[j] is not None and heard_keys[j] == core_keys[k]:
                choices.append(k)
                peers[k].append(j)
            k += 1
        by_heard[j] = choices
    holes = set(timing_holes)
    anchors = []
    for i, hits in enumerate(graph['matches']):
        if (len(hits) != 1 or i in graph['deleted'] or i in graph['substituted']
                or hits[0] in graph['affectedActual']):
            continue
        j = hits[0]
        if len(by_heard[j]) == 1 and len(peers[by_heard[j][0]]) == 1:
            anchors.append(Anchor(i, j, by_heard[j][0]))
    # Drop both sides of a clock inversion so a bad clock cannot move a repeat.
    suffix = len(cores.words)
    after = []
    for anchor in reversed(anchors):
        after.append(suffix)
        suffix = min(suffix, anchor.core)
    previous = -1
    stable = []
    for a, following in zip(anchors, reversed(after)):
        if previous < a.core < following:
            stable.append(a)
        previous = max(previous, a.core)
    pairs = []
    for left, right in zip(stable, stable[1:]):
        if (right.sheet, right.heard, right.core) == (left.sheet+1, left.heard+1, left.core+1):
            continue
        pairs.append((left,right))
    # A missing endpoint does not disqualify a lexical anchor. Even when all
    # endpoints are missing, interior exact words still have usable neighbors.
    pairs.extend((stable[i-1],stable[i+1]) for i in range(1,len(stable)-1) if stable[i].sheet in holes)
    gaps = []
    for left,right in pairs:
        langs = {o.language for o in sheet[left.sheet:right.sheet+1]}
        gaps.append(Gap(left, right, langs.pop() if len(langs) == 1 else None))
    sizes = len(sheet), len(heard.words), len(cores.words)
    if stable:
        first, last = stable[0],stable[-1]
        lower = (first.sheet,first.heard,first.core)
        upper = (last.sheet+1,last.heard+1,last.core+1)
        if first.sheet in holes:
            lower = tuple(i+1 for i in lower)
        if last.sheet in holes:
            upper = tuple(i-1 for i in upper)
        ranges = [((0,0,0),lower, (sheet[0].line,sheet[first.sheet].line)),
                  (upper,sizes,(sheet[last.sheet].line,sheet[-1].line))]
    else:
        ranges = [((0,0,0),sizes,(sheet[0].line,sheet[-1].line))]
    edges = [OpenEdge(*zip(lo,hi),line_range) for lo,hi,line_range in ranges if lo!=hi]
    return Plan(tuple(sheet), heard, cores, tuple(stable), tuple(gaps),
                tuple(edges), frozenset(holes))


def readings(p, gap):
    l, r, lang = gap.left, gap.right, gap.language
    def reading(words):
        return tuple(' '.join(key(w, lang)) for w in words)
    printed = reading(o.spoken for o in p.sheet[l.sheet+1:r.sheet])
    primary = reading(w.text for w in p.heard.words[l.heard+1:r.heard])
    physical = reading(w.text for w in p.cores.words[l.core+1:r.core])
    return printed, primary, physical


def options(p, gap):
    lang = gap.language
    printed, primary, physical = readings(p,gap)
    rivals = {printed, primary, physical, ()}
    lexicon = sorted({' '.join(key(o.spoken, lang)) for o in p.sheet if o.language == lang} - {''})
    for i, word in enumerate(physical):
        before, after = physical[:i], physical[i+1:]
        alternatives = [w for w in lexicon if w != word]
        if alternatives:
            foil = max(alternatives, key=lambda other: SequenceMatcher(None, word, other, autojunk=False).ratio())
            rivals.add(before+(foil,)+after)
        rivals.add(before+after)
        rivals.add(before+(word,word)+after)
        vowels = [ch for ch in word if ch in 'aeıioöuüâîûاوي']
        if vowels:
            rivals.add(before+(vowels[-1],)+after)
    return printed, primary, physical, tuple(sorted(rivals))


def skeleton(word):
    return ''.join(c for c in word if unicodedata.category(c) != 'Mn' and c != 'ـ')


def physical_id(audio, token, language):
    return audio, token.a, token.b, language, key(token.text,language)


def crop(p, gap):
    l, r = gap.left, gap.right
    a = min(p.heard.words[l.heard].a, p.cores.words[l.core].a)
    b = max(p.heard.words[r.heard].b, p.cores.words[r.core].b)
    return max(0, a-RATE//2), min(p.heard.frames, b+RATE//2)


@dataclass(frozen=True)
class PriorPhrase:
    audio: str
    language: str
    words: tuple[str, ...]
    intervals: tuple[tuple[int,int] | None, ...]
    source: str
    raw_intervals: tuple[tuple[int,int] | None, ...] | None = None


def cached_phrases(prior, audio, lang, spoken, evidence):
    """Require both neighboring words and every interior owner on the same physical clocks."""
    wanted = [key(w,lang) for w in spoken]
    for phrase in prior:
        if phrase.audio != audio or phrase.language != lang:
            continue
        if len(phrase.words) != len(phrase.intervals):
            raise ValueError('incomplete prior phrase')
        raw = phrase.intervals if phrase.raw_intervals is None else phrase.raw_intervals
        if len(raw) != len(phrase.words):
            raise ValueError('incomplete raw prior phrase')
        words = [key(w,lang) for w in phrase.words]
        for i in range(len(words)-len(wanted)+1):
            if words[i:i+len(wanted)] != wanted:
                continue
            spans = phrase.intervals[i:i+len(wanted)]
            if all(s is not None and s[0] < c.b and c.a < s[1]
                   for s,c in zip(spans,evidence,strict=True)):
                yield phrase.source, spans, raw[i:i+len(wanted)]


def _heard_onset(p, gap, core):
    """Require a single exact free-decoder word assigned to one physical core."""
    def intersects(left, right):
        return max(left.a, right.a) < min(left.b, right.b)

    matches = [(i, word) for i, word in enumerate(p.heard.words) if intersects(word, core)]
    if len(matches) != 1:
        return None
    index, word = matches[0]
    if (gap.left.heard < index < gap.right.heard
            and key(word.text, gap.language) == key(core.text, gap.language)
            and sum(intersects(word, other) for other in p.cores.words) == 1):
        return word.a
    return None


def _supported_start(landmark, region, measurements, owner, locked, sources):
    left, core, right = region
    low, high = left.b, min(core.a+HOP, right.a-1)
    for other, interval in locked.items():
        if other[0] != owner[0] or other == owner:
            continue
        if other[2] <= core.a:
            low = max(low, interval[1])
        elif core.b <= other[1]:
            high = min(high, interval[0]-1)
        else:
            return None
    if type(landmark) is not int or not low <= landmark <= high:
        return None
    candidates = []
    tolerance = RATE//10
    for measurement in measurements:
        if not isinstance(measurement, dict) or set(measurement) != {'sample', 'bounds', 'source'}:
            return None
        sample, bounds, source = (measurement[k] for k in ('sample', 'bounds', 'source'))
        if (not isinstance(source, str) or not isinstance(bounds, list) or len(bounds) != 2
                or any(type(n) is not int for n in bounds) or (source, tuple(bounds)) not in sources
                or type(sample) is not int or not bounds[0] < sample < bounds[1]
                or not low <= sample <= high or abs(sample-landmark) > tolerance):
            return None
        candidates.append((abs(sample-landmark), sample, source, tuple(bounds)))
    if not candidates or max(c[1] for c in candidates)-min(c[1] for c in candidates) > tolerance:
        return None
    distance, sample, source, _ = min(candidates)
    if any(c[0] == distance and c[1] != sample for c in candidates):
        return None
    return {'sample': sample, 'source': source, 'landmarkSample': landmark,
            'witness': 'unprompted Whisper', 'timerStarts': measurements}


def recover(p, gap, verifier, timer, *, locked, phones, prior=(), previous=()):
    """Both callbacks receive only Original-clock inputs. Locked is an immutable overlay input.

    The returned additions are estimated private intervals indexed by Original and
    the physical free-decoder core. No inferred edit mutates the source sheet.
    """
    if gap not in p.gaps:
        raise ValueError('gap does not belong to the plan')
    l, r, lang = gap.left, gap.right, gap.language
    out = dict(status='unresolved', bounds=crop(p,gap), reading=None, additions=[], withheld=[],
               publicationAuthority=False)
    def stop(reason):
        out['reason'] = reason
        return out
    a,b = out['bounds']
    if lang not in {'en', 'tr', 'ar'} or b-a > 30*RATE:
        return stop('unsupported language ownership or model window exceeded')
    printed, primary, physical, rivals = options(p, gap)
    out['competingReadings'] = {'sheet': printed, 'whisper': primary, 'musicCtc': physical}
    if any(not w for s in rivals for w in s) or len(rivals) < 2:
        return stop('missing distinguishable alternatives')
    flanks = (p.sheet[l.sheet].spoken, p.sheet[r.sheet].spoken)
    cores = p.cores.words[l.core+1:r.core]
    if any('<' in w.text for w in cores):
        return stop('unrecognized acoustic symbols')
    if lang.split('-')[0] == 'ar' and any(
            x != y and skeleton(x) == skeleton(y) for x in physical for alt in (printed,primary) for y in alt):
        return stop('unresolved pronunciation marks')
    if (lang.split('-')[0]=='ar' and physical!=printed and any(skeleton(w)==w for w in physical)
            and any(skeleton(o.spoken)!=o.spoken for o in p.sheet if o.language==lang)):
        return stop('missing Arabic reading has no vowel-mark evidence')
    if physical!=printed and ' '.join(physical)==' '.join(printed):
        return stop('ambiguous display word ownership')
    try:
        scores = verifier(p.heard.audio, (a,b), lang, flanks, rivals)
    except LocalEvidenceUnavailable as error:
        return stop(str(error))
    if set(scores) != set(rivals) or any(not math.isfinite(v) for v in scores.values()):
        raise ValueError('incomplete or nonfinite same-window verifier result')
    out['scores'] = [(list(k),scores[k]) for k in rivals]
    winners = [s for s in rivals if scores[s] == max(scores.values())]
    if winners != [physical]:
        return stop('acoustic disagreement or tied alternatives')
    try:
        ambiguous = any(len(alt) == len(physical) and alt != physical and
            all(x == y or phones(x,lang) == phones(y,lang) for x,y in zip(alt,physical)) for alt in rivals)
    except LocalEvidenceUnavailable as error:
        return stop(str(error))
    if ambiguous:
        return stop('acoustically indistinguishable alternatives')
    if not physical and primary:
        return stop('deletion disputed by unprompted recognition')
    if physical != printed and len({o.language for o in p.sheet}) > 1:
        return stop('changed mixed-language reading has no reviewed word owner')
    # A text score can favor a count without locating the repeats. Require a
    # separate one-to-one clock observation for each repeated surface in this gap.
    for word in set(physical):
        if physical.count(word) > 1:
            c = [w for w,s in zip(cores,physical) if s == word]
            h = [w for w,s in zip(p.heard.words[l.heard+1:r.heard],primary) if s == word]
            if len(c)!=len(h) or any([j for j,v in enumerate(h) if max(w.a,v.a)<min(w.b,v.b)] != [i]
                                      for i,w in enumerate(c)):
                return stop('repeat lacks distinct independent clocks')
    out.update(status='candidate' if physical else 'no-match', reading=physical)
    if not physical:
        return out
    # Preserve display only when the entire ordered reading agrees. A proposed
    # off-sheet or count-changing reading retains physical IDs, never a guessed sheet slot.
    display = ([o.display for o in p.sheet[l.sheet+1:r.sheet]] if physical == printed else
               [w.text for w in cores])
    return time_selected(p, gap, out, timer, locked, prior, previous,
                         (flanks[0], *physical, flanks[1]), display,
                         tuple(range(l.core+1, r.core)))


def time_selected(p, gap, out, timer, locked, prior, previous, spoken, display, positions):
    """Time a selected passage. Only words with physically assigned cores may receive intervals."""
    l,r,lang = gap.left,gap.right,gap.language
    a,b = out['bounds']
    assigned = [k for k in positions if k is not None]
    if (len(spoken) != len(positions)+2 or len(display) != len(positions)
            or assigned != sorted(set(assigned)) or any(not l.core < k < r.core for k in assigned)):
        raise ValueError('passage word does not own a distinct interior clock')
    indices = [l.core,*positions,r.core]
    def core_for(k, word):
        core = p.cores.words[k]
        return core if word == ' '.join(key(core.text, lang)) else Token(word, core.a, core.b)

    evidence = [p.cores.words[l.core],
                *(None if k is None else core_for(k, w) for k, w in zip(positions, spoken[1:-1], strict=True)),
                p.cores.words[r.core]]
    ids = [None if core is None else physical_id(p.heard.audio, core, lang) for core in evidence[1:-1]]

    def region(i):
        k = indices[i]
        return p.cores.words[k-1], evidence[i], p.cores.words[k+1]
    out['physicalOccurrences'] = [owner for owner in ids if owner is not None]
    out['timingSources'] = []
    out['retainedOccurrences'] = [owner for owner in ids if owner in locked]
    if all(i is None or i in locked for i in ids):
        return out
    def acceptable(i,interval,bounds):
        if interval is None:
            return False
        x,y = interval
        left,core,right = region(i)
        if (not all(type(n) is int for n in (x,y)) or not bounds[0] < x < y < bounds[1]
                or x > core.a+HOP or y < core.b-HOP
                or x < left.b or y > right.a):
            return False
        return not any(k[0] == p.heard.audio and max(x,v[0]) < min(y,v[1]) for k,v in locked.items())

    reusable = (list(cached_phrases(prior,p.heard.audio,lang,spoken,evidence))
                if None not in evidence else [])
    wider = max(0,a-RATE), min(p.heard.frames,b+RATE)
    sources = {('local Scicom', (a,b)), *((source, (a,b)) for source,_,_ in reusable)}
    if wider != (a,b) and wider[1]-wider[0] <= 30*RATE:
        sources.add(('wider Scicom', wider))
    measurements = defaultdict(list)
    missing = {}
    pending, needs, origins = [], [], {}

    def note(i, interval, bounds, source):
        if interval is not None:
            measurements[i].append({'sample': interval[0], 'bounds': list(bounds), 'source': source})

    def validate_start(i, candidates, retained):
        return _supported_start(_heard_onset(p, gap, region(i)[1]), region(i),
                                candidates, ids[i-1], retained, sources)

    for i in range(1, len(ids)+1):
        for source, _, raw in reusable:
            note(i, raw[i], (a,b), source)
    saved = {}
    for i, owner in enumerate(ids, 1):
        if owner is None or owner in locked:
            continue
        matches = [part for part in previous if isinstance(part, dict)
                   and part.get('occurrence') == [*owner[:4], list(owner[4])]]
        if len(matches) != 1:
            continue
        part, = matches
        start = part.get('start')
        if (part.get('word') != display[i-1] or 'end' not in part or part['end'] is not None
                or not isinstance(start, dict) or type(start.get('sample')) is not int
                or not isinstance(start.get('timerStarts'), list)):
            continue
        verified = validate_start(i, start['timerStarts'], locked)
        if (verified is not None and verified == start
                and all(m['source'] in {'local Scicom', 'wider Scicom'} or m in measurements[i]
                        for m in start['timerStarts'])):
            saved[i] = verified

    for i, (owner, word) in enumerate(zip(ids, display), 1):
        if owner is None or owner in locked:
            continue
        cached = {s[i] for _,s,_ in reusable}
        if (len(cached)==1 and acceptable(i,next(iter(cached)),(a,b))
                and all(acceptable(i, raw[i], (a,b)) for _,_,raw in reusable)):
            pending.append((owner,word,next(iter(cached))))
            origins[owner] = sorted({source for source,_,_ in reusable})
        elif len(cached)>1:
            missing[i] = 'prior timers disagree on this occurrence'
        else:
            needs.append(i)

    def ask(bounds):
        try:
            times = timer(p.heard.audio,bounds,lang,spoken)
        except LocalEvidenceUnavailable:
            times = None
        if times is not None and len(times) != len(spoken):
            raise ValueError('timer ownership mismatch')
        return times if times is not None else [None]*len(spoken)
    if needs:
        short = ask((a,b))
        # A long interval after the final acoustic token can be a sung tail or a
        # gap. Check crop sensitivity, never interpolate toward the next onset.
        widen = [i for i in needs if not acceptable(i,short[i],(a,b))
                 or region(i)[2].a-evidence[i].b >= 400*RATE//1000]
        second = ask(wider) if widen and ('wider Scicom', wider) in sources else None
        for i in needs:
            note(i, short[i], (a,b), 'local Scicom')
            if second is not None:
                note(i, second[i], wider, 'wider Scicom')
            first_ok = acceptable(i,short[i],(a,b))
            interval = short[i] if first_ok else None
            source = 'local Scicom'
            if i in widen:
                wide_ok = second is not None and acceptable(i,second[i],wider)
                if not wide_ok or (first_ok and max(abs(x-y) for x,y in zip(short[i],second[i]))>RATE//10):
                    interval = None
                elif not first_ok:
                    interval, source = second[i], 'wider Scicom'
            if interval is None:
                missing[i] = 'no consistent interval inside physical and retained bounds'
            else:
                pending.append((ids[i-1],display[i-1],interval))
                origins[ids[i-1]] = [source]
    # A pair of timers assigning the same tail to two new words has unresolved ownership.
    positions = {owner: i for i, owner in enumerate(ids, 1)}
    compatible = []
    for item in pending:
        i = positions[item[0]]
        if i in saved and item[2][0] != saved[i]['sample']:
            missing[i] = 'timer interval moves the retained start'
        else:
            compatible.append(item)
    for item in compatible:
        if any(item[0] != other[0] and max(item[2][0], other[2][0]) < min(item[2][1], other[2][1])
               for other in compatible):
            missing[positions[item[0]]] = 'overlapping new intervals'
        else:
            out['additions'].append(item)
    out['timingSources'] = [origins[item[0]] for item in out['additions']]
    retained = {**locked, **{owner: times for owner, _, times in out['additions']}}
    for i, reason in missing.items():
        out['withheld'].append([display[i-1], reason])
        start = validate_start(i, (saved[i]['timerStarts'] if i in saved else measurements[i]), retained)
        if start is not None:
            out.setdefault('wordBoundaries', []).append({'occurrence': ids[i-1], 'word': display[i-1],
                'start': start, 'end': None, 'reason': reason})
    return out


def recover_regions(p, verifier, timer, *, locked, phones, prior=(), performed_lines=None,
                    persist=None, load_previous=None, passage=None, verify_locations=None):
    """Run all bracketed disputes in scope; keep existing times and unbounded edges visible."""
    overlay = dict(locked)
    results = []
    def retain(row):
        if persist is not None:
            persist(row)
        results.append(row)
    def finish(row, sub, span):
        row['flanks'] = [p.sheet[i].display for i in (sub.left.sheet,sub.right.sheet)]
        row.update(span)
        row['physicalFlanks'] = [physical_id(p.cores.audio,p.cores.words[a.core],p.sheet[a.sheet].language)
                                 for a in (sub.left,sub.right)]
        retain(row)
        for owner,_,times in row['additions']:
            overlay[owner] = times
    for edge in p.open_edges:
        first,last = edge.lines
        if performed_lines is None or any(first<=line<=last for line in performed_lines):
            if passage is not None and passage.open_edges and edge.sheet[0] < edge.sheet[1]:
                from stemwise_engine.local_open_passage import locate_edge
                for row in locate_edge(p, edge, passage, verifier, performed_lines, verify_locations):
                    retain(row)
                continue
            retain(dict(status='unresolved',reason='missing independent flanking anchors',
                sheetRange=edge.sheet,recognitionRanges=[edge.heard,edge.cores],performedLines=[first,last],
                reading=None,additions=[],withheld=[],publicationAuthority=False))
    for gap in p.gaps:
        first, last = p.sheet[gap.left.sheet].line, p.sheet[gap.right.sheet].line
        if performed_lines is not None and not any(first <= line <= last for line in performed_lines):
            continue
        skipped = False
        if gap.language and not any(gap.left.sheet < i < gap.right.sheet for i in p.timing_holes):
            printed, heard, decoded = readings(p,gap)
            if printed == heard == decoded:
                inside = p.cores.words[gap.left.core+1:gap.right.core]
                if passage is None or all(physical_id(p.cores.audio,w,gap.language) in overlay for w in inside):
                    continue
                skipped = True
        span = {'performedLines': [first,last], 'sheetRange': [gap.left.sheet+1, gap.right.sheet],
                'recognitionRanges': [[gap.left.heard+1, gap.right.heard], [gap.left.core+1, gap.right.core]]}
        previous = load_previous(span) if load_previous is not None else ()
        row = None
        if not skipped:
            try:
                row = recover(p,gap,verifier,timer,locked=overlay,phones=phones,prior=prior,previous=previous)
            except LocalEvidenceUnavailable as error:
                row = dict(status='unresolved', reason=str(error), bounds=crop(p,gap),
                           additions=[], withheld=[], reading=None, publicationAuthority=False)
        if passage is None or gap.language not in ('en','tr','ar') or (row and row['status']!='unresolved'):
            if row is not None:
                finish(row,gap,span)
            continue
        from stemwise_engine.local_passage_recovery import passage_blocks
        for sub in passage_blocks(p,gap):
            scope = dict(span,recognitionRanges=[[sub.left.heard+1,sub.right.heard],[sub.left.core+1,sub.right.core]])
            previous = load_previous(scope) if load_previous is not None else ()
            try:
                part = passage.decide(p,gap,sub,verifier,timer,phones,overlay,prior,previous)
            except LocalEvidenceUnavailable as error:
                part = dict(status='unresolved',reason=str(error),bounds=crop(p,sub),additions=[],
                            withheld=[],reading=None,publicationAuthority=False)
            part['passageScope'] = span
            part['previousDisposition'] = row.get('reason') if row else 'unplaced matching text'
            finish(part,sub,scope)
    return results,overlay

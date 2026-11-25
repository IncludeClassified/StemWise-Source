"""Compare competing passage locations on one recording clock. No word-level authority."""
from stemwise_engine.local_lyric_recovery import key
from stemwise_engine.local_passage_recovery import certain


def overlaps(a, b):
    return max(a[0], b[0]) < min(a[1], b[1])


def character_matches(words, intervals, chars, language):
    """Maximum time-compatible character matches; each observed character can be spent once."""
    previous = [0]*(len(chars)+1)
    for word,(a,b) in zip(words, intervals, strict=True):
        for ch in '|'.join(key(word,language)):
            current = [0]
            for pos,(obs,s,e) in enumerate(chars):
                match = ch==obs and 2*a<=s+e<2*b
                current.append(max(current[-1],previous[pos+1],previous[pos]+int(match)))
            previous = current
    return previous[-1]


def displaced_passages(heard, sections, words, language, chars, coarse, proposals, *, locked=None,
                       recognition_owners=None):
    """Bracket a contiguous moved passage between physical anchors on the proposed reading.

    The interior has conditional timing only. A return entry is never a wording decision.
    """
    if len(coarse) != len(proposals) or len(words) != len(coarse):
        raise ValueError('timing owner count differs')
    if language not in ('en','tr','ar'):
        return []
    locked = {} if locked is None else locked
    if any(i<0 or i>=len(words) for i in locked):
        raise ValueError('unknown locked occurrence')
    expected = [key(w,language) for w in words]
    qwords = [(key(w,language),block['bounds']) for block in sections for w in block['words']]
    if recognition_owners is None:
        first = certain(expected, [key(w['text'],language) for w in heard])
        second = certain(expected, [w for w,_ in qwords])
    else:
        first, second = recognition_owners
    spans = []
    def direction(i):
        if i in locked or i == len(coarse) or not proposals[i] or not coarse[i]:
            return 0
        a,b = coarse[i]
        s,e = proposals[i]
        if a >= b or s >= e:
            return 0
        return 1 if s>=b else -1 if e<=a else 0
    i = 0
    while i < len(coarse):
        side = direction(i)
        if not side:
            i += 1
            continue
        j = i+1
        while direction(j)==side and proposals[j][0]>proposals[j-1][0]:
            j += 1
        witnesses = [k for k in range(i,j) if k in first and
                     overlaps(heard[first[k]]['bounds'], proposals[k]) and not overlaps(heard[first[k]]['bounds'], coarse[k])]
        local_words = [k for k in range(i,j) if k in second and overlaps(qwords[second[k]][1],proposals[k])]
        if len(witnesses)>=2 and len(local_words)>=2:
            a,b = witnesses[0], witnesses[-1]+1
            # The displaced-hypothesis contest and the shared splice have different owners.
            while a>i and coarse[a-1][0]>=proposals[a][0]:
                a-=1
            while b<j and coarse[b][0]<=proposals[b-1][0]:
                b+=1
            contest_a,contest_b=a,b
            local_words = [slot for slot in local_words if a <= slot < b]
            if len(local_words) < 2:
                i = j
                continue
            def shared(n):
                return n not in locked and proposals[n] and coarse[n] and overlaps(proposals[n],coarse[n])
            while a>0 and shared(a-1) and coarse[a-1][0]>=proposals[a][0] and proposals[a-1][0]<proposals[a][0]:
                a-=1
            while b<len(coarse) and shared(b) and proposals[b-1][0]>=coarse[b][0] and proposals[b-1][0]<proposals[b][0]:
                b+=1
            ordered = ((a==0 or (coarse[a-1] and coarse[a-1][0]<proposals[a][0])) and
                       (b==len(coarse) or (coarse[b] and proposals[b-1][0]<coarse[b][0])))
            lo = min(t[0] for t in proposals[a:b])
            hi = max(t[1] for t in proposals[a:b])
            locked_order = all((n<a and t[1]<=lo) or (n>=b and t[0]>=hi)
                               for n,t in locked.items())
            if ordered and locked_order:
                left=min(lo,min(t[0] for t in coarse[a:b]))
                right=max(hi,max(t[1] for t in coarse[a:b]))
                observed=[v for v in chars if 2*left<=v[1]+v[2]<2*right]
                region=slice(contest_a,contest_b)
                target=words[region]
                raw=character_matches(target,coarse[region],observed,language)
                alternate=character_matches(target,proposals[region],observed,language)
                foil=character_matches(target[1:]+target[:1],proposals[region],observed,language)
                if alternate>max(raw,foil,0):
                    spans.append(dict(start=a,stop=b,contest=[contest_a,contest_b],witnesses=witnesses,unpromptedSectionWords=local_words,
                                      originalMatches=raw,newMatches=alternate,shiftedWordsMatches=foil))
        i = j
    return spans


def competing_locations(p, prior, independent_words, chars, locks):
    """Nominate regions from verified cached timing runs and free Original recognition."""
    if not prior or not independent_words or not chars:
        return []
    languages = {o.language for o in p.sheet}
    # A whole-recording transcription has no reviewed per-word language clock.
    if len(languages) != 1 or not languages <= {'en','tr','ar'}:
        return []
    language, = languages
    expected = [key(o.spoken, language) for o in p.sheet]
    groups = {}
    for phrase in prior:
        if phrase.audio != p.heard.audio or phrase.language != language or len(phrase.words) < 2:
            continue
        tokens = [key(w,language) for w in phrase.words]
        slots = [i for i in range(len(expected)-len(tokens)+1) if expected[i:i+len(tokens)] == tokens]
        if len(slots) == 1:
            groups.setdefault((slots[0],len(tokens)), []).append(phrase)

    if not groups:
        return []
    heard = [dict(text=w.text, bounds=(w.a,w.b)) for w in p.heard.words]
    sections = [dict(bounds=(0,p.heard.frames), words=independent_words)]
    whisper = certain(expected, [key(w.text,language) for w in p.heard.words])
    qwen = certain(expected, [key(w,language) for w in independent_words])
    results = []
    for (start,size), phrases in groups.items():
        indices = range(start,start+size)
        words = [p.sheet[i].spoken for i in indices]
        owners = [{i-start: graph[i] for i in indices if i in graph} for graph in (whisper,qwen)]
        observed = [heard[whisper[i]]['bounds'] if i in whisper else None for i in indices]
        for baseline in phrases:
            candidates = [(other.source,other.intervals) for other in phrases if other != baseline]
            candidates.append(('unprompted Whisper word windows',observed))
            for source, candidate in candidates:
                if candidate == baseline.intervals:
                    continue
                for span in displaced_passages(heard, sections, words, language, chars, baseline.intervals,
                                               candidate, recognition_owners=owners):
                    lo, hi = span['start'], span['stop']
                    def extent(times):
                        return min(t[0] for t in times[lo:hi]), max(t[1] for t in times[lo:hi])
                    old, new = extent(baseline.intervals), extent(candidate)
                    swept = min(old[0],new[0]), max(old[1],new[1])
                    if any(overlaps(bounds, swept) for bounds in locks.values()):
                        continue
                    results.append(dict(sheetRange=[start+lo,start+hi], incumbent=old, alternative=new,
                        priorSource=baseline.source, alternativeSource=source,
                        contest=[start+i for i in span['contest']],
                        whisperSlots=[start+i for i in span['witnesses']],
                        independentTextSlots=[start+i for i in span['unpromptedSectionWords']],
                        characterCounts=[span[k] for k in ('originalMatches','newMatches','shiftedWordsMatches')]))
    return results

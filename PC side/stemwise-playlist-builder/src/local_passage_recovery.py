"""Private passage competition on the Original clock. Clocked models nominate locations; only the timer proposes ends."""
from collections import defaultdict
from dataclasses import dataclass
from difflib import SequenceMatcher
from functools import lru_cache
import math

from stemwise_engine.local_lyric_recovery import (
    Anchor, Gap, HOP, RATE, Token, crop, key, physical_id, skeleton, time_selected,
)
from stemwise_engine.lyrics_tokenization import optimal_edit_owners


def forms(tokens, language):
    return tuple(' '.join(key(w,language)) for w in tokens)


def alternate_spellings(logp,vocab):
    """Open CTC prefix beam on the existing physical token. This only nominates spellings."""
    import numpy as np
    inverse = {i:s for s,i in vocab.items()}

    @lru_cache(maxsize=512)
    def decode(core,lang):
        prefixes = {(): (0.,-math.inf)}
        for frame in logp[core.a//HOP:(core.b+HOP-1)//HOP]:
            best = np.argsort(-frame.astype(np.float32),kind='stable')[:8]
            next_row = defaultdict(lambda:[-math.inf,-math.inf])
            for prefix,(blank,sound) in prefixes.items():
                total = float(np.logaddexp(blank,sound))
                entry = next_row[prefix]
                entry[0] = np.logaddexp(entry[0],total+float(frame[0]))
                for raw in best:
                    i = int(raw)
                    if i==0:
                        continue
                    score = float(frame[i])
                    same = bool(prefix and prefix[-1]==i)
                    if same:
                        entry[1] = np.logaddexp(entry[1],sound+score)
                    dest = next_row[prefix+(i,)]
                    dest[1] = np.logaddexp(dest[1],(blank if same else total)+score)
            kept = sorted(next_row,key=lambda s:float(np.logaddexp(*next_row[s])),reverse=True)[:8]
            prefixes = {s:next_row[s] for s in kept}
        likelihood = {s:float(np.logaddexp(*v)) for s,v in prefixes.items()}
        best = [s for s,value in likelihood.items() if value==max(likelihood.values())]
        if len(best)!=1 or not best[0] or any(i in (1,2) for i in best[0]):
            return ()
        return (' '.join(key(''.join(inverse[i] for i in best[0]),lang)),)
    return decode


def overlap(x,y):
    return max(x.a,y.a)<min(x.b,y.b)


def exclusive_centers(a,b,observations,cores):
    """Allow a spill across an unrelated edge only when both centers have unique owners."""
    def contains(word,double_center):
        return 2*word.a <= double_center < 2*word.b
    return (contains(a,b.a+b.b) and contains(b,a.a+a.b)
            and sum(contains(h,b.a+b.b) for h in observations)==1
            and sum(contains(c,a.a+a.b) for c in cores)==1)


def certain(expected, actual):
    graph = optimal_edit_owners(expected,actual)
    return {i:ids[0] for i,ids in enumerate(graph['matches']) if len(ids)==1
            and i not in graph['deleted'] and i not in graph['substituted']
            and ids[0] not in graph['affectedActual']}


def passage_blocks(p,g):
    """Long passages share clocked junctions. Every uncovered link remains an explicit refusal."""
    if crop(p,g)[1]-crop(p,g)[0]<=30*RATE:
        return [g]
    l,r = g.left,g.right
    inventory = defaultdict(list)
    for i in range(l.sheet,r.sheet+1):
        inventory[key(p.sheet[i].spoken,g.language)].append(i)
    # A junction identifies a physical sound. Its printed slot only supplies a
    # spelling for the flank and may recur on later passes through the line.
    chain = [l]
    for k in range(l.core+1,r.core):
        c = p.cores.words[k]
        word = key(c.text,g.language)
        if word not in inventory or not word:
            continue
        js = [j for j in range(l.heard+1,r.heard)
              if overlap(c,p.heard.words[j]) and key(p.heard.words[j].text,g.language)==word]
        if len(js)!=1 or js[0]<=chain[-1].heard:
            continue
        h = p.heard.words[js[0]]
        if sum(overlap(h,x) for x in p.cores.words)!=1:
            continue
        chain.append(Anchor(inventory[word][0],js[0],k))
    chain.append(r)
    blocks = []
    i = 0
    while i<len(chain)-1:
        j = i+1
        while j+1<len(chain):
            test = Gap(chain[i],chain[j+1],g.language)
            a,b = crop(p,test)
            if b-a>30*RATE:
                break
            j += 1
        # The next block starts at this same pair of Original-clock observations.
        blocks.append(Gap(chain[i],chain[j],g.language))
        i = j
    return blocks


def sheet_paths(p,outer,g):
    left,right = outer.left,outer.right
    language = g.language
    start,end = [key(p.sheet[x.sheet].spoken,language) for x in (g.left,g.right)]
    lo = next(i for i in range(left.sheet+1) if p.sheet[i].line==p.sheet[left.sheet].line)
    hi = max(i for i in range(right.sheet,len(p.sheet)) if p.sheet[i].line==p.sheet[right.sheet].line)
    sheet = p.sheet[lo:hi+1]
    paths = defaultdict(set)
    for i,x in enumerate(sheet):
        if key(x.spoken,language)!=start:
            continue
        for j,y in enumerate(sheet):
            if key(y.spoken,language)!=end:
                continue
            if i<j:
                inside = sheet[i+1:j]
            elif x.line==y.line and g.left.core<g.right.core:
                # One traversal of the refrain boundary. The audio must still win
                # against omission, one-copy, and extra-copy alternatives.
                inside = tuple(o for o in sheet[i+1:] if o.line==x.line)+tuple(o for o in sheet[:j] if o.line==x.line)
            else:
                continue
            if all(o.language==language for o in (x,*inside,y)):
                paths[forms((o.spoken for o in inside),language)].add(tuple(o.display for o in inside))
    return paths


def sequences(streams):
    """Include intact observations and splices at an unambiguous shared word, in both directions."""
    paths = set(streams)
    for x in streams:
        for y in streams:
            if x==y:
                continue
            for i,j in certain(x,y).items():
                if x.count(x[i])==y.count(y[j])==1:
                    paths.add(x[:i+1]+y[j+1:])
    return paths


def common_owners(first,other):
    if ''.join(first)!=''.join(other):
        return certain(first,other)
    def spans(words):
        start = 0
        for w in words:
            stop = start+len(w)
            yield start,stop,w
            start = stop
    lookup = {span:i for i,span in enumerate(spans(other))}
    return {i:lookup[span] for i,span in enumerate(spans(first)) if span in lookup}


def insensitive_timer(timer,flanks,variants):
    """Correlated timer contexts can veto a coordinate, never corroborate it."""
    def measure(audio,bounds,lang,words):
        times = timer(audio,bounds,lang,words)
        if times is None:
            return None
        if len(times)!=len(words):
            raise ValueError('passage timer ownership mismatch')
        result = list(times)
        for other,owners in variants.items():
            alt = timer(audio,bounds,lang,(flanks[0],*other,flanks[1]))
            if alt is not None and len(alt)!=len(other)+2:
                raise ValueError('rival timer ownership mismatch')
            for i,j in owners.items():
                t = result[i+1]
                v = alt[j+1] if alt is not None else None
                if t is None or v is None or abs(t[0]-v[0])>RATE//10:
                    result[i+1] = None
                elif t[1] is None or v[1] is None or abs(t[1]-v[1])>RATE//10:
                    result[i+1] = (t[0],None)
        return result
    return measure


@dataclass
class Passage:
    # Both optional sources only nominate alternatives. Neither casts a vote.
    alternatives: object = None
    vocals: tuple = ()
    open_edges: bool = False
    locations: dict | None = None
    drift: tuple = ()

    def decide(self,p,outer,g,verifier,timer,phones,locked,prior,previous):
        a,b = crop(p,g)
        row = dict(status='unresolved',bounds=(a,b),reading=None,additions=[],withheld=[],
                   publicationAuthority=False)
        def refuse(reason):
            row['reason'] = reason
            return row
        if b-a>30*RATE:
            return refuse('no common interior clock within model context')
        l,r,lang = g.left,g.right,g.language
        cores = p.cores.words[l.core+1:r.core]
        heard = p.heard.words[l.heard+1:r.heard]
        c = forms((t.text for t in cores),lang)
        h = forms((t.text for t in heard),lang)
        prints = sheet_paths(p,outer,g)
        printed = set(prints)
        streams = printed | {c,h}
        for vocal in self.vocals:
            # Only use a derived observation bracketed by the same two words at
            # the same clocks. Never treat it as an independent occurrence or end.
            bounds = []
            for anchor in (l,r):
                core = p.cores.words[anchor.core]
                hits = [j for j,v in enumerate(vocal) if overlap(core,v) and key(v.text,lang)==key(core.text,lang)]
                bounds.append(hits)
            if all(len(x)==1 for x in bounds) and bounds[0][0]<bounds[1][0]:
                streams.add(forms((v.text for v in vocal[bounds[0][0]+1:bounds[1][0]]),lang))
        if any(not w or '<' in w for path in streams for w in path):
            return refuse('unrepresented comparison unit')
        paths = sequences(streams)
        # Word identity, count and an empty passage all compete on identical PCM.
        rivals = paths | {()}
        lexicon = {w for path in printed for w in path} | set(c) | set(h)
        for path in paths:
            for i,w in enumerate(path):
                rivals.add(path[:i]+path[i+1:])
                rivals.add(path[:i]+(w,)+path[i:])
                other = sorted(lexicon-{w})
                if other:
                    foil = max(other,key=lambda v:SequenceMatcher(None,v,w,autojunk=False).ratio())
                    rivals.add(path[:i]+(foil,)+path[i+1:])
        flanks = (p.sheet[l.sheet].spoken,p.sheet[r.sheet].spoken)
        if len(rivals)<2:
            return refuse('no distinguishable passage')
        scores = verifier(p.heard.audio,(a,b),lang,flanks,tuple(sorted(rivals)))
        if set(scores)!=rivals or any(not math.isfinite(v) for v in scores.values()):
            raise ValueError('incomplete passage acoustic comparison')
        row.update(scores=[(list(s),scores[s]) for s in sorted(rivals)],
                   observations={'sheet':sorted(printed),'whisper':h,'musicCtc':c},
                   flanks=list(flanks))
        best = [s for s in scores if scores[s]==max(scores.values())]
        legitimate = sorted(set(best)&paths)
        if not legitimate:
            return refuse('tie or counterfactual wins')
        winner = legitimate[0]
        row['preferredSequence'] = winner
        if len(best)>1:
            row['tiedSequences'] = sorted(best)
        if not winner:
            if c or h or len(best)!=1:
                return refuse('deletion has contrary acoustic observations or a tied reading')
            row.update(status='no-match',reading=())
            return row
        # Pronunciation competitors can be indistinguishable across word breaks.
        # Same letters with a different division cannot settle display ownership.
        variants = {other:common_owners(winner,other) for other in rivals if other!=winner and
                    (other in best or ''.join(other)==''.join(winner) or (len(other)==len(winner)
                     and all(x==y or phones(x,lang)==phones(y,lang) for x,y in zip(winner,other))))}
        marked = lang=='ar' and any(skeleton(o.spoken)!=o.spoken for o in p.sheet if o.language==lang)
        if marked and winner in printed:
            bases = tuple(map(skeleton,winner))
            for other in streams:
                if other!=winner and tuple(map(skeleton,other))==bases:
                    # A vowel dispute stays with its own slot. Even an identical
                    # neighbor must have stable timing under both pronunciations.
                    variants[other] = {i:j for i,j in common_owners(winner,other).items() if i==j}
        row['unresolvedAlternativeReadings'] = sorted(variants)
        if marked and winner not in printed:
            return refuse('unresolved Arabic reading')
        mixed = len({o.language for o in p.sheet})>1
        if mixed and winner not in printed:
            return refuse('alternate mixed-language sequence lacks reviewed owners')
        cidx,hidx = certain(winner,c),certain(winner,h)
        inventory = set(w for path in printed for w in path)
        positions = []
        display = []
        locked_clocks = {owner[:3] for owner in locked}
        for i,w in enumerate(winner):
            if winner in prints:
                spellings = {line[i] for line in prints[winner]}
            else:
                spellings = {o.display for o in p.sheet if o.language==lang and key(o.spoken,lang)==key(w,lang)}
            display.append(next(iter(spellings)) if len(spellings)==1 else w)
            k,j = cidx.get(i),hidx.get(i)
            # An alternate CTC spelling can only attach to the actual acoustic
            # core overlapped uniquely by the matching free word in this passage.
            if j is not None:
                hits = [idx for idx,core in enumerate(cores) if overlap(heard[j],core)]
                exact_clock = (len(hits)==1 and sum(overlap(heard[j],v) for v in p.cores.words)==1
                               and sum(overlap(cores[hits[0]],v) for v in p.heard.words)==1)
                # Only the exact, independently ordered lexical match can use
                # this fallback. A replacement spelling still needs a clean clock.
                ordered_clock = (k is not None and k in hits and
                                 sum(c[q]==w for q in hits)==1 and
                                 sum(key(v.text,lang)==key(w,lang) and overlap(v,cores[k])
                                     for v in p.heard.words)==1 and
                                 exclusive_centers(heard[j],cores[k],p.heard.words,p.cores.words))
                if not exact_clock and not ordered_clock:
                    j = None
                elif k is not None and k not in hits:
                    j = None
                elif k is None and self.alternatives is not None and w in inventory:
                    v = cores[hits[0]]
                    if w in self.alternatives(v,lang):
                        k = hits[0]
            mark_conflict = k is not None and lang=='ar' and c[k]!=w and skeleton(c[k])==skeleton(w)
            if (k is not None and j is not None and not mark_conflict and all(i in m for m in variants.values())
                    and not (len(spellings)>1 and (marked or mixed))):
                core = cores[k]
                proposed = physical_id(p.cores.audio, Token(w, core.a, core.b), lang)
                positions.append(l.core+1+k if proposed[:3] not in locked_clocks or proposed in locked else None)
            else:
                positions.append(None)
        used = [k for k in positions if k is not None]
        if not used or used!=sorted(set(used)):
            return refuse('no unique ordered physical reading')
        row['selectedWords'] = [(i,w,k) for i,(w,k) in enumerate(zip(winner,positions)) if k is not None]
        row['withheld'] = [[w,'no unique corroborated physical occurrence']
                           for w,k in zip(winner,positions) if k is None]
        row['status'] = 'candidate' if not variants and all(k is not None for k in positions) else 'partial'
        if row['status']=='candidate':
            row['reading'] = winner
        if variants:
            timer = insensitive_timer(timer,flanks,variants)
            prior = ()  # A single cached segmentation cannot clear the competing-context check.
        return time_selected(p,g,row,timer,locked,prior,previous,(flanks[0],*winner,flanks[1]),display,positions)

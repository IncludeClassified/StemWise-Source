"""Search an unbracketed sheet span on the complete Original clock, before private word recovery."""
from difflib import SequenceMatcher
from functools import lru_cache
from itertools import groupby
import hashlib
import json
import math

from stemwise_engine.local_lyric_recovery import LocalEvidenceUnavailable, RATE, skeleton
from stemwise_engine.local_passage_recovery import certain, forms, overlap


def locator_windows(packet,ctc,frames,*,mode='marked-strict'):
    binding = packet['binding']
    unmarked = mode=='unmarked' and packet['language']=='ar' and ctc.get('language')=='ar'
    if (packet['profile']!='global' or packet['mode']!=mode or (mode!='marked-strict' and not unmarked)
            or packet['purpose']!='passage_location_only' or packet['rate']!=RATE
            or packet['frames']!=frames or packet['grantsWordEvidence'] is not False
            or packet['grantsEndpointEvidence'] is not False
            or binding['locatorSha256']!='b2846930db9c147b422e065c3461b73e3d6df9c51e2c9023430e82b9704a7439'
            or binding['orderSha256']!='40f8f4689a7bd775f0d6b84692d2b72fc6e39ab746ab27ec1fa7ace8e661eff3'
            or any(binding[k]!=ctc[k] for k in ('audioSha256','modelSha256','emissionsSha256'))
            or binding['vocabularySha256']!=ctc['configs']['vocab.json']):
        raise ValueError('placement archive does not match the recording and lexical model')
    locations = {}
    lang = packet['language']
    if unmarked:
        # The grouped locator erased vowel marks. Recover the original line owners
        # from its bound sheet before passing any proposed location to the caller.
        sheet = [{k:row[k] for k in ('id','text')} for row in packet['rows']]
        encoded = json.dumps(sheet,sort_keys=True,ensure_ascii=False,separators=(',',':')).encode()
        if hashlib.sha256(encoded).hexdigest()!=binding['sheetSha256']:
            raise ValueError('unmarked placement sheet differs from its binding')
        if len({row['id'] for row in sheet})!=len(sheet):
            raise ValueError('duplicate unmarked placement sheet owner')
        owners_by_base = {}
        for row in sheet:
            original = forms(row['text'].split(),lang)
            owners_by_base.setdefault(tuple(map(skeleton,original)),{})[row['id']] = original
    for group in packet['groups']:
        reading = forms(group['text'].split(),lang)
        candidates = group['occurrences']
        if unmarked:
            owners = owners_by_base.get(reading,{})
            if owners.keys()!=set(group['ids']):
                continue
            originals = set(owners.values())
            if len(originals)!=1 or not candidates:
                continue
            reading, = originals
        else:
            candidates = group['top5']+candidates
        bounds = {tuple(item['core']) for item in candidates}
        if any(type(a) is not int or type(b) is not int or not 0<=a<b<=frames for a,b in bounds):
            raise ValueError('invalid archived location')
        locations.setdefault((lang,reading),set()).update(bounds)
    return locations


def paths(query, stream):
    """Whole-query edit distance at every possible start. Skipping the query scores zero."""
    n = len(query)
    errors = (n-1)//2
    hits = []
    for start in range(len(stream)):
        window = stream[start:start+n+errors]
        if len(window)<n-errors or not set(query).intersection(window):
            continue
        costs = list(range(len(window)+1))
        for i,w in enumerate(query,1):
            line = [i]
            for j,v in enumerate(window,1):
                line.append(min(line[-1]+1,costs[j]+1,costs[j-1]+(w!=v)))
            costs = line
        for length in range(n-errors,len(window)+1):
            margin = n-2*costs[length]
            if margin>0:
                mapping = certain(query,window[:length])
                witnesses = frozenset((q,start+k) for q,k in mapping.items())
                if witnesses:
                    hits.append((margin,abs(length-n),start,start+length,witnesses))
    # Remove padded/truncated versions of the same observed path. Shifted
    # repetitions have different indexed witnesses even if their words overlap.
    kept = []
    for score,_,a,b,matched in sorted(hits,key=lambda x:(-x[0],x[1],x[2])):
        if not any(matched<=other for _,_,_,other in kept):
            kept.append((a,b,score,matched))
    return kept


def on_clock(words,a,b):
    indices = [i for i,w in enumerate(words) if a*2<=w.a+w.b<b*2]
    return (indices[0],indices[-1]+1) if indices else None


def proposals(p,query,lang,cached,vocals=(),drift=()):
    hw,cw = p.heard.words,p.cores.words
    h,c = forms((w.text for w in hw),lang),forms((w.text for w in cw),lang)
    hp,cp = paths(query,h),paths(query,c)
    pairs = {}
    unpaired = {}
    def add(hr,cr,source,bounds=None):
        if hr and cr:
            pairs.setdefault((*hr,*cr),set()).add(source)
        elif bounds:
            def empty_at(words):
                i = next((i for i,w in enumerate(words) if w.a>=bounds[0]),len(words))
                return (i,i)
            tag = (hr or empty_at(hw),cr or empty_at(cw),bounds)
            unpaired.setdefault(tag,set()).add(source)
    def shared(hi,ci):
        return {q for q,j in hi for t,k in ci if q==t and overlap(hw[j],cw[k])}
    for a,b,_,hm in hp:
        paired = False
        for x,y,_,cm in cp:
            if shared(hm,cm):
                add((a,b),(x,y),'paired free recognition')
                paired = True
        if not paired:
            bounds = hw[a].a,hw[b-1].b
            add((a,b),on_clock(cw,*bounds),'Whisper',bounds)
    for x,y,_,cm in cp:
        if not any(shared(hm,cm) for _,_,_,hm in hp):
            bounds = cw[x].a,cw[y-1].b
            add(on_clock(hw,*bounds),(x,y),'music CTC',bounds)
    nominated = [(a,b,'archived CTC locator') for a,b in cached]
    for proposal in drift:
        for name in ('incumbent', 'alternative'):
            a,b = proposal[name]
            nominated.append((a,b,'passage comparison '+name))
    for vocal in vocals:
        for x,y,_,_ in paths(query,forms((w.text for w in vocal),lang)):
            nominated.append((vocal[x].a,vocal[y-1].b,'derived vocal recognition'))
    for a,b,source in nominated:
        hr,cr = on_clock(hw,a,b),on_clock(cw,a,b)
        # The character locator supplies another search window on the same CTC
        # emissions. Attach it to existing physical matches before proposing a new one.
        old = [k for k in pairs if hr and cr and max(k[0],hr[0])<min(k[1],hr[1])
               and max(k[2],cr[0])<min(k[3],cr[1])]
        if old:
            for k in old:
                pairs[k].add(source)
        else:
            add(hr,cr,source,(a,b))
    results = {}
    for (hr,cr,(a,b)),sources in unpaired.items():
        bounds = max(0,a-RATE//2),min(p.heard.frames,b+RATE//2)
        results[hr,cr,bounds] = dict(heard=hr,cores=cr,bounds=bounds,wordWitnesses=[],physicalWitnesses=[],
                                     sources=sorted(sources))
    starts = {(a,x) for a,_,x,_ in pairs}
    for (a,b,x,y),sources in pairs.items():
        hm = [(q,a+j) for q,j in certain(query,h[a:b]).items()]
        cm = [(q,x+k) for q,k in certain(query,c[x:y]).items()]
        witnesses = sorted(shared(hm,cm))
        # Include an adjoining observed echo in the reading competition. Its
        # evidence and timings will be checked anew at its own physical core.
        while (len(set(query))>1 and (b,y) not in starts and b<len(hw) and y<len(cw) and query[-1] in (h[b],c[y])
               and overlap(hw[b],cw[y])):
            b,y = b+1,y+1
        bounds = (max(0,min(hw[a].a,cw[x].a)-RATE//2),
                  min(p.heard.frames,max(hw[b-1].b,cw[y-1].b)+RATE//2))
        site = results.setdefault(((a,b),(x,y),bounds),dict(heard=(a,b),cores=(x,y),bounds=bounds,
                                    wordWitnesses=witnesses,physicalWitnesses=[(q,k) for q,k in cm if q in witnesses],
                                    sources=[]))
        site['sources'] = sorted(sources.union(site['sources']))
    return sorted(results.values(),key=lambda v:(v['bounds'],v['heard'],v['cores']))


def neighboring_words(p,site,lo,hi,lang):
    """Only adjacent words actually recognized at this site distinguish equal readings."""
    counts = [0,0]
    for step,slot,side in ((-1,lo-1,0),(1,hi,1)):
        j,k = site['heard'][side],site['cores'][side]
        if step==-1:
            j,k = j-1,k-1
        while 0<=slot<len(p.sheet) and 0<=j<len(p.heard.words) and 0<=k<len(p.cores.words):
            h,c,o = p.heard.words[j],p.cores.words[k],p.sheet[slot]
            if (o.language!=lang or not overlap(h,c) or len(set(forms((h.text,c.text,o.spoken),lang)))!=1):
                break
            counts[side] += 1
            j,k,slot = j+step,k+step,slot+step
    for step,slot,side in ((-1,lo-1,0),(1,hi,1)):
        candidates = [a for a in p.anchors if (a.sheet-slot)*step>=0]
        if not candidates:
            continue
        anchor = min(candidates,key=lambda a:abs(a.sheet-slot))
        sheet = [p.sheet[i] for i in range(slot,anchor.sheet+step,step)]
        if any(o.language!=lang or o.line!=sheet[0].line for o in sheet):
            continue
        first = site['heard'][side]-(step==-1)
        heard = [p.heard.words[i].text for i in range(first,anchor.heard+step,step)]
        if (forms(heard,lang)==forms((o.spoken for o in sheet),lang)
                and overlap(p.heard.words[anchor.heard],p.cores.words[anchor.core])
                and (anchor.core-site['cores'][side]+(step==-1))*step>=0):
            counts[side] = max(counts[side],len(sheet))
    return counts


def choose(p,edge,lo,hi,route,verify,verify_locations=None):
    slots = p.sheet[lo:hi]
    langs = {o.language for o in slots}
    row = dict(status='unresolved',reading=None,additions=[],withheld=[],publicationAuthority=False,
               sheetRange=[lo,hi],recognitionRanges=[edge.heard,edge.cores],performedLines=[slots[0].line]*2)
    if len(langs)!=1 or not langs<={'en','tr','ar'}:
        row['reason'] = 'open span lacks a single reviewed model language'
        return row
    lang, = langs
    query = forms((o.spoken for o in slots),lang)
    if not all(query):
        row['reason'] = 'empty lexical comparison unit'
        return row
    drift = [proposal for proposal in route.drift if lo <= proposal['sheetRange'][0]
             and proposal['sheetRange'][1] <= hi]
    sites = proposals(p,query,lang,(route.locations or {}).get((lang,query),()),route.vocals,drift)
    alternatives = {(),query}
    lines = []
    for _,words in groupby(p.sheet,key=lambda o:o.line):
        line = list(words)
        if all(o.language==lang for o in line):
            reading = forms((o.spoken for o in line),lang)
            if reading!=query and not any(reading[i:i+len(query)]==query for i in range(len(reading))):
                lines.append(reading)
    if lines:
        alternatives.add(max(lines,key=lambda q:SequenceMatcher(None,query,q,autojunk=False).ratio()))
    competition = tuple(sorted(alternatives))
    row['placement'] = dict(query=query,alternatives=competition,noMatchMargin=0,sites=sites)
    eligible = []
    unknown = False
    candidates=[]
    for site in sites:
        a,b = site['heard']
        x,y = site['cores']
        if not (edge.heard[0]<=a<=b<=edge.heard[1] and edge.cores[0]<=x<=y<=edge.cores[1]):
            site['reason'] = 'another anchored occurrence owns this part of the recording'
            continue
        if site['bounds'][1]-site['bounds'][0]>30*RATE:
            site['reason'] = 'location comparison exceeds the verifier context'
            unknown = True
            continue
        candidates.append(site)
    windows=[tuple(s['bounds']) for s in candidates]
    if verify_locations:
        try:
            measured=verify_locations(p.heard.audio,windows,lang,competition)
        except LocalEvidenceUnavailable as error:
            measured=[error]*len(windows)
    else:
        measured=[]
        for bounds in windows:
            try:
                measured.append(verify(p.heard.audio,bounds,lang,(None,None),competition))
            except LocalEvidenceUnavailable as error:
                measured.append(error)
    for site,scores in zip(candidates,measured,strict=True):
        if isinstance(scores,LocalEvidenceUnavailable):
            site['reason'] = str(scores)
            unknown = True
            continue
        if scores.keys()!=alternatives or not all(math.isfinite(s) for s in scores.values()):
            raise ValueError('incomplete open-location comparison')
        site['scores'] = [scores[q] for q in competition]
        site['margin'] = scores[query]-max(v for q,v in scores.items() if q!=query)
        site['neighborWords'] = neighboring_words(p,site,lo,hi,lang)
        site['context'] = sum(site['neighborWords'])
        if site['margin']>0:
            eligible.append(site)
        else:
            site['reason'] = 'no positive margin over the competing readings'
    eligible.sort(key=lambda s:(s['context'],s['margin']),reverse=True)
    if unknown or not eligible:
        row['reason'] = 'unmeasured competing location' if unknown else 'no supported location beats no match'
        return row
    top = eligible[0]
    for other in eligible[1:]:
        # Require the selected rendition to stand between its competitor and
        # the distinguishing observed context on both physical clocks.
        separates = any(top['neighborWords'][side]>0 and other['neighborWords'][side]==0 and all(
            (top[k][1]<=other[k][0] if side==0 else other[k][1]<=top[k][0])
            for k in ('heard','cores')) for side in (0,1))
        if other['context']>=top['context'] or not separates:
            row['reason'] = 'multiple physical readings lack distinguishing adjacent audio'
            return row
    if len(top['wordWitnesses'])<min(2,len(query)):
        row['reason'] = 'leading location lacks independent clocked lexical evidence'
        return row
    # A second unplaced printed copy could own exactly this rendition. Only
    # independently clocked distinguishing context can attach the site to one slot.
    for i in range(edge.sheet[0],edge.sheet[1]-len(query)+1):
        other = p.sheet[i:i+len(query)]
        if (i!=lo and all(o.language==lang and o.line==other[0].line for o in other)
                and forms((o.spoken for o in other),lang)==query
                and sum(neighboring_words(p,top,i,i+len(query),lang))>=top['context']):
            row['reason'] = 'competing printed occurrence at the same physical site'
            return row
    a,b = top['heard']
    x,y = top['cores']
    row['placement']['selected'] = {'heard': [a,b], 'cores': [x,y], 'bounds': top['bounds']}
    row['reason'] = 'located passage needs independent word boundaries'
    return row


def locate_edge(p, edge, route, verify, performed_lines, verify_locations=None):
    """An observed phrase locates the dispute. Its recognition window cannot bound a held word."""
    score = lru_cache(maxsize=None)(verify)
    for (line, language), indices in groupby(range(*edge.sheet), key=lambda i: (p.sheet[i].line, p.sheet[i].language)):
        if performed_lines is not None and line not in performed_lines:
            continue
        slots = list(indices)
        row = choose(p, edge, slots[0], slots[-1]+1, route, score,verify_locations)
        row['language'] = language
        row['withheld'] = [[p.sheet[i].display, row['reason']] for i in slots]
        yield row

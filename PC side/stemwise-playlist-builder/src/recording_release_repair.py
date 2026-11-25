"""Remeasure disputed releases on the selected recording without moving starts or timing locks."""
import hashlib
from pathlib import Path
import re

from stemwise_engine.common import (atomic_write_json, json_bytes, read_bounded_json,
                                    read_bounded_regular_bytes, sha256_file, validate_generated_path)
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping
from stemwise_engine.lyrics_normalize import performance_lines
from stemwise_engine.lyrics_tokenization import display_words
from stemwise_engine.provenance import semantic_source_identity

def _digest(data):
    return hashlib.sha256(json_bytes(data)).hexdigest()


def _sources(folder, audio, original, sources):
    from stemwise_engine.lyric_preview.job import _inputs, _sheet_lines
    root = folder/'vocal-overlap'
    record_path = root/'review.private.json'
    if not record_path.is_file():
        return None
    record = read_bounded_json(record_path, max_bytes=MAX_JSON_BYTES, root=root)
    current = _inputs(folder.parents[2]/'jobs'/folder.name, audio['sha256'], placement_only=True)
    if not current or record['binding'] != current[1]:
        return None
    sheet = _sheet_lines(current[0].decode('utf-8-sig'))
    if sheet != _sheet_lines(original['text']) or [line['sourceLine'] for line in sheet] != list(sources):
        return None
    if not re.fullmatch('[a-f0-9]{64}', record['folder']):
        raise ValueError('Invalid release measurement source folder')
    view = validate_generated_path(root, root/record['folder'])
    pair = read_bounded_json(view/'stem-pair.private.json', max_bytes=MAX_JSON_BYTES, root=view)
    if pair['recordingSha256'] != audio['sha256']:
        raise ValueError('Disputed release vocal pair belongs to a different recording')
    paths = {}
    for kind, prefix in (('vocal','vocals'), ('backing','backing')):
        digest = pair[kind+'Sha256']
        if not re.fullmatch('[a-f0-9]{64}',digest) or pair[kind] != f'{prefix}-{digest}.wav':
            raise ValueError('Invalid disputed release vocal pair')
        paths[kind] = str(view/pair[kind])
    binding = {k: pair[k] for k in ('vocalSha256','backingSha256')}
    guide = {}
    path = view/'supplied-guide.private.json'
    if path.is_file():
        packet = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=view)
        saved = packet['input']
        if (saved['recording']==audio['sha256'] and saved['sheet']==current[1]['sheetSha256']
                and packet['result']['durationMs']==audio['durationMs']):
            search_track = dict(kind='original',id='original',format='plain',text=current[0].decode('utf-8-sig'),
                                language=saved['language'])
            search_map = build_lexical_occurrence_mapping(audio['sha256'][:11], search_track, sources)
            for line, owner in zip(packet['result']['lines'],search_map['performedOccurrences'],strict=True):
                if line['sourcePhysicalIndex']!=owner['sourcePhysicalIndex']:
                    raise ValueError('Disputed release search guide changed occurrence')
                for j,(word,token) in enumerate(zip(line['words'],owner['words'],strict=True)):
                    if word['wordId']!=token['occurrenceWordId']:
                        raise ValueError('Disputed release search guide changed word')
                    a,b = word['startMs'], word['endMs']
                    if 0 <= a < b <= audio['durationMs']:
                        guide[(line['sourcePhysicalIndex'],j)] = {'startMs':a,'endMs':b}
    return binding, paths, guide


def _targets(workspace, state, original, admission, audio, track, guide, confirmed):
    from stemwise_engine import spoken_targets, stanza_timing
    from stemwise_engine.lyric_preview.job import recording_entrances
    from stemwise_engine.alignment_endpoints import _component_phonemizer_languages
    from stemwise_engine.word_timing_review import inspect, RELEASE_FLAGS
    folder=workspace.job_dir(state['videoId'])
    indices = admission.source_physical_indices
    plan = None
    if admission.spoken_targets:
        plan = spoken_targets.load(folder.parents[2]/'jobs'/folder.name, folder.name, original, indices, audio['sha256'])
        if plan is None or plan.reference != admission.spoken_targets:
            raise ValueError('Spoken plan changed during release repair')
    entrances=recording_entrances(workspace,folder.name,audio['sha256'],original,indices,audio['durationMs'],spoken=plan)
    review = inspect(original, track, state['endpointEvidence'], entrances, audio['durationMs'], None)
    disputed = {(r['line']-1,r['wordIndex']-1) for r in review['publicationFlags'] if r['reason'] in RELEASE_FLAGS}
    disputed.difference_update(confirmed)
    if not disputed:
        return []
    physical = performance_lines(original['text'])
    display = [display_words(physical[k]) for k in indices]
    spoken = plan.word_lines(indices) if plan else [[text for _,_,text in line] for line in display]
    languages = _component_phonemizer_languages({**original,'_spokenTargets':plan},display)
    if languages is None:
        return []
    mapping = build_lexical_occurrence_mapping(folder.name,original,indices)
    words, details = [], []
    for i,(owner,line,row) in enumerate(zip(mapping['performedOccurrences'],track['lines'],
                                           state['endpointEvidence']['occurrenceCandidates'],strict=True)):
        families=row['familyCandidates']
        if entrances and row['publishDecision']['result']!='words':
            families=stanza_timing.same_rendition(families,entrances.get(i),entrances.values())
        for j,(token,word) in enumerate(zip(owner['words'],line['words'],strict=True)):
            t = word['timing']
            values = {'wordId':token['occurrenceWordId'], 'text':display[i][j][2], 'spoken':spoken[i][j],
                      'language':languages[i][j].split('-')[0], 'startMs':t['startMs'], 'endMs':t['endMs']}
            words.append(values)
            candidates = []
            for family,candidate in families.items():
                ws = candidate.get('words',())
                if j<len(ws) and ws[j]['occurrenceWordId']==values['wordId']:
                    candidates.append({'family':family, 'route':candidate['route'],
                                       'startMs':ws[j]['startMs'], 'endMs':ws[j]['endMs']})
            details.append(dict(values,line=i,wordIndex=j,status=t['status'],candidates=candidates,
                                locked=t['status']=='supported' or row['publishDecision']['result']=='words',
                                releaseDisputed=(i,j) in disputed,guide=guide.get((owner['sourcePhysicalIndex'],j))))
    needed = set()
    for index,t in enumerate(details):
        if not t['releaseDisputed']:
            continue
        needed.add(index)
        if index+1<len(words) and not details[index+1]['locked'] and any(
                c['endMs']>words[index+1]['startMs'] for c in t['candidates'] if c['family']!='owsm-ctc'):
            needed.add(index+1)
    for index in needed:
        details[index]['previous']=words[index-1] if index else None
        details[index]['following']=words[index+1] if index+1<len(words) else None
    return [details[k] for k in sorted(needed)]


def _measurements(target, traces, observers):
    """Use an actually observed boundary; conflicting independent results remain unresolved."""
    from stemwise_engine.endpoint_vowel_release import AGREEMENT_MS
    values = {'startMs':[], 'endMs':[]}
    overlap=None
    for route, packet in traces.items():
        if target['wordId'] not in packet:
            continue
        decision = observers[route].replay(target,packet[target['wordId']])
        if decision is None:
            continue
        if type(decision) is int:
            decision = {'endMs':decision}
        if isinstance(decision,dict) and 'otherSingerWordIds' in decision:
            decision=dict(decision)
            owners=decision.pop('otherSingerWordIds')
            response=(target.get('crossLane') or {}).get('response',[])
            if (route!='arabicCoda' or type(owners) is not list or not owners or
                    owners!=[w['wordId'] for w in response[:len(owners)]] or type(decision.get('endMs')) is not int):
                raise ValueError('Invalid crossed-role release observation')
            overlap=(decision['endMs'],owners)
        if not isinstance(decision,dict) or decision.keys()-values.keys():
            raise ValueError('Invalid disputed word observation')
        for key,value in decision.items():
            if value is not None:
                if type(value) is not int:
                    raise ValueError('Invalid disputed word clock')
                values[key].append(value)
    if any(v and max(v)-min(v)>AGREEMENT_MS for v in values.values()):
        return {}
    # The observer order is fixed, even after serialization. Within agreement
    # use an actual measured frame, never the midpoint of two estimates.
    measured={key:v[0] for key,v in values.items() if v}
    if overlap and measured.get('endMs')==overlap[0]:
        measured['otherSingerWordIds']=overlap[1]
    return measured


def _ordered_changes(base, proposals, other_singers=frozenset()):
    """Check adjacent changes together. A failed proposal cannot move a competing word."""
    changes=dict(proposals)
    while changes:
        bad=set()
        for i,(a,b) in enumerate(base):
            aa,bb=changes.get(i,(a,b))
            if aa>=bb:
                bad.add(i)
            for j in range(i+1,len(base)):
                c=base[j][0]
                cc=changes.get(j,base[j])[0]
                crossed=(i,j) in other_singers and cc==c and aa==a
                if aa>cc or (not crossed and bb-cc>max(0,b-c)):
                    if aa>a or bb>b:
                        bad.add(i)
                    if cc<c:
                        bad.add(j)
        if not bad:
            break
        if not bad.intersection(changes):
            raise ValueError('Incumbent word order is invalid')
        for i in bad:
            changes.pop(i,None)
    return changes


def _apply(track, targets, traces, observers, ref, confirmed, duration):
    owners={t['wordId'] for t in targets}
    for route, packet in traces.items():
        if route not in observers or not isinstance(packet,dict) or packet.keys()-owners:
            raise ValueError('Disputed release observations have unexpected owners')
    flat=[((i,j),w['timing']) for i,line in enumerate(track['lines']) for j,w in enumerate(line['words'])]
    numbers={pos:k for k,(pos,_) in enumerate(flat)}
    base=[(w['startMs'],w['endMs']) for _,w in flat]
    proposed, measured_ends, other_singers = {},set(),set()
    for t in targets:
        pos=t['line'],t['wordIndex']
        k=numbers[pos]
        measured=_measurements(t,traces,observers)
        response_owners=measured.pop('otherSingerWordIds',[])
        if not t['releaseDisputed']:
            # The neighboring word was requested only to test whether its onset
            # cuts across the disputed release. Keep its end unless the measured
            # onset cannot fit and this same observer also measured the full word.
            if measured.get('startMs',base[k][0])<base[k][1]:
                measured.pop('endMs',None)
        if not measured:
            continue
        a,b=measured.get('startMs',base[k][0]),measured.get('endMs',base[k][1])
        if not 0<=a<b<=duration or t['locked'] and (a,b)!=base[k]:
            continue
        proposed[k]=(a,b)
        if response_owners:
            from stemwise_engine.alignment_endpoints import WORD_START_AGREEMENT_MS
            from stemwise_engine.role_phrase_overlap import within_response_word
            response_trace=traces['arabicCoda'][t['wordId']]['crossLane']
            for j,owner in enumerate(t['crossLane']['response'][:len(response_owners)]):
                after=numbers.get((pos[0]+1,j))
                if after is not None and (abs(base[after][0]-owner['startMs'])<=WORD_START_AGREEMENT_MS or
                                          within_response_word(t,response_trace,j,base[after][0])):
                    other_singers.add((k,after))
        if 'endMs' in measured and t['releaseDisputed']:
            measured_ends.add(k)
    accepted = _ordered_changes(base,proposed,other_singers)
    changed_lines=set()
    for k,(a,b) in accepted.items():
        pos,word=flat[k]
        if (a,b)!=base[k]:
            word.update(startMs=a,endMs=b,basis='Estimated from local vocal measurements of the performed word.')
            changed_lines.add(pos[0])
        if k in measured_ends:
            confirmed[pos]=(a,b,ref['sha256'])
    for i in changed_lines:
        line=track['lines'][i]
        line['timing'].update(startMs=line['words'][0]['timing']['startMs'],
                              endMs=max(w['timing']['endMs'] for w in line['words']))
    return bool(changed_lines or measured_ends.intersection(accepted))


def resolve(workspace, state, original, admission, audio, track, confirmed, *, ref=None, prepare=False, config=None):
    folder = workspace.job_dir(state['videoId'])
    try:
        found = _sources(folder,audio,original,admission.source_physical_indices)
    except (FileNotFoundError, KeyError, ValueError, TypeError):
        if ref:
            raise
        return None  # Absent or stale optional stems cannot authorize a measurement.
    if not found:
        if ref:
            raise ValueError('Disputed release recording inputs are unavailable')
        return None
    identities,paths,guide=found
    targets = _targets(workspace,state,original,admission,audio,track,guide,confirmed)
    if not targets:
        if ref:
            raise ValueError('Current disputed word set differs from the recorded repair')
        return None
    observers = {}
    languages = {t['language'] for t in targets}
    if 'en' in languages:
        from stemwise_engine import english_release_observer, english_release_tail
        english_release_tail.attach_witnesses(folder,state,audio,targets)
        observers['english'] = english_release_observer
    if languages.intersection({'ar','tr','ur'}):
        from stemwise_engine import multilingual_release_observer, vocal_release_transition
        observers['multilingual'] = multilingual_release_observer
    if 'ar' in languages:
        from stemwise_engine import lead_vowel_observer
        lead = lead_vowel_observer.inputs(folder,audio)
        if lead:
            lead_ids,lead_paths = lead
            identities = {**identities,**lead_ids}
            paths = {**paths,**lead_paths}
            observers['leadVowel'] = lead_vowel_observer
            from stemwise_engine import phrase_final_release
            phrase = phrase_final_release.attach(folder,audio,original,admission,targets)
            if phrase:
                identities.update(phrase)
                observers['phraseFinal'] = phrase_final_release
            from stemwise_engine import arabic_coda_release, role_phrase_overlap
            coda=arabic_coda_release.attach(folder,audio,original,admission,targets)
            response=role_phrase_overlap.attach(folder,audio,original,admission,targets)
            if coda or response:
                identities.update(coda or {})
                identities.update(response or {})
                observers['arabicCoda']=arabic_coda_release
    if not observers:
        if ref:
            raise ValueError('Current word language has no release observer')
        return None
    producer = {route:semantic_source_identity(Path(module.__file__),portable=True) for route,module in observers.items()}
    if 'english' in observers:
        from stemwise_engine import endpoint_vowel_release as phones
        from stemwise_engine import vocal_release_transition as spectrum
        producer['englishPhoneModel']=[phones.MODEL,phones.VOCAB,phones.LEXICON,phones.FRAME_MS,
                                       phones.AGREEMENT_MS,sorted(phones.VOWELS),list(english_release_observer.RUNTIME)]
        producer['englishReleaseTail'] = semantic_source_identity(Path(english_release_tail.__file__),portable=True)
        producer['englishSpectrum'] = [spectrum.FORMAT,spectrum.FRAME_MS,spectrum.HOP_MS,spectrum.GUARD_FRAMES,
            list(spectrum.BANDS_HZ),semantic_source_identity(Path(spectrum.__file__),
                ('_spectrum','_band_energy','extract','_read','_core','decide','decide_transition'),portable=True)]
    if 'leadVowel' in observers:
        producer['leadAssets'] = list(lead_vowel_observer.ASSETS)
    if 'arabicCoda' in observers:
        from stemwise_engine import arabic_role_letters, recording_phrase_placement
        producer['arabicLetters']=[arabic_role_letters.ASSETS,
            semantic_source_identity(Path(arabic_role_letters.__file__),portable=True)]
        producer['rolePhraseOverlap']=semantic_source_identity(Path(role_phrase_overlap.__file__),portable=True)
        producer['codaCorrespondence']=semantic_source_identity(Path(recording_phrase_placement.__file__),
                                                               ('_voice','correlate_word'),portable=True)
    if 'multilingual' in observers:
        from stemwise_engine import adjacent_release_onset
        producer['vocalTransition'] = semantic_source_identity(Path(vocal_release_transition.__file__),portable=True)
        producer['repeatAttack'] = semantic_source_identity(Path(adjacent_release_onset.__file__),portable=True)
        producer['multilingualPhoneAssets'] = multilingual_release_observer.ASSETS
    binding = {**identities,'recording':audio['sha256'],'durationMs':audio['durationMs'],'mapping':state['mappingSha256'],
               'content':admission.evidence_sha256,'baseTrack':_digest(track),'targets':_digest(targets),'producer':producer}
    name = 'measured-word-releases-'+_digest(binding)+'.private.json'
    path = folder/name
    if ref and (ref.get('path')!=name or set(ref)!={'path','sha256'}):
        raise ValueError('Disputed release evidence belongs to a different request')
    if not path.is_file():
        if ref:
            raise ValueError('Disputed release evidence is unavailable')
        if not prepare:
            return None
        context = dict(paths, **identities, original=str(audio['path']), project=str(config['projectDir']),
                       audioSha256=audio['sha256'],durationMs=audio['durationMs'],cacheDir=str(folder),
                       timeoutSeconds=int(config['analysisTimeoutMinutes'])*60)
        files = [(Path(context[k]),context[h]) for k,h in (('original','audioSha256'),('vocal','vocalSha256'),('backing','backingSha256'))]
        if any(sha256_file(p)!=digest for p,digest in files):
            raise ValueError('Disputed release audio bytes differ from the recorded inputs')
        traces = {route:module.observe(context,targets) for route,module in observers.items()}
        if any(sha256_file(p)!=digest for p,digest in files):
            raise ValueError('Disputed release audio changed during measurement')
        atomic_write_json(path,{'binding':binding,'observations':traces},root=folder)
    raw = read_bounded_regular_bytes(path,max_bytes=MAX_JSON_BYTES,root=folder)
    from stemwise_engine.common import parse_strict_json_bytes
    packet = parse_strict_json_bytes(raw)
    current = {'path':name,'sha256':hashlib.sha256(raw).hexdigest()}
    if ref is not None and current!=ref or packet.get('binding')!=binding:
        raise ValueError('Disputed release measurement changed')
    _apply(track,targets,packet['observations'],observers,current,confirmed,audio['durationMs'])
    return current  # Retain a completed abstention too. Only _apply can confirm a boundary.

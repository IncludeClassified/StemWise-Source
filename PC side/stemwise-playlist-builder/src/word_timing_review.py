"""Read-only per-song operator review. Observations never change timing or publication."""
from collections import Counter
from functools import lru_cache
from pathlib import Path, PureWindowsPath

from stemwise_engine.alignment_endpoints import (ENDPOINT_MMS_ROUTES, OWSM_CTC_FAMILY,
                                                WORD_START_AGREEMENT_MS, WORD_END_AGREEMENT_MS)
from stemwise_engine.common import read_bounded_json, sha256_file
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.lyrics_tokenization import display_words
from stemwise_engine.nemo_nfa_forced_alignment import ACOUSTIC_TIMESTEP_MS

VOCAL_GUIDE_ROUTES=frozenset('torchaudio-mms-fa-on-'+name for name in ENDPOINT_MMS_ROUTES)
SHORT_INTERVAL = 'one-acoustic-frame-or-shorter'
VOCAL_SPAN_DISAGREEMENT = 'vocal-span-disagreement'
RELEASE_FLAGS = {'possible-clipped-release-on-vocal-view', 'release-disagreement-in-same-phrase'}


@lru_cache(maxsize=1)
def boundary_recovery_recipe():
    from hashlib import sha256
    from stemwise_engine.common import json_bytes
    from stemwise_engine.provenance import semantic_source_identity
    modules = ('recording_word_placement', 'recording_release_repair', 'lead_vowel_observer',
               'recording_repeat_words', 'recording_phrase_placement', 'phrase_final_release',
               'repeated_word_coda', 'role_phrase_overlap', 'arabic_role_letters', 'arabic_coda_release',
               'timing_estimate_policy', 'held_repeat_release', 'role_repeat_boundaries',
               'vocal_span_recovery', 'vocal_span_estimate', 'role_vocal_span_recovery', 'sustained_release',
               'instrumental_break')
    return sha256(json_bytes([semantic_source_identity(Path(__file__).with_name(name+'.py'), portable=True)
                              for name in modules])).hexdigest()


def needs_release_measurement(job, report):
    authority = job.get('validation', {}).get('capabilities', {}).get('timingAuthority') or {}
    actionable = RELEASE_FLAGS | {'suspected-shared-repeat-occurrence', 'outside-matched-vocal-phrase',
                                  'possible-vocal-part-confusion', 'different-vocal-occurrence',
                                  'vocal-handoff-with-unresolved-boundaries','suspected-adjacent-repeat',
                                  VOCAL_SPAN_DISAGREEMENT,'word-crosses-instrumental-break'}
    return (job.get('state') == 'VALIDATED' and authority.get('kind') == 'reviewed-recording-acoustic-words'
            and (report.get('selectedMeasurementStatus') == 'unverified'
                 or (report.get('unresolvedPublicationFlagCount', 0) > 0
                     and any(flag['reason'] in actionable for flag in report.get('publicationFlags', ()))))
            and authority.get('boundaryRecoveryRecipe') != boundary_recovery_recipe())


def _distinct_vocal_entrance(word):
    from stemwise_engine.lyric_preview.candidates import BAR
    contrast = word.get('vocalContrast')
    crops = word.get('vocalCropRangeMs')
    mass = word.get('massWithin100ms', {})
    if not contrast or contrast[0] is not True or not crops or not crops[0]:
        return False
    a, b = crops[0]
    start = word['startMs']
    if not all(mass.get(view) for view in ('mix','lead','backing')):
        return False
    mix, lead, backing = (mass[view][0] for view in ('mix','lead','backing'))
    return (a <= b and max(abs(a-start), abs(b-start)) <= BAR
            and 0 <= backing < min(lead,mix) and max(lead,mix) <= 1)


def _shared_repeat_placements(single, intervals, spoken_owners):
    """Two adjacent owners starting in one vocal rendition leave the other in dispute."""
    words = [((i,k), w) for i,line in enumerate(single) for k,w in enumerate(line['words'])]
    for (p, first), (q, second) in zip(words, words[1:]):
        if (p not in intervals or q not in intervals or first['wordId'] == second['wordId']
                or first['text'] != second['text'] or not all(_distinct_vocal_entrance(w) for w in (first,second))):
            continue
        if spoken_owners is not None and spoken_owners[p[0]][p[1]] != spoken_owners[q[0]][q[1]]:
            continue
        a,b = first['startMs'],first['endMs']
        c,d = second['startMs'],second['endMs']
        if not (a < b <= c < d and c-a > WORD_START_AGREEMENT_MS):
            continue
        starts = (intervals[p][0], intervals[q][0])
        if (all(a <= t < b for t in starts) and c-max(starts) > WORD_START_AGREEMENT_MS
                or all(c <= t < d for t in starts) and min(starts)-b > WORD_START_AGREEMENT_MS):
            yield (p,q), (a,d), f'vocal entrance windows [{a},{b}) and [{c},{d}) ms'


def inspect(original, published, summary, anchors, duration, media, layers=(), single=(), handoffs=(),
            *, resolved_releases=None, spoken_owners=None, equivalent_estimates=(), misplaced=(),
            instrumental_breaks=()):
    from stemwise_engine.lyrics_normalize import physical_lines
    from stemwise_engine.stanza_timing import same_rendition
    from stemwise_engine.vocal_span_recovery import dispute
    lines = physical_lines(original['text'])
    rows = summary['occurrenceCandidates']
    accepted = (published or {}).get('lines') or []
    if accepted and (len(rows) != len(accepted) or
            any(lines[r['sourcePhysicalIndex']] != l['text'] for r,l in zip(rows, accepted))):
        raise ValueError('Published sequence differs from endpoint observations')
    counts = Counter()
    flags = []
    intervals = {}
    def flag(line, k, word, a, b, reason, source, *, other_rendition=False, context=None):
        lo,hi = context or (a,b)
        clip = {'startMs': max(0, min(lo,a)-1200), 'endMs': min(duration, max(hi,b)+1200)}
        if media:
            path = PureWindowsPath(media) if PureWindowsPath(media).is_absolute() else Path(media)
            clip.update(source=media, url=path.as_uri()+f'#t={clip["startMs"]/1000:.3f},{clip["endMs"]/1000:.3f}')
        flags.append({'line':line, 'wordIndex':k, 'word':word, 'startMs':a, 'endMs':b,
                      'reason':reason, 'observation':source, 'clip':clip})
        if other_rendition:
            flags[-1]['otherRendition'] = True
    complete = supported_lines = native_frame_count = 0
    mixed = original.get('language') == 'mul' or len(original.get('componentLanguages', [])) > 1
    for i,r in enumerate(rows):
        physical = lines[r['sourcePhysicalIndex']]
        tokens = display_words(physical)
        families = r['familyCandidates']
        eligible = (families if r['publishDecision']['result']=='words' else
                    same_rendition(families, anchors.get(i), anchors.values()))
        native = families.get(r.get('selectedPublisherFamily'), {}) if r['publishDecision']['result']=='words' else {}
        entries = accepted[i].get('words',[]) if accepted else []
        preview_words = single[i]['words'] if (len(single) == len(rows) and
                                                (not mixed or spoken_owners is not None)) else ()
        nemo_words = families.get('nemo-nfa', {}).get('words', ())
        if entries and len(entries)!=len(tokens):
            raise ValueError('Published word ownership changed')
        line_counts = Counter()
        for k,(left,right,word) in enumerate(tokens):
            t = {}
            if entries:
                if (entries[k]['startUtf16'],entries[k]['endUtf16'])!=(left,right):
                    raise ValueError('Published word span changed')
                t = (entries[k].get('timing', {}) if published.get('format') != 'synced' else
                     {'startMs':entries[k]['startMs'], 'endMs':entries[k]['endMs'], 'status':'supported'})
            elif not accepted and native.get('words'):
                t = {**native['words'][k], 'status':'native-word-interval'}
            timed = (type(t.get('startMs')) is int and type(t.get('endMs')) is int
                     and 0<=t['startMs']<t['endMs']<=duration)
            quality = t.get('status','unresolved') if timed else 'unresolved'
            counts[quality]+=1
            line_counts[quality]+=1
            if not timed:
                continue
            a,b = t['startMs'],t['endMs']
            intervals[i,k]=(a,b)
            if b-a<=20:
                flag(i+1,k+1,word,a,b,SHORT_INTERVAL,quality)
            if entries and quality == 'estimated' and b-a == ACOUSTIC_TIMESTEP_MS and len(nemo_words) == len(tokens):
                raw = nemo_words[k]
                if (a,b) == (raw['startMs'],raw['endMs']):
                    native_frame_count += 1
                    if len(preview_words) == len(tokens):
                        observed = preview_words[k]
                        if (word,left,right) == (observed.get('text'),observed.get('startUtf16'),observed.get('endUtf16')):
                            context = dispute(t,observed)
                            if context:
                                flag(i+1,k+1,word,a,b,VOCAL_SPAN_DISAGREEMENT,
                                     'recording-bound vocal crops',context=context)
            if entries and len(native.get('words',[]))==len(tokens):
                reference=native['words'][k]
                if abs(reference['startMs']-a)>WORD_START_AGREEMENT_MS:
                    flag(i+1,k+1,word,a,b,'differs-from-retained-word-start',r['selectedPublisherFamily'])
                if abs(reference['endMs']-b)>WORD_END_AGREEMENT_MS:
                    flag(i+1,k+1,word,a,b,'differs-from-retained-word-end',r['selectedPublisherFamily'])
            guide = families.get('mms-fa',{})
            if len(guide.get('words',[]))==len(tokens) and guide.get('route') in VOCAL_GUIDE_ROUTES:
                gw = guide['words'][k]
                if abs(gw['startMs']-a)<=WORD_START_AGREEMENT_MS and gw['endMs']-b>WORD_END_AGREEMENT_MS:
                    flag(i+1,k+1,word,a,b,'possible-clipped-release-on-vocal-view', guide['route'],
                         other_rendition='mms-fa' not in eligible)
        complete += bool(tokens) and not line_counts['unresolved'] and sum(line_counts.values())==len(tokens)
        supported_lines += bool(tokens) and line_counts['supported']==len(tokens)
        if not tokens or r['publishDecision']['result']=='words':
            continue
        anchor = anchors.get(i)
        candidates = {key:c for key,c in families.items() if c.get('words')}
        same = {key:c for key,c in candidates.items() if anchor is not None and abs(c['startMs']-anchor)<=WORD_START_AGREEMENT_MS}
        for f,c in candidates.items():
            if same and f not in same and (c['endMs'] < min(v['startMs'] for v in same.values())
                    or c['startMs'] > max(v['endMs'] for v in same.values())):
                other = any(j!=i and abs(c['startMs']-x)<=WORD_START_AGREEMENT_MS for j,x in anchors.items())
                w = c['words'][0]
                flag(i+1,1,tokens[0][2],w['startMs'],w['endMs'],
                     'suspected-adjacent-repeat' if other else 'outside-matched-vocal-phrase',f)
        full = [(f,c) for f,c in candidates.items() if f!=OWSM_CTC_FAMILY and len(c['words'])==len(tokens)]
        for k,(_,_,word) in enumerate(tokens):
            selected = intervals.get((i,k))
            conflicting_families = []
            selected_already_flagged = False
            for f,c in full:
                w = c['words'][k]
                later = [(family,other['words'][k]) for family,other in full
                         if abs(c['startMs']-other['startMs'])<=WORD_START_AGREEMENT_MS
                         and w.get('occurrenceWordId') == other['words'][k].get('occurrenceWordId')
                         and abs(w['startMs']-other['words'][k]['startMs'])<=WORD_START_AGREEMENT_MS
                         and other['words'][k]['endMs']-w['endMs']>WORD_END_AGREEMENT_MS]
                if not later:
                    continue
                later = [word for family,word in later if family in eligible] if f in eligible else []
                flag(i+1,k+1,word,w['startMs'],w['endMs'],'release-disagreement-in-same-phrase',f,
                     other_rendition=not later)
                if not later:
                    continue
                if selected == (w['startMs'], w['endMs']):
                    selected_already_flagged = True
                elif (selected and abs(selected[0]-w['startMs'])<=WORD_START_AGREEMENT_MS
                      and abs(selected[1]-w['endMs'])<=WORD_END_AGREEMENT_MS
                      and any(abs(selected[0]-other['startMs'])<=WORD_START_AGREEMENT_MS
                              and other['endMs']-selected[1]>WORD_END_AGREEMENT_MS for other in later)):
                    conflicting_families.append(f)
            if conflicting_families and not selected_already_flagged:
                flag(i+1,k+1,word,*selected,'release-disagreement-in-same-phrase',', '.join(sorted(conflicting_families)))
    if instrumental_breaks:
        from stemwise_engine.instrumental_break import FLAG
        for row in instrumental_breaks:
            i,k = row['line'],row['wordIndex']
            a,b = intervals[i,k]
            word = display_words(accepted[i]['text'])[k][2]
            gap = row['instrumentalMs']
            flag(i+1,k+1,word,a,b,FLAG,f'Verified backed instrumental gap begins near {gap} ms')
    for i,k,lo,hi in misplaced:
        a,b=intervals[i,k]
        text=display_words(accepted[i]['text'])[k][2]
        flag(i+1,k+1,text,a,b,'different-vocal-occurrence',
             f'Matched vocal fragment and local word decode at {lo}–{hi} ms',context=(lo,hi))
    if accepted and (not mixed or spoken_owners is not None):
        for positions, context, observation in _shared_repeat_placements(single, intervals, spoken_owners):
            for i,k in positions:
                flag(i+1,k+1,single[i]['words'][k]['text'],*intervals[i,k],
                     'suspected-shared-repeat-occurrence',observation,context=context)
    owners = {w['wordId']:(i,k,w['text']) for i,l in enumerate(single) for k,w in enumerate(l['words'])}
    parts = [(w, w.get('voice',l.get('voice'))) for l in layers for w in l['words']
             if w.get('voice',l.get('voice')) in ('lead','choir')]
    def overlaps(a,b,c,d):
        return a<d and c<b
    for w, role in parts:
        owner = owners.get(w['wordId'])
        if not owner:
            continue
        i,k,text = owner
        if (i,k) not in intervals:
            continue
        a,b = intervals[i,k]
        if (not overlaps(a,b,w['startMs'],w['endMs']) and
                any(other!=role and overlaps(a,b,v['startMs'],v['endMs']) for v,other in parts)):
            flag(i+1,k+1,text,a,b,'possible-vocal-part-confusion',f'private-{role}')
    for handoff in handoffs:
        owner=owners.get(handoff['opening'])
        if owner and not handoff['timed']:
            i,k,text=owner
            a,b=handoff['mixInterval']
            flag(i+1,k+1,text,a,b,'vocal-handoff-with-unresolved-boundaries',handoff['openingPart'])
    # Only measurements replayed by the selected recording/occurrence producer
    # enter this map. Keep the original conflicting observations for inspection.
    releases = {key: measurement for key, measurement in (resolved_releases or {}).items()
                if accepted and intervals.get(key) == measurement[:2]}
    acoustic_count = short_count = 0
    estimates = {(r['line'],r['wordIndex'],r['startMs'],r['endMs']) for r in equivalent_estimates}
    estimate_count = 0
    for item in flags:
        key = (item['line']-1,item['wordIndex']-1)
        selected = intervals.get(key)
        on_selected = bool(accepted and selected == (item['startMs'],item['endMs']) and not item.get('otherRendition'))
        if on_selected and item['reason'] == SHORT_INTERVAL:
            short_count += 1
        item['affectsPublished'] = on_selected and item['reason'] != SHORT_INTERVAL
        if on_selected and item['reason'] in RELEASE_FLAGS and key in releases:
            item['releaseEvidenceSha256'] = releases[key][2]
            item['affectsPublished'] = False
            acoustic_count += 1
        elif (item['affectsPublished'] and item['reason'] in RELEASE_FLAGS
              and (item['line'],item['wordIndex'],item['startMs'],item['endMs']) in estimates):
            item['affectsPublished'] = False
            item['estimatedWithinPolicy'] = True
            estimate_count += 1
    return {'status':'reviewed', 'endpointEvidenceAvailable':True, 'lyricsFound':bool(original.get('text', '').strip()),
            'estimatePolicyClearedFlagCount':estimate_count, 'equivalentTimingEstimates':list(equivalent_estimates),
            'acousticallyClearedFlagCount':acoustic_count,
            'acousticReleaseEvidence':[{'line':i+1,'wordIndex':k+1,'startMs':a,'endMs':b,'sha256':digest}
                                       for (i,k),(a,b,digest) in sorted(releases.items())],
            'shortIntervalCount':short_count, 'nativeSingleFrameCount':native_frame_count,
            'publicationFlags':[item for item in flags if item['affectsPublished']], 'lines':len(rows),'wordIntervals':dict(counts),'completeLines':complete,
            'fullySupportedPublishedLines':supported_lines, 'nativeWordCompleteLines':summary['wordLineCount'],
            'vocalPartCheck':'separate-lanes' if layers else 'no-bound-lane-evidence',
            'flags':flags}


def _pending_report(workflow, job):
    from stemwise_engine.lyrics_intake import read_role_snapshot
    from stemwise_engine.lyrics_normalize import performed_line_indices
    directory = workflow._job_dir(job['videoId'])
    if not any((directory/'drafts'/name).is_file() for name in ('original.txt','original.lrc')):
        return {'status':'awaiting-lyrics','lyricsFound':False,'endpointEvidenceAvailable':False}
    sheet, _ = read_role_snapshot(directory/'drafts','original')
    lines = performed_line_indices(sheet)
    return {'status':'awaiting-endpoints','lyricsFound':bool(lines),'lines':len(lines),
            'endpointEvidenceAvailable':False}


def _owner_prediction(workflow, job, ws, digest, duration, summary):
    """Replay the actual owner-admitted MMS packet, retaining separately measured rivals."""
    import hashlib
    from stemwise_engine.common import json_bytes
    from stemwise_engine.lyrics_align_pc import load_owner_word_prediction
    from stemwise_engine.lyrics_alignment import _plain_original
    from stemwise_engine.lyrics_normalize import performed_line_indices
    compiled, _, _=workflow._preflight_drafts(job,roles=frozenset({'original'}))
    original=_plain_original(compiled)
    indices=performed_line_indices(original['text'])
    candidate,mapping,numerical,admission=load_owner_word_prediction(
        Path(workflow.config['projectDir']),ws,job['videoId'],job['ownerWordPrediction'],
        original=original,indices=indices,canonical_sha=hashlib.sha256(json_bytes(compiled)).hexdigest(),audio_sha=digest)
    if numerical['provenance']['audio']['stream']['durationMs']!=duration:
        raise ValueError('Owner prediction recording clock differs')
    track=next(t for t in candidate['tracks'] if t['kind']=='original')
    owners=mapping['performedOccurrences']
    rows=summary['occurrenceCandidates'] if summary else [
        {'sourcePhysicalIndex':o['sourcePhysicalIndex'],'occurrenceId':o['occurrenceId'],
         'publishDecision':{'result':'estimated'},'familyCandidates':{}} for o in owners]
    reviewed=[]
    for row,owner,line in zip(rows,owners,track['lines'],strict=True):
        if (row['sourcePhysicalIndex'],row['occurrenceId'])!=(owner['sourcePhysicalIndex'],owner['occurrenceId']):
            raise ValueError('Owner prediction and competing endpoint occurrences differ')
        words=[{'occurrenceWordId':token['occurrenceWordId'],
                'startMs':w['timing']['startMs'],'endMs':w['timing']['endMs']}
               for token,w in zip(owner['words'],line['words'],strict=True)]
        selected={'route':'owner-mms-on-mix','startMs':line['timing']['startMs'],
                  'endMs':line['timing']['endMs'],'words':words}
        reviewed.append({**row,'familyCandidates':{**row['familyCandidates'],'owner-mms-on-mix':selected}})
    return (original,indices,admission.spoken_targets,track,
            {'occurrenceCandidates':reviewed,'wordLineCount':len(reviewed)})


def _require_selected_measurements(workspace, state, job, original, audio, published, *, native_choices=None):
    """Compare with the selected recording-bound output. Agreement tolerances only rank alternatives."""
    video_id = job['videoId']
    if published['format'] == 'regional':
        from stemwise_engine.lyric_content import ContentAdmission
        from stemwise_engine.recording_word_placement import project
        capabilities = job.get('validation', {}).get('capabilities', {})
        authority = capabilities.get('timingAuthority') or {}
        if (authority.get('kind') != 'reviewed-recording-acoustic-words'
                or authority.get('stateSha256') != sha256_file(workspace.state_path(video_id))
                or authority.get('mappingSha256') != state['mappingSha256']):
            raise ValueError('The selected estimate has no current recording-bound measurement plan')
        admission = ContentAdmission.from_record(capabilities.get('contentAdmission'))
        if authority.get('contentEvidenceSha256') != admission.evidence_sha256:
            raise ValueError('The selected estimate belongs to a different content review')
        resolved_releases = {}
        estimate_policy = authority.get('equivalentEstimates')
        if estimate_policy is not None and native_choices is None:
            native_choices = {}
        references = {'repeat_boundary_ref':'repeatBoundaryEvidence',
                      'vocal_span_ref':'vocalSpanEvidence', 'role_span_ref':'roleSpanEvidence'}
        extra = {argument:authority[key] for argument,key in references.items() if authority.get(key)}
        expected = project(workspace, state, original, admission, audio, authority.get('vowelReleaseEvidence'),
                           repair_ref=authority.get('releaseRepairEvidence'),
                           repeat_ref=authority.get('repeatedWordEvidence'),
                           phrase_ref=authority.get('phrasePlacementEvidence'),
                           listening_ref=authority.get('listeningReleaseEvidence'),
                           resolved_releases=resolved_releases,native_choices=native_choices,**extra)
        sustained = authority.get('sustainedReleaseEvidence')
        instrumental = authority.get('instrumentalBreakEvidence')
        if estimate_policy is not None or sustained or instrumental:
            from stemwise_engine.listened_release_choice import protected
            locks=protected(workspace.job_dir(video_id),expected,resolved_releases,authority.get('listeningReleaseEvidence'))
        if estimate_policy is not None:
            from stemwise_engine.timing_estimate_policy import replay
            replay(expected,native_choices,locks,estimate_policy,audio['sha256'],state['mappingSha256'])
        if sustained:
            from stemwise_engine.sustained_release import recover
            recover(workspace,state,original,admission,audio,expected,locks,estimate_policy,ref=sustained)
            resolved_releases.update({k:v for k,v in locks.items() if v[-1]==sustained['sha256']})
        if instrumental:
            from stemwise_engine.instrumental_break import recover
            recover(workspace,state,original,admission,audio,expected,locks,ref=instrumental)
            for boundary in instrumental['boundaries']:
                k = boundary['line'],boundary['wordIndex']
                resolved_releases[k] = locks[k]
        if published['lines'] != expected['lines']:
            raise ValueError('Selected intervals differ from the retained acoustic placement')
        return resolved_releases
    if published['format'] != 'synced':
        raise ValueError('The selected word timing has no measured format')
    from stemwise_engine.common import parse_strict_json_bytes, read_bounded_regular_bytes
    import hashlib
    path = workspace.mapping_path(video_id)
    raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=path.parent)
    if hashlib.sha256(raw).hexdigest() != state.get('mappingArtifactSha256'):
        raise ValueError('The selected timing has no current committed occurrence map')
    mapping = parse_strict_json_bytes(raw)
    rows = state['endpointEvidence']['occurrenceCandidates']
    numeric = mapping['numeric']
    if (mapping['videoId'] != video_id or
            numeric['sourcePhysicalIndices'] != state['contentBinding']['sourcePhysicalIndices']):
        raise ValueError('Committed timing belongs to different physical occurrences')
    def bounds(thing):
        return thing['startMs'], thing['endMs']
    for owner, row, retained, actual in zip(mapping['performedOccurrences'], rows,
            numeric['publicLineTimings'], published['lines'], strict=True):
        if (row['sourcePhysicalIndex'] != owner['sourcePhysicalIndex'] or row['occurrenceId'] != owner['occurrenceId']
                or row['publishDecision']['result'] != 'words' or bounds(retained) != bounds(actual)):
            raise ValueError('Selected occurrence differs from the committed timing')
        selected = row['familyCandidates'][row['selectedPublisherFamily']]['words']
        for word, native, measured, proposed in zip(owner['words'], selected,
                retained['words'], actual['words'], strict=True):
            if (native.get('occurrenceWordId', native.get('wordId')) != word['occurrenceWordId']
                    or bounds(native) != bounds(measured) or bounds(measured) != bounds(proposed)):
                raise ValueError('Selected interval differs from the committed acoustic word measurement')


def readback(workflow, job, status=None, *, published_override=None):
    from stemwise_engine import spoken_targets
    from stemwise_engine.lyric_preview import job as preview
    from stemwise_engine.lyrics_alignment import AlignmentWorkspace, _plain_original
    from stemwise_engine.packages import read_child
    vid = job['videoId']
    path = workflow._alignment_state_path(vid)
    try:
        def read(file):
            return read_bounded_json(file,max_bytes=MAX_JSON_BYTES,root=file.parent)
        state = read(path) if path.is_file() else {}
        summary=state.get('endpointEvidence') or {}
        owner=job.get('ownerWordPrediction')
        has_rows=bool(summary.get('occurrenceCandidates'))
        if not has_rows and not owner:
            return _pending_report(workflow, job)
        ws = AlignmentWorkspace(workflow.root,workspace_root=workflow.workspace_root)
        checkpoint = read(workflow.runtime/'work/checkpoints'/f'{vid}.json')
        audio = checkpoint['originalAudio']
        digest,duration = audio['sha256'],audio['durationMs']
        backup=None
        if has_rows:
            if state['videoId']!=vid:
                raise ValueError('Endpoint recording owner differs')
            if digest!=state['contentBinding']['audioSha256'] or duration!=state['durationMs']:
                if not owner:
                    raise ValueError('Endpoint recording clock or identity changed')
                has_rows=False
            else:
                # The current Original can have a different sidecar serialization (e.g. companions).
                # Compare the original track in the exact snapshot actually used by the endpoints.
                import re
                old_sha=state['canonicalPlainSha256']
                if not re.fullmatch('[0-9a-f]{64}',old_sha):
                    raise ValueError('Invalid canonical endpoint snapshot identity')
                backup=ws.canonical_plain/f'{vid}.{old_sha}.lyrics.json' if owner else ws.current_backup_path(vid)
                if sha256_file(backup)!=old_sha:
                    raise ValueError('Canonical endpoint snapshot changed')
        validated_audio=job.get('validation',{}).get('audioSha256')
        if validated_audio and digest!=validated_audio:
            raise ValueError('Validated recording differs from the endpoint evidence')
        selected_prediction=None
        if owner:
            original,indices,spoken_ref,selected_prediction,summary=_owner_prediction(
                workflow,job,ws,digest,duration,summary if has_rows else None)
            if has_rows and (_plain_original(read(backup))!=original or
                    list(indices)!=state['contentBinding']['sourcePhysicalIndices']):
                raise ValueError('Owner prediction and retained endpoint sheet differ')
        else:
            original = _plain_original(read(backup))
            indices = state['contentBinding']['sourcePhysicalIndices']
            spoken_ref=state['contentAdmission'].get('spokenTargets')
        d = workflow._job_dir(vid)
        spoken = spoken_targets.load(d,vid,original,indices,digest)
        if (spoken.reference if spoken else None)!=spoken_ref:
            raise ValueError('Pronunciation review changed')
        anchors = preview.recording_entrances(ws,vid,digest,original,indices,duration,spoken=spoken)
        layers, single, handoffs = [], [], []
        root = path.parent/'vocal-overlap'
        if (root/'review.private.json').is_file():
            record = read(root/'review.private.json')
            if record.get('status') in ('single','layered'):
                current = preview._inputs(d,digest,placement_only=True)
                if current and record.get('binding')==current[1]:
                    import re
                    if not re.fullmatch('[0-9a-f]{64}', record['folder']):
                        raise ValueError('Vocal part review folder changed')
                    folder = root/record['folder']
                    single_sha = record['timingSha256']
                    if record.get('status')=='layered':
                        preview._timing(folder,current[1],record['timingSha256'])
                        voices = preview._checked_timing(folder,current[1],'voice-timing.private.json',record['timingSha256'])
                        layers, single_sha = voices['lines'], voices['voiceEvidence']['singleVoiceSha256']
                    preview._single_timing(folder,current[1],single_sha)
                    packet = preview._checked_timing(folder,current[1],'word-timing.private.json',single_sha)
                    if packet['durationMs'] != duration:
                        raise ValueError('Vocal entrance clock differs from the recording')
                    single = packet['lines']
                    if [line['text'] for line in single] != [l['text'] for l in preview._sheet_lines(original['text'])]:
                        raise ValueError('Vocal-part sheet differs')
                    if record.get('backingActivitySha256'):
                        file=folder/'backing-activity.private.json'
                        profile=read(file)
                        if (sha256_file(file)!=record['backingActivitySha256'] or profile['recordingSha256']!=digest
                                or profile['singleVoiceSha256']!=single_sha):
                            raise ValueError('Vocal-part measurements changed')
                        handoffs=profile.get('handoffs',[])
        child = workflow.runtime/'children'/f'{vid}.stemwise.zip'
        published = published_override
        rhythm_document = None
        expected = None
        if published is None and (job['state'] in ('VALIDATED','DELIVERED') or job.get('withheldPublication')) and child.is_file():
            expected = job.get('validation',{}).get('childSha256')
            if not expected or sha256_file(child)!=expected:
                raise ValueError('Validated child changed')
            document,_ = read_child(child,workflow._runtime_config())
            if document['source']['videoId']!=vid or (job.get('recordingInput') and
                    document['source'].get('recording')!=job['recordingInput']):
                raise ValueError('Child belongs to a different recording')
            published=next((t for t in document.get('lyrics',{}).get('tracks',[]) if t['kind']=='original'),None)
            if validated_audio == digest and checkpoint.get('videoId') == vid:
                rhythm_document = document
        if selected_prediction is not None:
            if published is not None and published!=selected_prediction:
                raise ValueError('Selected intervals differ from the current bound owner prediction')
            published=selected_prediction
        media = job.get('recordingInput',{}).get('file')
        if media:
            media=workflow._windows_path(workflow.workspace_root/media)
        else:
            pcm = workflow.analysis_temp/vid/'analysis.wav'
            if pcm.is_file():
                media = workflow._windows_path(pcm)
        measurement = {}
        resolved_releases = None
        native_choices = {} if published_override is None else None
        if selected_prediction is not None:
            measurement['timingSource']='owner-verified-content-acoustic-estimates'
        if published is not None:
            try:
                if selected_prediction is None:
                    resolved_releases = _require_selected_measurements(ws, state, job, original, audio, published,
                                                                       native_choices=native_choices)
                    timing_authority = job.get('validation', {}).get('capabilities', {}).get('timingAuthority') or {}
                    listening_ref = timing_authority.get('listeningReleaseEvidence')
                    if timing_authority.get('kind') == 'reviewed-recording-acoustic-words':
                        from stemwise_engine.listened_release_choice import active
                        if active(ws.job_dir(vid)) != listening_ref:
                            raise ValueError('A changed recording listening decision requires Validate')
                    if listening_ref:
                        from stemwise_engine.listened_release_choice import report
                        measurement['listeningReleaseSelection'] = {
                            'sha256': listening_ref['sha256'], 'words': report(ws.job_dir(vid), listening_ref)}
            except (OSError, ValueError, TypeError, KeyError, IndexError) as error:
                measurement['selectedMeasurementStatus'] = 'unverified'
                measurement['selectedMeasurementReason'] = str(error)[:200]
            else:
                measurement['selectedMeasurementStatus'] = 'matched'
        owners = None
        if spoken and single:
            readings, languages = spoken.word_lines(indices), spoken.language_lines(indices)
            owners = [list(zip(words, langs, strict=True)) for words,langs in zip(readings,languages,strict=True)]
        estimates = ()
        misplaced = ()
        if measurement.get('selectedMeasurementStatus') == 'matched' and selected_prediction is None:
            authority = job.get('validation', {}).get('capabilities', {}).get('timingAuthority') or {}
            estimates = authority.get('equivalentEstimates', {}).get('words', ())
            misplaced = authority.get('phrasePlacementEvidence', {}).get('misplacedWords', ())
        breaks=()
        if published and single:
            from stemwise_engine.instrumental_break import scan
            breaks,_=scan(ws,state,original,indices,audio,published,single)
        output = inspect(original,published,summary,anchors,duration,media,layers,single,handoffs,
                         resolved_releases=resolved_releases,spoken_owners=owners,equivalent_estimates=estimates,
                         misplaced=misplaced,instrumental_breaks=breaks)
        output.update(measurement)
        flags=output['publicationFlags']
        output.update(unresolvedPublicationFlagCount=len(flags),clearedPublicationFlagCount=(
                      output['acousticallyClearedFlagCount'] + output['estimatePolicyClearedFlagCount']))
        if published and validated_audio and flags:
            from stemwise_engine.delivery_timing import checked_listening_review, review_binding
            binding=review_binding(vid,digest,published,flags)
            output['listeningReviewBinding']=binding
            try:
                checked_listening_review(workflow,job,binding,flags)
            except (OSError,ValueError,TypeError,KeyError) as error:
                output['listeningReviewReason']=str(error)[:200]
            else:
                output['unresolvedPublicationFlagCount']=0
                output['clearedPublicationFlagCount']+=len(flags)
        local=state.get('stanzaTimingReview',{})
        from stemwise_engine.stanza_timing import recording_binding
        if (local and anchors and all(local.get(k)==v for k,v in recording_binding(digest, state['mappingSha256'],
                spoken.reference if spoken else None).items()) and
                local.get('anchors')==[[i,t] for i,t in sorted(anchors.items())]):
            output['localStanzaMeasurements']=local['words']
        if published_override is None:
            output['listeningOptions']=[]
        if (published_override is None and expected and validated_audio and output['unresolvedPublicationFlagCount']
                and output.get('selectedMeasurementStatus')=='matched'):
            if published['format']=='regional':
                from stemwise_engine.listened_release_choice import options,prepare_command,reviewed_options
                output['listeningOptions']=options(published,flags,native_choices,resolved_releases or {},
                                                    job['validation']['childSha256'],digest)
                confirmed = reviewed_options(workflow._job_dir(vid), binding, flags)
                for option in output['listeningOptions']:
                    position = option['line'], option['wordIndex']
                    option['status'] = 'A-confirmed' if position in confirmed else 'needs-listening'
                    option['prepareCommand']=prepare_command(workflow,vid,option['id'])
            from stemwise_engine.repeat_beat_review import checked_beats, rank_ends, recording_clock
            guidance={'purpose':'listening-order-only','status':'no-verified-beat-grid','words':[]}
            beats=checked_beats(rhythm_document,checkpoint,expected,digest,duration) if rhythm_document else []
            events=rhythm_document['analysis']['rhythm']['beats'] if beats else []
            if beats and single:
                meter={e['timeMs']:e.get('beatInBar') for e in events}
                hints=rank_ends(beats,published,single,summary,anchors,flags,owners,meter)
                guidance.update(status='ranked' if hints else 'no-consistent-repeat-comparison',
                    source='beat-this-full',childSha256=expected,recordingSha256=digest,vocalGuideSha256=single_sha,words=hints)
            elif beats:
                guidance['status']='no-recording-bound-vocal-guide'
            output['repeatBeatReview']=guidance
            from stemwise_engine.instrumental_onset_review import automatic
            verified=bool(rhythm_document and recording_clock(rhythm_document,checkpoint,expected,digest,duration))
            output['instrumentalBoundaryReview']=automatic(workflow,job,audio,published,summary,anchors,
                                                          flags,events,guidance['words'],verified)
        output['export'] = ('verified-phone-delivery' if (status or {}).get('phoneDeliveries') else
            'delivery-recorded' if (status or job)['state']=='DELIVERED' else
            'validated-child-only' if (status or job)['state']=='VALIDATED' and published else 'unverified')
        return output
    except (OSError, ValueError, KeyError, TypeError, IndexError) as error:
        return {'status':'timing-review-inputs-unavailable','reason':str(error)[:200]}

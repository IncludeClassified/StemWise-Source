"""Replay an explicit listening choice between two retained releases of one sung word."""
import copy
import hashlib
from pathlib import Path
import re

from stemwise_engine.alignment_endpoints import WORD_END_AGREEMENT_MS, WORD_START_AGREEMENT_MS
from stemwise_engine.common import (atomic_write_json, exclusive_process_lock, json_bytes, parse_strict_json_bytes,
                                    read_bounded_json, read_bounded_regular_bytes, sha256_file, validate_generated_path)
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.editorial_review import valid_reviewer
from stemwise_engine.lyrics_tokenization import display_words

ACTIVE = 'listened-release-selection.private.json'
APPROVED = 'approved-audible-release-estimate'
APPROVED_INTERVAL = 'approved-audible-interval-estimate'


def _digest(value):
    return hashlib.sha256(json_bytes(value)).hexdigest()


def active(folder):
    path = folder/ACTIVE
    return read_bounded_json(path,max_bytes=MAX_JSON_BYTES,root=folder) if path.is_file() else None


def _record(folder, ref):
    if not isinstance(ref,dict) or set(ref)!={'path','sha256'} or not re.fullmatch('[a-f0-9]{64}',ref['sha256']):
        raise ValueError('Invalid listening decision reference')
    if ref['path'] != f"listened-release-{ref['sha256']}.private.json":
        raise ValueError('Invalid listening decision filename')
    raw = read_bounded_regular_bytes(folder/ref['path'],max_bytes=MAX_JSON_BYTES,root=folder)
    if hashlib.sha256(raw).hexdigest()!=ref['sha256']:
        raise ValueError('Listening decision has changed')
    return parse_strict_json_bytes(raw)


def _binding(workspace,state,admission,audio,track):
    return {'videoId':state['videoId'],'recordingSha256':audio['sha256'],'durationMs':audio['durationMs'],
            'stateSha256':sha256_file(workspace.state_path(state['videoId'])),'mappingSha256':state['mappingSha256'],
            'contentEvidenceSha256':admission.evidence_sha256,'priorTrackSha256':_digest(track)}


def _comparison(workspace,video_id,name):
    if not isinstance(name,str) or Path(name).name!=name or not name or '/' in name or '\\' in name:
        raise ValueError('Expected a listening comparison in the job timing-review-clips directory')
    root=workspace.root.parent/'jobs'/video_id/'timing-review-clips'
    return sha256_file(validate_generated_path(root,root/name))


def _candidate(track, choices, measured, line, word_index, family):
    if not all(type(n) is int and n>0 for n in (line,word_index)):
        raise ValueError('Invalid listened word index')
    pos=line-1,word_index-1
    row=choices.get((line,word_index),{})
    if family not in row or pos in measured:
        raise ValueError('No unresolved same-rendition native release for this selection')
    entry=track['lines'][pos[0]]
    timing=entry['words'][pos[1]]['timing']
    start,end=timing['startMs'],timing['endMs']
    selected=row[family]
    word=selected['word']
    # A third distinct native release, an implausible attack, or an intervening word
    # cannot be cleared by a listening comparison between two choices.
    candidates=list(row.values())
    if (timing['status']!='estimated' or abs(end-word['endMs'])<=WORD_END_AGREEMENT_MS or
            {c['word']['endMs'] for c in candidates}!={end,word['endMs']} or
            any(abs(c['lineStartMs']-selected['lineStartMs'])>WORD_START_AGREEMENT_MS
                or abs(c['word']['startMs']-start)>WORD_START_AGREEMENT_MS for c in candidates)
            or not any(start<=c['word']['startMs']<c['word']['endMs']<=word['endMs'] for c in candidates)):
        raise ValueError('Listening decision needs exactly the incumbent and one eligible competing measured release')
    return {'line':line,'wordIndex':word_index,'text':display_words(entry['text'])[pos[1]][2],
            'prior':dict(timing),'selected':selected,'compared':candidates}


def options(published,flags,native,measured,child_sha,audio_sha):
    """List reviewable choices only from the native packets already checked for this selected child."""
    from stemwise_engine.word_timing_review import RELEASE_FLAGS
    from stemwise_engine.recording_release_repair import _ordered_changes
    from stemwise_engine.recording_word_placement import BASIS
    spans=[(i,j,w['timing']) for i,row in enumerate(published['lines'],1) for j,w in enumerate(row['words'],1)]
    indices={(i,j):k for k,(i,j,_) in enumerate(spans)}
    bounds=[(t['startMs'],t['endMs']) for _,_,t in spans]
    grouped={}
    for flag in flags:
        grouped.setdefault((flag['line'],flag['wordIndex']),[]).append(flag)
    result=[]
    for (line,j),items in sorted(grouped.items()):
        if any(f['reason'] not in RELEASE_FLAGS for f in items):
            continue
        alternatives=[]
        for family in sorted(native.get((line,j),{})):
            try:
                alternatives.append(_candidate(published,native,measured,line,j,family))
            except ValueError:
                continue
        if len(alternatives)!=1:
            continue
        c=alternatives[0]
        current,new=c['prior'],c['selected']['word']
        index=indices[line,j]
        end_only={index:(current['startMs'],new['endMs'])}
        both={index:(new['startMs'],new['endMs'])}
        can_end=_ordered_changes(bounds,end_only)==end_only
        can_both=(current.get('basis')==BASIS and len({(x['word']['startMs'],x['word']['endMs']) for x in c['compared']})==2
                  and _ordered_changes(bounds,both)==both)
        identifier=_digest([child_sha,audio_sha,c,[(f['reason'],f['observation']) for f in items]])[:16]
        lo=min([current['startMs'],new['startMs'],*[f['clip']['startMs'] for f in items]])
        hi=max([current['endMs'],new['endMs'],*[f['clip']['endMs'] for f in items]])
        result.append({'id':f'{line}-{j}-{identifier}','line':line,'wordIndex':j,'word':c['text'],
                       'A':{'startMs':current['startMs'],'endMs':current['endMs']},
                       'B':{'startMs':new['startMs'],'endMs':new['endMs'],'family':c['selected']['family'],
                            'canSelectEnd':can_end,'canSelectInterval':can_both},
                       'context':{'startMs':lo,'endMs':hi}})
    return result


def prepare_command(workflow,video_id,option_id):
    """An exact PowerShell command with absolute WSL paths, usable from any directory."""
    from stemwise_engine.runtime_layout import load_layout
    root=Path(__file__).resolve().parents[2]
    layout=load_layout(root)
    source=getattr(workflow,'source_context',None)
    selector=['--recording-key',source.recording_key] if source else ['--video-id',video_id]
    args=[layout['wslDistribution'],'-e',str(root/layout['environments']['main']),'-B','-m',
          'stemwise_engine.listened_release_choice','--config',str(workflow.config_path),*selector,'--prepare',option_id]
    return 'wsl -d '+' '.join("'"+s.replace("'","''")+"'" for s in args)


def reviewed_options(directory,binding,flags):
    """Report individually confirmed current rows while the full recording gate is still blocked."""
    path=directory/'delivery-timing-review.json'
    if not path.is_file():
        return set()
    try:
        value=read_bounded_json(path,max_bytes=MAX_JSON_BYTES,root=directory)
    except (OSError,ValueError):
        return set()  # The delivery check reports the invalid review and keeps its flags open.
    if not isinstance(value,dict) or value.get('binding')!=binding or not valid_reviewer(value.get('reviewer')):
        return set()
    if not isinstance(value.get('decisions'),list):
        return set()
    fields=('line','wordIndex','startMs','endMs','reason')
    confirmed={tuple(d.get(k) for k in fields) for d in value.get('decisions',[]) if isinstance(d,dict)
               and d.get('disposition')=='confirmed-on-recording' and isinstance(d.get('heard'),str) and d['heard'].strip()}
    remaining={(f['line'],f['wordIndex']) for f in flags if tuple(f[k] for k in fields) not in confirmed}
    return {(f['line'],f['wordIndex']) for f in flags}-remaining


def _apply(workspace,state,admission,audio,track,choices,measured,record):
    from stemwise_engine.recording_release_repair import _ordered_changes
    from stemwise_engine.recording_word_placement import BASIS
    if record.get('binding')!=_binding(workspace,state,admission,audio,track):
        raise ValueError('Listening decision belongs to a different recording, review or prior placement')
    decisions=record.get('decisions')
    if not decisions or not isinstance(decisions,list):
        raise ValueError('No individual listening decisions')
    spans=[(i+1,j+1,w['timing']) for i,line in enumerate(track['lines']) for j,w in enumerate(line['words'])]
    indices={(line,j):k for k,(line,j,_) in enumerate(spans)}
    changes={}
    for item in decisions:
        both=item.get('disposition')==APPROVED_INTERVAL
        if (item.get('disposition') not in (APPROVED,APPROVED_INTERVAL) or not valid_reviewer(item.get('reviewer'))
                or not isinstance(item.get('heard'),str) or not item['heard'].strip()
                or both and (not isinstance(item.get('heardStart'),str) or not item['heardStart'].strip())
                or not both and ('heardStart' in item or 'supersedes' in item)
                or _comparison(workspace,state['videoId'],item['comparisonFile'])!=item['comparisonSha256']):
            raise ValueError('Missing or changed listening comparison and explicit estimate approval')
        chosen=item['candidate']
        line,j=chosen['line'],chosen['wordIndex']
        candidate=_candidate(track,choices,measured,line,j,chosen['selected']['family'])
        if candidate!=chosen or indices[line,j] in changes:
            raise ValueError('Listening decision no longer has the same native word measurements')
        word=candidate['selected']['word']
        if both:
            native={(c['word']['startMs'],c['word']['endMs']) for c in candidate['compared']}
            if len(native)!=2 or candidate['prior'].get('basis')!=BASIS:
                raise ValueError('Both-edge listening needs two distinct native alternatives and an unlocked incumbent onset')
            if 'supersedes' in item:
                previous=_record(workspace.job_dir(state['videoId']),item['supersedes'])
                if (previous.get('binding')!=record['binding'] or not any(d.get('disposition')==APPROVED
                        and d.get('candidate')==candidate for d in previous.get('decisions',()))):
                    raise ValueError('Superseded review belongs to a different recording, word or native release')
        changes[indices[line,j]]=(word['startMs'] if both else candidate['prior']['startMs'],word['endMs'])
    base=[(t['startMs'],t['endMs']) for _,_,t in spans]
    if _ordered_changes(base,changes)!=changes:
        raise ValueError('Listened release would cross another selected word')
    for k,(start,end) in changes.items():
        line,_,word=spans[k]
        basis=('Existing acoustic word interval selected by recording listening review.' if start!=word['startMs'] else
               'Existing acoustic release selected by recording listening review.')
        word.update(startMs=start,endMs=end,basis=basis)
        selected=track['lines'][line-1]
        selected['timing']['startMs']=selected['words'][0]['timing']['startMs']
        selected['timing']['endMs']=max(w['timing']['endMs'] for w in selected['words'])


def replay(workspace,state,admission,audio,track,choices,measured,ref):
    _apply(workspace,state,admission,audio,track,choices,measured,_record(workspace.job_dir(state['videoId']),ref))


def protected(folder, track, measured, ref):
    """Preserve a replayed listening choice in subsequent estimators, separately from acoustic confirmation."""
    if not ref:
        return measured
    locks=dict(measured)
    for item in _record(folder,ref)['decisions']:
        c=item['candidate']
        i,j=c['line']-1,c['wordIndex']-1
        word=track['lines'][i]['words'][j]['timing']
        end=c['selected']['word']['endMs']
        start=c['selected']['word']['startMs'] if item['disposition']==APPROVED_INTERVAL else c['prior']['startMs']
        if (word['startMs'],word['endMs'])!=(start,end):
            raise ValueError('A later timing operation changed a reviewed listening choice')
        locks[i,j]=start,end,ref['sha256']
    return locks


def report(folder,ref):
    """Compact selections for reporting after the projected intervals have passed replay."""
    return [{'line':c['line'],'wordIndex':c['wordIndex'],'word':c['text'],'endMs':c['selected']['word']['endMs'],
             'formerEndMs':c['prior']['endMs'],'family':c['selected']['family'],'reviewer':d['reviewer'],
             **({'startMs':c['selected']['word']['startMs'],'formerStartMs':c['prior']['startMs'],
                 'disposition':APPROVED_INTERVAL} if d['disposition']==APPROVED_INTERVAL else {})}
            for d in _record(folder,ref)['decisions'] for c in [d['candidate']]]


def select_listened_release(workspace,state,job,original,audio,published,*,line,word_index,family,reviewer,heard,
                            comparison_file,comparison_sha256,disposition,heard_start=None):
    """Issue a choice for the current validated preview and replay it before selecting it."""
    from stemwise_engine.recording_word_placement import project
    from stemwise_engine.lyric_content import ContentAdmission
    from stemwise_engine.word_timing_review import _require_selected_measurements
    video_id=state['videoId']
    folder=workspace.job_dir(video_id)
    job_dir=workspace.root.parent/'jobs'/video_id
    with exclusive_process_lock(job_dir/'workflow.lock'):
        fresh=read_bounded_json(job_dir/'job.json',max_bytes=MAX_JSON_BYTES,root=job_dir)
        if (fresh['videoId']!=video_id or fresh.get('validation')!=job.get('validation')
                or read_bounded_json(workspace.state_path(video_id),max_bytes=MAX_JSON_BYTES,root=folder)!=state):
            raise ValueError('Current validation or endpoint state has changed')
        _require_selected_measurements(workspace,state,job,original,audio,published)
        capabilities=job['validation']['capabilities']
        authority=capabilities['timingAuthority']
        admission=ContentAdmission.from_record(capabilities['contentAdmission'])
        choices,measured={},{}
        base=project(workspace,state,original,admission,audio,authority.get('vowelReleaseEvidence'),
                     repair_ref=authority.get('releaseRepairEvidence'),resolved_releases=measured,native_choices=choices)
        ref=active(folder)
        binding=_binding(workspace,state,admission,audio,base)
        record=_record(folder,ref) if ref else {'binding':binding,'decisions':[]}
        c=_candidate(base,choices,measured,line,word_index,family)
        decision={'candidate':c,'reviewer':reviewer,'heard':heard,'disposition':disposition,
                  'comparisonFile':comparison_file,'comparisonSha256':comparison_sha256}
        if heard_start is not None:
            decision['heardStart']=heard_start
        previous=next((d for d in record['decisions'] if (d['candidate']['line'],d['candidate']['wordIndex'])==(line,word_index)),None)
        if previous is None or {k:v for k,v in previous.items() if k!='supersedes'}!=decision:
            if ref!=authority.get('listeningReleaseEvidence'):
                raise ValueError('An earlier listening decision still awaits validation')
            if previous is None:
                record['decisions'].append(decision)
            else:
                if not (previous['candidate']==c and previous['disposition']==APPROVED and disposition==APPROVED_INTERVAL):
                    raise ValueError('Only the same validated native release may be extended to its approved onset')
                decision['supersedes']=ref
                record['decisions'][record['decisions'].index(previous)]=decision
        _apply(workspace,state,admission,audio,copy.deepcopy(base),choices,measured,record)
        # Write the immutable review first. An interrupted publication leaves the previous pointer usable.
        data=json_bytes(record)
        digest=hashlib.sha256(data).hexdigest()
        ref={'path':f'listened-release-{digest}.private.json','sha256':digest}
        from stemwise_engine.common import atomic_write_bytes
        atomic_write_bytes(folder/ref['path'],data,root=folder)
        _record(folder,ref)
        atomic_write_json(folder/ACTIVE,ref,root=folder)
        return ref


def select_from_job(workflow,video_id,review):
    from stemwise_engine.lyrics_alignment import AlignmentWorkspace, _plain_original
    from stemwise_engine.packages import read_child
    required = {'expectedChildSha256','recordingSha256','line','wordIndex','family','reviewer','heard',
                'comparisonFile','comparisonSha256','disposition'}
    if isinstance(review,dict) and review.get('disposition')==APPROVED_INTERVAL:
        required.add('heardStart')
    if not isinstance(review,dict) or set(review)!=required:
        raise ValueError('Provide the exact reviewed child, recording, comparison, word and measured family')
    ws=AlignmentWorkspace(workflow.root,workspace_root=workflow.workspace_root)
    job=read_bounded_json(workflow._job_path(video_id),max_bytes=MAX_JSON_BYTES,root=workflow._job_dir(video_id))
    child=workflow.runtime/'children'/f'{video_id}.stemwise.zip'
    validation=job.get('validation') or {}
    if (video_id!=job['videoId'] or sha256_file(child)!=review['expectedChildSha256']
            or validation.get('childSha256')!=review['expectedChildSha256']
            or validation.get('audioSha256')!=review['recordingSha256']):
        raise ValueError('Current recording or validated playback differs from the listener comparison')
    document,_=read_child(child,workflow._runtime_config())
    if (document['source']['videoId']!=video_id or document['source'].get('recording')!=job.get('recordingInput')
            or workflow.source_context and job.get('recordingInput')!=workflow.recording.identity):
        raise ValueError('Playback belongs to another source recording')
    published=next(t for t in document['lyrics']['tracks'] if t['kind']=='original')
    state=read_bounded_json(ws.state_path(video_id),max_bytes=MAX_JSON_BYTES,root=ws.job_dir(video_id))
    backup=ws.current_backup_path(video_id)
    if sha256_file(backup)!=state['canonicalPlainSha256']:
        raise ValueError('Retained endpoint lyrics changed')
    original=_plain_original(read_bounded_json(backup,max_bytes=MAX_JSON_BYTES,root=ws.canonical_plain))
    checkpoint=workflow.runtime/'work/checkpoints'/f'{video_id}.json'
    saved=read_bounded_json(checkpoint,max_bytes=MAX_JSON_BYTES,root=checkpoint.parent)
    audio=saved['originalAudio']
    if saved['videoId']!=video_id or audio['sha256']!=review['recordingSha256'] or audio['durationMs']!=state['durationMs']:
        raise ValueError('Endpoint recording clock differs from the listening comparison')
    ref=select_listened_release(ws,state,job,original,audio,published,line=review['line'],word_index=review['wordIndex'],
        family=review['family'],reviewer=review['reviewer'],heard=review['heard'],comparison_file=review['comparisonFile'],
        comparison_sha256=review['comparisonSha256'],disposition=review['disposition'],heard_start=review.get('heardStart'))
    return {'state':'awaiting-validation','review':ref,'words':report(ws.job_dir(video_id),ref)}


def _confirm_current(workflow,job,report,option,reviewer,heard,comparison):
    """Keep all individual reviews under the existing exact selected-track delivery binding."""
    from stemwise_engine.common import atomic_write_bytes
    from stemwise_engine.delivery_timing import checked_listening_review
    from stemwise_engine.workflow_records import FutureSongError
    directory=workflow._job_dir(job['videoId'])
    path=directory/'delivery-timing-review.json'
    binding,flags=report['listeningReviewBinding'],report['publicationFlags']
    with exclusive_process_lock(directory/'workflow.lock'):
        live=read_bounded_json(workflow._job_path(job['videoId']),max_bytes=MAX_JSON_BYTES,root=directory)
        authority=live.get('validation',{}).get('capabilities',{}).get('timingAuthority') or {}
        if (live.get('validation')!=job['validation'] or
                active(workflow._alignment_state_path(job['videoId']).parent)!=authority.get('listeningReleaseEvidence')):
            raise ValueError('The prepared listening comparison is stale')
        records=[]
        if path.is_file():
            raw=read_bounded_regular_bytes(path,max_bytes=MAX_JSON_BYTES,root=directory)
            prior=parse_strict_json_bytes(raw)
            if prior.get('binding')==binding and valid_reviewer(prior.get('reviewer')):
                records=[{'reviewer':prior['reviewer'],**d} for d in prior['decisions']]
            else:
                archive=directory/'timing-review-clips'/f'earlier-listening-review-{hashlib.sha256(raw).hexdigest()}.json'
                atomic_write_bytes(archive,raw,root=directory)
        fields=('line','wordIndex','startMs','endMs','reason')
        def identity(row):
            return tuple(row[k] for k in fields)
        by_flag={identity(d):d for d in records}
        all_flags={identity(f) for f in flags}
        if len(by_flag)!=len(records) or by_flag.keys()-all_flags:
            raise ValueError('Retained listening decisions differ from the current disputed intervals')
        for f in flags:
            if (f['line'],f['wordIndex']) != (option['line'],option['wordIndex']):
                continue
            k=identity(f)
            if k in by_flag and by_flag[k].get('disposition')=='confirmed-on-recording' and by_flag[k].get('heard'):
                continue  # Preserve the original listener and their observation.
            by_flag[k]={**dict(zip(fields,k)),'reviewer':reviewer,'heard':heard,'comparisonSha256':comparison,
                        'disposition':'confirmed-on-recording'}
        ordered=[by_flag[identity(f)] for f in flags if identity(f) in by_flag]
        if ordered!=records:
            atomic_write_json(path,{'binding':binding,'reviewer':reviewer,'decisions':ordered},root=directory)
        try:
            checked_listening_review(workflow,job,binding,flags)
        except FutureSongError:
            return {'state':'partial-listening-review','confirmed':len([d for d in ordered if d.get('disposition')=='confirmed-on-recording']),
                    'required':len(flags)}
        return {'state':'all-current-flags-reviewed','confirmed':len(flags),'required':len(flags)}


def review_option(workflow,video_id,action,option_id,*,selected=None,reviewer=None,heard=None,heard_start=None):
    """Prepare or explicitly review a pair that the ordinary timing report still exposes."""
    from stemwise_engine.common import atomic_write_bytes
    from stemwise_engine.listening_comparison import render
    from stemwise_engine.word_timing_review import readback
    directory=workflow._job_dir(video_id)
    job=read_bounded_json(workflow._job_path(video_id),max_bytes=MAX_JSON_BYTES,root=directory)
    current=readback(workflow,job)
    option=next((o for o in current.get('listeningOptions',()) if o['id']==option_id),None)
    if current.get('selectedMeasurementStatus')!='matched' or not option:
        raise ValueError('That listening option is unavailable on the current validated recording; reopen Status')
    if action=='apply' and selected=='neither':
        return {'state':'unresolved','option':option_id}
    html,media=render(workflow,video_id,option)
    digest=hashlib.sha256(html).hexdigest()
    filename=f'word-{option_id}.html'
    path=directory/'timing-review-clips'/filename
    if path.is_file() and sha256_file(path)!=digest:
        # A previous presentation may be cited by an immutable listening decision.
        # Keep it intact when the renderer or the available verified audio views change.
        filename=f'word-{option_id}-{digest}.html'
        path=directory/'timing-review-clips'/filename
    if action=='prepare':
        if not path.is_file():
            atomic_write_bytes(path,html,root=directory)
        if sha256_file(path)!=digest:
            raise ValueError('Saved comparison differs from the verified recording and intervals')
        return {'option':option,'media':media,'comparison':workflow._windows_path(path)}
    if action!='apply' or selected not in ('A','B') or not valid_reviewer(reviewer) or not isinstance(heard,str) or not heard.strip():
        raise ValueError('Apply requires an explicit choice, listener identity and what they heard')
    if not path.is_file() or sha256_file(path)!=digest:
        raise ValueError('Prepare and listen to this exact comparison before applying a choice')
    if selected=='A':
        if heard_start:
            raise ValueError('A keeps both current boundaries; describe the observation in --heard')
        return _confirm_current(workflow,job,current,option,reviewer,heard,digest)
    if not option['B']['canSelectInterval' if heard_start else 'canSelectEnd']:
        raise ValueError('That alternative cannot be selected while preserving the checked neighboring intervals and onset locks')
    request={'expectedChildSha256':job['validation']['childSha256'],'recordingSha256':job['validation']['audioSha256'],
             'line':option['line'],'wordIndex':option['wordIndex'],'family':option['B']['family'],'reviewer':reviewer,
             'heard':heard,'comparisonFile':filename,'comparisonSha256':digest,'disposition':APPROVED}
    if heard_start:
        request.update(disposition=APPROVED_INTERVAL,heardStart=heard_start)
    return select_from_job(workflow,video_id,request)


def main():
    import argparse
    import json
    import sys
    from stemwise_engine.future_song import FutureSongWorkflow
    from stemwise_engine.recording_workflow import RecordingWorkflow
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config',required=True,type=Path)
    parser.add_argument('--recording-key',help='Exact selected native recording key')
    parser.add_argument('--video-id')
    mode=parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--review',help='JSON review file, or - to read standard input')
    mode.add_argument('--prepare',metavar='OPTION',help='Prepare the A/B listening comparison listed in Status')
    mode.add_argument('--apply',metavar='OPTION',help='Review the prepared A/B comparison')
    parser.add_argument('--choice',choices=('A','B','neither'))
    parser.add_argument('--reviewer')
    parser.add_argument('--heard')
    parser.add_argument('--heard-start')
    args=parser.parse_args()
    workflow=RecordingWorkflow(args.config,args.recording_key) if args.recording_key else FutureSongWorkflow(args.config)
    video_id=args.video_id or (workflow.video_id if args.recording_key else None)
    if not video_id or args.recording_key and video_id!=workflow.video_id:
        parser.error('Select one exact video ID or native recording key')
    if args.prepare or args.apply:
        if args.prepare and any((args.choice,args.reviewer,args.heard,args.heard_start)):
            parser.error('Prepare only generates the comparison')
        result=review_option(workflow,video_id,'prepare' if args.prepare else 'apply',args.prepare or args.apply,
                             selected=args.choice,reviewer=args.reviewer,heard=args.heard,heard_start=args.heard_start)
        print(json.dumps(result,ensure_ascii=False))
        return
    if args.review=='-':
        raw=sys.stdin.buffer.read(MAX_JSON_BYTES+1)
        if len(raw)>MAX_JSON_BYTES:
            raise ValueError('Listening review is too large')
        review=parse_strict_json_bytes(raw)
    else:
        path=Path(args.review)
        review=read_bounded_json(path,max_bytes=MAX_JSON_BYTES,root=path.parent)
    print(json.dumps(select_from_job(workflow,video_id,review),ensure_ascii=False))


if __name__=='__main__':
    main()

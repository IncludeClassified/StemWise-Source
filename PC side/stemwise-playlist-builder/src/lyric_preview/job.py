"""Run and read back recording-bound word previews during normal PC jobs."""
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

from stemwise_engine.common import (atomic_write_bytes, atomic_write_json, parse_strict_json_bytes,
    read_bounded_json, read_bounded_regular_bytes, run_process_tree, sha256_file, validate_generated_path)
from stemwise_engine.lyrics_intake import read_recording_draft
from stemwise_engine.lyrics_normalize import physical_lines, performed_line_indices
from stemwise_engine.runtime_environment import command_environment, environment_python
from .layout import find_passages, stanza_groups


def _read(path, root):
    return read_bounded_json(path,max_bytes=16*1024*1024,root=root)


def _inputs(job_dir, audio_hash, *, placement_only=False):
    text,draft,meta,metadata=read_recording_draft(job_dir)
    language=meta.get('originalLanguage')
    if not isinstance(language,str):
        return None
    language=language.lower().split('-')[0]
    from stemwise_engine import spoken_targets
    if language not in ('ar','en','tr','mul') or (language!='mul' and len(meta.get('componentLanguages') or [])>1):
        return None
    indices=performed_line_indices(text)
    if not indices:
        return None
    if (job_dir/spoken_targets.PLAN_FILE).is_file() and not placement_only:
        # A fresh reviewed job can acquire the same acoustic stanza search as a
        # job whose private preview predates pronunciation review. Display words
        # seed only the search. Native word decoding uses the actual spoken plan.
        if not next(stanza_groups(_sheet_lines(text)),None):
            return None
        track=dict(id='original',kind='original',format='plain',text=text,language=meta['originalLanguage'],
                   script=meta.get('originalScript'),componentLanguages=meta.get('componentLanguages',[]))
        spoken_targets.load(job_dir,job_dir.name,track,indices,audio_hash)
    plain=job_dir/'drafts/original.txt'
    sheet=text.encode('utf-8')
    if plain.is_file():
        sheet=read_bounded_regular_bytes(plain,max_bytes=16*1024*1024,root=job_dir)
        if hashlib.sha256(sheet).hexdigest()!=draft:
            raise ValueError('Private preview draft changed during inspection')
    return sheet,{'audioSha256':audio_hash,'draftSha256':draft,'metadataSha256':metadata,
                  'language':language,'sheetSha256':hashlib.sha256(sheet).hexdigest()}


def _recipe(project):
    from stemwise_engine.model_registry import model_assets
    from stemwise_engine.provenance import semantic_source_identity
    spec=model_assets('lyrics_align_pc')['PRIVATE_VOCAL_PART_SEPARATOR']
    model=project.parent/'model-cache/lyrics-roformer'/spec['routeId']
    src=Path(__file__).parent
    files=[src/(name+'.py') for name in ('song_preview','overlapping_refrain',
           'separated_refrain','refrain_acoustics','supplied_song','instrumental_gap','repeated_stanzas',
           'layout','candidates','search','backing_activity','backing_words')]
    return [*[semantic_source_identity(f,portable=True) for f in files+[src.parent/'vocal_parts.py',
            src.parent/'model-route-registry.json',src.parent/'local_recovery_models.py']],
            semantic_source_identity(src/'song_preview_result.py',
                ('choose','support','recover_padded_edges','write_preview','write_native_preview'),portable=True),
            semantic_source_identity(src.parent/'acoustic_inputs.py',('mono_samples','mono_representation','informative_views'),portable=True),
            spec['checkpointSha256'],spec['configSha256'],(model/spec['checkpoint']).is_file(),
            (model/spec['config']).is_file()]


def _checked_timing(folder,binding,filename,digest=None):
    raw=read_bounded_regular_bytes(folder/filename,max_bytes=16*1024*1024,root=folder)
    if digest is not None and hashlib.sha256(raw).hexdigest()!=digest:
        raise ValueError('Private timing changed')
    data=parse_strict_json_bytes(raw)
    if (data['publicationAuthority'] is not False or data['recordingSha256']!=binding['audioSha256']
            or data['suppliedSheetSha256']!=binding['sheetSha256'] or data['language']!=binding['language']):
        raise ValueError('Private vocal intervals belong to different inputs')
    return data


def _sheet_lines(text):
    lines=physical_lines(text)
    return [{'sourceLine':i,'text':lines[i]} for i in performed_line_indices(text)]


def _single_timing(folder,binding,digest=None):
    from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping, lexical_mapping_identity
    from stemwise_engine.lyrics_tokenization import display_words
    data=_checked_timing(folder,binding,'word-timing.private.json',digest)
    raw=read_bounded_regular_bytes(folder/'performed.txt',max_bytes=16*1024*1024,root=folder)
    if hashlib.sha256(raw).hexdigest()!=binding['sheetSha256']:
        raise ValueError('Private preview sheet changed')
    text=raw.decode('utf-8-sig')
    lines=_sheet_lines(text)
    track=dict(kind='original',format='plain',id='original',language=binding['language'],text=text)
    mapping=build_lexical_occurrence_mapping(binding['audioSha256'][:11],track,[l['sourceLine'] for l in lines])
    if data['mapping']!=lexical_mapping_identity(mapping) or not lines or type(data['durationMs']) is not int:
        raise ValueError('Private word preview has an invalid mapping or clock')
    count=end=0
    for src,line,occ in zip(lines,data['lines'],mapping['performedOccurrences'],strict=True):
        if line['text']!=src['text'] or line['occurrenceId']!=occ['occurrenceId']:
            raise ValueError('Private preview changed the performed line')
        for word,owner,span in zip(line['words'],occ['words'],display_words(src['text']),strict=True):
            a,b=word['startMs'],word['endMs']
            if (word['wordId']!=owner['occurrenceWordId'] or word['timing']!='estimated'
                    or (word['startUtf16'],word['endUtf16'],word['text'])!=span or
                    type(a) is not int or type(b) is not int or not end<=a<b<=data['durationMs']):
                raise ValueError('Invalid private word ownership or interval')
            end=b
            count+=1
    ranges={(i+1,i+n) for starts,n in stanza_groups(lines) for i in starts}
    entrances={}
    repeated=data.get('repeatedStanzas',{})
    if not isinstance(repeated,dict) or not isinstance(repeated.get('placements',[]),list):
        raise ValueError('Invalid private stanza placements')
    previous_first=previous_ms=-1
    for row in repeated.get('placements',[]):
        first,last,t=row['firstLine'],row['lastLine'],row['vocalMatchMs']
        if (any(type(x) is not int for x in (first,last,t)) or (first,last) not in ranges
                or first<=previous_first or not previous_ms<t<data['durationMs']):
            raise ValueError('Invalid private stanza placement')
        entrances[first-1]=t
        previous_first,previous_ms=first,t
    return {'lines':len(lines),'words':count,'recoveredStanzas':len(entrances)},entrances,data['durationMs']


def _check_stanza_windows(folder, timing, binding, duration):
    repeated=timing['repeatedStanzas']
    path=validate_generated_path(folder,folder/repeated['observations'])
    if path.name!='observations.private.json' or sha256_file(path)!=repeated['observationsSha256']:
        raise ValueError('Stanza observation index changed')
    record=_read(path,folder)
    plan=record['plan']
    words=[w for line in timing['lines'] for w in line['words']]
    if (plan['master']!=binding['audioSha256'] or plan['rate']!=16000 or abs(plan['frames']/16-duration)>1
            or plan['language']!=binding['language'] or plan['wordIds']!=[w['wordId'] for w in words]
            or plan['words']!=[w['text'] for w in words] or set(record['sha256'])!={'lead','mix','backing'}):
        raise ValueError('Stanza observations belong to another recording or performance')
    key=hashlib.sha256(json.dumps(plan,sort_keys=True).encode()).hexdigest()
    for view, digest in record['sha256'].items():
        if sha256_file(path.parent/f'{view}-{key}.npz')!=digest:
            raise ValueError('Stanza acoustic measurements changed')
    offsets=[0]
    for line in timing['lines']:
        offsets.append(offsets[-1]+len(line['words']))
    for row in repeated['placements']:
        lo,hi=offsets[row['firstLine']-1],offsets[row['lastLine']]
        windows={(a,b) for first,last,a,b in plan['plans'] if (first,last)==(lo,hi)
                 and 0<=a<=row['vocalMatchMs']<b<=duration}
        if len(windows)<2:
            raise ValueError('Stanza lacks its own recorded search windows')


def recording_entrances(workspace,video_id,audio_sha,original,sources,duration_ms, *, spoken=None):
    """The native timers may use vocal locations to reject a conflicting search cut."""
    if not audio_sha:
        return {}
    root=workspace.job_dir(video_id)/'vocal-overlap'
    receipt=root/'review.private.json'
    if not receipt.is_file():
        return {}
    try:
        record=_read(receipt,root)
        if record['status'] not in ('single','layered') or not re.fullmatch('[0-9a-f]{64}',record['folder']):
            return {}
        if spoken is not None:
            spoken.require_current()
            if spoken.original_text != original['text']:
                return {}
        current=_inputs(workspace.root.parent/'jobs'/video_id,audio_sha,placement_only=spoken is not None)
        if (current is None or current[1]!=record['binding']
                or original['language'].lower().split('-')[0]!=current[1]['language']):
            return {}
        sheet=_sheet_lines(current[0].decode('utf-8-sig'))
        current_lines=_sheet_lines(original['text'])
        if current_lines!=sheet or list(sources)!=[l['sourceLine'] for l in sheet]:
            return {}
        folder=validate_generated_path(root,root/record['folder'])
        digest=record['timingSha256']
        if not isinstance(digest,str) or not re.fullmatch('[0-9a-f]{64}',digest):
            return {}
        if record['status']=='layered':
            layers=_checked_timing(folder,current[1],'voice-timing.private.json',digest)
            digest=layers['voiceEvidence']['singleVoiceSha256']
            if not isinstance(digest,str) or not re.fullmatch('[0-9a-f]{64}',digest):
                return {}
        _,entrances,duration=_single_timing(folder,current[1],digest)
        if entrances:
            _check_stanza_windows(folder,_checked_timing(folder,current[1],'word-timing.private.json',digest),
                                  current[1], duration)
        return entrances if duration==duration_ms else {}
    except (KeyError,ValueError,TypeError,OSError):
        return {}  # Optional search evidence; ordinary endpoint planning remains available.


def recording_handoffs(workspace,video_id,audio_sha,original,sources,duration):
    """Return only measured adjacent lead/choir handoffs on the current physical sheet."""
    root=workspace.job_dir(video_id)/'vocal-overlap'
    receipt=root/'review.private.json'
    if not receipt.is_file():
        return {}
    try:
        record=_read(receipt,root)
        if record['status']!='layered' or not record.get('backingActivitySha256'):
            return {}
        current=_inputs(workspace.root.parent/'jobs'/video_id,audio_sha,placement_only=True)
        if not current or record['binding']!=current[1] or not re.fullmatch('[0-9a-f]{64}',record['folder']):
            return {}
        sheet=_sheet_lines(current[0].decode('utf-8-sig'))
        if sheet!=_sheet_lines(original['text']) or list(sources)!=[row['sourceLine'] for row in sheet]:
            return {}
        folder=validate_generated_path(root,root/record['folder'])
        _timing(folder,current[1],record['timingSha256'])
        layered=_checked_timing(folder,current[1],'voice-timing.private.json',record['timingSha256'])
        binding=layered['voiceEvidence']
        path=folder/'backing-activity.private.json'
        if sha256_file(path)!=record['backingActivitySha256'] or binding.get('backingActivitySha256')!=record['backingActivitySha256']:
            return {}
        profile=_read(path,folder)
        if (profile['recordingSha256']!=audio_sha or profile['durationMs']!=duration or
                profile['singleVoiceSha256']!=binding['singleVoiceSha256'] or layered['durationMs']!=duration):
            return {}
        _single_timing(folder,current[1],binding['singleVoiceSha256'])
        single=_checked_timing(folder,current[1],'word-timing.private.json',binding['singleVoiceSha256'])
        pairs=[p for p in folder.glob('*/pair.private.json') if sha256_file(p)==profile['pairSha256']]
        if len(pairs)!=1:
            return {}
        part=validate_generated_path(folder,pairs[0].parent)
        if any(
                sha256_file(part/(role+'-words.private.json'))!=profile['readings'][role]
                for role in ('lead','choir','backing','mix')):
            return {}
        pair=_read(part/'pair.private.json',part)
        if pair['binding']['originalSha256']!=audio_sha or any(
                sha256_file(part/(role+'.wav'))!=pair[role] for role in ('lead','choir')):
            return {}
        words=[w for line in single['lines'] for w in line['words']]
        index={w['wordId']:i for i,w in enumerate(words)}
        measured={w['wordId']:w for line in layered['lines'] for w in line['words']}
        result={}
        for row in profile['handoffs']:
            if not row['timed']:
                continue
            left,right=measured[row['outgoing']],measured[row['opening']]
            i=index[right['wordId']]
            if not i or index[left['wordId']]!=i-1 or {left.get('voice'),right.get('voice')}!={'lead','choir'}:
                continue
            a,b=[left['startMs'],left['endMs']],[right['startMs'],right['endMs']]
            if (right['voice']!=row['openingPart'] or not a[0]<=b[0]<min(a[1],b[1])
                    or 'boundaryReview' in left or 'boundaryReview' in right
                    or not row['outgoingMeasurements'] or b not in row['openingMeasurements']):
                continue
            # Use both independently measured parts; the serial mix's outgoing end may differ.
            result[i]=[(tuple(p),tuple(b)) for p in row['outgoingMeasurements']
                       if p[0]<=b[0]<min(p[1],b[1])]
        return result
    except (KeyError,ValueError,TypeError,OSError,IndexError):
        return {}


def _timing(folder, binding, digest=None):
    data=_checked_timing(folder,binding,'voice-timing.private.json',digest)
    lanes={'lead':[],'choir':[]}
    for line in data['lines']:
        for word in line['words']:
            role=word.get('voice',line.get('voice'))
            if role is not None:
                lanes[role].append(word)
    ids=set()
    for words in lanes.values():
        if not words:
            raise ValueError('Missing vocal part')
        end=0
        for word in words:
            if (word['wordId'] in ids or not end<=word['startMs']<word['endMs']<=data['durationMs']
                    or word['timing']!='estimated'):
                raise ValueError('Invalid vocal-part ownership or timing')
            ids.add(word['wordId'])
            end=word['endMs']
    lead,choir=lanes.values()
    if not any(a['startMs']<b['endMs'] and b['startMs']<a['endMs'] for a in lead for b in choir):
        raise ValueError('Two-voice output contains no simultaneous words')
    return {'leadWords':len(lead),'choirWords':len(choir),
            'unresolvedIntervals':sum('boundaryReview' in w for w in lead+choir),
            'method':'separated-vocals' if ('vocalParts' in data['voiceEvidence'] or
                'backingActivitySha256' in data['voiceEvidence']) else 'stereo-position'}


def _repeated_occurrences(single, layered):
    """Keep every supplied occurrence. Expose only copies of known lead lines."""
    original = {line['occurrenceId']: line['text'] for line in single}
    selected = {line['occurrenceId']: line for line in layered}
    if (len(original) != len(single) or len(selected) != len(layered)
            or any(key not in selected or selected[key]['text'] != text for key, text in original.items())):
        return []
    lead = {line['text'] for key, line in selected.items() if key in original and line.get('voice') == 'lead'}
    additions = []
    for index, line in enumerate(layered):
        if line['occurrenceId'] in original:
            continue
        words = line['words']
        if (line.get('voice') != 'lead' or line['text'] not in lead or not words
                or any('boundaryReview' in word for word in words)):
            return []
        additions.append({'performedLine': index+1, 'occurrenceId': line['occurrenceId'],
            'wordCount': len(words), 'startMs': words[0]['startMs'], 'endMs': words[-1]['endMs']})
    return additions


def _projection(root, record, display):
    status=record['status']
    result={'status':status,'publicationAuthority':False}
    if status in ('unresolved','unavailable'):
        return {**result,'reason':record['reason'],'overlapAttempted':record.get('overlapAttempted',status=='unresolved')}
    if status not in ('single','layered') or not re.fullmatch('[0-9a-f]{64}',record['folder']):
        raise ValueError('Invalid private vocal review')
    folder=validate_generated_path(root,root/record['folder'])
    timing=folder/('voice-timing.private.json' if status=='layered' else 'word-timing.private.json')
    movie=validate_generated_path(folder,folder/'preview.mp4')
    digest=record['timingSha256']
    if not isinstance(digest,str):
        raise ValueError('Missing timing identity')
    details=_timing(folder,record['binding'],digest) if status=='layered' else _single_timing(folder,record['binding'],digest)[0]
    if status == 'layered' and details['method'] == 'separated-vocals':
        layered = _checked_timing(folder,record['binding'],timing.name,digest)
        evidence = layered['voiceEvidence']
        # A generic vocal-part handoff has no counted repeat proposal.
        if evidence.get('vocalParts') and evidence.get('locations'):
            single_digest = evidence['singleVoiceSha256']
            _single_timing(folder,record['binding'],single_digest)
            single = _checked_timing(folder,record['binding'],'word-timing.private.json',single_digest)
            repeats = _repeated_occurrences(single['lines'],layered['lines'])
            if repeats:
                details['repeatRecovery'] = {'status': 'estimated', 'publicationAuthority': False,
                    'performedLineCount': len(layered['lines']), 'addedOccurrences': repeats,
                    'audioSha256': record['binding']['audioSha256'],
                    'wordTiming': display(timing), 'wordTimingSha256': digest}
    if 'backingActivitySha256' in record:
        file=folder/'backing-activity.private.json'
        report=_read(file,folder)
        if (sha256_file(file)!=record['backingActivitySha256'] or report['recordingSha256']!=record['binding']['audioSha256']
                or report['singleVoiceSha256']!=sha256_file(folder/'word-timing.private.json')):
            raise ValueError('Backing assessment changed or belongs to a different preview')
        details['backingVocals']={'status':report['status'],'evidence':display(file)}
    if record.get('overlapReason'):
        details['overlapReason']=record['overlapReason']
    result.update(details,wordTiming=display(timing))
    render = folder/'preview-render.private.json'
    if render.is_file() and movie.is_file():
        try:
            from .song_preview_result import render_binding
            saved = _read(render,folder)
            if (saved['binding']==render_binding(digest,status=='layered')
                    and sha256_file(movie)==saved['videoSha256']):
                result['preview']=display(movie)
        except (OSError, ValueError, KeyError, TypeError):
            pass  # Timing remains available if the optional movie needs another render.
    return result


def job_readback(job_dir,evidence,runtime,video_id,display):
    root=evidence/'vocal-overlap'
    receipt=root/'review.private.json'
    if not receipt.is_file():
        return None
    overlap=False
    try:
        record=_read(receipt,root)
        overlap=record['status'] in ('layered','unresolved') or bool(record.get('overlapReason') or record.get('overlapAttempted'))
        checkpoint=_read(runtime/'work/checkpoints'/f'{video_id}.json',runtime)
        current=_inputs(job_dir,checkpoint['originalAudio']['sha256'])
        if current is None or current[1]!=record['binding']:
            return {'status':'inputs-changed','publicationAuthority':False,'overlapAttempted':overlap}
        result=_projection(root,record,display)
        from stemwise_engine.spoken_targets import PLAN_FILE
        if (job_dir/PLAN_FILE).is_file():
            result['nativeUse']='stanza-placement-only'
        return result
    except (KeyError,TypeError,ValueError,OSError):
        return {'status':'unavailable','reason':'private-result-unreadable','publicationAuthority':False,'overlapAttempted':overlap}


def prepare(job_dir,evidence,audio,audio_hash,config,*,after_refusal=False):
    """Called only by active recording review/alignment under the scoped workflow lock."""
    if not any((job_dir/'drafts'/name).is_file() for name in ('original.txt','original.lrc')):
        return False
    current=_inputs(job_dir,audio_hash)
    if current is None:
        return False
    sheet_bytes,binding=current
    lines=_sheet_lines(sheet_bytes.decode('utf-8-sig'))
    overlap=next(find_passages({'lines':lines,'language':binding['language']}),None) is not None
    if not (after_refusal or overlap or binding['language']=='mul' or next(stanza_groups(lines),None) is not None):
        return False
    project=Path(config['projectDir'])
    key=hashlib.sha256(json.dumps([binding,_recipe(project)],sort_keys=True).encode()).hexdigest()
    root=validate_generated_path(evidence,evidence/'vocal-overlap')
    receipt=root/'review.private.json'
    folder=validate_generated_path(root,root/key)
    from stemwise_engine.worker_protocol import require_worker_success, WorkerProtocolError
    from stemwise_engine.private_diagnostics import retain_error
    record={'binding':binding,'folder':key}
    if receipt.is_file():
        try:
            old=_read(receipt,root)
            if old.get('folder')==key and old.get('binding')==binding and old.get('status') in ('single','layered'):
                _projection(root,old,str)
                return True
        except (OSError,ValueError,TypeError,KeyError):
            pass  # Rebuild the private projection from the unchanged numerical inputs.
    sheet=folder/'performed.txt'
    atomic_write_bytes(sheet,sheet_bytes,root=root)
    command=[str(environment_python('main',project)),'-B','-m','stemwise_engine.lyric_preview.job',
             str(audio),str(sheet),binding['language'],str(folder),audio_hash]
    # An existing exact full-mix reading can be shared by recording review and playback.
    cached=evidence/('qwen-structure.private.json' if binding['language']=='mul' else f"qwen-structure-{binding['language']}.private.json")
    if cached.is_file():
        command.append(str(cached))
    diagnostic=folder/'preview.failure.private.txt'
    try:
        opts={'capture_output':True,'text':True,'timeout':None}  # Model stages own their watchdogs.
        with command_environment(command,opts,builder=project) as options:
            worker=run_process_tree(command,**options)
        require_worker_success(worker,code='private-vocal-preview-failed',diagnostic_path=diagnostic)
        outcome=json.loads(worker.stdout)
        if (isinstance(outcome,dict) and outcome.get('status') in ('single','layered')
                and set(outcome)<={'status','overlapReason','backingActivitySha256'}
                and isinstance(outcome.get('overlapReason',''),str)):
            if outcome['status']=='single':
                _single_timing(folder,binding)
                timing=folder/'word-timing.private.json'
            else:
                _timing(folder,binding)
                timing=folder/'voice-timing.private.json'
            record.update(**outcome,timingSha256=sha256_file(timing))
            if 'backingActivitySha256' in outcome:
                _projection(root,record,str)
        else:
            raise ValueError('Unexpected private vocal worker response')
    except (WorkerProtocolError,OSError,ValueError,KeyError,TypeError,subprocess.TimeoutExpired) as error:
        if isinstance(error,InterruptedError):
            raise
        from stemwise_engine.provider_operations import raise_if_operation_stopped
        raise_if_operation_stopped(error)
        if not isinstance(error,WorkerProtocolError):
            retain_error(diagnostic,error,context={'stage':'private-vocal-preview'})
        record.update(status='unavailable',reason='private-vocal-preview-failed',overlapAttempted=overlap)
    if _inputs(job_dir,audio_hash)!=current or sha256_file(audio)!=audio_hash:
        raise ValueError('Recording or draft changed during vocal preview')
    atomic_write_json(receipt,record,root=root)
    return True


def main():
    from contextlib import redirect_stdout
    from .song_preview import main as preview
    audio,sheet,language,folder,digest,*cache=sys.argv[1:]
    if sha256_file(Path(audio))!=digest:
        raise ValueError('Vocal preview recording changed')
    saved=Path(folder)/'mix-recognition.private.json'
    if cache and not saved.exists():
        from stemwise_engine import lyrics_align_pc as pc, qwen3_asr_transcribe as qwen
        candidate=Path(cache[0])
        try:
            native=_read(candidate,candidate.parent)['workerOutput']
            qwen._validated_output(native,{'audio':{'sha256':digest,'byteCount':Path(audio).stat().st_size},
                'language':None if language=='mul' else language,'model':{'revision':pc.QWEN3_ASR_REVISION,
                'snapshotSha256':pc.QWEN3_ASR_SNAPSHOT_SHA256}})
        except (OSError,ValueError,TypeError,KeyError):
            pass  # The preview obtains its own complete, recording-bound reading.
        else:
            atomic_write_json(saved,native,root=Path(folder))
    with redirect_stdout(sys.stderr):
        outcome=preview(['--audio',audio,'--lyrics',sheet,'--language',language,'--output',folder])
    print(json.dumps(outcome))
    return 0


if __name__=='__main__':
    from stemwise_engine.worker_protocol import run_worker_main
    raise SystemExit(run_worker_main(main))

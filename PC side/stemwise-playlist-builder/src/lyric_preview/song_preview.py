"""Build a private word-timed listening preview from an admitted job or a supplied lyric sheet."""
import argparse
import hashlib
import importlib.util
from importlib.metadata import version
import json
import math
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

from .search import PROJECT, contexts, read, supplied_contexts
from stemwise_engine.common import atomic_write_json, sha256_file, heavy_worker_stage, load_config
from stemwise_engine import source_recording_content
from stemwise_engine import endpoint_vowel_release
from stemwise_engine.lyrics_alignment import AlignmentWorkspace, build_lexical_occurrence_mapping, lexical_mapping_identity
from stemwise_engine.lyrics_tokenization import display_words
from stemwise_engine.local_recovery_models import _MODELS
from stemwise_engine.runtime_environment import environment_lease
from stemwise_engine.provenance import semantic_source_identity

def load_song(job, audio, vocal, backing, opening):
    state = read(job/'state.json')
    workspace = AlignmentWorkspace(job.parents[2], workspace_root=job.parents[3])
    original = next(t for t in read(workspace.current_backup_path(job.name))['tracks'] if t['kind']=='original')
    sources = state['contentAdmission']['sourcePhysicalIndices']
    master = sha256_file(audio)
    admission = state['contentAdmission']
    if (master!=admission['audioSha256'] or sha256_file(workspace.current_backup_path(job.name))!=admission['canonicalPlainSha256']):
        raise ValueError('Recording or canonical lyric sheet changed')
    path = job/admission['evidence']['path']
    if path.resolve().parent != job.resolve() or sha256_file(path) != admission['evidence']['sha256']:
        raise ValueError('Retained content decision changed')
    old=read(path)
    inputs=dict(job_dir=workspace.root.parent/'jobs'/job.name,evidence_root=job,video_id=job.name,
        original=original,indices=sources,audio_sha=master,duration_ms=state['durationMs'])
    decision=source_recording_content._decision(**inputs,
        selected_source={k:old['source'][k] for k in ('sourceVideoId','path','sha256')},
        contrary_references=[o['recognition'] for o in old['contrary']])
    source_recording_content.validate(decision,**inputs)
    if {k:v for k,v in decision.items() if k!='policy'}!={k:v for k,v in old.items() if k!='policy'}:
        raise ValueError('Current source-and-recording check changed the evidence for these lyrics')
    if original['language'] != 'en' or state['videoId'] != job.name:
        raise ValueError('This observation path requires a recording-bound English original')
    mapping = build_lexical_occurrence_mapping(job.name, original, sources)
    identity = lexical_mapping_identity(mapping)
    guide_file = job/'wav2vec2-public-endpoints-v4-en.private.json'
    if not any(c['cacheId']==guide_file.name and c['sha256']==sha256_file(guide_file)
               for c in state['cacheBindings']['records']):
        raise ValueError('Unbound search guide')
    guide = read(guide_file)
    if guide['provenance']['audio']['sha256'] != master or guide['mappingIdentity'] != identity:
        raise ValueError('Search guide belongs to different inputs')
    origin = read(opening)
    if (origin['sourceAudioSha256'] != master or origin['mappingIdentity'] != identity
            or origin['canonicalPlainSha256'] != state['canonicalPlainSha256'] or origin['videoId'] != job.name
            or origin['vocalSha256'] != sha256_file(vocal) or origin['backingSha256'] != sha256_file(backing)):
        raise ValueError('Vocal-entry evidence or stem pair belongs to different inputs')
    physical = original['text'].splitlines()
    rows = state['endpointEvidence']['occurrenceCandidates']
    words, base, locks, lines = [], [], set(), []
    for owner, row, native in zip(mapping['performedOccurrences'], rows, guide['lines'], strict=True):
        source, occurrence = owner['sourcePhysicalIndex'], owner['occurrenceId']
        if not (source == row['sourcePhysicalIndex'] == native['sourcePhysicalIndex']
                and occurrence == native['lineId'] == row['occurrenceId']):
            raise ValueError('Performed occurrence differs')
        locked = row['publishDecision']['result'] == 'words'
        chosen = row['familyCandidates'][row['selectedPublisherFamily']]['words'] if locked else native['words']
        start = len(words)
        for token, word, (left, right, display) in zip(owner['words'], chosen, display_words(physical[source]), strict=True):
            word_id = token['occurrenceWordId']
            if word.get('occurrenceWordId',word.get('wordId')) != word_id:
                raise ValueError('Performed word differs')
            if locked:
                locks.add(len(words))
            words.append({'wordId':word_id, 'text':display, 'startUtf16':left, 'endUtf16':right})
            base.append([word['startMs'],word['endMs']])
        lines.append({'occurrenceId':occurrence,'text':physical[source], 'first':start,'stop':len(words)})
    entry = origin['openingWordRecovery']
    if (entry['occurrenceId'] != lines[0]['occurrenceId'] or entry['wordId'] != words[0]['wordId']
            or entry['endMs'] is not None or 0 in locks or entry['startMs'] >= base[1][0]):
        raise ValueError('Wrong opening word or existing lock')
    # Search placement can use the remeasured start even if the old 20 ms
    # token has no usable release. The decoder fixes only the onset.
    if any(a[1]>b[0] for a,b in zip(base, base[1:])):
        raise ValueError('Incumbent intervals cross a neighboring word')
    return dict(words=words, lines=lines, base=base, locks=sorted(locks), opening=entry['startMs'],
        duration=state['durationMs'], stream=guide['provenance']['audio']['stream'], identity=identity,
        master=master, vocal=origin['vocalSha256'], backing=origin['backingSha256'],
        stateSha256=sha256_file(job/'state.json'), canonicalPlainSha256=state['canonicalPlainSha256'],
        contentPolicy=decision['policy'], originalContentDecisionSha256=admission['evidence']['sha256'],
        vocalWordEnds=endpoint_vowel_release.vocal_word_ends(rows)
            if endpoint_vowel_release.english_track(original) and not admission.get('spokenTargets') else {})


def pcm(path):
    import torch
    import torchaudio
    from stemwise_engine.acoustic_inputs import mono_samples, require_waveform_allocation
    require_waveform_allocation(path,16000,source_arrays=4)
    data,rate=sf.read(path,dtype='float32',always_2d=True)
    if not np.isfinite(data).all():
        raise ValueError('Nonfinite recording')
    mono = mono_samples(data)[0] if np.any(data) else data[:,0]
    return torchaudio.functional.resample(torch.from_numpy(mono),rate,16000).numpy()


def collect_views(song, files, folder, *, windows=None):
    import torch
    from stemwise_engine.ctc_worker import execution_deadline, retire_model
    label, revision, hashes = _MODELS['time']
    model = PROJECT.parent/'model-cache'/label/revision
    sample_count = math.ceil(song['stream']['frameCount']*16000/song['stream']['sampleRate'])
    if sample_count//16 != song['duration'] and abs(sample_count/16-song['duration'])>1:
        raise ValueError('Recording duration differs')
    if windows is None:
        plans = (list(supplied_contexts(song['base'],sample_count//16)) if 'suppliedSheetSha256' in song else
                 [p for p in contexts(song['base'],9,3,sample_count//16) if p[3]-p[2] <= 30000])
    else:
        plans = windows
        if any(not (0 <= lo < hi <= len(song['words']) and 0 <= a < b <= song['duration'] and b-a <= 30000)
               for lo,hi,a,b in plans):
            raise ValueError('Invalid acoustic passage window')
    signature = dict(model=revision, modelFiles=hashes, plans=plans, wordIds=[w['wordId'] for w in song['words']],
        words=[w['text'] for w in song['words']], rate=16000, frames=sample_count,
        master=song['master'], vocal=song['vocal'], backing=song['backing'], version=1)
    signature['collectorSha256']=semantic_source_identity(Path(__file__),('pcm','collect_views'),portable=True)
    language=song.get('language','en')
    signature['language']=language
    index=folder/'observations.private.json'
    def packet_paths(plan):
        key=hashlib.sha256(json.dumps(plan,sort_keys=True).encode()).hexdigest()
        return key,{view:folder/(view+'-'+key+'.npz') for view in files}
    if index.exists():
        saved=read(index)
        plan=saved['plan']
        expected=dict(signature)
        if {k:v for k,v in plan.items() if k!='runtime'}!=expected:
            raise ValueError('This output already belongs to another measurement. Use a separate folder.')
        _,paths=packet_paths(plan)
        if not all(sha256_file(p)==saved['sha256'][v] for v,p in paths.items()):
            raise ValueError('An archived observation changed')
        return plan,paths
    if any(sha256_file(model/name)!=digest for name,digest in hashes.items()):
        raise ValueError('Installed acoustic model changed')
    spec=importlib.util.spec_from_file_location('pinned_scicom_song_preview',model/'align.py')
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    torch.set_num_threads(4)
    config = load_config(PROJECT/'config.json')
    with (environment_lease('main',builder=PROJECT), heavy_worker_stage(config),
          execution_deadline(int(config['analysisTimeoutMinutes'])*60)):
        dev=torch.cuda.get_device_properties(0)
        signature['runtime']={'device':dev.name,'capability':[dev.major,dev.minor],'memoryBytes':dev.total_memory,
            'cuda':torch.version.cuda,'dtype':'bfloat16','packages':{p:version(p) for p in
                ('torch','torchaudio','transformers','soundfile','numpy')}}
        key,paths=packet_paths(signature)
        timer=module.ForcedAligner(str(model),device='cuda')
        native=timer._times
        capture=[]
        def observe(logits):
            times=native(logits)
            capture.append((times.copy(),logits.float().softmax(-1).cpu().numpy()))
            return times
        timer._times=observe
        try:
            for view in files:
                if paths[view].exists():
                    with np.load(paths[view],allow_pickle=False) as partial:
                        if str(partial['binding'])!=key:
                            raise ValueError('Interrupted observation has a different binding')
                    continue
                samples=pcm(files[view])
                if abs(len(samples)-sample_count)>1:
                    raise ValueError('Vocal and original recording clocks differ')
                arrays={'binding':np.array(key)}
                for k in range(0,len(plans),3):
                    group=plans[k:k+3]
                    texts=[signature['words'][lo:hi] for lo,hi,_,_ in group]
                    chunks=[samples[a*16:b*16] for _,_,a,b in group]
                    capture.clear()
                    out=timer.align_batch(chunks,texts,[language]*len(group))
                    if any(isinstance(item,Exception) for item in out):
                        raise next(item for item in out if isinstance(item,Exception))
                    (times,probs),=capture
                    pos=0
                    for slot,((lo,hi,a,b),target,observed) in enumerate(zip(group,texts,out,strict=True),k):
                        if [item['word'] for item in observed]!=target:
                            raise ValueError('Numerical worker changed target words')
                        n=hi-lo
                        bins=1+(b-a)//20
                        arrays[f'{view}p{slot}']=probs[pos:pos+2*n,:bins].reshape(n,2,bins).astype('float16')
                        arrays[f'{view}t{slot}']=times[pos:pos+2*n].reshape(n,2)
                        pos+=2*n
                    if pos!=len(times):
                        raise ValueError('Unexpected boundary observations')
                tmp=paths[view].with_suffix('.part')
                with tmp.open('wb') as f:
                    np.savez_compressed(f,**arrays)
                tmp.replace(paths[view])
                print('Saved',view,len(plans),'overlapping windows',flush=True)
        finally:
            # Reveal the class method and release the bound receiver before CUDA cleanup.
            del timer._times
            native = None
            del timer
            retire_model({'cuda_model':True})
    atomic_write_json(index,{'plan':signature,'sha256':{view:sha256_file(path) for view,path in paths.items()}})
    return signature,paths


class OverlapUnresolved(ValueError):
    """The separate vocal parts could not support a unique concurrent reading."""


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    source=parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--job',type=Path)
    source.add_argument('--lyrics',type=Path,help='UTF-8 untimed performed lyric sheet')
    parser.add_argument('--language',choices=('ar','en','tr','mul'),help='Language of the supplied sheet, or mul for mixed languages')
    for name in ('audio','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    for name in ('vocal','backing','opening'):
        parser.add_argument('--'+name,type=Path)
    parser.add_argument('--video',action='store_true',help='Render a self-contained private listening copy')
    parser.add_argument('--native-only',action='store_true',help='Render the whole-recording forced estimate without optional refinements')
    parser.add_argument('--observations',type=Path,help='Reuse previously measured timer observations in another folder')
    parser.add_argument('--compare-lyrics',action='append',nargs=2,metavar=('SOURCE','FILE'),default=[],
                        help='Compare the supplied words with a separately acquired UTF-8 source body')
    parser.add_argument('--recording-observation',type=Path,
                        help='Compare wording against the complete unprompted Qwen observation on this Original')
    parser.add_argument('--layered',action='store_true',help='Look for concurrent vocal parts on the supplied-sheet preview')
    args=parser.parse_args(argv)
    if args.observations and not (args.observations/'observations.private.json').is_file():
        raise ValueError('Missing saved timing observations')
    args.output.mkdir(parents=True,exist_ok=True)
    previous=args.output/'word-timing.private.json'
    if args.lyrics:
        if not args.language or any((args.opening,args.vocal,args.backing)):
            parser.error('--lyrics requires --language and derives its own vocal and backing pair')
        from .supplied_song import native_song, stem_pair, vocal_repeat_placement, compare_sheet, recording_words, review_messages
        from .song_preview_result import write_native_preview, render_video
        if previous.exists():
            prior=read(previous)
            if (prior.get('suppliedSheetSha256'),prior['recordingSha256'],prior.get('language'))!=(
                    sha256_file(args.lyrics),sha256_file(args.audio),args.language):
                raise ValueError('Preserve the previous preview and use a new output folder')
        if args.native_only and (args.observations or args.layered):
            parser.error('--native-only does not use optional acoustic observations')
        if args.native_only and previous.exists() and prior.get('method')!='whole-recording forced estimate':
            raise ValueError('Preserve the refined preview. Choose a separate folder for a native-only result.')
        if args.observations and previous.exists() and sha256_file(args.output/'supplied-guide.private.json')!=prior['guideSha256']:
            raise ValueError('Saved supplied guide differs from the retained preview')
        song=native_song(args.audio.resolve(),args.lyrics,args.language,args.output,replay_guide=bool(args.observations))
        cached_recording=args.output/'mix-recognition.private.json'
        if not args.native_only and not args.recording_observation and cached_recording.is_file():
            args.recording_observation=cached_recording
        recording=(args.recording_observation,recording_words(args.recording_observation,song,args.audio)) if args.recording_observation else None
        song['sheetComparison']=compare_sheet(args.lyrics,args.language,args.compare_lyrics,recording=recording)
        for message in review_messages(song['sheetComparison']):
            print(message,file=sys.stderr)
        if args.native_only or not previous.exists():
            write_native_preview(song,args.output)
        if args.native_only:
            if args.video:
                render_video(args.output,args.audio)
            return
        args.vocal,args.backing,song['vocal'],song['backing']=stem_pair(args.audio.resolve(),song['master'],args.output)
        vocal_repeat_placement(song,args.vocal,args.backing,args.lyrics,args.output,replay_guide=bool(args.observations))
    else:
        if args.compare_lyrics or args.recording_observation or args.layered or args.native_only or args.language or not all((args.opening,args.vocal,args.backing)):
            parser.error('--job requires --vocal, --backing and --opening')
        song=load_song(args.job,args.audio,args.vocal,args.backing,args.opening)
        if previous.exists():
            saved=read(previous)
            if (saved['recordingSha256'],saved.get('stateSha256'))!=(song['master'],song['stateSha256']):
                raise ValueError('Preserve the previous preview and select a new output folder')
    signature,paths=collect_views(song,{'mix':args.audio,'lead':args.vocal,'backing':args.backing},args.observations or args.output)
    from .song_preview_result import write_preview, render_video
    write_preview(song,signature,paths,args.output,(args.vocal,args.backing,song['vocal']))
    if args.lyrics:
        from .instrumental_gap import recover, recover_final
        recover(song,{'mix':args.audio,'lead':args.vocal,'backing':args.backing},args.lyrics,args.output,
                replay_guide=bool(args.observations))
        from .repeated_stanzas import recover_stanzas
        recover_stanzas(song,{'mix':args.audio,'lead':args.vocal,'backing':args.backing},args.output)
        recover_final(song,{'mix':args.audio,'lead':args.vocal,'backing':args.backing},args.output,signature,paths,recording)
    from .overlapping_refrain import find_passages, layer_preview
    outcome={'status':'single'}
    if args.layered or (args.lyrics and next(find_passages(song),None)):
        if not args.recording_observation:
            from .supplied_song import mix_observation
            args.recording_observation=mix_observation(song,args.audio,args.output)
            song['sheetComparison']=compare_sheet(args.lyrics,args.language,args.compare_lyrics,
                recording=(args.recording_observation,recording_words(args.recording_observation,song,args.audio)))
            for message in review_messages(song['sheetComparison']):
                print(message,file=sys.stderr)
        reason=layer_preview(song,args.output,args.audio,args.vocal,args.backing,args.recording_observation)
        if reason and args.layered:
            raise OverlapUnresolved(reason)
        if not reason:
            if args.video:
                render_video(args.output,args.audio,layered=True)
            return {'status':'layered'}
        outcome['overlapReason']=reason
        print('Concurrent parts unresolved: '+reason,file=sys.stderr)
    if args.lyrics:
        from .backing_words import inspect_backing
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.private_diagnostics import retain_error
        from stemwise_engine.provider_operations import raise_if_operation_stopped
        try:
            report,layered=inspect_backing(song,args.output,args.audio,args.vocal,args.backing,signature,paths)
        except (WorkerRetirementError,InterruptedError):
            raise
        except (OSError,RuntimeError,ValueError) as error:
            raise_if_operation_stopped(error)
            retain_error(args.output/'backing-activity.failure.private.txt',error,context={'stage':'backing-activity'})
            outcome['overlapReason']='Backing-vocal assessment unavailable'
        else:
            if report:
                outcome['backingActivitySha256']=sha256_file(report)
            if layered:
                if args.video:
                    render_video(args.output,args.audio,layered=True)
                return {'status':'layered','backingActivitySha256':outcome['backingActivitySha256']}
    if args.video:
        render_video(args.output,args.audio)
    return outcome


if __name__=='__main__':
    main()

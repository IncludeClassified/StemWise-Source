"""Prepare a private full-song preview directly from an Original and an untimed sheet."""
import hashlib
from difflib import SequenceMatcher
import os
from pathlib import Path
import sys
import tempfile
from collections import defaultdict

import numpy as np
import soundfile as sf

from .search import PROJECT, read
from .layout import stanza_groups
from stemwise_engine import lyrics_align_pc as pc, mms_forced_alignment as mms, wav2vec2_forced_alignment as wav2vec
from stemwise_engine.common import atomic_write_json, heavy_worker_stage, load_config, run_process_tree, sha256_file
from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping, lexical_mapping_identity, separate_vocals_demucs
from stemwise_engine.lyrics_normalize import performed_line_indices, physical_lines
from stemwise_engine.lyric_comparison import units
from stemwise_engine.lyrics_tokenization import display_words
from stemwise_engine.runtime_environment import environment_lease
from stemwise_engine.worker_protocol import require_worker_success


def wording_differences(written,other):
    changes=[{'sheetRange':[a,b],'sourceRange':[c,d],'sheet':written[a:b],'source':other[c:d]}
             for op,a,b,c,d in SequenceMatcher(None,written,other,autojunk=False).get_opcodes()
             if op!='equal']
    pairs=defaultdict(list)
    for j in range(len(other)-1):
        pairs[other[j],other[j+1]].append(j)
    swaps=[]
    for i,(a,b) in enumerate(zip(written,written[1:])):
        hits=pairs[b,a]
        if a==b or len(hits)!=1:
            continue
        j=hits[0]
        left=(i==j==0 or any(i>=k and j>=k and written[i-k]==other[j-k] for k in (1,2)))
        right=(i+2==len(written) and j+2==len(other) or any(i+k<len(written) and j+k<len(other)
               and written[i+k]==other[j+k] for k in (2,3)))
        if left and right:
            swaps.append({'sheetIndex':i,'sourceIndex':j,'sheet':[a,b],'source':[b,a]})
    return {'comparisonUnitChanges':changes,'adjacentWordOrderConflicts':swaps}


def recognition_language(song):
    return None if song['language']=='mul' else song['language']


def recording_words(path,song,audio):
    from stemwise_engine import qwen3_asr_transcribe as qwen
    observation=read(path)
    qwen._validated_output(observation,{'audio':{'sha256':song['master'],'byteCount':audio.stat().st_size},
        'language':recognition_language(song),'model':{'revision':pc.QWEN3_ASR_REVISION,
        'snapshotSha256':pc.QWEN3_ASR_SNAPSHOT_SHA256}})
    return observation['result']['transcription']


def mix_observation(song,audio,output):
    """Get the unprompted mix reading for an automatically detected two-part layout."""
    result=output/'mix-recognition.private.json'
    if result.is_file():
        recording_words(result,song,audio)
        return result
    config=load_config(PROJECT/'config.json')
    model=pc._qwen3_asr_snapshot(PROJECT.parent/'model-cache/lyrics-alignment')
    request={'formatVersion':pc.QWEN3_ASR_FORMAT_VERSION,'language':recognition_language(song),
             'audio':{'path':str(audio.resolve()),'sha256':song['master'],'byteCount':audio.stat().st_size},
             'model':{'path':str(model),'revision':pc.QWEN3_ASR_REVISION,'snapshotSha256':pc.QWEN3_ASR_SNAPSHOT_SHA256}}
    with tempfile.TemporaryDirectory(dir=output) as tmp, environment_lease('main',builder=PROJECT),heavy_worker_stage(config):
        src,dest=Path(tmp)/'request.json',Path(tmp)/'observation.json'
        atomic_write_json(src,request)
        task=run_process_tree([sys.executable,'-m','stemwise_engine.qwen3_asr_transcribe','--device','cuda',
                               '--request',str(src),'--output',str(dest)],capture_output=True,
                              timeout=int(config['analysisTimeoutMinutes'])*60)
        require_worker_success(task,code='mix-observation',diagnostic_path=output/'mix-recognition.failure.txt')
        recording_words(dest,song,audio)
        dest.replace(result)
    return result


def compare_sheet(sheet, language, references, *, recording=None):
    """Report disagreements against actual source bodies without editing either input."""
    scheduled=units(sheet.read_text(encoding='utf-8-sig'),language)
    heard=units(recording[1],language) if recording else ()
    reports=[]
    for label,filename in references:
        body=Path(filename).read_bytes()
        source=units(body.decode('utf-8-sig'),language)
        if not source:
            raise ValueError('Comparison source has no lyric words')
        report={'source':label,'bodySha256':hashlib.sha256(body).hexdigest(),**wording_differences(scheduled,source)}
        if recording:
            report['versusRecording']=wording_differences(source,heard)
        reports.append(report)
    result={'sheetSha256':sha256_file(sheet),'sources':reports,'wordingVerified':False}
    if recording:
        result['versusRecording']={'observationSha256':sha256_file(recording[0]),**wording_differences(scheduled,heard)}
    return result


def review_messages(report):
    """Expose conflicting local readings even when no recording transcript is available."""
    def differences(name,comparison):
        changes=comparison['comparisonUnitChanges']
        if not changes:
            return
        yield f'{name}: {len(changes)} differing passages. Full comparison is in sheetComparison.'
        for swap in comparison['adjacentWordOrderConflicts']:
            yield f"  Word order: {' '.join(swap['sheet'])} / {' '.join(swap['source'])}"
        for change in changes:
            left,right=change['sheet'],change['source']
            if 0<len(left)<=3 and 0<len(right)<=3:
                yield f"  Wording: {' '.join(left)} / {' '.join(right)}"
    for source in report['sources']:
        yield from differences(f"Supplied sheet vs {source['source']}",source)
        if 'versusRecording' in source:
            yield from differences(f"{source['source']} vs unprompted recording",source['versusRecording'])
    if 'versusRecording' in report:
        yield from differences('Supplied sheet vs unprompted recording',report['versusRecording'])
    else:
        yield 'Supplied wording has no unprompted recording comparison in this preview.'


def native_song(audio, sheet, language, output, *, replay_guide=False):
    config = load_config(PROJECT/'config.json')
    master = sha256_file(audio)
    raw = sheet.read_bytes()
    text = raw.decode('utf-8-sig')
    digest = hashlib.sha256(raw).hexdigest()
    original = {'id':'original','kind':'original','format':'plain','language':language,'text':text}
    physical = physical_lines(text)
    indices = list(performed_line_indices(text))
    if not indices:
        raise ValueError('The supplied sheet contains no performed words')
    # This is a local recording, with its own occurrence namespace and clock.
    key = master[:11]
    mapping = build_lexical_occurrence_mapping(key,original,indices)
    display = [display_words(physical[i]) for i in indices]
    info = sf.info(audio)
    stream = {'frameCount':info.frames,'sampleRate':info.samplerate,'channelCount':info.channels}
    duration = pc._worker_pcm_duration_ms({'stream':stream})
    request, line_ids, word_ids, identity = pc._forced_alignment_request(
        key,indices,mapping,display,duration_ms=duration)
    environment = os.environ.copy()
    if language == 'mul':
        cache = PROJECT.parent/'model-cache/torch'
        model_id = pc.models.require_pinned_file(cache/'hub/checkpoints/model.pt',pc.PINNED_MMS_SHA256,'MMS_FA checkpoint')
        environment['TORCH_HOME'] = str(cache)
        aligner, engine = mms, 'torchaudio-mms-fa'
        worker = PROJECT/'src/mms_forced_alignment.py'
        options = []
        producer = {worker.name:pc.ctc_worker.helper_source_identity(worker)}
    else:
        model, model_id = pc._wav2vec2_model_assets(config,language)
        aligner, engine = wav2vec, wav2vec.ENGINE
        worker = PROJECT/'src/wav2vec2_forced_alignment.py'
        options = ['--model',str(model),'--language',model_id['phonemizerLanguage'] or language]
        producer = pc._wav2vec2_helper_hashes(PROJECT)
    output.mkdir(parents=True,exist_ok=True)
    index = output/'supplied-guide.private.json'
    with environment_lease('main',builder=PROJECT), heavy_worker_stage(config):
        sig = dict(recording=master,sheet=digest,language=language,mapping=identity,model=model_id,
                   request=pc._alignment_request_sha256(request),producer=producer)
        if replay_guide and index.is_file():
            saved = read(index)
            runtime = saved['input']['runtime']
            if language == 'mul':
                pc._owner_prediction_runtime(runtime,PROJECT)
            if ((language != 'mul' and runtime['engine'] != engine) or runtime['resolvedDevice'] != 'cuda'
                    or runtime['cudaAvailable'] is not True or not runtime.get('cudaDeviceName')):
                raise ValueError('Stored guide has no qualified producer device')
            sig['runtime'] = runtime
        else:
            sig['runtime'] = aligner.runtime_info('cuda')
        if index.is_file():
            saved = read(index)
            if saved['input']!=sig:
                raise ValueError('The supplied guide belongs to different inputs. Use a new output folder.')
            guide = saved['result']
        else:
            with tempfile.TemporaryDirectory(dir=output) as temp:
                src, dest = Path(temp)/'request.json', Path(temp)/'guide.json'
                atomic_write_json(src,request)
                p = run_process_tree([sys.executable,'-B',str(worker),'--audio',str(audio),*options,
                    '--request',str(src),'--output',str(dest),'--device','cuda'], env=environment,
                    capture_output=True,timeout=int(config['analysisTimeoutMinutes'])*60)
                require_worker_success(p,code='supplied-sheet-guide-failed',diagnostic_path=output/'guide.failure.txt')
                guide = read(dest)
        pc._validate_numeric_mms_result(guide,expected_sources=indices,duration_ms=duration,
            expected_line_ids=line_ids,expected_word_ids=word_ids,expected_format_version=aligner.FORMAT_VERSION,
            expected_engine=engine,expected_segments=request['segments'])
        if not index.is_file():
            atomic_write_json(index,{'input':sig,'result':guide})
    words, lines, base = [], [], []
    for i,row,surfaces in zip(indices,guide['lines'],display,strict=True):
        first = len(words)
        for w,(a,b,surface) in zip(row['words'],surfaces,strict=True):
            words.append({'wordId':w['wordId'],'text':surface,'startUtf16':a,'endUtf16':b})
            base.append([w['startMs'],w['endMs']])
        lines.append({'occurrenceId':row['lineId'],'text':physical[i],'sourceLine':i,'first':first,'stop':len(words)})
    if any(a[1]>b[0] for a,b in zip(base,base[1:])):
        raise ValueError('Whole-recording guide crossed neighboring word intervals')
    return dict(words=words,lines=lines,base=base,locks=[],opening=None,duration=duration,stream=stream,
                identity=lexical_mapping_identity(mapping),master=master,language=language,vocalWordEnds={},
                suppliedSheetSha256=digest,guideSha256=sha256_file(index))


def stem_pair(audio, master, output):
    receipt = output/'stem-pair.private.json'
    if receipt.is_file():
        record = read(receipt)
        paths = [(output/record[key]).resolve() for key in ('vocal','backing')]
        if (record['recordingSha256']!=master or any(not p.is_relative_to(output.resolve()) for p in paths)
                or any(sha256_file(p)!=record[key+'Sha256'] for p,key in zip(paths,('vocal','backing')))):
            raise ValueError('Stem pair differs from the supplied recording or its saved measurement')
        return (*paths,record['vocalSha256'],record['backingSha256'])
    config = load_config(PROJECT/'config.json')
    os.environ['TORCH_HOME'] = str(PROJECT.parent/'model-cache/torch')
    with tempfile.TemporaryDirectory(dir=output) as temp:
        v = separate_vocals_demucs(audio,Path(temp),project_dir=PROJECT,timeout_seconds=int(config['analysisTimeoutMinutes'])*60)
        b = v.with_name('no_vocals.wav')
        vd,bd = sha256_file(v),sha256_file(b)
        # Content-addressed outputs protect existing pairs across interruptions.
        vocal,backing = output/f'vocals-{vd}.wav',output/f'backing-{bd}.wav'
        v.replace(vocal)
        b.replace(backing)
        atomic_write_json(receipt,{'recordingSha256':master,'vocal':vocal.name,'backing':backing.name,
                                   'vocalSha256':vd,'backingSha256':bd})
    return vocal,backing,vd,bd


def repeated_passages(song):
    """Find consecutive lines made entirely of the same display word."""
    previous = None
    for line in song['lines']:
        words = song['words'][line['first']:line['stop']]
        forms = {units(w['text'],song['language']) for w in words}
        form = next(iter(forms)) if len(forms)==1 else ()
        if previous and previous[0]==form and previous[3]+1==line['sourceLine']:
            previous = form,previous[1],line['stop'],line['sourceLine']
            continue
        if previous and previous[2]-previous[1]>1:
            yield previous[1:3]
        previous = (form,line['first'],line['stop'],line['sourceLine']) if form else None
    if previous and previous[2]-previous[1]>1:
        yield previous[1:3]


def _bound_vocal_guide(song, vocal, sheet, output, replay_guide):
    alternate = native_song(vocal,sheet,song['language'],output/'vocal-guide',replay_guide=replay_guide)
    if (alternate['master']!=song['vocal'] or alternate['suppliedSheetSha256']!=song['suppliedSheetSha256']
            or [(w['text'],w['startUtf16'],w['endUtf16']) for w in alternate['words']] !=
               [(w['text'],w['startUtf16'],w['endUtf16']) for w in song['words']]
            or [(l['sourceLine'],l['first'],l['stop']) for l in alternate['lines']] !=
               [(l['sourceLine'],l['first'],l['stop']) for l in song['lines']]):
        raise ValueError('Isolated-vocal guide has different audio or lyric owners')
    return alternate


def vocal_repeat_placement(song, vocal, backing, sheet, output, *, replay_guide=False):
    # A block contains all adjacent owners that compete for the same phrase.
    blocks = [[span] for span in repeated_passages(song)]
    groups = list(stanza_groups(song['lines']))
    if not blocks and not groups:
        return
    source = song['stream']
    def power(stream, ms):
        stream.seek(round(ms*stream.samplerate/1000))
        data=stream.read(max(1,stream.samplerate//10),dtype='float32',always_2d=True)
        return float(np.mean(data**2)) if data.size else 0.
    incumbent = song['base'].copy()
    decisions = []
    with sf.SoundFile(vocal) as voice, sf.SoundFile(backing) as music:
        if (voice.samplerate!=music.samplerate or len(voice)!=len(music) or
                abs(len(voice)/voice.samplerate-source['frameCount']/source['sampleRate'])>1/voice.samplerate):
            raise ValueError('Isolated-vocal guide clock differs from the recording')
        def vocal_fractions(times):
            values=[]
            for start,_ in times:
                v,b=power(voice,start),power(music,start)
                values.append(v/(v+b) if v+b else 0.)
            return values

        for starts,size in groups:
            spans=[(song['lines'][i]['first'],song['lines'][i+size-1]['stop']) for i in starts]
            shares=[vocal_fractions(incumbent[a:b]) for a,b in spans]
            if not (any(max(row)<=.5 for row in shares) and any(np.median(row)>.5 for row in shares)):
                continue
            run=[]
            for span in spans:
                if run and run[-1][1]!=span[0]:
                    blocks.append(run)
                    run=[]
                run.append(span)
            blocks.append(run)
        if not blocks:
            return
        alternate = _bound_vocal_guide(song,vocal,sheet,output,replay_guide)
        proposed = alternate['base']
        for block in blocks:
            lo,hi=block[0][0],block[-1][1]
            mix_scores=vocal_fractions(incumbent[lo:hi])
            voice_scores=vocal_fractions(proposed[lo:hi])
            if (sum(v>.5 for v in voice_scores)<=sum(v>.5 for v in mix_scores)
                    or np.median(voice_scores)<=np.median(mix_scores)
                    or any(max(voice_scores[a-lo:b-lo])<=.5 for a,b in block)):
                continue
            # Reconcile only immediately colliding neighbors. All coordinates
            # stay on the same recording; keep the Original's word owners.
            left,right=lo,hi
            while left and incumbent[left-1][1]>proposed[left][0]:
                left-=1
            while right<len(incumbent) and proposed[right-1][1]>incumbent[right][0]:
                right+=1
            if any(left<=i<right for i in song.get('locks',())):
                continue
            incumbent[left:right]=proposed[left:right]
            decisions.append(dict(firstWord=lo,stopWord=hi,replacedRange=[left,right],
                                  mixVocalFractions=mix_scores,vocalVocalFractions=voice_scores))
    if decisions:
        if any(a[1]>b[0] for a,b in zip(incumbent,incumbent[1:])):
            raise ValueError('Vocal placement crossed a neighboring word')
        song['base']=incumbent
        song['vocalRepeatPlacement']={'vocalGuideSha256':alternate['guideSha256'],'passages':decisions}

"""Decode raw boundary scores into private, word-timed listening cues."""
import hashlib
import html
import json
import os
import shutil
import tempfile
from pathlib import Path

import numpy as np

from .candidates import BAR, _mass, _observations, _ordered, _table
from .search import PROJECT
from stemwise_engine import endpoint_vowel_release
from stemwise_engine.common import atomic_write_bytes, atomic_write_json, load_config, run_process_tree, sha256_file


def choose(song, rows, points, *, preserve):
    locked=set(song['locks']) if preserve else set()
    tables=[]
    for i,old in enumerate(song['base']):
        starts,ends,scores=_table(old,rows['mix'][i]+rows['lead'][i],
            points['mix'][i] | points['lead'][i],song['duration'],i in locked)
        if preserve and i==0 and song['opening'] is not None:
            # The previously recovered onset is already owned. Score candidate
            # releases at their own positions, without moving that onset.
            start=song['opening']
            scores=scores.max(axis=0,keepdims=True)
            scores[:,ends<=start]=-np.inf
            starts=np.array([start])
        tables.append((starts,ends,scores))
    result=_ordered(tables,song['base'])
    if (len(result)!=len(song['words']) or any(not 0<=a<b<=song['duration'] for a,b in result)
            or any(a[1]>b[0] for a,b in zip(result,result[1:]))
            or any(result[i]!=song['base'][i] for i in locked)
            or (preserve and song['opening'] is not None and result[0][0]!=song['opening'])):
        raise ValueError('Invalid timing or changed accepted word')
    return result


def support(times, index, rows, points):
    mass={}
    near={}
    for view in ('mix','lead','backing'):
        observed=rows[view][index]
        mass[view]=[float(np.mean([_mass(cdf[k],np.asarray([t]),offset)[0] for offset,cdf in observed]))
                    if observed else 0. for k,t in enumerate(times)]
        near[view]=[any(abs(pair[k]-t)<=BAR for pair in points[view][index]) for k,t in enumerate(times)]
    distinct=[near['mix'][k] and near['lead'][k] and
              min(mass['mix'][k],mass['lead'][k])>mass['backing'][k] for k in (0,1)]
    return {'vocalContrast':distinct, 'massWithin100ms':mass,
            'vocalCropRangeMs':[[min(v),max(v)] if v else None for v in
                ([pair[k] for pair in points['lead'][index]] for k in (0,1))]}


def timestamp(ms):
    seconds,frac=divmod(ms,1000)
    minutes,sec=divmod(seconds,60)
    hour,minute=divmod(minutes,60)
    return f'{hour:02}:{minute:02}:{sec:02}.{frac:03}'


def recover_padded_edges(song, predicted, missing, rows, points):
    recovered = set()
    for i in missing:
        if not points['mix'][i] or not points['lead'][i]:
            continue
        starts,ends,scores = _table(predicted[i], rows['mix'][i]+rows['lead'][i],
            points['mix'][i] | points['lead'][i], song['duration'], False)
        if i:
            scores[starts < predicted[i-1][1],:] = -np.inf
        if i+1<len(predicted):
            scores[:,ends > predicted[i+1][0]] = -np.inf
        if not np.isfinite(scores).any():
            continue
        a,b = np.unravel_index(scores.argmax(), scores.shape)
        pair = [int(starts[a]),int(ends[b])]
        if all(support(pair,i,rows,points)['vocalContrast']):
            predicted[i] = pair
            recovered.add(i)
    return recovered


def write_preview(song,signature,paths,output,stem_pair):
    key=hashlib.sha256(json.dumps(signature,sort_keys=True).encode()).hexdigest()
    rows,points={},{}
    edge_rows,edge_points={},{}
    context={'base':song['base'],'plans':signature['plans'],'frames':signature['frames']}
    for view,path in paths.items():
        with np.load(path,allow_pickle=False) as packet:
            if str(packet['binding'])!=key:
                raise ValueError('Different recording or placement')
            rows[view],points[view]=_observations(packet,view,context)
            if 'suppliedSheetSha256' in song:
                edge_rows[view],edge_points[view]=_observations(packet,view,context,padded_edges=True)
    missing=[i for i in range(len(song['words'])) if i not in song['locks'] and not points['mix'][i] and not points['lead'][i]]
    if missing and 'suppliedSheetSha256' not in song:
        raise ValueError('A disputed word has no in-crop acoustic measurement')
    predicted=choose(song,rows,points,preserve=True)
    free=choose(song,rows,points,preserve=False)
    recovered=recover_padded_edges(song,predicted,missing,edge_rows,edge_points) if edge_rows else set()
    for i in recovered:
        for view in rows:
            rows[view][i],points[view][i]=edge_rows[view][i],edge_points[view][i]
    missing=set(missing)-recovered
    locks=set(song['locks'])
    words=[{**word,'startMs':a,'endMs':b,'timing':'retained' if i in locks else 'estimated'}
           for i,(word,(a,b)) in enumerate(zip(song['words'],predicted,strict=True))]
    candidates=endpoint_vowel_release.plan(words,song['vocalWordEnds'],song['duration'])
    releases,release_ref=endpoint_vowel_release.estimate(PROJECT,output,song['duration'],song['master'],stem_pair,
        candidates,int(load_config(PROJECT/'config.json')['analysisTimeoutMinutes'])*60)
    output_lines=[]
    for line in song['lines']:
        entries=[]
        for i in range(line['first'],line['stop']):
            a,b=predicted[i]
            release=releases.get(words[i]['wordId'])
            if release:
                b=release['estimatedEndMs']
            entries.append({**words[i], 'endMs':b,
                **({'heldVowelRelease':{k:v for k,v in release.items() if k!='traces'}} if release else {}),
                'startSource':'retained vocal entry' if i==0 and song['opening'] is not None else
                    'retained' if i in locks else 'whole-recording forced estimate' if i in missing else
                    'padded acoustic window' if i in recovered else 'acoustic prediction',
                **support((a,b),i,rows,points)})
        output_lines.append({'occurrenceId':line['occurrenceId'],'text':line['text'],'words':entries})
    estimates=[w for l in output_lines for w in l['words'] if w['timing']=='estimated']
    errors=[[abs(free[i][k]-song['base'][i][k]) for k in (0,1)] for i in sorted(locks)]
    summary={'lines':len(output_lines),'words':len(predicted),'retained':len(locks),'estimated':len(estimates),
        'bothDistinctFromBacking':sum(all(w['vocalContrast']) for w in estimates),
        'startDistinctFromBacking':sum(w['vocalContrast'][0] for w in estimates),
        'endDistinctFromBacking':sum(w['vocalContrast'][1] for w in estimates),
        'freeDecodeAgainstRetained':{'compared':len(errors),'startWithin100ms':sum(a<=BAR for a,b in errors),
            'endWithin100ms':sum(b<=BAR for a,b in errors),'bothWithin100ms':sum(max(a,b)<=BAR for a,b in errors)}}
    result={'quality':'private prediction','publicationAuthority':False,'recordingSha256':song['master'],
        'mapping':song['identity'],**{k:song[k] for k in ('canonicalPlainSha256','stateSha256',
        'contentPolicy','originalContentDecisionSha256','suppliedSheetSha256','guideSha256','language','vocalRepeatPlacement','sheetComparison') if k in song},
        'durationMs':song['duration'],'observations':{v:{'file':Path(os.path.relpath(p,output)).as_posix(),
            'sha256':sha256_file(p)} for v,p in paths.items()},
        'plan':signature,'summary':summary,'lines':output_lines}
    if releases:
        result['vowelReleaseEvidence']=release_ref
    if 'suppliedSheetSha256' in song:
        summary['wholeRecordingGuideOnly']=len(missing)
        summary['paddedEdgeWords']=len(recovered)
    write_cues(output,result)
    print(json.dumps(summary),flush=True)


def write_native_preview(song,output):
    method='whole-recording forced estimate'
    lines=[]
    for line in song['lines']:
        entries=[{**song['words'][i],'startMs':song['base'][i][0],'endMs':song['base'][i][1],
                  'startSource':method,'timing':'estimated'} for i in range(line['first'],line['stop'])]
        lines.append({'occurrenceId':line['occurrenceId'],'text':line['text'],'words':entries})
    count=len(song['words'])
    write_cues(output,{'method':method,'quality':'private prediction','publicationAuthority':False,
        'recordingSha256':song['master'],'mapping':song['identity'],'durationMs':song['duration'],
        **{k:song[k] for k in ('suppliedSheetSha256','guideSha256','language','sheetComparison') if k in song},
        'summary':{'lines':len(lines),'words':count,'estimated':count,'retained':0,'wholeRecordingGuideOnly':count},
        'lines':lines})


def write_cues(output,result,*,layered=False):
    atomic_write_json(output/('voice-timing.private.json' if layered else 'word-timing.private.json'),result)
    vtt=['WEBVTT','', 'NOTE Private listening preview includes estimated boundaries.','']
    entries=[(w,w.get('voice',line.get('voice'))) for line in result['lines'] for w in line['words']]
    if layered:
        entries.sort(key=lambda entry:entry[0]['startMs'])
    for w,voice in entries:
        surface=html.escape(w['text'])
        if voice:
            surface=f'<v {voice}>{surface}</v>'
        vtt.extend((w['wordId'],f'{timestamp(w["startMs"])} --> {timestamp(w["endMs"])}',surface,''))
    atomic_write_bytes(output/('voices.vtt' if layered else 'words.vtt'),('\n'.join(vtt)+'\n').encode('utf-8'))


def render_binding(timing_digest, layered):
    from stemwise_engine.provenance import semantic_source_identity
    font = Path(__file__).with_name('preview_font.py')
    return {'timingSha256':timing_digest,'layered':layered,
            'renderer':semantic_source_identity(Path(__file__),('render_video',),portable=True),
            'fontSelector':semantic_source_identity(font,portable=True)}


def render_video(output, audio, *, layered=False):
    """Show the actual audio and each separately predicted stop, including gaps."""
    timing=output/('voice-timing.private.json' if layered else 'word-timing.private.json')
    raw=timing.read_bytes()
    data=json.loads(raw)
    binding=render_binding(hashlib.sha256(raw).hexdigest(),layered)
    receipt=output/'preview-render.private.json'
    movie=output/'preview.mp4'
    if sha256_file(audio)!=data['recordingSha256']:
        raise ValueError('Listening copy must use the timed recording')
    if movie.is_file() and receipt.is_file():
        try:
            saved=json.loads(receipt.read_bytes())
            if saved['binding']==binding and sha256_file(movie)==saved['videoSha256']:
                return
        except (OSError,ValueError,KeyError,TypeError):
            pass
    from .preview_font import subtitle_font
    family,extra_font=subtitle_font(data['lines'])
    script=['[Script Info]', 'ScriptType: v4.00+', 'PlayResX: 1100','PlayResY: 240',
        '[V4+ Styles]',
        'Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding',
        # libass Encoding -1 resolves bidi across the complete line, including highlight overrides.
        f'Style: Lyrics,{family},37,&H00D8DBDC,&H00D8DBDC,&H001A1A1A,&H001A1A1A,0,0,0,0,100,100,0,0,1,0,0,5,48,48,16,-1',
        '[Events]','Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text']
    def ass_time(ms):
        h,r=divmod((ms+5)//10,360000)
        m,r=divmod(r,6000)
        s,cs=divmod(r,100)
        return f'{h}:{m:02}:{s:02}.{cs:02}'
    def event(a,b,text):
        if ass_time(a)!=ass_time(b):
            script.append(f'Dialogue: 0,{ass_time(a)},{ass_time(b)},Lyrics,,0,0,0,,{text}')
    def literal(text):
        return text.replace('\\','\\\u2060').replace('{',r'\{').replace('}',r'\}')
    for line in data['lines']:
        text=line['text']
        voice=line.get('voice')
        def lane(role):
            return (r'{\pos(550,70)}' if role=='lead' else r'{\pos(550,170)}' if role=='choir' else '')
        def label(role,a,b):
            y=70 if role=='lead' else 170
            event(a,b,r'{\an4\pos(24,'+str(y)+r')\fs19}'+('Lead' if role=='lead' else 'Choir' if voice else 'Backing'))
        if voice:
            label(voice,line['words'][0]['startMs'],line['words'][-1]['endMs'])
        encoded=text.encode('utf-16-le')
        last=line['words'][0]['startMs']
        for word in line['words']:
            a,b=word['startUtf16']*2,word['endUtf16']*2
            if encoded[a:b].decode('utf-16-le')!=word['text']:
                raise ValueError('Display word differs from timing owner')
            event(last,word['startMs'],lane(voice)+literal(text))
            role=word.get('voice',voice)
            if role and not voice:
                label(role,word['startMs'],word['endMs'])
            color=r'{\c&HDACE60&}' if role=='choir' else r'{\c&H69DFFF&}'
            colored=(literal(encoded[:a].decode('utf-16-le'))+color+literal(word['text'])+
                     r'{\rLyrics}'+literal(encoded[b:].decode('utf-16-le')))
            event(word['startMs'],word['endMs'],lane(role)+(literal(text) if word.get('boundaryReview') else colored))
            last=word['endMs']
    with tempfile.TemporaryDirectory(dir=output) as temp:
        folder=Path(temp)
        (folder/'lyrics.ass').write_text('\n'.join(script)+'\n',encoding='utf-8')
        subtitle_filter='ass=lyrics.ass'
        if extra_font:
            fonts=folder/'fonts'
            fonts.mkdir()
            shutil.copyfile(extra_font,fonts/extra_font.name)
            subtitle_filter+=':fontsdir=fonts'
        render=run_process_tree(['ffmpeg','-nostdin','-nostats','-v','info','-f','lavfi','-i',
            f'color=c=0x171a1c:s=1100x240:r=50:d={data["durationMs"]/1000:.3f}',
            '-i',str(audio.resolve()),'-map','0:v:0','-map','1:a:0','-vf',subtitle_filter,'-c:v','libx264',
            '-threads','4','-preset','veryfast','-crf','19','-pix_fmt','yuv420p',
            '-c:a','aac','-b:a','192k','-movflags','+faststart','preview.mp4'],cwd=folder,check=True,timeout=300,capture_output=True)
        missing=next((line for line in render.stderr.splitlines() if b'failed to find any fallback with glyph' in line),None)
        if missing:
            raise ValueError(missing.decode('utf-8',errors='replace'))
        rendered=folder/'preview.mp4'
        if sha256_file(timing)!=binding['timingSha256']:
            raise ValueError('Timing changed while rendering')
        rendered.replace(movie)
        atomic_write_json(receipt,{'binding':binding,'videoSha256':sha256_file(movie)})


if __name__ == '__main__':
    import argparse
    parser=argparse.ArgumentParser(description='Render a saved recording-bound lyric preview without running alignment.')
    parser.add_argument('--audio',type=Path,required=True)
    parser.add_argument('--timing',type=Path,required=True)
    args=parser.parse_args()
    if args.timing.name not in ('word-timing.private.json','voice-timing.private.json'):
        parser.error('--timing must select a saved single-voice or layered timing file')
    render_video(args.timing.parent,args.audio,layered=args.timing.name=='voice-timing.private.json')

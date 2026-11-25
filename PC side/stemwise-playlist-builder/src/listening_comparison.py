"""Build a self-contained two-view listening comparison using cached recording audio."""
import base64
from html import escape
import io
from pathlib import Path

from stemwise_engine.common import sha256_file


def _excerpt(path,digest,duration,lo,hi):
    import soundfile as sf
    if sha256_file(path)!=digest:
        raise ValueError('Listening source audio changed')
    with sf.SoundFile(path) as stream:
        rate=stream.samplerate
        if abs(len(stream)*1000/rate-duration)>2 or not 0<=lo<hi<=duration:
            raise ValueError('Listening excerpt differs from the selected recording clock')
        a,b=round(lo*rate/1000),min(len(stream),round(hi*rate/1000))
        stream.seek(a)
        pcm=stream.read(b-a,dtype='int16',always_2d=True)
        if len(pcm)!=b-a:
            raise ValueError('Listening excerpt is incomplete')
    out=io.BytesIO()
    sf.write(out,pcm,rate,format='WAV',subtype='PCM_16')
    return base64.b64encode(out.getvalue()).decode('ascii')


def render(workflow,video_id,option):
    from stemwise_engine.lyrics_alignment import AlignmentWorkspace,_plain_original
    from stemwise_engine.recording_release_repair import _sources
    from stemwise_engine.common import read_bounded_json
    from stemwise_engine.contract import MAX_JSON_BYTES
    ws=AlignmentWorkspace(workflow.root,workspace_root=workflow.workspace_root)
    folder=ws.job_dir(video_id)
    def read(path):
        return read_bounded_json(path,max_bytes=MAX_JSON_BYTES,root=path.parent)
    audio=read(workflow.runtime/'work/checkpoints'/f'{video_id}.json')['originalAudio']
    state=read(ws.state_path(video_id))
    original=_plain_original(read(ws.current_backup_path(video_id)))
    sources=_sources(folder,audio,original,state['contentAdmission']['sourcePhysicalIndices'])
    media=[('Original',workflow.analysis_temp/video_id/'analysis.wav',audio['sha256'])]
    if sources:
        ids,paths,_=sources
        media.append(('Isolated vocals',Path(paths['vocal']),ids['vocalSha256']))
    lo,hi=option['context']['startMs'],min(option['context']['endMs'],audio['durationMs'])
    word=escape(option['word'])
    tracks=[]
    for label,path,digest in media:
        samples=_excerpt(path,digest,audio['durationMs'],lo,hi)
        tracks.append(f'<section><label>{label}</label><audio controls preload="metadata" src="data:audio/wav;base64,{samples}"></audio></section>')
    lanes=[]
    for key,label in (('A','Current'),('B','Alternative')):
        p=option[key]
        a,b=p['startMs'],p['endMs']
        lanes.append(f'<div class="row" data-a="{a}" data-b="{b}"><b>{key} · {label}</b> '
                     f'<span>{a/1000:.3f}–{b/1000:.3f} s</span><div class="lane">'
                     f'<i style="left:{100*(a-lo)/(hi-lo):.5f}%;width:{100*(b-a)/(hi-lo):.5f}%"></i>'
                     f'<em></em></div></div>')
    html=f'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>{word} · Timing comparison</title><style>
body{{font:18px system-ui;max-width:44rem;margin:3rem auto;padding:0 1rem;color:#172535;background:#fafcfd}}
h1{{font-size:2rem}}section,.row{{margin:1.4rem 0}}label,audio{{display:block;width:100%;margin:.4rem 0}}
.row span{{float:right;font-variant-numeric:tabular-nums}}.lane{{height:.7rem;background:#dbe2e8;position:relative;margin-top:.5rem}}
i,em{{position:absolute;height:100%}}i{{background:#8a9bad}}.on i{{background:#1b775f}}em{{border-left:2px solid #152939}}
</style><h1>{word}</h1><p>Line {option['line']}, word {option['wordIndex']}. Each bar lights while that interval is active.</p>
{''.join(tracks)}{''.join(lanes)}
<script>const players=[...document.querySelectorAll('audio')],rows=[...document.querySelectorAll('.row')];
let active=players[0];function paint(){{const t={lo}+active.currentTime*1000;
for(const r of rows){{r.classList.toggle('on',t>=+r.dataset.a && t<+r.dataset.b);
r.querySelector('em').style.left=Math.min(100,Math.max(0,(t-{lo})*100/{hi-lo}))+'%';}}}}
for(const audio of players){{audio.onplay=()=>{{active=audio;for(const other of players)if(other!==audio)other.pause();
function playing(){{paint();if(!audio.paused && active===audio)requestAnimationFrame(playing);}}playing();}};
audio.onseeked=()=>{{active=audio;paint();}};}}paint();</script></html>'''
    return html.encode('utf-8'),[label for label,_,_ in media]

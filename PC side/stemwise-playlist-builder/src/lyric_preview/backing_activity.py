"""Screen a separated vocal residual on the recording clock before interpreting words."""
from pathlib import Path
from importlib.metadata import version
import numpy as np
from scipy import ndimage, signal
from .search import PROJECT, read
from stemwise_engine.common import atomic_write_json, heavy_worker_stage, sha256_file
from stemwise_engine.runtime_environment import environment_lease


def exposed_frames(lead, residual, backing):
    """20 ms frames with distinct residual energy in multiple vocal bands of the mix."""
    if len({len(x) for x in (lead,residual,backing)})!=1 or len(lead)<640:
        raise ValueError('Vocal parts and accompaniment have different clocks')
    # Keep the spectral matrices bounded on long recordings. Use actual neighboring
    # samples for the STFT and coherence filter at every internal chunk seam.
    hop=320
    step=16000*30
    if len(lead)>step+16*hop:
        frames=[]
        for start in range(0,len(lead),step):
            end=min(len(lead),start+step)
            lo=max(0,start-8*hop)
            hi=min(len(lead),end+8*hop)
            block=exposed_frames(lead[lo:hi],residual[lo:hi],backing[lo:hi])
            frames.append(block[(start-lo)//hop:(end-lo)//hop if end<len(lead) else None])
        return np.concatenate(frames)
    def spectrum(x):
        return signal.stft(x,16000,nperseg=640,noverlap=320)[2]
    def smooth(x):
        return ndimage.uniform_filter1d(x,size=11,axis=-1,mode='nearest')
    # Work one band at a time. A loud isolated track or a bass line alone
    # says little about a separate sung part in the original mix.
    l,r,b=map(spectrum,(lead,residual,backing))
    f=np.fft.rfftfreq(640,1/16000)
    votes=np.zeros(r.shape[1],dtype=int)
    for low,high in ((250,650),(650,1400),(1400,2800),(2800,5000)):
        bins=(low<=f)&(f<high)
        x,y,z=l[bins],r[bins],b[bins]
        def cross(a,c):
            return smooth(a*c.conj())
        xx,yy,zz=(cross(a,a).real for a in (x,y,z))
        xz,yx,yz=cross(x,z),cross(y,x),cross(y,z)
        # Project onto both controls, including correlated or silent controls.
        xx=xx+1e-12
        zz=zz+1e-12
        remaining=np.maximum(0.,yy-abs(yx)**2/xx-
            abs(yz-yx*xz/xx)**2/np.maximum(1e-12,zz-abs(xz)**2/xx))
        independent=remaining.sum(axis=0)
        votes+=(independent>0.5*yy.sum(axis=0)) & (independent>0.01*(xx+zz).sum(axis=0)) & (independent>1e-9)
    return votes>=2


def read_parts(folder, paths, language, config):
    """Unprompted word proposals on each actual track, including the instrumental control."""
    from faster_whisper import WhisperModel
    from stemwise_engine import lyrics_align_pc as pc
    from stemwise_engine.lyrics_alignment import resolve_compute_type
    from stemwise_engine.provenance import semantic_source_identity
    from stemwise_engine.worker_runtime_probe import ctranslate2_runtime_identity
    from stemwise_engine.ctc_worker import execution_deadline, retire_model
    result={}
    with environment_lease('main',builder=PROJECT),heavy_worker_stage(config):
        snapshot,model_ids=pc._verify_whisper_snapshot(PROJECT.parent/'model-cache/lyrics-alignment')
        compute=resolve_compute_type('cuda')
        binding=dict(model_ids,language=language,compute=compute,runtime=ctranslate2_runtime_identity('cuda'),
            fasterWhisperVersion=version('faster-whisper'),
            decoder=semantic_source_identity(Path(__file__),('read_parts',),portable=True))
        model=None
        try:
            for role,path in paths.items():
                owner=dict(binding,sha256=sha256_file(path))
                file=folder/(role+'-words.private.json')
                if file.exists():
                    saved=read(file)
                    if saved['binding']!=owner:
                        raise ValueError('Separated word reading belongs to different inputs')
                    result[role]=saved['words']
                    continue
                with execution_deadline(config['analysisTimeoutMinutes']*60):
                    if model is None:
                        model=WhisperModel(str(snapshot),device='cuda',compute_type=compute,local_files_only=True)
                    segments,_=model.transcribe(str(path),language=language,beam_size=5,word_timestamps=True,
                        condition_on_previous_text=False,vad_filter=False,temperature=0.)
                    words=[{'text':w.word.strip(),'startMs':round(1000*w.start),'endMs':round(1000*w.end)}
                           for seg in segments for w in seg.words or [] if w.word.strip() and w.end>w.start]
                atomic_write_json(file,{'binding':owner,'words':words})
                result[role]=words
        finally:
            if model is not None:
                with execution_deadline(30):
                    model.model.unload_model()
                del model
                retire_model({'cuda_model':True})
    return result

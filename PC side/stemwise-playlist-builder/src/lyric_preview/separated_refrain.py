"""Read both vocal parts on the target clock before assigning words to them."""
import math
from pathlib import Path

import soundfile as sf
import numpy as np
from scipy import signal

from .search import PROJECT, read
from stemwise_engine.common import atomic_write_json
from stemwise_engine.provenance import semantic_source_identity


def mono16(path):
    from stemwise_engine.acoustic_inputs import mono_samples, require_waveform_allocation
    require_waveform_allocation(path,16000,source_arrays=4)
    samples,rate=sf.read(path,always_2d=True,dtype='float32')
    divisor=math.gcd(rate,16000)
    mono = mono_samples(samples)[0] if np.any(samples) else samples[:,0]
    return signal.resample_poly(mono,16000//divisor,rate//divisor)


def recognize_parts(model_path, parts, windows, language):
    import torch
    from transformers import AutoModelForSpeechSeq2Seq, AutoProcessor
    from transformers.models.qwen3_asr.processing_qwen3_asr import LANGUAGE_CODE_TO_NAME
    from stemwise_engine import qwen3_asr_transcribe as qwen
    from stemwise_engine.ctc_worker import retire_model
    processor=AutoProcessor.from_pretrained(str(model_path),local_files_only=True)
    model=AutoModelForSpeechSeq2Seq.from_pretrained(str(model_path),local_files_only=True,
        dtype=torch.bfloat16,attn_implementation='sdpa').to('cuda').eval()
    results={}
    try:
        with torch.inference_mode():
            for role,track in parts.items():
                results[role]=[]
                for start,end in windows:
                    crop=track[start*16:end*16]
                    inp=processor.apply_transcription_request(audio=crop,language=language,prompt=None,return_tensors='pt')
                    inp={k:v.to('cuda',dtype=torch.bfloat16) if v.is_floating_point() else v.to('cuda') for k,v in inp.items()}
                    seq=model.generate(**inp,do_sample=False,num_beams=1,max_new_tokens=qwen.DECODER['maxNewTokens'])
                    tokens=seq[0,inp['input_ids'].shape[1]:].cpu()
                    result=qwen._generation_result(tokens,processor.tokenizer,language,model.generation_config.eos_token_id,
                                                   language_names=LANGUAGE_CODE_TO_NAME.values())
                    results[role].append(result)
    finally:
        del model
        retire_model({'cuda_model':True})
    return results


def part_observations(folder, receipt, parts, windows, language):
    from stemwise_engine import lyrics_align_pc as pc, qwen3_asr_transcribe as qwen
    from stemwise_engine.common import load_config, heavy_worker_stage
    from stemwise_engine.runtime_environment import environment_lease
    if len(parts['lead'])!=len(parts['choir']) or not all(0<=s*16<e*16<=len(parts['lead']) for s,e in windows):
        raise ValueError('Vocal-part observations do not share the recording clock')
    file=folder/'recognition.private.json'
    from stemwise_engine.ctc_worker import execution_deadline
    config=load_config(PROJECT/'config.json')
    with (environment_lease('main',builder=PROJECT),heavy_worker_stage(config),
          execution_deadline(config['analysisTimeoutMinutes']*60)):
        binding={'parts':{r:receipt[r] for r in parts},'windows':[list(w) for w in windows],'language':language,
                 'runtime':qwen._runtime_identity('cuda'),'decoder':qwen.DECODER,'model':pc.QWEN3_ASR_SNAPSHOT_SHA256,
                 'producer':semantic_source_identity(Path(__file__),('mono16','recognize_parts'),portable=True)}
        if file.exists():
            saved=read(file)
            if saved['binding']!=binding:
                raise ValueError('Existing vocal-part observations belong to other inputs')
        else:
            model=pc._qwen3_asr_snapshot(PROJECT.parent/'model-cache/lyrics-alignment')
            saved={'binding':binding,'observations':recognize_parts(model,parts,windows,language)}
            atomic_write_json(file,saved)
    rows=saved['observations']
    if set(rows)!=set(parts) or any(len(sequence)!=len(windows) for sequence in rows.values()):
        raise ValueError('Separated observations lost their vocal part or window')
    for sequence in rows.values():
        for result in sequence:
            qwen._validate_observation(result,language,require_complete=False)
    return rows


def supported_count(mix_count, readings, token, language, words_per_group):
    from stemwise_engine.lyric_comparison import units
    counts=[]
    for result in readings:
        words=units(result.get('transcription') or '',language)
        counts.append(len(words) if words and all(word==token for word in words) else None)
    expected=len(counts)*words_per_group
    if any(n is not None and n!=words_per_group for n in counts):
        return None
    if mix_count==expected or (mix_count is None and counts and all(n==words_per_group for n in counts)):
        return expected
    return None

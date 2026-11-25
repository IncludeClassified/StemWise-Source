"""Private lead and backing-vocal tracks on the Original recording clock."""
import json
import hashlib
from pathlib import Path

import numpy as np
import soundfile as sf

from stemwise_engine import alignment_models as models
from stemwise_engine.common import WorkerTemporaryDirectory, atomic_write_json, heavy_worker_stage, json_bytes, run_process_tree, sha256_file
from stemwise_engine.model_registry import model_assets
from stemwise_engine.provenance import semantic_source_identity
from stemwise_engine.runtime_environment import environment_lease, environment_python
from stemwise_engine.worker_protocol import require_worker_success


def separator_installation(project):
    spec=model_assets('lyrics_align_pc')['PRIVATE_VOCAL_PART_SEPARATOR']
    root=project.parent/'model-cache/lyrics-roformer'/spec['routeId']
    files = [(root/spec[k]).is_file() for k in ('checkpoint','config')]
    if not all(files):
        return 'partial' if any(files) else 'missing'
    try:
        for field in ('checkpoint', 'config'):
            models.require_pinned_file(root/spec[field], spec[field+'Sha256'], spec[field])
    except (OSError, RuntimeError):
        return 'invalid'
    return 'ready'


def vocal_residual(vocals, lead, output):
    """Use the separator's own resampling convention before subtracting its lead."""
    with sf.SoundFile(vocals) as mix, sf.SoundFile(lead) as solo:
        if mix.channels!=2 or solo.channels!=2:
            raise ValueError('Vocal separation requires two stereo inputs')
        signal=None
        if mix.samplerate!=solo.samplerate:
            from stemwise_engine.acoustic_inputs import require_waveform_allocation
            import torch
            import torchaudio
            require_waveform_allocation(vocals, solo.samplerate, source_arrays=3,
                                        target_arrays=3, target_channels=2)
            wave=torch.from_numpy(mix.read(dtype='float32').T.copy())
            signal=torchaudio.functional.resample(wave,mix.samplerate,solo.samplerate).numpy().T
        if (mix.frames if signal is None else len(signal))!=solo.frames:
            raise ValueError('Separated vocal tracks do not share a clock')
        with sf.SoundFile(output,'w',samplerate=solo.samplerate,channels=2,subtype='FLOAT') as rest:
            for start in range(0,solo.frames,65536):
                samples=mix.read(65536,dtype='float32') if signal is None else signal[start:start+65536]
                difference=samples-solo.read(len(samples),dtype='float32')
                if not np.isfinite(difference).all():
                    raise ValueError('Non-finite vocal residual')
                rest.write(difference)


def separate_vocal_parts(config, original_hash, vocals, vocal_hash, output):
    """Separate a verified all-vocals stem once, then reuse the bound pair."""
    from stemwise_engine.lyrics_align_pc import _roformer_runtime_provenance
    spec=model_assets('lyrics_align_pc')['PRIVATE_VOCAL_PART_SEPARATOR']
    project=Path(config['projectDir'])
    assets=project.parent/'model-cache/lyrics-roformer'/spec['routeId']
    helper=project/'src/roformer_separation.py'
    python=environment_python('main',builder=project)
    timeout=int(config['analysisTimeoutMinutes'])*60
    if sha256_file(vocals)!=vocal_hash:
        raise ValueError('Vocal separation source differs from the bound vocal stem')
    for field in ('checkpoint','config'):
        models.require_pinned_file(assets/spec[field],spec[field+'Sha256'],spec[field])
    runtime=_roformer_runtime_provenance(python,helper,device='cuda',timeout_seconds=timeout)
    binding={'originalSha256':original_hash,'vocalsSha256':vocal_hash,'model':spec['checkpointSha256'],
             'config':spec['configSha256'],'runtime':runtime,'worker':semantic_source_identity(helper),
             'residual':semantic_source_identity(Path(__file__),('vocal_residual',),portable=True)}
    folder=output/('vocal-parts-'+hashlib.sha256(json_bytes(binding)).hexdigest())
    receipt='pair.private.json'
    if not folder.exists():
        with WorkerTemporaryDirectory(prefix='vocal-parts-',dir=output) as tmp:
            stage=Path(tmp)/'parts'
            stage.mkdir()
            with environment_lease('main',builder=project),heavy_worker_stage(config):
                task=run_process_tree([str(python),'-m','stemwise_engine.roformer_separation',
                    '--route',spec['routeId'],'--audio',str(vocals),'--input-sha256',vocal_hash,
                    '--checkpoint',str(assets/spec['checkpoint']),'--checkpoint-sha256',spec['checkpointSha256'],
                    '--config',str(assets/spec['config']),'--config-sha256',spec['configSha256'],
                    '--output',str(stage/'lead.wav'),'--result',str(stage/'separator.private.json'),'--device','cuda'],
                    timeout=timeout,env=models.roformer_offline_env(project.parent),text=True,capture_output=True)
                require_worker_success(task,code='vocal-part-separation',diagnostic_path=output/'vocal-parts.failure.txt')
            measured=json.loads((stage/'separator.private.json').read_bytes())
            if (measured['input']['sha256']!=vocal_hash or measured['runtime']!=runtime
                    or measured['output']['sha256']!=sha256_file(stage/'lead.wav')):
                raise ValueError('Vocal separator did not produce the expected lead on this recording')
            vocal_residual(vocals,stage/'lead.wav',stage/'choir.wav')
            atomic_write_json(stage/receipt,{'binding':binding,'lead':measured['output']['sha256'],
                                             'choir':sha256_file(stage/'choir.wav')})
            try:
                stage.rename(folder)
            except OSError:
                if not (folder/receipt).is_file():
                    raise
    saved=json.loads((folder/receipt).read_bytes())
    if saved['binding']!=binding or any(sha256_file(folder/(r+'.wav'))!=saved[r] for r in ('lead','choir')):
        raise ValueError('Saved vocal parts differ from the current inputs. Preserve them and use another folder.')
    return folder,saved

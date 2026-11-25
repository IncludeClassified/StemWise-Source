"""Unprompted Arabic character landmarks on one recorded vocal role."""
from itertools import groupby
import json
from pathlib import Path

from stemwise_engine.alignment_models import require_pinned_file
from stemwise_engine.model_registry import model_assets

_ROUTE=model_assets('endpoint_candidate_prediction')
ASSETS={k:_ROUTE[k] for k in ('REPOSITORY','SNAPSHOT','MODEL_FILES')}


class ArabicLetters:
    def __init__(self,project):
        import torch
        from transformers import Wav2Vec2FeatureExtractor, Wav2Vec2ForCTC
        root=(Path(project).parent/'model-cache'/'lyrics-alignment'/
              ('models--'+ASSETS['REPOSITORY'].replace('/','--'))/'snapshots'/ASSETS['SNAPSHOT'])
        for name,digest in ASSETS['MODEL_FILES'].items():
            require_pinned_file(root/name,digest,name)
        self.names={v:k for k,v in json.loads((root/'vocab.json').read_bytes()).items()}
        torch.set_num_threads(min(4,torch.get_num_threads()))
        self.model=Wav2Vec2ForCTC.from_pretrained(root,local_files_only=True).eval()
        self.extractor=Wav2Vec2FeatureExtractor.from_pretrained(root,local_files_only=True)
        if self.model.config.inputs_to_logits_ratio!=320:
            raise ValueError('Arabic acoustic frame stride changed')

    def __call__(self,mono,sample_rate,origin_ms):
        import numpy as np
        import torch
        import torchaudio.functional as audio
        if (type(origin_ms) is not int or origin_ms<0 or type(sample_rate) is not int or sample_rate<8000 or
                np.ndim(mono)!=1 or not np.isfinite(mono).all() or not sample_rate//5<=len(mono)<=30*sample_rate):
            raise ValueError('Invalid Arabic vocal observation window')
        wav=audio.resample(torch.as_tensor(mono,dtype=torch.float32),sample_rate,16000).numpy()
        data=self.extractor(wav,sampling_rate=16000,return_tensors='pt')
        with torch.inference_mode():
            logits=self.model(data.input_values).logits[0]
            if not torch.isfinite(logits).all():
                raise ValueError('Invalid Arabic vocal emissions')
            peaks,labels=logits.softmax(-1).max(-1)
        events=[]
        for token,runs in groupby(enumerate(zip(labels.tolist(),peaks.tolist())),key=lambda x:x[1][0]):
            if token==self.model.config.pad_token_id:
                continue
            group=list(runs)
            peak=max(p for _,(_,p) in group)
            if peak>=.05:
                # CTC landmarks locate phones. Their exit is never an acoustic release.
                events.append([origin_ms+20*group[0][0]+2,origin_ms+20*group[-1][0]+22,
                               self.names[token],round(peak,5)])
        return events

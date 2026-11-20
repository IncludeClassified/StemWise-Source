"""Known-answer retained recognition fixtures; no model execution."""
import json
from pathlib import Path

import stemwise_engine.mms_asr as mms_asr
from stemwise_engine.common import sha256_file
from stemwise_engine.lyric_content import confirm_content


def write_recognition(root: Path, original, indices, audio_sha="a" * 64, *, recognized=None, adapter=None):
    root.mkdir(parents=True, exist_ok=True)
    text = original["text"] if isinstance(original, dict) else original
    words = (recognized if recognized is not None else
             " ".join(text.splitlines()[index] for index in indices)).split()
    runtime = {"torch": "fixture", "torchaudio": "fixture", "transformers": "fixture",
               "resolvedDevice": "cpu", "dtype": "float32", "recipe": "mms-greedy-20ms-v1"}
    adapter = adapter or mms_asr.ADAPTER
    duration = max(1000, len(words) * 200)
    worker = {"familyId": "mms-asr", "modelRevision": mms_asr.REVISION,
        "adapter": adapter, "assets": mms_asr.ASSETS_BY_ADAPTER[adapter], "audioSha256": audio_sha,
        "durationMs": duration, "frameMs": 20, "runtime": runtime,
        "words": [{"raw": word, "start": index * .2, "end": index * .2 + .1}
                  for index, word in enumerate(words)]}
    names = ("mms_asr.py", "endpoint_ctc_evidence.py", "wav2vec2_forced_alignment.py",
             "acoustic_inputs.py", "ctc_numerics.py", "ctc_worker.py", "worker_protocol.py",
                 "worker_publication.py", "model_registry.py", "model-route-registry.json")
    cache = {"provenance": {"familyId": "mms-asr", "promptMode": "withheld",
        "audioSha256": audio_sha, "durationMs": duration, "adapter": adapter, "runtime": runtime,
        "helpers": {name: mms_asr.semantic_source_identity(Path(mms_asr.__file__).parent / name) for name in names}},
        "workerOutput": worker}
    path = root / "native-asr-fixture-mix.private.json"
    path.write_text(json.dumps(cache), encoding="utf-8")
    return path


def retained_content(root: Path, original, indices, canonical_sha="c" * 64, audio_sha="a" * 64,
                     *, recognized=None):
    path = write_recognition(root, original, indices, audio_sha, recognized=recognized)
    admission = confirm_content(original, indices, canonical_sha, audio_sha,
                                evidence_path=path, evidence_root=root)
    return admission, path

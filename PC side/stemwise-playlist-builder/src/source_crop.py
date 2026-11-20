"""Recover uncommitted PCM preparation without replacing committed crop authority."""
from pathlib import Path
import hashlib
import tempfile

from stemwise_engine.common import atomic_write_json, durable_replace, read_bounded_json, sha256_file
from stemwise_engine.workflow_records import WorkflowInputError


def ensure_source_crop(path: Path, samples, rate: int, *, soundfile, numpy) -> None:
    checkpoint = path.with_suffix('.crop.json')
    expected = {'formatVersion': 1, 'sampleRate': rate, 'shape': list(samples.shape),
                'samplesSha256': hashlib.sha256(samples.tobytes()).hexdigest()}
    committed = checkpoint.exists() or (path.parent / 'prepared.json').exists()
    if checkpoint.exists():
        retained_identity = read_bounded_json(checkpoint, max_bytes=4096, root=path.parent)
        if (not isinstance(retained_identity, dict)
                or any(retained_identity.get(key) != value for key, value in expected.items())
                or not path.is_file() or retained_identity.get('sha256') != sha256_file(path)):
            raise WorkflowInputError('Committed source crop differs; preserve it for recovery',
                reason_code='retryable-technical', affected_input='committed source crop',
                next_action='Preserve the committed crop and resolve its input binding before Retry.')
    if path.exists():
        try:
            retained, retained_rate = soundfile.read(path, dtype='float32', always_2d=True)
        except (OSError, RuntimeError, ValueError):
            if committed:
                raise ValueError('Committed source crop cannot be decoded')
        else:
            if retained_rate == rate and numpy.array_equal(retained, samples):
                if not checkpoint.exists():
                    atomic_write_json(checkpoint, {**expected, 'sha256': sha256_file(path)}, root=path.parent)
                return
            if committed or retained.shape == samples.shape:
                raise ValueError('Retained source crop differs from the recording')
    elif committed:
        raise ValueError('Committed source crop is missing')
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(suffix='.wav', prefix='.source-', dir=path.parent, delete=False) as handle:
        temporary = Path(handle.name)
    try:
        soundfile.write(temporary, samples, rate, subtype='FLOAT')
        decoded, decoded_rate = soundfile.read(temporary, dtype='float32', always_2d=True)
        if decoded_rate != rate or not numpy.array_equal(decoded, samples):
            raise ValueError('Prepared source crop is incomplete')
        durable_replace(temporary, path)
        atomic_write_json(checkpoint, {**expected, 'sha256': sha256_file(path)}, root=path.parent)
    finally:
        temporary.unlink(missing_ok=True)


def read_source_crop(scratch: Path, plan: dict):
    import numpy as np
    import soundfile as sf
    from stemwise_engine.common import validate_generated_path
    from stemwise_engine.acoustic_inputs import require_waveform_allocation
    path = validate_generated_path(scratch, scratch / 'source.wav')
    require_waveform_allocation(path, 48000)
    samples, rate = sf.read(path, dtype='float32', always_2d=True)
    # Recording duration rounds to whole milliseconds, half up. Preserve the
    # actual EOF frames; callers compare the entire requested original crop,
    # never just a retained prefix, before any inference or publication.
    if (rate != 48000 or not len(samples)
            or (len(samples)+24)//48 != plan["endMs"]-plan["startMs"]
            or samples.shape[1] not in (1, 2) or not np.isfinite(samples).all()):
        raise ValueError("singing original source crop has invalid rate, duration or channels")
    return samples

"""Model-independent PCM planning and admission against available machine capacity."""
from dataclasses import dataclass
from pathlib import Path

CTC_MINIMUM_SAMPLES = 400

def resampled_sample_count(frames: int, source_rate: int, target_rate: int) -> int:
    if any(type(value) is not int or value <= 0 for value in (frames, source_rate, target_rate)):
        raise ValueError('acoustic sample-count metadata is invalid')
    return (frames * target_rate + source_rate - 1) // source_rate


def chunk_ranges(sample_count: int, chunk_samples: int, *, minimum_samples: int = CTC_MINIMUM_SAMPLES) -> tuple[tuple[int, int], ...]:
    if (type(sample_count) is not int or type(chunk_samples) is not int
            or type(minimum_samples) is not int or minimum_samples <= 0
            or sample_count < minimum_samples or chunk_samples < minimum_samples):
        raise ValueError('audio is shorter than the model receptive minimum or chunk size is invalid')
    chunks = []
    start = 0
    while start < sample_count:
        stop = min(start + chunk_samples, sample_count)
        if 0 < sample_count-stop < minimum_samples:
            stop = sample_count
        chunks.append((start, stop))
        start = stop
    return tuple(chunks)


@dataclass(frozen=True)
class AcousticBudget:
    available_bytes: int | None = None

    def require(self, *, frames: int = 0, states: int = 0, arrays: int = 1, item_bytes: int = 8,
                retained_bytes: int = 0) -> None:
        values = (frames, states, arrays, item_bytes, retained_bytes)
        if any(type(value) is not int or value < 0 for value in values):
            raise ValueError('acoustic budget dimensions are invalid')
        required = frames*states*arrays*item_bytes+retained_bytes
        if not required:
            return
        from stemwise_engine.analysis_resources import available_memory_bytes
        available = available_memory_bytes() if self.available_bytes is None else self.available_bytes
        if type(available) is not int or available < 0:
            raise ValueError('Available acoustic memory is invalid')
        if required > available:
            raise AcousticBudgetExceeded('acoustic-memory-capacity-unavailable')

    def require_recording(self, retained_bytes: int) -> None:
        self.require(retained_bytes=retained_bytes)


class AcousticBudgetExceeded(ValueError):
    """The operation cannot allocate safely with the currently available capacity."""


CURRENT_BUDGET = AcousticBudget()


def require_waveform_allocation(path, target_rate, *, source_arrays=1, target_arrays=1,
                                target_channels=1, recording=False):
    """Measure decoded/resampled PCM size before a decoder allocates it."""
    import soundfile as sf
    info = sf.info(path)
    if (info.frames <= 0 or info.channels not in (1, 2) or info.samplerate <= 0
            or type(target_rate) is not int or target_rate <= 0):
        raise ValueError('acoustic input metadata is invalid')
    if any(type(value) is not int or value <= 0 for value in (source_arrays, target_arrays, target_channels)):
        raise ValueError('acoustic allocation array dimensions are invalid')
    target_frames = resampled_sample_count(info.frames, info.samplerate, target_rate)
    retained = info.frames*info.channels*4*source_arrays + target_frames*target_channels*4*target_arrays
    if recording:
        CURRENT_BUDGET.require_recording(retained)
    else:
        CURRENT_BUDGET.require(retained_bytes=retained)
    return retained


def informative_views(samples):
    import numpy as np
    values = np.asarray(samples)
    if values.ndim != 2 or values.shape[1] not in (1, 2) or not len(values) or not np.isfinite(values).all():
        raise ValueError('acoustic representation input is invalid')
    views = {'mono': values[:, 0]} if values.shape[1] == 1 else {
        'left': values[:, 0], 'right': values[:, 1], 'mid': values.mean(axis=1),
        'side': (values[:, 0]-values[:, 1])/2}
    energy = {name: float(np.mean(np.square(signal, dtype=np.float64))) for name, signal in views.items()}
    peak = max(energy.values())
    if peak <= 1e-12:
        raise ValueError('acoustic-representation-uninformative')
    return {name: signal for name, signal in views.items() if energy[name] > max(1e-12, peak*1e-4)}


def mono_samples(samples):
    views = informative_views(samples)
    import numpy as np
    name = mono_representation({name: float(np.mean(np.square(signal, dtype=np.float64)))
                                for name, signal in views.items()})
    return views[name], name


def mono_representation(energy):
    """Select one recording-wide view, retaining a channel when mid cancels."""
    peak = max(energy.values())
    informative = {name for name, value in energy.items() if value > max(1e-12, peak*1e-4)}
    if not informative:
        raise ValueError('acoustic-representation-uninformative')
    for name in ('mono', 'mid'):
        if name in informative:
            return name
    return max((name for name in ('left', 'right') if name in informative), key=energy.get)


def audio_mono_representation(path):
    """Measure the same representation policy without allocating the recording."""
    import numpy as np
    import soundfile as sf
    sums, count = {}, 0
    with sf.SoundFile(path) as audio:
        for samples in audio.blocks(blocksize=65536, dtype='float32', always_2d=True):
            if samples.shape[1] not in (1, 2) or not np.isfinite(samples).all():
                raise ValueError('acoustic representation input is invalid')
            views = {'mono': samples[:, 0]} if samples.shape[1] == 1 else {
                'left': samples[:, 0], 'right': samples[:, 1], 'mid': samples.mean(axis=1),
                'side': (samples[:, 0]-samples[:, 1])/2}
            for name, signal in views.items():
                sums[name] = sums.get(name, 0.) + float(np.square(signal, dtype=np.float64).sum())
            count += len(samples)
    if not count:
        raise ValueError('acoustic-representation-uninformative')
    return mono_representation({name: value/count for name, value in sums.items()})


class StreamedMonoAudio:
    """Read bounded target-rate windows on the recording's global resampling grid."""
    def __init__(self, path, target_rate):
        import soundfile as sf
        self.path = Path(path)
        info = sf.info(self.path)
        if (info.frames <= 0 or info.channels not in (1, 2) or info.samplerate <= 0
                or type(target_rate) is not int or target_rate <= 0):
            raise ValueError('acoustic input metadata is invalid')
        self.source_rate, self.channels, self.frames = info.samplerate, info.channels, info.frames
        self.target_rate = target_rate
        self.sample_count = resampled_sample_count(info.frames, info.samplerate, target_rate)
        self.representation = audio_mono_representation(self.path)

    def read(self, start, stop):
        import math
        import soundfile as sf
        import torch
        import torchaudio
        if (type(start) is not int or type(stop) is not int
                or not 0 <= start < stop <= self.sample_count):
            raise ValueError('acoustic window is invalid')
        phase = self.source_rate//math.gcd(self.source_rate, self.target_rate)
        # Include the default sinc kernel's complete support on both sides.
        halo = math.ceil(6*self.source_rate/(min(self.source_rate, self.target_rate)*.99))+phase
        first = max(0, start*self.source_rate//self.target_rate-halo)//phase*phase
        last = min(self.frames, (stop*self.source_rate+self.target_rate-1)//self.target_rate+halo)
        CURRENT_BUDGET.require(retained_bytes=(last-first)*self.channels*4+(stop-start+2*halo)*4)
        with sf.SoundFile(self.path) as source:
            if (source.frames, source.samplerate, source.channels) != (self.frames, self.source_rate, self.channels):
                raise ValueError('acoustic input metadata changed')
            source.seek(first)
            values = torch.from_numpy(source.read(last-first, dtype='float32', always_2d=True).T)
        if self.representation in ('mono', 'mid'):
            values = values.mean(dim=0, keepdim=True)
        else:
            values = values[0:1] if self.representation == 'left' else values[1:2]
        if self.source_rate != self.target_rate:
            values = torchaudio.functional.resample(values, self.source_rate, self.target_rate)
        offset = start-first*self.target_rate//self.source_rate
        result = values[:, offset:offset+stop-start]
        if result.shape[-1] != stop-start or not torch.isfinite(result).all():
            raise ValueError('acoustic input window is incomplete or nonfinite')
        return result


def mapped_emissions(chunks, directory):
    """Spool model chunks once; retain a disk mapping instead of a list plus concat."""
    import numpy as np
    import tempfile
    import torch
    count, columns = 0, None
    with tempfile.TemporaryFile(dir=directory) as output:
        for chunk in chunks:
            if (chunk.ndim != 2 or chunk.shape[0] <= 0 or not chunk.is_floating_point()
                    or not torch.isfinite(chunk).all()):
                raise ValueError('acoustic emission chunk is invalid')
            if columns is not None and chunk.shape[1] != columns:
                raise ValueError('acoustic emission vocabulary changed')
            columns = int(chunk.shape[1])
            CURRENT_BUDGET.require(retained_bytes=chunk.numel()*4)
            chunk.detach().to(device='cpu', dtype=torch.float32).numpy().tofile(output)
            count += int(chunk.shape[0])
        if not count or not columns:
            raise ValueError('acoustic emissions are empty')
        output.flush()
        return torch.from_numpy(np.memmap(output, mode='r+', dtype='float32', shape=(count, columns)))


def mono_tensor(waveform):
    # Source tensors are on CPU; only choose a representation here, preserving
    # the existing Torch arithmetic mean whenever it carries useful energy.
    _, name = mono_samples(waveform.detach().numpy().T)
    if name in ('mid', 'mono'):
        return waveform.mean(dim=0, keepdim=True)
    return waveform[0:1] if name == 'left' else waveform[1:2]

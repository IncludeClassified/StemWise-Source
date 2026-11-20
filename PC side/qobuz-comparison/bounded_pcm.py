"""Disposable mapped PCM and bounded numerical windows for long comparisons."""
import math
from contextlib import contextmanager
from contextvars import ContextVar
import mmap
import shutil
from pathlib import Path
import weakref

import numpy as np


_BUDGET = ContextVar('comparison_budget', default=None)


@contextmanager
def comparison_operation(*, cancelled, seconds=1800):
    from stemwise_engine.provider_operations import ProviderBudget
    budget = ProviderBudget(seconds, cancelled=cancelled)
    token = _BUDGET.set(budget)
    try:
        budget.remaining()
        yield
        budget.remaining()
    finally:
        _BUDGET.reset(token)


def check_budget():
    budget = _BUDGET.get()
    if budget is not None:
        budget.remaining()
    return False


def remaining_seconds(default):
    budget = _BUDGET.get()
    return default if budget is None else min(default, budget.remaining())


CHUNK_FRAMES = 262144
RAM_BYTES = 16 * 1024 * 1024


SCRATCH_ROOT = Path(__file__).resolve().parents[1] / 'runtime/scratch/comparison-pcm'


class _ScratchDirectory:
    def __init__(self, root, path, lease, owner):
        self.name, self.descriptor = str(path), lease.fileno()
        self.root, self.lease, self.owner = root, lease, owner

    def cleanup(self):
        import fcntl
        from stemwise_engine.common import exclusive_process_lock, remove_manifested_directory
        if self.lease.closed:
            return
        try:
            with exclusive_process_lock(self.root/'registry.lock', wait_seconds=30):
                self.lease.close()
                with (Path(self.name)/'lease.lock').open('a+b') as lease:
                    try:
                        fcntl.flock(lease, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    except BlockingIOError:
                        return
                    remove_manifested_directory(self.root, Path(self.name), owner=self.owner)
        finally:
            self.lease.close()


def temporary_directory():
    import fcntl
    import uuid
    from stemwise_engine.common import (exclusive_process_lock, create_manifested_directory,
                        read_bounded_json, remove_manifested_directory, validate_generated_path)
    project = Path(__file__).resolve().parents[2]
    root = validate_generated_path(project, SCRATCH_ROOT)
    root.mkdir(parents=True, exist_ok=True)
    with exclusive_process_lock(root/'registry.lock', wait_seconds=30):
        for path in root.glob('.comparison-pcm-*'):
            identity = path.name.removeprefix('.comparison-pcm-')
            if len(identity) != 32 or any(c not in '0123456789abcdef' for c in identity) or path.is_symlink():
                continue
            owner = {'kind': 'comparison-pcm', 'identity': identity}
            manifest = path/'.stemwise-generated.json'
            if not manifest.is_file():
                continue
            record = read_bounded_json(manifest, max_bytes=16384, root=path)
            if record != {'manifestVersion': 1, 'owner': owner, 'generatedPaths': ['.']}:
                continue
            with (path/'lease.lock').open('a+b') as lease:
                try:
                    fcntl.flock(lease.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    continue
                remove_manifested_directory(root, path, owner=owner)
        identity = uuid.uuid4().hex
        path = root/f'.comparison-pcm-{identity}'
        owner = {'kind': 'comparison-pcm', 'identity': identity}
        create_manifested_directory(root, path, owner=owner)
        lease = (path/'lease.lock').open('a+b')
        try:
            fcntl.flock(lease.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            return _ScratchDirectory(root, path, lease, owner)
        except BaseException:
            lease.close()
            remove_manifested_directory(root, path, owner=owner)
            raise


def _retire(mapping, directory):
    mapping.close()
    directory.cleanup()


def mapped_file(path, directory, *, dtype, shape, mode='r'):
    result = np.memmap(path, dtype=dtype, mode=mode, shape=shape)
    weakref.finalize(result, _retire, result._mmap, directory)
    return result


def allocate(shape, dtype=np.float64):
    if math.prod(shape) * np.dtype(dtype).itemsize <= RAM_BYTES:
        return np.empty(shape, dtype=dtype)
    directory = temporary_directory()
    try:
        if shutil.disk_usage(directory.name).free < math.prod(shape)*np.dtype(dtype).itemsize + RAM_BYTES:
            raise OSError('Insufficient scratch disk for mapped recording comparison')
        return mapped_file(Path(directory.name) / 'pcm.bin', directory, dtype=dtype, shape=shape, mode='w+')
    except BaseException:
        directory.cleanup()
        raise


def discard(array):
    """Drop mapped working pages after each window, retaining the exact file bytes."""
    value = array
    while value is not None:
        if isinstance(value, np.memmap):
            if value.mode != 'r':
                value.flush()
            if hasattr(value._mmap, 'madvise'):
                value._mmap.madvise(mmap.MADV_DONTNEED)
            return
        value = getattr(value, 'base', None)


def finite(array):
    for first in range(0, len(array), CHUNK_FRAMES):
        check_budget()
        if not np.isfinite(array[first:first+CHUNK_FRAMES]).all():
            return False
        discard(array)
    return True


def float64(array):
    if array.dtype == np.float64:
        return array
    result = allocate(array.shape)
    for first in range(0, len(array), CHUNK_FRAMES):
        check_budget()
        result[first:first+CHUNK_FRAMES] = array[first:first+CHUNK_FRAMES]
        discard(array)
        discard(result)
    return result


def mono(array):
    result = allocate((len(array),), dtype=array.dtype)
    for first in range(0, len(array), CHUNK_FRAMES):
        check_budget()
        result[first:first+CHUNK_FRAMES] = array[first:first+CHUNK_FRAMES].mean(axis=1)
        discard(array)
        discard(result)
    return result


def mean_square(array, axis=None):
    if len(array) <= CHUNK_FRAMES:
        return np.mean(array ** 2, axis=axis)
    total = np.zeros(array.shape[1:] if axis == 0 else (), dtype=np.float64)
    for first in range(0, len(array), CHUNK_FRAMES):
        check_budget()
        block = array[first:first+CHUNK_FRAMES]
        total += np.sum(block ** 2, axis=axis, dtype=np.float64)
        discard(array)
    return total / (len(array) if axis == 0 else array.size)


def channel_agreement(a, b):
    """None denotes mutual silence; it cannot supply active recording evidence."""
    a_energy, b_energy = float(np.mean(a ** 2)), float(np.mean(b ** 2))
    if max(a_energy, b_energy) < 1e-9:
        return None
    if min(a_energy, b_energy) < max(a_energy, b_energy) * 1e-4:
        return 0.0
    return correlation(a, b)


def correlation(a, b):
    if len(a) <= CHUNK_FRAMES:
        x, y = a-a.mean(), b-b.mean()
        denominator = np.linalg.norm(x) * np.linalg.norm(y)
        return float(np.dot(x, y) / denominator) if denominator > 1e-12 else 0.0
    means = []
    for values in (a, b):
        total = 0.0
        for first in range(0, len(values), CHUNK_FRAMES):
            check_budget()
            total += np.sum(values[first:first+CHUNK_FRAMES], dtype=np.float64)
            discard(values)
        means.append(total / len(values))
    cross = xx = yy = 0.0
    for first in range(0, len(a), CHUNK_FRAMES):
        check_budget()
        x = a[first:first+CHUNK_FRAMES] - means[0]
        y = b[first:first+CHUNK_FRAMES] - means[1]
        cross += np.dot(x, y)
        xx += np.dot(x, x)
        yy += np.dot(y, y)
        discard(a)
        discard(b)
    denominator = math.sqrt(xx * yy)
    return float(cross / denominator) if denominator > 1e-12 else 0.0


def resample(array, up, down):
    from scipy import signal
    factor = math.gcd(up, down)
    up, down = up//factor, down//factor
    if array.nbytes <= RAM_BYTES:
        return signal.resample_poly(array, up, down, axis=0)
    result = allocate(((len(array)*up+down-1)//down, *array.shape[1:]), dtype=array.dtype)
    step = max(down, CHUNK_FRAMES//down*down)
    halo = math.ceil((math.ceil(10*max(up, down)/up) + down)/down)*down
    for start in range(0, len(array), step):
        check_budget()
        end = min(start+step, len(array))
        first, last = max(0, start-halo), min(len(array), end+halo)
        block = signal.resample_poly(array[first:last], up, down, axis=0)
        output_first, output_last = start*up//down, (end*up+down-1)//down
        offset = (start-first)*up//down
        result[output_first:output_last] = block[offset:offset+output_last-output_first]
        discard(array)
        discard(result)
    return result


def convolve(array, kernel):
    from scipy import signal
    if array.nbytes <= RAM_BYTES:
        return signal.oaconvolve(array, kernel, mode='same', axes=0)
    result = allocate(array.shape, dtype=np.result_type(array, kernel))
    halo = len(kernel)//2
    for start in range(0, len(array), CHUNK_FRAMES):
        check_budget()
        end = min(start+CHUNK_FRAMES, len(array))
        first, last = max(0, start-halo), min(len(array), end+halo)
        block = signal.oaconvolve(array[first:last], kernel, mode='same', axes=0)
        result[start:end] = block[start-first:end-first]
        discard(array)
        discard(result)
    return result


def channel_map(array, matrix):
    result = allocate((len(array), matrix.shape[1]), dtype=np.result_type(array, matrix))
    for first in range(0, len(array), CHUNK_FRAMES):
        check_budget()
        result[first:first+CHUNK_FRAMES] = array[first:first+CHUNK_FRAMES] @ matrix
        discard(array)
        discard(result)
    return result


def stereo_filter(array, taps):
    from scipy import signal
    result = allocate(array.shape)
    halo = len(taps)//2
    for start in range(0, len(array), CHUNK_FRAMES):
        check_budget()
        end = min(start+CHUNK_FRAMES, len(array))
        first, last = max(0, start-halo), min(len(array), end+halo)
        block = np.zeros((last-first, 2), dtype=np.float64)
        for source in (0, 1):
            for target in (0, 1):
                block[:, target] += signal.convolve(array[first:last, source], taps[::-1, source, target], mode='same')
        result[start:end] = block[start-first:end-first]
        discard(array)
        discard(result)
    return result

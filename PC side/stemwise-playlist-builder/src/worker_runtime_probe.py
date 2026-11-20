"""Observe CUDA and runtime identity inside the selected numerical interpreter."""
import argparse
import json
import platform
import ctypes
import os
import sys
from importlib.metadata import version

from stemwise_engine.model_registry import cuda_device_identity, runtime_profile
from stemwise_engine.runtime_environment import environment_python, environment_lease
from stemwise_engine.common import WorkerRetirementError, WORKER_RETIREMENT_FAILURE_MARKER


def ctranslate2_runtime_identity(device):
    import ctranslate2
    value = {'ctranslate2': ctranslate2.__version__, 'device': device}
    if device == 'cpu':
        return value
    if device != 'cuda' or ctranslate2.get_cuda_device_count() < 1:
        raise RuntimeError('CTranslate2 CUDA producer is unavailable')
    try:
        driver = ctypes.CDLL('nvcuda.dll' if os.name == 'nt' else 'libcuda.so.1')
        def call(name, *arguments):
            if getattr(driver, name)(*arguments) != 0:
                raise RuntimeError('CTranslate2 CUDA producer observation failed: ' + name)
        call('cuInit', ctypes.c_uint(0))
        selected, major, minor, driver_version = (ctypes.c_int() for _ in range(4))
        memory = ctypes.c_size_t()
        name, uuid = ctypes.create_string_buffer(256), ctypes.create_string_buffer(16)
        # WhisperModel uses logical ordinal zero; the driver applies CUDA visibility.
        call('cuDeviceGet', ctypes.byref(selected), ctypes.c_int(0))
        call('cuDeviceGetName', name, ctypes.c_int(len(name)), selected)
        call('cuDeviceGetAttribute', ctypes.byref(major), ctypes.c_int(75), selected)
        call('cuDeviceGetAttribute', ctypes.byref(minor), ctypes.c_int(76), selected)
        call('cuDeviceTotalMem_v2', ctypes.byref(memory), selected)
        call('cuDeviceGetUuid_v2', uuid, selected)
        call('cuDriverGetVersion', ctypes.byref(driver_version))
    except (OSError, AttributeError) as error:
        raise RuntimeError('CTranslate2 CUDA producer identity could not be observed') from error
    value.update(cudaDevice={'name': name.value.decode('utf-8'), 'capability': [major.value, minor.value],
        'totalMemoryBytes': memory.value, 'uuid': uuid.raw.hex()}, cudaDriver=driver_version.value,
        libraries={package: version(package) for package in
            ('nvidia-cublas-cu12', 'nvidia-cudnn-cu12', 'nvidia-cuda-nvrtc-cu12')})
    return value


def torch_runtime_identity():
    import torch
    if not torch.cuda.is_available():
        raise RuntimeError('Torch CUDA producer is unavailable')
    selected = torch.cuda.current_device()
    properties = torch.cuda.get_device_properties(selected)
    driver_version = ctypes.c_int()
    try:
        driver = ctypes.CDLL('nvcuda.dll' if os.name == 'nt' else 'libcuda.so.1')
        if driver.cuDriverGetVersion(ctypes.byref(driver_version)) != 0:
            raise RuntimeError('Torch CUDA driver identity could not be observed')
    except (OSError, AttributeError) as error:
        raise RuntimeError('Torch CUDA driver identity could not be observed') from error
    uuid = str(properties.uuid)
    return {'device': 'cuda', 'torch': str(torch.__version__), 'cudaDriver': driver_version.value,
        'cudaRuntime': str(torch.version.cuda), 'cudnn': str(torch.backends.cudnn.version()),
        'uuid': uuid if uuid.startswith('GPU-') else 'GPU-' + uuid, 'name': properties.name,
        'capability': list(torch.cuda.get_device_capability(selected)), 'totalMemoryBytes': properties.total_memory}


def selected_torch_runtime(project, timeout_seconds):
    from stemwise_engine.common import run_process_tree
    from stemwise_engine.worker_protocol import parse_worker_json
    with environment_lease('main', builder=project):
        completed = run_process_tree([str(environment_python('main', project)), '-B', '-m',
            'stemwise_engine.worker_runtime_probe', '--torch-runtime'], capture_output=True,
            text=True, check=False, timeout=timeout_seconds, env=os.environ.copy())
    if completed.returncode:
        if WORKER_RETIREMENT_FAILURE_MARKER in completed.stderr.splitlines():
            failure = WorkerRetirementError(WORKER_RETIREMENT_FAILURE_MARKER)
            failure.stderr = completed.stderr
            raise failure
        raise RuntimeError('Selected Torch CUDA producer identity could not be observed')
    value = parse_worker_json(completed.stdout)
    if (not isinstance(value, dict) or value.get('device') != 'cuda'
            or not isinstance(value.get('uuid'), str) or not value['uuid'].startswith('GPU-')):
        raise RuntimeError('Selected Torch CUDA producer identity is malformed')
    return value


def cuda_runtime_identity(profile):
    import torch
    pins = runtime_profile(profile)
    value = {'profileId': pins['profileId'], 'python': platform.python_version(),
        'torch': torch.__version__, 'cudaRuntime': torch.version.cuda,
        'cudaDevice': cuda_device_identity(profile, torch_module=torch)}
    if (value['python'] != pins['PYTHON_VERSION'] or value['torch'] != pins['TORCH_VERSION']
            or value['cudaRuntime'] != pins['CUDA_VERSION']):
        raise RuntimeError('CUDA probe runtime differs from exact dependency pins')
    return value


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--cuda-profile')
    mode.add_argument('--torch-runtime', action='store_true')
    arguments = parser.parse_args()
    try:
        print(json.dumps(torch_runtime_identity() if arguments.torch_runtime
            else cuda_runtime_identity(arguments.cuda_profile), sort_keys=True))
    except WorkerRetirementError:
        print(WORKER_RETIREMENT_FAILURE_MARKER, file=sys.stderr, flush=True)
        raise

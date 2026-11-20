"""Isolated runtime capability/metadata probes; never installs or runs inference."""
from __future__ import annotations
import argparse
import importlib.metadata
import json
import platform
import sys
from typing import Any


def package_identity(name: str) -> dict[str, Any]:
    try:
        distribution = importlib.metadata.distribution(name)
    except importlib.metadata.PackageNotFoundError:
        return {'available': False}
    record: dict[str, Any] = {'available': True, 'version': distribution.version}
    try:
        raw = distribution.read_text('direct_url.json')
        if raw:
            vcs = json.loads(raw).get('vcs_info') or {}
            source = {key: value for key, value in {
                'vcs': vcs.get('vcs'), 'commit': vcs.get('commit_id'),
                'requestedRevision': vcs.get('requested_revision')}.items() if value}
            if source:
                record['source'] = source
    except Exception as error:
        record['sourceReadError'] = type(error).__name__
    return record


def packages(names: list[str]) -> dict[str, Any]:
    return {'python': sys.version.split()[0], 'platform': platform.platform(),
            'packages': {name: package_identity(name) for name in names}}


def allin1_capability() -> dict[str, Any]:
    import torch
    import natten
    import allin1
    return {'allin1': importlib.metadata.version('allin1'),
            'natten': importlib.metadata.version('natten'), 'torch': torch.__version__,
            'cuda': torch.cuda.is_available(), 'hasLibnatten': getattr(natten, 'HAS_LIBNATTEN', None)}


def readiness(name):
    import importlib
    modules = {
        'main': ('numpy', 'soundfile', 'torch', 'torchaudio', 'scipy', 'soxr', 'beat_this',
                 'skey', 'essentia', 'transformers', 'phonemizer', 'mel_band_roformer'),
        'allin1': ('numpy', 'soundfile', 'torch', 'torchaudio', 'natten', 'allin1', 'demucs', 'madmom'),
        'lyrics': ('numpy', 'soundfile', 'ctranslate2', 'faster_whisper', 'syncalong', 'av', 'PIL'),
    }
    if name not in modules:
        raise ValueError('Unsupported environment readiness probe')
    for module in modules[name]:
        importlib.import_module(module)
    if name == 'main':
        import torch
        if not torch.cuda.is_available():
            raise RuntimeError('Main environment needs an available CUDA runtime')
        torch.empty(1, device='cuda')
        torch.cuda.synchronize()
    elif name == 'lyrics':
        import ctranslate2
        if ctranslate2.get_cuda_device_count() < 1 or not ctranslate2.get_supported_compute_types('cuda'):
            raise RuntimeError('Lyrics environment needs an available CUDA runtime')
    return {'environment': name, 'imported': list(modules[name])}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('probe', choices=('packages', 'allin1', 'phonemizer', 'readiness'))
    parser.add_argument('packages', nargs='*')
    args = parser.parse_args(argv)
    if args.probe == 'packages':
        value = packages(args.packages)
    elif args.probe == 'readiness':
        if len(args.packages) != 1:
            parser.error('readiness needs one environment')
        value = readiness(args.packages[0])
    elif args.probe == 'allin1':
        value = allin1_capability()
    else:
        from phonemizer.backend import EspeakBackend
        value = sorted(EspeakBackend.supported_languages())
    print(json.dumps(value, separators=(',', ':')))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

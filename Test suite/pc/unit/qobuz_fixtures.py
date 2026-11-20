"""Complete synthetic retained FLAC identities for comparison/admission fixtures."""
import hashlib
import json
import io
from pathlib import Path


def flac_bytes(seed=0):
    import numpy as np
    import soundfile as sf
    output = io.BytesIO()
    sf.write(output, np.linspace(-.2, .2, 480) + seed*.01, 48000, format='FLAC', subtype='PCM_16')
    return output.getvalue()


def write_download(base: Path, metadata: dict) -> None:
    metadata = dict(metadata)
    if 'qobuzTrackId' in metadata and metadata.get('status') != 'owner_rejected':
        audio = base / metadata['file']
        if audio.is_file():
            import numpy as np
            import soundfile as sf
            if audio.suffix == '.wav':
                samples, rate = (sf.read(audio, dtype='int16', always_2d=True)
                    if audio.read_bytes().startswith(b'RIFF') else (np.zeros((480, 2), dtype='int16'), 48000))
                actual = audio.with_suffix('.flac')
                sf.write(actual, samples, rate, format='FLAC', subtype='PCM_16')
                audio.unlink()
                audio = actual
            elif not audio.read_bytes().startswith(b'fLaC'):
                sf.write(audio, np.zeros((480, 2)), 48000, format='FLAC', subtype='PCM_16')
            from stemwise_media.save_download import require_decodable_flac
            require_decodable_flac(audio)
            metadata.update(file=audio.name, bytes=audio.stat().st_size,
                            sha256=hashlib.sha256(audio.read_bytes()).hexdigest())
    (base / 'download.json').write_text(json.dumps(metadata), encoding='utf-8')


def inline_http_worker(test):
    """Keep byte/publication fixtures local; separate socket tests exercise process deadlines."""
    from unittest.mock import patch
    from stemwise_engine.provider_operations import ProviderBudget
    from stemwise_media.transfer_budget import http_worker
    def run(budget, arguments, *, phase_seconds=None):
        budget.remaining()
        if arguments[0] != '--qobuz-http':
            raise AssertionError('Unexpected provider stage in HTTP fixture')
        result = http_worker(json.loads(arguments[1]))
        budget.remaining()
        return result
    replacement = patch.object(ProviderBudget, 'run', run)
    replacement.start()
    test.addCleanup(replacement.stop)

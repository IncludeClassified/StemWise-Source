"""Local, body-free signal views for candidate-only endpoint review.

These images are evidence, not a vocal detector or an automatic family vote.
No audio, text, timestamps, or workflow state are edited by this module.
"""

from __future__ import annotations

import hashlib
import io
import re
from pathlib import Path

import numpy as np
import PIL
import soundfile as sf
from PIL import Image, ImageDraw, ImageFont

from stemwise_engine.common import atomic_write_bytes, json_bytes, read_bounded_regular_bytes, sha256_file, validate_generated_path


METHOD = "local-signal-view-v1"
WIDTH, HEIGHT = 1800, 800
LEFT, RIGHT = 100, 1760
CONTEXT_MS, PAGE_MS = 2000, 10000
COLORS = ("#db4e38", "#1167ba", "#24833b", "#aa36ad")


def _pages(declaration: dict):
    duration = declaration["analysisAudio"]["durationMs"]
    for occurrence in declaration["occurrences"]:
        for boundary in ("startMs", "endMs"):
            candidates = occurrence["candidateFamilies"]
            times = [candidate[boundary] for candidate in candidates.values()]
            first, last = max(0, min(times) - CONTEXT_MS), min(duration, max(times) + CONTEXT_MS)
            for start in range(first, last, PAGE_MS):
                yield occurrence, boundary, start, min(last, start + PAGE_MS)


def _render(samples: np.ndarray, rate: int, occurrence: dict, boundary: str, start: int, end: int, *, source_label: str = "Exact retained mix. Signal inspection only; instruments/reverb can obscure a vocal boundary.") -> bytes:
    """Per-channel peak envelope and mean channel power; never downmix phase."""
    canvas = Image.new("RGB", (WIDTH, HEIGHT), "white")
    draw = ImageDraw.Draw(canvas)
    font = ImageFont.load_default(size=19)
    small = ImageFont.load_default(size=16)
    draw.text((LEFT, 12), f"Occurrence {occurrence['performedIndex']} | {boundary} | {start}-{end} ms", fill="black", font=font)
    draw.text((LEFT, 40), source_label, fill="black", font=small)
    plot_width = RIGHT - LEFT
    x = lambda ms: LEFT + (ms - start) * plot_width / (end - start)

    # A peak per pixel preserves short events and opposite-phase stereo audio.
    edges = np.linspace(0, len(samples), plot_width + 1).astype(int)
    peak = np.max(np.abs(samples), axis=1)
    envelope = np.array([peak[min(a, len(peak) - 1):max(a + 1, b)].max() for a, b in zip(edges, edges[1:])])
    db = np.maximum(-100.0, 20 * np.log10(np.maximum(envelope, 1e-5)))
    for value in (0, -20, -40, -60, -80, -100):
        y = 230 - (value + 100) * 1.4
        draw.line((LEFT, y, RIGHT, y), fill="#dddddd")
        draw.text((10, y - 8), str(value), fill="black", font=small)
    draw.text((10, 65), "dBFS peak", fill="black", font=small)
    draw.line([(LEFT + i, 230 - (float(value) + 100) * 1.4) for i, value in enumerate(db)], fill="#152c45", width=2)

    # 20 ms Hann windows, 5 ms hop; mean channel power avoids stereo cancellation.
    size, hop = max(16, round(rate * .020)), max(1, round(rate * .005))
    padded = np.pad(samples, ((size // 2, size // 2), (0, 0)))
    frames = np.lib.stride_tricks.sliding_window_view(padded, size, axis=0)[::hop]
    window = np.hanning(size)
    power = np.mean(np.abs(np.fft.rfft(frames * window, axis=-1)) ** 2, axis=1)
    power /= max(float(window.sum() ** 2), 1.0)
    frequencies = np.fft.rfftfreq(size, 1 / rate)
    limit = min(8000, rate / 2)
    spectrum = 10 * np.log10(np.maximum(power[:, frequencies <= limit], 1e-10))
    gray = np.uint8(np.clip((spectrum + 100) / 100, 0, 1) * 255)
    image = Image.fromarray(gray.T[::-1]).resize((plot_width, 320), Image.Resampling.NEAREST)
    canvas.paste(image.convert("RGB"), (LEFT, 285))
    draw.text((LEFT, 255), "Spectrogram: black -100 dBFS, white 0 dBFS | 20 ms window / 5 ms hop", fill="black", font=small)
    for fraction in (0, .25, .5, .75, 1):
        draw.text((10, 597 - fraction * 320), f"{round(limit * fraction)} Hz", fill="black", font=small)
    for tick in np.linspace(start, end, 6):
        px = x(tick)
        draw.line((px, 605, px, 613), fill="black")
        draw.text((px - 35, 618), f"{tick:.0f}", fill="black", font=small)
    draw.text((LEFT, 645), "Source position (milliseconds); markers are unchanged declared candidates:", fill="black", font=small)
    for index, (family, candidate) in enumerate(sorted(occurrence["candidateFamilies"].items())):
        color, time = COLORS[index % len(COLORS)], candidate[boundary]
        draw.text((LEFT, 672 + index * 26), f"{family}: {time} ms", fill=color, font=small)
        if start <= time <= end:
            px = x(time)
            draw.line((px, 80, px, 610), fill=color, width=2)
    output = io.BytesIO()
    canvas.save(output, format="PNG")
    return output.getvalue()


def packet(declaration: dict, declaration_sha256: str, audio_path: Path):
    """Yield deterministic images, then a body-free manifest; verify source twice."""
    identity = declaration["analysisAudio"]
    validate_generated_path(audio_path.parent, audio_path)
    if audio_path.stat().st_size != identity["byteCount"] or sha256_file(audio_path) != identity["sha256"]:
        raise ValueError("endpoint audio evidence source is stale")
    records = []
    with sf.SoundFile(audio_path) as source:
        if source.channels not in (1, 2) or abs(round(len(source) * 1000 / source.samplerate) - identity["durationMs"]) > 1:
            raise ValueError("endpoint audio evidence PCM identity is invalid")
        rate = source.samplerate
        for index, (occurrence, boundary, start, end) in enumerate(_pages(declaration)):
            first, last = round(start * rate / 1000), min(len(source), round(end * rate / 1000))
            source.seek(first)
            samples = source.read(last - first, dtype="float32", always_2d=True)
            if not len(samples) or not np.isfinite(samples).all():
                raise ValueError("endpoint audio evidence PCM is invalid")
            name = f"{index:04d}-{occurrence['performedIndex']}-{boundary}.png"
            raw = _render(samples, rate, occurrence, boundary, start, end)
            records.append({"file": name, "sha256": hashlib.sha256(raw).hexdigest(),
                            "occurrenceId": occurrence["occurrenceId"], "performedIndex": occurrence["performedIndex"],
                            "candidateFamilies": occurrence["candidateFamilies"], "boundary": boundary,
                            "startMs": start, "endMs": end})
            yield name, raw
    if sha256_file(audio_path) != identity["sha256"]:
        raise ValueError("endpoint audio evidence source changed during rendering")
    yield "manifest.json", json_bytes({
        "method": METHOD, "declarationSha256": declaration_sha256,
        "analysisAudio": identity, "rendererSha256": sha256_file(Path(__file__)),
        "runtime": {"numpy": np.__version__, "soundfile": sf.__version__, "pillow": PIL.__version__},
        "pages": records,
    })


def evidence_directory(attempt: Path) -> Path:
    # A sibling packet never changes the immutable endpoint attempt's manifest.
    return attempt.parent.parent / "endpoint-audio-evidence" / attempt.name


def write_packet(attempt: Path, declaration: dict, declaration_sha256: str, audio_path: Path) -> dict:
    directory = evidence_directory(attempt)
    validate_generated_path(attempt.parent.parent, directory)
    directory.mkdir(parents=True, exist_ok=True)
    manifest = directory / 'manifest.json'
    if manifest.exists():
        binding = {'method': METHOD, 'manifestSha256': sha256_file(manifest)}
        verify_packet(attempt, declaration, declaration_sha256, audio_path, binding)
        import json
        stored = json.loads(read_bounded_regular_bytes(manifest, max_bytes=16*1024*1024, root=directory))
        return {**binding, 'files': [str(directory / p['file']) for p in stored['pages']] + [str(manifest)]}
    files = []
    for name, raw in packet(declaration, declaration_sha256, audio_path):
        path = validate_generated_path(directory, directory / name)
        if not path.exists() or read_bounded_regular_bytes(path, max_bytes=16 * 1024 * 1024, root=directory) != raw:
            atomic_write_bytes(path, raw, root=directory)
        files.append(str(path))
    return {"method": METHOD, "manifestSha256": sha256_file(directory / "manifest.json"), "files": files}


def verify_stored_packet(attempt: Path, binding: dict) -> None:
    """Retain verifiable image evidence even after normal source-PCM cleanup."""
    import json

    if not isinstance(binding, dict) or set(binding) != {"method", "manifestSha256"} or binding.get("method") != METHOD:
        raise ValueError("endpoint audio evidence binding is invalid")
    directory = evidence_directory(attempt)
    validate_generated_path(attempt.parent.parent, directory)
    manifest = read_bounded_regular_bytes(directory / "manifest.json", max_bytes=16 * 1024 * 1024, root=directory)
    if hashlib.sha256(manifest).hexdigest() != binding["manifestSha256"]:
        raise ValueError("endpoint audio evidence manifest is stale")
    document = json.loads(manifest)
    if not isinstance(document, dict) or document.get("method") != METHOD or not isinstance(document.get("pages"), list) or not document["pages"]:
        raise ValueError("endpoint audio evidence manifest is invalid")
    for page in document["pages"]:
        if not isinstance(page, dict):
            raise ValueError("endpoint audio evidence page is invalid")
        name = page.get("file")
        if not isinstance(name, str) or re.fullmatch(r"\d+-\d+-(?:startMs|endMs)\.png", name) is None:
            raise ValueError("endpoint audio evidence page path is invalid")
        raw = read_bounded_regular_bytes(directory / name, max_bytes=16 * 1024 * 1024, root=directory)
        if hashlib.sha256(raw).hexdigest() != page.get("sha256"):
            raise ValueError("endpoint audio evidence image is stale")


def verify_packet(attempt: Path, declaration: dict, declaration_sha256: str, audio_path: Path, binding: dict, *, replay=False) -> None:
    verify_stored_packet(attempt, binding)
    directory = evidence_directory(attempt)
    import json
    manifest = json.loads(read_bounded_regular_bytes(directory / 'manifest.json', max_bytes=16*1024*1024, root=directory))
    if (manifest.get('declarationSha256') != declaration_sha256
            or manifest.get('analysisAudio') != declaration['analysisAudio']
            or manifest.get('rendererSha256') != sha256_file(Path(__file__))
            or manifest.get('runtime') != {'numpy': np.__version__, 'soundfile': sf.__version__, 'pillow': PIL.__version__}):
        raise ValueError('endpoint audio evidence producer or authority changed')
    identity = declaration['analysisAudio']
    if audio_path.stat().st_size != identity['byteCount'] or sha256_file(audio_path) != identity['sha256']:
        raise ValueError('endpoint audio evidence source is stale')
    expected = [{'file': f'{index:04d}-{occurrence["performedIndex"]}-{boundary}.png',
        'occurrenceId': occurrence['occurrenceId'], 'performedIndex': occurrence['performedIndex'],
        'candidateFamilies': occurrence['candidateFamilies'], 'boundary': boundary, 'startMs': start, 'endMs': end}
        for index, (occurrence, boundary, start, end) in enumerate(_pages(declaration))]
    if len(expected) != len(manifest['pages']) or any(
            {key: page.get(key) for key in wanted} != wanted for page, wanted in zip(manifest['pages'], expected)):
        raise ValueError('endpoint audio evidence page declaration changed')
    for page in manifest['pages']:
        raw = read_bounded_regular_bytes(directory / page['file'], max_bytes=16*1024*1024, root=directory)
        try:
            with Image.open(io.BytesIO(raw)) as image:
                if image.format != 'PNG' or image.size != (WIDTH, HEIGHT):
                    raise ValueError('unexpected signal image dimensions')
                image.verify()
        except (OSError, ValueError) as error:
            raise ValueError('endpoint audio evidence image is invalid') from error
    if replay:
        for name, expected in packet(declaration, declaration_sha256, audio_path):
            if read_bounded_regular_bytes(directory / name, max_bytes=16*1024*1024, root=directory) != expected:
                raise ValueError('endpoint audio evidence does not match the bound signal')

"""Rebuild Remaining and manifests from committed parts and canonical Android PCM.

Install the current Development/test APKs on a verified disposable target and directly select
DemoSourceNativeDecodeInstrumentationTest#decodeCurrentDemoOriginalsForFixtureGeneration.
Before uninstalling, read its six demo-original48 files through the exact shell-granted
GoldenQaDocumentsProvider document URIs into Test suite/work. Connected-test runs uninstall
their target, so they cannot export these inputs after returning. Uninstall both packages
after transfer. Pass the captured directory with --native-original-root for generation
and --check. FFmpeg must never substitute for the playback-native Original48 operand.
"""
import argparse
import shutil
import tempfile
import hashlib
import json
import os
from pathlib import Path
import subprocess

import numpy as np

PROJECT = Path(__file__).resolve().parents[3]
ROOT = PROJECT / "StemWise/app/src/development/assets/demo_library"
FFMPEG = os.environ.get("FFMPEG_BINARY", "ffmpeg")
FFPROBE = os.environ.get("FFPROBE_BINARY", "ffprobe")
PARTS = [
    ("lead-vocals", "Lead vocals"),
    ("backing-vocals", "Backing vocals"),
    ("drums", "All drums"),
    ("bass", "Bass"),
    ("acoustic-guitar", "Acoustic guitar"),
    ("electric-guitar", "Electric guitar"),
    ("harp", "Harp"),
    ("mandolin", "Mandolin"),
]
REMAINING_PRODUCER = "stemwise-native-original48-v1"


def native_original(folder, native_original_root):
    if native_original_root is None:
        raise ValueError("--native-original-root is required; capture canonical Android Original48 PCM")
    root = Path(native_original_root).resolve()
    if not root.is_relative_to(PROJECT):
        raise ValueError("Native fixture inputs must remain in the project folder")
    metadata = json.loads((root / f"{folder.name}.json").read_bytes())
    source_hash = sha((folder / "original.opus").read_bytes())
    if metadata != dict(producer=REMAINING_PRODUCER, sourceSha256=source_hash,
                        sampleRateHz=48000, channels=2, audibleFrames=576000):
        raise ValueError("Canonical native operand differs from this exact demo Original")
    payload = (root / f"{folder.name}.f32").read_bytes()
    if len(payload) != metadata["audibleFrames"] * 8:
        raise ValueError("Canonical native operand has a partial or extra frame")
    result = np.frombuffer(payload, dtype="<f4").reshape(-1, 2)
    if not np.isfinite(result).all():
        raise ValueError("Canonical native operand contains non-finite PCM")
    return result



def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def decode(path):
    streams = json.loads(subprocess.check_output([
        FFPROBE, "-v", "error", "-show_entries",
        "stream=codec_name,sample_rate,channels,bits_per_raw_sample,sample_fmt", "-of", "json", str(path),
    ], timeout=60))["streams"]
    remaining = path.suffix == ".wv"
    if (len(streams) != 1 or streams[0].get("codec_name") != ("wavpack" if remaining else "flac") or
            streams[0].get("sample_rate") != "48000" or streams[0].get("channels") != 2 or
            streams[0].get("bits_per_raw_sample") != ("32" if remaining else "16") or
            remaining and streams[0].get("sample_fmt") != "fltp"):
        raise ValueError("Fixture audio must be stereo 48 kHz FLAC16 or Float32 WavPack")
    pcm = subprocess.check_output([
        FFMPEG, "-v", "error", "-i", str(path), "-map", "0:a:0",
        "-f", "f32le", "-",
    ], timeout=60)
    if not pcm or len(pcm) % 8:
        raise ValueError("Fixture has an empty or partial stereo frame")
    decoded = np.frombuffer(pcm, dtype="<f4").reshape(-1, 2)
    if not np.isfinite(decoded).all():
        raise ValueError("Fixture contains non-finite PCM")
    return decoded


def encode(command, payload):
    print("ffmpeg encode: " + json.dumps(command))
    subprocess.run(command, input=payload, check=True, timeout=60)


def generate(folder, native_original_root=None):
    original = native_original(folder, native_original_root)
    frames = len(original)
    remaining = original.astype(np.float64)
    mixed = np.zeros(original.shape, dtype=np.float64)
    for index, _ in enumerate(PARTS, 1):
        name = f"part-{index}.flac"
        part = decode(folder / name)
        if part.shape != original.shape:
            raise ValueError("Fixture lane frame/PCM contract differs")
        # Match the production aeval Original-part1-...-part8 double subtraction.
        remaining -= part
        mixed += part
    remaining = remaining.astype("<f4")
    payload = remaining.tobytes()
    encode([
        FFMPEG, "-v", "error", "-y", "-f", "f32le", "-ar", "48000", "-ac", "2",
        "-i", "-", "-c:a", "wavpack", "-sample_fmt", "fltp", "-compression_level", "0",
        "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact",
        str(folder / "remaining.wv"),
    ], payload)
    if decode(folder / "remaining.wv").tobytes() != payload:
        raise ValueError("Remaining encoding changed Float32 PCM")
    if np.max(np.abs(original - (mixed + remaining))) >= 1e-6:
        raise ValueError("Demo lane reconstruction failed")
    (folder / "sources.json").write_bytes(canonical(source_manifest(folder, frames)))
    print(f"{folder.name}: {len(PARTS) + 1} lanes, {frames} frames")


def source_manifest(folder, frames):
    lanes = [(source_id, label, f"part-{index}.flac")
             for index, (source_id, label) in enumerate(PARTS, 1)] + [("remaining", "Remaining", "remaining.wv")]
    selected = [p[0] for p in PARTS]
    manifest = dict(schemaVersion=3, kind="stemwise.custom-sources", videoId=folder.name,
                    sourceSha256=sha((folder / "original.opus").read_bytes()),
                    planSha256=sha(canonical(selected)), sampleRateHz=48000, channels=2,
                    audibleFrames=frames, selectedSourceIds=selected, lanes=[])
    for source_id, label, name in lanes:
        data = (folder / name).read_bytes()
        lane = dict(sourceId=source_id, label=label, fileName=name,
                    sha256=sha(data), byteLength=len(data), audibleFrames=frames)
        if source_id == "remaining":
            lane["remainingProducer"] = REMAINING_PRODUCER
        manifest["lanes"].append(lane)
    manifest["integritySha256"] = sha(canonical(manifest))
    return manifest


def encoder_payload():
    indices = np.arange(48017 * 2, dtype=np.uint32)
    bits = ((indices * np.uint32(2654435761)) & 0x7fffff) | ((112 + indices % 20) << 23) | ((indices & 1) << 31)
    bits[:10] = [0, 0x80000000, 0x3fc00000, 0xc0300000, 1, 0x80000001,
                 0x0d800000, 0x8d800000, 0x3f7fffff, 0x477fff00]
    return bits.astype("<u4").tobytes()


def check_encoder(target):
    if decode(target).tobytes() != encoder_payload():
        raise ValueError("Encoder fixture changed independent raw-bit oracle")


def generate_encoder(target):
    payload = encoder_payload()
    encode([FFMPEG, "-v", "error", "-y", "-f", "f32le", "-ar", "48000", "-ac", "2",
                    "-i", "-", "-c:a", "wavpack", "-sample_fmt", "fltp", "-compression_level", "0", "-map_metadata", "-1",
                    "-fflags", "+bitexact", "-flags:a", "+bitexact", str(target)], payload)
    if decode(target).tobytes() != payload:
        raise ValueError("Encoder fixture changed independent raw-bit oracle")


def check_folder(folder, native_original_root=None):
    raw = (folder / 'sources.json').read_bytes()
    manifest = json.loads(raw)
    if len(raw) > 16 * 1024 or raw != canonical(manifest):
        raise ValueError('Fixture manifest must use exact canonical JSON bytes')
    original = native_original(folder, native_original_root)
    if raw != canonical(source_manifest(folder, len(original))):
        raise ValueError('Fixture manifest declaration/source integrity differs')
    mixed = np.zeros_like(original,dtype=np.float64)
    for lane in manifest['lanes']:
        path = folder/lane['fileName']
        if path.stat().st_size != lane['byteLength'] or sha(path.read_bytes()) != lane['sha256']:
            raise ValueError('Fixture lane integrity differs')
        decoded = decode(path)
        if len(decoded) != manifest['audibleFrames'] or decoded.shape != original.shape or not np.isfinite(decoded).all():
            raise ValueError('Fixture lane frame/PCM contract differs')
        mixed += decoded
    if not np.isfinite(original).all() or np.max(np.abs(original-mixed)) >= 1e-6:
        raise ValueError('Fixture reconstruction differs')


def rebuild(output_root, check_only=False, native_original_root=None):
    output_root = Path(output_root).resolve()
    if not output_root.is_relative_to(PROJECT):
        raise ValueError('Fixture output must remain in the project folder')
    library = output_root/'StemWise/app/src/development/assets/demo_library'
    names = ('DEMOUI00001','DEMOUI00002','DEMOUI00003')
    if check_only:
        for name in names:
            check_folder(library/name, native_original_root)
        check_encoder(output_root/'Test suite/android/instrumentation/assets/encoder/remaining-float32.wv')
        return
    work = PROJECT/'Test suite/work'
    work.mkdir(exist_ok=True)
    directory = tempfile.mkdtemp(dir=work, prefix='demo-source-generation-')
    preserve_staging = False
    try:
        staging = Path(directory)
        writes = []
        for name in names:
            folder = staging/name
            shutil.copytree(library/name,folder)
            generate(folder, native_original_root)
            check_folder(folder, native_original_root)
            writes.extend((folder/file,library/name/file) for file in ['remaining.wv','sources.json'])
        encoder = staging/'remaining-float32.wv'
        generate_encoder(encoder)
        writes.append((encoder,output_root/'Test suite/android/instrumentation/assets/encoder/remaining-float32.wv'))
        backups = staging/'backups'
        backups.mkdir()
        published = []
        try:
            for index,(source,destination) in enumerate(writes):
                backup = backups/str(index)
                existed = destination.exists()
                if existed:
                    shutil.copy2(destination,backup)
                published.append((destination,backup,existed))
                destination.parent.mkdir(parents=True,exist_ok=True)
                os.replace(source,destination)
        except BaseException as error:
            rollback_errors = []
            for destination,backup,existed in reversed(published):
                try:
                    if existed:
                        os.replace(backup,destination)
                    else:
                        destination.unlink(missing_ok=True)
                except BaseException as rollback_error:
                    rollback_errors.append(rollback_error)
            if rollback_errors:
                preserve_staging = True
                failure = RuntimeError('Fixture rollback incomplete; recovery files retained at ' + str(staging) +
                                       '; rollback errors: ' + '; '.join(str(e) for e in rollback_errors))
                failure.rollback_errors = tuple(rollback_errors)
                raise failure from error
            raise
    finally:
        if not preserve_staging:
            shutil.rmtree(directory)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check',action='store_true')
    parser.add_argument('--native-original-root', type=Path, required=True)
    parser.add_argument('--output-root',type=Path,default=PROJECT)
    args = parser.parse_args()
    version = subprocess.check_output([FFMPEG,'-version'],text=True,timeout=10).splitlines()[0]
    print(version)
    rebuild(args.output_root,args.check,args.native_original_root)


if __name__ == '__main__':
    main()

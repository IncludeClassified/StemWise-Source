"""Save observed Qobuz jobs; interruption cancels queued transfers and joins active ones."""
import argparse
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from threading import Event
from pathlib import Path

ROOT = Path(__file__).resolve().parent
from stemwise_engine.common import (atomic_write_json, durable_replace, exclusive_process_lock,
                    read_bounded_json, WorkerRetirementError)
from stemwise_engine.common import parse_strict_json_bytes
from stemwise_engine.enrichment_constants import MAX_RECORDING_TRACK_ID
from stemwise_media.transfer_budget import TransferBudget, http_request, hash_bounded

HEADERS = {"User-Agent": "Mozilla/5.0", "Referer": "https://eu.doubledouble.top/"}


def require_decodable_flac(audio, *, budget=None):
    import soundfile as sf
    try:
        if budget is not None: budget.remaining()
        with sf.SoundFile(audio) as source:
            if budget is not None: budget.remaining()
            if source.format != 'FLAC' or source.frames <= 0:
                raise ValueError('Retained FLAC must contain decoded samples')
            decoded = 0
            for block in source.blocks(blocksize=65536, dtype='int32'):
                if budget is not None: budget.remaining()
                decoded += len(block)
            if decoded != source.frames:
                raise ValueError('Retained FLAC decoding ended before its declared samples')
            if budget is not None: budget.remaining()
    except sf.SoundFileError as error:
        raise ValueError('Retained FLAC cannot be decoded; preserve its bytes for repair') from error


def validate_retained_download(base, track_id, metadata=None, *, verify_digest=True, allow_missing=False, budget=None):
    """Admit retained bytes only against their durable download identity."""
    budget = budget or TransferBudget(time.monotonic()+900)
    budget.remaining()
    value = metadata if metadata is not None else read_bounded_json(
        base / 'download.json', max_bytes=64 * 1024, root=base)
    if not isinstance(value, dict) or value.get('status') == 'owner_rejected':
        raise ValueError('The owner rejected this Qobuz track or its metadata is invalid')
    name = value.get('file')
    if (type(value.get('qobuzTrackId')) is not int or value['qobuzTrackId'] != track_id
            or not isinstance(name, str) or Path(name).name != name
            or not name.lower().endswith('.flac')
            or any(c in name for c in '<>:"/\\|?*') or any(ord(c) < 32 for c in name)):
        raise ValueError('Invalid retained Qobuz download binding')
    if (type(value.get('bytes')) is not int or value['bytes'] <= 0
            or not isinstance(value.get('sha256'), str)
            or re.fullmatch('[0-9a-f]{64}', value['sha256']) is None):
        raise ValueError('Retained download requires content verification; preserve its audio and metadata')
    audio = base / name
    if allow_missing and not audio.is_symlink() and not audio.exists():
        return None
    if audio.is_symlink() or not audio.is_file() or audio.stat().st_size != value['bytes']:
        raise ValueError('Retained download audio is missing or truncated; preserve it for recovery')
    with audio.open('rb') as stream:
        if stream.read(4) != b'fLaC':
            raise ValueError('Retained download is not FLAC')
    if verify_digest and hash_bounded(audio, budget) != value['sha256']:
        raise ValueError('Retained download digest differs; preserve it for recovery')
    if verify_digest:
        require_decodable_flac(audio, budget=budget)
    budget.remaining()
    return audio


def download_file(url, partial, *, budget=None):
    """Retain received bytes and resume a truncated response at its exact offset."""
    budget = budget or TransferBudget(time.monotonic()+900)
    http_request(url, budget, headers=HEADERS, partial=partial)


def save_download(track_id, job_url, *, budget=None):
    budget = budget or TransferBudget(time.monotonic()+900)
    budget.remaining()
    if not re.fullmatch(r"https://eu\.doubledouble\.top/dl/[A-Za-z0-9]+", job_url):
        raise ValueError("Use the completed job URL observed in the browser")
    track_id = int(track_id)
    if not 0 < track_id <= MAX_RECORDING_TRACK_ID:
        raise ValueError("Invalid Qobuz track ID")
    base = ROOT / "audio" / "qobuz" / str(track_id)
    base.mkdir(parents=True, exist_ok=True)
    with exclusive_process_lock(base / 'download.lock'):
        return _save_download(track_id, job_url, base, budget=budget)


def _finish_transfer(base, track_id, job_url, *, existing_metadata=None, budget=None):
    from stemwise_media.transfer_budget import hash_bounded
    budget = budget or TransferBudget(time.monotonic()+900)
    path = base / 'download-transfer.json'
    transfer = read_bounded_json(path, max_bytes=64 * 1024, root=base)
    if (not isinstance(transfer, dict) or set(transfer) != {
            'formatVersion', 'qobuzTrackId', 'jobUrl', 'sha256', 'metadata'}
            or type(transfer['formatVersion']) is not int or transfer['formatVersion'] != 1
            or type(transfer['qobuzTrackId']) is not int or transfer['qobuzTrackId'] != track_id
            or not isinstance(transfer['jobUrl'], str)
            or not re.fullmatch(r"https://eu\.doubledouble\.top/dl/[A-Za-z0-9]+", transfer['jobUrl'])
            or (existing_metadata is None and transfer['jobUrl'] != job_url)
            or not isinstance(transfer['sha256'], str)
            or not re.fullmatch('[0-9a-f]{64}', transfer['sha256'])):
        raise ValueError('Interrupted transfer binding differs; preserve its audio')
    metadata = transfer['metadata']
    if (not isinstance(metadata, dict) or metadata.get('qobuzTrackId') != track_id
            or metadata.get('sha256') != transfer['sha256']
            or type(metadata.get('bytes')) is not int or metadata['bytes'] <= 0):
        raise ValueError('Interrupted transfer metadata differs; preserve its audio')
    if existing_metadata is not None and existing_metadata != metadata:
        raise ValueError('Retained metadata differs from interrupted transfer; preserve both')
    name = metadata.get('file')
    if (not isinstance(name, str) or Path(name).name != name or not name.lower().endswith('.flac')
            or any(c in name for c in '<>:"/\\|?*') or any(ord(c) < 32 for c in name)):
        raise ValueError('Interrupted transfer filename differs; preserve its audio')
    file = base / name
    partial = file.with_suffix(f".flac.{transfer['jobUrl'].rsplit('/', 1)[1]}.partial")
    source = file if file.exists() else partial
    if (source.is_symlink() or not source.is_file() or source.stat().st_size != metadata['bytes']
            or hash_bounded(source, budget) != transfer['sha256']):
        raise ValueError('Interrupted transfer audio differs; preserve its audio')
    with source.open('rb') as handle:
        if handle.read(4) != b'fLaC':
            raise ValueError('Interrupted transfer is not FLAC')
    require_decodable_flac(source, budget=budget)
    if source == partial:
        durable_replace(partial, file)
    budget.remaining()
    if existing_metadata != metadata:
        atomic_write_json(base / 'download.json', metadata)
    path.unlink()
    partial.with_name(partial.name+'.http.json').unlink(missing_ok=True)
    return {"trackId": track_id, "title": metadata["title"], "status": "saved", "bytes": metadata["bytes"]}


def _save_download(track_id, job_url, base, *, budget=None):
    budget = budget or TransferBudget(time.monotonic()+900)
    metadata_path = base / "download.json"
    existing = None
    if metadata_path.exists():
        existing = read_bounded_json(metadata_path, max_bytes=64*1024, root=base)
        if existing.get('status') == 'owner_rejected':
            raise ValueError('The owner rejected this Qobuz track; do not retain or download it')
        if (base / 'download-transfer.json').exists():
            _finish_transfer(base, track_id, job_url, existing_metadata=existing, budget=budget)
        file = validate_retained_download(base, track_id, existing, allow_missing=True, budget=budget)
        if file is not None:
            return {"trackId": track_id, "status": "already_saved", "bytes": file.stat().st_size}
    if (base / 'download-transfer.json').exists():
        return _finish_transfer(base, track_id, job_url, budget=budget)
    job = parse_strict_json_bytes(http_request(job_url, budget, headers=HEADERS))
    if job.get("status") != "done" or job.get("service") != "qobuz":
        raise ValueError("Job is not a completed Qobuz download")
    cover_query = urllib.parse.urlsplit(job["current"].get("cover", "")).query
    submitted_url = urllib.parse.parse_qs(cover_query).get("url", [None])[0]
    if submitted_url != f"https://play.qobuz.com/track/{track_id}":
        raise ValueError("Completed job belongs to a different Qobuz track")
    url = urllib.parse.urljoin(HEADERS["Referer"], job["url"])
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or parsed.netloc != "eu.doubledouble.top" or not parsed.path.startswith("/temp/complete/"):
        raise ValueError("Unexpected download destination")
    name = urllib.parse.unquote(parsed.path.rsplit("/", 1)[-1])
    if not name.lower().endswith(".flac") or any(c in name for c in '<>:"/\\|?*') or any(ord(c) < 32 for c in name):
        raise ValueError("Unexpected FLAC filename")
    base.mkdir(parents=True, exist_ok=True)
    file = base / (existing['file'] if existing is not None else name)
    # Partial bytes belong to one server job, even when another job returns the same filename.
    partial = file.with_suffix(f".flac.{job_url.rsplit('/', 1)[1]}.partial")
    if file.exists():
        raise FileExistsError("Preserve existing audio; resolve the incomplete transfer before retrying")
    download_file(url, partial, budget=budget)
    from stemwise_media.transfer_budget import hash_bounded
    digest = hash_bounded(partial, budget)
    if existing is not None and (digest != existing['sha256'] or partial.stat().st_size != existing['bytes']):
        raise ValueError('Replacement audio differs from the approved recording digest; selection and receipt were preserved')
    require_decodable_flac(partial, budget=budget)
    with partial.open("rb+") as source:
        if source.read(4) != b"fLaC":
            raise ValueError("Server returned something other than a FLAC file")
        os.fsync(source.fileno())
    metadata = {"qobuzTrackId": track_id, "title": job["current"]["name"], "artist": job["current"]["artist"],
                "file": name, "bytes": partial.stat().st_size, "sha256": digest, "downloadService": "DoubleDouble Europe",
                "serviceReportedProvider": "qobuz", "submittedTrackUrl": f"https://play.qobuz.com/track/{track_id}",
                "sourceAcceptance": "owner_trusts_doubledouble", "audioComparison": "not_performed",
                "directQobuzDownload": False, "upstreamLosslessHistory": "not independently established"}
    if existing is not None:
        metadata = existing
    atomic_write_json(base / 'download-transfer.json', {
        'formatVersion': 1, 'qobuzTrackId': track_id, 'jobUrl': job_url,
        'sha256': digest, 'metadata': metadata})
    return _finish_transfer(base, track_id, job_url, existing_metadata=existing, budget=budget)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jobs", nargs="+", help="TRACK_ID=OBSERVED_COMPLETED_JOB_URL")
    pairs = list(dict.fromkeys(tuple(value.split("=", 1)) for value in parser.parse_args().jobs))
    if any(len(pair) != 2 for pair in pairs) or len({pair[0] for pair in pairs}) != len(pairs):
        parser.error("Specify each track once as TRACK_ID=JOB_URL")
    failed = False
    retirement_error = None
    cancelled = Event()
    executor = ThreadPoolExecutor(max_workers=2)
    def transfer(pair):
        try:
            return save_download(*pair, budget=TransferBudget(time.monotonic()+900, cancelled=cancelled.is_set))
        except WorkerRetirementError:
            cancelled.set()
            raise
    try:
        futures = [(pair[0], executor.submit(transfer, pair)) for pair in pairs]
        for track, future in futures:
            try:
                print(json.dumps(future.result(), ensure_ascii=False), flush=True)
            except WorkerRetirementError as error:
                retirement_error = error
                raise
            except Exception as error:
                print(f"Track {track}: {error}", file=sys.stderr, flush=True)
                failed = True
    finally:
        cancelled.set()
        try:
            executor.shutdown(wait=True, cancel_futures=True)
        finally:
            if retirement_error is not None:
                raise retirement_error
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())

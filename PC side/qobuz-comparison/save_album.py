"""Save requested tracks from a completed, browser-observed Qobuz album job."""
import argparse
import copy
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import unicodedata
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

from stemwise_media.catalogue_page import album_ordinals, read_album
from stemwise_media.save_download import HEADERS, ROOT, download_file, _finish_transfer, validate_retained_download
from stemwise_engine.common import atomic_write_json, exclusive_process_lock, read_bounded_json, sha256_file, run_process_tree
from stemwise_engine.common import parse_strict_json_bytes
from stemwise_media.transfer_budget import TransferBudget, copy_bounded, validate_archive, http_request, hash_bounded, require_free_space


def normalized(text):
    return "".join(c for c in unicodedata.normalize("NFKD", text).lower() if c.isalnum())


def identify(tags, tracks):
    number = int(tags.get('track', '0').split('/')[0])
    ordinals = album_ordinals(tracks)
    discs = {disc for disc, _ in ordinals}
    disc_tag = tags.get('disc', tags.get('discnumber'))
    if len(discs) > 1 and disc_tag is None:
        raise ValueError('Multi-disc album member requires a disc number')
    disc = int(disc_tag.split('/')[0]) if disc_tag is not None else 1
    matches = [track for track, pair in zip(tracks, ordinals, strict=True) if pair == (disc, number)]
    if len(matches) != 1:
        raise ValueError('Invalid or ambiguous album disc and track number')
    track = matches[0]
    titles = {normalized(track["title"]), normalized(track.get("displayTitle", track["title"]))}
    if normalized(tags.get("title", "")) not in titles:
        raise ValueError("Archive title disagrees with authoritative album track number")
    if (normalized(tags.get("artist", "")) != normalized(track["artist"])
            and normalized(re.split(r"[,;]", tags.get("artist", ""))[0]) != normalized(track["artist"])):
        raise ValueError("Archive performer disagrees with album metadata")
    album = normalized(track["album"])
    if normalized(tags.get("album", "")) != album:
        raise ValueError("Archive album disagrees with requested album")
    return track


def remove_archive_members(path, rejected, *, budget=None):
    budget = budget or TransferBudget(time.monotonic()+900, max_bytes=4*1024**3)
    with zipfile.ZipFile(path) as source:
        entries = validate_archive(source, budget)
        if not rejected.intersection(source.namelist()):
            return
        kept = [entry for entry in entries if entry.filename not in rejected]
        # Expanded size bounds staging without guessing recompression ratios.
        replacement_bytes = 65536 + sum(entry.file_size + 2*len(entry.filename.encode('utf-8'))
            + 2*len(entry.extra) + len(entry.comment) + 256 for entry in kept)
        require_free_space(path.parent, replacement_bytes)
        with tempfile.NamedTemporaryFile(dir=path.parent, suffix='.zip', delete=False) as temporary:
            filtered = Path(temporary.name)
        try:
            with zipfile.ZipFile(filtered, 'w') as target:
                for entry in kept:
                    stored = copy.copy(entry)
                    stored.compress_type = zipfile.ZIP_DEFLATED
                    with source.open(entry) as original, target.open(stored, 'w') as output:
                        copy_bounded(original, output, TransferBudget(budget.deadline, cancelled=budget.cancelled))
            hash_bounded(filtered, budget)
            source.close()
            filtered.replace(path)
        finally:
            try:
                filtered.unlink(missing_ok=True)
            except OSError as error:
                try:
                    print(f'album-filter-disposal-pending:{filtered}:{type(error).__name__}', file=sys.stderr)
                except (OSError, ValueError):
                    pass


def save_album(album_url, job_url, selected_ids):
    if not re.fullmatch(r"https://eu\.doubledouble\.top/dl/[A-Za-z0-9]+", job_url):
        raise ValueError("Use the completed album job URL observed in the browser")
    archive_dir = ROOT / 'audio/qobuz/album-archives'
    archive_dir.mkdir(parents=True, exist_ok=True)
    # Always take the archive lease before any per-track lease and retain it through extraction.
    with exclusive_process_lock(archive_dir / (job_url.rsplit('/', 1)[-1] + '.lock'), wait_seconds=300):
        return _save_album(album_url, job_url, selected_ids)


def _save_album(album_url, job_url, selected_ids):
    budget = TransferBudget(time.monotonic()+900, max_bytes=4*1024**3)
    if not re.fullmatch(r"https://eu\.doubledouble\.top/dl/[A-Za-z0-9]+", job_url):
        raise ValueError("Use the completed album job URL observed in the browser")
    for track_id in selected_ids:
        metadata = ROOT / 'audio/qobuz' / str(track_id) / 'download.json'
        if metadata.exists() and json.loads(metadata.read_text(encoding='utf-8')).get('status') == 'owner_rejected':
            raise ValueError('The owner rejected this Qobuz track; do not retain or download it')
    if not selected_ids:
        raise ValueError('Select at least one album track')
    completed = set()
    for track_id in selected_ids:
        base = ROOT / 'audio/qobuz' / str(track_id)
        with exclusive_process_lock(base / 'download.lock'):
            metadata, journal = base / 'download.json', base / 'download-transfer.json'
            if journal.exists():
                transfer = read_bounded_json(journal, max_bytes=64*1024, root=base)
                data = transfer.get('metadata', {}) if isinstance(transfer, dict) else {}
                if (not isinstance(transfer, dict) or not isinstance(data, dict)
                        or transfer.get('jobUrl') != job_url or data.get('submittedAlbumUrl') != album_url
                        or not isinstance(data.get('archiveMember'), str) or not data['archiveMember']
                        or not isinstance(data.get('archiveSha256'), str)
                        or not re.fullmatch('[0-9a-f]{64}', data['archiveSha256'])):
                    raise ValueError('Album transfer binding differs; preserve retained audio')
                _finish_transfer(base, track_id, job_url, existing_metadata=(
                    read_bounded_json(metadata, max_bytes=64*1024, root=base) if metadata.exists() else None), budget=budget)
            if metadata.exists():
                data = read_bounded_json(metadata, max_bytes=64*1024, root=base)
                if data.get('submittedAlbumUrl') == album_url:
                    if validate_retained_download(base, track_id, data, allow_missing=True, budget=budget) is not None:
                        completed.add(track_id)
    if completed == selected_ids:
        return
    tracks = read_album(album_url, budget=budget)
    if not selected_ids or not selected_ids <= {t["id"] for t in tracks}:
        raise ValueError("Requested IDs are not in this authoritative album")
    rejected = {}
    for track in tracks:
        metadata = ROOT / 'audio/qobuz' / str(track['id']) / 'download.json'
        if metadata.exists():
            value = json.loads(metadata.read_text(encoding='utf-8'))
            if value.get('status') == 'owner_rejected':
                if value.get('submittedAlbumUrl') != album_url or not value.get('archiveMember'):
                    raise ValueError('Rejected album member identity is missing; cannot retain this archive')
                rejected[track['id']] = value['archiveMember']
    expected_ids = {t['id'] for t in tracks} - rejected.keys()
    raw = http_request(job_url, budget, headers=HEADERS)
    job = parse_strict_json_bytes(raw)
    submitted = urllib.parse.parse_qs(urllib.parse.urlsplit(job.get("current", {}).get("cover", "")).query).get("url", [None])[0]
    if job.get("status") != "done" or job.get("service") != "qobuz" or submitted != album_url:
        raise ValueError("Completed job belongs to a different album")
    url = urllib.parse.urljoin(HEADERS["Referer"], job["url"])
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or parsed.netloc != "eu.doubledouble.top" or not parsed.path.startswith("/temp/complete/") or not parsed.path.endswith(".zip"):
        raise ValueError("Unexpected archive destination")
    seen = set()
    archive_dir = ROOT / "audio/qobuz/album-archives"
    archive_dir.mkdir(parents=True, exist_ok=True)
    retained = archive_dir / (job_url.rsplit("/", 1)[-1] + ".zip")
    if not retained.exists():
        partial = retained.with_suffix(".zip.partial")
        download_file(url, partial, budget=budget)
        if not zipfile.is_zipfile(partial):
            raise ValueError("Server returned an invalid album archive")
        if rejected:
            with zipfile.ZipFile(partial) as archive:
                missing = set(rejected.values()) - set(archive.namelist())
            if missing:
                partial.unlink()
                raise ValueError('Rejected album member identity changed; cannot retain this archive')
            remove_archive_members(partial, set(rejected.values()), budget=budget)
        partial.rename(retained)
    remove_archive_members(retained, set(rejected.values()), budget=budget)
    archive_sha256 = hash_bounded(retained, budget)
    with retained.open("rb") as archive_file:
        with zipfile.ZipFile(archive_file) as archive:
            entries = [i for i in validate_archive(archive, budget) if i.filename.lower().endswith(".flac")]
            if len(entries) != len(expected_ids):
                raise ValueError("Archive does not contain the complete declared album")
            mapped = []
            for entry in entries:
                require_free_space(ROOT, entry.file_size)
                with tempfile.NamedTemporaryFile(suffix=".flac", dir=ROOT) as temporary:
                    with archive.open(entry) as source:
                        copy_bounded(source, temporary, TransferBudget(budget.deadline, cancelled=budget.cancelled))
                    temporary.flush()
                    result = run_process_tree(["ffprobe", "-v", "error", "-show_entries", "format_tags:stream=codec_name",
                            "-of", "json", temporary.name], check=True, capture_output=True, text=True,
                            timeout=min(30, budget.remaining()), stdin=subprocess.DEVNULL)
                    metadata = json.loads(result.stdout)
                    if not any(s.get("codec_name") == "flac" for s in metadata.get("streams", [])):
                        raise ValueError("Archive member is not FLAC")
                    track = identify({k.lower(): v for k, v in metadata.get("format", {}).get("tags", {}).items()}, tracks)
                    track_id = track["id"]
                    if track_id in rejected:
                        raise ValueError('Archive contains rejected audio under an unexpected member name')
                    if track_id in seen:
                        raise ValueError("Duplicate archive track number")
                    seen.add(track_id)
                    mapped.append((entry, track))
            if seen != expected_ids:
                raise ValueError("Incomplete archive identity mapping")
            for entry, track in mapped:
                track_id = track['id']
                if track_id not in selected_ids:
                    continue
                require_free_space(ROOT, entry.file_size)
                with tempfile.NamedTemporaryFile(suffix=".flac", dir=ROOT) as temporary:
                    with archive.open(entry) as source:
                        copy_bounded(source, temporary, TransferBudget(budget.deadline, cancelled=budget.cancelled))
                    temporary.flush()
                    base = ROOT / "audio/qobuz" / str(track_id)
                    existing = None
                    if (base / "download.json").exists():
                        with exclusive_process_lock(base / 'download.lock'):
                            if (base / 'download-transfer.json').exists():
                                _finish_transfer(base, track_id, job_url, existing_metadata=
                                    read_bounded_json(base / 'download.json', max_bytes=64*1024, root=base), budget=budget)
                            existing = read_bounded_json(base/'download.json', max_bytes=64*1024, root=base)
                            retained_audio = validate_retained_download(base, track_id, existing, allow_missing=True, budget=budget)
                        if retained_audio is not None:
                            print(json.dumps({"trackId": track_id, "status": "already_saved"}), flush=True)
                            continue
                    base.mkdir(parents=True, exist_ok=True)
                    title = track.get("displayTitle", track["title"])
                    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", title).strip(" .")[:120] + ".flac"
                    if existing is not None:
                        name = existing['file']
                    file = base / name
                    digest = hash_bounded(Path(temporary.name), TransferBudget(budget.deadline, cancelled=budget.cancelled))
                    if existing is not None and (existing.get('submittedAlbumUrl') != album_url
                            or existing.get('archiveMember') != entry.filename
                            or existing['sha256'] != digest or existing['bytes'] != entry.file_size):
                        raise ValueError('Archive member differs from the retained album receipt; preserve both')
                    data = {"qobuzTrackId": track_id, "file": name, "title": title, "artist": track["artist"],
                            "bytes": Path(temporary.name).stat().st_size, "sha256": digest, "downloadService": "DoubleDouble Europe",
                            "serviceReportedProvider": "qobuz", "submittedAlbumUrl": album_url,
                            "archiveMember": entry.filename, "archiveSha256": archive_sha256,
                            "sourceAcceptance": "owner_trusts_doubledouble", "audioComparison": "not_performed",
                            "directQobuzDownload": False}
                    if existing is not None:
                        data = existing
                    transfer = {'formatVersion':1, 'qobuzTrackId':track_id, 'jobUrl':job_url,
                        'sha256':digest, 'metadata':data}
                    with exclusive_process_lock(base / 'download.lock'):
                        if (base / 'download.json').exists():
                            current = read_bounded_json(base/'download.json', max_bytes=64*1024, root=base)
                            if validate_retained_download(base, track_id, current, allow_missing=True, budget=budget) is not None:
                                print(json.dumps({'trackId': track_id, 'status': 'already_saved'}), flush=True)
                                continue
                            if current != existing:
                                raise ValueError('Album recovery receipt changed before publication')
                        elif existing is not None:
                            raise ValueError('Album recovery receipt disappeared before publication')
                        journal = base / 'download-transfer.json'
                        if journal.exists():
                            if read_bounded_json(journal, max_bytes=64*1024, root=base) != transfer:
                                raise ValueError('Album transfer binding differs; preserve retained audio')
                        else:
                            if file.exists():
                                # A fresh validated archive member proves exact bytes, not just a filename.
                                if file.is_symlink() or sha256_file(file) != transfer['sha256']:
                                    raise FileExistsError("Preserve existing unbound audio: " + str(file))
                            else:
                                partial = file.with_suffix(f".flac.{job_url.rsplit('/', 1)[1]}.partial")
                                if partial.is_symlink():
                                    raise ValueError('Album partial is not an owned regular file')
                                temporary.seek(0)
                                if partial.exists():
                                    with partial.open('rb') as previous:
                                        while chunk := previous.read(1024 * 1024):
                                            budget.remaining()
                                            if temporary.read(len(chunk)) != chunk:
                                                raise ValueError('Album partial differs from the exact archive member')
                                require_free_space(base, entry.file_size - (partial.stat().st_size if partial.exists() else 0))
                                with partial.open('ab' if partial.exists() else 'xb') as target:
                                    copy_bounded(temporary, target, TransferBudget(budget.deadline, cancelled=budget.cancelled),
                                        offset=partial.stat().st_size if partial.exists() else 0)
                                    target.flush()
                                    os.fsync(target.fileno())
                            atomic_write_json(journal, transfer)
                        _finish_transfer(base, track_id, job_url, existing_metadata=existing, budget=budget)
                    print(json.dumps({"trackId": track_id, "status": "saved", "bytes": data["bytes"]}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("album_url")
    parser.add_argument("job_url")
    parser.add_argument("track_ids", type=int, nargs="+")
    args = parser.parse_args()
    save_album(args.album_url, args.job_url, set(args.track_ids))

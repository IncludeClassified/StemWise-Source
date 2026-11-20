"""Private native Qobuz acquisition through DoubleDouble's current HTTP interface."""
import json
import os
import re
import time
import urllib.parse
import urllib.request
from pathlib import Path

from stemwise_media.quick_batch import write_json
from stemwise_media.save_download import HEADERS, _save_download, validate_retained_download
from stemwise_engine.common import exclusive_process_lock, read_bounded_json
from stemwise_engine.enrichment_constants import MAX_RECORDING_TRACK_ID
from stemwise_media.transfer_budget import TransferBudget, http_request

ROOT = Path(__file__).resolve().parent
ORIGIN = 'https://eu.doubledouble.top'


class CaptchaRequired(RuntimeError):
    pass


def _read_json(url, timeout):
    content = http_request(url, TransferBudget(time.monotonic()+timeout), headers=HEADERS)
    from stemwise_engine.common import parse_strict_json_bytes
    value = parse_strict_json_bytes(content)
    if not isinstance(value, dict):
        raise ValueError('DoubleDouble returned an invalid response')
    return value


def download(track_id, *, job_url=None, timeout_seconds=900):
    track_id = int(track_id)
    if not 0 < track_id <= MAX_RECORDING_TRACK_ID or timeout_seconds <= 0:
        raise ValueError('Expected a positive Qobuz track ID and timeout')
    base = ROOT / 'audio/qobuz' / str(track_id)
    with exclusive_process_lock(base / 'download.lock'):
        return _download(track_id, base, job_url=job_url, timeout_seconds=timeout_seconds)


def _download(track_id, base, *, job_url, timeout_seconds):
    budget = TransferBudget(time.monotonic()+timeout_seconds)
    metadata = base / 'download.json'
    if metadata.exists():
        value = json.loads(metadata.read_text(encoding='utf-8'))
        if value.get('status') == 'owner_rejected':
            raise ValueError('The owner rejected this Qobuz track')
        if validate_retained_download(base, track_id, value, allow_missing=True, budget=budget) is not None:
            return _save_download(track_id, ORIGIN + '/dl/Retained', base, budget=budget)
    pending = base / 'job.json'
    transfer_path = base / 'download-transfer.json'
    if transfer_path.exists():
        transfer = read_bounded_json(transfer_path, max_bytes=64*1024, root=base)
        retained_job = transfer.get('jobUrl') if isinstance(transfer, dict) else None
        retained_metadata = transfer.get('metadata', {}) if isinstance(transfer, dict) else {}
        if (not isinstance(retained_metadata, dict) or job_url is not None and job_url != retained_job
                or retained_metadata.get('qobuzTrackId') != track_id
                or retained_metadata.get('submittedTrackUrl', f'https://play.qobuz.com/track/{track_id}') != f'https://play.qobuz.com/track/{track_id}'):
            raise ValueError('Interrupted transfer job or track differs; preserve retained audio')
        if pending.exists():
            value = read_bounded_json(pending, max_bytes=64*1024, root=base)
            if not isinstance(value, dict) or value.get('trackId') != track_id or value.get('jobUrl') != retained_job:
                raise ValueError('Interrupted transfer differs from retained job; preserve both')
        result = _save_download(track_id, retained_job, base, budget=budget)
        pending.unlink(missing_ok=True)
        return result
    if pending.exists():
        value = read_bounded_json(pending, max_bytes=64*1024, root=base)
        if not isinstance(value, dict) or value.get('trackId') != track_id:
            raise ValueError('Invalid retained DoubleDouble job binding')
        retained_job = value.get('jobUrl')
        if retained_job is not None and job_url is not None and retained_job != job_url:
            raise ValueError('An accepted DoubleDouble job is already retained; resume its exact URL')
        job_url = retained_job or job_url
        if not job_url:
            raise ValueError('DoubleDouble submission is unknown; resume with the exact --job-url')
    deadline = budget.deadline

    def request(url):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('DoubleDouble job is still pending; rerun to resume its saved job')
        return _read_json(url, min(30, remaining))

    if not job_url:
        source = f'https://play.qobuz.com/track/{track_id}'
        token = os.environ.get('STEMWISE_DOUBLED_DOUBLE_TOKEN')
        config = request(ORIGIN + '/captcha/config?' + urllib.parse.urlencode({'url': source}))
        # ponytail: this service may require human CAPTCHA; an exact completed job resumes without bypassing it.
        if config.get('active') is not False and not token:
            raise CaptchaRequired('Solve CAPTCHA on DoubleDouble and rerun with --job-url from that exact track')
        query = {'url': source, 'donotshare': 'true'}
        if token:
            query['token'] = token
        base.mkdir(parents=True, exist_ok=True)
        write_json(pending, {'trackId': track_id, 'state': 'submission-unknown', 'jobUrl': None})
        submitted = request(ORIGIN + '/dl?' + urllib.parse.urlencode(query))
        if submitted.get('success') is False:
            pending.unlink()
            if 'captcha' in str(submitted.get('error', '')).lower():
                raise CaptchaRequired('DoubleDouble requires a fresh CAPTCHA; correct it and retry')
            raise RuntimeError('DoubleDouble rejected the download request')
        if submitted.get('success') is not True:
            raise ValueError('DoubleDouble submission response is ambiguous; recover the exact job URL')
        identity = submitted.get('id')
        if not isinstance(identity, str) or not re.fullmatch('[A-Za-z0-9]+', identity):
            raise ValueError('DoubleDouble returned an invalid job ID')
        job_url = ORIGIN + '/dl/' + identity
    if not isinstance(job_url, str) or not re.fullmatch(r'https://eu\.doubledouble\.top/dl/[A-Za-z0-9]+', job_url):
        raise ValueError('Use an exact DoubleDouble Europe job URL')
    base.mkdir(parents=True, exist_ok=True)
    write_json(pending, {'trackId': track_id, 'jobUrl': job_url})
    while True:
        state = request(job_url)
        if state.get('status') == 'done':
            result = _save_download(track_id, job_url, base, budget=budget)
            pending.unlink(missing_ok=True)
            return result
        if state.get('status') == 'error':
            write_json(base/'job-failure.json', {'trackId': track_id, 'jobUrl': job_url,
                'state': 'terminal-failure', 'response': state})
            pending.unlink()
            raise RuntimeError('DoubleDouble job failed; its diagnostic is retained; retry explicitly to create a new job')
        if state.get('status') not in ('queued', 'pending', 'working', 'processing', 'downloading', 'uploading', 'searching', 'initializing') and not state.get('friendlyStatus'):
            raise ValueError('DoubleDouble returned an unknown job state')
        time.sleep(min(5, max(0, deadline - time.monotonic())))

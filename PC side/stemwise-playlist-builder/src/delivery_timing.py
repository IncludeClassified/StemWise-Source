"""Resolve delivery eligibility from the selected word intervals and current recording evidence."""
import hashlib
import re
from dataclasses import dataclass

from stemwise_engine.common import json_bytes, read_bounded_json
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.editorial_review import valid_reviewer
from stemwise_engine.song_enrichment_v2 import require_publication_lyrics
from stemwise_engine.workflow_records import FutureSongError


def _digest(value):
    return hashlib.sha256(json_bytes(value)).hexdigest()


RECEIPT_VERSION = 'stemwise-timing-v1'


def publication_target(document):
    """Catalogue aliases change only the public video ID of the same native recording."""
    source = document['source']
    if source.get('recording'):
        source = {key: value for key, value in source.items() if key != 'videoId'}
    return _digest({**document, 'source': source})


def _receipt(review, manifest_sha, audio_sha):
    raw = f'{RECEIPT_VERSION}:{review}:{manifest_sha}:{audio_sha}'.encode('ascii')
    binding = hashlib.sha256(raw).hexdigest()
    return f'{RECEIPT_VERSION}:{review}:{binding}'.encode('ascii')


def require_transport_receipt(comment, manifest_sha, audio_sha):
    from stemwise_engine.packages import PackageValidationError
    parts = comment.split(b':')
    if (len(parts) != 3 or not re.fullmatch(b'[0-9a-f]{64}', parts[1])
            or comment != _receipt(parts[1].decode('ascii'), manifest_sha, audio_sha)):
        raise PackageValidationError('Missing or mismatched PC timing clearance for this song and Original')
    return parts[1].decode('ascii')


@dataclass(frozen=True)
class TimingClearance:
    target: str
    review_sha256: str

    def transport_receipt(self, document, raw_manifest, audio_sha):
        if self.target != publication_target(document) or not re.fullmatch('[0-9a-f]{64}', self.review_sha256):
            raise FutureSongError('Timing clearance belongs to a different reviewed song')
        return _receipt(self.review_sha256, hashlib.sha256(raw_manifest).hexdigest(), audio_sha)


def require_delivery_clearance(workflow, job, document):
    report = require_delivery_timing(workflow, job, document)
    return TimingClearance(publication_target(document), report['timingDecisionSha256'])


def review_binding(video_id, recording_digest, original, flags):
    """Exclude machine paths and private losing candidates from listening decisions."""
    fields = ('line', 'wordIndex', 'word', 'startMs', 'endMs', 'reason', 'observation')
    return {'videoId': video_id, 'analysisAudioSha256': recording_digest,
            'originalSha256': _digest(original),
            'flagsSha256': _digest([{k: f[k] for k in fields} for f in flags])}


def require_delivery_timing(workflow, job, document):
    """The caller also verifies the validated recording snapshot and final package bytes."""
    from stemwise_engine.word_timing_review import readback
    lyrics = document.get('lyrics')
    require_publication_lyrics(lyrics, duration_ms=document['analysis']['durationMs'])
    original = next(track for track in lyrics['tracks'] if track['kind'] == 'original')
    report = readback(workflow, job, published_override=original)
    if (report.get('status') != 'reviewed' or not report.get('endpointEvidenceAvailable')
            or report.get('completeLines') != len(original['lines'])
            or not isinstance(report.get('publicationFlags'), list)):
        raise FutureSongError('Word timing review is incomplete for the selected recording and intervals')
    flags = report['publicationFlags']
    video_id = job['videoId']
    audio = job.get('validation', {}).get('audioSha256')
    if not audio:
        raise FutureSongError('Word timing has no current validated recording identity')
    binding = review_binding(video_id, audio, original, flags)
    review = checked_listening_review(workflow, job, binding, flags)
    if report.get('selectedMeasurementStatus') != 'matched':
        raise FutureSongError(report.get('selectedMeasurementReason') or
                              'Selected intervals do not match their recording-bound measurements')
    evidence = {'timing': binding, 'validationChildSha256': job['validation'].get('childSha256'),
                'listeningReviewSha256': _digest(review) if review is not None else None}
    if report['acousticReleaseEvidence']:
        evidence['acousticReleaseEvidence'] = report['acousticReleaseEvidence']
    if report.get('listeningReleaseSelection'):
        evidence['listeningReleaseSelection'] = report['listeningReleaseSelection']
    if report.get('equivalentTimingEstimates'):
        evidence['equivalentTimingEstimates'] = report['equivalentTimingEstimates']
    return {**report, 'timingDecisionSha256': _digest(evidence),
            'unresolvedPublicationFlagCount': 0, 'clearedPublicationFlagCount': len(flags) +
            report['acousticallyClearedFlagCount'] + report.get('estimatePolicyClearedFlagCount', 0)}


def checked_listening_review(workflow, job, binding, flags):
    if not flags:
        return None
    from stemwise_engine.instrumental_break import FLAG
    if any(flag['reason']==FLAG for flag in flags):
        raise FutureSongError('A word crosses a verified instrumental break. Repair its boundary before delivery.')
    video_id = job['videoId']
    directory = workflow._job_dir(video_id)
    path = directory/'delivery-timing-review.json'
    reason = f'{len(flags)} unresolved timing flags on the selected word intervals'
    if not path.is_file():
        raise FutureSongError(reason)
    review = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=directory)
    if (not isinstance(review, dict) or review.get('binding') != binding
            or not valid_reviewer(review.get('reviewer'))):
        raise FutureSongError(reason + '; the retained listening review is stale or incomplete')
    decisions = review.get('decisions')
    if not isinstance(decisions, list) or len(decisions) != len(flags):
        raise FutureSongError(reason + '; every flagged interval needs an individual disposition')
    for flag, decision in zip(flags, decisions):
        # A correction requires a new validated interval. Only an actual comparison
        # against the recording may clear an unchanged flagged interval.
        if (not isinstance(decision, dict)
                or any(decision.get(key) != flag[key] for key in ('line','wordIndex','startMs','endMs','reason'))
                or decision.get('disposition') != 'confirmed-on-recording'
                or not isinstance(decision.get('heard'), str) or not decision['heard'].strip()):
            raise FutureSongError(reason + '; recording comparison is missing for a selected interval')
    return review


def withhold_publication(workflow, video_id, public_id, expected_sha256):
    """Recheck one exact public alias and retain its bytes for repair. Safe to resume by digest."""
    import zipfile
    from pathlib import Path
    from stemwise_engine.common import (durable_replace, exclusive_process_lock, read_json,
        resolve_path, sha256_file, validate_generated_path, validate_video_id)
    from stemwise_engine.original_delivery import _metadata, read_bundle
    video_id, public_id = validate_video_id(video_id), validate_video_id(public_id)
    if not re.fullmatch('[a-f0-9]{64}', expected_sha256):
        raise ValueError('Expected the exact transport SHA-256')
    root = workflow.workspace_root
    ready = resolve_path(workflow.config['futureReadyDirWindows'], base=Path(workflow.config['projectDir']))
    name = f'{public_id}.stemwise.zip'
    source = validate_generated_path(ready, ready/name, workspace_root=root)
    recovery = workflow._job_dir(video_id)/'withheld-packages'
    saved = validate_generated_path(root, recovery/f'{expected_sha256}.{name}', workspace_root=root)
    selection_lock = workflow.pc_side_root/'qobuz-comparison/comparison-results/delivery-selection.lock'
    with exclusive_process_lock(workflow._job_dir(video_id)/'workflow.lock'), exclusive_process_lock(selection_lock):
        # Old evidence can be withdrawn even if it no longer qualifies for a new run.
        job = read_json(workflow._job_path(video_id))
        if job.get('videoId') != video_id:
            raise ValueError('Withheld package has a different job owner')
        path = source if source.is_file() else saved
        if sha256_file(path) != expected_sha256:
            raise ValueError('Refusing to move a different transport')
        with zipfile.ZipFile(path) as archive:
            document, _, _ = _metadata(archive, workflow._runtime_config())
        identity = document['source']
        recording = workflow.recording.identity if workflow.source_context else None
        if identity['videoId'] != public_id or identity.get('recording') != recording:
            raise ValueError('Refusing to move another recording')
        receipt = job.get('delivery') or job.get('withheldPublication', {}).get('previousDelivery')
        if not receipt or (not workflow.source_context and receipt['sha256'] != expected_sha256):
            raise ValueError('Missing exact prior publication identity')
        if job.get('state') != 'DELIVERED' and not job.get('withheldPublication'):
            raise ValueError('The current job has no delivery to withdraw')
        try:
            report = require_delivery_timing(workflow, job, document)
            read_bundle(path, workflow._runtime_config(), expected_review=report['timingDecisionSha256'])
        except ValueError as error:
            reason = str(error)
        else:
            raise ValueError('Current complete reviewed timing still qualifies')
        if path == source:
            if saved.exists():
                raise ValueError('Both public and recovery files exist; preserving both')
            saved.parent.mkdir(parents=True, exist_ok=True)
            durable_replace(source, saved)
        if sha256_file(saved) != expected_sha256:
            raise ValueError('Withheld package readback failed')
        item = {'publicId': public_id, 'packageSha256': expected_sha256,
                'package': workflow._windows_path(saved), 'reason': reason}
        changed = job.get('deliveryAllowed') is not False
        job['deliveryAllowed'] = False
        if job.get('state') == 'DELIVERED':
            job['withheldPublication'] = {'previousDelivery': job.pop('delivery'), **item}
            job['state'] = 'LINE_UNRESOLVED'
            job['outcome'] = {'code': 'line-unresolved', 'reason': reason}
            changed = True
        elif job['withheldPublication'].get('publicId') != public_id:
            aliases = job['withheldPublication'].setdefault('aliases', {})
            if aliases.get(public_id) != item:
                aliases[public_id] = item
                changed = True
        if changed:
            workflow._save_job(job)
        return {'videoId': video_id, 'publicId': public_id, 'sha256': expected_sha256, 'reason': reason,
                'state': job['state'], 'retained': str(saved)}

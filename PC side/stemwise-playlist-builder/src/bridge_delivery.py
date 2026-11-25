"""Select one current, finalized audio package for the existing bridge producer."""
from dataclasses import dataclass

from stemwise_engine.common import exclusive_process_lock, sha256_file, validate_generated_path, validate_video_id
from stemwise_engine.delivery_selection import catalogue
from stemwise_engine.original_delivery import read_bundle
from stemwise_engine.workflow_records import FutureSongError, BridgePackageTooLarge


@dataclass(frozen=True)
class VerifiedDelivery:
    path: str
    name: str
    size: int
    sha256: str

    def to_record(self):
        return {'path': self.path, 'name': self.name, 'size': self.size, 'sha256': self.sha256}


def _file_revision(path):
    value = path.stat()
    return (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns)


def bind_budget(job, max_file_bytes):
    if max_file_bytes is None:
        return
    if type(max_file_bytes) is not int or max_file_bytes <= 0:
        raise FutureSongError('Bridge budget must be a positive byte count')
    if job.get('bridgeMaxFileBytes', max_file_bytes) != max_file_bytes:
        raise FutureSongError('Accepted bridge budget cannot change during retry')
    job['bridgeMaxFileBytes'] = max_file_bytes


def require_workflow_budget(job, *, recording_identity=None):
    limit = job.get('bridgeMaxFileBytes')
    if limit is None:
        return
    bind_budget(job, limit)
    if job.get('bridgeManualImport') is True:
        job['bridgeBudgetPreflight'] = {'status': 'manual-import', 'maxFileBytes': limit}
        return
    # Native Original is stored unchanged. Unknown/transcoded size cannot establish fit.
    minimum = None
    if recording_identity is not None:
        size = recording_identity['byteCount']
        if type(size) is not int or size <= 0:
            raise FutureSongError('Selected native Original byte count is invalid')
        minimum = size + 22 + sum(76+2*len(name) for name in ('song.json', 'cover.png', 'original.flac'))
    rejected = minimum is not None and minimum > limit
    job['bridgeBudgetPreflight'] = {'maxFileBytes': limit,
        'status': 'manual-import-required' if rejected else 'not-rechecked',
        'minimumPackageBytes': minimum}
    if rejected:
        raise BridgePackageTooLarge('bridge-package-too-large')


def describe(config_path, video_id, max_file_bytes):
    from stemwise_engine.future_song import FutureSongWorkflow
    from stemwise_engine.recording_workflow import RecordingWorkflow

    video_id = validate_video_id(video_id)
    if type(max_file_bytes) is not int or max_file_bytes <= 0:
        raise FutureSongError('BridgeDelivery requires the current positive maxFileBytes')
    workflow = FutureSongWorkflow(config_path)
    selected = catalogue(workflow, public_ids=[video_id])
    key = selected.get(video_id, f'youtube:{video_id}')
    if key is None:
        raise FutureSongError('recording-not-selected')
    if key.startswith('qobuz:'):
        workflow = RecordingWorkflow(config_path, key)
    owner_id = workflow.video_id if key.startswith('qobuz:') else video_id
    with exclusive_process_lock(workflow._job_dir(owner_id) / 'workflow.lock'):
        return _describe(workflow, owner_id, video_id, key, max_file_bytes)


def _describe(workflow, owner_id, video_id, key, max_file_bytes):
    path = validate_generated_path(workflow.workspace_root,
        workflow.workspace_root / 'Ready to Import' / f'{video_id}.stemwise.zip',
        workspace_root=workflow.workspace_root)
    revision = _file_revision(path)
    status = workflow.status(owner_id, verify_delivery=True)
    if (status.get('state') != 'DELIVERED'
            or status.get('deliveryAllowed') is not True
            or status.get('delivery', {}).get('readyToImport') is not True):
        raise FutureSongError('BridgeDelivery requires a current finalized audio delivery')
    if key.startswith('qobuz:'):
        if (status.get('phoneDeliveryAllowed') is not True
                or not any(item.get('videoId') == video_id for item in status.get('phoneDeliveries', []))):
            raise FutureSongError('BridgeDelivery requires the selected native audio alias')
    digest = sha256_file(path)
    if not key.startswith('qobuz:') and digest != status['delivery'].get('sha256'):
        raise FutureSongError('BridgeDelivery package differs from finalization')
    document, _, original = read_bundle(path, workflow._runtime_config())
    if (document['source']['videoId'] != video_id
            or (key.startswith('qobuz:') and document['source'].get('recording') != workflow.recording[3])
            or (key.startswith('youtube:') and (document['source'].get('recording') is not None
                or original != status['delivery'].get('original')))):
        raise FutureSongError('BridgeDelivery package recording differs')
    size = path.stat().st_size
    if size > max_file_bytes:
        raise BridgePackageTooLarge('bridge-package-too-large', completed=True)
    if catalogue(workflow, public_ids=[video_id]).get(video_id, f'youtube:{video_id}') != key or _file_revision(path) != revision:
        raise FutureSongError('BridgeDelivery selection or package changed during verification')
    descriptor = VerifiedDelivery(path.relative_to(workflow.workspace_root).as_posix(),
        path.name, size, digest)
    return {'formatVersion': 1, 'videoId': video_id, 'recordingKey': key,
        'package': descriptor.to_record()}

"""Construct native-source inputs for the shared durable workflow."""
from pathlib import Path
from stemwise_engine.common import resolve_path
from stemwise_engine.future_song import FutureSongWorkflow
from stemwise_engine.recording_source import selection


def RecordingWorkflow(config_path: Path, key: str, *, verify_media=True):
    from stemwise_engine.common import load_config, json_bytes
    from stemwise_engine.workflow_context import WorkflowSourceContext, native_state_root, telemetry_job_root
    config = load_config(config_path)
    recording = selection(config, key, verify_media=verify_media)
    pc = resolve_path(config['pcSideRootWindows'], base=config_path.parent)
    install = resolve_path(config['futureRootWindows'], base=config_path.parent)
    identity = recording.identity
    state_root = native_state_root(install, identity)
    namespace = state_root.name
    result_root = pc / 'generated-packages/recording-enrichment' / f"qobuz-{identity['trackId']}" / identity['sha256']
    context = WorkflowSourceContext(namespace, state_root,
        result_root/'results', telemetry_job_root(recording.workspace, key)/'reports'/identity['sha256'],
        Path(config['tempDir'])/'future-song-policy-state'/namespace,
        key, json_bytes(identity), tuple(recording.reference_ids))
    return FutureSongWorkflow(config_path, source_context=context, recording=recording, verify_media=verify_media)

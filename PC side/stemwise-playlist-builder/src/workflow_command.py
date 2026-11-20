"""Public recording command dispatch and operation admission."""
from __future__ import annotations
import argparse
import base64
import json
import sys
from contextlib import nullcontext
from pathlib import Path
from typing import Any
from stemwise_engine.common import exclusive_process_lock, validate_video_id
from stemwise_engine.campaign_operations import ModelOperationBusyError, model_operation_slot
from stemwise_engine.future_song import FutureSongError, FutureSongWorkflow, extract_video_id


def parser() -> argparse.ArgumentParser:
    command_line = argparse.ArgumentParser(description="Stemwise isolated future-song workflow")
    command_line.add_argument("--config", required=True, type=Path)
    command_line.add_argument("--recording-key")
    commands = command_line.add_subparsers(dest="command", required=True)
    new = commands.add_parser("new")
    new.add_argument('--max-file-bytes', type=int)
    url_input = new.add_mutually_exclusive_group()
    url_input.add_argument("--url")
    url_input.add_argument("--url-base64")
    status = commands.add_parser("status")
    status.add_argument("--video-id")
    bridge_delivery = commands.add_parser('bridgedelivery')
    bridge_delivery.add_argument('--video-id', required=True)
    bridge_delivery.add_argument('--max-file-bytes', required=True, type=int)
    replacement = commands.add_parser('replaceanalysis')
    replacement.add_argument('--video-id')
    replacement.add_argument('--expected-delivery-sha256', required=True)
    replacement.add_argument('--expected-analysis-sha256', required=True)
    for name in ("source", "artwork"):
        source = commands.add_parser(name)
        source.add_argument("--video-id")
        source_url = source.add_mutually_exclusive_group(required=True)
        source_url.add_argument("--url")
        source_url.add_argument("--url-base64")
    for name in ("validate", "finalize", "retry", "endpointevidence", "endpointadjudicate", "recordingevidence", "syllacandidate", "task", "reprocess"):
        subcommand = commands.add_parser(name)
        subcommand.add_argument("--video-id")
        if name == 'retry':
            subcommand.add_argument('--max-file-bytes', type=int)
            subcommand.add_argument('--manual-import', action='store_true')
        if name == 'syllacandidate':
            subcommand.add_argument('--shadow', action='store_true')
    return command_line


def execute(args: argparse.Namespace) -> dict[str, Any]:
    if args.command == 'syllacandidate' and not args.shadow:
        raise FutureSongError('SyllaCandidate is disabled without explicit --shadow')
    if args.command == 'bridgedelivery':
        if args.recording_key:
            raise FutureSongError('BridgeDelivery selects the current recording by exact video ID')
        from stemwise_engine.bridge_delivery import describe
        result = describe(args.config, args.video_id, args.max_file_bytes)
        return result
    key = args.recording_key
    if not key:
        if args.command == 'new' and not (args.url or args.url_base64):
            raise FutureSongError('New requires an exact recording URL')
        if args.command not in {'new', 'status'} and not args.video_id:
            raise FutureSongError('This action requires an exact video ID or recording key')
    if not key and args.command == 'new':
        url = args.url or base64.b64decode(args.url_base64, validate=True).decode('utf-8')
        key = f'youtube:{extract_video_id(url)}'
    elif not key and getattr(args, 'video_id', None):
        key = f'youtube:{args.video_id}'
    if args.recording_key:
        from stemwise_engine.recording_workflow import RecordingWorkflow
        workflow = RecordingWorkflow(args.config, args.recording_key, verify_media=args.command not in {'status', 'reprocess'})
        if getattr(args, 'video_id', None):
            if args.video_id != workflow.video_id:
                raise FutureSongError("Native recording lyric reference differs")
        if hasattr(args, 'video_id'):
            args.video_id = workflow.video_id
        if args.command == 'new' and not (args.url or args.url_base64):
            args.url = f'https://www.youtube.com/watch?v={workflow.video_id}'
    else:
        workflow = FutureSongWorkflow(args.config)
    if args.command == 'reprocess':
        # Reuse current admission/retry rules; this command never resets an accepted operation.
        from copy import copy
        from stemwise_engine.workflow_states import JobProgress, JobStage, StageOutcome
        identity = workflow.video_id if args.recording_key else args.video_id
        lock = workflow._job_dir(validate_video_id(identity)) / 'workflow.lock'
        with exclusive_process_lock(lock):
            if workflow._job_path(identity).exists():
                progress = JobProgress.current(workflow._load_job(identity))
                if progress.rule.interrupted_state is not None:
                    action = 'status'
                elif progress.stage == JobStage.DELIVERY:
                    action = 'validate'
                elif progress.outcome == StageOutcome.BLOCKED:
                    action = 'task'
                elif progress.rule.retry_action is not None:
                    action = 'retry'
                elif 'Finalize' in progress.rule.actions:
                    action = 'finalize'
                else:
                    action = 'validate'
            else:
                action = 'new'
            request = copy(args)
            request.command = action
            request.video_id = identity
            request.url = f'https://www.youtube.com/watch?v={identity}' if action == 'new' else None
            request.url_base64 = None
            request.max_file_bytes = None
            request.manual_import = False
        # The selected action reacquires ownership and rechecks its actual current stage.
        result = execute(request)
        result['reprocessAction'] = action
        return result
    if args.command not in {"status"}:
        workflow._require_platform_content_allowed(
            workflow.video_id if args.recording_key else key.split(":", 1)[1])
    if args.command == "new":
        submitted_url = args.url
        if args.url_base64:
            try:
                submitted_url = base64.b64decode(args.url_base64, validate=True).decode("utf-8")
            except (ValueError, UnicodeError) as error:
                raise FutureSongError("the encoded URL argument is invalid") from error
        with model_operation_slot(workflow.config):
            result = workflow.new(submitted_url, max_file_bytes=args.max_file_bytes)
    elif args.command == "status":
        if args.recording_key and not workflow._job_path(workflow.video_id).exists():
            result = {'recordingKey': args.recording_key, 'state': 'NOT_STARTED',
                      'recordingInput': workflow.recording.identity, 'phoneDeliveryAllowed': False}
        else:
            result = workflow.status(args.video_id)
    elif args.command == "task":
        with exclusive_process_lock(workflow._job_dir(validate_video_id(args.video_id)) / 'workflow.lock'):
            job = workflow._load_job(args.video_id)
            workflow._write_task(job)
            result = {'agentTask': workflow.summary(job)['agentTask']}
    elif args.command == "source":
        submitted_url = args.url
        if args.url_base64:
            try:
                submitted_url = base64.b64decode(args.url_base64, validate=True).decode("utf-8")
            except (ValueError, UnicodeError) as error:
                raise FutureSongError("the encoded source URL argument is invalid") from error
        with exclusive_process_lock(
            workflow._job_dir(validate_video_id(args.video_id)) / "workflow.lock"
        ):
            result = workflow.add_lyric_source(args.video_id, submitted_url)
    elif args.command == "artwork":
        submitted_url = args.url
        if args.url_base64:
            try:
                submitted_url = base64.b64decode(args.url_base64, validate=True).decode("utf-8")
            except (ValueError, UnicodeError) as error:
                raise FutureSongError("the encoded artwork URL argument is invalid") from error
        with exclusive_process_lock(
            workflow._job_dir(validate_video_id(args.video_id)) / "workflow.lock"
        ):
            result = workflow.select_artwork(args.video_id, submitted_url)
    elif args.command == "recordingevidence":
        with exclusive_process_lock(
            workflow._job_dir(validate_video_id(args.video_id)) / "workflow.lock"
        ):
            with model_operation_slot(workflow.config):
                result = workflow.recording_evidence(args.video_id)
    elif args.command == 'syllacandidate':
        with model_operation_slot(workflow.config):
            result = workflow.sylla_candidate(args.video_id, shadow=args.shadow)
    elif args.command in ("endpointevidence", "endpointadjudicate"):
        with exclusive_process_lock(
            workflow._job_dir(validate_video_id(args.video_id)) / "workflow.lock"
        ):
            with model_operation_slot(workflow.config) if args.command == 'endpointadjudicate' else nullcontext():
                result = workflow.endpoint_evidence(args.video_id, vocal=args.command == "endpointadjudicate")
    elif args.command == "validate":
        with exclusive_process_lock(
            workflow._job_dir(validate_video_id(args.video_id)) / "workflow.lock"
        ):
            job = workflow._load_job(args.video_id)
            display_only = workflow._is_display_only_validation(job)
            with nullcontext() if display_only else model_operation_slot(workflow.config):
                result = workflow.validate(args.video_id)
    elif args.command == "finalize":
        with exclusive_process_lock(
            workflow._job_dir(validate_video_id(args.video_id)) / "workflow.lock"
        ):
            result = workflow.finalize(args.video_id)
    elif args.command == 'replaceanalysis':
        with exclusive_process_lock(workflow._job_dir(validate_video_id(args.video_id))/'workflow.lock'):
            with exclusive_process_lock(workflow._alignment_worker_lock_path(args.video_id)):
                result = workflow.replace_analysis(args.video_id,
                    expected_delivery_sha256=args.expected_delivery_sha256,
                    expected_analysis_sha256=args.expected_analysis_sha256)
    elif args.command == "retry":
        with exclusive_process_lock(
            workflow._job_dir(validate_video_id(args.video_id)) / "workflow.lock"
        ):
            with model_operation_slot(workflow.config):
                result = workflow.retry(args.video_id, max_file_bytes=args.max_file_bytes,
                                        manual_import=args.manual_import)
    if args.recording_key:
        result.setdefault('phoneDeliveryAllowed', False)
        if args.command != 'status' and result.get('state') == 'DELIVERED' and result['phoneDeliveryAllowed'] is not True:
            result['state'] = 'DELIVERY_AUDIO_REQUIRED'
            result.pop('delivery', None)
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        result = execute(args)
        print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))
        return 0
    except (FutureSongError, ModelOperationBusyError) as error:
        code = (str(error) if isinstance(error, ModelOperationBusyError)
                else FutureSongWorkflow._workflow_operation_failure_code(error))
        payload: dict[str, Any] = {"error": code}
        from stemwise_engine.workflow_records import workflow_failure_details
        payload.update(workflow_failure_details(error))
        trace = error.__traceback__
        while trace is not None:
            if trace.tb_frame.f_code.co_filename == __file__:
                payload["failureLocation"] = {
                    "function": trace.tb_frame.f_code.co_name,
                    "line": trace.tb_lineno,
                }
            trace = trace.tb_next
        print(
            json.dumps(payload, ensure_ascii=False, indent=2),
            file=sys.stderr,
        )
        return 2
    except Exception as error:
        from stemwise_engine.workflow_records import workflow_failure_details
        details = workflow_failure_details(error)
        print(
            json.dumps(
                {"error": FutureSongWorkflow._workflow_operation_failure_code(error), **details},
                ensure_ascii=False,
                indent=2,
            ),
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

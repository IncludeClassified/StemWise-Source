"""Current delivery publication operation, composed by the workflow."""
from __future__ import annotations
from contextlib import ExitStack
from typing import Any


def finalization_producer_identity():
    from pathlib import Path
    from stemwise_engine.common import json_bytes, sha256_file
    import hashlib
    directory = Path(__file__).parent
    return hashlib.sha256(json_bytes({name: sha256_file(directory/name) for name in
        ('delivery_service.py', 'original_delivery.py', 'native_delivery.py', 'packages.py',
         'youtube.py', 'recording_source.py', 'workflow_context.py')})).hexdigest()


class DeliveryService:
    def __init__(self, workflow):
        self.workflow = workflow
        self.source = workflow.source

    def _publish_private_metadata(self, staged, destination, package_sha, previous_sha):
        from stemwise_engine.common import durable_replace, sha256_file, validate_generated_path
        from stemwise_engine.original_delivery import retain_replacement_rollback
        destination = validate_generated_path(self.workflow.ready, destination,
            workspace_root=self.workflow.workspace_root)
        actual = sha256_file(destination) if destination.is_file() else None
        if actual != previous_sha or (actual is None and destination.exists()):
            raise ValueError('The private metadata destination changed during finalization')
        if actual != package_sha:
            if actual is not None:
                retain_replacement_rollback(self.workflow, destination, package_sha, staged.parent,
                    previous_sha256=actual)
            durable_replace(staged, destination)

    def _restore_uncommitted_delivery(self, job, destination, package_sha, previous_sha, staging_root):
        """Undo only this operation's positively stale publication, preserving unrelated bytes."""
        import shutil
        from stemwise_engine.common import (durable_replace, manifested_temporary_directory,
            sha256_file, validate_generated_path)
        from stemwise_engine.workflow_records import FutureSongError
        destination = validate_generated_path(self.workflow.ready, destination,
            workspace_root=self.workflow.workspace_root)
        if previous_sha == package_sha or not destination.is_file() or sha256_file(destination) != package_sha:
            return
        if previous_sha is None:
            destination.unlink()
            return
        directory = validate_generated_path(self.workflow.history,
            self.workflow.history / job['videoId'], workspace_root=self.workflow.workspace_root)
        rollback = next((path for path in directory.glob(f"*.{destination.name}")
            if sha256_file(validate_generated_path(directory, path,
                workspace_root=self.workflow.workspace_root)) == previous_sha), None)
        if rollback is None:
            raise FutureSongError('The previous public package rollback is unavailable; recovery inputs were retained')
        with manifested_temporary_directory(staging_root, prefix=f"{job['videoId']}-",
                owner={'kind': 'package-finalization-staging', 'videoId': job['videoId']},
                workspace_root=self.workflow.workspace_root) as staging:
            restored = staging/'restore.stemwise.zip'
            shutil.copy2(rollback, restored)
            if sha256_file(restored) != previous_sha:
                raise FutureSongError('Previous public package rollback changed during restoration')
            if sha256_file(destination) != package_sha:
                raise FutureSongError('Public package changed before editorial rollback; recovery inputs were retained')
            durable_replace(restored, destination)
            if sha256_file(destination) != previous_sha:
                raise FutureSongError('Previous public package restoration readback differs')

    def _complete_publication(self, job: dict[str, Any]) -> None:
        from stemwise_engine.common import read_bounded_json, sha256_file, utc_now, validate_video_id
        from stemwise_engine.contract import MAX_JSON_BYTES
        from stemwise_engine.workflow_records import FutureSongError
        workflow = self.workflow
        pending = job.get('state') == 'DELIVERY_PUBLICATION_PENDING'
        if not (pending or job.get('state') == 'DELIVERED') or not isinstance(job.get('delivery'), dict):
            return
        if pending and workflow.source_context is None:
            raise FutureSongError('Native publication requires the selected recording')
        deliveries = []
        if not pending and workflow.source_context is not None:
            from stemwise_engine.native_delivery import current
            binding = workflow.ready/'recording.json'
            if (binding.is_file() and read_bounded_json(binding, max_bytes=MAX_JSON_BYTES,
                    root=workflow.ready) == self.source.result_binding(job)):
                deliveries = current(workflow, job)
        if not deliveries:
            deliveries = self.source.complete_delivery(workflow, job)
        video_id = validate_video_id(job["videoId"])
        destination = workflow.ready / f"{video_id}.stemwise.zip"
        if sha256_file(destination) != job["delivery"].get("sha256"):
            raise FutureSongError("Completed rollback cleanup requires the current delivery")
        if pending:
            from stemwise_engine.native_delivery import current
            from stemwise_engine.delivery_selection import publication_selection
            bindings = {item['videoId']:workflow.key for item in deliveries}
            if not bindings:
                raise FutureSongError('Native delivery has no verified public aliases')
            with publication_selection(workflow, bindings, selected_before=bindings):
                if ({item['videoId'] for item in current(workflow, job)} != set(bindings)
                        or read_bounded_json(workflow.ready/'recording.json', max_bytes=MAX_JSON_BYTES,
                                             root=workflow.ready) != workflow._result_binding(job)):
                    raise FutureSongError('The public native aliases or recording binding changed before commit')
                job['state'] = 'DELIVERED'
                job['deliveryAllowed'] = True
                job['delivery'].update(readyToImport=True, deliveredAt=utc_now())
                job['delivery'].pop('preparedAt')
                workflow._save_job(job)
        from stemwise_engine.original_delivery import cleanup_replacement_rollback
        cleanup_replacement_rollback(workflow, video_id)
        for item in deliveries:
            if item['videoId'] != video_id:
                cleanup_replacement_rollback(workflow, item['videoId'])
        from stemwise_engine.job_ownership import cleanup_analysis_replacement
        cleanup_analysis_replacement(workflow, job)

    def run(self, video_id: str) -> dict[str, Any]:
        import shutil
        import time
        started = time.monotonic()
        from stemwise_engine.workflow_records import FutureSongError
        from stemwise_engine.workflow_states import TERMINAL_STATES
        from stemwise_engine.common import (manifested_temporary_directory, preflight_staging_space,
            sha256_file, utc_now, validate_generated_path)
        from stemwise_engine.packages import read_child, validate_child_zip
        from stemwise_engine.delivery_timing import require_delivery_clearance
        job = self.workflow._recover_artwork_repair(self.workflow._load_job(video_id))
        if job.get('state') in {'DELIVERY_PUBLICATION_PENDING', 'DELIVERED'}:
            child = self.workflow.runtime/'children'/f'{video_id}.stemwise.zip'
            document, _ = read_child(child, self.workflow._runtime_config())
            clearance=require_delivery_clearance(self.workflow, job, document)
        from stemwise_engine.original_delivery import verify_original_delivery, publish_bundle
        from stemwise_engine.delivery_selection import publication_selection
        if job['state'] == 'DELIVERED' and self.workflow.source_context is not None:
            from stemwise_engine.native_delivery import _selected_ids, current
            if _selected_ids(self.workflow) and not current(self.workflow, job):
                if not self.workflow._validated_snapshot_is_current(job, require_delivery=True,
                        allow_pending_delivery_cleanup=True):
                    raise FutureSongError('The selected native delivery cannot resume from its private receipt')
                job.update(state='DELIVERY_PUBLICATION_PENDING', deliveryAllowed=False)
                job['delivery'].update(readyToImport=False, preparedAt=job['delivery'].pop('deliveredAt'))
                self.workflow._save_job(job)
        if job.get('state') == 'DELIVERY_PUBLICATION_PENDING':
            self._complete_publication(job)
            self.workflow._write_task(job)
            self.workflow._remove_retained_analysis_audio(video_id)
            self.workflow._cleanup_completed_endpoint_contexts(job)
            return self.workflow.summary(job)
        if job.get("state") in TERMINAL_STATES:
            self.workflow._scavenge_finalization_staging(video_id)
            if self.workflow._validated_snapshot_is_current(job, require_delivery=True):
                verify_original_delivery(self.workflow, job, expected_review=clearance.review_sha256)
                self.workflow._write_task(job)
                self.workflow._remove_retained_analysis_audio(video_id)
                self.workflow._cleanup_completed_endpoint_contexts(job)
                self._complete_publication(job)
                return self.workflow.summary(job)
            if self.workflow._validated_snapshot_is_current(
                job,
                require_delivery=True,
                allow_pending_delivery_cleanup=True,
            ):
                verify_original_delivery(self.workflow, job, expected_review=clearance.review_sha256)
                self.workflow._write_task(job)
                self.workflow._remove_retained_analysis_audio(video_id)
                if self.workflow._validated_snapshot_is_current(job, require_delivery=True):
                    self.workflow._cleanup_completed_endpoint_contexts(job)
                    self._complete_publication(job)
                    return self.workflow.summary(job)
            raise FutureSongError(
                "the completed delivery snapshot is missing or changed; delivery was not modified"
            )
        if job.get("state") != "VALIDATED":
            raise FutureSongError("Finalize requires a successful Validate result; run Validate first")
        if not (
            self.workflow._validated_snapshot_is_current(job)
            or self.workflow._validated_snapshot_is_current(
                job, allow_finalization_pointer=True
            )
        ):
            raise FutureSongError(
                "the validated snapshot is stale or incomplete; run Validate again before Finalize"
            )
        input_sha = self.workflow._capability_input_sha(video_id)
        plan = self.source.plan_delivery(self.workflow, video_id)
        selected_before = plan.selected_before
        staging_root = self.workflow._scavenge_finalization_staging(video_id)
        source = validate_generated_path(
            self.workflow.runtime,
            self.workflow.runtime / "children" / f"{video_id}.stemwise.zip",
            workspace_root=self.workflow.workspace_root,
        )
        destination_root = self.workflow.ready
        destination = self.workflow.ready / source.name
        destination = validate_generated_path(
            destination_root,
            destination,
            workspace_root=self.workflow.workspace_root,
        )
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination = validate_generated_path(
            destination_root,
            destination,
            workspace_root=self.workflow.workspace_root,
        )
        if destination.exists() and not destination.is_file():
            raise FutureSongError("the Ready to Import destination is not a regular file")
        previous_sha = sha256_file(destination) if destination.is_file() else None
        source_sha = sha256_file(source)
        repair = job.get("artworkRepair")
        validated_sha = (
            repair.get("childSha256")
            if isinstance(repair, dict)
            else job.get("validation", {}).get("childSha256")
        )
        if source_sha != validated_sha:
            raise FutureSongError(
                "the internal child changed after validation; run Validate again before Finalize"
            )
        document, _ = read_child(source, self.workflow._runtime_config())
        clearance = require_delivery_clearance(self.workflow, job, document)
        package_sha = source_sha
        def require_current_inputs(*, published=False):
            if (self.workflow._capability_input_sha(video_id) == input_sha
                    and (self.workflow._validated_snapshot_is_current(job)
                         or self.workflow._validated_snapshot_is_current(job, allow_finalization_pointer=True))):
                try:
                    if require_delivery_clearance(self.workflow, job, document)!=clearance:
                        raise FutureSongError('Recording timing review changed during finalization')
                    return
                except (OSError, ValueError):
                    if published:
                        self._restore_uncommitted_delivery(job, destination, package_sha, previous_sha, staging_root)
                    raise
            if published:
                self._restore_uncommitted_delivery(job, destination, package_sha, previous_sha, staging_root)
            raise FutureSongError('Editorial inputs or authority changed during finalization; run Validate again')
        original_identity = None
        destination_is_current = (
            plan.metadata_only and previous_sha == source_sha
        )
        with ExitStack() as transaction:
            if not destination_is_current:
                owner = {"kind": "package-finalization-staging", "videoId": video_id}
                with manifested_temporary_directory(
                    staging_root,
                    prefix=f"{video_id}-",
                    owner=owner,
                    workspace_root=self.workflow.workspace_root,
                ) as staging:
                    staged_delivery = validate_generated_path(
                        staging,
                        staging / "delivery.stemwise.zip",
                        workspace_root=self.workflow.workspace_root,
                    )
                    rollback_bytes = (
                        destination.stat().st_size if destination.is_file() else 0
                    )
                    preflight_staging_space(
                        destination,
                        staged_delivery,
                        candidate_bytes=source.stat().st_size,
                        rollback_bytes=rollback_bytes,
                    )
                    shutil.copy2(source, staged_delivery)
                    validate_child_zip(
                        staged_delivery,
                        self.workflow._runtime_config(),
                        expected_video_id=video_id,
                    )
                    if sha256_file(staged_delivery) != source_sha:
                        raise FutureSongError(
                            "staged delivery bytes differ from the validated internal child"
                        )
                    if not plan.metadata_only:
                        from stemwise_engine.original_delivery import stage_original
                        staged_delivery, original_identity = stage_original(self.workflow, staged_delivery, staging, destination,
                                                                          job=job,clearance=clearance)
                        package_sha = sha256_file(staged_delivery)
                    if sha256_file(source) != validated_sha:
                        raise FutureSongError('The internal child changed during finalization')
                    require_current_inputs()
                    if plan.metadata_only:
                        self._publish_private_metadata(staged_delivery, destination, package_sha, previous_sha)
                    else:
                        transaction.enter_context(publication_selection(self.workflow,
                            {video_id: plan.source_key}, selected_before=selected_before))
                        package_sha = publish_bundle(self.workflow, staged_delivery, destination,
                            metadata=source, original=original_identity, previous_sha256=previous_sha,
                            selected_before=selected_before)
            if original_identity is None:
                validate_child_zip(destination, self.workflow._runtime_config(), expected_video_id=video_id)
            if sha256_file(destination) != package_sha:
                raise FutureSongError("delivered bytes differ from the prepared package")
            require_current_inputs(published=True)
            finalization_run_id, finalization_run_sha256 = self.workflow.report_store.commit_finalization_run(
                job, destination=destination, package_sha256=package_sha)
            require_current_inputs(published=True)
            job["state"] = "DELIVERY_PUBLICATION_PENDING" if plan.metadata_only else "DELIVERED"
            job['deliveryAllowed'] = not plan.metadata_only
            job["delivery"] = {
                "path": self.workflow._windows_path(destination),
                "sha256": package_sha,
                "bytes": destination.stat().st_size,
                ("preparedAt" if plan.metadata_only else "deliveredAt"): utc_now(),
                "readyToImport": not plan.metadata_only,
                "reviewEvidenceRetained": True,
                "runId": finalization_run_id,
                "runSha256": finalization_run_sha256,
            }
            if original_identity is not None:
                job['delivery']['original'] = original_identity
            self.workflow._save_job(job)
        if plan.metadata_only:
            self._complete_publication(job)
        verify_original_delivery(self.workflow, job, expected_review=clearance.review_sha256)
        self.workflow._write_task(job)
        self.workflow._remove_retained_analysis_audio(video_id)
        self.workflow._cleanup_completed_endpoint_contexts(job)
        if not plan.metadata_only:
            self._complete_publication(job)
        from stemwise_engine.alignment_attempt import current_song_ledger
        ledger = current_song_ledger()
        if ledger is not None:
            ledger.refresh()
            ledger.record['observedFinalization'] = {'operation': 'package-finalization', 'version': 1,
                'producerSha256': finalization_producer_identity(),
                'sourceKind': plan.source_kind,
                'maxInputBytes': ledger.record.get('analysisInputBytes'),
                'seconds': time.monotonic()-started, 'packageSha256': package_sha,
                'runId': finalization_run_id, 'runSha256': finalization_run_sha256}
            ledger.save()
        return self.workflow.summary(job)

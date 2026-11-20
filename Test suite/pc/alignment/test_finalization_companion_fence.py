"""Current editorial authority stays fenced through actual audio-bearing publication."""
import copy
import json
import unittest
from unittest.mock import patch

import test_available_completion as completion_fixtures
from stemwise_engine.common import atomic_write_json, sha256_file
from stemwise_engine.future_song import FutureSongError
from stemwise_engine.original_delivery import create_bundle, prepare_original, read_bundle
from stemwise_engine.packages import create_child_zip, read_child


class FinalizationCompanionFenceTests(unittest.TestCase):
    def setUp(self):
        self.case = completion_fixtures.AvailableCompletionTests()
        self.addCleanup(self.case.doCleanups)
        self.case.setUp()
        self.workflow = self.case.workflow
        self.video = self.case.video
        self.draft, self.review, _, _ = self.case.companion_publication_fixture()
        self.case.approve_current_companions(self.draft, self.review)
        self.evidence = self.draft.parent/'companion-evidence.json'
        atomic_write_json(self.evidence, {'fixture': 'synthetic retained review input'})
        for decision in self.review['companionReviews'].values():
            decision['evidence'] = [{'path': self.evidence.name, 'sha256': sha256_file(self.evidence)}]
        atomic_write_json(self.draft.parent/'agent-review.json', self.review)
        self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
        self.destination = self.workflow.ready/self.case.child.name

    def prior_package(self):
        document, cover = read_child(self.case.child, self.case.pipeline.config)
        previous = copy.deepcopy(document)
        previous['generatedAt'] = '2020-01-01T00:00:00Z'
        metadata = self.case.root/'previous.zip'
        create_child_zip(metadata, previous, self.case.root/'cover.png', self.case.pipeline.config)
        audio, identity = prepare_original(self.case.audio.parent/'source.flac', previous, self.case.root)
        create_bundle(self.destination, metadata, audio, identity, self.case.pipeline.config, self.case.root)
        self.assertEqual(read_bundle(self.destination, self.case.pipeline.config)[0], previous)
        return self.destination.read_bytes()

    def change_candidate(self):
        (self.draft/'translation-en.txt').write_text('goodbye world\nhello world\n', encoding='utf-8')

    def assert_staging_change_rejects(self, change):
        import stemwise_engine.original_delivery as delivery
        previous = self.prior_package()
        before = self.workflow._job_path(self.video).read_bytes()
        child = self.case.child.read_bytes()
        audio = self.case.audio.read_bytes()
        create = delivery.create_bundle
        def changed_after_staging(*args, **kwargs):
            result = create(*args, **kwargs)
            change()
            return result
        with patch.object(delivery, 'create_bundle', side_effect=changed_after_staging):
            with self.assertRaises(FutureSongError):
                self.workflow.finalize(self.video)
        self.assertEqual(self.destination.read_bytes(), previous)
        self.assertEqual(self.workflow._load_job(self.video)['state'], 'VALIDATED')
        self.assertEqual(self.workflow._load_job(self.video)['validation'], json.loads(before)['validation'])
        self.assertEqual(self.case.child.read_bytes(), child)
        self.assertEqual(self.case.audio.read_bytes(), audio)
        self.assertFalse(self.workflow._validated_snapshot_is_current(self.workflow._load_job(self.video)))

    def test_candidate_edit_while_staging_preserves_prior_package(self):
        self.assert_staging_change_rejects(self.change_candidate)

    def test_review_evidence_edit_while_staging_preserves_prior_package(self):
        self.assert_staging_change_rejects(lambda: atomic_write_json(self.evidence, {'changed': True}))

    def test_original_evidence_change_outside_input_digest_is_revalidated(self):
        from stemwise_engine.editorial_admission import available_lyrics
        job = self.workflow._load_job(self.video)
        audio = self.workflow._completed_analysis_audio(self.case.pipeline, self.video)
        _, capabilities = available_lyrics(self.workflow, job, audio)
        evidence = self.workflow._alignment_state_path(self.video).parent / capabilities['contentAdmission']['evidence']['path']
        before = self.workflow._capability_input_sha(self.video)
        def change():
            atomic_write_json(evidence, {'invalidated': True})
            self.assertEqual(self.workflow._capability_input_sha(self.video), before)
        self.assert_staging_change_rejects(change)

    def assert_public_readback_change_rejects(self, *, existing, boundary='readback'):
        import stemwise_engine.original_delivery as delivery
        previous = self.prior_package() if existing else None
        read = delivery.read_bundle
        commit = self.workflow.report_store.commit_finalization_run
        changed = False
        def change_at_readback(path, *args, **kwargs):
            nonlocal changed
            result = read(path, *args, **kwargs)
            if boundary == 'readback' and path == self.destination and not changed:
                changed = True
                self.change_candidate()
            return result
        def change_after_run_commit(*args, **kwargs):
            nonlocal changed
            result = commit(*args, **kwargs)
            if boundary == 'run-commit':
                changed = True
                self.change_candidate()
            return result
        with patch.object(delivery, 'read_bundle', side_effect=change_at_readback), \
                patch.object(self.workflow.report_store, 'commit_finalization_run', side_effect=change_after_run_commit):
            with self.assertRaises(FutureSongError):
                self.workflow.finalize(self.video)
        self.assertTrue(changed)
        self.assertEqual(self.destination.read_bytes() if self.destination.exists() else None, previous)
        self.assertEqual(self.workflow._load_job(self.video)['state'], 'VALIDATED')
        self.assertTrue(self.case.audio.is_file())
        self.assertTrue(self.case.child.is_file())

    def test_candidate_edit_at_public_readback_restores_exact_previous_package(self):
        self.assert_public_readback_change_rejects(existing=True)

    def test_candidate_edit_at_first_public_readback_removes_only_uncommitted_candidate(self):
        self.assert_public_readback_change_rejects(existing=False)

    def test_edit_between_finalization_run_and_job_commit_restores_prior_package(self):
        self.assert_public_readback_change_rejects(existing=True, boundary='run-commit')

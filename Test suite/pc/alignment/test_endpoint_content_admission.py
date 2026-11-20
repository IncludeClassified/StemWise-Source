import copy
import json
import tempfile
from unittest.mock import patch
from types import SimpleNamespace
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "Test suite/pc/unit"))
from lyric_content_fixtures import retained_content
import stemwise_engine.endpoint_ctc_evidence as ctc
from stemwise_engine.workflow_records import LyricsContentBlocker, ContentEvidenceUnavailable


class EndpointContentAdmissionTests(unittest.TestCase):
    def owner_fixture(self, root):
        from stemwise_engine.common import atomic_write_json, json_bytes, sha256_file
        from stemwise_engine.lyrics_intake import compile_draft, read_recording_draft
        from stemwise_engine.lyrics_alignment import (AlignmentWorkspace, snapshot_canonical_plain,
            build_lexical_occurrence_mapping, lexical_mapping_identity)
        from stemwise_engine.lyric_content import OWNER_VERIFICATION_FILE
        workspace = AlignmentWorkspace(root/'workflow', workspace_root=root)
        video = 'TESTVID0001'
        job = workspace.root.parent/'jobs'/video
        draft = job/'drafts'
        draft.mkdir(parents=True)
        (draft/'original.txt').write_text('hello world\nhello world\n', encoding='utf-8')
        atomic_write_json(draft/'lyrics.meta.json', {'videoId': video, 'originalLanguage': 'en',
            'originalScript': 'Latn', 'sources': {'original': {'name': 'Reviewed fixture'}}})
        lyrics = compile_draft(draft, {'outputDir': str(root/'runtime')}, {video}, expected_video_id=video)
        original = lyrics['tracks'][0]
        _, canonical_sha = snapshot_canonical_plain(workspace, video, json_bytes(lyrics))
        audio = root/'selected-pcm'
        audio.write_bytes(b'exact independently reviewed source identity')
        audio_sha = sha256_file(audio)
        _, draft_sha, _, metadata_sha = read_recording_draft(job)
        mapping = build_lexical_occurrence_mapping(video, original, (0, 1))
        review = {'formatVersion': 1, 'videoId': video, 'audioSha256': audio_sha,
            'draftSha256': draft_sha, 'metadataSha256': metadata_sha,
            'mappingSha256': lexical_mapping_identity(mapping)['mappingSha256'], 'spokenTargets': None,
            'reviewer': 'Independent owner fixture', 'completedAt': '2026-11-13T19:00:00Z',
            'decision': 'owner-verified-complete-performed-lyrics', 'timing': 'allow-complete-predicted-words',
            'rationale': 'Known-answer complete wording, pronunciation and both performed occurrences.'}
        return workspace, video, original, canonical_sha, audio_sha, job/OWNER_VERIFICATION_FILE, review

    def test_owner_verification_binds_complete_target_without_inventing_asr_support(self):
        from stemwise_engine.common import atomic_write_json, sha256_file
        from stemwise_engine.lyric_content import (owner_workspace_admission, require_content,
            require_admitted_sequence, ContentAdmission)
        work = Path(__file__).resolve().parents[3]/'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as name:
            workspace, video, original, canonical, audio, path, review = self.owner_fixture(Path(name))
            self.assertIsNone(owner_workspace_admission(workspace, video, original, (0, 1), canonical, audio))
            atomic_write_json(path, review)
            with patch('stemwise_engine.lyric_content._recognized', side_effect=AssertionError('not ASR evidence')):
                with self.assertRaises(ContentEvidenceUnavailable):
                    owner_workspace_admission(workspace, video, original, (0, 1), canonical, audio, retain=False)
                self.assertFalse(workspace.job_dir(video).exists())
                admission = owner_workspace_admission(workspace, video, original, (0, 1), canonical, audio)
                self.assertEqual(admission.owner_verification, {'path': path.name, 'sha256': sha256_file(path)})
                self.assertEqual(ContentAdmission.from_record(admission.to_record()), admission)
                self.assertEqual(require_content(admission.to_record(), original, (0, 1), canonical,
                    audio, workspace.job_dir(video)), admission)
                require_admitted_sequence(admission, original, (0, 1), 'contrary model output',
                    evidence_root=workspace.job_dir(video))
                # Independent companion edits cannot change the reviewed Original target.
                metadata = path.parent/'drafts/lyrics.meta.json'
                value = json.loads(metadata.read_bytes())
                value['sources']['translationEn'] = {'name': 'Independent companion context'}
                atomic_write_json(metadata, value)
                self.assertEqual(owner_workspace_admission(workspace, video, original, (0, 1), canonical, audio), admission)

    def test_owner_verification_rejects_flags_changed_targets_and_changed_retained_decisions(self):
        from stemwise_engine.common import atomic_write_json
        from stemwise_engine.lyric_content import owner_workspace_admission, require_content
        work = Path(__file__).resolve().parents[3]/'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as name:
            workspace, video, original, canonical, audio, path, review = self.owner_fixture(Path(name))
            atomic_write_json(path, review)
            admission = owner_workspace_admission(workspace, video, original, (0, 1), canonical, audio)
            retained = workspace.job_dir(video)/admission.evidence_path
            before = retained.read_bytes()
            wrong = [{'verified': True}, *({**review, key: value} for key, value in (
                ('decision', 'model-agreement'), ('audioSha256', 'f'*64), ('draftSha256', 'f'*64),
                ('metadataSha256', 'f'*64), ('mappingSha256', 'f'*64),
                ('reviewer', 'REPLACE_WITH_REVIEWER'), ('completedAt', '2026-99-99T19:00:00Z'),
                ('spokenTargets', {'path': 'guessed', 'sha256': 'f'*64})))]
            for value in wrong:
                with self.subTest(review=value):
                    atomic_write_json(path, value)
                    with self.assertRaises(LyricsContentBlocker):
                        owner_workspace_admission(workspace, video, original, (0, 1), canonical, audio)
                    self.assertEqual(retained.read_bytes(), before)
            atomic_write_json(path, review)
            with self.assertRaises(LyricsContentBlocker):
                owner_workspace_admission(workspace, video, original, (1, 0), canonical, audio)
            draft = path.parent/'drafts/original.txt'
            saved = draft.read_bytes()
            draft.write_bytes(b'wrong words\nhello world\n')
            with self.assertRaises(LyricsContentBlocker):
                require_content(admission.to_record(), original, (0, 1), canonical, audio, workspace.job_dir(video))
            draft.write_bytes(saved)
            retained.write_bytes(before+b' ')
            with self.assertRaises((LyricsContentBlocker, ContentEvidenceUnavailable)):
                require_content(admission.to_record(), original, (0, 1), canonical, audio, workspace.job_dir(video))
            self.assertEqual(retained.read_bytes(), before+b' ')
            retained.write_bytes(before)
            path.unlink()
            with self.assertRaises(LyricsContentBlocker):
                require_content(admission.to_record(), original, (0, 1), canonical, audio, workspace.job_dir(video))

    def test_shared_plan_rejects_missing_content_before_changing_inputs(self):
        lyrics = {"tracks": [{"kind": "original", "format": "plain", "language": "en", "text": "before\ntarget\nafter"}]}
        mapping = {"performedOccurrences": [{"occurrenceId": str(i), "sourcePhysicalIndex": i,
            "words": [{"occurrenceWordId": f"w{i}"}]} for i in range(3)]}
        families = {"mms-fa": {"startMs": 1000, "endMs": 2000},
                    "language-wav2vec2": {"startMs": 1100, "endMs": 2000}}
        evidence = {"occurrenceCandidates": [{"familyCandidates": copy.deepcopy(families)} for _ in range(3)]}
        declaration = {"analysisAudio": {"durationMs": 4000}, "occurrences": [
            {"performedIndex": 1, "occurrenceId": "1", "candidateFamilies": families}]}
        before = copy.deepcopy((declaration, mapping, evidence, lyrics))
        with self.assertRaises(LyricsContentBlocker):
            ctc._plan(declaration, mapping, evidence, lyrics)
        self.assertEqual((declaration, mapping, evidence, lyrics), before)

    def fixture(self, root):
        video = 'TESTVID0001'
        lyrics = {'tracks': [{'kind': 'original', 'format': 'plain', 'language': 'en',
            'text': 'before\ntarget\nafter'}]}
        canonical = root / 'lyrics-alignment/canonical-plain' / (video + '.json')
        canonical.parent.mkdir(parents=True)
        canonical.write_text(json.dumps(lyrics), encoding='utf-8')
        audio = root / 'source.wav'
        audio.write_bytes(b'unchanged selected PCM identity')
        mapping = {'performedOccurrences': [{'occurrenceId': str(i), 'sourcePhysicalIndex': i,
            'words': [{'occurrenceWordId': 'w' + str(i)}]} for i in range(3)]}
        families = {'mms-fa': {'startMs': 1000, 'endMs': 2000},
            'language-wav2vec2': {'startMs': 1100, 'endMs': 2000}}
        evidence = {'occurrenceCandidates': [{'familyCandidates': copy.deepcopy(families),
            'publishDecision': {'result': 'line-only'}} for _ in range(3)]}
        evidence['occurrenceCandidates'][1]['publishDecision'] = {
            'result': 'unresolved', 'code': 'line-start-disagreement'}
        evidence_root = canonical.parent.parent / 'jobs' / video
        declaration = {'videoId': video, 'canonicalPlainSha256': ctc.sha256_file(canonical),
            'analysisAudio': {'sha256': ctc.sha256_file(audio), 'durationMs': 4000},
            'occurrences': [{'performedIndex': 1, 'occurrenceId': '1', 'candidateFamilies': families}]}
        admission, recognition = retained_content(evidence_root, lyrics['tracks'][0], (0, 1, 2),
            declaration['canonicalPlainSha256'], declaration['analysisAudio']['sha256'])
        declaration['contentAdmission'] = admission.to_record()
        return declaration, mapping, evidence, lyrics, canonical, audio, recognition, admission

    def test_real_retained_words_allow_only_explicit_timing_recovery_and_are_rechecked(self):
        work = Path(__file__).resolve().parents[3] / 'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as name:
            root = Path(name)
            declaration, mapping, evidence, lyrics, canonical, audio, recognition, admission = self.fixture(root)
            plan = ctc._plan(declaration, mapping, evidence, lyrics, evidence_root=recognition.parent)
            self.assertEqual([line['lineId'] for line in plan[0]['request']['lines']], ['0', '1', '2'])
            self.assertEqual(plan[0]['targetLine'], 1)
            for reason in ('unsafe-occurrence-identity', 'low-forced-line-support', 'unknown-content-code'):
                evidence['occurrenceCandidates'][1]['publishDecision']['code'] = reason
                with self.assertRaises(LyricsContentBlocker):
                    ctc._plan(declaration, mapping, evidence, lyrics, evidence_root=recognition.parent)
            saved = evidence['occurrenceCandidates'][0].pop('publishDecision')
            with self.assertRaises(LyricsContentBlocker):
                ctc._plan(declaration, mapping, evidence, lyrics, evidence_root=recognition.parent)
            evidence['occurrenceCandidates'][0]['publishDecision'] = saved
            evidence['occurrenceCandidates'][1]['publishDecision']['code'] = 'line-end-disagreement'
            cache = json.loads(recognition.read_bytes())
            cache['workerOutput']['words'][1]['raw'] = 'wrong'
            recognition.write_text(json.dumps(cache), encoding='utf-8')
            with self.assertRaises(LyricsContentBlocker):
                ctc._plan(declaration, mapping, evidence, lyrics, evidence_root=recognition.parent)
            self.assertEqual(ctc.sha256_file(audio), declaration['analysisAudio']['sha256'])
            self.assertEqual(ctc.sha256_file(canonical), declaration['canonicalPlainSha256'])

    def test_direct_packet_writers_recheck_content_before_cached_replay_or_any_mutation(self):
        import stemwise_engine.endpoint_candidate_prediction as candidate
        import stemwise_engine.endpoint_repeat_alignment as repeat
        import stemwise_engine.endpoint_native_ctc as native
        import stemwise_engine.endpoint_owsm_ctc as owsm
        work = Path(__file__).resolve().parents[3] / 'Test suite/work'
        for writer in (ctc.write_packet, candidate.write_packet,
                       repeat.write_packet, native.write_packet, owsm.write_packet):
            with self.subTest(writer=writer.__module__), tempfile.TemporaryDirectory(dir=work) as name:
                root = Path(name)
                declaration, mapping, evidence, lyrics, canonical, audio, recognition, _ = self.fixture(root)
                attempt = root / 'job/endpoint-review/attempt'
                attempt.mkdir(parents=True)
                (attempt / 'review.json').write_bytes(b'pending review')
                retained = root / 'job/endpoint-singing-prediction/attempt/manifest.json'
                retained.parent.mkdir(parents=True)
                retained.write_bytes(b'prior immutable packet')
                # A still-well-formed artifact with a changed word must never trigger repair.
                cache = json.loads(recognition.read_bytes())
                cache['workerOutput']['words'][1]['raw'] = 'wrong'
                recognition.write_text(json.dumps(cache), encoding='utf-8')
                before = {p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()}
                arguments = (attempt, declaration, 'd' * 64, audio,
                    {'projectDir': str(root / 'builder'), 'analysisTimeoutMinutes': 1}, mapping, evidence, canonical)
                with patch('stemwise_engine.endpoint_ctc_evidence._invoke', side_effect=AssertionError('must not infer')):
                    with self.assertRaises(LyricsContentBlocker):
                        writer(*arguments, *(() if writer == ctc.write_packet else
                            ({'method': ctc.METHOD, 'manifestSha256': 'e' * 64},)))
                self.assertEqual({p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()}, before)

    def test_boundary_abstention_can_recover_but_capability_or_unknown_abstention_cannot(self):
        work = Path(__file__).resolve().parents[3] / 'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as name:
            *_, admission = self.fixture(Path(name))
            decisions = {boundary: {'result': 'abstain', 'reason': 'ctc-views-or-perturbations-inconclusive'}
                for boundary in ('startMs', 'endMs')}
            packet = {'occurrences': [{'assessment': decisions}]}
            self.assertTrue(ctc.needs_timing_recovery(packet, admission))
            for reason in ('target-vocabulary-unsupported', 'mixed-language-context-needs-supported-word-bindings', 'unknown'):
                decisions['endMs']['reason'] = reason
                with self.assertRaises(LyricsContentBlocker) as blocked:
                    ctc.needs_timing_recovery(packet, admission)
                self.assertEqual(blocked.exception.binding, admission.binding)

    def test_confirmed_timing_only_cached_packet_replays_without_model_or_byte_changes(self):
        work = Path(__file__).resolve().parents[3] / 'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as name:
            root = Path(name)
            declaration, mapping, evidence, lyrics, canonical, audio, _, _ = self.fixture(root)
            attempt = root / 'job/endpoint-review/attempt'
            directory = ctc._directory(attempt)
            directory.mkdir(parents=True)
            records = [{'route': route, 'shiftMs': shift, 'decisions': {
                boundary: {'result': 'supported', 'candidateMs': stamp, 'compatibleCandidatesMs': [stamp]}
                for boundary, stamp in (('startMs', 1000), ('endMs', 2000))}}
                for route in ctc.ROUTES for shift in (-5, 0, 5)]
            runtime = {'device': 'cuda', 'uuid': 'GPU-known-cache-fixture', 'name': 'Known fixture',
                'torch': 'fixture', 'cudaDriver': 12080, 'cudaRuntime': '12.8', 'cudnn': 'fixture',
                'capability': [8, 0], 'totalMemoryBytes': 8*1024**3}
            manifest = {'method': ctc.METHOD, 'scorerVersion': ctc.SCORER_VERSION,
                'producerRuntime': runtime,
                'declarationSha256': 'd' * 64, 'analysisAudio': declaration['analysisAudio'],
                'canonicalPlainSha256': declaration['canonicalPlainSha256'], 'reviewRequired': True,
                'occurrences': [{'occurrenceId': '1', 'inference': {'records': records},
                    'assessment': ctc._assess(records, declaration['occurrences'][0]['candidateFamilies'])}]}
            (directory / 'manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
            before = {p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()}
            with patch.object(ctc, '_invoke', side_effect=AssertionError('cache replay must not infer')) as inference, \
                    patch('stemwise_engine.worker_runtime_probe.selected_torch_runtime', return_value=runtime):
                packet = ctc.write_packet(attempt, declaration, 'd' * 64, audio,
                    {'projectDir': str(root / 'builder'), 'analysisTimeoutMinutes': 1}, mapping, evidence, canonical)
                inference.assert_not_called()
            self.assertEqual(packet['occurrences'][0]['assessment']['startMs']['candidateMs'], 1000)
            self.assertEqual(packet['occurrences'][0]['assessment']['endMs']['candidateMs'], 2000)
            self.assertEqual({p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()}, before)

    def test_exhausted_failure_review_rechecks_content_and_caller_timing_allowlist(self):
        from stemwise_engine.endpoint_adjudication import failure_source
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace, snapshot_canonical_plain
        work = Path(__file__).resolve().parents[3] / 'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as name:
            root = Path(name)
            declaration, mapping, evidence, _, canonical, _, recognition, admission = self.fixture(root)
            video, attempt = declaration['videoId'], '1' * 32
            snapshot_canonical_plain(AlignmentWorkspace(root, workspace_root=root), video, canonical.read_bytes())
            state_path = recognition.parent / 'state.json'
            reasons = ['line-start-disagreement']
            common = {'routeFailures': [], 'internalRepeatStructureKind': 'none', 'internalRepeatAuditComplete': True}
            state = {**common, 'status': 'line-unresolved', 'attemptId': attempt, 'reasons': reasons}
            state_path.write_text(json.dumps(state), encoding='utf-8')
            summary = {'resultClass': 'line-unresolved', 'unresolvedLineReasons': {'line-start-disagreement': 1}}
            chosen = {**summary, 'occurrenceCandidates': evidence['occurrenceCandidates']}
            diagnostics = {**common, 'resultClass': 'line-unresolved', 'reasons': reasons,
                'contentAdmission': admission.to_record(), 'endpointEvidence': summary,
                'selectedEndpointAttemptIndex': 0, 'endpointRouteAttempts': [
                    {'routeId': route + ':baseline', 'endpointEvidence': chosen}
                    for route in ('htdemucs-vocals', 'kimberley-jensen-roformer-vocals', 'sucial-v2-roformer-dry-vocals')]}
            run_path = root / 'run.json'
            run_path.write_text('{}', encoding='utf-8')
            run = {'failureCode': 'line-unresolved', 'summary': {'reasons': reasons}}
            context = SimpleNamespace(root=root, workspace_root=root, song_policy_matches=lambda _: True,
                alignment_state_path=lambda _: state_path, report_store=SimpleNamespace(report_root=lambda _: root,
                alignment_failure_snapshot=lambda *_: (run_path, run, diagnostics)),
                lexical_mapping=lambda _: (mapping, {}), pipeline=lambda: None,
                completed_analysis_audio=lambda *_: declaration['analysisAudio'])
            job = {'videoId': video, 'state': 'LINE_UNRESOLVED', 'outcome': {'code': 'line-unresolved'},
                'lastAlignmentAttemptId': attempt}
            from stemwise_engine.workflow_records import FutureSongError
            for result_class in ('committed-lines', 'committed-mixed'):
                word_state = {**state, 'endpointEvidence': {'resultClass': result_class, 'lineOnlyCount': 1},
                              'reasons': ['low-forced-word-support']}
                state_path.write_bytes(json.dumps(word_state).encode('utf-8'))
                before = {p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()}
                with self.subTest(result_class=result_class), self.assertRaisesRegex(FutureSongError,
                        'word-timing-recovery-required'):
                    failure_source(context, job, reasons_allowed={'line-start-disagreement'})
                self.assertEqual({p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()}, before)
            state_path.write_bytes(json.dumps(state).encode('utf-8'))
            result = failure_source(context, job, reasons_allowed={'line-start-disagreement'})
            self.assertEqual(result['reason'], 'exhausted-endpoint-cascade')
            attempts = diagnostics['endpointRouteAttempts']
            hint = {'routeId': 'htdemucs-vocals:prior-recording-seed', 'endpointEvidence': chosen}
            diagnostics['endpointRouteAttempts'] = [hint, *attempts]
            diagnostics['selectedEndpointAttemptIndex'] = 1
            self.assertEqual(failure_source(context, job, reasons_allowed={'line-start-disagreement'})['reason'],
                             'exhausted-endpoint-cascade')
            diagnostics['endpointRouteAttempts'] = [hint]
            diagnostics['selectedEndpointAttemptIndex'] = 0
            with self.assertRaisesRegex(ValueError, 'baseline cascade'):
                failure_source(context, job, reasons_allowed={'line-start-disagreement'})
            diagnostics['endpointRouteAttempts'] = attempts
            with self.assertRaises(LyricsContentBlocker):
                failure_source(context, job, reasons_allowed={'line-end-disagreement'})
            recognition.unlink()
            before = {p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()}
            with self.assertRaises(ContentEvidenceUnavailable):
                failure_source(context, job, reasons_allowed={'line-start-disagreement'})
            self.assertEqual({p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()}, before)

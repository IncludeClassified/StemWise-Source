"""Repaired native families must consume one final window/occurrence plan."""
import copy
import json
import tempfile
from dataclasses import replace
from unittest.mock import Mock, patch
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'Test suite/pc/unit'))
import stemwise_engine.alignment_endpoints as endpoints
import stemwise_engine.structure_selection as structure_selection
import stemwise_engine.alignment_models as alignment_models
import stemwise_engine.lyrics_align_pc as alignment
from stemwise_engine.common import atomic_write_json
from lyric_content_fixtures import retained_content
from stemwise_engine.lyrics_alignment import AlignmentWorkspace, snapshot_canonical_plain, build_lexical_occurrence_mapping


def retained_state(workspace, video_id, original, raw, indices):
    _, canonical_sha = snapshot_canonical_plain(workspace, video_id, raw)
    admission, _ = retained_content(workspace.job_dir(video_id), original, indices, canonical_sha, 'a' * 64)
    state = {'canonicalPlainSha256': canonical_sha, 'contentBinding': admission.binding,
             'contentAdmission': admission.to_record(), 'pinnedModels': {}}
    atomic_write_json(workspace.state_path(video_id), state)
    return state


class AlignmentWindowSynchronizationTests(unittest.TestCase):
    def endpoint_baseline_case(self, failure, *, second_ready=True, wrong_identity=False, word_only=False):
        scratch = tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work')
        self.addCleanup(scratch.cleanup)
        root = Path(scratch.name)
        workspace = AlignmentWorkspace(root/'alignment', workspace_root=root)
        words = ('one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight')
        if word_only:
            words = tuple(f'{word} in {word}' for word in words)
            words = (*words[:-1], words[0])  # Repeated text keeps distinct occurrence/word owners.
        original = {'id':'original', 'kind':'original', 'format':'plain', 'language':'en',
                    'text':'\n'.join(words)}
        indices = tuple(range(8))
        state = retained_state(workspace, 'abcdefghijk', original,
            json.dumps({'tracks':[original]}).encode(), indices)
        mapping = build_lexical_occurrence_mapping('abcdefghijk', original, indices)
        routes = ('htdemucs-vocals', 'kimberley-jensen-roformer-vocals')
        from stemwise_engine.lyrics_tokenization import display_words
        lines = []
        for index, owner in enumerate(mapping['performedOccurrences']):
            start, end = index*1000+100, index*1000+800
            spans = ((0, 100), (150, 550), (600, 700)) if word_only else ((0, 700),)
            mapped_words = [{'wordId': word_owner['occurrenceWordId'], 'startMs': start+a,
                    'endMs': start+b, 'confidence': .8, 'acousticTokenCount': 1,
                    'startUtf16': left, 'endUtf16': right}
                for word_owner, (a, b), (left, right, _) in zip(
                    owner['words'], spans, display_words(words[index]))]
            lines.append({'lineId':owner['occurrenceId'], 'sourcePhysicalIndex':index,
                'startMs':start, 'endMs':end, 'confidence':.8, 'minimumWordConfidence':.8,
                'displayWordCount':len(mapped_words), 'romanizedWordCount':len(mapped_words),
                'acousticTokenCount':len(mapped_words), 'words':mapped_words})
        verifier = tuple({**copy.deepcopy(line), 'route':endpoints.WAV2VEC2_ROUTE} for line in lines)
        def mms(**options):
            result = copy.deepcopy(lines)
            ready = set(range(7)) if options['audio_view_id'] == routes[0] else {7} if second_ready else set()
            for index, line in enumerate(result):
                if index not in ready:
                    if word_only:
                        line['words'][1]['confidence'] = .01
                        line.update(confidence=1.61/3, minimumWordConfidence=.01)
                    else:
                        line['confidence'] = .01
                        line['minimumWordConfidence'] = .01
                        line['words'][0]['confidence'] = .01
            if wrong_identity:
                result[0]['lineId'] = 'occurrence-'+'f'*24
            return {'lines':result}, True
        failures, checkpoints = [], []
        progress = alignment.AlignmentProgress(state=copy.deepcopy(state), endpoint_attempts=[], checkpoint=lambda:None)
        def checkpoint():
            checkpoints.append(copy.deepcopy(progress.state))
            atomic_write_json(workspace.state_path('abcdefghijk'), progress.state)
        progress.checkpoint = checkpoint
        stage = endpoints.EndpointAlignmentStage(
            request=alignment.EndpointAlignmentRequest(
                context=alignment.AlignmentContext(config={'analysisTimeoutMinutes':1}, video_id='abcdefghijk',
                    workspace=workspace, temp_root=root/'temp', device='cpu', duration_ms=8000, endpoint_duration_ms=8000),
                text=alignment.EndpointText(original=original, alignment=SimpleNamespace(source_physical_indices=indices),
                    lexical_mapping=mapping, sources=list(indices), source_occurrences=indices,
                    display_word_lines=[], acoustic_language_lines=[]),
                evidence=alignment.EndpointEvidence(baseline_segments=[{'segmentIndex':0, 'startLineIndex':0,
                    'endLineIndex':8, 'windowStartMs':0, 'windowEndMs':8000}], seeded_mms_segments=None,
                    verifier_candidates=verifier, verifier_family=endpoints.WAV2VEC2_FAMILY,
                    verifier_reused=True, wav2vec2_language='en', free_family_candidates={}, independent_inputs={},
                    reviewed_lrc_starts={}, route_names={route:'torchaudio-mms-fa-on-'+route for route in routes},
                    content_admission=state['contentAdmission']),
                audio=alignment.EndpointAudio(expected_mix_sha256='a'*64, analysis_audio=None,
                    canonical_mix=Mock(side_effect=AssertionError('unexpected mix inference')),
                    get_endpoint_audio=Mock(side_effect=AssertionError('unexpected audio reconstruction')))),
            services=alignment.EndpointServices(
                workers=alignment.EndpointWorkers(load_mms=mms, load_wav2vec2=Mock(side_effect=failure),
                    load_owsm=Mock(), load_nfa=Mock(), read_audio_provenance=lambda *_,**__:
                        {'audioViews':{route:{'sha256':'b'*64} for route in routes}},
                    verifier_candidates=Mock(), needs_local_verifier=alignment._needs_route_local_verifier),
                events=alignment.AlignmentEvents(attempt=lambda *_,**__:None,
                    failure=lambda *args:failures.append(args), roformer=lambda *_,**__:None),
                progress=progress, release_model=lambda:None, runtime_probe_cache={}))
        return stage, routes, failures, checkpoints

    def test_word_goal_continues_routes_and_keeps_independent_completed_neighbors(self):
        for second_ready in (True, False):
            with self.subTest(second_ready=second_ready):
                stage, routes, failures, checkpoints = self.endpoint_baseline_case(
                    TimeoutError('optional word refinement failed'), second_ready=second_ready, word_only=True)
                evaluate = Mock(wraps=stage.evaluate_endpoint_route_pass)
                route, result = alignment._run_conditional_cascade(routes, evaluate)
                self.assertEqual(evaluate.call_count, 2)
                self.assertEqual(route, routes[1])
                self.assertEqual(checkpoints[0]['endpointEvidence']['wordLineCount'], 7)
                self.assertEqual(result.summary['lineReadyCount'], 8)
                self.assertEqual(result.summary['wordLineCount'], 8 if second_ready else 7)
                self.assertEqual(result.summary['resultClass'], 'committed-words' if second_ready else 'committed-mixed')
                self.assertEqual([bool(line.get('words')) for line in result.timings], [True]*7+[second_ready])
                owners = result.summary['occurrenceCandidates']
                self.assertNotEqual(owners[0]['occurrenceId'], owners[-1]['occurrenceId'])
                self.assertTrue(all(line['words'][0]['startMs'] == i*1000+100
                                    for i, line in enumerate(result.timings[:7])))
                # A later optional producer failure preserves the exact cumulative progress.
                prior = copy.deepcopy(stage.cumulative_endpoint_result)
                with patch.object(stage, 'load_baseline_mms', side_effect=RuntimeError('late worker failed')):
                    with self.assertRaisesRegex(RuntimeError, 'late worker failed'):
                        stage.evaluate_endpoint_route_pass(routes[1])
                self.assertEqual(stage.cumulative_endpoint_result, prior)
                self.assertTrue(failures)

    def test_normal_anchored_word_recovery_reuses_complete_context_and_retains_late_failure(self):
        from stemwise_engine.lyrics_tokenization import display_words
        for failure in (False, True):
            with self.subTest(failure=failure):
                stage, routes, failures, _ = self.endpoint_baseline_case(TimeoutError('unused'), word_only=True)
                baseline_loader = stage.services.workers.load_mms
                verifier = copy.deepcopy(stage.request.evidence.verifier_candidates)
                def shorten(line):
                    for index, word in enumerate(line['words']):
                        word.update(startMs=line['startMs']+120*index,
                                    endMs=line['startMs']+120*index+100)
                    line['endMs'] = line['words'][-1]['endMs']
                for line in verifier:
                    shorten(line)
                stage.request = replace(stage.request,
                    text=replace(stage.request.text, display_word_lines=[display_words(text)
                        for text in stage.request.text.original['text'].splitlines()]),
                    evidence=replace(stage.request.evidence, verifier_candidates=verifier))
                calls = []
                def mms(**options):
                    calls.append(('mms', options['request_variant'], copy.deepcopy(options['segments'])))
                    result, reused = baseline_loader(**options)
                    for line in result['lines']:
                        shorten(line)
                    if options['request_variant'] == 'anchored':
                        if failure: raise RuntimeError('optional anchored worker failed')
                        result['lines'][-1]['words'][1]['confidence'] = .8
                        result['lines'][-1].update(confidence=.8, minimumWordConfidence=.8)
                    return result, reused
                def wav(**options):
                    calls.append(('wav', options['request_variant'], copy.deepcopy(options['segments'])))
                    return {'route': endpoints.WAV2VEC2_ROUTES_BY_AUDIO_VIEW[options['audio_view_id']],
                        'durationMs': 8000, 'lines': list(verifier), 'resolvedSegments': options['segments']}, True, {
                            'modelSha256': 'a'*64, 'snapshotManifestSha256': 'b'*64}
                stage.services = replace(stage.services, workers=replace(stage.services.workers,
                    load_mms=mms, load_wav2vec2=wav, verifier_candidates=alignment._forced_alignment_verifier_candidates))
                complete, result = stage.evaluate_endpoint_route_pass(routes[0])
                self.assertEqual(complete, not failure)
                self.assertEqual(result.summary['wordLineCount'], 7 if failure else 8)
                self.assertEqual(result.summary['lineReadyCount'], 8)
                self.assertEqual([(family, variant) for family, variant, _ in calls],
                    [('mms', 'baseline'), ('wav', 'baseline'), ('wav', 'anchored'), ('mms', 'anchored')])
                self.assertEqual(calls[-1][2], calls[-2][2])
                self.assertEqual([(segment['startLineIndex'], segment['endLineIndex']) for segment in calls[-1][2]],
                    [(i, i+1) for i in range(8)])
                self.assertEqual([word['startMs'] for line in result.timings[:7] for word in line['words']],
                    [i*1000+100+120*j for i in range(7) for j in range(3)])
                self.assertEqual(bool(failures), failure)

    def test_bound_independent_words_upgrade_line_only_without_erasing_contradictions(self):
        stage, routes, _, _ = self.endpoint_baseline_case(TimeoutError('unused'), word_only=True)
        forced, _ = stage.load_baseline_mms(routes[0])
        baseline = endpoints._refine_public_timings(forced, stage.request.evidence.verifier_candidates,
            include_unresolved=True)
        witnesses = copy.deepcopy(stage.request.evidence.verifier_candidates)
        self.assertEqual(baseline[1]['wordLineCount'], 7)
        upgraded, summary = endpoints._apply_independent_endpoint_consensus(baseline,
            {endpoints.OWSM_CTC_FAMILY: witnesses}, include_unresolved=True)
        self.assertEqual(summary['resultClass'], 'committed-mixed')
        self.assertEqual(summary['wordLineCount'], 7)
        from stemwise_engine.lyrics_tokenization import display_words
        display = [display_words(line) for line in stage.request.text.original['text'].splitlines()]
        stage.request = replace(stage.request,
            evidence=replace(stage.request.evidence, independent_inputs={'en': (list(range(8)),
                stage.request.text.lexical_mapping, display)}),
            audio=replace(stage.request.audio, canonical_mix=Mock(return_value=(Path('bound.wav'),
                {'stream': {'durationMs': 8000}}))))
        loader = Mock(return_value=(witnesses, True))
        stage.services = replace(stage.services, workers=replace(stage.services.workers, load_owsm=loader))
        (stage_timings, stage_summary), _, _ = stage.apply_independent_consensus(baseline)
        self.assertEqual(stage_summary['resultClass'], 'committed-mixed')
        self.assertEqual(stage_timings, upgraded)
        stage.apply_independent_consensus(baseline)
        self.assertEqual(loader.call_count, 1, 'Exact word recovery must reuse the stage-owned completed witness')
        self.assertEqual(upgraded[:7], baseline[0][:7])
        self.assertNotIn('words', upgraded[-1], 'Lexical onset evidence cannot replace a missing word-end witness')
        retained_witness = summary['occurrenceCandidates'][-1]['familyCandidates'][endpoints.OWSM_CTC_FAMILY]
        self.assertEqual([(word['occurrenceWordId'], word['startMs'], word['endMs'])
                          for word in retained_witness['words']],
                         [(word['wordId'], word['startMs'], word['endMs']) for word in witnesses[-1]['words']])
        repaired = copy.deepcopy(forced)
        repaired['lines'][-1]['words'][1]['confidence'] = .8
        repaired['lines'][-1].update(confidence=.8, minimumWordConfidence=.8)
        complete = endpoints._refine_public_timings(repaired, stage.request.evidence.verifier_candidates,
                                                   include_unresolved=True)
        qualified, qualified_summary = endpoints._apply_independent_endpoint_consensus(complete,
            {endpoints.OWSM_CTC_FAMILY: witnesses}, include_unresolved=True)
        self.assertEqual(qualified_summary['resultClass'], 'committed-words')
        self.assertEqual(qualified[-1]['words'], [
            {'startUtf16': 0, 'endUtf16': 3, 'startMs': 7100, 'endMs': 7200},
            {'startUtf16': 4, 'endUtf16': 6, 'startMs': 7250, 'endMs': 7650},
            {'startUtf16': 7, 'endUtf16': 10, 'startMs': 7700, 'endMs': 7800}])
        for fault in ('owner', 'incomplete', 'contradiction'):
            with self.subTest(fault=fault):
                changed = copy.deepcopy(witnesses)
                if fault == 'owner': changed[-1]['words'][0]['wordId'] = witnesses[0]['words'][0]['wordId']
                elif fault == 'incomplete': changed[-1]['words'] = []
                else: changed[-1]['words'][1]['startMs'] += endpoints.WORD_START_AGREEMENT_MS+1
                output, rejected = endpoints._apply_independent_endpoint_consensus(baseline,
                    {endpoints.OWSM_CTC_FAMILY: changed}, include_unresolved=True)
                self.assertEqual(rejected['wordLineCount'], 7)
                self.assertNotIn('words', output[-1])
        changed = copy.deepcopy(witnesses)
        changed[-1]['words'][1]['startMs'] += endpoints.WORD_START_AGREEMENT_MS+1
        revoked = endpoints._apply_independent_endpoint_consensus((qualified, qualified_summary),
            {endpoints.OWSM_CTC_FAMILY: changed}, include_unresolved=True)
        self.assertEqual(revoked[1]['occurrenceCandidates'][-1]['publishDecision']['code'], 'qualified-word-contradiction')
        for merge in (endpoints._merge_endpoint_route_passes, endpoints._merge_endpoint_passes):
            output, retained = merge(revoked, (qualified, qualified_summary))
            self.assertEqual(retained['wordLineCount'], 7)
            self.assertNotIn('words', output[-1])

    def test_anchored_word_upgrade_requires_exact_current_mapping_and_pronunciation(self):
        from stemwise_engine.workflow_records import LyricsContentBlocker
        stage, routes, _, _ = self.endpoint_baseline_case(TimeoutError('unused'), word_only=True)
        forced, _ = stage.load_baseline_mms(routes[0])
        verifier = stage.request.evidence.verifier_candidates
        baseline = endpoints._refine_public_timings(forced, verifier, include_unresolved=True)
        repaired = copy.deepcopy(forced)
        repaired['lines'][-1]['words'][1]['confidence'] = .8
        repaired['lines'][-1].update(confidence=.8, minimumWordConfidence=.8)
        complete = endpoints._refine_public_timings(repaired, verifier, include_unresolved=True)
        for merge in (endpoints._merge_endpoint_route_passes, endpoints._merge_endpoint_passes):
            output, summary = merge(baseline, complete)
            self.assertEqual(summary['resultClass'], 'committed-words')
            self.assertEqual(output[:7], baseline[0][:7])
            wrong = copy.deepcopy(complete)
            wrong[1]['occurrenceCandidates'][-1]['occurrenceId'] = baseline[1]['occurrenceCandidates'][0]['occurrenceId']
            with self.assertRaisesRegex(ValueError, 'occurrence identity'):
                merge(baseline, wrong)
        stage.require_timing_recovery(baseline)
        for fault in ('word-owner', 'word-count'):
            invalid = copy.deepcopy(forced)
            if fault == 'word-owner': invalid['lines'][-1]['words'][0]['wordId'] = forced['lines'][0]['words'][0]['wordId']
            else: invalid['lines'][-1]['words'] = []
            rejected, bad_routes, _, _ = self.endpoint_baseline_case(AssertionError('must not infer'), word_only=True)
            next_model = Mock(side_effect=AssertionError('ambiguous content must not start word recovery'))
            rejected.services = replace(rejected.services, workers=replace(rejected.services.workers,
                load_mms=lambda **_: (invalid, True), load_wav2vec2=next_model))
            with self.subTest(fault=fault), self.assertRaises(LyricsContentBlocker):
                rejected.evaluate_endpoint_route_pass(bad_routes[0])
            next_model.assert_not_called()
            self.assertIsNone(rejected.cumulative_endpoint_result)
        for reason in ('stable-word-id-disagreement', 'word-count-disagreement', 'incomplete-display-word-map'):
            changed = copy.deepcopy(baseline)
            changed[1]['occurrenceCandidates'][-1]['publishDecision']['code'] = reason
            with self.subTest(reason=reason), self.assertRaises(LyricsContentBlocker):
                stage.require_timing_recovery(changed)
        mismatched = copy.deepcopy(baseline)
        mismatched[1]['occurrenceCandidates'][-1]['familyCandidates']['mms-fa']['words'][0]['occurrenceWordId'] = 'wrong'
        with self.assertRaises(LyricsContentBlocker):
            stage.require_timing_recovery(mismatched)
        original = stage.request.text.original
        with patch('stemwise_engine.lyric_content.require_pronunciation_support',
                side_effect=LyricsContentBlocker('pronunciation-support-unavailable')):
            with self.assertRaises(LyricsContentBlocker):
                stage.require_timing_recovery(baseline)
        stage.request = replace(stage.request, audio=replace(stage.request.audio, expected_mix_sha256='c'*64))
        with self.assertRaises(LyricsContentBlocker):
            stage.require_timing_recovery(baseline)

    def test_optional_verifier_failure_retains_seven_lines_for_the_complementary_route(self):
        from stemwise_engine.provider_operations import ProviderStageDeadline
        for failure in (TimeoutError('optional deadline'), RuntimeError('optional model failure'),
                        OSError('optional I/O'), ValueError('optional malformed result'),
                        ProviderStageDeadline('optional stage expired')):
            with self.subTest(failure=type(failure).__name__):
                stage, routes, failures, checkpoints = self.endpoint_baseline_case(failure)
                route, result = alignment._run_conditional_cascade(routes, stage.evaluate_endpoint_route_pass)
                self.assertEqual(route, routes[1])
                self.assertEqual(result.summary['lineReadyCount'], 8)
                self.assertEqual([(line['startMs'],line['endMs']) for line in result.timings],
                    [(index*1000+100,index*1000+800) for index in range(8)])
                self.assertEqual(len(failures), 2)
                self.assertEqual(checkpoints[0]['endpointEvidence']['lineReadyCount'], 7)
                first = stage.services.progress.endpoint_attempts[0]
                self.assertTrue(first['endpointEvidence']['routeLocalVerifierAttempted'])
                self.assertEqual(first['endpointEvidence']['routeLocalVerifierFilledLineCount'], 0)
                self.assertFalse(first['reused'])
                durable = json.loads(stage.request.context.workspace.state_path('abcdefghijk').read_bytes())
                self.assertEqual(durable['endpointEvidence']['lineReadyCount'], 8)

    def test_optional_verifier_fallback_never_approves_the_remaining_unresolved_line(self):
        stage, routes, _, _ = self.endpoint_baseline_case(TimeoutError('optional deadline'), second_ready=False)
        _, result = alignment._run_conditional_cascade(routes, stage.evaluate_endpoint_route_pass)
        self.assertEqual(result.summary['lineReadyCount'], 7)
        self.assertEqual(result.summary['resultClass'], 'line-unresolved')
        self.assertEqual(result.summary['occurrenceCandidates'][7]['publishDecision']['result'], 'unresolved')

    def test_optional_verifier_fallback_preserves_fatal_content_budget_and_identity_guards(self):
        from stemwise_engine.acoustic_inputs import AcousticBudgetExceeded
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        from stemwise_engine.workflow_records import LyricsContentBlocker
        for failure in (WorkerRetirementError('worker owns resources'),
                        LyricsContentBlocker('content-binding-differs'),
                        AcousticBudgetExceeded('acoustic-memory-capacity-unavailable'),
                        ProviderCancelled('cancelled'), ProviderDeadline('whole operation expired'), KeyboardInterrupt()):
            with self.subTest(failure=type(failure).__name__):
                stage, routes, failures, checkpoints = self.endpoint_baseline_case(failure)
                evaluate = Mock(wraps=stage.evaluate_endpoint_route_pass)
                with self.assertRaises(type(failure)):
                    alignment._run_conditional_cascade(routes, evaluate)
                self.assertEqual(evaluate.call_count, 1)
                self.assertIsNone(stage.cumulative_endpoint_result)
                self.assertFalse(failures)
                self.assertFalse(checkpoints)
        stage, routes, failures, checkpoints = self.endpoint_baseline_case(TimeoutError(), wrong_identity=True)
        with self.assertRaisesRegex(ValueError, 'stable occurrence IDs'):
            stage.evaluate_endpoint_route_pass(routes[0])
        self.assertIsNone(stage.cumulative_endpoint_result)
        self.assertFalse(failures)
        self.assertFalse(checkpoints)

    def test_other_endpoint_fallbacks_preserve_the_same_cancellation_and_budget_fences(self):
        from stemwise_engine.acoustic_inputs import AcousticBudgetExceeded
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        for boundary in ('consensus', 'seed', 'anchored-mms'):
            for failure in (ProviderCancelled('cancelled'), ProviderDeadline('operation expired'),
                            AcousticBudgetExceeded('acoustic-memory-capacity-unavailable')):
                with self.subTest(boundary=boundary, failure=type(failure).__name__):
                    stage, routes, failures, checkpoints = self.endpoint_baseline_case(TimeoutError())
                    stage.services = replace(stage.services, workers=replace(stage.services.workers,
                        needs_local_verifier=lambda *_, **__:False))
                    if boundary == 'consensus':
                        stage.request = replace(stage.request, evidence=replace(stage.request.evidence,
                            independent_inputs={'en':([], {}, [])}))
                        stage.load_independent_witness = Mock(side_effect=failure)
                        invoke = lambda: alignment._run_conditional_cascade(routes, stage.evaluate_endpoint_route_pass)
                    else:
                        stage.services = replace(stage.services, workers=replace(stage.services.workers,
                            load_mms=Mock(side_effect=failure)))
                        if boundary == 'seed':
                            stage.request = replace(stage.request, evidence=replace(stage.request.evidence,
                                seeded_mms_segments=stage.request.evidence.baseline_segments))
                            invoke = lambda: alignment._run_conditional_cascade(routes, stage.evaluate_endpoint_route)
                        else:
                            invoke = lambda: stage.repair_anchored(routes[0], stage.request.evidence.baseline_segments,
                                stage.request.evidence.verifier_candidates)
                    with self.assertRaises(type(failure)):
                        invoke()
                    self.assertIsNone(stage.cumulative_endpoint_result)
                    self.assertFalse(failures)
                    self.assertFalse(checkpoints)

    def test_missing_acoustic_input_restores_once_while_cached_results_need_no_audio(self):
        from stemwise_engine.worker_protocol import MissingAlignmentAudio
        for mode in ('cache-hit', 'restore', 'restored-missing', 'repeated-absence'):
            with self.subTest(mode=mode):
                stage = self.check_plan(unprompted=True)
                audio = stage.request.context.temp_root.parent / 'restored.wav'
                audio.write_bytes(b'exact fixture acoustic input')
                segments = stage.request.evidence.baseline_segments
                success = ({'resolvedSegments': segments}, True,
                    {'modelSha256': 'a'*64, 'snapshotManifestSha256': 'b'*64})
                replies = [success] if mode == 'cache-hit' else [MissingAlignmentAudio('acoustic input missing'),
                    MissingAlignmentAudio('acoustic input missing again') if mode == 'repeated-absence' else success]
                loader = Mock(side_effect=replies)
                restore = Mock(return_value=(audio if mode != 'restored-missing' else audio.with_name('absent.wav'), {}, {}))
                stage.request = replace(stage.request, audio=replace(stage.request.audio, get_endpoint_audio=restore))
                stage.services = replace(stage.services, workers=replace(stage.services.workers,
                    load_wav2vec2=loader, verifier_candidates=lambda *a, **kw: ('verified',)*3))
                if mode in {'restored-missing', 'repeated-absence'}:
                    with self.assertRaises(MissingAlignmentAudio):
                        stage.load_route_wav2vec2('htdemucs-vocals', request_variant='baseline', segments=segments)
                else:
                    result = stage.load_route_wav2vec2('htdemucs-vocals', request_variant='baseline', segments=segments)
                    self.assertEqual(result, (('verified',)*3, True, segments))
                self.assertEqual(restore.call_count, 0 if mode == 'cache-hit' else 1)
                self.assertEqual(loader.call_count, 1 if mode in {'cache-hit', 'restored-missing'} else 2)

    def test_missing_wav2vec2_dependency_does_not_restore_audio_or_loop(self):
        stage = self.check_plan(unprompted=True)
        loader = Mock(side_effect=[FileNotFoundError('missing numerical helper'), RuntimeError('unexpected retry')])
        restore = Mock(return_value=(Path('available.wav'), {}, {}))
        stage.request = replace(stage.request, audio=replace(stage.request.audio, get_endpoint_audio=restore))
        stage.services = replace(stage.services, workers=replace(stage.services.workers, load_wav2vec2=loader))
        with self.assertRaisesRegex(FileNotFoundError, 'missing numerical helper'):
            stage.load_route_wav2vec2('htdemucs-vocals', request_variant='baseline',
                                     segments=stage.request.evidence.baseline_segments)
        loader.assert_called_once()
        restore.assert_not_called()

    def test_mms_and_wav_internal_repairs_rejoin_on_exact_final_windows(self):
        self.check_plan(unprompted=False)

    def test_unprompted_verifier_recomputes_identity_after_mms_internal_merge(self):
        self.check_plan(unprompted=True)

    def test_repeated_anchored_plan_fails_technically_without_replacing_prior_result(self):
        stage = self.check_plan(unprompted=False)
        initial = copy.deepcopy(stage.request.evidence.baseline_segments)
        short = copy.deepcopy(initial)
        short[-1]['windowEndMs'] -= 1
        mms_calls = []
        wav_calls = []
        failures = []
        prior = ([], {'resultClass': 'line-unresolved', 'lineReadyCount': 1})
        stage.cumulative_endpoint_result = prior
        state_before = copy.deepcopy(stage.services.progress.state)
        def mms(**request):
            mms_calls.append(copy.deepcopy(request['segments']))
            if len(mms_calls) > 1:
                raise AssertionError('anchored plan cycle started another cached MMS attempt')
            return {'resolvedSegments': copy.deepcopy(short), 'lines': [{}, {}, {}]}, True
        def wav(**request):
            wav_calls.append(copy.deepcopy(request['segments']))
            return {'resolvedSegments': copy.deepcopy(initial), 'lines': [{}, {}, {}]}, True, {
                'modelSha256': 'a'*64, 'snapshotManifestSha256': 'b'*64}
        stage.services = replace(stage.services,
            workers=replace(stage.services.workers, load_mms=mms, load_wav2vec2=wav,
                verifier_candidates=lambda *_, **__: ('verifier',)*3),
            events=replace(stage.services.events, failure=lambda *args: failures.append(args)))
        paired, _, _ = stage.repair_anchored('htdemucs-vocals', initial, ('verifier',)*3)
        self.assertIsNone(paired)
        self.assertIs(stage.cumulative_endpoint_result, prior)
        self.assertEqual(stage.services.progress.state, state_before)
        self.assertEqual(mms_calls, [initial])
        self.assertEqual(wav_calls, [short])
        self.assertEqual(len(failures), 1)
        self.assertEqual(failures[0][:2], ('endpoint-bootstrap', 'htdemucs-vocals:anchored-mms'))
        self.assertEqual(failures[0][2], 'endpoint-anchored-plan-no-progress')

    def check_plan(self, *, unprompted):
        scratch = tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work')
        self.addCleanup(scratch.cleanup)
        root = Path(scratch.name)
        workspace = AlignmentWorkspace(root/'alignment', workspace_root=root)
        original = {'id':'original','kind':'original','format':'plain','language':'en',
                    'text':'One two\nThree four\nOne two'}
        state = retained_state(workspace, 'abcdefghijk', original,
                               json.dumps({'tracks':[original]}).encode(), (0,1,2))
        initial=[{'segmentIndex':index,'startLineIndex':index,'endLineIndex':index+1,
                  'windowStartMs':index*1000,'windowEndMs':(index+1)*1000}
                 for index in range(3)]
        widened=endpoints._merge_short_bootstrap_segment(initial,1,boundary='start')
        final=endpoints._merge_short_bootstrap_segment(widened,1,boundary='start')
        mms_calls=[]
        wav_calls=[]
        failures=[]
        identity={'stream':{'sampleRate':16000,'channelCount':2,'frameCount':48000,'durationMs':3000}}
        def mms(**kwargs):
            mms_calls.append(copy.deepcopy(kwargs['segments']))
            resolved=final if unprompted or len(mms_calls)>1 else widened
            return {'resolvedSegments':copy.deepcopy(resolved),'lines':[{}, {}, {}]},True
        def wav(**kwargs):
            wav_calls.append(copy.deepcopy(kwargs['segments']))
            self.assertEqual(kwargs['segments'],widened,
                             'Wav must first use the exact plan actually resolved by MMS')
            return {'resolvedSegments':copy.deepcopy(final),'marker':'final native verifier'},True,{
                'modelSha256':'a'*64,'snapshotManifestSha256':'b'*64}
        def candidates(result,*_,**__):
            self.assertEqual(result['resolvedSegments'],final)
            return ('final native verifier',)*3
        stage=endpoints.EndpointAlignmentStage(
            request=alignment.EndpointAlignmentRequest(
                context=alignment.AlignmentContext(config={'analysisTimeoutMinutes':1}, video_id='abcdefghijk',
                    workspace=workspace, temp_root=root/'temp', device='cpu', duration_ms=3000, endpoint_duration_ms=3000),
                text=alignment.EndpointText(original=original, alignment=SimpleNamespace(source_physical_indices=(0,1,2)),
                    lexical_mapping={}, sources=[0,1,2], source_occurrences=(0,1,2),
                    display_word_lines=[], acoustic_language_lines=[]),
                evidence=alignment.EndpointEvidence(baseline_segments=initial, seeded_mms_segments=None,
                    verifier_candidates=('original verifier',)*3,
                    verifier_family=endpoints.UNPROMPTED_WHISPER_FAMILY if unprompted else endpoints.WAV2VEC2_FAMILY,
                    verifier_reused=True, wav2vec2_language='en', free_family_candidates={}, independent_inputs={},
                    reviewed_lrc_starts={}, route_names={}, content_admission=state['contentAdmission']),
                audio=alignment.EndpointAudio(expected_mix_sha256='a'*64, analysis_audio=Path('native.wav'),
                    canonical_mix=lambda:None, get_endpoint_audio=lambda _:None)),
            services=alignment.EndpointServices(
                workers=alignment.EndpointWorkers(load_mms=mms, load_wav2vec2=wav,
                    load_owsm=lambda **_:None, load_nfa=lambda **_:None,
                    read_audio_provenance=lambda *_,**__: {'audioViews':{'htdemucs-vocals':identity}},
                    verifier_candidates=candidates, needs_local_verifier=lambda *_,**__:False),
                events=alignment.AlignmentEvents(attempt=lambda *a,**k:None,
                    failure=lambda *args:failures.append(args), roformer=lambda *a,**k:None),
                progress=alignment.AlignmentProgress(state=copy.deepcopy(state), endpoint_attempts=[], checkpoint=lambda:None),
                release_model=lambda:None, runtime_probe_cache={}))
        paired,segments,_=stage.repair_anchored('htdemucs-vocals',initial,('original verifier',)*3)
        forced,verifier=paired.require_plan(segments)
        self.assertEqual(segments,final)
        self.assertEqual(forced['resolvedSegments'],final)
        self.assertFalse(failures)
        self.assertEqual(mms_calls,[initial] if unprompted else [initial,final])
        self.assertEqual(wav_calls,[] if unprompted else [widened])
        self.assertEqual(verifier,('original verifier',)*3 if unprompted else ('final native verifier',)*3)
        return stage

    def test_independent_witness_lazy_audio_and_cache_belong_to_the_stage(self):
        stage=self.check_plan(unprompted=True)
        calls=[]
        identity={'stream':{'durationMs':3000}}
        mix=Mock(return_value=(Path('mix.wav'),identity))
        def witness(**request):
            calls.append(request)
            return ({'lineIndex':0},{'lineIndex':1},{'lineIndex':2}),True
        stage.request=replace(stage.request,
            audio=replace(stage.request.audio,canonical_mix=mix),
            evidence=replace(stage.request.evidence,independent_inputs={'en':([0,1,2],{},[])}))
        stage.services=replace(stage.services,workers=replace(stage.services.workers,load_owsm=witness))
        first=stage.load_independent_witness(endpoints.OWSM_CTC_FAMILY)
        self.assertEqual(first,stage.load_independent_witness(endpoints.OWSM_CTC_FAMILY))
        self.assertEqual(len(calls),1)
        mix.assert_called_once_with()
        self.assertEqual(stage.mix_audio,Path('mix.wav'))
        self.assertIsNone(stage.request.audio.mix_audio)
        self.assertIsNone(stage.request.audio.mix_identity)
        other=endpoints.EndpointAlignmentStage(request=stage.request,services=stage.services)
        self.assertEqual(other.independent_witnesses,{})
        self.assertIsNone(other.mix_audio)

    def test_independent_component_failure_keeps_valid_consensus_and_exact_loader_cache_on_resume(self):
        import subprocess
        from stemwise_engine.lyrics_tokenization import display_words
        from test_alignment_batch_publication import install_worker_sources
        stage, routes, failures, _ = self.endpoint_baseline_case(RuntimeError('unused'))
        root = stage.request.context.temp_root.parent
        project = root/'builder'
        (project/'src').mkdir(parents=True)
        install_worker_sources(project)
        helper = 'owsm_ctc_forced_alignment.py'
        (project/'src'/helper).write_bytes((ROOT/'PC side/stemwise-playlist-builder/src'/helper).read_bytes())
        python = root/'runtime/espnet-202511-owsm/.venv/bin/python'
        python.parent.mkdir(parents=True)
        python.touch()
        audio = root/'mix.wav'
        audio.write_bytes(b'exact synthetic recording')
        identity = {'sha256': alignment._sha256_file(audio), 'byteCount': audio.stat().st_size,
            'stream': {'durationMs': 8000}}
        mapping = stage.request.text.lexical_mapping
        words = [display_words(line) for line in stage.request.text.original['text'].split('\n')]
        inputs = {language: (indices, {**mapping, 'performedOccurrences': [
            {**mapping['performedOccurrences'][index], 'performedIndex': local}
            for local, index in enumerate(indices)]}, [words[index] for index in indices])
            for language, indices in (('en', list(range(7))), ('tr', [7]))}
        stage.request = replace(stage.request,
            context=replace(stage.request.context, config={'projectDir': str(project), 'analysisTimeoutMinutes': 1}),
            text=replace(stage.request.text, display_word_lines=words),
            evidence=replace(stage.request.evidence, independent_inputs=inputs),
            audio=replace(stage.request.audio, canonical_mix=lambda: (audio, identity)))
        stage.services = replace(stage.services, workers=replace(stage.services.workers,
            load_owsm=alignment._load_or_run_owsm_ctc_alignment))
        stage.route_diagnostic = lambda error, *_: str(error)
        forced, _ = stage.services.workers.load_mms(audio_view_id=routes[0])
        for line in forced['lines']:
            line['confidence'] = .01
        baseline = endpoints._refine_public_timings(forced, stage.request.evidence.verifier_candidates,
            {}, include_unresolved=True)
        self.assertEqual(baseline[1]['lineReadyCount'], 0)
        calls = []
        def request(_config, language, _audio, audio_identity, lines, duration_ms, **_):
            return {'language': language, 'audio': audio_identity, 'lines': lines, 'durationMs': duration_ms,
                'model': {}, 'source': {}, 'runtime': {}}
        def numerical(**options):
            value = options['request']
            language = value['language']
            calls.append(language)
            if language == 'tr' and calls.count('tr') == 1:
                return subprocess.CompletedProcess([], 1, '', 'retired second-language failure')
            lines = []
            for index, line in enumerate(value['lines']):
                source = line['sourcePhysicalIndex']
                start, end = source*1000+100, source*1000+800
                lines.append({'lineIndex': index, 'lineId': line['lineId'], 'sourcePhysicalIndex': source,
                    'startMs': start, 'endMs': end, 'words': [{'wordId': word['wordId'],
                        'startMs': start, 'endMs': end} for word in line['words']]})
            result = {'engine': alignment.owsm_ctc.ENGINE,
                'route': alignment.owsm_ctc.ROUTES_BY_LANGUAGE[language],
                'formatVersion': alignment.owsm_ctc.FORMAT_VERSION, 'durationMs': 8000,
                'audio': {key: identity[key] for key in ('sha256', 'byteCount')},
                'request': {'sha256': options['request_sha256'],
                    'byteCount': len((json.dumps(value, ensure_ascii=False, separators=(',', ':'))+'\n').encode())},
                'lineCount': len(lines), 'lines': lines}
            options['output_path'].write_text(json.dumps(result), encoding='utf-8')
            return subprocess.CompletedProcess([], 0, '', '')
        with patch.object(alignment, '_owsm_ctc_request', side_effect=request), \
                patch.object(alignment, '_run_closed_alignment_worker', side_effect=numerical):
            partial, used, reused = stage.apply_independent_consensus(baseline)
            self.assertEqual(calls, ['en', 'tr'], failures)
            cache = stage.request.context.workspace.job_dir('abcdefghijk')/'owsm-ctc-public-endpoints-v1-en.private.json'
            self.assertTrue(cache.is_file(), failures)
            self.assertTrue(used)
            self.assertFalse(reused)
            self.assertEqual(partial[1]['lineReadyCount'], 0)
            self.assertEqual(partial[1]['resultClass'], 'line-unresolved')
            self.assertEqual([endpoints.OWSM_CTC_FAMILY in row['familyCandidates']
                              for row in partial[1]['occurrenceCandidates']], [True]*7+[False])
            self.assertEqual(partial[1]['occurrenceCandidates'][7]['publishDecision']['result'], 'unresolved')
            retained = cache.read_bytes()
            self.assertEqual(len(failures), 1)
            resumed = endpoints.EndpointAlignmentStage(request=stage.request, services=stage.services)
            complete, used, reused = resumed.apply_independent_consensus(baseline)
            self.assertTrue(used)
            self.assertFalse(reused)
            self.assertEqual(complete[1]['lineReadyCount'], 0)
            self.assertTrue(all(endpoints.OWSM_CTC_FAMILY in row['familyCandidates']
                                for row in complete[1]['occurrenceCandidates']))
            self.assertEqual(calls, ['en', 'tr', 'tr'])
            self.assertEqual(cache.read_bytes(), retained)


class AlignmentOwnershipTests(unittest.TestCase):
    def test_model_owner_reuses_one_model_and_retires_before_releasing_the_lease(self):
        order=[]
        lease=Mock()
        lease.__enter__=Mock(side_effect=lambda:order.append('lease-acquired'))
        lease.__exit__=Mock(side_effect=lambda *a:order.append('lease-released'))
        model=Mock()
        model.close.side_effect=lambda:order.append('model-retired')
        with patch.object(alignment_models,'heavy_worker_stage',return_value=lease), \
                patch.object(alignment_models,'FasterWhisperEvidence',return_value=model) as load, \
                patch.object(alignment_models.gc,'collect',side_effect=lambda:order.append('cuda-retired')):
            owner=alignment_models.WhisperModelOwner(config={'analysisTimeoutMinutes':1},model_name='test',
                snapshot=Path('model'),device='cpu',compute_type='float32')
            self.assertIs(owner.get(),model)
            self.assertIs(owner.get(),model)
            load.assert_called_once()
            owner.close()
            owner.close()
        self.assertEqual(order,['lease-acquired','model-retired','cuda-retired','lease-released'])
        self.assertIsNone(owner.model)
        self.assertIsNone(owner.lease)

    def test_publication_preserves_owner_on_rejection_and_commits_the_exact_candidate(self):
        from stemwise_engine.lyrics_alignment import (AlignmentMetrics,AlignmentResult,AlignmentWorkspace,
            build_lexical_occurrence_mapping,save_alignment_artifacts,commit_candidate,build_synced_sidecar)
        from stemwise_engine.alignment_publication import AlignmentPublisher
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root=Path(name)
            workspace=AlignmentWorkspace(root/'alignment',workspace_root=root)
            video_id='abcdefghijk'
            original={'id':'original','kind':'original','format':'plain','language':'en',
                      'text':'One two three four','source':{'name':'Known fixture'}}
            raw=json.dumps({'tracks':[original]}).encode()
            owner=root/'owner'/f'{video_id}.lyrics.json'
            owner.parent.mkdir()
            owner.write_bytes(raw)
            state = retained_state(workspace, video_id, original, raw, (0,))
            result=AlignmentResult(accepted=True,reasons=(),starts_ms=(100,),source_physical_indices=(0,),
                metrics=AlignmentMetrics(lyric_word_count=4,transcript_word_count=4,matched_word_count=4,
                    directly_matched_line_count=1,line_count=1,lyric_word_coverage=1.,transcript_word_coverage=1.,
                    direct_line_coverage=1.,average_word_score=100.,timed_span_ratio=.8))
            progress=copy.deepcopy(state)
            selection=structure_selection.StructureSelectionStage(
                request=alignment.StructureSelectionRequest(video_id=video_id,original=original,duration_ms=1000,
                    repeat_structure_kind='none',reviewed_baseline_structure=False,
                    performed_structure_review_sha256=None, evidence_root=workspace.job_dir(video_id)),
                options=[('youtube-caption',result,{'type':'youtube-caption'},True)],
                merge_transcripts=[],progress=progress).select()
            self.assertIsInstance(selection,alignment.StructureSelectionResult)
            self.assertIs(selection.alignment,result)
            self.assertEqual((selection.label,selection.reused),('youtube-caption',True))
            self.assertTrue(progress['internalRepeatAuditComplete'])
            mapping=build_lexical_occurrence_mapping(video_id,original,(0,))
            candidate=build_synced_sidecar({'tracks':[original]},result,1000,
                line_timings=[{'startMs':100,'endMs':900}])
            endpoint=alignment.EndpointSelectionResult(timings=({'startMs':100,'endMs':900},),
                summary={'resultClass':'committed-lines'},reused=True,attempt_index=0)
            request=alignment.AlignmentPublicationRequest(workspace=workspace,video_id=video_id,sidecar_path=owner,
                original_raw=raw,expected_current_raw=raw,candidate=candidate,alignment=result,lexical_mapping=mapping,
                public_line_timings=list(endpoint.timings),summary={'accepted':True},endpoint_selection=endpoint,no_commit=True)
            publisher=AlignmentPublisher()
            for result_class in ('committed-lines', 'committed-mixed', 'committed-words'):
                incomplete = replace(endpoint, summary={'resultClass': result_class,
                    'reasons': [], 'lineOnlyReasons': {'low-forced-word-support': 1}})
                pending = copy.deepcopy(state)
                publication = publisher.publish(replace(request, no_commit=False, endpoint_selection=incomplete), pending)
                self.assertEqual((publication.status, publication.counter), ('line-unresolved', 'reviewRequired'))
                self.assertEqual(pending['reasons'], ['incomplete-display-word-map']
                    if result_class == 'committed-words' else ['low-forced-word-support'])
                self.assertEqual(owner.read_bytes(), raw)
            from stemwise_engine.lyrics_tokenization import display_word_ranges
            timings=[{'startMs':100,'endMs':900,'words':[
                {'startUtf16':start,'endUtf16':end,'startMs':100+i*200,'endMs':250+i*200}
                for i,(start,end) in enumerate(display_word_ranges(original['text']))]}]
            candidate=build_synced_sidecar({'tracks':[original]},result,1000,line_timings=timings)
            endpoint = replace(endpoint, timings=tuple(timings), summary={'resultClass':'committed-words'})
            request = replace(request, candidate=candidate, public_line_timings=timings, endpoint_selection=endpoint)
            self.assertEqual(publisher.publish(request,state).status,'candidate')
            self.assertEqual(owner.read_bytes(),raw)
            self.assertEqual(json.loads(workspace.candidate_path(video_id).read_bytes()),candidate)
            replacement=raw+b' '
            owner.write_bytes(replacement)
            with self.assertRaisesRegex(ValueError,'sidecar changed'):
                publisher.publish(replace(request,no_commit=False),copy.deepcopy(state))
            self.assertEqual(owner.read_bytes(),replacement)
            self.assertEqual(json.loads(workspace.candidate_path(video_id).read_bytes()),candidate)
            owner.write_bytes(raw)
            committed=publisher.publish(replace(request,no_commit=False),copy.deepcopy(state))
            self.assertEqual((committed.status,committed.counter),('committed-words','committed'))
            self.assertEqual(json.loads(owner.read_bytes()),candidate)
            self.assertEqual(workspace.current_backup_path(video_id).read_bytes(),raw)


if __name__=='__main__':
    unittest.main()

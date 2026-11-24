import copy
import json
from dataclasses import replace
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from typing import Any
from unittest.mock import Mock, patch

ROOT=Path(__file__).resolve().parents[3]
sys.path[:0]=[str(ROOT/'Test suite/pc/unit'),str(Path(__file__).parent)]
import stemwise_engine.worker_protocol as worker_protocol
import stemwise_engine.alignment_endpoints as endpoints
import stemwise_engine.alignment_audio as alignment_audio
import stemwise_engine.lyrics_align_pc as worker
from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping
from stemwise_engine.lyrics_tokenization import display_words
from lyric_content_fixtures import retained_content
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from process_fixture import prepare_runtime_layout


class NativeSeedIntegrationTests(unittest.TestCase):
    def test_complete_later_route_reconciles_prior_qualified_endpoints(self):
        cases = ((200, False), (200, True), (-100, False), (None, True))
        for offset, weak_line, seeded in ((t, weak, seeded) for t, weak in cases for seeded in (False, True)):
            with self.subTest(offset=offset, weak_line=weak_line, seeded=seeded):
                self.stage.cumulative_endpoint_result = None
                first = copy.deepcopy(self.forced)
                later = copy.deepcopy(self.forced)
                for forced, shift in ((first, -200), (later, offset or 200)):
                    forced['lines'][0]['startMs'] += shift
                    forced['lines'][0]['words'][0]['startMs'] += shift
                first['lines'][-1]['confidence'] = 0.01
                first['lines'][-1]['minimumWordConfidence'] = 0.01
                if weak_line:
                    for line in later['lines']:
                        line['confidence'] = line['minimumWordConfidence'] = .01
                        if offset is None:
                            line['words'][0]['confidence'] = .01
                self.stage.load_baseline_mms = lambda *a, **k: (first, True)
                self.stage.services = replace(self.stage.services, workers=replace(
                    self.stage.services.workers, needs_local_verifier=lambda *a, **k: False))
                ready, _ = self.stage.evaluate_endpoint_route_pass('htdemucs-vocals')
                self.assertFalse(ready)
                self.stage.load_baseline_mms = lambda *a, **k: (later, True)
                ready, result = self.stage.evaluate_endpoint_route_pass('htdemucs-vocals', use_timing_seed=seeded)
                self.assertEqual(ready, offset == -100)
                occurrence = result.summary['occurrenceCandidates'][0]
                if offset == 200:
                    self.assertEqual(occurrence['publishDecision']['code'], 'qualified-endpoint-contradiction')
                    self.assertEqual(occurrence['publishDecision']['result'], 'unresolved')
                elif offset is None:
                    self.assertNotEqual(occurrence['publishDecision']['result'], 'unresolved')
                    self.assertEqual(result.timings[0]['startMs'], self.forced['lines'][0]['startMs']-200)

    def test_independent_fill_is_not_attributed_to_the_local_verifier(self):
        complete = endpoints._refine_public_timings(self.forced, self.verifier, {},
            verifier_family=endpoints.WAV2VEC2_FAMILY,
            mms_route='torchaudio-mms-fa-on-htdemucs-vocals', include_unresolved=True)
        incomplete = (None, *self.verifier[1:])
        self.stage.request = replace(self.stage.request,
            evidence=replace(self.stage.request.evidence, verifier_candidates=incomplete))
        self.stage.load_baseline_mms = lambda *a, **k: (self.forced, True)
        self.stage.load_route_wav2vec2 = lambda *a, **k: (incomplete, True, None)
        self.stage.services = replace(self.stage.services,
            workers=replace(self.stage.services.workers, needs_local_verifier=lambda *a, **k: True))
        self.stage.apply_independent_consensus = lambda baseline: (
            (complete[0], {**complete[1], 'independentConsensusFilledLineCount': 1}), True, True)
        ready, result = self.stage.evaluate_endpoint_route_pass('htdemucs-vocals')
        self.assertTrue(ready)
        self.assertEqual(result.summary['routeLocalVerifierFilledLineCount'], 0)
        self.assertEqual(result.summary['independentConsensusFilledLineCount'], 1)

    def setUp(self):
        original={'id':'original-en','kind':'original','format':'plain','language':'en',
                  'text':'\n'.join(f'Line number {word} here' for word in ('zero','one','two','three','four','five','six','seven'))}
        temporary=tempfile.TemporaryDirectory(prefix='seed-stage-',dir=ROOT/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        root=Path(temporary.name)
        self.workspace=worker.AlignmentWorkspace(root/'agent',workspace_root=root)
        _,canonical=worker.snapshot_canonical_plain(self.workspace,'abcdefghijk',
            json.dumps({'tracks':[original]}).encode('utf-8'))
        admission,_=retained_content(self.workspace.job_dir('abcdefghijk'),original,tuple(range(8)),canonical)
        state={'canonicalPlainSha256':canonical,'contentAdmission':admission.to_record(),
               'contentBinding':admission.binding,'pinnedModels':{}}
        self.workspace.write_state('abcdefghijk',state)
        self.sources=tuple(range(8))
        mapping=build_lexical_occurrence_mapping('abcdefghijk',original,self.sources)
        forced=[]
        verifier=[]
        for index,occurrence in enumerate(mapping['performedOccurrences']):
            start=3250+index*2000
            words=[{'wordId':word['occurrenceWordId'],'startMs':start+250*position,
                    'endMs':start+250*(position+1),'confidence':.9,'acousticTokenCount':1,'romanizedWordCount':1}
                   for position,word in enumerate(occurrence['words'])]
            line={'lineIndex':index,'lineId':occurrence['occurrenceId'],
                  'sourcePhysicalIndex':index,'startMs':start,'endMs':start+1000,
                  'confidence':.9,'minimumWordConfidence':.9,'displayWordCount':4,'romanizedWordCount':4,'acousticTokenCount':4,'words':words}
            forced.append(line)
            ranges=display_words(original['text'].splitlines()[index])
            verifier.append({**line,'route':endpoints.WAV2VEC2_ROUTE,
                             'words':tuple({**word,'startUtf16':a,'endUtf16':b}
                                           for word,(a,b,_) in zip(words,ranges))})
        self.forced={'lines':forced}
        self.verifier=tuple(verifier)
        hints=tuple({'startMs':row['startMs'],'endMs':row['endMs']} for row in forced)
        self.seed_segments=endpoints._build_bootstrap_segments(8,32000,hints,self.verifier)
        self.full_segments=[{'segmentIndex':0,'startLineIndex':0,'endLineIndex':8,
                             'windowStartMs':0,'windowEndMs':32000}]
        self.calls=[]
        self.attempts=[]
        self.stage = endpoints.EndpointAlignmentStage(
            request=worker.EndpointAlignmentRequest(
                context=worker.AlignmentContext(config={'analysisTimeoutMinutes':1}, video_id='abcdefghijk',
                    workspace=self.workspace, temp_root=root,
                    device='cpu', duration_ms=32000, endpoint_duration_ms=32000),
                text=worker.EndpointText(original=original, alignment=SimpleNamespace(source_physical_indices=self.sources),
                    lexical_mapping=mapping, sources=self.sources, source_occurrences=self.sources,
                    display_word_lines=[], acoustic_language_lines=None),
                evidence=worker.EndpointEvidence(baseline_segments=self.full_segments, seeded_mms_segments=self.seed_segments,
                    verifier_candidates=self.verifier, verifier_family=endpoints.WAV2VEC2_FAMILY,
                    verifier_reused=True, wav2vec2_language='en', free_family_candidates={}, independent_inputs={},
                    reviewed_lrc_starts={}, route_names={'htdemucs-vocals':'torchaudio-mms-fa-on-htdemucs-vocals'},
                    content_admission=admission.to_record()),
                audio=worker.EndpointAudio(expected_mix_sha256='a'*64, analysis_audio=Path('native.wav'),
                    canonical_mix=lambda: (_ for _ in ()).throw(AssertionError('unexpected mix acquisition')),
                    get_endpoint_audio=lambda _: (_ for _ in ()).throw(AssertionError('cached model substitute owns audio')))),
            services=worker.EndpointServices(
                workers=worker.EndpointWorkers(load_mms=worker._load_final_mms_alignment,
                    load_wav2vec2=worker._load_or_run_wav2vec2_alignment,
                    load_owsm=worker._load_or_run_owsm_ctc_alignment, load_nfa=worker._load_or_run_nemo_nfa_alignment,
                    read_audio_provenance=alignment_audio.read_audio_provenance,
                    verifier_candidates=worker._forced_alignment_verifier_candidates,
                    needs_local_verifier=worker._needs_route_local_verifier),
                events=worker.AlignmentEvents(attempt=lambda *a, **k:None, failure=lambda *a:None,
                    roformer=lambda *a, **k:None),
                progress=worker.AlignmentProgress(state=state, endpoint_attempts=self.attempts, checkpoint=lambda:None),
                release_model=lambda:None, runtime_probe_cache={}))
        self.stage.apply_independent_consensus = lambda baseline:(baseline,False,True)

    def run_route(self, *, seed_fault=None, enabled=True):
        def load(**arguments):
            self.calls.append(arguments)
            if arguments['request_variant']=='prior-recording-seed':
                if isinstance(seed_fault,Exception):
                    raise seed_fault
                if seed_fault=='wrong-boundary':
                    result=copy.deepcopy(self.forced)
                    for row in result['lines']:
                        row['startMs']+=800;row['endMs']+=800
                        for word in row['words']:
                            word['startMs']+=800;word['endMs']+=800
                    return result,False
            return copy.deepcopy(self.forced),True
        self.stage.services = replace(self.stage.services, workers=replace(self.stage.services.workers, load_mms=load))
        if not enabled:
            self.stage.request = replace(self.stage.request, evidence=replace(self.stage.request.evidence, seeded_mms_segments=None))
        return self.stage.evaluate_endpoint_route('htdemucs-vocals')

    def test_native_seed_uses_distinct_cache_and_original_unconstrained_verifier_gate(self):
        before=copy.deepcopy(self.verifier)
        ready,result=self.run_route()
        self.assertTrue(ready)
        self.assertEqual(result.summary['resultClass'],'committed-words')
        self.assertEqual([row['request_variant'] for row in self.calls],['prior-recording-seed'])
        self.assertEqual(self.calls[0]['segments'],self.seed_segments)
        self.assertEqual(self.verifier,before)
        self.assertEqual(self.attempts[0]['routeId'],'htdemucs-vocals:prior-recording-seed')

    def test_native_gate_rejects_bad_seed_then_same_route_runs_original_full_baseline(self):
        ready,result=self.run_route(seed_fault='wrong-boundary')
        self.assertTrue(ready)
        self.assertEqual([row['request_variant'] for row in self.calls],['prior-recording-seed','baseline'])
        self.assertEqual(self.calls[1]['segments'],self.full_segments)
        self.assertEqual(self.attempts[0]['endpointEvidence']['resultClass'],'line-unresolved')
        self.assertEqual(self.attempts[1]['routeId'],'htdemucs-vocals:baseline')
        self.assertEqual(result.summary['resultClass'],'committed-words')

    def test_required_independent_consensus_vetoes_a_ready_seed_before_full_native_fallback(self):
        calls=[]
        def consensus(baseline):
            calls.append(copy.deepcopy(baseline))
            if len(calls)==1:
                unsupported=copy.deepcopy(self.forced)
                for line in unsupported['lines']:
                    line['confidence']=line['minimumWordConfidence']=.01
                blocked=endpoints._refine_public_timings(unsupported,self.verifier,{},
                    verifier_family=endpoints.WAV2VEC2_FAMILY,
                    mms_route='torchaudio-mms-fa-on-htdemucs-vocals',include_unresolved=True)
                return blocked,True,False
            return baseline,False,True
        self.stage.apply_independent_consensus=consensus
        ready,result=self.run_route()
        self.assertTrue(ready)
        self.assertEqual([row['request_variant'] for row in self.calls],['prior-recording-seed','baseline'])
        self.assertEqual(len(calls),2)
        self.assertEqual(calls[0][1]['resultClass'],'committed-words')
        self.assertEqual(self.attempts[0]['endpointEvidence']['resultClass'],'line-unresolved')
        self.assertFalse(self.attempts[0]['reused'])
        self.assertEqual(self.attempts[0]['endpointEvidence']['mixVerifierPublishedLineCount'],8)
        self.assertEqual(result.summary['resultClass'],'committed-words')

    def test_search_segments_never_establish_repeated_occurrence_identity(self):
        sources=(0,1,2,3,0,5,6,7)
        self.stage.request = replace(self.stage.request, text=replace(self.stage.request.text, source_occurrences=sources))
        forced=copy.deepcopy(self.forced)
        forced['lines'][4]['sourcePhysicalIndex']=0
        load = Mock(return_value=(forced,True))
        self.stage.services = replace(self.stage.services, workers=replace(self.stage.services.workers, load_mms=load))
        evaluate=self.stage.evaluate_endpoint_route_pass
        before = self.workspace.state_path('abcdefghijk').read_bytes()
        with self.assertRaisesRegex(ValueError, 'distinct source identities'):
            evaluate('htdemucs-vocals',use_timing_seed=True)
        load.assert_called_once()
        self.assertEqual(load.call_args.kwargs['request_variant'], 'prior-recording-seed')
        self.assertEqual(load.call_args.kwargs['segments'], self.seed_segments)
        self.assertEqual(self.workspace.state_path('abcdefghijk').read_bytes(), before)
        self.assertFalse(self.stage.services.progress.state.get('committed'))

    def test_failed_seed_worker_or_invalid_result_runs_original_full_baseline(self):
        for error in (worker_protocol.WorkerProcessError('failed','model-failure'),ValueError('invalid segment result')):
            self.calls.clear();self.attempts.clear()
            ready,_=self.run_route(seed_fault=error)
            self.assertTrue(ready)
            self.assertEqual([row['request_variant'] for row in self.calls],['prior-recording-seed','baseline'])

    def test_absent_seed_runs_only_existing_full_native_baseline(self):
        ready,_=self.run_route(enabled=False)
        self.assertTrue(ready)
        self.assertEqual([row['request_variant'] for row in self.calls],['baseline'])
        self.assertEqual(self.calls[0]['segments'],self.full_segments)

    def test_unknown_worker_retirement_failure_is_not_relabelled_as_seed_miss(self):
        with self.assertRaisesRegex(RuntimeError,'could not be retired'):
            self.run_route(seed_fault=RuntimeError('Owned command workers could not be retired'))
        self.assertEqual(len(self.calls),1)

    def test_seed_variant_has_its_own_real_mms_cache_path(self):
        with tempfile.TemporaryDirectory(prefix='seed-cache-',dir=ROOT/'Test suite/work') as temporary:
            base=Path(temporary)
            project=base/'project'
            prepare_runtime_layout(project)
            for path in (project/'.venv/bin/python',project/'src/mms_forced_alignment.py',
                         base/'model-cache/torch/hub/checkpoints/955717e8-8726e21a.th',
                         base/'model-cache/torch/hub/checkpoints/model.pt'):
                path.parent.mkdir(parents=True,exist_ok=True)
                path.write_bytes(b'fixture')
            identity={'stream':{'sampleRate':48000,'channelCount':2,'frameCount':1536000,'durationMs':32000}}
            variants=[]
            def load(**arguments):
                variants.append(arguments['variant'])
                self.assertEqual(arguments['segments'],self.seed_segments)
                return self.forced,True
            with patch.object(worker,'_mms_runtime_provenance',return_value={}), \
                 patch.object(alignment_audio,'read_audio_provenance',return_value={'audioViews':{'htdemucs-vocals':identity}}), \
                 patch.object(worker,'_forced_alignment_provenance',return_value={'audio':identity}), \
                 patch.object(worker,'_load_or_run_mms_alignment',side_effect=load), \
                 patch.object(worker,'_project_endpoint_eof',side_effect=lambda value,_,**kwargs:value):
                for request in ('baseline','prior-recording-seed'):
                    worker._load_final_mms_alignment(config={'projectDir':str(project)},
                        video_id='abcdefghijk',original=self.stage.request.text.original,
                        alignment=self.stage.request.text.alignment,occurrence_mapping=self.stage.request.text.lexical_mapping,
                        duration_ms=32000,workspace=self.workspace,temp_root=base,device='cpu',
                        timeout_seconds=1,request_variant=request,segments=self.seed_segments)
            self.assertEqual(variants,['public-endpoints-v3','public-endpoints-v3-prior-recording-seed'])
            workspace=SimpleNamespace(job_dir=lambda _:base)
            paths=[worker._forced_alignment_cache_path(workspace,'abcdefghijk',variant) for variant in variants]
            self.assertNotEqual(paths[0],paths[1])


if __name__=='__main__':
    unittest.main()

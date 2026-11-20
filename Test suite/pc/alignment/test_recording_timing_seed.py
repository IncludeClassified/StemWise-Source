import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path[:0] = [str(Path(__file__).parent), str(ROOT/'PC side/stemwise-playlist-builder/src')]
import stemwise_engine.recording_timing_seed as seed
from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping, lexical_mapping_identity


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True).encode('utf-8')
    path.write_bytes(raw)
    return hashlib.sha256(raw).hexdigest()


class TimingSeedTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='timing-seed-', dir=ROOT/'Test suite/work')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.pc = self.root/'PC side'
        self.project = self.pc/'stemwise-playlist-builder'
        self.project.mkdir(parents=True)
        self.library = self.pc/'qobuz-comparison'
        self.video_id = 'abcdefghijk'
        self.base = self.project/'future-songs/policy-state/current'
        self.alignment = self.base/'lyrics-alignment/jobs'/self.video_id
        self.config = {'projectDir':str(self.project), 'futureRootWindows':str(self.project/'future-songs'),
                       'recordingInput':{'recordingKey':'qobuz:123', 'trackId':123,
                           'file':'PC side/qobuz-comparison/audio/qobuz/123/source.flac'}}
        self.inventory = {'playlist':[{'videoId':self.video_id, 'source':'qobuz', 'trackId':123,
                                      'file':'audio/qobuz/123/source.flac'}]}
        write_json(self.library/'comparison-results/library-files.json', self.inventory)
        self.original = {'id':'original-en', 'kind':'original', 'format':'plain', 'language':'en',
                         'text':'\n'.join(f'Line number {index} here' for index in range(8))}
        self.sources = list(range(8))
        self.mapping = build_lexical_occurrence_mapping(self.video_id, self.original, self.sources)
        identity = lexical_mapping_identity(self.mapping)
        self.timings = [{'startMs':3000+index*2000,'endMs':4000+index*2000} for index in range(8)]
        mapping_sha = write_json(self.alignment/'mapping.private.json', {
            **self.mapping, 'mappingSha256':identity['mappingSha256'],
            'numeric':{'sourcePhysicalIndices':self.sources,'publicLineTimings':self.timings}})
        canonical_raw = json.dumps({'tracks':[self.original]}, sort_keys=True).encode('utf-8')
        canonical_sha = hashlib.sha256(canonical_raw).hexdigest()
        canonical = self.base/'lyrics-alignment/canonical-plain'/f'{self.video_id}.{canonical_sha}.lyrics.json'
        canonical.parent.mkdir(parents=True)
        canonical.write_bytes(canonical_raw)
        self.state = {'videoId':self.video_id,'status':'committed-lines','accepted':True,
                      'durationMs':32000,'canonicalPlainSha256':canonical_sha,
                      'mappingArtifactSha256':mapping_sha,**identity}
        write_json(self.alignment/'state.json', self.state)
        self.native = self.native_candidates()
        self.arguments = {'video_id':self.video_id,'lexical_mapping':self.mapping,
                          'sources':self.sources,'duration_ms':32000,'native_verifier':self.native}

    def native_candidates(self, *, scale=1, offset=250):
        return tuple({'lineId':occurrence['occurrenceId'],'route':'wav2vec2-ctc-forced-on-mix',
                      'startMs':round(timing['startMs']*scale+offset),
                      'endMs':round(timing['endMs']*scale+offset),'confidence':.9,
                      'words':[{'wordId':word['occurrenceWordId'],'confidence':.9}
                               for word in occurrence['words']]}
                     for timing, occurrence in zip(self.timings,self.mapping['performedOccurrences']))

    def discover(self, **changes):
        return seed.discover_timing_seed(self.config, **{**self.arguments,**changes})

    def test_current_native_map_only_proposes_intervals_and_writes_nothing(self):
        before = {p.relative_to(self.root):p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        hints = self.discover()
        self.assertEqual(hints, tuple({'startMs':3250+index*2000,'endMs':4250+index*2000} for index in range(8)))
        self.assertTrue(all(set(row)=={'startMs','endMs'} for row in hints))
        self.assertEqual(before, {p.relative_to(self.root):p.read_bytes() for p in self.root.rglob('*') if p.is_file()})
        self.assertFalse((self.pc/'temporary-analysis').exists())

    def test_small_clock_difference_is_mapped_from_native_anchors(self):
        native = self.native_candidates(scale=1.001, offset=-150)
        self.assertEqual(self.discover(native_verifier=native), tuple(
            {'startMs':round(row['startMs']*1.001-150),'endMs':round(row['endMs']*1.001-150)}
            for row in self.timings))

    def test_hints_use_existing_guarded_native_segment_builder(self):
        from stemwise_engine.alignment_endpoints import _build_bootstrap_segments
        hints=self.discover()
        segments=_build_bootstrap_segments(len(self.sources),32000,hints,self.native)
        self.assertEqual([row['startLineIndex'] for row in segments],list(range(8)))
        self.assertEqual([row['endLineIndex'] for row in segments],list(range(1,9)))
        self.assertEqual(segments[0]['windowStartMs'],0)
        self.assertEqual(segments[-1]['windowEndMs'],32000)
        for previous,current in zip(segments,segments[1:]):
            self.assertEqual(previous['windowEndMs'],current['windowStartMs'])
            boundary=previous['windowEndMs']
            self.assertGreaterEqual(boundary-self.native[previous['endLineIndex']-1]['endMs'],250)
            self.assertGreaterEqual(self.native[current['startLineIndex']]['startMs']-boundary,250)

    def test_changed_words_sequence_or_old_artifact_fall_back(self):
        changed = {**self.original,'text':self.original['text'].replace('Line number 0','Wrong words')}
        wrong = build_lexical_occurrence_mapping(self.video_id, changed, self.sources)
        self.assertIsNone(self.discover(lexical_mapping=wrong))
        self.assertIsNone(self.discover(sources=list(reversed(self.sources))))
        mapping = self.alignment/'mapping.private.json'
        mapping.write_bytes(mapping.read_bytes()+b' ')
        self.assertIsNone(self.discover())

    def test_reference_alone_and_rejected_old_alignment_do_not_seed(self):
        self.inventory['playlist'][0]['trackId'] = 124
        write_json(self.library/'comparison-results/library-files.json', self.inventory)
        self.assertIsNone(self.discover())
        self.inventory['playlist'][0]['trackId'] = 123
        write_json(self.library/'comparison-results/library-files.json', self.inventory)
        self.state['accepted'] = False
        write_json(self.alignment/'state.json', self.state)
        self.assertIsNone(self.discover())

    def test_missing_prior_is_a_miss_and_does_not_change_current_state(self):
        (self.alignment/'state.json').unlink()
        self.assertIsNone(self.discover())
        self.assertEqual(self.native,self.arguments['native_verifier'])

    def test_wrong_native_route_occurrence_or_word_identity_do_not_seed(self):
        for field, value in (('route','wav2vec2-ctc-forced-on-kim-roformer'), ('lineId','wrong')):
            native=copy.deepcopy(self.native)
            native[4][field]=value
            self.assertIsNone(self.discover(native_verifier=native))
        native=copy.deepcopy(self.native)
        native[4]['words'][0]['wordId']='wrong'
        self.assertIsNone(self.discover(native_verifier=native))

    def test_adjacent_weak_or_inconsistent_native_anchors_do_not_seed(self):
        cases=[]
        native=copy.deepcopy(self.native)
        for row in native:
            row['confidence']=.05
        cases.append(native)
        native=copy.deepcopy(self.native)
        for row in native[3:]:
            row['confidence']=.05
        cases.append(native)
        native=copy.deepcopy(self.native)
        for index in (1,3,4,6):
            native[index]['startMs']+=1500
            native[index]['endMs']+=1500
        cases.append(native)
        cases.append(self.native_candidates(scale=1.01))
        for native in cases:
            self.assertIsNone(self.discover(native_verifier=native))

    def test_a_native_outlier_does_not_move_other_search_hints_or_suppress_verifier(self):
        native=copy.deepcopy(self.native)
        native[4]['startMs']+=1500
        native[4]['endMs']+=1500
        before=copy.deepcopy(native)
        self.assertEqual(self.discover(native_verifier=native),tuple(
            {'startMs':3250+index*2000,'endMs':4250+index*2000} for index in range(8)))
        self.assertEqual(native,before)

    def test_invalid_native_confidence_or_prior_overlap_cannot_create_hints(self):
        for invalid in (True, float('nan'),float('inf'),1.1):
            native=copy.deepcopy(self.native)
            for row in native:
                row['confidence']=invalid
            self.assertIsNone(self.discover(native_verifier=native))
        timings=copy.deepcopy(self.timings)
        timings[3]['endMs']=timings[4]['startMs']+1
        self.assertIsNone(seed._map_hints(timings,self.mapping,self.native,32000))

    def test_distributed_sparse_native_anchors_can_propose_untrusted_search_hints(self):
        native=tuple(row if index in {0,3,7} else None for index,row in enumerate(self.native))
        hints=self.discover(native_verifier=native)
        self.assertEqual(hints,tuple({'startMs':3250+index*2000,'endMs':4250+index*2000} for index in range(8)))

    def test_frozen_text_is_checked_independently_of_mapping_state(self):
        path=next((self.base/'lyrics-alignment/canonical-plain').glob('*.json'))
        changed=copy.deepcopy(self.original)
        changed['text']=changed['text'].replace('Line number 0','Different original')
        raw=json.dumps({'tracks':[changed]}).encode('utf-8')
        sha=hashlib.sha256(raw).hexdigest()
        (path.parent/f'{self.video_id}.{sha}.lyrics.json').write_bytes(raw)
        self.state['canonicalPlainSha256']=sha
        write_json(self.alignment/'state.json',self.state)
        self.assertIsNone(self.discover())


if __name__=='__main__':
    unittest.main()

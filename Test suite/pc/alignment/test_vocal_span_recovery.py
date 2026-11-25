import copy
import unittest

import numpy as np

from stemwise_engine import vocal_release_transition as spectral, vocal_span_recovery as spans


class SungSpanTest(unittest.TestCase):
    def test_final_word_does_not_follow_the_other_vocal_part_into_the_break(self):
        import json
        from pathlib import Path
        from tempfile import TemporaryDirectory
        from types import SimpleNamespace
        from unittest.mock import patch
        import soundfile as sf
        from stemwise_engine.common import sha256_file
        from stemwise_engine.instrumental_break import recover

        with TemporaryDirectory(dir=Path('Test suite/work')) as scratch:
            folder = Path(scratch)
            view = folder/'vocal-overlap'/'view'
            parts = view/'parts'
            parts.mkdir(parents=True)
            time = np.arange(6*44100)/44100
            lead = np.sin(2*np.pi*230*time)*np.where(time<1.7,.8,np.where(time<2.9,.04,0))
            backing = .2*np.sin(2*np.pi*230*time)+.3*np.sin(2*np.pi*790*time)
            other = .4*np.sin(2*np.pi*420*time)*(time>=1.7)
            files = {}
            for name,path,pcm in (('lead',parts/'lead.wav',lead),
                                  ('vocal',view/'vocals.wav',lead+other),
                                  ('backing',view/'backing.wav',backing)):
                sf.write(path,np.stack((pcm,pcm),axis=1),44100,subtype='FLOAT')
                files[name] = (str(path),sha256_file(path))
            words = [{'text':word,'startMs':a,'endMs':b} for word,a,b in
                     (('sana',700,880),('kurban',880,1000),('olsun',1000,1770))]
            for index, word in enumerate(words):
                word.update(startUtf16=6*index,endUtf16=6*index+len(word['text']),
                            vocalContrast=[True,True],vocalCropRangeMs=[[word['startMs']]*2,[word['endMs']]*2])
            track = {'format':'regional','lines':[{'text':'sana kurban olsun','timing':{'endMs':4400},
                'words':[{'startUtf16':w['startUtf16'],'endUtf16':w['endUtf16'],
                          'timing':{'status':'estimated','startMs':w['startMs'],'endMs':w['endMs']}}
                         for w in words]}]}
            track['lines'][0]['words'][-1]['timing']['endMs'] = 4400
            reference = copy.deepcopy(track)
            workspace = SimpleNamespace(job_dir=lambda _: folder)
            def readings(choir):
                digests = {}
                for role, observed in (('lead',words),('choir',choir),('backing',[])):
                    path = parts/f'{role}-words.private.json'
                    path.write_text(json.dumps({'words':observed}),encoding='utf-8')
                    digests[role] = sha256_file(path)
                policy = view/'backing-activity.private.json'
                policy.write_text(json.dumps({'readings':digests}),encoding='utf-8')
                (folder/'vocal-overlap/review.private.json').write_text(
                    json.dumps({'backingActivitySha256':sha256_file(policy)}),encoding='utf-8')
            def try_recover(protected):
                return recover(workspace,{'videoId':'fixture'},{'language':'tr'},
                               SimpleNamespace(source_physical_indices=(0,)),
                               {'sha256':'a'*64,'durationMs':6000},track,protected)
            guide = [{'text':'sana kurban olsun','words':words}]
            with patch('stemwise_engine.recording_repeat_words._inputs',return_value=('b'*64,guide,files)):
                readings(words)
                self.assertIsNone(try_recover({}))
                self.assertEqual(track, reference)
                readings([])
                self.assertIsNone(try_recover({(0,2)}))
                self.assertEqual(track, reference)
                result = try_recover({})
                self.assertIsNotNone(result)
                timing = track['lines'][0]['words'][-1]['timing']
                self.assertEqual(timing['startMs'],1000)
                self.assertAlmostEqual(timing['endMs'],1700,delta=50)
                self.assertEqual(track['lines'][0]['words'][:-1],reference['lines'][0]['words'][:-1])

                # A consonant can finish before the backing without a sustained pitch.
                # The rest must extend past the old interval to be observable here.
                track.update(copy.deepcopy(reference))
                timing = track['lines'][0]['words'][-1]['timing']
                timing['endMs'] = track['lines'][0]['timing']['endMs'] = 2900
                words[-1]['vocalContrast'] = [False,False]
                noise = np.random.default_rng(15).standard_normal(len(time))*.32
                lead = lead*(time<1.55) + noise*((time>=1.55)&(time<1.7))
                for kind in ('vocal','lead'):
                    path = files[kind][0]
                    sf.write(path,np.stack((lead,lead),axis=1),44100,subtype='FLOAT')
                    files[kind] = (path,sha256_file(Path(path)))
                readings(words)
                self.assertIsNone(try_recover({}))
                self.assertEqual(timing['endMs'],2900)
                readings([])
                terminal = try_recover({})
                self.assertIsNotNone(terminal)
                self.assertIn('terminalEndMs',terminal['boundaries'][0])
                self.assertAlmostEqual(timing['endMs'],1700,delta=50)
                self.assertEqual(timing['startMs'],1000)

    def test_instrumental_gap_blocks_delivery_and_release_uses_the_signal(self):
        from stemwise_engine.delivery_timing import checked_listening_review
        from stemwise_engine.instrumental_break import FLAG,_release
        from stemwise_engine.lyric_preview.instrumental_gap import quiet_gap
        from stemwise_engine.workflow_records import FutureSongError

        with self.assertRaisesRegex(FutureSongError,'verified instrumental break'):
            checked_listening_review(None,{},None,[{'reason':FLAG}])
        sustained=np.ones(50)
        backing=np.ones(50)
        self.assertIsNone(quiet_gap(sustained,backing))
        self.assertEqual(quiet_gap(np.r_[np.ones(10),np.zeros(40)],backing),1000)
        time=np.arange(24000)/16000
        voice=np.sin(2*np.pi*230*time)*(time<.7)
        features=spectral.extract(voice,np.zeros_like(voice),16000,0)
        self.assertLessEqual(abs(_release(features,730,750)-700),50)
        continuous=spectral.extract(np.sin(2*np.pi*230*time),np.zeros_like(voice),16000,0)
        self.assertIsNone(_release(continuous,730,750))

    def test_held_vowel_release_is_measured_even_if_the_selected_core_is_short(self):
        from stemwise_engine.sustained_release import measured_end

        time = np.arange(24000)/16000
        vowel = np.sin(2*np.pi*220*time)*((time>=.1)&(time<.7))
        following = np.sin(2*np.pi*350*time)*(time>=1.05)
        voice = vowel+following
        quality = spectral.extract(voice,np.zeros_like(voice),16000,0)
        runs = [[0,100,'SP'],[100,700,'ao'],[700,1050,'SP'],[1050,1500,'y']]
        target = {'interval':[110,170],'fence':1010,'guideEnd':170}
        trace = {'features':{'vocal':quality,'choir':quality},'frames':{'vocal':runs,'choir':runs}}
        end = measured_end(target,trace)
        self.assertIsNotNone(end)
        self.assertLessEqual(abs(end-700),50)
        self.assertIsNone(measured_end({**target,'interval':[110,700]},trace))
        self.assertIsNone(measured_end({**target,'fence':600},trace))
        backing_only = spectral.extract(voice,voice,16000,0)
        self.assertIsNone(measured_end(target,{**trace,'features':{'vocal':quality,'choir':backing_only}}))

    def test_terminal_breath_requires_the_lead_release_and_leaves_a_longer_breath_open(self):
        from stemwise_engine.sustained_release import _terminal_breath_end

        time = np.arange(24000)/16000
        vowel = np.sin(2*np.pi*220*time)*((time>=.1)&(time<.7))
        silence = np.zeros_like(vowel)
        features = spectral.extract(vowel,silence,16000,0)
        target = {'interval': [110, 320], 'guideEnd': 700, 'fence': 1010}
        frames = [[0,100,'SP'],[100,700,'ao'],[700,1500,'SP']]
        trace = {'features': {'vocal': features, 'lead': features},
                 'frames': {'vocal': frames, 'lead': frames}}
        self.assertAlmostEqual(_terminal_breath_end(target, trace), 700, delta=50)
        self.assertIsNone(_terminal_breath_end({**target, 'guideEnd': 840}, trace))
        with_breath = [[0,100,'SP'],[100,700,'ao'],[700,780,'hh'],[780,1500,'SP']]
        self.assertIsNone(_terminal_breath_end(target, {**trace, 'frames': {**trace['frames'], 'lead': with_breath}}))
        later_breath = [[0,100,'SP'],[100,700,'ao'],[700,900,'SP'],[900,960,'hh'],[960,1500,'SP']]
        self.assertAlmostEqual(_terminal_breath_end(target, {**trace, 'frames': {**trace['frames'], 'vocal': later_breath}}),
                               700, delta=50)
        choir_only = {'features': {'vocal': features, 'choir': features},
                      'frames': {'vocal': frames, 'choir': frames}}
        self.assertIsNone(_terminal_breath_end(target, choir_only))

    def test_masked_terminal_breath_needs_a_continuous_vowel_and_a_drop_in_both_views(self):
        from stemwise_engine.sustained_release import _masked_breath_end

        time = np.arange(24000)/16000
        vowel = np.sin(2*np.pi*220*time) * (time>=.1)
        backing = 1.2 * np.sin(2*np.pi*220*time)
        faded = vowel * (time<.7)
        quality = spectral.extract(faded, backing, 16000, 0)
        self.assertTrue(np.all(np.asarray(quality['frames'])[32:57,2]<.35))
        runs = [[0,100,'SP'], [100,700,'ao'], [700,1500,'SP']]
        trace = {'features':{'lead':quality,'vocal':quality},'frames':{'lead':runs,'vocal':runs}}
        target = {'interval':[110,280], 'guideEnd':670, 'fence':1230}
        self.assertAlmostEqual(_masked_breath_end(target,trace),700,delta=30)
        continuous = spectral.extract(vowel, backing, 16000, 0)
        self.assertIsNone(_masked_breath_end(target,{**trace,'features':{**trace['features'],'vocal':continuous}}))
        interrupted = [[0,100,'SP'],[100,310,'ao'],[310,430,'SP'],[430,700,'ao'],[700,1500,'SP']]
        self.assertIsNone(_masked_breath_end(target,{**trace,'frames':{**trace['frames'],'lead':interrupted}}))
        self.assertIsNone(_masked_breath_end({**target,'fence':800},trace))

    def test_masked_terminal_breath_search_stops_in_local_quiet_before_a_long_break(self):
        from pathlib import Path
        from types import SimpleNamespace
        from unittest.mock import patch
        from stemwise_engine.sustained_release import _targets

        text = '\u0627\u0644\u0644\u0647'
        word = {'wordId':'final', 'text':text, 'startMs':1200, 'endMs':2200,
                'vocalContrast':[True,False], 'vocalCropRangeMs':[[1190,1200],[2220,2240]],
                'massWithin100ms':{'mix':[.8,.2],'lead':[.9,.3],'backing':[.02,.5]}}
        guide = [{'text':text,'words':[word]}]
        track = {'lines':[{'words':[{'timing':{'status':'estimated','startMs':1220,'endMs':1300}}]}]}
        admission = SimpleNamespace(source_physical_indices=(0,),spoken_targets=None)
        audio = {'durationMs':24000,'sha256':'a'*64}
        folder = Path('scope/lyrics-alignment/jobs/song')
        with patch('stemwise_engine.spoken_targets.load',return_value=None):
            base = (track,guide,admission,{'text':text,'language':'ar'},folder,audio)
            self.assertEqual(_targets(*base,{},None,allow_masked=False),[])
            target, = _targets(*base,{},None)
            self.assertEqual(target['interval'],[1220,1300])
            self.assertEqual(target['guideEnd'],2200)
            self.assertEqual(target['fence'],2850)
            self.assertTrue(target['maskedBreath'])
            self.assertEqual(_targets(*base,{(0,0)},None),[])

    def test_vowel_only_word_can_release_into_different_voiced_articulation(self):
        from stemwise_engine.sustained_release import measured_end

        time = np.arange(24000)/16000
        vowel = np.sin(2*np.pi*220*time)
        following = np.sin(2*np.pi*660*time)
        silence = np.zeros_like(time)
        runs = [[0,800,'ow'],[800,850,'l'],[850,960,'y'],[960,1500,'ae']]
        target = {'interval':[110,320],'guideEnd':330,'fence':1100}

        def measure(voice, roles):
            features = spectral.extract(voice,silence,16000,0)
            trace = {'features':{r:features for r in roles},'frames':roles}
            return measured_end(target,trace,vowel_only=True)

        connected = np.where(time<.8,vowel,following)
        ends = {'vocal':runs,'choir':runs}
        self.assertAlmostEqual(measure(connected,ends),800,delta=50)
        self.assertIsNone(measure(vowel,ends))
        self.assertIsNone(measure(connected,{'vocal':runs,'choir':[[0,1500,'ow']]}))
        interrupted = np.where((time>=.45)&(time<.55),0,connected)
        broken = [[0,450,'ow'],[450,550,'SP'],[550,800,'ow'],*runs[1:]]
        self.assertAlmostEqual(measure(interrupted,{'vocal':broken,'choir':broken}),450,delta=50)

    def test_adjacent_recovery_measures_the_release_and_later_entrance_separately(self):
        from stemwise_engine.vocal_span_estimate import complete_adjacent_words

        time = np.arange(24000)/16000
        first = np.sin(2*np.pi*220*time)*((time>=.2)&(time<.73))
        second = np.sin(2*np.pi*310*time)*((time>=.82)&(time<1.13))
        vocal = first+second
        def guide(a,b):
            return {'startMs':a,'endMs':b,'vocalCropRangeMs':[[a-10,a+10],[b-10,b+10]],
                    'massWithin100ms':{'mix':[.8,.8],'lead':[.9,.9],'backing':[.05,.05]}}
        def word(a,b,status):
            return {'timing':{'startMs':a,'endMs':b,'status':status}}
        lines = [{'words':[guide(200,730),guide(820,1130),guide(1240,1430)]}]
        request = [{'index':0,'targets':[{'index':0,'selected':[240,320]}]}]
        track = {'lines':[{'timing':{},'words':[word(240,320,'estimated'),
                                               word(320,750,'estimated'),word(1240,1430,'supported')]}]}
        baseline = copy.deepcopy(track)
        def repair(data, backing):
            observations = {'0':{'spectrum':spectral.extract(vocal,backing,16000,0)}}
            protected = {}
            complete_adjacent_words(data,lines,request,observations,protected,'packet')
            return protected
        self.assertEqual(repair(track,vocal),{})
        self.assertEqual(track,baseline)
        proofs = repair(track,np.zeros_like(vocal))
        self.assertEqual(set(proofs),{(0,0),(0,1)})
        self.assertLessEqual(abs(proofs[0,0][1]-730),50)
        self.assertLessEqual(abs(proofs[0,1][0]-820),50)
        self.assertLess(proofs[0,0][1],proofs[0,1][0])
        self.assertEqual(track['lines'][0]['words'][2],baseline['lines'][0]['words'][2])

    @staticmethod
    def case():
        t=np.arange(16000)/16000
        signal=np.sin(2*np.pi*230*t)*(t<.69)
        guide={'startMs':300,'endMs':680,'vocalCropRangeMs':[[290,310],[670,690]],
               'massWithin100ms':{'mix':[.8,.8],'lead':[.7,.7],'backing':[.01,.01]}}
        target=dict(index=0,wordId='a',guide=[300,680],guideWord=guide,flanked=True,flanks=[],
                    native=[[320,400]],spoken='في',selected=[320,400])
        observed={'words':{'a':{'reading':['f','iː'],'local':[315,490]}},
                  'frames':[[0,290,'SP'],[290,360,'f'],[360,690,'iy'],[690,1000,'SP']],
                  'spectrum':spectral.extract(signal,np.zeros_like(signal),16000,0)}
        return target,observed

    def test_acoustic_exit_after_single_native_frame_preserves_neighbor(self):
        target,obs=self.case()
        track={'lines':[{'timing':{'status':'estimated'},'words':[
            {'timing':{'startMs':320,'endMs':400,'status':'estimated'}},
            {'timing':{'startMs':750,'endMs':900,'status':'supported'}}]}]}
        locked=copy.deepcopy(track['lines'][0]['words'][1])
        proofs={}
        request=[{'index':0,'targets':[target]}]
        before=copy.deepcopy(track)
        spans._apply(track,request,{'0':obs},proofs,'audio-packet',require_anchored_decode=True)
        self.assertEqual((track,proofs),(before,{}))
        obs['words']['a']['fenced']=[315,490]
        spans._apply(track,request,{'0':obs},proofs,'audio-packet',require_anchored_decode=True)
        self.assertEqual(proofs,{(0,0):(320,690,'audio-packet')})
        self.assertEqual(track['lines'][0]['words'][0]['timing']['status'],'estimated')
        self.assertEqual(track['lines'][0]['words'][1],locked)
        self.assertEqual(track['lines'][0]['timing']['endMs'],900)

    def test_admitted_arabic_without_a_spoken_plan_reaches_span_measurement(self):
        from pathlib import Path
        from tempfile import TemporaryDirectory
        from types import SimpleNamespace
        from unittest.mock import patch
        from stemwise_engine.common import atomic_write_json
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace, build_lexical_occurrence_mapping
        from stemwise_engine.lyrics_tokenization import display_words
        from stemwise_engine import recording_repeat_words

        original = {'id':'original','kind':'original','format':'plain','language':'ar','text':'فِي نور'}
        admission = SimpleNamespace(source_physical_indices=[0], spoken_targets=None, evidence_sha256='e'*64)
        audio = {'sha256':'a'*64,'durationMs':1000}
        video = 'abcdefghijk'
        owner = build_lexical_occurrence_mapping(video,original,[0])['performedOccurrences'][0]
        state = {'videoId':video,'mappingSha256':'b'*64,'pinnedRuntime':{}}
        guide, words, choices = [], [], {}
        for j,((left,right,text),token) in enumerate(zip(display_words(original['text']),owner['words'],strict=True)):
            a,b = ((320,400),(750,900))[j]
            x,y = ((300,680),(750,900))[j]
            guide.append(dict(text=text,startUtf16=left,endUtf16=right,startMs=x,endMs=y))
            timing = {'startMs':a,'endMs':b,'status':'estimated' if not j else 'supported'}
            words.append(dict(startUtf16=left,endUtf16=right,timing=timing))
            choices[1,j+1] = {'nemo-nfa':{'wordId':token['occurrenceWordId'],'word':timing}}
        track = {'lines':[{'text':original['text'],'words':words}]}
        preview = [{'text':original['text'],'words':guide}]
        paths = {'vocal':['vocals.wav','1'*64], 'backing':['no_vocals.wav','2'*64]}
        work = Path(__file__).resolve().parents[3]/'Test suite/work'
        with TemporaryDirectory(dir=work) as temporary:
            ws = AlignmentWorkspace(Path(temporary))
            atomic_write_json(ws.job_dir(video)/'vocal-overlap/review.private.json',{'status':'single'})
            with patch.object(recording_repeat_words,'_inputs',return_value=('c'*64,preview,paths)) as inputs, \
                    patch.object(spans,'_requests',wraps=spans._requests) as requests:
                with self.assertRaisesRegex(ValueError,'Sung span recording observations unavailable'):
                    spans.recover(ws,state,original,admission,audio,track,choices,{})
                self.assertEqual(requests.call_args.args[3:5],([['فِي','نور']],[['ar','ar']]))
                inputs.reset_mock()
                for text in ('فِي ٢', 'فِي نور'):
                    excluded = {**original,'text':text,'language':'ar' if '٢' in text else 'mul'}
                    self.assertIsNone(spans.recover(ws,state,excluded,admission,audio,track,choices,{}))
                inputs.assert_not_called()

    def test_backing_only_and_different_local_owner_abstain(self):
        target,obs=self.case()
        word=obs['words']['a']
        self.assertIsNone(spans._span(target,word['reading'],[50,200],obs['frames'],obs['spectrum']))
        wave=np.sin(2*np.pi*230*np.arange(16000)/16000)
        both=spectral.extract(wave,wave,16000,0)
        self.assertIsNone(spans._span(target,word['reading'],word['local'],obs['frames'],both))

    def test_other_owner_and_incumbent_receipt_are_protected(self):
        target,obs=self.case()
        track={'lines':[{'timing':{},'words':[{'timing':{'startMs':320,'endMs':400,'status':'estimated'}},
                                               {'timing':{'startMs':620,'endMs':920,'status':'supported'}}]}]}
        base=copy.deepcopy(track)
        reqs=[{'index':0,'targets':[target]}]
        proofs={}
        spans._apply(track,reqs,{'0':obs},proofs,'audio')
        self.assertEqual(track,base)
        self.assertEqual(proofs,{})
        track['lines'][0]['words'][1]['timing']['startMs']=770
        base=copy.deepcopy(track)
        previous={(0,0):(320,400,'prior')}
        spans._apply(track,reqs,{'0':obs},previous,'new')
        self.assertEqual(track,base)
        self.assertEqual(previous,{(0,0):(320,400,'prior')})
        obs['words']['another']=obs['words'].pop('a')
        with self.assertRaisesRegex(ValueError,'another word'):
            spans._apply(track,reqs,{'0':obs},previous,'new')


    def test_word_owned_vowel_decay_can_precede_the_classifier_exit(self):
        t=np.arange(16000)/16000
        voice=np.sin(2*np.pi*t*200)
        backing=.07*np.sin(2*np.pi*t*330)
        mix=np.where(t<.650,voice,backing)
        target,obs=self.case()
        target.update(selected=[280,360],guide=[200,650])
        frames=[[0,250,'SP'],[250,745,'iy'],[745,1000,'p']]
        features=spectral.extract(mix,backing,16000,0)
        self.assertEqual(spans._vowel_decay(target,['f','iː'],[250,380],frames,features),(280,666))
        self.assertIsNone(spans._vowel_decay(target,['f','iː','d'],[250,380],frames,features))
        sustained=spectral.extract(voice,backing,16000,0)
        self.assertIsNone(spans._vowel_decay(target,['f','iː'],[250,380],frames,sustained))
        self.assertIsNone(spans._vowel_decay(target,['f','iː'],[20,65],frames,features))

    def test_duration_alone_and_matching_short_vocal_do_not_create_a_dispute(self):
        target,_=self.case()
        timing=dict(startMs=320,endMs=400,status='estimated')
        self.assertEqual(spans.dispute(timing,target['guideWord']),(290,690))
        shorter={**target['guideWord'],'startMs':315,'endMs':405,
                 'vocalCropRangeMs':[[310,320],[390,410]]}
        self.assertIsNone(spans.dispute(timing,shorter))
        self.assertIsNone(spans.dispute({**timing,'status':'supported'},target['guideWord']))


if __name__=='__main__':
    unittest.main()

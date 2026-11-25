import copy
import unittest

import numpy as np

from stemwise_engine import vocal_release_transition as spectral, vocal_span_recovery as spans


class SungSpanTest(unittest.TestCase):
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
        spans._apply(track,[{'index':0,'targets':[target]}],{'0':obs},proofs,'audio-packet')
        self.assertEqual(proofs,{(0,0):(320,690,'audio-packet')})
        self.assertEqual(track['lines'][0]['words'][0]['timing']['status'],'estimated')
        self.assertEqual(track['lines'][0]['words'][1],locked)
        self.assertEqual(track['lines'][0]['timing']['endMs'],900)

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

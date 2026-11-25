import copy
import unittest

import numpy as np

from stemwise_engine import phrase_final_release as observer, vocal_release_transition as spectrum


def evidence(coda=None):
    rate=16000
    t=np.arange(rate*2)/rate
    tone=np.sin(2*np.pi*220*t)+.45*np.sin(2*np.pi*440*t)
    x=tone*((t>=.6)&(t<1.2))
    if coda is not None:
        x=x+coda
    features=spectrum.extract(x,np.zeros_like(x),rate,0)
    guide={'wordId':'search-word','hint':1190,'crops':[1185,1205],'nextRecognized':False}
    target={'wordId':'physical-6','spoken':'اللَّه','language':'ar','startMs':570,'finalPhrase':guide}
    runs=[[490,600,'l'],[600,1200,'ao'],[1200,1800,'SP']]
    trace={'wordId':'physical-6','spoken':target['spoken'],'guide':dict(guide),'reading':['a','l','aː','h'],
           'nextPhone':None,'contexts':[runs,copy.deepcopy(runs)],'lead':features,'vocal':features}
    return target,trace


class PhraseFinalReleaseTest(unittest.TestCase):
    def test_measures_word_before_rest_and_refuses_extra_breath(self):
        target,trace=evidence()
        self.assertLessEqual(abs(observer.replay(target,trace)-1200),spectrum.FRAME_MS)
        rate=16000
        t=np.arange(rate*2)/rate
        noise=np.random.default_rng(3).normal(size=len(t))
        # High-frequency, physically distinct breath after the initially quiet vowel exit.
        breath=.18*np.r_[0,np.diff(noise)]*((t>1.28)&(t<1.37))
        target,trace=evidence(breath)
        self.assertIsNone(observer.replay(target,trace))

    def test_refuses_other_word_context_singer_or_unheard_coda(self):
        target,trace=evidence()
        with self.assertRaises(ValueError):
            observer.replay(dict(target,wordId='other-rendition'),trace)
        trace['reading'][-1]='t'
        self.assertIsNone(observer.replay(target,trace))
        target,trace=evidence()
        trace['contexts'][1][2][2]='m'
        self.assertIsNone(observer.replay(target,trace))
        target,trace=evidence()
        for row in trace['lead']['frames']:
            row[2]=0
        self.assertIsNone(observer.replay(target,trace))

    def test_handoff_requires_new_articulation_and_measures_vocal_fade(self):
        rate=16000
        t=np.arange(rate*2)/rate
        first=np.sin(2*np.pi*220*t)+.5*np.sin(2*np.pi*440*t)
        other=np.sin(2*np.pi*340*t)+.9*np.sin(2*np.pi*1360*t)
        target,trace=evidence()
        target['finalPhrase']['nextRecognized']=trace['guide']['nextRecognized']=True
        trace['nextPhone']='m'
        for runs in trace['contexts']:
            runs[2]=[1200,1550,'m']
        x=first*np.clip((1.22-t)/.040,0,1)+other*np.clip((t-1.23)/.035,0,1)
        trace['vocal']=trace['lead']=spectrum.extract(x,np.zeros_like(t),rate,0)
        result=observer.replay(target,trace)
        self.assertIsNotNone(result)
        self.assertLessEqual(abs(result-1220),spectrum.FRAME_MS)
        trace['vocal']=trace['lead']=spectrum.extract(first,np.zeros_like(t),rate,0)
        self.assertIsNone(observer.replay(target,trace))
        target['finalPhrase']['nextRecognized']=trace['guide']['nextRecognized']=False
        trace['vocal']=trace['lead']=spectrum.extract(x,np.zeros_like(t),rate,0)
        self.assertIsNone(observer.replay(target,trace))

    def test_whole_free_phrase_and_correct_occurrence_are_required(self):
        def word(text,start):
            return {'text':text,'startMs':start,'endMs':start+160,'vocalContrast':[True,True],
                    'massWithin100ms':{'mix':[.9,.9],'lead':[.9,.9],'backing':[.05,.05]},
                    'vocalCropRangeMs':[[start,start],[start+160,start+160]]}
        line={'words':[word('من',1000),word('هنا',1200),word('غنّى',1400)]}
        heard=[dict(w) for w in line['words']]
        heard[-1]['endMs']=11400  # The free transcript's reported end must confer no timing.
        self.assertIsNotNone(observer._recognition(line,heard,1420))
        self.assertIsNone(observer._recognition(line,heard,8420))
        self.assertIsNone(observer._recognition(line,heard+[dict(w) for w in heard],1420))
        heard[0]['text']='أين'
        self.assertIsNone(observer._recognition(line,heard,1420))


if __name__=='__main__':
    unittest.main()

"""Physical release and refusal controls for a lead-recognized Arabic word."""
import copy
import unittest

import numpy as np
from stemwise_engine import lead_vowel_observer as observer, vocal_release_transition as spectrum


def fixture():
    start, stop, next_start = 900, 2170, 2500
    target = dict(wordId='occurrence-2', text='شفيعنا', spoken='شفيعنا', language='ar',
                  startMs=start, endMs=1500,
                  candidates=[{'family':'nemo-nfa','startMs':start+30}],
                  previous={'text':'يا','language':'ar'},
                  following={'text':'يا','spoken':'يا','language':'ar','startMs':next_start})
    triplet=[{'text':'يا','startMs':100,'endMs':700},
             {'text':'شفيعنا','startMs':700,'endMs':1600},
             {'text':'يا','startMs':1600,'endMs':2700}]
    runs=[[700,860,'SP'],[860,970,'sh'],[970,1060,'f'],[1060,1180,'iy'],
          [1180,1250,'n'],[1250,stop,'ae'],[stop,next_start,'SP'],[next_start,2590,'y'],[2590,2700,'ae']]
    trace={'wordId':target['wordId'],'spoken':target['spoken'],'following':'يا',
           'triplet':triplet,'pronunciation':['ʃ','f','iː','ʕ','n','aː'],'nextPhones':['j','aː'],
           'contexts':[runs,copy.deepcopy(runs)]}
    rate=16000
    t=np.arange(rate*3)/rate
    tone=np.sin(2*np.pi*200*t)+.4*np.sin(2*np.pi*600*t)+.1*np.sin(2*np.pi*1200*t)
    signal=tone*((t>=start/1000)&(t<stop/1000))
    quiet=np.zeros_like(signal)
    trace['leadOverBacking']=spectrum.extract(signal,quiet,rate,0)
    trace['leadOverChoir']=spectrum.extract(signal,quiet,rate,0)
    return target,trace,signal,stop


class LeadVowelObserverTest(unittest.TestCase):
    def test_weak_vowel_tail_is_bounded_by_the_lead_and_keeps_a_late_competitor(self):
        target,trace,signal,stop=fixture()
        target['candidates'][0]['endMs']=1450
        t=np.arange(len(signal))/16000*1000
        lead=signal*np.minimum(1,np.maximum(0,(stop-t)/130))
        mask=2*signal*(t>stop-40)
        trace['leadOverBacking']=spectrum.extract(lead,mask,16000,0)
        trace['leadOverChoir']=spectrum.extract(lead,np.zeros_like(lead),16000,0)
        for runs in trace['contexts']:
            runs[5:6]=[[1250,stop-95,'ae'],[stop-95,stop-55,'SP'],[stop-55,stop,'ax']]
        result=observer.replay(target,trace)
        self.assertIsNotNone(result)
        self.assertLessEqual(abs(result-stop),spectrum.FRAME_MS)
        target['candidates'].append(dict(family='other',startMs=901,endMs=3300))
        self.assertIsNone(observer.replay(target,trace))
        target['candidates'].pop()
        trace['contexts'][0][6][2]='s'
        self.assertIsNone(observer.replay(target,trace))

    def test_glottal_coda_measures_breath_cut_after_vowel_and_refuses_competing_noise(self):
        from scipy.signal import butter, sosfilt
        target,trace,signal,_=fixture()
        target.update(text='الله',spoken='الله')
        trace.update(spoken=target['spoken'],pronunciation=['ʔ','a','l','l','aː','h'])
        trace['triplet'][1]['text']=target['text']
        runs=[[650,780,'uw'],[780,1140,'l'],[1140,2170,'aa'],[2170,2500,'SP'],[2500,2590,'y']]
        trace['contexts']=[runs,copy.deepcopy(runs)]
        rate=16000
        t=np.arange(len(signal))/rate
        excitation=np.random.default_rng(70).normal(size=len(t))
        noise=sosfilt(butter(4,(650,4500),btype='bandpass',fs=rate,output='sos'),excitation)
        aspiration=.15*noise*np.clip((t-2.16)/.025,0,1)*(t<2.32)
        def decision(coda, mask=False):
            lead=signal+coda
            trace['leadOverChoir']=spectrum.extract(lead,np.zeros_like(t),rate,0)
            trace['leadOverBacking']=spectrum.extract(lead,lead if mask else np.zeros_like(t),rate,0)
            return observer.replay(target,trace)
        result=decision(aspiration)
        self.assertIsNotNone(result)
        self.assertLessEqual(abs(result-2320),spectrum.FRAME_MS)
        self.assertIsNone(decision(np.zeros_like(t)))
        self.assertIsNone(decision(aspiration,mask=True))
        self.assertIsNone(decision(aspiration*(t>2.25)))
        # The word's aspirate falls off just before a separately identified
        # glide that begins before a full silence guard can fit.
        taper=np.clip((2.37-t)/.09,0,1)
        next_vowel=.8*np.sin(2*np.pi*220*t)*np.clip((t-2.393)/.018,0,1)
        for runs in trace['contexts']:
            runs[-2][1]=2350
            runs[-1][0]=2350
        residual=.012*np.sin(2*np.pi*175*t)*((t>2.28)&(t<2.393))
        joined=.2*noise*np.clip((t-2.16)/.025,0,1)*taper+residual+next_vowel
        result=decision(joined)
        self.assertIsNotNone(result)
        self.assertLessEqual(abs(result-2370),spectrum.FRAME_MS)
        self.assertIsNone(decision(joined,mask=True))
        trace['triplet'][1]['text']='غير'
        self.assertIsNone(decision(aspiration))

    def test_measures_actual_cut_and_refuses_unheard_coda_or_masking(self):
        target,trace,signal,physical_cut=fixture()
        self.assertLessEqual(abs(observer.replay(target,trace)-physical_cut),spectrum.FRAME_MS)
        changed=copy.deepcopy(trace)
        changed['pronunciation'].append('h')
        self.assertIsNone(observer.replay(target,changed))
        changed=copy.deepcopy(trace)
        changed['leadOverChoir']=spectrum.extract(signal,signal,16000,0)
        self.assertIsNone(observer.replay(target,changed))
        changed=copy.deepcopy(trace)
        changed['contexts'][1][5]=[1250,2170,'r']
        self.assertIsNone(observer.replay(target,changed))

    def test_wrong_physical_repeat_and_early_word_without_rest_are_refused(self):
        target,trace,_,_=fixture()
        changed=copy.deepcopy(target)
        changed['startMs']=3200
        self.assertIsNone(observer.replay(changed,trace))
        changed=copy.deepcopy(trace)
        changed['triplet'][0]['text']='من'
        self.assertIsNone(observer.replay(target,changed))
        changed=copy.deepcopy(target)
        changed['candidates']=[dict(family='nemo-nfa',startMs=1800)]
        self.assertIsNone(observer.replay(changed,trace))
        changed=copy.deepcopy(trace)
        changed['contexts'][0][6][2]='y'
        self.assertIsNone(observer.replay(target,changed))


if __name__=='__main__':
    unittest.main()

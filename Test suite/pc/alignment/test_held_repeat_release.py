import copy
import unittest

import numpy as np
from stemwise_engine import held_repeat_release as held, vocal_release_transition as spectrum


def fixture():
    g={'startMs':100,'endMs':1220,'vocalCropRangeMs':[[100,105],[1200,1245]]}
    w={'a':100,'b':600,'locked':False,'line':0,'index':0,'guide':g,
       'candidates':[(100,600),(100,1270)]}
    events=[[t,p,.95] for t,p in [(110,'a'),(140,'l'),(200,'x'),(230,'a'),(300,'m'),(350,'d'),
                                  (390,'u'),(420,'l'),(460,'i'),(500,'n')]]
    trace={'pronunciation':['a','l','ħ','a','m','d','l','l','h'],
           'views':{'vocal':events,'lead':events,'backing':[]}}
    runs=[[100,420,'s'],[420,520,'n'],[520,1200,'ah'],[1200,1410,'SP'],[1410,1600,'b']]
    t=np.arange(16000*2)/16000
    wave=(np.sin(2*np.pi*200*t)+.3*np.sin(2*np.pi*600*t))*np.clip((1.27-t)/.11,0,1)
    f=spectrum.extract(wave,wave*0,16000,0)
    ev={'runs':[{'vocal':runs,'lead':runs}, {'vocal':runs,'lead':runs}], 'vocal':f,'lead':f}
    return {'word':w,'neighbor':None,'observation':0},trace,ev


class HeldRepeatTest(unittest.TestCase):
    def test_complete_word_can_use_late_native_end_after_independently_detected_vowel(self):
        request,trace,frames=fixture()
        self.assertEqual(held.select(request,trace,frames),{'end':1270})
        changed=copy.deepcopy(request)
        changed['word']['candidates'].append((100,1800))
        self.assertIsNone(held.select(changed,trace,frames))
        changed=copy.deepcopy(trace)
        changed['views']['backing']=changed['views']['vocal']
        self.assertIsNone(held.select(request,changed,frames))
        changed=copy.deepcopy(trace)
        changed['views']['choir']=changed['views']['vocal']
        self.assertIsNone(held.select(request,changed,frames))
        changed=copy.deepcopy(frames)
        changed['runs'][1]['lead']=[[100,1200,'ao'],[1200,1600,'SP']]
        self.assertIsNone(held.select(request,trace,changed))

    def test_ongoing_voice_and_stale_or_locked_interval_stay_untouched(self):
        request,trace,frames=fixture()
        changed=copy.deepcopy(frames)
        t=np.arange(32000)/16000
        tone=np.sin(2*np.pi*200*t)
        changed['lead']=spectrum.extract(tone,tone*0,16000,0)
        self.assertIsNone(held.select(request,trace,changed))
        end={'status':'estimated','startMs':100,'endMs':600}
        track={'lines':[{'timing':dict(end),'words':[{'timing':dict(end)}]}]}
        original=copy.deepcopy(track)
        self.assertEqual(held._apply(track,[request],[trace],[frames],{(0,0):True}),{})
        self.assertEqual(track,original)
        track['lines'][0]['words'][0]['timing']['startMs']=90
        self.assertEqual(held._apply(track,[request],[trace],[frames],{}),{})
        self.assertEqual(track['lines'][0]['words'][0]['timing']['endMs'],600)


if __name__=='__main__':
    unittest.main()

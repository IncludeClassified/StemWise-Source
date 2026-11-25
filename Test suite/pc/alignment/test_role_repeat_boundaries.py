import copy
import unittest

from stemwise_engine import role_repeat_boundaries as role


def guide(a,b):
    return dict(startMs=a,endMs=b,vocalContrast=[True,True],vocalCropRangeMs=[[a,a],[b,b]],
                massWithin100ms=dict(mix=[.8,.8],lead=[.9,.9],backing=[.01,.01]))


class RoleRepeatTests(unittest.TestCase):
    def test_distinct_voiced_targets_and_atomic_partial_or_locked_refusal(self):
        windows=[guide(1000,1750),guide(2200,2950)]
        words=[dict(line=0,index=i,guide=g,a=a,b=b,locked=False)
               for i,(g,(a,b)) in enumerate(zip(windows,[(1000,1400),(1450,2600)]))]
        track={'lines':[{'timing':{},'words':[{'timing':dict(startMs=w['a'],endMs=w['b'],status='estimated')}
                                              for w in words]}]}
        request=dict(line=0,words=words,unit=[[0,0],[0,1]],observation=0,
                     context=[{'a':1000},{'a':2200},{'a':3450}])
        trace={'views':{'choir':[(1000,'a',.8),(1390,'l',.8),(2201,'e',.9),(2560,'l',.8)]}}
        dense=[(950,1100,'ae'),(1100,1200,'l'),(1200,1700,'ax'),(1700,2000,'SP'),
               (2000,2250,'ae'),(2250,2510,'l'),(2510,2900,'aa'),(2900,3450,'SP')]
        result={'choir':dict(hits=[True,True],runs=[dense,dense],registers={},
                 decodes=[[[1000,1400],[2200,2600],[3450,3670]],[[1000,1400],[2200,2600],[3450,3670]]])}
        changes=role.apply(copy.deepcopy(track),[request],[trace],[result],{})
        self.assertEqual(changes,{(0,0):(1000,1700),(0,1):(2200,2900)})
        self.assertEqual(role.apply(copy.deepcopy(track),[request],[trace],[result],{(0,1):'locked'}),{})
        result['choir']['hits'][1]=False
        original=copy.deepcopy(track)
        self.assertEqual(role.apply(track,[request],[trace],[result],{}),{})
        self.assertEqual(original,track)

    def test_uncued_words_require_phonetic_articulation_on_their_own_part(self):
        words=[['الحمد',500,1400,.9],['لله',1400,1830,.9],['الحمد',1830,2410,.9],['لله',2410,2990,.9]]
        self.assertTrue(role._recognized('الْحَمْدُ لِلَّهِ',words,2000,3150))
        self.assertFalse(role._recognized('الحمد لله',words,1000,3000))
        self.assertFalse(role._recognized('الحمد لله',words,3050,4000))
        self.assertFalse(role._partial([(1100,'a',.9),(1300,'u',.9)],['l','a','n'],1000,1400))
        self.assertTrue(role._partial([(1000,'l',.9),(1100,'a',.9),(1300,'n',.9)],['l','a','n'],990,1400))

    def test_register_needs_a_distinct_low_exit_and_a_continuing_upper_part(self):
        import numpy as np
        rate=44100
        t=np.arange(3*rate)/rate
        f=130
        low=sum(.12*np.sin(2*np.pi*f*h*t) for h in (1,2,3))
        high=sum(.12*np.sin(2*np.pi*f*h*t) for h in (4,8))
        fallen=low*np.clip((1.48-t)/.10,0,1)
        bracket=role._register_tail(fallen+high,0,1000,2200)
        self.assertEqual(bracket[0],1000)
        self.assertLess(abs(bracket[1]-1480),100)
        self.assertIsNone(role._register_tail(low+high,0,1000,2200))
        self.assertIsNone(role._register_tail(fallen,0,1000,2200))

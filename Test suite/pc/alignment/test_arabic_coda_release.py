import copy
import unittest
from unittest.mock import patch

from stemwise_engine import arabic_coda_release as coda


def target():
    return {'wordId':'target-2','text':'عليك','spoken':'عليك','language':'ar','line':1,'wordIndex':2,
            'startMs':1000,'previous':{'spoken':'الله'},'following':{'spoken':'وسلم','startMs':2000},
            'candidates':[{'family':'timer','startMs':1000,'endMs':2200}],
            'roleCoda':{'guide':[950,1420],'crops':[1410,1430],'roles':['choir'],'lineText':'الله عليك وسلم'}}


def letters(chars, start):
    return [[start+100*j,start+100*j+20,p,.95] for j,p in enumerate(chars)]


class ArabicCodaTest(unittest.TestCase):
    def test_selects_independent_local_candidate_inside_spoken_stop_and_successor(self):
        t=target()
        observed=letters('عليك|وسلم',1000)
        tr={'wordId':t['wordId'],'spoken':t['spoken'],'role':'choir','reading':['ʕ','a','l','a','j','k'],
            'letters':[observed,copy.deepcopy(observed)]}
        self.assertEqual(coda.replay(t,{'coda':[tr]}),{'endMs':1420})
        # Native ends and the following-word projection can be widely wrong.
        self.assertNotIn(1420,(t['following']['startMs'],observed[3][1],observed[5][0]))
        shifted=copy.deepcopy(t)
        shifted['roleCoda']['crops'][1]=1800
        self.assertIsNone(coda.replay(shifted,{'coda':[tr]}))
        wrong=copy.deepcopy(tr)
        wrong['letters'][1][2][2]='ش'
        self.assertIsNone(coda.replay(t,{'coda':[wrong]}))
        wrong=copy.deepcopy(tr)
        wrong['letters'][1][6][2]='د'
        self.assertIsNone(coda.replay(t,{'coda':[wrong]}))
        wrong=copy.deepcopy(tr)
        wrong['reading'].append('a')
        self.assertIsNone(coda.replay(t,{'coda':[wrong]}))
        # The guide can still select an estimate when the entire possible
        # short ending is bracketed to one timing tolerance. A written vowel
        # with no independent vocal realization requires separate observation.
        tight=copy.deepcopy(wrong)
        for obs in tight['letters']:
            for event in obs[4:]:
                event[0]-=80
                event[1]-=80
        t2=copy.deepcopy(t)
        t2['roleCoda'].update(guide=[950,1400],crops=[1390,1410])
        self.assertEqual(coda.replay(t2,{'coda':[tight]}),{'endMs':1400})
        t2['spoken']=tight['spoken']='عليكَ'
        self.assertIsNone(coda.replay(t2,{'coda':[tight]}))
        with self.assertRaises(ValueError):
            coda.replay(t,{'coda':[dict(tr,role='lead')]})

    def test_another_take_supplies_only_lexical_landmark_and_requires_all_waveform_segments(self):
        t=target()
        t.update(spoken='الله',previous={'spoken':'رسول'},following={'spoken':'يا'},wordIndex=2)
        t['roleCoda']['lineText']='يا رسول الله يا حبيب الله'
        s=copy.deepcopy(t)
        s.update(wordId='source-8',line=7,startMs=11000)
        s['roleCoda']['guide']=[10950,12800]
        e=letters('|اللا|',10900)
        peer={'target':s,'letters':[e,e],'matches':[[.84,-3],[.88,1],[.89,-2]]}
        self.assertTrue(coda._matches_peer(t,peer))
        peer['matches'][2][1]=110
        self.assertFalse(coda._matches_peer(t,peer))
        peer['matches'][2]=[.25,0]
        self.assertFalse(coda._matches_peer(t,peer))
        peer['matches'][2]=[.82,0]
        peer['letters']=[letters('|النا|',10900)]*2
        self.assertFalse(coda._matches_peer(t,peer))
        peer['letters']=[e,e]
        s['following']['spoken']='واحد'
        self.assertFalse(coda._matches_peer(t,peer))

    def test_overlap_scope_survives_only_for_the_original_measured_end(self):
        t=target()
        from stemwise_engine import role_phrase_overlap
        overlap={'endMs':1560,'otherSingerWordIds':['chorus-1']}
        with patch.object(role_phrase_overlap,'replay',return_value=overlap):
            self.assertEqual(coda.replay(t,{'crossLane':{}}),overlap)
            with patch.object(coda,'_coda',return_value=1510):
                self.assertEqual(coda.replay(t,{'coda':[{}],'crossLane':{}}),{'endMs':1510})
            with patch.object(coda,'_coda',return_value=1370):
                self.assertIsNone(coda.replay(t,{'coda':[{}],'crossLane':{}}))


if __name__=='__main__':
    unittest.main()

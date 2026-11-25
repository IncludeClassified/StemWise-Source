"""A lead can finish over this particular choir response only."""
import copy
import unittest

import numpy as np

from stemwise_engine import arabic_coda_release, recording_release_repair as repair, role_phrase_overlap as roles
from stemwise_engine.vocal_release_transition import extract


def fixture():
    t=np.arange(16000*4)/16000
    lead=(np.sin(t*2*np.pi*203)+.3*np.sin(t*2*np.pi*609))*((t>=1)&(t<2.3))
    choir=.1*np.sin(t*2*np.pi*870)*(t>=1.65)
    response=[dict(wordId='c1',text='صلّى',startMs=1650,endMs=1930),
              dict(wordId='c2',text='الله',startMs=1950,endMs=2300),
              dict(wordId='c3',text='وسلم',startMs=2430,endMs=3100)]
    info=dict(response=response,directChoir=[True]*3,responseMatch=[True]*3,nextLead=None)
    target=dict(wordId='lead',language='ar',text='محمد',line=0,wordIndex=0,releaseDisputed=True,locked=False,
                startMs=1000,endMs=1300,following={'startMs':1650},crossLane=info,candidates=[])
    events=lambda pairs:[[a,a+20,p,.95] for a,p in pairs]
    trace=dict(wordId='lead',binding=info,leadLetters=events([(1010,'م'),(1190,'ح'),(1480,'م'),(1580,'ا')]),
               choirLetters=events([(1660,'ص'),(1760,'ل'),(1960,'ا'),(2020,'ل'),(2200,'ه'),(2440,'و'),(2500,'س')]),
               leadOverChoir=extract(lead,choir,16000,0),leadOverBacking=extract(lead,np.zeros_like(t),16000,0))
    return target,trace


class CrossLaneReleaseTest(unittest.TestCase):
    def test_recovered_lead_copies_keep_original_owners_and_do_not_add_other_lyrics(self):
        from stemwise_engine.lyric_preview.job import _repeated_occurrences

        lead = {'occurrenceId': 'l1', 'text': 'لبنان', 'voice': 'lead',
                'words': [{'startMs': 800, 'endMs': 960}]}
        choir = {'occurrenceId': 'c1', 'text': 'أرض الأرز', 'voice': 'choir',
                 'words': [{'startMs': 1100, 'endMs': 1300}, {'startMs': 1400, 'endMs': 1600}]}
        added = {**lead, 'occurrenceId': 'l2', 'words': [{'startMs': 1170, 'endMs': 1530}]}
        original = [lead,choir]
        self.assertEqual(_repeated_occurrences(original,[lead,choir,added]),
            [{'performedLine': 3, 'occurrenceId': 'l2', 'wordCount': 1, 'startMs': 1170, 'endMs': 1530}])
        self.assertEqual(_repeated_occurrences(original,[lead,choir,{**added, 'text': 'كلام جديد'}]),[])
        self.assertEqual(_repeated_occurrences(original,[lead,added]),[])
        self.assertEqual(_repeated_occurrences(original,[lead,choir,{**added, 'voice': 'choir'}]),[])
        self.assertEqual(_repeated_occurrences(original,[lead,choir,{**added,
            'words': [{'startMs': 1170,'endMs': 1171,'boundaryReview': 'unresolved'}]}]),[])

    def test_held_lead_and_distinct_response_raw_replay(self):
        t,r=fixture()
        result=roles.replay(t,r)
        self.assertIsNotNone(result)
        self.assertLessEqual(abs(result['endMs']-2300),32)
        self.assertIn('c2',result['otherSingerWordIds'])
        # A very late native contender with no independently observed later
        # same-lead word still prevents an estimate.
        t['candidates']=[dict(family='nemo-nfa',startMs=1000,endMs=3500)]
        self.assertIsNone(roles.replay(t,r))
        t['candidates']=[]
        swapped=copy.deepcopy(r);swapped['leadLetters'],swapped['choirLetters']=r['choirLetters'],r['leadLetters']
        self.assertIsNone(roles.replay(t,swapped))
        leaking=copy.deepcopy(r);leaking['choirLetters']=r['leadLetters']+r['choirLetters']
        self.assertIsNone(roles.replay(t,leaking))

    def test_order_keeps_other_lanes_and_all_onsets_unchanged(self):
        t,r=fixture()
        def timed(a,b): return {'timing':{'startMs':a,'endMs':b,'status':'estimated'}}
        track={'lines':[{'timing':{},'words':[timed(1000,1300)]},
                        {'timing':{},'words':[timed(1650,1800),timed(1950,2070),timed(2430,2700)]}]}
        confirmed={};binding={'sha256':'a'*64};traces={'arabicCoda':{'lead':{'crossLane':r}}}
        self.assertTrue(repair._apply(track,[t],traces,{'arabicCoda':arabic_coda_release},binding,confirmed,4000))
        self.assertEqual(track['lines'][0]['words'][0]['timing']['startMs'],1000)
        self.assertEqual([w['timing']['startMs'] for w in track['lines'][1]['words']],[1650,1950,2430])
        self.assertLessEqual(abs(confirmed[(0,0)][1]-2300),32)
        self.assertFalse(repair._ordered_changes([(1000,1300),(1550,1600),(1700,1750)],
                                                  {0:(1000,1800)},{(0,1)}))
        self.assertFalse(repair._ordered_changes([(1000,1300),(1550,1600)],{0:(1020,1800)},{(0,1)}))

    def test_existing_late_choir_start_is_owned_only_inside_its_observed_word(self):
        t,r=fixture()
        t['crossLane']['response'][1]['startMs']=1790
        r['choirLetters'].insert(2,[1880,1900,'|',.99])
        r['choirLetters'].insert(6,[2250,2270,'|',.99])
        self.assertTrue(roles.within_response_word(t,r,1,2180))
        self.assertFalse(roles.within_response_word(t,r,1,2250))
        def apply(start):
            def timed(a,b): return {'timing':{'startMs':a,'endMs':b,'status':'estimated'}}
            track={'lines':[{'timing':{},'words':[timed(1000,1300)]},
                            {'timing':{},'words':[timed(1650,1740),timed(start,2290),timed(2430,2750)]}]}
            confirmed={}
            return repair._apply(track,[t],{'arabicCoda':{'lead':{'crossLane':r}}},{'arabicCoda':arabic_coda_release},
                                 {'sha256':'a'*64},confirmed,4000)
        self.assertTrue(apply(2180))
        self.assertFalse(apply(2250))
        r['choirLetters'][3][2]='غ'
        self.assertFalse(roles.within_response_word(t,r,1,2180))

    def test_masked_fade_needs_actual_quiet_and_a_recognized_subsequent_lead_word(self):
        t,r=fixture();n=np.arange(64000)/16000
        t['crossLane']['nextLead']=dict(text='يا',startMs=2750,endMs=3250)
        r['leadLetters'].extend([[2730,2750,'|',.95],[2930,2950,'ي',.95],[3010,3030,'ا',.95]])
        tone=np.sin(2*np.pi*202*n)+.3*np.sin(2*np.pi*606*n)
        lead=tone*(((n>=1)&(n<2.1))+.32*((n>=2.1)&(n<2.30)))
        competing=np.sin(2*np.pi*202*n)*(n>=2.1)
        r['leadOverChoir']=extract(lead,competing,16000,0)
        r['leadOverBacking']=extract(lead,competing,16000,0)
        end=roles._partly_masked(t,r,1500)
        self.assertIsNotNone(end)
        self.assertLessEqual(abs(end-2300),32)
        r['leadLetters'].insert(4,[1960,1980,'س',.98])
        self.assertIsNone(roles._partly_masked(t,r,1500))
        r['leadLetters'].pop(4)
        r['leadOverChoir']=extract(tone,np.zeros_like(n),16000,0)
        r['leadOverBacking']=r['leadOverChoir']
        self.assertIsNone(roles._partly_masked(t,r,1500))


if __name__=='__main__':
    unittest.main()

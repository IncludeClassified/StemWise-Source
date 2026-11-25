"""A role-local vocal interval requires its owned articulation and preserves timing locks."""
import copy
import unittest

from stemwise_engine import role_vocal_span_recovery as span


class RoleSpanTests(unittest.TestCase):
    def setUp(self):
        self.word={'id':'word-1','spoken':'يا','a':1010,'b':1390}
        runs=[[800,1000,'SP'],[1000,1090,'y'],[1090,1400,'ah'],[1400,1530,'s']]
        self.trace={'pron':[['j','aː']],'runs':[runs,copy.deepcopy(runs)],'decodes':[[[1050,1220]],[[1060,1240]]]}
        self.track={'lines':[{'words':[{'timing':{'status':'estimated','startMs':1150,'endMs':1230}}],'timing':{}}]}
        self.state={'endpointEvidence':{'occurrenceCandidates':[{'familyCandidates':{}}]}}
        self.request={'line':0,'role':'lead','words':[self.word],'targets':[0]}

    def test_repeated_short_glide_needs_word_onset_and_its_own_vowel_exit(self):
        self.assertEqual(span._interval(0,self.word,(1150,1230),self.trace,[]),(1000,1400))
        for wrong in ([[800,1500,'ah']],[[800,1000,'SP'],[1000,1090,'y'],[1090,2100,'ah']]):
            changed=copy.deepcopy(self.trace)
            changed['runs'][1]=wrong
            self.assertIsNone(span._interval(0,self.word,(1150,1230),changed,[]))
        changed=copy.deepcopy(self.trace)
        changed['decodes'][1][0]=[2400,2600]
        self.assertIsNone(span._interval(0,self.word,(1150,1230),changed,[]))

    def test_estimate_replays_both_edges_but_never_replaces_an_existing_lock(self):
        protected={}
        span._apply(self.track,[self.request],[self.trace],{},protected,self.state,'a'*64)
        self.assertEqual(protected,{(0,0):(1000,1400,'a'*64)})
        self.assertEqual(self.track['lines'][0]['words'][0]['timing']['status'],'estimated')
        word=self.track['lines'][0]['words'][0]['timing']
        word.update(startMs=1120,endMs=1200,status='supported')
        span._apply(self.track,[self.request],[self.trace],{},{},self.state,'a'*64)
        self.assertEqual((word['startMs'],word['endMs']),(1120,1200))

    def test_residual_aspiration_requires_a_complete_local_word_end(self):
        word={'id':'held','spoken':'الله','a':1000,'b':1430}
        runs=[[850,1000,'aa'],[1000,1130,'l'],[1130,1400,'ah'],[1400,1460,'w'],[1460,1600,'ah']]
        trace={'pron':[['ʔ','a','l','l','aː','h']],'runs':[runs,runs],
               'decodes':[[[1130,1450]],[[1130,1460]]]}
        self.assertEqual(span._interval(0,word,(1270,1350),trace,[]),(1000,1430))
        # A neighboring onset cannot supply its timestamp as a release.
        self.assertEqual(span._interval(0,word,(1270,1350),trace,[],latest=1417),(1000,1400))
        trace['decodes'][0][0][1]=1800
        self.assertIsNone(span._interval(0,word,(1270,1350),trace,[]))

    def test_merged_neighbor_requires_physical_dip_and_a_named_aspirate(self):
        word={'id':'held','spoken':'الله','a':1000,'b':1430}
        distinct=[[800,1000,'aa'],[1000,1110,'l'],[1110,1390,'ah'],[1390,1420,'hh'],[1420,1650,'ah']]
        merged=[[800,1000,'aa'],[1000,1110,'l'],[1110,1650,'ah']]
        trace={'pron':[['ʔ','a','l','l','aː','h']],'runs':[distinct,merged],
               'decodes':[[[1050,1420]],[[1060,1420]]]}
        self.assertIsNone(span._interval(0,word,(1250,1330),trace,[]))
        trace['dips']={'0':[1401,1445,1475]}
        self.assertEqual(span._interval(0,word,(1250,1330),trace,[]),(1000,1420))
        trace['runs']=[merged,merged]
        self.assertIsNone(span._interval(0,word,(1250,1330),trace,[]))

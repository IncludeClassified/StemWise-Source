"""Replay an audible final breath while keeping the following singer separate."""
import unittest

import numpy as np

from stemwise_engine import phrase_final_coda as coda, vocal_release_transition as spectrum


class PhraseFinalCodaTest(unittest.TestCase):
    def test_measures_one_aspirate_without_following_choir_or_a_second_breath(self):
        rate = 16000
        t = np.arange(rate*2)/rate
        voiced = np.sin(2*np.pi*220*t)*(t>=.6)*np.clip((1.25-t)/.08,0,1)
        choir = .45*np.sin(2*np.pi*420*t)*(t>=1.04)
        high = np.r_[0,np.diff(np.random.default_rng(5).normal(size=t.size))]
        quiet = np.zeros_like(t)

        def breath(lo,hi):
            envelope = np.sin(np.pi*np.clip((t-lo)/(hi-lo),0,1))
            return .10*high*envelope

        guide = {'wordId':'search-owner','hint':1190}
        target = {'wordId':'physical-word','startMs':570,'endMs':1100,
                  'following':{'startMs':1550},'phraseFinalCoda':guide}
        runs = [[490,600,'l'],[600,1200,'ao'],[1200,1550,'SP']]

        def observe(lead, chorus):
            features = {role:spectrum.extract(signal,quiet,rate,0) for role,signal in (
                ('lead',lead),('vocal',lead+chorus),('choir',chorus),('backing',quiet))}
            return coda.replay(target,{'wordId':'physical-word','guide':guide,'vowel':'aː',
                                       'contexts':[runs,runs],'features':features})

        aspirate = breath(1.15,1.38)
        end = observe(voiced+aspirate,choir)
        self.assertIsNotNone(end)
        self.assertLessEqual(abs(end-1380),2*spectrum.FRAME_MS)
        self.assertIsNone(observe(voiced,choir+aspirate))
        self.assertIsNone(observe(voiced+aspirate+breath(1.41,1.50),choir))
        target['following']['startMs'] = 1330
        self.assertIsNone(observe(voiced+aspirate,choir))

    def test_early_recognition_can_cover_the_current_word_onset(self):
        def word(text,a,b):
            return {'text':text,'startMs':a,'endMs':b}

        line = [word('في',1000,1160),word('كل',1500,1660),word('ألقاه',2100,2350)]
        following = word('دمعي',2550,2690)
        observed = [*line[:-1],word('ألقاه',1740,2300),word('دمعي',2300,2700)]
        self.assertTrue(coda._matches(line,observed,line[-1],following))
        self.assertFalse(coda._matches(line,observed+observed,line[-1],following))
        self.assertFalse(coda._matches(line,observed,line[-1],word('دمعي',2750,2900)))
        observed[0] = word('في',200,390)
        self.assertFalse(coda._matches(line,observed,line[-1],following))

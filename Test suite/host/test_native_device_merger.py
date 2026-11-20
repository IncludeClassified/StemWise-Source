import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'device'))
from run_native_device import verify_merger_observation


class NativeDeviceMergerObservationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads((Path(__file__).resolve().parents[1] /
                                  'android/fixtures/merger-oracle.json').read_text(encoding='utf-8'))

    def observation(self, bits):
        return json.dumps({'nativeLinearMixer': {'observedBits': bits, 'bitMismatches': 0}})

    def test_each_supported_profile_uses_captured_samples(self):
        for profile in ('separate', 'fused'):
            bits = [case[profile + 'Bits'] for case in self.fixture['mixCases']]
            result = verify_merger_observation(self.observation(bits), self.fixture)
            self.assertEqual(result['profile'], profile + '-RN32')
            self.assertEqual(result['cases'], 167)

    def test_claimed_success_cannot_replace_or_change_pcm(self):
        bits = [case['separateBits'] for case in self.fixture['mixCases']]
        bits[5] ^= 1
        with self.assertRaisesRegex(RuntimeError, 'arithmetic profile'):
            verify_merger_observation(self.observation(bits), self.fixture)
        with self.assertRaisesRegex(RuntimeError, 'extent'):
            verify_merger_observation(self.observation(bits[:-1]), self.fixture)
        with self.assertRaisesRegex(RuntimeError, 'one native'):
            verify_merger_observation('PASS', self.fixture)


if __name__ == '__main__':
    unittest.main()

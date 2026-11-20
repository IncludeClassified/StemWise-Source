from __future__ import annotations

import unittest

from stemwise_engine.ipa_validation import validate_companion_text, validate_ipa


class IpaValidationTests(unittest.TestCase):
    def test_supported_notation_preserves_ascii_diacritics_and_wrappers(self):
        for text in ('salam', '[ˈsã.laːm]', '/n̩ tʰ kʷ a˥˩/', 't͡ʃ d͜ʒ',
                     'ǀ ǁ ǂ ǃ ʘ', 'á ã a̰ a̤ ḁ a̪ a̺ a̻ a̚',
                     'a | b ‖', 'ˌa.ˈba', 'a‿b', 'ꜜa ꜛa', 'é ẽ á ǖ',
                     "aⁿ aˡ a˞ aʼ p'", 'a↗ b↘', 'ç β θ χ ⱱ æ ø œ ð ŋ ħ',
                     'ŋ̊ ŋ̍ ŋ̑ ç å ȃ'):
            with self.subTest(text=text):
                self.assertEqual(validate_ipa(text), ())

    def test_malformed_sequences_are_located_without_echoing_content(self):
        for text, code, position in (
            ('😀', 'ipa-unsupported-symbol', 1), ('̃a', 'ipa-unattached-mark', 1),
            ('a\u200eb', 'ipa-unsupported-symbol', 2), ('t͡', 'ipa-dangling-tie', 2),
            ('t͜', 'ipa-dangling-tie', 2), ('ʰ', 'ipa-unattached-mark', 1),
            ('[unclosed', 'ipa-wrapper', 1), ('مرحبا', 'ipa-unsupported-symbol', 1),
            ('[a/b]', 'ipa-wrapper', 3), ('[]', 'ipa-empty-unit', 2),
            ('/ /', 'ipa-empty-unit', 2), ('aˈ', 'ipa-dangling-prefix', 2),
            ('a.', 'ipa-empty-unit', 2), ('a ʰ', 'ipa-unattached-mark', 3),
            ('<a>', 'ipa-unsupported-symbol', 1), ('a�', 'ipa-unsupported-symbol', 2),
            ('ᵃ', 'ipa-unsupported-symbol', 1), ('a\t', 'ipa-unsupported-symbol', 2),
        ):
            with self.subTest(text=text):
                issue = validate_ipa(text)[0]
                self.assertEqual((issue.code, issue.line, issue.position), (code, 1, position))
                self.assertNotIn('text', vars(issue))
        self.assertLessEqual(len(validate_ipa('😀' * 100)), 64)

    def test_canonical_equivalence_does_not_compatibility_fold_or_approve_pronunciation(self):
        self.assertEqual(validate_ipa('ã'), validate_ipa('a\u0303'))
        self.assertEqual(validate_ipa('aʰ'), ())
        self.assertTrue(validate_ipa('ʰ'))
        # Valid syntax is deliberately unable to judge a proposed word's pronunciation.
        self.assertEqual(validate_ipa('salam'), validate_ipa('banana'))

    def test_companion_shape_keeps_physical_blank_lines_and_repeated_occurrences(self):
        source = 'One\n\nOne\nTwo'
        self.assertEqual(validate_companion_text(source, 'a\n\na\nb', role='ipa'), ())
        self.assertEqual(validate_companion_text(source, 'First\n\nFirst\nSecond', role='translationEn'), ())
        self.assertEqual(validate_companion_text(source, 'a\na\n\nb', role='ipa')[0].code,
                         'companion-stanza-shape')
        self.assertEqual(validate_companion_text(source, 'a\nb', role='ipa')[0].code,
                         'companion-line-count')


if __name__ == '__main__':
    unittest.main()

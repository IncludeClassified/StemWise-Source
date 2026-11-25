"""Provider-text normalization checks without network or publication."""
import unittest

from stemwise_engine.lyrics_acquire import LyricsAcquisitionError, _normalize_text


class ProviderTextTests(unittest.TestCase):
    def test_crlf_trailing_spaces_and_tabs_do_not_create_invalid_draft(self):
        self.assertEqual(
            _normalize_text("\r\nFirst  line  \r\n\t\r\nSecond line\t\r\n"),
            "First  line\n\nSecond line",
        )

    def test_blank_provider_reply_is_not_admitted(self):
        with self.assertRaises(LyricsAcquisitionError):
            _normalize_text(" \t\r\n")

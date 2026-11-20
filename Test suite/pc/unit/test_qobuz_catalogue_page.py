"""Album pages retain the rendition and use the displayed full-track duration."""
import json
import unittest
from html import escape
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[3]
from stemwise_media import catalogue_page as MODULE


def album_page(duration='<span class="track__item--duration">00:04:24</span>'):
    metadata = escape(json.dumps({"item_id": 44581464, "item_name": "Jannah", "item_brand": "Maher Zain",
                                  "item_category": "One (Vocals-Only International Version)",
                                  "item_variant_max": "44.1Khz - 16-bits"}), quote=True)
    return f'''<div data-nbtracksdisplayed="1"><div class="track" data-track="44581464"
      data-duration="300" data-track-v2="{metadata}" data-source="https://example.invalid/preview?token=unused">
      <div class="track__items" title="Jannah (Vocals-Only English)">{duration}</div>
      <p class="track__info">Maher Zain, MainArtist - Awakening Music, MusicPublisher</p></div></div>'''


MULTIDISC_TRACKS = ((101, 1, 'First'), (102, 1, 'Second'), (201, 2, 'Third'), (202, 2, 'Fourth'))


def multidisc_album_page(ordinals=(None, None, None, None)):
    rows = []
    for (track_id, disc, title), ordinal in zip(MULTIDISC_TRACKS, ordinals, strict=True):
        metadata = escape(json.dumps({'item_id': track_id, 'item_name': title,
            'item_brand': 'Singer', 'item_category': 'Collection'}), quote=True)
        number = '' if ordinal is None else f' data-track-number="{ordinal}"'
        rows.append(f'<div data-track="{track_id}" data-disc-number="{disc}"{number} data-track-v2="{metadata}">'
            '<span class="track__item--duration">00:00:01</span></div>')
    return '<div data-nbtracksdisplayed="4">' + ''.join(rows) + '</div>'


class QobuzAlbumPageTest(unittest.TestCase):
    def test_omitted_ordinals_are_disc_local_and_explicit_ordinals_are_preserved(self):
        for ordinals, expected in (
                ((None, None, None, None), [(1, 1), (1, 2), (2, 1), (2, 2)]),
                ((1, None, 1, None), [(1, 1), (1, 2), (2, 1), (2, 2)]),
                ((7, 8, None, None), [(1, 7), (1, 8), (2, 1), (2, 2)]),
                ((7, 8, 9, 10), [(1, 7), (1, 8), (2, 9), (2, 10)])):
            with self.subTest(ordinals=ordinals):
                tracks = MODULE.parse_album_html(multidisc_album_page(ordinals))
                self.assertEqual([(row['discNumber'], row['trackNumber']) for row in tracks], expected)
                self.assertEqual([row['id'] for row in tracks], [101, 102, 201, 202])

    def test_ambiguous_mixed_or_nonpositive_ordinals_are_rejected(self):
        for ordinals in ((None, 1, 1, 2), (1, 2, 2, None), (7, None, 1, 2), (None, 7, 1, 2),
                         (0, 2, 1, 2), (1, 2, -1, 2)):
            with self.subTest(ordinals=ordinals), self.assertRaises(ValueError):
                MODULE.parse_album_html(multidisc_album_page(ordinals))

    def test_full_duration_and_language_survive_preview_attributes(self):
        tracks = MODULE.parse_album_html(album_page())
        self.assertEqual(len(tracks), 1)
        self.assertEqual(tracks[0]["id"], 44581464)
        self.assertEqual(tracks[0]["duration"], 264)
        self.assertEqual(tracks[0]["version"], "Vocals-Only English")
        self.assertEqual(tracks[0]["artist"], "Maher Zain")
        self.assertEqual(tracks[0]["album"], "One (Vocals-Only International Version)")
        self.assertNotIn("unused", json.dumps(tracks))

    def test_missing_duration_cannot_be_replaced_by_preview_duration(self):
        with self.assertRaisesRegex(ValueError, "duration"):
            MODULE.parse_album_html(album_page(""))

    def test_missing_or_partial_track_list_is_rejected(self):
        for source in ("<html>unavailable</html>", album_page().replace('data-nbtracksdisplayed="1"', 'data-nbtracksdisplayed="2"')):
            with self.subTest(source=source[:60]), self.assertRaises(ValueError):
                MODULE.parse_album_html(source)


if __name__ == "__main__":
    unittest.main()

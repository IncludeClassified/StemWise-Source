from __future__ import annotations

import sys
import unittest
from unittest.mock import MagicMock, patch
from pathlib import Path

from PIL import Image, ImageDraw


PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"

from stemwise_engine.youtube import (  # noqa: E402
    CENTER_SQUARE_PADDING_THRESHOLD,
    center_square_padding_score,
    remove_center_square_padding,
)


class YouTubeArtworkTests(unittest.TestCase):
    def test_source_cover_keeps_admissible_fallback_when_larger_png_exceeds_limit(self):
        import io
        import random
        import tempfile
        import stemwise_engine.youtube as youtube
        from stemwise_engine.contract import MAX_COVER_BYTES, MAX_COVER_PIXELS_PER_SIDE

        def oversized_jpeg(size, limit):
            pixels = random.Random(731).randbytes(size[0] * size[1] * 3)
            with Image.frombytes('RGB', size, pixels) as image, io.BytesIO() as encoded:
                image.save(encoded, format='JPEG', quality=75)
                payload = encoded.getvalue()
            self.assertLess(len(payload), limit, 'Downloaded JPEG must fit the actual byte limit')
            with Image.open(io.BytesIO(payload)) as decoded, io.BytesIO() as normalized:
                decoded.convert('RGB').save(normalized, format='PNG', optimize=True)
                self.assertGreater(normalized.tell(), limit,
                                   'Real decoded PNG must exceed the actual byte limit')
            return payload

        local_limit = 1024 * 1024
        small_oversized = oversized_jpeg((1280, 720), local_limit)
        ceiling_oversized = oversized_jpeg((3000, 3000), MAX_COVER_BYTES)
        with Image.new('RGB', (320, 320), '#3581aa') as fallback, io.BytesIO() as encoded:
            fallback.save(encoded, format='PNG')
            fallback_payload = encoded.getvalue()
            expected_pixels = fallback.tobytes()
        invalid_url = 'https://fixture.invalid/oversized.jpg'
        fallback_url = 'https://fixture.invalid/fallback.png'
        cases = (
            ('oversized-first', small_oversized, local_limit, [invalid_url, fallback_url]),
            ('valid-first', small_oversized, local_limit, [fallback_url, invalid_url]),
            ('default-limit-early-exit', ceiling_oversized, MAX_COVER_BYTES,
             [invalid_url, fallback_url]),
            ('all-invalid-preserves-destination', small_oversized, local_limit, [invalid_url]),
        )
        for name, payload, limit, candidates in cases:
            with self.subTest(case=name), tempfile.TemporaryDirectory(
                    dir=PROJECT_ROOT.parents[1]/'Test suite/work') as temporary:
                target = Path(temporary)/'cover.png'
                previous = fallback_payload
                target.write_bytes(previous)
                # Missing dimensions are supported; actual candidate ordering uses preference.
                info = {'thumbnails': [
                    {'url': url, 'preference': len(candidates)-index}
                    for index, url in enumerate(candidates)
                ]}
                source = youtube.YouTubeSource({
                    'artworkMaxPixels': MAX_COVER_PIXELS_PER_SIDE, 'pngMaxBytes': limit,
                })
                def fetch(url, **kwargs):
                    response = {invalid_url: payload, fallback_url: fallback_payload}.get(
                        url, b'not an image')
                    self.assertLessEqual(len(response), kwargs['max_bytes'])
                    return response
                with patch.object(youtube, 'fetch_bytes', side_effect=fetch) as download:
                    if fallback_url not in candidates:
                        with self.assertRaises(RuntimeError):
                            source.create_cover(info, 'AAAAAAAAAAA', target)
                        self.assertEqual(target.read_bytes(), previous)
                    else:
                        result = source.create_cover(info, 'AAAAAAAAAAA', target)
                        self.assertEqual(result['source']['url'], youtube.canonical_url('AAAAAAAAAAA'))
                        self.assertLessEqual(target.stat().st_size, limit)
                        with Image.open(target) as chosen:
                            self.assertEqual(chosen.format, 'PNG')
                            self.assertEqual(chosen.size, (320, 320))
                            self.assertEqual(chosen.convert('RGB').tobytes(), expected_pixels)
                        self.assertIn(fallback_url, [call.args[0] for call in download.call_args_list])
                    self.assertEqual([call.args[0] for call in download.call_args_list[:len(candidates)]],
                                     candidates)

    def test_optional_candidate_timeout_keeps_valid_cover_and_cancellation_still_aborts(self):
        import io
        import tempfile
        import stemwise_engine.youtube as youtube
        from stemwise_engine.provider_operations import provider_operation, current_budget, ProviderCancelled
        output = io.BytesIO()
        Image.new('RGB', (900, 900), '#3581aa').save(output, format='PNG')
        for cancelled in (False, True):
            epoch = [0.]
            calls = []
            def download(*args):
                calls.append(args)
                if len(calls) == 1:
                    return output.getvalue()
                if cancelled:
                    raise ProviderCancelled('explicit cancellation')
                current_budget().pause(20.)
            with self.subTest(cancelled=cancelled), tempfile.TemporaryDirectory(
                    dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
                target = Path(name)/'cover.png'
                with provider_operation(10, clock=lambda: epoch[0], sleeper=lambda value: epoch.__setitem__(0, epoch[0]+value)), \
                        patch.object(youtube, '_thumbnail_candidates', return_value=['first', 'slow']), \
                        patch.object(youtube, '_download_image', side_effect=download):
                    if cancelled:
                        with self.assertRaises(ProviderCancelled):
                            youtube.create_cover_png({}, 'AAAAAAAAAAA', target, max_pixels=4096, max_bytes=1000000)
                        self.assertFalse(target.exists())
                    else:
                        youtube.create_cover_png({}, 'AAAAAAAAAAA', target, max_pixels=4096, max_bytes=1000000)
                        with Image.open(target) as cover:
                            self.assertEqual(cover.size, (900, 900))
    def test_candidate_ranking_uses_actual_source_pixels_before_cropping_and_zip_preserves_selection(self):
        import io
        import tempfile
        import stemwise_engine.youtube as youtube
        square = Image.new('RGB', (900, 900), '#3581aa')
        padded = Image.new('RGB', (1280, 720), '#57231c')
        inset = Image.new('RGB', (720, 720), '#f4efe4')
        draw = ImageDraw.Draw(inset)
        for offset in range(0, 720, 24):
            draw.line((0, offset, 719, 719-offset), fill='#1c6680', width=7)
        padded.paste(inset, (280, 0))
        payloads = []
        for image in (padded, square):
            output = io.BytesIO()
            image.save(output, format='PNG')
            payloads.append(output.getvalue())
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            target = Path(name)/'cover.png'
            with patch.object(youtube, '_thumbnail_candidates', return_value=['padded', 'square']), \
                    patch.object(youtube, '_download_image', side_effect=payloads):
                result = youtube.create_cover_png({}, 'AAAAAAAAAAA', target, max_pixels=4096, max_bytes=1000000)
            with Image.open(target) as chosen:
                self.assertEqual(chosen.size, (720, 720))
                self.assertEqual(chosen.convert('RGB').tobytes(), inset.tobytes())
            from test_packages import package_config, song_document
            from stemwise_engine.packages import create_child_zip, read_child
            config = package_config(Path(name))
            document = song_document('AAAAAAAAAAA')
            document['artwork'] = result
            child = Path(name)/'child.zip'
            selected_bytes = target.read_bytes()
            create_child_zip(child, document, target, config)
            packaged_document, packaged_cover = read_child(child, config)
            self.assertEqual(packaged_document['artwork'], result)
            self.assertEqual(packaged_cover, selected_bytes)

    def test_actual_source_quality_beats_false_metadata_and_first_near_ceiling_candidate(self):
        import io
        import tempfile
        import stemwise_engine.youtube as youtube
        from stemwise_engine.contract import MAX_COVER_BYTES, MAX_COVER_PIXELS_PER_SIDE
        for first_size, best_size in ((900, 1200), (3000, 4096)):
            with self.subTest(first=first_size, best=best_size), tempfile.TemporaryDirectory(
                    dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
                payloads = {}
                for url, size, color in (('https://fixture.invalid/first', first_size, '#3581aa'),
                                         ('https://fixture.invalid/best', best_size, '#aa8135')):
                    with Image.new('RGB', (size, size), color) as image, io.BytesIO() as encoded:
                        image.save(encoded, format='PNG')
                        payloads[url] = encoded.getvalue()
                # A false large declaration puts the inferior source first; the winner has no dimensions.
                info = {'thumbnails': [
                    {'url': 'https://fixture.invalid/first', 'width': 4096, 'height': 4096},
                    {'url': 'https://fixture.invalid/best'},
                ]}
                target = Path(name)/'cover.png'
                source = youtube.YouTubeSource({'artworkMaxPixels': MAX_COVER_PIXELS_PER_SIDE,
                                                'pngMaxBytes': MAX_COVER_BYTES})
                with patch.object(youtube, 'fetch_bytes', side_effect=lambda url, **_: payloads.get(url, b'invalid')) as fetched:
                    source.create_cover(info, 'AAAAAAAAAAA', target)
                self.assertIn('https://fixture.invalid/best', [call.args[0] for call in fetched.call_args_list])
                with Image.open(target) as chosen:
                    self.assertEqual(chosen.size, (best_size, best_size))
                    self.assertEqual(chosen.getpixel((0, 0)), (170, 129, 53))

    def test_oversized_header_is_rejected_before_image_load(self):
        import stemwise_engine.youtube as youtube
        opened = MagicMock()
        opened.__enter__.return_value = opened
        opened.size = (6000, 6000)
        opened.width, opened.height = opened.size
        with patch.object(youtube, '_thumbnail_candidates', return_value=['https://fixture/image']), \
                patch.object(youtube, '_download_image', return_value=b'image header'), \
                patch('PIL.Image.open', return_value=opened):
            with self.assertRaisesRegex(RuntimeError, 'decoded input pixel budget'):
                youtube.create_cover_png({}, 'AAAAAAAAAAA', Path('unused.png'), max_pixels=4096, max_bytes=100000)
        opened.load.assert_not_called()

    def test_large_padding_refinement_never_materializes_full_float32_pixels(self):
        import numpy as np
        from stemwise_engine.youtube import _inset_center_square_box
        image = Image.new('RGB', (4000, 3000), 'black')
        image.paste(Image.new('RGB', (2000, 2000), '#a76c38'), (1000, 500))
        actual_asarray = np.asarray
        shapes = []
        def bounded(value, *args, **kwargs):
            result = actual_asarray(value, *args, **kwargs)
            if result.dtype == np.float32 and result.ndim == 3:
                shapes.append(result.shape)
            return result
        with patch.object(np, 'asarray', side_effect=bounded):
            box, _ = _inset_center_square_box(image)
        self.assertIsNotNone(box)
        self.assertTrue(shapes)
        self.assertLess(max(np.prod(shape) for shape in shapes), 4000 * 181 * 3)

    def test_centered_square_side_padding_is_removed(self) -> None:
        image = Image.new("RGB", (1280, 720), "#57231c")
        artwork = Image.new("RGB", (720, 720), "#f4efe4")
        draw = ImageDraw.Draw(artwork)
        for offset in range(0, 720, 24):
            draw.line((0, offset, 719, 719 - offset), fill="#1c6680", width=7)
        image.paste(artwork, (280, 0))

        cropped, score = remove_center_square_padding(image)

        self.assertGreaterEqual(score, CENTER_SQUARE_PADDING_THRESHOLD)
        self.assertEqual((720, 720), cropped.size)
        self.assertEqual(artwork.tobytes(), cropped.tobytes())

    def test_genuine_wide_artwork_is_preserved(self) -> None:
        image = Image.new("RGB", (1280, 720))
        pixels = image.load()
        for y in range(image.height):
            for x in range(image.width):
                pixels[x, y] = (x * 255 // 1279, y * 255 // 719, (x + y) * 255 // 1998)

        result, score = remove_center_square_padding(image)

        self.assertLess(score, CENTER_SQUARE_PADDING_THRESHOLD)
        self.assertEqual((1280, 720), result.size)

    def test_blended_side_padding_is_removed_from_complexity_ratio(self) -> None:
        image = Image.new("RGB", (1280, 720), "#6d1e26")
        draw = ImageDraw.Draw(image)
        for offset in range(80, 640, 20):
            draw.rectangle((330, offset, 950, offset + 8), fill="#c5d5db")
        result, score = remove_center_square_padding(image)
        self.assertGreaterEqual(score, CENTER_SQUARE_PADDING_THRESHOLD)
        self.assertEqual((720, 720), result.size)

    def test_inset_square_with_four_sided_letterboxing_is_removed(self) -> None:
        image = Image.new("RGB", (640, 480), "black")
        draw = ImageDraw.Draw(image)
        draw.rectangle((0, 60, 639, 419), fill="#444444")
        artwork = Image.new("RGB", (360, 360), "#eeeeee")
        artwork_draw = ImageDraw.Draw(artwork)
        artwork_draw.rectangle((80, 70, 280, 300), fill="#222222")
        image.paste(artwork, (140, 60))

        result, score = remove_center_square_padding(image)

        self.assertGreaterEqual(score, CENTER_SQUARE_PADDING_THRESHOLD)
        self.assertEqual((360, 360), result.size)
        self.assertEqual(artwork.tobytes(), result.tobytes())

    def test_symmetric_black_horizontal_letterbox_is_removed(self) -> None:
        image = Image.new("RGB", (480, 360), "black")
        artwork = Image.new("RGB", (480, 270), "#d8cdbb")
        artwork_draw = ImageDraw.Draw(artwork)
        artwork_draw.ellipse((30, 20, 220, 250), fill="#725744")
        artwork_draw.rectangle((250, 0, 479, 269), fill="#334455")
        image.paste(artwork, (0, 45))

        result, score = remove_center_square_padding(image)

        self.assertGreaterEqual(score, CENTER_SQUARE_PADDING_THRESHOLD)
        self.assertEqual((480, 270), result.size)
        self.assertEqual(artwork.tobytes(), result.tobytes())

    def test_intentional_black_sides_on_square_cover_are_preserved(self) -> None:
        image = Image.new("RGB", (720, 720), "black")
        draw = ImageDraw.Draw(image)
        draw.ellipse((250, 40, 470, 300), fill="#74594c")
        draw.rectangle((190, 300, 530, 719), fill="#171717")
        draw.text((95, 520), "NO ONE BUT YOU", fill="#d8cfc5")

        result, score = remove_center_square_padding(image)

        self.assertEqual((720, 720), result.size)
        self.assertEqual(image.tobytes(), result.tobytes())

    def test_square_artwork_is_preserved(self) -> None:
        image = Image.new("RGB", (1000, 1000), "navy")
        result, score = remove_center_square_padding(image)
        self.assertEqual(0.0, center_square_padding_score(image))
        self.assertEqual(0.0, score)
        self.assertEqual((1000, 1000), result.size)


if __name__ == "__main__":
    unittest.main()

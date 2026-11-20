from __future__ import annotations

import copy
import json
import hashlib
import re
import sys
import unittest
from pathlib import Path


SRC = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder" / "src"

import installed_contract


class InstalledContractTests(unittest.TestCase):
    VIDEO_ID = "AbCdEf_12-3"

    def test_qualification_seed_manifests_match_the_exact_staged_media(self):
        assets = Path(__file__).resolve().parents[3] / 'StemWise/app/src/development/assets/demo_library'
        manifests = sorted(assets.glob('*/song.json'))
        self.assertEqual(3, len(manifests))
        for path in manifests:
            with self.subTest(song=path.parent.name):
                document = installed_contract.parse_installed_json(path.read_bytes())
                original = document['media']['files'][0]
                audio = (path.parent / original['path']).read_bytes()
                self.assertEqual(len(audio), original['byteLength'])
                self.assertEqual(hashlib.sha256(audio).hexdigest(), original['sha256'])
                artwork = document['artwork']
                cover = (path.parent / artwork['file']).read_bytes()
                self.assertEqual(len(cover), artwork['byteLength'])
                self.assertEqual(hashlib.sha256(cover).hexdigest(), artwork['sha256'])
                installed_contract.validate_installed_document(document, expected_video_id=path.parent.name,
                    expected_original_sha256=original['sha256'])

    def test_explicit_current_transport_and_installed_examples_pass_schema(self):
        from stemwise_engine.song_enrichment_v2 import validate_song
        from jsonschema import Draft202012Validator
        root = Path(__file__).resolve().parents[3]
        validate_song(self.payload())
        document = self.document()
        installed_contract.validate_installed_document(document, expected_video_id=self.VIDEO_ID,
            expected_original_sha256=self.media()['original.opus']['sha256'])
        schema = root/'StemWise/contracts/enrichment/song-enrichment-v2.schema.json'
        Draft202012Validator(json.loads(schema.read_bytes())).validate(self.payload())

    def test_youtube_original_uses_its_actual_codec_without_native_marker(self) -> None:
        for path in ("original.opus", "original.flac"):
            with self.subTest(path=path):
                payload = self.payload()
                original = {"sha256": "a" * 64, "byteLength": 123456}
                document = installed_contract.build_installed_document(
                    payload, original_sha256=original["sha256"], media={path: original},
                    content_revision=1, payload_sha256="f" * 64,
                    artwork_sha256="b" * 64, artwork_bytes=68,
                )
                installed_contract.validate_installed_document(document,
                    expected_video_id=self.VIDEO_ID, expected_original_sha256=original["sha256"])
                self.assertEqual([{"path": path, **original}], document["media"]["files"])
                self.assertNotIn("recording", document["source"])

    def test_unknown_or_multiple_originals_reject_without_changing_input(self) -> None:
        original = {"sha256": "a" * 64, "byteLength": 123456}
        for media in ({}, {"original.opus": original, "original.flac": original},
                      {"Original.flac": original}, {"original.wav": original},
                      {"original.flac": original, "part-1.flac": original}):
            payload = self.payload()
            before = copy.deepcopy(payload)
            with self.subTest(paths=list(media)), self.assertRaises(ValueError):
                installed_contract.build_installed_document(payload,
                    original_sha256=original["sha256"], media=media, content_revision=1,
                    payload_sha256="f" * 64, artwork_sha256="b" * 64, artwork_bytes=68)
            self.assertEqual(before, payload)
        for path in ("original.wav", "Original.opus"):
            document = self.document()
            document["media"]["files"][0]["path"] = path
            document["install"]["generatedDigest"] = installed_contract.installed_digest(document)
            before = copy.deepcopy(document)
            with self.subTest(path=path), self.assertRaises(ValueError):
                installed_contract.validate_installed_document(document,
                    expected_video_id=self.VIDEO_ID,
                    expected_original_sha256=self.media()["original.opus"]["sha256"])
            self.assertEqual(before, document)

    def test_native_original_still_requires_flac_and_exact_bytes(self) -> None:
        original = {"sha256": "a" * 64, "byteLength": 123456}
        payload = self.payload()
        payload["source"]["recording"] = {
            "recordingKey": "qobuz:123", "trackId": 123,
            "file": "PC side/qobuz-comparison/downloads/123/audio.flac",
            "sha256": original["sha256"], "byteCount": original["byteLength"], "durationMs": 12000,
        }
        for path, identity in (("original.flac", original),
                               ("original.opus", original),
                               ("original.flac", {**original, "sha256": "b" * 64}),
                               ("original.flac", {**original, "byteLength": 123455})):
            with self.subTest(path=path, identity=identity):
                def build():
                    return installed_contract.build_installed_document(payload,
                        original_sha256=identity["sha256"], media={path: identity}, content_revision=1,
                        payload_sha256="f" * 64, artwork_sha256="b" * 64, artwork_bytes=68)
                if path == "original.flac" and identity == original:
                    document = build()
                    self.assertEqual([{"path": path, **original}], document["media"]["files"])
                    document["media"]["files"][0]["path"] = "original.opus"
                    document["install"]["generatedDigest"] = installed_contract.installed_digest(document)
                    with self.assertRaises(ValueError):
                        installed_contract.validate_installed_document(document,
                            expected_video_id=self.VIDEO_ID, expected_original_sha256=original["sha256"])
                else:
                    with self.assertRaises(ValueError):
                        build()

    def test_installed_schema_admits_actual_codec_and_rejects_ambiguous_media(self) -> None:
        from jsonschema import Draft202012Validator
        schema_path = Path(__file__).resolve().parents[3] / "StemWise/contracts/enrichment/installed-enrichment-v2.schema.json"
        validator = Draft202012Validator(json.loads(schema_path.read_text(encoding="utf-8")))
        for path in ("original.opus", "original.flac"):
            document = self.document()
            document["media"]["files"][0]["path"] = path
            self.assertEqual([], list(validator.iter_errors(document)))
            for invalid in ([], [{**document["media"]["files"][0], "path": "original.wav"}],
                            [document["media"]["files"][0]] * 2):
                changed = copy.deepcopy(document)
                changed["media"]["files"] = invalid
                self.assertTrue(list(validator.iter_errors(changed)))
            document["source"]["recording"] = {
                "recordingKey": "qobuz:123", "trackId": 123,
                "file": "PC side/qobuz-comparison/downloads/123/audio.flac",
                "sha256": document["media"]["files"][0]["sha256"],
                "byteCount": document["media"]["files"][0]["byteLength"], "durationMs": 12000,
            }
            self.assertEqual(path != "original.flac", bool(list(validator.iter_errors(document))))

    def payload(self) -> dict:
        return {
            "formatVersion": 2,
            "kind": "stemwise.song-enrichment",
            "source": {"provider": "youtube", "videoId": self.VIDEO_ID},
            "generatedAt": "2026-08-09T12:00:00Z",
            "generator": {"name": "stemwise-pc", "version": "2.1.0"},
            "artwork": {
                "file": "cover.png",
                "kind": "youtube-thumbnail",
                "source": {"name": "YouTube thumbnail"},
            },
            "metadata": {
                "matchStatus": "youtube-fallback",
                "title": "Contract fixture",
                "artists": ["Contract artist"],
                "sources": [
                    {
                        "name": "YouTube Music",
                        "url": f"https://music.youtube.com/watch?v={self.VIDEO_ID}",
                    }
                ],
            },
        }

    def media(self) -> dict[str, dict]:
        return {
            path: {"sha256": f"{index + 1:064x}", "byteLength": (index + 1) * 1000}
            for index, path in enumerate(("original.opus",))
        }

    def document(self) -> dict:
        media = self.media()
        return installed_contract.build_installed_document(
            self.payload(),
            original_sha256=media["original.opus"]["sha256"],
            media=media,
            content_revision=3,
            payload_sha256="f" * 64,
            artwork_sha256="a" * 64,
            artwork_bytes=68,
        )

    def test_installed_projection_binds_only_the_required_original(self) -> None:
        document = self.document()
        self.assertEqual(
            ["original.opus"],
            [item["path"] for item in document["media"]["files"]],
        )
        self.assertRegex(document["install"]["generatedDigest"], r"^[a-f0-9]{64}$")
        self.assertEqual("selected-root", document["artwork"]["delivery"])
        self.assertEqual("cover.png", document["artwork"]["file"])
        installed_contract.validate_installed_document(
            document,
            expected_video_id=self.VIDEO_ID,
            expected_original_sha256=self.media()["original.opus"]["sha256"],
        )

    def test_extra_or_nested_media_fails_closed(self) -> None:
        document = self.document()
        document["media"]["files"].append(copy.deepcopy(document["media"]["files"][0]))
        with self.assertRaises(ValueError):
            installed_contract.validate_installed_document(
                document,
                expected_video_id=self.VIDEO_ID,
                expected_original_sha256=self.media()["original.opus"]["sha256"],
            )

    def test_digest_tampering_and_case_changed_identity_are_rejected(self) -> None:
        document = self.document()
        tampered = copy.deepcopy(document)
        tampered["metadata"]["title"] = "Changed without digest"
        with self.assertRaises(ValueError):
            installed_contract.validate_installed_document(
                tampered,
                expected_video_id=self.VIDEO_ID,
                expected_original_sha256=self.media()["original.opus"]["sha256"],
            )
        with self.assertRaises(ValueError):
            installed_contract.validate_installed_document(
                document,
                expected_video_id=self.VIDEO_ID.lower(),
                expected_original_sha256=self.media()["original.opus"]["sha256"],
            )

        for field, value in (("name", "another-pc"), ("version", "2.0.1")):
            changed = copy.deepcopy(document)
            changed["generator"][field] = value
            changed["install"]["generatedDigest"] = installed_contract.installed_digest(changed)
            with self.subTest(field=field), self.assertRaises(ValueError):
                installed_contract.validate_installed_document(
                    changed,
                    expected_video_id=self.VIDEO_ID,
                    expected_original_sha256=self.media()["original.opus"]["sha256"],
                )

    def test_missing_media_identity_never_builds_an_installed_document(self) -> None:
        media = self.media()
        original_sha256 = media.pop("original.opus")["sha256"]
        with self.assertRaises(ValueError):
            installed_contract.build_installed_document(
                self.payload(),
                original_sha256=original_sha256,
                media=media,
                content_revision=1,
                payload_sha256="f" * 64,
                artwork_sha256="a" * 64,
                artwork_bytes=68,
            )

    def test_transport_v1_cannot_build_an_installed_document(self) -> None:
        payload = self.payload()
        payload["formatVersion"] = 1
        media = self.media()
        with self.assertRaises(ValueError):
            installed_contract.build_installed_document(
                payload,
                original_sha256=media["original.opus"]["sha256"],
                media=media,
                content_revision=1,
                payload_sha256="f" * 64,
                artwork_sha256="a" * 64,
                artwork_bytes=68,
            )

    def test_shared_negative_corpus_fails_closed_for_bytes_and_types(self) -> None:
        corpus_path = (
            Path(__file__).resolve().parents[3]
            / "Test suite"
            / "android"
            / "fixtures"
            / "schemas"
            / "installed-enrichment-v2-negative-corpus.json"
        )
        corpus = json.loads(corpus_path.read_text(encoding="utf-8"))
        for case in corpus["rawCases"]:
            raw = (
                bytes.fromhex(case["hex"])
                if "hex" in case
                else case["text"].encode("utf-8")
            )
            if case.get("prefixBom"):
                raw = b"\xef\xbb\xbf" + raw
            with self.subTest(case=case["name"]), self.assertRaises(ValueError):
                installed_contract.parse_installed_json(raw)

        for case in corpus["mutationCases"]:
            changed = copy.deepcopy(self.document())
            name = case["name"]
            if name == "missing-generated-at":
                changed.pop("generatedAt")
            elif name == "wrong-generator-type":
                changed["generator"] = []
            elif name == "fractional-content-revision":
                changed["install"]["contentRevision"] = 1.5
            elif name == "string-byte-length":
                changed["media"]["files"][0]["byteLength"] = "1000"
            elif name == "non-object-optional-group":
                changed["lyrics"] = []
            elif name == "non-canonical-generated-at":
                changed["generatedAt"] = "2026-08-09T12:00:00.0Z"
            elif name == "open-root":
                changed["extra"] = True
            elif name == "open-source":
                changed["source"]["extra"] = True
            elif name == "open-generator":
                changed["generator"]["extra"] = True
            elif name == "open-media":
                changed["media"]["extra"] = True
            elif name == "open-media-entry":
                changed["media"]["files"][0]["extra"] = True
            elif name == "open-install":
                changed["install"]["extra"] = True
            elif name == "open-artwork":
                changed["artwork"]["extra"] = True
            elif name == "open-artwork-source":
                changed["artwork"]["source"]["extra"] = True
            elif name == "missing-artwork":
                changed.pop("artwork")
            elif name == "missing-artwork-file":
                changed["artwork"].pop("file")
            elif name == "wrong-artwork-file":
                changed["artwork"]["file"] = "Cover.png"
            elif name == "uppercase-artwork-hash":
                changed["artwork"]["sha256"] = "A" * 64
            elif name == "extra-media":
                changed["media"]["files"].append(
                    copy.deepcopy(changed["media"]["files"][0])
                )
            elif name == "case-changed-path":
                changed["media"]["files"][0]["path"] = "Original.opus"
            elif name == "wrong-source-id":
                changed["source"]["videoId"] = "ZbCdEf_12-3"
            elif name == "stale-original-hash":
                changed["install"]["originalSha256"] = "b" * 64
            elif name == "uppercase-media-hash":
                changed["media"]["files"][0]["sha256"] = "A" * 64
            elif name == "wrong-artwork-delivery":
                changed["artwork"]["delivery"] = "app-private-cache"
            elif name == "wrong-artwork-kind":
                changed["artwork"]["kind"] = "cover"
            elif name == "digest-mismatch":
                changed["install"]["generatedDigest"] = "0" * 64
            else:
                self.fail(f"unknown shared corpus case {name}")
            if name != 'digest-mismatch':
                changed['install']['generatedDigest'] = installed_contract.installed_digest(changed)
            with self.subTest(case=name), self.assertRaises(ValueError) as caught:
                installed_contract.validate_installed_document(
                    changed,
                    expected_video_id=self.VIDEO_ID,
                    expected_original_sha256=self.media()["original.opus"]["sha256"],
                )
            if name != 'digest-mismatch':
                self.assertNotEqual(str(caught.exception), 'installed enrichment digest is invalid', name)

    def test_nested_generated_groups_fail_with_matching_digest(self):
        for group, value in (('metadata', {'title': 123}), ('lyrics', {'bogus': True}),
                             ('analysis', {'durationMs': -1})):
            changed = copy.deepcopy(self.document())
            changed[group] = value
            changed['install']['generatedDigest'] = installed_contract.installed_digest(changed)
            with self.subTest(group=group), self.assertRaises(ValueError):
                installed_contract.validate_installed_document(changed,
                    expected_video_id=self.VIDEO_ID,
                    expected_original_sha256=self.media()['original.opus']['sha256'])

    def test_integer_digest_domain_rejects_inexact_binary64_values(self):
        for value in (9007199254740992, 9007199254740993):
            changed = copy.deepcopy(self.document())
            changed['install']['contentRevision'] = value
            with self.assertRaisesRegex(ValueError, 'binary64'):
                installed_contract.installed_digest(changed)

    def test_shared_timestamp_corpus_has_exact_pc_acceptance_parity(self) -> None:
        corpus_path = (
            Path(__file__).resolve().parents[3]
            / "Test suite"
            / "android"
            / "fixtures"
            / "schemas"
            / "installed-enrichment-v2-timestamp-corpus.json"
        )
        corpus = json.loads(corpus_path.read_text(encoding="utf-8"))
        for value in corpus["positive"]:
            with self.subTest(value=value):
                self.assertTrue(installed_contract.is_canonical_utc_timestamp(value))
        for case in corpus["negative"]:
            with self.subTest(case=case["name"]):
                self.assertFalse(
                    installed_contract.is_canonical_utc_timestamp(case["value"])
                )


if __name__ == "__main__":
    unittest.main()

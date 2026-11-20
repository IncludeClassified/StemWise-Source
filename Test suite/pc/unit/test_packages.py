from __future__ import annotations

import base64
import copy
import hashlib
import json
import stat
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock


PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"

import stemwise_engine.contract as contract  # noqa: E402
from stemwise_engine.common import atomic_write_json, load_config  # noqa: E402
from stemwise_engine.packages import (  # noqa: E402
    PackageValidationError,
    create_child_zip,
    read_child,
    validate_child_zip,
)


PNG_1X1 = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC"
)


def package_config(root: Path) -> dict:
    layout = json.loads((Path(__file__).resolve().parents[3]/'PC side/runtime-layout.json').read_text(encoding='utf-8'))
    for pc_root, builder in ((root, 'project'), (root/'PC side', 'stemwise-playlist-builder')):
        pc_root.mkdir(parents=True, exist_ok=True)
        fixture = {**layout, 'environments': {name: f'{builder}/{directory}/bin/python'
            for name, directory in (('main', '.venv'), ('allin1', '.venv-allin1'), ('lyrics', '.venv-lyrics-align'))}}
        (pc_root/'runtime-layout.json').write_text(json.dumps(fixture), encoding='utf-8')
    return {
        "projectDir": str(root / "project"),
        "outputDir": str(root / "output"),
        "tempDir": str(root / "temporary-audio"),
        "playlistUrl": "https://music.youtube.com/playlist?list=PL00000000000000000000000000000000",
        "playlistName": "Awakening",
        "generatorVersion": "2.1.0",
        "artworkMaxPixels": contract.MAX_COVER_PIXELS_PER_SIDE,
        "jsonMaxBytes": contract.MAX_JSON_BYTES,
        "pngMaxBytes": contract.MAX_COVER_BYTES,
        "childZipMaxBytes": contract.MAX_SINGLE_ZIP_BYTES,
        "analysisTimeoutMinutes": 45,
        "keepFailedAudio": False,
    }


def song_document(video_id: str) -> dict:
    return {
        "formatVersion": 2,
        "kind": "stemwise.song-enrichment",
        "source": {"provider": "youtube", "videoId": video_id},
        "generatedAt": "2026-07-26T12:00:00Z",
        "generator": {"name": "stemwise-pc", "version": "2.1.0"},
        "metadata": {
            "matchStatus": "youtube-fallback",
            "title": f"Song {video_id}",
            "artists": ["Artist"],
            "sources": [
                {
                    "name": "YouTube",
                    "url": f"https://www.youtube.com/watch?v={video_id}",
                }
            ],
        },
        "artwork": {
            "file": "cover.png",
            "kind": "youtube-thumbnail",
            "source": {
                "name": "YouTube thumbnail",
                "url": f"https://www.youtube.com/watch?v={video_id}",
            },
        },
        "lyrics": {
            "status": "unavailable",
            "reason": "No owner-supplied or authorized full lyrics were provided.",
            "tracks": [],
        },
        "analysis": {
            "status": "partial",
            "durationMs": 1_000,
            **{
                name: {"status": "unavailable", "reason": "No analysis input."}
                for name in ("rhythm", "key", "structure", "tuning", "loudness")
            },
            "waveform": {
                "status": "partial",
                "reason": "Only one waveform level was available.",
                "encoding": "s16le-min-max-rms-base64-v1",
                "levels": [
                    {
                        "frameDurationMs": 50,
                        "frameCount": 20,
                        "data": base64.b64encode(b"\x00" * 120).decode("ascii"),
                    }
                ],
            },
            "toolRuns": [
                (
                    {"name": name, "status": "complete", "version": "1"}
                    if name == "stemwise-waveform"
                    else {"name": name, "status": "skipped", "reason": "No analysis input."}
                )
                for name in (
                    "beat-this-full",
                    "stemwise-rhythm-normalizer",
                    "s-key",
                    "all-in-one",
                    "essentia",
                    "stemwise-waveform",
                )
            ],
        },
    }


def write_child(
    path: Path,
    video_id: str,
    config: dict,
    *,
    generated_at: str | None = None,
) -> None:
    cover = path.parent / f"{path.stem}.cover.png"
    cover.parent.mkdir(parents=True, exist_ok=True)
    cover.write_bytes(PNG_1X1)
    document = song_document(video_id)
    if generated_at is not None:
        document["generatedAt"] = generated_at
    create_child_zip(path, document, cover, config)


class PackageBoundaryTests(unittest.TestCase):
    def test_child_creation_rejects_linked_cover_without_replacing_destination(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            cover = root / "cover.png"
            cover.write_bytes(PNG_1X1)
            linked = root / "linked.png"
            linked.symlink_to(cover)
            destination = root / "existing.zip"
            destination.write_bytes(b"retained destination")
            with self.assertRaisesRegex(PackageValidationError, "symlink|regular"):
                create_child_zip(
                    destination, song_document("a1B2c3D4e5F"), linked, package_config(root)
                )
            self.assertEqual(b"retained destination", destination.read_bytes())



    def test_private_child_bytes_use_canonical_metadata_and_ignore_output_root_time(self) -> None:
        with tempfile.TemporaryDirectory() as first_name, tempfile.TemporaryDirectory() as second_name:
            roots = [Path(first_name), Path(second_name)]
            child_payloads: list[bytes] = []
            for root in roots:
                config = package_config(root)
                children = []
                for video_id in ("a1B2c3D4e5F", "f6G7h8I9j0K"):
                    child = root / f"{video_id}.stemwise.zip"
                    write_child(child, video_id, config)
                    children.append(child)
                child_payloads.append(children[0].read_bytes())

                for path, expected_names, compression in (
                    (children[0], ["song.json", "cover.png"], zipfile.ZIP_DEFLATED),
                ):
                    with zipfile.ZipFile(path, "r") as archive:
                        self.assertEqual(archive.namelist(), expected_names)
                        for info in archive.infolist():
                            self.assertEqual(info.date_time, (1980, 1, 1, 0, 0, 0))
                            self.assertEqual(info.create_system, 3)
                            self.assertEqual(info.external_attr, (stat.S_IFREG | 0o644) << 16)
                            self.assertEqual(info.internal_attr, 0)
                            self.assertEqual(info.compress_type, compression)
                            self.assertEqual(info.flag_bits, 0)

            self.assertEqual(child_payloads[0], child_payloads[1])

            changed = song_document("a1B2c3D4e5F")
            changed["metadata"]["title"] = "Deliberately changed title"
            changed_child = roots[1] / "changed.stemwise.zip"
            create_child_zip(
                changed_child,
                changed,
                roots[1] / "a1B2c3D4e5F.stemwise.cover.png",
                package_config(roots[1]),
            )
            self.assertNotEqual(
                hashlib.sha256(child_payloads[0]).hexdigest(),
                hashlib.sha256(changed_child.read_bytes()).hexdigest(),
            )

    def test_finalized_child_always_requires_artwork_and_cover_pair(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            config = package_config(root)
            document = song_document("a1B2c3D4e5F")
            for group in ("artwork", "lyrics", "analysis"):
                del document[group]
            child = root / "metadata-update.zip"
            with self.assertRaisesRegex(PackageValidationError, "requires artwork"):
                create_child_zip(child, document, None, config)

            with self.assertRaisesRegex(PackageValidationError, "requires artwork"):
                create_child_zip(root / "missing-cover.zip", song_document("a1B2c3D4e5F"), None, config)

            cover = root / "cover.png"
            cover.write_bytes(PNG_1X1)
            with self.assertRaisesRegex(PackageValidationError, "requires artwork"):
                create_child_zip(root / "unexpected-cover.zip", document, cover, config)

            externally_invalid = root / "external-missing-cover.zip"
            with zipfile.ZipFile(externally_invalid, "w") as archive:
                archive.writestr(
                    "song.json",
                    json.dumps(song_document("a1B2c3D4e5F"), separators=(",", ":")),
                )
            with self.assertRaisesRegex(PackageValidationError, "exactly root"):
                validate_child_zip(externally_invalid, config)

    def test_child_rejects_case_insensitive_duplicate_names(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            config = package_config(root)
            path = root / "duplicates.zip"
            song = json.dumps(song_document("a1B2c3D4e5F"), separators=(",", ":")).encode()
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("song.json", song)
                archive.writestr("SONG.JSON", song)
                archive.writestr("cover.png", PNG_1X1)
            with self.assertRaisesRegex(PackageValidationError, "case-insensitive duplicate"):
                validate_child_zip(path, config)

    def test_child_rejects_non_strict_json_at_the_package_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            valid = json.dumps(song_document("a1B2c3D4e5F"), separators=(",", ":")).encode()
            for raw in (
                b"\xef\xbb\xbf" + valid,
                b'{"formatVersion":2,' + valid[1:],
                valid.replace(b'"formatVersion":2', b'"formatVersion":NaN'),
                valid.replace(b"Artist", b"\xff"),
                b"[" * 2_000 + b"]" * 2_000,
            ):
                with self.subTest(prefix=raw[:20]):
                    child = root / "malformed.zip"
                    with zipfile.ZipFile(child, "w") as archive:
                        archive.writestr("song.json", raw)
                        archive.writestr("cover.png", PNG_1X1)
                    with self.assertRaisesRegex(PackageValidationError, "strict UTF-8 JSON"):
                        validate_child_zip(child, package_config(root))

    def test_child_rejects_explicit_symlink_member(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            config = package_config(root)
            path = root / "a1B2c3D4e5F.stemwise.zip"
            song = json.dumps(song_document("a1B2c3D4e5F"), separators=(",", ":")).encode()
            symlink = zipfile.ZipInfo("song.json")
            symlink.create_system = 3
            symlink.external_attr = (stat.S_IFLNK | 0o777) << 16
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr(symlink, song)
                archive.writestr("cover.png", PNG_1X1)
            with self.assertRaisesRegex(PackageValidationError, "regular file"):
                validate_child_zip(path, config)

    def test_cover_uncompressed_size_is_checked_before_read(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            config = package_config(root)
            path = root / "a1B2c3D4e5F.stemwise.zip"
            write_child(path, "a1B2c3D4e5F", config)
            restrictive = dict(config, pngMaxBytes=8)
            original_open = zipfile.ZipFile.open
            opens: list[str] = []

            def tracking_open(archive, member, *args, **kwargs):
                opens.append(member.filename if isinstance(member, zipfile.ZipInfo) else member)
                return original_open(archive, member, *args, **kwargs)

            with mock.patch.object(zipfile.ZipFile, "open", tracking_open):
                with self.assertRaisesRegex(PackageValidationError, "cover.png exceeds"):
                    validate_child_zip(path, restrictive)
            self.assertIn("song.json", opens)
            self.assertNotIn("cover.png", opens)

    def test_read_child_uses_one_archive_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            config = package_config(root)
            path = root / "a1B2c3D4e5F.stemwise.zip"
            write_child(path, "a1B2c3D4e5F", config)
            real_zip_file = zipfile.ZipFile
            opens = 0

            def counted_zip_file(*args, **kwargs):
                nonlocal opens
                opens += 1
                return real_zip_file(*args, **kwargs)

            with mock.patch('stemwise_engine.packages.zipfile.ZipFile', side_effect=counted_zip_file):
                document, cover = read_child(path, config)
            self.assertEqual(opens, 1)
            self.assertEqual(document["source"]["videoId"], "a1B2c3D4e5F")
            self.assertEqual(cover, PNG_1X1)







    def test_frozen_limits_cannot_be_raised_by_config(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            config = package_config(root)
            child = root / "a1B2c3D4e5F.stemwise.zip"
            write_child(child, "a1B2c3D4e5F", config)
            raised = dict(config, jsonMaxBytes=contract.MAX_JSON_BYTES + 1)
            with self.assertRaisesRegex(PackageValidationError, "external contract maximum"):
                validate_child_zip(child, raised)

            config_path = root / "config.json"
            disk_config = {
                "playlistUrl": config["playlistUrl"],
                "outputDirWindows": str(root / "loaded-output"),
                "generatorVersion": "2.1.0",
                "jsonMaxBytes": contract.MAX_JSON_BYTES + 1,
            }
            config_path.write_text(json.dumps(disk_config), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "external contract maximum"):
                load_config(config_path)

    def test_config_defaults_match_android_limits(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            config_path = root / "config.json"
            config_path.write_text(
                json.dumps(
                    {
                        "playlistUrl": "https://music.youtube.com/playlist?list=PL00000000000000000000000000000000",
                        "outputDirWindows": str(root / "output"),
                        "generatorVersion": "2.1.0",
                    }
                ),
                encoding="utf-8",
            )
            loaded = load_config(config_path)
            self.assertEqual(loaded["pngMaxBytes"], 16 * 1024 * 1024)
            self.assertEqual(loaded["childZipMaxBytes"], 32 * 1024 * 1024)





if __name__ == "__main__":
    unittest.main()

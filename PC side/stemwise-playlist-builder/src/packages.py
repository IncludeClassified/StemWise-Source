"""Private metadata ZIP preparation for the reviewed one-song workflow."""

from __future__ import annotations

import io
import hashlib
import os
import stat
import tempfile
import zipfile
from pathlib import Path
from typing import Any

from stemwise_engine.common import (
    VIDEO_ID_RE,
    durable_replace,
    json_bytes,
    parse_strict_json_bytes,
    read_bounded_regular_bytes,
)
from stemwise_engine.contract import (
    MAX_COVER_BYTES,
    MAX_COVER_PIXELS_PER_SIDE,
    MAX_JSON_BYTES,
    MAX_SINGLE_ZIP_BYTES,
)


class PackageValidationError(ValueError):
    pass


from stemwise_engine.workflow_records import ArtworkUnavailableError


class PackageArtworkError(PackageValidationError, ArtworkUnavailableError):
    """A package artwork failure retains both validation and repair ownership."""



CANONICAL_ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
CANONICAL_ZIP_CREATOR_SYSTEM = 3
CANONICAL_ZIP_EXTERNAL_ATTR = (stat.S_IFREG | 0o644) << 16


def _canonical_zip_info(name: str, compression: int) -> zipfile.ZipInfo:
    """Return byte-canonical metadata for one regular ZIP member."""

    info = zipfile.ZipInfo(filename=name, date_time=CANONICAL_ZIP_TIMESTAMP)
    info.compress_type = compression
    info.create_system = CANONICAL_ZIP_CREATOR_SYSTEM
    info.external_attr = CANONICAL_ZIP_EXTERNAL_ATTR
    info.internal_attr = 0
    info.flag_bits = 0
    return info


def _configured_limit(config: dict[str, Any], key: str, frozen_maximum: int) -> int:
    value = config.get(key)
    if type(value) is not int or value <= 0:
        raise PackageValidationError(f"{key} must be a positive integer")
    if value > frozen_maximum:
        raise PackageValidationError(
            f"{key} cannot exceed the external contract maximum {frozen_maximum}"
        )
    return value


def _require_regular_member(info: zipfile.ZipInfo, context: str) -> None:
    if info.is_dir():
        raise PackageValidationError(f"{context} must be a regular file")
    if info.create_system == 3:
        member_type = stat.S_IFMT((info.external_attr >> 16) & 0xFFFF)
        # Some ZIP writers omit the Unix file-type bits. Accept that neutral
        # representation, but reject explicit symlinks/devices/FIFOs/sockets.
        if member_type not in (0, stat.S_IFREG):
            raise PackageValidationError(f"{context} must be a regular file, not a symlink or special file")


def _require_regular_path(path: Path, context: str) -> os.stat_result:
    try:
        result = path.lstat()
    except FileNotFoundError as error:
        raise PackageValidationError(f"{context} does not exist: {path.name}") from error
    if not stat.S_ISREG(result.st_mode):
        raise PackageValidationError(f"{context} must be a regular file")
    return result


def _run_contract(document: dict[str, Any]) -> None:
    try:
        from stemwise_engine.song_enrichment_v2 import validate_song, require_publication_lyrics
    except ImportError as error:
        raise PackageValidationError("version-2 enrichment validator is unavailable") from error
    try:
        validate_song(document)
        if "lyrics" in document:
            require_publication_lyrics(document["lyrics"])
    except Exception as error:
        raise PackageValidationError(str(error)) from error


def validate_document(
    document: dict[str, Any],
    expected_video_id: str | None = None,
    *, recording_input: dict[str, Any] | None = None,
) -> None:
    if not isinstance(document, dict):
        raise PackageValidationError("song.json root must be an object")
    try:
        video_id = document["source"]["videoId"]
    except (KeyError, TypeError) as error:
        raise PackageValidationError("song.json has no source.videoId") from error
    if not isinstance(video_id, str) or not VIDEO_ID_RE.fullmatch(video_id):
        raise PackageValidationError("source.videoId is not an exact YouTube ID")
    if expected_video_id is not None and video_id != expected_video_id:
        raise PackageValidationError(
            f"Expected video ID {expected_video_id}, found {video_id}"
        )
    if recording_input is not None:
        if document["source"].get("recording") != recording_input:
            raise PackageValidationError("Native result has a missing or different recording binding")
    _run_contract(document)


def _verify_png(payload: bytes, *, max_bytes: int, max_pixels: int) -> None:
    if not payload.startswith(b"\x89PNG\r\n\x1a\n"):
        raise PackageArtworkError("cover.png does not have a PNG signature")
    if len(payload) > max_bytes:
        raise PackageArtworkError("cover.png exceeds the configured limit")
    try:
        import stemwise_engine.contract as contract

        image = contract.validate_cover_png(payload)
        if image.width > max_pixels or image.height > max_pixels:
            raise PackageArtworkError('cover.png exceeds the pixel limit')
    except Exception as error:
        raise PackageArtworkError(str(error)) from error


def validate_child_payloads(raw_json: bytes, cover: bytes, *, expected_video_id=None,
                            json_max_bytes=MAX_JSON_BYTES, png_max_bytes=MAX_COVER_BYTES,
                            artwork_max_pixels=MAX_COVER_PIXELS_PER_SIDE, recording_input=None):
    if len(raw_json) > json_max_bytes:
        raise PackageValidationError('song.json exceeds the configured limit')
    try:
        document = parse_strict_json_bytes(raw_json)
    except ValueError as error:
        raise PackageValidationError('song.json is not strict UTF-8 JSON') from error
    validate_document(document, expected_video_id, recording_input=recording_input)
    if 'artwork' not in document:
        raise PackageArtworkError('Every finalized child requires paired artwork and cover.png')
    _verify_png(cover, max_bytes=png_max_bytes, max_pixels=artwork_max_pixels)
    return document, cover


def _decode_child(
    archive: zipfile.ZipFile,
    *,
    expected_video_id: str | None,
    json_max_bytes: int,
    png_max_bytes: int,
    artwork_max_pixels: int,
    return_cover: bool = False,
    recording_input: dict[str, Any] | None = None,
) -> dict[str, Any] | tuple[dict[str, Any], bytes]:
    infos = archive.infolist()
    names = [info.filename for info in infos]
    folded_names = [name.casefold() for name in names]
    if len(folded_names) != len(set(folded_names)):
        raise PackageValidationError("Child ZIP contains case-insensitive duplicate names")
    if set(names) != {"song.json", "cover.png"}:
        raise PackageValidationError(
            "A child ZIP must contain exactly root song.json and paired cover.png"
        )
    for info in infos:
        if "/" in info.filename or "\\" in info.filename:
            raise PackageValidationError("Child ZIP entries must be root regular files")
        _require_regular_member(info, f"Child ZIP entry {info.filename!r}")
        if info.flag_bits & 0x1:
            raise PackageValidationError("Encrypted ZIP entries are not supported")
    song_info = archive.getinfo("song.json")
    if song_info.file_size > json_max_bytes:
        raise PackageValidationError("song.json exceeds the configured limit")
    raw_json = archive.read(song_info)
    cover_info = archive.getinfo("cover.png")
    if cover_info.file_size > png_max_bytes:
        raise PackageArtworkError("cover.png exceeds the configured limit")
    cover = archive.read(cover_info)
    document, cover = validate_child_payloads(raw_json, cover, expected_video_id=expected_video_id,
        json_max_bytes=json_max_bytes, png_max_bytes=png_max_bytes,
        artwork_max_pixels=artwork_max_pixels, recording_input=recording_input)
    return (document, cover) if return_cover else document


def validate_child_zip(
    path: Path,
    config: dict[str, Any],
    *,
    expected_video_id: str | None = None,
) -> dict[str, Any]:
    child_max_bytes = _configured_limit(config, "childZipMaxBytes", MAX_SINGLE_ZIP_BYTES)
    json_max_bytes = _configured_limit(config, "jsonMaxBytes", MAX_JSON_BYTES)
    png_max_bytes = _configured_limit(config, "pngMaxBytes", MAX_COVER_BYTES)
    artwork_max_pixels = _configured_limit(
        config, "artworkMaxPixels", MAX_COVER_PIXELS_PER_SIDE
    )
    if _require_regular_path(path, "Child ZIP").st_size > child_max_bytes:
        raise PackageValidationError("Child ZIP exceeds the configured limit")
    try:
        with zipfile.ZipFile(path, "r") as archive:
            return _decode_child(
                archive,
                expected_video_id=expected_video_id,
                json_max_bytes=json_max_bytes,
                png_max_bytes=png_max_bytes,
                artwork_max_pixels=artwork_max_pixels,
                recording_input=config.get("recordingInput"),
            )
    except zipfile.BadZipFile as error:
        raise PackageValidationError("Child is not a valid ZIP") from error


def create_child_zip(
    destination: Path,
    document: dict[str, Any],
    cover_path: Path | None,
    config: dict[str, Any],
) -> None:
    json_max_bytes = _configured_limit(config, "jsonMaxBytes", MAX_JSON_BYTES)
    png_max_bytes = _configured_limit(config, "pngMaxBytes", MAX_COVER_BYTES)
    artwork_max_pixels = _configured_limit(
        config, "artworkMaxPixels", MAX_COVER_PIXELS_PER_SIDE
    )
    child_max_bytes = _configured_limit(config, "childZipMaxBytes", MAX_SINGLE_ZIP_BYTES)
    video_id = document.get("source", {}).get("videoId")
    validate_document(document, video_id, recording_input=config.get("recordingInput"))
    payload = json_bytes(document, pretty=False)
    if len(payload) > json_max_bytes:
        raise PackageValidationError("song.json exceeds the configured limit")
    has_artwork = "artwork" in document
    if not has_artwork or cover_path is None:
        raise PackageValidationError(
            "Every finalized child ZIP requires artwork and a paired cover_path"
        )
    try:
        cover = read_bounded_regular_bytes(cover_path, max_bytes=png_max_bytes)
    except (OSError, ValueError) as error:
        raise PackageValidationError(str(error)) from error
    _verify_png(
        cover,
        max_bytes=png_max_bytes,
        max_pixels=artwork_max_pixels,
    )
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{destination.name}.", dir=destination.parent)
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        with zipfile.ZipFile(
            temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9, allowZip64=False
        ) as archive:
            archive.writestr(
                _canonical_zip_info("song.json", zipfile.ZIP_DEFLATED),
                payload,
                compresslevel=9,
            )
            archive.writestr(
                _canonical_zip_info("cover.png", zipfile.ZIP_DEFLATED),
                cover,
                compresslevel=9,
            )
        if temporary.stat().st_size > child_max_bytes:
            raise PackageValidationError("Child ZIP exceeds the configured limit")
        with zipfile.ZipFile(temporary) as archive:
            if (archive.namelist() != ['song.json', 'cover.png']
                    or archive.read('song.json') != payload or archive.read('cover.png') != cover):
                raise PackageValidationError('Staged child bytes differ from validated payloads')
        expected_digest = hashlib.sha256(temporary.read_bytes()).digest()
        durable_replace(temporary, destination)
        if hashlib.sha256(destination.read_bytes()).digest() != expected_digest:
            raise PackageValidationError('Published child bytes differ from validated staged bytes')
    finally:
        temporary.unlink(missing_ok=True)


def read_child(path: Path, config: dict[str, Any]) -> tuple[dict[str, Any], bytes | None]:
    child_max_bytes = _configured_limit(config, "childZipMaxBytes", MAX_SINGLE_ZIP_BYTES)
    if _require_regular_path(path, "Child ZIP").st_size > child_max_bytes:
        raise PackageValidationError("Child ZIP is missing or exceeds the configured limit")
    try:
        with zipfile.ZipFile(path, "r") as archive:
            result = _decode_child(
                archive,
                expected_video_id=None,
                json_max_bytes=_configured_limit(config, "jsonMaxBytes", MAX_JSON_BYTES),
                png_max_bytes=_configured_limit(config, "pngMaxBytes", MAX_COVER_BYTES),
                artwork_max_pixels=_configured_limit(
                    config, "artworkMaxPixels", MAX_COVER_PIXELS_PER_SIDE
                ),
                return_cover=True,
                recording_input=config.get("recordingInput"),
            )
    except zipfile.BadZipFile as error:
        raise PackageValidationError("Child is not a valid ZIP") from error
    assert isinstance(result, tuple)
    return result

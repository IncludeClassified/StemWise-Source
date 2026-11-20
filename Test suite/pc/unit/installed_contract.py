"""Test reference for StemWise's installed enrichment v2 document.

The PC transports ``stemwise.song-enrichment`` v2 plus optional cover bytes.
Android binds that payload to the exact selected-folder original and installs this
derived document. These helpers keep the version, projection and digest rules
independently checkable without giving the PC permission to write phone storage.
"""

from __future__ import annotations

import copy
import hashlib
import json
import math
import re
from decimal import Decimal
from datetime import datetime
from typing import Any, Mapping
from urllib.parse import urlsplit

from stemwise_engine.song_enrichment_v2 import GENERATOR_NAME, GENERATOR_VERSION, validate_song, validate_source, validate_generated_groups
from stemwise_engine.common import parse_strict_json_bytes


from stemwise_engine.enrichment_constants import (
    INSTALLED_SCHEMA_VERSION as SCHEMA_VERSION, INSTALLED_KIND as KIND,
    INSTALLED_CONTRACT_VERSION as CONTRACT_VERSION, CANONICAL_UTC_TIMESTAMP_PATTERN,
)
SHA256_RE = re.compile(r"^[a-f0-9]{64}$")
VIDEO_ID_RE = re.compile(r"^[A-Za-z0-9_-]{11}$")
CANONICAL_INSTANT_RE = re.compile(CANONICAL_UTC_TIMESTAMP_PATTERN)


def is_canonical_utc_timestamp(value: Any) -> bool:
    if not isinstance(value, str) or not CANONICAL_INSTANT_RE.fullmatch(value):
        return False
    try:
        # Python 3.10 accepts at most six fractional digits in fromisoformat even though the
        # installed-v2 contract deliberately permits nanoseconds. The lexical gate has already
        # proven exact UTC Z and 0/3/6/9 fractional digits, so validate the real calendar/time
        # portion without normalizing or truncating the source string used by the digest.
        datetime.strptime(value[:19], "%Y-%m-%dT%H:%M:%S")
    except ValueError:
        return False
    return True
MEDIA_PATHS = (
    "original.opus",
    "original.flac",
)


def parse_installed_json(data: bytes) -> Mapping[str, Any]:
    """Parse installed-v2 bytes without BOMs, duplicate keys, constants or trailing data."""
    if not data or data.startswith(b"\xef\xbb\xbf"):
        raise ValueError("installed JSON must be nonempty BOM-free UTF-8")
    value = parse_strict_json_bytes(data)
    if not isinstance(value, dict):
        raise ValueError("installed JSON root must be an object")
    return value


def _number(value: int | float) -> str:
    if type(value) is int and abs(value) > 9007199254740991:
        raise ValueError('installed JSON integers must be within the exact binary64 range')
    if isinstance(value, bool) or not math.isfinite(float(value)):
        raise ValueError("installed JSON numbers must be finite")
    if value == 0:
        return "0"
    decimal = Decimal(repr(value))
    magnitude = abs(float(value))
    if 1e-6 <= magnitude < 1e21:
        fixed = format(decimal, "f")
        if "." in fixed:
            fixed = fixed.rstrip("0").rstrip(".")
        return fixed
    normalized = format(decimal.normalize(), "e")
    coefficient, exponent = normalized.split("e")
    coefficient = coefficient.rstrip("0").rstrip(".")
    exponent_value = int(exponent)
    sign = "+" if exponent_value >= 0 else ""
    return f"{coefficient}e{sign}{exponent_value}"


def _canonical(value: Any) -> str:
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if type(value) in (int, float):
        return _number(value)
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if isinstance(value, list):
        return "[" + ",".join(_canonical(item) for item in value) + "]"
    if isinstance(value, dict):
        keys = sorted(value, key=lambda item: item.encode("utf-16-be"))
        return "{" + ",".join(
            _canonical(key) + ":" + _canonical(value[key]) for key in keys
        ) + "}"
    raise TypeError(f"unsupported installed JSON value {type(value).__name__}")


def installed_digest(document: Mapping[str, Any]) -> str:
    projection = copy.deepcopy(dict(document))
    install = projection.get("install")
    if not isinstance(install, dict):
        raise ValueError("installed document has no install object")
    install.pop("generatedDigest", None)
    return hashlib.sha256(_canonical(projection).encode("utf-8")).hexdigest()


def build_installed_document(
    payload: Mapping[str, Any],
    *,
    original_sha256: str,
    media: Mapping[str, Mapping[str, Any]],
    content_revision: int,
    payload_sha256: str,
    artwork_sha256: str,
    artwork_bytes: int,
) -> dict[str, Any]:
    validate_song(payload)
    if not SHA256_RE.fullmatch(original_sha256) or not SHA256_RE.fullmatch(payload_sha256):
        raise ValueError("installed hashes must be lowercase SHA-256")
    if type(content_revision) is not int or content_revision < 1:
        raise ValueError("content_revision must be a positive integer")
    recording = payload["source"].get("recording")
    if not isinstance(media, Mapping) or len(media) != 1:
        raise ValueError("installed media must contain exactly one canonical Original")
    media_paths = tuple(media)
    if media_paths[0] not in MEDIA_PATHS or recording and media_paths[0] != "original.flac":
        raise ValueError("installed Original codec does not match the source binding")
    media_files: list[dict[str, Any]] = []
    for path in media_paths:
        identity = media[path]
        sha256 = identity.get("sha256")
        byte_length = identity.get("byteLength")
        if not SHA256_RE.fullmatch(str(sha256)) or type(byte_length) is not int or byte_length < 1:
            raise ValueError("installed media identity is invalid")
        media_files.append({"path": path, "byteLength": byte_length, "sha256": sha256})
    if media_files[0]["sha256"] != original_sha256:
        raise ValueError("original media hash does not match the install identity")
    if recording and (recording["sha256"] != original_sha256 or recording["byteCount"] != media_files[0]["byteLength"]):
        raise ValueError("native recording does not match the installed media")
    output: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "kind": KIND,
        "source": copy.deepcopy(payload["source"]),
        "generatedAt": payload["generatedAt"],
        "generator": copy.deepcopy(payload["generator"]),
        "media": {"files": media_files},
    }
    for group in ("metadata", "lyrics", "analysis"):
        if group in payload:
            output[group] = copy.deepcopy(payload[group])
    if "artwork" not in payload:
        raise ValueError("installed enrichment requires paired selected-root artwork")
    if not SHA256_RE.fullmatch(artwork_sha256) or artwork_bytes < 1:
        raise ValueError("artwork requires its selected-root PNG identity")
    artwork = payload["artwork"]
    output["artwork"] = {
        "delivery": "selected-root",
        "file": "cover.png",
        "mimeType": "image/png",
        "byteLength": artwork_bytes,
        "sha256": artwork_sha256,
        "kind": artwork["kind"],
        "source": copy.deepcopy(artwork["source"]),
    }
    output["install"] = {
        "contractVersion": CONTRACT_VERSION,
        "originalSha256": original_sha256,
        "contentRevision": content_revision,
        "payloadSha256": payload_sha256,
    }
    output["install"]["generatedDigest"] = installed_digest(output)
    validate_installed_document(
        output,
        expected_video_id=payload["source"]["videoId"],
        expected_original_sha256=original_sha256,
    )
    return output


def validate_installed_document(
    document: Mapping[str, Any],
    *,
    expected_video_id: str,
    expected_original_sha256: str,
) -> Mapping[str, Any]:
    if not isinstance(document, Mapping):
        raise ValueError("installed document root must be an object")
    if not VIDEO_ID_RE.fullmatch(expected_video_id):
        raise ValueError("expected_video_id must be an exact YouTube video ID")
    if not SHA256_RE.fullmatch(expected_original_sha256):
        raise ValueError("expected_original_sha256 must be lowercase SHA-256")
    allowed = {
        "schemaVersion", "kind", "source", "generatedAt", "generator", "media", "install",
        "metadata", "artwork", "lyrics", "analysis",
    }
    if set(document) - allowed:
        raise ValueError("installed document contains unknown root properties")
    if document.get("schemaVersion") != SCHEMA_VERSION or document.get("kind") != KIND:
        raise ValueError("unsupported installed enrichment contract")
    if type(document.get("schemaVersion")) is not int or not isinstance(document.get("kind"), str):
        raise ValueError("installed contract discriminators have invalid types")
    source = document.get("source")
    if validate_source(source) != expected_video_id:
        raise ValueError("installed source does not match the case-sensitive directory identity")
    recording = source.get("recording")
    generated_at = document.get("generatedAt")
    if not is_canonical_utc_timestamp(generated_at):
        raise ValueError("installed generatedAt must be a canonical real UTC instant")
    generator = document.get("generator")
    if (
        not isinstance(generator, Mapping)
        or set(generator) != {"name", "version"}
        or generator.get("name") != GENERATOR_NAME
        or generator.get("version") != GENERATOR_VERSION
    ):
        raise ValueError("installed generator identity is unsupported")
    for group in ("metadata", "lyrics", "analysis"):
        if group in document and not isinstance(document[group], Mapping):
            raise ValueError(f"installed {group} must be an object")
    validate_generated_groups(document)
    media = document.get("media")
    if not isinstance(media, Mapping) or set(media) != {"files"}:
        raise ValueError("installed document has no closed media identity")
    files = media.get("files")
    if not isinstance(files, list) or len(files) != 1:
        raise ValueError("installed media must contain exactly one canonical Original")
    for identity in files:
        if (
            not isinstance(identity, Mapping)
            or set(identity) != {"path", "byteLength", "sha256"}
            or not isinstance(identity.get("path"), str)
            or identity["path"] not in MEDIA_PATHS
            or recording and identity["path"] != "original.flac"
            or type(identity.get("byteLength")) is not int
            or identity["byteLength"] < 1
            or not isinstance(identity.get("sha256"), str)
            or not SHA256_RE.fullmatch(identity["sha256"])
        ):
            raise ValueError("installed media identity is invalid")
    if recording and (recording["sha256"] != expected_original_sha256 or recording["byteCount"] != files[0]["byteLength"]):
        raise ValueError("native recording does not match the installed media")
    install = document.get("install")
    if not isinstance(install, Mapping):
        raise ValueError("installed document has no install object")
    if (
        set(install) != {
            "contractVersion", "originalSha256", "contentRevision",
            "payloadSha256", "generatedDigest",
        }
        or install.get("contractVersion") != CONTRACT_VERSION
        or not isinstance(install.get("contractVersion"), str)
        or install.get("originalSha256") != expected_original_sha256
        or not isinstance(install.get("originalSha256"), str)
        or files[0]["sha256"] != expected_original_sha256
        or type(install.get("contentRevision")) is not int
        or install["contentRevision"] < 1
        or not isinstance(install.get("payloadSha256"), str)
        or not SHA256_RE.fullmatch(install["payloadSha256"])
        or not isinstance(install.get("generatedDigest"), str)
        or not SHA256_RE.fullmatch(install["generatedDigest"])
    ):
        raise ValueError("installed enrichment identity is invalid")
    if install["generatedDigest"] != installed_digest(document):
        raise ValueError("installed enrichment digest is invalid")
    artwork = document.get("artwork")
    if (
        not isinstance(artwork, Mapping)
        or set(artwork) != {
            "delivery", "file", "mimeType", "byteLength", "sha256", "kind", "source",
        }
    ):
        raise ValueError("installed artwork must be one closed selected-root identity")
    if (
        artwork.get("delivery") != "selected-root"
        or not isinstance(artwork.get("delivery"), str)
        or artwork.get("file") != "cover.png"
        or not isinstance(artwork.get("file"), str)
        or artwork.get("mimeType") != "image/png"
        or not isinstance(artwork.get("mimeType"), str)
        or type(artwork.get("byteLength")) is not int
        or artwork["byteLength"] < 1
        or not isinstance(artwork.get("sha256"), str)
        or not SHA256_RE.fullmatch(artwork["sha256"])
        or not isinstance(artwork.get("kind"), str)
        or artwork["kind"] not in {
            "album-cover", "single-cover", "youtube-thumbnail", "other"
        }
        or not _valid_source_descriptor(artwork.get("source"))
    ):
        raise ValueError("installed artwork must use selected-root cover.png delivery")
    return document


def _valid_source_descriptor(value: Any) -> bool:
    if not isinstance(value, Mapping) or set(value) not in ({"name"}, {"name", "url"}):
        return False
    if not isinstance(value.get("name"), str) or not value["name"].strip():
        return False
    if "url" not in value:
        return True
    if not isinstance(value["url"], str) or not value["url"].strip():
        return False
    parsed = urlsplit(value["url"])
    return parsed.scheme == "https" and bool(parsed.hostname) and parsed.username is None


__all__ = [
    "CONTRACT_VERSION",
    "KIND",
    "MEDIA_PATHS",
    "SCHEMA_VERSION",
    "build_installed_document",
    "installed_digest",
    "parse_installed_json",
    "validate_installed_document",
]

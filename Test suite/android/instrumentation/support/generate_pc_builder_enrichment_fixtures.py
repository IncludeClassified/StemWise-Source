"""Generate Android instrumentation assets through the real StemWise PC packager.

The native song passes through the PC metadata packager and Original delivery wrapper.
All raw documents still contribute to schema vocabulary coverage; partial documents are
contract fixtures only and are never emitted as Android intake artifacts.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import shutil
import sys
from pathlib import Path
from typing import Any, Iterable


SCHEMA_FIXTURES = (
    "full-song-enrichment-v2.json",
    "native-flac-song-enrichment-v2.json",
    "partial-plain-lyrics-update-v2.json",
    "unavailable-lyrics-update-v2.json",
    "metadata-only-update-v2.json",
)
NATIVE_FIXTURE = "native-flac-song-enrichment-v2.json"
NATIVE_ARCHIVE = "native-flac-song-enrichment-v2.stemwise.zip"

# The external builder is an input, not an output location for this test bridge.
sys.dont_write_bytecode = True


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def strict_object(pairs: Iterable[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON property {key!r}")
        result[key] = value
    return result


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(
        path.read_text(encoding="utf-8"),
        object_pairs_hook=strict_object,
        parse_constant=lambda value: (_ for _ in ()).throw(
            ValueError(f"non-finite JSON number {value}")
        ),
    )
    if not isinstance(value, dict):
        raise ValueError(f"{path.name} must contain a JSON object")
    return value


def schema_property_names(value: Any) -> set[str]:
    result: set[str] = set()
    if isinstance(value, dict):
        properties = value.get("properties")
        if isinstance(properties, dict):
            result.update(properties)
        for child in value.values():
            result.update(schema_property_names(child))
    elif isinstance(value, list):
        for child in value:
            result.update(schema_property_names(child))
    return result


def document_property_names(value: Any) -> set[str]:
    result: set[str] = set()
    if isinstance(value, dict):
        result.update(value)
        for child in value.values():
            result.update(document_property_names(child))
    elif isinstance(value, list):
        for child in value:
            result.update(document_property_names(child))
    return result


def require_owned_generated_output(output: Path) -> None:
    """Fail closed before the script performs its one recursive cleanup."""
    expected_suffix = (
        "generated",
        "androidtestassets",
        "pcbuilderenrichment",
        "qa",
        "pc-builder-enrichment",
    )
    actual_suffix = tuple(part.lower() for part in output.parts[-len(expected_suffix) :])
    if actual_suffix != expected_suffix or output == Path(output.anchor):
        raise ValueError(
            "Refusing to clean an output outside the owned generated Android-test asset path: "
            f"{output}"
        )


def require_builder_modules(builder_src: Path) -> dict[str, Path]:
    import importlib
    import stemwise_engine
    if {Path(path).resolve() for path in stemwise_engine.__path__} != {builder_src.resolve()}:
        raise RuntimeError('PC fixture builder/import mismatch: package search path')
    names = ('packages', 'contract', 'common', 'song_enrichment_v2', 'original_delivery',
             'lyrics_tokenization', 'lyrics_unicode', 'model_registry')
    paths = {name: Path(importlib.import_module('stemwise_engine.'+name).__file__).resolve()
             for name in names}
    for name, actual in paths.items():
        if actual != (builder_src/(name+'.py')).resolve():
            raise RuntimeError('PC fixture builder/import mismatch: '+name)
    for name, module in tuple(sys.modules.items()):
        if name.startswith('stemwise_engine.') and getattr(module, '__file__', None):
            if not Path(module.__file__).resolve().is_relative_to(builder_src.resolve()):
                raise RuntimeError('PC fixture builder/import mismatch: '+name)
    return paths


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--builder", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--regional-fixture", type=Path, required=True)
    parser.add_argument("--cover-base64", type=Path, required=True)
    parser.add_argument("--schema", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    builder = args.builder.resolve()
    builder_src = builder / "src"
    packages_path = builder_src / "packages.py"
    contract_path = builder_src / "contract.py"
    common_path = builder_src / "common.py"
    validator_path = builder_src / "song_enrichment_v2.py"
    delivery_path = builder_src / "original_delivery.py"
    tokenization_path = builder_src / "lyrics_tokenization.py"
    unicode_path = builder_src / "lyrics_unicode.py"
    registry_path = builder_src / "model-route-registry.json"
    model_registry_path = builder_src / "model_registry.py"
    for required in (packages_path, contract_path, common_path, validator_path, delivery_path, tokenization_path, unicode_path, registry_path, model_registry_path):
        if not required.is_file():
            raise FileNotFoundError(f"StemWise PC builder dependency is missing: {required}")

    # Importing this exact directory is the compatibility boundary under test.
    actual = require_builder_modules(builder_src)
    packages_path, contract_path, common_path, validator_path, delivery_path, tokenization_path, unicode_path, model_registry_path = (
        actual[name] for name in ('packages', 'contract', 'common', 'song_enrichment_v2',
                                 'original_delivery', 'lyrics_tokenization', 'lyrics_unicode', 'model_registry'))
    from stemwise_engine.packages import create_child_zip  # pylint: disable=import-outside-toplevel
    from stemwise_engine.original_delivery import create_bundle, read_bundle  # pylint: disable=import-outside-toplevel

    import stemwise_engine.contract as contract
    import PIL
    from stemwise_engine.model_registry import runtime_profile
    if sys.version_info[:2] != (3, 13) or PIL.__version__ != runtime_profile('main')['packages']['Pillow']:
        raise RuntimeError("Android compatibility fixtures require Python 3.13 and Pillow 12.3.0")
    config = {
        "jsonMaxBytes": contract.MAX_JSON_BYTES,
        "pngMaxBytes": contract.MAX_COVER_BYTES,
        "artworkMaxPixels": contract.MAX_COVER_PIXELS_PER_SIDE,
        "childZipMaxBytes": contract.MAX_SINGLE_ZIP_BYTES,
    }
    documents: dict[str, dict[str, Any]] = {}
    for source_name in SCHEMA_FIXTURES:
        documents[source_name] = read_json(args.fixtures.resolve() / source_name)
    documents[args.regional_fixture.name] = read_json(args.regional_fixture)

    schema = read_json(args.schema.resolve())
    schema_fields = schema_property_names(schema)
    fixture_fields: set[str] = set()
    for document in documents.values():
        fixture_fields.update(document_property_names(document))
    if fixture_fields != schema_fields:
        missing = sorted(schema_fields - fixture_fields)
        unknown = sorted(fixture_fields - schema_fields)
        raise ValueError(
            "PC-to-Android fixtures do not exercise the exact schema property vocabulary; "
            f"missing={missing}, unknown={unknown}"
        )

    output = args.output.resolve()
    require_owned_generated_output(output)
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    cover = output / "cover.png"
    cover.write_bytes(base64.b64decode(args.cover_base64.read_text(encoding="ascii")))

    document = documents[NATIVE_FIXTURE]
    artifact_config = {**config, "recordingInput": document["source"]["recording"]}
    metadata = output / "metadata.zip"
    destination = output / NATIVE_ARCHIVE
    original = Path(__file__).resolve().parents[1] / "assets/encoder/flac24-stereo-96000.flac"
    identity = {"name": "original.flac", "sha256": sha256_bytes(original.read_bytes()),
                "byteCount": original.stat().st_size}
    create_child_zip(metadata, document, cover, artifact_config)
    create_bundle(destination, metadata, original, identity, artifact_config, output)
    reopened, _, reopened_original = read_bundle(destination, artifact_config, metadata=metadata, original=identity)
    if reopened != document or reopened_original != identity:
        raise AssertionError("PC delivery changed the complete native song during round trip")
    metadata.unlink()
    artifact_records = [{
        "artifact": NATIVE_ARCHIVE,
        "artifactSha256": sha256_bytes(destination.read_bytes()),
        "delivery": "complete-song-zip",
        "originalSha256": identity["sha256"],
        "input": NATIVE_FIXTURE,
        "inputSha256": sha256_bytes((args.fixtures.resolve() / NATIVE_FIXTURE).read_bytes()),
        "generatedAt": document["generatedAt"],
        "videoId": document["source"]["videoId"],
    }]

    cover.unlink()
    provenance = {
        "schemaSha256": sha256_bytes(args.schema.resolve().read_bytes()),
        "schemaPropertyCount": len(schema_fields),
        "schemaProperties": sorted(schema_fields),
        "pcBuilder": {
            "configSha256": sha256_bytes(json.dumps(config, sort_keys=True, separators=(",", ":")).encode()),
            "validatorPySha256": sha256_bytes(validator_path.read_bytes()),
            "tokenizationPySha256": sha256_bytes(tokenization_path.read_bytes()),
            "lyricsUnicodePySha256": sha256_bytes(unicode_path.read_bytes()),
            "modelRouteRegistrySha256": sha256_bytes(registry_path.read_bytes()),
            "modelRegistryPySha256": sha256_bytes(model_registry_path.read_bytes()),
            "pythonVersion": ".".join(map(str, sys.version_info[:3])),
            "pillowVersion": PIL.__version__,
            "commonPySha256": sha256_bytes(common_path.read_bytes()),
            "contractPySha256": sha256_bytes(contract_path.read_bytes()),
            "packagesPySha256": sha256_bytes(packages_path.read_bytes()),
            "originalDeliveryPySha256": sha256_bytes(delivery_path.read_bytes()),
        },
        "artifacts": artifact_records,
    }
    (output / "pc-builder-provenance.json").write_text(
        json.dumps(provenance, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

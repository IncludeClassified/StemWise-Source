"""Focused generation checks; runtime known answers remain in NativePacketGoldenTest."""
import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("native_packet_schema", ROOT / "StemWise/tools/native_packet_schema.py")
SCHEMA = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCHEMA)


class NativePacketMappingGenerationTest(unittest.TestCase):
    def fixture(self):
        mapping = {
            "name": "counters", "file": "Codec.kt", "constructor": "NativeRuntimeCounters",
            "packet": "RUNTIME_DIAGNOSTICS", "read": "counter(@)", "indent": "    ",
            "fields": [("commandSnapshotsPublished", "COMMAND_SNAPSHOTS_PUBLISHED"),
                       ("lastRenderPayloadHash", "LAST_RENDER_PAYLOAD_HASH", "values[@]")],
        }
        source = ("val counters = NativeRuntimeCounters(\n"
                  "    // BEGIN GENERATED counters\nold\n    // END GENERATED counters\n)\n")
        values = {"RUNTIME_DIAGNOSTICS_COMMAND_SNAPSHOTS_PUBLISHED": 2,
                  "RUNTIME_DIAGNOSTICS_LAST_RENDER_PAYLOAD_HASH": 26}
        return mapping, source, values

    def render(self, mapping, source, values):
        with patch.object(SCHEMA, "KOTLIN_DIAGNOSTIC_MAPPINGS", [mapping]), patch.object(Path, "read_text", return_value=source):
            return next(iter(SCHEMA.kotlin_diagnostic_mapping_projections(values).values()))

    def test_counter_validation_and_raw_hash_reads_remain_distinct(self):
        mapping, source, values = self.fixture()
        actual = self.render(mapping, source, values)
        self.assertIn("commandSnapshotsPublished = counter(NativePacketSchema.RUNTIME_DIAGNOSTICS_COMMAND_SNAPSHOTS_PUBLISHED)", actual)
        self.assertIn("lastRenderPayloadHash = values[NativePacketSchema.RUNTIME_DIAGNOSTICS_LAST_RENDER_PAYLOAD_HASH]", actual)
        self.assertNotIn("old", actual)

    def test_missing_boundary_owner_unknown_field_and_duplicate_property_fail(self):
        mapping, source, values = self.fixture()
        for broken in [source.replace("// BEGIN GENERATED counters", "missing"),
                       source.replace("// END GENERATED counters", "missing"),
                       source.replace("NativeRuntimeCounters(", "Missing(")]:
            with self.subTest(source=broken), self.assertRaises(ValueError):
                self.render(mapping, broken, values)
        for fields in [[("invalid", "MISSING")], [mapping["fields"][0], mapping["fields"][0]]]:
            broken = copy.deepcopy(mapping)
            broken["fields"] = fields
            with self.subTest(fields=fields), self.assertRaises(ValueError):
                self.render(broken, source, values)

    def test_committed_projections_are_current(self):
        for path, expected in SCHEMA.projections().items():
            with self.subTest(path=str(path)):
                self.assertEqual(expected, path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()

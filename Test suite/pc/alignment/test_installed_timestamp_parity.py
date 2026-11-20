from __future__ import annotations

import copy
import json
import sys
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[3]
PROJECT = ROOT / "PC side" / "stemwise-playlist-builder"
SRC = PROJECT / "src"

sys.path.insert(0, str(ROOT / "Test suite/pc/unit"))
import installed_contract
import stemwise_engine.song_enrichment_v2 as external_contract
from stemwise_engine.contract import ContractError


SCHEMA_DIRECTORY = ROOT / "Test suite" / "android" / "fixtures" / "schemas"
TIMESTAMP_CORPUS = SCHEMA_DIRECTORY / "installed-enrichment-v2-timestamp-corpus.json"
KNOWN_GOOD_FIXTURE = (
    ROOT
    / "Test suite"
    / "fixtures"
    / "packages"
    / "self-check-v2"
    / "known-good-full-song-enrichment-v2.json"
)


class InstalledTimestampAlignmentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.corpus = json.loads(TIMESTAMP_CORPUS.read_text(encoding="utf-8"))
        cls.installed_schema = json.loads(
            (ROOT / "StemWise/contracts/enrichment/installed-enrichment-v2.schema.json").read_text(
                encoding="utf-8"
            )
        )
        cls.external_schema = json.loads(
            (ROOT / "StemWise/contracts/enrichment/song-enrichment-v2.schema.json").read_text(
                encoding="utf-8"
            )
        )
        cls.fixture = json.loads(KNOWN_GOOD_FIXTURE.read_text(encoding="utf-8"))
        cls.external_validator = Draft202012Validator(
            cls.external_schema,
            format_checker=FormatChecker(),
        )

    def assert_external_schema_accepts(self, document: object) -> None:
        errors = list(self.external_validator.iter_errors(document))
        self.assertEqual([], errors, [error.message for error in errors])

    def assert_external_schema_rejects(self, document: object) -> None:
        self.assertTrue(list(self.external_validator.iter_errors(document)))

    def test_shared_corpus_matches_installed_and_external_contracts(self) -> None:
        installed_generated_at = self.installed_schema["properties"]["generatedAt"]
        external_generated_at = self.external_schema["properties"]["generatedAt"]
        self.assertEqual(installed_generated_at, external_generated_at)
        installed_schema_validator = Draft202012Validator(
            installed_generated_at,
            format_checker=FormatChecker(),
        )

        for value in self.corpus["positive"]:
            self.assertTrue(installed_contract.is_canonical_utc_timestamp(value), value)
            self.assertFalse(list(installed_schema_validator.iter_errors(value)), value)
            document = copy.deepcopy(self.fixture)
            document["generatedAt"] = value
            self.assertIs(external_contract.validate_song(document), document)
            self.assert_external_schema_accepts(document)

        for case in self.corpus["negative"]:
            value = case["value"]
            self.assertFalse(
                installed_contract.is_canonical_utc_timestamp(value), case["name"]
            )
            self.assertTrue(
                list(installed_schema_validator.iter_errors(value)), case["name"]
            )
            document = copy.deepcopy(self.fixture)
            document["generatedAt"] = value
            with self.assertRaises(ContractError, msg=case["name"]) as caught:
                external_contract.validate_song(document)
            self.assertEqual("$.generatedAt", caught.exception.path)
            self.assert_external_schema_rejects(document)

    def test_known_good_full_fixture_and_exact_artist_controls_remain_valid(self) -> None:
        document = copy.deepcopy(self.fixture)
        self.assertIs(external_contract.validate_song(document), document)
        self.assert_external_schema_accepts(document)

        case_distinct = copy.deepcopy(self.fixture)
        case_distinct["metadata"]["artists"] = ["Exact Artist", "exact artist"]
        self.assertIs(external_contract.validate_song(case_distinct), case_distinct)
        self.assert_external_schema_accepts(case_distinct)
        self.assertEqual(
            ["Exact Artist", "exact artist"], case_distinct["metadata"]["artists"]
        )

        duplicate = copy.deepcopy(self.fixture)
        artists = duplicate["metadata"]["artists"]
        artists.append(artists[0])
        duplicate_index = len(artists) - 1
        with self.assertRaises(ContractError) as caught:
            external_contract.validate_song(duplicate)
        self.assertEqual(f"$.metadata.artists[{duplicate_index}]", caught.exception.path)
        self.assert_external_schema_rejects(duplicate)

    def test_component_languages_are_original_only_across_runtime_and_schema(self) -> None:
        valid = copy.deepcopy(self.fixture)
        self.assertEqual(
            ["en", "tr"], valid["lyrics"]["tracks"][0]["componentLanguages"]
        )
        self.assertIs(external_contract.validate_song(valid), valid)
        self.assert_external_schema_accepts(valid)

        for kind in ("translation", "transliteration"):
            invalid = copy.deepcopy(self.fixture)
            index = next(
                index
                for index, track in enumerate(invalid["lyrics"]["tracks"])
                if track["kind"] == kind
            )
            invalid["lyrics"]["tracks"][index]["componentLanguages"] = ["en", "tr"]
            with self.assertRaises(ContractError) as caught:
                external_contract.validate_song(invalid)
            self.assertEqual(
                f"$.lyrics.tracks[{index}].componentLanguages", caught.exception.path
            )
            self.assert_external_schema_rejects(invalid)


if __name__ == "__main__":
    unittest.main()

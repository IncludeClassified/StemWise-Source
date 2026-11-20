from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
import copy
import io
import zipfile
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"

from stemwise_engine.lyrics_qualification import (
    producer_system_identity,
    qualification_profile_identity,
    run_qualification,
)


def _digest(label: str) -> str:
    return hashlib.sha256(label.encode()).hexdigest()


def _write(path: Path, value: object) -> str:
    raw = (json.dumps(value, ensure_ascii=True, sort_keys=True, allow_nan=False) + "\n").encode()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(raw)
    return hashlib.sha256(raw).hexdigest()


def _object_digest(value: object) -> str:
    raw = json.dumps(
        value,
        ensure_ascii=True,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode()
    return hashlib.sha256(raw).hexdigest()


def _source_digest(entries: list[dict[str, object]]) -> str:
    digest = hashlib.sha256()
    for entry in entries:
        digest.update(
            f"{entry['path']}\0{entry['bytes']}\0{entry['sha256']}\0".encode()
        )
    return digest.hexdigest()


class LyricsMeasurementTests(unittest.TestCase):
    def test_actual_bundle_readback_separates_playable_and_supported_lyrics(self):
        from stemwise_engine.common import run_process_tree, sha256_file
        from stemwise_engine.original_delivery import create_bundle, prepare_original
        from stemwise_engine.packages import create_child_zip
        from test_packages import song_document, package_config, PNG_1X1
        from stemwise_engine.lyrics_qualification import _measurement_export, joint_word_metrics
        from stemwise_engine.lyrics_tokenization import TOKENIZATION_VERSION
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            audio = root / 'original.flac'
            run_process_tree(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i',
                'sine=frequency=440:duration=1', '-c:a', 'flac', str(audio)],
                capture_output=True, check=True, timeout=15)
            cover = root / 'cover.png'
            cover.write_bytes(PNG_1X1)
            owned = {'wordIdSha256': _digest('word'), 'startUtf16': 0, 'endUtf16': 3}
            word = {**owned, 'startMs': 100, 'endMs': 900}
            line = {'occurrenceIdSha256': _digest('line'), 'startMs': 100, 'endMs': 900, 'words': [word]}
            required = {line['occurrenceIdSha256']: {'text': 'One', 'words': [owned]}}
            reference = {'recordingSha256': sha256_file(audio), 'lexicalAuthoritySha256': _digest('authority'),
                'durationMs': 1000, 'occurrences': [line]}
            result = {**reference}
            for mode in ('synced', 'plain', 'unavailable'):
                document = song_document('abcdefghijk')
                if mode != 'unavailable':
                    track = {'id': 'original', 'kind': 'original', 'language': 'en', 'format': mode,
                             'source': {'name': 'Synthetic test reference'}}
                    if mode == 'plain':
                        track['text'] = 'One'
                    else:
                        track['lines'] = [{'text': 'One', 'startMs': 100, 'endMs': 900,
                            'words': [{key: word[key] for key in ('startUtf16', 'endUtf16', 'startMs', 'endMs')}]}]
                    document['lyrics'] = {'status': 'partial', 'reason': 'Companions unavailable',
                        'tokenizationVersion': TOKENIZATION_VERSION, 'tracks': [track]}
                metadata, bundle = root / f'{mode}.metadata.zip', root / f'{mode}.zip'
                create_child_zip(metadata, document, cover, package_config(root))
                original, identity = prepare_original(audio, document, root)
                create_bundle(bundle, metadata, original, identity, package_config(root), root)
                descriptor = {'path': bundle.name, 'sha256': sha256_file(bundle)}
                exported, supported, estimated = _measurement_export(root, descriptor, reference, result, required)
                self.assertTrue(exported['valid'], exported)
                self.assertEqual([], estimated)
                scored = joint_word_metrics(reference, {**result, 'occurrences': supported}, required,
                                            complete_export=exported['valid'])
                self.assertEqual(int(mode == 'synced'), scored['verifiedWords'])
                wrong = {**reference, 'recordingSha256': _digest('different recording')}
                self.assertFalse(_measurement_export(root, descriptor, wrong, result, required)[0]['valid'])

    def test_regional_estimates_and_missing_words_never_enter_supported_metrics(self):
        from stemwise_engine.lyrics_qualification import _measurement_timed_projection, joint_word_metrics
        from stemwise_engine.lyrics_tokenization import display_word_ranges
        from test_regional_lyrics import FIXTURE
        import stemwise_engine.song_enrichment_v2 as song_enrichment_v2
        document = json.loads(FIXTURE.read_bytes())
        song_enrichment_v2.validate_song(document, installed_duration_ms=5000)
        track = document['lyrics']['tracks'][0]
        required, occurrences = {}, []
        for index, row in enumerate(track['lines']):
            identity = _digest(f'line:{index}')
            words = [{'wordIdSha256': _digest(f'word:{index}:{number}'), 'startUtf16': start,
                      'endUtf16': end} for number, (start, end) in enumerate(display_word_ranges(row['text']))]
            required[identity] = {'text': row['text'], 'words': words}
            timed = []
            for owned, public in zip(words, row.get('words', [])):
                timing = public['timing']
                if timing['status'] != 'none':
                    timed.append({**owned, 'startMs': timing['startMs'], 'endMs': timing['endMs']})
            if row['timing']['status'] != 'none':
                occurrences.append({'occurrenceIdSha256': identity, 'startMs': row['timing']['startMs'],
                    'endMs': row['timing']['endMs'], 'words': timed})
        reference = {'recordingSha256': _digest('audio'), 'lexicalAuthoritySha256': _digest('authority'),
                     'durationMs': 5000, 'occurrences': occurrences}
        result = copy.deepcopy(reference)
        supported, estimated = _measurement_timed_projection(track, reference, result, required)
        self.assertEqual(1, sum(len(line['words']) for line in supported))
        self.assertEqual(1, sum(len(line['words']) for line in estimated))
        self.assertEqual(2, len(supported))
        metrics = joint_word_metrics(reference, {**result, 'occurrences': supported}, required, complete_export=True)
        self.assertEqual(1, metrics['verifiedWords'])
        self.assertEqual(6, metrics['allReferenceWords'])

    def test_joint_coverage_counts_missing_and_line_only_words_in_denominator(self):
        from stemwise_engine.lyrics_qualification import joint_word_metrics
        reference = {'recordingSha256': _digest('audio'), 'lexicalAuthoritySha256': _digest('text'),
                     'durationMs': 11000, 'occurrences': []}
        required, published = {}, []
        for index in range(100):
            identity, word_id = _digest(f'line{index}'), _digest(f'word{index}')
            word = {'wordIdSha256': word_id, 'startUtf16': 0, 'endUtf16': 3,
                    'startMs': index * 100 + 10, 'endMs': index * 100 + 80}
            line = {'occurrenceIdSha256': identity, 'startMs': index * 100,
                    'endMs': index * 100 + 90, 'words': [word]}
            reference['occurrences'].append(line)
            required[identity] = {'words': [word]}
            published.append({**line, 'words': [word] if index < 10 else []})
        result = {**reference, 'occurrences': published}
        metrics = joint_word_metrics(reference, result, required, complete_export=True)
        self.assertEqual(metrics['verifiedWords'], 10)
        self.assertEqual(metrics['verifiedWordCoverage'], .1)
        self.assertEqual(metrics['correctSupportedLines'], 100)
        self.assertEqual(metrics['correctCompleteLines'], 10)
        result['occurrences'][0]['words'] = [{**published[0]['words'][0], 'endMs': 600}]
        self.assertEqual(joint_word_metrics(reference, result, required,
            complete_export=True)['verifiedWords'], 9)
        self.assertEqual(joint_word_metrics(reference, result, required,
            complete_export=True)['correctSupportedLines'], 100)
        self.assertEqual(joint_word_metrics(reference, result, required,
            complete_export=True)['correctCompleteLines'], 9)
        self.assertEqual(joint_word_metrics(reference, result, required,
            complete_export=False)['verifiedWordCoverage'], 0)

    def test_measurement_accepts_unbound_gates_without_qualifying_or_export_credit(self):
        from stemwise_engine.lyrics_qualification import run_measurement
        with tempfile.TemporaryDirectory() as name:
            fixture = QualificationFixture(Path(name))
            profile_path, corpus_path, results_path = fixture.build()
            profile = json.loads(profile_path.read_bytes())
            profile.update(status='unbound', qualificationClaimsAllowed=False,
                           producerSystem=dict.fromkeys(profile['producerSystem']))
            profile['strata']['minimumCaseCount'] = None
            for gates in ('minimum', 'maximum'):
                profile['gates'][gates] = dict.fromkeys(profile['gates'][gates])
            corpus = json.loads(corpus_path.read_bytes())
            for entry, reference in zip(corpus['cases'], fixture.references.values()):
                entry['requiredWords'] = [{
                    'occurrenceIdSha256': line['occurrenceIdSha256'], 'text': 'one',
                    'words': [{'wordIdSha256': line['words'][0]['wordIdSha256'] if line['words']
                               else _digest('unscorable:' + line['occurrenceIdSha256']),
                               'startUtf16': 0, 'endUtf16': 3}]
                } for line in reference['occurrences']]
            results = json.loads(results_path.read_bytes())
            results['corpusManifestSha256'] = _write(corpus_path, corpus)
            results['profileSha256'] = _write(profile_path, profile)
            _write(results_path, results)
            report = run_measurement(profile_path, corpus_path, results_path)
            self.assertFalse(report['qualified'])
            self.assertFalse(report['qualificationClaimsAllowed'])
            self.assertEqual(report['verifiedWordCoverage'], 0)
            self.assertEqual(report['cases'][0]['referenceMappingCoverage'], .5)
            with self.assertRaisesRegex(ValueError, 'unbound'):
                run_qualification(profile_path, corpus_path, results_path, pc_only=True)


class QualificationFixture:
    def __init__(self, root: Path, *, claims_allowed: bool = False, include_device: bool = False) -> None:
        self.root = root
        self.claims_allowed = claims_allowed
        self.include_device = include_device
        self.annotation = {"kind": "synthetic-annotation-protocol", "formatVersion": 2}
        self.device_profile = {
            "serial": "ANDROID-QUALIFICATION",
            "model": "TabletFixture",
            "device": "tablet",
            "socModel": "fixture-soc",
            "androidBuildFingerprint": "fixture/tablet:16/build/test-keys",
            "api": 36,
        }
        self.expected_app_build = {
            "applicationId": "app.stemwise.development", "versionCode": 56, "versionName": "3.7.21-development",
            "certificateSha256": "67cf677864a1bc66f940191e892cab64ca41a614a9c09b41278ffcd9b8149d28",
        }
        self.source_manifest = [
            {
                "path": "StemWise/app/src/main/fixture.kt",
                "bytes": 7,
                "sha256": _digest("source-fixture").upper(),
            }
        ]
        self.android_source_sha256 = _source_digest(self.source_manifest)
        deliverable = io.BytesIO()
        with zipfile.ZipFile(deliverable, "w") as apk:
            apk.writestr("AndroidManifest.xml", b"binary-manifest-fixture")
            apk.writestr("classes.dex", b"dex-fixture")
            apk.writestr("lib/arm64-v8a/libstemwise.so", b"native-fixture")
        self.deliverable_bytes = deliverable.getvalue()
        self.deliverable_sha256 = hashlib.sha256(self.deliverable_bytes).hexdigest()
        self.required_lanes = [{"lane": "lyrics-integration", "target": None}]
        self.host_qualification = {
            "unitTests": {
                "counts": {
                    "suites": 1,
                    "tests": 1,
                    "failures": 0,
                    "errors": 0,
                    "skipped": 0,
                    "methods": ["app.stemwise.LyricsQualificationTest#passes"],
                },
                "reportLocations": {
                    "junitXml": "host-qualification/unit-tests/results",
                },
            },
            "lint": {
                "counts": {
                    "issues": 0,
                    "fatal": 0,
                    "errors": 0,
                    "warnings": 0,
                    "hints": 0,
                    "informational": 0,
                },
                "reportLocations": {
                    "xml": "host-qualification/lint/results.xml",
                },
            },
        }
        self.producer_system = producer_system_identity()
        self.producer_system_sha256 = _object_digest(self.producer_system)
        self.references: dict[str, dict[str, object]] = {}
        self.results: dict[str, dict[str, object]] = {}
        for split in ("development", "held-out"):
            case_id = _digest(f"case:{split}")
            line_word = _digest(f"line-word:{split}")
            line_unsafe = _digest(f"line-unsafe:{split}")
            word = _digest(f"word:{split}")
            authority = _digest(f"authority:{split}")
            recording = _digest(f"recording:{split}")
            self.references[split] = {
                "kind": "stemwise.lyrics-qualification-reference",
                "formatVersion": 2,
                "caseIdSha256": case_id,
                "lexicalAuthoritySha256": authority,
                "recordingSha256": recording,
                "songVersionFamilySha256": _digest(f"version-family:{split}"),
                "singerSha256": _digest(f"singer:{split}"),
                "split": split,
                "durationMs": 5000,
                "stratum": {"language": "en", "script": "Latn", "difficulty": "synthetic"},
                "occurrences": [
                    {
                        "occurrenceIdSha256": line_word,
                        "eligibleForWordTiming": True,
                        "unsafeForWordPublication": False,
                        "startMs": 100,
                        "endMs": 1000,
                        "words": [
                            {
                                "wordIdSha256": word,
                                "startUtf16": 0,
                                "endUtf16": 3,
                                "startMs": 100,
                                "endMs": 400,
                            }
                        ],
                    },
                    {
                        "occurrenceIdSha256": line_unsafe,
                        "eligibleForWordTiming": False,
                        "unsafeForWordPublication": True,
                        "startMs": 1200,
                        "endMs": 2000,
                        "words": [],
                    },
                ],
            }
            self.results[split] = {
                "kind": "stemwise.lyrics-qualification-result",
                "formatVersion": 2,
                "caseIdSha256": case_id,
                "lexicalAuthoritySha256": authority,
                "recordingSha256": recording,
                "producerSystemSha256": self.producer_system_sha256,
                "outcome": "committed-mixed",
                "occurrences": [
                    {
                        "occurrenceIdSha256": line_word,
                        "startMs": 100,
                        "endMs": 1000,
                        "words": [
                            {
                                "wordIdSha256": word,
                                "startUtf16": 0,
                                "endUtf16": 3,
                                "startMs": 100,
                                "endMs": 400,
                            }
                        ],
                    },
                    {
                        "occurrenceIdSha256": line_unsafe,
                        "startMs": 1200,
                        "endMs": 2000,
                        "words": [],
                    },
                ],
                "validation": {
                    "displayProjectionChecked": 1,
                    "displayProjectionValid": 1,
                    "graphemeProjectionChecked": 1,
                    "graphemeProjectionValid": 1,
                },
                "diagnostics": {
                    "crossViewBoundarySpreadMs": [0],
                    "chunkVsFullBoundaryDeltaMs": [0],
                    "rerunBoundaryDeltaMs": [0],
                    "canonicalTimelineResidualMs": [0],
                    "fallbackRoutes": [{"routeIdSha256": _digest("route"), "count": 1}],
                },
                "resources": {
                    "runtimeMs": 10,
                    "peakMemoryBytes": 20,
                    "diskBytes": 30,
                    "retainedBytes": 0,
                },
            }
        self.profile = {
            "kind": "stemwise.lyrics-qualification-profile",
            "formatVersion": 2,
            "status": "frozen",
            "qualificationClaimsAllowed": claims_allowed,
            "metricVersion": "stemwise.lyrics-qualification-metrics.v1",
            "metricCodeSha256": hashlib.sha256(
                (PROJECT / "src/lyrics_qualification.py").read_bytes()
            ).hexdigest(),
            "producerSystem": self.producer_system,
            "tokenizationVersion": "stemwise.display-words.v2",
            "corpusManifestSha256": None,
            "annotationProtocolSha256": None,
            "hardwareProfiles": {"pc": None, "android": None},
            "androidQualification": {
                "sourceSha256": None,
                "deliverableArtifactSha256": None,
                "hostQualificationSha256": None,
                "appBuild": None,
                "requiredLanes": [],
            },
            "tolerancesMs": {"lineStart": 100, "lineEnd": 100, "wordStart": 100, "wordEnd": 100},
            "structurePredicates": {
                "minimumIndependentSupport": 2,
                "distinctPassingSequencesAllowed": 1,
                "independentRouteFamilies": ["manual-caption", "unprompted-asr", "mms-acoustic"],
            },
            "splitPolicy": {
                "separateBy": ["recordingSha256", "songVersionFamilySha256", "singerSha256"],
                "values": ["development", "held-out"],
                "scoredSplit": "held-out",
                "heldOutMayTuneCandidate": False,
            },
            "strata": {
                "dimensions": ["language", "script", "difficulty"],
                "minimumCaseCount": 1,
                "inventory": [{"language": "en", "script": "Latn", "difficulty": "synthetic"}],
            },
            "confidenceBound": {"method": "wilson-one-sided-upper", "alpha": 0.05},
            "gates": {
                "applyToEachStratum": True,
                "minimum": {
                    "lineBoundaryWithinToleranceRate": 1.0,
                    "wordBoundaryWithinToleranceRate": 1.0,
                    "performedOccurrenceAccuracy": 1.0,
                    "wordBearingLineCoverage": 1.0,
                    "safeAbstentionRate": 1.0,
                    "intervalValidityRate": 1.0,
                    "monotonicityRate": 1.0,
                    "utf16ProjectionRate": 1.0,
                    "graphemeProjectionRate": 1.0,
                    "lexicalAuthorityIdentityRate": 1.0,
                    "exactRecordingIdentityRate": 1.0,
                },
                "maximum": {
                    "falsePublicationUpperBound": 0.8,
                    "crossViewBoundarySpreadP95Ms": 10,
                    "chunkVsFullP95Ms": 10,
                    "rerunP95Ms": 10,
                    "canonicalTimelineResidualP95Ms": 1,
                    "unresolvedRate": 0,
                    "technicalFailureRate": 0,
                    "runtimeP95Ms": 1000,
                    "peakMemoryMaxBytes": 2000,
                    "diskMaxBytes": 3000,
                    "retainedMaxBytes": 100,
                },
            },
        }
        self.device_report = {
            "schemaVersion": 2,
            "mode": "Device",
            "serial": self.device_profile["serial"],
            "runId": "LYRICS-ANDROID-QUALIFICATION",
            "attemptId": "synthetic-attempt",
            "started": "2026-08-23T00:00:00+00:00",
            "finished": "2026-08-23T00:01:00+00:00",
            "status": "PASS",
            "failure": None,
            "failureKind": None,
            "lanes": [
                {
                    "lane": "lyrics-integration",
                    "target": None,
                    "started": "2026-08-23T00:00:00+00:00",
                    "finished": "2026-08-23T00:01:00+00:00",
                    "status": "PASS",
                    "failure": None,
                    "cleanupFailure": None,
                    "failureKind": None,
                }
            ],
            "deviceProfile": self.device_profile,
            "artifact": {
                "fileName": "deliverable-application.apk",
                "bytes": len(self.deliverable_bytes),
                "sha256": self.deliverable_sha256,
            },
            "artifacts": [
                {
                    "role": "deliverable",
                    "kind": "application",
                    "fileName": "deliverable-application.apk",
                    "bytes": len(self.deliverable_bytes),
                    "sha256": self.deliverable_sha256,
                    "applicationId": "app.stemwise.development",
                    "versionCode": str(self.expected_app_build['versionCode']),
                    "versionName": self.expected_app_build['versionName'],
                    "certificateSha256": "67CF677864A1BC66F940191E892CAB64CA41A614A9C09B41278FFCD9B8149D28",
                    "evidencePath": "immutable-artifacts/deliverable-application.apk",
                    "buildConfiguration": None,
                    "buildEvidence": [],
                }
            ],
            "hostQualification": copy.deepcopy(self.host_qualification),
            "qualificationSourceSha256": self.android_source_sha256,
            "qualificationSourceManifest": {
                "path": "qualification-source-manifest.json",
                "sha256": "pending",
            },
            "deviceInventory": {
                "path": "inventory.json",
                "sha256": "pending",
            },
            "deviceExecutionAccounting": {
                "path": "execution-accounting.json",
                "sha256": "pending",
            },
            "producerSystemSha256": self.producer_system_sha256,
            "audioSourceSha256": self.android_source_sha256,
            "audioSourceEndSha256": self.android_source_sha256,
            "audioArtifacts": [],
            "audioExecutions": [],
            "audioBuildConfiguration": None,
            "audioBuildEvidence": [],
        }

    def build(self) -> tuple[Path, Path, Path]:
        corpus_root = self.root / "corpus"
        results_root = self.root / "results"
        annotation_sha = _write(corpus_root / "annotation-protocol.json", self.annotation)
        corpus_cases = []
        for split, reference in self.references.items():
            path = f"reference-{split}.json"
            sha256 = _write(corpus_root / path, reference)
            corpus_cases.append(
                {
                    "caseIdSha256": reference["caseIdSha256"],
                    "reference": {"path": path, "sha256": sha256},
                }
            )
        corpus = {
            "kind": "stemwise.lyrics-qualification-corpus",
            "formatVersion": 2,
            "annotationProtocol": {"path": "annotation-protocol.json", "sha256": annotation_sha},
            "cases": corpus_cases,
        }
        corpus_path = corpus_root / "manifest.json"
        corpus_sha = _write(corpus_path, corpus)
        hardware_sha = _write(
            results_root / "hardware.json",
            {
                "kind": "stemwise.pc-hardware-profile",
                "formatVersion": 2,
                "operatingSystem": {
                    "name": "synthetic-os",
                    "release": "1",
                    "version": "1.0",
                    "machine": "x86_64",
                },
                "cpu": {"model": "synthetic-cpu", "logicalProcessorCount": 8},
                "memoryBytes": 17179869184,
                "gpus": [
                    {
                        "name": "synthetic-gpu",
                        "driver": "1.0",
                        "memoryBytes": 8589934592,
                    }
                ],
                "runtime": {
                    "python": "3.10.18",
                    "implementation": "CPython",
                    "torch": "2.7.0+cu128",
                    "cuda": "12.8",
                    "cudnn": "9",
                },
            },
        )
        junit = results_root / "host-qualification" / "unit-tests" / "results"
        junit.mkdir(parents=True, exist_ok=True)
        (junit / "TEST-synthetic.xml").write_text(
            '<testsuite tests="1" failures="0" errors="0" skipped="0">'
            '<testcase classname="app.stemwise.LyricsQualificationTest" name="passes"/>'
            '</testsuite>',
            encoding="utf-8",
        )
        lint_root = results_root / "host-qualification" / "lint"
        lint_root.mkdir(parents=True, exist_ok=True)
        (lint_root / "results.xml").write_text("<issues/>", encoding="utf-8")
        self.profile["corpusManifestSha256"] = corpus_sha
        self.profile["annotationProtocolSha256"] = annotation_sha
        self.profile["hardwareProfiles"]["pc"] = hardware_sha
        if self.claims_allowed:
            self.profile["hardwareProfiles"]["android"] = _object_digest(self.device_profile)
            self.profile["androidQualification"] = {
                "sourceSha256": self.android_source_sha256,
                "deliverableArtifactSha256": self.deliverable_sha256,
                "hostQualificationSha256": _object_digest(self.host_qualification),
                "appBuild": copy.deepcopy(self.expected_app_build),
                "requiredLanes": self.required_lanes,
            }
        profile_path = self.root / "profile.json"
        profile_sha = _write(profile_path, self.profile)

        result_cases = []
        for split, result in self.results.items():
            path = f"result-{split}.json"
            sha256 = _write(results_root / path, result)
            result_cases.append(
                {
                    "caseIdSha256": result["caseIdSha256"],
                    "result": {"path": path, "sha256": sha256},
                }
            )
        manifest = {
            "kind": "stemwise.lyrics-qualification-results",
            "formatVersion": 2,
            "profileSha256": profile_sha,
            "corpusManifestSha256": corpus_sha,
            "producerSystem": self.producer_system,
            "hardwareProfile": {"path": "hardware.json", "sha256": hardware_sha},
            "cases": result_cases,
        }
        if self.include_device:
            source_manifest_sha = _write(
                results_root / "qualification-source-manifest.json",
                self.source_manifest,
            )
            self.device_report["qualificationSourceManifest"][
                "sha256"
            ] = source_manifest_sha
            method = "app.stemwise.LyricsQualificationTest#passes"
            inventory_sha = _write(
                results_root / "inventory.json",
                {
                    "deliberateExclusionCount": 0,
                    "laneCounts": {"lyrics-integration": 1},
                    "methodCount": 1,
                    "methods": [
                        {
                            "class": "app.stemwise.LyricsQualificationTest",
                            "id": method,
                            "lane": "lyrics-integration",
                        }
                    ],
                    "schemaVersion": 1,
                    "sourceRoot": "synthetic",
                    "status": "PASS",
                    "suiteEntries": [],
                    "suiteEntryCount": 0,
                    "unassignedCount": 0,
                    "unintendedDuplicateCount": 0,
                    "wrapperCount": 0,
                    "wrappers": [],
                },
            )
            accounting_sha = _write(
                results_root / "execution-accounting.json",
                {
                    "schemaVersion": 1,
                    "label": "device",
                    "status": "PASS",
                    "expectedCount": 1,
                    "passingCount": 1,
                    "skippedCount": 0,
                    "expectedMethods": [method],
                    "passingMethods": [method],
                    "skippedMethods": [],
                    "missingMethods": [],
                    "unexpectedMethods": [],
                },
            )
            self.device_report["deviceInventory"]["sha256"] = inventory_sha
            self.device_report["deviceExecutionAccounting"][
                "sha256"
            ] = accounting_sha
            artifact_path = results_root / "immutable-artifacts" / "deliverable-application.apk"
            artifact_path.parent.mkdir(parents=True, exist_ok=True)
            artifact_path.write_bytes(self.deliverable_bytes)
            pc_report = copy.deepcopy(self.device_report)
            pc_report['mode'] = 'PcSuite'
            pc_report['deviceProfile'] = None
            pc_report['lanes'] = []
            pc_report['artifact'] = None
            pc_report['artifacts'] = []
            pc_sha = _write(results_root / 'pc-run.json', pc_report)
            android_sha = _write(results_root / 'android-run.json', self.device_report)
            self.full_report = {'schemaVersion': 3, 'mode': 'Full', 'status': 'PASS', 'phases': {
                'PcSuite': {'path': 'results/pc-run.json', 'sha256': pc_sha},
                'AndroidSuite': {'path': 'results/android-run.json', 'sha256': android_sha}}}
            device_sha = _write(results_root / "device-run.json", self.full_report)
            manifest["deviceQualificationReport"] = {"path": "device-run.json", "sha256": device_sha}
        results_path = results_root / "manifest.json"
        _write(results_path, manifest)
        return profile_path, corpus_path, results_path


class QualificationIdentityTests(unittest.TestCase):
    def test_real_pc_only_enumeration_qualifies_without_android_or_tests(self):
        import stemwise_engine.lyrics_qualification as qualification
        from stemwise_engine import source_inputs
        from unittest.mock import patch
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1]/'Test suite/work') as name:
            root=Path(name)
            for relative in (*[p for p in source_inputs.TREES if p.startswith('PC side/')],
                    'PC side/qobuz-comparison', 'StemWise/contracts/enrichment'):
                (root/relative).mkdir(parents=True,exist_ok=True)
            for relative in [p for p in source_inputs.FILES if p.startswith('PC side/')]:
                path=root/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('{}',encoding='utf-8')
            builder=root/'PC side/stemwise-playlist-builder'
            (builder/'config.json').write_text('{}',encoding='utf-8')
            (builder/'future-songs/policy/lyrics-policy.json').write_text('{}',encoding='utf-8')
            (root/'StemWise/contracts/enrichment/song-enrichment-v2.schema.json').write_text('{}',encoding='utf-8')
            with patch.object(qualification,'__file__',str(builder/'src/lyrics_qualification.py')):
                before=qualification.producer_system_identity(pc_only=True)
                self.assertEqual(before,qualification.producer_system_identity(pc_only=True))
                with self.assertRaises(FileNotFoundError): qualification.producer_system_identity()
            self.assertFalse((root/'Test suite').exists())
            self.assertFalse((root/'StemWise/app').exists())

    def test_pc_identity_ignores_test_docs_and_android_but_binds_pc_producer(self):
        import stemwise_engine.lyrics_qualification as qualification
        from unittest.mock import patch
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1]/'Test suite/work') as name:
            root=Path(name)
            files={
                'PC side/stemwise-playlist-builder/src/producer.py':'numerical-source',
                'PC side/stemwise-playlist-builder/future-songs/policy/lyrics-policy.json':'{}',
                'PC side/stemwise-playlist-builder/config.json':'{}',
                'StemWise/contracts/enrichment/song-enrichment-v2.schema.json':'{}',
                'StemWise/app/src/Player.kt':'android-production',
                'Test suite/pc/unit/test_producer.py':'test-only',
                'SEND_TO_AGENTS.md':'usage-prose',
            }
            for relative,body in files.items():
                path=root/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body,encoding='utf-8')
            module=root/'PC side/stemwise-playlist-builder/src/lyrics_qualification.py'
            paths=[root/relative for relative in files]
            with patch.object(qualification,'__file__',str(module)), patch('stemwise_engine.source_inputs.source_files',return_value=paths):
                before=qualification.producer_system_identity(pc_only=True)
                for relative in ('Test suite/pc/unit/test_producer.py','SEND_TO_AGENTS.md','StemWise/app/src/Player.kt'):
                    (root/relative).write_text('changed',encoding='utf-8')
                    self.assertEqual(before,qualification.producer_system_identity(pc_only=True))
                (root/'PC side/stemwise-playlist-builder/src/producer.py').write_text('different processing',encoding='utf-8')
                self.assertNotEqual(before,qualification.producer_system_identity(pc_only=True))


class LyricsQualificationTest(unittest.TestCase):
    def test_full_composition_rejects_missing_failed_changed_or_different_source_phases(self):
        for fault in ('missing', 'changed-report', 'failed', 'different-source', 'host-xml', 'pc-source-manifest', 'inline-format'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory(dir=PROJECT.parents[1] / 'Test suite/work') as name:
                fixture = QualificationFixture(Path(name), claims_allowed=True, include_device=True)
                paths = fixture.build()
                root = paths[2].parent
                full_path = root / 'device-run.json'
                full = json.loads(full_path.read_text(encoding='utf-8'))
                pc_path = root / 'pc-run.json'
                pc = json.loads(pc_path.read_text(encoding='utf-8'))
                if fault == 'missing':
                    pc_path.unlink()
                elif fault == 'changed-report':
                    pc_path.write_bytes(pc_path.read_bytes() + b' ')
                elif fault == 'host-xml':
                    (root / 'host-qualification/unit-tests/results/TEST-synthetic.xml').write_text(
                        '<testsuite tests="0" failures="0" errors="0" skipped="0"/>', encoding='utf-8')
                elif fault == 'inline-format':
                    full = fixture.device_report
                else:
                    if fault == 'failed':
                        pc['status'] = 'FAIL'
                    elif fault == 'different-source':
                        pc['qualificationSourceSha256'] = '0' * 64
                    else:
                        source = copy.deepcopy(fixture.source_manifest)
                        source[0]['sha256'] = '0' * 64
                        other = root / 'pc-source.json'
                        pc['qualificationSourceManifest'] = {'path': other.name, 'sha256': _write(other, source)}
                    full['phases']['PcSuite']['sha256'] = _write(pc_path, pc)
                manifest = json.loads(paths[2].read_text(encoding='utf-8'))
                manifest['deviceQualificationReport']['sha256'] = _write(full_path, full)
                _write(paths[2], manifest)
                with self.assertRaises(ValueError):
                    run_qualification(*paths)

    def test_unbound_startup_ignores_optional_scorer_hash_but_scoring_still_enforces_it(self):
        import stemwise_engine.lyrics_qualification as qualification
        from unittest.mock import patch
        profile = PROJECT / 'qualification/qualification-profile.json'
        actual = qualification._sha256_file
        def changed_scorer(path):
            value = actual(path)
            return ('0'*64, value[1]) if Path(path) == Path(qualification.__file__) else value
        with patch.object(qualification, '_sha256_file', side_effect=changed_scorer):
            self.assertEqual(qualification_profile_identity(profile)['status'], 'unbound')
            with self.assertRaisesRegex(ValueError, 'metric code hash mismatch'):
                run_qualification(profile, Path('unused-corpus.json'), Path('unused-results.json'))
    def test_reversed_word_starts_reject_reference_and_count_false_publication(self):
        from stemwise_engine.lyrics_qualification import _validate_reference, _new_accumulator, _add_case, _metrics
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1]/'Test suite/work') as name:
            fixture = QualificationFixture(Path(name))
            reference = copy.deepcopy(fixture.references['held-out'])
            first = reference['occurrences'][0]['words'][0]
            first.update(startMs=100, endMs=150)
            second = {**first, 'wordIdSha256': _digest('second'), 'startUtf16': 4, 'endUtf16': 7,
                      'startMs': 130, 'endMs': 170}
            reference['occurrences'][0]['words'].append(second)
            result = copy.deepcopy(fixture.results['held-out'])
            result['occurrences'][0]['words'] = copy.deepcopy(reference['occurrences'][0]['words'])
            for key in result['validation']:
                result['validation'][key] = 2
            _validate_reference(reference, fixture.profile)
            for starts, valid in (((100, 130), True), ((100, 100), True), ((130, 100), False)):
                with self.subTest(starts=starts):
                    candidate = copy.deepcopy(result)
                    for word, start in zip(candidate['occurrences'][0]['words'], starts):
                        word.update(startMs=start, endMs=start + 50)
                    accumulator = _new_accumulator()
                    _add_case(accumulator, reference, candidate, fixture.profile)
                    metrics = _metrics(accumulator, .05)
                    self.assertEqual(metrics['monotonicityRate'] == 1, valid)
                    self.assertEqual(metrics['intervalValidityRate'] == 1, valid)
                    if not valid:
                        self.assertGreater(metrics['falsePublicationRate'], 0)
                        reversed_reference = copy.deepcopy(reference)
                        reversed_reference['occurrences'][0]['words'] = candidate['occurrences'][0]['words']
                        with self.assertRaisesRegex(ValueError, 'word starts are not monotonic'):
                            _validate_reference(reversed_reference, fixture.profile)

    def setUp(self):
        from unittest.mock import patch
        # Source enumeration has separate scratch-tree regressions; freeze that
        # I/O boundary while exercising the qualification evidence contract.
        producer = {key: _digest(key) for key in ('sourceSha256', 'lyricsPolicySemanticsSha256',
            'generatorConfigSha256', 'externalSchemaSha256')}
        for target in ('stemwise_engine.lyrics_qualification.producer_system_identity', __name__+'.producer_system_identity'):
            mocked = patch(target, return_value=producer)
            mocked.start()
            self.addCleanup(mocked.stop)




    def test_pc_only_cli_uses_pc_gates_without_claiming_full_qualification(self):
        import stemwise_engine.lyrics_qualification as qualification
        from contextlib import redirect_stdout
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1]/'Test suite/work') as name:
            fixture = QualificationFixture(Path(name))
            paths = fixture.build()
            arguments = [option for pair in zip(('--profile','--corpus-manifest','--results-manifest'), map(str,paths)) for option in pair]
            output=io.StringIO()
            with redirect_stdout(output):
                self.assertEqual(qualification.main([*arguments,'--pc-only']),0)
            report=json.loads(output.getvalue())
            self.assertTrue(report['pcPassed'])
            self.assertFalse(report['devicePassed'])
            self.assertFalse(report['qualified'])
            with redirect_stdout(io.StringIO()):
                self.assertEqual(qualification.main(arguments),1)
            result_path=Path(name)/'results/result-held-out.json'
            result_path.write_bytes(result_path.read_bytes()+b' ')
            with redirect_stdout(io.StringIO()):
                self.assertEqual(qualification.main([*arguments,'--pc-only']),2)

    def test_pc_pass_is_not_global_without_device_report_and_tamper_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1]/'Test suite/work') as name:
            fixture = QualificationFixture(Path(name))
            paths = fixture.build()
            report = run_qualification(*paths)
            self.assertTrue(report["pcPassed"])
            self.assertFalse(report["devicePassed"])
            self.assertFalse(report["qualified"])

            result_path = Path(name) / "results" / "result-held-out.json"
            result_path.write_bytes(result_path.read_bytes() + b" ")
            with self.assertRaisesRegex(ValueError, "hash mismatch"):
                run_qualification(*paths)

    def test_exact_identities_are_compared_and_only_held_out_is_gated(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1]/'Test suite/work') as name:
            fixture = QualificationFixture(Path(name))
            fixture.results["development"]["occurrences"][0]["endMs"] = 1500
            report = run_qualification(*fixture.build())
            self.assertTrue(report["pcPassed"])
            self.assertGreater(report["development"]["lineEndErrorMs"]["p95"], 100)

            fixture.results["held-out"]["lexicalAuthoritySha256"] = _digest("wrong-authority")
            report = run_qualification(*fixture.build())
            self.assertFalse(report["pcPassed"])
            self.assertEqual(report["heldOut"]["overall"]["lexicalAuthorityIdentityRate"], 0.0)
            self.assertEqual(report["heldOut"]["overall"]["exactRecordingIdentityRate"], 1.0)

            fixture.results["held-out"]["recordingSha256"] = _digest("wrong-recording")
            report = run_qualification(*fixture.build())
            self.assertEqual(report["heldOut"]["overall"]["exactRecordingIdentityRate"], 0.0)

    def test_result_outcome_must_match_occurrence_and_word_semantics(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1]/'Test suite/work') as name:
            fixture = QualificationFixture(Path(name))
            fixture.results["held-out"]["outcome"] = "committed-lines"
            with self.assertRaisesRegex(ValueError, "outcome contradicts"):
                run_qualification(*fixture.build())

    def test_content_blocker_counts_as_unresolved_and_cannot_publish_timings(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1]/'Test suite/work') as name:
            fixture = QualificationFixture(Path(name))
            result = fixture.results["held-out"]
            result["outcome"] = "lyrics-content-unresolved"
            with self.assertRaisesRegex(ValueError, "outcome contradicts"):
                run_qualification(*fixture.build())
            result["occurrences"] = []
            result["validation"] = dict.fromkeys(result["validation"], 0)
            report = run_qualification(*fixture.build())
            self.assertEqual(report["heldOut"]["overall"]["unresolvedRate"], 1.0)
            self.assertEqual(report["heldOut"]["overall"]["technicalFailureRate"], 0.0)
            self.assertEqual(report["heldOut"]["overall"]["outcomes"]["lyrics-content-unresolved"], 1)

    def test_frozen_split_groups_and_strata_inventory_are_exhaustive(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1]/'Test suite/work') as name:
            fixture = QualificationFixture(Path(name))
            fixture.references["held-out"]["singerSha256"] = fixture.references["development"]["singerSha256"]
            with self.assertRaisesRegex(ValueError, "grouping crosses"):
                run_qualification(*fixture.build())

        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1]/'Test suite/work') as name:
            fixture = QualificationFixture(Path(name))
            fixture.profile["strata"]["inventory"].append(
                {"language": "ar", "script": "Arab", "difficulty": "rtl"}
            )
            with self.assertRaisesRegex(ValueError, "exactly cover"):
                run_qualification(*fixture.build())

        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1]/'Test suite/work') as name:
            fixture = QualificationFixture(Path(name))
            fixture.profile["structurePredicates"]["minimumIndependentSupport"] = 1
            with self.assertRaisesRegex(ValueError, "differ from runtime"):
                run_qualification(*fixture.build())

    def test_global_qualification_requires_hash_verified_android_report(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1]/'Test suite/work') as name:
            fixture = QualificationFixture(Path(name), claims_allowed=True)
            report = run_qualification(*fixture.build())
            self.assertTrue(report["pcPassed"])
            self.assertFalse(report["qualified"])

            fixture.include_device = True
            paths = fixture.build()
            report = run_qualification(*paths)
            self.assertTrue(report["devicePassed"])
            self.assertTrue(report["qualified"])

            device_path = Path(name) / "results" / "device-run.json"
            device_path.write_bytes(device_path.read_bytes() + b" ")
            with self.assertRaisesRegex(ValueError, "hash mismatch"):
                run_qualification(*paths)

        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1]/'Test suite/work') as name:
            fixture = QualificationFixture(Path(name), claims_allowed=True, include_device=True)
            fixture.device_profile["model"] = "SM-X936B"
            paths = fixture.build()
            self.assertTrue(run_qualification(*paths)['qualified'])
            device_path = paths[2].parent / 'android-run.json'
            changed = json.loads(device_path.read_text(encoding='utf-8'))
            changed['deviceProfile']['model'] = 'different-physical-device'
            manifest = json.loads(paths[2].read_text(encoding='utf-8'))
            full_path = paths[2].parent / 'device-run.json'
            full = json.loads(full_path.read_text(encoding='utf-8'))
            full['phases']['AndroidSuite']['sha256'] = _write(device_path, changed)
            manifest['deviceQualificationReport']['sha256'] = _write(full_path, full)
            _write(paths[2], manifest)
            with self.assertRaisesRegex(ValueError, "device profile hash mismatch"):
                run_qualification(*paths)

    def test_device_gate_rejects_forged_source_lanes_host_and_artifact(self) -> None:
        mutations = [
            ("device identity is invalid", lambda fixture: fixture.device_profile.__setitem__('socModel', '')),
            ("integer is invalid", lambda fixture: fixture.device_profile.__setitem__('api', 27)),
            ("integer is invalid", lambda fixture: fixture.device_profile.__setitem__('api', True)),
            ("unexpected fields", lambda fixture: fixture.device_profile.__setitem__('budgets', {})),
            (
                "source hash mismatch",
                lambda fixture: fixture.device_report.__setitem__(
                    "qualificationSourceSha256", _digest("forged-source")
                ),
            ),
            (
                "lane inventory mismatch",
                lambda fixture: fixture.device_report["lanes"].append(
                    {
                        **fixture.device_report["lanes"][0],
                        "lane": "unexpected-lane",
                    }
                ),
            ),
            (
                "lane is duplicated",
                lambda fixture: fixture.device_report["lanes"].append(
                    copy.deepcopy(fixture.device_report["lanes"][0])
                ),
            ),
            (
                "has no device lanes",
                lambda fixture: fixture.device_report.__setitem__("lanes", []),
            ),
            (
                "member is missing",
                lambda fixture: fixture.device_report["hostQualification"]["lint"][
                    "reportLocations"
                ].__setitem__("xml", "forged.xml"),
            ),
            (
                "deliverable artifact identity mismatch",
                lambda fixture: fixture.device_report["artifact"].__setitem__(
                    "sha256", _digest("forged-artifact")
                ),
            ),
        ]
        for expected, mutate in mutations:
            with self.subTest(expected=expected), tempfile.TemporaryDirectory(dir=PROJECT.parents[1]/'Test suite/work') as name:
                fixture = QualificationFixture(
                    Path(name), claims_allowed=True, include_device=True
                )
                mutate(fixture)
                with self.assertRaisesRegex(ValueError, expected):
                    run_qualification(*fixture.build())

    def test_frozen_artifact_build_controls_both_report_versions(self) -> None:
        for mismatch in (None, 'profile', 'artifact'):
            with self.subTest(mismatch=mismatch), tempfile.TemporaryDirectory(dir=PROJECT.parents[1]/'Test suite/work') as name:
                fixture = QualificationFixture(Path(name), claims_allowed=True, include_device=True)
                fixture.expected_app_build.update(versionCode=57, versionName='current-artifact-fixture-development')
                artifact = fixture.device_report['artifacts'][0]
                artifact.update(versionCode='57', versionName='current-artifact-fixture-development')
                if mismatch == 'profile':
                    fixture.expected_app_build['versionCode'] = 56
                elif mismatch == 'artifact':
                    artifact['versionName'] = 'different-artifact'
                if mismatch:
                    with self.assertRaisesRegex(ValueError, 'app build|deliverable evidence'):
                        run_qualification(*fixture.build())
                else:
                    self.assertTrue(run_qualification(*fixture.build())['qualified'])

    def test_frozen_android_qualification_rejects_consumer_release_identity_and_suffix(self) -> None:
        for change in ({'applicationId': 'app.stemwise'}, {'versionName': '3.7.21'}):
            with self.subTest(change=change), tempfile.TemporaryDirectory(dir=PROJECT.parents[1]/'Test suite/work') as name:
                fixture = QualificationFixture(Path(name), claims_allowed=True, include_device=True)
                fixture.expected_app_build.update(change)
                fixture.device_report['artifacts'][0].update(change)
                with self.assertRaisesRegex(ValueError, 'Android app build'):
                    run_qualification(*fixture.build())


if __name__ == "__main__":
    unittest.main()

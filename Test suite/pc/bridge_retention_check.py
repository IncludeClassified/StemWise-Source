"""Offline Finalize -> Bridge producer/store -> full-ZIP consumer retention check.

Run with --helper pointing to the Linux fixture built from fixtures/bridge_retention.go
against StemWise/third_party/phone-pc-bridge/source/bridge-core. No bridge service is contacted.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'Test suite/pc/unit'))
import test_native_delivery
from stemwise_engine.bridge_delivery import describe
from stemwise_engine.future_song import FutureSongWorkflow
from stemwise_engine.original_delivery import read_bundle


def verify(helper):
    fixture = test_native_delivery.NativeDeliveryTest()
    fixture.setUp()
    try:
        aliases, _ = fixture.prepare_reprocessing(registered=True, absent_plan=True)
        with fixture.reprocessing_boundaries():
            fixture.workflow.finalize(fixture.workflow.video_id)
        def policy(instance):
            instance.contract_identity = fixture.workflow.contract_identity
            instance.policy_sha256 = fixture.workflow.policy_sha256
            return fixture.workflow.policy
        with patch.object(FutureSongWorkflow, '_validate_policy', policy), \
                patch.object(FutureSongWorkflow, '_validated_snapshot_is_current', return_value=True):
            package = describe(fixture.workflow.config_path, fixture.workflow.video_id,
                aliases[0].stat().st_size)['package']
        source = aliases[0]
        before = source.read_bytes(), source.stat().st_mtime_ns
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            completed = subprocess.run([str(helper), str(source), name], check=True,
                text=True, capture_output=True, timeout=30)
            descriptor = json.loads(completed.stdout)
            fixture.assertEqual({key: descriptor[key] for key in ('name', 'size', 'sha256')},
                {key: package[key] for key in ('name', 'size', 'sha256')})
            transferred = Path(name) / 'received.zip'
            fixture.assertEqual(transferred.read_bytes(), before[0])
            fixture.assertEqual(read_bundle(transferred, fixture.workflow._runtime_config())[0], fixture.document)
            fixture.assertEqual((source.read_bytes(), source.stat().st_mtime_ns), before)
            fixture.assertEqual(list((Path(name) / 'managed/fixture-peer').iterdir()), [])
    finally:
        fixture.doCleanups()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--helper', required=True, type=Path)
    arguments = parser.parse_args()
    result = unittest.TextTestRunner().run(unittest.FunctionTestCase(lambda: verify(arguments.helper.resolve())))
    raise SystemExit(not result.wasSuccessful())

import importlib.util
from pathlib import Path
import shutil
import tempfile
import subprocess
import sys
import json
import unittest

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location('document_contracts', ROOT/'Test suite/host/document_contracts.py')
DOCS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DOCS)


class DocumentContractsTests(unittest.TestCase):
    def test_schema_regeneration_updates_its_documented_digest_without_a_second_command(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            paths = ['StemWise/tools/generate-contract-limits.py', 'Test suite/host/document_contracts.py',
                     'STEMWISE_DOCUMENTATION.md',
                     'StemWise/contracts/audio/playback-capacity.json',
                     'StemWise/audio-engine/src/main/java/app/stemwise/model/StemSourceCatalogue.kt',
                     'StemWise/audio-engine/src/main/java/app/stemwise/model/StemRoleContract.kt',
                     'StemWise/audio-engine/src/main/java/app/stemwise/playback/engine/NativeLaneIngressBridge.kt']
            paths += [str(path.relative_to(ROOT)) for path in
                      (ROOT/'StemWise/contracts/enrichment').glob('*.json')]
            for relative in paths:
                destination = root/relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT/relative, destination)
            for relative in ('PC side/stemwise-playlist-builder/src',
                             'StemWise/audio-engine/src/main/cpp/native_engine',
                             'StemWise/app/src/main/java/app/stemwise/enrichment'):
                (root/relative).mkdir(parents=True, exist_ok=True)
            schema = root/'StemWise/contracts/enrichment/song-enrichment-v2.schema.json'
            contract = json.loads(schema.read_text(encoding='utf-8'))
            contract['$defs']['nativeRecording']['description'] = 'A changed contract description.'
            schema.write_text(json.dumps(contract), encoding='utf-8')
            subprocess.run([sys.executable, '-B', str(root/paths[0])], check=True, capture_output=True)
            DOCS.check(root)
            subprocess.run([sys.executable, '-B', str(root/paths[0]), '--check'], check=True, capture_output=True)

    def test_current_contract_values(self):
        DOCS.check()

    def test_changed_executable_limit_cannot_leave_stale_documentation(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            paths = ['STEMWISE_DOCUMENTATION.md',
                     'StemWise/contracts/audio/playback-capacity.json',
                     'StemWise/audio-engine/src/main/java/app/stemwise/model/StemSourceCatalogue.kt',
                     'StemWise/audio-engine/src/main/java/app/stemwise/model/StemRoleContract.kt',
                     'StemWise/audio-engine/src/main/java/app/stemwise/playback/engine/NativeLaneIngressBridge.kt',
                     'StemWise/contracts/enrichment/installed-enrichment-v2.schema.json']
            for relative in paths:
                destination = root/relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT/relative, destination)
            DOCS.check(root)
            with (root/'STEMWISE_DOCUMENTATION.md').open('a', encoding='utf-8') as stream:
                stream.write('\nA reworded explanation is not numerical authority.\n')
            (root/'focused-reference.md').write_text('A focused reference is allowed.', encoding='utf-8')
            DOCS.check(root)
            implementation = root/paths[2]
            implementation.write_text(implementation.read_text().replace('    const val MAX_TARGETS', '  const val MAX_TARGETS'), encoding='utf-8')
            DOCS.check(root)
            path = root/'StemWise/contracts/audio/playback-capacity.json'
            value = json.loads(path.read_bytes())
            value['maximumTargets'] = 7
            path.write_text(json.dumps(value), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'differ from executable'):
                DOCS.check(root)
            self.assertEqual(DOCS.values(root)['Selected target limit'], '7')


if __name__ == '__main__':
    unittest.main()

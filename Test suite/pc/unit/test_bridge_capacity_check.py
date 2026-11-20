import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('bridge_capacity_check', ROOT / 'Test suite/pc/bridge_capacity_check.py')
capacity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capacity)


class BridgeCapacityCheckTest(unittest.TestCase):
    def test_exact_pass_is_required_and_skip_failure_absence_and_substitution_reject(self):
        test = 'TestStorageReservationsOnSmallFilesystem'
        passed = {'Action': 'pass', 'Package': 'phonepcbridge/files', 'Test': test}
        capacity.require_pass(json.dumps(passed), 'files', test)
        for rows in ([], [dict(passed, Action='skip')], [dict(passed, Action='fail')],
                     [passed, passed], [dict(passed, Test='TestOther')],
                     [dict(passed, Package='phonepcbridge/pc')],
                     [passed, dict(passed, Action='skip', Test=test + '/child')]):
            with self.subTest(rows=rows), self.assertRaises(RuntimeError):
                capacity.require_pass('\n'.join(map(json.dumps, rows)), 'files', test)

    def test_capacity_runner_changes_select_bridge_qualification(self):
        spec = importlib.util.spec_from_file_location('ci_changes', ROOT / 'Test suite/host/ci_changes.py')
        ci = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(ci)
        for path in ('Test suite/pc/bridge_capacity_check.py', 'Test suite/pc/unit/test_bridge_capacity_check.py'):
            self.assertTrue(ci.affected([path])['bridge-core'])

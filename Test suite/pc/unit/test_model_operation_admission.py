"""Outstanding operations use real shared slots, not editable receipt flags."""
import contextlib
import io
import os
from pathlib import Path
import select
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / 'PC side/stemwise-playlist-builder/src'
import stemwise_engine.common as common
import stemwise_engine.future_song as future_song
import stemwise_engine.workflow_command as workflow_command
from stemwise_engine.campaign_operations import ModelOperationBusyError, model_operation_slot


class ModelOperationAdmissionTest(unittest.TestCase):
    def test_fourth_canonical_operation_rejects_before_mutation_then_capacity_recovers(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory, contextlib.ExitStack() as stack:
            project = Path(directory) / 'PC side/builder'
            project.mkdir(parents=True)
            config = {'projectDir': str(project)}
            before = project / 'job.json'
            before.write_bytes(b'{"state":"preserved"}')
            environment = {**os.environ, 'PYTHONPATH': str(SOURCE), 'PYTHONDONTWRITEBYTECODE': '1'}
            environment.pop(common._PROCESS_LOCK_LEASES_ENV, None)
            script = ('import sys\nfrom stemwise_engine.campaign_operations import model_operation_slot\nwith model_operation_slot({"projectDir":sys.argv[1]}):\n print("admitted",flush=True)\n input()\n')
            workers = []
            for _ in range(3):
                worker = stack.enter_context(common.owned_process_tree(
                    [sys.executable, '-B', '-c', script, str(project)], env=environment,
                    stdin=-1, stdout=-1, stderr=-1, text=True))
                self.assertTrue(select.select([worker.stdout], [], [], 5)[0], 'Slot owner did not start')
                self.assertEqual(worker.stdout.readline().strip(), 'admitted')
                workers.append(worker)
            workflow = Mock(config=config)
            workflow.new.side_effect = lambda _url: (before.write_bytes(b'overwritten') or {'state': 'NEW'})
            with patch.object(workflow_command, 'FutureSongWorkflow', return_value=workflow), \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                result = workflow_command.main(['--config', 'fixture.json', 'new', '--url',
                                          'https://youtu.be/abcdefghijk'])
            self.assertNotEqual(result, 0, 'Fourth canonical operation bypassed the shared cap')
            self.assertEqual(before.read_bytes(), b'{"state":"preserved"}')
            workflow.new.assert_not_called()
            with self.assertRaises(ModelOperationBusyError):
                with model_operation_slot(config):
                    self.fail('Freely written state must not free a live kernel slot')
            workers[0].communicate('\n', timeout=5)
            self.assertEqual(workers[0].returncode, 0)
            with model_operation_slot(config):
                with model_operation_slot(config):
                    pass
                inherited = common.run_process_tree(
                    [sys.executable, '-B', '-c',
                     'import sys\nfrom stemwise_engine.campaign_operations import model_operation_slot\nwith model_operation_slot({"projectDir":sys.argv[1]}): print("inherited")', str(project)],
                    env=environment, check=True, capture_output=True, text=True, timeout=5)
                self.assertEqual(inherited.stdout.strip(), 'inherited')


if __name__ == '__main__':
    unittest.main()

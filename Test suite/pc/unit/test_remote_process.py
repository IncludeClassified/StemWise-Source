"""Remote retirement requires the original live process birth and command."""
from pathlib import Path
import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'host'))
from remote_process import run_remote


class RemoteProcessTest(unittest.TestCase):
    directory = '/data/local/tmp/stemwise-native-' + 'a' * 32

    def fixture(self, *, changed=False, interrupted=False, start_lost=False):
        commands = []
        original = OSError('original ADB failure')
        def stat(birth):
            fields = ['S', '1', '123'] + ['0'] * 16 + [birth] + ['0'] * 4
            return '123 (sh) ' + ' '.join(fields)
        def run(command, **kwargs):
            commands.append(command[-1])
            text = command[-1]
            if text.startswith('setsid sh ') and start_lost:
                raise original
            if text.startswith('if [ -f '):
                if interrupted:
                    raise original
                return 'DONE\r\n0\r\nactual output\r\n'
            if text.startswith('cat '):
                return '123\r\n' + stat('100') + '\r\n'
            if text.startswith('if [ -e '):
                return stat('101' if changed else '100') + '\r\nsh\0' + self.directory + '/guardian.sh\0'
            if text == 'ps -A -o PID,PGID,STAT':
                return 'PID PGID STAT\r\n123 123 Z\r\n'
            return ''
        return run, commands, original

    def test_crlf_reply_retires_descendants_before_returning_output(self):
        run, commands, _ = self.fixture()
        self.assertEqual('actual output\n', run_remote(['adb'], self.directory, ['probe'], run))
        self.assertIn('kill -KILL -- -123', commands)
        self.assertEqual('ps -A -o PID,PGID,STAT', commands[-1])

    def test_changed_birth_never_signals_a_reused_pid_or_reports_success(self):
        run, commands, _ = self.fixture(changed=True)
        with self.assertRaisesRegex(RuntimeError, 'ownership changed') as raised:
            run_remote(['adb'], self.directory, ['probe'], run)
        self.assertTrue(raised.exception.remote_cleanup_failed)
        self.assertFalse(any(command.startswith('kill ') for command in commands))

    def test_missing_guardian_still_requires_no_live_group_members(self):
        for descendants in (False, True):
            with self.subTest(descendants=descendants):
                original, commands, _ = self.fixture()
                def run(command, **kwargs):
                    if command[-1].startswith('if [ -e '):
                        commands.append(command[-1]); return 'ABSENT\n'
                    if command[-1] == 'ps -A -o PID,PGID,STAT':
                        commands.append(command[-1])
                        return 'PID PGID STAT\n' + ('124 123 S\n' if descendants else '124 123 Z\n')
                    return original(command, **kwargs)
                if descendants:
                    with self.assertRaisesRegex(RuntimeError, 'ownership|group') as raised:
                        run_remote(['adb'], self.directory, ['probe'], run)
                    self.assertTrue(raised.exception.remote_cleanup_failed)
                else:
                    self.assertEqual('actual output\n', run_remote(['adb'], self.directory, ['probe'], run))
                self.assertIn('ps -A -o PID,PGID,STAT', commands)
                self.assertFalse(any(command.startswith('kill ') for command in commands))

    def test_original_failure_survives_remote_cleanup(self):
        for where in ('progress', 'startup'):
            with self.subTest(where=where):
                run, commands, original = self.fixture(interrupted=where == 'progress', start_lost=where == 'startup')
                with self.assertRaises(OSError) as raised:
                    run_remote(['adb'], self.directory, ['probe'], run)
                self.assertIs(original, raised.exception)
                self.assertIn('kill -KILL -- -123', commands)

    @unittest.skipUnless(sys.platform.startswith('linux'), 'Requires Linux process-group ownership')
    def test_real_descendant_outliving_guardian_cannot_report_cleanup_success(self):
        suite = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory(dir=suite / 'work', prefix='remote-orphan-') as temporary:
            root = Path(temporary)
            leader = subprocess.Popen(['sh', '-c', 'sleep 30 & echo $! > child; '
                'while [ ! -f release ]; do sleep .01; done'], cwd=root,
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            try:
                receipt = Path(f'/proc/{leader.pid}/stat').read_text(encoding='utf-8')
                deadline = time.monotonic() + 5
                while not (root / 'child').exists():
                    if time.monotonic() >= deadline: raise TimeoutError('Child startup failed')
                    time.sleep(.01)
                (root / 'release').touch(); leader.wait(timeout=5)
                ordinary, commands, _ = self.fixture()
                def run(command, **kwargs):
                    text = command[-1]
                    if text.startswith('cat '): return str(leader.pid) + '\n' + receipt
                    if text.startswith('if [ -e '): return 'ABSENT\n'
                    if text == 'ps -A -o PID,PGID,STAT':
                        return subprocess.check_output(['ps', '-A', '-o', 'pid,pgid,stat'], text=True, timeout=2)
                    if text.startswith('kill '): self.fail('An absent guardian cannot authorize a group signal')
                    return ordinary(command, **kwargs)
                with self.assertRaisesRegex(RuntimeError, 'group remains') as raised:
                    run_remote(['adb'], self.directory, ['probe'], run)
                self.assertTrue(raised.exception.remote_cleanup_failed)
            finally:
                try: os.killpg(leader.pid, signal.SIGKILL)
                except ProcessLookupError: pass
                leader.wait(timeout=5)

    @unittest.skipUnless(sys.platform.startswith('linux'), 'Requires Linux process-group ownership')
    def test_real_guardian_never_publishes_a_partially_written_completion(self):
        suite = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory(dir=suite / 'work', prefix='remote-receipt-') as temporary:
            root = Path(temporary)
            remote = root / 'remote'
            remote.mkdir()
            guardian = None
            first_reply = None
            with (remote / 'guardian.log').open('wb') as log:
                def run(command, **kwargs):
                    nonlocal guardian, first_reply
                    text = command[-1].replace(self.directory, 'remote')
                    if text.startswith('setsid sh '):
                        guardian = subprocess.Popen(['sh', 'remote/guardian.sh'], cwd=root,
                            stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                            start_new_session=True)
                        return ''
                    if text == 'ps -A -o PID,PGID,STAT':
                        text = 'ps -A -o pid,pgid,stat'
                    paused_poll = text.startswith('if [ -f ') and first_reply is None
                    if paused_poll:
                        deadline = time.monotonic() + 5
                        while not (remote / 'receipt-open').exists():
                            if guardian.poll() is not None or time.monotonic() >= deadline:
                                raise TimeoutError('Guardian did not reach receipt publication')
                            time.sleep(.01)
                    result = subprocess.run(['bash', '-c', text], cwd=root, check=True,
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                        encoding='utf-8', timeout=kwargs['timeout']).stdout
                    if text.startswith('printf %s '):
                        script = remote / 'guardian.sh'
                        # Shell redirection opens the receipt before this function writes its code.
                        pause_writer = ('printf() {\n'
                            '  : > remote/receipt-open\n'
                            '  while [ ! -f remote/release-receipt ]; do sleep .01; done\n'
                            '  command printf "$@"\n'
                            '}\n')
                        script.write_text(pause_writer + script.read_text(encoding='utf-8'),
                            encoding='utf-8')
                    if paused_poll:
                        first_reply = result
                        (remote / 'release-receipt').touch()
                    # The local transport maps only the disposable device path, not PID/birth data.
                    if text.startswith('if [ -e /proc/'):
                        result = result.replace('remote/guardian.sh', self.directory + '/guardian.sh')
                    return result
                try:
                    self.assertEqual('actual output\n', run_remote(['adb'], self.directory,
                        ['sh', '-c', 'echo actual output'], run, timeout=5))
                    self.assertEqual('RUNNING\n', first_reply)
                    self.assertEqual('0\n', (remote / 'completion').read_text(encoding='utf-8'))
                finally:
                    if guardian is not None:
                        if guardian.poll() is None:
                            os.killpg(guardian.pid, signal.SIGKILL)
                        guardian.wait(timeout=5)

    def test_malformed_completion_remains_a_failure_and_retires_the_guardian(self):
        for reply in ('DONE\n', 'DONE\nactual output\n', 'DONE\n0'):
            with self.subTest(reply=reply):
                run, commands, _ = self.fixture()
                def malformed(command, **kwargs):
                    if command[-1].startswith('if [ -f '):
                        return reply
                    return run(command, **kwargs)
                with self.assertRaisesRegex(ValueError, 'Malformed remote completion'):
                    run_remote(['adb'], self.directory, ['probe'], malformed)
                self.assertIn('kill -KILL -- -123', commands)

    def test_foreign_directory_is_rejected_before_any_command(self):
        run, commands, _ = self.fixture()
        with self.assertRaises(ValueError):
            run_remote(['adb'], '/data/local/tmp/owner', ['probe'], run)
        self.assertEqual([], commands)

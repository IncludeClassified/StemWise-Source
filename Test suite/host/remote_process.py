"""Bounded disposable ADB commands, with a live guardian reserving group identity."""
from dataclasses import dataclass, field
import re
import shlex
import time


@dataclass(frozen=True)
class ProcessIdentity:
    pid: str
    birth: str
    group: str
    command: tuple[str, ...]
    state: str = field(compare=False)


def process_identity(text, pid, required_argument):
    text = text.replace('\r\n', '\n')
    if text.strip() == 'ABSENT':
        return None
    stat, separator, command = text.partition('\n')
    closing = stat.rfind(')')
    fields = stat[closing + 2:].split()
    tokens = tuple(command.rstrip('\r\n').split('\0'))
    if (not separator or closing < 0 or stat.split(' ', 1)[0] != pid or len(fields) <= 19
            or not fields[19].isdecimal() or required_argument not in tokens):
        raise ValueError('Remote process identity is malformed or belongs to another command')
    return ProcessIdentity(pid, fields[19], fields[2], tokens, fields[0])


def run_remote(adb, directory, command, run, *, timeout=30):
    if re.fullmatch(r'/data/local/tmp/stemwise-native-[0-9a-f]{32}', directory) is None:
        raise ValueError('Remote test directory is not a disposable owned namespace')
    run(adb + ['shell', 'command -v setsid'], timeout=10)
    guardian = directory + '/guardian.sh'
    script = ('echo $$ > ' + directory + '/pid\n'
              'cat /proc/$$/stat > ' + directory + '/birth\n'
              '( sleep ' + str(int(timeout) + 15) + '; kill -KILL -- -$$ ) &\n'
              + shlex.join(command) + ' > ' + directory + '/stdout 2>&1\n'
              'printf "%s\\n" "$?" > ' + directory + '/completion.tmp\n'
              'mv ' + directory + '/completion.tmp ' + directory + '/completion\nwait\n')
    launched = False
    failure = None
    output = None
    try:
        run(adb + ['shell', 'printf %s ' + shlex.quote(script) + ' > ' + guardian], timeout=10)
        launched = True
        run(adb + ['shell', 'setsid sh ' + guardian + ' </dev/null >' + directory + '/guardian.log 2>&1 &'], timeout=10)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            text = run(adb + ['shell', 'if [ -f ' + directory + '/completion ]; then '
                       'echo DONE; cat ' + directory + '/completion ' + directory + '/stdout; else echo RUNNING; fi'], timeout=2).replace('\r\n', '\n')
            if text.startswith('DONE\n'):
                code, separator, output = text[5:].partition('\n')
                if not separator or not code.isdecimal():
                    raise ValueError('Malformed remote completion')
                if int(code):
                    raise RuntimeError('Remote test exited ' + code + ': ' + output[-4000:])
                break
            if text.strip() != 'RUNNING':
                raise ValueError('Malformed remote progress reply')
            time.sleep(.05)
        else:
            raise TimeoutError('Remote test deadline exceeded')
    except BaseException as error:
        failure = error
    finally:
        if launched:
            try:
                receipt = run(adb + ['shell', 'cat ' + directory + '/pid ' + directory + '/birth'], timeout=5).replace('\r\n', '\n')
                pid, separator, original = receipt.partition('\n')
                if not separator or not pid.isdecimal():
                    raise ValueError('Remote guardian startup ownership is unknown')
                birth_fields = original[original.rfind(')') + 2:].split()
                if len(birth_fields) <= 19:
                    raise ValueError('Remote guardian birth receipt is malformed')
                script = ('if [ -e /proc/' + pid + '/stat ]; then cat /proc/' + pid +
                          '/stat; cat /proc/' + pid + '/cmdline; else echo ABSENT; fi')
                identity = process_identity(run(adb + ['shell', script], timeout=5), pid, guardian)
                if identity is not None:
                    if identity.birth != birth_fields[19] or identity.group != pid:
                        raise RuntimeError('Remote guardian ownership changed; files retained')
                    run(adb + ['shell', 'kill -KILL -- -' + pid], timeout=5)
                deadline = time.monotonic() + 5
                while True:
                    rows = run(adb + ['shell', 'ps -A -o PID,PGID,STAT'], timeout=2).splitlines()
                    if not rows or rows[0].split() != ['PID', 'PGID', 'STAT'] or any(
                            len(row.split()) != 3 or not all(value.isdecimal() for value in row.split()[:2]) for row in rows[1:]):
                        raise ValueError('Remote process group inventory is malformed; files retained')
                    active = [row for row in rows[1:] if row.split()[1] == pid and not row.split()[2].startswith('Z')]
                    if not active:
                        break
                    if identity is None:
                        raise RuntimeError('Remote guardian ownership is absent but its group remains; files retained')
                    if time.monotonic() >= deadline:
                        raise RuntimeError('Remote process group did not retire; files retained')
                    time.sleep(.05)
            except BaseException as error:
                if failure is None:
                    failure = error
                else:
                    failure.add_note('Remote cleanup failed: ' + str(error))
                failure.remote_cleanup_failed = True
    if failure is not None:
        raise failure
    return output

"""Keep a managed Python environment alive until its command tree retires."""
from contextlib import contextmanager
from contextvars import ContextVar
import json
import math
import os
from pathlib import Path
import stat
import sys
import time


BUILDER = Path(__file__).resolve().parents[1]
ENVIRONMENTS = ('main', 'allin1', 'lyrics')
_LEASES = ContextVar('runtime_environment_leases', default=())
_INHERITED = 'STEMWISE_ENVIRONMENT_LEASES'


def environment_path(name, builder=BUILDER):
    return environment_python(name, builder).parent.parent


def environment_python(name, builder=BUILDER):
    from stemwise_engine.runtime_layout import load_layout
    root = Path(builder).resolve().parent
    return root/load_layout(root)['environments'][name]


@contextmanager
def environment_lease(name, *, builder=BUILDER, exclusive=False, wait_seconds=60):
    import fcntl
    if not math.isfinite(wait_seconds) or wait_seconds < 0:
        raise ValueError('Environment lease wait must be finite and nonnegative')
    path = Path(builder).resolve()/f'.environment-use-{name}.lock'
    path.parent.mkdir(parents=True, exist_ok=True)
    held = {path: (descriptor, mode) for path, descriptor, mode in _LEASES.get()}
    if path in held and (not exclusive or held[path][1]):
        yield
        return
    descriptor = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    registered = False
    try:
        deadline = time.monotonic()+wait_seconds
        while True:
            try:
                fcntl.flock(descriptor, (fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH) | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() >= deadline:
                    raise TimeoutError(f'Environment {name} is still in use; current installation preserved')
                time.sleep(min(.05, max(0, deadline-time.monotonic())))
        _LEASES.set((*_LEASES.get(), (path, descriptor, exclusive)))
        registered = True
        yield
    finally:
        if registered:
            # Persistent workers can retire in a different order than admission.
            _LEASES.set(tuple(record for record in _LEASES.get() if record[1] != descriptor))
        # Descendants share the open file description; never explicitly unlock it.
        os.close(descriptor)


def _adopt_leases():
    import fcntl
    payload = os.environ.pop(_INHERITED, None)
    if payload is None:
        return
    records = json.loads(payload)
    if not isinstance(records, list) or len(records) > len(ENVIRONMENTS):
        raise ValueError('Invalid inherited environment leases')
    held = []
    for record in records:
        if (not isinstance(record, list) or len(record) != 3 or not isinstance(record[0], str)
                or not Path(record[0]).is_absolute() or type(record[1]) is not int or record[1] < 0
                or type(record[2]) is not bool):
            raise ValueError('Invalid inherited environment lease')
        path, descriptor, exclusive = Path(record[0]), record[1], record[2]
        actual, expected = os.fstat(descriptor), path.stat()
        if not stat.S_ISREG(actual.st_mode) or (actual.st_dev, actual.st_ino) != (expected.st_dev, expected.st_ino):
            raise ValueError('Inherited environment descriptor differs from its lock')
        fcntl.flock(descriptor, (fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH) | fcntl.LOCK_NB)
        held.append((path, descriptor, exclusive))
    _LEASES.set(tuple(held))


@contextmanager
def command_environment(args, kwargs, *, builder=BUILDER):
    # Preserve the lexical interpreter path: resolving its symlink loses the venv identity.
    command = Path(os.path.abspath(args[0]))
    name = None
    for route in ENVIRONMENTS:
        python = environment_python(route, builder)
        if command == python or (command.parent == python.parent and command.name.startswith('python')):
            name = route
            break
    from contextlib import nullcontext
    with environment_lease(name, builder=builder) if name is not None else nullcontext():
        held = _LEASES.get()
        if held:
            kwargs['pass_fds'] = tuple(sorted(set(kwargs.get('pass_fds', ())) | {fd for _, fd, _ in held}))
            environment = kwargs.get('env')
            kwargs['env'] = {**(os.environ if environment is None else environment),
                             _INHERITED: json.dumps([[str(path), fd, exclusive] for path, fd, exclusive in held])}
        yield kwargs


if sys.platform == 'linux' and __name__ != '__main__':
    # The CLI delegates through common, which imports this canonical module before spawning.
    _adopt_leases()

if __name__ == '__main__':
    from stemwise_engine.common import run_process_tree
    if len(sys.argv) < 2:
        raise SystemExit('A managed Python command is required')
    raise SystemExit(run_process_tree(sys.argv[1:], timeout=None).returncode)

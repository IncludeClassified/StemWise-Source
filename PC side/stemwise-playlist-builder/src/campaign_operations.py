"""Track active engine operations with inherited kernel-owned leases."""
from contextlib import ExitStack, contextmanager
from pathlib import Path
from itertools import count

from stemwise_engine.common import (ProcessLockBusyError, exclusive_process_lock,
                    process_lock_descriptor, validate_generated_path)


@contextmanager
def model_operation_slot(config):
    project = Path(config['projectDir'])
    if not project.is_absolute():
        raise ValueError('Model operation requires an absolute projectDir')
    root = validate_generated_path(project, project / 'runtime/model-operations',
                                   workspace_root=project.parent.parent)
    for path in root.glob('[0-9]*.lock'):
        if process_lock_descriptor(path) is not None:
            yield
            return
    # Reset owns the allocation gate across its scan. Numerical workers separately
    # arbitrate CPU/GPU capacity; a cached request needs only its live-work lease.
    with ExitStack() as lease:
        with exclusive_process_lock(root / 'allocation.lock', wait_seconds=2):
            for index in count():
                try:
                    lease.enter_context(exclusive_process_lock(root / f'{index}.lock', wait_seconds=0))
                except ProcessLockBusyError:
                    continue
                break
        yield

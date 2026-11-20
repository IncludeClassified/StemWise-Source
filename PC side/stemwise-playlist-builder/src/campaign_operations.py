"""Bound outstanding engine operations with inherited kernel-owned slots."""
from contextlib import ExitStack, contextmanager
from pathlib import Path

from stemwise_engine.common import (ProcessLockBusyError, exclusive_process_lock,
                    process_lock_descriptor, validate_generated_path)

MAX_MODEL_OPERATIONS = 3


class ModelOperationBusyError(RuntimeError):
    pass


@contextmanager
def model_operation_slot(config):
    project = Path(config['projectDir'])
    if not project.is_absolute():
        raise ValueError('Model operation requires an absolute projectDir')
    root = validate_generated_path(project, project / 'runtime/model-operations',
                                   workspace_root=project.parent.parent)
    paths = [root / f'{index}.lock' for index in range(MAX_MODEL_OPERATIONS)]
    for path in paths:
        if process_lock_descriptor(path) is not None:
            yield
            return
    with ExitStack() as lease:
        for path in paths:
            try:
                lease.enter_context(exclusive_process_lock(path, wait_seconds=0))
            except ProcessLockBusyError:
                continue
            yield
            return
        raise ModelOperationBusyError('model-operation-capacity-full')

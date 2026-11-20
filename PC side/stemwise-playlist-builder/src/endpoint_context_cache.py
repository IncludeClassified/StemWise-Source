"""Atomically retain completed endpoint contexts under their exact producer and inputs."""
import hashlib
from copy import deepcopy
from stemwise_engine.common import atomic_write_bytes, json_bytes, read_bounded_json, sha256_file, validate_generated_path


def context_binding(scratch, model):
    from stemwise_engine.endpoint_vocal_evidence import ROUTES
    prepared = read_bounded_json(scratch / 'prepared.json', max_bytes=1024*1024, root=scratch)
    if set(prepared.get('views', {})) != set(ROUTES):
        raise ValueError('Endpoint context vocal views are incomplete')
    for record in prepared['views'].values():
        path = validate_generated_path(scratch, scratch / record['path'])
        identity = record['provenance']
        if (path.stat().st_size <= 0 or path.stat().st_size != identity['byteCount']
                or sha256_file(path) != identity['sha256']):
            raise ValueError('Endpoint context vocal view is missing or changed')
    return {'model': deepcopy(model), 'inputs': {name: sha256_file(scratch / name)
        for name in ('request.private.json', 'prepared.json', 'source.wav')}}


def read_context_result(scratch, model, *, binding=None):
    try:
        name = 'inference-' + hashlib.sha256(json_bytes(model)).hexdigest() + '.json'
        packet = read_bounded_json(scratch / name, max_bytes=16*1024*1024, root=scratch)
        if packet['binding'] == (binding if binding is not None else context_binding(scratch, model)):
            return packet['result']
    except (OSError, ValueError, KeyError, TypeError):
        pass
    return None


def write_context_result(scratch, model, result, *, binding=None):
    current = context_binding(scratch, model)
    if binding is not None and current != binding:
        raise ValueError('Endpoint context inputs changed during inference')
    if read_context_result(scratch, model, binding=current) is None:
        name = 'inference-' + hashlib.sha256(json_bytes(model)).hexdigest() + '.json'
        atomic_write_bytes(scratch / name, json_bytes({'binding': current, 'result': result}), root=scratch)

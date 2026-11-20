"""Shared executable language-routing metadata, independent of model runtimes."""
import json
from pathlib import Path
import re
import copy
import hashlib
from dataclasses import dataclass
from stemwise_engine.common import read_bounded_json

_registry_path = Path(__file__).with_name('model-route-registry.json')
_registry_sha256 = hashlib.sha256(_registry_path.read_bytes()).hexdigest()
_registry = read_bounded_json(_registry_path, max_bytes=1024*1024)
if _registry.get('formatVersion') != 1 or not isinstance(_registry.get('languages'), dict):
    raise ValueError('Unsupported model route registry')
LANGUAGES = _registry['languages']


@dataclass(frozen=True)
class ExecutionPolicy:
    version: int
    predictionMaxVariants: int
    predictionMaxCandidates: int

    def __post_init__(self):
        if (any(type(value) is not int or value <= 0 for value in self.__dict__.values())
                or self.version != 1):
            raise ValueError('Invalid ordinary-song execution policy')


def execution_policy():
    require_registry_current()
    value = _registry.get('executionPolicy')
    if not isinstance(value, dict):
        raise ValueError('Ordinary-song execution policy is unavailable')
    return ExecutionPolicy(**value)


def declared_script_owners(components):
    owners = {}
    for language in components:
        profile = LANGUAGES.get(language)
        if profile is None:
            return None
        for script in profile['editorial']['scripts']:
            if script not in {'Zyyy', 'Zzzz'}:
                owners.setdefault(script, set()).add(language)
    return owners


def require_registry_current():
    if hashlib.sha256(_registry_path.read_bytes()).hexdigest() != _registry_sha256:
        raise ValueError('Model registry changed during this operation; start a fresh process')


def validate_asset_declarations(value):
    if not isinstance(value, dict) or not value:
        raise ValueError('Current model assets are missing')
    def check(item, key=''):
        if isinstance(item, dict):
            for name, content in item.items():
                check(content, name)
        elif isinstance(item, list):
            if len(item) == 2 and type(item[0]) is int:
                if item[0] <= 0 or not isinstance(item[1], str) or re.fullmatch('[0-9a-f]{64}', item[1]) is None:
                    raise ValueError('Model file size/digest is invalid: '+key)
            else:
                for content in item:
                    check(content, key)
        elif key.endswith(('_SHA256', 'Sha256')) or key == 'sha256' or key.endswith(('.json', '.bin', '.safetensors', '.pth')):
            if not isinstance(item, str) or re.fullmatch('[0-9a-f]{64}', item) is None:
                raise ValueError('Model asset digest is invalid: '+key)
        elif key.endswith(('_BYTE_COUNT', 'ByteCount')) or key == 'byteCount':
            if type(item) is not int or item <= 0:
                raise ValueError('Model asset byte count is invalid: '+key)
        elif key == 'gitBlobSha1':
            if not isinstance(item, str) or re.fullmatch('[0-9a-f]{40}', item) is None:
                raise ValueError('Model Git blob identity is invalid')
        elif key in {'PYTHON_VERSION', 'python'}:
            if not isinstance(item, str) or re.fullmatch(r'\d+\.\d+\.\d+', item) is None:
                raise ValueError('Execution profile Python version is invalid')
    check(value)


validate_asset_declarations(_registry.get('assets'))
validate_asset_declarations(_registry.get('runtimeProfiles'))
for _profile in _registry['runtimeProfiles'].values():
    if not isinstance(_profile, dict) or not isinstance(_profile.get('profileId'), str) or not _profile['profileId']:
        raise ValueError('Execution profile identity is missing')


def model_assets(component):
    require_registry_current()
    value = _registry.get('assets', {}).get(component)
    if not isinstance(value, dict):
        raise ValueError('Unknown current model asset declaration: '+str(component))
    def restore(item):
        if isinstance(item, dict):
            return {key: restore(value) for key, value in item.items()}
        if isinstance(item, list):
            if len(item) == 2 and type(item[0]) is int and isinstance(item[1], str) and re.fullmatch('[0-9a-f]{64}', item[1]):
                return tuple(item)
            return [restore(value) for value in item]
        return item
    return restore(value)


def runtime_profile(name):
    require_registry_current()
    value = _registry.get('runtimeProfiles', {}).get(name)
    if not isinstance(value, dict) or not isinstance(value.get('profileId'), str):
        raise ValueError('Unsupported current execution profile: '+str(name))
    return copy.deepcopy(value)


def validate_cuda_device(value, profile):
    requirements = runtime_profile(profile)
    if (not isinstance(value, dict) or set(value) != {'name', 'capability', 'totalMemoryBytes'}
            or not isinstance(value['name'], str) or not value['name'].strip()
            or not isinstance(value['capability'], list) or len(value['capability']) != 2
            or any(type(part) is not int or part < 0 for part in value['capability'])
            or type(value['totalMemoryBytes']) is not int):
        raise ValueError('CUDA device identity is malformed')
    major, minor = value['capability']
    assets = model_assets(profile)
    minimum_memory = assets.get('CHECKPOINT_BYTE_COUNT', assets.get('MODEL_BYTE_COUNT'))
    if (minor > 9 or major * 10 + minor < requirements['CUDA_MIN_CAPABILITY']
            or value['totalMemoryBytes'] < minimum_memory):
        raise ValueError('CUDA device does not meet model capability/resource requirements')
    return value


def cuda_device_identity(profile, *, torch_module=None):
    if torch_module is None:
        import torch as torch_module
    if not torch_module.cuda.is_available():
        raise RuntimeError('CUDA device is unavailable')
    properties = torch_module.cuda.get_device_properties(0)
    identity = validate_cuda_device({
        'name': torch_module.cuda.get_device_name(0),
        'capability': list(torch_module.cuda.get_device_capability(0)),
        'totalMemoryBytes': properties.total_memory,
    }, profile)
    major, minor = identity['capability']
    compatible = False
    for architecture in torch_module.cuda.get_arch_list():
        match = re.fullmatch(r'(sm|compute)_(\d+)(\d)', architecture)
        if match:
            target = (int(match[2]), int(match[3]))
            compatible |= ((target[0] == major and target[1] <= minor) if match[1] == 'sm'
                           else target <= (major, minor))
    if not compatible:
        raise RuntimeError('CUDA device is unsupported by the pinned Torch binary')
    return identity


for _language, _entry in LANGUAGES.items():
    if (not isinstance(_language, str) or re.fullmatch('[a-z]{2,3}', _language) is None
            or not isinstance(_entry, dict) or set(_entry) - {'editorial', 'nativeAsr', 'owsmEndpointRoute'}
            or not isinstance(_entry.get('editorial'), dict)
            or not isinstance(_entry['editorial'].get('scripts'), list)
            or not _entry['editorial']['scripts']
            or any(not isinstance(script, str) or re.fullmatch('[A-Z][a-z]{3}', script) is None
                   for script in _entry['editorial']['scripts'])):
        raise ValueError('Model route lacks an editorial language profile')
    if 'nativeAsr' in _entry:
        _route = _entry['nativeAsr']
        if (not isinstance(_route, dict) or set(_route) != {'adapter', 'scripts'}
                or not isinstance(_route['adapter'], str)
                or re.fullmatch('[a-z][a-z_-]{1,39}', _route['adapter']) is None
                or not isinstance(_route['scripts'], list) or not _route['scripts']
                or not set(_route['scripts']) <= set(_entry['editorial']['scripts']) | {'Zyyy'}):
            raise ValueError('Native recognition route differs from its editorial profile')
    if 'owsmEndpointRoute' in _entry and (not isinstance(_entry['owsmEndpointRoute'], str)
            or re.fullmatch('owsm-ctc-[a-z0-9-]{1,80}', _entry['owsmEndpointRoute']) is None):
        raise ValueError('Endpoint route identity is invalid')


def validate_runtime_views(*, profiles, native_assets, owsm_routes):
    """Reject drift in generated executable language and asset projections."""
    adapters = {entry['nativeAsr']['adapter'] for entry in LANGUAGES.values() if 'nativeAsr' in entry}
    routes = {language: entry['owsmEndpointRoute'] for language, entry in LANGUAGES.items()
              if 'owsmEndpointRoute' in entry}
    declared = model_assets('mms_asr')['ASSETS_BY_ADAPTER']
    if (set(profiles) != set(LANGUAGES) or set(native_assets) != adapters
            or native_assets != declared or owsm_routes != routes):
        raise ValueError('Current model language, asset or route projection differs')
    return {'editorialLanguages':sorted(profiles),'nativeAdapters':sorted(adapters),'endpointRoutes':dict(sorted(routes.items()))}


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--profile')
    group.add_argument('--asset')
    parser.add_argument('field')
    arguments = parser.parse_args()
    record = runtime_profile(arguments.profile) if arguments.profile else model_assets(arguments.asset)
    if arguments.field not in record:
        parser.error('Current declaration has no field '+arguments.field)
    value = record[arguments.field]
    print(value if isinstance(value, str) else json.dumps(value, sort_keys=True))

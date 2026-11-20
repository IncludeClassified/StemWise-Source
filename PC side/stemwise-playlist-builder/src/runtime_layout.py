"""The shared bootstrap/launcher layout; inventory never installs or runs models."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import re


def load_layout(pc_root):
    from stemwise_engine.model_registry import runtime_profile
    root = Path(pc_root).resolve()
    value = json.loads((root/'runtime-layout.json').read_text(encoding='utf-8'))
    if value.get('schemaVersion') != 1 or value.get('platform') != 'linux-x86_64':
        raise ValueError('Unsupported runtime layout')
    if not isinstance(value.get('wslDistribution'), str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_. -]*', value['wslDistribution']):
        raise ValueError('Runtime WSL distribution is invalid')
    if value.get('python', {}).get('version') != runtime_profile('main')['python']:
        raise ValueError('Runtime layout differs from the current execution profile; regenerate its projection')
    external = value.get('externalInputs')
    if not isinstance(external, list) or any(not isinstance(item, dict) or
            not isinstance(item.get('required'), bool) or not isinstance(item.get('purpose'), str)
            for item in external):
        raise ValueError('External inputs require a path, Boolean required flag and purpose')
    variables = value.get('environmentVariables', {})
    if not isinstance(variables, dict) or any(not re.fullmatch(r'[A-Z][A-Z0-9_]*', name) for name in variables):
        raise ValueError('Runtime environment variables are invalid')
    paths = [value['python']['executable'],value['python']['managedInstallDirectory'], *variables.values(),
             *value['environments'].values(),*(item['path'] for item in value['retainedResources']),
             *(item.get('path') for item in external)]
    for relative in paths:
        if not isinstance(relative,str) or not re.fullmatch(r'[A-Za-z0-9_.\-/]+', relative) or PurePosixPath(relative).is_absolute() or '..' in PurePosixPath(relative).parts:
            raise ValueError('Runtime path must remain relative to PC side')
    for item in external:
        if not (root/item['path']).resolve().is_relative_to(root):
            raise ValueError('External input resolves outside PC side')
    return value


def inventory(pc_root):
    root = Path(pc_root).resolve()
    layout = load_layout(root)
    records = []
    for name,relative in {'bootstrap':layout['python']['executable'],**layout['environments']}.items():
        path = root/relative
        record = {'route':name,'path':relative,'ready':False}
        if path.is_file():
            try:
                result = subprocess.run([str(path),'--version'],capture_output=True,text=True,timeout=15)
                record.update(version=result.stdout.strip(),ready=result.returncode == 0 and
                              result.stdout.strip() == 'Python '+layout['python']['version'])
                if not record['ready']:
                    record['reason'] = 'required-interpreter-version-or-execution-failed'
            except (OSError, subprocess.TimeoutExpired):
                record['reason'] = 'required-interpreter-unavailable-or-timeout'
        else:
            record['reason'] = 'required-interpreter-missing'
        records.append(record)
    for resource in layout['retainedResources']:
        path = root/resource['path']
        ready = False
        if path.is_file():
            digest = hashlib.sha256()
            with path.open('rb') as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(chunk)
            ready = digest.hexdigest() == resource['sha256']
        records.append({'resource':resource['path'],'ready':ready,
                        'reason':None if ready else 'required-retained-artifact-missing-or-changed'})
    external = [{'input':item['path'],'required':item['required'],'available':(root/item['path']).exists(),
                 'purpose':item['purpose']} for item in layout.get('externalInputs', [])]
    return {'schemaVersion':1,'ready':all(record['ready'] for record in records) and
            all(not item['required'] or item['available'] for item in external),'routes':records,
            'externalInputs':external,
            'modelReadiness':'Run the existing doctor for the selected numerical routes; no model inference was performed'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pc-root',type=Path,default=Path(__file__).resolve().parents[2])
    parser.add_argument('--python-field', choices=('executable', 'managedInstallDirectory'))
    parser.add_argument('--environment', choices=('main', 'allin1', 'lyrics'))
    parser.add_argument('--environment-variable')
    args = parser.parse_args()
    if args.python_field:
        print(load_layout(args.pc_root)['python'][args.python_field])
        return 0
    if args.environment:
        print(load_layout(args.pc_root)['environments'][args.environment])
        return 0
    if args.environment_variable:
        print(load_layout(args.pc_root)['environmentVariables'][args.environment_variable])
        return 0
    result = inventory(args.pc_root)
    print(json.dumps(result))
    return 0 if result['ready'] else 2


if __name__ == '__main__':
    raise SystemExit(main())

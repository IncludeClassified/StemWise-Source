"""Read test routing without importing tests or initializing a model runtime."""
import argparse
import ast
import json
from pathlib import Path
import re

PC = Path(__file__).resolve().parent
RUNTIMES = {'base': 'main', 'lyrics': 'lyrics', 'analysis': 'allin1', 'windows': 'windows'}


def method_inventory(path, *, batched=False):
    tree = ast.parse(path.read_text(encoding='utf-8-sig'), filename=str(path))
    owners = [owner for owner in tree.body if isinstance(owner, ast.ClassDef)]
    if batched:
        direct_case = ast.dump(ast.parse('unittest.TestCase', mode='eval').body)
        if (any(len(owner.bases) != 1 or ast.dump(owner.bases[0]) != direct_case
                or owner.keywords or owner.decorator_list for owner in owners)
                or any(isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
                       and node.name == 'load_tests' for node in tree.body)):
            raise ValueError(f'Batched PC module requires flat unittest.TestCase classes: {path.stem}')
    methods = [f'{owner.name}.{method.name}' for owner in owners
               for method in owner.body if isinstance(method, (ast.FunctionDef, ast.AsyncFunctionDef))
               and method.name.startswith('test_')]
    if len(methods) != len(set(methods)) or not methods:
        raise ValueError(f'Empty or duplicate PC test inventory: {path.stem}')
    return methods


def classifications(pc=PC):
    config = json.loads((pc / 'test-runtimes.json').read_text(encoding='utf-8'))
    if (not isinstance(config, dict) or type(config.get('version')) is not int
            or config['version'] != 1 or not isinstance(config.get('modules'), dict)):
        raise ValueError('Unsupported PC test classification')
    files = {f'{directory}/{path.stem}': path for directory in ('unit', 'alignment')
             for path in (pc / directory).glob('test_*.py')}
    rules = config['modules']
    if set(files) != set(rules):
        raise ValueError(f'PC modules require explicit runtime classification: {sorted(set(files) ^ set(rules))}')
    names = [path.stem for path in files.values()]
    if len(names) != len(set(names)):
        raise ValueError('Ambiguous PC test module names')
    for module, path in files.items():
        rule = rules[module]
        if 'batchSize' in rule and (type(rule['batchSize']) is not int or rule['batchSize'] <= 0):
            raise ValueError(f'Invalid PC batch size: {module}')
        methods = method_inventory(path, batched='batchSize' in rule)
        if rule.get('runtime') not in RUNTIMES:
            raise ValueError(f'Unknown PC runtime: {module}')
        cpu = rule.get('cpu')
        if rule['runtime'] == 'windows' and cpu != 'none':
            raise ValueError(f'Windows checks cannot enter the portable CPU selection: {module}')
        if isinstance(cpu, list):
            declared = cpu + rule.get('runtimeOnly', [])
            if len(declared) != len(set(declared)) or set(declared) != set(methods):
                raise ValueError(f'Mixed PC module requires every method classified: {module}')
        elif cpu not in ('all', 'none') or 'runtimeOnly' in rule:
            raise ValueError(f'Invalid CPU classification: {module}')
    return rules


def cpu_selections(pc=PC):
    return [module.split('/')[1] + ('.' + method if method else '')
            for module, rule in classifications(pc).items()
            for method in ([''] if rule['cpu'] == 'all' else rule['cpu'] if isinstance(rule['cpu'], list) else [])]


def plan(selections=(), pc=PC, *, per_module=False):
    rules = classifications(pc)
    by_name = {module.split('/')[1]: module for module in rules}
    groups = []
    requested = list(dict.fromkeys(selections or by_name))
    for selection in requested:
        if not re.fullmatch(r'test_[A-Za-z0-9_]+(?:\.[A-Za-z_][A-Za-z0-9_]*)*', selection):
            raise ValueError(f'Invalid PC test selector: {selection}')
        key = by_name.get(selection.split('.')[0])
        if key is None:
            raise ValueError(f'Unknown PC test module: {selection}')
        if any(selection.startswith(other + '.') for other in requested if other != selection):
            continue
        directory = key.split('/')[0]
        runtime = RUNTIMES[rules[key]['runtime']]
        group = next((g for g in groups if (g['directory'], g['runtime']) == (directory, runtime)), None)
        if group is None:
            group = {'directory': directory, 'runtime': runtime, 'selections': []}
            groups.append(group)
        group['selections'].append(selection)
    if not per_module:
        return groups
    modules = []
    for group in groups:
        for selection in group['selections']:
            key = by_name[selection.split('.')[0]]
            batch_size = rules[key].get('batchSize') if '.' not in selection else None
            selected = ([selection + '.' + method for method in method_inventory(pc / (key + '.py'), batched=True)]
                        if batch_size is not None else [selection])
            size = batch_size or len(selected)
            modules.extend({**group, 'selections': selected[start:start + size]}
                           for start in range(0, len(selected), size))
    return modules


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--per-module', action='store_true')
    parser.add_argument('selections', nargs='*')
    args = parser.parse_args()
    print(json.dumps(plan(args.selections, per_module=args.per_module), ensure_ascii=False))

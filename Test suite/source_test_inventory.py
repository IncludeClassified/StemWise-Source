"""Small fail-closed JUnit source reader; executed JUnit results remain independent."""
from pathlib import Path
import re
from functools import lru_cache

_TOKEN = re.compile(r'[A-Za-z_$][\w$]*|[^\s]')


MANAGED_AUDIO = 'app.stemwise.qualification.ManagedAudioTest'
OPTIONAL_ACOUSTIC = 'app.stemwise.qualification.OptionalAcousticTest'


def is_managed_audio_test(owner: str, method: str, source: str) -> bool:
    """Explicit class/method membership shared with device audio isolation."""
    return f'{owner}#{method}' in _source_inventory(source)[1]


def is_optional_acoustic_test(owner: str, method: str, source: str) -> bool:
    return f'{owner}#{method}' in _source_inventory(source)[2]


def _tokens(text):
    result = []
    index = 0
    while index < len(text):
        if text[index].isspace():
            index += 1
        elif text.startswith('//', index):
            end = text.find('\n', index)
            index = len(text) if end < 0 else end
        elif text.startswith('/*', index):
            depth = 1
            index += 2
            while depth and index < len(text):
                if text.startswith('/*', index):
                    depth += 1
                    index += 2
                elif text.startswith('*/', index):
                    depth -= 1
                    index += 2
                else:
                    index += 1
            if depth:
                raise ValueError('Unterminated source comment')
        elif text[index] in ('"', "'"):
            quote = '"""' if text.startswith('"""', index) else text[index]
            index += len(quote)
            while index < len(text) and not text.startswith(quote, index):
                index += 2 if text[index] == '\\' and len(quote) == 1 else 1
            if index >= len(text):
                raise ValueError('Unterminated source string')
            index += len(quote)
        elif text[index] == '`':
            end = text.find('`', index + 1)
            if end < 0:
                raise ValueError('Unterminated Kotlin identifier')
            result.append(text[index:end + 1])
            index = end + 1
        else:
            match = _TOKEN.match(text, index)
            result.append(match.group())
            index = match.end()
    return result


def _qualified(tokens, index):
    parts = [tokens[index]]
    index += 1
    while index + 1 < len(tokens) and tokens[index] == '.':
        parts.append(tokens[index + 1])
        index += 2
    return '.'.join(parts), index


def _annotation_groups(tokens, aliases, package):
    groups = {}
    index = 0
    while index < len(tokens):
        if tokens[index] != '@':
            index += 1
            continue
        start = index
        annotations = []
        while index < len(tokens) and tokens[index] == '@':
            annotation, index = _qualified(tokens, index + 1)
            annotations.append(aliases.get(annotation, annotation if '.' in annotation else f'{package}.{annotation}'))
            if index < len(tokens) and tokens[index] == '(':
                depth = 1
                index += 1
                while index < len(tokens) and depth:
                    depth += (tokens[index] == '(') - (tokens[index] == ')')
                    index += 1
                if depth:
                    raise ValueError('Unbalanced source annotation')
        for annotation in ('org.junit.Test', MANAGED_AUDIO, OPTIONAL_ACOUSTIC):
            if annotations.count(annotation) > 1:
                raise ValueError('Duplicate test membership annotation: ' + annotation)
        groups[start] = (set(annotations), index)
    return groups


@lru_cache(maxsize=256)
def _source_inventory(source):
    tokens = _tokens(source)
    package = None
    aliases = {'Test': 'org.junit.Test'}
    for index, token in enumerate(tokens[:-1]):
        if token == 'package':
            package, _ = _qualified(tokens, index + 1)
        elif token == 'import':
            imported, end = _qualified(tokens, index + 1)
            alias = tokens[end + 1] if tokens[end:end + 1] == ['as'] else imported.rsplit('.', 1)[-1]
            aliases[alias] = imported
    groups = _annotation_groups(tokens, aliases, package)
    class_annotations = {}
    modifiers = {'public', 'private', 'internal', 'protected', 'abstract', 'open', 'final', 'data',
                 'sealed', 'enum', 'annotation', 'expect', 'actual'}
    for annotations, cursor in groups.values():
        while cursor < len(tokens) and tokens[cursor] in modifiers:
            cursor += 1
        if cursor < len(tokens) and tokens[cursor] == 'class':
            class_annotations[cursor] = annotations

    class_braces = {}
    for index, token in enumerate(tokens[:-1]):
        if token != 'class' or (index and tokens[index - 1] in (':', '.')):
            continue
        name = tokens[index + 1]
        if not re.fullmatch(r'[A-Za-z_$][\w$]*', name):
            continue
        cursor = index + 2
        while cursor < len(tokens) and tokens[cursor] not in ('{', '}', ';', 'class'):
            cursor += 1
        if cursor < len(tokens) and tokens[cursor] == '{':
            class_braces[cursor] = (name, class_annotations.get(index, set()))

    result, managed, optional = {}, set(), set()
    scopes = []
    for index, token in enumerate(tokens):
        if token == '{':
            scopes.append(class_braces.get(index))
        elif token == '}':
            if not scopes:
                raise ValueError('Unbalanced source braces')
            scopes.pop()
        elif index in groups:
            annotations, cursor = groups[index]
            if 'org.junit.Test' not in annotations:
                continue
            owners = [value for value in scopes if value is not None]
            if len(owners) > 1:
                raise ValueError('Unsupported nested JUnit test class')
            if package is None or len(owners) != 1:
                raise ValueError('Cannot bind JUnit test to its package/class')
            declaration = cursor
            while cursor < len(tokens) and tokens[cursor] not in ('(', '{', '}', ';'):
                cursor += 1
            if cursor >= len(tokens) or tokens[cursor] != '(' or cursor == declaration:
                raise ValueError('Unsupported JUnit method declaration')
            name = tokens[cursor - 1].strip('`')
            if 'fun' not in tokens[declaration:cursor] and not any(
                    value in tokens[declaration:cursor] for value in ('void', 'public', 'protected', 'private')):
                raise ValueError('Unsupported JUnit declaration')
            if not name or '#' in name:
                raise ValueError('Unsupported JUnit method identity')
            owner = f'{package}.{owners[0][0]}'
            result.setdefault(owner, []).append(name)
            membership = annotations | owners[0][1]
            identity = owner + '#' + name
            if MANAGED_AUDIO in membership:
                managed.add(identity)
            if OPTIONAL_ACOUSTIC in membership:
                if MANAGED_AUDIO not in membership:
                    raise ValueError('Optional acoustic test must declare managed audio membership')
                optional.add(identity)
    if scopes:
        raise ValueError('Unbalanced source braces')
    return result, frozenset(managed), frozenset(optional)


def source_tests(path: Path):
    try:
        return _source_inventory(path.read_text(encoding='utf-8'))[0]
    except ValueError as error:
        raise ValueError(f'{error}: {path}') from error

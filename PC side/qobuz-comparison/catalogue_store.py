"""Short locked catalogue commits merge independent rows and reject owner conflicts."""
import copy
from contextlib import contextmanager
from pathlib import Path

from stemwise_engine.common import atomic_write_bytes, exclusive_process_lock, json_bytes, read_bounded_json

MAX_BYTES = 16*1024*1024


def _write_catalogue(path, value):
    encoded = json_bytes(value)
    if len(encoded) > MAX_BYTES:
        raise ValueError('Catalogue exceeds its readable size limit')
    atomic_write_bytes(path, encoded)


class Catalogue(dict):
    def __init__(self, value):
        super().__init__(value)
        self.baseline = copy.deepcopy(value)


def read_catalogue(path):
    value = read_bounded_json(path, max_bytes=MAX_BYTES, root=path.parent)
    _rows(value)
    return Catalogue(value)


def _rows(value):
    if not isinstance(value, dict) or not isinstance(value.get('rows'), list):
        raise ValueError('Catalogue must contain rows')
    rows = {}
    for row in value['rows']:
        if not isinstance(row, dict) or type(row.get('position')) is not int or row['position'] in rows:
            raise ValueError('Catalogue row identity is invalid or duplicated')
        rows[row['position']] = row
    return rows


@contextmanager
def catalogue_transaction(path):
    with exclusive_process_lock(path.with_name(path.name+'.lock'), wait_seconds=30), \
            exclusive_process_lock(path.parent/'comparison-results/delivery-selection.lock', wait_seconds=30):
        yield


def commit_catalogue(path, proposed):
    with catalogue_transaction(path):
        return _commit_catalogue_locked(path, proposed)


def _commit_catalogue_locked(path, proposed):
    if not path.exists():
        _rows(proposed)
        _write_catalogue(path, proposed)
        merged = dict(proposed)
    else:
        if not isinstance(proposed, Catalogue):
            raise ValueError('Catalogue mutation requires a loaded baseline')
        current = read_catalogue(path)
        original, desired, latest = map(_rows, (proposed.baseline, proposed, current))
        if original.keys() != desired.keys() or original.keys() != latest.keys():
            raise ValueError('Catalogue membership changed; reload before mutation')
        changes = {key: row for key, row in desired.items() if row != original[key]}
        for key, row in changes.items():
            if latest[key] != original[key] and latest[key] != row:
                raise ValueError(f'Catalogue row {key} changed concurrently; owner edit preserved')
            latest[key] = row
        merged = dict(current)
        absent = object()
        for key in proposed.keys() | proposed.baseline.keys():
            if key == 'rows' or proposed.get(key, absent) == proposed.baseline.get(key, absent):
                continue
            if current.get(key, absent) != proposed.baseline.get(key, absent) and current.get(key, absent) != proposed.get(key, absent):
                raise ValueError('Catalogue metadata changed concurrently; owner edit preserved')
            if key in proposed:
                merged[key] = proposed[key]
            else:
                merged.pop(key, None)
        merged['rows'] = [latest[row['position']] for row in current['rows']]
        _write_catalogue(path, merged)
    # Keep row references used by a running comparison attached to the committed snapshot.
    rows = _rows(proposed)
    for row in merged['rows']:
        if rows[row['position']] is not row:
            rows[row['position']].clear()
            rows[row['position']].update(copy.deepcopy(row))
    merged['rows'] = [rows[row['position']] for row in merged['rows']]
    proposed.clear()
    proposed.update(merged)
    if isinstance(proposed, Catalogue):
        proposed.baseline = copy.deepcopy(merged)
        return proposed
    return Catalogue(merged)

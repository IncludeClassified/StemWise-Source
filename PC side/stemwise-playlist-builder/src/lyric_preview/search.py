"""Original-clock crop plan used by the frozen English timing observation collector."""
import json
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[2]
CONTEXT_MARGIN_MS = 1500


def read(path):
    return json.loads(path.read_bytes())


def contexts(base, size, stride, total_ms):
    last = max(0,len(base)-size)
    for left in sorted(set(range(0,last+1,stride)) | {last}):
        right = min(len(base),left+size)
        first,end = base[left][0],base[right-1][1]
        start = (max(0,first-CONTEXT_MARGIN_MS) if left==0 else
                 max(first-CONTEXT_MARGIN_MS,min(first,(first+base[left-1][1])//2)))
        stop = (min(total_ms,end+CONTEXT_MARGIN_MS) if right==len(base) else
                min(end+CONTEXT_MARGIN_MS,max(end,(end+base[right][0])//2)))
        if not 0 <= start < stop <= total_ms:
            raise ValueError('Invalid physical context')
        yield [left,right,start,stop]


def supplied_contexts(base, total_ms):
    """Keep nine-word context where it fits, then cover gaps with shorter windows."""
    count = len(base)
    covered = set()
    for size, stride in ((9, 3), (7, 1), (5, 1), (3, 1)):
        for plan in contexts(base, size, stride, total_ms):
            left, right, start, end = plan
            # The collector discards internal crop-edge words as censored.
            interior = range(left + int(left > 0), right - int(right < count))
            if end - start <= 30000 and (size == 9 or any(i not in covered for i in interior)):
                covered.update(interior)
                yield plan

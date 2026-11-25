import numpy as np
from .search import CONTEXT_MARGIN_MS


BAR = 100  # milliseconds on the recording clock
FRAME = 20


def _grid(points, old, duration):
    first = (max(0, min(points) - BAR) + FRAME - 1) // FRAME * FRAME
    last = min(duration, max(points) + BAR)
    return np.unique(np.r_[old, np.arange(first, last + 1, FRAME, dtype=int)])


def _mass(cdf, times, offset):
    lo = -np.floor_divide(-(times - offset - BAR), FRAME)
    hi = np.floor_divide(times - offset + BAR, FRAME) + 1
    return cdf[np.clip(hi, 0, len(cdf)-1)] - cdf[np.clip(lo, 0, len(cdf)-1)]


def _observations(packet, view, meta, *, padded_edges=False):
    count = len(meta['base'])
    rows = [[] for _ in range(count)]
    points = [set() for _ in range(count)]
    for slot, (left, right, start, end) in enumerate(meta['plans']):
        if not (0 <= left < right <= count and right-left <= 9 and 0 <= start < end <= meta['frames']//16):
            raise ValueError('Invalid word owners or crop bounds')
        owners = range(left + int(left > 0), right - int(right < count))
        if padded_edges:
            # Only internal crop edges with the planner's full listening margin
            # and no neighboring lyric word inside that margin qualify.
            base = meta['base']
            owners = set()
            if left and base[left-1][1] <= start <= base[left][0]-CONTEXT_MARGIN_MS:
                owners.add(left)
            if right<count and base[right-1][1]+CONTEXT_MARGIN_MS <= end <= base[right][0]:
                owners.add(right-1)
            if not owners:
                continue
        probabilities = packet[f'{view}p{slot}'].astype('float32')
        timestamps = packet[f'{view}t{slot}'].astype('float64')
        if (probabilities.shape != (right-left, 2, 1+(end-start)//FRAME)
                or timestamps.shape != (right-left, 2)
                or not np.isfinite(probabilities).all() or (probabilities < 0).any()
                or not np.isfinite(timestamps).all()):
            raise ValueError('Invalid boundary observations')
        probabilities[:, :, [0, -1]] = 0  # a crop wall can censor an onset or release
        cumulative = np.pad(probabilities.cumsum(axis=-1,dtype='float64'), ((0,0),(0,0),(1,0)))
        for word in owners:
            onset, release = map(round, start + 1000*timestamps[word-left])
            if start + FRAME < onset < release < end - FRAME:
                points[word].add((onset,release))
            rows[word].append((start,cumulative[word-left]))
    return rows, points


def _table(old, rows, points, duration, locked):
    fixed = (np.array([old[0]]), np.array([old[1]]), np.array([[0.]]))
    if locked or not points:
        return fixed
    starts, ends = [_grid([old[k], *(v[k] for v in points)], old[k], duration) for k in (0,1)]
    onsets = np.stack([_mass(cdf[0],starts,offset) for offset,cdf in rows])
    releases = np.stack([_mass(cdf[1],ends,offset) for offset,cdf in rows])
    # Combine start and end support inside each crop before averaging crops.
    score = (onsets.T @ releases) / len(rows)
    if not score.any():
        return fixed
    score = np.log(np.maximum(1e-12,score))
    score[ends[None,:] <= starts[:,None]] = -np.inf
    incumbent = starts.searchsorted(old[0]), ends.searchsorted(old[1])
    score[incumbent] = np.nextafter(score[incumbent],np.inf)
    return starts, ends, score


def _ordered(tables, base):
    previous_ends = np.array([0])
    scores = np.array([0.])
    history = []
    for i, (starts, ends, local) in enumerate(tables):
        overlap = max(0,base[i-1][1]-base[i][0]) if i else 0
        best = np.maximum.accumulate(scores)
        previous = np.maximum.accumulate(np.where(scores > np.r_[-np.inf,best[:-1]],np.arange(len(scores)),0))
        limit = np.searchsorted(previous_ends,starts+overlap,side='right')-1
        values = local + np.where(limit >= 0,best[np.maximum(limit,0)],-np.inf)[:,None]
        take = values.argmax(axis=0)
        scores = values[take,np.arange(len(ends))]
        if not np.isfinite(scores).any():
            raise ValueError('No ordered timing path')
        history.append((starts,ends,take,previous[np.maximum(limit[take],0)]))
        previous_ends = ends
    index = int(scores.argmax())
    intervals = []
    for starts, ends, take, previous in reversed(history):
        intervals.append([int(starts[take[index]]),int(ends[index])])
        index = previous[index]
    return intervals[::-1]

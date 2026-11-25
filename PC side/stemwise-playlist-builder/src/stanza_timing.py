"""Local endpoint remeasurement at recording-matched stanza entrances."""
import json
import os
from pathlib import Path
import sys

from stemwise_engine.alignment_endpoints import (OWSM_CTC_FAMILY, WORD_START_AGREEMENT_MS,
    WORD_END_AGREEMENT_MS, _applicable_endpoint_candidates, _valid_endpoint_sequence)
from stemwise_engine.common import heavy_worker_stage, run_process_tree, sha256_file
from stemwise_engine.runtime_environment import environment_lease, environment_python
from stemwise_engine.model_registry import model_assets
from stemwise_engine.worker_protocol import parse_worker_json, require_worker_success


def recording_binding(recording, mapping, spoken):
    return {'recording': recording, 'mapping': mapping, 'spokenTargets': spoken,
            'modelSha256':model_assets('lyrics_align_pc')['PINNED_MMS_SHA256'], 'audioView':'mix'}


def compared_pairs(pairs, local, anchor):
    """Use a windowed re-decode to choose among measured pairs; it adds no timing vote."""
    if not local or local.get('anchorMs')!=anchor or anchor is None:
        return pairs
    a,b=local['window']
    start,end=local['startMs'],local['endMs']
    if not a<start<end<b:
        return pairs
    close={f:(x,y) for f,(x,y) in pairs.items() if f not in ('mms-fa',OWSM_CTC_FAMILY)
           and a<=x<y<=b and abs(x-start)<=WORD_START_AGREEMENT_MS and abs(y-end)<=WORD_END_AGREEMENT_MS}
    return close or pairs


def same_rendition(families, anchor, entrances):
    """Retain native observations of this entrance. A release disagreement alone cannot displace a line."""
    if anchor is None:
        return families
    near = [c for f,c in families.items() if f not in ('mms-fa', OWSM_CTC_FAMILY) and c.get('words')
            and abs(c['startMs']-anchor)<=WORD_START_AGREEMENT_MS]
    if not near:
        return families
    def matches(c):
        return (all(abs(c['startMs']-anchor) < abs(c['startMs']-other) for other in entrances if other!=anchor)
                and any(c['words'][0]['startMs'] <= guide['words'][0]['endMs']+WORD_START_AGREEMENT_MS
                        and guide['words'][0]['startMs'] <= c['words'][0]['endMs']+WORD_START_AGREEMENT_MS
                        for guide in near))
    return {f:c for f,c in families.items() if c.get('words') and matches(c)}


def locations(entrances, mapping, summary, readings, duration):
    """Use the matched physical entrance plus a second family's local phrase extent."""
    requests = []
    rows = summary['occurrenceCandidates']
    owners = mapping['performedOccurrences']
    if len(rows) != len(owners) or len(readings) != len(owners):
        raise ValueError('Stanza evidence count changed')
    for index, anchor in sorted(entrances.items()):
        owner, row = owners[index], rows[index]
        if (row['occurrenceId'], row['sourcePhysicalIndex']) != (owner['occurrenceId'], owner['sourcePhysicalIndex']):
            raise ValueError('Stanza owner changed')
        if row['publishDecision']['result'] == 'words':
            continue
        ids = [word['occurrenceWordId'] for word in owner['words']]
        near = {}
        for family, candidate in _applicable_endpoint_candidates(row).items():
            if (family not in ('mms-fa', OWSM_CTC_FAMILY) and _valid_endpoint_sequence(candidate)
                    and [w['occurrenceWordId'] for w in candidate.get('words', [])] == ids
                    and abs(candidate['startMs'] - anchor) <= WORD_START_AGREEMENT_MS
                    and all(abs(candidate['startMs'] - anchor) < abs(candidate['startMs'] - other)
                            for i, other in entrances.items() if i != index)):
                near[family] = candidate
        if not near:
            continue
        # A word timer cannot choose the width of a different repeat's crop.
        guide = min(near.values(), key=lambda c: abs(c['startMs'] - anchor))
        a = max(0, min(anchor, guide['startMs']) - WORD_END_AGREEMENT_MS)
        b = min(duration, guide['endMs'] + WORD_END_AGREEMENT_MS)
        if any(a <= other < b for i, other in entrances.items() if i != index):
            continue
        words = [{'wordId': wid, 'acousticText': text} for wid, text in zip(ids, readings[index], strict=True)]
        requests.append({'index': index, 'anchorMs': anchor, 'window': [a, b], 'families': sorted(near),
            'line': {'lineId': owner['occurrenceId'], 'sourcePhysicalIndex': owner['sourcePhysicalIndex'], 'words': words}})
    return requests


def measure(project, path, digest, requests):
    os.environ['TORCH_HOME'] = str(project.parent/'model-cache/torch')
    from stemwise_engine import endpoint_mms_prediction as mms
    from stemwise_engine.acoustic_inputs import StreamedMonoAudio
    if sha256_file(path) != digest:
        raise ValueError('Stanza audio view changed')
    stream = StreamedMonoAudio(path, mms.RATE)
    model = mms.load_model(project)
    result = []
    for req in requests:
        a,b = req['window']
        target = mms.target_plan({'lines': [req['line']]}, None, model.vocab)
        signal = stream.read(a*16, min(stream.sample_count, b*16)).numpy()[0]
        times, _ = mms.align_signal(model, signal, target, a)
        result.append([{'wordId': w['wordId'], 'startMs': round(w['startMs']), 'endMs': round(w['endMs'])}
                       for w in times])
    return result


def review(requests, measurements, summary):
    output = []
    for req, words in zip(requests, measurements, strict=True):
        row = summary['occurrenceCandidates'][req['index']]
        a, b = req['window']
        previous = a
        for k,(w, asked) in enumerate(zip(words, req['line']['words'], strict=True)):
            start, end = w['startMs'], w['endMs']
            if (asked['wordId'] != w['wordId'] or type(start) != int or type(end) != int
                    or not previous <= start < end <= b):
                raise ValueError('Local stanza word or clock differs')
            previous = end
            corroboration = {}
            for boundary,limit in [('startMs', WORD_START_AGREEMENT_MS), ('endMs', WORD_END_AGREEMENT_MS)]:
                corroboration[boundary] = [f for f in req['families']
                    if abs(w[boundary] - row['familyCandidates'][f]['words'][k][boundary]) <= limit
                    and a < w[boundary] < b]
            output.append({**w, 'performedIndex': req['index'], 'wordIndex': k, 'window': [a,b],
                'anchorMs': req['anchorMs'], 'nearbyFamilies': corroboration})
    return output


def run(config, folder, requests, summary, canonical_mix):
    if not requests:
        return []
    project = Path(config['projectDir'])
    path, view = canonical_mix()
    command = [str(environment_python('main', project)), '-B', '-m', 'stemwise_engine.stanza_timing',
               str(project), str(path), view['sha256']]
    with environment_lease('main', builder=project), heavy_worker_stage(config):
        proc = run_process_tree(command, input=json.dumps(requests), text=True, capture_output=True,
                                timeout=int(config['analysisTimeoutMinutes'])*60)
    require_worker_success(proc, code='stanza-remeasurement-failed', diagnostic_path=folder/'stanza-timing.failure.private.txt')
    return review(requests, parse_worker_json(proc.stdout), summary)


if __name__ == '__main__':
    print(json.dumps(measure(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], json.load(sys.stdin))))

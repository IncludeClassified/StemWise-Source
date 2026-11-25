"""Complete a reviewed recording's timing from its retained acoustic observations."""
import copy
import hashlib
from pathlib import Path

from stemwise_engine import endpoint_vowel_release
from stemwise_engine.alignment_audio import read_audio_provenance
from stemwise_engine.alignment_endpoints import (
    NEMO_NFA_FAMILY, OWSM_CTC_FAMILY, WAV2VEC2_FAMILY, _applicable_endpoint_candidates, _independent_endpoint_inputs,
    assess_complete_word_sequence, WORD_START_AGREEMENT_MS, WORD_END_AGREEMENT_MS,
)
from stemwise_engine.common import (WorkerTemporaryDirectory, atomic_write_json, json_bytes, parse_strict_json_bytes,
                                    read_bounded_json, read_bounded_regular_bytes, sha256_file)
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping, lexical_mapping_identity
from stemwise_engine.lyrics_normalize import performance_lines
from stemwise_engine.lyrics_tokenization import display_words


FAMILIES = {'mms-fa': ('mms-fa', 'torchaudio-mms-fa'),
            'wav2vec2': (WAV2VEC2_FAMILY, 'wav2vec2-ctc-forced-alignment'),
            'owsm-ctc': (OWSM_CTC_FAMILY, 'owsm-ctc-lexical-endpoints'),
            'nemo-nfa': ('nemo-nfa', 'nemo-nfa')}
BASIS = 'Estimated from acoustic word alignment on this recording.'


class IncompleteWordPlacement(ValueError):
    """The retained measurements cannot complete the words while preserving the locks."""


def _observation(row, id_key):
    scores = ('startMs', 'endMs', 'confidence', 'ctcLogScore', 'acousticTokenCount')
    # The line-level acoustic token count is not used by every publisher.
    line = tuple(row.get(k) for k in scores[:-1])
    words = tuple((w[id_key], *(w.get(k) for k in scores)) for w in row.get('words', ()))
    return line, words


def _matches_component_packet(component, packet):
    if component is None:
        return False
    _, mapping, _ = component
    if packet.get('mappingIdentity') != lexical_mapping_identity(mapping):
        return False
    owners = mapping['performedOccurrences']
    lines = packet['lines']
    if len(lines) != len(owners):
        return False
    for owner, line in zip(owners, lines):
        if (line['sourcePhysicalIndex'] != owner['sourcePhysicalIndex']
                or line['lineId'] != owner['occurrenceId']
                or [w['wordId'] for w in line['words']] != [w['occurrenceWordId'] for w in owner['words']]):
            return False
    return True


def _measurements(workspace, state, mapping, audio, original, display_lines, *, packet_refs=None):
    """Index actual word observations, keeping each recognizer and rendition separate."""
    folder = workspace.job_dir(state['videoId'])
    provenance = read_audio_provenance(workspace, state['videoId'], audio['durationMs'],
        expected_mix_sha256=audio['sha256'])
    views = (provenance or {}).get('audioViews', {})
    observed = {}
    identity = lexical_mapping_identity(mapping)
    component_mappings = {}
    for ref in state['cacheBindings']['records']:
        if ref['familyId'] not in FAMILIES:
            continue
        family, engine = FAMILIES[ref['familyId']]
        filename = ref['cacheId']
        if Path(filename).name != filename:
            raise ValueError('Invalid endpoint cache path')
        raw = read_bounded_regular_bytes(folder/filename, max_bytes=MAX_JSON_BYTES, root=folder)
        if hashlib.sha256(raw).hexdigest() != ref['sha256']:
            raise ValueError('Endpoint measurement changed')
        packet = parse_strict_json_bytes(raw)
        if packet.get('engine') != engine:
            continue
        if packet.get('mappingIdentity') != identity:
            # Independent timers can measure just the lines owned by their
            # language. Rebuild the producer's slice from today's reviewed text.
            if family not in (NEMO_NFA_FAMILY, OWSM_CTC_FAMILY):
                continue
            if family not in component_mappings:
                from stemwise_engine.owsm_ctc_forced_alignment import ROUTES_BY_LANGUAGE
                languages = {'ar'} if family == NEMO_NFA_FAMILY else set(ROUTES_BY_LANGUAGE)
                component_mappings[family] = _independent_endpoint_inputs(original, mapping, display_lines,
                    languages, subset=family == NEMO_NFA_FAMILY)
            component = component_mappings[family].get(packet.get('language'))
            if not _matches_component_packet(component, packet):
                continue
        source = packet.get('provenance', {}).get('audio', packet.get('audio', {}))
        if packet['videoId'] != state['videoId'] or packet['durationMs'] != audio['durationMs']:
            continue
        if source.get('sourceAudioSha256', source.get('sha256')) != audio['sha256']:
            # The dry vocal's immediate parent is another stem. Replay both transforms.
            view = views.get(source.get('routeId'))
            if view is None or source != {k: v for k, v in view.items() if k != 'stageMetrics'}:
                continue
        route = packet.get('route') or 'torchaudio-mms-fa-on-' + packet['provenance']['audioViewId']
        for line in packet['lines']:
            key = (family, route, line['sourcePhysicalIndex'], line['lineId'])
            obs = _observation(line, 'wordId')
            observed.setdefault(key, set()).add(obs)
            if packet_refs is not None:
                packet_refs.setdefault((key,obs), []).append({'path':filename,'sha256':ref['sha256']})
    return observed


def _placements(words, locked, handoffs=None):
    """Choose a globally ordered sequence of measured boundaries without moving a lock."""
    parents = []
    previous = {(0, 0, 0): 0}
    for i,(measurements, fixed) in enumerate(zip(words, locked, strict=True)):
        full = {family: pair for family, pair in measurements.items() if family != OWSM_CTC_FAMILY}
        starts = {a for a, _ in measurements.values()}
        # A start from another view may refine a full-word interval only inside
        # that measured word. A distant instrumental hit cannot bridge the gap.
        pairs = {pair for pair in full.values()}
        # Intersecting disagreeing words can manufacture a sub-frame word that
        # no family measured. A composite must contain a complete observation.
        pairs.update((a, b) for a in starts for left, b in full.values()
                     if left < a < b and any(a <= x < y <= b for x, y in full.values()))
        choices = {}
        links = {}
        for a, b in sorted({fixed} if fixed else pairs):
            # Each family supplies one onset. Use the vocal forced end when its
            # placement is compatible. Lexical token exits never predict releases.
            start_error = sum(abs(a - x) for x, _ in measurements.values())
            vocal = full.get('mms-fa')
            end_error = abs(b - vocal[1]) if vocal and vocal[0] <= a < vocal[1] else 0
            for p,prior_cost in previous.items():
                prior_start,prior_end,older_end=p
                if older_end>a:
                    continue
                if prior_end>a and not (prior_start<=a and any(
                        abs(prior_start-left[0])<=WORD_START_AGREEMENT_MS and abs(prior_end-left[1])<=WORD_END_AGREEMENT_MS
                        and abs(a-right[0])<=WORD_START_AGREEMENT_MS and abs(b-right[1])<=WORD_END_AGREEMENT_MS
                        for left,right in (handoffs or {}).get(i,()))):
                    continue
                # Remember previous overlapping releases until the word after this pair is clear.
                state=a,b,max(older_end,prior_end) if prior_end>a else 0
                cost=prior_cost+start_error+end_error
                if cost<choices.get(state,float('inf')):
                    choices[state],links[state]=cost,p
        if not choices:
            raise IncompleteWordPlacement('No complete ordered acoustic path through existing locks')
        previous = choices
        parents.append(links)
    current = min(previous, key=previous.get)
    result = []
    for links in reversed(parents):
        result.append(current[:2])
        current = links[current]
    return result[::-1]


def project(workspace, state, original, admission, audio, release_ref=None, *, repair_ref=None, resolved_releases=None,
            listening_ref=None, native_choices=None, repeat_ref=None, phrase_ref=None, raw_choices=None,
            repeat_boundary_ref=None, vocal_span_ref=None, role_span_ref=None):
    folder = workspace.job_dir(state['videoId'])
    sources = admission.source_physical_indices
    mapping = build_lexical_occurrence_mapping(state['videoId'], original, sources)
    identity = lexical_mapping_identity(mapping)
    if (not sources or state['videoId'] != folder.name
            or state['contentAdmission'].get('spokenTargets') != admission.spoken_targets
            or admission.audio_sha256 != audio['sha256'] or state['durationMs'] != audio['durationMs']
            or any(state.get(k) != v for k, v in identity.items())):
        raise ValueError('Endpoint state differs from current reviewed target')
    from stemwise_engine.lyric_preview.job import recording_entrances, recording_handoffs
    from stemwise_engine import spoken_targets, stanza_timing
    spoken = None
    if admission.spoken_targets:
        spoken = spoken_targets.load(workspace.root.parent/'jobs'/folder.name, folder.name, original, sources, audio['sha256'])
        if spoken is None or spoken.reference != admission.spoken_targets:
            raise ValueError('Recording placement pronunciation review changed')
    physical = performance_lines(original['text'])
    display_lines = [display_words(physical[i]) for i in sources]
    acoustic_original = {**original, '_spokenTargets': spoken} if spoken else original
    choices = native_choices if native_choices is not None else ({} if listening_ref or repeat_ref or phrase_ref or vocal_span_ref or role_span_ref else None)
    if phrase_ref and raw_choices is None:
        raw_choices = {}
    packet_refs = {}
    extra = {'packet_refs':packet_refs} if choices is not None else {}
    observed = _measurements(workspace, state, mapping, audio, acoustic_original, display_lines, **extra)
    entrances = recording_entrances(workspace, folder.name, audio['sha256'], original, sources, audio['durationMs'], spoken=spoken)
    review = state.get('stanzaTimingReview', {})
    binding = stanza_timing.recording_binding(audio['sha256'], identity['mappingSha256'], admission.spoken_targets)
    local_words = {}
    if entrances and all(review.get(k)==v for k,v in binding.items()) and review.get('anchors')==[
            [i,t] for i,t in sorted(entrances.items())]:
        local_words = {w['wordId']:w for w in review['words']}
    measurements, locks, output = [], [], []
    for owner, row in zip(mapping['performedOccurrences'], state['endpointEvidence']['occurrenceCandidates'], strict=True):
        occurrence, source = owner['occurrenceId'], owner['sourcePhysicalIndex']
        if (row['occurrenceId'], row['sourcePhysicalIndex']) != (occurrence, source):
            raise ValueError('Endpoint occurrence differs')
        ids = [w['occurrenceWordId'] for w in owner['words']]
        families = {}
        for family, candidate in row['familyCandidates'].items():
            intervals = tuple((w['occurrenceWordId'], w['startMs'], w['endMs']) for w in candidate.get('words', ()))
            key = (family, candidate['route'], source, occurrence)
            if _observation(candidate, 'occurrenceWordId') in observed.get(key, ()) and [i for i, _, _ in intervals] == ids:
                if any(type(a) != int or type(b) != int or not 0 <= a < b <= audio['durationMs'] for _, a, b in intervals):
                    raise ValueError('Invalid acoustic word interval')
                families[family] = candidate
        verified_families = families
        fixed = row['publishDecision']['result'] == 'words'
        if not fixed and entrances:
            families = stanza_timing.same_rendition(families, entrances.get(len(output)), entrances.values())
        supported = False
        if fixed:
            publisher = row['selectedPublisherFamily']
            if publisher not in families:
                raise IncompleteWordPlacement('Existing timing lock has no matching numerical observation')
            witnesses = {k: v for k, v in _applicable_endpoint_candidates(row).items() if k in families}
            _, reason = assess_complete_word_sequence(families[publisher], witnesses, publisher_family=publisher)
            supported = reason is None and witnesses.keys() == _applicable_endpoint_candidates(row).keys()
        text = physical[source]
        entries = []
        for index, (left, right, _) in enumerate(display_words(text)):
            values = {family: (candidate['words'][index]['startMs'], candidate['words'][index]['endMs'])
                      for family, candidate in families.items()}
            if not fixed and local_words:
                values = stanza_timing.compared_pairs(values, local_words.get(ids[index]), entrances.get(len(output)))
            if choices is not None and not fixed:
                packets = {
                    family: {'occurrenceId':occurrence,'wordId':ids[index],'family':family,'route':c['route'],
                             'lineStartMs':c['startMs'],'word':c['words'][index],
                             'packets':packet_refs[((family,c['route'],source,occurrence),_observation(c,'occurrenceWordId'))]}
                    for family,c in (verified_families if raw_choices is not None else families).items()
                    if family!=OWSM_CTC_FAMILY}
                position = len(output)+1,index+1
                if raw_choices is not None:
                    raw_choices[position] = packets
                choices[position] = {f:c for f,c in packets.items() if f in values and
                                     (c['word']['startMs'],c['word']['endMs'])==values[f]}
            measurements.append(values)
            locks.append(values[publisher] if fixed else None)
            entries.append({'startUtf16': left, 'endUtf16': right})
        output.append((text, supported, entries))
    handoffs=recording_handoffs(workspace,folder.name,audio['sha256'],original,sources,audio['durationMs'])
    intervals = iter(_placements(measurements,locks,handoffs))
    track = {k: v for k, v in original.items() if k != 'text'}
    track.update(format='regional', lines=[])
    for text, supported, entries in output:
        quality = {'status': 'supported'} if supported else {'status': 'estimated', 'basis': BASIS}
        for entry in entries:
            a, b = next(intervals)
            entry['timing'] = {**quality, 'startMs': a, 'endMs': b}
        track['lines'].append({'text': text, 'contentStatus': 'admitted', 'occurrenceStatus': 'supported',
            'timing': {**quality, 'startMs': entries[0]['timing']['startMs'],
                       'endMs':max(word['timing']['endMs'] for word in entries)},
            'words': entries})
    confirmed = {} if resolved_releases is None else resolved_releases
    if release_ref and endpoint_vowel_release.english_track(original) and admission.spoken_targets is None:
        estimates = endpoint_vowel_release.read_estimates(folder, release_ref, audio['sha256'], audio['durationMs'])
        # Replay the word timer on which the vocal and backing traces were
        # actually measured. Adopt only its confirmed extension inside the
        # currently selected neighbors, without moving the selected onset.
        alternate = copy.deepcopy(track)
        targets = []
        for i, (line, alt, owner) in enumerate(zip(track['lines'], alternate['lines'], mapping['performedOccurrences'], strict=True)):
            for j, (word, proposal, token) in enumerate(zip(line['words'], alt['words'], owner['words'], strict=True)):
                timing, trial = word['timing'], proposal['timing']
                obs = estimates.get(token['occurrenceWordId'])
                if (obs and timing['status'] == 'estimated' and timing['startMs'] < obs['endMs'] <= timing['endMs']
                        < obs['estimatedEndMs'] and obs['startMs'] < timing['endMs']):
                    trial['endMs'] = obs['endMs']
                    targets.append(((i, j), timing, trial, obs, line))
        endpoint_vowel_release.refine_estimated_track({'tracks': [alternate]}, mapping, estimates, audio['durationMs'])
        for position, timing, trial, obs, line in targets:
            if trial['endMs'] == obs['estimatedEndMs']:
                timing.update(trial)
                confirmed[position] = (timing['startMs'], timing['endMs'], release_ref['sha256'])
                if line['words'][-1]['timing'] is timing:
                    line['timing'].update(endMs=trial['endMs'], basis=trial['basis'])
    if repair_ref is not None:
        from stemwise_engine.recording_release_repair import resolve
        resolve(workspace,state,original,admission,audio,track,confirmed,ref=repair_ref)
    if listening_ref is not None:
        from stemwise_engine import listened_release_choice
        listened_release_choice.replay(workspace,state,admission,audio,track,choices,confirmed,listening_ref)
    protected=(listened_release_choice.protected(folder,track,confirmed,listening_ref) if listening_ref else confirmed)
    if repeat_ref:
        from stemwise_engine.recording_repeat_words import recover
        recover(workspace,state,original,admission,audio,track,protected,choices,ref=repeat_ref,
                confirmed_measurements=confirmed)
        protected.update(confirmed)
    if repeat_boundary_ref:
        from stemwise_engine import held_repeat_release
        held_repeat_release.recover(workspace,state,original,admission,audio,track,choices,protected,
                                    confirmed,repeat_ref,ref=repeat_boundary_ref)
    if phrase_ref:
        from stemwise_engine import recording_phrase_placement
        recording_phrase_placement.recover(workspace,state,original,admission,audio,track,choices,protected,
                                           all_native=raw_choices,ref=phrase_ref)
    if role_span_ref or vocal_span_ref:
        _vocal_spans(workspace,state,original,admission,audio,track,choices,protected,confirmed,
                     role_span_ref,vocal_span_ref,repair_ref,None)
    return track


def _vocal_spans(workspace,state,original,admission,audio,track,choices,protected,confirmed,
                 role_ref,vocal_ref,release_ref,config):
    authority={}
    if role_ref or config:
        from stemwise_engine import role_vocal_span_recovery
        evidence=role_vocal_span_recovery.recover(workspace,state,original,admission,audio,track,choices,protected,
                         ref=role_ref,config=config,repair_ref=release_ref)
        if evidence:
            authority['roleSpanEvidence']=evidence
    if vocal_ref or config:
        from stemwise_engine import vocal_span_recovery
        evidence=vocal_span_recovery.recover(workspace,state,original,admission,audio,track,choices,protected,
                         ref=vocal_ref,config=config)
        if evidence:
            authority['vocalSpanEvidence']=evidence
    for evidence in authority.values():
        confirmed.update({k:v for k,v in protected.items() if v[-1]==evidence['sha256']})
    return authority


def _vowel_reference(workflow, folder, state, original, admission, audio, *, prepare):
    if not endpoint_vowel_release.english_track(original) or admission.spoken_targets is not None:
        return None
    from stemwise_engine.alignment_endpoints import vowel_observation_plan
    from stemwise_engine.lyrics_alignment import separate_vocals_demucs
    indices = admission.source_physical_indices
    mapping = build_lexical_occurrence_mapping(folder.name, original, indices)
    retained = state.get('estimatedVowelReleases', {})
    physical = performance_lines(original['text'])
    candidates = vowel_observation_plan(mapping, [display_words(physical[i]) for i in indices],
        state['endpointEvidence'], audio['durationMs'])
    fallback = None
    if (retained.get('mapping') == lexical_mapping_identity(mapping) and retained.get('audioSha256') == audio['sha256']
            and retained.get('canonicalPlainSha256') == state['canonicalPlainSha256']):
        fallback = retained['evidence']
        endpoint_vowel_release.read_estimates(folder, fallback, audio['sha256'], audio['durationMs'])
        packet = read_bounded_json(folder/fallback['path'],max_bytes=MAX_JSON_BYTES,root=folder)
        if packet['request']['candidates']==candidates:
            return fallback
    if not candidates:
        return None
    binding = hashlib.sha256(json_bytes([audio['sha256'], lexical_mapping_identity(mapping), candidates,
        endpoint_vowel_release._producer()])).hexdigest()
    path = folder/f'recording-word-release-{binding}.private.json'
    if path.is_file():
        record = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=folder)
        if record['binding'] != binding:
            raise ValueError('Recording vowel measurement changed')
        return record['evidence']
    project_dir = Path(workflow.config['projectDir'])
    if not prepare or not (project_dir.parent/'model-cache'/endpoint_vowel_release.MODEL_DIR/'model.onnx').is_file():
        return fallback
    pcm = Path(audio['path'])
    if sha256_file(pcm) != audio['sha256']:
        raise ValueError('Canonical recording changed before vowel estimation')
    timeout = int(workflow.config['analysisTimeoutMinutes'])*60
    with WorkerTemporaryDirectory(prefix='word-release-', dir=folder) as temp:
        vocal = separate_vocals_demucs(pcm, Path(temp), project_dir=project_dir, timeout_seconds=timeout)
        _, ref = endpoint_vowel_release.estimate(project_dir, folder, audio['durationMs'], audio['sha256'],
            (vocal, vocal.with_name('no_vocals.wav'), sha256_file(vocal)), candidates, timeout)
    if sha256_file(pcm) != audio['sha256']:
        raise ValueError('Canonical recording changed during vowel estimation')
    atomic_write_json(path, {'binding': binding, 'evidence': ref})
    return ref


def reviewed_placement(workflow, job, workspace, original, admission, audio, *, prepare=False):
    from stemwise_engine.lyric_content import is_source_recording_admission
    from stemwise_engine.lyrics_alignment import _plain_original
    from stemwise_engine.workflow_records import FutureSongError
    folder = workspace.job_dir(job['videoId'])
    path = folder/'state.json'
    if not path.is_file():
        return None
    state = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=folder)
    if state.get('status') != 'line-unresolved' or not state.get('endpointEvidence', {}).get('occurrenceCandidates'):
        return None
    if not (admission.adjudication or is_source_recording_admission(admission, folder)):
        return None
    # Canonical serializations may differ. Recheck the old bytes and the exact
    # Original track independently of the current recording-content decision.
    backup = read_bounded_regular_bytes(workspace.current_backup_path(folder.name),
        max_bytes=MAX_JSON_BYTES, root=workspace.canonical_plain)
    if hashlib.sha256(backup).hexdigest() != state['canonicalPlainSha256'] or _plain_original(parse_strict_json_bytes(backup)) != original:
        return None
    outcome, review, sources = workflow._validate_authority_outcome(job)
    if outcome != 'resolved':
        return None
    try:
        workflow._require_recording_review(job, review, sources, audio)
    except FutureSongError:
        return None
    ref = _vowel_reference(workflow, folder, state, original, admission, audio, prepare=prepare)
    from stemwise_engine import listened_release_choice
    listening_ref = listened_release_choice.active(folder)
    choices = {}
    raw_choices = {}
    try:
        confirmed = {}
        track = project(workspace, state, original, admission, audio, ref, resolved_releases=confirmed,native_choices=choices,
                        raw_choices=raw_choices)
    except IncompleteWordPlacement:
        return None
    authority = {'kind': 'reviewed-recording-acoustic-words', 'mappingSha256': state['mappingSha256'],
        'contentEvidenceSha256': admission.evidence_sha256, 'stateSha256': sha256_file(path)}
    if ref:
        authority['vowelReleaseEvidence'] = ref
    from stemwise_engine.recording_release_repair import resolve
    repair = resolve(workspace,state,original,admission,audio,track,confirmed,
                     prepare=prepare,config=workflow.config)
    if repair:
        authority['releaseRepairEvidence'] = repair
    if listening_ref:
        listened_release_choice.replay(workspace,state,admission,audio,track,choices,confirmed,listening_ref)
        authority['listeningReleaseEvidence'] = listening_ref
    protected=listened_release_choice.protected(folder,track,confirmed,listening_ref)
    from stemwise_engine.recording_repeat_words import recover
    prior_authority=job.get('validation',{}).get('capabilities',{}).get('timingAuthority') or {}
    repeat_prior=prior_authority.get('repeatedWordEvidence')
    repeat=None
    if prepare or repeat_prior:
        repeat=recover(workspace,state,original,admission,audio,track,protected,choices,
                       ref=None if prepare else repeat_prior,prepare=prepare,config=workflow.config,
                       confirmed_measurements=confirmed)
        if repeat:
            authority['repeatedWordEvidence']=repeat
        protected.update(confirmed)
    boundary_prior=prior_authority.get('repeatBoundaryEvidence')
    if boundary_prior and not repeat and not prepare:
        raise ValueError('Post-repeat timing requires its recording-bound repeat observation')
    if repeat and (prepare or boundary_prior):
        from stemwise_engine import held_repeat_release
        boundaries=held_repeat_release.recover(workspace,state,original,admission,audio,track,choices,protected,
                confirmed,repeat,ref=None if prepare else boundary_prior,prepare=prepare,config=workflow.config)
        if boundaries:
            authority['repeatBoundaryEvidence']=boundaries
    if prepare or prior_authority.get('phrasePlacementEvidence'):
        from stemwise_engine import recording_phrase_placement
        phrase = recording_phrase_placement.recover(workspace,state,original,admission,audio,track,choices,protected,
                    all_native=raw_choices,config=workflow.config if prepare else None,
                    ref=None if prepare else prior_authority['phrasePlacementEvidence'])
        if phrase:
            authority['phrasePlacementEvidence'] = phrase
    if prepare or prior_authority.get('roleSpanEvidence') or prior_authority.get('vocalSpanEvidence'):
        authority.update(_vocal_spans(workspace,state,original,admission,audio,track,choices,protected,confirmed,
                        None if prepare else prior_authority.get('roleSpanEvidence'),
                        None if prepare else prior_authority.get('vocalSpanEvidence'), repair,
                        workflow.config if prepare else None))
    from stemwise_engine import timing_estimate_policy
    prior = (job.get('validation', {}).get('capabilities', {}).get('timingAuthority') or {}).get('equivalentEstimates')
    if prepare:
        from stemwise_engine.word_timing_review import boundary_recovery_recipe
        authority['boundaryRecoveryRecipe'] = boundary_recovery_recipe()
        authority['equivalentEstimates'] = timing_estimate_policy.prepare(
            workflow,job,workspace,state,original,admission,audio,track,choices,protected)
    elif prior is not None:
        timing_estimate_policy.replay(track,choices,protected,prior,audio['sha256'],state['mappingSha256'])
        authority['equivalentEstimates'] = prior
        if prior_authority.get('boundaryRecoveryRecipe'):
            authority['boundaryRecoveryRecipe'] = prior_authority['boundaryRecoveryRecipe']
    release_upgrade = False
    if job.get('capabilityRefresh'):
        from stemwise_engine.word_timing_review import boundary_recovery_recipe
        current_recipe = boundary_recovery_recipe()
        release_upgrade = prior_authority.get('boundaryRecoveryRecipe') != current_recipe
        if release_upgrade:
            authority['boundaryRecoveryRecipe'] = current_recipe
    sustained_ref = prior_authority.get('sustainedReleaseEvidence')
    if prepare or release_upgrade or sustained_ref:
        from stemwise_engine import sustained_release
        sustained = sustained_release.recover(workspace,state,original,admission,audio,track,protected,
            authority.get('equivalentEstimates'),ref=None if prepare or release_upgrade else sustained_ref,
            config=workflow.config if prepare or release_upgrade else None)
        if sustained:
            authority['sustainedReleaseEvidence'] = sustained
    gap_ref = prior_authority.get('instrumentalBreakEvidence')
    if prepare or release_upgrade or gap_ref:
        from stemwise_engine import instrumental_break
        gap = instrumental_break.recover(workspace,state,original,admission,audio,track,protected,
                                         ref=None if prepare or release_upgrade else gap_ref)
        if gap:
            authority['instrumentalBreakEvidence'] = gap
    return track, authority

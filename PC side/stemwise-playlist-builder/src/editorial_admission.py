"""Recording-bound review of heuristic lyric warnings; structural errors remain fatal."""
import re
import errno


def _raise_input_io_failure(error):
    while error is not None:
        if isinstance(error, OSError) and error.errno not in {errno.ENOENT, errno.ENOTDIR, errno.ELOOP}:
            raise error
        error = error.__cause__


def complete_original_word_timing(lyrics):
    """Schema-valid supported timing must cover a nonempty entire Original."""
    originals = [track for track in lyrics['tracks'] if track['kind'] == 'original']
    return (len(originals) == 1 and originals[0]['format'] == 'synced'
        and bool(originals[0]['lines'])
        and all(line.get('words') for line in originals[0]['lines']))


def complete_original_word_placement(lyrics):
    """Structural completeness; current owner/numerical authority is checked by readers."""
    if complete_original_word_timing(lyrics):
        return True
    originals = [track for track in lyrics['tracks'] if track['kind'] == 'original']
    return (len(originals) == 1 and originals[0]['format'] == 'regional'
        and bool(originals[0]['lines']) and all(
            line['contentStatus'] == 'admitted' and line['occurrenceStatus'] == 'supported'
            and line['timing']['status'] in {'supported', 'estimated'} and bool(line.get('words'))
            and all(word['timing']['status'] in {'supported', 'estimated'} for word in line['words'])
            for line in originals[0]['lines']))


def require_completed_lyrics(lyrics, *, duration_ms, endpoint_review=None):
    from stemwise_engine.song_enrichment_v2 import validate_lyrics
    from stemwise_engine.workflow_records import WorkflowInputError
    validate_lyrics(lyrics, duration_ms=duration_ms)
    if complete_original_word_placement(lyrics) and lyrics['status'] != 'complete':
        from stemwise_engine.workflow_review import companion_requirements
        original = next(track for track in lyrics['tracks'] if track['kind'] == 'original')
        required = companion_requirements({'originalLanguage': original['language'],
            'componentLanguages': original.get('componentLanguages', [])})
        present = {track['kind'] for track in lyrics['tracks']}
        roles = [label for (kind, label), needed in zip(
            (('translation', 'Translation'), ('transliteration', 'IPA')), required)
            if needed and kind not in present]
        affected = ', '.join(roles) or 'Translation/IPA admission'
        raise WorkflowInputError('required companion review is incomplete: ' + affected,
            reason_code='review-stale', affected_input=affected,
            next_action='Supply and independently review each required current companion against the retained Original, '
                'recording and occurrences, then Validate. Preserve complete Original word timing and its quality labels.')
    untimed_roles = [label for track in lyrics['tracks']
        for kind, label in (('translation', 'Translation'), ('transliteration', 'IPA'))
        if track['kind'] == kind and track['format'] == 'plain']
    if complete_original_word_placement(lyrics) and untimed_roles:
        raise WorkflowInputError('required companion line timing is incomplete: ' + ', '.join(untimed_roles),
            reason_code='line-unresolved', affected_input=', '.join(untimed_roles),
            next_action='Project each independently reviewed companion onto the exact current Original occurrence lines, '
                'then Validate. Preserve complete Original word timing and its quality labels.')
    if (not complete_original_word_placement(lyrics)
            or isinstance(endpoint_review, dict) and endpoint_review.get('result') != 'accepted'):
        raise WorkflowInputError('lyrics-completion-required: every performed word needs current complete start and end timing; '
            'unavailable lyrics require qualified exhaustive search and recovery evidence',
            reason_code='lyrics-content-unresolved' if lyrics['status'] == 'unavailable' else 'line-unresolved',
            affected_input='current Original lyrics, performed occurrences and word endpoints',
            next_action='Continue exact-recording lyric and endpoint recovery; preserve current evidence and prior packages. '
                'Unavailable completion remains blocked until exhaustive search and recovery can be qualified.')


def project_timed_companion(original, companion, source_physical_indices):
    """Place an already admitted role at its Original occurrences without upgrading timing."""
    import copy
    from stemwise_engine.ipa_validation import companion_lines
    if original.get('format') not in {'synced', 'regional'} or companion.get('format') != 'plain':
        raise ValueError('companion projection requires timed Original and plain admitted role')
    physical = companion_lines(companion['text'], ipa=companion.get('kind') == 'transliteration')
    sources = tuple(source_physical_indices)
    if (not sources or len(sources) != len(original['lines']) or any(type(i) is not int for i in sources)
            or sources != tuple(i for i, line in enumerate(physical) if line)):
        raise ValueError('companion occurrence text differs from its complete Original')
    result = copy.deepcopy(companion)
    result.pop('text')
    result.update(format=original['format'], lines=[])
    for source, line in zip(sources, original['lines']):
        if original['format'] == 'synced':
            result['lines'].append({'text': physical[source], 'startMs': line['startMs'], 'endMs': line['endMs']})
            continue
        if line['contentStatus'] != 'admitted' or line['occurrenceStatus'] != 'supported':
            raise ValueError('companion timing requires admitted Original occurrences')
        timing = copy.deepcopy(line['timing'])
        if timing['status'] == 'estimated':
            timing['basis'] = 'Estimated occurrence placement inherited from the Original acoustic word prediction.'
        result['lines'].append({'text': physical[source], 'contentStatus': 'admitted',
            'occurrenceStatus': 'supported', 'timing': timing})
    return result


def companion_admissions(workflow, job, original, audio_identity, content_admission):
    """Read and admit independent roles against the same current original authority."""
    from stemwise_engine.common import sha256_file
    from stemwise_engine.companion_review import companion_binding, admit_companion
    from stemwise_engine.lyrics_intake import read_recording_draft, read_role_snapshot, LyricsInputError
    from stemwise_engine.workflow_records import FutureSongError
    from stemwise_engine.workflow_review import companion_requirements
    directory = workflow._job_dir(job['videoId'])
    draft = directory/'drafts'
    original_text, original_sha, metadata, metadata_sha = read_recording_draft(directory)
    if original_text != original['text']:
        raise FutureSongError('original-input-changed-during-admission')
    required = companion_requirements(metadata)
    review = {}
    authority_error = 'original-recording-review-unresolved' if content_admission is None else None
    try:
        if content_admission is not None and content_admission.owner_verification is not None:
            review, sources = workflow._owner_prediction_reviews(job, audio_identity)
        else:
            outcome, review, sources = workflow._validate_authority_outcome(job)
            if outcome != 'resolved':
                authority_error = 'original-recording-review-unresolved'
            else:
                workflow._require_recording_review(job, review, sources, audio_identity)
    except (FutureSongError, ValueError, OSError) as error:
        _raise_input_io_failure(error)
        authority_error = 'original-recording-review-stale'
    decisions = review.get('companionReviews', {})
    if not isinstance(decisions, dict):
        decisions = {}
    results = {}
    for (kind, role), needed in zip((('translation', 'translationEn'), ('transliteration', 'ipa')), required):
        if not needed:
            results[kind] = admit_companion(original, None, None,
                binding=None, required=False, evidence_root=directory)
            continue
        try:
            compiled, _, _ = workflow._preflight_drafts(job, roles=frozenset({'original', role}))
            candidate = next((track for track in compiled['tracks'] if track['kind'] == kind), None)
            if candidate is None:
                results[kind] = admit_companion(original, None, None,
                    binding=None, required=True, evidence_root=directory)
                continue
            text, raw_sha = read_role_snapshot(draft, role)
            if text != candidate['text'] or compiled['tracks'][0] != original:
                raise LyricsInputError('companion-input-changed-during-admission')
            binding = companion_binding(job['videoId'], original, candidate,
                original_input_sha256=original_sha, candidate_input_sha256=raw_sha,
                original_metadata_sha256=metadata_sha, audio_sha256=audio_identity['sha256'],
                sources_sha256=sha256_file(directory/'sources.json'), content_admission=content_admission)
            result = admit_companion(original, candidate, decisions.get(kind), binding=binding,
                required=True, evidence_root=directory, qa_resolutions=review.get('qaResolutions', []))
            if authority_error and result['state'] == 'admitted':
                result = {'state': 'stale', 'issues': [{'code': authority_error}],
                    'binding': binding, 'nextAction': 'Review the current original recording dependencies.'}
            results[kind] = result
        except (LyricsInputError, FutureSongError, OSError) as error:
            _raise_input_io_failure(error)
            issues, raw_sha = [{'code': 'companion-input-invalid'}], None
            try:
                from stemwise_engine.ipa_validation import selective_ipa, validate_companion_text
                text, raw_sha = read_role_snapshot(draft, role)
                located = validate_companion_text(original['text'], text, role=role, allow_skips=selective_ipa(original))
                if located:
                    issues = [{'code': item.code, 'line': item.line, 'position': item.position} for item in located]
            except (LyricsInputError, ValueError, OSError) as diagnostic_error:
                _raise_input_io_failure(diagnostic_error)
            results[kind] = {'state': 'invalid', 'issues': issues,
                'candidateInputSha256': raw_sha, 'binding': None,
                'nextAction': 'Repair this companion input, then review its exact current bytes.'}
    return results


def available_lyrics(workflow, job, audio_identity, *, on_blocker=None, prepare_acoustic=False,
                     refresh_retained_releases=False):
    """Project only claims whose current retained authority can be revalidated."""
    import copy
    import hashlib
    from stemwise_engine.common import json_bytes
    from stemwise_engine.contract import MAX_JSON_BYTES
    from stemwise_engine.lyrics_alignment import AlignmentWorkspace, _plain_original, _physical_lines
    from stemwise_engine.lyric_content import require_workspace_content, confirm_content, owner_workspace_admission
    from stemwise_engine.workflow_records import LyricsContentBlocker, ContentEvidenceUnavailable, FutureSongError
    from stemwise_engine.song_enrichment_v2 import validate_lyrics
    workspace = AlignmentWorkspace(workflow.root, workspace_root=workflow.workspace_root)
    video_id = job['videoId']
    unavailable = {'status': 'unavailable', 'reason': 'Performed lyric content or occurrence is not established.', 'tracks': []}
    def withheld(error):
        if on_blocker is not None:
            on_blocker(error)
        return unavailable, {'content': 'unavailable', 'timing': 'none', 'companions': []}
    try:
        compiled, _, _ = workflow._preflight_drafts(job, roles=frozenset({'original'}))
        original = _plain_original(compiled)
        sources = [index for index, line in enumerate(_physical_lines(original['text'])) if line.strip()]
    except (FutureSongError, OSError, ValueError) as error:
        _raise_input_io_failure(error)
        return withheld(error)
    try:
        canonical_sha = hashlib.sha256(json_bytes(compiled)).hexdigest()
        prediction = job.get('ownerWordPrediction')
        if prediction is not None:
            admission = workflow._owner_prediction_admission(job, original, sources, canonical_sha, audio_identity)
        else:
            admission = owner_workspace_admission(workspace, video_id, original, sources, canonical_sha,
                audio_identity['sha256'], retain=False)
            if admission is None and job.get('recordingContentEvidence'):
                from stemwise_engine.lyric_content import source_recording_workspace_admission
                admission = source_recording_workspace_admission(workspace, video_id, original, sources,
                    canonical_sha, audio_identity['sha256'])
            if admission is None:
                admission = require_workspace_content(workspace, video_id, sources, original=original,
                                                      audio_sha=audio_identity['sha256'])
    except (LyricsContentBlocker, ContentEvidenceUnavailable) as current_error:
        if job.get('ownerWordPrediction') is not None:
            return withheld(current_error)
        proof = job.get('recordingContentEvidence') or job.get('recordingEvidenceInputs')
        try:
            from stemwise_engine.lyrics_intake import read_recording_draft
            _, draft_sha, _, metadata_sha = read_recording_draft(workflow._job_dir(video_id))
            if not isinstance(proof, dict):
                return withheld(current_error)
            if (proof.get('audioSha256') != audio_identity['sha256']
                    or proof.get('draftSha256') != draft_sha or proof.get('metadataSha256') != metadata_sha):
                return withheld(ContentEvidenceUnavailable('content-evidence-changed', binding=current_error.binding))
            reference = proof.get('evidence', proof.get('recognition'))
            if (not isinstance(reference, dict) or set(reference) != {'path', 'sha256'}
                    or not isinstance(reference['path'], str) or not isinstance(reference['sha256'], str)):
                return withheld(ContentEvidenceUnavailable('content-evidence-invalid', binding=current_error.binding))
            path = workspace.job_dir(video_id)/reference['path']
            from stemwise_engine.common import read_bounded_regular_bytes
            raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=workspace.job_dir(video_id))
            if hashlib.sha256(raw).hexdigest() != reference['sha256']:
                return withheld(ContentEvidenceUnavailable('content-evidence-changed', binding=current_error.binding))
            canonical_sha = hashlib.sha256(json_bytes(compiled)).hexdigest()
            try:
                admission = confirm_content(original, sources, canonical_sha,
                    audio_identity['sha256'], evidence_path=path, evidence_root=workspace.job_dir(video_id))
            except LyricsContentBlocker as error:
                if error.reasons != ('content-sequence-uncertain',):
                    raise
                from stemwise_engine.lyric_content import plain_content_excerpt
                excerpt = plain_content_excerpt(original, sources, canonical_sha, audio_identity['sha256'],
                    audio_identity['durationMs'], evidence_path=path, evidence_root=workspace.job_dir(video_id))
                if excerpt is None or excerpt[1]['evidence'] != reference:
                    return withheld(error)
                track = copy.deepcopy(original)
                track['text'] = excerpt[0]
                projection = {'status': 'partial',
                    'reason': 'Uncertain lyric lines are omitted; timing and companion features are unavailable.',
                    'tracks': [track]}
                validate_lyrics(projection, duration_ms=audio_identity['durationMs'])
                if on_blocker is not None:
                    on_blocker(error)
                return projection, {'content': 'partial', 'timing': 'plain', 'companions': [],
                                    'contentExcerpt': excerpt[1]}
        except (LyricsContentBlocker, ContentEvidenceUnavailable, OSError, ValueError) as error:
            _raise_input_io_failure(error)
            return withheld(error if isinstance(error, (LyricsContentBlocker, ContentEvidenceUnavailable))
                else ContentEvidenceUnavailable('content-evidence-unavailable' if isinstance(error, OSError)
                    else 'content-evidence-invalid'))
    tracks = [copy.deepcopy(original)]
    retained_timing = workflow._available_original_timing(job, original, audio_identity)
    if (retained_timing is None and not job.get('ownerWordPrediction') and not job.get('displayReview')
            and not job.get('endpointReview') and admission.owner_verification is None):
        from stemwise_engine.recording_word_placement import reviewed_placement
        retained_timing = reviewed_placement(workflow, job, workspace, original, admission, audio_identity,
            prepare=prepare_acoustic, refresh_retained_releases=refresh_retained_releases)
    if retained_timing is not None:
        tracks[0] = retained_timing[0]
    approved = []
    companions = companion_admissions(workflow, job, original, audio_identity, admission)
    for kind, result in companions.items():
        if result['state'] == 'admitted':
            companion = copy.deepcopy(result['track'])
            if tracks[0]['format'] in {'synced', 'regional'}:
                companion = project_timed_companion(tracks[0], companion, sources)
            tracks.append(companion)
            approved.append(kind)
    projection = {'status': 'partial', 'reason': 'Supported timing or reviewed companion features are unavailable.', 'tracks': tracks}
    timing = ('estimated' if tracks[0]['format'] == 'regional' else 'synced') if retained_timing is not None else 'plain'
    if any('words' in line for track in tracks for line in track.get('lines', ())):
        from stemwise_engine.lyrics_tokenization import TOKENIZATION_VERSION
        projection['tokenizationVersion'] = TOKENIZATION_VERSION
    validate_lyrics(projection, duration_ms=audio_identity['durationMs'])
    if (complete_original_word_placement(projection)
            and all(result['state'] in {'admitted', 'not-required'} for result in companions.values())):
        projection['status'] = 'complete'
        projection.pop('reason')
    capabilities = {'content': 'admitted', 'timing': timing, 'companions': approved,
        'contentAdmission': admission.to_record(), 'companionAdmission': {
            kind: {key: value for key, value in result.items() if key != 'track'}
            for kind, result in companions.items()}}
    if retained_timing is not None:
        capabilities['timingAuthority'] = retained_timing[1]
    return projection, capabilities

RESOLVABLE_QA_CODES = frozenset({'editorial-role-label', 'probable-source-credit-line',
    'visual-elongation-or-punctuation', 'editorial-honorific-annotation',
    'probable-every-day-word-boundary', 'editorial-repetition-notation',
    'embedded-timestamp', 'caption-artifact-label'})


def validate_qa_resolutions(value, video_id, *, track_kind=None):
    keys = {'formatVersion', 'videoId', 'recordingSha256', 'textSha256', 'trackKind',
            'issue', 'decision', 'reviewer', 'completedAt', 'rationale'}
    if not isinstance(value, list) or len(value) > 200:
        raise ValueError('qaResolutions must be a bounded list')
    seen = set()
    for item in value:
        if track_kind is not None and (not isinstance(item, dict) or item.get('trackKind') != track_kind):
            continue
        if (not isinstance(item, dict) or set(item) != keys or type(item['formatVersion']) is not int
                or item['formatVersion'] != 1 or item['videoId'] != video_id
                or item['trackKind'] not in ('original', 'translation')
                or item['decision'] != ('source-meaning-preserved' if item.get('trackKind') == 'translation'
                                        else 'literal-performed-content')
                or any(not isinstance(item[key], str) or re.fullmatch('[0-9a-f]{64}', item[key]) is None
                       for key in ('recordingSha256', 'textSha256'))
                or any(not isinstance(item[key], str) or not item[key].strip() or len(item[key]) > 1000
                       for key in ('reviewer', 'completedAt', 'rationale'))
                or not isinstance(item['issue'], dict)
                or item['issue'].get('code') not in RESOLVABLE_QA_CODES
                or item['issue'].get('severity') not in ('warning', 'review')):
            raise ValueError('QA resolution is malformed or attempts to waive a mandatory failure')
        from stemwise_engine.common import json_bytes
        identity = json_bytes({key: item[key] for key in ('trackKind', 'issue')})
        if identity in seen:
            raise ValueError('Duplicate QA issue resolution')
        seen.add(identity)
    return value


def unresolved_qa_issues(audit, resolutions, *, video_id, recording_sha256, track_kind):
    validate_qa_resolutions(resolutions, video_id, track_kind=track_kind)
    applicable = [item for item in resolutions if isinstance(item, dict) and item.get('trackKind') == track_kind]
    for item in applicable:
        if (item['recordingSha256'] != recording_sha256 or item['textSha256'] != audit['sha256']
                or item['issue'] not in audit['issues']):
            raise ValueError('QA resolution no longer matches the exact recording, text and issue')
    return [issue for issue in audit['issues'] if not any(item['issue'] == issue for item in applicable)]

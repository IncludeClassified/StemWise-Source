"""Current endpoint-only failure admission, independent of campaign state."""
from stemwise_engine.common import read_bounded_json, sha256_file
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.workflow_records import FutureSongError


def failure_source(context, job, *, reasons_allowed):
    if (job.get('state') != 'LINE_UNRESOLVED' or job.get('outcome', {}).get('code') != 'line-unresolved'
            or not context.song_policy_matches(job)):
        raise FutureSongError('Endpoint review requires a current LINE_UNRESOLVED job')
    state_path = context.alignment_state_path(job['videoId'])
    state = read_bounded_json(state_path, max_bytes=MAX_JSON_BYTES, root=state_path.parent)
    attempt_id, reasons = state.get('attemptId'), state.get('reasons')
    if (state.get('status') != 'line-unresolved' or attempt_id != job.get('lastAlignmentAttemptId')
            or not isinstance(reasons, list) or not reasons
            or any(not isinstance(reason, str) for reason in reasons)
            or len(set(reasons)) != len(reasons)):
        raise FutureSongError('Endpoint failure has changed or includes unresolved structure')
    endpoint_progress = state.get('endpointEvidence', {})
    if (endpoint_progress.get('resultClass') in {'committed-lines', 'committed-mixed'}
            or endpoint_progress.get('lineOnlyCount', 0) > 0
            or 'incomplete-display-word-map' in reasons):
        raise FutureSongError('word-timing-recovery-required')
    run_path, run, diagnostics = context.report_store.alignment_failure_snapshot(
        context.report_store.report_root(job['videoId']), job['videoId'], attempt_id)
    attempts, selected = diagnostics.get('endpointRouteAttempts'), diagnostics.get('selectedEndpointAttemptIndex')
    required = ['htdemucs-vocals', 'kimberley-jensen-roformer-vocals', 'sucial-v2-roformer-dry-vocals']
    if (not isinstance(attempts, list) or type(selected) is not int or not 0 <= selected < len(attempts)
            or [item.get('routeId', '')[:-9] for item in attempts if isinstance(item, dict)
                and isinstance(item.get('routeId'), str) and item['routeId'].endswith(':baseline')] != required):
        raise FutureSongError('Endpoint baseline cascade is incomplete')
    allowed_routes = {f'{route}:{variant}' for route in required
                      for variant in ('baseline', 'anchored', 'cumulative', 'prior-recording-seed')}
    endpoint = diagnostics.get('endpointEvidence')
    chosen = attempts[selected].get('endpointEvidence') if isinstance(attempts[selected], dict) else None
    if (run.get('failureCode') != 'line-unresolved' or run.get('summary', {}).get('reasons') != reasons
            or diagnostics.get('reasons') != reasons or diagnostics.get('resultClass') != 'line-unresolved'
            or any(not isinstance(item, dict) or item.get('routeId') not in allowed_routes
                or not isinstance(item.get('endpointEvidence'), dict)
                or item['endpointEvidence'].get('resultClass') != 'line-unresolved' for item in attempts)
            or not isinstance(chosen, dict) or not isinstance(endpoint, dict)
            or {key: value for key, value in chosen.items() if key != 'occurrenceCandidates'} != endpoint
            or endpoint.get('resultClass') != 'line-unresolved'
            or not isinstance(endpoint.get('unresolvedLineReasons'), dict)
            or set(endpoint['unresolvedLineReasons']) != set(reasons)
            or any(type(count) is not int or count <= 0 for count in endpoint['unresolvedLineReasons'].values())
            or state.get('routeFailures') not in (None, []) or diagnostics.get('routeFailures') not in (None, [])
            or diagnostics.get('internalRepeatStructureKind') not in {'none', 'unsupported', 'collapsible'}
            or diagnostics.get('internalRepeatAuditComplete') is not True
            or state.get('internalRepeatStructureKind') != diagnostics['internalRepeatStructureKind']
            or state.get('internalRepeatAuditComplete') is not True):
        raise FutureSongError('Endpoint cascade is not complete and eligible for current review')
    from stemwise_engine.endpoint_ctc_evidence import require_endpoint_content
    from stemwise_engine.lyrics_alignment import AlignmentWorkspace
    canonical = AlignmentWorkspace(context.root, workspace_root=context.workspace_root).current_backup_path(job['videoId'])
    mapping, _ = context.lexical_mapping(job['videoId'])
    audio = context.completed_analysis_audio(context.pipeline(), job['videoId'])
    admission = require_endpoint_content({'contentAdmission': diagnostics.get('contentAdmission'),
        'canonicalPlainSha256': sha256_file(canonical), 'analysisAudio': audio}, mapping, chosen,
        read_bounded_json(canonical, max_bytes=MAX_JSON_BYTES, root=canonical.parent), state_path.parent)
    # The complete failure's reasons must be timing-only too, not only the selected route's.
    from stemwise_engine.lyric_content import require_timing_only
    require_timing_only(reasons, admission, occurrences=chosen.get('occurrenceCandidates'),
                        expected_occurrences=mapping.get('performedOccurrences'))
    if any(reason not in reasons_allowed for reason in reasons):
        from stemwise_engine.workflow_records import LyricsContentBlocker
        raise LyricsContentBlocker('failure-not-confirmed-timing-only', binding=admission.binding)
    return {'reason': 'exhausted-endpoint-cascade', 'alignmentAttemptId': attempt_id,
        'bindings': {'alignmentStateSha256': sha256_file(state_path), 'alignmentRunSha256': sha256_file(run_path)}}

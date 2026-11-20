"""Read-only projections of captured current jobs and their local progress records."""
from pathlib import Path
from typing import Any
import hashlib
import json
import math
import re
from stemwise_engine.common import read_json, read_bounded_json, read_bounded_regular_bytes, parse_strict_json_bytes
from stemwise_engine.workflow_records import FutureSongError


def candidate_recognition_sha(provenance, audio_sha, duration_ms, evidence_root, *, witness=None):
    """Bind an unapproved recognition view to its retained Original, without timing admission."""
    audio = provenance.get('audio', {})
    if ((audio.get('sha256') == audio_sha and not any(key in audio for key in ('routeId', 'sourceViewId', 'sourceAudioSha256'))
            and provenance.get('audioView', 'mix') == 'mix') or (provenance.get('familyId') == 'mms-asr'
            and provenance.get('audioSha256') == audio_sha and provenance.get('audioView', 'mix') == 'mix')):
        return audio_sha
    from stemwise_engine.model_registry import model_assets, runtime_profile
    from stemwise_engine.provenance import semantic_source_identity
    pins = model_assets('lyrics_align_pc')
    routes = pins['ROFORMER_ROUTES']
    retained = witness if witness is not None else read_bounded_json(Path(evidence_root)/'audio-provenance.private.json',
        max_bytes=16*1024*1024, root=evidence_root)
    if (retained.get('provenanceVersion') != 3 or retained.get('videoId') != Path(evidence_root).name
            or retained.get('durationMs') != duration_ms
            or retained.get('mix', {}).get('sha256') != audio_sha):
        raise ValueError('Content recovery view is not bound to the Original')
    def bound_view(route_id, seen=()):
        view = retained.get('audioViews', {}).get(route_id)
        if (route_id in seen or not isinstance(view, dict) or view.get('routeId') != route_id
                or not isinstance(view.get('sha256'), str) or re.fullmatch(r'[0-9a-f]{64}', view['sha256']) is None):
            raise ValueError('Content recovery view is not bound to the Original')
        if route_id == 'htdemucs-vocals':
            expected = {'familyId': route_id, 'sourceViewId': 'mix', 'sourceAudioSha256': audio_sha,
                'model': 'htdemucs', 'parameters': {'shifts': 0, 'twoStems': 'vocals'},
                'demucsModelSha256': pins['PINNED_DEMUCS_SHA256']}
        elif route_id in routes:
            definition = routes[route_id]
            source_id = definition['sourceViewId']
            source = retained['mix'] if source_id == 'mix' else bound_view(source_id, (*seen, route_id))
            expected = {key: definition[key] for key in ('familyId', 'sourceViewId',
                'checkpointSha256', 'configSha256', 'assetRepository', 'assetRevision')}
            expected.update(sourceAudioSha256=source['sha256'],
                helperSha256=semantic_source_identity(Path(__file__).with_name('roformer_separation.py')))
            package = {key: pins['ROFORMER_PACKAGE_'+suffix] for key, suffix in (
                ('version', 'VERSION'), ('wheelSha256', 'WHEEL_SHA256'),
                ('sourceManifestSha256', 'SOURCE_MANIFEST_SHA256'), ('sourceCommit', 'SOURCE_COMMIT'))}
            expected.update({'package'+key[0].upper()+key[1:]: value for key, value in package.items()})
            profile, runtime = runtime_profile('main'), view.get('runtime', {})
            if (runtime.get('python') != profile['python']
                    or runtime.get('packages') != {name: profile['packages'][name] for name in pins['ROFORMER_RUNTIME_PACKAGES']}
                    or runtime.get('package') != package
                    or view.get('device') not in {'cpu', 'cuda'}
                    or runtime.get('requestedDevice') != view['device']
                    or runtime.get('resolvedDevice') != view['device']
                    or view['device'] == 'cuda' and runtime.get('cudaAvailable') is not True):
                raise ValueError('Content recovery separator runtime changed')
        else:
            raise ValueError('Content recovery view is not bound to the Original')
        if any(view.get(key) != value for key, value in expected.items()):
            raise ValueError('Content recovery view is not bound to the Original')
        return view
    view = bound_view(audio.get('routeId'))
    if (audio != {key: value for key, value in view.items() if key != 'stageMetrics'}
            or provenance.get('audioView', view['routeId']) != view['routeId']):
        raise ValueError('Content recovery view is not bound to the Original')
    return view['sha256']


def recognition_view_witness(provenance, audio_sha, duration_ms, evidence_root, *, mix, descriptors=()):
    """Bind this observation's exact chain, independently of the current materialized views."""
    audio = provenance.get('audio', {})
    if 'routeId' not in audio:
        candidate_recognition_sha(provenance, audio_sha, duration_ms, evidence_root)
        return None
    retained = {'provenanceVersion': 3, 'videoId': Path(evidence_root).name,
        'durationMs': duration_ms, 'mix': mix, 'audioViews': {}}
    view = audio
    while True:
        route = view.get('routeId')
        if not isinstance(route, str) or route in retained['audioViews']:
            raise ValueError('Content recovery view chain is cyclic or missing')
        retained['audioViews'][route] = {key: value for key, value in view.items() if key != 'stageMetrics'}
        source = view.get('sourceViewId')
        if source == 'mix':
            break
        parents = [{key: value for key, value in item.items() if key != 'stageMetrics'} for item in descriptors
            if isinstance(item, dict) and item.get('routeId') == source
            and item.get('sha256') == view.get('sourceAudioSha256')]
        unique = {json.dumps(parent, sort_keys=True, ensure_ascii=False): parent for parent in parents}
        if len(unique) != 1:
            raise ValueError('Content recovery view parent is missing or ambiguous')
        view = next(iter(unique.values()))
    candidate_recognition_sha(provenance, audio_sha, duration_ms, evidence_root, witness=retained)
    return retained


def read_audio_view_witness(reference, evidence_root):
    if reference is None:
        return None
    if not isinstance(reference, dict) or set(reference) != {'path', 'sha256'}:
        raise ValueError('Content recovery view witness reference is invalid')
    raw = read_bounded_regular_bytes(Path(evidence_root)/reference['path'], max_bytes=16*1024*1024, root=evidence_root)
    if hashlib.sha256(raw).hexdigest() != reference['sha256']:
        raise ValueError('Content recovery view witness changed')
    value = parse_strict_json_bytes(raw)
    if not isinstance(value, dict) or set(value) != {'provenanceVersion', 'videoId', 'durationMs', 'mix', 'audioViews'}:
        raise ValueError('Content recovery view witness is malformed')
    return value


def validate_recovery_candidates(job_dir, evidence_root, packet, *, video_id, audio_sha, duration_ms):
    """Read current immutable candidate references without invoking recognition."""
    from stemwise_engine.lyrics_intake import read_recording_draft
    from stemwise_engine.lyric_content import _recognized, _recognized_text
    draft, draft_sha, metadata, metadata_sha = read_recording_draft(job_dir)
    if (not isinstance(packet, dict) or packet.get('formatVersion') != 1
            or packet.get('videoId') != video_id or packet.get('audioSha256') != audio_sha
            or packet.get('durationMs') != duration_ms or packet.get('draftSha256') != draft_sha
            or packet.get('metadataSha256') != metadata_sha or packet.get('publicationAuthority') is not False
            or packet.get('status') != 'pending-recording-review'
            or not isinstance(packet.get('candidates'), list) or not packet['candidates']):
        raise ValueError('Content recovery inputs changed')
    def read(reference, base):
        if not isinstance(reference, dict) or set(reference) != {'path', 'sha256'}:
            raise ValueError('Content recovery reference is invalid')
        raw = read_bounded_regular_bytes(base/reference['path'], max_bytes=16*1024*1024, root=base)
        if hashlib.sha256(raw).hexdigest() != reference['sha256']:
            raise ValueError('Content recovery reference changed')
        return raw
    read(packet['primaryRecognition'], evidence_root)
    for candidate in packet['candidates']:
        if (candidate.get('videoId') != video_id or candidate.get('audioSha256') != audio_sha
                or candidate.get('durationMs') != duration_ms or candidate.get('publicationAuthority') is not False
                or candidate.get('status') != 'pending-recording-review'):
            raise ValueError('Content recovery candidate identity changed')
        cache = parse_strict_json_bytes(read(candidate['recognition'], evidence_root))
        provenance = cache.get('provenance')
        if (cache.get('videoId') != video_id or not isinstance(provenance, dict) or provenance.get('durationMs',
                provenance.get('audio', {}).get('stream', {}).get('durationMs')) != duration_ms):
            raise ValueError('Content recovery recognition identity changed')
        witness = read_audio_view_witness(candidate.get('audioViewWitness'), evidence_root)
        recognized_sha = candidate_recognition_sha(provenance, audio_sha, duration_ms, evidence_root, witness=witness)
        words = cache.get('words')
        if cache.get('kind') == 'faster-whisper-untimed-lexical':
            from stemwise_engine.whisper_lexical_candidate import validate_trigger
            words = _recognized(cache, recognized_sha, candidate_only=True)
            validate_trigger(cache, job_dir, audio_sha=audio_sha)
        elif cache.get('kind') == 'nemo-ctc-untimed-lexical':
            words = _recognized(cache, recognized_sha, candidate_only=True)
        elif 'modelRevision' in provenance and 'workerOutput' in cache:
            words = _recognized(cache, recognized_sha, candidate_only=True)
            if not isinstance(words, str) or not words.strip():
                raise ValueError('Content recovery recognition is empty')
        elif (provenance.get('decoder', {}).get('promptMode') != 'withheld'
                or cache.get('promptSha256') != hashlib.sha256(b'').hexdigest()
                or not isinstance(words, list) or not words
                or any(not isinstance(word, dict) or not isinstance(word.get('raw'), str) for word in words)):
            raise ValueError('Content recovery recognition identity changed')
        expected = (_recognized_text(words).strip()+'\n').encode('utf-8')
        if read(candidate['candidate'], job_dir) != expected:
            raise ValueError('Content recovery candidate differs from recognition')
        if 'referenceAdaptation' in candidate:
            from stemwise_engine.lyric_content import reference_adaptation
            adaptation = reference_adaptation({'text': draft, 'language': metadata.get('originalLanguage')}, words)
            if adaptation is None or candidate['referenceAdaptation'] != adaptation[0]:
                raise ValueError('Reference adaptation differs from the current text and recognition')
            if (adaptation[1] is not None) != ('referenceCandidate' in candidate):
                raise ValueError('Reference candidate is incomplete')
            if adaptation[1] is not None and read(candidate['referenceCandidate'], job_dir) != adaptation[1].encode('utf-8'):
                raise ValueError('Reference candidate differs from the reference projection')
        elif 'referenceCandidate' in candidate:
            raise ValueError('Reference candidate has no current adaptation')
    if 'routeFailures' in packet:
        from stemwise_engine.model_registry import model_assets
        from stemwise_engine.nemo_lexical_candidate import FAMILY as NEMO_LEXICAL_FAMILY
        from stemwise_engine.private_diagnostics import validate_references
        failures = packet['routeFailures']
        if not isinstance(failures, list):
            raise ValueError('Content recovery route failures are invalid')
        for failure in failures:
            if (not isinstance(failure, dict) or set(failure) != {'familyId', 'routeId', 'code', 'diagnostics'}
                    or failure['familyId'] not in {'qwen3-asr', 'faster-whisper', NEMO_LEXICAL_FAMILY}
                    or failure['routeId'] not in {'mix', 'htdemucs-vocals', *model_assets('lyrics_align_pc')['ROFORMER_ROUTES']}
                    or failure['familyId'] == NEMO_LEXICAL_FAMILY and failure['routeId'] != 'mix'
                    or not isinstance(failure['code'], str) or re.fullmatch(r'[A-Za-z0-9_.:+-]{1,120}', failure['code']) is None):
                raise ValueError('Content recovery route failure is invalid')
            for reference in validate_references(failure['diagnostics']):
                raw = read_bounded_regular_bytes(job_dir/reference['file'], max_bytes=12000, root=job_dir)
                if len(raw) != reference['byteCount'] or hashlib.sha256(raw).hexdigest() != reference['sha256']:
                    raise ValueError('Content recovery route failure changed')
                context = parse_strict_json_bytes(raw.split(b'\n', 1)[0])
                expected = {key: packet[key] for key in ('audioSha256', 'durationMs', 'draftSha256', 'metadataSha256')}
                expected.update({key: failure[key] for key in ('familyId', 'routeId')})
                if not isinstance(context, dict) or any(context.get(key) != value for key, value in expected.items()):
                    raise ValueError('Content recovery route failure inputs changed')
    return packet


def recovery_candidates_readback(job, job_dir, evidence_root, runtime, display_path):
    packet = job.get('recordingRecoveryCandidates')
    if packet is None:
        return None
    try:
        checkpoint_path = runtime/'work/checkpoints'/f"{job['videoId']}.json"
        checkpoint = read_bounded_json(checkpoint_path, max_bytes=16*1024*1024, root=runtime)
        audio = checkpoint['originalAudio']
        packet = validate_recovery_candidates(job_dir, evidence_root, packet, video_id=job['videoId'],
            audio_sha=audio['sha256'], duration_ms=audio['durationMs'])
        def reference(value, base):
            return {**value, 'path': display_path(base/value['path'])}
        return {**packet, 'primaryRecognition': reference(packet['primaryRecognition'], evidence_root),
            'candidates': [{**candidate, 'candidate': reference(candidate['candidate'], job_dir),
                'recognition': reference(candidate['recognition'], evidence_root),
                **({'audioViewWitness': reference(candidate['audioViewWitness'], evidence_root)}
                    if 'audioViewWitness' in candidate else {}),
                **({'referenceCandidate': reference(candidate['referenceCandidate'], job_dir)}
                    if 'referenceCandidate' in candidate else {})} for candidate in packet['candidates']],
            **({'routeFailures': [{**failure, 'diagnostics': [{**item,
                'file': display_path(job_dir/item['file'])} for item in failure['diagnostics']]}
                for failure in packet['routeFailures']]} if 'routeFailures' in packet else {})}
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return {'status': 'unavailable', 'publicationAuthority': False}


def recovery_failure_readback(job, job_dir, evidence_root, runtime, display_path):
    failure = job.get('recordingRecoveryFailure')
    if failure is None:
        return None
    try:
        if (not isinstance(failure, dict) or set(failure) != {'formatVersion', 'videoId', 'stage', 'status', 'code',
                    'audioSha256', 'durationMs', 'draftSha256', 'metadataSha256', 'primaryRecognition', 'privateDiagnostics'}
                or failure.get('formatVersion') != 1
                or failure.get('videoId') != job['videoId'] or failure.get('stage') != 'independent-content-recognition'
                or failure.get('status') != 'retryable-technical' or not isinstance(failure.get('code'), str)
                or re.fullmatch(r'[A-Za-z0-9_.:+-]{1,120}', failure['code']) is None
                or type(failure.get('durationMs')) is not int or failure['durationMs'] <= 0
                or any(not isinstance(failure.get(key), str) or re.fullmatch(r'[0-9a-f]{64}', failure[key]) is None
                    for key in ('audioSha256', 'draftSha256', 'metadataSha256'))):
            raise ValueError('Optional recovery failure is invalid')
        from stemwise_engine.lyrics_intake import read_recording_draft
        checkpoint = read_bounded_json(runtime/'work/checkpoints'/f"{job['videoId']}.json", max_bytes=16*1024*1024, root=runtime)
        _, draft_sha, _, metadata_sha = read_recording_draft(job_dir)
        current = (checkpoint['originalAudio']['sha256'] == failure['audioSha256']
            and checkpoint['originalAudio']['durationMs'] == failure['durationMs']
            and draft_sha == failure['draftSha256'] and metadata_sha == failure['metadataSha256'])
        projected = dict(failure)
        reference = failure.get('primaryRecognition')
        if reference is not None:
            if (not isinstance(reference, dict) or set(reference) != {'path', 'sha256'}
                    or not isinstance(reference['path'], str) or not isinstance(reference['sha256'], str)
                    or re.fullmatch(r'[0-9a-f]{64}', reference['sha256']) is None):
                raise ValueError('Optional recovery primary reference is invalid')
            path = evidence_root/reference['path']
            raw = read_bounded_regular_bytes(path, max_bytes=16*1024*1024, root=evidence_root)
            current = current and hashlib.sha256(raw).hexdigest() == reference['sha256']
            projected['primaryRecognition'] = {**reference, 'path': display_path(path)}
        projected['privateDiagnostics'] = []
        try:
            from stemwise_engine.private_diagnostics import validate_references
            references = validate_references(failure['privateDiagnostics']) if failure['privateDiagnostics'] else []
            for reference in references:
                path = job_dir/reference['file']
                raw = read_bounded_regular_bytes(path, max_bytes=12000, root=job_dir)
                if len(raw) != reference['byteCount'] or hashlib.sha256(raw).hexdigest() != reference['sha256']:
                    raise ValueError('Optional recovery private cause changed')
                projected['privateDiagnostics'].append({**reference, 'file': display_path(path)})
        except (OSError, ValueError):
            projected['privateDiagnostics'] = []
            projected['diagnosticsStatus'] = 'unavailable'
        return {**projected, 'status': 'retryable-technical' if current else 'stale'}
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return {'status': 'unavailable'}


def project_job_summary(job: dict[str, Any], *, workspace_root: Path, root: Path,
        runtime: Path, job_dir: Path, recording_evidence_inputs: dict | None,
        review_attempts: dict[str, Path]) -> dict[str, Any]:
    def workspace_relative(path: Path) -> str:
        return path.resolve().relative_to(workspace_root.resolve()).as_posix()

    def without_paths(value: Any) -> Any:
        if isinstance(value, dict):
            return {
                key: without_paths(item)
                for key, item in value.items()
                if key not in {"path", "report", "candidatePath", "draft"}
                and not key.lower().endswith("path")
            }
        if isinstance(value, list):
            return [without_paths(item) for item in value]
        return value

    def safe_code(value: Any, limit: int = 160) -> str | None:
        return (
            value
            if isinstance(value, str)
            and re.fullmatch(rf"[A-Za-z0-9_.:+-]{{1,{limit}}}", value)
            else None
        )

    result = {
        "videoId": job["videoId"],
        "state": job["state"],
        "updatedAt": job.get("updatedAt"),
        "deliveryAllowed": job["deliveryAllowed"],
        "job": workspace_relative(job_dir),
        "agentTask": workspace_relative(
            job_dir / "AGENT_TASK.txt"
        ),
    }
    sources_path = job_dir/'sources.json'
    if sources_path.exists():
        try:
            sources = read_bounded_json(sources_path, max_bytes=16*1024*1024, root=job_dir)
            status = sources['lyricsAvailability']
            if sources.get('videoId') != job['videoId'] or status not in {
                    'draft-acquired', 'candidate-evidence', 'not-found',
                    'not-searched', 'lookup-failed', 'candidate-rejected'}:
                raise ValueError('Source availability is invalid')
            issues = sources['lyricsAvailabilityIssues']
            if not isinstance(issues, list) or any(issue not in {
                    'not-searched', 'lookup-failed', 'candidate-rejected'} for issue in issues):
                raise ValueError('Source availability issues are invalid')
            if any(type(sources.get(key)) is not bool for key in
                   ('draftLyricsPresent', 'usableLyricEvidenceAvailable')):
                raise ValueError('Source availability flags are invalid')
            result['sourceAvailability'] = {'status': status, 'issues': issues,
                'draftLyricsPresent': sources['draftLyricsPresent'],
                'usableLyricEvidenceAvailable': sources['usableLyricEvidenceAvailable']}
        except (OSError, ValueError, KeyError, TypeError):
            result['sourceAvailability'] = {'status': 'inspection-failed'}
    from stemwise_engine.workflow_states import JobProgress
    progress = JobProgress.current(job)
    result['progress'] = progress.to_record()
    result['permittedActions'] = sorted(progress.rule.actions)
    if 'bridgeMaxFileBytes' in job:
        result['bridgeBudget'] = {'maxFileBytes': job['bridgeMaxFileBytes'],
            'manualImport': job.get('bridgeManualImport') is True,
            'preflight': job.get('bridgeBudgetPreflight', {'status': 'not-rechecked'})}
    evidence = job.get('recordingEvidence')
    review_inputs = recording_evidence_inputs
    if review_inputs is not None:
        result['recordingEvidenceInputs'] = review_inputs
    recovery = recovery_candidates_readback(job, job_dir, root/'lyrics-alignment/jobs'/job['videoId'],
        runtime, workspace_relative)
    if recovery is not None:
        result['recordingRecoveryCandidates'] = recovery
    recovery_failure = recovery_failure_readback(job, job_dir, root/'lyrics-alignment/jobs'/job['videoId'], runtime, workspace_relative)
    if recovery_failure is not None:
        result['recordingRecoveryFailure'] = recovery_failure
    if 'audioFirstCandidate' in job:
        try:
            candidate = job['audioFirstCandidate']
            checkpoint = read_json(runtime/'work/checkpoints'/f"{job['videoId']}.json")
            if (candidate['formatVersion'] != 1 or candidate['videoId'] != job['videoId']
                    or candidate['audioSha256'] != checkpoint['originalAudio']['sha256']):
                raise ValueError('Audio candidate owner differs')
            roots = {'candidate': job_dir, 'recognition': root/'lyrics-alignment/jobs'/job['videoId']}
            projected = {}
            for name, base in roots.items():
                reference = candidate[name]
                path = base/reference['path']
                raw = read_bounded_regular_bytes(path, max_bytes=16*1024*1024, root=base)
                if hashlib.sha256(raw).hexdigest() != reference['sha256']:
                    raise ValueError('Audio candidate evidence changed')
                projected[name] = {'path': workspace_relative(path), 'sha256': reference['sha256']}
            result['audioFirstCandidate'] = {**projected, 'status': 'pending-recording-review',
                'publicationAuthority': False}
        except (OSError, ValueError, TypeError, KeyError):
            result['audioFirstCandidate'] = {'status': 'unavailable', 'publicationAuthority': False}
    if evidence is not None:
        if (not isinstance(evidence, dict) or set(evidence) != {'status', 'durationMs'}
                or evidence.get('status') not in {'running', 'failed', 'complete'}
                or type(evidence.get('durationMs')) is not int or evidence['durationMs'] < 0):
            result['recordingEvidence'] = {'status': 'corrupt'}
        else:
            result['recordingEvidence'] = dict(evidence)
            if evidence['status'] == 'failed' and 'recordingEvidenceDiagnostics' in job:
                from stemwise_engine.private_diagnostics import validate_references
                try:
                    references = validate_references(job['recordingEvidenceDiagnostics'])
                    verified = []
                    for reference in references:
                        path = job_dir / reference['file']
                        body = read_bounded_regular_bytes(path, max_bytes=12000, root=path.parent)
                        if (len(body) != reference['byteCount']
                                or hashlib.sha256(body).hexdigest() != reference['sha256']):
                            raise ValueError('Recording evidence diagnostic changed')
                        verified.append({**reference, 'file': workspace_relative(path)})
                    result['recordingEvidence']['privateDiagnostics'] = verified
                except (OSError, ValueError):
                    result['recordingEvidence']['diagnosticsStatus'] = 'unavailable'
    if "validation" in job:
        result["validation"] = without_paths(job["validation"])
    if "delivery" in job:
        result["delivery"] = without_paths(job["delivery"])
    if "artworkReview" in job:
        result["artworkReview"] = without_paths(job["artworkReview"])
    if "artworkRepair" in job:
        result["artworkRepair"] = without_paths(job["artworkRepair"])
    if "outcome" in job:
        result["outcome"] = without_paths(job["outcome"])
    if "error" in job:
        error = job["error"]
        result["error"] = (
            error
            if isinstance(error, str)
            and re.fullmatch(r"[A-Za-z0-9_.:+-]{1,160}", error)
            else "future-song-error"
        )
    endpoint_review = job.get("endpointReview")
    if isinstance(endpoint_review, dict) and isinstance(
        endpoint_review.get("attemptId"), str
    ):
        try:
            attempt = review_attempts.get('endpointReview')
            if attempt is None:
                raise FutureSongError('endpoint review attempt is invalid')
            result["endpointReview"] = {
                key: endpoint_review[key]
                for key in (
                    "attemptId",
                    "declarationSha256",
                    "sourceAlignmentRunId",
                    "sourceAlignmentRunSha256",
                    "sourceAlignmentStateSha256",
                    "result",
                    "reviewSha256",
                    "alignmentRunId",
                    "alignmentRunSha256",
                )
                if key in endpoint_review
            }
            if attempt.is_dir():
                result["endpointReview"].update(
                    declaration=workspace_relative(attempt / "declaration.json"),
                    review=workspace_relative(attempt / "review.json"),
                )
        except FutureSongError:
            pass
    display_review = job.get("displayReview")
    if isinstance(display_review, dict) and isinstance(
        display_review.get("attemptId"), str
    ):
        try:
            attempt = review_attempts.get('displayReview')
            if attempt is None:
                raise FutureSongError('display review attempt is invalid')
            result["displayReview"] = {
                key: display_review[key]
                for key in (
                    "attemptId",
                    "declarationSha256",
                    "preReviewTextSha256",
                    "result",
                    "originalSha256",
                    "runId",
                    "runSha256",
                )
                if key in display_review
            }
            if attempt.is_dir():
                result["displayReview"].update(
                    declaration=workspace_relative(attempt / "declaration.json"),
                    original=workspace_relative(attempt / "original.txt"),
                )
        except FutureSongError:
            pass
    alignment_state_path = (
        root / "lyrics-alignment" / "jobs" / job["videoId"] / "state.json"
    )
    if alignment_state_path.is_file():
        try:
            alignment_state = read_json(alignment_state_path)
            if (not isinstance(alignment_state, dict) or safe_code(alignment_state.get('status')) is None
                    or any(not isinstance(alignment_state.get(key, []), list)
                           or any(safe_code(value) is None for value in alignment_state.get(key, []))
                           for key in ('attemptedEvidence', 'reasons'))
                    or not isinstance(alignment_state.get('metrics', {}), dict)
                    or any(safe_code(key, 120) is None or type(value) not in {bool, int, float}
                           or (type(value) is float and not math.isfinite(value))
                           for key, value in alignment_state.get('metrics', {}).items())):
                raise ValueError('invalid alignment progress record')
            result["lyricsAlignmentProgress"] = {
                "status": safe_code(alignment_state.get("status")) or "corrupt",
                "alignmentVersion": (
                    alignment_state.get("alignmentVersion")
                    if type(alignment_state.get("alignmentVersion")) is int
                    else None
                ),
                "attemptId": safe_code(alignment_state.get("attemptId")),
                "workerPid": (
                    alignment_state.get("workerPid")
                    if type(alignment_state.get("workerPid")) is int
                    else None
                ),
                "workerStartedAtUnix": (
                    alignment_state.get("workerStartedAtUnix")
                    if type(alignment_state.get("workerStartedAtUnix")) is int
                    else None
                ),
                "evidenceType": safe_code(alignment_state.get("evidenceType")),
                "attemptedEvidence": [
                    code
                    for value in alignment_state.get("attemptedEvidence", [])
                    if (code := safe_code(value)) is not None
                ],
                "metrics": {
                    key: value
                    for key, value in (
                        alignment_state.get("metrics", {}).items()
                        if isinstance(alignment_state.get("metrics"), dict)
                        else ()
                    )
                    if safe_code(key, 120) is not None
                    and type(value) in {bool, int, float}
                    and (type(value) is not float or math.isfinite(value))
                },
                "reasons": [
                    code
                    for value in alignment_state.get("reasons", [])
                    if (code := safe_code(value, 120)) is not None
                ],
                "timedLineCount": (
                    alignment_state.get("timedLineCount")
                    if type(alignment_state.get("timedLineCount")) is int
                    else None
                ),
            }
        except (OSError, ValueError, json.JSONDecodeError):
            result['lyricsAlignmentProgress'] = {'status': 'corrupt',
                'source': workspace_relative(alignment_state_path), 'error': 'progress-record-invalid'}
    checkpoint_path = runtime / "work" / "checkpoints" / f"{job['videoId']}.json"
    if checkpoint_path.is_file():
        try:
            checkpoint = read_json(checkpoint_path)
            if not isinstance(checkpoint, dict) or safe_code(checkpoint.get('status')) is None:
                raise ValueError('invalid analysis progress record')
            stages = checkpoint.get("stages", {})
            if (not isinstance(stages, dict) or any(safe_code(name, 120) is None
                    or not isinstance(value, dict) or safe_code(value.get('status')) is None
                    for name, value in stages.items())):
                raise ValueError('invalid analysis stage progress')
            if isinstance(stages, dict):
                result["analysisProgress"] = {
                    "status": safe_code(checkpoint.get("status")) or "corrupt",
                    "updatedAt": checkpoint.get("updatedAt"),
                    "stages": {
                        name: safe_code(value.get("status")) or "corrupt"
                        for name, value in stages.items()
                        if safe_code(name, 120) is not None
                        and isinstance(value, dict)
                    },
                }
        except (OSError, ValueError, json.JSONDecodeError):
            result['analysisProgress'] = {'status': 'corrupt',
                'source': workspace_relative(checkpoint_path), 'error': 'progress-record-invalid'}
    return result


def aggregate_status_summary(summary: dict[str, Any]) -> dict[str, Any]:
    # Inventory/resume decisions need identities and action pointers; per-ID Status owns
    # the full validated diagnostics (which can contain megabytes of alignment evidence).
    result = {
        key: summary[key]
        for key in (
            "videoId", "state", "updatedAt", "sourceState", "deliveryAllowed",
            "job", "agentTask", "statusError", "failureDetails", "error", "deliveryVerification",
        )
        if key in summary
    }
    for field, keys in {
        "endpointReview": ("attemptId", "result", "declaration", "review"),
        "displayReview": ("attemptId", "result", "declaration", "original"),
        "lyricsAlignmentProgress": ("status", "attemptId", "workerPid", "workerStartedAtUnix"),
        "analysisProgress": ("status", "updatedAt"),
        "delivery": ("sha256", "bytes", "deliveredAt", "readyToImport"),
        "outcome": ("code", "state"),
    }.items():
        if summary["state"] == "DELIVERED" and field in {
            "endpointReview", "displayReview", "lyricsAlignmentProgress", "analysisProgress", "outcome",
        }:
            continue
        value = summary.get(field)
        if isinstance(value, dict):
            result[field] = {
                key: value[key] for key in keys
                if key in value and (value[key] is None or type(value[key]) in {str, int, float, bool})
            }
    return result

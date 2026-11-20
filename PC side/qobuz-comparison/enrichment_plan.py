"""Select retained recordings for enrichment without changing campaign state."""
import collections
import json
from pathlib import Path

import stemwise_media.quick_batch as batch

ROOT = Path(__file__).resolve().parent
from stemwise_engine.common import validate_video_id, sha256_file

from stemwise_engine.workflow_context import current_state_root, native_state_root
STATE = current_state_root(ROOT.parent / 'stemwise-playlist-builder/future-songs')


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def build_plan(library, catalogue, state=STATE):
    rows = {row['videoId']: row for row in catalogue['rows']}
    selections = library['playlist']
    if len(rows) != len(selections) or {row['videoId'] for row in selections} != set(rows):
        raise ValueError('Library selections must cover the exact catalogue')
    if len({row['videoId'] for row in selections}) != len(selections):
        raise ValueError('Duplicate playlist identity')
    selected_flac_ids = {row['trackId'] for row in selections if row['source'] == 'qobuz'}
    selected_youtube_ids = {row['videoId'] for row in selections if row['source'] == 'youtube'}

    previous = {}
    for row in selections:
        video_id = validate_video_id(row['videoId'])
        job_path = state / 'jobs' / video_id / 'job.json'
        checkpoint_path = state / 'runtime/work/checkpoints' / f'{video_id}.json'
        job = read(job_path) if job_path.exists() else {}
        checkpoint = read(checkpoint_path) if checkpoint_path.exists() else {}
        if any(value and value.get('videoId') != video_id for value in (job, checkpoint)):
            raise ValueError('Prior job/checkpoint identity differs')
        previous[video_id] = {
            'jobState': job.get('state'),
            'analysisComplete': checkpoint.get('status') == 'complete',
            'job': job_path.relative_to(ROOT.parent.parent).as_posix() if job else None,
            'checkpoint': checkpoint_path.relative_to(ROOT.parent.parent).as_posix() if checkpoint else None,
        }

    tasks = []
    flac_ids = set()
    for item in library['flac']:
        track_id = item['trackId']
        if type(track_id) is not int or track_id <= 0 or track_id in flac_ids:
            raise ValueError('Invalid or duplicate FLAC identity')
        flac_ids.add(track_id)
        if track_id not in selected_flac_ids:
            continue
        file = batch.qobuz_file(track_id)
        if file is None or file.resolve() != (ROOT / item['file']).resolve():
            raise ValueError('FLAC selection is missing, rejected, or stale')
        aliases = [row for row in selections if row['source'] == 'qobuz' and row['trackId'] == track_id]
        if any(row['file'] != item['file'] for row in aliases):
            raise ValueError('Playlist FLAC binding differs')
        related = [row['videoId'] for row in catalogue['rows']
                   if any(candidate['id'] == track_id for candidate in row['candidates'])]
        digest = sha256_file(file)
        native_root = native_state_root(state.parent.parent, {'trackId': track_id, 'sha256': digest})
        native_previous = {}
        for video_id in related or [item.get('lyricReferenceVideoId')]:
            if video_id is None:
                continue
            job_path = native_root / 'jobs' / video_id / 'job.json'
            checkpoint_path = native_root / 'runtime/work/checkpoints' / f'{video_id}.json'
            job = read(job_path) if job_path.exists() else {}
            checkpoint = read(checkpoint_path) if checkpoint_path.exists() else {}
            if any(value and value.get('videoId') != video_id for value in (job, checkpoint)):
                raise ValueError('Prior native job/checkpoint identity differs')
            if job and (job.get('recordingInput', {}).get('recordingKey') != f'qobuz:{track_id}'
                        or job['recordingInput'].get('sha256') != digest):
                raise ValueError('Prior native recording binding differs')
            if job or checkpoint:
                native_previous = {'jobState': job.get('state'),
                    'analysisComplete': checkpoint.get('status') == 'complete',
                    'job': job_path.relative_to(ROOT.parent.parent).as_posix() if job else None,
                    'checkpoint': checkpoint_path.relative_to(ROOT.parent.parent).as_posix() if checkpoint else None}
                break
        action = ('preserve_delivered' if native_previous.get('jobState') == 'DELIVERED' else
                  'resume_existing' if native_previous.get('jobState') else 'first_audio_analysis')
        tasks.append({
            'recordingKey': f'qobuz:{track_id}', **item,
            'source': 'qobuz',
            'action': action,
            'preferredPlaylistVideoIds': [row['videoId'] for row in aliases],
            'relatedPlaylistVideoIds': related,
            'lyricReferenceVideoId': item.get('lyricReferenceVideoId') or (related[0] if related else None),
            'previous': native_previous,
            'lyricReferenceHistory': {video_id: previous[video_id] for video_id in related},
            'reuseContent': 'check_existing_drafts_against_selected_recording',
            'reuseAudioResults': native_previous.get('analysisComplete', False),
            'reuseNativeAudioResults': 'only_intact_exact_native_pcm_cache',
            'additionalVersionOnly': not aliases,
            'execution': 'canonical_local_recording_enrichment',
        })

    youtube_ids = set()
    for item in library['youtube']:
        video_id = item['videoId']
        if video_id not in selected_youtube_ids:
            continue
        if video_id in youtube_ids or video_id not in previous:
            raise ValueError('Duplicate or unknown retained YouTube identity')
        youtube_ids.add(video_id)
        file = batch.source_file(video_id)
        if file is None or file.resolve() != (ROOT / item['file']).resolve():
            raise ValueError('YouTube selection is missing or stale')
        if any(row['source'] == 'youtube' and row['videoId'] == video_id and row['file'] != item['file']
               for row in selections):
            raise ValueError('Playlist YouTube binding differs')
        old = previous[video_id]
        action = ('preserve_delivered' if old['jobState'] == 'DELIVERED' else
                  'resume_existing' if old['jobState'] else 'first_enrichment')
        tasks.append({
            'recordingKey': f'youtube:{video_id}', **item,
            'source': 'youtube', 'action': action, 'previous': old,
            'reuseAudioResults': old['analysisComplete'],
            'execution': 'canonical_status_and_existing_gates',
        })

    ignored = []
    counts = collections.Counter()
    for row in selections:
        source, video_id = row['source'], row['videoId']
        if source == 'qobuz':
            if row['trackId'] not in flac_ids:
                raise ValueError('Selected FLAC is outside the wanted library')
            counts[next(task['action'] for task in tasks if task['recordingKey'] == f"qobuz:{row['trackId']}")] += 1
        elif source == 'youtube':
            if video_id not in youtube_ids:
                raise ValueError('Selected YouTube is outside the wanted library')
            counts['preserve_delivered' if previous[video_id]['jobState'] == 'DELIVERED' else
                   'resume_existing' if previous[video_id]['jobState'] else 'first_enrichment'] += 1
        elif source == 'unavailable' and rows[video_id].get('ownerRecordingDecision') == 'ignored_unavailable':
            ignored.append({'position': row['position'], 'videoId': video_id, 'reason': 'owner_ignored_unavailable'})
            counts['ignored_unavailable'] += 1
        else:
            raise ValueError('Unavailable selection has no owner disposition')

    return {
        'formatVersion': 1, 'kind': 'stemwise.retained-library-enrichment-plan',
        'purpose': 'work_selection_only_not_completion_or_campaign_policy',
        'fileBase': 'PC side/qobuz-comparison',
        'playlistCounts': dict(counts),
        'recordingCounts': dict(collections.Counter(task['action'] for task in tasks)),
        'audioRecordings': tasks, 'ignoredPlaylistEntries': ignored,
        'recordingAnalysis': {
            'analyze': "& '.\\PC side\\STEMWISE_AGENT.ps1' -Action AnalyzeRecording -RecordingKey 'qobuz:TRACK_ID'",
            'status': "& '.\\PC side\\STEMWISE_AGENT.ps1' -Action RecordingStatus -RecordingKey 'qobuz:TRACK_ID'",
            'scope': ['waveform', 'rhythm', 'key', 'structure', 'tuning', 'loudness'],
            'lyricsTimed': False, 'phoneDeliveryAllowed': False,
        },
        'recordingEnrichment': {
            'new': "& '.\\PC side\\STEMWISE_AGENT.ps1' -Action New -RecordingKey 'qobuz:TRACK_ID'",
            'status': "& '.\\PC side\\STEMWISE_AGENT.ps1' -Action Status -RecordingKey 'qobuz:TRACK_ID'",
            'actions': ['Task', 'Retry', 'Source', 'Artwork', 'RecordingEvidence', 'Validate',
                        'EndpointEvidence', 'EndpointAdjudicate', 'Finalize'],
            'scope': ['native_audio_analysis', 'recording_content_review', 'vocal_separation',
                      'lyric_line_and_word_alignment', 'display_review', 'recording_scoped_result',
                      'source_bound_android_import'],
            'phoneImportSupported': True,
            'phoneDeliveryRequiresMatchingOriginal': True,
            'phoneDeliveryStatus': 'Canonical per-recording Status verifies actual phoneDeliveries; this plan never grants delivery.',
            'reuse': 'New reuses exact-native-PCM acoustic cache, valid text drafts and intact local lyric research; skip acoustic pre-passes and never inherit timing or approval.',
        },
        'reuseRules': {
            'preserve': ['selected_native_downloads', 'owner_notes', 'lyric_sources', 'editorial_drafts', 'historical_packages_and_checkpoints'],
            'supersededYouTube': 'Retirement is explicit; planning and export preserve audio and additional owner versions.',
            'content': 'Reuse wording, translation and IPA where valid; verify changed wording/order/version, never reauthor unchanged text.',
            'changedAudio': ['waveform', 'rhythm', 'key', 'structure', 'vocal_separation', 'lyric_line_and_word_timing'],
            'sharedFiles': 'Analyze each exact retained FLAC once; aliases share acoustic work, not invented lyric or recording approvals.',
            'unchangedYouTube': 'Use canonical Status/Validate/Retry; preserve completed analysis when its existing binding passes.',
            'completion': 'The plan grants no bypass or delivery authority; do not force-rebuild completed jobs, substitute YouTube for selected FLAC, or mark these tasks completed.',
        },
    }


def save_plan():
    catalogue = read(ROOT / 'catalogue.json')
    plan = build_plan(batch.build_library(catalogue), catalogue, STATE)
    destination = ROOT / 'comparison-results/enrichment-plan.json'
    batch.write_json(destination, plan)
    return {'plan': str(destination.resolve()), 'playlistCounts': plan['playlistCounts'],
            'recordingCounts': plan['recordingCounts'], 'analysisStarted': False,
            'localAudioAnalysisAvailable': True, 'localAudioCampaignIntegration': True}

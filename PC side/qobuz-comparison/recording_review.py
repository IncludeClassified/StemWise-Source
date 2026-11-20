"""Review every retained recording at 48 kHz and list unmatched files on both sides."""
from stemwise_engine.common import WorkerRetirementError
import collections
import copy
import csv
import io
import json
import re
import sys
from threading import Event
import time
import unicodedata
from concurrent.futures import ThreadPoolExecutor, as_completed

import stemwise_media.quick_batch as batch
from stemwise_media.compare import probe
from stemwise_media.recording_equivalence import supported

MATCHES = {'strong_recording_match', 'matching_body_with_extra_audio',
           'matching_body_with_edge_edits', 'matching_body_with_speed_change'}


def confirmed(result):
    if result.get('classification') == 'matching_body_with_speed_change':
        return confirmed(result.get('alignedWaveform', {}))
    if result.get('classification') not in MATCHES:
        return False
    if result['classification'] == 'strong_recording_match' and result.get('fullContentSupported') is not True:
        return False
    channels = result.get('body', result).get('channels', [])
    return ({c.get('channel') for c in channels} == {'left', 'right'}
            and any(c.get('windows', 0) > 0 for c in channels)
            and all((c.get('windows', 0) > 0 or c.get('mutuallySilentWindows', 0) > 0)
                    and c.get('minimumCorrelation', 0) >= .97 for c in channels))


def review_tracks(row, tracks):
    def key(track):
        title = re.split(r'\s*\(|\s+feat\.?\s|\s+ft\.?\s', track['title'], 1, flags=re.I)[0]
        return tuple(''.join(c for c in unicodedata.normalize('NFKD', value).casefold() if c.isalnum())
                     for value in (track['artist'], title))
    ids = {t['id'] for t in row['candidates']} | set(row.get('flacNameMatch', {}).get('trackIds', []))
    rejected = set(row.get('rejectedTrackIds', []))
    ids -= rejected
    keys = {key(tracks[i]) for i in ids if i in tracks}
    return [t for i, t in tracks.items() if i not in rejected and (i in ids or key(t) in keys)]


def review_row(row, tracks, *, evidence=None):
    import stemwise_media.bounded_pcm as pcm_helpers
    from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
    pcm_helpers.check_budget()
    reference = batch.source_file(row['videoId'])
    available = [(t, evidence.candidate(t['id']) if evidence is not None else batch.qobuz_file(t['id'])) for t in tracks]
    available = [(t, f) for t, f in available if f]
    previous = row.get('refinedAudioComparison', {})
    reference_sha = batch.file_sha256(reference) if reference else None
    reusable = (reference and previous.get('sampleRate') == 48000
                and previous.get('recipeVersion') == 1
                and previous.get('referenceFile') == reference.relative_to(batch.ROOT).as_posix()
                and previous.get('referenceSha256') == reference_sha)
    cached = {c['trackId']: c for c in previous.get('candidates', [])} if reusable else {}
    results, measured, pcm = [], 0, None
    if reference and available:
        for track, path in available:
            pcm_helpers.check_budget()
            candidate_sha = batch.file_sha256(path)
            old = cached.get(track['id'])
            if (old and old.get('candidateFile') == path.relative_to(batch.ROOT).as_posix()
                    and old.get('candidateSha256') == candidate_sha
                    and (confirmed(old) or previous.get('clockDriftReview') == 2 and (
                        old.get('reason') == 'Distributed audio anchors disagree'
                        or old.get('reason') == 'Dense waveform comparison differs' and 'differingWindows' in old))):
                results.append(old)
                continue
            try:
                if pcm is None:
                    pcm = batch.decode(reference, 48000)
                candidate = batch.decode(path, 48000)
                result = batch.analyze_waveforms(pcm, candidate, 48000,
                                                allow_edge_edits=True, allow_fractional_delay=True)
                if result['classification'] not in MATCHES:
                    drift = batch.analyze_clock_drift(pcm, candidate, 48000)
                    if drift['classification'] == 'matching_body_with_speed_change':
                        result = drift
                del candidate
                if batch.file_sha256(path) != candidate_sha:
                    raise ValueError('Compared candidate audio changed')
            except (ProviderCancelled, ProviderDeadline):
                raise
            except WorkerRetirementError:
                raise
            except Exception as error:
                result = {'classification': 'error', 'reason': batch.safe_error(error)}
            measured += 1
            results.append({'trackId': track['id'], 'candidateFile': path.relative_to(batch.ROOT).as_posix(),
                            'candidateSha256': candidate_sha, **result})
    if reference and batch.file_sha256(reference) != reference_sha:
        raise ValueError('Compared reference audio changed')
    status, winner = batch.decision(results) if results else ('not_checked', None)
    codec = probe(reference)['codec_name'] if reference and available else None
    return {'status': status, 'matchedTrackId': winner, 'sampleRate': 48000,
            'recipeVersion': 1,
            'referenceFile': reference.relative_to(batch.ROOT).as_posix() if reference else None,
            'referenceSha256': reference_sha,
            'sourceCodec': codec, 'fullFrequencyMixVerified': False, 'recordingReview': 1,
            'edgeReview': True, 'fractionalDelayReview': 3, 'alternativeReview': True, 'clockDriftReview': 2,
            'unavailableReason': 'Source audio unavailable' if not reference else 'No retained candidate' if not available else None,
            'candidates': results}, measured


def current_matches(row, *, evidence=None):
    evidence = evidence or batch.ComparisonEvidence()
    matches = []
    for candidate in row.get('refinedAudioComparison', {}).get('candidates', []):
        if not confirmed(candidate) or candidate['trackId'] in row.get('rejectedTrackIds', []):
            continue
        try:
            binding = batch.current_comparison_binding(row, candidate['trackId'], evidence=evidence)
        except (OSError, ValueError):
            binding = None
        if binding is not None:
            matches.append(candidate)
    return matches


def paired_tracks(data, *, evidence=None):
    return {candidate['trackId'] for row in data['rows'] for candidate in current_matches(row, evidence=evidence)}


def equivalences(row, *, evidence=None, admitted_track_ids=None):
    evidence = evidence or batch.ComparisonEvidence()
    result = row.get('recordingEquivalence', {})
    if result.get('sampleRate') != 8000 or not row.get('videoId'):
        return []
    source = evidence.source(row['videoId'])
    if (source is None or result.get('referenceFile') != source.relative_to(batch.ROOT).as_posix()
            or result.get('recipeVersion') != 1
            or result.get('referenceSha256') != evidence.digest(source)):
        return []
    matches = []
    for candidate in result.get('candidates', []):
        if admitted_track_ids is not None and candidate['trackId'] not in admitted_track_ids:
            continue
        if candidate['trackId'] in row.get('rejectedTrackIds', []) or not supported(candidate):
            continue
        path = evidence.candidate(candidate['trackId'])
        if (path and candidate.get('candidateFile') == path.relative_to(batch.ROOT).as_posix()
                and candidate.get('candidateSha256') == evidence.digest(path)):
            matches.append(candidate)
    return matches


def needs_review(row, *, evidence=None, admitted_track_ids=None):
    return not (owner_selection_is_current(row)
                or current_matches(row, evidence=evidence)
                or equivalences(row, evidence=evidence, admitted_track_ids=admitted_track_ids))


def owner_selection_is_current(row):
    if not row.get('ownerAudioSelection'):
        return False
    note = row.get('ownerRecordingNote')
    # Preserve direct owner selections; historical prose-derived selections await explicit review.
    return note is None or (isinstance(note, dict) and note.get('decisionFormatVersion') == 1
        and note.get('identity') == f"youtube-{row['position']}-{row['videoId']}"
        and note.get('decision') in {'keep_youtube', 'accepted_versions', 'accepted_version',
                                    'accepted_title_link', 'same_recording'})


def handled_flac(data, *, evidence=None, admitted_track_ids=None):
    ids = paired_tracks(data, evidence=evidence) | {c['trackId'] for row in data['rows'] for c in equivalences(row, evidence=evidence, admitted_track_ids=admitted_track_ids)}
    for row in data['rows']:
        choice = row.get('ownerAudioSelection', {}) if owner_selection_is_current(row) else {}
        ids.update(r['trackId'] for r in choice.get('reviews', []))
        ids.update(r['trackId'] for r in choice.get('additionalVersions', []) if r['source'] == 'qobuz')
        if choice.get('trackId'):
            ids.add(choice['trackId'])
    ids.update(int(i) for i, note in data.get('ownerTrackNotes', {}).items()
        if note.get('decisionFormatVersion') == 1 and note.get('identity') == f'flac-{i}'
        and note.get('decision') in {'accepted_version', 'accepted_versions', 'accepted_title_link', 'same_recording'})
    return ids


def candidate_problem(candidate):
    if supported(candidate):
        note = 'الأداء نفسه مدعوم عبر معظم الأغنية بعد تعويض فرق القنوات والميكس؛ لا يعني تطابق الماستر أو جميع التفاصيل. نوافذ الاختلاف محفوظة في النتيجة.'
        if candidate.get('compensation') == 'fixed_stereo_filter':
            note += ' استُخدم مرشح ثابت للأغنية كلها لتعويض فرق الماستر والتأخير بين العينات.'
        if candidate.get('candidateTimeScale'):
            note += ' يتطلب الربط تعويض فرق سرعة ثابت؛ توقيت النسختين مختلف.'
        return note
    if candidate.get('reason') == 'Only parts of the performance agree':
        return (f"نوافذ الاتفاق في القناتين: {candidate.get('supportedWindowFraction', 0):.1%}. "
                'بقيت اختلافات داخل التسجيل بعد المحاذاة والتعويض؛ لم يجتز شروط دعم الأداء نفسه.')
    if candidate.get('reason') == 'No consistent distributed performance alignment':
        return 'لم تدعم المقاطع الموزعة محاذاة واحدة للتسجيل؛ لا يكفي تشابه الموسيقى أو مقطع واحد للحسم.'
    reason = candidate.get('reason', '')
    anchors = candidate.get('anchors', [])
    channels = candidate.get('channels', [])
    if reason == 'Dense waveform comparison differs':
        spans = '; '.join(f"{r['referenceStartSeconds']:.2f}–{r['referenceEndSeconds']:.2f} ثانية" for r in candidate.get('differingWindows', []))
        explanation = ('التوافق قوي في أجزاء واسعة، لكن توجد اختلافات داخل التسجيل.'
                       if channels and all(c['medianCorrelation'] >= .97 for c in channels)
                       else 'بعد ضبط التوقيت، لا تتطابق نوافذ الصوت في إحدى القناتين أو كلتيهما.')
        if spans:
            explanation += ' نوافذ الفحص في يوتيوب: ' + spans
    elif reason == 'Distributed audio anchors disagree':
        scores = [a['correlation'] for a in anchors]
        offsets = [a['offsetSeconds'] for a in anchors]
        if len(anchors) == 3 and min(scores) >= .85 and max(offsets) - min(offsets) <= .02:
            explanation = f'التوقيت ثابت تقريباً، لكن تطابق المقاطع {min(scores):.3f}–{max(scores):.3f} أقل من حد التأكيد 0.950؛ قد يفسره اختلاف ميكس/ماستر/ضغط، ولا يثبت أداءً مختلفاً.'
        else:
            explanation = 'لم تتفق ثلاثة مقاطع موزعة على تسجيل واحد بتوقيت ثابت؛ الإصدار أو الميكس أو ترتيب المحتوى قد يكون مختلفاً.'
    elif candidate.get('classification') == 'error':
        explanation = 'تعذر فك الصوت أو فحصه: ' + reason
    else:
        explanation = 'لم يجتز شروط التطابق: ' + (reason or 'لا توجد نتيجة صوتية مؤكدة.')
    return explanation


def problem(row, tracks, *, evidence=None, admitted_track_ids=None):
    if owner_selection_is_current(row):
        notes = [row.get('ownerRecordingNote', {}).get('note', '')]
        notes += [r.get('note', '') for r in row['ownerAudioSelection'].get('reviews', [])]
        if row.get('rejectedTrackIds'):
            notes.append('حُذفت نسخة FLAC بطلبك.' if row.get('ownerRecordingDecision') == 'keep_youtube'
                         else 'نسخة FLAC السابقة خاطئة؛ رفضها المالك وحُذفت.')
        return 'محسوم باختيارك السابق؛ لا يحتاج إعادة مراجعة. ' + ' / '.join(dict.fromkeys(n.strip() for n in notes if n.strip()))
    if equivalences(row, evidence=evidence, admitted_track_ids=admitted_track_ids):
        return candidate_problem(equivalences(row, evidence=evidence, admitted_track_ids=admitted_track_ids)[0])
    result = row.get('refinedAudioComparison', {})
    if result.get('unavailableReason'):
        if result['unavailableReason'] == 'Source audio unavailable':
            metadata = batch.ROOT / 'audio/youtube' / row['videoId'] / 'download.json'
            error = json.loads(metadata.read_text(encoding='utf-8')).get('error') if metadata.is_file() else None
            return 'ملف يوتيوب غير متوفر محلياً؛ تعذرت المقارنة.' + (' آخر محاولة تنزيل: ' + batch.safe_error(error) if error else '')
        if row.get('rejectedTrackIds'):
            return 'نسخة FLAC السابقة خاطئة؛ رفضها المالك وحُذفت، ولا يوجد بديل مقبول محفوظ.'
        if row.get('metadataSelection', {}).get('basis') == 'multi_song_compilation_or_concert_not_one_catalogue_track':
            return 'حفلة أو تجميع؛ ليست أغنية واحدة، ولم نجد ملف FLAC يقابل التسجيل الكامل.'
        note = row.get('flacNameMatch', {}).get('note', '')
        return 'لا توجد نسخة FLAC مقبولة محفوظة لهذه الأغنية.' + (' ' + note if note else '')
    candidates = result.get('candidates', [])
    matched = current_matches(row, evidence=evidence)
    if matched:
        kinds = {c['classification'] for c in matched}
        notes = []
        if 'strong_recording_match' in kinds:
            notes.append('اجتاز التسجيل كاملًا فحص القناتين.')
        if 'matching_body_with_edge_edits' in kinds:
            notes.append('التسجيل الأساسي متطابق؛ تختلف نافذة البداية أو النهاية.')
        if 'matching_body_with_extra_audio' in kinds:
            notes.append('التسجيل المشترك متطابق؛ توجد مادة صوتية إضافية خارجه.')
        if 'matching_body_with_speed_change' in kinds:
            notes.append('التسجيل متطابق بعد تعويض فرق سرعة ثابت؛ التوقيت الأصلي مختلف.')
        return ' '.join(notes)
    if any(confirmed(candidate) for candidate in candidates):
        return 'المقارنة المحفوظة غير صالحة للملفات الحالية؛ يلزم فحص صوتي جديد، والنتيجة السابقة محفوظة.'
    notes = [r['note'].strip().replace('\n', ' ') for r in row.get('ownerAudioSelection', {}).get('reviews', []) if r.get('note')]
    strongest = max(candidates, key=lambda c: min((a['correlation'] for a in c.get('anchors', [])), default=0), default={})
    track = tracks.get(strongest.get('trackId'))
    label = '#' + str(strongest.get('trackId', '?'))
    if track:
        label += f" — {track['artist']} — {track['title']} ({track.get('version') or track['album']})"
    explanation = 'أفضل مرشح من المقارنات: ' + label + '. ' + candidate_problem(strongest)
    if notes:
        explanation += ' ملاحظة المالك: ' + ' / '.join(dict.fromkeys(notes))
    return explanation


def export_review(data, *, evidence=None):
    with batch.catalogue_transaction(batch.ROOT/'catalogue.json'), batch.exclusive_process_lock(
            batch.ROOT/'comparison-results/export.lock'):
        batch._recover_publications(batch.ROOT/'comparison-results')
        path = batch.ROOT/'catalogue.json'
        return _export_review_locked(batch.read_catalogue(path) if path.exists() else data, evidence or batch.ComparisonEvidence())


def _export_review_locked(data, evidence):
    output = batch.ROOT / 'comparison-results'
    library = json.loads((output / 'library-files.json').read_text(encoding='utf-8'))
    tracks = {t['trackId']: t for t in library['flac']}
    admitted_track_ids = tracks.keys()
    pairs = paired_tracks(data, evidence=evidence)
    handled = handled_flac(data, evidence=evidence, admitted_track_ids=admitted_track_ids)
    unmatched, differences = [], []
    stream = io.StringIO(newline='')
    writer = csv.writer(stream)
    writer.writerow(['الترتيب', 'الأغنية', 'ترميز يوتيوب', 'نتيجة التسجيل', 'FLAC المتطابق صوتياً', 'المشكلة أو الاختلاف', 'ملف يوتيوب', 'FLAC من الأداء نفسه مع اختلافات', 'يحتاج مراجعة'])
    for row in data['rows']:
        result = row.get('refinedAudioComparison', {})
        matches = current_matches(row, evidence=evidence)
        title = row.get('ownerDisplayTitle') or row.get('sourceMetadata', {}).get('title') or row.get('title') or row['videoId']
        entry = (row['position'], title, problem(row, tracks, evidence=evidence, admitted_track_ids=admitted_track_ids))
        if needs_review(row, evidence=evidence, admitted_track_ids=admitted_track_ids):
            unmatched.append(entry)
        elif matches and not any(c['classification'] == 'strong_recording_match' for c in matches):
            differences.append(entry)
        if matches:
            status = batch.decision(matches)[0]
        elif (result.get('status') in MATCHES | {'matched'}
              or any(confirmed(c) for c in result.get('candidates', []))):
            status = 'stale'
        else:
            status = result.get('status', 'not_checked')
        writer.writerow([row['position'], title, result.get('sourceCodec') or '', status,
            '; '.join(f"#{c['trackId']} {tracks[c['trackId']]['title']} ({tracks[c['trackId']].get('version') or tracks[c['trackId']]['album']})" for c in matches if c['trackId'] in tracks), entry[2], result.get('referenceFile') or '',
            '; '.join(str(c['trackId']) for c in equivalences(row, evidence=evidence, admitted_track_ids=admitted_track_ids)), 'نعم' if needs_review(row, evidence=evidence, admitted_track_ids=admitted_track_ids) else 'لا'])
    report = ['# التسجيلات غير المتطابقة والاختلافات', '',
              'القائمة تقتصر على ما لم يحسمه المالك أو المقارنة الصوتية. تبقى نتائج 48 kHz الصارمة منفصلة عن قرارات المالك وعن فحص الأداء مع اختلاف الميكس. البحث بالبصمة يشمل كل الملفات ولا يعتمد على العنوان.', '',
              'عدم اجتياز التطابق لا يثبت أداءً مختلفاً: قد يكون السبب ماستراً أو ميكساً أو تعديلاً أو ضغطاً مختلفاً. مواضع الاختلاف هي نوافذ فحص مدتها ثانيتان، وليست حدود التعديل الدقيقة. الملفات الأصلية محفوظة.', '',
              f'المتبقي فعليًا: {len(unmatched)} بند يوتيوب و{len(tracks.keys() - handled)} ملف FLAC. يتضمن ذلك نقص الملفات المتاحة، وليس كله بحاجة إلى استماع.', '',
              f'الفحص الصارم وحده يترك {sum(not current_matches(r, evidence=evidence) for r in data["rows"])} بند يوتيوب و{len(tracks.keys() - pairs)} ملف FLAC؛ هذه أعداد فنية، وليست قائمة مهام المالك.', '',
              '[نتائج جميع بنود البلايليست](recording-matches.csv)', '']
    report += ['## يوتيوب: المتبقي دون قرار أو مقابل', '']
    for position, title, note in unmatched:
        report += [f'- **{position} — {title}**: {note}']
    report += ['']
    report += ['## FLAC: المتبقي دون قرار أو مقابل', '']
    for track_id in sorted(tracks.keys() - handled):
        track = tracks[track_id]
        related = [(row, c) for row in data['rows'] for c in row.get('refinedAudioComparison', {}).get('candidates', []) + row.get('recordingEquivalence', {}).get('candidates', []) if c['trackId'] == track_id]
        versions = track.get('version') or track['album']
        report += [f"- **#{track_id} — {track['artist']} — {track['title']} ({versions})**: "
                   + ('لا يوجد مقابل مؤكد؛ هذا لا يعني أن ملف FLAC تالف.' if related else 'لم تُجرَ مقارنة صوتية لهذا الإصدار مع مصدر يوتيوب متوفر.')]
        explanations = collections.defaultdict(list)
        for row, candidate in related:
            admitted = current_matches(row, evidence=evidence) + equivalences(row, evidence=evidence, admitted_track_ids=admitted_track_ids)
            note = ('المقارنة المحفوظة غير صالحة للملفات الحالية؛ يلزم فحص جديد.'
                    if (confirmed(candidate) or supported(candidate)) and candidate not in admitted
                    else candidate_problem(candidate))
            explanations[note].append(row['position'])
        for note, positions in explanations.items():
            report += [f"  - مقارنة ببنود {', '.join(map(str, positions))}: {note}"]
    evidence.require_unchanged()
    batch._publish_comparison_reports(output, {
        'recording-matches.csv': stream.getvalue().encode('utf-8-sig'),
        'unmatched-recordings.txt': ('\n'.join(report) + '\n').encode('utf-8')})
    performance_pairs = pairs | {c['trackId'] for row in data['rows'] for c in equivalences(row, evidence=evidence, admitted_track_ids=admitted_track_ids)}
    return {'unmatchedYouTubeRows': len(unmatched), 'matchingWithDifferences': len(differences),
            'pairedFlacFiles': len(tracks.keys() & pairs), 'performancePairedFlacFiles': len(tracks.keys() & performance_pairs),
            'handledFlacFiles': len(tracks.keys() & handled), 'unmatchedFlacFiles': len(tracks.keys() - handled)}


def main():
    data = batch.read_catalogue(batch.ROOT / 'catalogue.json')
    evidence = batch.ComparisonEvidence()
    candidates = {t['id']: t for row in data['rows'] for t in row['candidates']}
    tracks, unavailable = {}, {}
    for track_id, track in candidates.items():
        try:
            if evidence.candidate(track_id) is None:
                raise ValueError('Retained candidate is unavailable')
            tracks[track_id] = {**track, **data.get('ownerTrackMetadata', {}).get(str(track_id), {})}
        except (OSError, ValueError) as error:
            unavailable[track_id] = batch.safe_error(error)
    started, measured, completed, pending, failed_rows = time.monotonic(), 0, 0, 0, 0
    cancelled = Event()
    fatal = []
    def run(row):
        import stemwise_media.bounded_pcm as pcm
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        try:
            with pcm.comparison_operation(cancelled=cancelled.is_set):
                bound = {candidate['trackId'] for key in ('quickAudioComparison', 'refinedAudioComparison', 'recordingEquivalence')
                    for candidate in row.get(key, {}).get('candidates', [])
                    if 'candidateFile' in candidate or 'candidateSha256' in candidate}
                selected = row.get('ownerAudioSelection', {})
                if selected.get('source') == 'qobuz':
                    bound.add(selected['trackId'])
                if any(track['id'] in unavailable and track['id'] not in row.get('rejectedTrackIds', [])
                       and (track['id'] in bound or (batch.ROOT/'audio/qobuz'/str(track['id'])/'download.json').exists())
                       for track in row['candidates']):
                    raise ValueError('A required recording is unavailable; prior evidence retained')
                if (row['candidates'] or row.get('refinedAudioComparison')) and evidence.source(row['videoId']) is None:
                    raise ValueError('Source audio unavailable; prior evidence retained')
                result, count = review_row(row, review_tracks(row, tracks), evidence=evidence)
                return result, count, None
        except (ProviderCancelled, ProviderDeadline):
            raise
        except WorkerRetirementError as error:
            fatal.append(error)
            cancelled.set()
            raise
        except (OSError, ValueError) as error:
            return None, 0, batch.safe_error(error)
    pool = ThreadPoolExecutor(max_workers=2)
    try:
        futures = {pool.submit(run, copy.deepcopy(row)): row['position'] for row in data['rows']}
        rows = {row['position']: row for row in data['rows']}
        for future in as_completed(futures):
            row = rows[futures[future]]
            result, count, unavailable_reason = future.result()
            if result is None:
                failed_rows += 1
                print(json.dumps({'position':row['position'],'status':'unavailable','reason':unavailable_reason}), flush=True)
                continue
            measured += count
            row['refinedAudioComparison'] = result
            existing = {t['id'] for t in row['candidates']}
            row['candidates'] += [copy.deepcopy(tracks[c['trackId']]) for c in result['candidates'] if c['trackId'] not in existing]
            completed += 1
            pending += 1
            if pending >= 16:
                batch.write_json(batch.ROOT / 'catalogue.json', data)
                pending = 0
            print(json.dumps({'completed': completed, 'total': len(data['rows']), 'position': row['position'],
                    'status': result['status'], 'measuredPairs': measured, 'elapsedSeconds': round(time.monotonic() - started)}), flush=True)
    except BaseException:
        if pending:
            try:
                batch.write_json(batch.ROOT / 'catalogue.json', data)
            except WorkerRetirementError:
                raise
            except Exception as commit_error:
                # Preserve the interruption; a concurrent owner conflict remains explicit.
                print('Completed review batch not committed: '+batch.safe_error(commit_error), file=sys.stderr)
        if fatal:
            raise fatal[0]
        raise
    finally:
        cancelled.set()
        pool.shutdown(wait=True, cancel_futures=True)
    if pending:
        batch.write_json(batch.ROOT / 'catalogue.json', data)
    evidence.require_unchanged()
    batch.export(data, evidence=evidence)
    summary = export_review(data, evidence=evidence)
    print(json.dumps(summary | {'new48khzPairs': measured, 'elapsedSeconds': round(time.monotonic() - started)}), flush=True)
    return int(bool(failed_rows) or any(c.get('classification') == 'error' for row in data['rows']
        for c in row.get('refinedAudioComparison', {}).get('candidates', [])))


if __name__ == '__main__':
    raise SystemExit(main())

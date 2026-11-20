"""Export playlist/song-title availability separately from recording identity."""
import collections
import csv
import json
import re
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent
LABELS = {'retained_version': 'توجد نسخة FLAC محفوظة', 'not_found': 'لم نجد FLAC مقبولاً',
          'compilation': 'حفلة أو تجميع أغانٍ', 'unidentified': 'عنوان المصدر غير معروف'}


def normalized(text):
    return ''.join(c for c in unicodedata.normalize('NFKD', text or '').casefold() if c.isalnum())


def name_rows(data, library):
    retained = {t['trackId']: t for t in library['flac']}
    audio_links = {r['position']: r.get('performanceTrackIds', []) for r in library.get('playlist', [])}
    results = []
    for row in data['rows']:
        selection = row.get('metadataSelection', {})
        basis = selection.get('basis', '')
        override = row.get('flacNameMatch')
        ids = override['trackIds'] if override else [t['id'] for t in row['candidates']]
        if not override and basis == 'matching_artist_title_and_rendition_not_located_in_checked_catalogue':
            ids = []  # A same-title candidate with no established performer is not a song match.
        ids = list(ids) + audio_links.get(row['position'], [])
        ids = sorted(set(ids).intersection(retained) - set(row.get('rejectedTrackIds', [])))
        status = 'retained_version' if ids else 'not_found'
        if basis == 'source_metadata_unavailable':
            status = 'unidentified'
        elif basis == 'multi_song_compilation_or_concert_not_one_catalogue_track':
            status = 'compilation'
        title = (row.get('ownerDisplayTitle') or row.get('sourceMetadata', {}).get('title')
                 or row.get('title') or row['videoId'])
        note = override.get('note', '') if override else ''
        if set(ids) & set(audio_links.get(row['position'], [])):
            note += (' ' if note else '') + 'ربط مدعوم بمقارنة الصوت مع السماح باختلاف الميكس؛ لا يثبت تطابق الماستر.'
        if ids and not note:
            title_matches = any(normalized(re.split(r'\s*\(|\s+feat\.?\s|\s+ft\.?\s',
                    retained[i].get('title', ''), 1, flags=re.I)[0]) in normalized(title) for i in ids)
            note = ('اسم الأغنية متوافق؛ راجع وصف الإصدار والفنان أدناه.' if title_matches
                    else 'الربط السابق يستخدم اختلاف تهجئة أو ترجمة أو اسم ألبوم؛ لا يثبت الاسم وحده تطابق التسجيل.')
        if row.get('rejectedTrackIds'):
            note = ('حذفنا نسخة FLAC بطلب المالك؛ نسخة يوتيوب هي الاختيار المعتمد.'
                    if row.get('ownerRecordingDecision') == 'keep_youtube'
                    else 'نسخة FLAC السابقة خاطئة وحذفناها بطلب المالك؛ لم نعتمد بديلاً.')
        results.append({'position': row['position'], 'videoId': row['videoId'], 'title': title,
                'originalTitle': row.get('sourceMetadata', {}).get('title') or row.get('title') or '',
                'status': status, 'availableTrackIds': ids, 'note': note})
    return results


def export_names(data, library, output):
    rows = name_rows(data, library)
    tracks = {t['trackId']: t for t in library['flac']}
    with (output / 'playlist-names.csv').open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(['الترتيب', 'عنوان البلايليست', 'العنوان الأصلي', 'الحالة', 'معرفات FLAC',
                         'أسماء FLAC والفنان والإصدار', 'ملاحظات', 'YouTube'])
        for row in rows:
            versions = [f"{tracks[i]['artist']} — {tracks[i]['title']} — {tracks[i].get('version') or 'راجع الألبوم'} — {tracks[i]['album']}" for i in row['availableTrackIds']]
            writer.writerow([row['position'], row['title'], row['originalTitle'], LABELS[row['status']],
                ';'.join(map(str, row['availableTrackIds'])), '; '.join(versions), row['note'],
                'https://www.youtube.com/watch?v=' + row['videoId']])
    with (output / 'flac-names.csv').open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(['معرف Qobuz', 'اسم FLAC', 'الفنان', 'الإصدار', 'الألبوم', 'ترتيب البلايليست', 'عناوين البلايليست', 'الملف'])
        for track in library['flac']:
            related = [r for r in rows if track['trackId'] in r['availableTrackIds']]
            writer.writerow([track['trackId'], track['title'], track['artist'], track.get('version') or '',
                track['album'], ';'.join(str(r['position']) for r in related), '; '.join(r['title'] for r in related), track['file']])
    counts = dict(collections.Counter(r['status'] for r in rows))
    report = ['# أغاني البلايليست التي لم نجد لها FLAC مقبولاً', '',
              f"القائمة تشمل {len(rows)} بنداً و{len(tracks)} ملف FLAC موسيقياً محفوظاً. توجد نسخة محفوظة لـ{counts.get('retained_version', 0)} بنداً؛ هذا عدد بنود البلايليست وليس عدد الأغاني الفريدة.", '',
              'هذه مراجعة أسماء وفنانين وإصدارات باستخدام الكتالوج المفحوص والملفات المحفوظة وتصحيحات المالك. ليست فحصاً صوتياً جديداً ولا إثباتاً لعدم وجود FLAC في العالم. ملف بنفس الاسم لفنان آخر لا يكفي. وجود إصدار آخر لا يثبت تطابق تسجيل يوتيوب.', '',
              '[القائمة الكاملة للبلايليست](playlist-names.csv) · [مقارنة كل ملف FLAC بالأسماء](flac-names.csv)', '']
    for status, heading in [('not_found', 'أغانٍ لم نجد لها نسخة مقبولة'),
                            ('compilation', 'تسجيلات طويلة: حفلة أو تجميع'), ('unidentified', 'مصدر لا نعرف عنوانه')]:
        report += ['## ' + heading, '']
        for row in rows:
            if row['status'] == status:
                report += [f"- **{row['position']} — {row['title']}** — [يوتيوب](https://www.youtube.com/watch?v={row['videoId']})" + (f". {row['note']}" if row['note'] else '')]
        report += ['']
    (output / 'missing-flac.txt').write_text('\n'.join(report) + '\n', encoding='utf-8')
    data['flacNameAvailabilityCounts'] = counts
    return counts


if __name__ == '__main__':
    catalogue = json.loads((ROOT / 'catalogue.json').read_text(encoding='utf-8'))
    library = json.loads((ROOT / 'comparison-results/library-files.json').read_text(encoding='utf-8'))
    print(json.dumps(export_names(catalogue, library, ROOT / 'comparison-results'), ensure_ascii=False))

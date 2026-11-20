"""Native Opus/FLAC downloads and conservative full-recording comparisons."""
import argparse
import json
import re
import sys
import urllib.parse
from pathlib import Path

import stemwise_media.quick_batch as batch
from stemwise_media.compare import analyze_clock_drift, analyze_waveforms, decode, probe
from stemwise_media.doubledouble import CaptchaRequired, download as download_qobuz
from stemwise_media.recording_equivalence import RATE, analyze_equivalence, supported

ROOT = Path(__file__).resolve().parent
from stemwise_engine.common import validate_video_id, extract_youtube_video_id
from stemwise_engine.enrichment_constants import MAX_DURATION_MS, MAX_RECORDING_TRACK_ID

MAX_DURATION_SECONDS = MAX_DURATION_MS / 1000

STRICT_MATCHES = {'strong_recording_match', 'matching_body_with_extra_audio', 'matching_body_with_edge_edits'}


def youtube_id(value):
    return extract_youtube_video_id(value)


def qobuz_id(value):
    if re.fullmatch('[1-9][0-9]*', value):
        track = int(value)
        if track <= MAX_RECORDING_TRACK_ID:
            return track
        raise ValueError('Qobuz track ID exceeds the package contract maximum')
    url = urllib.parse.urlsplit(value)
    if url.scheme == 'https' and url.netloc == 'play.qobuz.com' and re.fullmatch('/track/[1-9][0-9]*', url.path):
        return qobuz_id(url.path.rsplit('/', 1)[1])
    raise ValueError('Use https://play.qobuz.com/track/ID or a positive Qobuz track ID')


def download_source(provider, url, *, job_url=None, timeout_seconds=900):
    if provider == 'youtube':
        if job_url:
            raise ValueError('--job-url belongs only to DoubleDouble')
        identity = youtube_id(url)
        result = batch.download_reference({'position': 1, 'videoId': identity}, 'bestaudio[acodec=opus]')
        if result['status'] == 'failed':
            raise RuntimeError(result['error'])
        file = batch.source_file(identity)
        expected_codec = 'opus'
    elif provider == 'doubledouble':
        identity = qobuz_id(url)
        result = download_qobuz(identity, job_url=job_url, timeout_seconds=timeout_seconds)
        file = batch.qobuz_file(identity)
        expected_codec = 'flac'
    else:
        raise ValueError('Unknown audio provider')
    if not file or probe(file)['codec_name'] != expected_codec:
        raise ValueError('The retained file is not the requested native audio codec')
    return {**result, 'provider': provider, 'file': str(file.resolve()), 'codec': expected_codec}


def compare_files(reference, candidate, *, cancelled=lambda: False, seconds=1800):
    from stemwise_media.bounded_pcm import comparison_operation
    with comparison_operation(cancelled=cancelled, seconds=seconds):
        reference, candidate = Path(reference).resolve(strict=True), Path(candidate).resolve(strict=True)
        metadata = [probe(file) for file in (reference, candidate)]
        if any(not 0 < value['duration'] <= MAX_DURATION_SECONDS or value['channels'] not in (1, 2) for value in metadata):
            raise ValueError('Compare finite mono/stereo recordings up to two hours')
        a, b = decode(reference, 48000), decode(candidate, 48000)
        strict = analyze_waveforms(a, b, 48000, allow_edge_edits=True, allow_fractional_delay=True)
        performance = None
        if strict['classification'] not in STRICT_MATCHES:
            drift = analyze_clock_drift(a, b, 48000)
            if drift['classification'] == 'matching_body_with_speed_change':
                strict = drift
            else:
                performance = analyze_equivalence(decode(reference, RATE), decode(candidate, RATE), deep=True)
        strict_supported = strict['classification'] in STRICT_MATCHES | {'matching_body_with_speed_change'}
        same = strict_supported or (performance is not None and supported(performance))
        classification = strict['classification'] if strict_supported else 'same_performance_with_differences' if same else 'unconfirmed'
        return {'reference': str(reference), 'candidate': str(candidate), 'referenceAudio': metadata[0],
                'candidateAudio': metadata[1], 'classification': classification, 'sameRecordingSupported': bool(same),
                'fullContentSupported': bool(strict_supported and strict.get('fullContentSupported', False)),
                'waveform': strict, 'performance': performance, 'losslessHistoryEstablished': False,
                'sourceAuthenticityEstablished': False, 'lyricTimingTransferEstablished': False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    download = commands.add_parser('download', help='Retain native audio; completed downloads are reused')
    download.add_argument('--provider', choices=['youtube', 'doubledouble'], required=True)
    download.add_argument('url')
    register = commands.add_parser('register', help='Enroll one retained FLAC for native PC enrichment; no analysis')
    register.add_argument('--qobuz', required=True)
    for command in (download, register):
        command.add_argument('--youtube', required=command is register, help='Real lyric/artwork reference; never substituted as native audio')
        command.add_argument('--title', required=command is register)
        command.add_argument('--artist', required=command is register)
        command.add_argument('--album', default='')
        command.add_argument('--replace-youtube', action='store_true',
                             help='Approved matching FLAC replaces the reference audio; keep YouTube metadata only')
    compare = commands.add_parser('compare', help='Compare local files through their full shared timeline')
    compare.add_argument('reference', type=Path)
    compare.add_argument('candidate', type=Path)
    commands.add_parser('plan', help='Select retained recordings for enrichment without starting analysis')
    pair = commands.add_parser('pair', help='Download exact YouTube and Qobuz tracks, then compare')
    pair.add_argument('--youtube', required=True)
    pair.add_argument('--qobuz', required=True)
    for command in (download, pair):
        command.add_argument('--job-url', help='Resume the exact DoubleDouble Europe track job after CAPTCHA')
        command.add_argument('--timeout', type=int, default=900, help='DoubleDouble job wait deadline in seconds')
    args = parser.parse_args(argv)
    try:
        if args.command == 'download':
            enroll = any((args.youtube, args.title, args.artist, args.album, args.replace_youtube))
            if enroll:
                from stemwise_engine.recording_source import registration_details, register_input
                if args.provider != 'doubledouble' or not all((args.youtube, args.title, args.artist)):
                    raise ValueError('Native enrichment download needs DoubleDouble, --youtube, --title and --artist together')
                track = qobuz_id(args.url)
                reference = youtube_id(args.youtube)
                registration_details(track, reference, args.title, args.artist, args.album)
            result = download_source(args.provider, args.url, job_url=args.job_url, timeout_seconds=args.timeout)
            if enroll:
                result.update(register_input(ROOT, track, reference, args.title, args.artist, args.album))
                if args.replace_youtube:
                    result['replacement'] = batch.retire_youtube_audio(reference, track)
        elif args.command == 'register':
            from stemwise_engine.recording_source import register_input
            result = register_input(ROOT, qobuz_id(args.qobuz), youtube_id(args.youtube),
                                    args.title, args.artist, args.album)
            if args.replace_youtube:
                result['replacement'] = batch.retire_youtube_audio(youtube_id(args.youtube), qobuz_id(args.qobuz))
        elif args.command == 'compare':
            result = compare_files(args.reference, args.candidate)
        elif args.command == 'plan':
            from stemwise_media.enrichment_plan import save_plan
            result = save_plan()
        else:
            youtube_id(args.youtube)
            qobuz_id(args.qobuz)
            reference = download_source('youtube', args.youtube)
            candidate = download_source('doubledouble', args.qobuz, job_url=args.job_url, timeout_seconds=args.timeout)
            result = compare_files(reference['file'], candidate['file'])
        print(json.dumps(result, ensure_ascii=False, allow_nan=False))
        return 0 if args.command in ('download', 'register', 'plan') or result['sameRecordingSupported'] else 1
    except CaptchaRequired as error:
        print(json.dumps({'status': 'action_required', 'reason': 'captcha_required', 'message': str(error),
                          'serviceUrl': 'https://eu.doubledouble.top/'}, ensure_ascii=False))
        return 3
    except Exception as error:
        print('audio: ' + batch.safe_error(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())

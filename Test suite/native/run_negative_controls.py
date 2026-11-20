"""Compile isolated production mutations and require their exact runtime invariant failures."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import sys

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'StemWise/audio-engine/src/main/cpp'
sys.path.insert(0, str(ROOT / 'Test suite/host'))
from owned_process import run
import audio_evidence
from stemwise_engine.common import WorkerRetirementError, WorkerTemporaryDirectory

CORE = 'NativeAudioOutputCallbackCore.h'
CLOSURE = 'stemwise_native_playback_pcm_closure_test'
CACHE_CLEAR_PROGRESS = 'AE-06 cache read cannot starve Clear continuation progress'
FAILURE_PREFIXES = {
    CLOSURE: 'FAIL: ',
    'stemwise_late_stem_mix_control_mailbox_test': 'Late stem mix control mailbox test failure: ',
    'stemwise_current_song_pcm_cache_test': 'CurrentSongPcmCache test failure: ',
    'stemwise_native_playback_runtime_test': 'NativePlaybackRuntime test failure: ',
}
CASES = {
    'stale-control': ('LateStemMixControlMailbox.h',
        'slot->selectionToken.load(std::memory_order_relaxed) != snapshot.selectionToken ||',
        'false ||', 'stemwise_late_stem_mix_control_mailbox_test', [], 'AE-01 stale control changed independent current endpoint'),
    'zero-acceptance': (CORE,
        'if (mPushPendingOffset == mPushPendingFrames) mPushHistory->releaseSource();\n        mCallbackFrames.fetch_add(frames, std::memory_order_release);',
        'if (mPushPendingOffset == mPushPendingFrames) mPushHistory->releaseSource();\n        mCallbackFrames.fetch_add(frames == 0U ? 1U : frames, std::memory_order_release);',
        CLOSURE, ['--negative-push-contracts'], 'zero sink progress never advances any frame ledger'),
    'suffix-drop': (CORE,
        'return {mPushPcm.get() + mPushPendingOffset * kChannelCount,',
        'return {mPushPcm.get() + (mPushPendingOffset + (mPushPendingOffset ? 1U : 0U)) * kChannelCount,',
        CLOSURE, ['--negative-push-contracts'], '192/zero/64 submits every original rendered sample exactly once without rerendering'),
    'suffix-duplicate': (CORE,
        'return {mPushPcm.get() + mPushPendingOffset * kChannelCount,',
        'return {mPushPcm.get() + (mPushPendingOffset - (mPushPendingOffset ? 1U : 0U)) * kChannelCount,',
        CLOSURE, ['--negative-push-contracts'], '192/zero/64 submits every original rendered sample exactly once without rerendering'),
    'acceptance-is-presentation': (CORE,
        'bool acceptPushPrefix(std::uint64_t sequence, std::size_t frames) noexcept {',
        '''bool acceptPushPrefix(std::uint64_t sequence, std::size_t frames) noexcept {
        const bool accepted = acceptPushPrefixWithoutObservation(sequence, frames);
        if (accepted && frames != 0U) static_cast<void>(observePushPresentation(pushSubmittedFrames() - 1U));
        return accepted;
    }
    bool acceptPushPrefixWithoutObservation(std::uint64_t sequence, std::size_t frames) noexcept {''',
        CLOSURE, ['--negative-push-contracts'], 'positive acceptance advances submission only'),
    'premature-resource-release': (CORE,
        '''if (!operation.acquired || mClosed.load(std::memory_order_acquire) ||
            !mPushEnabled.load(std::memory_order_acquire) ||
            mPushConsumerActive.load(std::memory_order_acquire) ||
            generation <= mRoles.producerGeneration()) return false;''',
        '''if (mClosed.load(std::memory_order_acquire) ||
            !mPushEnabled.load(std::memory_order_acquire) ||
            generation <= mRoles.producerGeneration()) return false;''',
        CLOSURE, ['--negative-consumer-lifetime'],
        'STOP retirement cannot reset a ring or owner while an exact consumer operation executes'),
    'ignore-loop-clear': ('native_engine/runtime/NativePlaybackRuntime.cpp',
        'if (!policy.valid()) return NativePlaybackPublishStatus::InvalidSnapshot;',
        'if (!policy.valid()) return NativePlaybackPublishStatus::InvalidSnapshot;\n        if (!enabled) return NativePlaybackPublishStatus::Accepted;',
        'stemwise_native_playback_runtime_test', ['--direct-loop-clear-pressure'],
        'AE-06 Clear preparation order='),
    'cache-read-starves-clear': ('native_engine/runtime/NativePlaybackRuntime.cpp',
        'if (!waitingForPreparedSuccessor && queued < target && (queued == 0U || reserveGrew)) return progressed;',
        '''if (!waitingForPreparedSuccessor && queued < target && (queued == 0U || reserveGrew)) return progressed;
            if (!waitingForPreparedSuccessor && queued < target && bankStates[renderBank]->pipeline->awaitingCacheRead()) return progressed;''',
        'stemwise_native_playback_runtime_test', ['--direct-loop-clear-cache-contention'],
        CACHE_CLEAR_PROGRESS),
    'wrong-cache-identity': ('native_engine/render/CurrentSongPcmCache.cpp',
        '''currentKey.songIdentityHash != nextKey.songIdentityHash ||
            currentKey.originalContentHash != nextKey.originalContentHash ||
            currentKey.stemPackageHash != nextKey.stemPackageHash ||''',
        'false ||', 'stemwise_current_song_pcm_cache_test', [],
        'AE-07 wrong source identity exposed previous source PCM'),
}


def detected(result, assertion, target):
    # A signal, compiler error, sanitizer crash, or timeout is not the specified rejection.
    if result.returncode != 1:
        return False
    failures = [line for line in result.stderr.splitlines()
                if any(line.startswith(prefix) for prefix in FAILURE_PREFIXES.values())]
    prefix = FAILURE_PREFIXES[target]
    if len(failures) != 1 or not failures[0].startswith(prefix):
        return False
    message = failures[0][len(prefix):]
    if assertion == CACHE_CLEAR_PROGRESS:
        # This original starvation fault can prevent the held Clear's first enqueue,
        # before the fixture reaches its deliberately deferred active cache copy.
        if message == ('AE-06 order=clear-pause-source-resume heldClear=1: '
                'AE-06 Clear reaches continuation acceptance before the competing command'):
            return True
    if assertion in ('AE-06 Clear preparation order=', CACHE_CLEAR_PROGRESS):
        held = '1' if assertion == CACHE_CLEAR_PROGRESS else '[01]'
        return bool(re.fullmatch(r'AE-06 order=(?P<order>clear-only|clear-pause-source-resume|'
            r'pause-clear-source-resume|pause-source-clear-resume|source-pause-clear-resume) heldClear=' + held + ': ' +
            re.escape('AE-06 Clear preparation order=') + r'(?P=order) owner=[0-9]+/[0-9]+ command=[0-9]+ '
            r'demand=[0-9]+/[0-9]+/[0-9]+ [^\r\n]+', message))
    return message == assertion


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', choices=CASES, action='append')
    parser.add_argument('--jobs', type=int, default=2)
    parser.add_argument('--memory', action='store_true', help='Run the same mutations with ASan/UBSan')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    if os.name == 'nt' or args.jobs < 1:
        parser.error('Use Linux/WSL and a positive job count.')
    required = args.case or list(CASES)
    report = {'schemaVersion': 1, 'lane': 'negative-controls', 'sourceSha256': audio_evidence.source_identity(),
              'required': required, 'records': [], 'cases': [], 'sanitizers': args.memory}
    report['tools'] = {name: {'path': str(Path(shutil.which(name)).resolve()),
        'version': run([name, '--version'], timeout=10, check=True).stdout.splitlines()[0]}
        for name in ('cmake', 'c++')}
    os.environ.update(ASAN_OPTIONS='detect_leaks=1:halt_on_error=1',
                       UBSAN_OPTIONS='halt_on_error=1:print_stacktrace=1')
    scratch = ROOT / 'Test suite/work'
    scratch.mkdir(parents=True, exist_ok=True)
    try:
        with WorkerTemporaryDirectory(prefix='audio-mutations-', dir=scratch) as directory:
            workspace = Path(directory)
            source = workspace / 'source'
            build = workspace / 'build'
            shutil.copytree(SOURCE, source)
            cmake = source / 'CMakeLists.txt'
            cmake.write_text(cmake.read_text(encoding='utf-8').replace(
                'set(STEMWISE_NATIVE_TEST_ROOT "${STEMWISE_TEST_SUITE_ROOT}/native")',
                f'set(STEMWISE_TEST_SUITE_ROOT "{(ROOT / "Test suite").as_posix()}")\n'
                'set(STEMWISE_NATIVE_TEST_ROOT "${STEMWISE_TEST_SUITE_ROOT}/native")'), encoding='utf-8')
            run(['cmake', '-S', str(source), '-B', str(build), '-G', 'Ninja',
                 '-DCMAKE_BUILD_TYPE=RelWithDebInfo',
                 '-DSTEMWISE_NATIVE_SANITIZERS=' + ('ON' if args.memory else 'OFF')],
                timeout=60, check=True)
            targets = sorted({CASES[name][3] for name in required})
            def compile_targets(selected):
                result = run(['cmake', '--build', str(build), '--parallel', str(args.jobs), '--target', *selected], timeout=300)
                if result.returncode:
                    raise RuntimeError('Mutation compilation failed (not detection):\n' + result.stdout + result.stderr)
            compile_targets(targets)
            if args.memory:
                report['instrumentation'] = {}
                for target in targets:
                    symbols = run(['nm', '-D', str(build / target)], timeout=30, check=True).stdout
                    required_symbols = ['__asan_init', '__ubsan_handle_']
                    if any(symbol not in symbols for symbol in required_symbols):
                        raise RuntimeError('Actual sanitizer instrumentation absent: ' + target)
                    report['instrumentation'][target] = required_symbols
            baselines = {}
            for name in required:
                _, _, _, target, arguments, _ = CASES[name]
                command = [str(build / target), *arguments]
                key = tuple(command)
                if key not in baselines:
                    result = run(command, timeout=90)
                    if result.returncode:
                        raise RuntimeError('Unmodified detector failed: ' + name + '\n' + result.stdout + result.stderr)
                    baselines[key] = {'returnCode': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr}
            for name in required:
                _, _, _, target, arguments, _ = CASES[name]
                command = [str(build / target), *arguments]
                key = tuple(command)
                path, before, after, _, _, assertion = CASES[name]
                path = source / path
                original = path.read_text(encoding='utf-8')
                if original.count(before) != 1:
                    raise RuntimeError('Exact production mutation no longer matches: ' + name)
                retirement_failed = False
                try:
                    path.write_text(original.replace(before, after), encoding='utf-8')
                    compile_targets([target])
                    result = run(command, timeout=90)
                    passed = detected(result, assertion, target)
                    report['records'].append([name, 'passed' if passed else 'failed'])
                    report['cases'].append({'id': name, 'assertion': assertion, 'baseline': baselines[key],
                        'returnCode': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr,
                        'artifactSha256': audio_evidence.digest(build / target)})
                    if not passed:
                        raise RuntimeError('Required invariant failure absent: ' + name + '\n' + result.stdout + result.stderr)
                    print('PASS: ' + name + '; detected: ' + assertion, flush=True)
                except WorkerRetirementError as error:
                    retirement_failed = True
                    error.args = (str(error) + '; compiler/verifier inputs and mutated header retained at ' + str(workspace),)
                    raise
                finally:
                    if not retirement_failed:
                        path.write_text(original, encoding='utf-8')
    finally:
        if args.report:
            report['sourceEndSha256'] = audio_evidence.source_identity()
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()

"""Run focused memory or data-race checks in a separate Linux build directory."""
import argparse
import configparser
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'host'))
import audio_evidence


TARGETS = ('stemwise_wavpack_reader_test', 'stemwise_time_pitch_test', 'stemwise_sample_rate_test', 'stemwise_latest_command_mailbox_test', 'stemwise_current_song_pcm_cache_test', 'stemwise_multibus_ring_buffer_test',
           'stemwise_song_render_pipeline_test', 'stemwise_native_playback_runtime_test',
           'stemwise_native_playback_pcm_closure_test')
THREAD_TARGETS = ('stemwise_native_ownership_publication_test', 'stemwise_latest_command_mailbox_test', 'stemwise_multibus_ring_buffer_test', 'stemwise_atomic_loop_policy_test',
                  'stemwise_late_stem_mix_control_mailbox_test', 'stemwise_native_playback_runtime_test')
MEMORY_SHARDS = ('core', 'long-30', 'long-37')


def partition_tests(tests, shard):
    if shard == 'all':
        return tuple(tests)
    if shard not in MEMORY_SHARDS:
        raise ValueError('Unknown memory sanitizer shard')
    long_cases = {seconds: f'stemwise_song_render_pipeline_long_loop_canonical_{seconds}s_tuned_9_parts_test' for seconds in (30, 37)}
    if shard == 'core':
        selected = tuple(test for test in tests if test not in long_cases.values())
    else:
        name = long_cases[int(shard.removeprefix('long-'))]
        selected = (name,) if name in tests else ()
    if not selected:
        raise ValueError('Sanitizer shard has no registered cases: '+shard)
    return selected


def targets_for(kind, shard='all'):
    if kind == 'thread':
        if shard != 'all':
            raise ValueError('Thread sanitizer does not have memory shards')
        return THREAD_TARGETS
    if shard.startswith('long-'):
        return ('stemwise_song_render_pipeline_test',)
    return TARGETS


def symbol_tool():
    if os.name != 'nt':
        tool = shutil.which('nm')
        if tool:
            return tool
    else:
        root = Path(__file__).resolve().parents[2]
        toolchain = configparser.ConfigParser()
        toolchain.read_string('[android]\n'+(root / 'StemWise/gradle/android-toolchain.properties').read_text(encoding='utf-8'))
        version = toolchain['android']['ndkVersion']
        sdks = [os.environ.get('ANDROID_SDK_ROOT'), os.environ.get('ANDROID_HOME'),
                str(Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk') if 'LOCALAPPDATA' in os.environ else None]
        for sdk in filter(None, sdks):
            for tool in (Path(sdk) / 'ndk' / version / 'toolchains/llvm/prebuilt').glob('*/bin/llvm-nm.exe'):
                if tool.is_file():
                    return str(tool)
    raise ValueError('Sanitizer symbol inspection tool unavailable')


def verify_instrumentation(artifact, kind):
    try:
        output = subprocess.check_output([symbol_tool(), '-D', '--undefined-only', str(artifact)],
                                         text=True, stderr=subprocess.PIPE, timeout=30)
    except (OSError, subprocess.SubprocessError) as error:
        raise ValueError('Sanitizer symbol inspection failed: ' + str(artifact)) from error
    imports = {match.group(1).split('@')[0] for match in
               re.finditer(r'^\s*(?:[0-9a-fA-F]+\s+)?U\s+(\S+)\s*$', output, re.M)}
    valid = ('__tsan_init' in imports if kind == 'thread' else
             '__asan_init' in imports and any(name.startswith('__ubsan_handle_') for name in imports))
    if not valid:
        raise ValueError('Sanitizer instrumentation absent: ' + str(artifact))
    return sorted(imports)



def selected_tests(manifest, targets, kind, shard='all'):
    registered = {test['name'] for test in manifest['tests']}
    if kind == 'thread':
        selected = tuple('stemwise_native_playback_concurrency_check_test'
                         if target == 'stemwise_native_playback_runtime_test' else target for target in targets)
        selected += ('stemwise_native_playback_late_control_queue_full_test',)
        missing = set(selected) - registered
    else:
        selected = []
        covered = set()
        for test in manifest['tests']:
            command = test.get('command', [])
            if not command or Path(command[0]).stem not in targets:
                continue
            target = Path(command[0]).stem
            if target == 'stemwise_native_playback_pcm_closure_test' and command[1:] != ['--managed-loop-continuity']:
                continue
            if target in ('stemwise_native_playback_runtime_test', 'stemwise_song_render_pipeline_test') and len(command) == 1:
                raise RuntimeError('Aggregate sanitizer replay must not be registered: ' + target)
            selected.append(test['name'])
            covered.add(target)
        missing = set(targets) - covered
    if missing:
        raise RuntimeError('Required sanitizer tests absent: ' + ', '.join(sorted(missing)))
    return partition_tests(selected, shard)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', required=True)
    parser.add_argument('--jobs', type=int, default=2)
    parser.add_argument('--kind', choices=('memory', 'thread'), default='memory')
    parser.add_argument('--shard', choices=('all', *MEMORY_SHARDS), default='all')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    if os.name == 'nt':
        parser.error('Run with Linux Python, including WSL; keep the sanitizer build separate.')
    source = Path(__file__).resolve().parents[2] / 'StemWise/audio-engine/src/main/cpp'
    build = Path(args.build).resolve()
    if build == source or args.jobs < 1:
        parser.error('Use a separate build directory and a positive job count.')
    environment = dict(os.environ, ASAN_OPTIONS='detect_leaks=1:halt_on_error=1',
                       UBSAN_OPTIONS='halt_on_error=1:print_stacktrace=1',
                       TSAN_OPTIONS='halt_on_error=1')
    source_sha256 = audio_evidence.source_identity() if args.report else None
    targets = targets_for(args.kind, args.shard)
    commands = (
        ['cmake', '-S', str(source), '-B', str(build), '-G', 'Ninja',
         '-DCMAKE_BUILD_TYPE=RelWithDebInfo',
         '-DSTEMWISE_NATIVE_SANITIZERS=' + ('ON' if args.kind == 'memory' else 'OFF'),
         '-DSTEMWISE_NATIVE_THREAD_SANITIZER=' + ('ON' if args.kind == 'thread' else 'OFF')],
        ['cmake', '--build', str(build), '--parallel', str(args.jobs), '--target', *targets],
    )
    for command in commands:
        subprocess.run(command, env=environment, check=True)
    manifest = json.loads(subprocess.check_output(
        ['ctest', '--test-dir', str(build), '--show-only=json-v1'], env=environment))
    tests = selected_tests(manifest, targets, args.kind, args.shard)
    command = ['ctest', '--test-dir', str(build), '--output-on-failure', '--no-tests=error',
               '--timeout', '180', '-R', '^(' + '|'.join(tests) + ')$']
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        xml = args.report.resolve().with_suffix('.xml')
        xml.unlink(missing_ok=True)
        command += ['--output-junit', str(xml)]
    result = subprocess.run(command, env=environment)
    if args.report:
        tools = {tool: subprocess.check_output([tool, '--version'], text=True).splitlines()[0]
                 for tool in ('cmake', 'ctest', 'c++')}
        artifacts = [build / target for target in targets]
        # Check the linked instrumentation too; a CMake option alone does not prove it executed.
        for artifact in artifacts:
            verify_instrumentation(artifact, args.kind)
        audio_evidence.native_lane_report(args.report, 'sanitizer-' + args.kind,
            source_sha256, tests, xml, artifacts, tools, shard=args.shard)
    result.check_returncode()


if __name__ == '__main__':
    main()

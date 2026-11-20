"""Run a scoped native regression using the target ABI and frozen rehearsal APK libraries."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import uuid
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'host'))
from owned_process import run as run_owned
from remote_process import run_remote


def verify_merger_observation(text, fixture):
    from merger_math import binary_value, mix_bound
    cases = fixture['mixCases']
    observations = [json.loads(line)['nativeLinearMixer'] for line in text.splitlines()
                    if line.startswith('{"nativeLinearMixer":')]
    if len(observations) != 1:
        raise RuntimeError('Expected one native linear mixer observation')
    observed = observations[0]['observedBits']
    if len(observed) != len(cases) or len(cases) != 167:
        raise RuntimeError('Native merger corpus extent differs from the frozen fixture')
    profiles = [profile for profile in ('separate', 'fused')
                if all(bits == case[profile + 'Bits'] for bits, case in zip(observed, cases))]
    if len(profiles) != 1:
        raise RuntimeError('Native merger has an unsupported or indistinguishable arithmetic profile')
    peak_error = 0
    error_squares = 0
    for bits, case in zip(observed, cases):
        ideal = sum(binary_value(x) * binary_value(g)
                    for x, g in zip(case['laneBits'], case['gainBits']))
        error = abs(binary_value(bits) - ideal)
        if error > mix_bound(case['laneBits'], case['gainBits']):
            raise RuntimeError('Native merger exceeds independent bound: ' + case['id'])
        peak_error = max(peak_error, error)
        error_squares += error * error
    return dict(profile=profiles[0] + '-RN32', cases=len(cases), bitMismatches=0,
                maximumBeyondBound=0, peakError=float(peak_error),
                rmsError=float(error_squares / len(cases)) ** 0.5,
                peakErrorNumerator=str(peak_error.numerator), peakErrorDenominator=str(peak_error.denominator))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('adb', 'serial', 'apk', 'ndk', 'output'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--abi', required=True, choices=('arm64-v8a', 'x86_64'))
    parser.add_argument('--case', default='active-channels', choices=('active-channels', 'merger-math'))
    args = parser.parse_args()
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    root = Path(__file__).resolve().parents[2]
    source = root / {'active-channels': 'Test suite/native/TimePitchProcessorTest.cpp',
                     'merger-math': 'Test suite/native/LateStemMixerTest.cpp'}[args.case]
    includes = root / 'StemWise/audio-engine/src/main/cpp'
    toolchain = Path(args.ndk) / 'toolchains/llvm/prebuilt/windows-x86_64'
    compiler = toolchain / 'bin/clang++.exe'
    remote = '/data/local/tmp/stemwise-native-' + uuid.uuid4().hex
    adb = [args.adb, '-s', args.serial]
    result = {'status': 'FAIL', 'case': args.case, 'serial': args.serial, 'abi': args.abi,
              'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
              'apkSha256': hashlib.sha256(Path(args.apk).read_bytes()).hexdigest()}

    def run(command, timeout=120, *, stream=False):
        completed = run_owned(command, timeout=timeout,
            **({'output_log': output / 'commands.log'} if stream else {}))
        if not stream:
            with (output / 'commands.log').open('a', encoding='utf-8') as log:
                log.write(completed.stdout + completed.stderr)
        if completed.returncode:
            raise RuntimeError(f'{Path(command[0]).name} exited {completed.returncode}; see commands.log')
        return completed.stdout

    pushed = False
    failure = None
    try:
        actual_abi = run(adb + ['shell', 'getprop', 'ro.product.cpu.abi']).strip()
        if actual_abi != args.abi:
            raise RuntimeError(f'Device ABI changed: selected {args.abi}, actual {actual_abi}')
        libraries = ['libc++_shared.so']
        with zipfile.ZipFile(args.apk) as apk:
            try:
                library_bytes = {name: apk.read(f'lib/{args.abi}/{name}') for name in libraries}
            except KeyError as error:
                raise RuntimeError(f'APK is missing native libraries for ABI {args.abi}') from error
        for name, data in library_bytes.items():
            (output / name).write_bytes(data)
        result['librarySha256'] = {name: hashlib.sha256(data).hexdigest()
                                   for name, data in library_bytes.items()}
        binary = output / 'physical_output_check'
        target = {'arm64-v8a': 'aarch64-linux-android28', 'x86_64': 'x86_64-linux-android28'}[args.abi]
        command = [str(compiler), '--target=' + target,
                   '--sysroot=' + str(toolchain / 'sysroot'), '-std=c++20', '-O2',
                   '-Wall', '-Wextra', '-Werror', '-I', str(includes), str(source),
                   '-L', str(output)]
        if args.case == 'merger-math':
            fixture = root / 'Test suite/android/fixtures/merger-oracle.json'
            run([sys.executable, '-B', str(root / 'Test suite/host/merger_math.py'),
                 '--cpp-header', str(output / 'merger-oracle.h')])
            command += ['-DSTEMWISE_MERGER_TEST_CAPTURE', '-I', str(output),
                        str(includes / 'LateStemMixer.cpp')]
            bound_sources = [source, includes / 'LateStemMixer.cpp', includes / 'LateStemMixer.h',
                             includes / 'StemwiseMultibus.h', root / 'Test suite/native/MergerKnownAnswer.h',
                             fixture, root / 'Test suite/host/merger_math.py', Path(__file__).resolve()]
            result['sourceSha256s'] = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
                                      for path in bound_sources}
            result['compilerSha256'] = hashlib.sha256(compiler.read_bytes()).hexdigest()
            result['compilerVersion'] = run([str(compiler), '--version']).strip()
            result['scope'] = ('Standalone production LateStemMixer translation unit with test-only linear capture, '
                               'NDK -O2 and frozen APK libc++; not an observation inside the APK mixer')
        else:
            dsp = includes / 'native_engine/dsp'
            command += ['-I', str(dsp), '-I', str(root / 'Test suite/native'),
                        '-isystem', str(includes / 'third_party/signalsmith-stretch/include'),
                        '-isystem', str(includes / 'third_party/signalsmith-stretch/third_party/signalsmith-linear/include'),
                        str(dsp / 'TimePitchProcessor.cpp'), str(dsp / 'TimePitchTransition.cpp')]
        result['compileCommand'] = command + ['-o', str(binary)]
        run(result['compileCommand'], stream=True)
        if args.case == 'merger-math':
            result['neededLibraries'] = run([str(toolchain / 'bin/llvm-readelf.exe'), '--needed-libs', str(binary)]).strip()
            if 'libc++_shared.so' not in result['neededLibraries']:
                raise RuntimeError('Native merger runner does not use the frozen APK libc++ library')
        result['runnerSha256'] = hashlib.sha256(binary.read_bytes()).hexdigest()
        run(adb + ['shell', 'mkdir', remote])
        pushed = True
        for name in libraries + [binary.name]:
            run(adb + ['push', str(output / name), remote + '/' + name])
        run(adb + ['shell', 'chmod', '700', remote + '/' + binary.name])
        text = run_remote(adb, remote, ['env', 'LD_LIBRARY_PATH=' + remote,
                          remote + '/' + binary.name, '--' + args.case], run,
                          timeout={'active-channels': 180, 'merger-math': 30}[args.case])
        proof = {
            'active-channels': 'PASS active-channel history, allocation, and target measurements',
            'merger-math': 'Merger controls passed: 512/512 subsets, 45 gain endpoints, mixed gains, nine-frame ramp, 9/9 withheld lanes',
        }[args.case]
        if proof not in text or (args.case == 'active-channels' and text.count('active-DSP parts=') != 12):
            raise RuntimeError('Native regression did not produce its required proof')
        if args.case == 'merger-math':
            result['mergerMath'] = verify_merger_observation(text, json.loads(fixture.read_text(encoding='utf-8')))
        result['status'] = 'PASS'
        print(text.strip())
    except BaseException as error:
        result['error'] = str(error)
        failure = error
    finally:
        try:
            if pushed and not getattr(failure, 'remote_cleanup_failed', False):
                run(adb + ['shell', 'rm', '-rf', remote])
        except Exception as error:
            result['status'] = 'FAIL'
            result['cleanupError'] = str(error)
            if failure is None:
                failure = error
        finally:
            (output / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    if failure is not None:
        raise failure


if __name__ == '__main__':
    main()

from __future__ import annotations

import base64
import hashlib
import json
import os
import shutil
import shlex
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path


PROJECT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"
WORK = Path(__file__).resolve().parents[3] / "Test suite" / "work"
LAUNCHER = PROJECT.parent / "STEMWISE_AGENT.ps1"
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from process_fixture import process_birth, terminate_exact, windows_birth, terminate_windows_exact, prepare_runtime_package

def copy_launcher(destination):
    shutil.copy2(LAUNCHER, destination)
    shutil.copy2(PROJECT.parent/'runtime-layout.json', destination.parent/'runtime-layout.json')
    helpers = destination.parent/'stemwise-playlist-builder'
    helpers.mkdir(parents=True, exist_ok=True)
    for name in ('atomic-json.ps1', 'command-spec.ps1', 'process-supervisor.ps1', 'WorkerJob.cs'):
        shutil.copy2(PROJECT/name, helpers/name)


WINDOWS_POWERSHELL = shutil.which("powershell.exe")


def _windows_path(path: Path) -> str:
    if os.name == "nt":
        return str(path)
    return subprocess.check_output(
        ["wslpath", "-w", str(path)], text=True
    , timeout=60).strip()


def _host_path(windows_path: str) -> Path:
    if os.name == "nt":
        return Path(windows_path)
    return Path(
        subprocess.check_output(
            ["wslpath", "-u", windows_path], text=True
        , timeout=60).strip()
    )


def _run_encoded(script: str, *, timeout: float) -> subprocess.CompletedProcess[str]:
    encoded = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
    return subprocess.run(
        [
            WINDOWS_POWERSHELL,
            "-NoProfile",
            "-NonInteractive",
            "-EncodedCommand",
            encoded,
        ],
        check=False,
        capture_output=True,
        text=True,
        timeout=timeout,
    )


@unittest.skipUnless(
    WINDOWS_POWERSHELL and (os.name == 'nt' or shutil.which('wslpath')),
    'Actual Windows wrappers require Windows PowerShell.',
)
class WrapperCommandDispatchTests(unittest.TestCase):
    def test_commands_reach_actual_python_parsers_in_canonical_case_without_changing_arguments(self):
        from unittest.mock import patch
        from stemwise_engine import workflow_command, stemwise_pc, lyrics_intake, recording_analysis
        from stemwise_media import audio_library
        cases = (
            ('future-song.ps1', 'source', "-VideoId '-BcDeFgHiJk' -Url 'https://youtu.be/AbCdEfGhIjK?Tag=MiXeD'",
                'stemwise_engine.workflow_command'),
            ('future-song.ps1', 'syllacandidate', "-VideoId '-BcDeFgHiJk' -Shadow",
                'stemwise_engine.workflow_command'),
            ('future-song.ps1', 'syllacandidate', "-RecordingKey 'qobuz:11' -Shadow",
                'stemwise_engine.workflow_command'),
            ('stemwise.ps1', 'inventory', "--url 'https://Example.test/Playlist?ID=MiXeD'",
                'stemwise_engine.stemwise_pc'),
            ('lyrics.ps1', 'scaffold', "--video-id 'AbCdEfGhIjK' --language 'en' --script 'Latn'",
                'stemwise_engine.lyrics_intake'),
            ('recording-analysis.ps1', 'status', "-RecordingKey 'qobuz:11'",
                'stemwise_engine.recording_analysis'),
            ('audio.ps1', 'compare', "'/CaseSensitive/Original.FLAC' '/CaseSensitive/Candidate.FLAC'",
                'stemwise_media.audio_library'),
        )
        with tempfile.TemporaryDirectory(prefix='command-dispatch-', dir=WORK) as directory:
            root = Path(directory)/'builder'
            root.mkdir()
            shutil.copy2(PROJECT.parent/'runtime-layout.json', root.parent/'runtime-layout.json')
            for name in ('runtime-bootstrap.ps1', 'command-spec.ps1', *(case[0] for case in cases)):
                shutil.copy2(PROJECT/name, root/name)
            mock_wsl = """
$env:STEMWISE_LAUNCH_TOKEN = $null
$env:STEMWISE_DOUBLED_DOUBLE_TOKEN = $null
function global:wsl.exe {
    if ($args -contains 'wslpath') { '/CaseSensitive/PC side/builder' }
    elseif ($args -notcontains 'test') { ConvertTo-Json -Compress -InputObject @($args) }
    $global:LASTEXITCODE = 0
}
"""
            for wrapper, command, arguments, entry in cases:
                for spelling in (command, command.title(), command.upper()):
                    with self.subTest(wrapper=wrapper, spelling=spelling):
                        result = _run_encoded(mock_wsl +
                            f"& '{_windows_path(root/wrapper)}' {spelling} {arguments}", timeout=8)
                        self.assertEqual(result.returncode, 0, result.stderr)
                        forwarded = json.loads(result.stdout)
                        argv = forwarded[forwarded.index(entry)+1:]
                        if wrapper == 'future-song.ps1':
                            parsed = workflow_command.parser().parse_args(argv)
                            if command == 'syllacandidate':
                                self.assertIs(parsed.shadow, True)
                                if 'qobuz:11' in arguments:
                                    self.assertEqual(parsed.recording_key, 'qobuz:11')
                                    self.assertIsNone(parsed.video_id)
                                else:
                                    self.assertEqual(parsed.video_id, '-BcDeFgHiJk')
                            else:
                                self.assertEqual(parsed.video_id, '-BcDeFgHiJk')
                                self.assertEqual(base64.b64decode(parsed.url_base64).decode('utf-8'),
                                    'https://youtu.be/AbCdEfGhIjK?Tag=MiXeD')
                        elif wrapper == 'stemwise.ps1':
                            parsed = stemwise_pc.parser().parse_args(argv)
                            self.assertEqual(parsed.url, 'https://Example.test/Playlist?ID=MiXeD')
                        elif wrapper == 'lyrics.ps1':
                            parsed = lyrics_intake.parser().parse_args(argv)
                            self.assertEqual((parsed.video_id, parsed.language, parsed.script),
                                ('AbCdEfGhIjK', 'en', 'Latn'))
                        elif wrapper == 'recording-analysis.ps1':
                            with patch.object(recording_analysis, 'load_config', return_value={}), \
                                    patch.object(recording_analysis, 'status', return_value={}) as status:
                                self.assertEqual(recording_analysis.main(argv), 0)
                            status.assert_called_once_with({}, 'qobuz:11')
                            continue
                        else:
                            with patch.object(audio_library, 'compare_files',
                                    return_value={'sameRecordingSupported': True}) as compare:
                                self.assertEqual(audio_library.main(argv), 0)
                            compare.assert_called_once_with(Path('/CaseSensitive/Original.FLAC'),
                                Path('/CaseSensitive/Candidate.FLAC'))
                            continue
                        self.assertEqual(parsed.command, command)
                        self.assertEqual(parsed.config.as_posix(), '/CaseSensitive/PC side/builder/config.json')

    def test_unknown_commands_are_rejected_before_external_dispatch(self):
        for wrapper in ('future-song.ps1', 'stemwise.ps1', 'lyrics.ps1', 'recording-analysis.ps1', 'audio.ps1'):
            with self.subTest(wrapper=wrapper):
                arguments = "-RecordingKey 'qobuz:11'" if wrapper == 'recording-analysis.ps1' else ''
                result = _run_encoded("function global:wsl.exe { throw 'EXTERNAL_DISPATCH_MUST_NOT_START' }\n" +
                    f"& '{_windows_path(PROJECT/wrapper)}' UnknownCommand {arguments}", timeout=8)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn('EXTERNAL_DISPATCH_MUST_NOT_START', result.stderr)
        for wrapper, arguments in ((PROJECT/'future-song.ps1', 'syllacandidate -VideoId AbCdEfGhIjK'),
                (LAUNCHER, '-Action SyllaCandidate -VideoId AbCdEfGhIjK')):
            with self.subTest(shadow_default=wrapper.name):
                result = _run_encoded("function global:wsl.exe { throw 'EXTERNAL_DISPATCH_MUST_NOT_START' }\n" +
                    f"& '{_windows_path(wrapper)}' {arguments}", timeout=8)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('disabled without explicit -Shadow', result.stderr)
                self.assertNotIn('EXTERNAL_DISPATCH_MUST_NOT_START', result.stderr)


@unittest.skipUnless(
    WINDOWS_POWERSHELL and (os.name == "nt" or shutil.which("wslpath")),
    "The canonical detached launcher requires Windows PowerShell/WMI.",
)
class DetachedAgentLauncherTests(unittest.TestCase):
    def test_replacement_wrapper_preserves_exact_selector_and_expected_hashes(self):
        from stemwise_engine.workflow_command import parser
        with tempfile.TemporaryDirectory(prefix='replacement-wrapper-', dir=WORK) as directory:
            root = Path(directory)
            for name in ('future-song.ps1', 'command-spec.ps1'):
                shutil.copy2(PROJECT/name, root/name)
            (root/'runtime-bootstrap.ps1').write_text(
                "function Get-StemWiseRuntime { param($BuilderWindows) @{Builder='/fixture'} }\n"
                "function Invoke-StemWiseRuntime { param($Runtime,$CommandArguments) ConvertTo-Json -Compress -InputObject @($CommandArguments) }\n",
                encoding='utf-8')
            for selector in ("-VideoId '-bcdefghijk'", "-RecordingKey 'qobuz:11'"):
                result = _run_encoded(
                    f"& '{_windows_path(root/'future-song.ps1')}' replaceanalysis {selector} "
                    f"-ExpectedDeliverySha256 '{'a'*64}' -ExpectedAnalysisSha256 '{'b'*64}'", timeout=8)
                self.assertEqual(result.returncode, 0, result.stderr)
                argv = json.loads(result.stdout)
                self.assertEqual(argv[:2], ['-m', 'stemwise_engine.workflow_command'])
                command = parser().parse_args(argv[2:])
                self.assertEqual(command.command, 'replaceanalysis')
                self.assertEqual(command.video_id, '-bcdefghijk' if 'VideoId' in selector else None)
                self.assertEqual(command.recording_key, 'qobuz:11' if 'RecordingKey' in selector else None)
                self.assertEqual(command.expected_delivery_sha256, 'a'*64)
                self.assertEqual(command.expected_analysis_sha256, 'b'*64)
                self.assertEqual(sorted(path.name for path in root.iterdir()),
                    ['command-spec.ps1', 'future-song.ps1', 'runtime-bootstrap.ps1'])

    def test_replacement_wrappers_reject_invalid_approval_before_runtime(self):
        with tempfile.TemporaryDirectory(prefix='replacement-refusal-', dir=WORK) as directory:
            root = Path(directory)
            copy_launcher(root/'STEMWISE_AGENT.ps1')
            builder = root/'stemwise-playlist-builder'
            shutil.copy2(PROJECT/'future-song.ps1', builder/'future-song.ps1')
            (builder/'runtime-bootstrap.ps1').write_text("throw 'RUNTIME_MUST_NOT_START'\n", encoding='utf-8')
            for selector in ('', "-VideoId 'bad'", "-RecordingKey 'qobuz:0'",
                             "-VideoId 'abcdefghijk' -RecordingKey 'qobuz:11'",
                             "-VideoId 'abcdefghijk' -Url 'https://youtu.be/abcdefghijk'"):
                for script, command in ((root/'STEMWISE_AGENT.ps1', '-Action ReplaceAnalysis'),
                                        (builder/'future-song.ps1', 'replaceanalysis')):
                    result = _run_encoded(f"& '{_windows_path(script)}' {command} {selector} "
                        f"-ExpectedDeliverySha256 '{'a'*64}' -ExpectedAnalysisSha256 '{'b'*64}'", timeout=8)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn('one exact source selector', result.stderr)
                    self.assertNotIn('RUNTIME_MUST_NOT_START', result.stderr)
            for script, command in ((root/'STEMWISE_AGENT.ps1', '-Action Status'),
                                    (builder/'future-song.ps1', 'status')):
                result = _run_encoded(f"& '{_windows_path(script)}' {command} -ExpectedDeliverySha256 '{'a'*64}'", timeout=8)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('require', result.stderr)
                self.assertNotIn('RUNTIME_MUST_NOT_START', result.stderr)

    def test_song_identity_vectors_match_python_entrypoints(self):
        from stemwise_engine.future_song import extract_video_id
        from stemwise_media.audio_library import youtube_id
        cases = ['https://m.youtube.com/watch?v=abcdefghijk',
            'https://youtube.com/shorts/abcdefghijk', 'https://youtu.be/abcdefghijk',
            'https://youtube.com/watch?v=abcdefghijk&v=lmnopqrstuv',
            'https://youtube.com/watch?v=abcdefghijk&v=abcdefghijk',
            'https://youtube.com/watch?v=bad&v=abcdefghijk',
            'https://youtube.com:443/watch?v=abcdefghijk',
            'http://youtube.com/watch?v=abcdefghijk',
            'https://youtube.com/shorts/abcdefghijk?v=lmnopqrstuv',
            'https://youtu.be/abcdefghijk?v=lmnopqrstuv',
            'https://youtube.com/playlist?v=abcdefghijk',
            'https://youtube.com/embed/abcdefghijk?v=abcdefghijk',
            'https://youtube.com/live/abcdefghijk?%76=lmnopqrstuv',
            'https://youtube.com/watch?%76=abcdefghijk',
            'https://youtube.com/SHORTS/abcdefghijk',
            'https://youtu.be//abcdefghijk',
            'https://youtube.com/watch?v=abcdefghijk%0A',
            'https://youtube.com/watch?v=abcdefghijk%0D%0A',
            'https://youtube.com/watch?v=abc%0Adefghijk',
            'https://youtube.com/watch?v=abc\ndefghijk',
            'https://youtube.com/watch?v=abc\tdefghijk']
        def expected(parse, value):
            try: return parse(value)
            except Exception: return None
        identities = [expected(extract_video_id, value) for value in cases]
        self.assertEqual(identities, [expected(youtube_id, value) for value in cases])
        values = ','.join("'"+value+"'" for value in cases)
        script = f". '{_windows_path(LAUNCHER)}'\n$results=@(foreach($value in @({values})) {{ " \
            "try { Resolve-StemWiseWatchVideoId -Value $value } catch { $null } }); ConvertTo-Json -InputObject $results -Compress"
        result = _run_encoded(script, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), identities)
        self.assertEqual(identities[-5:], [None]*5)
        with tempfile.TemporaryDirectory(prefix='reject-song-url-', dir=WORK) as directory:
            root = Path(directory)/'PC side'
            root.mkdir()
            launcher = root/'STEMWISE_AGENT.ps1'
            copy_launcher(launcher)
            setup = _run_encoded(rf"""
. '{_windows_path(launcher)}'
foreach ($path in @(Get-StemWiseActionDependencies -Action New -AgentRoot '{_windows_path(root)}' -Builder '{_windows_path(root/'stemwise-playlist-builder')}')) {{
 if (-not (Test-Path -LiteralPath $path)) {{
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
  [IO.File]::WriteAllText($path, '{{}}')
 }}
}}
""", timeout=5)
            self.assertEqual(setup.returncode, 0, setup.stderr)
            before = {path.relative_to(directory): path.read_bytes() if path.is_file() else None
                for path in Path(directory).rglob('*')}
            for url in ('https://youtube.com/shorts/AAAAAAAAAAA?v=BBBBBBBBBBB',
                    'https://youtube.com/playlist?v=AAAAAAAAAAA',
                    'https://youtube.com/watch?v=AAAAAAAAAAA%0A',
                    'https://youtube.com/watch?v=AAAAAAAAAAA%0D%0A',
                    'https://youtube.com/watch?v=AAA\nAAAAAAAA'):
                rejected = _run_encoded(f"& '{_windows_path(launcher)}' -Action New -Url '{url}'", timeout=5)
                self.assertNotEqual(rejected.returncode, 0)
                self.assertIn('supported YouTube song route', rejected.stderr)
                self.assertEqual({path.relative_to(directory): path.read_bytes() if path.is_file() else None
                    for path in Path(directory).rglob('*')}, before)

    def test_reset_recording_selector_reaches_maintenance_preview_and_apply(self):
        with tempfile.TemporaryDirectory(prefix='reset-selector-', dir=WORK) as temporary:
            root = Path(temporary)/'PC side'
            root.mkdir()
            launcher = root/'STEMWISE_AGENT.ps1'
            copy_launcher(launcher)
            bootstrap = root/'stemwise-playlist-builder/runtime-bootstrap.ps1'
            bootstrap.write_text("function Get-StemWiseRuntime { param($BuilderWindows) @{Builder='fixture-builder'} }\n"
                "function Invoke-StemWiseRuntime { param($Runtime,$CommandArguments) "
                "@{argv=@($CommandArguments)}|ConvertTo-Json -Compress; $global:LASTEXITCODE=0 }\n", encoding='utf-8')
            for apply in ('', '-Apply -PreviewToken ' + 'a'*64):
                result = _run_encoded(f"& '{_windows_path(launcher)}' -Action Reset -RecordingKey qobuz:12 {apply}", timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr)
                argv = json.loads(result.stdout)['argv']
                self.assertEqual(argv[:2], ['-m', 'stemwise_engine.campaign_maintenance'])
                self.assertEqual(argv[argv.index('--recording-key')+1], 'qobuz:12')
                self.assertEqual('--apply' in argv, bool(apply))
                if apply:
                    self.assertEqual(argv[argv.index('--preview-token')+1], 'a'*64)
            for arguments in ('-Action Reset -RecordingKey qobuz:12 -Apply',
                              '-Action Reset -All -Apply',
                              '-Action Reset -RecordingKey qobuz:12 -PreviewToken ' + 'a'*64,
                              '-Action Plan -RecordingKey qobuz:12',
                              '-Action Reset -RecordingKey qobuz:12 -VideoId abcdefghijk'):
                result = _run_encoded(f"& '{_windows_path(launcher)}' {arguments}", timeout=10)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(result.stdout.strip())

    def test_exited_worker_retires_grandchild_holding_redirected_streams(self):
        with tempfile.TemporaryDirectory(prefix='held-pipes-', dir=WORK) as name:
            root = Path(name)
            runner = root/'worker.ps1'
            runner.write_text("""$start = New-Object Diagnostics.ProcessStartInfo
$start.FileName = Join-Path $PSHOME 'powershell.exe'
$start.Arguments = '-NoProfile -NonInteractive -Command "[Console]::Out.WriteLine(''grandchild''); Start-Sleep -Seconds 60"'
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$child = [Diagnostics.Process]::Start($start)
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'grandchild.json'), (@{id=$child.Id;birth=$child.StartTime.ToUniversalTime().ToString('o')} | ConvertTo-Json -Compress))
exit 0
""", encoding='utf-8')
            completion = root/'completion.json'
            launched = _run_encoded(rf"""
. '{_windows_path(LAUNCHER).replace("'", "''")}'
Start-StemWiseDetachedWorker -PowerShellExe (Join-Path $PSHOME 'powershell.exe') `
    -Runner '{_windows_path(runner).replace("'", "''")}' -RunnerArguments @('probe') `
    -WorkingDirectory '{_windows_path(root).replace("'", "''")}' `
    -Stdout '{_windows_path(root/'stdout.log').replace("'", "''")}' `
    -Stderr '{_windows_path(root/'stderr.log').replace("'", "''")}' `
    -Completion '{_windows_path(completion).replace("'", "''")}' | ConvertTo-Json -Compress
""", timeout=20)
            self.assertEqual(launched.returncode, 0, launched.stderr)
            supervisor = json.loads(launched.stdout)['ProcessId']
            birth = windows_birth(supervisor)
            try:
                deadline = time.monotonic()+15
                while not completion.exists() and time.monotonic() < deadline:
                    time.sleep(.1)
                self.assertTrue(completion.exists(), 'surviving descendant held the worker streams open')
                self.assertEqual(json.loads(completion.read_bytes())['exitCode'], 0,
                    (root/'stderr.log').read_text(encoding='utf-8', errors='replace'))
                child = json.loads((root/'grandchild.json').read_bytes())
                self.assertEqual(windows_birth(child['id']), 0)
            finally:
                terminate_windows_exact(supervisor, birth)

    def test_log_write_failure_preserves_worker_result_and_drains_output(self):
        WORK.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='failed-log-', dir=WORK) as name:
            root = Path(name)
            runner = root/'worker.ps1'
            runner.write_text("""[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'ready'), 'ready')
while (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'release'))) { Start-Sleep -Milliseconds 50 }
for ($index=0; $index -lt 512; $index++) { [Console]::Out.Write('x'*8192) }
exit 23
""", encoding='utf-8')
            completion = root/'completion.json'
            launched = _run_encoded(rf"""
. '{_windows_path(LAUNCHER).replace("'", "''")}'
Start-StemWiseDetachedWorker -PowerShellExe (Join-Path $PSHOME 'powershell.exe') `
    -Runner '{_windows_path(runner).replace("'", "''")}' -RunnerArguments @('probe') `
    -WorkingDirectory '{_windows_path(root).replace("'", "''")}' `
    -Stdout '{_windows_path(root/'stdout.log').replace("'", "''")}' `
    -Stderr '{_windows_path(root/'stderr.log').replace("'", "''")}' `
    -Completion '{_windows_path(completion).replace("'", "''")}' | ConvertTo-Json -Compress
""", timeout=20)
            self.assertEqual(launched.returncode, 0, launched.stderr)
            supervisor = json.loads(launched.stdout)['ProcessId']
            birth = windows_birth(supervisor)
            try:
                deadline = time.monotonic()+15
                while not (root/'ready').exists() and time.monotonic() < deadline:
                    time.sleep(.1)
                self.assertTrue((root/'ready').exists())
                (root/'stdout.log').unlink()
                (root/'stdout.log').mkdir()
                (root/'release').write_bytes(b'release')
                while not completion.exists() and time.monotonic() < deadline:
                    time.sleep(.1)
                self.assertTrue(completion.exists(), 'failed log writer stopped draining worker output')
                self.assertEqual(json.loads(completion.read_bytes())['exitCode'], 23)
            finally:
                terminate_windows_exact(supervisor, birth)

    def test_retention_inspects_the_exact_windows_process_birth(self):
        from stemwise_engine.telemetry_retention import _launcher_alive
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        from process_fixture import read_line
        command = "[Console]::Out.WriteLine((@{processId=$PID;processStartTimeUtc=(Get-Process -Id $PID).StartTime.ToUniversalTime().ToString('o')} | ConvertTo-Json -Compress)); Start-Sleep -Seconds 30"
        worker = subprocess.Popen([WINDOWS_POWERSHELL, '-NoProfile', '-NonInteractive', '-Command', command],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        launch = None
        birth = None
        try:
            launch = json.loads(read_line(worker.stdout, timeout=10))
            birth = windows_birth(launch['processId'])
            self.assertTrue(_launcher_alive(launch))
            self.assertFalse(_launcher_alive({**launch, 'processStartTimeUtc': '2000-01-01T00:00:00Z'}))
            self.assertIsNone(_launcher_alive({**launch, 'processStartTimeUtc': None}))
            terminate_windows_exact(launch['processId'], birth)
            worker.wait(timeout=10)
            self.assertFalse(_launcher_alive(launch))
        finally:
            if launch is not None and worker.poll() is None:
                terminate_windows_exact(launch['processId'], birth)
            if worker.poll() is None:
                worker.kill()
            worker.communicate(timeout=10)
            worker.stdout.close()
            worker.stderr.close()

    def test_active_worker_logs_are_bounded_and_keep_the_latest_tail(self):
        WORK.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='bounded-logs-', dir=WORK) as name:
            root = Path(name)
            runner = root/'chatty.ps1'
            runner.write_text("""$ErrorActionPreference='Stop'
$chunk='x'*8192
for ($index=0; $index -lt 512; $index++) {
    [Console]::Out.Write($chunk); [Console]::Error.Write($chunk)
}
[Console]::Out.Write('latest-stdout'); [Console]::Error.Write('latest-stderr')
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'ready'), 'ready')
while (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'release'))) { Start-Sleep -Milliseconds 50 }
exit 23
""", encoding='utf-8')
            completion = root/'completion.json'
            launched = _run_encoded(rf"""
. '{_windows_path(LAUNCHER).replace("'", "''")}'
Start-StemWiseDetachedWorker -PowerShellExe (Join-Path $PSHOME 'powershell.exe') `
    -Runner '{_windows_path(runner).replace("'", "''")}' -RunnerArguments @('probe') `
    -WorkingDirectory '{_windows_path(root).replace("'", "''")}' `
    -Stdout '{_windows_path(root/'stdout.log').replace("'", "''")}' `
    -Stderr '{_windows_path(root/'stderr.log').replace("'", "''")}' `
    -Completion '{_windows_path(completion).replace("'", "''")}' | ConvertTo-Json -Compress
""", timeout=20)
            self.assertEqual(launched.returncode, 0, launched.stderr)
            supervisor = json.loads(launched.stdout)['ProcessId']
            birth = windows_birth(supervisor)
            try:
                deadline = time.monotonic()+20
                while not (root/'ready').exists() and time.monotonic() < deadline:
                    time.sleep(.1)
                self.assertTrue((root/'ready').exists(), (root/'stderr.log').read_text()[-2000:])
                self.assertFalse(completion.exists(), 'tail cap must hold while the worker is still active')
                for stream in ('stdout', 'stderr'):
                    body = (root/f'{stream}.log').read_bytes()
                    self.assertGreater(len(body), 0)
                    self.assertLessEqual(len(body), 1024*1024)
                    self.assertTrue(body.endswith(f'latest-{stream}'.encode()))
                (root/'release').write_bytes(b'release')
                while not completion.exists() and time.monotonic() < deadline:
                    time.sleep(.1)
                self.assertTrue(completion.exists(), 'chatty worker did not retire')
                self.assertEqual(json.loads(completion.read_bytes())['exitCode'], 23)
            finally:
                terminate_windows_exact(supervisor, birth)

    def test_alignment_wrapper_dispatches_each_advertised_current_action(self):
        from stemwise_engine.lyrics_align_pc import parser
        wrapper = _windows_path(PROJECT/'lyrics-align.ps1').replace("'", "''")
        advertised = _run_encoded(rf"""
$Attribute=(Get-Command '{wrapper}').Parameters['Command'].Attributes | Where-Object {{ $_ -is [Management.Automation.ValidateSetAttribute] }}
ConvertTo-Json -InputObject @($Attribute.ValidValues) -Compress
""", timeout=5)
        self.assertEqual(advertised.returncode, 0, advertised.stderr)
        actions = json.loads(advertised.stdout)
        self.assertEqual(set(actions), {'setup', 'doctor', 'model', 'inventory', 'align', 'status', 'validate'})
        for action in (value for value in actions if value != 'setup'):
            with self.subTest(action=action):
                result = _run_encoded(rf"""
function global:wsl.exe {{
 if ($args -contains 'wslpath') {{ '/mapped/builder' }}
 elseif ($args -notcontains 'test') {{ ConvertTo-Json -InputObject @($args) -Compress -Depth 5 }}
 $global:LASTEXITCODE=0
}}
& '{wrapper}' '{action}'
""", timeout=7)
                self.assertEqual(result.returncode, 0, result.stderr)
                dispatched = [value for value in json.loads(result.stdout) if value is not None][-1]
                self.assertEqual(dispatched, 'preload-model' if action == 'model' else action)
                parser().parse_args(['--config', 'fixture.json', dispatched])
        rejected = _run_encoded(f"& '{wrapper}' 'bulk'", timeout=5)
        self.assertNotEqual(rejected.returncode, 0)

    @unittest.skipUnless(os.name == 'posix', 'requires the production Windows-to-WSL transport')
    def test_launch_completion_and_real_workflow_records_share_one_operation_id(self):
        with tempfile.TemporaryDirectory(prefix='stemwise-operation-correlation-', dir=WORK) as directory:
            root = Path(directory) / 'PC side'
            root.mkdir()
            launcher = root / 'STEMWISE_AGENT.ps1'
            copy_launcher(launcher)
            builder = root / 'stemwise-playlist-builder'
            setup = _run_encoded(rf"""
. '{_windows_path(launcher)}'
foreach ($path in @(Get-StemWiseActionDependencies -Action Validate -AgentRoot '{_windows_path(root)}' -Builder '{_windows_path(builder)}')) {{
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
    [IO.File]::WriteAllText($path, '{{}}')
}}
""", timeout=5)
            self.assertEqual(setup.returncode, 0, setup.stderr)
            shutil.copy2(PROJECT / 'future-song.ps1', builder / 'future-song.ps1')
            shutil.copy2(PROJECT / 'runtime-bootstrap.ps1', builder / 'runtime-bootstrap.ps1')
            (builder / 'src').mkdir(exist_ok=True)
            interpreter = builder / '.venv/bin/python'
            interpreter.write_text(f'#!/bin/sh\nexec {shlex.quote(sys.executable)} "$@"\n', encoding='utf-8')
            interpreter.chmod(0o755)
            # Substitute runtime/model admission only; retain both real runners,
            # WSL environment transport and immutable workflow record publication.
            (builder / 'src/runtime_environment.py').write_text(
                'import os,sys\nos.execv(sys.argv[1],sys.argv[1:])\n', encoding='utf-8')
            (builder / 'src/workflow_command.py').write_text(
                'import json,sys\nfrom pathlib import Path\n'
                f'sys.path.insert(0,{str(Path(__file__).resolve().parent)!r})\n'
                'from workflow_fixture import initialized_workflow\n'
                f'workflow=initialized_workflow(Path({str(root.parent)!r}))\n'
                'workflow._require_platform_content_allowed=lambda *_:None\n'
                'workflow._require_selected_audio=lambda *_:None\n'
                'workflow._run_mutating_operation("a1B2c3D4e5F","Validate",lambda:None)\n'
                'print(json.dumps(workflow.report_store.latest_workflow_operation("a1B2c3D4e5F")))\n', encoding='utf-8')
            prepare_runtime_package(builder)
            launched = _run_encoded(rf"""
& '{_windows_path(launcher)}' -Action Validate -VideoId 'a1B2c3D4e5F'
""", timeout=8)
            self.assertEqual(launched.returncode, 0, launched.stderr)
            receipt = json.loads(launched.stdout)
            birth = windows_birth(receipt['processId'])
            try:
                self.assertRegex(receipt['operationId'], r'^[0-9a-f]{32}$')
                self.assertIn(receipt['operationId'], receipt['record'])
                self.assertEqual(_host_path(receipt['record']).parent,
                    root.parent / 'Engine telemetry/youtube-a1B2c3D4e5F/operations' / receipt['operationId'])
                self.assertEqual(json.loads(_host_path(receipt['record']).read_bytes())['operationId'],
                                 receipt['operationId'])
                completion_path = _host_path(receipt['completion'])
                deadline = time.monotonic() + 20
                while not completion_path.exists() and time.monotonic() < deadline:
                    time.sleep(.05)
                self.assertTrue(completion_path.exists(), 'correlated fake workflow did not retire')
                completion = json.loads(completion_path.read_bytes())
                self.assertEqual(completion['exitCode'], 0, _host_path(receipt['stderr']).read_text()[-1500:])
                self.assertEqual(completion['operationId'], receipt['operationId'])
                self.assertEqual(completion['supervisorProcessId'], receipt['processId'])
                outcome = json.loads(_host_path(receipt['stdout']).read_bytes())
                self.assertEqual((outcome['operationId'], outcome['phase']), (receipt['operationId'], 'outcome'))
                records = [json.loads(path.read_bytes()) for path in
                    (root.parent / 'Engine telemetry/youtube-a1B2c3D4e5F/reports/runs').glob('*/run.json')]
                self.assertEqual(len(records), 2)
                self.assertEqual({record['operationId'] for record in records}, {receipt['operationId']})
                self.assertEqual({record['phase'] for record in records}, {'start', 'outcome'})
                self.assertEqual(_host_path(receipt['stderr']).read_text(), '')
            finally:
                terminate_windows_exact(receipt['processId'], birth)

    def test_invalid_operation_id_rejects_launch_before_creating_logs_or_worker(self):
        with tempfile.TemporaryDirectory(prefix='stemwise-invalid-operation-', dir=WORK) as directory:
            root = Path(directory)
            for token in ('A' * 32, 'a' * 31, '../operation'):
                result = _run_encoded(rf"""
. '{_windows_path(LAUNCHER)}'
try {{
    Start-StemWiseDetachedWorker -OperationId '{token}' -PowerShellExe 'unused.exe' `
        -Runner 'unused.ps1' -RunnerArguments @('-Noop') -WorkingDirectory '{_windows_path(root)}' `
        -Stdout '{_windows_path(root / 'stdout')}' -Stderr '{_windows_path(root / 'stderr')}' `
        -Completion '{_windows_path(root / 'completion')}'
    throw 'invalid token admitted'
}} catch {{
    if ($_.Exception.Message -cne 'Invalid workflow operation ID.') {{ throw }}
}}
exit 0
""", timeout=5)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(list(root.iterdir()), [])

    def test_bridge_delivery_dispatches_exact_selector_and_live_file_limit_without_launching_work(self):
        with tempfile.TemporaryDirectory(prefix='stemwise-bridge-', dir=WORK) as directory:
            root = Path(directory) / 'PC side'
            root.mkdir()
            launcher = root / 'STEMWISE_AGENT.ps1'
            copy_launcher(launcher)
            setup = _run_encoded(rf"""
. '{_windows_path(launcher)}'
foreach ($path in @(Get-StemWiseActionDependencies -Action BridgeDelivery -AgentRoot '{_windows_path(root)}' -Builder '{_windows_path(root / 'stemwise-playlist-builder')}')) {{
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
    [IO.File]::WriteAllText($path, '{{}}')
}}
""", timeout=5)
            self.assertEqual(setup.returncode, 0, setup.stderr)
            builder = root / 'stemwise-playlist-builder'
            (builder / 'future-song.ps1').write_text(
                'param($Command,$VideoId,[long]$MaxFileBytes)\n'
                '@{command=$Command;videoId=$VideoId;maxFileBytes=$MaxFileBytes}|ConvertTo-Json -Compress\n',
                encoding='utf-8')
            result = _run_encoded(rf"""
& '{_windows_path(launcher)}' -Action BridgeDelivery -VideoId '-bcdefghijk' -MaxFileBytes 1073741824
""", timeout=7)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout), {'command': 'bridgedelivery',
                'videoId': '-bcdefghijk', 'maxFileBytes': 1073741824})
            self.assertFalse(list(builder.rglob('*.launch.json')))
            rejected = _run_encoded(rf"""
& '{_windows_path(launcher)}' -Action BridgeDelivery -VideoId 'abcdefghijk'
""", timeout=5)
            self.assertNotEqual(rejected.returncode, 0)

    def test_native_wrapper_uses_one_authoritative_workflow_process(self):
        with tempfile.TemporaryDirectory(prefix='native-entry-', dir=WORK) as directory:
            builder = Path(directory) / 'builder'
            (builder / '.venv/bin').mkdir(parents=True)
            (builder / 'src').mkdir()
            shutil.copy2(PROJECT / 'future-song.ps1', builder / 'future-song.ps1')
            shutil.copy2(PROJECT / 'runtime-bootstrap.ps1', builder / 'runtime-bootstrap.ps1')
            shutil.copy2(PROJECT / 'command-spec.ps1', builder / 'command-spec.ps1')
            interpreter = builder / '.venv/bin/python'
            interpreter.write_text('#!/bin/sh\nexec /usr/bin/python3 "$@"\n')
            interpreter.chmod(0o755)
            (builder / 'src/recording_workflow.py').write_text('raise AssertionError("Redundant binding process")\n')
            for name in ('recording_source.py', 'pipeline.py'):
                (builder / 'src' / name).touch()
            (builder / 'src/workflow_command.py').write_text('import json,sys\nprint(json.dumps({"argv":sys.argv[1:]}))\n')
            (builder / 'src/runtime_environment.py').write_text(
                'import os,sys\nos.execv(sys.argv[1],sys.argv[1:])\n', encoding='utf-8')
            prepare_runtime_package(builder)
            for command in ('status', 'task', 'new', 'retry'):
                budget = '-MaxFileBytes 1073741824' if command in ('new', 'retry') else ''
                result = _run_encoded(rf"""
& '{_windows_path(builder / 'future-song.ps1')}' {command} -RecordingKey 'qobuz:11' {budget}
""", timeout=8)
                self.assertEqual(result.returncode, 0, result.stderr)
                args = json.loads(result.stdout)['argv']
                self.assertIn(command, args)
                self.assertEqual(args[args.index('--recording-key') + 1], 'qobuz:11')
                if budget:
                    self.assertEqual(args[args.index('--max-file-bytes')+1], '1073741824')

    def test_task_dispatch_refreshes_both_namespaces_and_returns_json(self):
        with tempfile.TemporaryDirectory(prefix='stemwise-task-', dir=WORK) as directory:
            root = Path(directory) / 'PC side'
            root.mkdir()
            copy_launcher(root / 'STEMWISE_AGENT.ps1')
            setup = _run_encoded(rf"""
. '{_windows_path(root / 'STEMWISE_AGENT.ps1')}'
foreach ($path in @(Get-StemWiseActionDependencies -Action Status -AgentRoot '{_windows_path(root)}' -Builder '{_windows_path(root / 'stemwise-playlist-builder')}')) {{
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
    [IO.File]::WriteAllText($path, '{{}}')
}}
""", timeout=5)
            self.assertEqual(setup.returncode, 0, setup.stderr)
            builder = root / 'stemwise-playlist-builder'
            for relative in ('src/recording_workflow.py', 'src/recording_analysis.py', 'src/recording_source.py', 'src/pipeline.py'):
                target = builder / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text('fixture')
            files = root / 'qobuz-comparison/comparison-results/library-files.json'
            files.parent.mkdir(parents=True)
            files.write_text('{}')
            task = builder / 'future-songs/policy-state/stemwise-future-song-21/jobs/abcdefghijk/AGENT_TASK.txt'
            task.parent.mkdir(parents=True)
            task.write_text('obsolete')
            (builder / 'future-song.ps1').write_text(
                "param($Command,$VideoId,$RecordingKey)\n"
                f"[IO.File]::WriteAllText('{_windows_path(task)}', 'refreshed')\n"
                "@{agentTask='refreshed';recordingKey=$RecordingKey}|ConvertTo-Json -Compress\n")
            for selector in ("-VideoId 'abcdefghijk'", "-RecordingKey 'qobuz:11'"):
                task.write_text('obsolete')
                result = _run_encoded(rf"""
& '{_windows_path(root / 'STEMWISE_AGENT.ps1')}' -Action Task {selector}
""", timeout=7)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(json.loads(result.stdout)['agentTask'], 'refreshed')
                self.assertEqual(task.read_text(), 'refreshed')

    def test_shared_parent_job_is_rejected_before_runner_starts(self):
        WORK.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='stemwise-parent-job-', dir=WORK) as directory:
            root = Path(directory)
            launcher = root / 'STEMWISE_AGENT.ps1'
            # Put the real supervisor in a kernel job before its normal attach.
            # This substitutes inherited containment, not the admission check.
            copy_launcher(launcher)
            supervisor = root/'stemwise-playlist-builder/process-supervisor.ps1'
            source = supervisor.read_text(encoding='utf-8-sig')
            marker = '    [StemWiseWorkerJob]::Attach()'
            self.assertEqual(source.count(marker), 1)
            source = source.replace(marker, marker + '\n' + marker)
            # Completion publication precedes exit; keep that cleanup race observable.
            exit_marker = 'exit ([int]$exitCode)'
            self.assertEqual(source.count(exit_marker), 1)
            supervisor.write_text(source.replace(exit_marker,
                'Start-Sleep -Seconds 30\n' + exit_marker), encoding='utf-8')
            runner = root / 'runner.ps1'
            runner.write_text('[IO.File]::WriteAllText((Join-Path $PSScriptRoot "started"), "started")\n')
            completion = root / 'completion.json'
            script = rf"""
. '{_windows_path(launcher).replace("'", "''")}'
Start-StemWiseDetachedWorker -PowerShellExe (Resolve-StemWiseWindowsPowerShell) `
    -Runner '{_windows_path(runner).replace("'", "''")}' -RunnerArguments @('-Noop') `
    -WorkingDirectory '{_windows_path(root).replace("'", "''")}' `
    -Stdout '{_windows_path(root / 'stdout.log').replace("'", "''")}' `
    -Stderr '{_windows_path(root / 'stderr.log').replace("'", "''")}' `
    -Completion '{_windows_path(completion).replace("'", "''")}' | ConvertTo-Json -Compress
"""
            launched = _run_encoded(script, timeout=5)
            self.assertEqual(launched.returncode, 0, launched.stderr)
            supervisor_pid = json.loads(launched.stdout)['ProcessId']
            birth = windows_birth(supervisor_pid)
            try:
                deadline = time.monotonic() + 10
                while not completion.exists() and time.monotonic() < deadline:
                    time.sleep(.03)
                self.assertTrue(completion.exists(), 'contained supervisor did not return a failure')
                self.assertNotEqual(json.loads(completion.read_text())['exitCode'], 0,
                                    'shared-parent supervisor admitted its runner')
                self.assertIn('shared-parent-job', (root / 'stderr.log').read_text())
                self.assertFalse((root / 'started').exists(), 'runner started before containment rejection')
            finally:
                terminate_windows_exact(supervisor_pid, birth)


    @unittest.skipUnless(os.name == 'posix', 'requires the production WSL environment boundary')
    def test_detached_recording_command_needs_no_agent_registration(self):
        self._check_detached_recording_key()






    def _check_detached_recording_key(self):
        WORK.mkdir(parents=True, exist_ok=True)
        key = 'qobuz:12'
        with tempfile.TemporaryDirectory(prefix='stemwise-recording-key-', dir=WORK) as directory:
            root = Path(directory) / 'PC side'
            root.mkdir()
            launcher = root / 'STEMWISE_AGENT.ps1'
            copy_launcher(launcher)
            builder = root / 'stemwise-playlist-builder'
            # Replace only the numerical entry: retain the real public launcher,
            # WMI supervisor, recording wrapper, WSL transport and Python process.
            script = rf"""
. '{_windows_path(launcher).replace("'", "''")}'
$agentRoot = '{_windows_path(root).replace("'", "''")}'
$builder = Join-Path $agentRoot 'stemwise-playlist-builder'
foreach ($path in @(Get-StemWiseActionDependencies -Action AnalyzeRecording -AgentRoot $agentRoot -Builder $builder)) {{
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
    [IO.File]::WriteAllText($path, '{{}}')
}}
"""
            setup = _run_encoded(script, timeout=5)
            self.assertEqual(setup.returncode, 0, setup.stderr)
            self.assertFalse((root/'qobuz-comparison/comparison-results/library-files.json').exists())
            shutil.copy2(PROJECT / 'recording-analysis.ps1', builder / 'recording-analysis.ps1')
            interpreter = builder / '.venv/bin/python'
            interpreter.write_text('#!/bin/sh\nexec /usr/bin/python3 "$@"\n')
            interpreter.chmod(0o755)
            (builder / 'src/recording_analysis.py').write_text(
                'import json,os,sys\nprint(json.dumps({"recordingKey":sys.argv[-1],'
                '"operationId":os.environ.get("STEMWISE_LAUNCH_TOKEN")}))\n')
            (builder / 'src/runtime_environment.py').write_text(
                'import os,sys\nos.execv(sys.argv[1],sys.argv[1:])\n', encoding='utf-8')
            prepare_runtime_package(builder)
            launched = _run_encoded(rf"""
$env:CODEX_THREAD_ID = 'external-tool'
& '{_windows_path(launcher).replace("'", "''")}' -Action AnalyzeRecording -RecordingKey '{key}'
""", timeout=7)
            self.assertEqual(launched.returncode, 0, launched.stderr)
            receipt = json.loads(launched.stdout)
            self.assertEqual(_host_path(receipt['record']).parent,
                root.parent / 'Engine telemetry/qobuz-12/operations' / receipt['operationId'])
            completion = _host_path(receipt['completion'])
            deadline = time.monotonic() + 12
            while not completion.exists() and time.monotonic() < deadline:
                time.sleep(.05)
            self.assertTrue(completion.exists(), 'detached identity probe did not finish')
            self.assertEqual(json.loads(completion.read_text())['exitCode'], 0,
                             _host_path(receipt['stderr']).read_text()[-1000:])
            self.assertEqual(json.loads(completion.read_text())['operationId'], receipt['operationId'])
            self.assertEqual(json.loads(_host_path(receipt['stdout']).read_text()),
                             {'recordingKey': key, 'operationId': receipt['operationId']})
            status = _run_encoded(rf"& '{_windows_path(launcher).replace(chr(39), chr(39)*2)}' -Action RecordingStatus -RecordingKey '{key}'", timeout=7)
            self.assertEqual(status.returncode, 0, status.stderr)
            self.assertEqual(json.loads(status.stdout), {'recordingKey': key, 'operationId': None})
            self.assertFalse((root/'qobuz-comparison/comparison-results/library-files.json').exists())

    @unittest.skipUnless(os.name == 'posix', 'requires the production WSL /proc boundary')
    def test_forced_windows_supervisor_death_retires_nested_wsl_models(self):
        WORK.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='stemwise-wsl-lifetime-', dir=WORK) as directory:
            root = Path(directory)
            model_ids = root / 'models.json'
            entry = root / 'entry.py'
            child = ('import json,os,subprocess,sys,time\nfrom pathlib import Path\n'
                     'child=subprocess.Popen([sys.executable,"-c","import time;time.sleep(60)"],'
                     'start_new_session=True)\n'
                     f'Path({str(model_ids)!r}).write_text(json.dumps([os.getpid(),child.pid]))\n'
                     'time.sleep(60)\n')
            entry.write_text('import os,sys\nfrom pathlib import Path\n'
                             'from stemwise_engine.common import run_process_tree\n'
                             f'Path({str(root / "entry.pid")!r}).write_text(str(os.getpid()))\n'
                             f'run_process_tree([sys.executable,"-u","-c",{child!r}],'
                             'timeout=60,capture_output=True)\n')
            runner = root / 'runner.ps1'
            runner.write_text(f'& wsl.exe -d Ubuntu --exec "{sys.executable}" "{entry}"\n'
                              'exit $LASTEXITCODE\n')
            launch = rf"""
. '{_windows_path(LAUNCHER).replace("'", "''")}'
Start-StemWiseDetachedWorker -PowerShellExe (Resolve-StemWiseWindowsPowerShell) `
    -Runner '{_windows_path(runner).replace("'", "''")}' -RunnerArguments @('-Noop') `
    -WorkingDirectory '{_windows_path(root).replace("'", "''")}' `
    -Stdout '{_windows_path(root / 'stdout.log').replace("'", "''")}' `
    -Stderr '{_windows_path(root / 'stderr.log').replace("'", "''")}' `
    -Completion '{_windows_path(root / 'completion.json').replace("'", "''")}' | ConvertTo-Json -Compress
"""
            supervisor = None
            linux_ids = []
            linux_births = {}
            supervisor_birth = None
            try:
                launched = _run_encoded(launch, timeout=5)
                self.assertEqual(launched.returncode, 0, launched.stderr)
                supervisor = json.loads(launched.stdout)['ProcessId']
                supervisor_birth = windows_birth(supervisor)
                deadline = time.monotonic() + 10
                while not model_ids.exists() and time.monotonic() < deadline:
                    time.sleep(.03)
                self.assertTrue(model_ids.exists(), (root / 'stderr.log').read_text()[-1000:])
                linux_ids = [int((root / 'entry.pid').read_text()), *json.loads(model_ids.read_text())]
                linux_births = {pid:process_birth(pid) for pid in linux_ids}
                stopped = _run_encoded(f'Stop-Process -Id {supervisor} -Force -ErrorAction Stop', timeout=5)
                self.assertEqual(stopped.returncode, 0, stopped.stderr)
                deadline = time.monotonic() + 6
                while any(Path(f'/proc/{pid}').exists() for pid in linux_ids) and time.monotonic() < deadline:
                    time.sleep(.03)
                self.assertEqual([pid for pid in linux_ids if Path(f'/proc/{pid}').exists()], [],
                                 'Windows cancellation left Linux model workers alive')
                self.assertFalse((root / 'completion.json').exists())
            finally:
                if supervisor:
                    terminate_windows_exact(supervisor,supervisor_birth)
                for pid in linux_ids:
                    try:
                        terminate_exact(pid,linux_births.get(pid))
                    except ProcessLookupError:
                        pass

    @unittest.skipUnless(os.name == 'posix', 'requires the production WSL /proc boundary')
    def test_cancelled_operation_releases_lease_without_retiring_queued_operation(self):
        WORK.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='stemwise-operation-isolation-', dir=WORK) as directory:
            root = Path(directory)
            lease = root / 'shared-worker.lock'
            supervisors = []
            linux_births = {}

            def wait_for(path):
                deadline = time.monotonic() + 12
                while not path.exists() and time.monotonic() < deadline:
                    time.sleep(.03)
                self.assertTrue(path.exists(), f'operation did not reach {path.name}')

            def remember(pid):
                stat = Path(f'/proc/{pid}/stat')
                if stat.exists():
                    linux_births[pid] = stat.read_text().rsplit(')', 1)[1].split()[19]

            def launch(name):
                folder = root / name
                folder.mkdir()
                child = ('import os,time\nfrom pathlib import Path\n'
                         f'Path({str(folder / "model.pid")!r}).write_text(str(os.getpid()))\n'
                         f'while not Path({str(folder / "release")!r}).exists():time.sleep(.03)\n')
                entry = folder / 'entry.py'
                entry.write_text('import os,sys\nfrom pathlib import Path\n'
                                 'from stemwise_engine.common import exclusive_process_lock,run_process_tree\n'
                                 f'Path({str(folder / "entry.pid")!r}).write_text(str(os.getpid()))\n'
                                 f'with exclusive_process_lock(Path({str(lease)!r}),wait_seconds=30):\n'
                                 f' run_process_tree([sys.executable,"-c",{child!r}],timeout=30)\n')
                runner = folder / 'runner.ps1'
                runner.write_text(f'& wsl.exe -d Ubuntu --exec "{sys.executable}" "{entry}"\n'
                                  'exit $LASTEXITCODE\n')
                script = rf"""
. '{_windows_path(LAUNCHER).replace("'", "''")}'
$worker = Start-StemWiseDetachedWorker -PowerShellExe (Resolve-StemWiseWindowsPowerShell) `
    -Runner '{_windows_path(runner).replace("'", "''")}' -RunnerArguments @('-Noop') `
    -WorkingDirectory '{_windows_path(folder).replace("'", "''")}' `
    -Stdout '{_windows_path(folder / 'stdout.log').replace("'", "''")}' `
    -Stderr '{_windows_path(folder / 'stderr.log').replace("'", "''")}' `
    -Completion '{_windows_path(folder / 'completion.json').replace("'", "''")}'
$process = Get-Process -Id $worker.ProcessId -ErrorAction Stop
@{{pid=$process.Id;birth=$process.StartTime.ToUniversalTime().ToString('o')}} | ConvertTo-Json -Compress
"""
                started = _run_encoded(script, timeout=7)
                self.assertEqual(started.returncode, 0, started.stderr)
                supervisors.append(json.loads(started.stdout))
                wait_for(folder / 'entry.pid')
                remember(int((folder / 'entry.pid').read_text()))
                return folder

            def retire(identity):
                return _run_encoded(rf"""
$process = Get-Process -Id {identity['pid']} -ErrorAction SilentlyContinue
if ($process -and $process.StartTime.ToUniversalTime().ToString('o') -ceq '{identity['birth']}') {{
    Stop-Process -Id $process.Id -Force -ErrorAction Stop
}}
""", timeout=5)

            try:
                owner = launch('owner')
                wait_for(owner / 'model.pid')
                model = int((owner / 'model.pid').read_text())
                remember(model)
                queued = launch('queued')
                self.assertFalse((queued / 'model.pid').exists(), 'queued operation bypassed the held lease')
                stopped = retire(supervisors[0])
                self.assertEqual(stopped.returncode, 0, stopped.stderr)
                wait_for(queued / 'model.pid')
                remember(int((queued / 'model.pid').read_text()))
                deadline = time.monotonic() + 6
                while Path(f'/proc/{model}').exists() and time.monotonic() < deadline:
                    time.sleep(.03)
                self.assertFalse(Path(f'/proc/{model}').exists(), 'cancelled owner left its model alive')
                self.assertFalse((owner / 'completion.json').exists(), 'cancellation manufactured completion')
                (queued / 'release').touch()
                wait_for(queued / 'completion.json')
                self.assertEqual(json.loads((queued / 'completion.json').read_text())['exitCode'], 0,
                                 (queued / 'stderr.log').read_text()[-1000:])
            finally:
                for identity in supervisors:
                    retire(identity)
                for pid, birth in linux_births.items():
                    try:
                        stat = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()
                        if stat[19] == birth:
                            terminate_exact(pid,linux_births.get(pid))
                    except ProcessLookupError:
                        pass
                    except FileNotFoundError:
                        pass

    def test_forced_supervisor_death_retires_runner_and_detached_grandchild(self):
        WORK.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='stemwise-worker-lifetime-', dir=WORK) as directory:
            root = Path(directory)
            runner = root / 'runner.ps1'
            shutil.copy2(PROJECT/'atomic-json.ps1', root/'atomic-json.ps1')
            runner.write_text('''
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'atomic-json.ps1')
$childScript = Join-Path $PSScriptRoot 'child.ps1'
[IO.File]::WriteAllText($childScript, '. (Join-Path $PSScriptRoot "atomic-json.ps1"); Write-StemWiseJsonAtomic -Path (Join-Path $PSScriptRoot "child.json") -Value @{pid=$PID}; Start-Sleep -Seconds 60')
$child = Start-Process powershell.exe -ArgumentList @('-NoProfile','-NonInteractive','-File',('"{0}"' -f $childScript)) -WindowStyle Hidden -PassThru
Write-StemWiseJsonAtomic -Path (Join-Path $PSScriptRoot 'runner.json') -Value @{runner=$PID;child=$child.Id}
Start-Sleep -Seconds 60
''', encoding='utf-8')
            completion = root / 'completion.json'
            script = rf"""
. '{_windows_path(LAUNCHER).replace("'", "''")}'
Start-StemWiseDetachedWorker -PowerShellExe (Resolve-StemWiseWindowsPowerShell) `
    -Runner '{_windows_path(runner).replace("'", "''")}' -RunnerArguments @('-Noop') `
    -WorkingDirectory '{_windows_path(root).replace("'", "''")}' `
    -Stdout '{_windows_path(root / 'stdout.log').replace("'", "''")}' `
    -Stderr '{_windows_path(root / 'stderr.log').replace("'", "''")}' `
    -Completion '{_windows_path(completion).replace("'", "''")}' | ConvertTo-Json -Compress
"""
            known_ids = []
            known_births = {}
            try:
                launched = _run_encoded(script, timeout=5)
                self.assertEqual(launched.returncode, 0, launched.stderr)
                supervisor = json.loads(launched.stdout)['ProcessId']
                supervisor_birth = windows_birth(supervisor)
                known_ids.append(supervisor)
                known_births[supervisor] = supervisor_birth
                deadline = time.monotonic() + 10
                while (not (root/'runner.json').exists() or not (root/'child.json').exists()) and time.monotonic() < deadline:
                    time.sleep(.05)
                self.assertTrue((root/'runner.json').exists() and (root/'child.json').exists(),
                                (root / 'stderr.log').read_text()[-1000:])
                observed = json.loads((root / 'runner.json').read_text())
                known_ids.extend((observed['runner'], observed['child']))
                known_births.update({pid:windows_birth(pid) for pid in known_ids})
                self.assertEqual(json.loads((root/'child.json').read_text(encoding='utf-8-sig'))['pid'],
                                 observed['child'])
                retired = _run_encoded(rf"""
Stop-Process -Id {supervisor} -Force -ErrorAction Stop
$ids = @({observed['runner']},{observed['child']})
$deadline = [DateTime]::UtcNow.AddSeconds(3)
do {{
    $alive = @(Get-Process -Id $ids -ErrorAction SilentlyContinue)
    if ($alive.Count -eq 0) {{ break }}
    Start-Sleep -Milliseconds 20
}} while ([DateTime]::UtcNow -lt $deadline)
@($alive | ForEach-Object {{$_.Id}}) | ConvertTo-Json -Compress
""", timeout=5)
                self.assertEqual(retired.returncode, 0, retired.stderr)
                alive = json.loads(retired.stdout) if retired.stdout.strip() else []
                self.assertEqual(alive, [], 'forced supervisor death left task-owned Windows workers alive')
                self.assertFalse(completion.exists(), 'forced termination manufactured a successful completion')
            finally:
                if known_ids:
                    for pid in known_ids:
                        terminate_windows_exact(pid,known_births.get(pid))


    def test_endpoint_adjudication_returns_receipt_before_worker_finishes(self) -> None:
        # Replace acoustic inference only; exercise the public launcher and real supervisor.
        WORK.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="stemwise-endpoint-launch-", dir=WORK) as directory:
            root = Path(directory) / 'PC side'
            root.mkdir()
            launcher = root / "STEMWISE_AGENT.ps1"
            copy_launcher(launcher)
            builder = root / "stemwise-playlist-builder"
            release = builder / "release"
            started = builder / "started.json"
            script = rf"""
. '{_windows_path(launcher).replace("'", "''")}'
$agentRoot = '{_windows_path(root).replace("'", "''")}'
$builder = Join-Path $agentRoot 'stemwise-playlist-builder'
foreach ($path in @(Get-StemWiseActionDependencies -Action EndpointAdjudicate -AgentRoot $agentRoot -Builder $builder)) {{
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
    [IO.File]::WriteAllText($path, '{{}}')
}}
$runnerText = @'
param([string]$Command, [string]$VideoId)
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'started.json'), (@{{pid=$PID;command=$Command;videoId=$VideoId}} | ConvertTo-Json -Compress))
$deadline = [DateTime]::UtcNow.AddSeconds(20)
while (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'release'))) {{
    if ([DateTime]::UtcNow -ge $deadline) {{ exit 99 }}
    Start-Sleep -Milliseconds 50
}}
[pscustomobject]@{{command=$Command;videoId=$VideoId}} | ConvertTo-Json -Compress
exit 23
'@
[IO.File]::WriteAllText((Join-Path $builder 'future-song.ps1'), $runnerText)
& (Join-Path $agentRoot 'STEMWISE_AGENT.ps1') -Action EndpointAdjudicate -VideoId '6TEa-ZSnZ4U'
"""
            try:
                try:
                    result = _run_encoded(script, timeout=5)
                except subprocess.TimeoutExpired:
                    self.fail("endpoint launcher waited for the unreleased worker instead of returning a receipt")
                self.assertEqual(result.returncode, 0, result.stderr)
                receipt = json.loads(result.stdout)
                self.assertEqual(receipt["action"], "EndpointAdjudicate")
                self.assertEqual(receipt["videoId"], "6TEa-ZSnZ4U")
                self.assertEqual(receipt["launchState"], "launched")
                self.assertIn("-Action Status -VideoId '6TEa-ZSnZ4U'", receipt["statusCommand"])
                completion = _host_path(receipt["completion"])
                self.assertFalse(completion.exists(), "worker finished before test released it")
                deadline = time.monotonic() + 10
                while not started.exists() and time.monotonic() < deadline:
                    time.sleep(.05)
                self.assertTrue(started.exists(), "detached endpoint worker did not start")
                observed = json.loads(started.read_text(encoding="utf-8-sig"))
                self.assertEqual(observed["command"], "endpointadjudicate")
                self.assertEqual(observed["videoId"], "6TEa-ZSnZ4U")
                release.touch()
                while not completion.exists() and time.monotonic() < deadline:
                    time.sleep(.05)
                self.assertTrue(completion.exists(), "endpoint exit result was not recorded")
                outcome = json.loads(completion.read_text(encoding="utf-8-sig"))
                self.assertEqual(outcome["exitCode"], 23)
                self.assertEqual(outcome["supervisorProcessId"], receipt["processId"])
                self.assertEqual(outcome["workerProcessId"], observed["pid"])
                self.assertEqual(json.loads(_host_path(receipt["stdout"]).read_text()),
                                 {"command": "endpointadjudicate", "videoId": "6TEa-ZSnZ4U"})
            finally:
                release.touch()
                if started.exists():
                    worker_pid = int(json.loads(started.read_text(encoding="utf-8-sig"))["pid"])
                    _run_encoded(f"$p=Get-Process -Id {worker_pid} -ErrorAction SilentlyContinue; "
                                 "if ($p) { Wait-Process -InputObject $p -Timeout 5 -ErrorAction Stop }",
                                 timeout=7)

    def test_detached_worker_returns_immediately_and_records_exit(self) -> None:
        launcher = _windows_path(LAUNCHER).replace("'", "''")
        script = rf"""
. '{launcher}'
$testRoot = Join-Path '{_windows_path(WORK).replace("'", "''")}' ('stemwise-detached-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$runner = Join-Path $testRoot 'fake-worker.ps1'
$runnerText = @'
param([string]$VideoId)
Start-Sleep -Seconds 6
[pscustomobject]@{{ videoId = $VideoId; currentDirectory = $PWD.Path }} | ConvertTo-Json -Compress
exit 23
'@
[IO.File]::WriteAllText($runner, $runnerText, [Text.UTF8Encoding]::new($false))
$stdout = Join-Path $testRoot 'stdout.log'
$stderr = Join-Path $testRoot 'stderr.log'
$completion = Join-Path $testRoot 'completion.json'
$watch = [Diagnostics.Stopwatch]::StartNew()
$process = Start-StemWiseDetachedWorker `
    -PowerShellExe (Resolve-StemWiseWindowsPowerShell) `
    -Runner $runner `
    -RunnerArguments @('-VideoId', '-AbCdEf1234') `
    -WorkingDirectory $testRoot `
    -Stdout $stdout `
    -Stderr $stderr `
    -Completion $completion
[pscustomobject]@{{
    elapsedMs = $watch.ElapsedMilliseconds
    processId = $process.ProcessId
    testRoot = $testRoot
    stdout = $stdout
    stderr = $stderr
    completion = $completion
}} | ConvertTo-Json -Compress
"""
        started = time.monotonic()
        result = _run_encoded(script, timeout=3.5)
        elapsed = time.monotonic() - started
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertLess(elapsed, 3.5)
        receipt = json.loads(result.stdout.strip())
        self.assertLess(receipt["elapsedMs"], 3000)
        self.assertGreater(receipt["processId"], 0)

        root = _host_path(receipt["testRoot"])
        completion_path = _host_path(receipt["completion"])
        try:
            deadline = time.monotonic() + 12
            while time.monotonic() < deadline and not completion_path.exists():
                time.sleep(0.1)
            self.assertTrue(completion_path.exists(), "completion record was not written")
            completion = json.loads(completion_path.read_text(encoding="utf-8-sig"))
            self.assertEqual(completion["exitCode"], 23)
            self.assertEqual(completion["supervisorProcessId"], receipt["processId"])
            self.assertGreater(completion["workerProcessId"], 0)

            stdout = _host_path(receipt["stdout"]).read_text(encoding="utf-8-sig")
            worker_output = json.loads(stdout.strip())
            self.assertEqual(worker_output["videoId"], "-AbCdEf1234")
            self.assertEqual(
                worker_output["currentDirectory"].casefold(),
                receipt["testRoot"].casefold(),
            )
            self.assertEqual(_host_path(receipt["stderr"]).read_text(), "")
            retired = _run_encoded(rf"""
$deadline = [DateTime]::UtcNow.AddSeconds(3)
do {{
    $alive = @(Get-Process -Id @({completion['supervisorProcessId']},{completion['workerProcessId']}) -ErrorAction SilentlyContinue)
    if ($alive.Count -eq 0) {{ break }}
    Start-Sleep -Milliseconds 20
}} while ([DateTime]::UtcNow -lt $deadline)
$alive.Count
""", timeout=5)
            self.assertEqual(retired.returncode, 0, retired.stderr)
            self.assertEqual(int(retired.stdout), 0, 'completed operation retained Windows workers')
        finally:
            self.assertTrue(root.name.startswith("stemwise-detached-test-"))
            shutil.rmtree(root, ignore_errors=True)

    def test_create_failure_throws_and_removes_unowned_logs(self) -> None:
        launcher = _windows_path(LAUNCHER).replace("'", "''")
        script = rf"""
. '{launcher}'
$testRoot = Join-Path '{_windows_path(WORK).replace("'", "''")}' ('stemwise-detached-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$stdout = Join-Path $testRoot 'stdout.log'
$stderr = Join-Path $testRoot 'stderr.log'
$threw = $false
try {{
    Start-StemWiseDetachedWorker `
        -PowerShellExe (Join-Path $testRoot 'does-not-exist.exe') `
        -Runner (Join-Path $testRoot 'fake.ps1') `
        -RunnerArguments @('-VideoId', '-AbCdEf1234') `
        -WorkingDirectory $testRoot `
        -Stdout $stdout `
        -Stderr $stderr `
        -Completion (Join-Path $testRoot 'completion.json') | Out-Null
}} catch {{
    $threw = $true
}}
[pscustomobject]@{{
    threw = $threw
    logsExist = ((Test-Path -LiteralPath $stdout) -or (Test-Path -LiteralPath $stderr))
    testRoot = $testRoot
}} | ConvertTo-Json -Compress
"""
        result = _run_encoded(script, timeout=3.5)
        self.assertEqual(result.returncode, 0, result.stderr)
        outcome = json.loads(result.stdout.strip())
        root = _host_path(outcome["testRoot"])
        try:
            self.assertTrue(outcome["threw"])
            self.assertFalse(outcome["logsExist"])
        finally:
            self.assertTrue(root.name.startswith("stemwise-detached-test-"))
            shutil.rmtree(root, ignore_errors=True)

    def test_action_specific_dependency_matrix(self) -> None:
        launcher = _windows_path(LAUNCHER).replace("'", "''")
        script = rf"""
. '{launcher}'
$agentRoot = '{_windows_path(PROJECT.parent)}'
$builder = Join-Path $agentRoot 'builder'
$matrix = [ordered]@{{}}
foreach ($action in @('Help','Doctor','Plan','Reset','Reprocess','Task','Status','BridgeDelivery','Source','Artwork','Finalize','Validate','New','Retry','EndpointEvidence','EndpointAdjudicate','RecordingEvidence','SyllaCandidate')) {{
    $matrix[$action] = @(Get-StemWiseActionDependencies `
        -Action $action -AgentRoot $agentRoot -Builder $builder)
}}
[pscustomobject]@{{
    powershell = Resolve-StemWiseWindowsPowerShell
    matrix = $matrix
}} | ConvertTo-Json -Depth 5 -Compress
"""
        result = _run_encoded(script, timeout=3.5)
        self.assertEqual(result.returncode, 0, result.stderr)
        outcome = json.loads(result.stdout.strip())
        matrix = {
            action: {value.replace("\\", "/") for value in paths}
            for action, paths in outcome["matrix"].items()
        }

        self.assertTrue(
            outcome["powershell"].replace("\\", "/").casefold().endswith(
                "/system32/windowspowershell/v1.0/powershell.exe"
            )
        )
        for action in ("Help", "Doctor"):
            self.assertEqual(matrix[action], set())

        minimal_suffixes = {
            '/builder/future-song.ps1','/builder/runtime-bootstrap.ps1','/builder/.tools/bootstrap/bin/python',
            '/builder/config.json','/builder/src/workflow_command.py',
            '/builder/future-songs/policy/lyrics-policy.json','/stemwise-playlist-builder/.venv/bin/python'}
        for action in ('Status','BridgeDelivery','Task','Source','Artwork','Finalize','Validate','EndpointEvidence','Retry','Reprocess'):
            self.assertEqual(len(matrix[action]),len(minimal_suffixes))
            self.assertTrue(all(any(path.endswith(suffix) for path in matrix[action]) for suffix in minimal_suffixes))
        for action, entry in (('Reset', '/builder/src/campaign_maintenance.py'),
                              ('Plan', '/qobuz-comparison/audio_library.py')):
            self.assertEqual(len(matrix[action]), 5)
            self.assertTrue(any(path.endswith(entry) for path in matrix[action]))
            self.assertFalse(any('model-cache' in path or '.venv-allin1' in path or 'lyrics-align' in path
                                 for path in matrix[action]))
        self.assertEqual(len(matrix['EndpointAdjudicate']),10)
        recording = matrix["RecordingEvidence"]
        self.assertTrue(any(p.endswith('/src/recording_evidence.py') for p in recording))
        self.assertTrue(any(p.endswith('/.venv-lyrics-align/bin/python') for p in recording))
        self.assertTrue(any(p.endswith('/src/model-route-registry.json') for p in recording))
        self.assertFalse(any(p.endswith('.th') for p in recording))
        shadow_suffixes = minimal_suffixes | {'/builder/src/sylla_candidate.py',
            '/builder/src/sylla_forced_alignment.py', '/builder/src/sylla-model.json'}
        self.assertEqual(len(matrix['SyllaCandidate']), len(shadow_suffixes))
        self.assertTrue(all(any(path.endswith(suffix) for path in matrix['SyllaCandidate'])
            for suffix in shadow_suffixes))
        self.assertFalse(any('lyrics-align' in path or '.venv-allin1' in path or 'model-cache' in path
            for path in matrix['SyllaCandidate']))
        self.assertEqual(len(matrix['New']), 12)
        self.assertTrue(any(path.endswith('/.venv-allin1/bin/python') for path in matrix['New']))
        self.assertFalse(any('lyrics-align' in path or path.endswith('.th') for path in matrix['New']))

        runner = (PROJECT / "future-song.ps1").read_text(encoding="utf-8-sig")
        selection = runner.index('Get-StemWiseRuntime -BuilderWindows $PSScriptRoot')
        self.assertNotIn("'reopen'", runner)
        self.assertNotIn("$Command -eq 'validate'", runner)
        self.assertLess(selection, runner.index("Invoke-StemWiseRuntime -Runtime $runtime"))


    def test_task_rejects_video_id_path_traversal(self) -> None:
        launcher = _windows_path(LAUNCHER).replace("'", "''")
        script = rf"""
$threw = $false
$message = ''
try {{
    & '{launcher}' -Action Task -VideoId '..\outside'
}} catch {{
    $threw = $true
    $message = $_.Exception.Message
}}
[pscustomobject]@{{ threw = $threw; message = $message }} | ConvertTo-Json -Compress
"""
        result = _run_encoded(script, timeout=3.5)
        self.assertEqual(result.returncode, 0, result.stderr)
        outcome = json.loads(result.stdout.strip())
        self.assertTrue(outcome["threw"])
        self.assertIn("11-character YouTube video ID", outcome["message"])

    def test_atomic_json_record_updates_pending_intent(self) -> None:
        launcher = _windows_path(LAUNCHER).replace("'", "''")
        script = rf"""
. '{launcher}'
$testRoot = Join-Path $env:TEMP ('stemwise-launch-record-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$record = Join-Path $testRoot 'launch.json'
$launch = [ordered]@{{ launchState = 'pending'; processId = $null }}
Write-StemWiseJsonAtomic -Path $record -Value $launch
$pending = Get-Content -Raw -LiteralPath $record | ConvertFrom-Json
$launch['launchState'] = 'launched'
$launch['processId'] = 12345
Write-StemWiseJsonAtomic -Path $record -Value $launch
$launched = Get-Content -Raw -LiteralPath $record | ConvertFrom-Json
$temporaryCount = @(Get-ChildItem -LiteralPath $testRoot -File | Where-Object {{
    $_.Name.EndsWith('.tmp') -or $_.Name.EndsWith('.bak')
}}).Count
[pscustomobject]@{{
    testRoot = $testRoot
    pendingState = $pending.launchState
    pendingProcessId = $pending.processId
    launchedState = $launched.launchState
    launchedProcessId = $launched.processId
    temporaryCount = $temporaryCount
}} | ConvertTo-Json -Compress
"""
        result = _run_encoded(script, timeout=3.5)
        self.assertEqual(result.returncode, 0, result.stderr)
        outcome = json.loads(result.stdout.strip())
        root = _host_path(outcome["testRoot"])
        try:
            self.assertEqual(outcome["pendingState"], "pending")
            self.assertIsNone(outcome.get("pendingProcessId"))
            self.assertEqual(outcome["launchedState"], "launched")
            self.assertEqual(outcome["launchedProcessId"], 12345)
            self.assertEqual(outcome["temporaryCount"], 0)
        finally:
            self.assertTrue(root.name.startswith("stemwise-launch-record-test-"))
            shutil.rmtree(root, ignore_errors=True)




class PortableAgentContractTests(unittest.TestCase):
    def test_core_orchestrator_can_import_alignment_contract(self) -> None:
        script = '\nimport builtins\nimport sys\n\nreal_import = builtins.__import__\n\ndef without_syncalong(name, *args, **kwargs):\n    if name == "syncalong" or name.startswith("syncalong."):\n        raise ModuleNotFoundError("blocked dedicated runtime", name="syncalong")\n    return real_import(name, *args, **kwargs)\n\nbuiltins.__import__ = without_syncalong\nfrom stemwise_engine.lyrics_alignment import (ALIGNMENT_VERSION, AlignmentWorkspace, snapshot_canonical_plain)\nassert isinstance(ALIGNMENT_VERSION, int)\nassert AlignmentWorkspace\nassert snapshot_canonical_plain\n'
        environment = os.environ.copy()
        environment["PYTHONPATH"] = os.pathsep.join(
            filter(
                None,
                (str(PROJECT / "src"), environment.get("PYTHONPATH")),
            )
        )
        result = subprocess.run(
            [sys.executable, "-c", script],
            capture_output=True,
            text=True,
            check=False,
            env=environment,
        timeout=60)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_receipt_contract_uses_unique_atomic_records(self) -> None:
        source = LAUNCHER.read_text(encoding="utf-8-sig") + (PROJECT/"process-supervisor.ps1").read_text() + (PROJECT/"atomic-json.ps1").read_text()
        self.assertIn("([WmiClass]'Win32_Process').Create", source)
        self.assertIn("[Guid]::NewGuid().ToString('N')", source)
        self.assertIn("completion = $Completion", source)
        self.assertIn("workingDirectory = $WorkingDirectory", source)
        self.assertIn("($processArguments -join ' '), $payload.workingDirectory", source)
        self.assertIn("launchState = 'pending'", source)
        pending_write = source.index("Write-StemWiseJsonAtomic -Path $Record -Value $Launch")
        process_create = source.index("$Process = Start-StemWiseDetachedWorker", pending_write)
        launched_state = source.index("$Launch['launchState'] = 'launched'", process_create)
        self.assertLess(pending_write, process_create)
        self.assertLess(process_create, launched_state)
        self.assertIn("\"& '$EscapedLauncherPath' -Action Status -VideoId '$EscapedStatusVideoId'\"", source)
        self.assertNotIn('".\\STEMWISE_AGENT.ps1 -Action Status', source)
        self.assertIn("$Launch['processId'] = $Process.ProcessId", source)
        self.assertNotIn("processId = $Process.Id", source)
        self.assertNotIn("Join-Path $PSHOME 'powershell.exe'", source)
        self.assertIn("System32\\WindowsPowerShell\\v1.0\\powershell.exe", source)
        self.assertIn("Write-StemWiseJsonAtomic -Path $payload.completion -Value $completionRecord", source)
        self.assertIn("$Stream.Flush($true)", source)
        self.assertLess(
            source.index("if ($Action -eq 'Doctor')"),
            source.index("$RequiredFiles = Get-StemWiseActionDependencies"),
        )



if __name__ == "__main__":
    unittest.main()

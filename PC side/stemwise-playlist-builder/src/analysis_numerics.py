"""Current acoustic producers and scheduling; diagnostics have a separate owner."""
from __future__ import annotations

import os
import signal
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from contextvars import copy_context
from pathlib import Path
from typing import Any

from stemwise_engine.analyzers import (analyze_allin1, analyze_allin1_many, analyze_beat_this,
                       analyze_essentia, analyze_skey, analyze_tonal_evidence, normalize_rhythm, overall_analysis_status)
from stemwise_engine.common import diagnostic_code, WorkerRetirementError
from stemwise_engine.analysis_resources import analysis_resource_plan, require_analysis_capacity
from stemwise_engine.private_diagnostics import (DiagnosticCode, error_record, attach_analysis_causes,
                                 emit_analysis_stage_failure)
from stemwise_engine.waveform import build_waveform


def _waveform_analysis(audio_path, duration_ms):
    try:
        waveform = build_waveform(audio_path, duration_ms=duration_ms)
        waveform_tool = {"name": "stemwise-waveform", "status": "complete", "version": "1"}
    except Exception as error:
        reason = DiagnosticCode(f"waveform-unavailable:{diagnostic_code(error)}", [error_record(error)])
        waveform = {"status": "unavailable", "reason": reason}
        waveform_tool = {"name": "stemwise-waveform", "status": "failed", "version": "1", "reason": reason}
    return waveform, waveform_tool


def _analysis_document(audio_path, duration_ms, gpu, cpu, allin1):
    rhythm, normalizer_tool, key, skey_tool, beat_tool = gpu
    skey_tool = dict(skey_tool)
    skey_evidence = skey_tool.pop('_privateKeyEvidence', None)
    tuning, loudness, essentia_tool = cpu
    structure, diagnostic, allin1_tool = allin1
    waveform, waveform_tool = _waveform_analysis(audio_path, duration_ms)
    analysis = {
        "status": "partial", "durationMs": duration_ms, "rhythm": rhythm, "key": key,
        "structure": structure, "tuning": tuning, "loudness": loudness, "waveform": waveform,
        "toolRuns": [beat_tool, normalizer_tool, skey_tool, allin1_tool, essentia_tool, waveform_tool],
    }
    if diagnostic:
        analysis["diagnostics"] = {"allInOneRhythm": diagnostic}
    analysis["status"] = overall_analysis_status(analysis)
    if skey_evidence is not None:
        analysis['_privateKeyEvidence'] = {'formatVersion': 1, 'skey': skey_evidence,
            'tonal': analyze_tonal_evidence(audio_path, structure)}
    attach_analysis_causes(analysis)
    return analysis



def _gpu_analysis(audio_path, duration_ms):
    plan = analysis_resource_plan(duration_ms)
    if not plan['fullWindowAdmitted']:
        unavailable = {'status': 'unavailable', 'reason': plan['reason']}
        tool = lambda name: {'name': name, 'status': 'skipped', 'reason': plan['reason']}
        skey_tool = {**tool('s-key'), '_privateKeyEvidence': {'status': 'unavailable', 'reason': plan['reason']}}
        return unavailable, tool('stemwise-rhythm-normalizer'), unavailable, skey_tool, tool('beat-this-full')
    beats, downbeats, beat_tool = analyze_beat_this(audio_path, duration_ms)
    rhythm, normalizer_tool = normalize_rhythm(beats, downbeats, duration_ms)
    key, skey_tool = analyze_skey(audio_path)
    return rhythm, normalizer_tool, key, skey_tool, beat_tool



def repair_components(previous, duration_ms):
    from stemwise_engine.contract import _validate_analysis
    duration, _ = _validate_analysis(previous, '$.analysis')
    if duration != duration_ms or previous['status'] == 'unavailable':
        raise ValueError('Repair needs a usable matching prior analysis')
    plan = analysis_resource_plan(duration_ms)
    targets = {row['name'] for row in previous['toolRuns'] if row['status'] == 'failed'
        or row['status'] == 'skipped' and row.get('reason') in {
            'analysis-full-window-budget-exceeded', 'analysis-streaming-memory-unavailable'}
        and (plan['streamingAdmitted'] if row['name'] in {'essentia', 'stemwise-waveform'}
             else plan['fullWindowAdmitted'])}
    if plan['streamingAdmitted'] and any(previous[name]['status'] == 'unavailable'
            and previous[name].get('reason') != 'tuning-insufficient-pitched-frames' for name in ('tuning', 'loudness')):
        essentia = next(row for row in previous['toolRuns'] if row['name'] == 'essentia')
        if essentia['status'] in {'complete', 'failed'}:
            targets.add('essentia')
    return targets


def _repair_analysis_worker(audio_path, duration_ms, project_dir, timeout_seconds, previous):
    """Run only failed or newly admitted producers; preserve independent results."""
    from copy import deepcopy
    targets = repair_components(previous, duration_ms)
    if not targets:
        return previous
    deadline = time.monotonic() + timeout_seconds
    result = deepcopy(previous)
    tools = {row['name']: row for row in result['toolRuns']}
    recovered = False
    skey_evidence = None
    if targets & {'beat-this-full', 'stemwise-rhythm-normalizer'}:
        if 'beat-this-full' in targets:
            beats, downbeats, beat_tool = analyze_beat_this(audio_path, duration_ms)
        else:
            events = previous['rhythm'].get('beats', [])
            beats = [row['timeMs'] for row in events]
            downbeats = [row['timeMs'] for row in events if row['isDownbeat']]
            beat_tool = tools['beat-this-full']
        if beat_tool['status'] == 'complete':
            rhythm, normalizer = normalize_rhythm(beats, downbeats, duration_ms)
            result['rhythm'] = rhythm
            tools.update({'beat-this-full': beat_tool, 'stemwise-rhythm-normalizer': normalizer})
            recovered = ('beat-this-full' in targets or normalizer['status'] == 'complete')
    if 's-key' in targets:
        key, tool = analyze_skey(audio_path)
        skey_evidence = tool.pop('_privateKeyEvidence', None)
        if tool['status'] == 'complete':
            result['key'], tools['s-key'], recovered = key, tool, True
    if 'all-in-one' in targets:
        structure, diagnostic, tool = analyze_allin1(audio_path, duration_ms, project_dir=project_dir,
            timeout_seconds=max(1, int(deadline-time.monotonic())))
        if tool['status'] == 'complete':
            result['structure'], tools['all-in-one'], recovered = structure, tool, True
            if diagnostic:
                result.setdefault('diagnostics', {})['allInOneRhythm'] = diagnostic
    if 'essentia' in targets:
        tuning, loudness, tool = analyze_essentia(audio_path)
        changed = False
        for name, value in (('tuning', tuning), ('loudness', loudness)):
            if previous[name]['status'] == 'unavailable' and (value['status'] != 'unavailable'
                    or value.get('reason') == 'tuning-insufficient-pitched-frames'):
                result[name], recovered, changed = value, True, True
        if changed:
            if previous['loudness'].get('descriptors'):
                tool['profile'] = tools['essentia']['profile']
            if any(result[name]['status'] != 'unavailable' for name in ('tuning', 'loudness')):
                tool = {**tool, 'status': 'complete'}
                tool.pop('reason', None)
            tools['essentia'] = tool
    if 'stemwise-waveform' in targets:
        waveform, tool = _waveform_analysis(audio_path, duration_ms)
        result['waveform'], tools['stemwise-waveform'] = waveform, tool
        recovered = recovered or tool['status'] == 'complete'
    if not recovered:
        raise RuntimeError('No failed acoustic component recovered')
    if time.monotonic() >= deadline:
        raise TimeoutError('analysis repair worker exceeded its deadline')
    result['toolRuns'] = list(tools.values())
    result['status'] = overall_analysis_status(result)
    if skey_evidence is not None:
        result['_privateKeyEvidence'] = {'formatVersion': 1, 'skey': skey_evidence,
            'tonal': analyze_tonal_evidence(audio_path, result['structure'])}
    attach_analysis_causes(result)
    return result


def _analysis_worker(
    audio_path: Path,
    duration_ms: int,
    project_dir: Path,
    timeout_seconds: int,
) -> dict[str, Any]:
    """Run the analyzer bundle inside its bounded worker process."""

    require_analysis_capacity(duration_ms)
    deadline = time.monotonic() + timeout_seconds
    if not analysis_resource_plan(duration_ms)['parallel']:
        gpu = _gpu_analysis(audio_path, duration_ms)
        cpu = analyze_essentia(audio_path)
        structure = analyze_allin1(audio_path, duration_ms, project_dir=project_dir,
            timeout_seconds=max(1, int(deadline-time.monotonic())))
        if time.monotonic() >= deadline:
            raise TimeoutError('analysis worker exceeded its deadline')
        return _analysis_document(audio_path, duration_ms, gpu, cpu, structure)
    # Keep subprocess supervision on the main thread so cancellation kills its tree.
    with ThreadPoolExecutor(max_workers=2) as workers:
        gpu = workers.submit(copy_context().run, _gpu_analysis, audio_path, duration_ms)
        cpu = workers.submit(copy_context().run, analyze_essentia, audio_path)
        structure = analyze_allin1(
            audio_path,
            duration_ms,
            project_dir=project_dir,
            timeout_seconds=max(1, int(deadline - time.monotonic())),
        )
        gpu_result = gpu.result(
            timeout=max(0, deadline - time.monotonic()))
        cpu_result = cpu.result(
            timeout=max(0, deadline - time.monotonic()))
    if time.monotonic() >= deadline:
        raise TimeoutError("analysis worker exceeded its deadline")
    return _analysis_document(audio_path, duration_ms, gpu_result, cpu_result, structure)



def _analysis_worker_many(inputs, project_dir, timeout_seconds, on_completed=None):
    """One bounded batch reuses models; GPU song stages remain serial."""
    if any(not analysis_resource_plan(duration)['parallel'] for _, duration in inputs):
        results = []
        for index, (audio, duration) in enumerate(inputs):
            from stemwise_engine.provider_operations import ProviderCancelled
            print(f'STEMWISE_ACOUSTIC_SERIAL_STARTED={index}', flush=True)
            try:
                row = _analysis_worker(audio, duration, project_dir, timeout_seconds)
            except (WorkerRetirementError, ProviderCancelled):
                raise
            except Exception as error:
                emit_analysis_stage_failure(error, index, 0)
                row = {'error': 'acoustic-stage-failed'}
            results.append(row)
            if on_completed is not None:
                on_completed(index, row)
        return results
    components = [[None, None, None] for _ in inputs]
    results = [None] * len(inputs)
    publication = threading.Lock()
    stopped = threading.Event()
    fatal = []

    def stop(error):
        with publication:
            if not fatal or isinstance(error, WorkerRetirementError) and not isinstance(fatal[0], WorkerRetirementError):
                fatal[:] = [error]
            stopped.set()

    def ready(index, component, result):
        with publication:
            if stopped.is_set():
                return
            components[index][component] = result
            if results[index] is not None or any(item is None for item in components[index]):
                return
            gpu, cpu, structure = components[index]
            if isinstance(gpu, Exception) or isinstance(cpu, Exception):
                result = {'error': 'acoustic-stage-failed'}
            else:
                audio, duration = inputs[index]
                result = _analysis_document(audio, duration, gpu, cpu, structure)
            results[index] = result
            if on_completed is not None:
                on_completed(index, result)

    def stages(function, component):
        for index, (audio, duration) in enumerate(inputs):
            if stopped.is_set():
                return
            # A Python interrupt cannot stop a blocked native inference thread.
            timer = threading.Timer(timeout_seconds, lambda: os.kill(os.getpid(), signal.SIGKILL))
            timer.start()
            try:
                result = function(audio, duration)
            except (WorkerRetirementError, KeyboardInterrupt) as error:
                stop(error)
                return
            except Exception as error:
                from stemwise_engine.provider_operations import ProviderCancelled
                if isinstance(error, ProviderCancelled):
                    stop(error)
                    return
                emit_analysis_stage_failure(error, index, component)
                result = error
            finally:
                timer.cancel()
                timer.join()
            ready(index, component, result)

    with ThreadPoolExecutor(max_workers=2) as workers:
        gpu = workers.submit(copy_context().run, stages, _gpu_analysis, 0)
        cpu = workers.submit(copy_context().run, stages, lambda audio, _: analyze_essentia(audio), 1)
        try:
            structure = analyze_allin1_many(inputs, project_dir=project_dir, timeout_seconds=timeout_seconds,
                                           on_result=lambda index, row: ready(index, 2, row),
                                           cancelled=stopped.is_set)
            if len(structure) != len(inputs):
                raise ValueError('All-In-One batch result count differs')
            for index, row in enumerate(structure):
                ready(index, 2, row)
            gpu.result()
            cpu.result()
        except BaseException as error:
            stop(error)
    for future in (gpu, cpu):
        try:
            future.result()
        except BaseException as error:
            stop(error)
    if fatal:
        raise fatal[0]
    return results

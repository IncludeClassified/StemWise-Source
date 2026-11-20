"""Current analyzer resource admission, independent of qualified timing numerics."""
from dataclasses import dataclass
from pathlib import Path
import shutil
from stemwise_engine.acoustic_inputs import AcousticBudgetExceeded


@dataclass(frozen=True)
class AnalysisResourcePolicy:
    version: int
    essentiaWindowMs: int
    minimumProcessReserveBytes: int
    minimumScratchReserveBytes: int

    def __post_init__(self):
        if (type(self.version) is not int or self.version != 2 or any(type(value) is not int or value <= 0 for value in
                (self.essentiaWindowMs, self.minimumProcessReserveBytes, self.minimumScratchReserveBytes))):
            raise ValueError('Unsupported acoustic resource policy')


def analysis_resource_policy():
    from stemwise_engine.common import read_bounded_json
    return AnalysisResourcePolicy(**read_bounded_json(Path(__file__).with_name('analysis-resource-policy.json'),
        max_bytes=4096))


def available_memory_bytes():
    # The PC engine runs on Linux; include its cgroup limit when one is present.
    fields = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text(encoding='ascii').splitlines())
    available = int(fields['MemAvailable'].split()[0]) * 1024
    limit = Path('/sys/fs/cgroup/memory.max')
    if limit.exists():
        maximum = limit.read_text(encoding='ascii').strip()
        if maximum != 'max':
            used = int(limit.with_name('memory.current').read_text(encoding='ascii'))
            available = min(available, max(0, int(maximum)-used))
    return available


def analysis_resource_plan(duration_ms):
    if type(duration_ms) is not int or duration_ms <= 0:
        raise ValueError('Analysis resource admission needs a positive duration')
    policy = analysis_resource_policy()
    available = available_memory_bytes()
    pcm_budget = max(0, available-policy.minimumProcessReserveBytes)
    # Decode/resampling arrays only; model-owned buffers retain their runtime's own limits.
    producer = (duration_ms * 48000*2*4*4 + 999) // 1000
    combined = producer + (duration_ms * (44100*4*2 + 22050*4*2) + 999) // 1000
    streaming = (min(duration_ms, policy.essentiaWindowMs)*44100*4*2+999)//1000
    stream_admitted = streaming <= pcm_budget
    full_admitted = stream_admitted and producer <= pcm_budget
    reason = (None if full_admitted else 'analysis-streaming-memory-unavailable' if not stream_admitted
        else 'analysis-full-window-budget-exceeded')
    return {'policyVersion': policy.version, 'fullWindowAdmitted': full_admitted,
        'streamingAdmitted': stream_admitted, 'essentiaWindowMs': policy.essentiaWindowMs,
        'combinedPcmBytes': combined, 'pcmBudgetBytes': pcm_budget,
        'parallel': full_admitted and combined <= pcm_budget, 'reason': reason}


def require_analysis_capacity(duration_ms, *, scratch_root=None):
    plan = analysis_resource_plan(duration_ms)
    if not plan['streamingAdmitted']:
        raise AcousticBudgetExceeded(plan['reason'])
    if scratch_root is not None:
        path = Path(scratch_root)
        while not path.exists():
            if path == path.parent:
                raise ValueError('Analysis scratch has no existing filesystem owner')
            path = path.parent
        # Canonical float PCM plus the All-In-One alias copy when hard links fail.
        required = (duration_ms * 48000 * 2 * 4 * 2 + 999) // 1000
        required += analysis_resource_policy().minimumScratchReserveBytes
        available = shutil.disk_usage(path).free
        plan.update(minimumScratchBytes=required, availableScratchBytes=available)
        if available < required:
            raise AcousticBudgetExceeded('analysis-scratch-space-unavailable')
    return plan

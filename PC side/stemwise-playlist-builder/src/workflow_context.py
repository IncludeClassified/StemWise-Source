"""Current workflow layout and immutable source-specific construction inputs."""
from dataclasses import dataclass
from pathlib import Path
import re


def telemetry_job_root(workspace: Path, source_key: str) -> Path:
    if not isinstance(source_key, str) or re.fullmatch(
            r'(?:youtube:[A-Za-z0-9_-]{11}|qobuz:[1-9][0-9]*)', source_key) is None:
        raise ValueError('Telemetry source identity is invalid')
    return workspace / 'Engine telemetry' / source_key.replace(':', '-', 1)


def report_control_path(report_root: Path, name: str) -> Path:
    """Mutable report selection is runtime state; immutable runs remain auditable."""
    if name not in {'current-run.json', 'current-operation.json'}:
        raise ValueError('Report control name is invalid')
    return report_root / 'runtime' / name


def current_state_root(install_root: Path) -> Path:
    return install_root / 'policy-state/current'


def native_state_root(install_root: Path, identity: dict) -> Path:
    return install_root / 'policy-state' / f"current-qobuz-{identity['trackId']}-{identity['sha256']}"


@dataclass(frozen=True)
class WorkflowSourceContext:
    state_name: str
    root: Path
    ready: Path
    reports: Path
    analysis_temp: Path
    recording_key: str
    recording_identity_json: bytes
    lyric_reference_ids: tuple[str, ...]


@dataclass(frozen=True)
class SourceDeliveryPlan:
    source_key: str
    selected_before: dict | None
    metadata_only: bool

    @property
    def source_kind(self):
        return 'native' if self.metadata_only else 'youtube'

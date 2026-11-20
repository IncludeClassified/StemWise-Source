"""Select timing evidence for the exact admitted lyric sequence."""
from __future__ import annotations
from typing import Any
from dataclasses import dataclass
from pathlib import Path
from stemwise_engine.alignment_phases import StructureSelectionRequest, StructureSelectionResult
from stemwise_engine.lyric_content import (confirm_content, require_content, timing_observation_eligible,
    is_source_recording_admission, require_timing_only)
from stemwise_engine.lyrics_alignment import AlignmentResult, _physical_lines as _plain_physical_lines
from stemwise_engine.workflow_records import LyricsContentBlocker


def _result_score(result: Any) -> float:
    metrics = result.metrics
    return (
        metrics.lyric_word_coverage * 0.50
        + metrics.direct_line_coverage * 0.30
        + (metrics.average_word_score / 100.0) * 0.15
        + min(metrics.timed_span_ratio, 1.0) * 0.05
    )


def _baseline_source_indices(text: str) -> tuple[int, ...]:
    return tuple(index for index, line in enumerate(_plain_physical_lines(text)) if line)


def _has_structural_mutation(text: str, result: AlignmentResult) -> bool:
    return result.source_physical_indices != _baseline_source_indices(text)


def _select_evidence_option(options, *, text, reviewed_baseline_structure=False,
                            performed_structure_review_sha256=None):
    if not options:
        raise RuntimeError("no lyric timing evidence was produced")
    if any(_has_structural_mutation(text, item[1]) for item in options):
        raise LyricsContentBlocker("content-occurrence-uncertain")
    selected = max(options, key=lambda item: (item[1].accepted, _result_score(item[1])))
    if reviewed_baseline_structure:
        if performed_structure_review_sha256 is None:
            raise ValueError("performed baseline structure review is inapplicable")
        evidence = {**selected[2], 'performedStructureReview': {
            'decision': 'baseline', 'sha256': performed_structure_review_sha256}}
        return selected[0], selected[1], evidence, selected[3]
    return selected


@dataclass(kw_only=True)
class StructureSelectionStage:
    request: StructureSelectionRequest
    options: list[tuple[str, AlignmentResult, dict[str, Any], bool]]
    merge_transcripts: list[list[Any]]
    progress: dict[str, Any]
    merge_evidence_paths: list[Path | None] | None = None

    def select(self) -> StructureSelectionResult:
        sources = _baseline_source_indices(self.request.original["text"])
        admission = require_content(self.progress.get("contentAdmission"), self.request.original, sources,
            self.progress.get("canonicalPlainSha256"),
            self.progress.get("contentBinding", {}).get("audioSha256"), self.request.evidence_root)
        paths = self.merge_evidence_paths or [None] * len(self.merge_transcripts)
        if len(paths) != len(self.merge_transcripts):
            raise ValueError('Every structure transcript requires an explicit evidence association')
        for transcript, path in zip(self.merge_transcripts, paths):
            source_admission = (confirm_content(self.request.original, sources,
                admission.canonical_plain_sha256, admission.audio_sha256,
                evidence_path=path, evidence_root=self.request.evidence_root) if path is not None else admission)
            timing_observation_eligible(source_admission, self.request.original, sources, transcript,
                evidence_path=path, evidence_root=self.request.evidence_root)
        for _, result, _, _ in self.options:
            if result.source_physical_indices != sources:
                raise LyricsContentBlocker("content-occurrence-uncertain", binding=admission.binding)
            if not result.accepted:
                if (result.reasons == ('recognition-wording-mismatch',) and not result.starts_ms
                        and is_source_recording_admission(admission, self.request.evidence_root)):
                    continue
                require_timing_only(result.reasons, admission)
        self.progress["internalRepeatAuditComplete"] = True
        return StructureSelectionResult(*_select_evidence_option(
            self.options,
            text=self.request.original["text"],
            reviewed_baseline_structure=self.request.reviewed_baseline_structure,
            performed_structure_review_sha256=(
                self.request.performed_structure_review_sha256
            ),
        ))

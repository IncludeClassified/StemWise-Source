"""Editorial input validation, independent of workflow orchestration and model setup."""
from __future__ import annotations
from dataclasses import dataclass
import re
import time
import unicodedata
from pathlib import Path
from typing import Any
from stemwise_engine.common import read_bounded_regular_bytes, validate_video_id
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.metadata_research import validate_public_source_url
from stemwise_engine.workflow_records import FutureSongError

POLICY_VERSION = "stemwise-future-song/24"
ALLOWED_DISPOSITIONS = {
    "accepted-exact-recording",
    "accepted-same-song-version",
    "accepted-owner-supplied",
    "accepted-provisional-exact-id-provider",
    "accepted-provisional-published-source",
}
@dataclass(frozen=True)
class EditorialPolicy:
    version: str
    dispositions: frozenset[str]


CURRENT_POLICY = EditorialPolicy(POLICY_VERSION, frozenset(ALLOWED_DISPOSITIONS))

def validate_plain_draft(path: Path) -> None:
    try:
        payload = read_bounded_regular_bytes(
            path, max_bytes=MAX_JSON_BYTES, root=path.parent
        )
    except ValueError as error:
        raise FutureSongError(f"{path.name}: must be a bounded regular local file") from error
    if payload.startswith(b"\xef\xbb\xbf"):
        raise FutureSongError(f"{path.name}: UTF-8 BOM is not allowed")
    try:
        text = payload.decode("utf-8")
    except UnicodeDecodeError as error:
        raise FutureSongError(f"{path.name}: must be valid UTF-8") from error
    if "\r" in text:
        raise FutureSongError(f"{path.name}: must use LF line endings")
    if not text.endswith("\n") or text.endswith("\n\n"):
        raise FutureSongError(f"{path.name}: must have exactly one final LF")
    body = text[:-1]
    if not body or not body.strip() or body.startswith("\n") or body.endswith("\n"):
        raise FutureSongError(f"{path.name}: has an invalid leading/trailing blank line")
    if unicodedata.normalize("NFC", body) != body:
        raise FutureSongError(f"{path.name}: must be Unicode NFC")
    lines = body.split("\n")
    for index, line in enumerate(lines, start=1):
        if line.rstrip(" \t") != line:
            raise FutureSongError(f"{path.name}:{index}: trailing horizontal whitespace")
    if any(not lines[index].strip() and not lines[index + 1].strip() for index in range(len(lines) - 1)):
        raise FutureSongError(f"{path.name}: more than one blank line separates sections")


def validate_recording_review(
    review: dict[str, Any],
    *,
    audio_sha256: str,
    draft_sha256: str,
    sources_sha256: str,
    available_source_ids: set[str],
) -> None:
    """Bind a recording comparison to its audio, wording, and source evidence."""
    expected = {
        "formatVersion", "audioSha256", "draftSha256", "sourcesSha256",
        "recordingCompared", "versionSpecificWordingReviewed", "performedOrderReviewed",
        "sourceAssociations", "publicSources",
    }
    if not isinstance(review, dict) or set(review) != expected:
        raise FutureSongError("recordingReview has missing or unexpected properties")
    if type(review["formatVersion"]) is not int or review["formatVersion"] != 2:
        raise FutureSongError("recordingReview formatVersion must be 2")
    for key, actual in (
        ("audioSha256", audio_sha256),
        ("draftSha256", draft_sha256),
        ("sourcesSha256", sources_sha256),
    ):
        claimed = review[key]
        if any(
            not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{64}", value) is None
            for value in (claimed, actual)
        ):
            raise FutureSongError(f"recordingReview {key} must be a SHA-256 digest")
        if claimed != actual:
            raise FutureSongError(f"recordingReview {key} is stale")
    for key in (
        "recordingCompared", "versionSpecificWordingReviewed", "performedOrderReviewed",
    ):
        if review[key] is not True:
            raise FutureSongError(f"recordingReview {key} must be true")
    associations = review["sourceAssociations"]
    if not isinstance(associations, list):
        raise FutureSongError("recordingReview sourceAssociations must be an array")
    seen = set()
    usable = False
    for source in associations:
        if not isinstance(source, dict) or set(source) != {"videoId", "relation"}:
            raise FutureSongError("recordingReview source association is malformed")
        try:
            video_id = validate_video_id(source["videoId"])
        except (TypeError, ValueError) as error:
            raise FutureSongError("recordingReview source videoId is invalid") from error
        relation = source["relation"]
        if not isinstance(relation, str) or relation not in {
            "exact-recording", "text-reference", "different-version", "unusable",
        }:
            raise FutureSongError("recordingReview source relation is invalid")
        if video_id in seen:
            raise FutureSongError("recordingReview repeats a staged source")
        seen.add(video_id)
        usable |= relation in {"exact-recording", "text-reference"}
    if seen != available_source_ids:
        raise FutureSongError("recordingReview must classify every staged source exactly once")
    public_sources = review["publicSources"]
    if not isinstance(public_sources, list):
        raise FutureSongError("recordingReview publicSources must be an array")
    for source in public_sources:
        if not isinstance(source, dict) or set(source) != {"url", "relation"}:
            raise FutureSongError("recordingReview public source is malformed")
        try:
            validate_public_source_url(source["url"], staged_source_ids=available_source_ids)
        except ValueError as error:
            raise FutureSongError("recordingReview public source URL is invalid") from error
        relation = source["relation"]
        if not isinstance(relation, str) or relation not in {
            "exact-recording", "text-reference", "different-version",
        }:
            raise FutureSongError("recordingReview public source relation is invalid")
        usable |= relation in {"exact-recording", "text-reference"}
    if not usable:
        raise FutureSongError("recordingReview requires usable recording or text-reference evidence")


def valid_reviewer(value: Any) -> bool:
    return (isinstance(value, str) and bool(value.strip()) and len(value) <= 200
            and value.strip() != 'REPLACE_WITH_REVIEWER'
            and '\n' not in value and '\r' not in value)


def validate_agent_review(
    review: dict[str, Any],
    *,
    video_id: str,
    available_lyric_source_ids: set[str] | None = None,
    policy: EditorialPolicy = CURRENT_POLICY,
) -> str:
    common = {
        "formatVersion",
        "policyVersion",
        "videoId",
        "authorityOutcome",
        "recordingOrSourceAssociationReviewed",
        "youtubeSurfaceVariantsReviewed",
        "publicSourceResearchComplete",
        "normalYouTubeVideoId",
        "youtubeMusicVideoId",
        "lyricEvidenceVideoIds",
        "unresolvedMaterialIssues",
    }
    resolved = {
        "reviewer",
        "completedAt",
        "sourceDisposition",
        "originalReviewed",
    }
    optional = resolved | {"notes", "performedStructureReview", "recordingReview", "metadataReview",
                           'qaResolutions', 'companionReviews', 'recordingAbsenceReview'}
    if set(review) - common - optional or common - set(review):
        raise FutureSongError("agent-review.json has missing or unexpected properties")
    if review["formatVersion"] != 2 or review["policyVersion"] != policy.version:
        raise FutureSongError("agent-review.json uses the wrong policy/schema version")
    if review["videoId"] != video_id:
        raise FutureSongError("agent-review.json videoId does not match the job")
    if 'companionReviews' in review and (not isinstance(review['companionReviews'], dict)
            or set(review['companionReviews']) - {'translation', 'transliteration'}):
        raise FutureSongError('companionReviews must contain only current companion roles')
    if 'qaResolutions' in review:
        if not isinstance(review['qaResolutions'], list) or len(review['qaResolutions']) > 200:
            raise FutureSongError('qaResolutions must be a bounded list')
    outcome = review["authorityOutcome"]
    if outcome not in {"pending", "resolved", "authority-unresolved", "lyrics-absent"}:
        raise FutureSongError("agent-review.json authorityOutcome is invalid")
    if ('recordingAbsenceReview' in review) != (outcome == 'lyrics-absent'):
        raise FutureSongError('Only a completed absence review may close lyric recovery')
    if "notes" in review and (
        not isinstance(review["notes"], str)
        or len(review["notes"]) > 1000
        or "\n" in review["notes"]
        or "\r" in review["notes"]
    ):
        raise FutureSongError("agent-review.json notes must be one concise line")
    structure_review = review.get("performedStructureReview")
    if structure_review is not None:
        expected_structure_review = {
            "sourceAlignmentAttemptId",
            "sourceAlignmentRunSha256",
            "sourceAlignmentStateSha256",
            "decision",
            "witnessVideoId",
            "witnessLanguage",
            "witnessSha256",
            "reviewer",
            "completedAt",
        }
        if (
            not isinstance(structure_review, dict)
            or set(structure_review) != expected_structure_review
            or re.fullmatch(
                r"[0-9a-f]{32}",
                str(structure_review.get("sourceAlignmentAttemptId") or ""),
            )
            is None
            or any(
                re.fullmatch(r"[0-9a-f]{64}", str(structure_review.get(key) or ""))
                is None
                for key in (
                    "sourceAlignmentRunSha256",
                    "sourceAlignmentStateSha256",
                    "witnessSha256",
                )
            )
            or structure_review.get("decision") != "baseline"
            or not valid_reviewer(structure_review.get("reviewer"))
            or re.fullmatch(
                r"[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*",
                str(structure_review.get("witnessLanguage") or ""),
            )
            is None
            or re.fullmatch(
                r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z",
                str(structure_review.get("completedAt") or ""),
            )
            is None
        ):
            raise FutureSongError("performedStructureReview is malformed")
        try:
            witness_video_id = validate_video_id(structure_review["witnessVideoId"])
            time.strptime(structure_review["completedAt"], "%Y-%m-%dT%H:%M:%SZ")
        except (TypeError, ValueError) as error:
            raise FutureSongError("performedStructureReview is malformed") from error
        if not isinstance(review.get("lyricEvidenceVideoIds"), list) or (
            witness_video_id not in review["lyricEvidenceVideoIds"]
        ):
            raise FutureSongError("performedStructureReview witness is not approved")
    for key in (
        "recordingOrSourceAssociationReviewed",
        "youtubeSurfaceVariantsReviewed",
        "publicSourceResearchComplete",
    ):
        if type(review[key]) is not bool:
            raise FutureSongError(f"agent-review.json {key} must be Boolean")
    surface_ids: list[str] = []
    for key in ("normalYouTubeVideoId", "youtubeMusicVideoId"):
        value = review[key]
        if value is not None:
            if not isinstance(value, str):
                raise FutureSongError(f"agent-review.json {key} must be a video ID or null")
            try:
                surface_ids.append(validate_video_id(value))
            except ValueError as error:
                raise FutureSongError(f"agent-review.json {key} is not a valid video ID") from error
    evidence_ids = review["lyricEvidenceVideoIds"]
    if (
        not isinstance(evidence_ids, list)
        or not evidence_ids
        or any(not isinstance(value, str) for value in evidence_ids)
    ):
        raise FutureSongError("lyricEvidenceVideoIds must be a nonempty video-ID array")
    try:
        normalized_evidence_ids = [validate_video_id(value) for value in evidence_ids]
    except ValueError as error:
        raise FutureSongError("lyricEvidenceVideoIds contains an invalid video ID") from error
    if len(set(normalized_evidence_ids)) != len(normalized_evidence_ids):
        raise FutureSongError("lyricEvidenceVideoIds must not contain duplicates")
    if any(value not in normalized_evidence_ids for value in surface_ids):
        raise FutureSongError("reviewed surface IDs must be included in lyricEvidenceVideoIds")
    if available_lyric_source_ids is not None and (
        set(normalized_evidence_ids) != available_lyric_source_ids
    ):
        raise FutureSongError(
            "agent-review.json must acknowledge every staged lyric evidence ID exactly once"
        )
    issues = review["unresolvedMaterialIssues"]
    if (
        not isinstance(issues, list)
        or len(issues) > 20
        or any(
            not isinstance(item, str)
            or not item.strip()
            or len(item) > 300
            or "\n" in item
            or "\r" in item
            for item in issues
        )
    ):
        raise FutureSongError("unresolvedMaterialIssues must be a string array")
    if outcome == "pending":
        return outcome
    if not all(
        review[key]
        for key in (
            "recordingOrSourceAssociationReviewed",
            "youtubeSurfaceVariantsReviewed",
            "publicSourceResearchComplete",
        )
    ):
        raise FutureSongError("authority research is not marked complete")
    if not resolved <= set(review):
        if outcome == "resolved":
            raise FutureSongError("resolved agent-review.json is incomplete")
        required_completion = {"reviewer", "completedAt"}
        if not required_completion <= set(review):
            raise FutureSongError("authority-unresolved review has no completion identity")
    reviewer = review["reviewer"]
    if not valid_reviewer(reviewer):
        raise FutureSongError("agent-review.json requires a nonblank reviewer")
    completed_at = review["completedAt"]
    if not isinstance(completed_at, str) or not re.fullmatch(
        r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", completed_at
    ):
        raise FutureSongError("agent-review.json completedAt must be canonical UTC")
    try:
        time.strptime(completed_at, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as error:
        raise FutureSongError("agent-review.json completedAt is not a valid UTC time") from error
    if outcome == 'lyrics-absent':
        proof = review['recordingAbsenceReview']
        if (issues or not isinstance(proof, dict) or set(proof) != {'audioSha256', 'durationMs',
                'startMs', 'endMs', 'finding', 'heard', 'sourcesSha256'}
                or proof['finding'] != 'no-performed-words' or type(proof['startMs']) is not int
                or proof['startMs'] != 0 or type(proof['durationMs']) is not int or proof['durationMs'] <= 0
                or type(proof['endMs']) is not int or proof['endMs'] != proof['durationMs']
                or any(not isinstance(proof[k], str) or not re.fullmatch('[0-9a-f]{64}', proof[k])
                        for k in ('audioSha256','sourcesSha256'))
                or not isinstance(proof['heard'], str) or not proof['heard'].strip()):
            raise FutureSongError('Lyric absence requires a whole-recording listening finding and completed source research')
        return outcome
    if outcome == "authority-unresolved":
        if not issues:
            raise FutureSongError("authority-unresolved requires material unresolved issues")
        return outcome
    if review["sourceDisposition"] not in policy.dispositions:
        raise FutureSongError("agent-review.json has an unsupported source disposition")
    if type(review['originalReviewed']) is not bool:
        raise FutureSongError('agent-review.json originalReviewed must be Boolean')
    if not review["originalReviewed"]:
        raise FutureSongError("original lyrics were not reviewed")
    if issues:
        raise FutureSongError("material editorial issues remain unresolved")
    return outcome

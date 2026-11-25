"""Pure, body-free projections of current alignment and review records."""
from __future__ import annotations
import copy
import hashlib
import math
import re
from typing import Any
from stemwise_engine.workflow_records import (FutureSongError, LyricsContentBlocker, ROUTE_STAGES, ROUTE_STATUSES,
                              EndpointReviewResult)


def _diagnostic_codes(values: Any) -> list[str]:
    if not isinstance(values, list):
        return []
    return [
        value
        for value in values
        if isinstance(value, str)
        and re.fullmatch(r"[A-Za-z0-9_.:+-]{1,120}", value)
    ][:20]


def _stable_private_id(value: Any) -> Any:
    if not isinstance(value, str):
        return value
    if "/" in value or "\\" in value or not re.fullmatch(
        r"[A-Za-z0-9_.:+-]{1,120}", value
    ):
        return "sha256:" + hashlib.sha256(value.encode("utf-8")).hexdigest()
    return value


def _project_report_value(value, *, depth=0, numeric_only=False):
    if depth > 8:
        return None
    if type(value) in {bool, int}:
        return value
    if type(value) is float:
        return value if math.isfinite(value) else None
    if isinstance(value, str):
        return value if not numeric_only and re.fullmatch(r"[A-Za-z0-9_.:+-]{1,160}", value) else None
    if not isinstance(value, (list, dict)):
        return None
    if isinstance(value, list):
        items = [_project_report_value(v, depth=depth + 1, numeric_only=numeric_only) for v in value[:1000]]
        shown = [item for item in items if item is not None]
    else:
        from itertools import islice
        shown = {}
        for key, item in islice(value.items(), 1000):
            if isinstance(key, str) and re.fullmatch(r"[A-Za-z0-9_.:+-]{1,120}", key):
                safe = _project_report_value(item, depth=depth + 1, numeric_only=numeric_only)
                if safe is not None and safe not in ({}, []):
                    shown[key] = safe
    if len(value) > 1000:
        return {'items': shown, 'totalCount': len(value), 'shownCount': len(shown),
                'omittedCount': len(value) - len(shown)}
    return shown


def _safe_report_value(value: Any, *, depth: int = 0) -> Any:
    return _project_report_value(value, depth=depth)


def _numeric_report_value(value: Any, *, depth: int = 0) -> Any:
    return _project_report_value(value, depth=depth, numeric_only=True)


def _public_runtime_identity(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    packages = value.get("packages")
    result: dict[str, Any] = {}
    python = _safe_report_value(value.get("python"))
    if isinstance(python, str):
        result["python"] = python
    safe_packages = ({
            name: version
            for name, version in packages.items()
            if isinstance(name, str)
            and isinstance(version, str)
            and _safe_report_value(name) == name
            and _safe_report_value(version) == version
        } if isinstance(packages, dict) else {})
    if safe_packages:
        result["packages"] = safe_packages
    return result


def _occurrence_candidate_diagnostics(value: Any) -> list[dict[str, Any]]:
    """Project the exact text-free endpoint candidate shape without truncation."""

    if not isinstance(value, list):
        raise FutureSongError("endpoint occurrence candidates are malformed")

    def integer(item: Any, *, positive: bool = False) -> int:
        if type(item) is not int or item < int(positive):
            raise FutureSongError("endpoint occurrence candidate integer is invalid")
        return item

    def confidence(item: Any) -> int | float:
        if type(item) not in {int, float} or not math.isfinite(float(item)) or not 0 <= item <= 1:
            raise FutureSongError("endpoint occurrence candidate confidence is invalid")
        return item

    def ctc_log_score(item: Any) -> int | float:
        if type(item) not in {int, float} or not math.isfinite(item) or item > .000001:
            raise FutureSongError("endpoint occurrence CTC score is invalid")
        return item

    def stable_id(item: Any, kind: str) -> str:
        if not isinstance(item, str) or re.fullmatch(
            rf"{re.escape(kind)}-[0-9a-f]{{24}}", item
        ) is None:
            raise FutureSongError("endpoint occurrence candidate ID is invalid")
        return item

    def route_id(item: Any) -> str:
        if not isinstance(item, str) or re.fullmatch(
            r"[A-Za-z0-9_.:+-]{1,160}", item
        ) is None:
            raise FutureSongError("endpoint occurrence candidate route is invalid")
        return item

    family_order = (
        "mms-fa",
        "language-wav2vec2",
        "owsm-ctc",
        "nemo-nfa",
        "youtube-caption",
        "unprompted-faster-whisper",
    )
    publisher_families = family_order[:4]

    def candidate(item: Any, *, family: str) -> dict[str, Any]:
        if not isinstance(item, dict):
            raise FutureSongError("endpoint occurrence candidate is malformed")
        mms = family == "mms-fa"
        lexical_only = family in {"owsm-ctc", "nemo-nfa"}
        allowed = {"route", "startMs", "endMs", "words"}
        if mms or "confidence" in item:
            allowed.add("confidence")
        if family == "owsm-ctc":
            allowed.update({"endpointSemantics", "ctcLogScore", "acousticTokenCount"})
        if set(item) - allowed:
            raise FutureSongError(
                "endpoint occurrence candidate contains unsupported fields"
            )
        start = integer(item.get("startMs"))
        end = integer(item.get("endMs"), positive=True)
        if start >= end:
            raise FutureSongError("endpoint occurrence candidate timing is invalid")
        result: dict[str, Any] = {
            "route": route_id(item.get("route")),
            "startMs": start,
            "endMs": end,
        }
        if mms:
            result["confidence"] = confidence(item.get("confidence"))
        elif "confidence" in item:
            result["confidence"] = confidence(item["confidence"])
        if "endpointSemantics" in item:
            if item["endpointSemantics"] != "first-to-last-approved-lexical-ctc-token-span-v1":
                raise FutureSongError("endpoint occurrence semantics are invalid")
            result["endpointSemantics"] = item["endpointSemantics"]
        if "ctcLogScore" in item:
            result["ctcLogScore"] = ctc_log_score(item["ctcLogScore"])
        if "acousticTokenCount" in item:
            result["acousticTokenCount"] = integer(item["acousticTokenCount"], positive=True)
        words = item.get("words")
        if mms or "words" in item:
            if not isinstance(words, list) or not words:
                raise FutureSongError("endpoint occurrence candidate words are malformed")
            projected_words = []
            seen_word_ids: set[str] = set()
            previous_time_end = start
            previous_utf16_end = 0
            publishes_timings = family in publisher_families
            for word in words:
                if not isinstance(word, dict):
                    raise FutureSongError("endpoint occurrence candidate word is malformed")
                allowed_word = {
                    "occurrenceWordId",
                    "startMs",
                    "endMs",
                    "confidence",
                    "acousticTokenCount",
                }
                if not mms and not lexical_only:
                    allowed_word.update({"startUtf16", "endUtf16"})
                if family == "owsm-ctc":
                    allowed_word.add("ctcLogScore")
                required_word = {"occurrenceWordId", "startMs", "endMs"}
                if mms:
                    required_word.update({"confidence", "acousticTokenCount"})
                elif not lexical_only:
                    required_word.update({"startUtf16", "endUtf16"})
                if not required_word <= set(word) or set(word) - allowed_word:
                    raise FutureSongError(
                        "endpoint occurrence candidate word contains unsupported fields"
                    )
                word_start = integer(word.get("startMs"))
                word_end = integer(word.get("endMs"), positive=True)
                if (
                    word_start >= word_end
                    or (
                        publishes_timings
                        and (
                            word_start < start
                            or word_end > end
                            or word_start < previous_time_end
                        )
                    )
                ):
                    raise FutureSongError("endpoint occurrence word timing is invalid")
                occurrence_word_id = stable_id(
                    word.get("occurrenceWordId"), "occurrence-word"
                )
                if occurrence_word_id in seen_word_ids:
                    raise FutureSongError("endpoint occurrence word ID is duplicated")
                projected = {
                    "occurrenceWordId": occurrence_word_id,
                    "startMs": word_start,
                    "endMs": word_end,
                }
                if mms:
                    projected.update(
                        confidence=confidence(word.get("confidence")),
                        acousticTokenCount=integer(
                            word.get("acousticTokenCount"), positive=True
                        ),
                    )
                elif not lexical_only:
                    start_utf16 = integer(word.get("startUtf16"))
                    end_utf16 = integer(word.get("endUtf16"), positive=True)
                    if start_utf16 >= end_utf16:
                        raise FutureSongError(
                            "endpoint occurrence word display range is invalid"
                        )
                    if start_utf16 < previous_utf16_end:
                        raise FutureSongError(
                            "endpoint occurrence word display order is invalid"
                        )
                    projected.update(
                        startUtf16=start_utf16,
                        endUtf16=end_utf16,
                    )
                if not mms:
                    if "confidence" in word:
                        projected["confidence"] = confidence(word["confidence"])
                    if "acousticTokenCount" in word:
                        projected["acousticTokenCount"] = integer(word["acousticTokenCount"], positive=True)
                    if "ctcLogScore" in word:
                        projected["ctcLogScore"] = ctc_log_score(word["ctcLogScore"])
                projected_words.append(projected)
                seen_word_ids.add(occurrence_word_id)
                previous_time_end = word_end
                if not mms and not lexical_only:
                    previous_utf16_end = projected["endUtf16"]
            result["words"] = projected_words
        return result

    def word_ids(item):
        return [word['occurrenceWordId'] for word in item.get('words', ())]

    def require_bound_words(item, expected_ids):
        if not word_ids(item) or word_ids(item) != expected_ids:
            raise FutureSongError('endpoint contradiction word ownership is invalid')
        previous_end = item['startMs']
        for word in item['words']:
            if word['startMs'] < previous_end or word['endMs'] > item['endMs']:
                raise FutureSongError('qualified endpoint word timing is invalid')
            previous_end = word['endMs']

    def bound_candidate(item, family, expected_ids, *, require_words=False):
        projected = candidate(item, family=family)
        ids = word_ids(projected)
        if (require_words and not ids) or (ids and ids != expected_ids):
            raise FutureSongError('endpoint contradiction word ownership is invalid')
        if require_words:
            require_bound_words(projected, expected_ids)
        return projected

    def bound_candidates(items, expected_ids):
        if (not isinstance(items, dict) or not items
                or any(family not in family_order for family in items)):
            raise FutureSongError('endpoint contradiction families are invalid')
        return {family: bound_candidate(item, family, expected_ids, require_words=True)
            for family, item in items.items()}

    output = []
    for performed_index, item in enumerate(value):
        if not isinstance(item, dict):
            raise FutureSongError("endpoint occurrence candidate record is malformed")
        allowed_record = {
            "occurrenceId",
            "performedIndex",
            "sourcePhysicalIndex",
            "familyCandidates",
            "selectedPublisherFamily",
            "publishDecision",
            "reviewedLrcStartMs",
            "supportingEndpointFamilies",
            "endpointWitnessFamilies",
            "qualifiedEndpointContradictions",
            "qualifiedWordContradictions",
            "supportingWordEndpointFamilies",
            "ineligibleEndpointFamilies",
        }
        if set(item) - allowed_record:
            raise FutureSongError(
                "endpoint occurrence candidate record contains unsupported fields"
            )
        if integer(item.get("performedIndex")) != performed_index:
            raise FutureSongError("endpoint occurrence candidate order is invalid")
        decision = item.get("publishDecision")
        if not isinstance(decision, dict) or decision.get("result") not in {
            "words",
            "line-only",
            "unresolved",
        }:
            raise FutureSongError("endpoint occurrence publish decision is invalid")
        projected_decision = {"result": decision["result"]}
        expected_decision = {"result"}
        if decision["result"] != "words":
            expected_decision.add("code")
            codes = _diagnostic_codes([decision.get("code")])
            if len(codes) != 1:
                raise FutureSongError("endpoint occurrence publish code is invalid")
            projected_decision["code"] = codes[0]
        if set(decision) != expected_decision:
            raise FutureSongError(
                "endpoint occurrence publish decision contains unsupported fields"
            )
        raw_families = item.get("familyCandidates")
        if (
            not isinstance(raw_families, dict)
            or "mms-fa" not in raw_families
            or any(family not in family_order for family in raw_families)
        ):
            raise FutureSongError("endpoint occurrence candidate families are invalid")
        families = {
            family: candidate(raw_families[family], family=family)
            for family in family_order
            if family in raw_families
        }
        selected_family = item.get("selectedPublisherFamily")
        supporting = item.get("supportingEndpointFamilies")
        word_support = item.get('supportingWordEndpointFamilies')
        expected_ids = word_ids(families['mms-fa'])
        if decision["result"] == "unresolved":
            if "selectedPublisherFamily" in item or supporting is not None:
                raise FutureSongError(
                    "unresolved endpoint occurrence has a selected publisher"
                )
        elif (
            selected_family not in publisher_families
            or selected_family not in families
            or (decision["result"] == "words" and selected_family != "mms-fa" and word_support is None)
        ):
            raise FutureSongError("endpoint occurrence publisher is invalid")
        if supporting is not None:
            line_consensus = decision == {
                'result': 'line-only', 'code': 'independent-endpoint-consensus-line-only'}
            word_decision = (decision['result'] == 'words' and word_support is not None
                or decision == {'result': 'line-only', 'code': 'qualified-word-contradiction'}
                    and 'qualifiedWordContradictions' in item)
            if (
                not (line_consensus or word_decision)
                or not isinstance(supporting, list)
                or len(supporting) < 2
                or any(not isinstance(family, str) for family in supporting)
                or len(set(supporting)) != len(supporting)
                or any(
                    family not in (publisher_families if line_consensus else family_order) or family not in families
                    for family in supporting
                )
                or selected_family not in supporting
                or line_consensus and supporting
                != [family for family in publisher_families if family in supporting]
            ):
                raise FutureSongError("endpoint consensus support is invalid")
        elif selected_family != "mms-fa" and decision["result"] != "unresolved":
            raise FutureSongError("alternate endpoint publisher has no consensus")
        record: dict[str, Any] = {
            "occurrenceId": stable_id(item.get("occurrenceId"), "occurrence"),
            "performedIndex": performed_index,
            "sourcePhysicalIndex": integer(item.get("sourcePhysicalIndex")),
            "familyCandidates": families,
            "publishDecision": projected_decision,
        }
        if decision["result"] != "unresolved":
            record["selectedPublisherFamily"] = selected_family
        if supporting is not None:
            record["supportingEndpointFamilies"] = list(supporting)
        if 'supportingWordEndpointFamilies' in item:
            if (decision['result'] != 'words' or supporting is None
                    or not isinstance(word_support, list) or len(word_support) < 2
                    or any(not isinstance(family, str) or family not in families for family in word_support)
                    or len(set(word_support)) != len(word_support)
                    or selected_family not in word_support or not set(word_support) <= set(supporting)
                    or any(not word_ids(families[family]) or word_ids(families[family]) != expected_ids
                        for family in word_support)):
                raise FutureSongError('endpoint word consensus support is invalid')
            for family in word_support:
                require_bound_words(families[family], expected_ids)
            record['supportingWordEndpointFamilies'] = list(word_support)
        if "reviewedLrcStartMs" in item:
            record["reviewedLrcStartMs"] = integer(item["reviewedLrcStartMs"])
        if 'endpointWitnessFamilies' in item:
            witnesses = item['endpointWitnessFamilies']
            if (not isinstance(witnesses, list) or not witnesses
                    or any(not isinstance(family, str) or family not in family_order for family in witnesses)
                    or len(set(witnesses)) != len(witnesses)):
                raise FutureSongError('endpoint witness families are invalid')
            record['endpointWitnessFamilies'] = list(witnesses)
        if 'ineligibleEndpointFamilies' in item:
            ineligible = item['ineligibleEndpointFamilies']
            if (not isinstance(ineligible, dict) or not ineligible
                    or any(family not in family_order or code != 'invalid-or-inapplicable-endpoint'
                           for family, code in ineligible.items())):
                raise FutureSongError('ineligible endpoint families are invalid')
            record['ineligibleEndpointFamilies'] = dict(ineligible)
        if 'qualifiedEndpointContradictions' in item:
            conflict = item['qualifiedEndpointContradictions']
            if (decision != {'result': 'unresolved', 'code': 'qualified-endpoint-contradiction'}
                    or not isinstance(conflict, dict) or set(conflict) != {
                        'previousPublisherFamily', 'previousPublisher', 'incomingCandidates'}
                    or conflict['previousPublisherFamily'] not in publisher_families
                    or not isinstance(conflict['incomingCandidates'], dict)
                    or not conflict['incomingCandidates']
                    or any(family not in family_order for family in conflict['incomingCandidates'])):
                raise FutureSongError('qualified endpoint contradiction is invalid')
            record['qualifiedEndpointContradictions'] = {
                'previousPublisherFamily': conflict['previousPublisherFamily'],
                'previousPublisher': bound_candidate(conflict['previousPublisher'], conflict['previousPublisherFamily'], expected_ids),
                'incomingCandidates': {family: bound_candidate(value, family, expected_ids)
                    for family, value in conflict['incomingCandidates'].items()}}
        if 'qualifiedWordContradictions' in item:
            conflict = item['qualifiedWordContradictions']
            if (decision not in ({'result': 'line-only', 'code': 'qualified-word-contradiction'},
                                {'result': 'unresolved', 'code': 'qualified-word-contradiction'})
                    and not (decision == {'result': 'unresolved', 'code': 'qualified-endpoint-contradiction'}
                        and 'qualifiedEndpointContradictions' in record)
                    or not isinstance(conflict, dict) or set(conflict) not in (
                        {'previousPublisher', 'incomingCandidates'}, {'previousCandidates', 'incomingCandidates'})):
                raise FutureSongError('qualified word contradiction is invalid')
            projected = {'incomingCandidates': bound_candidates(conflict['incomingCandidates'], expected_ids)}
            if 'previousCandidates' in conflict:
                projected['previousCandidates'] = bound_candidates(conflict['previousCandidates'], expected_ids)
            else:
                prior_family = selected_family or record.get('qualifiedEndpointContradictions', {}).get('previousPublisherFamily')
                if prior_family not in publisher_families:
                    raise FutureSongError('qualified word contradiction publisher is invalid')
                projected['previousPublisher'] = bound_candidate(conflict['previousPublisher'], prior_family,
                    expected_ids, require_words=True)
            record['qualifiedWordContradictions'] = projected
        output.append(record)
    return output


def _structure_sequence_candidate(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise FutureSongError("structure sequence candidate is malformed")
    result: dict[str, Any] = {}
    if "performedOccurrences" in value:
        occurrences = value["performedOccurrences"]
        if not isinstance(occurrences, list):
            raise FutureSongError("structure candidate occurrences are malformed")
        projected_occurrences = []
        for performed_index, occurrence in enumerate(occurrences):
            if (
                not isinstance(occurrence, dict)
                or occurrence.get("performedIndex") != performed_index
                or type(occurrence.get("sourcePhysicalIndex")) is not int
                or occurrence["sourcePhysicalIndex"] < 0
                or not isinstance(occurrence.get("occurrenceId"), str)
                or re.fullmatch(
                    r"occurrence-[0-9a-f]{24}", occurrence["occurrenceId"]
                )
                is None
            ):
                raise FutureSongError("structure candidate occurrence is invalid")
            projected_occurrence = {
                "occurrenceId": occurrence["occurrenceId"],
                "performedIndex": performed_index,
                "sourcePhysicalIndex": occurrence["sourcePhysicalIndex"],
            }
            if "startMs" in occurrence:
                if type(occurrence["startMs"]) is not int or occurrence["startMs"] < 0:
                    raise FutureSongError("structure candidate start is invalid")
                projected_occurrence["startMs"] = occurrence["startMs"]
            projected_occurrences.append(projected_occurrence)
        result["performedOccurrences"] = projected_occurrences
    return result


def _endpoint_evidence_diagnostics(
    value: Any, *, include_candidates: bool = True
) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise FutureSongError("endpoint evidence is malformed")
    result = {
        key: safe
        for key in (
            "resultClass",
            "lineCount",
            "lineReadyCount",
            "wordLineCount",
            "lineOnlyCount",
            "lineOnlyReasons",
            "unresolvedLineReasons",
            "reviewedLrcStartAgreementMs",
            "reviewedLrcStartEvidenceCount",
            "familyCandidateCounts",
            "selectedPublisherFamilyCounts",
            "baselinePublishedLineCount",
            "anchoredFilledLineCount",
            "bootstrapAttempted",
            "wordStartAgreementMs",
            "wordEndAgreementMs",
            "minimumMmsLineConfidence",
            "minimumMmsWordConfidence",
            "independentConsensusAttempted",
            "independentConsensusFilledLineCount",
            "independentConsensusAmbiguousLineCount",
        )
        if (safe := _safe_report_value(value.get(key))) is not None
    }
    if "occurrenceCandidates" in value:
        candidates = _occurrence_candidate_diagnostics(
            value["occurrenceCandidates"]
        )
        if type(value.get("lineCount")) is not int or len(candidates) != value["lineCount"]:
            raise FutureSongError("endpoint occurrence candidate count is invalid")
        if include_candidates:
            result["occurrenceCandidates"] = candidates
    return result


def _structure_witness_diagnostics(value: Any) -> dict[str, Any]:
    from stemwise_engine.workflow_records import structure_witness_record
    value = structure_witness_record(value)
    if (
        value.get("family") != "qwen3-asr"
        or value.get("route") not in {
            "qwen3-asr-unprompted-mix",
            "qwen3-asr-unprompted-htdemucs-vocals",
            "qwen3-asr-unprompted-kimberley-jensen-roformer-vocals",
            "qwen3-asr-unprompted-sucial-v2-roformer-dry-vocals",
        }
        or value.get("status") not in {"started", "success", "failure"}
        or type(value.get("candidateCount")) is not int
        or value["candidateCount"] <= 0
    ):
        raise FutureSongError("structure witness identity is invalid")
    result: dict[str, Any] = {
        "family": value["family"],
        "route": value["route"],
        "status": value["status"],
        "candidateCount": value["candidateCount"],
    }
    if "contentAdmission" in value:
        result["contentAdmission"] = value["contentAdmission"]
    if "languageHint" in value:
        hint = value["languageHint"]
        if hint is not None and (not isinstance(hint, str) or re.fullmatch(r"[a-z]{2,3}", hint) is None):
            raise FutureSongError("structure witness language hint is invalid")
        result["languageHint"] = hint
    for key in (
        "cacheReused",
        "selectedSequenceSupported",
        "selectedSequenceEveryLineDirect",
    ):
        if key in value:
            if type(value[key]) is not bool:
                raise FutureSongError("structure witness boolean is invalid")
            result[key] = value[key]
    if "passingCandidateCount" in value:
        passing = value["passingCandidateCount"]
        if type(passing) is not int or not 0 <= passing <= value["candidateCount"]:
            raise FutureSongError("structure witness passing count is invalid")
        result["passingCandidateCount"] = passing
    metrics = _numeric_report_value(value.get("metrics"))
    if isinstance(metrics, dict) and metrics:
        result["metrics"] = metrics
    for key in (
        "lexicalAuthoritySha256",
        "modelSnapshotSha256",
        "helperSha256",
    ):
        if key in value:
            if not isinstance(value[key], str) or re.fullmatch(
                r"[0-9a-f]{64}", value[key]
            ) is None:
                raise FutureSongError("structure witness hash is invalid")
            result[key] = value[key]
    if "modelRevision" in value:
        if not isinstance(value["modelRevision"], str) or re.fullmatch(
            r"[0-9a-f]{40}", value["modelRevision"]
        ) is None:
            raise FutureSongError("structure witness revision is invalid")
        result["modelRevision"] = value["modelRevision"]
    if "failureCode" in value:
        codes = _diagnostic_codes([value["failureCode"]])
        if len(codes) != 1:
            raise FutureSongError("structure witness failure code is invalid")
        result["failureCode"] = codes[0]
    return result


def _alignment_diagnostics(state: dict[str, Any]) -> dict[str, Any]:
    attempts: list[dict[str, Any]] = []
    for item in state.get("evidenceAttempts", []):
        if not isinstance(item, dict):
            continue
        record: dict[str, Any] = {}
        route = _stable_private_id(item.get("type"))
        if isinstance(route, str):
            record["type"] = route
        for key in ("accepted", "reused"):
            if type(item.get(key)) is bool:
                record[key] = item[key]
        reasons = _diagnostic_codes(item.get("reasons"))
        if reasons:
            record["reasons"] = reasons
        metrics = _numeric_report_value(item.get("metrics"))
        if isinstance(metrics, dict) and metrics:
            record["metrics"] = metrics
        if "sequenceCandidate" in item:
            record["sequenceCandidate"] = _structure_sequence_candidate(
                item["sequenceCandidate"]
            )
        attempts.append(record)
    endpoint = state.get("endpointEvidence")
    endpoint_review = state.get("endpointReview")
    performed_structure_review = state.get("performedStructureReview")
    if performed_structure_review is not None and (
        not isinstance(performed_structure_review, dict)
        or set(performed_structure_review) != {"decision", "sha256"}
        or performed_structure_review.get("decision") != "baseline"
        or re.fullmatch(
            r"[0-9a-f]{64}",
            str(performed_structure_review.get("sha256") or ""),
        )
        is None
    ):
        raise FutureSongError("performed structure review evidence is malformed")
    if endpoint_review is not None:
        endpoint_review = EndpointReviewResult.from_record(endpoint_review).to_record()
    pinned_models = state.get("pinnedModels")
    result: dict[str, Any] = {}
    if 'privateDiagnostics' in state:
        from stemwise_engine.private_diagnostics import validate_references
        try:
            result['privateDiagnostics'] = validate_references(state['privateDiagnostics'])
        except ValueError as error:
            raise FutureSongError('alignment diagnostic identity is invalid') from error
    if "contentAdmission" in state:
        from stemwise_engine.lyric_content import ContentAdmission
        result["contentAdmission"] = ContentAdmission.from_record(state["contentAdmission"]).to_record()
    if state.get("status") == "lyrics-content-unresolved":
        blocker = LyricsContentBlocker(
            *(_diagnostic_codes(state.get("reasons")) or ["content-evidence-unavailable"]),
            binding=state.get("contentBinding"))
        if blocker.binding is not None:
            result["contentBinding"] = blocker.binding
    for key, value in (
        ("alignmentVersion", state.get("alignmentVersion")),
        ("resultClass", state.get("status")),
        ("durationMs", state.get("durationMs")),
        ("evidenceType", _stable_private_id(state.get("evidenceType"))),
        ("evidenceReused", state.get("evidenceReused")),
        ("endpointEvidenceReused", state.get("endpointEvidenceReused")),
        ("timedLineCount", state.get("timedLineCount")),
        ("internalRepeatStructureKind", state.get("internalRepeatStructureKind")),
        ("internalRepeatAuditComplete", state.get("internalRepeatAuditComplete")),
        ("reviewedLrcStartCount", state.get("reviewedLrcStartCount")),
        ("realignedFromCanonicalPlain", state.get("realignedFromCanonicalPlain")),
    ):
        safe = _safe_report_value(value)
        if safe is not None:
            result[key] = safe
    model_hashes = ({
            key: value
            for key, value in pinned_models.items()
            if isinstance(key, str)
            and key.endswith("Sha256")
            and isinstance(value, str)
            and re.fullmatch(r"[0-9a-f]{64}", value)
        } if isinstance(pinned_models, dict) else {})
    if model_hashes:
        result["pinnedModels"] = model_hashes
    runtime = _public_runtime_identity(state.get("pinnedRuntime"))
    if runtime:
        result["pinnedRuntime"] = runtime
    attempted = [
        _stable_private_id(value)
        for value in state.get("attemptedEvidence", [])
        if isinstance(value, str)
    ] if isinstance(state.get("attemptedEvidence"), list) else []
    if attempted:
        result["attemptedEvidence"] = attempted
    if endpoint_review is not None:
        result["endpointReview"] = endpoint_review
    if performed_structure_review is not None:
        result["performedStructureReview"] = performed_structure_review
    if attempts:
        result["attempts"] = attempts
    if "structureWitness" in state:
        result["structureWitness"] = _structure_witness_diagnostics(
            state["structureWitness"]
        )
    if "routeAttempts" in state:
        raw_route_attempts = state["routeAttempts"]
        if not isinstance(raw_route_attempts, list):
            raise FutureSongError("route attempts are malformed")
        route_attempts = []
        for sequence, item in enumerate(raw_route_attempts):
            if (
                not isinstance(item, dict)
                or item.get("sequence") != sequence
                or item.get("stage")
                not in ROUTE_STAGES
                or not isinstance(item.get("routeId"), str)
                or re.fullmatch(r"[A-Za-z0-9_.:+-]{1,160}", item["routeId"])
                is None
                or (
                    item.get("status")
                    not in ROUTE_STATUSES
                    and not (
                        item.get("stage") == "endpoint-seed"
                        and item.get("status") == "failed"
                    )
                )
            ):
                raise FutureSongError("route attempt is invalid")
            status = item["status"]
            expected = {"sequence", "stage", "routeId", "status"}
            projected = {
                "sequence": sequence,
                "stage": item["stage"],
                "routeId": item["routeId"],
                "status": status,
            }
            if status != "started":
                if type(item.get("cacheReused")) is not bool:
                    raise FutureSongError("route attempt cache identity is invalid")
                expected.add("cacheReused")
                projected["cacheReused"] = item["cacheReused"]
            if status in {"transient-failure", "failure", "failed"} or status == "unavailable" and "code" in item:
                code = _diagnostic_codes([item.get("code")])
                if len(code) != 1:
                    raise FutureSongError("route attempt failure code is invalid")
                expected.add("code")
                projected["code"] = code[0]
                if 'privateDiagnostics' in item:
                    from stemwise_engine.private_diagnostics import validate_references
                    try:
                        references = validate_references(item['privateDiagnostics'])
                    except ValueError as error:
                        raise FutureSongError('route diagnostic identity is invalid') from error
                    expected.add('privateDiagnostics')
                    projected['privateDiagnostics'] = copy.deepcopy(references)
            if set(item) != expected:
                raise FutureSongError("route attempt contains unsupported fields")
            route_attempts.append(projected)
        result["routeAttempts"] = route_attempts
    if "endpointRouteAttempts" in state:
        raw_endpoint_attempts = state["endpointRouteAttempts"]
        if not isinstance(raw_endpoint_attempts, list):
            raise FutureSongError("endpoint route attempts are malformed")
        endpoint_attempts = []
        for item in raw_endpoint_attempts:
            if (
                not isinstance(item, dict)
                or not isinstance(item.get("routeId"), str)
                or re.fullmatch(r"[A-Za-z0-9_.:+-]{1,160}", item["routeId"])
                is None
                or type(item.get("reused")) is not bool
            ):
                raise FutureSongError("endpoint route attempt is invalid")
            endpoint_attempts.append(
                {
                    "routeId": item["routeId"],
                    "reused": item["reused"],
                    "endpointEvidence": _endpoint_evidence_diagnostics(
                        item.get("endpointEvidence")
                    ),
                }
            )
        result["endpointRouteAttempts"] = endpoint_attempts
        selected_index = state.get("selectedEndpointAttemptIndex")
        if selected_index is not None or isinstance(endpoint, dict):
            if (
                type(selected_index) is not int
                or not 0 <= selected_index < len(endpoint_attempts)
            ):
                raise FutureSongError("selected endpoint attempt index is invalid")
            selected_evidence = endpoint_attempts[selected_index]["endpointEvidence"]
            aggregate = _endpoint_evidence_diagnostics(endpoint)
            if endpoint_review is None:
                if aggregate != selected_evidence:
                    raise FutureSongError(
                        "selected endpoint attempt does not match aggregate"
                    )
                result["selectedEndpointAttemptIndex"] = selected_index
            result["endpointEvidence"] = _endpoint_evidence_diagnostics(
                endpoint, include_candidates=False
            )
    elif "selectedEndpointAttemptIndex" in state:
        raise FutureSongError("selected endpoint attempt has no route ledger")
    elif isinstance(endpoint, dict):
        result["endpointEvidence"] = _endpoint_evidence_diagnostics(endpoint)
    if "routeFailures" in state:
        raw_failures = state["routeFailures"]
        if not isinstance(raw_failures, list):
            raise FutureSongError("route failures are malformed")
        route_failures = []
        for item in raw_failures:
            if not isinstance(item, dict):
                raise FutureSongError("route failure is malformed")
            stage = _diagnostic_codes([item.get("stage")])
            route = _diagnostic_codes([item.get("routeId")])
            code = _diagnostic_codes([item.get("code")])
            if len(stage) != 1 or len(route) != 1 or len(code) != 1:
                raise FutureSongError("route failure is invalid")
            route_failures.append(
                {"stage": stage[0], "routeId": route[0], "code": code[0]}
            )
        result["routeFailures"] = route_failures
    selected_evidence = state.get("evidence")
    if isinstance(selected_evidence, dict):
        selected = _safe_report_value(
            {
                key: selected_evidence[key]
                for key in (
                    "type",
                    "engine",
                    "detectedLanguage",
                    "detectedLanguageProbability",
                    "segmentCount",
                    "wordCount",
                    "decodingProfile",
                    "captionKind",
                    "captionLanguage",
                    "passes",
                    "passCount",
                    "acousticTrackKind",
                    "baselineConfidence",
                    "candidateConfidence",
                    "selectedLineCount",
                    "selectedInsertedBlockCount",
                    "selectedOmittedBlockCount",
                    "selectedCollapsedRepeatBlockCount",
                    "structureConfirmation",
                    "timingSource",
                    "timingEvidenceRoutes",
                    "structuralSupportFamilies",
                    "structuralSupportCount",
                    "thresholdsUnchanged",
                )
                if key in selected_evidence
            }
        )
        if isinstance(selected, dict) and selected:
            result["selectedEvidence"] = selected
    metrics = _numeric_report_value(state.get("metrics"))
    if isinstance(metrics, dict) and metrics:
        result["metrics"] = metrics
    reasons = _diagnostic_codes(state.get("reasons"))
    if reasons:
        result["reasons"] = reasons
    fallback_fields = {
        "vocalFallbackError": "faster-whisper-vocals",
        "precisionFallbackError": "faster-whisper-precision",
        "unpromptedFallbackError": "faster-whisper-precision-unprompted",
        "precisionVocalFallbackError": "faster-whisper-precision-vocals",
        "forcedAlignmentFallbackError": "mms-fa-repeat-structure",
    }
    fallbacks = []
    for field, route in fallback_fields.items():
        codes = _diagnostic_codes([state.get(field)])
        if codes:
            fallbacks.append({"route": route, "status": "failed", "code": codes[0]})
    if fallbacks:
        result["fallbacks"] = fallbacks
    cascade = state.get("roformerCascade")
    if isinstance(cascade, dict) and cascade.get("cascadeVersion") == 1:
        routes = []
        for item in cascade.get("routes", []):
            if not isinstance(item, dict):
                continue
            route = _safe_report_value(
                {
                    key: item[key]
                    for key in (
                        "routeId",
                        "familyId",
                        "sourceViewId",
                        "outputViewId",
                        "status",
                        "cacheReused",
                        "runtimeMs",
                        "peakCudaMemoryBytes",
                        "failureStage",
                        "failureCode",
                    )
                    if key in item
                }
            )
            if isinstance(route, dict) and route:
                routes.append(route)
        roformer_cascade: dict[str, Any] = {
            "cascadeVersion": 1,
            "routes": routes,
        }
        selected = _safe_report_value(cascade.get("selectedEndpointRoute"))
        if isinstance(selected, str):
            roformer_cascade["selectedEndpointRoute"] = selected
        result["roformerCascade"] = roformer_cascade
    return result

"""Qualified context fences shared by current OWSM recovery."""


def qualified_context_bounds(plan, mapping, evidence):
    """Known context tokens cannot consume unrelated unresolved singing."""
    bounds = {}
    context = {line["lineId"] for line in plan["request"]["lines"]}
    if len(mapping["performedOccurrences"]) != len(evidence["occurrenceCandidates"]):
        raise ValueError("native prediction qualified context mapping is incomplete")
    for mapped, row in zip(mapping["performedOccurrences"], evidence["occurrenceCandidates"]):
        if mapped["occurrenceId"] not in context or row["publishDecision"]["result"] == "unresolved":
            continue
        candidate = row["familyCandidates"][row["selectedPublisherFamily"]]
        for word in mapped["words"]:
            bounds[word["occurrenceWordId"]] = [max(0, candidate["startMs"]-300), candidate["endMs"]+400]
    return bounds

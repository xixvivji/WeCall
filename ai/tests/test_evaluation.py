from evaluate_local import equivalent, classify, summarize


def lot(value): return {"op": "EQ", "field": "LOT_NUMBER", "values": [value]}
def expiry(value): return {"op": "EQ", "field": "EXPIRY_DATE", "values": [value]}


def test_equivalence_accepts_order_and_in_list_but_not_and_or_changes():
    a, b = lot("A"), expiry("2028-01-01")
    assert equivalent({"op": "OR", "children": [a, lot("B")]}, {"op": "IN", "field": "LOT_NUMBER", "values": ["B", "A"]})
    assert equivalent({"op": "AND", "children": [a, b]}, {"op": "AND", "children": [b, a]})
    assert not equivalent({"op": "OR", "children": [a, b]}, {"op": "AND", "children": [a, b]})


def test_range_boundaries_and_omitted_conditions_are_detected():
    bounds = {"op": "BETWEEN", "field": "EXPIRY_DATE", "values": ["2028-01-01", "2028-01-03"]}
    assert not equivalent(bounds, {"op": "IN", "field": "EXPIRY_DATE", "values": ["2028-01-01", "2028-01-03"]})
    assert not equivalent(lot("A"), {"op": "AND", "children": [lot("A"), bounds]})
    assert equivalent(expiry("9999-12-31"), expiry("9999-12-31"))
    assert equivalent(expiry("0001-01-01"), expiry("0001-01-01"))


def test_operational_and_validation_failures_do_not_count_as_correct_refusal():
    assert classify(None, lot("A"), "EXTRACTED") == "UNSAFE_EXTRACTION"
    assert classify(lot("B"), lot("A"), "EXTRACTED") == "WRONG_RULE"
    assert classify(None, None, "MANUAL_REVIEW_REQUIRED") == "CORRECT_REFUSAL"
    assert classify(lot("B"), None, "MANUAL_REVIEW_REQUIRED") == "OVER_REFUSAL"
    assert classify(None, None, "INVALID_MODEL_OUTPUT") == "VALIDATION_REJECTED"
    assert classify(None, None, "LOCAL_MODEL_UNAVAILABLE") == "OPERATIONAL_ERROR"
    assert summarize([{"passed": False, "verdict": "OPERATIONAL_ERROR"}])["passed"] == 0

"""Run real local inference against synthetic, independently authored expectations."""
import asyncio
import itertools
import json
import time
from datetime import date, timedelta
from pathlib import Path
from uuid import uuid4

from fastapi import HTTPException
from app.extraction import ExtractionRequest
from app.local_provider import LocalProvider, MODEL, PROMPT_VERSION


def evaluate(rule, lot, expiry):
    op = rule["op"]
    if op in ("AND", "OR"):
        states = [evaluate(r, lot, expiry) for r in rule["children"]]
        if op == "AND" and False in states: return False
        if op == "OR" and True in states: return True
        if None in states: return None
        return op == "AND"
    value = lot if rule["field"] == "LOT_NUMBER" else expiry
    if value is None: return None
    return rule["values"][0] <= value <= rule["values"][1] if op == "BETWEEN" else value in rule["values"]


def equivalent(actual, expected):
    lots, dates = {None, "UNLISTED"}, {None, "2000-01-01"}
    def collect(rule):
        if rule["op"] in ("AND", "OR"):
            for child in rule["children"]: collect(child)
        elif rule["field"] == "LOT_NUMBER": lots.update(rule["values"])
        else:
            for value in rule["values"]:
                for offset in (-1, 0, 1): dates.add((date.fromisoformat(value) + timedelta(days=offset)).isoformat())
    collect(actual); collect(expected)
    return all(evaluate(actual, lot, expiry) == evaluate(expected, lot, expiry) for lot, expiry in itertools.product(lots, dates))


async def main():
    root = Path(__file__).resolve().parent
    cases = json.loads((root / "evaluation/cases.json").read_text())
    fixture = json.loads((root / "app/fixtures/recall-001.json").read_text())
    cases.insert(0, {"id": "golden-recall", "source": fixture["sourceText"], "rule": fixture["rule"]})
    results = []
    for case in cases:
        start = time.monotonic()
        try:
            response = await LocalProvider().extract(ExtractionRequest(requestId=uuid4(), sourceText=case["source"]))
            passed = case["rule"] is not None and equivalent(response.rule.model_dump(), case["rule"])
            outcome = "EXTRACTED"
        except HTTPException as error:
            outcome = error.detail["code"]
            passed = case["rule"] is None and outcome == "MANUAL_REVIEW_REQUIRED"
        results.append({"id": case["id"], "passed": passed, "outcome": outcome, "seconds": round(time.monotonic()-start, 2)})
        print(json.dumps(results[-1], ensure_ascii=False), flush=True)
    output = root.parent / "validation-results/local-ai-evaluation.json"
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps({"model": MODEL, "promptVersion": PROMPT_VERSION, "cases": results}, ensure_ascii=False, indent=2))
    if not all(r["passed"] for r in results): raise SystemExit(1)


if __name__ == "__main__": asyncio.run(main())

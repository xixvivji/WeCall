"""Run real local inference against synthetic, independently authored expectations."""
import argparse
import asyncio
import hashlib
import subprocess
from collections import Counter
import itertools
import json
import time
from datetime import date, timedelta, datetime, timezone
from pathlib import Path
from uuid import uuid4

from fastapi import HTTPException
from app.extraction import ExtractionRequest
from app.local_provider import LocalProvider, MODEL, PROMPT_VERSION, requires_manual_review


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
                for offset in (-1, 0, 1):
                    try: dates.add((date.fromisoformat(value) + timedelta(days=offset)).isoformat())
                    except OverflowError: pass
    collect(actual); collect(expected)
    return all(evaluate(actual, lot, expiry) == evaluate(expected, lot, expiry) for lot, expiry in itertools.product(lots, dates))


OPERATIONAL_ERRORS = {"LOCAL_MODEL_UNAVAILABLE", "LOCAL_MODEL_TIMEOUT", "LOCAL_MODEL_BUSY", "NON_LOCAL_MODEL_BLOCKED"}


def classify(expected, actual, outcome):
    if outcome == "EXTRACTED":
        if expected is None: return "UNSAFE_EXTRACTION"
        return "CORRECT_EXTRACTION" if equivalent(actual, expected) else "WRONG_RULE"
    if outcome in OPERATIONAL_ERRORS: return "OPERATIONAL_ERROR"
    if outcome == "MANUAL_REVIEW_REQUIRED":
        return "CORRECT_REFUSAL" if expected is None else "OVER_REFUSAL"
    return "VALIDATION_REJECTED"


def summarize(results):
    counts = Counter(row["verdict"] for row in results)
    return {"total": len(results), "passed": sum(r["passed"] for r in results),
            "counts": dict(sorted(counts.items()))}


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", choices=("development", "expanded", "holdout"), default="development")
    parser.add_argument("--label", default=datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    args = parser.parse_args()
    if not args.label.replace("-", "").replace("_", "").isalnum(): parser.error("label must be alphanumeric")
    root = Path(__file__).resolve().parent
    path = root / "evaluation" / ({"development": "cases.json"}.get(args.suite, args.suite + ".json"))
    cases = json.loads(path.read_text())
    if args.suite == "development":
        fixture = json.loads((root / "app/fixtures/recall-001.json").read_text())
        cases.insert(0, {"id": "golden-recall", "source": fixture["sourceText"], "rule": fixture["rule"]})
    if len({c["id"] for c in cases}) != len(cases): raise SystemExit("Duplicate case ID")
    output = root.parent / "validation-results" / f"local-ai-{args.suite}-{args.label}.json"
    output.parent.mkdir(exist_ok=True)
    if output.exists(): raise SystemExit("Result already exists; use a new label")
    results = []
    record = {
        "model": MODEL, "promptVersion": PROMPT_VERSION, "suite": args.suite,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "gitCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "providerSha256": hashlib.sha256((root / "app/local_provider.py").read_bytes()).hexdigest(),
        "suiteSha256": hashlib.sha256(json.dumps(cases, ensure_ascii=False, sort_keys=True).encode()).hexdigest(),
        "complete": False, "cases": results,
    }
    for case in cases:
        start = time.monotonic()
        actual, quote = None, None
        try:
            response = await LocalProvider().extract(ExtractionRequest(requestId=uuid4(), sourceText=case["source"]))
            actual, quote = response.rule.model_dump(exclude_none=True), response.sourceQuote
            outcome = "EXTRACTED"
        except HTTPException as error:
            outcome = error.detail["code"]
        verdict = classify(case["rule"], actual, outcome)
        row = {"id": case["id"], "category": case.get("category", "development"),
               "passed": verdict in ("CORRECT_EXTRACTION", "CORRECT_REFUSAL"),
               "verdict": verdict, "outcome": outcome,
               "preflightRefusal": outcome == "MANUAL_REVIEW_REQUIRED" and requires_manual_review(case["source"]),
               "expectedRule": case["rule"], "actualRule": actual, "sourceQuote": quote,
               "seconds": round(time.monotonic()-start, 2)}
        results.append(row)
        record["summary"] = summarize(results)
        output.write_text(json.dumps(record, ensure_ascii=False, indent=2))
        print(json.dumps({k: row[k] for k in ("id", "verdict", "outcome", "seconds")}, ensure_ascii=False), flush=True)
    record["complete"] = True
    record["finishedAt"] = datetime.now(timezone.utc).isoformat()
    output.write_text(json.dumps(record, ensure_ascii=False, indent=2))
    print(json.dumps(record["summary"], ensure_ascii=False), flush=True)
    if not all(r["passed"] for r in results): raise SystemExit(1)


if __name__ == "__main__": asyncio.run(main())

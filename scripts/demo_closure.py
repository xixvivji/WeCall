"""Demonstrate blocked closure and successful closure using separate synthetic cases."""
import argparse
from api_client import ApiClient
import json
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    base = parser.parse_args().base_url.rstrip("/")

    client = ApiClient(base)
    request = client.request

    demo = Path(__file__).with_name("demo_recall.py")
    unresolved = json.loads(subprocess.check_output([sys.executable, str(demo), "--base-url", base, "--with-evidence", "--with-tasks"], text=True))
    unresolved_prefix = f'/api/v1/recalls/{unresolved["caseId"]}'
    unresolved_run = unresolved["evidence"]["resultAssessmentId"]
    check = request(unresolved_prefix + f"/closure-check?assessmentId={unresolved_run}")
    if check["ready"] or "UNRESOLVED_SHIPMENTS" not in {b["code"] for b in check["blockers"]}:
        raise SystemExit("Unlinked shipment did not block closure")
    request(unresolved_prefix + "/closure", {"assessmentId": unresolved_run, "expectedVersion": check["version"], "reviewer": "demo-reviewer", "note": "가상 종료 차단 검증", "responseCoverageConfirmed": True}, expected=409)

    # A separate complete dataset: 5 known units in stock, no shipments.
    # Do not infer or fill the missing shipment links in the original sample.
    sample = Path(__file__).resolve().parents[1] / "samples" / "closure-001"
    golden = json.loads((sample / "expected.json").read_text())
    csvs = {("shipmentAllocations" if name == "shipment_allocations" else name):
            (sample / f"{name}.csv").read_text()
            for name in ("products", "receipts", "inventory", "shipments", "shipment_allocations")}
    boundary = "wecall-" + uuid.uuid4().hex
    parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="asOf"\r\n\r\n2026-09-09T18:00:00+09:00\r\n'.encode()]
    for field, text in csvs.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{field}"; filename="{field}.csv"\r\nContent-Type: text/csv\r\n\r\n{text}\r\n'.encode())
    parts.append(f"--{boundary}--\r\n".encode())
    dataset = request("/api/v1/datasets", b"".join(parts), expected=201, content_type=f"multipart/form-data; boundary={boundary}")
    case = request("/api/v1/recalls", {"title": "종료 검증 전용 가상 사건", "sourceType": "INTERNAL", "sourceText": (sample / "notice.md").read_text()}, expected=201)
    prefix = f'/api/v1/recalls/{case["id"]}'
    definition = json.loads((sample / "condition-request.json").read_text())
    definition["datasetId"] = dataset["datasetId"]
    condition = request(prefix + "/conditions", definition, expected=201)
    request(prefix + f'/conditions/{condition["id"]}/approval', {"reviewer": "demo-reviewer"})
    run = request(prefix + "/assessments", {"conditionId": condition["id"]}, expected=201)
    for key in ("inventoryTotals", "shipmentTotals"):
        if run[key] != golden[key]:
            raise SystemExit(f"Closure fixture golden mismatch: {key}")
    task = request(prefix + "/tasks", {"taskType": "QUARANTINE", "targetType": "INVENTORY", "assessmentId": run["id"], "targetId": "I1", "title": "가상 재고 5개 격리", "instructions": "합성 자료의 전체 대상 5개 격리 확인", "assignee": "demo-operator", "actor": "demo-reviewer"}, expected=201)
    task_url = prefix + f'/tasks/{task["id"]}'
    task = request(task_url + "/transitions", {"expectedVersion": task["version"], "action": "START", "actor": "demo-operator", "note": "가상 작업"})
    task = request(task_url + "/proofs", {"expectedVersion": task["version"], "evidenceText": "합성 증빙: I1 5개 격리 확인. 실제 실적 아님.", "actor": "demo-operator"}, expected=201)
    task = request(task_url + f'/proofs/{task["proofs"][0]["id"]}/review', {"expectedVersion": task["version"], "decision": "ACCEPTED", "actor": "demo-reviewer", "note": "가상 대상 전체 확인"})
    request(task_url + "/transitions", {"expectedVersion": task["version"], "action": "COMPLETE", "actor": "demo-reviewer", "note": "가상 작업 완료"})
    check = request(prefix + f'/closure-check?assessmentId={run["id"]}')
    if not check["ready"]:
        raise SystemExit(f"Complete fixture blocked: {check['blockers']}")
    close_body = {"assessmentId": run["id"], "expectedVersion": check["version"], "reviewer": "demo-reviewer", "note": "가상 대상 5개와 작업·증빙 전체 검토", "responseCoverageConfirmed": True}
    closed = request(prefix + "/closure", close_body)
    snapshot = closed["history"][0]
    request(prefix + "/assessments", {"conditionId": condition["id"]}, expected=409)
    reopened = request(prefix + "/reopen", {"expectedVersion": closed["lifecycleVersion"], "reviewer": "demo-reviewer", "note": "가상 추가 통보 검토"})
    if reopened["history"][0] != snapshot:
        raise SystemExit("Closure snapshot changed after reopening")
    close_body["expectedVersion"] = reopened["lifecycleVersion"]
    final = request(prefix + "/closure", close_body)
    if final["status"] != "CLOSED" or len(final["history"]) != 3:
        raise SystemExit("Unexpected final lifecycle")
    report = request(prefix + f"/report?assessmentId={run['id']}")
    if report["assessment"] != run or report["case"]["status"] != golden["finalStatus"]:
        raise SystemExit("Closed report changed the original assessment or case status")
    if [event["type"] for event in report["lifecycle"]] != golden["history"]:
        raise SystemExit("Report lifecycle differs from authored expectations")
    if not any(t["id"] == task["id"] and t["status"] == "COMPLETED" for t in report["tasks"]):
        raise SystemExit("Completed response missing from report")
    client.logout()
    print(json.dumps({"blockedCaseId": unresolved["caseId"], "blockedReason": "UNRESOLVED_SHIPMENTS", "closedCaseId": case["id"], "datasetId": dataset["datasetId"], "assessmentId": run["id"], "reportVerified": True, "finalStatus": final["status"], "history": [event["type"] for event in final["history"]]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

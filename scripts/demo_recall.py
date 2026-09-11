"""Create a fresh synthetic dataset/case and run the approved demo workflow.

Requires an already-running local backend. Requires WECALL_USERNAME and WECALL_PASSWORD for an actual local account.
"""
import argparse
from api_client import ApiClient
import json
from pathlib import Path
import urllib.error
import urllib.request
import uuid
import time

SAMPLE = Path(__file__).resolve().parents[1] / "samples" / "recall-001"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--with-evidence", action="store_true", help="Approve synthetic receipt evidence and verify after results")
    parser.add_argument("--with-tasks", action="store_true", help="Assign a response task, review proof and complete it")
    parser.add_argument("--with-extraction", action="store_true", help="Use explicitly enabled FastAPI fixture extraction")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")

    client = ApiClient(base)
    request = client.request

    boundary = "wecall-" + uuid.uuid4().hex
    parts = []
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="asOf"\r\n\r\n2026-09-09T18:00:00+09:00\r\n'.encode())
    for name in ("products", "receipts", "inventory", "shipments", "shipment_allocations"):
        field = "shipmentAllocations" if name == "shipment_allocations" else name
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{field}"; filename="{name}.csv"\r\nContent-Type: text/csv\r\n\r\n'.encode())
        parts.extend([(SAMPLE / f"{name}.csv").read_bytes(), b"\r\n"])
    parts.append(f"--{boundary}--\r\n".encode())
    dataset = request("/api/v1/datasets", b"".join(parts), f"multipart/form-data; boundary={boundary}")
    case = request("/api/v1/recalls", {"title": "가상 별빛 크래커 회수", "sourceType": "SUPPLIER", "sourceText": (SAMPLE / "notice.md").read_text()})
    prefix = f'/api/v1/recalls/{case["id"]}'
    definition = json.loads((SAMPLE / "condition-request.json").read_text())
    definition["datasetId"] = dataset["datasetId"]
    extraction = None
    if args.with_extraction:
        extraction = request(prefix + "/extractions", {}, expected=202)
        deadline = time.monotonic() + 30
        while extraction["status"] in ("QUEUED", "RUNNING") and time.monotonic() < deadline:
            time.sleep(0.25)
            extraction = request(prefix + f'/extractions/{extraction["id"]}')
        if extraction["status"] != "SUCCEEDED":
            raise SystemExit(f"Extraction failed: {extraction['status']} / {extraction['errorCode']}")
        if extraction["output"]["mode"] != "MOCK":
            raise SystemExit("This synthetic demo expects explicit MOCK mode")
        definition["rule"] = extraction["output"]["rule"]
        definition["sourceQuote"] = extraction["output"]["sourceQuote"]
        extraction = request(prefix + f'/extractions/{extraction["id"]}/condition',
                             {"definition": definition, "note": "합성 샘플 조건·상품 연결 검토 시연"}, expected=201)
        condition = {"id": extraction["conditionId"]}
    else:
        condition = request(prefix + "/conditions", definition)
    request(prefix + f'/conditions/{condition["id"]}/approval', {"reviewer": "demo-reviewer"})
    result = request(prefix + "/assessments", {"conditionId": condition["id"]})
    stored = request(prefix + f'/assessments/{result["id"]}')
    if stored != result:
        raise SystemExit("Stored assessment differs from response")
    golden = json.loads((SAMPLE / "expected_summary.json").read_text())["before"]
    for section, key in (("inventory", "inventoryTotals"), ("shipments", "shipmentTotals")):
        expected = dict(zip(("target", "nonTarget", "needsReview"), (golden[section][s] for s in ("TARGET", "NON_TARGET", "NEEDS_REVIEW"))))
        if result[key] != expected:
            raise SystemExit(f"Golden mismatch: {key}")
    output = {"datasetId": dataset["datasetId"], "caseId": case["id"], "conditionId": condition["id"], "assessmentId": result["id"],
              "inventoryTotals": result["inventoryTotals"], "shipmentTotals": result["shipmentTotals"],
              "resultUrl": base + prefix + f'/assessments/{result["id"]}'}
    if extraction:
        output["extraction"] = {key: extraction[key] for key in ("id", "status", "reviewStatus")}
        output["extraction"]["mode"] = extraction["output"]["mode"]
    if args.with_evidence:
        proposal = json.loads((SAMPLE / "evidence-request.json").read_text())
        proposal["baseAssessmentId"] = result["id"]
        proposal["documentText"] = (SAMPLE / "receipt-evidence.md").read_text()
        evidence = request(prefix + "/evidence", proposal)
        if evidence["issues"]:
            raise SystemExit(f"Unexpected evidence issues: {evidence['issues']}")
        approved = request(prefix + f'/evidence/{evidence["id"]}/approval', {
            "reviewer": "demo-reviewer", "note": "합성 명세서의 R4 입고와 전체 40 EA 단일 제조분 확인",
            "receiptAndSingleLotConfirmed": True})
        after = request(prefix + f'/assessments/{approved["resultAssessmentId"]}')
        after_golden = json.loads((SAMPLE / "expected_summary.json").read_text())["after"]
        for section, key in (("inventory", "inventoryTotals"), ("shipments", "shipmentTotals")):
            expected = dict(zip(("target", "nonTarget", "needsReview"), (after_golden[section][state] for state in ("TARGET", "NON_TARGET", "NEEDS_REVIEW"))))
            if after[key] != expected:
                raise SystemExit(f"After golden mismatch: {key}")
        if request(prefix + f'/assessments/{result["id"]}') != result:
            raise SystemExit("Original assessment changed")
        s2 = next(shipment for shipment in after["shipments"] if shipment["shipmentId"] == "S2")
        if s2["unlinked"] != 10 or s2["needsReview"] != 10:
            raise SystemExit("Unlinked shipment was incorrectly resolved")
        output["evidence"] = {"id": evidence["id"], "status": approved["status"],
                              "resultDatasetId": approved["resultDatasetId"], "resultAssessmentId": after["id"],
                              "inventoryTotals": after["inventoryTotals"], "shipmentTotals": after["shipmentTotals"],
                              "unlinkedS2": s2["unlinked"]}
    if args.with_tasks:
        task_assessment = after if args.with_evidence else result
        task = request(prefix + "/tasks", {
            "taskType": "QUARANTINE", "targetType": "INVENTORY", "assessmentId": task_assessment["id"],
            "targetId": "I4", "title": "가상 I4 재고 격리 확인", "instructions": "가상 재고 30 EA의 격리 결과를 기록",
            "actor": "demo-reviewer"})
        task_url = prefix + f'/tasks/{task["id"]}'
        task = request(task_url + "/assignment", {"expectedVersion": task["version"], "assignee": "demo-operator", "actor": "demo-reviewer", "note": "가상 작업 배정"})
        task = request(task_url + "/transitions", {"expectedVersion": task["version"], "action": "START", "actor": "demo-operator", "note": "가상 작업 시작"})
        task = request(task_url + "/proofs", {"expectedVersion": task["version"], "evidenceText": "시연용 가상 증빙: I4 30 EA 격리 확인. 실제 격리 실적 아님.", "actor": "demo-operator"})
        proof_id = task["proofs"][0]["id"]
        task = request(task_url + f'/proofs/{proof_id}/review', {"expectedVersion": task["version"], "decision": "ACCEPTED", "actor": "demo-reviewer", "note": "가상 증빙의 대상·수량 검토"})
        task = request(task_url + "/transitions", {"expectedVersion": task["version"], "action": "COMPLETE", "actor": "demo-reviewer", "note": "가상 작업 완료 기록"})
        if request(task_url) != task or task["status"] != "COMPLETED":
            raise SystemExit("Task completion was not persisted")
        if request(prefix + f'/assessments/{task_assessment["id"]}') != task_assessment:
            raise SystemExit("Completing a task changed the assessment")
        output["task"] = {"id": task["id"], "status": task["status"], "assignee": task["assignee"], "version": task["version"], "eventCount": len(task["events"])}
    client.logout()
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

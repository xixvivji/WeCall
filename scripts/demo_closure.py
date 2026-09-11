"""Demonstrate blocked closure and successful closure using separate synthetic cases."""
import argparse
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

    def request(path, body=None, expected=200, content_type="application/json"):
        if isinstance(body, dict):
            body = json.dumps(body, ensure_ascii=False).encode()
        req = urllib.request.Request(base + path, data=body, headers={"Content-Type": content_type})
        try:
            response = urllib.request.urlopen(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            result = json.load(response)
            if response.code != expected:
                raise SystemExit(f"Expected {expected}, got {response.code}: {result}")
            return result

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
    csvs = {
        "products": "product_id,name,manufacturer,pack_size,unit\nP1,가상 과자,가상 제조사,100g,EA\n",
        "receipts": "receipt_id,product_id,lot_number,expiry_date,received_quantity,received_at\nR1,P1,A01,2026-10-31,5,2026-09-01\n",
        "inventory": "inventory_id,receipt_id,warehouse,quantity,hold_status\nI1,R1,WH1,5,NONE\n",
        "shipments": "shipment_id,order_id,product_id,quantity,shipped_at\n",
        "shipmentAllocations": "allocation_id,shipment_id,receipt_id,quantity\n",
    }
    boundary = "wecall-" + uuid.uuid4().hex
    parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="asOf"\r\n\r\n2026-09-09T18:00:00+09:00\r\n'.encode()]
    for field, text in csvs.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{field}"; filename="{field}.csv"\r\nContent-Type: text/csv\r\n\r\n{text}\r\n'.encode())
    parts.append(f"--{boundary}--\r\n".encode())
    dataset = request("/api/v1/datasets", b"".join(parts), expected=201, content_type=f"multipart/form-data; boundary={boundary}")
    case = request("/api/v1/recalls", {"title": "종료 검증 전용 가상 사건", "sourceType": "INTERNAL", "sourceText": "가상 과자 100g 중 제조번호 A01 회수"}, expected=201)
    prefix = f'/api/v1/recalls/{case["id"]}'
    condition = request(prefix + "/conditions", {"datasetId": dataset["datasetId"], "sourceQuote": "제조번호 A01", "productReviews": {"P1": {"status": "MATCHED", "reason": "가상 상품 확인"}}, "rule": {"op": "EQ", "field": "LOT_NUMBER", "values": ["A01"]}}, expected=201)
    request(prefix + f'/conditions/{condition["id"]}/approval', {"reviewer": "demo-reviewer"})
    run = request(prefix + "/assessments", {"conditionId": condition["id"]}, expected=201)
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
    print(json.dumps({"blockedCaseId": unresolved["caseId"], "blockedReason": "UNRESOLVED_SHIPMENTS", "closedCaseId": case["id"], "finalStatus": final["status"], "history": [event["type"] for event in final["history"]]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

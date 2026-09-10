# 합성 회수 사건 C1

모든 업체·제품·기록은 개발용 가상 자료다. 기준 시각은 2026-09-09 18:00 Asia/Seoul, 단위는 EA이다.

1. `notice.md`를 읽고 P1 상품 연결 및 `(lot IN [A01,A02]) AND (expiry = 2026-10-31)` 조건을 승인했다고 가정한다.
2. `products.csv`, `receipts.csv`, `inventory.csv`, `shipments.csv`, `shipment_allocations.csv`를 입력한다.
3. `expected_receipt_decisions.csv`와 `expected_shipment_impacts.csv`의 before 열을 정답으로 사용한다.
4. `receipt-evidence.md`를 사람이 검토·승인하여 R4 제조번호를 A02로 보완한 새 버전으로 재판정한다. after 열과 비교한다.

| 수량 | 증거 승인 전 | 승인 후 |
| --- | ---: | ---: |
| 대상 재고 | 110 | 140 |
| 비대상 재고 | 110 | 110 |
| 확인 필요 재고 | 30 | 0 |
| 대상 출고 | 60 | 70 |
| 비대상 출고 | 10 | 10 |
| 확인 필요 출고 | 20 | 10 |

S2는 30개 중 20개만 R2에 연결되어 있다. 남은 10개의 입고 제조분은 알 수 없으며 증거 승인 뒤에도 그대로 확인 필요다. R2의 입고량에서 현재 재고와 연결 출고를 뺀 값이 10이라고 해서 S2에 자동 연결하면 안 된다.
S4는 R4에 실제 연결되어 있으므로 R4 판정이 바뀌면 영향 수량도 바뀐다.

CSV는 UTF-8, 첫 행 헤더, 날짜 YYYY-MM-DD, 빈 제조번호는 unknown이다. ID는 파일 내 유일하며 참조 ID는 존재해야 한다. 원본 파일은 수정하지 않는다. 이 샘플은 전체 업로드 계약이나 실제 OCR 성능 평가 자료가 아니다.

저장소 루트에서 `python3 scripts/validate_sample.py`로 참조·수량·정답표 일관성을 검사한다. 이 검사는 향후 Java 판정 엔진의 정확성 검증을 대신하지 않는다.

백엔드 실행 후 `python3 scripts/demo_recall.py --with-evidence`로 등록부터 증거 승인 후 재판정까지 시연할 수 있다. `evidence-request.json`의 baseAssessmentId는 실제 기존 판정 ID로 대체한다. PDF/OCR 시연이 아닌 텍스트 증거·수동 제안 검토다.

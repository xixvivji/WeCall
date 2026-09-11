# 회수 사건·조건 승인·판정 API

## 구현 범위

사건 원문 등록 → 데이터 버전 지정 → 상품 검토와 조건 초안 등록 → 수동 승인 → 판정 → 저장된 영향 조회.
기존 Java 21 / Spring Boot 3.5.16 / Gradle 8.7 / PostgreSQL 구성을 사용한다.
V2 Flyway 마이그레이션으로 recall_case, recall_condition, assessment_run을 추가한다.

AI 추출과 자동 상품 매칭은 아직 구현하지 않았다. 사건 종료는 [종료 API](closure-api.md)로 구현했다. 대응 작업은 [작업 API](task-api.md)로 구현했다. 추가 증거 승인은 [입고 증거 API](evidence-api.md)로 구현했다.
인증·권한은 미구현이므로 승인자 reviewer는 **검증된 신원이 아닌 입력 라벨**이다. 로컬 개발용 API이며 기본 127.0.0.1 바인딩을 유지한다.

## 샘플 전체 실행

저장소 루트에서:

```sh
docker compose up -d postgres
cd backend
./gradlew bootRun
```

별도 터미널의 저장소 루트에서:

```sh
python3 scripts/demo_recall.py
```

이 스크립트는 매번 **새 합성 데이터 버전·사건·조건·승인·판정**을 생성한다. 사람이 사전에 작성한 조건·상품 정답을 demo-reviewer 라벨로 승인하는 시연이다. 실제 고객 승인 절차를 자동화한 것이 아니다.
출력 ID와 resultUrl로 저장 결과를 재조회할 수 있다. `--base-url http://127.0.0.1:18080`처럼 로컬 서버 주소를 바꿀 수 있다.

| 합계 | 대상 | 비대상 | 확인 필요 |
| --- | ---: | ---: | ---: |
| 재고 | 110 | 110 | 30 |
| 출고 | 60 | 10 | 20 |

출고 확인 필요 20개는 R4 제조번호 누락에 연결된 10개와 S2 제조분 미연결 10개다. 재고·출고는 별도 지표이므로 현재 잔량처럼 합쳐 표시하지 않는다. 단위는 EA다.

## API 목록

모든 요청·응답은 JSON이며 경로 ID는 UUID다.

| 메서드 | 경로 | 동작 |
| --- | --- | --- |
| POST | /api/v1/recalls | 사건 생성, 201 |
| GET | /api/v1/recalls/{caseId} | 원문·조건 버전 목록 조회 |
| POST | /api/v1/recalls/{caseId}/conditions | 새 조건 버전 생성, 201 |
| GET | /api/v1/recalls/{caseId}/conditions/{conditionId} | 조건·상품 검토·근거·승인 기록 조회 |
| POST | /api/v1/recalls/{caseId}/conditions/{conditionId}/approval | 초안 승인 |
| POST | /api/v1/recalls/{caseId}/assessments | 승인된 조건으로 새 판정 저장, 201 |
| GET | /api/v1/recalls/{caseId}/assessments/{runId} | 당시 저장 결과 조회 |

사건 등록 예시:

```json
{
  "title": "별빛 크래커 회수",
  "sourceType": "SUPPLIER",
  "sourceText": "제조번호 A01 또는 A02이면서 소비기한이 2026-10-31인 제품"
}
```

sourceType: SUPPLIER / OFFICIAL / INTERNAL. 제목은 200자, 원문은 100000자 이하.

조건 요청은 [샘플 JSON](../../samples/recall-001/condition-request.json)을 사용하되 datasetId를 CSV 업로드 응답의 실제 UUID로 바꾼다. 0으로 채운 UUID는 자리표시자다.

- productReviews: 데이터 버전 안의 상품별 MATCHED/EXCLUDED와 검토 이유. 최대 1000개, 적어도 하나 MATCHED 필요.
- 생략한 상품: 미검토이므로 해당 입고는 NEEDS_REVIEW. 상품명으로 자동 연결하지 않는다.
- sourceQuote: 사건 원문에 그대로 존재하는 인용문, 최대 10000자. 텍스트 포함 여부만 검증하므로 논리적 근거 충분성은 담당자가 검토해야 한다.
- rule: 아래 제한된 조건 트리. 상품 검토와 rule은 한 버전 정의에 보존한다.

승인 요청: `{"reviewer":"local-reviewer"}`. 공백 불가, 최대 200자.
판정 요청: `{"conditionId":"승인된 조건 UUID"}`. 최신 버전을 자동 선택하지 않는다.

## 조건 트리

- 논리: AND / OR. field·values 없이 children 2~20개.
- 필드: LOT_NUMBER / EXPIRY_DATE.
- 연산: EQ(값 1개), IN(값 목록), BETWEEN(날짜 경계 2개, 양끝 포함).
- 리프는 children 없이 field·values 사용. 날짜는 유효한 YYYY-MM-DD.
- 최대 깊이 8, 전체 노드 100, 리프 값 100개. SQL·스크립트·자유 텍스트 조건은 실행하지 않는다.
- 표의 행별 조건은 OR(AND(행1 조건), AND(행2 조건)) 구조로 유지한다. 열의 값 목록으로 합치지 않는다.

누락 입력은 UNKNOWN이다. FALSE AND UNKNOWN은 FALSE, TRUE OR UNKNOWN은 TRUE이며 그 외 결정할 수 없는 조합은 UNKNOWN이다.
따라서 소비기한만 지정한 조건에는 제조번호가 없어도 판정할 수 있다.

## 결과와 근거

- receipts: receiptId, productId, decision(TARGET/NON_TARGET/NEEDS_REVIEW), reason.
- reason: CONDITION_MATCHED / CONDITION_NOT_MATCHED / REQUIRED_FACT_MISSING / PRODUCT_EXCLUDED / PRODUCT_NOT_REVIEWED.
- inventory: 재고 ID·입고·창고·수량·기존 보류 상태·판정. 보류 상태는 자동 변경하지 않는다.
- shipments: 출고·주문 ID, 전체 수량, target/nonTarget/needsReview, unlinked.
- unlinked는 needsReview에 **포함된** 제조분 미연결 수량이다. 별도로 더하면 중복 집계다. 현재 구현은 미연결 수량을 보수적으로 확인 필요로 유지한다.
- inventoryTotals / shipmentTotals: 각각 판정별 수량 합계.
- conditionId로 승인된 조건·상품 비교 이유·원문 인용을 확인하고, datasetId·receiptId로 입력 기록을 추적한다. 입력 행별 조회 API는 아직 없다.

판정은 JSONB 스냅샷으로 저장하며 읽기 시 재계산하지 않는다. 논리 ERD의 개별 DECISION 테이블을 별도로 구현한 것은 아니다.
수정 API 없이 새 조건 버전·새 판정으로 변경을 남긴다. 승인 완료 조건은 다시 승인할 수 없으며, 이전 승인 버전도 명시적으로 지정하면 재실행 가능하다. 자동 폐기·대체 승인 정책은 후속 범위다.

## 오류와 검증

- 400: 요청 누락·형식 오류, 지원하지 않는 조건, 존재하지 않는 상품 참조, 원문에 없는 인용.
- 404: 없는 사건·데이터·조건·결과 또는 다른 사건에 속한 조건·결과 요청.
- 409: 미승인 조건 판정 또는 중복 승인.
- 사건별 잠금으로 조건 버전 번호를 할당하고 승인도 행 잠금으로 중복 처리를 방지한다.
- 복합 FK로 판정의 사건·조건·데이터 버전 불일치를 차단한다.

`cd backend && ./gradlew test bootJar`로 PostgreSQL 통합 테스트와 조건 엔진 테스트를 실행한다.
샘플의 수작업 정답 CSV/JSON과 결과를 비교하며, 이전 판정 보존·미승인 차단·날짜 경계·누락값 논리·행 관계를 검사한다.
추가 증거 승인 후(after) 정답 검증도 입고 증거 통합 테스트와 `demo_recall.py --with-evidence`에 포함한다.

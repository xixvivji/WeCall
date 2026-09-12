# PostgreSQL CSV 등록 API

## 로컬 실행

저장소 루트에서 `docker compose up -d postgres`, 이어서 `cd backend && ./gradlew bootRun`.
PostgreSQL 17.6을 로컬 5432 포트에 실행한다. 기존 DB가 이 포트를 사용하면 Compose 포트와 DB_URL을 함께 변경한다.
Flyway가 V1 스키마를 자동 적용한다. 런타임 스키마 자동 생성은 사용하지 않는다.

기본 접속: DB `wecall`, 사용자 `wecall`, 비밀번호 `wecall-local-only` (로컬 개발 전용).
Spring 설정은 DB_URL, DB_USERNAME, DB_PASSWORD로 변경한다. Compose는 DB_PASSWORD를 읽는다.
비밀번호 변경은 기존 볼륨의 DB 사용자 비밀번호를 자동 변경하지 않는다.
`docker compose stop`으로 중지할 수 있으며 볼륨은 보존된다.

## 요청

`POST /api/v1/datasets` — multipart/form-data. 아래는 본문 형식 예시다. 실제 호출에는 로그인 세션 쿠키와 CSRF 헤더를 추가한다. 인증을 포함한 시연 스크립트 사용은 [로그인 가이드](auth-api.md)를 따른다.

```sh
curl --fail-with-body http://127.0.0.1:8080/api/v1/datasets \
  -F 'asOf=2026-09-09T18:00:00+09:00' \
  -F 'products=@samples/recall-001/products.csv' \
  -F 'receipts=@samples/recall-001/receipts.csv' \
  -F 'inventory=@samples/recall-001/inventory.csv' \
  -F 'shipments=@samples/recall-001/shipments.csv' \
  -F 'shipmentAllocations=@samples/recall-001/shipment_allocations.csv'
```

201 응답: `datasetId`, `asOf`, `counts`, `unlinkedShipmentQuantity`.
샘플 counts는 products 3, receipts 7, inventory 7, shipments 4, shipment_allocations 4이며 미연결 출고는 10 EA다.
동일 자료 재업로드도 새 UUID의 스냅샷이다. 자동 중복 업로드 방지나 기존 데이터 갱신은 아직 없다.

## 입력 계약과 오류

- UTF-8(BOM 허용), 샘플과 동일한 헤더·순서. CSV 인용 및 쉼표 필드를 지원한다.
- 오류 `row`는 헤더를 1로 센 논리 레코드 번호다. 인용 필드에 줄바꿈이 있으면 물리 줄 번호와 다를 수 있다. 파싱 실패는 0이다.
- 파일당 5MB·10000개 데이터 행, 요청당 26MB. 텍스트 필드는 최대 500자.
- 수량은 0~10억의 정수 EA. 출고 연결량은 1 이상. 다른 단위는 아직 지원하지 않는다.
- 날짜는 YYYY-MM-DD. 기준 시각은 오프셋 포함 ISO 8601. 입출고 날짜는 입력 오프셋 기준 기준일 이하여야 한다.
- 제조번호·소비기한 빈 값은 NULL로 보존한다. 나머지 필드는 필수다.
- hold_status는 NONE 또는 HELD. 보류 상태는 회수 대상 판정과 별개다.
- 각 파일 ID 중복, 없는 참조, 상품이 다른 출고 연결, 입고 이전 출고, 수량 초과를 거절한다.
- 재고+연결 출고가 입고량 이하면 허용한다. 부족분으로 출고 연결을 추정하지 않는다.
- 연결량이 출고량보다 적어도 등록 가능하다. 미연결 수량을 별도로 반환하며 비대상을 뜻하지 않는다.
- 헤더만 있는 파일은 빈 목록으로 허용한다. 아직 모든 품목·기간을 커버한다는 보장은 없다.
- 400 INVALID_DATASET: errors 배열에 file, row, field, message. 필드 형식 → 참조 → 수량 검증 순으로 오류를 반환한다.
- 400 INVALID_REQUEST: 필수 multipart 필드 또는 asOf 누락·형식 오류.
- 413 UPLOAD_TOO_LARGE: 업로드 용량 초과.

5종 파일은 한 트랜잭션으로 저장된다. 검증 실패와 저장 중 DB 오류 모두 부분 저장을 남기지 않는다.
스냅샷별 복합 PK/FK로 다른 버전의 행을 연결하지 못하게 한다. 상품 일치는 복합 FK로도 보호한다.
집계 수량 제약은 등록 서비스가 검증한다. 현재 수정 API는 없으며 추후 수정 경로에서도 같은 검증이 필요하다.

## 현재 범위

JDBC + Flyway로 일괄 등록을 구현했다. JPA 엔터티는 아직 도입하지 않았다.
[로그인한 검토자와 CSRF 헤더](auth-api.md)가 필요하다. 기본 바인딩은 127.0.0.1이다. 원본 파일 저장·해시·행별 조회 API는 후속 범위다.
현재 dataset은 불변 입력 스냅샷이며 논리 ERD의 전체 사건·승인 모델은 아직 테이블화하지 않았다.

## 검증

Docker 실행 상태에서 `cd backend && ./gradlew test bootJar`.
Testcontainers가 별도 PostgreSQL을 만들기 때문에 개발 DB 데이터는 테스트에서 변경하지 않는다.

## 데이터 준비 상태 점검

두 역할 모두 `GET /api/v1/datasets/{id}/readiness`로 해당 버전의 상품·입고·재고·출고·연결 기록 건수와 누락 요약을 조회한다. 제조번호·소비기한 누락은 각각 집계하고 missingEither는 둘 중 하나 이상 누락된 입고의 중복 없는 건수다.

출고마다 같은 데이터 버전의 연결 수량을 먼저 합산한다. 양수 출고는 연결 없음(0), 일부 연결(0 < 연결 < 출고), 전체 연결로 구분하며 0수량 출고는 별도 집계한다. unlinkedQuantity는 출고 수량에서 실제 연결 합계를 뺀 수량의 합(EA)이다. 존재하지 않는 참조·음수·과다 연결은 기존 CSV 등록 검증에서 거부한다.

`GET /api/v1/datasets/{id}/readiness/issues?type=receipts|shipments&page=0&size=20`은 누락 입고 또는 연결 미확인 수량이 양수인 출고만 반환한다. 기본 type은 receipts, size는 1~100이며 ID 오름차순이다. items/page/size/totalElements/totalPages를 반환한다. 잘못된 조건은 400, 없는 버전은 404다. 요약과 각 목록 요청은 읽기 전용 DB 스냅샷에서 조회하며 데이터 값을 변경하지 않는다.

프론트의 데이터 관리 → 준비 상태 점검에서 해당 버전과 기준 시각, 누락·연결 현황, 페이지별 상세 기록을 확인한다. 데이터 누락은 회수 대상 판정이 아니며 실제 판정은 승인된 회수 조건에 따른다. 입고 증거 승인으로 생성한 보완 버전도 독립적으로 조회한다. 출고 연결 보완은 실제 기록을 확인한 새 CSV 버전 등록으로 처리한다.

# FastAPI 조건 추출 연동

실제 AI 모델은 아직 선정·연결하지 않았다. 기본 provider는 `disabled`이며 모델 미설정으로 실패한다. `fixture`는 지정된 합성 원문에 저장된 정답을 반환하는 모의 구현이다. 임의 문서를 분석하지 않는다. 모델 선정 후 `ai/app/extraction.py`의 Provider 계약을 구현해야 한다.

## 실행과 시연

`.env`에 충분히 긴 임의의 `WECALL_AI_SERVICE_TOKEN`을 설정하고 두 서버에서 동일하게 불러온다. 토큰은 Git에 저장하지 않는다. 기본 주소는 `http://127.0.0.1:8000`이다.

모의 시연에서만 FastAPI 실행 터미널에 `export WECALL_AI_PROVIDER=fixture`, 백엔드 실행 터미널에 `export WECALL_AI_ALLOW_MOCK=true`를 적용한다. `.env`를 불러온 후 적용하고 서버를 실행한다. 실제 모델처럼 사용하지 않는다.

검토자 계정 환경변수를 불러온 저장소 루트에서:

```sh
python3 scripts/demo_recall.py --with-extraction --with-evidence --with-tasks
```

합성 원문 분석 요청 → 결과 폴링 → 합성 조건·상품 연결 검토 시연 → 조건 초안 생성 → 별도 승인 → 판정 순서다. `mode: MOCK`을 출력하며 기존 정답 합계와 누락 출고 10개 보존을 검증한다.

## 업무 API

기본 경로: `/api/v1/recalls/{caseId}/extractions`. 로그인과 변경 요청의 CSRF 토큰이 필요하다. GET은 REVIEWER·OPERATOR, POST는 REVIEWER만 허용한다.

| 메서드 | 경로 | 동작 |
| --- | --- | --- |
| POST | 기본 경로 | 원문 스냅샷을 저장하고 QUEUED 작업 반환(202) |
| GET | 기본 경로 | 사건 분석 이력 조회 |
| GET | `/{id}` | 상태·원문·결과·오류·검토 이력 조회 |
| POST | `/{id}/condition` | 성공 결과를 검토하여 조건 DRAFT 생성(201) |
| POST | `/{id}/dismissal` | 완료된 분석을 사유와 함께 기각 |

조건 전환 본문은 `{"definition": <기존 조건 생성 요청>, "note": "검토 사유"}`이다. 담당자가 datasetId, 상품 연결, rule, sourceQuote를 검토해 제출한다. 원래 추출 결과는 수정하지 않는다. 별도 조건 승인 전에는 판정할 수 없다. 기각 본문은 `{"note":"사유"}`이다. 검토자 신원은 세션에서 가져온다.

작업 상태는 QUEUED → RUNNING → SUCCEEDED 또는 FAILED다. 검토 상태는 PENDING → ACCEPTED 또는 DISMISSED다. 사건별 동시 진행 요청은 하나로 제한한다. 진행 중인 작업은 기각할 수 없다. 실패 결과도 담당자가 검토·기각해야 하며 PENDING 작업은 사건 종료를 차단한다. 다시 요청하면 별도 이력으로 남는다.

## 서버 간 계약

FastAPI `POST /v1/extractions`는 `X-Service-Token` 인증과 `{requestId, sourceText}`를 받는다. 결과는 requestId, 원문의 UTF-8 SHA-256인 sourceSha256, schemaVersion(v1), mode(MOCK/LIVE), provider, model, promptVersion, rule, sourceQuote, warnings를 포함한다. sourceQuote는 원문의 실제 부분 문자열이어야 한다.

Spring은 작업 ID·원문 해시·스키마·조건 AST·인용문·메타데이터를 검증한다. 명시적으로 허용하지 않은 MOCK 결과는 실패 처리한다. 구조 검증은 의미적 정확성을 보장하지 않으므로 사람의 검토가 필요하다.

DB 작업 선점 트랜잭션 후 외부 호출을 수행한다. 연결 제한 2초, 읽기 제한 10초, 응답 상한 256KiB다. 이 제한은 읽기 전체의 절대 실행시간 제한은 아니다. RUNNING이 2분을 넘으면 다음 워커 점검 시 WORKER_INTERRUPTED로 남기고 늦은 결과로 덮어쓰지 않는다. 자동 재시도는 하지 않는다. 현재 워커는 서버당 순차 처리한다.

정상 응답과 제한 이내의 잘못된 성공 응답은 원문 그대로 보존한다. HTTP 오류·연결 오류·과대 응답은 원문을 보존하지 않는다. API 조회에 원문과 추출 결과가 포함되므로 운영 배포 시 접근·보존 정책을 추가해야 한다.

검증: PostgreSQL 통합 테스트에서 비동기 상태, 승인 분리, 잘못된 ID·해시·조건·인용, 응답 크기, 타임아웃, 모의 응답 차단, 중복 요청, 종료 차단, 중단 복구, 전환 롤백, 권한을 확인한다. Python 테스트는 서비스 인증·기본 비활성화·정확한 fixture 입력 제한을 확인한다.

## 교체 가능한 백엔드 호출 인터페이스

`ExtractionClient`는 모델이나 HTTP 라이브러리에 의존하지 않는 Java 인터페이스다.

```java
Response extract(UUID requestId, String source, String sha);
```

입력은 작업 ID·회수 원문 스냅샷·UTF-8 SHA-256이다. 반환은 기존 `ExtractionModels.Result`와 감사용 원본 응답이며 오류는 `ExtractionClient.Failed`의 코드로 전달한다. 구현체는 작업 ID·해시·조건 AST·인용문 검증과 응답 크기 제한을 책임진다. 승인·재판정·데이터 접근은 이 인터페이스의 책임이 아니다.

기존 HTTP 코드는 `FastApiExtractionClient implements ExtractionClient`로 분리했다. 워커와 결과 저장 서비스는 인터페이스에만 의존한다. FastAPI 내부 모델만 바꾸고 v1 HTTP 계약을 유지하면 Java 변경이 필요 없다. 통신 방식까지 바꾸면 인터페이스 구현체를 교체하고 Spring 빈을 하나만 등록한다. 검증·타임아웃·오류 계약도 새 구현체에서 유지해야 한다.

이번 변경은 호출 경계를 분리한 것이며 실제 AI 모델이나 신규 외부 호출을 추가하지 않는다. 기존 FastAPI 연동과 명시적 fixture 시연은 유지한다. 기본 provider는 여전히 disabled다. 내부망 강제·네트워크 반출 차단은 별도 작업이다.

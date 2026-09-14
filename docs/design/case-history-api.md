# 사건 업무 이력

`GET /api/v1/recalls/{caseId}/history`

로그인한 REVIEWER·OPERATOR가 기존 사건 읽기 권한으로 조회한다. 열린 사건과 종료된 사건 모두 지원한다. 비로그인401, 없는 사건404, 잘못된 UUID·매개변수400. 변경 API는 없으며 기존 승인·검토·이벤트 기록을 읽어 통합한다.

## 요청

- `kind`: ALL(기본), CONDITION, EVIDENCE, TASK, PROOF, LIFECYCLE
- `order`: DESC(기본, 최신순), ASC(오래된순)
- `page`: 0부터, 최대100000
- `size`: 기본20, 1–100

응답은 `items`, `totalElements`, `totalPages`, `page`, `size`를 제공한다. 건수·목록은 하나의 REPEATABLE_READ 트랜잭션에서 읽는다. 정렬은 기록 시각과 고유 이력 ID로 결정한다. 같은 시각의 서로 다른 기록 간 선후관계를 추정하지 않는다. 페이지 사이 새 기록이 추가될 수 있으므로 전체 순회용 고정 스냅샷은 아니다.

각 항목: `id`, `kind`, `type`, `occurredAt`, `actor`, `note`, `title`, `version`, `conditionId`, `evidenceId`, `taskId`, `proofId`, `assignee`. 없는 값은 null이며 화면에는 처리자·사유의 누락을 `기록 없음`으로 표시한다. 버전은 원본의 조건/작업/종료 버전이며 통합 전역 순번이 아니다.

## 원본 기록과 표시 범위

| 유형 | 원본 | 이벤트 |
| --- | --- | --- |
| CONDITION | recall_condition의 approved_at/approved_by | CONDITION_APPROVED |
| EVIDENCE | receipt_evidence의 reviewed_at/reviewed_by/review_note | EVIDENCE_APPROVED, EVIDENCE_REJECTED |
| TASK | response_task_event | TASK_CREATED, TASK_ASSIGNED, TASK_START, TASK_COMPLETE, TASK_CANCEL, TASK_REOPEN |
| PROOF | response_task_event의 PROOF_ADDED/PROOF_REVIEWED | PROOF_ADDED, PROOF_ACCEPTED, PROOF_REJECTED |
| LIFECYCLE | case_lifecycle_event | CASE_CLOSED, CASE_REOPENED |

증빙 검토는 작업 이벤트 한 건을 사용하며 response_task_proof 검토 정보를 별도 이벤트로 중복 생성하지 않는다. proofId는 해당 작업에 속한 실제 증빙이 확인될 때만 제공한다. 배정 담당자는 현재 담당자를 복사하지 않고 이벤트 details에 저장된 assignee를 사용한다. 작업 제목은 원본 작업의 제목이며 현재 수정 API는 없다.

조건 승인 사유와 사건·조건 초안·입고 증거 등록자의 정보는 기존 스키마에 별도 저장되지 않는다. 조건 승인 사유는 null이며, 사건 등록·조건 초안 생성·입고 증거 제안·판정 실행·첨부 다운로드는 이번 이력 범위에 넣지 않았다. 증거 승인으로 파생된 조건의 승인 기록은 실제 저장된 조건 승인으로 표시한다. 기존 계정 인증 도입 전 actor 문자열을 현재 인증 이력으로 재해석하지 않는다.

원문·증빙 본문·조건 JSON·종료 스냅샷·파일은 응답에 포함하지 않는다. 사유는 저장된 문자열을 Vue 텍스트로 렌더링한다. 원본 ID를 이용해 조건, 입고 증거, 작업/증빙 상세, 종료 점검으로 이동한다. 추가 저장 테이블이나 과거 기록 수정은 없다.

## 검증

`CaseHistoryTests`: 4개 통합 테스트로 원본 통합·증빙 중복 방지·null 사유·원문 제외·동일 시각 안정 정렬·페이지 경계·역순·유형 필터·사건 범위·종료 사건·두 역할·비로그인·입력 오류를 확인한다.

브라우저 `history.spec.ts`는 실제 사건에 작업 배정 이력22건을 만들고 페이지20/2건·정렬·빈 필터·통신 실패 복구·실행자 읽기·작업 바로가기·모바일 가로 넘침을 검증한다. 기존 `workflow.spec.ts`는 조건 승인2건(증거 보완의 파생 조건 포함), 입고 증거 승인/반려, 증빙 승인, 작업 완료 표시와 증빙 바로가기를 검증한다.

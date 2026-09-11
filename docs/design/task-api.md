# 대응 작업·담당자·처리 증빙 API

## 범위

격리·출고보류·판매보류·공급사 확인·반품 확인·안내 준비 작업을 수동 등록하고 담당자, 진행 상태, 텍스트 증빙, 검토 결과, 이력을 기록한다.
이 API는 **조치의 기록**이다. 창고·판매 채널에 실행 명령을 보내거나 고객에게 발송하지 않는다. 원본 재고·판정 결과를 변경하지 않는다.
담당자와 actor는 현재 입력 라벨이며 인증된 사용자 ID가 아니다. 역할별 접근 제어, 파일 첨부, 자동 작업 생성, 사건 종료 승인은 후속 범위다.

## 대상과 상태

- CASE: 사건 전체. assessmentId와 targetId를 생략하면 분석 전 긴급 보류도 기록 가능하다. targetId는 항상 생략한다.
- INVENTORY / SHIPMENT: 같은 사건의 특정 판정 assessmentId와 그 결과에 존재하는 targetId가 필요하다.
- 작업은 명시한 판정 버전에 고정된다. 재판정으로 자동 갱신·완료·취소하지 않는다. 범위 변화는 검토 후 재개하거나 새 작업으로 기록한다.
- 대상 여부가 NEEDS_REVIEW여도 유효한 지시를 근거로 보류 작업을 기록할 수 있다. NON_TARGET도 자동 차단하지 않으므로 담당자가 지시 범위를 확인한다.

상태 전이:

```text
OPEN --START--> IN_PROGRESS --COMPLETE--> COMPLETED
                      ^                       |
                      +--------REOPEN---------+
OPEN / IN_PROGRESS --CANCEL--> CANCELLED
```

- START: 담당자 지정 필수.
- COMPLETE: 현재 처리 회차에 ACCEPTED 증빙이 최소 1개 있고 PENDING 증빙이 없어야 한다. REJECTED 자료만 있으면 불가.
- REOPEN: COMPLETED만 재개. reviewRound를 증가시키고 새 회차 증빙을 요구한다. 이전 증빙과 검토·완료 이력은 보존한다.
- CANCEL: OPEN/IN_PROGRESS만 취소. 취소는 완료가 아니며 완료 시각도 없다. 취소 사유를 남긴다. 미처리 문제를 해결했다는 의미가 아니다.
- COMPLETED/CANCELLED에서는 배정·증빙 추가·검토 불가. CANCELLED는 재개 API를 제공하지 않는다.

## API

기본 경로: `/api/v1/recalls/{caseId}/tasks`.

| 메서드·경로 | 동작 |
| --- | --- |
| POST 기본 경로 | 수동 작업 등록, 201 |
| GET 기본 경로 | 사건 내 작업 요약 목록 |
| GET /{taskId} | 작업·모든 회차 증빙·이력 조회 |
| POST /{taskId}/assignment | 담당자 배정·변경 |
| POST /{taskId}/transitions | START/COMPLETE/REOPEN/CANCEL |
| POST /{taskId}/proofs | 텍스트 증빙 등록, 201 |
| POST /{taskId}/proofs/{proofId}/review | 증빙 승인·거절 |

생성 예시 (assessmentId는 실제 UUID로 대체):

```json
{
  "taskType": "QUARANTINE",
  "targetType": "INVENTORY",
  "assessmentId": "00000000-0000-0000-0000-000000000000",
  "targetId": "I4",
  "title": "재고 임시 격리 확인",
  "instructions": "지정된 I4 재고 30 EA의 격리 처리 결과를 확인",
  "actor": "품질 담당"
}
```

taskType: QUARANTINE / SHIPMENT_HOLD / SALES_HOLD / SUPPLIER_CHECK / RETURN_CONFIRMATION / NOTICE_PREPARATION.
assignee는 생성 시 생략하거나 지정한다. title 200자, instructions 10000자, targetId 500자 이하.
현재 작업 종류와 대상 종류의 업무 적합성은 담당자가 판단한다. 수량은 지시문과 증빙에서 관리하며 별도 완료 수량 원장은 아직 없다.

배정: `{"expectedVersion":0,"assignee":"물류 담당","actor":"품질 담당","note":"창고 업무 배정"}`.

상태 전이: `{"expectedVersion":1,"action":"START","actor":"물류 담당","note":"처리 시작"}`.

증빙 등록: `{"expectedVersion":2,"evidenceText":"I4 30 EA 격리 결과 확인 기록","actor":"물류 담당"}`.

증빙 검토: `{"expectedVersion":3,"decision":"ACCEPTED","actor":"품질 담당","note":"대상·수량·처리 내용 확인"}`.
REJECTED와 사유를 입력해 거절할 수 있다. 검토 완료 증빙은 덮어쓰거나 재검토하지 않으며 수정 자료를 새로 등록한다.

evidenceText는 100000자, actor/assignee는 200자, note는 2000자 이하의 공백 아닌 문자열이다.
검토는 사람이 증빙의 적합성과 충분성을 확인한 결과다. 코드가 증빙 내용의 진위를 자동 검증하는 것은 아니다. SHA-256은 저장 텍스트 식별에 사용한다.

## 동시 수정과 이력

생성 응답 version은 0이다. 배정·전이·증빙 등록·검토마다 1씩 증가한다.
모든 변경 요청에 **가장 최근 응답의 expectedVersion**을 넣는다. 증빙 검토도 작업 version을 사용한다.
같은 버전으로 동시에 수정하면 한 요청만 성공하고 나머지는 409다. 새로 조회 후 내용을 확인해서 재요청해야 한다.

작업 변경과 이벤트 기록은 한 트랜잭션이다. 이벤트 저장 실패 시 상태·증빙 변경도 롤백한다.
이벤트는 버전순으로 CREATED, ASSIGNED, START, PROOF_ADDED, PROOF_REVIEWED, COMPLETE, REOPEN, CANCEL을 보존한다.
작업 상세의 proofs에는 과거 회차도 포함한다. 완료 검증은 현재 reviewRound만 사용한다.
목록·증빙·이력 조회는 현재 페이지 구분 없이 반환하므로 대규모 운영 전에 페이지 처리가 필요하다.

## 오류

- 400: 필수값·형식·대상 지정 오류.
- 404: 다른 사건의 판정/작업, 다른 작업의 증빙, 없는 ID.
- 409: 최신 버전 충돌, 허용되지 않은 전이, 담당자 누락, 미검토/승인 증빙 부족, 중복 증빙 검토.

작업 완료는 회수 대상 판정이나 사건 종료와 별개다. 완료가 미연결 출고를 해결하거나 내부 종료를 승인하지 않는다.

## 시연

백엔드 실행 후 저장소 루트에서:

```sh
python3 scripts/demo_recall.py --with-tasks
# 입고 증거 보완까지 함께 시연
python3 scripts/demo_recall.py --with-evidence --with-tasks
```

매번 새 합성 사건·작업을 생성한다. 가상 처리 증빙을 등록·검토하며 실제 격리 실적이 아니다.
41개 테스트 중 대응 작업 통합 테스트 9개에서 완료 조건·재개 회차·긴급 작업·대상 관계·동시 수정·이력 실패 롤백을 확인했다.

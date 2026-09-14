# MVP 요구사항과 완료 기준

기준일: 2026-09-13. 코드 기준: 파일 첨부 V10 구현 반영. 이 문서의 ‘구현’은 합성 데이터로 기능과 테스트를 확인했다는 뜻이며, 실사용 고객 검증이나 운영 배포 완료를 뜻하지 않는다.

## 범위

한 기업의 포장 가공식품 회수 업무를 대상으로 한다. 검토자(REVIEWER)와 실행 담당자(OPERATOR), 수동 CSV 등록·사건 입력·사람의 승인·기록에 근거한 판정이 기본이다. AI 실제 모델은 보류하고 교체 가능한 호출 인터페이스를 유지한다. main 병합·배포는 별도 결정이며 현재 통합·기본 브랜치는 develop이다.

## 구현 및 인수 기준

| ID | 요구사항 | 완료 판단 기준 | 상태·검증 근거 |
| --- | --- | --- | --- |
| DATA-01 | CSV 5종 등록 | 참조·타입·날짜·수량 오류는 원자적으로 거부; 누락 제조번호는 보존 | 구현 / DatasetImportTests |
| DATA-02 | 데이터 준비 상태 | 누락 입고 중복 제외; 출고 무연결·부분·완전·0수량 분리; 버전 경계 유지 | 구현 / DatasetImportTests, workflow.spec.ts |
| DATA-03 | 파일 추적 정보 | 실제 업로드 바이트 해시·크기·행수와 로그인 등록자 저장; 실패 시 전부 롤백 | 구현 / DatasetImportTests |
| DATA-04 | 보완 버전 계보 | 승인 증거·기준 데이터로 이동; 원본 해시를 보완 버전에 복사하지 않음 | 구현 / ReceiptEvidenceTests |
| CASE-01 | 사건 관리 | 원문·출처 저장; 사건명·상태 검색과 페이지 조회 | 구현 / RecallWorkflowTests, WorkspaceQueryTests |
| RULE-01 | 조건·상품 연결 검토 | 조건은 버전으로 저장; 상품 연결과 근거를 사람이 입력·승인; 미승인 판정 차단 | 구현 / RecallWorkflowTests |
| RULE-02 | 결정적 판정 | 누락을 자동 비대상 처리하지 않음; 승인 조건으로만 계산; 과거 결과 보존 | 구현 / RecallWorkflowTests, RuleEngineTests |
| TRACE-01 | 출고 추적 | 연결 부족분은 확인 필요에 포함; 없는 입고·출고 관계를 생성하지 않음 | 구현 / 샘플 정답표, ReceiptEvidenceTests |
| EVID-01 | 입고 증거 검토 | 입고·상품·전체 수량·입고일·단일 제조분 근거 확인; 승인 시 새 데이터·판정 생성 | 구현 / ReceiptEvidenceTests |
| TASK-01 | 작업·증빙 | 활성 담당자 배정, 실행·증빙·검토·완료·재개, 버전 충돌 차단, 회차별 이력 | 구현 / ResponseTaskTests |
| CLOSE-01 | 사건 종료 | 미확인·미처리 사유 차단; 승인 스냅샷 보존; 재개 이력 | 구현 / CaseClosureTests |
| AUTH-01 | 인증·역할 | 세션·CSRF; 담당자는 본인 작업 실행만 허용; 검토자 전용 작업 서버 차단 | 구현 / AuthSecurityTests |
| AUTH-02 | 계정 보안 | 본인 비밀번호 변경·비활성화 시 기존 세션 다음 요청 차단; 재활성화해도 구세션 회복 안 됨 | 구현 / AuthSecurityTests |
| VIEW-01 | 업무 대시보드 | 최신 판정만 사건 집계; 미판정 별도; 내 할 일·재배정 목록 제공 | 구현 / WorkspaceQueryTests, AuthSecurityTests |
| VIEW-02 | 검토 대기함 | 조건·입고 증거·현재 회차 증빙만 집계; 검토 후 새 조회에서 제외; 검토자 전용 | 구현 / WorkspaceQueryTests, workflow.spec.ts |
| VIEW-03 | 판정 비교 | 기준·비교 버전 명시, 증감 방향 고정, 한쪽 없는 기록 구분, 데이터 차이 안내 | 구현 / comparison.spec.ts, workflow.spec.ts |
| EXPORT-01 | 판정 CSV | 지정 판정의 결과·버전 ID 반환; 수식 입력 방어; 연결 미확인을 중복 합산하지 않음 | 구현 / RecallWorkflowTests, workflow.spec.ts |
| AI-01 | AI 호출 계약 | 백엔드 ExtractionClient 교체 가능; 실패·응답·입력 해시 보존; 자동 승인 없음 | 인터페이스·모의 연동 구현 / ExtractionIntegrationTests |
| FILE-01 | 증거 파일 첨부 | 허용 파일 검증·안전 저장·업무별 권한·불변 연결·실패 복구·감사 기록 검증 | 로컬 구현 / AttachmentTests, workflow.spec.ts, 정리 스크립트 테스트; [계약](design/attachments-api.md) |
| OPS-01 | 운영 배포 | 승인된 환경, HTTPS, 시크릿 관리, 백업 복원, 모니터링·접근 정책 검증 | 미구현 |
| VALID-01 | 실무 적합성 | 승인된 자료로 현업 담당자가 정답·오류 사례·처리 흐름 확인 | 대기 / [자료 준비 양식](validation/materials-checklist.md) |

테스트 소스: [백엔드](../backend/src/test/java/com/wecall/), [프론트](../frontend/e2e/). 최신 보고서가 없는 환경에서는 상태를 재검증한다. 현재 기준 마지막 로컬 검증은 백엔드 104건 통과, 프론트 18건(계산 로직 2건·브라우저 16건) 통과, 프론트 빌드 성공, 고아 파일 후보 검사 1건 통과다. 문서 작성만으로 테스트를 새로 실행했다고 보지 않는다.

## 다음 개발 순서

CI 자동 검증과 합성 업무 검증, 주요 화면 예외 처리 보완을 완료했다. [화면 복구 검증](validation/ui-recovery-2026-09-14.md)을 참고한다. 사용자 요청에 따라 AI·배포는 보류한다.

1. 사건별 업무 이력 조회 구현 완료. [이력 API·검증](design/case-history-api.md)을 참고한다.
2. CSV 빈 양식·항목 설명·파일/행 오류 표·수정 재등록 검증 완료. [작성 안내](validation/csv-authoring-guide.md)를 참고한다.
3. 회사가 허용한 자료·환경과 현업 담당자로 실무 적합성을 확인한다.
4. 배포 재개 시 운영 저장소·악성 파일 검사·DB와 파일 백업 복구·보관 정책을 준비하고 release 브랜치에서 검증한다.

## 이번 MVP에서 약속하지 않는 것

다중 기업 격리, 실제 ERP·메일 연동, 외부 판매중지 실행, 고객 자동 발송, 세트 투입 추적, 파일 OCR, 실제 모델 성능, 감사 로그 변조 방지 저장소, 운영 SLA는 현재 제공하지 않는다. 코드 테스트 통과로 법적 회수 완료나 실무 효과를 보장하지 않는다.

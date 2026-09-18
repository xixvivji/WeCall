# MVP 요구사항과 완료 기준

기준일: 2026-09-18 · 코드 기준: develop, Flyway V13. ‘구현’은 개발·합성 검증 기준이며 현업 인수나 운영 배포 완료를 뜻하지 않는다.

## 범위

한 기업의 포장 가공식품 회수 업무, 검토자·실행 담당자 두 역할, CSV 5종과 직접 입력·텍스트 PDF, 사람의 승인과 실제 기록 기반 판정이 대상이다. AI는 FastAPI로 서빙하는 로컬 모델을 사용한다. OCR과 배포는 보류한다. 웹 공고 자동 수집·AI 상품 자동 연결은 현재 제공하지 않는다.

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
| RULE-03 | 원문 재검토 | 영향 없음 근거·초안 철회·승인/판정/종료 차단·과거 판정 보존 | 구현 / CaseClosureTests, ReceiptEvidenceTests, source-review.spec.ts; [계약](design/source-revalidation.md) |
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
| AI-01 | AI 호출 계약 | 백엔드 ExtractionClient 교체 가능; 실패·응답·입력 해시 보존; 자동 승인 없음 | 인터페이스·모의 및 로컬 모델 연동 구현 / ExtractionIntegrationTests, Python provider tests |
| FILE-01 | 증거 파일 첨부 | 허용 파일 검증·안전 저장·업무별 권한·불변 연결·실패 복구·감사 기록 검증 | 로컬 구현 / AttachmentTests, workflow.spec.ts, 정리 스크립트 테스트; [계약](design/attachments-api.md) |
| FILE-02 | 악성 파일 검사 연동 | clamd 성공 응답만 통과, 시간 제한·위협·장애 차단, 다중 첨부 롤백, 다운로드 재검사 | 어댑터 구현 / ClamdScannerTests, AttachmentTests; 실제 엔진 배치는 별도 |
| DOC-01 | 텍스트 PDF 입력 | 로컬 추출·미리보기·사람 확인; 한도 초과·암호화·무텍스트 PDF 거부 | 구현 / SourcePdfTests, source-pdf.spec.ts |
| DOC-02 | 원본 보관·이력 | 파일 해시·등록자·서버 추출본·검토 원문 보관, 인증 다운로드·실패 롤백, 수정 버전·사유 | 구현 / AttachmentTests, source-pdf.spec.ts; [계약](design/source-pdf.md) |
| DOC-03 | 원문 변경과 AI | 이전 원문 버전의 분석을 새 조건으로 전환하지 않음; 과거 결과 유지 | 구현 / ExtractionIntegrationTests; 기존 조건 재검토는 RULE-03 참고 |
| AI-02 | 실제 로컬 모델 | FastAPI→로컬 Ollama 조건 초안·근거·거절, 외부 모델 자동 대체 없음 | 제한 범위 구현 / [로컬 모델](design/local-ai.md) |
| AI-03 | AI 검토 화면 | 요청·결과·근거 확인, 조건·상품 연결의 사람 검토와 별도 승인 | 구현 / extraction.spec.ts |
| AI-04 | 입력·실패 안내 | UTF-8·줄 수 사전 검사, 서버 초과 거부, 오류 분류·수동 작성 안내·입력 보존 | 구현 / ExtractionIntegrationTests, ai-guidance.spec.ts; [계약](design/ai-failure-guidance.md) |
| REPORT-01 | 보고서 | 선택한 판정 수량과 현재 업무·원문 버전을 구분, 인쇄 제공 | 구현 / report.spec.ts, [보고서 계약](design/case-report-api.md) |
| OPS-01 | 운영 배포 | 승인된 환경, HTTPS, 시크릿 관리, 백업 복원, 모니터링·접근 정책 검증 | 미구현 |
| VALID-01 | 실무 적합성 | 승인된 자료로 현업 담당자가 정답·오류 사례·처리 흐름 확인 | 대기 / [자료 준비 양식](validation/materials-checklist.md) |


## 검증 근거

- [2026-09-18 개발 MVP 마무리](validation/mvp-completion-2026-09-18.md): 백엔드131·프론트31·Python35 테스트, 실제 로컬 모델 합성35건 및 업무·종료 회귀 통과.
- [2026-09-18 원문 재검토 검증](validation/source-revalidation-2026-09-18.md): 백엔드 129개, 프론트 28개(계산 2개·브라우저 26개), 원문 변경·영향 없음 확인·초안 철회 흐름 통과.
- [2026-09-17 원본·수정 이력 검증](validation/source-history-2026-09-17.md): 백엔드 124개, 프론트 27개(계산 2개·브라우저 25개), 실제 로컬 AI 합성 업무 회귀 및 CI 통과.
- Python AI 계약·평가 테스트 35개와 실제 모델 합성 사례 35건은 서로 다른 검증이다. [AI 평가 결과와 한계](validation/ai-evaluation-expansion-2026-09-16.md)를 따른다.
- 현재 문서 수정 과정에서 애플리케이션 테스트를 새로 실행했다는 의미는 아니다. 테스트 소스는 [백엔드](../backend/src/test/java/com/wecall/), [프론트](../frontend/e2e/), [AI](../ai/tests/)에 있다.
- 합성 업무 검수는 [인수 진행표](validation/acceptance-guide.md)를 따르며 실제 담당자 인수는 별도 대기다.

## 현재 한계와 다음 보완

PDF 원본·원문 버전을 보존하고 오래된 AI 결과 전환은 차단한다. 기존 조건의 재검토·초안 철회와 종료 차단을 구현했다. 과거 버전 미기록 조건은 임의 연결하지 않는다. AI는 제조번호·소비기한의 제한된 조건을 다루며 원문 6,000바이트·80줄 초과, 복잡한 예외·상품별 조건은 수동 검토가 필요하다. ClamAV는 어댑터만 검증했으며 실제 엔진 운용은 별도다.

원문 재검토·분석 오류 안내·입력 검사와 합성 대표 유형 검증을 완료했다. 개발 MVP는 마무리 가능하며 실제 자료 인수·운영 준비는 별도다. 선택적 후속 기능과 완료 기준은 [보완 설계](design/product-roadmap.md)를 따른다.

다중 기업 격리, ERP·이메일 자동 연동, 외부 판매중지·고객 자동 발송, 복잡한 세트 추적, 실제 회수 수량 원장, OCR, 감사 기록 변조 방지 저장소, 운영 SLA는 현재 제공하지 않는다. 과거 조건·판정을 자동 취소하거나 작업 완료를 실제 회수 완료 수량으로 바꾸지 않는다.

개발 통합·기본 브랜치는 develop이다. main 반영과 배포는 별도 요청 범위다.

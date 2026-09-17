# 텍스트 PDF 원문 등록 검증 — 2026-09-17

- 구현 커밋: d424db5.
- 백엔드 테스트 119개 통과, bootJar 생성 성공. 첫 실행의 Docker 미연결은 Docker 시작 후 전체 재실행으로 해소했다.
- 프론트 타입 검사·배포 빌드 성공, 전체 테스트 27개 통과(계산 2개·브라우저 25개).
- 첫 브라우저 실행에서 직접 입력 사건까지 AI 탭으로 이동해 기존 조건 작성 흐름이 끊기는 회귀를 발견했다. PDF 적용 사건만 AI 탭으로 이동하도록 수정 후 전체 재실행 통과.
- [GitHub CI 35171227597](https://github.com/xixvivji/WeCall/actions/runs/35171227597) 성공: Python AI 계약 테스트 35개, 백엔드·프론트·브라우저·합성 HTTP 업무 검증.
- 실제 로컬 모델 기반 기존 업무 회귀도 통과: `demo_recall.py --with-live-extraction --with-evidence --with-tasks`. 결과는 Git 제외 경로 `validation-results/pdf-intake-ai-regression.json`에 저장했다.

PDF 추출 API는 합성 PDF로 실제 PDFBox 파싱을 검증했다. 브라우저의 PDF 응답은 합성 계약 응답이며, 실제 모델 회귀는 기존 합성 텍스트 입력이다. 따라서 실제 회사 PDF의 문서 추출 품질이나 PDF부터 모델까지 한 번에 수행한 실문서 검증을 의미하지 않는다. 회사 문서·외부 모델 API는 사용하지 않았다.

범위와 제한은 [PDF 원문 설계](../design/source-pdf.md)를 참조한다. OCR, 원본 보관·수정 이력, 실제 ClamAV 엔진 운용, 배포는 포함하지 않았다.

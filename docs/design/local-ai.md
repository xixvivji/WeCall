# 로컬 AI 조건 추출

2026-09-16. 사용자 선택에 따라 문서 추론은 로컬 모델로 시작한다. FastAPI `ollama-local` provider와 Vue의 사건 → **AI 조건 추출** 탭을 구현했다. 기본 provider는 계속 `disabled`이고 아래 실행 도구에서 명시적으로 활성화한다.

## 실행

최초 도구·모델 설치에는 인터넷 다운로드가 필요하다. 문서 추론과 구분한다. macOS 개발 환경:

```sh
brew install ollama
# .env에 두 서버가 공유할 WECALL_AI_SERVICE_TOKEN 설정 (출력·Git 저장 금지)
python3 scripts/run_local_ai.py ollama
# 다른 터미널: 모델 설치
ollama pull qwen3:4b-instruct
# 다른 터미널
python3 scripts/run_local_ai.py api
# 다른 터미널: 기존 8080 서버를 종료한 후 실행
./backend/gradlew -p backend bootJar
python3 scripts/run_local_ai.py backend
# 다른 터미널
cd frontend
npm run dev
```

`run_local_ai.py`는 저장소 `.env`를 읽고 서버 주소를 `127.0.0.1`로 고정한다. Ollama는 `OLLAMA_NO_CLOUD=1`, 요청 본문 로깅 비활성, 모델1개·병렬1개로 실행한다. FastAPI는 worker1개와 access log 비활성으로 실행한다. 기존 서버가 점유한 포트를 자동으로 종료하거나 우회하지 않는다. 백엔드는 실행 중 재빌드 충돌을 피하기 위해 JAR를 임시 경로에 복사해 실행한다.

## 화면 흐름

1. 검토자가 사건 원문을 등록하고 AI 조건 추출에서 분석을 요청한다.
2. 서버 작업을 2초 간격으로 조회한다. 조회 실패 시 재시도하며 다른 화면으로 이동해도 서버 작업은 유지된다.
3. 실제/모의 응답 표시, 원문 근거, 조건 구조와 주의사항을 확인한다.
4. **조건과 상품 연결 검토**에서 데이터 버전·상품 연결·근거·조건·검토 사유를 사람이 작성한다. AI는 상품 ID를 선택하지 않는다.
5. 저장하면 기존 `/extractions/{id}/condition` API로 검토 이력과 DRAFT 조건이 함께 생성된다. 별도 조건 승인 전에는 판정하지 않는다.
6. 실패·부적합 분석은 사유를 남겨 기각한다. 미검토 분석은 종료 차단 항목이며 다음 할 일에서 AI 탭으로 연결한다.

실행 담당자는 결과 조회만 가능하다. 작성 중 검토 내용에는 기존 미저장 입력 보호를 적용한다.

## 모델·지원 범위

- 모델: `qwen3:4b-instruct`, Ollama 로컬 GGUF. 모델명은 코드에서 고정하며 문서·요청에서 바꾸지 못한다.
- 입력: 원문 UTF-8 **6,000바이트, 80줄 이하**. 초과 시 잘라 읽지 않고 422로 거절한다. PDF/이미지 OCR·RAG·상품 자동 매칭은 포함하지 않는다.
- 출력: 제조번호 EQ/IN, 소비기한 EQ/IN/양 끝 포함 BETWEEN, 단순 AND 또는 OR 결합. 중첩된 혼합 논리와 상품별 다른 규칙은 수동 검토 대상이다. 이 의미적 판단은 모델이 잘못할 수 있으므로 사람 검토가 필수다.
- 보수적 사전 차단: 제외·미만·초과·이상·이하·이전·이후 등의 표현과 일부 연도 미확인 표현은 조건 누락을 피하려고 문서 전체에서 차단한다. 상품 설명이나 일반 문장에 등장해도 거절될 수 있다. 모든 부정 표현을 탐지한다는 뜻은 아니다.
- 모델은 원문 근거의 시작/끝 줄 번호만 선택한다. 서버가 원본 문자열을 그대로 연결해 인용문을 만든다. 모델이 근거를 다시 쓰지 않는다.
- 값의 근거 내 존재, 날짜 형식·실제 달력 날짜·범위 순서, 연산자와 값 개수, 완료 응답, 크기를 검증한다. 인용이 실제 존재해도 AND/OR 의미·조건 누락까지 정확하다는 보장은 없다.
- temperature0, seed42, context12288, 출력 최대1536토큰. 공급 모델·버전·하드웨어에 따라 결과는 바뀔 수 있다.

## 연결·실패 경계

`Spring → 127.0.0.1:8000 FastAPI → 127.0.0.1:11434 Ollama → 로컬 모델`

FastAPI는 환경 프록시와 HTTP 리다이렉트를 사용하지 않는다. 문서의 URL을 따라가지 않고 도구 호출을 제공하지 않는다. 모델 `/api/show` 응답에 원격 모델 정보가 있거나 로컬 GGUF 정보가 없으면 문서 전송 전에 차단한다. 외부 모델로 자동 대체하지 않는다. 실행 도구는 Ollama 클라우드 기능도 끈다.

이것은 앱의 로컬 연결 구성이다. OS 방화벽·완전 격리망·다른 프로세스의 통신·운영 백업까지 통제하는 구현은 아니다. 관리자가 다른 실행 방법으로 서버/코드를 바꾸면 동일 경계를 보장하지 않는다. 모델 파일·도구 다운로드는 인터넷을 사용했다.

FastAPI 전체 요청 제한90초, 모델 HTTP read80초·connect2초, 응답64KiB. 프로세스당 동시 추론1개, 추가 요청503. 중단 시 소켓을 닫지만 Ollama 내부 실행 자원을 즉시 회수한다고 보장하지 않는다. Spring 로컬 실행 설정은 read100초이며 워커의 중단 복구2분보다 짧다. 기본 백엔드 read10초는 그대로 유지하고 `WECALL_AI_READ_TIMEOUT_MS`로 설정한다.

422는 수동 검토 또는 입력 한도 초과, 502는 잘못된 출력, 503은 실행 불가·사용 중, 504는 추론 시간 초과다. v1 Spring은 422를 AI_REJECTED_INPUT, 나머지 HTTP 오류를 AI_UNAVAILABLE로 저장하므로 화면은 상세 원인을 확정하지 않는다. FastAPI 오류 본문에는 문서·모델 응답을 담지 않는다. 성공 원문·결과는 기존 계약에 따라 PostgreSQL에 보존된다.

## 검증과 평가 재실행

```sh
cd ai
uv sync --locked
uv run pytest -q
uv run python evaluate_local.py
```

정상 입력5개와 수동 검토4개의 작은 합성 개발 세트다. 일부 거절은 모델 추론 전에 규칙으로 차단하므로 모델 단독 정확도와 구분한다. 개발 중 이 세트로 프롬프트를 조정했으며 독립적인 실무 평가 세트가 아니다. 모델 평가 결과는 Git 제외 `validation-results/local-ai-evaluation.json`에 기록된다. 기대 조건과 추출 조건의 값·경계·누락값 조합에서 판정 일치를 검사한다.

실제 전체 호출은 저장소 루트에서 계정 환경 변수를 준비한 뒤:

```sh
python3 scripts/demo_recall.py --with-live-extraction --with-evidence --with-tasks
```

합성 자료만 생성하며 LIVE/ollama-local 여부, 초안 검토 전환, 별도 승인, 행별 정답과 합계, 원본 보존과 보고서를 검증한다. 스크립트는 합성 검토를 자동으로 진행하므로 실제 현업 승인으로 해석하지 않는다. 기존 `--with-extraction`은 여전히 MOCK 전용이다.

CI는 모델을 다운로드하지 않고 Python의 인증·출력 검증·로컬 주소·원격 모델 차단·리다이렉트·시간 제한·동시 호출 테스트와 UI 계약·기존 업무 검증을 실행한다. 실제 모델 평가는 위 명령으로 별도 실행한다.

공식 근거: [Ollama 로컬·클라우드 설정](https://docs.ollama.com/faq), [구조화 출력](https://docs.ollama.com/capabilities/structured-outputs), [채팅 API](https://docs.ollama.com/api/chat), [Qwen3 4B Instruct 모델·라이선스](https://ollama.com/library/qwen3:4b-instruct).

[2026-09-16 실제 모델·업무 검증 결과](../validation/local-ai-2026-09-16.md)를 참고한다.

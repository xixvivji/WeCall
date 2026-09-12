# WeCall B2B

식품 유통사의 회수 요청 분석·영향 추적·대응 관리 서비스.
PostgreSQL 기반 CSV 등록, 회수 사건·조건 등록·승인, 대상 판정과 영향 조회를 구현했습니다. 입고 증거 텍스트 등록·승인·누락값 보완·재판정까지 구현했습니다. 담당자 배정·대응 작업·텍스트 처리 증빙·완료/재개 이력도 구현했습니다. 사건 종료 점검·승인·재개와 당시 점검 결과 보존도 구현했습니다. 세션 로그인과 검토자·실행 담당자 권한도 적용했습니다. FastAPI 조건 추출 요청·결과 저장·검토용 조건 초안 전환을 구현했습니다. 실제 AI 모델과 OCR은 아직 연결하지 않았습니다.

## 프로젝트 문서

- [프로젝트 개요와 개발 순서](docs/overview.md)
- [통합 기획안 원문 (2026-09-09)](docs/product-plan-2026-09-09.md)

## 협업 규칙

[Git Flow·커밋 메시지 규칙](CONTRIBUTING.md)을 따릅니다. 개발 통합 브랜치는 `develop`, 커밋 형식은 `feat : 한글 내용`입니다.

## 구성

- `backend/`: Java 21, Spring Boot 3.5.16, Gradle Wrapper 8.7
- `ai/`: Python 3.12, FastAPI, uv (의존성 버전은 uv.lock으로 고정)
- DB: PostgreSQL 17.6 · Flyway · Spring JDBC
- `frontend/`: Vue 3 · TypeScript · Vite
- AI 모델, 파일 저장소, 배포 환경: 추후 결정

Spring Boot는 업무 로직·조건 판정·승인·데이터 저장을 맡고, FastAPI는 문서 해석·조건 추출·상품 후보 비교를 담당할 예정입니다. 두 서버 간 조건 추출 호출은 연결했으며, 명시적으로 활성화한 합성 모의 응답으로 검증합니다.

## 설계·샘플 데이터

- [논리 ERD 초안](docs/design/erd.md)
- [회수 사건·조건 승인·판정 API](docs/design/recall-api.md)
- [입고 증거 보완·재판정 API](docs/design/evidence-api.md)
- [대응 작업·처리 증빙 API](docs/design/task-api.md)
- [사건 종료 점검·승인·재개 API](docs/design/closure-api.md)
- [FastAPI 조건 추출 연동·모의 시연](docs/design/ai-extraction-api.md)
- [Vue 업무 화면·실행 방법·조회 API](docs/design/frontend-workspace.md)
- [로그인·계정·역할별 권한](docs/design/auth-api.md)
- [합성 회수 사건·CSV·정답표](samples/recall-001/README.md)
- 샘플 검증: `python3 scripts/validate_sample.py`

[CSV 등록 API·DB 실행 가이드](docs/design/csv-import.md)에 요청 예시와 입력 제약을 정리했습니다.

## 로컬 실행

Java 21, Docker와 uv가 필요합니다. 첫 실행 전 [계정 설정](docs/design/auth-api.md)을 따라 `.env`를 준비하세요. Gradle은 별도 설치하지 않아도 됩니다.

### 백엔드

```sh
set -a
source .env
set +a
docker compose up -d postgres
cd backend
./gradlew bootRun
```

상태 확인: http://localhost:8080/actuator/health

### AI 서버 (별도 터미널)

```sh
set -a
source .env
set +a
cd ai
uv sync --locked
uv run uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

상태 확인: http://localhost:8000/health

API 문서: http://localhost:8000/docs

uv가 필요한 Python 3.12 환경을 준비합니다. 최초 실행 시 인터넷 연결이 필요합니다.

### 프론트엔드 (별도 터미널)

Node.js 24를 사용합니다. 백엔드 실행 후 저장소 루트에서:

```sh
cd frontend
npm ci
npm run dev
```

`http://127.0.0.1:5173/`에서 기존 계정으로 로그인합니다. 사건 검색·등록, CSV 업로드, 조건 작성·승인, 영향 조회, 대응 작업과 종료 점검을 사용할 수 있습니다. 입고 증거·AI 검토·계정 생성의 전용 화면은 아직 없습니다.

## 회수 흐름 시연

백엔드 실행 후 별도 터미널의 저장소 루트에서 `python3 scripts/demo_recall.py`를 실행하면 합성 사건을 새로 등록·승인·판정하고 정답 합계를 검사합니다. 증거 승인 전후까지 확인하려면 `python3 scripts/demo_recall.py --with-evidence`를 사용합니다. `--with-tasks`를 추가하면 담당자 배정·증빙 검토·작업 완료까지 시연합니다.

종료 차단 및 종료·재개 시연은 `python3 scripts/demo_closure.py`로 실행합니다. 두 종류의 별도 합성 사건을 생성합니다.

## 검증

```sh
cd backend
./gradlew test bootJar
```

```sh
cd ai
uv run --locked pytest
```

인증·CSRF는 적용되어 있습니다. 운영 배포 설정은 아직 없으며 현재는 로컬 개발용입니다.
API 키와 비밀번호는 Git에 올리지 않습니다. `.env` 파일은 Git에서 제외됩니다.

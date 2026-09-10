# WeCall B2B

식품 유통사의 회수 요청 분석·영향 추적·대응 관리 서비스.
PostgreSQL 기반 CSV 등록, 회수 사건·조건 등록·승인, 대상 판정과 영향 조회를 구현했습니다. AI 모델 연결과 증거 보완·작업 관리는 아직 구현하지 않았습니다.

## 프로젝트 문서

- [프로젝트 개요와 개발 순서](docs/overview.md)
- [통합 기획안 원문 (2026-09-09)](docs/product-plan-2026-09-09.md)

## 구성

- `backend/`: Java 21, Spring Boot 3.5.16, Gradle Wrapper 8.7
- `ai/`: Python 3.12, FastAPI, uv (의존성 버전은 uv.lock으로 고정)
- DB: PostgreSQL 17.6 · Flyway · Spring JDBC
- 프론트엔드, AI 모델, 파일 저장소, 배포 환경: 추후 결정

Spring Boot는 업무 로직·조건 판정·승인·데이터 저장을 맡고, FastAPI는 문서 해석·조건 추출·상품 후보 비교를 담당할 예정입니다. 두 서버 간 호출은 아직 연결하지 않았습니다.

## 설계·샘플 데이터

- [논리 ERD 초안](docs/design/erd.md)
- [회수 사건·조건 승인·판정 API](docs/design/recall-api.md)
- [합성 회수 사건·CSV·정답표](samples/recall-001/README.md)
- 샘플 검증: `python3 scripts/validate_sample.py`

[CSV 등록 API·DB 실행 가이드](docs/design/csv-import.md)에 요청 예시와 입력 제약을 정리했습니다.

## 로컬 실행

Java 21, Docker와 uv가 필요합니다. Gradle은 별도 설치하지 않아도 됩니다.

### 백엔드

```sh
docker compose up -d postgres
cd backend
./gradlew bootRun
```

상태 확인: http://localhost:8080/actuator/health

### AI 서버 (별도 터미널)

```sh
cd ai
uv sync --locked
uv run uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

상태 확인: http://localhost:8000/health

API 문서: http://localhost:8000/docs

uv가 필요한 Python 3.12 환경을 준비합니다. 최초 실행 시 인터넷 연결이 필요합니다.

## 회수 흐름 시연

백엔드 실행 후 별도 터미널의 저장소 루트에서 `python3 scripts/demo_recall.py`를 실행하면 합성 사건을 새로 등록·승인·판정하고 정답 합계를 검사합니다.

## 검증

```sh
cd backend
./gradlew test bootJar
```

```sh
cd ai
uv run --locked pytest
```

인증과 운영 배포 설정은 아직 없습니다. 현재 상태는 로컬 개발용입니다.
API 키와 비밀번호는 Git에 올리지 않습니다. `.env` 파일은 Git에서 제외됩니다.

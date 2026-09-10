# WeCall B2B

식품 유통사의 회수 요청 분석·영향 추적·대응 관리 서비스.
현재는 서버 실행과 상태 확인이 가능한 기초 프로젝트입니다. 업무 기능과 AI 모델 연결은 아직 구현하지 않았습니다.

## 프로젝트 문서

- [프로젝트 개요와 개발 순서](docs/overview.md)
- [통합 기획안 원문 (2026-09-09)](docs/product-plan-2026-09-09.md)

## 구성

- `backend/`: Java 21, Spring Boot 3.5.16, Gradle Wrapper 8.7
- `ai/`: Python 3.12, FastAPI, uv (의존성 버전은 uv.lock으로 고정)
- 프론트엔드, DB, AI 모델, 저장소, 배포 환경: 추후 결정

Spring Boot는 업무 로직·조건 판정·승인·데이터 저장을 맡고, FastAPI는 문서 해석·조건 추출·상품 후보 비교를 담당할 예정입니다. 두 서버 간 호출은 아직 연결하지 않았습니다.

## 설계·샘플 데이터

- [논리 ERD 초안](docs/design/erd.md)
- [합성 회수 사건·CSV·정답표](samples/recall-001/README.md)
- 샘플 검증: `python3 scripts/validate_sample.py`

DB와 업무 API는 아직 구현 전이며, 설계·정답표를 개발 기준으로 추가했습니다.

## 로컬 실행

Java 21과 uv가 필요합니다. Gradle은 별도 설치하지 않아도 됩니다.

### 백엔드

```sh
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

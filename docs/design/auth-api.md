# 세션 로그인·역할별 권한

## 현재 방식

Spring Security 세션 인증을 사용한다. 비밀번호는 BCrypt 해시로 PostgreSQL app_user에 저장하고, 클라이언트는 HttpOnly JSESSIONID 쿠키로 로그인 상태를 유지한다. 로그인 시 세션 ID가 교체되고 로그아웃 시 세션이 무효화된다.
세션 유휴 시간은 30분, SameSite=Lax다. 세션은 현재 단일 서버 메모리에 있으므로 서버 재시작 시 다시 로그인해야 한다.

## 로컬 계정 준비

실제 비밀번호는 `.env`에만 보관하며 Git에서 제외한다. `.env.example`은 비밀번호가 비어 있는 예시다.
이번 로컬 작업에서는 임의의 강한 비밀번호로 demo-reviewer / demo-operator 계정을 준비했다. 비밀번호는 이 머신의 `.env`에서 확인한다.
새 환경에서는 `.env.example`을 `.env`로 복사해 비밀번호를 채운다. 평문 비밀번호를 커밋하거나 명령행 인자로 전달하지 않는다.

저장소 루트에서:

```sh
set -a
source .env
set +a
docker compose up -d postgres
cd backend
./gradlew bootRun
```

초기 계정은 아래 환경변수가 있을 때만 생성한다. 기본 비밀번호나 공개 회원가입은 없다.

- WECALL_BOOTSTRAP_USERNAME / WECALL_BOOTSTRAP_PASSWORD: 최초 검토자.
- WECALL_BOOTSTRAP_OPERATOR_USERNAME / WECALL_BOOTSTRAP_OPERATOR_PASSWORD: 선택적 최초 실행자.
- 이미 존재하는 계정의 비밀번호·역할은 시작 시 덮어쓰지 않는다. `.env` 값 변경은 기존 계정의 비밀번호 변경이 아니다.

시연 스크립트는 WECALL_USERNAME / WECALL_PASSWORD로 실제 로그인한다. 기본 샘플 담당자는 demo-operator이므로 해당 계정도 준비한다. 별도 터미널에서도 `.env` 환경변수를 로드해야 한다.

```sh
python3 scripts/demo_recall.py --with-evidence --with-tasks
python3 scripts/demo_closure.py
```

## 인증 API와 CSRF

| 요청 | 기능 |
| --- | --- |
| GET /api/auth/csrf | 익명/로그인 상태의 CSRF 토큰과 headerName 발급 |
| POST /api/auth/login | application/x-www-form-urlencoded의 username, password로 로그인 |
| GET /api/auth/me | 로그인 username과 roles 조회 |
| POST /api/auth/logout | 세션 무효화, 204 |
| GET /api/users | 검토자 전용 사용자 목록 (비밀번호·해시 제외) |
| POST /api/users | 검토자 전용 계정 생성, 201 |

먼저 CSRF API에서 쿠키와 `{token, headerName}`을 받는다. 로그인 요청에도 해당 헤더와 쿠키를 보낸다. 로그인 성공 후 토큰이 교체되므로 **CSRF API를 다시 호출**한다. 이후 모든 POST(업로드·로그아웃 포함)에 최신 헤더와 세션 쿠키를 함께 보낸다.
기본 headerName은 X-CSRF-TOKEN이다. 로그인 JSON이 아닌 form 요청을 사용한다. HTTP Basic/JWT는 이번 구현에 포함하지 않는다.

시연 클라이언트 `scripts/api_client.py`가 쿠키 저장·로그인·토큰 재발급·로그아웃을 처리한다.

- 401: 로그인 필요, 계정·비밀번호 불일치, 비활성 계정 로그인.
- 403: 역할 부족, 타인의 작업 실행, CSRF 누락/불일치. 미인증 POST도 CSRF 필터가 먼저 403을 반환할 수 있다.
- 400: 잘못된 계정 양식 또는 존재하지 않는 담당자.
- 409: 중복 username.
- health 조회는 공개이며 업무 데이터는 인증이 필요하다.

## 역할

| 업무 | REVIEWER | OPERATOR |
| --- | --- | --- |
| 사건·판정·작업 조회 | 가능 | 가능 |
| CSV 등록·사건·조건 생성 | 가능 | 불가 |
| 조건·입고 증거 승인/거절·판정 실행 | 가능 | 불가 |
| 입고 증거 제안 등록 | 가능 | 가능 |
| 작업 생성·배정 | 가능 | 불가 |
| 작업 시작·처리 증빙 등록 | 모든 작업 | 본인에게 배정된 작업 |
| 증빙 검토·완료·재개·취소 | 가능 | 불가 |
| 사건 종료·재개 | 가능 | 불가 |
| 계정 목록·생성 | 가능 | 불가 |

현재 단일 기업 MVP이며 두 역할 모두 해당 기업의 업무 데이터를 조회한다. 사용자별 조회 격리와 다중 기업 격리는 아직 없다.
본인 배정 검사는 작업 행 잠금 아래 수행해 배정 변경과 실행 요청의 경합을 방지한다.

## 계정 생성·담당자·이력

POST /api/users 예시:

```json
{
  "username": "logistics-01",
  "displayName": "물류 담당",
  "password": "로컬에서 생성한 강한 비밀번호로 대체",
  "role": "OPERATOR"
}
```

username은 영문 소문자로 시작하고 소문자·숫자·점·밑줄·하이픈을 사용하는 3~64자다. displayName은 200자 이하, 비밀번호는 12자 이상·UTF-8 기준 72바이트 이하, 역할은 REVIEWER/OPERATOR다.
username 변경·계정 삭제·역할 변경·비밀번호 변경 API는 아직 없다. 비활성 플래그는 로그인 시 검사하지만 현재 별도 관리 API와 기존 세션 강제 회수 기능은 없다.

작업 assignee에는 표시 이름 대신 **활성 계정 username**을 지정한다. 검토자 계정도 배정할 수 있다.
actor/reviewer 입력 필드는 하위 호환용으로만 남아 있으며 생략 가능하다. 전달한 이름은 권한과 저장 행위자 결정에 사용하지 않고 로그인 username으로 덮어쓴다. 예를 들어 조건 승인 요청은 이제 `{}`로 충분하다.
actor/reviewer를 보낼 경우 기존 형식 검사(공백 금지·200자 이하)는 적용된다. note와 확인 체크 등 실제 검토 내용은 계속 필수다.
과거에 입력 라벨로 저장된 이력은 소급 변환하지 않는다. 계정명은 변경하지 않는 식별자로 사용하며 신규 HTTP 승인·작업 이력에 기록한다.

## 검증과 남은 운영 범위

60개 테스트에서 기존 업무 흐름에 인증·CSRF를 적용하고, 실제 로그인·세션 교체·로그아웃·권한·담당 작업 제한·행위자 위조 방지·해시 저장을 검증했다.
모의 사용자로 업무 로직을 검증하는 테스트와 DB 계정/비밀번호로 실제 로그인하는 보안 테스트를 분리했다.

로컬 기본 바인딩은 127.0.0.1이다. HTTPS 배포 시 SESSION_COOKIE_SECURE=true를 사용한다. 배포용 TLS, 로그인 시도 제한, 계정 복구, 세션 공유·강제 회수, 프론트 CORS/배포 도메인 설정은 별도 작업이다.

## 조건 추출 권한

분석 이력 GET은 두 역할에 허용한다. 분석 요청·조건 초안 전환·기각 POST는 REVIEWER만 허용하고 검토자 신원은 로그인 계정으로 기록한다.

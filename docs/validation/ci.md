# CI 자동 검증

[워크플로](../../.github/workflows/ci.yml)는 develop 및 feature/chore/release/hotfix 브랜치 푸시, develop/main 대상 PR, 수동 실행 시 동작한다. 배포 단계는 없다. 검사 실패 시 병합하지 않는 것이 협업 규칙이며, GitHub 브랜치 보호를 통한 강제 차단은 아직 설정하지 않았다.

## 실행 범위

1. 합성 CSV 참조·수량·수기 정답표 검사 및 파일 정리 도구 단위 테스트.
2. Java 21 / Gradle Wrapper 8.7로 백엔드 전체 테스트와 JAR 빌드. 통합 테스트는 Testcontainers PostgreSQL 17.6을 사용한다.
3. Node 22에서 `npm ci`, Vue 타입 검사·빌드, Chromium 설치.
4. 별도의 빈 PostgreSQL 서비스 DB에서 Spring과 Vite를 시작하고 준비 상태를 확인한다.
5. 프론트 16개 테스트(비교 로직 2개·브라우저 14개), HTTP 판정·증거·작업 시나리오와 종료·재개 시나리오 실행.
6. 성공·실패 시 JUnit XML과 합성 시나리오 JSON 결과를 7일 보관한다. 원본 첨부, 서버 로그, 브라우저 로그인 화면과 trace는 업로드하지 않는다.

CI의 고정 비밀번호는 매 실행 후 폐기되는 테스트 DB·합성 계정용이다. 실제 계정 비밀번호나 회사 문서는 GitHub Secrets 또는 테스트 자료로 연결하지 않는다. 실제 AI 서비스가 없어도 검증되며 외부 모델은 호출하지 않는다. 업무 DB·첨부 디렉터리는 각 GitHub 실행 VM 안에서만 생성된다.

## 확인과 재현

GitHub 저장소 **Actions → CI → Backend and business workflow**에서 실패 단계를 확인한다. `verification-reports`에 백엔드·브라우저 테스트 결과와 `recall.json`, `closure.json`이 있다. 실패 이전 단계에서 생성되지 않은 보고서는 없을 수 있다.

로컬 검증은 [시연 문서](demo-scenarios.md)의 명령을 사용한다. [CI 실행 스크립트](../../scripts/ci/business-workflow.sh)는 별도 빈 테스트 DB와 환경 변수로 구성한 환경 전용이다. 8080·5173에 서버가 있으면 기존 서버를 건드리지 않고 중단한다. 스크립트가 시작한 프로세스는 종료 시 정리한다. 개인 `.env`가 없어도 브라우저 테스트를 실행할 수 있으며 환경 변수가 우선한다.

현재 브라우저 묶음은 한 worker에서 순서대로 실행한다. 대시보드 테스트는 앞선 업무 흐름 테스트가 만든 작업을 조회하므로 전체 묶음으로 실행한다. 테스트 병렬화나 개별 실행 독립성 개선은 추후 작업이다.

구성 참고: [GitHub PostgreSQL 서비스 문서](https://docs.github.com/en/actions/tutorials/use-containerized-services/create-postgresql-service-containers), [Playwright CI 문서](https://playwright.dev/docs/ci).

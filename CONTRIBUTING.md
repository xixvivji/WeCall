# Git 협업 규칙

## 브랜치 전략: Git Flow

- `main`: 배포 기준 브랜치. 일반 기능 작업을 직접 커밋하지 않는다.
- `develop`: 다음 개발 버전의 통합 브랜치.
- `feature/<작업명>`: develop에서 분기하고 완료 후 develop으로 병합.
- `chore/<작업명>`: 문서·설정 등 유지보수 작업. develop에서 분기하고 develop으로 병합.
- `release/<버전>`: develop에서 분기해 출시 준비. 완료 후 main과 develop 모두에 반영하고 main에 버전 태그를 붙인다.
- `hotfix/<작업명>`: main에서 분기해 배포 오류 수정. 완료 후 main과 develop 모두에 반영한다. 진행 중 release가 있으면 해당 브랜치에도 수정이 누락되지 않게 한다.

작업명은 소문자 영문과 하이픈을 사용한다. 브랜치 병합은 기본적으로 `--no-ff`를 사용해 작업 단위를 남긴다.
검증과 diff 확인 후 병합하며, release/hotfix의 main 반영과 배포는 별도 요청된 범위에서 진행한다.
원격 기본 브랜치·보호 규칙 변경은 이번 규칙 도입에 포함하지 않는다.

## 커밋 메시지

형식은 **`타입 : 한글 내용`**이다. 콜론 양쪽에 공백을 한 칸씩 넣는다.

```text
feat : 회수 사건 등록 API 구현
fix : 제조번호 누락 시 대상 판정 오류 수정
docs : 회수 API 실행 방법 정리
refactor : 조건 판정 로직 분리
test : 출고 영향 조회 테스트 추가
chore : 개발 환경 설정 변경
```

필요하면 `build`, `ci`, `perf`, `style`, `revert`를 사용하되 같은 형식을 유지한다.
본문은 필요한 경우 빈 줄 뒤에 변경 이유와 검증 내용을 한글로 작성한다.
병합 커밋에도 `chore : 회수 사건 기능을 develop에 병합`처럼 같은 규칙을 적용한다.
기존에 공유한 커밋 메시지는 소급 수정하거나 강제 푸시하지 않는다.

## 일반 작업 순서

```sh
git switch develop
git pull --ff-only origin develop
git switch -c feature/example
# 구현 및 관련 검증
git add <변경한 파일>
git commit -m "feat : 기능 설명"
git push -u origin feature/example
```

완료한 작업을 확인한 뒤 develop에 병합한다. PR을 사용하는 경우 대상은 develop으로 지정한다.
main 직접 커밋, 공유 브랜치 이력 재작성, 요청하지 않은 배포는 하지 않는다.

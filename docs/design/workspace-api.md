# 업무 대시보드와 작업 조회

`/#/workspace`에서 두 역할 모두 대시보드와 내 할 일을 조회한다. 기존 사건 목록은 `/`에 유지한다.

## 집계

`GET /api/v1/workspace/summary`는 진행 중 사건, 전체 미완료 작업, 본인의 미완료 작업, 확인 필요 사건, 미판정 사건 건수를 반환한다. 미완료는 OPEN 또는 IN_PROGRESS이며 취소·완료는 제외한다.

확인 필요 사건은 진행 중 사건마다 가장 최근 실행한 판정(created_at DESC, id DESC) 하나에서 재고 또는 출고 needsReview 수량이 양수인 사건이다. 과거 판정을 중복 집계하지 않는다. 판정 없는 사건은 unassessedCases로 분리한다. 서로 겹칠 수 있는 사건의 재고·출고 수량은 합산하지 않는다. 작업 상태는 대상 판정과 별개다.

## 작업 목록

`GET /api/v1/workspace/tasks`:

- scope: mine(기본), all, reassign. mine은 서버의 로그인 계정을 사용한다. all/reassign은 REVIEWER만 허용한다.
- status: ACTIVE(기본), ALL, OPEN, IN_PROGRESS, COMPLETED, CANCELLED.
- q: 사건명 또는 작업명의 대소문자 구분 없는 부분 검색, 최대 200자. SQL 와일드카드로 해석하지 않는다.
- page: 0부터, 최대 100000. size: 기본 20, 최대 100.
- 응답: items, totalElements, page, size, totalPages. 수정 시각·ID 역순이며 한 요청의 개수와 목록은 같은 DB 스냅샷을 사용한다.

reassign은 미배정 또는 비활성·존재하지 않는 계정에 배정된 미완료 작업이다. 상태 필터와 함께 적용한다. 바로가기는 `/recalls/{caseId}?task={taskId}`로 사건의 대응 작업 상세를 열며 기존 배정·증빙·상태 API 및 권한 검사를 사용한다. 조회가 자동 재배정이나 작업 상태 변경을 수행하지 않는다.

집계는 요청 시 조회하며 자동 갱신·실시간 알림은 없다. 작업 조회 버튼과 화면 재진입으로 갱신한다. 실행 담당자의 다른 사건 조회 권한은 기존 정책을 유지하되 전체 작업 관리 목록은 검토자만 제공한다.

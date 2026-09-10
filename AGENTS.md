# WeCall 작업 규칙

- Git 전략과 커밋 규칙은 `CONTRIBUTING.md`를 따른다.
- Git Flow 사용: 일반 작업은 최신 develop에서 feature/<작업명> 또는 chore/<작업명>으로 분기한다. 완료된 작업은 검증 후 develop으로 통합한다.
- main에 일반 기능을 직접 커밋하지 않는다. release는 develop에서, hotfix는 main에서 분기한다.
- 커밋 메시지는 반드시 `feat : 한글 내용` 형태로 작성한다. 타입은 작업에 맞게 fix/docs/refactor/test/chore/build/ci/perf/style/revert 등을 사용한다.
- 병합 커밋도 같은 메시지 규칙을 지킨다. 기존 공유 커밋을 메시지 변경 목적으로 재작성하지 않는다.
- 제품의 대상 판정, 상품 연결, 조치 상태를 구분하고 실제 기록이 없는 출고 연결을 추정하여 확정하지 않는다.

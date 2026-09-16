# 로컬 AI 합성 평가 세트

모든 원문은 가상이며 회사 자료를 포함하지 않는다. 정답은 모델 실행 전에 사람이 의도한 업무 의미로 작성한다. 기대 규칙이 null이면 현재 지원 범위에서 초안을 만들면 안 되는 문서다.

| 세트 | 건수 | 용도 |
| --- | ---: | --- |
| development | 9 | cases.json 8개 + 기존 recall-001 원문1개. 초기 프롬프트 개발에 사용 |
| expanded | 18 | OR·목록·날짜 표기·여러 줄·무관한 날짜·상품별 조건·취소·상충 통보·명령문. v1 기준 측정 후 v2 보완에 사용 |
| holdout | 8 | 나머지 세트와 함께 미리 작성하고 v2 보완 후 처음 실행. 별도 표현 점검용 |

holdout도 같은 개발자가 만든 작은 합성 세트이며 독립적인 회사 문서 평가나 통계적 일반화 근거가 아니다. 향후 이 세트의 실패를 보고 수정하면 해당 결과는 회귀 검증으로 취급하고 새 평가 자료를 준비한다. 결과를 맞추기 위해 기대 정답을 모델 출력으로 덮어쓰지 않는다.

## 실행과 결과 보존

Ollama 로컬 서버와 qwen3:4b-instruct가 준비된 상태에서 `ai` 디렉터리에서:

```sh
uv run python evaluate_local.py --suite development
uv run python evaluate_local.py --suite expanded
uv run python evaluate_local.py --suite holdout
```

`--label baseline-v1`처럼 실행 이름을 지정할 수 있다. 기본은 UTC 시각이다. 동일 이름의 기존 결과가 있으면 덮어쓰지 않고 종료한다. 결과는 Git 제외 `validation-results/local-ai-<suite>-<label>.json`에 매 사례마다 기록하며 중단된 실행에는 complete=false가 남는다.

모델 이름, 프롬프트 버전, Git 커밋, provider 파일 SHA-256, 전체 평가 데이터 SHA-256, 시작·종료 시각, 사례별 예상/실제 규칙·근거·시간을 남긴다. 수정 중인 코드도 파일 해시로 구분한다. 모델 태그는 바뀔 수 있으므로 공식 검증 결과에는 Ollama 모델 ID도 함께 기재한다.

## 판정 기준

| 결과 | 의미 |
| --- | --- |
| CORRECT_EXTRACTION | 정상 문서에서 기대 규칙과 판정 결과 일치 |
| CORRECT_REFUSAL | 초안을 만들면 안 되는 문서에 MANUAL_REVIEW_REQUIRED 응답 |
| WRONG_RULE | 정상 문서에서 조건 누락·AND/OR·값·날짜 범위 등이 다른 규칙 반환 |
| UNSAFE_EXTRACTION | 거절해야 하는 문서에서 규칙 반환 |
| OVER_REFUSAL | 정상 문서를 수동 검토로 거절 |
| VALIDATION_REJECTED | 구조·근거 검증 등에서 차단. 정답 거절로 합산하지 않음 |
| OPERATIONAL_ERROR | 모델 미기동·시간 초과·사용 중·로컬 모델 확인 실패. 정확도 통과로 계산하지 않음 |

규칙의 JSON 순서만 비교하지 않는다. 두 규칙에 등장하는 제조번호, 미등장 제조번호, 날짜 경계와 인접 날짜, 값 누락 조합에서 3값 판정을 비교한다. 서버의 실제 판정 구현은 별도 HTTP 업무 검증으로 확인한다.

사전 표현 검사로 거절한 결과는 preflightRefusal=true로 구분한다(이 필드 추가 이전의 baseline/first 실행에는 없을 수 있다). 전체 일치 건수는 모델 단독 정확도가 아니다. API 장애를 올바른 거절로 계산하거나 과도한 거절을 안전성 개선만으로 숨기지 않는다.

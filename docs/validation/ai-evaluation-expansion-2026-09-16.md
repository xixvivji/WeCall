# 로컬 AI 평가 확대 — 2026-09-16

같은 로컬 Qwen3 4B Instruct 모델(0edcdef34593), Ollama0.34.0, Apple Silicon 16GB 환경에서 평가했다. 외부 모델 API나 회사 문서는 사용하지 않았다. 프롬프트/정책은 local-extract-v1에서 v2로 변경했다.

## 수정 전후

| 세트 | 용도 | 일치 | 잘못된 규칙 | 거절 대상에 규칙 생성 | 정상 문서 거절 | 검증/실행 오류 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 확대 v1 | expanded | 12/18 | 1 | 3 | 2 | 0 |
| 확대 v2 최종 | expanded | 18/18 | 0 | 0 | 0 | 0 |
| 별도 8건 첫 실행 | holdout | 8/8 | 0 | 0 | 0 | 0 |
| 기존 개발 회귀 | development | 9/9 | 0 | 0 | 0 | 0 |

최종 세트 합계35건 중 정상 조건19건·수동 검토 대상16건이 기대 결과와 일치했다. 이는 **모델과 사전/사후 검사를 합친 작은 합성 평가 결과**다. 확대18건 중6건은 추론 전 사전 검사에서 거절했다. 모델 단독 정확도100% 또는 실무 문서 정확도를 뜻하지 않는다.

별도8건은 사례/정답을 미리 작성하고 v2 보완 후 처음 실행했다. 이 결과를 보고 기대 정답이나 프롬프트를 다시 수정하지 않았다. 같은 개발자가 만든 합성 자료라는 편향이 있고 외부 독립 평가가 아니다. 실제 회사 자료 검수는 여전히 남아 있다.

## 발견 사항과 수정

| 수정 전 사례 | 관찰 | 보완 |
| --- | --- | --- |
| or-fields | 제조번호 H31 **이거나** 소비기한 2028-06-17을 AND로 반환 | 한국어 OR 예시·지시 보완. 두 필드 사이 마지막 명시 연결어와 결과가 다르면 수동 검토 |
| multiple-products | 서로 다른 상품의 P18/Q19를 하나의 OR 조건으로 합침 | 값이 제시된 제조번호/소비기한 항목이 반복되면 수동 검토. 단순 필드 재언급은 별도 값으로 세지 않음 |
| conflicting-notice | 같은 제조번호를 회수/비회수로 동시에 안내했지만 양성 조건 생성 | 상충·비회수 표현과 반복 항목 차단 |
| instruction-only-new | 회수 사실 없이 출력·자동 승인을 요구하는 글에서 F00 생성 | 명시적 출력/승인 명령 표현 사전 차단 및 프롬프트 구분 강화 |
| date-distractor | 공지일·제조일이 같이 있다는 이유로 명확한 소비기한도 거절 | 주변 날짜는 무시하고 명확한 소비기한만 추출하도록 지시 보완 |
| unrelated-after | “접수 이후 연락”의 이후를 날짜 범위로 오인 | 완전한 날짜 뒤의 이전/이후와 업무 안내를 구분 |

이 보완은 일반적인 한국어 의미 분석이나 완전한 지시문 공격 방어가 아니다. 같은 상품에 대한 반복 설명도 거절할 수 있고, 탐지하지 못하는 부정·상충·다중 상품 표현이 있을 수 있다. 자동 적용·승인을 하지 않고 기존 사람 검토와 별도 승인 흐름을 유지한다.

## 평가 도구 개선

- 정상 추출, 올바른 거절, 잘못된 규칙, 위험한 추출, 과도한 거절, 응답 검증 실패, 운영 오류를 구분한다. 서버 장애를 올바른 거절로 계산하지 않는다.
- 예상/실제 규칙의 날짜 경계·인접 날짜·제조번호·누락값 판정을 비교한다. 논리적으로 같은 IN/OR 표현과 순서 차이는 허용한다.
- 기존 결과 덮어쓰기를 막고 각 실행을 별도 파일에 기록한다. 중단 시 부분 결과와 complete=false를 남긴다.
- 커밋·provider 파일 해시·평가 자료 해시로 수정 중 코드도 구분한다. 명령과 데이터는 [평가 안내](../../ai/evaluation/README.md)를 따른다.

## 실행 증거

Python 계약·평가 도구 테스트35건 통과. 실제 모델 세트별 결과는 아래 파일 이름으로 로컬 Git 제외 경로에 보존했다.

- `validation-results/local-ai-expanded-baseline-v1.json`: complete=true, provider SHA-256 `8cf18980cced5ea4b8c785fed72bdcf604b019296e182fd9e04031c4b7f763e1`, 평가 SHA-256 `d203d2b74ddcc61045a1b665e8e21a827aaece2b83bbb371b0d5ae05be11ed13`
- `validation-results/local-ai-expanded-final-v2.json`: complete=true, provider SHA-256 `82b5b95ec88d2db71a714655d6f9be7416b82ffce61ac754a0f5c39d8cfdb47e`, 평가 SHA-256 `d203d2b74ddcc61045a1b665e8e21a827aaece2b83bbb371b0d5ae05be11ed13`
- `validation-results/local-ai-holdout-first-v2.json`: complete=true, provider SHA-256 `82b5b95ec88d2db71a714655d6f9be7416b82ffce61ac754a0f5c39d8cfdb47e`, 평가 SHA-256 `a15921373549504d9a3eac04fb0b627cef174f60c3356a4c2017304f74eb2c77`
- `validation-results/local-ai-development-regression-v2.json`: complete=true, provider SHA-256 `82b5b95ec88d2db71a714655d6f9be7416b82ffce61ac754a0f5c39d8cfdb47e`, 평가 SHA-256 `bc8ef3e2f4b3064c50d20956be242578785a71ccd1a62ac9f07afd5143507b65`

확대 최종/별도/기존 회귀의 provider 해시는 같다. 초기 보완 실행 remediation-v2 이후 단순 필드 재언급을 값 반복과 구분하는 보완을 추가하고 최종18건을 다시 검증했다.

실제 Spring→FastAPI→로컬 모델 업무 재검증도 통과했다. 사건 `5dbb5280-78be-4b45-9a8c-64b2e04a6aef`, AI 작업 `f94f0c1a-250a-49c1-b2f5-b4dda230f099`, 최초 판정 `0bd57294-7129-49b3-ad02-ea932cf2755f`. 보완 전 재고110/110/30·출고60/10/20, 보완 후 재고140/110/0·출고70/10/10 EA이며 미연결 출고10은 유지된다. 원시 결과는 Git 제외 `validation-results/local-ai-v2-workflow.json`에 보존했다.

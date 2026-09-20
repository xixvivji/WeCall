# 심사용 CPU 배포

Vercel은 `frontend`를 Root Directory로 설정한다. `frontend/vercel.json`의 `/api/*`는 GCP HTTPS 원본으로 전달한다. 브라우저는 동일 출처로 로그인·CSRF 요청을 보낸다. AI 분석은 기존 비동기 작업 요청과 상태 조회를 사용한다.

GCP: e2-standard-4, 4 vCPU, RAM 16GB, 50GB balanced persistent disk, 표준 VM. 무료 체험 계정을 유지한다. 2026-09-20 콘솔의 us-central1 견적은 VM 월 $97.84 + 디스크 월 $5.00이며 IP·전송·스냅샷 등은 별도다. 336시간의 VM+디스크 단순 환산 약 $47.34. GPU나 외부 모델 API를 사용하지 않는다.

## 서버 실행

Linux 호스트 네트워크를 사용하므로 Mac의 로컬 Compose 파일 대신 GCP Linux에서 실행한다.

```sh
python3 deploy/init-env.py YOUR_PUBLIC_HOST
sudo docker compose -f deploy/compose.yaml --env-file deploy/.env build
sudo docker compose -f deploy/compose.yaml --env-file deploy/.env up -d
sudo docker compose -f deploy/compose.yaml --env-file deploy/.env exec ollama ollama pull qwen3:4b-instruct
```

`.env`는 서버에서 신규 생성하며 0600 권한이다. 기존 개발용 비밀번호를 재사용하지 않는다. DB·FastAPI·Ollama·Spring은 루프백으로 바인딩하고 외부 웹 접근은 Caddy의 80/443만 사용한다. SSH는 별도 키 인증이다. Vercel에는 DB 비밀번호나 AI 서비스 토큰을 등록하지 않는다.

Caddy가 인증서를 발급한다. 데모의 sslip.io 주소는 서버 IP에 의존하므로 VM 중지 후 재시작으로 IP가 바뀌면 원본 URL과 인증서 설정을 갱신해야 한다. 고정 주소가 필요하면 현재 IP를 예약한다.

## 체험 범위

- 공개 `/#/demo`: 서버 데이터를 변경하지 않는 저장된 합성 예시.
- 실제 AI: 승인된 심사용 계정의 로그인 업무 기능. 현재 익명 방문자별 실제 AI 데이터 격리 기능은 없다. 검토자 비밀번호를 공개 페이지나 저장소에 넣지 않는다.
- 초기 데이터는 저장소의 합성 샘플만 사용한다. 회사 데이터·로컬 DB·기존 첨부파일은 복사하지 않는다.
- 요청을 한 번에 하나 처리한다. CPU 실측 후 기존 90초 모델 제한과 결과를 확인한다. 작은 합성 세트의 성공을 운영 성능으로 일반화하지 않는다.

## 2주 운영

- `docker compose ... ps`, HTTPS 샘플·로그인·실제 추출 성공 여부, 디스크 여유를 점검한다.
- 재시작 정책과 영구 볼륨을 사용한다. DB와 첨부 볼륨은 함께 백업해야 한다. 디스크 스냅샷만으로 업무 단위 일관성을 보장하지 않는다.
- 심사 종료일을 확인한 후 VM을 중지한다. 중지 후에도 디스크·스냅샷·예약 IP 비용은 남을 수 있다. 이 설정은 자동 종료를 예약하지 않는다.
- 일반 계정으로 전환하지 않는다. 무료 체험 크레딧·기한 소진 시 서비스 중단 가능성을 고려한다. 예산 알림은 자동 지출 차단이 아니다.

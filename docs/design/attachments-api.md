# 증거 파일 첨부 API와 로컬 운영

상태: 로컬 구현. PDF/PNG/JPEG를 새 입고 증거·작업 증빙과 함께 생성한다. 합성 자료로 검증했으며 운영 보관 정책·악성 파일 검사·OCR은 미구현이다.

## 등록 계약

기존 JSON 등록 API는 유지한다. 파일이 있으면 같은 URL에 `multipart/form-data`를 보낸다.

| URL | metadata(JSON part) | 권한 |
| --- | --- | --- |
| POST /api/v1/recalls/{caseId}/evidence | 기존 NewEvidence 필드 그대로 | 검토자·실행 담당자, 진행 중 사건 |
| POST /api/v1/recalls/{caseId}/tasks/{taskId}/proofs | expectedVersion, evidenceText | 검토자 또는 해당 작업의 담당자; 진행 중 작업·사건 |

`metadata` part의 Content-Type은 application/json, 파일 part의 이름은 반복 `files`다. 파일은 1~5개이며 파일당 10 MiB다. 텍스트 원문·근거 입력과 사람의 승인은 여전히 필수다. 서버가 로그인 사용자로 등록자를 결정하며 actor 입력을 신뢰하지 않는다. 성공 응답은 기존 생성 결과(201)와 같다. 첨부 ID는 아래 목록 API로 읽는다.

파일과 업무 기록을 하나의 생성 작업으로 묶으므로 실패 후 텍스트만 등록되는 부분 성공은 없다. 이전 증거·증빙에 파일을 추가·교체·삭제하는 API는 제공하지 않는다. 텍스트만 생성한 증빙에 나중에 파일이 필요하면 새로운 증빙을 제출한다.

## 검증과 제한

- 파일명은 경로 없이 200자 이내; 빈 이름·제어문자·슬래시·역슬래시를 거부한다. 저장 경로는 사용자 이름이 아닌 서버 UUID로 만든다.
- PDF는 `%PDF-` 헤더와 PDFBox 파싱으로 확인하며 암호화 문서·0쪽·500쪽 초과 문서는 거부한다.
- PNG/JPEG는 실제 이미지 포맷이 확장자와 일치해야 하며 ImageIO 디코딩으로 확인한다. 2천만 픽셀 초과 이미지는 거부한다. 클라이언트 Content-Type만으로 형식을 판단하지 않는다.
- PDFBox는 [공식 배포](https://pdfbox.apache.org/download.html)의 3.0.8을 사용한다. 이 파싱은 문서 형식 검사이며 바이러스·스크립트·유해 문서 탐지나 안전한 실행 보장이 아니다. 서버는 내용을 실행하거나 브라우저에 inline 미리보기를 제공하지 않는다.
- 빈 파일·형식 오류·파일 6개 이상은 400, 파일 크기 초과는 413이다. 전체 multipart 요청 상한은 52 MiB다. CSV는 서비스 검사로 기존 파일당 5 MiB·전체 26 MiB 제한을 유지한다.

## 목록과 다운로드

- `GET /api/v1/recalls/{caseId}/attachments?evidenceId={id}` 또는 `?proofId={id}`: 둘 중 하나만 지정. id, filename, mediaType, byteSize, sha256, uploadedBy, createdAt 반환. 해당 사건·대상의 첨부가 없으면 빈 배열이다.
- `GET /api/v1/recalls/{caseId}/attachments/{id}/download`: 두 역할 모두 로그인 후 같은 기업 조회 정책으로 사용한다. 다른 사건 ID 또는 없는 파일 ID는 404, 미인증은 401이다.
- 읽은 파일 크기·SHA-256을 DB 메타데이터와 다시 비교한다. 누락·읽기 실패·무결성 불일치는 503으로 반환한다. 저장 경로나 파일 내용을 오류에 포함하지 않는다.
- Content-Disposition: attachment, Cache-Control: no-store, X-Content-Type-Options: nosniff, CSP sandbox를 사용한다. 다운로드 요청 이력은 파일 확인 후 응답 전 기록한다. 클라이언트가 저장까지 완료했다는 증거는 아니다.

첨부 목록의 화면은 원문 파일명·등록자·시각·해시를 보여준다. 자료의 정확성과 입고·상품·수량·제조분 사실은 별도로 검토한다. 첨부 파일을 AI·OCR로 전송하지 않는다.

## 저장과 실패 복구

기본 경로는 `~/.wecall/attachments`, 환경변수 `WECALL_ATTACHMENT_DIR`로 전용 경로를 지정한다. Git/공개 웹 루트 밖의 디렉터리를 사용한다. 현재 어댑터는 POSIX 파일 권한을 사용하는 macOS/Linux용이며 생성 디렉터리 0700, 파일 0600이다. 기존 디렉터리 권한은 운영자가 점검한다. 경로 조상 또는 파일의 심볼릭 링크를 허용하지 않는다. macOS의 `/tmp`처럼 링크인 경로 대신 실제 경로(`/private/tmp`)를 지정한다.

검증된 바이트를 UUID.part로 기록·fsync하고 UUID.blob으로 원자 이동한다. 해당 업무 생성과 첨부 메타데이터·UPLOADED 이력은 같은 DB 트랜잭션으로 커밋한다. 다운로드는 커밋된 DB 행만 조회하므로 저장 중 파일은 공개되지 않는다. 파일 시스템과 DB 사이에 분산 트랜잭션이 있는 것은 아니다. 롤백 후 파일을 삭제하고 정리 실패 시 파일 UUID만 로그로 남긴다.

강제 종료 시 파일만 남을 수 있어 로컬용 오프라인 점검 스크립트를 제공한다. 기본 동작은 목록 조회다.

```sh
python3 scripts/cleanup_attachment_orphans.py
```

모든 백엔드를 정지하고 다른 인스턴스가 같은 저장소를 쓰지 않는 것을 확인한 뒤에만 정리한다. 로컬 compose의 wecall DB와 지정 디렉터리를 함께 사용해야 한다. 운영·원격 DB 정리 도구가 아니다.

```sh
python3 scripts/cleanup_attachment_orphans.py --delete --confirm-all-backends-stopped
```

삭제 모드는 로컬 8080 포트가 열려 있으면 거부한다. 포트를 바꿨다면 `--backend-port`를 맞춘다. DB 조회 실패 시 삭제하지 않는다. DB가 참조하지 않고 수정된 지 24시간 이상 된 UUID.blob/UUID.part 일반 파일만 후보이며, 링크·기타 파일명·신규 파일·연결 파일은 보존한다. 운영 삭제·보관 만료 기능을 대신하지 않는다.

업무 DB와 첨부 디렉터리는 함께 백업·복원해야 한다. 복원 일관성·암호화·저장소 가용성·악성 파일 검사 및 운영 보관 기간은 아직 별도 작업이다. [파일 관리 정책](file-data-policy.md)

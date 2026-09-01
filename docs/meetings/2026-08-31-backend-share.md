# Backend 팀 공유 사항 (2026-08-31, 인프라)

기준선 적재 방식 결정(방법 2)에 따라 Backend가 알아야 할 것과 해줘야 할 것을 정리했습니다.
배경과 결정 근거는 `docs/meetings/2026-08-31-baseline-serving.md` 참고.

## 1. 결정 요약

Spark 배치는 MySQL에 직접 쓰지 않습니다. 기준선 데이터는
`Spark(서버1) → HTTPS(nginx 443) → Spring 업로드 API → MySQL baseline_serving`
경로로만 들어갑니다. 서비스 DB 쓰기는 전부 Spring 단일 관문을 지난다는 원칙입니다.

## 2. 지금 해줄 것 — 계약 검토 (반나절 예상)

`docs/contracts/internal-api.yaml` 에 업로드 API 계약 초안을 만들어 뒀습니다. 검토해 주세요.

- 엔드포인트 2개: 행 묶음 업로드(`POST /internal/baselines/segment/versions/{dataVersion}/rows`, 요청당 최대 5000행)와
  활성화(`POST .../activate`)
- 행 필드는 ERD의 baseline_serving 컬럼과 맞춰 뒀습니다 (segmentId, dow, hourSlot, weatherBucket, p50/p80/p95, sampleCount)
- 경로·필드명·에러 형식은 Backend가 편한 대로 바꿔도 됩니다. 확정만 되면 인프라는 가짜 수신 서버로 배치 쪽을 독립 개발합니다

## 3. 나중에 구현할 것 — 엔드포인트 (급하지 않음)

이 API는 transport 수집기와 segment_id 설계가 끝나야 호출하는 쪽이 생깁니다.
회원·일정·추천이 우선이고, 이 구현은 Backend 일정에 맞춰 진행하면 됩니다. 구현 시 지킬 규칙:

- **검증 게이트**: p50 ≤ p80 ≤ p95, sampleCount ≥ 1, weatherBucket enum. 위반 시 요청 전체 거부(부분 저장 없음)
- **활성화 분리**: 업로드만으로는 서빙에 반영 안 됨. activate 요청에서 행 수 일치·급감 여부 확인 후 pipeline_config 활성 버전 전환
- **삭제 규칙**: activate 성공 직후에만 삭제. 활성 버전과 직전 버전은 항상 보호(찰나 충돌 방지 + 롤백용). 48시간 지난 고아 버전은 함께 정리
- **신선도 기록**: 마지막 활성화 시각을 저장해 두고, 기준선이 오래되면 추천 confidence 강등 + DATA_STALE 알림에 사용
  (ERD의 confidence, DATA_STALE 타입이 여기에 연결됨)
- **인증**: `X-DDUBI-API-KEY` 헤더 검증. 키는 환경변수 `INTERNAL_API_KEY` (커밋 금지, 서버1 배치와 서버2 Spring만 보유).
  nginx의 /internal/ 경로 IP 제한과 요청 크기 상향은 인프라가 서버 이식 때 설정

## 4. ERD 검토 의견 (제안)

- **baseline_serving 유니크 인덱스**: 조회 키인 (segment_id, dow, hour_slot, weather_bucket, data_version)에
  유니크 인덱스 권장. 서빙 조회 속도 + 재업로드 시 중복 방지
- **route_request.raw_response 보관 정책**: ODSAY 응답 원본은 경로당 수십 KB라 이 테이블이 용량을 가장 빨리 먹습니다.
  보관 기간(예: 7일 후 삭제)이나 "DB엔 요약만" 정책을 정해두면 좋겠습니다
- **테이블명 동기화 완료**: redis-keys.yaml의 조인 대상 표기를 schedule_segment 에서 ERD의 route_segment 로 맞춰 뒀습니다

## 5. 같이 정해야 할 것 (다음 회의 후보)

- **날씨 조회 방식**: Redis 키가 격자 단위(`weather:latest:{nx}:{ny}`)입니다. Backend가 구 이름으로 조회하고 싶으면
  구→격자 매핑을 Backend가 들고 있을지, Spark가 구별 키로 한 번 더 써줄지 결정 필요
- **좌표→segmentIds 변환 주체** (수집기 vs Spark), Spark가 provider↔segment 매핑을 읽는 방법
- **MYSQL_ROOT_PASSWORD 등 비밀값 공유 방식**: .env는 커밋 금지라 별도 채널로 공유해야 합니다

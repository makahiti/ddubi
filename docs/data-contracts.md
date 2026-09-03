# Data contracts

이 문서는 사람이 읽는 전체 계약 설명입니다. 기계 검증용 계약은 `docs/contracts/`에 둡니다.

## 공통 원칙

- 이벤트 시간은 ISO 8601과 Asia/Seoul 오프셋을 사용합니다.
- 각 이벤트는 중복 제거를 위한 `eventId`를 가집니다.
- 원본 발생 시각 `occurredAt`과 수집 시각 `collectedAt`을 구분합니다.
- 스키마 변경은 호환성을 검토하고 버전을 올립니다.
- API 키와 개인정보는 이벤트 payload에 넣지 않습니다.

## 위치와 구간(segment) 처리 원칙

- 뚜비는 경로를 직접 탐색하지 않고 외부 길찾기 API 의 후보 경로를 재평가한다. 경로는 뚜비 내부 구간 ID(`segment_id`)의 나열이다.
- 공간 교차 연산은 DB(MySQL)·Redis 어디서도 하지 않는다. 행사·사고·통제 위치는 **수집·전처리 단계**에서 영향받는 `segmentIds` 로 변환한다.
  - 수집기가 변환할 수 있으면(provider ID 가 곧 구간인 경우) 이벤트에 `segmentIds` 를 바로 채운다.
  - 아니면 원본 좌표 `location` 만 싣고, Spark 전처리가 `segmentIds` 를 채운다.
- Spark 는 구간별 상태를 `segment.status` 토픽과 Redis `segment:status:{segmentId}` 에 쓴다 (`events/segment-status.schema.json`).
- 외부 길찾기의 step은 `route_segment`, 그 step에서 확정된 모든 공식 인접 구간은
  `route_segment_edge`에 1:N으로 저장한다. Backend는
  `route_segment_edge.segment_id → route_segment → route(is_selected) → schedule`로
  영향을 받는 일정만 역조회한다. 별도 `schedule_segment`는 만들지 않는다.
- `provider_segment_mapping`은 카카오 요청 판정 캐시이며 Collector/Spark의 공용 crosswalk
  레지스트리가 아니다. Collector/Spark가 활성 canonical topology와 실시간 provider ID 대응표를
  읽는 방법(인증 내부 API, versioned artifact, compacted topic 중 하나)은 팀 계약 확정 대기다.
- 좌표 → segmentIds 변환 주체와 위 crosswalk 배포 경계가 구현되기 전에는 Backend의 로컬
  `EXACT` 결과만으로 end-to-end 실시간 매핑이 완성됐다고 보지 않는다.

## 계약 파일

- `contracts/events/*.schema.json`: Kafka 이벤트 JSON Schema
- `contracts/topics.yaml`: 토픽명·파티션·보존 정책
- `contracts/redis-keys.yaml`: Redis 키 형식·TTL·값 형식

# TOPIS 공지 AI 전처리 계약

## 한눈에 보는 흐름

```text
TOPIS API → Collector 중복 제거 → Kafka city.raw
                                      ↓
                              GMS 문장 구조화
                                      ↓
                              Kakao 장소 좌표
                                      ↓
                    동일한 정규화 DataFrame
                       ↙                    ↘
             Redis 최신 JSON          HDFS Parquet 이력
```

Collector는 `noticeId + 원문 내용 hash`가 이전 성공 발행과 같으면 Kafka에 다시 보내지 않는다.
같은 공지 ID라도 본문이 수정되면 새 개정본으로 발행한다. Spark는 Kafka의 `topis.notice`만 골라
GMS와 Kakao를 호출한다.

## 입력과 결과

예를 들어 원문이 `9월 19일 14시부터 17시까지 세종대로 일부 구간에서 집회로 인한 차량 통제`라면
수집시각의 연도 2026을 보충해 `startAt=2026-09-19T14:00:00+09:00`,
`endAt=2026-09-19T17:00:00+09:00`으로 만든다. `yearInferred=true`와
`yearBasis=COLLECTED_AT_YEAR`를 함께 남기므로 원문 연도와 혼동하지 않는다.

GMS는 행사 종류, 영향 종류, 시간, 도로명, 장소 순서, 영향 버스·정류장과 원문 근거를 JSON으로
반환한다. 이 JSON은 중간 응답일 뿐이며 최종 저장 형식은 Spark DataFrame이다. 장소마다 Kakao 검색
결과를 `routePoints` 구조체 배열로 붙인다. 여러 장소는 한 행의 배열에 순서대로 남고,
인접 장소 쌍은 `routeLegs` 배열로 만든다.

도로명 하나만 있는 경우에는 도로 전체를 한 점이라고 단정하지 않는다. 검색된 대표 좌표와
`radiusMeters=1000`, `radiusBasis=ROAD_REPRESENTATIVE`를 남긴다. 일반 장소의 기본 반경은 500m다.
후속 버스 결합에서는 이 좌표·반경 또는 두 점 사이 구간과 버스 정류장/구간의 공간 교차를 계산한다.

주요 품질 컬럼은 다음과 같다.

| 컬럼 | 뜻 |
|---|---|
| `processingStatus` | `VALID`, `PARTIAL`, `REJECTED`, `NOT_RELEVANT`, `INVALID_AI_RESPONSE` |
| `qualityFlags` | 시간 누락, 좌표 모호성, 원문에 없는 근거 등 검토 사유 |
| `routeMappingReady` | 모든 장소 좌표가 단일 후보로 확정됐는지 여부 |
| `rejectionReasons` | 허용하지 않는 분류·시간 역전 등 사용 불가 사유 |
| `validUntil` | Redis에서 현재 영향으로 사용할 수 있는 마지막 시각 |
| `noticeContentHash` | 같은 공지의 내용 개정 추적 값 |

GMS/Kakao의 일시 장애는 Spark 배치를 실패시켜 같은 Kafka 오프셋을 재시도한다. 형식이 잘못된 AI
응답은 잡 전체를 멈추지 않고 `INVALID_AI_RESPONSE`로 HDFS 검토 대상에 남긴다. Kakao가 후보를
여러 개 주면 좌표는 보존하되 `AMBIGUOUS`, `routeMappingReady=false`로 표시해 자동 버스 결합에서 뺀다.
검색 결과에 서울 주소 후보가 하나도 없으면 타 지역의 동명 장소를 대신 선택하지 않고
`NOT_FOUND`, `usableForBusMapping=false`로 남긴다.

## 저장과 장애 분리

- Redis: `traffic:latest:topis-event:{eventId}`에 정규화 행 전체를 JSON으로 저장한다. 종료된 행사와
  이미 `validUntil`이 지난 행은 쓰지 않는다. 새 개정본에서 `ENDED`·만료됐거나 행사 수가 줄어
  최신 event ID 목록에서 사라진 기존 키는 즉시 삭제한다. 늦게 도착한 과거 Kafka 행은 더 최신인
  Redis 키를 삭제할 수 없다. TTL은 최대 30일이며 행사 종료 6시간 뒤를 넘지 않는다.
- HDFS: `TOPIS_SILVER_OUTPUT_URI`가 설정된 경우 같은 스키마를 Parquet으로 저장한다.
- HDFS 경로가 비어 있거나 HDFS 스트림이 실패해도 Redis 스트림은 계속 실행한다.
- 기존 `traffic:latest:topis-notice:{providerNoticeId}`는 원문 호환 키이며 AI 결과 키와 역할이 다르다.

## 실행

실시간 잡은 새 기능 기본 꺼짐 원칙에 따라 별도 Compose 프로필로 켠다.

```sh
docker compose --profile spark --profile topis-ai up -d spark-streaming-topis
docker logs spark-streaming-topis --tail 100
```

필수 환경변수는 `GMS_API_KEY`, `KAKAO_REST_API_KEY`다. HDFS Silver도 쓸 서버만
`TOPIS_SILVER_OUTPUT_URI`를 설정한다. 키 값은 `.env.example`이나 Git에 넣지 않는다.

실제 두 API가 연결되고 시간·좌표가 생성되는지 샘플 한 건으로 확인할 수 있다. 키 값은 출력하지 않는다.

```sh
python sparkjobs/tools/verify_topis_enrichment.py
```

Collector 중복 제거 도입 전의 과거 아카이브는 공지 ID와 내용 hash가 같은 반복 행을 먼저 줄인 뒤
AI를 호출한다. 출력은 실시간 경로와 섞지 않은 새 버전 경로를 사용한다.

```sh
sh submit.sh batch/topis_silver_backfill.py \
  --input hdfs://hdfs-namenode:9000/ddubi/archive \
  --output hdfs://hdfs-namenode:9000/ddubi/silver/topis-backfill/v1 \
  --start 2026-09-10 --end 2026-09-17
```

`complete`가 만들어진 실행만 완료본이다. Redis는 실시간 최신값 전용이므로 백필 잡은 갱신하지 않는다.

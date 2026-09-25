> **폐기(2026-09-23)**: 2026-09-23 dt 결정으로 segment-observations·segment-status 잡을 폐기했다(앞으로도 쓸 계획 없음). 이 문서는 기록으로만 남긴다. compose·환경변수·알림·브리핑·토픽 계약에서 관련 항목을 뺐다.

# 구간 기준선·현재 판정 적용 및 담당자 확인 사항

2026-09-10. **Collector는 변경하지 않는다.** 이번 변경은 Spark 계산·검증·Redis 보관 정책·계약이다. 운영 서버 적용, 외부 담당자에게 메시지 전송, Collector 호출 주기·키 교체는 수행하지 않았다.

## 1. 구간 ID와 대응표

데이터팀은 Backend `CanonicalSegmentIdFactory`와 `redis-keys.yaml`의 `canonical-adjacent-v2`를 그대로 사용한다. 별도의 구간 이름을 만들지 않는다. 예: `SUB:2:0222:0223`.

현재 지하철은 DB 공급원 외에 버전 고정 JSON.gz 공급원도 있다. 즉시 가능한 전달 방식은 **Backend가 사용한 바로 그 마스터 파일과 SHA-256을 Spark에 배포**하는 것이다. `common/subway_crosswalk.py`가 실시간 호선·출발역·도착역 쌍을 정적 인접 ID로 검증한다. 역 하나만으로 방향을 추정하거나 정적 ID를 실시간 ID에 대입하지 않는다. 실시간 ID가 없는 인천·에버라인 구간은 계속 UNRESOLVED다.

`batch/export_segment_registry.py`는 기존 버스 대응표와 지하철 파일을 `segmentId/masterVersion` JSON으로 추출한다. 기준선·판정 프로그램이 같은 레지스트리를 읽으며, 마스터가 달라지면 원본 사실을 격리한다.

Backend와 확인할 HTTP 전달안(아직 서버 구현·상호 합의 안 됨):

- 내부 인증을 사용하는 `GET /internal/transit/subway-master`에서 현재 활성 버전의 동일한 gzip 파일을 내려준다.
- 응답에 불변 `dataVersion`, 파일 SHA-256, 내용 길이를 제공한다. 조회 중 활성 버전이 바뀌면 혼합하지 않고 재시도 가능하게 실패한다.
- Spark는 시작 시 한 번 받아 해시를 확인하고 해당 잡 전체에서 버전을 고정한다. 각 이벤트마다 DB/API를 조회하지 않는다.
- 새 파일은 전체 다운로드·검증 후 원자 교체한다. 실패 시 기존 완전한 파일을 유지한다.

## 2. Redis 보관 TTL과 신선도 분리

| 값 | 현재 코드의 수집 주기 | 보관 TTL(3배) | 현재 관측으로 인정할 최대 나이 |
|---|---:|---:|---:|
| 버스 위치·도착 | 8시간 | 24시간 | 120초 |
| 지하철 위치 | 1시간 | 3시간 | 120초 |
| 지하철 도착 | 6분 | 18분 | 120초 |
| 도시 정보 | 5분 | 18시간 | 15분 |
| 돌발·지하철 알림 | 15분 | 45분 | 10분 |
| TOPIS 공지 | 15분 | 45분 | 30분 |

Spark 최신값 JSON에 `retentionSeconds`, `freshnessSeconds`, `validUntil`을 추가했다. 도시 정보의 18시간 TTL은 장애 분석과 마지막 관측 보존을 위한 것이며 신선도는 계속 15분이다. TTL이 길어도 `validUntil`이 지난 값은 현재 위치·현재 도착시간·현재 지연의 근거로 사용할 수 없다. Backend는 이 시각을 확인해 unknown/기준선으로 전환해야 한다. 수집 간격을 나중에 변경하면 표와 Spark 정책도 함께 바꾼다.

공지 중복을 앞으로 Collector에서 생략하면 변경이 없는 공지는 마지막 발행 후 만료될 수 있다. 이 Redis 키는 진행 중 공지 전체 목록을 보장하지 않는다. 공지의 지속 여부·종료·삭제를 별도 동기화하지 않고 TTL만 길게 해서 위험을 확정하면 안 된다.

## 3. 구간 기준선

구현: `common/segment_facts.py`, `batch/segment_baseline.py`.

입력은 `segment-observation.schema.json`에 따른 **실제 인접 구간 통과시간**이다. 이벤트·구간·차량 ID,
마스터/매핑 버전, 관측·진입·이탈·수집·만료시각, 검증 품질, 초 단위 소요시간, 날씨 분류와 출처가
필요하다. 도착예정 초·시간표 예정시간·ML 예측·8시간 간격 위치차는 실측 정답으로 받지 않는다.
결측 날씨를 DRY로 채우지 않는다.

동일 eventId의 같은 사실은 한 번 계산한다. 같은 ID에 다른 소요시간/구간/날씨가 있으면 해당 ID 전체를 격리한다. 알려지지 않은 구간·버전, 잘못된 시각·단위·관측 종류도 `rejected`에 보존한다.

출력 그룹은 `segmentId × masterVersion × dow × hourSlot × weatherBucket`이며 dow는 1=일요일이다. P50/P80/P95, 표본 수, 관측 날짜 수, 최초/최종 시각, 버전, 최소 표본 충족 여부를 출력한다. 최소 표본·관측 날짜 수는 실행 인자로 명시한다. 부족한 그룹은 0초로 채우지 않는다.

기본은 검토용 Parquet 작성이다. `--upload`를 지정해야 기존 내부 업로드 도구로 적합 행만 올리고, 모든 청크가 성공한 뒤 활성화한다. 이번 작업에서는 운영 업로드를 하지 않았다.

**9/17은 자동 완성 날짜가 아니다.** 일주일이 지나도 실제 통과시간을 충분히 확보하지 못하거나 시간대/날씨별 표본이 비면 기준선이 부족하다. 지금 수집하는 저빈도 위치와 도착예정 데이터만 쌓인다는 이유로 구간 실측이 확보되었다고 볼 수 없다. 이번 프로그램 입력을 만드는 실측 수집/정규화 연결은 후속 작업이다. ML 예측도 실측인 것처럼 이 입력에 섞지 않는다.

## 4. 최근 10분 판정과 발행

고정시각 Replay/배치 진입점은 `streaming/segment_status.py`다. 매분 재평가하는
`streaming/segment_status_live.py`와 Kafka 실측 ingest는 `recommendation-data` Compose 프로필에
연결했다. 실제 통과 생산자와 검증 registry/baseline이 없으므로 아직 운영 활성화 대상은 아니다.

- 평가시각 직전 600초의 실측을 관측 요일·시간대·날씨별로 묶고 가장 최근 그룹의 중앙값과 해당 P50을 비교한다. 11시 평가에서 10:59 관측은 10시 기준선을 사용한다. 서로 다른 날씨의 최신 관측시각이 같으면 임의 선택하지 않는다.
- `delaySeconds=max(0, ceil(관측 중앙값-P50))`. slow/delayed 임계값, 최소 표본, 관측 최대 나이, 정책 버전은 실행자가 검토해 명시한다.
- 실측/기준선/날씨가 없거나 오래됐거나 표본이 부족하면 `unknown`, delay는 생략한다. 기준선의 `eligible=false`도 제외한다. 지연이 32비트 정수 범위를 넘으면 `DELAY_OUT_OF_RANGE`로 보류한다.
- `blocked`는 별도의 검수된 중단 증거 파일(`--blockages`)에 구간·마스터 버전·원본 UUID·출처·유효기간·confirmed=true가 있는 경우만 가능하다. 제목만 있는 공지나 큰 지연으로 추정하지 않는다.
- confidence는 실측·기준선 표본 게이트를 통과했을 때 `max(0,1-관측나이/허용나이)`다. 검수된 중단은 확인 여부 1, 나머지 unknown은 0이다. 이는 모델의 통계적 정확도가 아니다.
- `validUntil`은 최대 5분이며 관측 신선도/중단 유효기간을 넘지 않는다.
- 전체 최신 상태와 이력 대상(상태 변경 + 10분 구획별 전체)을 별도 Parquet로 준비한다. 직전 **성공 발행** 결과만 `--previous`로 넘긴다. 경계분을 놓쳐도 구획이 바뀌면 전체를 보낸다.
- `--publish-output`으로 준비된 같은 결과를 Kafka `segment.status`와 Redis `segment:status:{segmentId}`에 보낸다. Kafka key는 segmentId, 값은 같은 상태 계약이다. Kafka ACK와 Redis 쓰기 모두 성공하기 전에 previous 포인터를 전진시키지 않는다.
- 두 저장소는 분산 트랜잭션이 아니다. 실패 시 동일 결과를 재발행하며 Kafka 중복은 결정적 eventId로 제거해야 한다. Redis는 computedAt이 오래된 쓰기를 거절한다. 결과 준비 도중 실패하면 publish하지 않고 새 출력 경로로 재계산한다. 발행기는 latest/history/rejected/manifest의 `_SUCCESS`와 실행 manifest를 확인해 불완전한 출력을 거절한다.

## 5. Backend·인프라 검토안

기존 필수 필드와 상태 enum은 유지하고, 선택 필드로 관측값/시각/표본 수, 비교 P50·버전, 직전 상태, 품질 사유, 정책 버전, eventId, masterVersion을 추가했다. `segment-status.schema.json`과 코드의 선택 필드가 일치한다.

아래는 손계산 fixture의 Backend 소비 예시다. 실제 운영 예측값이 아니다. 관측 90/100/110초의 중앙값 100초와 P50 50초를 비교한 결과이며, 이 예시의 slow/delayed 경계는 30/60초다. 선택 필드 eventId는 생략했다.

```json
{
  "segmentId": "SUB:2:0222:0223",
  "masterVersion": "fixture-master",
  "status": "slow",
  "delaySeconds": 50,
  "confidence": 0.8,
  "observedSeconds": 100,
  "observedAt": "2026-09-10T10:09:00+09:00",
  "observationCount": 3,
  "baselineSeconds": 50,
  "baselineVersion": "fixture-baseline",
  "previousStatus": "normal",
  "qualityReason": "NONE",
  "causes": [],
  "policyVersion": "fixture-policy",
  "computedAt": "2026-09-10T10:10:00+09:00",
  "validUntil": "2026-09-10T10:14:00+09:00",
  "source": "spark.segment-status"
}
```

데이터팀 제안은 **Redis는 매 평가의 전체 최신값, Kafka 이력은 상태 변경 + 10분 구획별 전체 기록**이다. 이력 중복 제거 키는 eventId다. 담당자의 승인을 대신하지 않으며 외부 메시지를 보내지 않았다. Kafka/HDFS 보관 일수·토픽 설정·서버 배포는 인프라 담당 범위로 남긴다.

## Collector 담당자 전달 사항 — 코드 변경 없음

- 알림/TOPIS 공지는 현재 조회 범위 1,000건을 반복 발행한다. 발생시각만 자르면 늦은 공지·수정 공지가 누락되므로 제공자 ID+본문 해시로 미발행 버전을 구분하고, **Kafka 성공 ACK 뒤** 영속 체크포인트를 갱신하는 방식이 필요하다. 재시작 중복은 결정적 eventId로 방어한다.
- 현재 기본값의 같은 키 공유 호출 계획은 지하철 도착 `2×240=480`, 위치 `16×24=384`, 알림 `1×96=96`, 합계 **960회/일**이다. 코드의 공유 예산 900보다 많다. 실제 키 값은 열람하거나 출력하지 않았다.
- 권고안은 추가 키 발급보다 도착 수집을 10분으로 늘리는 방안이다. 해당 조건에서 `288+384+96=768회/일`로 재시도 여유 132회를 남긴다. **Collector는 현재 6분 그대로이며 이 제안은 미적용**이다. 키를 나누는 경우도 제공자의 이용 조건과 실제 한도를 담당자가 확인해야 한다.

## 로컬 검증 결과 (2026-09-10)

- Spark 전체 `python -m pytest -q --tb=short`: **53 passed, 2 failed**. 실패한 기존 날씨 아카이브 테스트 2개는 Windows의 `HADOOP_HOME`/winutils 미구성으로 Parquet 저장 단계에서 실패했다. 계산 결과 오류로 분류하지 않았으며 테스트를 생략 처리하지 않았다.
- 마지막 레지스트리 검증 보강 후 `tests/test_segment_pipeline.py`: **9 passed**. 손계산 P50/P80/P95, 예측/실측 구분, 중복/충돌, 미등록 ID·버전, 정각 경계, 부족 기준선, stale, 확정 중단, 극단값, 동일 입력 ID 재현, 만료 결과 재발행 방어, 불완전 출력·Kafka 실패 방어를 확인했다.
- 관련 TTL·Redis·업로드·구간 테스트 묶음: **41 passed**. JSON Schema와 출력 예시, YAML 계약 문법, Python 3.8 문법 검사도 통과했다.
- Kafka 실패/Redis 쓰기 순서와 업로드는 격리된 테스트로 확인했다. 실제 운영 Kafka·Redis·Backend에는 발행/업로드하지 않았다. 새 CLI의 실제 파일 저장부터 발행까지의 통합 검증은 Linux/HDFS 실행 환경에서 추가로 필요하다.
- `collector/`, `infra/`, `.gitlab-ci.yml` 변경 없음. 커밋·푸시·MR·배포 없음.

## 2026-09-11 후속 연결

실측 Kafka ingest, 매분 상태 실행, Redis ACK 이후 Kafka 트리거, 수도권 마스터 내부 다운로드가 추가됐다. 현재 실행 조건과 미확보 원천은 [통합 보강 기록](transit-service-integration.md)을 따른다. 위 날짜의 검증 수치를 현재 전체 테스트 결과로 해석하지 않는다.

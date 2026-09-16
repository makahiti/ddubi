# 뚜비 데이터 저장·실시간 예측·ML 재학습 아키텍처 가이드

> 이 문서는 외부 실시간 데이터가 Kafka, Spark, HDFS, Redis, GPU 학습 서버와 서비스 서버를 어떤 순서로 이동하는지 설명한다.
>
> 최종 아키텍처 그림: [ddubi-final-data-ml-architecture-v2.svg](./ddubi-final-data-ml-architecture-v2.svg)

## 1. 전체 흐름 한눈에 보기

```text
외부 API 9개
    ↓
Collector
    ↓ JSON Event
Kafka
    ├──────────────────────────────────────────┐
    │                                          │
    ↓                                          ↓
Raw Archiver                            Spark Structured Streaming
    ↓                                          ↓
HDFS Archive                       검증·정제·타입/단위 통일·ID 매핑
                                               ├─ 전체 정상 이력 → HDFS Silver
                                               ├─ 정제 실패     → HDFS Rejected
                                               └─ 최신값        → Redis

HDFS Silver
    ↓
Spark Batch
    ↓ 여러 카테고리의 시점 결합 + 실제 Label 결합
HDFS Gold
    ↓ 완성된 Gold Snapshot 전달
공용 GPU 서버의 ml-trainer
    ↓ 학습·검증·승인
Model Bundle
    ↓
서버2 ml-serving
    ↓ Redis 실시간 Feature 조회 후 predict()
Spring Backend
    ↓
사용자 앱
```

핵심 역할은 다음과 같이 분리한다.

```text
Kafka       = 실시간 데이터 운반
Spark       = 데이터 계산·정제·결합
HDFS        = 전체 이력과 학습 데이터 보관
Redis       = 서비스가 빠르게 읽을 최신값 보관
GPU 서버    = 모델 재학습
ml-serving  = 승인된 모델로 실시간 예측
```

---

## 2. 서버별 배치

### 서버1: 데이터 처리 영역

```text
서버1 · 데이터 서버
├─ Collector
├─ Kafka
├─ Raw Archiver
├─ Spark Master / Worker
├─ Spark Structured Streaming
├─ Spark Batch
├─ HDFS NameNode
└─ HDFS DataNode1
```

서버1은 데이터를 수집하고, 전달하고, 정제하고, 장기 보관하며, ML 학습용 데이터셋을 만드는 영역이다.

### 서버2: 서비스 영역

```text
서버2 · 서비스 서버
├─ Redis
├─ Spring Backend
├─ ml-serving
├─ MySQL
├─ Prometheus / Grafana
└─ HDFS DataNode2
```

서버2는 사용자 요청에 빠르게 응답하고 실시간 예측을 제공하는 영역이다.

### 공용 GPU 학습 서버

```text
공용 GPU 서버
├─ 전달받은 Gold Snapshot
├─ ml-trainer
├─ 학습·검증 코드
└─ Model Bundle 출력
```

공용 GPU 서버는 모델 학습을 위한 계산 서버다. 사용자 앱의 실시간 예측 요청을 직접 처리하지 않는다.

---

## 3. 하나의 HDFS와 내부 디렉터리

Silver와 Gold 때문에 HDFS 서버를 새로 두 개 추가하는 것이 아니다. HDFS 클러스터는 하나이며, 그 안에 목적별 디렉터리를 만든다.

```text
HDFS 클러스터 1개
└─ /ddubi
   ├─ archive/
   ├─ silver/
   ├─ gold/
   ├─ rejected/
   └─ checkpoints/
```

| HDFS 경로 | 의미 |
|---|---|
| `/ddubi/archive` | 외부에서 받은 원본 데이터 |
| `/ddubi/silver` | 정상적으로 정제된 전체 데이터 이력 |
| `/ddubi/gold` | ML 모델별 최종 학습 데이터셋 |
| `/ddubi/rejected` | 정제에 실패한 실제 데이터와 실패 이유 |
| `/ddubi/checkpoints` | Spark Streaming의 처리 위치와 복구 상태 |

따라서 다음 표현이 정확하다.

```text
HDFS Silver 서버  (X)
HDFS Gold 서버    (X)

하나의 HDFS 안에 /silver와 /gold 디렉터리  (O)
```

---

## 4. NameNode와 DataNode

HDFS 내부 구성은 다음과 같다.

```text
HDFS 클러스터
├─ NameNode
│  └─ 파일 경로, 파일 정보, 블록 위치 관리
│
├─ DataNode1 · 서버1
│  └─ 실제 데이터 블록 저장
│
└─ DataNode2 · 서버2
   └─ 실제 데이터 블록 저장·복제
```

`DataNode2`는 두 번째 HDFS가 아니다. 같은 HDFS 클러스터에 참여하는 두 번째 저장 노드다.

복제 계수가 2인 경우 하나의 파일을 구성하는 블록이 양쪽 DataNode에 복제된다.

```text
HDFS 파일: /ddubi/silver/bus/part-00001.parquet

Block A → DataNode1 + DataNode2
Block B → DataNode1 + DataNode2
```

Spark나 사용자는 DataNode를 직접 지정하지 않는다.

```text
Spark
→ hdfs://hdfs-namenode:9000/ddubi/silver/bus
→ NameNode가 실제 블록 위치 안내
→ DataNode에서 읽기 또는 쓰기
```

---

## 5. Kafka: 실시간 데이터 운반 계층

Collector는 외부 API 응답을 공통 이벤트 형식으로 만들어 Kafka에 전달한다.

```text
외부 API
→ Collector
→ 공통 Event Envelope JSON
→ Kafka Topic
```

Kafka는 데이터를 정제하거나 장기 보관하는 저장소가 아니라, 여러 처리기가 실시간 데이터를 독립적으로 읽을 수 있게 하는 운반 계층이다.

```text
Kafka
├─ Raw Archiver가 독립적으로 읽음
├─ Silver Streaming이 독립적으로 읽음
└─ Redis Streaming이 독립적으로 읽음
```

한 Consumer가 메시지를 읽었다고 다른 Consumer가 읽을 수 없게 되는 것이 아니다. 각 Consumer는 자신의 Offset 또는 Checkpoint를 따로 관리한다.

---

## 6. Archive: 원본 보존 경로

```text
Kafka
→ Raw Archiver
→ HDFS /ddubi/archive
```

Archive는 외부에서 받은 원본을 보존하는 계층이다.

주요 목적은 다음과 같다.

- 전처리 오류 발생 시 원본으로 복구
- 정제 규칙 변경 후 과거 데이터 재처리
- Kafka 보존 기간이 지난 데이터 재생
- 외부 API 형식 변경과 누락 원인 분석

Archive는 Spark Silver 앞에 반드시 거쳐야 하는 중간 단계가 아니다.

```text
Kafka → Archiver → Archive
Kafka → Spark → Silver
```

두 흐름은 직렬이 아니라 병렬이다.

---

## 7. Spark Structured Streaming: 실시간 정제

Spark Structured Streaming은 Kafka 데이터를 DataFrame으로 읽고 전처리한다.

```text
Kafka JSON
→ Spark DataFrame
→ 스키마 적용
→ 검증·정제
→ 정상과 오류 분리
```

정제 과정에는 다음 작업이 포함된다.

- 필수 필드 검증
- 숫자·문자열·Timestamp 타입 통일
- 속도·거리·시간 등의 단위 통일
- provider ID 분리와 검증
- provider ID에서 내부 `segmentId` 매핑
- `eventId` 기반 중복 방어
- 발생시각과 수집시각 구분
- 5개 데이터 카테고리 분류
- 스키마·전처리 버전 기록

정제 결과는 목적에 따라 세 방향으로 나뉜다.

```text
정제 결과
├─ 정상 데이터 전체 → HDFS Silver
├─ 정제 실패 데이터 → HDFS Rejected
└─ 정상 데이터 최신값 → Redis
```

---

## 8. HDFS Silver: 정제된 전체 이력

Silver는 Kafka 데이터를 Spark Streaming으로 정제한 전체 이력이다.

```text
Kafka
→ Spark Structured Streaming
→ 정상 정제 DataFrame
→ HDFS /ddubi/silver
```

### Silver 카테고리

Silver는 데이터 성격에 따라 5개 카테고리로 구분한다.

| 카테고리 | 대표 이벤트 |
|---|---|
| `bus` | 버스 도착·위치 |
| `subway` | 지하철 도착·위치·알림 |
| `traffic` | 도로 소통·돌발·교통 공지 |
| `city` | 도시 혼잡·유동인구 |
| `weather` | 날씨 관측 |

API 개수와 이벤트 종류 개수는 반드시 같지 않다. 하나의 외부 API 응답에서 여러 종류의 이벤트가 만들어질 수 있기 때문이다.

### Silver 디렉터리 예시

```text
/ddubi/silver/
├─ bus/
├─ subway/
├─ traffic/
├─ city/
└─ weather/
```

날짜와 시간으로 파티셔닝한다.

```text
/ddubi/silver/bus/
└─ eventDate=2026-09-14/
   └─ eventHour=15/
      ├─ part-00000-....snappy.parquet
      └─ part-00001-....snappy.parquet
```

### DataFrame과 Parquet

```text
Spark가 계산할 때     = DataFrame
HDFS에 물리 저장할 때 = Parquet 파일
다시 읽을 때          = Parquet 파일 집합을 DataFrame으로 해석
```

HDFS에 DataFrame 객체 자체가 저장되는 것은 아니다.

Silver에는 최신값 한 건만 저장하지 않고 정상 이력을 모두 저장한다.

```text
15:00 속도 30km/h → Silver 저장
15:01 속도 25km/h → Silver 저장
15:02 속도 18km/h → Silver 저장
```

---

## 9. HDFS Rejected: 정제 실패 데이터

Rejected는 Silver의 다음 단계가 아니라, Silver로 들어가지 못한 데이터가 이동하는 옆 경로다.

```text
Spark 검증
├─ 정상 → Silver
└─ 실패 → Rejected
```

Rejected 대상 예시는 다음과 같다.

- JSON 파싱 실패
- 필수 `eventId` 누락
- 필수 provider ID 누락
- 시각 파싱 실패
- 필수 숫자 필드 변환 실패
- 지원하지 않는 이벤트 타입
- 데이터 매핑 버전 불일치

Rejected에는 원본과 실패 이유를 함께 저장한다.

```text
eventId
eventType
source
kafkaTopic
kafkaPartition
kafkaOffset
errorCode
errorReason
pipelineVersion
rawPayload
rejectedAt
```

```text
/ddubi/rejected/
└─ source=bus-arrival/
   └─ rejectedDate=2026-09-14/
      └─ rejectedHour=15/
         └─ part-....snappy.parquet
```

Rejected 데이터는 정제 규칙을 수정한 뒤 별도 재처리 작업이 다시 읽는다.

```text
Rejected 또는 Archive
→ Spark Batch 재처리
→ 새로운 정제 규칙
   ├─ 성공 → Silver
   └─ 실패 → Rejected
```

---

## 10. Checkpoint: Spark 장애 복구 상태

Checkpoint는 데이터 품질 오류 로그가 아니다. Spark Streaming이 Kafka를 어디까지 처리했는지 기억하는 시스템 상태다.

```text
Rejected
→ 정제에 실패한 실제 데이터와 실패 이유

Checkpoint
→ 처리한 Kafka Offset과 Streaming 실행 상태
```

예:

```text
Checkpoint: Kafka offset 12,500까지 처리 완료
Rejected:   offset 12,431의 eventTime 형식이 잘못됨
```

각 Streaming 출력은 독립된 Checkpoint를 사용한다.

```text
/ddubi/checkpoints/
├─ silver-curated/
├─ silver-rejected/
├─ realtime-latest/
└─ weather-latest/
```

Spark가 재시작되면 Checkpoint를 확인하고 마지막 처리 위치 다음부터 Kafka를 읽는다.

Checkpoint는 운영자가 직접 수정하거나 Rejected 재처리를 위해 사용하는 디렉터리가 아니다.

---

## 11. Redis: 온라인 최신값 저장소

Redis는 정제된 데이터 중 서비스와 실시간 ML 예측에 필요한 최신값만 보관한다.

```text
Kafka
→ Spark Structured Streaming
→ 정제 DataFrame
→ ID별 최신 행 선택
→ JSON 변환
→ Redis Key + JSON + TTL
```

예:

```text
Key
traffic:latest:flow:LINK-1001
```

```json
{
  "eventType": "traffic.flow",
  "providerKey": "LINK-1001",
  "speedKph": 23.4,
  "occurredAt": "2026-09-14T15:30:00+09:00",
  "validUntil": "2026-09-14T15:35:00+09:00",
  "featureVersion": "traffic-v1"
}
```

Redis에는 DataFrame을 직접 저장하지 않는다.

```text
Spark 내부       = DataFrame
Redis 저장       = Key와 JSON 문자열
자동 만료        = TTL
데이터 신선도    = validUntil
```

Silver와 Redis는 동일한 정제 규칙을 사용하지만 보존 목적이 다르다.

```text
Silver = 정제된 전체 시간 이력
Redis  = 각 대상의 가장 최신 상태
```

---

## 12. Spark Batch와 HDFS Gold

Spark Batch는 HDFS Silver를 읽어서 모델별 학습 데이터를 만든다.

```text
HDFS Silver
→ Spark Batch
→ 필요한 카테고리와 시간 구간 선택
→ `segmentId`·시점 기준 결합
→ 실제 결과 Label 결합
→ 모델별 학습 DataFrame
→ HDFS Gold Parquet
```

Silver의 모든 데이터를 하나의 거대한 DataFrame으로 무조건 합치지는 않는다. 모델마다 필요한 입력과 Label이 다르기 때문에 모델별 Gold DataFrame을 만든다.

### 버스 도착시간 모델 예시

```text
Silver 버스 도착
+ Silver 버스 위치
+ Silver 도로 소통
+ Silver 날씨
+ 실제 도착시간 Label
→ 버스 도착시간 Gold DataFrame
```

### 도시 혼잡도 모델 예시

```text
Silver 도시 혼잡
+ Silver 날씨
+ 시간·요일 Feature
+ 이후 실제 혼잡도 Label
→ 도시 혼잡도 Gold DataFrame
```

### Gold 디렉터리 예시

```text
/ddubi/gold/
├─ bus-arrival/
├─ subway-delay/
├─ traffic-speed/
└─ city-congestion/
```

각 모델의 데이터 버전은 변경하지 않는 Snapshot으로 보관한다.

```text
/ddubi/gold/bus-arrival/
└─ version=20260914-v1/
   ├─ part-....snappy.parquet
   ├─ manifest.json
   └─ _SUCCESS
```

`_SUCCESS`가 있어야 Gold 생성이 완전히 끝난 것으로 판단한다.

---

## 13. Gold에서 GPU 재학습까지

HDFS는 학습을 실행하지 않는다. 학습 데이터만 보관한다.

```text
HDFS Gold       = 학습 데이터 저장
스케줄러         = 학습 실행 시점 결정
GPU ml-trainer  = 모델 학습·검증
```

재학습 흐름은 다음과 같다.

```text
1. Spark Batch가 새로운 Gold 버전 생성
2. manifest.json과 _SUCCESS 확인
3. 스케줄러가 재학습 시작
4. 완성된 Gold Snapshot을 GPU 서버에 전달
5. GPU ml-trainer가 Train / Validation / Test 수행
6. 새 모델과 기존 모델의 성능 비교
7. 승인 기준을 통과한 모델만 Model Bundle 생성
8. 승인 Bundle을 서버2 ml-serving에 배포
```

공용 GPU 서버에 내부 HDFS 직접 접근 권한을 열 수 없다면 서버1이 Snapshot을 전달한다.

```text
HDFS Gold
→ 서버1에서 완성 여부와 Checksum 확인
→ SCP 또는 승인된 공유 저장소
→ GPU 서버 로컬 학습 디렉터리
```

스케줄러는 Airflow, GitLab Scheduler, Kubernetes CronJob 또는 Linux cron 등을 사용할 수 있다.

---

## 14. Model Bundle

GPU 학습 서버는 모델 파일과 예측 계약을 하나의 Bundle로 만든다.

```text
model-bundle/
├─ model.pkl 또는 model.onnx
├─ feature-schema.json
├─ preprocessing.json
├─ metrics.json
├─ manifest.json
└─ requirements.txt
```

| 파일 | 역할 |
|---|---|
| 모델 파일 | 실제 추론 모델 |
| `feature-schema.json` | Feature 이름·타입·순서 |
| `preprocessing.json` | 결측값·인코딩·스케일링 규칙 |
| `metrics.json` | MAE·RMSE 등 검증 결과 |
| `manifest.json` | 모델·코드·Gold 데이터 버전과 Checksum |
| `requirements.txt` | 실행 라이브러리 버전 |

서버2는 승인된 Bundle만 읽기 전용으로 배포받는다.

```text
/opt/ddubi/models/
└─ bus-arrival/
   ├─ current → version=20260914-v3
   ├─ version=20260914-v2/
   └─ version=20260914-v3/
```

문제가 발생하면 `current`를 이전 승인 버전으로 되돌려 Rollback한다.

---

## 15. 서버2의 실시간 ML 예측

공용 GPU 서버는 실시간 예측을 하지 않는다. 서버2의 `ml-serving`이 승인된 모델을 메모리에 올려 예측한다.

```text
사용자 앱
→ Spring Backend
→ ml-serving
→ Redis Online Feature 조회
→ Feature Vector 생성
→ 승인 모델 predict()
→ Spring Backend
→ 사용자 앱
```

### 1단계: Spring이 예측 대상 전달

```json
{
  "modelName": "segment-delay",
  "predictionTime": "2026-09-14T15:35:00+09:00",
  "segmentIds": ["SEG-1001", "SEG-1002", "SEG-1003"]
}
```

Spring은 사용자 요청을 바탕으로 예측 대상 구간과 시각을 결정한다.

### 2단계: ml-serving이 Redis 조회

```text
MGET
ml-feature:segment:SEG-1001
ml-feature:segment:SEG-1002
ml-feature:segment:SEG-1003
```

```text
Redis JSON
→ TTL·validUntil 검사
→ Feature Version 검사
→ 결측값·Fallback 판단
→ Pandas DataFrame 또는 NumPy Vector
→ model.predict()
```

실시간 추론에서는 PySpark DataFrame을 만들지 않는다. Redis JSON을 가벼운 Pandas 또는 NumPy 입력으로 변환한다.

### 3단계: 예측 결과 반환

```json
{
  "modelVersion": "segment-delay-20260914-v3",
  "predictions": [
    {
      "segmentId": "SEG-1001",
      "predictedTravelSeconds": 182
    }
  ]
}
```

Spring은 구간별 예측 결과를 이용해 최종 경로와 예상 소요시간을 계산한다.

Spring도 사용자 화면에 현재 속도·혼잡도·날씨를 표시하기 위해 Redis를 조회할 수 있다.

```text
Redis
├─ Spring Backend → 화면에 표시할 최신 상태
└─ ml-serving     → 모델 추론용 Online Feature
```

실시간 요청 중에는 HDFS와 Kafka를 직접 조회하지 않는다. Redis 데이터가 없거나 오래됐다면 기준선 사용 또는 예측 불가 상태를 반환한다.

---

## 16. 학습 Feature와 예측 Feature 일치

Gold에서 학습할 때와 Redis에서 실시간 예측할 때 사용하는 Feature 계약은 반드시 같아야 한다.

```text
학습 경로
HDFS Gold → speedKph, rainfallMm, hour → 모델 학습

예측 경로
Redis     → speedKph, rainfallMm, hour → 모델 예측
```

일치해야 하는 항목은 다음과 같다.

- Feature 이름
- 데이터 타입
- 단위
- 결측값 처리
- 범주형 인코딩
- Feature 순서
- 전처리 버전

예를 들어 학습에서 속도를 `km/h`로 사용했는데 예측에서는 `m/s` 값을 넣으면 모델은 실행되더라도 결과가 잘못된다.

공통 Feature 계약은 Gold Dataset, Redis JSON, Model Bundle의 `feature-schema.json`에 동일하게 반영한다.

---

## 17. 장애와 복구 흐름

### Spark Streaming 중단

```text
Spark 재시작
→ HDFS Checkpoint 확인
→ 마지막 처리 Kafka Offset 다음부터 재개
```

### Kafka 보존 기간 초과

```text
HDFS Archive
→ Spark Batch Replay
→ 정제 규칙 재적용
→ Silver 재생성
```

### Rejected 재처리

```text
Rejected 또는 Archive
→ 수정된 정제 규칙으로 Batch 재처리
   ├─ 성공 → Silver
   └─ 실패 → Rejected
```

### 새 모델 장애

```text
새 Model Bundle 오류
→ 신규 모델 비활성화
→ 이전 승인 모델로 Rollback
→ 서비스 예측 계속 제공
```

GPU 서버가 중단되어도 서버2에는 이전 승인 모델이 남아 있으므로 실시간 예측은 계속할 수 있다.

---

## 18. 작은 Parquet 파일 관리

Redis는 수초 단위로 최신값을 반영할 수 있지만 Silver를 같은 주기로 저장하면 HDFS에 작은 파일이 지나치게 많이 생성될 수 있다.

권장 시작 기준은 다음과 같다.

```text
Redis 최신값 반영     : 약 10초 Micro Batch
HDFS Silver 적재      : 약 1~5분 Micro Batch
Silver 파일 Compaction: 하루 1회 또는 파일 수 기준
```

실제 주기는 데이터 유입량과 생성되는 Parquet 파일 크기를 측정한 뒤 조정한다.

---

## 19. 인프라 연결 요구사항

### 서버1

- Collector에서 Kafka 접근
- Raw Archiver에서 Kafka와 HDFS 접근
- Spark에서 Kafka, HDFS, Redis 접근
- Spark Batch에서 HDFS Silver와 Gold 접근
- HDFS NameNode와 DataNode1 영구 Volume
- Streaming Query별 독립 Checkpoint 경로

### 서버2

- Redis 영구 Volume과 인증
- Spring에서 Redis와 `ml-serving` 접근
- `ml-serving`에서 Redis 접근
- 승인 Model Bundle 읽기 전용 Volume
- HDFS DataNode2 영구 Volume
- Prometheus·Grafana 모니터링

### 서버 간

- Spark → 서버2 Redis 연결
- NameNode/DataNode1 ↔ DataNode2 HDFS 통신
- 서버1 → 서버2 모니터링 메트릭 연결
- 승인 Model Bundle → 서버2 전달 경로

### 공용 GPU 서버

- 완성된 Gold Snapshot 전달 경로
- 재학습 실행 계정과 GPU 자원
- 학습 로그·성능 지표 보관
- 승인 Model Bundle 반환 경로
- HDFS 내부망을 직접 열지 않을 경우 SCP 또는 승인 공유 저장소

---

## 20. 최종 완료 기준

### 정상 이벤트

```text
Kafka
├─ Archive에 원본 저장
├─ Silver에 정제 이력 저장
└─ Redis에 최신값 저장
```

### 오류 이벤트

```text
Silver 저장 안 됨
Redis 저장 안 됨
Rejected에 원문과 실패 이유 저장
```

### Spark 재시작

```text
Checkpoint부터 처리 재개
Redis 최신값이 과거값으로 되돌아가지 않음
Silver에 논리적인 이벤트 중복이 발생하지 않음
```

### ML 재학습과 예측

```text
Silver → 모델별 Gold 생성
Gold Snapshot 고정
GPU 재학습과 성능 검증
승인 Model Bundle을 서버2에 배포
ml-serving이 Redis Feature로 예측
문제 발생 시 이전 모델로 Rollback
```

---

## 21. 자주 혼동하는 내용

| 질문 | 답변 |
|---|---|
| Silver와 Gold는 새로운 HDFS인가? | 아니다. 기존 HDFS 안의 디렉터리·데이터 계층이다. |
| Silver는 Streaming으로 만드는가? | Kafka를 읽는 Spark Structured Streaming이 만든다. |
| Gold는 Redis인가? | 아니다. Silver에서 Spark Batch로 만든 HDFS 학습 데이터다. |
| Redis는 원본을 저장하는가? | 아니다. 정제된 최신값만 JSON+TTL로 저장한다. |
| HDFS에 DataFrame이 저장되는가? | Spark 내부에서는 DataFrame이고 HDFS에는 Parquet 파일로 저장된다. |
| Gold는 하나의 거대한 DataFrame인가? | 아니다. 모델 또는 학습 목적별로 하나씩 만든다. |
| Rejected는 일반 로그인가? | 실패한 실제 데이터와 실패 이유를 보관하는 데이터셋이다. |
| Checkpoint는 오류 데이터인가? | 아니다. Spark가 처리 위치를 기억하는 복구 상태다. |
| DataNode2는 두 번째 HDFS인가? | 아니다. 같은 HDFS의 두 번째 저장 노드다. |
| HDFS가 모델을 재학습하는가? | 아니다. 스케줄러가 GPU `ml-trainer`를 실행한다. |
| GPU 서버가 사용자 예측도 수행하는가? | 아니다. 학습을 담당하고 서버2 `ml-serving`이 예측한다. |
| 실시간 예측 때 HDFS를 읽는가? | 아니다. `ml-serving`이 Redis 최신 Feature를 읽는다. |

---

## 22. 최종 아키텍처 요약

```text
전체 원본은 HDFS Archive에 저장한다.
정제된 전체 이력은 HDFS Silver에 저장한다.
정제에 실패한 데이터는 HDFS Rejected에 저장한다.
서비스에 필요한 최신값은 Redis에 저장한다.
Silver를 모델 목적에 맞게 결합한 결과는 HDFS Gold에 저장한다.
Gold Snapshot으로 공용 GPU 서버에서 모델을 재학습한다.
승인된 모델만 서버2 ml-serving에 배포한다.
ml-serving은 Redis 최신 Feature를 읽어 실시간 예측한다.
```

이 아키텍처의 핵심은 다음 한 문장이다.

> 전체 이력은 HDFS, 온라인 최신값은 Redis, 데이터 가공은 Spark, 재학습은 GPU 서버, 실시간 예측은 서버2가 담당한다.

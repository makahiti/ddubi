# 날씨·사고·통제·집회 입력의 공통 ML 전처리

현재 v12 입력/모델을 변경하지 않는다. 새 경계는 `environment-asof-v1`이며 검증 모델은
`ML_ENVIRONMENT_BUNDLE_DIR`로 별도 지정한다. 미설정/검증 실패는 환경 예측 503이고 기존 v12는 유지된다.
일반 도보는 API fallback, 지하철은 시간표/실시간 계산이다. 실제 정답을 확보하지 않은
대기·환승·지하철 모델을 학습하지 않는다. 도착예정/평균/실제 통과 기록을 혼합하지 않는다.

## 원천에서 정규화까지

원본 Kafka/Parquet 이력을 보존한다. 버스 canonical ID·마스터·도로 링크 방향/적용 기간을 확인한 뒤
날씨는 해당 구간의 검증 격자, 도로는 해당 구간이 실제 지나는 링크, 사건은 검증 영향 구간에 연결한다.
`segmentId`, `masterVersion`, `source`, `eventId`, `observedAt`, `publishedAt`, `collectedAt`,
`validFrom`, `validUntil`이 필요하다. 발표시각이 없는 파일을 mtime이나 현재 날짜로 소급하지 않는다.
미검증 링크/격자는 정규화 입력에 넣지 않고 원본과 실패 이유를 남긴다. 공지 제목만으로 집회/시위를
구간에 확정 연결하지 않는다. 공개 예정/예보는 `isForecast=true`와 원래 발표 이력을 보존한다.

`environment_features.prepare`는 예측 asOf까지 발표되고 실제 수집된 revision만 선택하고,
구간 passageAt에 적용되는 같은 버전의 최신 자료를 고른다. 같은 우선순위의 상충 자료는 결측이다.
습도0~100, 음수 강수/속도/적설/풍속, NaN/Infinity를 검증한다. 사건 수는 `feedComplete=true`일 때만
0이 의미 있다. 불완전한 피드·자료 부재는 null이다. 전처리가 마스터/공간 매핑을 새로 추측하지 않는다.

## 실제 요청과 전처리 예

내부 HTTP `POST /v1/bus/environment:prepare`에 다음을 보낸다. 아래는 테스트용 예시이며 실자료가 아니다.

```json
{
  "request": {
    "segmentId": "BUS:1:2:3",
    "masterVersion": "fixture-v1",
    "asOf": "2026-09-11T09:00:00+09:00",
    "passageAt": "2026-09-11T09:10:00+09:00"
  },
  "observations": [{
    "segmentId": "BUS:1:2:3",
    "masterVersion": "fixture-v1",
    "kind": "weather",
    "eventId": "fixture-weather-1",
    "source": "fixture",
    "observedAt": "2026-09-11T08:50:00+09:00",
    "publishedAt": "2026-09-11T08:51:00+09:00",
    "collectedAt": "2026-09-11T08:52:00+09:00",
    "validFrom": "2026-09-11T08:50:00+09:00",
    "validUntil": "2026-09-11T09:30:00+09:00",
    "values": {"temperatureC": 23.0, "precipitationMm": 1.2}
  }]
}
```

전처리 응답의 `features`는 다음과 같다. 나머지 필드에는 버전·시각·출처·missingFeatures와
conflictingKinds/rejectedObservations/productionReady=false가 포함된다.

```json
{
  "temperatureC": 23.0,
  "humidityPercent": null,
  "precipitationMm": 1.2,
  "snowDepthCm": null,
  "windSpeedMps": null,
  "roadSpeedKph": null,
  "accidentCount": null,
  "closureCount": null,
  "assemblyCount": null,
  "protestCount": null
}
```

동일 입력을 `POST /v1/bus/environment:predict`로 보내면 같은 prepare를 거친 후 명시 등록 모델을 쓴다.
모델이 없으면 503/MODEL_NOT_READY다. 미등록 구간·전체 환경 결측은 UNAVAILABLE이며
정상 반환도 PREVIEW/productionReady=false, meanSeconds, target, modelVersion, featureVersion을 보존한다.
이 인터페이스는 내부용이며 일반 사용자에게 이력·모델 입력 조작을 맡기는 경로가 아니다.
Backend의 현행 `/v1/bus/predictions:batch` v12 호출을 자동 전환하지 않는다. 새 모델의 실제
재학습·미래 검증과 원천 이력 연결이 끝난 뒤 호출을 전환해야 한다.

## 학습 준비와 실행

samples.jsonl 한 행은 위 request와 별도 label을 가진다. label은 예측 입력에 포함하지 않는다.

```json
{
  "request": {
    "segmentId": "BUS:1:2:3", "masterVersion": "fixture-v1",
    "asOf": "2026-09-11T09:00:00+09:00", "passageAt": "2026-09-11T09:10:00+09:00"
  },
  "label": {
    "eventId": "fixture-trip-1", "source": "fixture", "verified": true,
    "kind": "ACTUAL_TRAVERSAL", "seconds": 95.0,
    "startedAt": "2026-09-11T09:10:00+09:00",
    "completedAt": "2026-09-11T09:11:35+09:00", "availableAt": "2026-09-11T09:11:40+09:00"
  }
}
```

1. `python ml-serving/tools/prepare_environment_training.py --samples samples.jsonl
   --observations observations.jsonl --output prepared`를 실행한다. 공통 prepare의 결과에 label만 붙인다.
   날짜 경계 후보 중 행 수70%에 가장 가까운 경계를 선택한다. 테스트 시작까지 알려지지 않은
   학습 정답은 withheld로 분리하며 실제 train/test/withheld 행 수를 manifest에 남긴다.
   같은 `(source, eventId)` 정답 중복, 목표 혼합, 예측값 정답 사용, 시간대 누락은 실패다. 입력별 최대100,000행의
   검증 도구이며 기존 1억 행 전체 학습 파이프라인의 대체물은 아니다.
2. `python ml-serving/tools/train_environment_model.py --prepared prepared --output new-bundle
   --version <new-version>`은 GPU device0, CPU최대16으로 LightGBM 별도 모델을 학습한다.
   test는 학습/튜닝에 사용하지 않는다. 환경 열·구간 범주 사전·KST요일/시간 파생을 서빙과 공유한다.
   결과는 미래기간 MAE/RMSE와 train 중앙값 baseline, 미등록 구간 수, 모델 SHA를 기록한다.
   GPU 실패 시 CPU로 조용히 전환하지 않는다. 기존 v12 모델을 덮어쓰지 않는다.
3. 새 모델로 실제 미래 표본을 검증하기 전에는 productionReady를 올리지 않는다. 모델/API 예상값
   차이만으로 정확도를 주장하지 않는다. 관측/피드 품질 및 기존 모델/경로 API와 같은 표본 비교도 필요하다.

현재 서버 자료의 누락과 운영 적용 조건은 [통합 보강 기록](transit-service-integration.md)에 정리했다.

## 2026-09-13 정답 검증 보강

준비 manifest의 `labelVersion`은 `environment-label-v2`다. 이전 준비 파일은 새 도구로 다시
전처리해야 하며, 학습 도구가 버전/SHA와 정답을 다시 검증한다. 기존 v12 모델 파일에는 적용하지 않는다.
`ACTUAL_TRAVERSAL`은 실제 `startedAt`과 `completedAt`이 필요하고 두 시각 차이가 `seconds`와
일치해야 한다. `asOf <= startedAt < completedAt <= availableAt`을 만족해야 한다. 미래 통과 예상값인
`passageAt`을 실제 진입시각으로 대신하지 않는다. 시간대 평균 목표는 별도로 유지한다.

관측 `source`/`eventId`가 비어 있으면 HTTP뿐 아니라 파일 전처리에서도 제외한다.
관측은 `(masterVersion, segmentId)`로 먼저 묶은 뒤 동일 prepare를 적용한다.
manifest의 `quality`는 train/test/withheld별 행 수, 환경 전체 결측 행 수, 열별 결측,
충돌·제외 관측 참조 수, 구간 수, 정답 출처별 수, 실제 시간순 asOf 범위를 기록한다.
이 보고서에 수치가 있다는 사실만으로 운영 모델을 승인하지 않는다.

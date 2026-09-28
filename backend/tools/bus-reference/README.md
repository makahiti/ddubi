# 버스 수식 참조자료 생성

이 디렉터리는 서울 ML 실패 및 서울 외 지역 버스 fallback에 필요한 충분통계를
Backend 적재 계약으로 변환한다. 원본 교통자료를 서비스 서버에 넣지 않고, 프로젝트
canonical ID까지 매핑된 요약 JSONL만 입력으로 사용한다.

## 부산 GTFS 매핑 연결

`build_busan_gtfs_integration.py`는 부산 연구 산출물의 301개 원본 노선을 실제 정류장열
패턴으로 분리하고, 기존 통합 버스 마스터에 `BUSAN_GTFS` 노선을 추가한다. 동시에 각
canonical edge에 24시간 수식 입력 JSONL을 생성한다. 시간표가 없는 시간은
`localSampleCount=0`으로 기록되어 Backend가 구간 평균과 부산 시간대 계수로 계산한다.
도구는 파일만 만들며 DB 적재나 활성화는 하지 않는다.

`build_metropolitan_gtfs_mapping.py`는 같은 연구 폴더의 수도권 산출물을 현재
`GBIS`·`INCHEON_PUBLIC_CSV` 마스터에 연결한다. 정규화 노선명과 양 끝 공식 정류장
ID가 모두 일치하고 후보가 하나인 edge만 채택하며, 결측·복수 후보는 manifest에
제외 사유로 남긴다.

## 입력 예시

한 줄에 한 JSON 객체를 작성한다. 아래는 경기·인천 형식이다.

```json
{"namespace":"GBIS","routeId":"GBIS.R1","fromStopId":"GBIS.S1","toStopId":"GBIS.S2","fromOrder":1,"toOrder":2,"dow":1,"hourSlot":8,"sampleKind":"GTFS_SCHEDULE_FORMULA","sampleCount":18,"observedDates":0,"observationStart":"2025-03-01","observationEnd":"2030-12-31","localMeanSeconds":72.5,"localSampleCount":18,"segmentMeanSeconds":68.2,"hourFactor":1.06}
```

서울은 `weightedSeconds`, `weightTotal`을 사용한다. 부산은 경기·인천 필드에
`alpha: 0.25`를 추가한다. 상세 필드 의미와 수식은
`docs/contracts/bus-travel-time-formula-fallback.md`를 따른다.

## 생성과 검증

```powershell
python backend/tools/bus-reference/build_formula_bundle.py `
  <mapped-sufficient-statistics.jsonl> `
  <new-output-folder> `
  --master-version <active-master-version>

# 이미 정렬된 지역별 대용량 파일은 메모리에 모두 올리지 않고 하나로 묶는다.
python backend/tools/bus-reference/build_formula_bundle.py `
  <busan-canonical.jsonl> <new-output-folder> `
  --additional-source <metropolitan-canonical.jsonl> `
  --master-version <active-master-version>

python -m unittest discover `
  -s backend/tools/bus-reference `
  -p "test_*.py" -v
```

출력 폴더에는 `export.json`과 `000000.json.gz`부터 시작하는 청크가 생성된다.
도구는 기존 폴더를 덮어쓰지 않으며 네트워크나 MySQL을 변경하지 않는다.

## 서버 적용 순서

1. `fallback.enabled=false` 상태에서 새 번들을 서버로 전달한다.
2. 기존 전송 도구로 전체 체크섬과 행·청크 수를 확인한다.
3. 내부 API `/internal/bus-references/{version}/chunks/{chunk}`로 적재한다.
4. `/internal/bus-references/{version}/status`의 합계와 `export.json`을 대조한다.
5. 새 `dataVersion`, `expectedRows`, `expectedChunks`를 설정한 뒤 fallback을 켠다.
6. `/gate`와 실제 Journey 응답의 `BUS_HISTORICAL_REFERENCE` 또는
   `BUS_GTFS_FORMULA` 출처를 확인한다.

데이터 변경 시 기존 버전을 덮어쓰지 않고 새 번들을 만든다. 서버 배포 키와 내부 API
키는 입력 JSONL, manifest, 명령 인자, 로그에 기록하지 않는다.

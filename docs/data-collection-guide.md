# 실시간 데이터 수집 가이드

이 문서는 처음 프로젝트에 참여한 팀원이 **무엇을, 어떻게, 얼마나 자주 수집하는지** 한눈에 확인하기 위한 기준 문서다.

## 1. 전체 수집 현황

| 분류 | 수집기 | 가져오는 데이터 | 한 사이클 API 요청 수 | 기본 수집 간격 | 하루 최대 계획 요청 | Kafka 토픽 |
|---|---|---|---:|---:|---:|---|
| 버스 | `bus_arrival.py` | 선택한 100개 노선의 모든 정류장별 첫 번째·두 번째 버스 도착예정 | 100 | 8시간¹ | 300 | `transport.raw` |
| 버스 | `bus_location.py` | 선택한 100개 노선에서 운행 중인 모든 차량 위치 | 100 | 8시간¹ | 300 | `transport.raw` |
| 지하철 | `subway_arrival.py` | 서울 전체 역의 열차 도착예정 | 보수적으로 2² | 6분 | 480 | `transport.raw` |
| 지하철 | `subway_location.py` | 16개 호선의 운행 열차 위치 | 16 | 1시간 | 384 | `transport.raw` |
| 지하철 | `subway_alert.py` | 지연·무정차·운행 변경 알림 | 1 | 15분 | 96 | `transport.raw` |
| 도시 | `city_realtime.py` | 121개 장소의 인구·교통·주차·따릉이·기상·행사 등 | 121 | 6시간 | 484 | `city.raw` |
| 교통 돌발 | `traffic_incident.py` | 현재 발생 중인 사고·공사·도로 통제 | 1 | 15분 | 96 | `city.raw` |
| 공지·행사 | `topis_notice.py` | TOPIS 통제·버스 우회·교통 공지 | 1 | 15분 | 96 | `city.raw` |
| 날씨 | `weather.py` | 서울 14개 기상 격자의 초단기실황 | 14 | 10분 | 2,016³ | `city.raw` |

¹ 전체 723개 노선 ID 목록은 보존한다. 기본적으로 목록 앞의 100개만 사용하며 `BUS_ROUTE_IDS`로 대상 노선을 직접 선택할 수 있다. 두 버스 수집기가 같은 키를 사용해도 계획 호출량은 하루 합계 600회다.

² 전체 지하철 도착 API는 결과 파일로 HTTP 리다이렉트될 수 있다. 호출 예산은 원 요청과 리다이렉트를 각각 세어 2회로 잡는다.

³ 날씨는 별도 기상청 키와 한도를 사용한다. 이번 변경에서 `weather.py`와 그 10분 주기는 수정하지 않았다.

> 하루 1,000회 제한 아래에서 서울 전체 버스 723개 노선을 한 사이클에 수집하는 것은 불가능하다. 현재는 100개 노선만 8시간마다 조회해 도착 300회 + 위치 300회, 하루 합계 600회로 운영한다. 더 많은 노선이 필요하면 제공기관에서 호출 한도를 증설해야 한다.

## 2. 서버에서 데이터가 흐르는 과정

```text
서울시·공공데이터 API
        ↓
collector/src/sources/*.py
        ↓  API 응답을 Record로 변환
collector/src/main.py
        ↓  공통 JSON 이벤트 생성
Kafka: transport.raw / city.raw
        ├─→ Archiver → Parquet → HDFS (원본 장기 보관)
        └─→ Spark realtime_latest → Redis (서비스용 최신 상태)
```

모든 Kafka 메시지는 JSON이며 공통 구조는 다음과 같다.

| Kafka JSON 키 | 의미 |
|---|---|
| `eventId` | 이 Kafka 이벤트에 새로 부여한 UUID |
| `eventType` | `bus.arrival`, `traffic.incident`처럼 데이터 종류를 나타내는 값 |
| `source` | 데이터를 만든 수집기 이름 |
| `occurredAt` | 제공기관이 알려준 발생 시각. 제공 시각이 없으면 수집 시각 사용 |
| `collectedAt` | 우리 서버가 API에서 받은 시각 |
| `region` | 교통 이벤트의 지역. 값이 없으면 생략 |
| `location` | 위도·경도를 확실히 알 때만 포함 |
| `segmentIds` | 내부 도로·대중교통 구간과 매핑됐을 때만 포함 |
| `payload` | 제공기관 ID, 원본 응답, 수집 보조 정보 |

`payload`의 공통 키는 다음과 같다.

| 키 | 의미 |
|---|---|
| `providerRouteId` | 서울시 버스 노선 ID |
| `providerStopId` | 서울시 정류장 ID |
| `providerVehicleId` | 서울시 버스 차량 ID |
| `providerLineId` | 지하철 호선 ID 또는 이름 |
| `providerStationId` | 지하철역 ID |
| `providerTrainId` | 열차 번호 |
| `providerAlertId` | 지하철 알림 식별값. 공식 ID가 없어 주요 필드로 만든 SHA-256 |
| `providerNoticeId` | TOPIS가 제공한 공지 ID |
| `providerIncidentId` | TOPIS가 제공한 돌발상황 ID(`ACC_ID`) |
| `providerLinkId` | TOPIS 도로 링크 ID |
| `providerAreaId` | 실시간 도시데이터 장소 코드 |
| `raw` | API에서 받은 항목 원본. HDFS에서 재처리할 때 사용 |
| `observedAt` | 우리 수집기가 해당 항목을 확인한 시각 |
| `timestampBasis` | `provider`: 제공 시각 사용, `received`: 수집 시각 사용 |
| `contentHash` | `raw` 전체로 만든 SHA-256. 같은 ID의 내용 변경 확인용 |

`eventId`는 매번 달라진다. 같은 공지인지 판단할 때는 `providerNoticeId`, 같은 돌발상황인지 판단할 때는 `providerIncidentId`를 사용한다. 같은 ID인데 `contentHash`가 달라졌다면 내용이 수정된 것이다.

## 3. 요청 방식과 필수 파라미터

인증키는 Header가 아니라 API가 정한 Query Parameter 또는 URL 경로로 전달한다. `.env`는 키를 안전하게 보관하는 위치일 뿐, API의 인증 방식이 아니다.

| 수집기 | 요청 방식 | 반드시 필요한 값 |
|---|---|---|
| 버스 도착 | `GET http://ws.bus.go.kr/api/rest/arrive/getArrInfoByRouteAll` | Query: `serviceKey`, `busRouteId`, `resultType=json` |
| 버스 위치 | `GET http://ws.bus.go.kr/api/rest/buspos/getBusPosByRtid` | Query: `serviceKey`, `busRouteId`, `resultType=json` |
| 지하철 도착 | `GET http://swopenapi.seoul.go.kr/api/subway/{KEY}/json/realtimeStationArrival/ALL/` | Path: 지하철 인증키, 출력형식 `json`, 역 범위 `ALL` |
| 지하철 위치 | `GET http://swopenapi.seoul.go.kr/api/subway/{KEY}/json/realtimePosition/0/1000/{호선}/` | Path: 지하철 인증키, 시작·끝 행, 호선명 |
| 지하철 알림 | `GET http://openapi.seoul.go.kr:8088/{KEY}/json/getNtceList/1/1000/` | Path: 일반 인증키, 형식, 서비스명, 시작·끝 행 |
| 도시데이터 | `GET http://openapi.seoul.go.kr:8088/{KEY}/json/citydata/1/5/{장소코드}` | Path: 일반 인증키, 형식, 서비스명, 시작·끝 행, 장소 코드 |
| 실시간 돌발 | `GET http://openapi.seoul.go.kr:8088/{KEY}/xml/AccInfo/1/1000/` | Path: 일반 인증키, `xml`, `AccInfo`, 시작·끝 행 |
| TOPIS 공지 | `GET http://openapi.seoul.go.kr:8088/{KEY}/xml/NoticeList/1/1000/` | Path: 일반 인증키, `xml`, `NoticeList`, 시작·끝 행 |
| 날씨 | `GET .../getUltraSrtNcst` | Query: `authKey` 또는 `serviceKey`, `pageNo`, `numOfRows`, `dataType=JSON`, `base_date`, `base_time`, `nx`, `ny` |

서울시 실시간 돌발과 TOPIS 공지는 외부 API가 XML만 지원한다. 해당 수집기가 XML을 읽어 Python 객체로 바꾼 뒤 Kafka에는 JSON으로 전송한다.

## 4. 응답 키 설명

### 4.1 버스 도착 `bus.arrival`

API 최상위는 `msgHeader`와 `msgBody`이며, 실제 정류장별 데이터는 `msgBody.itemList[]`에 들어 있다.

| 원본 키 | 의미 |
|---|---|
| `headerCd`, `headerMsg` | 요청 결과 코드와 메시지. `headerCd=0`이어야 정상 |
| `stId`, `stNm`, `arsId` | 정류장 ID, 정류장명, 승객 안내용 정류장 번호 |
| `busRouteId`, `rtNm` | 노선 ID와 노선명 |
| `staOrd`, `dir` | 이 정류장의 노선상 순번과 운행 방향 |
| `firstTm`, `lastTm`, `term` | 첫차 시각, 막차 시각, 배차간격(분) |
| `mkTm` | 도착정보가 만들어진 시각 |
| `vehId1`, `vehId2` | 첫 번째·두 번째 도착 예정 차량 ID |
| `plainNo1`, `plainNo2` | 첫 번째·두 번째 차량 번호판 |
| `traTime1`, `traTime2` | 첫 번째·두 번째 차량의 도착예정 초 |
| `arrmsg1`, `arrmsg2` | 사람이 읽는 도착 안내 문구 |
| `sectOrd1`, `sectOrd2` | 두 차량이 현재 위치한 노선 구간 순번 |
| `stationNm1`, `stationNm2` | 두 차량이 있는 정류장명 |
| `isArrive1`, `isArrive2` | 도착 여부 코드 |
| `isLast1`, `isLast2` | 막차 여부 |
| `busType1`, `busType2` | 차량 유형 코드 |
| `reride_Num1/2`, `brdrde_Num1/2` | 재차·승하차 관련 제공값. 코드 의미는 활용가이드 기준으로 해석 |

### 4.2 버스 위치 `bus.position`

실제 차량 목록은 `msgBody.itemList[]`이다.

| 원본 키 | 의미 |
|---|---|
| `vehId`, `plainNo` | 차량 ID와 번호판 |
| `busRouteId` | 현재 조회한 노선 ID. 응답에 없으면 요청값으로 보강 |
| `sectOrd`, `sectionId` | 현재 구간 순번과 구간 ID |
| `gpsX`, `gpsY` | 경도와 위도 |
| `sectDist`, `fullSectDist` | 현재 구간 이동거리와 구간 전체거리 |
| `stopFlag` | 정류장 도착 여부 |
| `congetion` | 차내 혼잡도 코드(원 API 오탈자 그대로) |
| `dataTm` | 위치정보 생성 시각 |
| `nextStId`, `nextStTm` | 다음 정류장 ID와 예상 시간 |

### 4.3 지하철 도착 `subway.arrival`

전체 건수는 `errorMessage.total`, 목록은 `realtimeArrivalList[]`이다. 두 수가 다르면 불완전 응답으로 처리한다.

| 원본 키 | 의미 |
|---|---|
| `subwayId`, `subwayList` | 호선 ID, 연계 호선 목록 |
| `statnId`, `statnNm`, `statnList` | 현재 역 ID·이름·연계 역 목록 |
| `updnLine` | 상행·하행 또는 내선·외선 |
| `trainLineNm` | 행선지와 방면을 합친 표시 이름 |
| `btrainNo` | 열차 번호 |
| `btrainSttus` | 일반·급행 등 열차 종류 |
| `bstatnId`, `bstatnNm` | 종착역 ID와 이름 |
| `barvlDt` | 도착까지 남은 초 |
| `arvlMsg2`, `arvlMsg3` | 도착 상태 문구와 현재/이전 역 문구 |
| `arvlCd` | 도착 상태 코드(진입·도착·출발 등) |
| `recptnDt` | 데이터 수신 시각 |
| `lstcarAt` | 막차 여부 |

### 4.4 지하철 위치 `subway.position`

호선별 전체 건수는 `errorMessage.total`, 목록은 `realtimePositionList[]`이다.

| 원본 키 | 의미 |
|---|---|
| `subwayId`, `subwayNm` | 호선 ID와 이름 |
| `trainNo` | 열차 번호 |
| `statnId`, `statnNm` | 열차가 있는 역 ID와 이름 |
| `updnLine` | 운행 방향 |
| `statnTid`, `statnTnm` | 종착역 ID와 이름 |
| `trainSttus` | 진입·도착·출발 등 열차 상태 코드 |
| `directAt` | 급행 여부 |
| `lstcarAt` | 막차 여부 |
| `recptnDt`, `lastRecptnDt` | 현재·직전 데이터 수신 시각 |

### 4.5 지하철 알림 `subway.alert`

목록은 `getNtceList.row[]` 또는 API 버전에 따라 `items.item[]`에 들어 있다.

| 원본 키 | 의미 |
|---|---|
| `noftTtl` | 알림 제목 |
| `noftCn` | 지연·무정차·정상화 등을 설명하는 본문 |
| `noftOcrnDt` | 알림 발생 시각 |
| `lineNmLst` | 대상 호선 목록 |
| `stnSctnCdLst` | 대상 역·구간 코드 목록. `전구간`일 수 있음 |
| `noftSeCd` | 알림 유형 코드 |
| `nonstopYn` | 무정차 관련 여부 |
| `upbdnbSe` | 상·하행 구분 |
| `xcseSitnBgngDt`, `xcseSitnEndDt` | 이례상황 시작·종료 시각 |
| `crtrYmd` | 데이터 기준일 |

### 4.6 실시간 도시데이터 `city.realtime`

장소 기본정보는 `CITYDATA.AREA_CD`, `AREA_NM`이며, 아래 묶음이 한 장소 응답에 함께 들어 있다.

| 응답 묶음 | 가져오는 정보와 주요 키 |
|---|---|
| `LIVE_PPLTN_STTS` | 혼잡도 `AREA_CONGEST_LVL`, 최소·최대 인구 `AREA_PPLTN_MIN/MAX`, 성별·연령대 비율, 인구 기준시각 `PPLTN_TIME`, 예측 `FCST_PPLTN` |
| `ROAD_TRAFFIC_STTS` | 평균 도로 속도와 도로별 소통 상태 |
| `PRK_STTS` | 주차장명·수용면수 `CPCTY`·현재 주차대수 `CUR_PRK_CNT`·요금·좌표 |
| `SUB_STTS` | 주변 지하철역·호선·주소·편의시설 |
| `LIVE_SUB_PPLTN` | 최근 5·10·30분 및 누적 지하철 승하차 최소·최대 인원 |
| `BUS_STN_STTS` | 버스정류장 ID·ARS ID·이름·좌표 |
| `LIVE_BUS_PPLTN` | 최근 5·10·30분 및 누적 버스 승하차 최소·최대 인원 |
| `ACDNT_CNTRL_STTS` | 사고·통제 발생/종료 시각, 유형, 내용, 좌표 |
| `SBIKE_STTS` | 따릉이 대여소 ID, 공유율, 주차 자전거·거치대 수, 좌표 |
| `WEATHER_STTS` | 기온·습도·풍향·풍속·강수·자외선·미세먼지·대기통합지수·24시간 예보 |
| `CHARGER_STTS` | 전기차 충전소·충전기 상태·주소·좌표·이용 제한 |
| `EVENT_STTS` | 행사명·기간·장소·좌표·유무료·URL |
| `LIVE_CMRCL_STTS` | 상권 혼잡도, 결제 건수·금액 범위, 성별·연령·개인/법인 비율, 기준시각 |
| `LIVE_DST_MESSAGE` | 재난안전 메시지 |
| `LIVE_YNA_NEWS` | 연합뉴스 목록 |

하위 데이터마다 갱신 주기가 다르지만 `citydata` API는 위 내용을 통합 응답으로 돌려준다. 현재 수집기는 장소별 통합 응답 전체를 `raw`에 보존한다.

### 4.7 실시간 돌발 `traffic.incident`

외부 XML을 다음 JSON 키로 바꿔 `raw`에 저장한다.

| JSON 키 | 원본 XML | 의미 |
|---|---|---|
| `incidentId` | `ACC_ID` | 서울시가 부여한 돌발 고유 ID |
| `occurredDate`, `occurredTime` | `OCCR_DATE`, `OCCR_TIME` | 발생 일자와 시각 |
| `expectedClearDate`, `expectedClearTime` | `EXP_CLR_DATE`, `EXP_CLR_TIME` | 종료 예정 일자와 시각 |
| `incidentTypeCode` | `ACC_TYPE` | 돌발 유형 코드 |
| `incidentDetailTypeCode` | `ACC_DTYPE` | 돌발 세부유형 코드 |
| `linkId` | `LINK_ID` | 발생 도로의 TOPIS 링크 ID |
| `grs80tmX`, `grs80tmY` | `GRS80TM_X/Y` | GRS80 TM 좌표 |
| `description` | `ACC_INFO` | 사고·공사·통제 상세 내용 |

### 4.8 TOPIS 공지 `topis.notice`

외부 XML을 다음 JSON 키로 바꿔 `raw`에 저장한다.

| JSON 키 | 의미 |
|---|---|
| `noticeId` | TOPIS가 제공한 공지 고유 ID |
| `ratingCode`, `ratingName` | 공지 등급 코드와 이름 |
| `categoryCode`, `categoryName` | 통제·버스 등 공지 분류 코드와 이름 |
| `title` | 공지 제목 |
| `contentHtml` | 원본 HTML 본문 |
| `contentText` | 태그를 제거해 분석하기 쉽게 만든 본문 |
| `updatedAt`, `createdAt` | 수정·등록 시각 |
| `detailUrl` | 상세 공지 주소 |

### 4.9 날씨 `weather.observation`

날씨는 API 항목 배열을 `observations` 객체로 정리한다.

| 키 | 의미 |
|---|---|
| `districts` | 같은 기상 격자를 사용하는 서울 자치구 목록 |
| `nx`, `ny` | 기상청 격자 좌표 |
| `baseDate`, `baseTime` | 관측 기준 일자와 시각 |
| `T1H` | 기온(℃) |
| `RN1` | 1시간 강수량(mm) |
| `UUU`, `VVV` | 동서·남북 바람 성분(m/s) |
| `REH` | 습도(%) |
| `PTY` | 강수형태 코드 |
| `VEC` | 풍향(도) |
| `WSD` | 풍속(m/s) |

## 5. 호출 한도 보호 방식

- `API_DAILY_LIMIT=900`이 기본값이다. 공식 한도 1,000회보다 100회를 남긴다.
- 자정에 단순 초기화하지 않고 **현재 시점부터 과거 24시간** 요청 수를 센다.
- 비기상 수집기 8개가 같은 Docker volume의 SQLite를 공유한다. WAL 모드와
  30초 `busy_timeout`, 짧은 `BEGIN IMMEDIATE` 트랜잭션으로 동시 쓰기를 직렬화한다.
- HTTP 요청 직전에 횟수를 저장하므로 실패한 요청과 리다이렉트도 보수적으로 포함한다.
- 같은 인증키 문자열을 여러 수집기가 사용하면 SHA-256으로 같은 키임을 알아내 합산한다. 인증키 원문은 DB에 저장하지 않는다.
- SQLite 상태는 Docker 공용 볼륨 `collector-budget`에 저장되어 컨테이너 재시작 뒤에도 유지된다.
- 전체 버스·도시 수집을 시작할 잔여 횟수가 부족하면 일부만 받고 멈추지 않도록 사이클 시작 전에 차단한다.
- `COLLECTOR_INTERVAL_SECONDS`를 작게 설정해도 각 소스의 안전한 최소 간격보다 짧아지지 않는다.

이 제한기는 우리 서버의 호출을 통제한다. 같은 키를 Postman이나 다른 서버에서도 사용하면 그 호출은 이 SQLite에 기록되지 않으므로 운영키는 수집기 전용으로 사용하는 것이 안전하다.

## 6. 서버 실행

`infra/.env.example`을 복사해 `infra/.env`를 만들고 인증키 값을 채운다. 실제 키는 Git에 커밋하지 않는다.

```bash
cd infra
docker compose -f docker-compose.yml -f docker-compose.server1.yml \
  --profile core --profile collect \
  --profile archive --profile hdfs --profile spark up -d --build
```

검증할 위치:

1. 수집기 로그: `docker compose logs -f collector-bus-arrival collector-bus-location collector-subway-arrival collector-subway-location collector-subway-alert collector-city-realtime collector-traffic-incident collector-topis-notice`
2. Kafka UI: `transport.raw`, `city.raw` 토픽의 메시지 증가 확인
3. Spark 로그: `docker logs spark-streaming-realtime --tail 100`
4. HDFS: `/ddubi/archive` 아래 Parquet 생성 확인

현재 개발 PC에서는 Docker Engine이 실행 중이지 않아 실제 Compose 서버를 띄우지는 못했다. 대신 모든 API에 대한 모의 응답을 공통 이벤트 JSON으로 변환하고, Kafka 프로토콜 모의 브로커에 실제 produce/consume하여 두 토픽에서 다시 읽히는 것까지 자동 검증한다.

## 7. 공식 데이터 페이지

- [서울시 실시간 도시데이터](https://data.seoul.go.kr/dataList/OA-21285/F/1/datasetView.do)
- [서울 지하철 알림정보](https://data.seoul.go.kr/dataList/OA-22718/A/1/datasetView.do)
- [서울시 실시간 돌발 정보](https://data.seoul.go.kr/dataList/OA-13315/A/1/datasetView.do)
- [TOPIS 공지사항](https://data.seoul.go.kr/dataList/OA-22861/A/1/datasetView.do)

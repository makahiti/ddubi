# 조건부 출발 조정 — 원천 데이터·컬럼·처리 가이드

- 수정일: 2026-09-16. [계산 정책](arrival-deadline-calculation.md), [파트별 작업 계획](arrival-deadline-implementation-plan.md)의 데이터 명세다.
- **현재**는 저장소 코드에서 확인한 것, **추가**는 이 기능을 구현할 때 만드는 계약이다. 원천에 없는 필드를 제공되는 것처럼 표현하지 않는다.
- 공식 출처는 아래 링크, 실제 파싱 필드는 Collector·Spark·Backend 코드 기준이다. 운영 키 권한·쿼터·지원 범위·데이터 최신성 검증은 별도 인수 작업이다. 이 문서는 API를 운영 호출하거나 데이터를 새로 확보했다는 증거가 아니다.
- 새로운 JSON 필드·Redis 키·Kafka 토픽은 이 문서만으로 활성화하지 않는다. Producer와 Consumer가 같은 변경에서 기계 계약·fixture·호환 테스트를 추가한다.
- 최신 대상 브랜치 대조: `dt/dev@f6905b9`의 Bus Silver, 버스 위치 재시도, 지하철 새벽 생략·빈 응답, TOPIS 공지 중복 억제까지 확인했다. 해당 기능은 아래 계획의 재사용 기반이다.

## 0. 먼저 확인할 데이터 확보 현황과 확보 작업

**이 문서는 사용자가 데이터를 모두 보유하고 있다는 전제로 작성하지 않는다.** API 공개, 수집 코드 존재, 파일 존재, 운영 수집 성공, 서비스 사용 가능은 각각 다른 상태다. 아래에서 운영 데이터의 실제 보유·권한·최신성이 입증되지 않은 항목은 모두 **확보 미확인**이다. 이는 데이터가 없다고 확정한 것도 아니며, 구현 선행 조건으로 확인·확보해야 한다는 뜻이다.

### 0.1 상태를 기록하는 방법

| 축 | 기록할 값 | 통과 증거 |
|---|---|---|
| 출처 | 공식 공개 / 제휴 / 제공기관 문의 필요 / 내부 생성 | 공식 URL·서비스명·적용 범위 |
| 코드 | 수집기 있음 / 변환기 있음 / 추가 구현 필요 | 아래 코드 링크. 코드 존재만으로 데이터 확보 처리 금지 |
| 확보 | 미확인 / 신청 중 / 샘플 확보 / 운영 검증 | 키를 뺀 실제 응답·파일 SHA·수집일·권한/쿼터 확인 기록 |
| 사용 가능 | 불가 / 제한 사용 / 검증 범위 내 사용 | ID 매핑·최신성·필수 필드·지원 지역·소비자 테스트 |

아래 원천 필드는 기존 코드가 소비하는 필드 또는 확보 시 요구할 필드다. **요구할 필드를 외부 API가 실제 제공한다고 해석하지 않는다.** 13절의 `TripStopPrediction`, `NormalizedIncident`, `EvaluationSnapshot`은 내부 설계이며 다운로드 가능한 데이터셋 이름이 아니다.

### 0.2 실시간·준실시간 데이터 확보 목록

| ID / 필요한 데이터 | 공식 제공처·신청 페이지 | 받아올 원본과 목적 | 현재 확인한 것 / 아직 확인 못 한 것 | 확보 담당의 다음 작업·미확보 시 동작 |
|---|---|---|---|---|
| S03 버스 도착예측 | 서울특별시 [버스도착정보조회](https://www.data.go.kr/data/15000314/openapi.do) | `busRouteId,stId,staOrd,mkTm,exps1/2,vehId1/2,full1/2,deTourAt`; 정류장 도착 이후 탑승 가능한 차량과 대기시간 | `collector/src/sources/bus_arrival.py` 존재. 실제 키 승인·대상 노선 수집 성공·120초 신선도는 미확인 | 공공데이터포털 활용신청→대상 routeId 샘플 수집→4.2절 필드/코드 확인→쿼터 산정. 미확보면 카카오 fallback, 실시간 대기 반영 표시 금지 |
| S04 버스 위치 | 서울특별시 [버스위치정보조회](https://www.data.go.kr/data/15000332/openapi.do) | `vehId,sectOrd,sectDist,fullSectDist,stopFlag,congetion`, 제공 좌표·시각; 차량 순서/상태 보조 | `bus_location.py` 존재. 연속 운행 기록·실제 통과시각 보유 미확인 | 별도 서비스 권한과 `getBusPosByRtid` 샘플 검증. 없으면 위치 보정 제외; 위치만으로 실제 구간 정답 생성 금지 |
| S06 지하철 도착예측 | 서울열린데이터광장 [OA-12764](https://data.seoul.go.kr/dataList/OA-12764/A/1/datasetView.do) | `subwayId,statnId,btrainNo,recptnDt,barvlDt,updnLine,bstatnNm,btrainStt,arvlCd`; 역별 도착예측 | `subway_arrival.py` 존재. 현재 운영 수집 범위·모든 하차역 예측 확보 미확인 | 인증키 신청·일괄/역별 호출 권한과 건수 확인→원본 저장→노선/역 ID 매핑. 없으면 시간표 추정; 정확한 지연분 주장 금지 |
| S07 지하철 위치 | 서울열린데이터광장 [OA-12601](https://data.seoul.go.kr/dataList/OA-12601/A/1/datasetView.do) | `subwayId,trainNo,statnId/statnNm,updnLine,statnTnm,trainSttus,recptnDt`; 열차 위치·방향·상태 | `subway_location.py` 존재. 시간표 열차와 실시간 열차의 동일성 미확인 | 노선별 실제 응답과 상태 코드 확보→serviceDate/방향 검증. 없으면 위치 기반 보정 제외 |
| S08 지하철 지연·중단 공지 | 서울열린데이터광장 [OA-22718 getNtceList](https://data.seoul.go.kr/dataList/OA-22718/A/1/datasetView.do) | `noftOcrnDt,noftTtl,noftCn,lineNmLst,stnSctnCdLst`; 공식 이유·영향 구간 후보 | `subway_alert.py` 존재. 구조화된 장애 종료·방향·열차별 적용 여부 미확인 | 인증키·구간 코드표·정정/해제 실제 예제 확보. 없으면 ETA 변화만 안내하고 사고 이유 생성 금지 |
| S09 도로 돌발·통제 | 서울 TOPIS [OA-13315 AccInfo](https://data.seoul.go.kr/dataList/OA-13315/A/1/datasetView.do) | `acc_id,link_id,acc_type,acc_dtype,occr_date/time,exp_clr_date/time,acc_info,grs80tm_x/y`; 사건 식별·링크·예상 기간 | `traffic_incident.py` 존재. FULL 통제·버스 적용·실제 종료를 확정할 필드 미확보/미검증 | 키 신청→전체 페이지 샘플→[유형 코드](https://data.seoul.go.kr/dataList/OA-13312/A/1/datasetView.do)와 7절 도로 매핑 확보. 미확보면 POTENTIAL_IMPACT만 |
| S12-N 우회·집회·행사 공지 | 서울 TOPIS [OA-22861 NoticeList](https://data.seoul.go.kr/dataList/OA-22861/A/1/datasetView.do) | `bdwr_seq,bdwr_ttl_nm,bdwr_cts,create_date,update_date,url`; 공지 원문과 수정 추적 | `topis_notice.py` 존재. 모든 사고를 포괄하거나 종료 상태를 보장하는 피드가 아님 | 이용조건·키·XML 원문과 영향 도로 근거 확보. 미확보면 구체 공지 기반 원인 안내 제외 |
| S11 현재 날씨 | 기상청 [단기예보 조회서비스](https://www.data.go.kr/data/15084084/openapi.do), [API허브](https://apihub.kma.go.kr/apiList.do?seqApi=10) | `baseDate/baseTime,nx,ny,category,obsrValue`; T1H/RN1/REH/PTY/WSD | weather 수집 코드 존재. 운영 격자·실제 관측 이력 미확인 | 사용할 플랫폼별 키 승인→대상 격자와 관측시각 확인. 누락을 DRY/0으로 채우지 않음 |
| S11-F 미래 날씨 예보 | 위 기상청 서비스의 초단기/단기예보 | `baseDate/baseTime,fcstDate/fcstTime,nx,ny,category,fcstValue`; 미래 진입시각의 예보 | 이번 확인 범위에서 예보 이력 수집 완료 증거 없음 | 예보 operation·category·단위를 별도 검증하고 발표본별 보존. 미확보면 현재 날씨를 미래 실측처럼 사용 금지 |
| S12-C 장소 혼잡 | 서울 [실시간 도시데이터 OA-21285](https://data.seoul.go.kr/dataList/OA-21285/F/1/datasetView.do) | AREA_CD/NM, 인구 혼잡/범위, 지역 도로 평균속도 및 각 갱신시각; 보조 위험 | 도시데이터 수집 경로 존재. 대상 장소·현재 응답 최신성 미확인 | 제공 장소와 서비스 지역 대조 후 수집. 지역 평균을 버스 edge 속도로 대입 금지 |
| S14 도로 링크별 소통 | 서울 TOPIS [실시간 도로 소통 OA-13291](https://data.seoul.go.kr/dataList/OA-13291/A/1/datasetView.do) | 필요한 값: 링크 ID, 속도, 통행시간, 관측시각. **정확한 raw 필드명·단위는 활용명세/샘플 확인 후 확정** | 공식 데이터셋 확인. 현재 해당 원천 수집·정규화 연결과 샘플 확보 미확인 | 수집 담당이 명세·샘플 확보→AccInfo/버스 링크와 namespace 대조→별도 adapter 제안. 확보 전 ‘교통량 증가’ 원인이나 통제 지연초 생성 금지 |

S03/S04의 서울 스키마가 수도권 전체를 지원한다는 뜻은 아니다. 경기·인천 노선은 해당 지역 공식 원천과 adapter를 따로 검증하기 전 실시간 지원 대상으로 표시하지 않는다. 원천 페이지가 ‘실시간’이라고 해도 프로젝트가 해당 주기·범위로 수집하고 있다는 증거가 되지 않는다.

### 0.3 정적·배치·제휴 데이터 확보 목록

| 필요한 데이터 | 출처·확보 경로 | 반드시 확보/검증할 값 | 로컬 근거와 확보 제한 |
|---|---|---|---|
| 카카오 후보 경로 | [카카오 REST 문서](https://developers.kakao.com/docs/ko/kakaomap/rest-api), 저장소 `kakao-public-transit-route.md`의 호출 계약 | 후보·step 순서·수단·시간·거리·정류장 이름·차량·geometry | Client/fixture 존재. 운영 endpoint 권한·지원 범위는 실제 키로 별도 검증 필요; 문서 링크만으로 사용권 보장하지 않음 |
| 접근·이탈 도보 | [카카오모빌리티 제휴 도보](https://developers.kakaomobility.com/affiliate/walking/directions.html) | 검증 endpoint, duration/distance/geometry, 응답 코드 | **제휴 권한·실제 성공 응답 확보 미확인.** 제휴 신청/확인→service 식별자·쿼터→샘플→DTO. 일반 REST 키 보유로 대체 불가 |
| 버스 노선·정류장·좌표·순서 | `backend/transit-master/bus/metropolitan-bus-v6.json.gz` 및 개별 manifest; 원천 추적은 [수도권 버스 계약](metropolitan-bus-mapping.md) | namespace,routeId,stopId,노선 순번,방향,좌표,기준일,원본 출처 | artifact 파일 존재. 원본 재확보 가능성·현 운행일 유효성·운영 DB 적재는 별도 확인. 해당 파일 존재를 전체 최신 원천 확보로 표시 금지 |
| 지하철 역·노선·좌표 | `backend/transit-master/subway/metropolitan-subway-master.sources.json`에 원문 URL·SHA; [서울 역정보 OA-21232](https://data.seoul.go.kr/dataList/OA-21232/S/1/datasetView.do) 및 manifest의 KRIC 자료 | operator,lineId,stationId,이름,좌표,순서,버전 | 로컬 마스터/출처 manifest 존재. 역 중심 좌표와 출입구·승강장 좌표는 다름; 출입구 좌표 확보 증거 없음 |
| 지하철 정적 시간표 | [서울교통공사 열차운행시각표](https://www.data.go.kr/data/15098251/fileData.do) | 호선,역사코드,주중주말,방향,급행,열차코드,도착/출발시간,시행일 | 빌더·원천 manifest 존재. 최신 파일 적용·운영자별 누락 검증 필요. 서울교통공사 파일로 전체 수도권 운행을 가정하지 않음 |
| 버스 edge↔도로 링크 | 7.2절 [BIS 노선 57](https://t-data.seoul.go.kr/dataprovide/trafficdataviewfile.do?data_id=57), [노선구간 58](https://t-data.seoul.go.kr/dataprovide/trafficdataviewfile.do?data_id=58), [구간링크 62](https://t-data.seoul.go.kr/dataprovide/trafficdataviewfile.do?data_id=62), [공간링크 63](https://t-data.seoul.go.kr/dataprovide/trafficdataviewfile.do?data_id=63), [표준링크 7](https://t-data.seoul.go.kr/dataprovide/trafficdataviewfile.do?data_id=7) | 노선/구간/링크 ID, 순번, 시작/끝 노드, 사용여부,적용기간 | audit/promote 도구 존재. 운영 가능한 COMPLETE 매핑 확보는 미확인; 도구 존재만으로 차단 기능 활성화 금지 |
| 공휴일 달력 | [한국천문연구원 특일 정보](https://www.data.go.kr/data/15012690/openapi.do) | 날짜,공휴일 여부,명칭,공개/조회일; 수집 후 serviceDate/dayType 생성 | 추가 확보·연결 필요. 운행사의 특별 시간표는 공휴일 달력만으로 알 수 없어 별도 확보 |
| 환승 통로·승강장 정보 | 현재 카카오 TRANSFER_WALK; 별도 공식 환승 자료는 원천·단위 확인 후 사용 | 출발/도착 노선·방향·승강장,통로 거리/기준시간,대기 포함 여부 | 미추적 `transfer-and-boarding.md`와 로컬 파일을 승인된 자료로 자동 채택하지 않음. 확보 전 카카오 환승 WALK 유지 |

### 0.4 공개 API로 바로 채울 수 있다고 가정하면 안 되는 데이터

| 필요한 값 | 실제 확보 상태·출처 | 확보 방법/담당 | 확보 전 제한 |
|---|---|---|---|
| 정적 열차코드↔실시간 열차 ID | **직접 대응표를 제공하는 확정 출처 미확보** | 마스터 담당이 해당 운영자에 운행일·방향·행선지별 대응 자료 요청. 운영자·제공 문서·허용범위를 확보 기록에 남김 | 이름/번호 일치만으로 결합 금지; 시간표 fallback |
| 모든 역의 열차 미래 도착/출발 예측 | S06이 필요한 모든 정차시각을 제공하는지 미확인 | 수집 담당이 실제 제공 역 범위를 측정하고 운영자 예측 피드 확보 가능성 문의 | 하차 예측은 null; 정적 이동시간은 ESTIMATED |
| 실제 버스/열차 정류장·역 통과기록 | **차량별 정답 원천 미확보**. ETA와 sparse 위치는 정답이 아님 | 수집 담당이 운수사/운영기관에 vehicle/trip,stop,실제 arrival/departure,event timestamp 기록 요청. 제공 불가면 기능 범위 축소 | ACTUAL_TRAVERSAL 기반 신규 기준선·정확도 검증을 완료했다고 표시 금지 |
| 요일·시간대별 확정 버스 배차간격 | 해당 세부 범위를 보장하는 원천·필드 미확정 | 마스터 담당이 노선/운수사 운행계획 확보, 범위형 간격과 scalar를 구별 | 미래 차량 합성 금지; API fallback/UNKNOWN |
| FULL/PARTIAL·적용 차종·방향·실제 해제 | AccInfo 현재 파싱값만으로 모두 확보되지 않음 | 공식 통제 공고/코드표·TOPIS 담당 자료에서 명시 근거 확보, 필요 시 검토 기록 | POTENTIAL_IMPACT만; BLOCKED·해제 알림 확정 금지 |
| 실제 탑승·하차·환승 완료시각 | 외부 확정 데이터셋 미확보 | 별도 사용자 동의와 수집 설계가 승인된 경우에만 앱 측정. 출발 버튼은 실제 탑승 증거가 아님 | 개인 대기·환승 실측 학습/검증 미지원 |
| 구간 P50/P80/P95·confidence·원인 영향 | **외부에서 그대로 받는 값이 아니라 내부 산출물** | 실제 fact·표본·매핑 확보 후 Spark 생성; confidence는 품질 정책으로 계산 | 입력 없으면 unknown; 분위수·원인을 임의 생성 금지 |
| 운영 가능한 ML 예측 | 내부 ML 서비스. 원천 수집 출처가 아님 | ML 담당이 학습 원본 manifest·정답 정의·지원 범위·운영 승인 증거 제공 | PREVIEW 제한 유지 |

미확정 출처에는 가짜 API 이름이나 필드명을 채우지 않는다. 담당자가 제공기관의 답변과 샘플을 확보한 뒤 상태를 갱신하거나 해당 기능을 지원 범위에서 제외한다.

### 0.5 실제 확보 작업 순서와 인계 파일

1. 데이터 담당이 위 목록의 source별로 `sourceId, officialUrl, serviceName, provider, coverage, acquisitionStatus, owner`를 작성한다. 초기 acquisitionStatus는 미확인이다.
2. 공개 API는 활용신청·승인·키별 쿼터 확인, 파일은 최신 시행일·다운로드·SHA 확인, 제휴는 사용 권한 확인을 먼저 한다.
3. 키를 제거한 정상/빈/오류 응답을 받아 `rawField → normalizedField → 타입/단위 → 필수 여부 → null 처리` 표를 실제 응답과 대조한다. 아래 상세표와 다르면 파서를 먼저 고치지 말고 계약 차이를 기록한다.
4. 실제 보유 범위인 날짜·노선·역·지역·행 수·원천 최신성·누락률을 기록한다. 샘플 1건 확보를 운영 수집 완료로 바꾸지 않는다.
5. 수집·매핑·소비자 테스트가 통과한 범위만 사용 가능으로 바꾼다. 데이터 확보 전에는 샘플 기반 어댑터 개발까지만 가능하다.

권장 인계 산출물은 **추가 예정** `docs/contracts/source-acquisition-register.csv`다. 컬럼은 `sourceId,officialUrl,serviceName,codePath,acquisitionStatus,sampleRef,sampleSha256,coverage,sourceTimeField,approvedQuota,pollIntervalSeconds,observedAgeSeconds,mappingStatus,consumer,blockedFeature,owner,verifiedAt`다. 지금은 검증 결과가 없으므로 샘플 SHA·쿼터·운영 최신성에 숫자를 만들어 넣지 않는다. 실제 raw 파일은 허용된 데이터 저장소에 두고 문서에는 참조와 마스킹한 fixture만 남긴다.

2026-09-16 출처 확인 범위: 버스 위치·서울 돌발/소통/공지·서울교통공사 시간표·카카오 도보의 공식 안내 페이지를 확인했다. 지하철 도착 페이지는 이번 조회에서 서비스 지연 응답이었으므로 최신 명세 재확인이 필요하다. 실제 인증 API 호출·키 권한·운영 데이터 보유는 이번 문서 작업에서 확인하지 않았다. 나머지 기존 출처 링크/필드는 저장소 계약·코드·원천 manifest 근거이며 최신 응답 검증 완료가 아니다.

## 1. 어떤 데이터를 누가 사용하는가

| ID | 입력·공식 출처 | 받아오는 주체 | 출력/주 소비자 |
|---|---|---|---|
| S01 | 사용자 출발 예정 P·일정 시작 D·출발지·목적지 | Client → Backend | 일정 DB → 조건부 출발 정책 |
| S02 | [카카오 대중교통 경로](https://developers.kakao.com/docs/ko/kakaomap/rest-api) | Backend PublicTransitRouteClient | 후보·WALK·총시간·geometry → 매핑·여정 평가 |
| S02-W | [카카오모빌리티 제휴용 도보 길찾기](https://developers.kakaomobility.com/affiliate/walking/directions.html) | Backend WalkingRouteClient 추가 | ACCESS_WALK·EGRESS_WALK 거리/시간/geometry. 제휴 권한 필요 |
| S03 | [서울 버스 도착정보](https://www.data.go.kr/data/15000314/openapi.do) | Collector bus_arrival | transport.raw → 최신 도착 → 버스 대기 |
| S04 | [서울 버스 위치 API](https://www.data.go.kr/data/15000332/openapi.do) `getBusPosByRtid` | Collector bus_location | transport.raw → 최신 위치 → 탑승 가능성 보조 |
| S05 | [서울교통공사 열차운행시각표](https://www.data.go.kr/data/15098251/fileData.do) | Backend 시간표 도구 | 버전 지정 JSON artifact → 시간표 서비스 |
| S06 | [서울 지하철 도착정보](https://data.seoul.go.kr/dataList/OA-12764/A/1/datasetView.do) | Collector subway_arrival | transport.raw → 역·열차별 ETA |
| S07 | [서울 지하철 열차 위치](https://data.seoul.go.kr/dataList/datasetView.do?infId=OA-12601&serviceKind=1&srvType=A) | Collector subway_location | transport.raw → 열차 위치·운행 상태 |
| S08 | [서울 지하철 알림정보](https://data.seoul.go.kr/dataList/OA-22718/A/1/datasetView.do) | Collector subway_alert | 공지·영향 노선/역 → 사건 근거 |
| S09 | [서울 실시간 돌발 AccInfo](https://data.seoul.go.kr/dataList/OA-13315/A/1/datasetView.do) | Collector traffic_incident | city.raw → 도로 사건/링크 인덱스 |
| S10 | BIS/TOPIS 도로 연결 자료, 아래 7절 | Backend 오프라인 도구 | 검증된 버스 edge ↔ 방향 도로 링크 artifact |
| S11 | [기상청 초단기실황](https://apihub.kma.go.kr/apiList.do?seqApi=10) | Collector weather | city.raw → weather 최신값/기준선 bucket |
| S12 | [서울 도시데이터](https://data.seoul.go.kr/dataVisual/seoul/guide.do), TOPIS NoticeList | Collector 해당 source | 지역 위험 보조·공지. 개별 구간 실측 아님 |
| S13 | ML HTTP·실측 구간·baseline_serving | ML/Spark/Spring 내부 API | 버스 이동 예측·기준선·구간 지연 상태 |

S04의 현재 호출 주소·필드는 [bus_location.py](../../collector/src/sources/bus_location.py), 마스터/지역별 공급자 정의는 [대기시간 계약](transit-waiting.md)을 함께 확인한다. 서울의 필드를 경기·인천에 복사하지 않는다. 기존 지역별 adapter와 namespace를 유지한다.

## 2. 공통 원본·시간·ID 규칙

### 2.1 현재 원본 envelope와 추가 품질 정보

| 필드 | 타입/단위 | 처리 규칙 |
|---|---|---|
| `eventId` | string | 현재 Collector의 발행 UUID. 같은 사건의 영속 ID와 구분 |
| `eventType`, `source` | string | bus.arrival, subway.arrival, traffic.incident 등 기존 계약 값 유지 |
| `occurredAt` | ISO 8601 +09:00 | 제공자 발생/생성시각. 원천이 없으면 수신 기준임을 별도 표시 |
| `collectedAt` | ISO 8601 +09:00 | 실제 수집시각. 오래된 원천시각을 이 값으로 새것처럼 만들지 않음 |
| `payload.raw` | object | 파싱 가능한 원본 필드와 코드 문자열 보존. 키·토큰 제외 |
| provider ID | string | 숫자 모양이어도 문자열. 이름이나 내부 segmentId로 변환해 덮지 않음 |
| `segmentIds` | string[] 또는 기존 허용 null | 검증된 대응만 채움. 미매핑을 임의 ID로 채우지 않음 |
| `sourceGeneratedAt` | timestamp nullable | 원천 시각. null이면 실시간 보증 불가 |
| `timestampBasis` | enum, 추가 표준화 | PROVIDER_GENERATED / PROVIDER_OCCURRED / RECEIVED_ONLY |
| `contentHash` | SHA-256 string | 정규화한 원천 내용 해시. 수집시각·UUID·키 제외 |
| `mappingVersion`, `masterVersion` | string nullable | 사용한 topology/crosswalk 식별. 없으면 EXACT 주장 금지 |

원천 재조회로 수집 UUID가 바뀌어도 동일 사건을 새 사건으로 알리지 않는다. 사건 identity는 `source + providerIncidentId`를 우선 사용하고 내용 변화는 별도 revision으로 관리한다. 같은 원천시각에 다른 내용이 있으면 충돌로 보존한다. 오래된 이벤트가 늦게 들어와 최신 상태를 덮지 못하게 한다.

### 2.2 수집 성공과 사건 없음의 구분 — 추가 계약

추가 `source.poll-status` 이벤트의 제안 필드는 `pollId:string`, `source:string`, `scopeKey:string`, `startedAt/finishedAt:timestamp`, `status:SUCCESS|PARTIAL|FAILED|RATE_LIMITED|SKIPPED_NON_SERVICE`, `completeSnapshot:boolean`, `pageCount:int`, `rowCount:int`, `errorCode:string|null`, `nextAllowedAt:timestamp|null`이다. Kafka key는 `source:scopeKey`, 서비스 사용자 ID는 포함하지 않는다.

- 모든 페이지와 전체 건수 검증에 성공해야 completeSnapshot=true다.
- ‘성공한 빈 전체 목록’, ‘한 페이지 빈 결과’, ‘호출 실패’를 구별한다.
- 전체 목록이 현재 활성 사건만 제공한다는 원천 보증까지 있어야 미등장을 종료 근거로 사용할 수 있다. 그렇지 않으면 UNKNOWN/STALE이다.
- Redis에 사건 키가 없다는 것만으로 ‘통제 없음’을 주장하지 않는다.
- 새 토픽·스키마·보존 정책은 [topics.yaml](topics.yaml), events 하위 신규 schema, Archiver 구독에 함께 반영할 작업이다.

## 3. S01·S02: 사용자 입력과 후보 경로

### 3.1 사용자 입력

현재 `plannedDepartAt`, `arriveBy`를 유지한다. 신규 등록에서 둘 다 필수다. 좌표는 경도/위도 순서와 지원 지역을 검증한다. 일정 이름·중요도는 별도 기존 필드다. 등록 결과가 소요시간을 입력받는 구조로 바뀌는 것은 아니다.

`P + duration`의 duration은 아래 원천을 결합한 해당 출발의 계산값이다. 앱이 `D - API총시간`으로 만든 시각을 사용자 P 대신 보내면 안 된다.

### 3.2 카카오 원천 → 내부 컬럼

현재 호출: `GET https://dapi.kakao.com/v2/routing/publictraffic`, `start_x/start_y/end_x/end_y`, 인증은 헤더. 원천·시간 분해 근거: [카카오 계약](kakao-public-transit-route.md).

| raw 경로 | 내부 컬럼 | 타입/단위 | 사용 |
|---|---|---|---|
| `routes[]` 배열 위치 | providerCandidateIndex | int | 원천 후보 순서 추적. 순위라고 가정하지 않음 |
| `route.properties.totalTime` | providerTotalSeconds | number, 초 | 전체 fallback. 단계 합과 중복 합산 금지 |
| `route.properties.totalDistance` | totalDistanceMeters | number, m | 거리 표시·품질 확인 |
| `steps[].properties.type` | mode | BUS/SUBWAY/WALK | WALKING → WALK |
| `steps[].properties.time` | providerStepSeconds | number, 초 | 원천 단계시간 |
| `steps[].properties.distance` | stepDistanceMeters | number, m | 단계 거리 |
| `steps[].properties.stops[].name` | fromName/toName/intermediateNames | string | 매핑 후보 증거. 안정 ID 아님 |
| `steps[].properties.vehicles[].name/type` | providerRouteKey/busAlternatives | string/array | 차량 대안을 별개로 보존 |
| `steps[].path.points` | geometry | [lon,lat][] | 노선·방향·경유 검증 |
| 전체시간 − step시간 합 | unassignedDurationSeconds | number, 초 | 의미 미확정 잔여. 대기로 단정 금지 |

응답 DTO의 실제 nesting은 `PublicTransitRouteClient`와 fixture를 기준으로 한다. 여기서 `route`는 routes 배열의 한 원소다. 이름만으로 canonical ID를 발급하지 않는다. `MasterDataTransitMappingResolver`의 EXACT 결과와 공식 인접 edge를 이용한다. PARTIAL/AMBIGUOUS/UNMAPPED는 이유를 남기고 API fallback으로 제한한다. WALK segmentId는 현재 계약대로 null이다.

### 3.3 step 역할 분류와 승·하차 좌표

대중교통 응답을 받은 다음 후보마다 아래 값을 만든다.

| 내부 필드 | 타입/nullable | 산출 규칙 |
|---|---|---|
| `legRole` | ACCESS_WALK/TRANSFER_WALK/EGRESS_WALK/RIDE | WALK의 앞·뒤에 존재하는 BUS/SUBWAY index로 분류. BUS/SUBWAY는 RIDE |
| `boardingCoordinateCandidate` | Coordinate nullable | 첫 BUS/SUBWAY step `path.points[0]` |
| `alightingCoordinateCandidate` | Coordinate nullable | 마지막 BUS/SUBWAY step `path.points[last]` |
| `mappedBoardingCoordinate` | Coordinate nullable | 첫 transit step의 EXACT 마스터 승차 node 좌표 |
| `mappedAlightingCoordinate` | Coordinate nullable | 마지막 transit step의 EXACT 마스터 하차 node 좌표 |
| `boardingCoordinate` / `alightingCoordinate` | Coordinate nullable | 카카오 후보와 마스터 좌표가 정책 허용 오차 안에서 유일하게 일치한 endpoint |
| `coordinateEvidence` | object | provider point, master ID/version, 거리 차이 m, 정책 버전, 판정 |

현재 `KakaoPublicTransitResponse.Stop` DTO는 `name`만 받고 실제 fixture도 stop 좌표를 제공하지 않는다. 따라서 stop 객체에 존재한다고 가정해 x/y 필드를 만들지 않는다. `path.points`의 첫·끝도 노선 polyline endpoint일 뿐 승강장 출입구 보증이 없으므로 EXACT 마스터 좌표와 검증한다. 지하철 마스터가 역 중심점만 제공해 출입구가 확인되지 않으면 `STATION_CENTER_ONLY`로 품질을 낮추고 도보 API endpoint 정확성을 보장하지 않는다.

좌표 검증 임계값은 실제 corpus로 정해 `walking-endpoint-policy-v1`에 둔다. 문서 단계에서 임의 50m 등을 확정하지 않는다. 거리 계산은 WGS84 geodesic/Haversine m 단위이며 경도·위도 순서를 바꾸지 않는다. 하나의 step에 차량 대안이 여러 개면 선택한 EXACT 대안의 좌표를 사용하고 서로 다른 대안 좌표를 섞지 않는다.

### 3.4 S02-W 카카오 제휴 도보 API

공식 호출 규격은 다음과 같다.

- `GET https://apis-navi.kakaomobility.com/affiliate/walking/v1/directions`
- Header: `Authorization: KakaoAK ${REST_API_KEY}`, `service: {제휴 등록 서비스 식별자}`, `Content-Type: application/json`.
- Query: `origin={longitude},{latitude}`, `destination={longitude},{latitude}`, `priority=DISTANCE`, `summary=false`. `waypoints`는 접근·이탈 기본 호출에서는 사용하지 않는다.
- `default_speed=0`이면 공식 문서상 4km/h 기준 ETA다. 사용자 보행속도 정책을 도입하기 전에는 값을 임의 변경하지 않는다.
- 이 API는 제휴 파트너 전용이므로 별도 계약·권한·쿼터 확인을 배포 선행 조건으로 둔다. 일반 카카오맵 REST API 권한과 동일하다고 가정하지 않는다.

후보마다 최대 두 번 호출한다.

| 호출 | origin | destination | 결과 역할 |
|---|---|---|---|
| 접근 | 사용자 일정의 출발 좌표 | 검증된 첫 승차 좌표 | ACCESS_WALK |
| 이탈 | 검증된 마지막 하차 좌표 | 사용자 일정의 목적지 좌표 | EGRESS_WALK |

도보 응답 DTO는 공식 응답의 route summary와 sections/roads/guides 전체를 원문 fixture로 먼저 검증한 다음 필요한 값을 `distanceMeters`, `durationSeconds`, `geometry`, `guides`, `providerRouteIndex`로 정규화한다. 응답 스키마를 확인하지 않고 자동차 길찾기의 필드 구조를 복사하지 않는다. 시간·거리 음수, 빈 route, 시작/종료 endpoint가 요청 좌표와 크게 다른 응답은 거부한다.

캐시 키는 `walkingApiVersion + endpointPolicyVersion + roundedOrigin + roundedDestination + priority + defaultSpeed`를 제안한다. 좌표 반올림 정밀도와 TTL은 정확도·호출 예산 실측으로 정하며 서로 다른 정류장/출입구가 합쳐지지 않게 한다. API 키·사용자 일정 제목·원문 개인정보를 캐시 키나 로그에 넣지 않는다.

fallback 순서는 `검증된 별도 도보 API → 동일 역할의 카카오 대중교통 WALK → provider 전체시간 + DATA_INSUFFICIENT`다. 직선거리/고정 보행속도로 확정 시간을 만들지 않는다. 대중교통 응답에 ACCESS/EGRESS WALK가 없고 별도 도보 API도 실패하면 0초로 두지 않는다.

### 3.5 비용 범위와 중복 방지

`providerTotalSeconds`에는 step 밖 접근·대기·환승 시간이 포함될 수 있다. 별도 도보 API의 duration을 무조건 더하면 중복될 수 있다.

1. 원본 step별 `legRole`, 시간, 거리와 별도 WALK를 후보 snapshot에 모두 보존한다.
2. 대중교통 ACCESS/EGRESS WALK가 있으면 동일 역할의 별도 결과로 교체하며 두 값을 더하지 않는다.
3. 해당 WALK step이 없으면 `unassignedDurationSeconds`에 도보가 포함되지 않았다는 검증 전에는 provider 총시간에 추가하지 않는다.
4. WAIT/RIDE/TRANSFER/ACCESS/EGRESS의 비용 범위가 완전히 검증된 후보만 componentized ETA를 만든다.
5. 불완전한 후보는 provider 전체시간 fallback과 별도 도보 정보의 미적용 이유를 반환한다. 일부만 합친 ETA를 운영 추천으로 사용하지 않는다.

## 4. S03·S04: 버스 도착·위치

### 4.1 API와 키

- 도착: `http://ws.bus.go.kr/api/rest/arrive/getArrInfoByRouteAll`, 요청 `busRouteId`, `serviceKey`.
- 위치: `http://ws.bus.go.kr/api/rest/buspos/getBusPosByRtid`, 요청 `busRouteId`, `serviceKey`.
- 응답 itemList를 수집한다. 상위 오류 코드·빈 목록·부분 오류를 먼저 검사한다.
- 도착 키: `transit:latest:bus-arrival:{providerRouteId}:{providerStopId}`.
- 위치 키: `transit:latest:bus-position:{providerRouteId}:{providerVehicleId}`.
- 매핑은 `SEOUL_BUS + routeId + stopId + stopOrder/occurrence + masterVersion`으로 검증한다. 순환노선은 같은 정류장 재등장 위치를 구별한다.

### 4.2 원천 → 현재 Spark 컬럼 → 사용

| raw | 정규화 컬럼 | 타입/단위 | 처리 |
|---|---|---|---|
| busRouteId 또는 호출 routeId | providerRouteId | string | 호출 문맥과 응답 일치 검사 |
| stId | providerStopId | string | 도착 정류장 |
| staOrd | stopOrder | int | 노선 내 정류장 순번 |
| mkTm | sourceGeneratedAt | timestamp | 도착예측의 기준시각 |
| exps1 / exps2 | firstArrivalSeconds / secondArrivalSeconds | int, 초 | 예상 절대 도착 = mkTm + exps |
| traTime1 / traTime2 | firstTravelTimeMinutes / secondTravelTimeMinutes | double, 분 | exps와 다른 의미. 대기초로 오독 금지 |
| vehId1 / vehId2 | firstVehicleId / secondVehicleId | string | 해당 도착예측 차량 식별 |
| sectOrd1 / sectOrd2 | firstVehicleSectionOrder / secondVehicleSectionOrder | int | 차량 구간 순서. 남은 정류장 수라고 간주하지 않음 |
| isArrive1 / isArrive2 | firstTerminalArrivalCode / secondTerminalArrivalCode | string code | 종점 도착 상태. 코드표·현재 필터 보존 |
| full1 / full2 | firstFullCode / secondFullCode | string code | 만차 등 탑승 가능성 필터 |
| deTourAt | detourCode | string code | 우회 운행. 정상 경로 적용 가능성 검증 |
| arrmsg1 / arrmsg2 | firstArrivalMessage / secondArrivalMessage | string | 표시·원인 후보. 수치 파싱 대체 수단 아님 |
| 위치 vehId / sectOrd | providerVehicleId / sectionOrder | string/int | 같은 차량의 구간 확인 |
| 위치 sectDist / fullSectDist | sectionDistanceMeters / fullSectionDistanceMeters | double, m | 위치 해석 보조, 곧바로 구간 통과 실측 아님 |
| 위치 congetion | congestionCode | code | 원천 철자를 보존한 parser. 혼잡 코드를 인원수로 바꾸지 않음 |
| 위치 stopFlag | stopFlag | code | 정차 상태 보조 |

현재 `BusWaitingService`의 신선도는 120초이고, exps는 양수·상한 7200초 및 차량 ID·우회·종점·만차 조건을 검사한다. 새로운 정책을 넣을 때 기존 검증을 제거하지 않는다. 원천별 0 값의 의미를 검증하기 전 도착 즉시 탑승 가능으로 바꾸지 않는다.

승강장/정류장 도착을 a라 하면, 검증된 차량 시각 b 중 `b >= a`인 차량을 찾고 `wait=b-a`로 계산한다. 소스가 차량 도착과 출발/승차마감 시각을 구분하지 않으면 그 오차를 한계로 남긴다. 관측되지 않은 세 번째 차량이나 미래 운행을 첫째/둘째 차량으로 외삽하지 않는다.

먼 미래 계획 대기의 `0.6 × 배차간격`은 기존 결정과 실제 구현 여부를 구분한다. 배차간격 H의 공식 노선 마스터 값·시간대·요일·단위를 확보하고 scalar H가 검증된 경우만 적용한다. H가 ‘8~15분’이면 중간값을 임의 사용하지 않는다. 미확보면 API fallback/UNKNOWN이며, 이 작업에 정확한 버스 시간표가 이미 있다고 가정하지 않는다.

### 4.3 목표 브랜치의 Bus Silver 재사용

`dt/dev@f6905b9`에서 `sparkjobs/common/bus_normalizer.py`의 COMMON/POSITION/ARRIVAL이 필드 타입 기준이다. 공통 컬럼 `silverVersion,eventId,eventType,source,providerNamespace,providerRouteId,providerStopId,providerVehicleId,observedAt,collectedAt,archiveDate,timestampBasis,claimedSegmentIds,claimedMappingJson,rawPayloadJson,missingFields,qualityFlags,rejectionReasons,status`를 재사용한다.

위치에는 longitude/latitude/providerStopOrder/sectionOrder/sectionDistanceMeters/fullSectionDistanceMeters/congestionCode/providerStateCode/directionCode/stopFlag, 도착에는 위 4.2의 필드와 firstRemainingStops/secondRemainingStops 등이 있다. 없는 공급자 필드는 null이며 서울 sectOrd를 remainingStops로 옮기지 않는다. timestampBasis의 기존 provider/received 값을 새 표준 enum과 연결할 때 adapter가 필요하다.

현재 버스 Redis는 이 표준 컬럼을 최상위에, 기존 소비용 observation/location/providerIds를 함께 둔다. observedAt이 없으면 occurredAt/validUntil도 null이다. `_latestOrderAt`은 수집 순서 비교용이지 관측시각이 아니다. Silver가 도착 0초를 보존하는 것과 Backend가 해당 값으로 안전한 탑승을 확정하는 것은 다른 검증 단계다.

HDFS의 `batches/batch=*/data`에서 complete가 있는 배치, `status=ACCEPTED AND repeatIndex=1`을 분석 입력으로 선택한다. 배치 간 eventId 중복과 내용 충돌은 다시 확인한다. archiveDate는 수집일이며 운행일이 아니다. 기존 observedAt과 품질 정보를 버리고 raw parser를 새로 만드는 작업을 하지 않는다. 실제 통과 정답은 여전히 별도 확보·검증해야 한다.

## 5. S05~S07: 지하철 시간표·실시간

### 5.1 정적 시간표 CSV → 런타임 artifact

기존 도구 [build_timetable.py](../../backend/tools/transit-timing/build_timetable.py)를 확장한다. 원본 CP949를 정확히 읽고 파일 SHA-256·공식 배포일·적용일을 manifest에 기록한다.

| CSV 원문 컬럼 | artifact 컬럼 | 규칙 |
|---|---|---|
| 호선 | lineId/providerLineId | 정적 호선과 실시간 호선 ID를 crosswalk로 구분 |
| 역사코드 | calls[].stationId/providerStationId | 정적 역 → 실시간 역 검증 매핑 |
| 주중주말 | service calendar 분류 | DAY/SAT/END 선택을 serviceDate에 연결. 공휴일을 단순 요일로 처리 금지 |
| 방향 | direction/arrivalDirection/positionDirection | 각 소스 코드가 같은 뜻인지 검증 |
| 급행여부 | trainType | 현재 일반열차 지원. 급행은 정차 패턴 검증 전 제외 |
| 열차코드 | trip key의 trainCode | 실시간 trainId와 동일하다고 가정하지 않음 |
| 열차도착시간 | calls[].arrivalAt | serviceDate + HH:MM:SS, 24~29시 허용 |
| 열차출발시간 | calls[].departureAt | 위와 동일. 중간역 시각 누락은 연결 불가 |

현재 artifact는 `version, masterDataVersion, source, publishedAt, validFrom, validUntil, trips, limitations`를 포함한다. trip은 `key,lineId,providerLineId,direction,trainId,trainType,serviceDate,calls`와 방향/행선지 검증 필드를 갖는다. **현재 빌더는 실시간 trainId 대응을 만들어 주지 않는다.** 정적 열차코드를 trainId에 복사하면 안 된다.

추가 train crosswalk 컬럼: `serviceDate:date, staticTripKey:string, providerLineId:string, providerTrainId:string, direction:string, destinationStationId:string, trainType:string, validFrom/validUntil:timestamp, evidenceRef:string, matchStatus:EXACT|AMBIGUOUS|UNMAPPED, crosswalkVersion:string`. 날짜·방향·행선지·역 순서와 운영자 근거가 유일하게 일치한 항목만 EXACT로 승격한다. 자동으로 유일성을 입증 못 하면 검토 자료를 남기고 실시간 결합을 비활성화한다.

공휴일 달력은 버전 지정 `serviceDate,dayType,sourceRef,publishedAt` artifact로 공급하는 기본안이다. 현재 빌더의 수동 dayType 입력을 공휴일 자동 연동 완료로 표현하지 않는다. 서울교통공사 자료가 모든 수도권 운영자의 모든 운행을 포함한다고 가정하지 않는다.

### 5.2 실시간 입력

현재 도착 수집 경로는 `.../api/subway/{key}/json/realtimeStationArrival/ALL/`, 위치는 `.../api/subway/{key}/json/realtimePosition/0/1000/{line}/`이다. 호스트는 `swopenapi.seoul.go.kr`. 키 마스킹 후 로그를 남긴다. 일괄 건수와 누락 여부를 검증한다.

| 도착/위치 raw | 정규화 컬럼 | 타입/단위·용도 |
|---|---|---|
| subwayId | providerLineId | string, 실시간 노선 ID |
| statnId | providerStationId | string, 실시간 역 ID |
| btrainNo 또는 trainNo | providerTrainId | string, 같은 운행일 내 열차 식별 후보 |
| recptnDt | sourceGeneratedAt | timestamp, 예측 생성시각 |
| 도착 barvlDt | arrivalSeconds | int, 초. 절대 ETA = recptnDt + barvlDt |
| 도착 arvlCd | arrivalStatusCode | code, 진입/도착/출발 상태 검증 |
| 도착 arvlMsg2 / arvlMsg3 | arrivalMessage / previousStationMessage | 원문 안내. 숫자 지연분의 보증 아님 |
| 도착 updnLine | arrivalDirection | 원천 방향 문자열 |
| 도착 bstatnNm / btrainStt | arrivalDestination / arrivalTrainType | 행선지·일반/급행 |
| 위치 updnLine / statnTnm | direction / destinationStation | 방향·종착역 검증 |
| 위치 statnNm / trainSttus | stationName / trainStatusCode | 현재 역·상태, 속도 실측 아님 |

현재 키는 `transit:latest:subway-arrival:{providerLineId}:{providerStationId}:{providerTrainId}`, `transit:latest:subway-position:{providerLineId}:{providerTrainId}`다. fresh 기준 120초를 평가 시 재검사한다. `recptnDt + barvlDt`를 계산하면 수집 지연을 중복 더하지 않는다. 현재시각에 barvlDt를 더하면 오래된 예측만큼 도착을 늦추는 오류다.

### 5.3 지연을 계산할 수 있는 범위와 추가 출력

동일 trip/stop의 계획 도착 s, 관측된 예측 도착 e를 유일하게 연결한 경우에만 `scheduleDeviationSeconds=e-s`를 만든다. signed 값을 보존하고 서비스 지연 표시에는 max(0,값)을 사용한다. 이전 ETA보다 느려진 값은 별도 `predictionChangeSeconds`로 두며 공식 사고 원인과 구분한다.

추가 `TripStopPrediction` 계약:

| 필드 | 타입 | 생성 근거 |
|---|---|---|
| serviceDate, tripKey, providerTrainId | date/string | 검증된 trip crosswalk |
| stationId, stopSequence | string/int | 같은 trip의 실제 정차 순서 |
| scheduledArrivalAt/scheduledDepartureAt | timestamp nullable | 정적 시간표 |
| predictedArrivalAt/predictedDepartureAt | timestamp nullable | 해당 역에 실제 제공되는 예측만 채움 |
| observedArrivalAt/observedDepartureAt | timestamp nullable | 실측으로 확인된 경우만 채움 |
| stopStatus | SCHEDULED/SKIPPED/CANCELED/UNKNOWN | 구조화된 공식 정보 또는 검증된 적용 규칙 |
| observedAt, validUntil | timestamp | 원천 시각과 신선도 |
| predictionBasis | REALTIME/TIMETABLE/ESTIMATED | 각 시각의 출처 |
| incidentRefs | string[] | 적용이 검증된 사건 |

현재 수집 데이터만으로 모든 역의 예측 출발·실제 도착이 채워지지 않는다. 없는 값은 null이다. 승차역 실시간 + 하차역 정적 소요를 쓰면 `ESTIMATED`로 표시하며 ‘전체 지연 반영 완료’라고 하지 않는다. 현재 열차 위치·남은 역·배차간격은 후보 제거/추정 입력이지 정확한 미래 속도의 증거가 아니다.

취소·무정차·중단이 확인되면 정적 시간표로 되돌려 탑승 가능으로 만들지 않는다. 후속 열차·다른 경로를 탐색한다. 미래 시간표 역산은 정상 운행 가정이고, 실시간이 적용 가능한 짧은 horizon 밖에서는 품질을 별도 표시한다.

## 6. S08: 지하철 공지

현재 source는 `getNtceList`, 경로 `http://openapi.seoul.go.kr:8088/{key}/json/getNtceList/{start}/{end}/`다. 현재 파서/정규화 근거: [subway_alert.py](../../collector/src/sources/subway_alert.py), [kafka_adapter.py](../../collector/src/sources/kafka_adapter.py).

| raw | 내부 필드 | 사용/추가 작업 |
|---|---|---|
| noftOcrnDt | sourceGeneratedAt / officialNoticeAt | 공지 발생시각. 실제 장애 시작과 같다고 단정하지 않음 |
| noftTtl | alertTitle | 원문 제목 |
| noftCn | alertContent | 원문 본문, 공식 사유 표시 후보 |
| lineNmLst | alertLineName | 노선명 목록 → 검증된 노선 ID 매핑 |
| stnSctnCdLst | providerStationSections | **추가 정규화**. 코드표·구간 방향 검증 후 역/edge 전개 |

현재 합성 alert ID는 발생시각·제목·노선·역구간 등을 해시한다. 본문 수정과 제목 수정 시 identity/revision 처리가 다르므로 해제/정정 이벤트를 단순 신규 사고로 세지 않게 보완한다. 원천 종료시각·구조화된 취소 필드가 항상 있다고 가정하지 않는다.

노선명이 일치한다는 이유만으로 전 노선·반대 방향을 차단하지 않는다. 명시적인 전 노선 중단이면 그 범위를 사용하고, 구간 미확정이면 노선 관련 공지로만 표시한다. 자연어 공지에서 LLM이 추정한 시각·차단 범위를 확정 상태로 자동 승격하지 않는다. 규칙/수동 검토의 버전·근거 구절을 보존한다.

## 7. S09·S10: 도로 통제와 실제 경로의 연결

### 7.1 AccInfo 원천 → 정규화

현재 `traffic_incident.py`는 XML을 읽으며 대소문자를 낮춘 providerFields를 함께 보존한다. API: `http://openapi.seoul.go.kr:8088/{key}/xml/AccInfo/{start}/{end}/`. 페이지 최대 1000건, 모든 페이지 성공 여부를 기록한다.

| 원문 XML 이름(소문자 기준) | 현재 정규화 필드 | 타입/용도 |
|---|---|---|
| acc_id | incidentId → providerIncidentId | string, 사건 identity |
| occr_date / occr_time | occurredDate / occurredTime → occurredAt | 서울 기준 시작시각 후보 |
| exp_clr_date / exp_clr_time | expectedClearDate / expectedClearTime | 예상 해제. 실제 종료로 간주 금지 |
| acc_type / acc_dtype | incidentTypeCode / incidentDetailTypeCode | 원천 코드표 버전과 함께 해석 |
| link_id | linkId → providerLinkId | string, namespace `SEOUL_ACCINFO_LINK` |
| grs80tm_x / grs80tm_y | grs80tmX / grs80tmY | 원천 투영좌표. WGS84로 오독 금지 |
| acc_info | description | 공식 상황 설명. 완전/부분 통제 추정의 유일 근거로 사용 금지 |
| 전체 원문 필드 | providerFields | 원본 추적·파서 재처리 |

이 API의 현재 정규화 항목에는 ‘모든 차로 완전 통제’, ‘버스 통행 금지’, ‘정확한 적용 방향’, ‘실제 해제’가 검증된 구조화 값으로 모두 제공되지 않는다. 따라서 기존 기본 결과 `POTENTIAL_IMPACT`를 바로 `BLOCKED`로 변경할 수 없다. **필요한 통제 사실은 공식 코드표/공지/검토된 대응 자료로 추가 확보해야 한다.**

좌표를 사용할 경우 원천 CRS와 변환 EPSG를 먼저 확인하고 artifact에 보존한다. 문서 이름에 GRS80이 있다는 이유만으로 한 EPSG를 임의 선택하지 않는다. 검증된 링크 ID 연결이 있으면 좌표 근접 추정보다 우선한다.

### 7.2 공식 파일의 정확한 연결 컬럼

| 파일·출처 | 읽는 컬럼 | 조인·검증 |
|---|---|---|
| [BIS 노선별 경유노드, 58](https://t-data.seoul.go.kr/dataprovide/trafficdataviewfile.do?data_id=58) | 노선ID, 사용여부, 노드ID, 정류장순번, 노드일련번호, 구간ID | routeId + 정류장 ID + occurrence 순번으로 승하차 위치를 유일하게 찾음 |
| [BIS 구간링크, 62](https://t-data.seoul.go.kr/dataprovide/trafficdataviewfile.do?data_id=62) | 구간ID, 구간순번, 링크ID | 연속된 구간순번으로 실제 통과 링크 전개 |
| [BIS 공간 링크, 63](https://t-data.seoul.go.kr/dataprovide/trafficdataviewfile.do?data_id=63) | 링크ID, 사용여부, 시작노드ID, 종료노드ID | 인접 링크의 끝→시작 일치로 방향 검증 |
| [TOPIS 표준링크, 7](https://t-data.seoul.go.kr/dataprovide/trafficdataviewfile.do?data_id=7) | 표준링크ID, 링크ID, 시작노드ID, 종료노드ID | 표준 ID와 방향 노드가 함께 일치할 때 서비스 링크 후보 생성 |
| [서울 표준링크 매핑](https://data.seoul.go.kr/dataList/OA-15061/S/1/datasetView.do) | 제공 파일의 표준↔서비스 ID | 보조 대조. 파일 버전·컬럼 헤더는 입수 파일 manifest로 고정 |

근거는 [audit.py](../../backend/tools/road-impact/audit.py)다. CSV 셀 BOM을 정리하되 원본 SHA를 보존한다. 같은 구간/순번의 다른 내용, 끊긴 링크, 여러 대응 후보는 제외한다. BIS 화면 표출 좌표 자료(56)는 실제 WGS84 route geometry로 사용하지 않는다.

TOPIS 링크 숫자와 AccInfo link_id가 같아 보여도 namespace 동일성이 입증되지 않으면 연결 완료가 아니다. 운영 artifact 승격은 `promote.py` 검증과 공식 자료 적용기간·방향·원천 동일성 검토를 통과해야 한다.

### 7.3 운영용 매핑 artifact

현재 구조를 재사용할 필수 정보: `masterVersion`, `busCrosswalkSha256`, 파일 SHA, 그리고 mappings의 `segmentId`, `routeId`, `fromStopId`, `toStopId`, `fromOrder`, `toOrder`, `direction`, `coverage=COMPLETE`, `directionVerified=true`, `evidenceMethod=OFFICIAL_ROUTE_LINKS|REVIEWED_ROUTE_GEOMETRY`, `evidenceRef`, `validFrom`, `validUntil`, `roadLinks[{namespace,linkId}]`.

하나의 BUS edge가 여러 도로 링크를 지날 수 있다. partial coverage이면 누락 부분의 무통제를 보장할 수 없다. 동일 segmentId가 순환 occurrence를 구별하지 못하면 위 순번을 추가 결합 키로 사용한다. Backend와 Spark에는 동일 버전 artifact를 공급하고 checksum 불일치 시 확정 영향 연산을 거부한다.

### 7.4 사건 상태·영향 출력 — 추가 계약

| 제안 컬럼 | 타입/nullable | 생성·소비 규칙 |
|---|---|---|
| incidentKey | string, 필수 | hash(source + providerIncidentId). 원천 ID 없는 공지는 합성 근거 명시 |
| revision | string, 필수 | 의미 있는 원천 내용 hash. 매 poll UUID 아님 |
| source, providerIncidentId, evidenceRef | string | 출처·추적 원문 식별자. 키 포함 URL 금지 |
| kind | ROAD_CLOSURE/PARTIAL_RESTRICTION/TRANSIT_DISRUPTION/UNKNOWN | 공식 코드·검증된 공지 매핑 |
| lifecycle | ACTIVE/ENDED/STALE/UNKNOWN | 만료와 해제를 분리 |
| restriction | FULL/PARTIAL/UNKNOWN | 확인 자료 없으면 UNKNOWN |
| affectedModes | string[] | 버스/자동차/도보 등 확인된 적용 범위만 |
| startAt, expectedEndAt, confirmedEndAt | timestamp nullable | 예상 종료와 실제 종료 별도 |
| sourceGeneratedAt, observedAt, validUntil | timestamp | 발생·관측·유효성 분리 |
| affectedEdges | object[] | segmentId + occurrence + 방향 + mappingVersion |
| affectedTrips/stops | object[] | 지하철 운행일/열차/정차 범위, 확인된 경우만 |
| matchStatus | EXACT/PARTIAL/AMBIGUOUS/UNMAPPED | ID/방향/범위 검증 결과 |
| sourceEventIds | string[] | 원본 재처리 추적 |
| measuredDelaySeconds | number nullable, 초 | 대응된 관측/모델 결과가 있을 때만. 사건 존재만으로 만들지 않음 |

현재 `road:incidents:{namespace}:{linkId}` hash와 `road:incident-index:{sha}`는 재사용한다. 원천시간 비교·내용 충돌·링크 이동 CAS를 유지하면서 새 revision/lifecycle을 확장한다. Backend용 road.impact의 기존 POTENTIAL_IMPACT 소비자와 호환되는 v2 또는 선택 필드 도입이 먼저 필요하다.

차단 판정은 `EXACT 매핑 ∧ 적용 방향/수단 일치 ∧ FULL 확인 ∧ 실제 통과시간과 유효 통제기간 겹침`이다. 시간범위는 `[startAt,endAt)`로 정의하고 경계 테스트를 한다. 종료 미확정이면 자료의 유효성 범위 안에서만 보수적 위험/통과 불확실로 처리한다. 도로 일부 차로 통제는 경로 제외 조건이 아니며, 새 ETA를 구간 관측·모델 또는 제공자 재조회로 산출한다.

## 8. S11·S12: 날씨·도시·일반 공지

### 8.1 기상청

현재 endpoint는 API허브 `getUltraSrtNcst` 또는 공공데이터포털 동명 API다. 입력은 `base_date,base_time,nx,ny,dataType,pageNo,numOfRows`와 인증키다. 현재 Collector의 기준시각 선택은 현재−40분의 정시이고 14개 중복 제거 서울 격자를 사용한다. 발표 지연·빈 결과를 구별해야 한다.

| 원천 category/필드 | 정규화 값 | 단위·사용 |
|---|---|---|
| baseDate/baseTime | occurredAt | 발표/관측 기준 서울 시각 |
| nx/ny | nx/ny | 정수 격자. 좌표 매핑 버전 고정 |
| T1H | temperatureC | °C |
| RN1 | precipitationMm | 1시간 강수 mm. 결측 문자열을 0으로 변환 금지 |
| REH | humidityPercent | % |
| PTY | precipitationType / weatherBucket | 원천 코드표에 따른 DRY/RAIN/SNOW. 결측→DRY 금지 |
| WSD | windSpeedMps | m/s |
| UUU/VVV/VEC | 바람 보조 관측 | 원천 단위 보존, 사용하지 않으면 raw로만 보존 |

`weather:latest:{nx}:{ny}` TTL은 7200초다. source시각·validUntil을 별도로 확인한다. PTY에서 적설 깊이를 만들 수 없으므로 snowDepthCm은 별도 실측이 없으면 null이다. 날씨는 현재 ML에 임의 컬럼으로 추가하지 않는다. [ML 데이터 결정](ml-data-decision.md)의 학습/예측 일치와 모델 버전 변경이 선행이다.

### 8.2 도시데이터

현재 `citydata/1/5/{area_identifier}`를 장소별 호출하며 장소 설정은 collector/config 아래 버전 관리한다. 전체 지역 수·범위는 설정과 공식 목록을 비교한다.

| raw 하위 경로 | 현재 정규화 컬럼 | 사용 제한 |
|---|---|---|
| AREA_CD / AREA_NM | providerAreaId / 장소 이름 | 장소 단위 식별, segmentId 아님 |
| LIVE_PPLTN_STTS.AREA_CONGEST_LVL | areaCongestionLevel | 지역 혼잡 위험 보조 |
| LIVE_PPLTN_STTS.AREA_PPLTN_MIN/MAX | areaPopulationMin/areaPopulationMax | 추정 인구 범위. 정확 인원 아님 |
| ROAD_TRAFFIC_STTS.AVG_ROAD_DATA_SPEED | areaRoadSpeedKph | 지역 평균 속도. 개별 버스 edge 속도로 대입 금지 |

실제 배열/객체 변형은 `realtime_latest.py`의 fallback JSON path를 재사용한다. 공간 연결 범위·관측시각 없이 구간 지연 원인으로 사용하지 않는다. 별도 `traffic.flow` parser가 있다는 이유만으로 링크별 속도 Collector가 완성됐다고 간주하지 않는다.

### 8.3 TOPIS NoticeList

원천 XML → `topis_notice.py`: `bdwr_seq→noticeId`, `bdwr_ttl_nm→title`, `bdwr_cts→contentHtml/contentText`, `bdwr_div_cd/nm→categoryCode/categoryName`, `bdwr_rtng_cd/nm→ratingCode/ratingName`, `update_date/create_date→updatedAt/createdAt`, `url→detailUrl`.

HTML은 표시용 텍스트로 안전하게 정리한다. 원문 시각·분류·본문을 남기되 제목만으로 전 차로 통제·정확한 영향시각을 확정하지 않는다. source+noticeId는 identity, updatedAt+내용 hash는 revision이다. 원인 안내에는 검증된 구간 대응이 추가로 필요하다.

목표 브랜치의 `collector/src/notice_dedup.py`는 TOPIS만 noticeId와 정렬한 raw payload hash로 중복을 억제하며 Kafka ACK 뒤 SQLite에 성공을 기록한다. 내용이 같으면 Kafka로 재발행하지 않으므로 현재 Redis 2700초 TTL은 계속 게시된 공지도 만료시킬 수 있다. 전체 공지 존재·종료 판단에는 위 2.2의 poll/snapshot 계약이 추가로 필요하다. 기존 dedup을 삭제하거나 같은 본문을 매번 신규 사건으로 발행하는 방식으로 해결하지 않는다.

## 9. S13: ML·실측·기준선·구간 상태

### 9.1 현재 ML HTTP 입력과 출력

현재 `POST /v1/bus/predictions:batch`, 구현 [schemas.py](../../ml-serving/schemas.py), [predictor.py](../../ml-serving/predictor.py), [HTTP 계약](ml-serving-http.md).

| 입력 컬럼 | 타입·생성 근거 |
|---|---|
| request_id | string, Backend 요청 추적 |
| segments[].segment_key | string, 요청 내 결과 연결 키 |
| provider_namespace | string, 지원 공급자 확인 |
| provider_route_id/provider_from_stop_id/provider_to_stop_id | string, 검증된 provider ID |
| from_stop_sequence | int, 출발 정류장 occurrence |
| service_date | YYYY-MM-DD, 해당 edge 진입 예상일 |
| hour_slot | int 0~23, 해당 edge 진입 예상시각 |

출력에서 읽을 필드: `model_version`, `model_type`, `target`, `production_ready`, `bundle_production_ready`, `supported_from`, `supported_until`, `computed_at`, `results[].segment_key/status/reason/prediction`. prediction은 `mean_seconds`, `missing_feature_count`, `missing_features`, `unseen_id_count`, `history_sample_count`, `data_insufficient`, `training_ids_verified`를 포함한다.

현재 응답의 `production_ready`는 false로 제한되고 예측 target은 시간대별 버스 구간 평균이다. 모델 bundle의 승인 플래그만 보고 운영 가능으로 바꾸지 않는다. 지원 날짜는 runtime manifest를 읽고 과거 문서의 날짜를 하드코딩하지 않는다. PREVIEW/UNAVAILABLE와 이유를 보존한다.

검증한 mean_seconds만 내부 초로 올림 변환한다. 음수·NaN·무한대는 거부한다. BUS step의 모든 필요한 edge가 같은 모델/정책으로 검증되지 않으면 일부 edge만 교체하여 원천 총시간과 섞지 않는다. API fallback과 미적용 이유를 반환한다.

실시간 bus-segment-online-feature 계약이 있어도 현재 HTTP 모델이 그 필드를 소비한다고 가정하지 않는다. features 확장은 학습·검증·runtime 전처리와 같은 버전으로 수행한다. 날씨·행사 때문에 기존 예측에 임의 가산하는 것으로 학습 불일치를 해결하지 않는다.

### 9.2 실측 구간 통과 입력

기존 [segment-observation.schema.json](segment-observation.schema.json)은 **contracts 바로 아래**에 있다. 중복 스키마를 만들지 않는다.

| 컬럼 | 타입/단위 | 출처·검증 |
|---|---|---|
| eventId | string | 같은 실측의 재전송에도 안정적인 식별 |
| segmentId, masterVersion | string | 검증된 인접 구간·마스터 |
| occurredAt | timestamp | 실제 구간 통과 완료시각 |
| collectedAt | timestamp | 관측 수집시각 |
| weatherBucket | DRY/RAIN/SNOW | 해당 실측 시점의 검증된 날씨 |
| sampleKind | ACTUAL_TRAVERSAL | 예측 ETA·시간대 평균과 구별 |
| source | string | 실제 운행기록 원천 |
| durationSeconds | number > 0, 초 | 동일 차량의 확인된 구간 시작·종료 차이 |

실제 차량별 정류장 통과기록의 확보가 선행 작업이다. 현재 sparse 위치나 도착예측으로 실제 통과를 확정하지 않는다. 동일 차량·운행·인접 정류장·순서·시각을 검증한 경우만 사실로 정규화한다. 결측 날씨는 DRY로 위조하지 않고 rejected/quarantine 및 재처리 대상으로 둔다. 추가 컬럼이 필요한 경우 additionalProperties=false인 기존 schema를 버전 변경한다.

### 9.3 기준선과 상태

Spark 기준선 grouping은 `segmentId × dow × hourSlot × weatherBucket × dataVersion`, dow는 1=일요일이다. 출력 p50/p80/p95는 초, sampleCount와 기간·계산시각·버전을 보존한다. p50≤p80≤p95를 검증하고 Spring 내부 API에 청크 업로드 후 전량 성공해야 활성화한다. Spark는 서비스 DB에 직접 쓰지 않는다.

Backend `BaselineSnapshot`은 현재 P50 중심이며 key에 날씨·품질 정보가 충분히 없다. 여기에 p80/p95·weatherBucket·sampleCount·dataVersion·computedAt/표본기간을 계약에 맞춰 담는 작업이 필요하다. 활성 버전을 평가 시작 시 한 번 고정한다.

현재 segment_status는 실제 통과 샘플의 최근 윈도 median과 같은 조건의 P50을 비교하는 기반이다. 출력 `segmentId,status,delaySeconds,confidence,computedAt,validUntil,source,causes`를 사용한다. `delaySeconds=max(0,ceil(currentMedian-p50))`는 정상 실측·표본 조건을 통과할 때만 계산한다. 결측·stale은 unknown이며 confidence가 성공확률이라는 뜻은 아니다.

`segment:status:{segmentId}`와 `segment.status`는 상태·위험·재계산 trigger다. Backend가 이미 ML/실시간으로 대체한 이동시간에 delaySeconds를 또 가산하지 않는다. 검증된 blocked 입력을 live job까지 연결하고 Redis 쓰기 성공 후 변경 이벤트를 발행한다. 사건이 바뀌지 않은 1분 tick마다 알림을 만들지 않는다.

## 10. 신선도·수집 주기·호출 예산

### 10.1 현재 코드 값과 문제

| source | 현재 Collector 기본 주기 | 최신값 보관 TTL | 현재 소비 신선도 |
|---|---:|---:|---:|
| 버스 도착/위치 | 28,800초 | 86,400초 | 120초 |
| 지하철 도착 | 360초 | 1,080초 | 120초 |
| 지하철 위치 | 3,600초 | 10,800초 | 120초 |
| 지하철 공지 | 900초 | 2,700초 | 600초 |
| 도로 돌발 | 900초 | latest 2,700초 / 도로 hash 기본 600초 | 도로 소비 600초 |
| 도시데이터 | 21,600초 | 64,800초 | 900초 |
| TOPIS 공지 | 900초 | 2,700초 | 1,800초 |

코드 기본값이며 현재 운영 설정이 이와 같다고 검증한 것은 아니다. `main.py`와 `kafka_adapter.py`의 max(기본,설정) 때문에 환경변수만 60초로 내려도 수집 주기가 짧아지지 않는다. **TTL을 늘려 실시간 문제를 해결하면 안 된다.** 원천 주기·승인 쿼터 안에서 수집을 조정하고 못 맞춘 범위는 stale로 보여 준다.

목표 브랜치는 bus_location 실패의 5분 후 1회 재시도를 추가했으나 정규 8시간 주기는 유지한다. 지하철 위치/도착은 기본 03:30~04:30 수집 생략과 특별 날짜 override를 갖는다. 이 구간은 전 호선 비운행이 입증된 시간이 아니므로 야간/특별 운행 일정에서 ‘열차 없음’으로 판정하면 안 된다. INFO-200 정상 빈 응답과 장애도 기존 validator로 구별한다.

### 10.2 수집 담당 구현 순서

1. 실제 키 그룹별 승인 한도·초당 제한·호출 가능한 범위를 설정으로 기록한다. 키 값은 기록하지 않는다.
2. Backend는 활성 일정에서 필요한 provider 노선/역/도로 scope를 중복 제거해 전달한다. 제안 demand 계약은 `scopeKey,source,providerNamespace,providerId,neededFrom,neededUntil,priority,expiresAt`이며 개인 위치/일정 제목은 제외한다.
3. 공통 수집 스케줄러가 scope별 요청을 합치고 `request_budget.py`의 키 그룹별 rolling 24h 제한을 통과한 요청만 실행한다.
4. 목표 poll 간격을 달성 못 하면 source.poll-status에 RATE_LIMITED/nextAllowedAt을 기록한다. 대기 작업을 무한 적재하지 않는다.
5. 원천/키가 다르면 각각 예산을 집계하고, 같은 실제 키를 환경변수 여러 개에 넣은 경우에도 그룹을 공유한다.
6. 반복 수집에서 본문이 같아도 ‘성공적으로 현재 활성임을 확인’한 관측 heartbeat는 남긴다. 내용 revision과 observedAt 갱신을 구분한다.

산정식: `하루 호출 = Σ(scope별 활성초 / poll초 올림 × 페이지 수) + 실패 재시도 + 정적 갱신`. 버스 100개 노선을 하루 24시간 60초 간격으로 1회씩 조회하면 **144,000회/일**이다. 지하철 ALL 단일 호출도 60초 간격이면 **1,440회/일**이다. 현재 RequestBudget 기본 900을 승인 쿼터 확인 없이 올려 해결하지 않는다. 활성 시간·scope 제한 또는 공급자 승인 확대가 필요하다. UI/서버 1분 재계산이 원천 데이터의 1분 갱신을 보장하지 않는다.

## 11. 파트 간 전달 패키지와 검증 fixture

각 source 담당자는 다음을 함께 전달한다.

1. 키를 제거한 원본 정상·빈 응답·오류·누락·순서역전 fixture와 공식 문서 URL/확인일.
2. raw 필드→정규화 컬럼 표, 코드표·단위·원천시각·지역/운영자 범위.
3. source/ID namespace와 매핑 artifact·SHA·validFrom/Until·미매핑 통계.
4. 호출 성공률·원천 나이·실제 poll 간격·키 그룹 사용량, 성공/부분/실패 구분.
5. 정상화 schema 검증 결과, Redis 예제, 변경 이벤트 예제, 거부 사유별 건수.
6. Replay 시 원본 event time·정책·모델·매핑 버전, 중복 처리와 늦은 이벤트 결과.

필수 합성 fixture:

| fixture | 기대 |
|---|---|
| mkTm=08:00, exps=120, collectedAt=08:00:30 | ETA=08:02, 08:02:30 아님 |
| recptnDt=08:00, barvlDt=180, now=08:01 | ETA=08:03, 08:04 아님 |
| 같은 providerTrainId, 다른 serviceDate | 서로 다른 운행. 교차 결합 금지 |
| 같은 역 이름, 다른 노선/방향 | EXACT 금지 |
| AccInfo 같은 숫자 링크, namespace 다름 | 통제 영향 미확정 |
| FULL 통제 [08:30,09:00), 실제 통과 09:00부터 | 시간 겹침 없음 |
| PARTIAL 통제, 지연 관측 없음 | 차단/임의 5분 가산 금지 |
| expectedClearAt 경과, 해제 공지 없음 | 종료 확정 금지 |
| 늦게 도착한 옛 revision | 최신 값 유지·늦은 원본 보존 |
| poll 실패로 사건 목록 비어 있음 | 정상/해제로 바꾸지 않음 |
| 첫 버스 만차, 둘째만 유효 | 둘째 차량 대기 후 전체 환승 재계산 |
| 하차역 예측 없음 | 정적/추정 근거 표시, 실시간 전체 반영 주장 금지 |
| ETA/위치만 있고 통과기록 없음 | ACTUAL_TRAVERSAL 생성 금지 |

## 12. 확보되지 않으면 기능을 제한할 항목

| 미확보 항목 | 확보 작업/담당 | 미확보 상태의 허용 결과 |
|---|---|---|
| AccInfo ↔ 방향 도로 ↔ 버스 occurrence 동일성 | 데이터 마스터 담당: 공식 파일 audit/promote 및 검토 | 영향 가능성만 표시 |
| 완전/부분 통제·적용 수단·실제 해제 | 수집 담당: 공식 코드/공지·검토 근거 확보 | 자동 차단/해제 확정 불가 |
| 시간표 열차코드 ↔ 실시간 열차 ID | 교통 마스터 담당: 날짜·방향·패턴 crosswalk | 시간표 fallback, 실시간 결합 제한 |
| 지하철 하차역 미래 예측·실제 운행 기록 | 수집 담당: 운영자 제공 범위 확인·원천 계약 | 정적 이동시간 기반 추정, 정확한 지연분 불가 |
| 버스 배차간격의 요일/시간대별 값 | 마스터 담당: 원천 컬럼·코드표 검증 | 미래 대기 fallback/UNKNOWN |
| 실제 구간 통과 정답 | 데이터 담당: 차량 통과기록 Producer/검증 | 신규 실측 기준선·정확도 주장 불가 |
| 키 예산으로 1분 내 신선도 | 수집·운영 담당: 실측 산정·대상 축소/승인 | stale 표시, 1분 최신 관측 보장 불가 |
| 모델 운영 승인·시간 분해 검증 | ML·Backend 담당: 평가 보고/검증 corpus | PREVIEW 유지, 운영 수락 차단 |

이 표는 ‘나중에 정한다’로 숨긴 빈칸이 아니라 담당자가 확보해야 할 산출물과, 실패했을 때 차단할 기능의 인수 조건이다. 확보하지 못한 값을 추측하여 컬럼을 채우지 않는다.

## 13. 파트 간 표준 레코드와 생성 방법

원천별 raw DTO를 Backend가 직접 읽지 않는다. Collector/Spark가 아래 표준 레코드로 정규화하고, Backend는 snapshot에 필요한 필드만 읽는다. 필드가 없으면 0·빈 문자열·현재시각으로 채우지 않고 nullable 또는 명시 상태를 사용한다.

### 13.1 SourcePollStatus

| 컬럼 | 타입 | 생성 규칙 |
|---|---|---|
| `source`, `scopeKey` | string | 예: `seoul.bus.arrival`, `route:100100609`. 사용자 ID 미포함 |
| `pollId` | string | 한 poll/페이지 묶음의 안정적 ID |
| `startedAt`, `finishedAt` | timestamp | Collector clock, Asia/Seoul offset 포함 |
| `sourceGeneratedAt` | timestamp nullable | 원천이 제공한 가장 최근 발생시각. 없으면 null |
| `status` | enum | SUCCESS/EMPTY_COMPLETE/PARTIAL/FAILED/RATE_LIMITED/SCHEDULED_SKIP |
| `pagesExpected`, `pagesReceived` | int nullable | 전체 목록 해제 판단에 필요한 완전성 |
| `recordCount`, `invalidCount` | int | 파싱 전 total과 혼동하지 않음 |
| `nextAllowedAt` | timestamp nullable | 쿼터/429 backoff 시각 |
| `errorCode` | string nullable | 키·본문을 제외한 정규화 코드 |

`EMPTY_COMPLETE`만 ‘정상적으로 조회했으나 현재 항목 없음’이다. PARTIAL/FAILED의 빈 목록으로 기존 incident를 종료하지 않는다. 이 레코드는 원천 최신성 표시와 incident lifecycle의 완전 스냅샷 판단에 함께 사용한다.

### 13.2 BusArrivalPrediction

| 컬럼 | 타입/단위 | raw → 생성 |
|---|---|---|
| `providerNamespace` | string | 서울 버스 원천 고정 namespace |
| `routeId`, `stopId` | string | 원천 route/station ID를 문자열 보존 |
| `vehicleId` | string nullable | 원천 차량 ID. 없으면 차량을 합성하지 않음 |
| `predictionRank` | int | 첫째/둘째 제공 순서 |
| `sourceGeneratedAt` | timestamp | `mkTm` 또는 원천 생성시각 |
| `arrivalInSeconds` | int | `exps`/`exps2`, 0 이상 검증 |
| `predictedArriveAt` | timestamp | `sourceGeneratedAt + arrivalInSeconds` |
| `routeType`, `messageCode`, `occupancyCode` | string nullable | 탑승 가능/운행 상태 판정 원본 코드 보존 |
| `observedAt`, `validUntil` | timestamp | 수집 관측과 사용 기한 분리 |
| `eventId`, `rawRef` | string | 중복 제거와 원본 추적 |

같은 vehicleId/rank에서 새 sourceGeneratedAt이 이전보다 오래되면 latest를 되돌리지 않는다. Backend는 플랫폼 도착시각 이후이고 validUntil 안인 예측만 사용한다. 첫째가 이미 지났거나 탑승 불가이면 둘째를 검토하며, `arrivalInSeconds + collectedAt`으로 ETA를 다시 만들지 않는다.

### 13.3 BusVehicleObservation

`routeId, vehicleId, stopId, stopSequence/sectionOrder, sourceGeneratedAt, coordinates(nullable), operationStatus, observedAt, validUntil, eventId`를 보존한다. 위치가 정류장 통과를 뜻하는 원천 코드와 순서가 확인될 때만 뒤 단계에서 ACTUAL_TRAVERSAL 후보가 된다. GPS 좌표 두 점만으로 정류장 통과 완료를 만들지 않는다.

### 13.4 TripStopPrediction

| 컬럼 | 타입 | 생성·조인 키 |
|---|---|---|
| `providerNamespace` | string | 운영자/원천 구분 |
| `serviceDate` | date | 운행 시작일. 자정 뒤 25시 정차도 같은 날짜 |
| `staticTripKey` | string | 시간표 artifact의 운행 키 |
| `providerTrainId` | string nullable | EXACT crosswalk일 때만 연결 |
| `lineId`, `direction`, `destinationCode` | string | 이름 대신 마스터 코드 사용 |
| `stopId`, `stopSequence` | string/int | 같은 이름 역의 노선/순서 구별 |
| `scheduledArriveAt`, `scheduledDepartAt` | timestamp | 24시 초과를 절대시각으로 변환 |
| `predictedArriveAt`, `predictedDepartAt` | timestamp nullable | 원천이 제공하고 운행 대응이 확인된 범위만 |
| `operationStatus` | enum | NORMAL/DELAYED/CANCELED/SKIP/UNKNOWN |
| `mappingStatus` | enum | EXACT/PARTIAL/AMBIGUOUS/UNMAPPED |
| `sourceGeneratedAt`, `observedAt`, `validUntil` | timestamp | 원천·수집·유효성 구별 |
| `sourceEventIds` | string[] | 위치/도착/공지 결합 추적 |

Spark는 `(serviceDate, staticTripKey, stopSequence)` 정적 행을 기준으로 EXACT crosswalk 실시간 행을 left join한다. 하차역 예측이 없으면 null을 유지한다. 승차역 한 건의 `delaySeconds`를 같은 열차의 모든 이후 역에 무조건 복제하지 않는다.

### 13.5 NormalizedIncident와 Impact

`NormalizedIncident`는 사건 사실이고 `RouteImpact`는 마스터와 결합한 영향이다.

| 구분 | 핵심 컬럼 | 생성 시점 |
|---|---|---|
| 사건 | incidentKey, revision, kind, restriction, lifecycle, affectedModes, start/end, sourceGeneratedAt, observedAt, validUntil, rawRef | Collector raw를 Spark가 코드·시간 단위로 정규화 |
| 영향 | incidentKey/revision, segmentId 또는 trip/stop, occurrence, direction, mappingVersion, matchStatus, overlap policy, evidenceRef | 검증된 마스터와 join |

`incidentKey`는 같은 사건의 수정에도 유지되고 `revision`만 바뀐다. 원천 incident ID가 안정적이면 source+ID를 사용한다. 제목·좌표 반올림 hash만으로 다른 사건을 무리하게 합치지 않는다. 동일 사건이 여러 원천에서 오면 source event를 보존한 cluster ID를 별도로 만들며, 확정 병합 전에는 각각 추적한다.

### 13.6 SegmentObservation·Baseline·Status

```text
BusVehicleObservation 또는 검증된 실제 운행기록
  → 동일 vehicle/trip의 인접 stop 통과 두 건 검증
  → durationSeconds = nextPassedAt - currentPassedAt
  → SegmentObservation(ACTUAL_TRAVERSAL)
  → Parquet fact dedup(eventId)
  → segmentId×dow×hourSlot×weatherBucket 분위수
  → Spring 내부 API upload(dataVersion)
  → 전량 검증 후 activate
```

Baseline row는 `segmentId,dow,hourSlot,weatherBucket,p50,p80,p95,sampleCount,sampleFrom,sampleTo,computedAt,dataVersion`을 갖는다. Backend는 평가 시작 때 active dataVersion을 한 번 읽고 모든 후보에 같은 버전을 사용한다.

SegmentStatus는 최근 ACTUAL_TRAVERSAL window와 같은 조건의 baseline을 비교한다. `status,delaySeconds,confidence` 외에 `sampleCount,currentWindowFrom/currentWindowTo,baselineVersion,sourceEventIds,causes,computedAt,validUntil`을 보존한다. 표본 부족·baseline 없음·stale이면 `unknown`; 정상이라고 채우지 않는다.

### 13.7 EvaluationSnapshot

Backend가 계산기에 전달할 snapshot은 다음 묶음의 불변 복사다.

| 묶음 | 포함 필드 |
|---|---|
| identity | scheduleId, inputRevision, asOf, snapshotId/hash |
| candidate | provider response hash, candidate IDs, fetchedAt, validUntil |
| master | transit/road/timetable version과 SHA |
| walking | endpoint evidence, API/fallback source, duration/distance/geometry ref |
| bus | 사용 scope의 BusArrivalPrediction/VehicleObservation과 poll status |
| subway | TripStopPrediction과 timetable/crosswalk version |
| incident | 사용한 revision, lifecycle, impact mapping, poll completeness |
| baseline/status | active baseline version, Redis value와 sourceGeneratedAt/validUntil |
| model | modelVersion, supported range, production flags, feature snapshot |
| policy | conditional departure, search, freshness, notification policy version |

snapshot hash는 비밀키·사용자 제목·raw 전체 본문을 포함하지 않는다. 같은 hash는 같은 계산 입력을 뜻해야 하며, Redis 현재값을 가리키는 키만 저장하고 내용은 보존하지 않는 방식은 Replay 요구를 충족하지 못한다.

## 14. source별 조인·fallback 실행표

| 계산 단계 | 1순위 조인/값 | 검증 실패 시 | 사용자 결과에 남길 코드 |
|---|---|---|---|
| 승·하차 node | 카카오 step + 공식 마스터 EXACT | provider step 좌표/시간을 fallback 범위로만 유지 | TRANSIT_MAPPING_PARTIAL/UNMAPPED |
| 접근·이탈 도보 | 검증 node 좌표 + 카카오 제휴 도보 | 같은 역할의 카카오 대중교통 WALK | WALK_API_UNAVAILABLE/ENDPOINT_UNVERIFIED |
| 버스 대기 | route+stop+차량의 fresh ETA | 검증 배차/카카오 BUS 전체 fallback | BUS_REALTIME_STALE/NO_BOARDABLE_VEHICLE |
| 버스 이동 | 모든 인접 edge 운영 모델 | 동일 BUS step 전체 provider/baseline 정책 | MODEL_PREVIEW/PARTIAL_EDGE_COVERAGE |
| 지하철 | serviceDate+trip+stop realtime | 정적 시간표와 ESTIMATED | TRAIN_CROSSWALK_UNRESOLVED/ALIGHT_PREDICTION_MISSING |
| 도로 통제 | incident+EXACT edge+방향+시간 | 영향 가능성만 표시, 차단 금지 | ROAD_IMPACT_PARTIAL/UNVERIFIED |
| 기준선 | 같은 active version+dow/hour/weather | provider fallback 또는 unknown | BASELINE_MISSING/STALE |
| 실시간 상태 | validUntil 안 Redis + 계약 schema | baseline/fallback, confidence 저하 | SEGMENT_STATUS_MISSING/STALE |

fallback은 성공값처럼 덮어쓰지 않는다. 최종 v2 결과의 `qualityReasons`에 위 코드를 모으고 각 timeline leg의 `durationSource`에도 실제 사용한 값을 기록한다.

## 15. as-of join과 Replay 구현 기준

평가시각 `asOf` 이후에 발생하거나 수집된 정보는 해당 평가에 넣지 않는다. 원천이 늦게 도착한 경우 `sourceGeneratedAt<=asOf`이더라도 당시 시스템에서 관측하지 못했다면 과거 온라인 결과 재현에서는 `observedAt<=asOf`도 만족해야 한다. 사후 분석은 두 모드를 구별한다.

- `ONLINE_REPLAY`: 당시 observedAt 기준으로 사용 가능했던 정보만 재현한다.
- `EVENT_TIME_ANALYSIS`: 늦게 도착한 원천까지 포함하여 실제 사건시각 기준 정확도를 분석한다.

정적 마스터·시간표·모델·정책은 asOf에 활성화돼 있던 버전을 manifest에서 읽는다. 현재 최신 artifact로 과거 결과를 다시 계산한 값을 동일 Replay라고 부르지 않는다. Kafka 중복은 eventId로 제거하고 같은 identity의 새 revision은 원천시각과 revision 순서 규칙으로 선택한다.

Replay 결과에는 `inputManifestHash, outputHash, decision, ETA, proposedDepartAt, cause revisions, qualityReasons`를 기록한다. output이 달라지면 원천, artifact, 정책, 비결정적 연산 중 어느 축이 달랐는지 diff를 낼 수 있어야 한다.

## 16. 데이터 담당 완료 체크리스트

source 또는 계산 데이터 하나를 완료하려면 다음이 모두 있어야 한다.

- 공식 출처 URL·확인일·제공 범위·호출 제한과 키 그룹
- 실제 raw 필드명, 코드표, ID namespace, 시간 의미와 단위
- 정상/빈/부분/오류/stale/순서역전 fixture
- 정규화 schema와 raw→컬럼 변환 테스트
- 매핑 artifact version/SHA/coverage와 rejected 이유 통계
- eventId/revision/dedup/watermark/state/TTL/validUntil 정책
- Redis/Kafka/Parquet/Internal API 예제와 소비자 contract test
- fallback 시 사용할 값과 사용자 결과의 qualityReason
- 동일 manifest Replay 결과와 운영 freshness/lag 지표

원천 호출에 성공했다는 사실만으로 완료하지 않는다. Backend가 특정 후보의 특정 leg에 안전하게 연결하고, 연결 실패를 정상값으로 숨기지 않으며, 같은 입력을 다시 계산할 수 있어야 완료다.

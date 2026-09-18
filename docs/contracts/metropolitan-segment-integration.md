# 수도권 구간 ID·예측·저장 연결 계약

## 공통 ID (canonical-adjacent-v2)

Redis의 `segment:status:{segmentId}`와 BUS의 4개 콜론 구분 필드는 유지한다.
서울은 `BUS:{routeId}:{fromStopId}:{toStopId}`를 보존한다. GBIS와 INCHEON_PUBLIC_CSV는
각 구성요소에 출처와 점을 붙인다. 예: `BUS:GBIS.1:GBIS.10:GBIS.11`.
출처는 지역 표시명이 아닌 ID 발급 체계다. 다른 출처의 같은 숫자를 자동 통합하지 않는다.
각 구성요소는 기존과 같이 50자 이하이며 절단·해시로 조용히 대체하지 않는다.
방향은 인접 from→to 순서로 나타내고, 노선 내 순번·원본 방향·마스터 버전은 별도 보존한다.

정적 topology 대응과 실시간 ID 대응, 학습 ID 대응은 독립 상태다. 경기·인천에 공통 ID가
발급되어도 서울 실시간 API ID로 조회하지 않는다. 여러 차량이나 출처 후보는 명시적으로
선택한 한 경로만 저장·예측한다. 서로 다른 대안의 edge를 합치지 않는다.

## 대응표 배포

배포 원본은 `backend/transit-master/`에서 관리한다. `manifest.json`과 `manage.py check`로
파일별 SHA·호환 조합을 확인하고 `manage.py env`가 출력하는 동일 환경값을 사용한다.

`build_metropolitan_master.py`는 기존 마스터와 별도로 `*.crosswalk.json.gz` 및 SHA manifest를
만든다. 스키마 버전 1, ID 정책 버전, 마스터 버전·SHA, 인접 edge 목록을 포함한다.
Collector와 Spark는 `BUS_CROSSWALK_PATH`와 `BUS_CROSSWALK_SHA256`으로 **같은 파일**을 읽는다.
일반 요청마다 파일을 다시 읽지 않고 프로세스 시작/잡 시작 시 고정한다. 교체 시 두 프로세스를
같은 배포 버전으로 재시작한다. 파일 미설정·서로 다른 버전·모호한 조회는 미매핑으로 남긴다.

현재 서울 수집기는 `providerNamespace=SEOUL_BUS`를 명시한다. arrival은 노선·정류장 ID로
유일한 진입 edge가 있을 때만 연결하고 `ARRIVAL_TARGET_INCOMING_EDGE`로 표시한다.
이는 차량 현재 구간이나 구간 소요시간 정답이라는 뜻이 아니다. position은 검증된
from/to 정류장 ID를 검증한 경우만 연결한다. 서울 `getBusPosByRtid`는
`stopFlag=0`, 유효한 `sectOrd`, 해당 순번 edge의 `fromStopId=lastStnId`가 모두
일치할 때만 양 끝 ID를 채운다. `nextStId`가 있으면 도착점도 일치해야 한다.
정차·순번/정류장 불일치·종점·결측은 미매핑이다. `stationSeq`를 대체 순번으로 쓰지 않는다.
이는 현재 마스터와 관측 증거가 일치하는 제한된 연결이며 전체 노선의 순번 동치를 보장하지 않는다.
payload의 `canonicalMapping`에는 마스터/ID 정책 버전, 상태, 의미 범위와 이유를 기록한다.
Spark는 같은 대응표로 독립 검증한 결과만 전달하고 기존에 들어온 잘못된 BUS segmentIds는 제거한다.

필드 근거: [서울시 버스위치 조회](https://data.seoul.go.kr/dataList/OA-1093/L/1/datasetView.do)의
`서울특별시_버스위치정보조회_서비스_활용가이드_20230530.docx`(2026-09-14 확인).
`sectOrd=구간순번`, `lastStnId=최종정류장ID`, `stopFlag=0 운행중/1 도착`이다.
공식 문서만으로 현재 마스터 순번과의 일치를 단정할 수 없어 출발 ID를 함께 대조한다.
Collector 실패 이유는 `POSITION_NOT_BETWEEN_STOPS`, `POSITION_SECTION_ORDER_MISSING_OR_INVALID`,
`POSITION_LAST_STOP_MISSING`, `POSITION_SECTION_NOT_IN_MASTER`, `POSITION_ORDER_STOP_MISMATCH`,
`POSITION_NEXT_STOP_MISMATCH`, `POSITION_ROUTE_CONTEXT_MISMATCH`로 원본 이벤트에서 집계할 수 있다.
원본 Kafka payload와 수집 시각은 그대로 보존하며 연결 성공을 데이터 최신성·운행시간 정답으로
해석하지 않는다. 운영 매핑률과 기존 0건의 실제 원인 확정은 운영 이벤트를 별도로 검사해야 한다.

정상 입력 예시(합성): `routeId=1, vehId=V, sectOrd=1, lastStnId=10, stopFlag=0`.
마스터 순번 1의 edge가 `10 → 11`이면 `providerFromStopId=10`, `providerToStopId=11`,
`segmentIds=["BUS:1:10:11"]`, scope=`EXPLICIT_ADJACENT_STOP_PAIR`를 전달한다.

BUS 최신값 키는 서울은 기존 키를 유지한다. 다른 출처는 기존 prefix 뒤에 namespace를 추가한다.
namespace가 없는 과거 이벤트는 source가 `seoul.bus_*`인 경우만 서울로 해석한다.
미확인 출처를 서울로 추측하지 않는다. 최신 관측은 `transit:latest:*`에 저장하며 관측만으로
`segment:status` 지연·정상값을 만들어내지 않는다.

## 대안 평가와 ML 경계

검색 응답은 기존 카카오 시간(초)을 유지하고 BUS 대안별 평가를 추가한다. 선택 전 대안 간
시간을 합치지 않는다. 모델은 `BusPredictionPort` 경계로 연결하며 기본 구현은 사용 불가다.
서버의 실제 bundle·인코더·공통 전처리·학습 ID 대응을 확인하기 전 가짜 모델을 배포하지 않는다.
입력은 마스터/ID 정책, 예측 절대시각(Instant), source route/stop ID·순번·좌표·방향과 전체 canonical edge 순서다.
요일·시간대 특성은 어댑터에서 Asia/Seoul로 변환한다.
이것은 모델의 최종 특성 벡터가 아니며 학습 당시 공통 전처리를 수행하는 어댑터가 최종 특성을 만든다.

승인된 어댑터 결과에는 모델/특성 버전, 입력 지문, 학습 ID 대응 확인, 생산 사용 승인,
모든 edge의 평균 소요시간(초)·표본 수·유효시각이 필요하다. 빠짐·중복·음수·NaN·만료·지문 불일치는
전체 버스 step을 카카오 값으로 fallback한다. 정상 결과는 **평균** 구간시간을 합산하며
P50/P80/P95를 합산해 전체 분위수라고 부르지 않는다. 대기·도보·환승·미배정 시간을 덧붙이지 않는다.
실제 입력 부족은 `predictionReady=false`와 이유로 공개한다.

`featuresAsOf`는 예측 기준시각보다 늦을 수 없다. `computedAt`은 추론 계산시각,
`validUntil`은 결과의 유효시각이다. 모델은 기준시각 이후에 계산될 수 있으나
유효시각이 기준시각·계산시각보다 모두 뒤여야 한다. 입력 지문이 다르면 캐시 재사용을 거절한다.

`mapping.busAlternatives[].predictionReady`는 정적 매핑 캐시에서는 항상 false다.
검색 응답을 만들 때만 현재 모델 평가 결과로 복사한다. 한 대안의 여러 출처 후보 중 하나 이상
사용 가능한 경우 true이며 실제 사용 대상은 `busEvaluations[].alternativeIndex/candidateIndex`로 선택한다.
모델 승인 상태는 매핑 캐시에 저장하지 않는다. `mappingSummary`의 `busAlternativeCount`,
`busIdentityConfirmedCount`, `busGeometryVerifiedCount`, `busPredictionReadyCount`는 대안 단위다.

Backend 소비 예시(모델 미등록, 필드 일부, ID는 형식 설명용):

```json
{
  "durationSeconds": 100,
  "busEvaluations": [{
    "alternativeIndex": 0,
    "candidateIndex": 0,
    "providerName": "예시 버스",
    "predictionReady": false,
    "source": "KAKAO",
    "reason": "MODEL_OR_TRAINING_CROSSWALK_UNAVAILABLE",
    "estimatedSeconds": 100.0,
    "modelVersion": null,
    "featureVersion": null,
    "segmentIds": ["BUS:GBIS.1:GBIS.10:GBIS.11", "BUS:GBIS.1:GBIS.11:GBIS.12"]
  }]
}
```

승인된 모델이 각 edge의 평균 30초·40초를 반환하면 해당 평가만 70초가 된다.
기존 `durationSeconds`와 후보 전체 시간은 카카오 원본을 유지한다. 최종 경로 선택·출발시각 계산은
추천 엔진이 담당하며 이 단계에서 서로 다른 버스 대안이나 분위수를 합산하지 않는다.

## 선택 결과 저장

`RouteSegmentEdgeWriter`는 소유권 확인을 마친 상위 저장 서비스가 호출하는 내부 경계다.
기존 route_segment 행을 잠그고 조회 당시 입력과 같은 BUS step인지 확인한 후 서버에서 재매핑한다.
선택된 차량/출처 후보가 VERIFIED일 때만 전체 edge 목록을 한 트랜잭션으로 교체한다.
오류 시 이전 edge가 유지된다. 경로 검색은 계속 읽기 전용이며 자동 저장하지 않는다.
사용자·일정 생성/선택 API와 알림은 이 변경에서 구현하지 않는다.

상위 저장 서비스는 소유권을 확인하고 route/route_segment를 만든 트랜잭션 안에서
`replace(routeId, routeSegmentId, serverSegment, alternativeIndex, candidateIndex)`를 호출한다.
serverSegment는 서버 검색 결과여야 하며 클라이언트가 만든 mapping 객체를 그대로 전달하지 않는다.
재매핑 결과·마스터·정책이 달라지면 재검색을 요구한다. 같은 결과의 반복 저장은 멱등이다.
여러 edge인 부모의 레거시 `segment_id`는 NULL이고 `route_segment_edge`가 역조회의 기준이다.

## 갱신과 운영 인계

마스터 gzip과 대응표 manifest의 masterVersion/masterSha256를 먼저 대조한다. 새 마스터 활성화와
Collector/Spark 대응표 교체를 같은 배포 단위로 관리하고, 교체 중 서로 다른 버전은 미매핑으로 처리한다.
예약·분기 수동 확인 규칙은 검토일 범위를 벗어나면 계속 보류하며 자동으로 날짜를 연장하지 않는다.
새 자료는 새 버전으로 재생성하고 1~20 응답 및 오탐 방어를 다시 검증한 후 배포한다.

## 호환과 검증

기존 서울 ID와 지하철 ID, V1~V3 및 Redis TTL은 보존한다. ID 정책 변경으로 BUS 매핑 캐시
키 세대를 교체한다. 신규 지역 ID는 기존 기준선 API의 4개 구성요소 정규식에 들어간다.
기존 데이터 일괄 삭제나 DB 초기화는 하지 않는다. V4 적용과 마스터 관리 적재는 별도 실행이다.
단위 테스트 외에 artifact의 모든 edge에서 Java/Python ID 동일성, Collector→이벤트→Spark와
1~20 카카오 재생, 실제 MySQL 트랜잭션 테스트를 구분해 기록한다.

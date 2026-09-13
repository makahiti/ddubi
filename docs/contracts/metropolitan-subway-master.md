# 수도권 지하철 마스터·매핑 계약

2026-09-10. 정적 경로 매핑용 자료이며 실제 운행시간 정답·열차 운행 보장·ML 재학습 완료를 의미하지 않는다.

## 적용과 ID

- 배포 파일: `backend/src/main/resources/transit/metropolitan-subway-master.json.gz`.
- 공급원: `ResourceSubwayMasterDataProvider` → 기존 `MasterDataTransitMappingResolver` → `SubwaySegmentMatcher`.
- `ddubi.transit-mapping.subway-master-resource=classpath:transit/metropolitan-subway-master.json.gz`를 설정하고 재시작하면 사용한다. 미설정이면 기존 JDBC 활성 버전을 사용한다. 파일 지정 후 읽기·검증 실패는 기동 실패이며 DB로 조용히 전환하지 않는다.
- DB·Redis를 적재/초기화하지 않는다. 실제 배포·활성화는 별도다. Collector/Spark에는 같은 버전의 ID·crosswalk를 배포해야 추가 호선을 조회할 수 있다.
- 기존 서울 시간표의 호선/역 ID는 유지한다. 추가 KRIC 호선은 `KRIC.{공식 운행 노선번호}`, 역은 `KRIC.{정적 역사 원본 노선번호}.{역번호}`다. GTX-A는 `GTXA.A`, `GTXA.X102`처럼 운영사 역 코드를 별도 namespace로 사용한다.
- `SUB:{호선}:{출발역}:{도착역}` 문자열 형식과 길이는 그대로다. 예: `SUB:KRIC.S2802:KRIC.S2802.3209:KRIC.S2802.3210`.
- KRIC 운영 목록의 `001` 등은 순번일 수 있으므로 역 ID로 쓰지 않는다. 정적 역사 파일의 ID와 대조한다. 코레일 공유 역은 명시한 서비스별 원본 노선 우선순위로 선택하며, 서로 다른 역 코드는 임의 합치지 않는다.

## 파일 필드

| 필드 | 의미 |
|---|---|
| schemaVersion | 파일 스키마, 현재 1 |
| dataVersion | 생성 내용 SHA-256에서 만든 불변 버전. 좌표 감사 파일은 별도 |
| lines[].subwayId | 내부 namespace가 포함된 정적 공식 호선 ID |
| lines[].realtimeSubwayId | 서울 실시간 API 호선 ID. 대응표 없으면 null |
| lines[].lineName | 표시·후보 검색용 호선명 |
| lines[].direction | 해당 운행 패턴의 진행 방향. KRIC C1F/C1R은 공유 인접 edge로 정한 내부 방향 토큰이며 공식 상/하행 코드가 아님 |
| lines[].servicePatternId | 공식 운행 역 순서의 해시 또는 기존 시간표 패턴 ID |
| lines[].stations[].stationId | 정적 역 ID. 실시간 statnId와 다름 |
| lines[].stations[].realtimeStationId | 별도 검증한 실시간 statnId. 없으면 null |
| lines[].stations[].stationName | 공식 역사명 |
| lines[].stations[].sequence | 해당 방향·패턴에서 1부터 연속된 순번 |
| aliases.subwayLineAliasIds / subwayStationAliasIds | 검증된 외부 표기 → 정적 ID 집합 |

좌표는 `metropolitan-subway-coordinates.json`의 정적 stationId별 WGS84 `latitude/longitude`와 출처로 제공한다. 현재 런타임 Matcher는 이름·ID·순서를 사용한다. 좌표와 폴리라인의 대조는 이번 오프라인 검증이며 런타임 공간 게이트로 구현된 것은 아니다.

## 판정·실패·재현

1. 호선 후보를 찾고 같은 호선의 실제 서비스 패턴만 탐색한다. 서로 다른 운행 경로를 이어 붙이지 않는다.
2. 승하차역과 제공된 경유역의 순서를 확인한다. 제공 목록은 공식 부분경로의 부분수열로 허용한다. 급행의 생략 역을 복원할 수 있지만 실제 열차별 정차 서비스·급행 소요시간을 확정하지는 않는다.
3. 정적 호선·방향·전체 역 경로가 하나인 경우만 `EXACT`. 복수 경로는 `AMBIGUOUS`, 증거 부족은 `PARTIAL/UNMAPPED`이며 인접 ID를 발급하지 않는다.
4. 첫 역=마지막 역이고 내부 역이 유일한 공식 순환 패턴만 경계 통과를 허용한다. 열린 단축 운행, 분기 회차, 한 바퀴 이상 이동은 추정하지 않는다. 순환 경계에서는 원본 역 순번이 감소할 수 있으므로 출력 `segments[].order`가 이동 순서다.
5. 같은 경로를 가진 패턴은 ID로 안정 정렬한다. 동일 입력·마스터는 반복 실행과 마스터 행 순서 변경에도 같은 결과를 반환한다. 캐시 정책을 `subway-v7`로 변경해 이전 판정을 재사용하지 않는다.
6. `EXACT`와 실시간 조회 가능 여부는 별도다. null crosswalk에는 정적 ID를 대입하지 않는다. 이 파일에 없는 호선도 임의 생성하지 않는다.

## 출처와 수집팀 전달

| 공식 출처 | 확보·보강한 값 | Collector/마스터 갱신 때 필요한 값 |
|---|---|---|
| [서울교통공사 시간표](https://www.data.go.kr/data/15098251/fileData.do) | 20260616 CSV 424,264행, 정적 역 코드 458개, 패턴 311개 | 호선·역 코드, 열차·방향·일반/급행, 요일, 도착·출발시각, 적용일 |
| [KRIC 통합 역사](https://data.kric.go.kr/rips/M_01_01/detail.do?id=32) | 20260630 파일의 역사 1,099행, 원본 노선·역 ID·좌표 | 전체 운영사 역사 ID·호선·역명·좌표·원본 기준일 |
| [KRIC 통합 운행](https://data.kric.go.kr/rips/M_01_01/detail.do?id=900) | 20260630 파일 223,425행, 명시적 역 목록·열차별 정차 순서 | 운행유형, 열차·요일·시각, 실제 경유 순서와 적용일 |
| [서울 TOPIS 역사 좌표](https://data.seoul.go.kr/dataList/OA-21232/S/1/datasetView.do) | 784행, 정적 코드·호선·좌표 교차 확인 | 전체 정적 코드·호선·좌표, 갱신일. 표시 행 번호를 역 순서로 사용 금지 |
| [GTX-A 운영사](https://www.gtx-a.com/main.do) | 북부/남부 9개 역의 운영사 코드·공개 운행 순서 | 공식 역·노선 코드, 운행 구간·시간표. 서울~수서 연결을 추정하지 않음 |
| [인천교통공사 I210](https://www.ictr.or.kr/main/subway/subwayStation.do?line_no=2&station_no=210) | 서구청→서해구청 표기, 앞뒤 아시아드경기장·가정 | 역명 변경 이력, 원본 역 코드 대응 |
| [서울 실시간 역 목록](https://data.seoul.go.kr/dataList/OA-12764/A/1/datasetView.do) | 20260902 워크북 696행으로 별도 crosswalk | subwayId/statnId·호선·역명, 운영사별 실제 도착 API 코드 |

출처 파일명·URL·다운로드 SHA-256은 `metropolitan-subway-master.sources.json`에 둔다. 최신 파일이어도 내부 행 기준일은 오래될 수 있다. 서울 시간표 포털 표시 건수와 실제 파일 행 수가 달라 실제 읽은 행 수를 기록했다.

보강 시 원본 운행자료 595건은 파싱/역명 오류로 격리했다(인천2호선 구분자 오류 414, 신길온천 역사 누락 180, 센럴파크 오자 1). 정상적인 다른 공식 운행 패턴에서 연결이 확보된 경우만 수록했다. 이를 원본 전체 무결성이나 수도권 모든 열차 커버리지로 표현하지 않는다.

## 1~20.json 검증 범위

- 248개 SUBWAY step, 18개 호선. 서울 시간표만 사용한 비교군 111 EXACT → 보강 및 순환선 수정 후 248 EXACT.
- 217개 step은 모든 인접 edge의 실시간 crosswalk 확보. 인천1 13개, 인천2 6개, 에버라인 12개는 총 31개 step에서 실시간 ID 미확보.
- 공식 좌표와 카카오 폴리라인의 최근접 선분 거리·이동 순서를 별도 확인했다. 250m는 검토 표시용 실험 기준, 25m는 투영 순서 오차 허용값으로 제품 정책이 아니다.
- KRIC 양원(1204)의 다른 지역 좌표는 같은 코드·호선의 TOPIS 좌표로 보정했다. 미사(2563)의 TOPIS 좌표는 KRIC 5호선 미사(0555) 좌표로 보정했다. 원본 ID·보정 근거를 보존했다.
- 보정 후 이번 248개는 좌표 누락·검토 거리 초과·역순이 없다. 이 수치는 독립 정답셋 정확도나 수도권 전체 100% 보장을 뜻하지 않는다.
- 역별 실제 도착/출발 관측, 실시간 서비스 코드의 운영 API 호출 성공, 급행·분기 열차 선택, 환승 통로 매핑과 ML 시간 예측은 별도 검증 대상이다.

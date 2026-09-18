# 수도권 버스 매핑 계약

## 범위와 배포

통합 CSV와 공식 형상 메타데이터를 `backend/tools/build_metropolitan_master.py`로 버전 고정 gzip JSON으로 변환한다. CSV의 출처·공식 ID·순번·방향·좌표·승하차 null을 보존한다. 원본 카카오 JSON 및 다운로드 원문은 저장소 밖에서 관리한다. 이 자료는 수도권 전체 노선의 완전한 목록이라는 보장이 없다.

Spring 관리 적재가 `metropolitan_bus_master`에 검증된 artifact를 저장하고 활성 포인터를 원자적으로 교체한다. 모든 BUS 요청은 항상 이 메모리 스냅샷을 사용한다. 활성 마스터가 없으면 `UNMAPPED/MASTER_DATA_NOT_CONFIGURED`이며 서울 전용 fallback은 없다. 서울 지하철 마스터와 독립 버전으로 관리하여 버스 갱신이 지하철을 비우지 않는다. 새 Flyway와 ERD는 리뷰 대상이며 운영 DB 적용은 이 작업에 포함하지 않는다.

## 결과

`TransitMappingResult.busAlternatives[]`는 카카오 차량 대안별 결과다. 각 대안은 `providerName`, `identityStatus`, `geometryStatus`, `reason`, `candidates`, `predictionReady`를 갖는다. predictionReady는 매핑 캐시에서는 false이고 응답에서만 현재 모델 평가를 반영한다. candidates의 namespace + routeId는 공식 출처 키이며 공용 segment_id가 아니다. stops는 공식 순번을 보존하고, 좌표는 WGS84, 거리는 m다.

- identityStatus: `CONFIRMED`, `AMBIGUOUS`, `UNRESOLVED`.
- geometryStatus: `VERIFIED`, `CONFLICT`, `UNAVAILABLE`, `NOT_CHECKED`.
- identity가 CONFIRMED여도 공간 충돌, 승하차 unknown, 학습 대응 미확인 상태를 성공으로 숨기지 않는다.
- 출처 간 병합은 같은 공식 노선 ID, 인천 관할 근거, 전체 부분경로 stop ID·방향·좌표·승하차 조건 대조가 성립할 때만 한다. 원본 출처 후보는 보존한다.
- 여러 차량 대안의 edge를 연결하거나 임의의 한 차량을 선택하지 않는다. 기존 최상위 EXACT 의미는 유지한다. 출처 후보가 하나인 단일 검증 대안은 서울·경기·인천 모두 canonical-adjacent-v2의 EXACT/segments를 발급한다. 다중 대안·출처는 PARTIAL이며 선택한 후보의 전체 경로만 사용한다.
- 50m 끝점·중간점·형상 기준, 40m 형상 샘플 간격은 v6 회귀 정책이다. 도로가 실제 동일하다는 보증이나 ML 예측 정확도가 아니다.
- 수동 별칭은 검토일·원본 전체 경로·정류장 이름 순서와 고정 마스터에 한정한다. 검토일을 현재 날짜로 자동 연장하지 않는다. 캐시 정책 키에도 검토 기준일을 포함한다.
- 캐시는 차량 표기(예약/A/B 포함), 전체 정류장열과 전체 경로 좌표를 포함한다. 기존 첫·끝 좌표 서명은 BUS 매핑에 사용하지 않는다.

## 검증

`1.json`~`20.json`의 BUS step 281개, 차량 대안 768개를 Java에서 평가한다. 2026-09-09 검토 기준 v6 757개 통과와 ID·순서까지 비교한다. 실행일에 만료된 검토 규칙은 별도 실행에서 제외하고 그 차이를 보고한다. 기존 통과 건수를 독립 정답률로 표현하지 않는다. 역방향·좌표 이동·알 수 없는 번호/이름·0 길이·중간 형상 변조를 방어한다.

지하철 적재기는 BUS XLSX와 BUS 별칭을 받지 않는다. 지하철 버전·검증 별칭·캐시는 기존 테이블을 사용한다. 이전 서울 버스 테이블과 과거 마이그레이션은 보존하되 새 BUS 요청에서는 읽거나 쓰지 않는다.

공용 ID 발급·배포 대응표·응답 품질 집계·대안별 예측·선택 edge 저장은
[수도권 구간 연결 계약](metropolitan-segment-integration.md)을 따른다.

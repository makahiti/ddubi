/**
 * 카카오가 이름으로 반환한 대중교통 경로를 서울 공식 정적 ID의 인접 구간으로 변환한다.
 *
 * <h2>처리 흐름</h2>
 * <ol>
 *     <li>{@code SeoulTransitMasterImportService}가 버스 XLSX와 지하철 CSV를 검증해
 *     버전 마스터 테이블에 적재하고, 전체 성공 후에만 ACTIVE 포인터를 교체한다.</li>
 *     <li>{@code JdbcSeoulTransitMasterDataProvider}가 ACTIVE 버전을 노선별 정류장 순서와
 *     호선·방향·서비스 패턴별 역 순서의 불변 읽기 모델로 조립한다.</li>
 *     <li>{@code BusSegmentMatcher}/{@code SubwaySegmentMatcher}가 카카오의 노선명,
 *     승·하차명, 전체 통과 순서와 BUS 좌표를 공식 topology에 대조한다.</li>
 *     <li>후보 경로가 하나로 증명된 {@code EXACT}만 공식 인접 노드 쌍으로 전개한다.
 *     나머지는 실패 이유를 가진 {@code PARTIAL/AMBIGUOUS/UNMAPPED}로 남긴다.</li>
 *     <li>{@code CanonicalSegmentIdFactory}가 인접 노드 쌍을
 *     {@code BUS:route:from:to}/{@code SUB:line:from:to}로 직렬화한다.</li>
 * </ol>
 *
 * <h2>ID 경계</h2>
 * <p>canonical ID는 정적 서울 마스터 ID이며 이름이나 실시간 API ID를 섞지 않는다.
 * 지하철 {@code subwayId/statnId} 같은 실시간 ID는 별도 crosswalk 필드다. 따라서 실시간
 * 대응표가 없어도 topology 매핑은 가능하지만, 호출자는 {@code realtimeCoverage}가
 * {@code FULL}인지 별도로 확인해야 실시간 보정에 사용할 수 있다.</p>
 *
 * <h2>안전 원칙</h2>
 * <p>이 패키지는 매핑률보다 잘못된 {@code EXACT} 방지를 우선한다. 다중 노선, 분기 경로,
 * 역순 좌표 또는 부족한 증거가 있으면 임의 후보를 선택하지 않는다. 매핑 결과에는 마스터와
 * 정책 버전을 남겨 캐시·Replay·수동 검토가 같은 판정을 재현할 수 있게 한다.</p>
 */
package com.ssafy.ddubi.domain.route.mapping;

package com.ssafy.ddubi.domain.route.mapping;

import java.util.List;
import java.util.Optional;

/**
 * 서울 공식 교통 마스터의 저장 위치를 매칭 로직에서 분리하는 읽기 전용 포트다.
 *
 * <p>Backend 팀이 DB 테이블을 확정하면 JPA 어댑터가 이 인터페이스를 구현한다.
 * 대량 행을 카카오 요청마다 조회하지 말고, 노선별 정류장·방향별 역 순서로 묶은
 * 스냅샷을 마스터 버전 변경 시점에 제공해야 한다.</p>
 */
public interface SeoulTransitMasterDataProvider {

    /** 현재 ACTIVE 포인터가 가리키는 버전. 없으면 마스터가 아직 준비되지 않은 상태다. */
    Optional<String> activeVersion();

    /** 공식 노선 ID와 운행 순서가 포함된 전체 버스 노선 스냅샷을 반환한다. */
    List<BusRouteMaster> loadBusRoutes(String dataVersion);

    /** 공식 호선 ID·방향·역 순서가 포함된 전체 지하철 노선 스냅샷을 반환한다. */
    List<SubwayLineMaster> loadSubwayLines(String dataVersion);

    /** 현재 활성 버전에 속하고 verified=true인 별칭만 반환한다. */
    TransitAliasSnapshot loadAliases(String dataVersion);
}

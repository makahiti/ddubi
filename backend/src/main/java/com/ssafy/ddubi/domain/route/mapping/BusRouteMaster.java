package com.ssafy.ddubi.domain.route.mapping;

import java.util.Comparator;
import java.util.List;

/**
 * 공식 노선 ID와 그 노선의 전체 정류장 순서를 묶은 읽기 모델이다.
 * 파일, API, MySQL 등 마스터의 저장 위치와 무관하게 매칭기가 받는 공통 입력이다.
 * 생성 시 정류장을 sequence 순으로 정렬하므로 이후 알고리즘은 목록 순서를 운행 순서로 신뢰한다.
 */
public record BusRouteMaster(
        String routeId,
        String routeName,
        List<BusStopMaster> stops
) {
    public BusRouteMaster {
        // 호출자가 순서가 섞인 마스터를 주더라도 매칭 결과가 입력 배열 순서에 따라 달라지지 않게 한다.
        stops = stops == null
                ? List.of()
                : stops.stream()
                .sorted(Comparator.comparingInt(BusStopMaster::sequence))
                .toList();
    }
}

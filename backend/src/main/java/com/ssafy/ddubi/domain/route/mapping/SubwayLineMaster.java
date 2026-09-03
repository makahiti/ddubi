package com.ssafy.ddubi.domain.route.mapping;

import java.util.Comparator;
import java.util.List;

/**
 * 공식 지하철 호선의 한 운행 방향·서비스 패턴과 전체 역 순서를 묶은 읽기 모델이다.
 * 같은 subwayId라도 상행·하행, 본선·지선·단축운행을 별도 객체로 제공해야 실제 역 순서를
 * 잃지 않는다. 서로 다른 패턴의 승차~하차 경로가 같으면 하나의 후보로 취급하고, 분기 경로가
 * 다르면 매칭기는 임의 선택하지 않고 AMBIGUOUS를 반환한다.
 */
public record SubwayLineMaster(
        String subwayId,
        String realtimeSubwayId,
        String lineName,
        String direction,
        String servicePatternId,
        List<SubwayStationMaster> stations
) {
    /** 선형 노선처럼 운행 패턴을 따로 구분하지 않는 테스트·수동 마스터용 생성자다. */
    public SubwayLineMaster(
            String subwayId,
            String lineName,
            String direction,
            String servicePatternId,
            List<SubwayStationMaster> stations
    ) {
        this(subwayId, subwayId, lineName, direction, servicePatternId, stations);
    }

    /** 테스트에서 전달한 subwayId는 이미 실시간 API 코드인 것으로 취급한다. */
    public SubwayLineMaster(
            String subwayId,
            String lineName,
            String direction,
            List<SubwayStationMaster> stations
    ) {
        this(subwayId, subwayId, lineName, direction, "DEFAULT", stations);
    }

    public SubwayLineMaster {
        // 입력 파일의 행 순서와 무관하게 공식 sequence를 유일한 운행 순서 기준으로 사용한다.
        stations = stations == null
                ? List.of()
                : stations.stream()
                .sorted(Comparator.comparingInt(SubwayStationMaster::sequence))
                .toList();
    }
}

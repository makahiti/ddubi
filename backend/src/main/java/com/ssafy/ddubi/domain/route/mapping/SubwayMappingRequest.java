package com.ssafy.ddubi.domain.route.mapping;

import java.util.List;

/**
 * 카카오 SUBWAY step에서 공식 ID 매핑에 필요한 증거만 분리한 요청이다.
 *
 * @param providerLineNames 카카오 vehicles.name에서 얻은 호선명 목록
 * @param fromStationName 카카오 stops의 첫 번째 승차역명
 * @param toStationName 카카오 stops의 마지막 하차역명
 * @param passingStationNames 카카오가 반환한 승차·중간·하차역 순서
 */
public record SubwayMappingRequest(
        List<String> providerLineNames,
        String fromStationName,
        String toStationName,
        List<String> passingStationNames
) {
    public SubwayMappingRequest {
        // 호출자 목록 변경이 같은 요청의 매핑 결과를 바꾸지 않도록 불변 복사한다.
        providerLineNames = providerLineNames == null ? List.of() : List.copyOf(providerLineNames);
        passingStationNames = passingStationNames == null ? List.of() : List.copyOf(passingStationNames);
    }
}

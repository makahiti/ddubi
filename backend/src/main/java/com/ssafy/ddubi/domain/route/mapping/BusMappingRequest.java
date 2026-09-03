package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.model.Coordinate;

import java.util.List;

/**
 * 카카오 BUS step에서 공식 ID 매핑에 필요한 증거만 분리한 요청이다.
 * 카카오 DTO를 직접 받지 않아 매칭 알고리즘을 외부 API와 독립적으로 테스트할 수 있다.
 *
 * @param providerRouteNames 카카오 vehicles.name 목록. 한 step에 여러 탑승 가능 노선이 올 수 있다.
 * @param fromStopName 카카오 stops의 첫 번째 정류장명
 * @param toStopName 카카오 stops의 마지막 정류장명
 * @param passingStopNames 카카오가 반환한 승차·중간·하차 정류장 순서
 * @param geometry 카카오 path.points를 변환한 진행 좌표. 없으면 이름·순서 증거만 사용한다.
 */
public record BusMappingRequest(
        List<String> providerRouteNames,
        String fromStopName,
        String toStopName,
        List<String> passingStopNames,
        List<Coordinate> geometry
) {
    public BusMappingRequest {
        // 매칭 도중 외부 목록이 바뀌어 동일 요청의 결과가 달라지지 않도록 방어적 복사한다.
        providerRouteNames = providerRouteNames == null ? List.of() : List.copyOf(providerRouteNames);
        passingStopNames = passingStopNames == null ? List.of() : List.copyOf(passingStopNames);
        geometry = geometry == null ? List.of() : List.copyOf(geometry);
    }
}

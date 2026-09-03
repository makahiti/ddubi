package com.ssafy.ddubi.domain.route.service;

import com.ssafy.ddubi.domain.route.model.Coordinate;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import com.ssafy.ddubi.domain.route.model.VehicleInfo;

import java.util.List;

/**
 * segment_id를 찾는 데 필요한 provider 증거 모음이다.
 * Resolver가 카카오 DTO를 직접 알 필요가 없도록 우리 내부 값만 전달한다.
 *
 * <p>이 record의 이름과 vehicle 값은 아직 검증되지 않은 카카오 표기다. 공식 ID로 오인해
 * 저장하거나 Redis 키에 사용하면 안 된다. Resolver가 활성 마스터에서 EXACT를 반환한 뒤에만
 * {@code TransitMappingResult.segments}의 canonical ID를 사용한다.</p>
 *
 * @param mode 카카오 step을 변환한 BUS/SUBWAY/WALK
 * @param providerRouteKey 원본 차량명 목록의 표시·감사용 결합값이며 canonical ID가 아니다
 * @param stopNames 승차·중간·하차 순서를 보존한 provider 이름 목록
 * @param vehicles 한 step에서 탑승 가능한 차량·호선 대안 전체
 * @param geometry WGS84 경도·위도 path. BUS는 첫·끝 좌표를 품질 증거로 사용한다
 */
public record SegmentResolveRequest(
        TransportMode mode,
        String providerRouteKey,
        String fromName,
        String toName,
        List<String> stopNames,
        List<VehicleInfo> vehicles,
        List<Coordinate> geometry
) {
    public SegmentResolveRequest {
        stopNames = stopNames == null ? List.of() : List.copyOf(stopNames);
        vehicles = vehicles == null ? List.of() : List.copyOf(vehicles);
        geometry = geometry == null ? List.of() : List.copyOf(geometry);
    }

    /** Matcher가 사용하는 차량·노선명만 null 없이 순서대로 추출한다. */
    public List<String> vehicleNames() {
        return vehicles.stream()
                .map(VehicleInfo::name)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }
}

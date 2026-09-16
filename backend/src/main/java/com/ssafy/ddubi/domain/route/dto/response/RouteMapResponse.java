package com.ssafy.ddubi.domain.route.dto.response;

import com.ssafy.ddubi.domain.route.model.Coordinate;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 지도에 경로를 그리는 데 필요한 것만 담는다.
 *
 * <h3>좌표가 왜 필요한가</h3>
 * 출발·도착 두 점만 이으면 건물을 뚫고 가는 직선이 된다. 버스는 도로를 따라 굽어 가므로
 * 그 모양을 표현하려면 중간 점이 촘촘히 있어야 한다. 그 점들의 목록이 폴리라인이다.
 *
 * <h3>구간마다 나눠 주는 이유</h3>
 * 전부 이어 붙인 하나의 선으로 주면 <b>도보와 버스를 다르게 그릴 수 없다.</b> 보통 도보는
 * 점선, 버스는 노선색 실선으로 그린다. {@code mode} 와 함께 구간별로 준다.
 *
 * <h3>좌표가 없는 구간도 있다</h3>
 * 외부 API 가 안 준 구간은 {@code coordinates} 가 빈 배열이다. 그 구간은 선을 못 그린다 —
 * 앞뒤 구간의 끝점을 직선으로 이을지, 아예 비울지는 화면이 정한다. 없는 좌표를 서버가
 * 지어내지 않는다.
 */
@Schema(description = "경로 지도 표시용 좌표")
public record RouteMapResponse(

        @Schema(description = "경로 ID")
        Long routeId,

        @Schema(description = "모든 좌표를 담는 사각형. 지도 초기 화면을 맞추는 데 쓴다. 좌표가 하나도 없으면 null")
        Bounds bounds,

        @Schema(description = "구간별 폴리라인. seq 오름차순")
        List<Segment> segments
) {

    @Schema(description = "지도 영역")
    public record Bounds(double minLat, double minLng, double maxLat, double maxLng) {
    }

    @Schema(description = "한 구간의 폴리라인")
    public record Segment(

            @Schema(description = "구간 순서 (1부터)")
            Integer seq,

            @Schema(description = "이동수단 — 선 스타일을 정하는 근거")
            TransportMode mode,

            @Schema(description = "타는 곳")
            String fromName,

            @Schema(description = "내리는 곳")
            String toName,

            @Schema(description = "경도·위도 순서의 좌표 목록. 외부 API 가 주지 않았으면 빈 배열")
            List<Coordinate> coordinates
    ) {
        public Segment {
            coordinates = coordinates == null ? List.of() : List.copyOf(coordinates);
        }
    }

    public static RouteMapResponse of(Long routeId, List<Segment> segments) {
        return new RouteMapResponse(routeId, bounds(segments), segments);
    }

    /** 좌표가 하나도 없으면 null 이다. 0,0 으로 채우면 화면이 아프리카 앞바다를 비춘다. */
    private static Bounds bounds(List<Segment> segments) {
        List<Coordinate> all = segments.stream().flatMap(segment -> segment.coordinates().stream()).toList();
        if (all.isEmpty()) {
            return null;
        }
        return new Bounds(
                all.stream().mapToDouble(Coordinate::latitude).min().orElseThrow(),
                all.stream().mapToDouble(Coordinate::longitude).min().orElseThrow(),
                all.stream().mapToDouble(Coordinate::latitude).max().orElseThrow(),
                all.stream().mapToDouble(Coordinate::longitude).max().orElseThrow());
    }
}

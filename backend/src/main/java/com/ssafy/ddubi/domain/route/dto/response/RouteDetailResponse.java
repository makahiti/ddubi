package com.ssafy.ddubi.domain.route.dto.response;

import com.ssafy.ddubi.domain.route.entity.SavedRoute;
import com.ssafy.ddubi.domain.route.entity.SavedRouteSegment;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 경로 하나의 구간 분해. "몇 번 버스를 타고 어디서 내리나" 를 답한다.
 *
 * <h3>좌표는 여기 없다</h3>
 *
 * 폴리라인은 구간당 수백 점이라 이 응답을 몇십 배로 부풀린다. 경로 안내 화면은 좌표를 쓰지 않고, 지도 화면은 {@code GET /routes/{routeId}/map}
 * 으로 따로 받는다.
 */
@Schema(description = "경로 상세 — 구간 분해")
public record RouteDetailResponse(
        @Schema(description = "경로 ID") Long routeId,
        @Schema(description = "이 경로가 속한 일정 ID") Long scheduleId,
        @Schema(description = "최신 순위") Integer rankNo,
        @Schema(description = "화면 표시 순위. 목록과 상세에서 같은 번호를 사용한다") Integer displayRank,
        @Schema(description = "주 경로로 선택된 후보인가") boolean selected,
        @Schema(description = "외부 API 기본 예상시간(분) — 뚜비 보정 전") Integer baseDurationMin,
        @Schema(description = "외부 API 경로 식별자") String providerPathId,
        @Schema(description = "구간 목록. seq 오름차순") List<Segment> segments) {

    @Schema(description = "경로의 한 구간")
    public record Segment(
            @Schema(description = "구간 순서 (1부터)") Integer seq,
            @Schema(description = "이동수단") TransportMode mode,
            @Schema(description = "외부 API 노선·역 식별자") String providerRouteKey,
            @Schema(description = "타는 곳") String fromName,
            @Schema(description = "내리는 곳") String toName,
            @Schema(description = "이 구간의 기본 소요시간(분)") Integer baseDurationMin) {

        private static Segment from(SavedRouteSegment segment) {
            return new Segment(
                    segment.getSeq(),
                    segment.getMode(),
                    segment.getProviderRouteKey(),
                    segment.getFromName(),
                    segment.getToName(),
                    segment.getBaseDurationMin());
        }
    }

    public static RouteDetailResponse from(SavedRoute route, int displayRank) {
        return new RouteDetailResponse(
                route.getId(),
                route.getSchedule().getId(),
                route.getRankNo(),
                displayRank,
                route.isSelected(),
                route.getBaseDurationMin(),
                route.getProviderPathId(),
                route.getSegments().stream().map(Segment::from).toList());
    }
}

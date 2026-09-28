package com.ssafy.ddubi.domain.route.dto.response;

import com.ssafy.ddubi.domain.route.entity.SavedRoute;
import com.ssafy.ddubi.domain.route.entity.SavedRouteSegment;
import com.ssafy.ddubi.domain.route.model.FareRange;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 후보 경로 목록의 한 줄. 화면이 "어떤 경로들이 있나" 를 고르는 데 필요한 것만 담는다.
 *
 * <p>구간 전체는 넣지 않는다. 후보가 열 개면 구간이 수십 개가 되고, 목록 화면은 그중 아무것도 쓰지 않는다. 상세는 {@code GET /routes/{routeId}}
 * 로 따로 받는다.
 */
@Schema(description = "후보 경로 요약")
public record RouteSummaryResponse(
        @Schema(description = "경로 ID — 상세·지도 조회에 쓴다") Long routeId,
        @Schema(description = "최신 순위. 재계산하면 바뀐다") Integer rankNo,
        @Schema(description = "화면 표시 순위. 선택 경로가 1이며 현재 목록 안에서 연속 번호를 사용한다") Integer displayRank,
        @Schema(description = "주 경로로 선택된 후보인가") boolean selected,
        @Schema(description = "외부 API 기본 예상시간(분) — 뚜비 보정 전") Integer baseDurationMin,
        @Schema(description = "해당 저장 경로의 예상 요금(원). 제공자가 주지 않았으면 null") FareRange fare,
        @Schema(description = "출발 지점 이름") String fromName,
        @Schema(description = "도착 지점 이름") String toName,
        @Schema(description = "구간 수") int segmentCount,
        @Schema(description = "이동수단 순서 — 예: [WALK, BUS, WALK]. 목록에 아이콘을 늘어놓을 근거")
                List<String> modes) {

    public static RouteSummaryResponse from(SavedRoute route, int displayRank) {
        List<SavedRouteSegment> segments = route.getSegments();
        return new RouteSummaryResponse(
                route.getId(),
                route.getRankNo(),
                displayRank,
                route.isSelected(),
                route.getBaseDurationMin(),
                route.fare(),
                segments.isEmpty() ? null : segments.get(0).getFromName(),
                segments.isEmpty() ? null : segments.get(segments.size() - 1).getToName(),
                segments.size(),
                segments.stream().map(segment -> segment.getMode().name()).toList());
    }
}

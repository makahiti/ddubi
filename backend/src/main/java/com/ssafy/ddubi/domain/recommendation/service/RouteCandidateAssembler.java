package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.route.entity.RouteRequest;
import com.ssafy.ddubi.domain.route.entity.SavedRoute;
import com.ssafy.ddubi.domain.route.entity.SavedRouteSegment;
import com.ssafy.ddubi.domain.route.entity.SavedRouteSegmentEdge;
import com.ssafy.ddubi.domain.route.mapping.MappedTransitSegment;
import com.ssafy.ddubi.domain.route.mapping.TransitMappingResult;
import com.ssafy.ddubi.domain.route.mapping.TransitMappingStatus;
import com.ssafy.ddubi.domain.route.model.RouteCandidate;
import com.ssafy.ddubi.domain.route.model.RouteSegment;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 외부 경로 후보 하나를 <b>저장할 엔티티</b>와 <b>산정기에 넣을 입력</b>으로 동시에 바꾼다.
 *
 * <h3>왜 한 곳에서 둘 다 만드나</h3>
 *
 * 두 결과는 같은 전개 규칙을 따라야 한다. 저장된 edge 와 계산에 쓴 edge 가 어긋나면, 나중에 그 추천을 열어 봐도 <b>저장된 경로로는 같은 숫자가 나오지
 * 않는다.</b> 따로 만들면 언젠가 한쪽만 고쳐진다.
 *
 * <h3>확정된 단일 canonical 경로만 edge로 전개한다</h3>
 *
 * 매핑이 PARTIAL·AMBIGUOUS 면 어떤 인접구간을 지나는지 확신할 수 없다. 확신 없는 ID 로 기준선·실시간 상태를 끌어오면 <b>다른 구간의 지연을 이 경로에
 * 얹게 된다.</b> 그런 step 은 edge 없이 두고 경로 API 시간을 그대로 쓴다.
 */
@Component
public class RouteCandidateAssembler {
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public RouteCandidateAssembler(com.fasterxml.jackson.databind.ObjectMapper json) {
        this.json = json;
    }

    private String geometry(RouteSegment segment) {
        try {
            return json.writeValueAsString(segment.geometry());
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("경로 좌표 직렬화 실패");
        }
    }

    /** 후보 하나를 전개한 결과. 엔티티와 산정 입력이 같은 순서로 짝을 이룬다. */
    public record Assembled(SavedRoute route, List<StepEstimateInput> steps) {
        public Assembled {
            steps = List.copyOf(steps);
        }
    }

    public Assembled assemble(
            Schedule schedule, RouteRequest routeRequest, RouteCandidate candidate, int rankNo) {
        SavedRoute route =
                SavedRoute.builder()
                        .schedule(schedule)
                        .routeRequest(routeRequest)
                        .providerPathId(String.valueOf(candidate.providerCandidateIndex()))
                        .baseDurationMin(toMinutes(candidate.totalDurationSeconds()))
                        .fareMinimumWon(
                                candidate.fare() == null ? null : candidate.fare().minimumWon())
                        .fareMaximumWon(
                                candidate.fare() == null ? null : candidate.fare().maximumWon())
                        .selected(false)
                        .rankNo(rankNo)
                        .build();

        List<StepEstimateInput> steps = new ArrayList<>(candidate.segments().size());

        for (RouteSegment segment : candidate.segments()) {
            SavedRouteSegment saved =
                    SavedRouteSegment.builder()
                            .seq(segment.sequence())
                            .mode(segment.mode())
                            .providerRouteKey(
                                    com.ssafy.ddubi.domain.recommendation.evaluation
                                            .BusAlternativeExpansion.displayedRouteKey(segment))
                            .fromName(segment.fromName())
                            .toName(segment.toName())
                            .geometry(geometry(segment))
                            .baseDurationMin(toMinutes(segment.durationSeconds()))
                            .build();
            route.addSegment(saved);

            List<EdgeEstimateInput> edges = expand(segment, saved);
            steps.add(
                    new StepEstimateInput(
                            segment.sequence(), segment.mode(), segment.durationSeconds(), edges));
        }

        return new Assembled(route, steps);
    }

    /** provider step 을 canonical 인접구간으로 전개하고, 같은 내용을 {@code route_segment_edge} 로도 남긴다. */
    private List<EdgeEstimateInput> expand(RouteSegment segment, SavedRouteSegment saved) {
        TransitMappingResult mapping = segment.mapping();
        if (mapping == null || mapping.status() != TransitMappingStatus.EXACT) {
            return List.of();
        }

        List<EdgeEstimateInput> edges = new ArrayList<>(mapping.segments().size());
        for (MappedTransitSegment mapped :
                com.ssafy.ddubi.domain.recommendation.evaluation.RouteEvidenceAssembler.edges(
                        segment)) {
            saved.addEdge(
                    SavedRouteSegmentEdge.builder()
                            .edgeSeq(mapped.sequence())
                            .segmentId(mapped.segmentId())
                            .mappingVersion(mapping.masterDataVersion())
                            .policyVersion(mapping.mappingPolicyVersion())
                            .build());

            edges.add(
                    new EdgeEstimateInput(mapped.sequence(), mapped.segmentId(), busKeyOf(mapped)));
        }
        return edges;
    }

    /**
     * 예측 모델이 쓰는 제공자 ID 묶음. 버스가 아니면 null 이다 — 모델이 커버하는 것은 버스 구간 이동시간뿐이다(ML 결정 문서 2026-09-08).
     *
     * <p>여기 들어가는 값은 카카오 ID 가 아니라 <b>서울 공식 마스터에서 확정한 ID</b>다. {@code MappedTransitSegment} 가 그렇게
     * 만들어져 있어 그대로 옮기면 된다.
     */
    private BusProviderKey busKeyOf(MappedTransitSegment mapped) {
        if (mapped.mode() != TransportMode.BUS) {
            return null;
        }
        return new BusProviderKey(
                mapped.routeId(), mapped.fromNodeId(), mapped.toNodeId(), mapped.fromOrder());
    }

    /**
     * DB 는 분 단위다. <b>올림</b>한다 — 90초를 1분으로 깎으면 그만큼 출발이 늦어지고, 늦는 쪽의 손해가 일찍 나가는 쪽보다 크다.
     *
     * <p>계산 자체는 초 단위로 하고 이 값은 표시·기록용이다.
     */
    private static int toMinutes(long seconds) {
        return (int) ((seconds + 59) / 60);
    }
}

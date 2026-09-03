package com.ssafy.ddubi.domain.route.dto;

import com.ssafy.ddubi.domain.route.model.Coordinate;
import com.ssafy.ddubi.domain.route.model.FareRange;
import com.ssafy.ddubi.domain.route.model.RouteCandidate;
import com.ssafy.ddubi.domain.route.model.RouteSearchResult;
import com.ssafy.ddubi.domain.route.model.RouteSegment;
import com.ssafy.ddubi.domain.route.model.RouteType;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import com.ssafy.ddubi.domain.route.model.VehicleInfo;
import com.ssafy.ddubi.domain.route.mapping.TransitMappingResult;
import com.ssafy.ddubi.domain.route.mapping.TransitMappingStatus;
import com.ssafy.ddubi.domain.route.mapping.RealtimeMappingCoverage;

import java.time.Instant;
import java.util.List;

/**
 * 백엔드가 프론트에 공개하는 길찾기 응답이다.
 * 카카오 원본 DTO와 내부 모델을 직접 노출하지 않기 위해 별도로 정의했다.
 */
public record RouteSearchResponse(
        String provider,
        Instant fetchedAt,
        String landingUrl,
        List<Candidate> candidates
) {
    /** 내부 검색 결과를 외부 공개용 응답으로 복사한다. */
    public static RouteSearchResponse from(RouteSearchResult result) {
        return new RouteSearchResponse(
                result.provider(),
                result.fetchedAt(),
                result.landingUrl(),
                result.candidates().stream().map(Candidate::from).toList()
        );
    }

    /** 여러 후보 중 하나. 예: 버스만 이용하는 후보, 지하철+버스를 이용하는 후보. */
    public record Candidate(
            // 카카오 응답 배열에서의 원래 위치. 카카오 배열은 반드시 최단 시간순이 아니다.
            int providerCandidateIndex,
            RouteType type,
            // 후보 전체 거리(m)와 시간(초). 각 구간을 합산한 값이 아니라 카카오 전체값이다.
            long totalDistanceMeters,
            long totalDurationSeconds,
            int transfers,
            FareRange fare,
            // 전체값에서 구간 합을 뺀 차이. 대기·환승 등 독립 구간으로 나오지 않은 부분이다.
            long unassignedDistanceMeters,
            long unassignedDurationSeconds,
            // 후보별 매핑 성공·실패 수. 실패율을 숨기지 않고 운영 지표와 수동 검증에 사용한다.
            MappingSummary mappingSummary,
            List<Segment> segments
    ) {
        private static Candidate from(RouteCandidate candidate) {
            return new Candidate(
                    candidate.providerCandidateIndex(),
                    candidate.type(),
                    candidate.totalDistanceMeters(),
                    candidate.totalDurationSeconds(),
                    candidate.transfers(),
                    candidate.fare(),
                    candidate.unassignedDistanceMeters(),
                    candidate.unassignedDurationSeconds(),
                    MappingSummary.from(candidate.segments()),
                    candidate.segments().stream().map(Segment::from).toList()
            );
        }
    }

    /** WALK를 제외한 대중교통 step의 마스터 매핑 품질 집계다. */
    public record MappingSummary(
            int transitStepCount,
            int exactCount,
            int partialCount,
            int ambiguousCount,
            int unmappedCount,
            int realtimeFullStepCount,
            int realtimePartialStepCount,
            int realtimeUnavailableStepCount
    ) {
        private static MappingSummary from(List<RouteSegment> segments) {
            int exact = 0;
            int partial = 0;
            int ambiguous = 0;
            int unmapped = 0;
            int realtimeFull = 0;
            int realtimePartial = 0;
            int realtimeUnavailable = 0;
            for (RouteSegment segment : segments) {
                TransitMappingStatus status = segment.mapping().status();
                switch (status) {
                    case EXACT -> exact++;
                    case PARTIAL -> partial++;
                    case AMBIGUOUS -> ambiguous++;
                    case UNMAPPED -> unmapped++;
                    // WALK는 대중교통 마스터의 성공률 분모에 넣지 않는다.
                    case NOT_APPLICABLE -> { }
                }
                if (status != TransitMappingStatus.NOT_APPLICABLE) {
                    RealtimeMappingCoverage coverage = segment.mapping().realtimeCoverage();
                    switch (coverage) {
                        case FULL -> realtimeFull++;
                        case PARTIAL -> realtimePartial++;
                        case NONE -> realtimeUnavailable++;
                        case NOT_APPLICABLE -> { }
                    }
                }
            }
            return new MappingSummary(exact + partial + ambiguous + unmapped,
                    exact, partial, ambiguous, unmapped,
                    realtimeFull, realtimePartial, realtimeUnavailable);
        }
    }

    /** 후보 하나를 다시 버스·지하철·도보 단위로 쪼갠 이동 구간. */
    public record Segment(
            // 한 후보 안에서의 이동 순서이며 1부터 시작한다.
            int sequence,
            TransportMode mode,
            // WALK는 null. BUS/SUBWAY도 공통 ID 계약이 확정되기 전까지 null일 수 있다.
            String segmentId,
            // 카카오 차량/노선 이름. 안정적인 내부 ID가 아니라 추후 매핑에 사용할 근거다.
            String providerRouteKey,
            String guidance,
            String fromName,
            String toName,
            long distanceMeters,
            long durationSeconds,
            List<String> stopNames,
            List<VehicleInfo> vehicles,
            // 카카오 path.points를 [경도, 위도] 순서로 변환한 지도 표시용 좌표다.
            List<Coordinate> geometry,
            // 공식 마스터 매핑 상태·ID·인접 구간. EXACT만 시간 연산에 사용한다.
            TransitMappingResult mapping
    ) {
        private static Segment from(RouteSegment segment) {
            return new Segment(
                    segment.sequence(),
                    segment.mode(),
                    segment.segmentId(),
                    segment.providerRouteKey(),
                    segment.guidance(),
                    segment.fromName(),
                    segment.toName(),
                    segment.distanceMeters(),
                    segment.durationSeconds(),
                    segment.stopNames(),
                    segment.vehicles(),
                    segment.geometry(),
                    segment.mapping()
            );
        }
    }
}

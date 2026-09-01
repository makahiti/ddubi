package com.ssafy.ddubi.domain.route.service;

import com.ssafy.ddubi.domain.route.client.dto.KakaoPublicTransitResponse;
import com.ssafy.ddubi.domain.route.model.Coordinate;
import com.ssafy.ddubi.domain.route.model.FareRange;
import com.ssafy.ddubi.domain.route.model.RouteCandidate;
import com.ssafy.ddubi.domain.route.model.RouteSearchResult;
import com.ssafy.ddubi.domain.route.model.RouteSegment;
import com.ssafy.ddubi.domain.route.model.RouteType;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import com.ssafy.ddubi.domain.route.model.VehicleInfo;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 카카오 전용 응답을 카카오와 무관한 우리 내부 경로 모델로 번역한다.
 * 외부 API의 필드명이 바뀌면 주로 이 클래스와 카카오 응답 DTO만 수정하면 된다.
 */
@Component
public class KakaoRouteMapper {

    // 버스·지하철 구간을 우리 파이프라인의 segment_id와 연결하는 별도 담당자다.
    private final SegmentIdResolver segmentIdResolver;

    public KakaoRouteMapper(SegmentIdResolver segmentIdResolver) {
        this.segmentIdResolver = segmentIdResolver;
    }

    /**
     * 카카오 전체 응답을 우리 서비스가 이해하는 검색 결과로 바꾼다.
     * fetchedAt을 밖에서 받는 이유는 테스트에서 호출 시각을 고정할 수 있게 하기 위해서다.
     */
    public RouteSearchResult map(KakaoPublicTransitResponse response, Instant fetchedAt) {
        // 성공 상태와 후보 존재 여부를 먼저 확인한다.
        validateResponse(response);

        // 카카오 배열의 순서를 잃지 않도록 index를 후보 번호로 같이 저장한다.
        List<RouteCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < response.routes().size(); index++) {
            candidates.add(mapCandidate(index, response.routes().get(index)));
        }

        String landingUrl = response.properties() == null ? null : response.properties().landingURL();
        return new RouteSearchResult("KAKAO", fetchedAt, landingUrl, candidates);
    }

    private void validateResponse(KakaoPublicTransitResponse response) {
        if (response == null) {
            throw providerResponseError("카카오 응답 본문이 비어 있습니다.");
        }
        if (!"OK".equals(response.status())) {
            // 카카오 상태를 우리 공통 ErrorCode로 번역한다.
            throw switch (Objects.toString(response.status(), "")) {
                case "EQUAL_POINTS", "INVALID_REQUEST" ->
                        new BusinessException(ErrorCode.INVALID_REQUEST);
                case "STARTNODES_NULL", "ENDNODES_NULL", "NO_RESULTS" ->
                        new BusinessException(ErrorCode.ROUTE_NOT_AVAILABLE);
                default -> providerResponseError("알 수 없는 카카오 응답 상태입니다.");
            };
        }
        if (response.routes() == null || response.routes().isEmpty()) {
            throw new BusinessException(ErrorCode.ROUTE_NOT_AVAILABLE);
        }
    }

    private RouteCandidate mapCandidate(int index, KakaoPublicTransitResponse.Route route) {
        if (route == null || route.properties() == null || route.steps() == null) {
            throw providerResponseError("카카오 후보 경로의 필수 필드가 없습니다.");
        }

        KakaoPublicTransitResponse.RouteProperties properties = route.properties();
        List<RouteSegment> segments = new ArrayList<>();
        // step 배열 하나하나가 BUS/SUBWAY/WALK 구간 하나가 된다. sequence는 DB 규칙대로 1부터 시작한다.
        for (int stepIndex = 0; stepIndex < route.steps().size(); stepIndex++) {
            segments.add(mapSegment(stepIndex + 1, route.steps().get(stepIndex)));
        }

        // 카카오 전체값과 step 합은 다를 수 있다. 전체값을 버리지 않고 차이도 따로 계산한다.
        long totalDistance = requiredNonNegative(properties.totalDistance(), "totalDistance");
        long totalDuration = requiredNonNegative(properties.totalTime(), "totalTime");
        long segmentDistance = segments.stream().mapToLong(RouteSegment::distanceMeters).sum();
        long segmentDuration = segments.stream().mapToLong(RouteSegment::durationSeconds).sum();

        return new RouteCandidate(
                index,
                parseRouteType(properties.type()),
                totalDistance,
                totalDuration,
                properties.transfers() == null ? 0 : properties.transfers(),
                mapFare(properties.fare()),
                Math.max(0, totalDistance - segmentDistance),
                Math.max(0, totalDuration - segmentDuration),
                segments
        );
    }

    private RouteSegment mapSegment(int sequence, KakaoPublicTransitResponse.Step step) {
        if (step == null || step.properties() == null) {
            throw providerResponseError("카카오 이동 구간의 필수 필드가 없습니다.");
        }

        KakaoPublicTransitResponse.StepProperties properties = step.properties();
        // 카카오의 WALKING 문자열을 우리 enum의 WALK로 바꾸는 작업도 여기서 한다.
        TransportMode mode = parseMode(properties.type());

        // 정류장 객체에서 이름만 뽑아 순서를 그대로 유지한다.
        List<String> stopNames = safeList(properties.stops()).stream()
                .map(KakaoPublicTransitResponse.Stop::name)
                .filter(Objects::nonNull)
                .toList();
        // 차량은 한 구간에 여러 대안(예: 51번 또는 119번)이 올 수 있다.
        List<VehicleInfo> vehicles = safeList(properties.vehicles()).stream()
                .map(vehicle -> new VehicleInfo(vehicle.name(), vehicle.type()))
                .toList();
        // 공개 API에 안정적인 route ID가 없으므로 차량 이름을 '매핑 근거'로만 보존한다.
        String providerRouteKey = vehicles.stream()
                .map(VehicleInfo::name)
                .filter(Objects::nonNull)
                .distinct()
                .reduce((left, right) -> left + "," + right)
                .orElse(null);
        // 첫 정류장을 출발지, 마지막 정류장을 도착지로 사용한다.
        String fromName = stopNames.isEmpty() ? null : stopNames.get(0);
        String toName = stopNames.isEmpty() ? null : stopNames.get(stopNames.size() - 1);

        String segmentId = null;
        if (mode != TransportMode.WALK) {
            // ID 형식을 이 Mapper에 하드코딩하지 않는다. 현재 기본 Resolver는 미확정이라 빈 값을 준다.
            segmentId = segmentIdResolver.resolve(new SegmentResolveRequest(
                    mode, providerRouteKey, fromName, toName, stopNames, vehicles
            )).orElse(null);
        }

        return new RouteSegment(
                sequence,
                mode,
                segmentId,
                providerRouteKey,
                properties.guidance(),
                fromName,
                toName,
                requiredNonNegative(properties.distance(), "step.distance"),
                requiredNonNegative(properties.time(), "step.time"),
                stopNames,
                vehicles,
                mapGeometry(step.path())
        );
    }

    private FareRange mapFare(KakaoPublicTransitResponse.Fare fare) {
        if (fare == null) {
            return new FareRange(null, null);
        }
        if (fare.value() != null) {
            // value=1650처럼 하나만 오면 최소와 최대를 둘 다 1650으로 맞춘다.
            return new FareRange(fare.value(), fare.value());
        }
        // min/max 형태는 범위를 그대로 보존한다.
        return new FareRange(fare.min(), fare.max());
    }

    private List<Coordinate> mapGeometry(KakaoPublicTransitResponse.Path path) {
        if (path == null || path.points() == null) {
            return List.of();
        }
        // 깨진 좌표는 제외하고 [경도, 위도] 쌍만 Coordinate로 바꾼다.
        return path.points().stream()
                .filter(point -> point != null && point.size() >= 2
                        && point.get(0) != null && point.get(1) != null)
                .map(point -> new Coordinate(point.get(0), point.get(1)))
                .toList();
    }

    private RouteType parseRouteType(String value) {
        try {
            return RouteType.valueOf(value);
        } catch (RuntimeException exception) {
            throw providerResponseError("지원하지 않는 카카오 경로 종류입니다.");
        }
    }

    private TransportMode parseMode(String value) {
        return switch (Objects.toString(value, "")) {
            case "WALKING" -> TransportMode.WALK;
            case "BUS" -> TransportMode.BUS;
            case "SUBWAY" -> TransportMode.SUBWAY;
            default -> throw providerResponseError("지원하지 않는 카카오 이동 구간 종류입니다.");
        };
    }

    private long requiredNonNegative(Long value, String field) {
        // 거리·시간이 없거나 음수면 잘못된 외부 응답이므로 조용히 0으로 만들지 않는다.
        if (value == null || value < 0) {
            throw providerResponseError("카카오 응답의 " + field + " 값이 올바르지 않습니다.");
        }
        return value;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private BusinessException providerResponseError(String message) {
        return new BusinessException(ErrorCode.ROUTE_PROVIDER_UNAVAILABLE, message);
    }
}

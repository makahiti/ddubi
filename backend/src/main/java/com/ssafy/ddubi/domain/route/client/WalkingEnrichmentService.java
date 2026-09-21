package com.ssafy.ddubi.domain.route.client;

import static com.ssafy.ddubi.domain.route.model.RouteLegRoleClassifier.Role;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.model.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 접근·이탈 WALK를 검증된 제휴 결과로 교체한다. 환승 WALK와 provider 총시간은 중복 가산하지 않는다. 카카오 경로 끝점 또는 검증된 공식 좌표를 사용하고 출처를
 * 구별한다. API 또는 좌표 검증이 실패하면 기존 WALK 시간과 실패 근거를 반환한다. 총시간은 구간 합으로 다시 만든다. 동일 좌표 요청은 한 평가 안에서 캐시하지만 후보
 * 수에 따른 인위적인 호출 횟수 제한은 두지 않는다. 외부 제공자의 timeout·429·오류는 클라이언트 경계에서 실패 근거로 보존한다.
 */
@Service
public class WalkingEnrichmentService {
    private final WalkingRouteClient client;
    private final boolean enabled;
    private final double maxMeters;
    private final List<WalkingEndpointResolver.MasterPoint> points;
    private final WalkingCoordinateArtifact artifact;
    private final boolean providerEndpoints;

    /** 기존 fixture는 공식 마스터 좌표 검증 정책을 유지한다. 운영은 명시적인 provider 좌표 정책을 주입한다. */
    public WalkingEnrichmentService(
            WalkingRouteClient client,
            ObjectMapper json,
            boolean enabled,
            double maxMeters,
            String path) {
        this(client, json, enabled, maxMeters, path, false);
    }

    /**
     * 이전 테스트·내부 호출부의 생성자 호환용이다. ignoredCallLimit 값은 더 이상 저장하거나 적용하지 않는다. 운영 설정에는 이 인자가 없으며 모든 신규 좌표
     * 요청을 호출한다.
     */
    public WalkingEnrichmentService(
            WalkingRouteClient client,
            ObjectMapper json,
            boolean enabled,
            int ignoredCallLimit,
            double maxMeters,
            String path) {
        this(client, json, enabled, maxMeters, path, false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WalkingEnrichmentService(
            WalkingRouteClient client,
            ObjectMapper json,
            @Value("${ddubi.walking.enabled:true}") boolean enabled,
            @Value("${ddubi.walking.endpoint-max-meters:0}") double maxMeters,
            @Value("${ddubi.walking.master-coordinate-path:}") String path,
            @Value("${ddubi.walking.provider-endpoints:true}") boolean providerEndpoints) {
        if (maxMeters < 0 || !Double.isFinite(maxMeters))
            throw new IllegalArgumentException("도보 정책 범위 오류");
        this.client = client;
        this.enabled = enabled;
        this.maxMeters = maxMeters;
        List<WalkingEndpointResolver.MasterPoint> loaded = List.of();
        WalkingCoordinateArtifact coordinateArtifact = null;
        if (path != null && !path.isBlank())
            try {
                if (Files.size(Path.of(path)) > 32 * 1024 * 1024)
                    throw new IllegalArgumentException();
                try (var input = Files.newInputStream(Path.of(path))) {
                    var root = json.readTree(input);
                    if (root.isArray())
                        loaded =
                                List.of(
                                        json.treeToValue(
                                                root, WalkingEndpointResolver.MasterPoint[].class));
                    else coordinateArtifact = new WalkingCoordinateArtifact(root);
                }
            } catch (Exception invalid) {
                loaded = List.of();
            }
        points = List.copyOf(loaded);
        artifact = coordinateArtifact;
        this.providerEndpoints = providerEndpoints;
    }

    /** provider 끝점 정책을 직접 주입하는 이전 테스트의 호환용이며 ignoredCallLimit은 적용하지 않는다. */
    public WalkingEnrichmentService(
            WalkingRouteClient client,
            ObjectMapper json,
            boolean enabled,
            int ignoredCallLimit,
            double maxMeters,
            String path,
            boolean providerEndpoints) {
        this(client, json, enabled, maxMeters, path, providerEndpoints);
    }

    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Leg(
            String candidateKey,
            int sequence,
            String legRole,
            long durationSeconds,
            String durationSource,
            EndpointEvidence endpointEvidence) {
        /** 저장된 이전 v2 평가도 새 공개 enum으로 읽는다. 과거 근거의 검증 여부는 별도 보존한다. */
        public Leg {
            if ("WALK_ONLY".equals(legRole)) legRole = "ACCESS_WALK";
            if ("KAKAO_AFFILIATE_WALKING".equals(durationSource))
                durationSource = "KAKAO_WALKING_API";
            if ("KAKAO_TRANSIT".equals(durationSource)) durationSource = "PUBLIC_TRANSIT_STEP";
        }
    }

    /** validUntil은 실제 교체한 도보 응답 중 가장 빠른 만료다. 미적용/legacy 값은 null이다. */
    public record Evidence(
            List<Leg> legs,
            List<String> fallbackReasons,
            int apiCalls,
            Instant validUntil,
            Map<String, List<String>> candidateFallbackReasons) {
        public Evidence {
            candidateFallbackReasons =
                    candidateFallbackReasons == null
                            ? Map.of()
                            : Map.copyOf(candidateFallbackReasons);
        }

        public Evidence(
                List<Leg> legs, List<String> fallbackReasons, int apiCalls, Instant validUntil) {
            this(legs, fallbackReasons, apiCalls, validUntil, Map.of());
        }
    }

    public record Prepared(List<RouteCandidate> candidates, Evidence evidence) {}

    /**
     * 일정에 고정 저장할 도보 결과의 수명을 일정 종료시각까지 맞춘다. 카카오 응답 직후의 짧은 유효시간은 응답 검증용이지, 정적인 도보 구간을 매분 다시 호출하라는 의미가
     * 아니다. 실제 카카오 시간이 한 구간에도 적용되지 않았다면 실패 결과에 임의 유효기간을 붙이지 않는다.
     */
    public Prepared retainUntil(Prepared prepared, Instant scheduleEndsAt) {
        Objects.requireNonNull(prepared);
        Objects.requireNonNull(scheduleEndsAt);
        Evidence current = prepared.evidence();
        boolean applied =
                current.legs().stream()
                        .anyMatch(leg -> "KAKAO_WALKING_API".equals(leg.durationSource()));
        Evidence retained =
                new Evidence(
                        current.legs(),
                        current.fallbackReasons(),
                        current.apiCalls(),
                        applied ? scheduleEndsAt : null,
                        current.candidateFallbackReasons());
        return new Prepared(prepared.candidates(), retained);
    }

    /** 후보 재검색이 일어나도 동일 좌표 요청 결과를 공유한다. calls는 제한값이 아니라 실제 외부 호출 횟수 진단값이다. */
    public static final class Session {
        private final Map<WalkingRouteClient.Request, WalkingRouteClient.Evidence> cache =
                new HashMap<>();
        private int calls;
    }

    public Prepared prepare(
            List<RouteCandidate> routes, Coordinate origin, Coordinate destination, Instant asOf) {
        return prepare(routes, origin, destination, asOf, points);
    }

    /** 선택 후보를 먼저, 나머지는 provider 예상시간 순서로 보강한다. candidate key와 원본 비용은 보존한다. */
    public Prepared prepare(
            List<RouteCandidate> routes,
            Coordinate origin,
            Coordinate destination,
            Instant asOf,
            String selectedKey) {
        return prepare(routes, origin, destination, asOf, selectedKey, new Session());
    }

    public Prepared prepare(
            List<RouteCandidate> routes,
            Coordinate origin,
            Coordinate destination,
            Instant asOf,
            String selectedKey,
            Session session) {
        var prioritized =
                routes.stream()
                        .sorted(
                                Comparator.comparing(
                                                (RouteCandidate r) ->
                                                        !Objects.equals(
                                                                com.ssafy.ddubi.domain
                                                                        .recommendation.evaluation
                                                                        .RouteEvidenceAssembler.key(
                                                                        r),
                                                                selectedKey))
                                        .thenComparingLong(RouteCandidate::totalDurationSeconds))
                        .toList();
        return prepare(prioritized, origin, destination, asOf, points, session);
    }

    /** fake fixture도 운영과 같은 EXACT·비용 검사 경로를 통과한다. API 장애는 후보별로 격리한다. */
    public Prepared prepare(
            List<RouteCandidate> routes,
            Coordinate origin,
            Coordinate destination,
            Instant asOf,
            List<WalkingEndpointResolver.MasterPoint> master) {
        return prepare(routes, origin, destination, asOf, master, new Session());
    }

    public Prepared prepare(
            List<RouteCandidate> routes,
            Coordinate origin,
            Coordinate destination,
            Instant asOf,
            List<WalkingEndpointResolver.MasterPoint> master,
            Session session) {
        var output = new ArrayList<RouteCandidate>();
        var metadata = new ArrayList<Leg>();
        var reasons = new LinkedHashSet<String>();
        var candidateReasons = new LinkedHashMap<String, List<String>>();
        var cache = session.cache;
        Instant validUntil = null;
        for (var route : routes) {
            var routeReasons = new LinkedHashSet<String>();
            var transit =
                    route.segments().stream().filter(s -> s.mode() != TransportMode.WALK).toList();
            var originalRoles = new RouteLegRoleClassifier().classify(route.segments());
            var inputSteps = new ArrayList<>(route.segments());
            // 양 끝 도보 누락은 원본 총시간과 관계없이 조회한다. 환승 도보는 그대로 유지한다.
            if (!transit.isEmpty()) {
                boolean accessMissing = !originalRoles.containsValue(Role.ACCESS_WALK);
                boolean egressMissing = !originalRoles.containsValue(Role.EGRESS_WALK);
                if (enabled) {
                    if (accessMissing) {
                        var firstRide = transit.get(0);
                        inputSteps.add(
                                0,
                                syntheticWalk(
                                        route.segments().stream()
                                                        .mapToInt(RouteSegment::sequence)
                                                        .min()
                                                        .orElse(1)
                                                - 1,
                                        "출발지",
                                        endpointName(firstRide.fromName(), "첫 승차 지점")));
                    }
                    if (egressMissing) {
                        var lastRide = transit.get(transit.size() - 1);
                        inputSteps.add(
                                syntheticWalk(
                                        route.segments().stream()
                                                        .mapToInt(RouteSegment::sequence)
                                                        .max()
                                                        .orElse(0)
                                                + 1,
                                        endpointName(lastRide.toName(), "마지막 하차 지점"),
                                        "도착지"));
                    }
                } else if (accessMissing || egressMissing) {
                    routeReasons.add("WALK_DISABLED");
                }
            }
            var roles = new RouteLegRoleClassifier().classify(inputSteps);
            var steps = new ArrayList<RouteSegment>();
            for (var step : inputSteps) {
                Role role = roles.get(step.sequence());
                long seconds = step.durationSeconds();
                String source = "PUBLIC_TRANSIT_STEP";
                // 지도용 좌표. 도보 API 가 실제 걷는 길을 주면 그것으로 바꾼다.
                List<Coordinate> path = step.geometry();
                EndpointEvidence endpoint = null;
                String fallback = null;
                if (role == Role.ACCESS_WALK || role == Role.EGRESS_WALK) {
                    boolean access = role == Role.ACCESS_WALK;
                    if (transit.isEmpty()) fallback = "WALK_NO_TRANSIT_ENDPOINT";
                    else if (!enabled) fallback = "WALK_DISABLED";
                    else if (roles.values().stream().filter(r -> r == role).count() != 1)
                        fallback = "WALK_COST_COVERAGE_UNRESOLVED";
                    else {
                        var ride = access ? transit.get(0) : transit.get(transit.size() - 1);
                        var coordinates =
                                artifact == null ? master : artifact.matching(ride, access, asOf);
                        coordinates =
                                coordinates.stream()
                                        .filter(
                                                p ->
                                                        p.validUntil() == null
                                                                || p.validUntil().isAfter(asOf))
                                        .toList();
                        var resolved =
                                new WalkingEndpointResolver()
                                        .resolve(ride, access, coordinates, maxMeters);
                        // 카카오 경로 자체의 끝점을 사용할 때는 canonical EXACT/역 출입구라고
                        // 표시하지 않는다. 외부 경로 좌표를 이용한 접근·이탈 예상이라는 출처를 남긴다.
                        if (providerEndpoints && artifact == null && master.isEmpty())
                            resolved = providerEndpoint(ride, access);
                        endpoint = resolved.evidence();
                        if (resolved.verified()) {
                            var request =
                                    new WalkingRouteClient.Request(
                                            access ? origin : resolved.coordinate(),
                                            access ? resolved.coordinate() : destination,
                                            "SHORTEST",
                                            EndpointEvidence.POLICY_VERSION);
                            var found = cache.get(request);
                            if (found == null) {
                                session.calls++;
                                try {
                                    found = client.route(request);
                                } catch (RuntimeException failure) {
                                    found =
                                            new WalkingRouteClient.Evidence(
                                                    null,
                                                    null,
                                                    "KAKAO_WALKING_API",
                                                    "WALK_PROVIDER_UNAVAILABLE");
                                }
                                if (found == null)
                                    found =
                                            new WalkingRouteClient.Evidence(
                                                    null, null, null, "WALK_EMPTY_RESPONSE");
                                cache.put(request, found);
                            }
                            if (found != null
                                    && found.seconds() != null
                                    && found.seconds() > 0
                                    && found.seconds() <= 86400
                                    && found.validUntil() != null
                                    && found.validUntil().isAfter(asOf)) {
                                seconds = found.seconds();
                                source = "KAKAO_WALKING_API";
                                // 시간과 같은 응답에서 온 좌표만 쓴다. 지도에 그려지는 선과
                                // 화면에 적히는 시간이 서로 다른 조회 결과이면 안 된다.
                                if (!found.path().isEmpty()) path = found.path();
                                if (validUntil == null || found.validUntil().isBefore(validUntil))
                                    validUntil = found.validUntil();
                                for (var point : coordinates)
                                    if (point.validUntil() != null
                                            && point.validUntil().isBefore(validUntil))
                                        validUntil = point.validUntil();
                            } else
                                fallback =
                                        found.reason() == null
                                                ? "WALK_INVALID_RESPONSE"
                                                : found.reason();
                        } else fallback = resolved.evidence().reason();
                    }
                    if (fallback != null) {
                        reasons.add(fallback);
                        routeReasons.add(fallback);
                        if (endpoint == null) endpoint = EndpointEvidence.unavailable(fallback);
                    }
                }
                if ("EVALUATION_ENDPOINT_WALK".equals(step.providerRouteKey())
                        && !"KAKAO_WALKING_API".equals(source)) {
                    // 원본에 없던 도보의 API 실패를 0초 성공으로 만들지 않는다.
                    routeReasons.add("WALK_ENDPOINT_DURATION_UNAVAILABLE");
                    continue;
                }
                steps.add(
                        new RouteSegment(
                                step.sequence(),
                                step.mode(),
                                step.segmentId(),
                                step.providerRouteKey(),
                                step.guidance(),
                                step.fromName(),
                                step.toName(),
                                step.distanceMeters(),
                                seconds,
                                step.stopNames(),
                                step.vehicles(),
                                path,
                                step.mapping()));
                // 동일 카카오 후보에서 전개한 버스 대안도 서로 다른 증거 key를 유지한다.
                metadata.add(
                        new Leg(
                                com.ssafy.ddubi.domain.recommendation.evaluation
                                        .RouteEvidenceAssembler.key(route),
                                step.sequence(),
                                role.name(),
                                seconds,
                                source,
                                endpoint));
            }
            // 사용자 정책: 총시간은 명시된 구간의 합으로 다시 만든다.
            // provider 총시간의 미분류 몫은 배분하거나 대기로 가정하지 않는다.
            output.add(
                    new RouteCandidate(
                            route.providerCandidateIndex(),
                            route.type(),
                            route.totalDistanceMeters(),
                            steps.stream().mapToLong(RouteSegment::durationSeconds).sum(),
                            route.transfers(),
                            route.fare(),
                            route.unassignedDistanceMeters(),
                            0,
                            steps));
            candidateReasons.put(
                    com.ssafy.ddubi.domain.recommendation.evaluation.RouteEvidenceAssembler.key(
                            route),
                    List.copyOf(routeReasons));
            reasons.addAll(routeReasons);
        }
        return new Prepared(
                List.copyOf(output),
                new Evidence(
                        List.copyOf(metadata),
                        List.copyOf(reasons),
                        session.calls,
                        validUntil,
                        candidateReasons));
    }

    /**
     * 원본 대중교통 후보에 빠진 양 끝 도보를 저장 가능한 구간으로 만든다. 도보 API가 성공하면 이 구간도
     * {@code route_segment}에 영속화되므로, DB의 NOT NULL 계약에 맞춰 출발·도착 이름을 생성 시점부터 보존한다.
     */
    private static RouteSegment syntheticWalk(int sequence, String fromName, String toName) {
        return new RouteSegment(
                sequence,
                TransportMode.WALK,
                null,
                "EVALUATION_ENDPOINT_WALK",
                null,
                fromName,
                toName,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    /** 제공자 응답에 정류장명이 없더라도 합성 도보의 DB 계약과 사용자 경로 설명을 함께 지킨다. */
    private static String endpointName(String providerName, String fallback) {
        return providerName == null || providerName.isBlank() ? fallback : providerName;
    }

    private static WalkingEndpointResolver.Endpoint providerEndpoint(
            RouteSegment ride, boolean access) {
        Coordinate point =
                ride.geometry().isEmpty()
                        ? null
                        : ride.geometry().get(access ? 0 : ride.geometry().size() - 1);
        if (point == null
                || !Double.isFinite(point.latitude())
                || !Double.isFinite(point.longitude())
                || Math.abs(point.latitude()) > 90
                || Math.abs(point.longitude()) > 180)
            return new WalkingEndpointResolver.Endpoint(
                    null, EndpointEvidence.unavailable("WALK_PROVIDER_ENDPOINT_MISSING"), false);
        return new WalkingEndpointResolver.Endpoint(
                point,
                new EndpointEvidence(
                        true,
                        "KAKAO_PUBLIC_TRANSIT",
                        null,
                        null,
                        "provider-path-endpoint-v1",
                        null,
                        "PROVIDER_PATH_ENDPOINT",
                        "PROVIDER_COORDINATE",
                        null),
                true);
    }
}

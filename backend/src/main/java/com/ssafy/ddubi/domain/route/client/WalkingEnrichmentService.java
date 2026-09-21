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
 * 구별한다. API 또는 좌표 검증이 실패하면 기존 WALK 시간과 실패 근거를 반환한다. 총시간은 구간 합으로 다시 만든다. 캐시는 한 평가 안에서만 유지하고 좌표
 * 반올림/장기 TTL은 실측 전 적용하지 않는다.
 */
@Service
public class WalkingEnrichmentService {
    private final WalkingRouteClient client;
    private final boolean enabled;
    private final int budget;
    private final double maxMeters;
    private final List<WalkingEndpointResolver.MasterPoint> points;
    private final WalkingCoordinateArtifact artifact;
    private final boolean providerEndpoints;

    /** 기존 fixture는 공식 마스터 좌표 검증 정책을 유지한다. 운영은 명시적인 provider 좌표 정책을 주입한다. */
    public WalkingEnrichmentService(
            WalkingRouteClient client,
            ObjectMapper json,
            boolean enabled,
            int budget,
            double maxMeters,
            String path) {
        this(client, json, enabled, budget, maxMeters, path, false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WalkingEnrichmentService(
            WalkingRouteClient client,
            ObjectMapper json,
            @Value("${ddubi.walking.enabled:true}") boolean enabled,
            @Value("${ddubi.walking.max-calls:10}") int budget,
            @Value("${ddubi.walking.endpoint-max-meters:0}") double maxMeters,
            @Value("${ddubi.walking.master-coordinate-path:}") String path,
            @Value("${ddubi.walking.provider-endpoints:true}") boolean providerEndpoints) {
        if (budget < 0 || budget > 32 || maxMeters < 0 || !Double.isFinite(maxMeters))
            throw new IllegalArgumentException("도보 정책 범위 오류");
        this.client = client;
        this.enabled = enabled;
        this.budget = budget;
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

    /** 후보 재검색이 일어나도 한 일정 평가의 도보 예산과 동일 요청 결과는 공유한다. */
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
                    if (accessMissing)
                        inputSteps.add(
                                0,
                                syntheticWalk(
                                        route.segments().stream()
                                                        .mapToInt(RouteSegment::sequence)
                                                        .min()
                                                        .orElse(1)
                                                - 1));
                    if (egressMissing)
                        inputSteps.add(
                                syntheticWalk(
                                        route.segments().stream()
                                                        .mapToInt(RouteSegment::sequence)
                                                        .max()
                                                        .orElse(0)
                                                + 1));
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
                            if (found == null && session.calls < budget) {
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
                                if (validUntil == null || found.validUntil().isBefore(validUntil))
                                    validUntil = found.validUntil();
                                for (var point : coordinates)
                                    if (point.validUntil() != null
                                            && point.validUntil().isBefore(validUntil))
                                        validUntil = point.validUntil();
                            } else
                                fallback =
                                        found == null
                                                ? "WALK_CALL_BUDGET_EXHAUSTED"
                                                : found.reason() == null
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
                                step.geometry(),
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

    private static RouteSegment syntheticWalk(int sequence) {
        return new RouteSegment(
                sequence,
                TransportMode.WALK,
                null,
                "EVALUATION_ENDPOINT_WALK",
                null,
                null,
                null,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                null);
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

package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.model.Coordinate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 카카오 BUS step을 서울 공식 노선의 인접 정류장 구간으로 보수적으로 변환한다.
 *
 * <p>카카오 {@code vehicles[]}는 같은 step에서 탈 수 있는 대안 노선 목록일 수 있다.
 * 따라서 서로 다른 대안이 둘 이상이면 임의로 하나를 고르지 않는다. 단일 노선에 대해서도
 * 이름과 순번만으로 EXACT를 발급하지 않고, 카카오 path 양 끝과 공식 승·하차 정류장 사이의
 * 절대 거리를 미터 단위로 확인한다.</p>
 *
 * <p>입력 좌표는 WGS84 경도·위도이며 허용 반경은 생성자에서 주입한다. 이 반경은 소요시간
 * 예측 가중치가 아니라 잘못된 동명 정류장 선택을 차단하는 품질 게이트다. 실제 golden corpus의
 * endpoint residual 분포를 측정해 운영 설정을 조정해야 한다. 좌표가 없거나 깨졌으면 성공으로
 * 간주하지 않고 PARTIAL로 반환한다.</p>
 */
public class BusSegmentMatcher {

    /** Replay 근거가 생기기 전 사용하는 보수적 초기값이며 운영 설정으로 덮어쓸 수 있다. */
    public static final double DEFAULT_MAX_ENDPOINT_DISTANCE_METERS = 150.0;

    private final TransitNameNormalizer normalizer;
    private final Map<String, List<BusRouteMaster>> routesByNormalizedName;
    private final Map<String, List<BusRouteMaster>> routesById;
    private final Map<String, Set<String>> routeAliasIds;
    private final Map<String, Set<String>> stopAliasIds;
    private final double maxEndpointDistanceMeters;

    public BusSegmentMatcher(Collection<BusRouteMaster> routes) {
        this(routes, new TransitNameNormalizer(), Map.of(), Map.of(),
                DEFAULT_MAX_ENDPOINT_DISTANCE_METERS);
    }

    public BusSegmentMatcher(Collection<BusRouteMaster> routes, TransitNameNormalizer normalizer) {
        this(routes, normalizer, Map.of(), Map.of(), DEFAULT_MAX_ENDPOINT_DISTANCE_METERS);
    }

    /**
     * @param routes 공식 노선별 정류장 순서. 같은 routeId의 지선/패턴도 별도 원소로 보존한다.
     * @param routeAliasIds 기본 정규화된 외부 노선명 → 공식 routeId 후보 집합
     * @param stopAliasIds 기본 정규화된 외부 정류장명 → 노선별 공식 stopId 후보 집합
     * @param maxEndpointDistanceMeters path 끝점과 공식 정류장의 최대 허용거리(m), 0보다 커야 한다.
     */
    public BusSegmentMatcher(
            Collection<BusRouteMaster> routes,
            TransitNameNormalizer normalizer,
            Map<String, Set<String>> routeAliasIds,
            Map<String, Set<String>> stopAliasIds,
            double maxEndpointDistanceMeters
    ) {
        this.normalizer = Objects.requireNonNull(normalizer);
        if (!Double.isFinite(maxEndpointDistanceMeters) || maxEndpointDistanceMeters <= 0) {
            throw new IllegalArgumentException("버스 endpoint 허용거리는 0보다 큰 유한값이어야 합니다.");
        }
        this.maxEndpointDistanceMeters = maxEndpointDistanceMeters;
        this.routeAliasIds = normalizedAliasIds(routeAliasIds, true);
        this.stopAliasIds = normalizedAliasIds(stopAliasIds, false);
        List<BusRouteMaster> safeRoutes = routes == null ? List.of() : List.copyOf(routes);
        this.routesByNormalizedName = indexRoutesByName(safeRoutes);
        this.routesById = indexRoutesById(safeRoutes);
    }

    /**
     * BUS step 하나를 매핑한다. EXACT일 때만 공식 승·하차 ID와 인접 segment 목록을 반환한다.
     * PARTIAL/AMBIGUOUS/UNMAPPED에서는 실패 이유와 후보 수를 보존해 매핑 실패율을 숨기지 않는다.
     */
    public BusMappingResult match(BusMappingRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        // 1단계: vehicles.name을 공식 routeId 후보로 바꾼다.
        // 한 step의 vehicles는 대안 노선 목록일 수 있으므로 서로 다른 routeId를 하나로 합치지 않는다.
        RouteLookup lookup = findRouteCandidates(request.providerRouteNames());
        if (lookup.normalizedProviderNames().isEmpty()) {
            return failed(BusMappingStatus.UNMAPPED, "PROVIDER_VEHICLE_NOT_PROVIDED", 0);
        }
        // 대안 중 일부만 공식 마스터에 있으면 현재 마스터 결측을 단일 노선 성공으로 숨기지 않는다.
        if (lookup.unresolvedProviderCount() > 0 && lookup.normalizedProviderNames().size() > 1) {
            return failed(BusMappingStatus.PARTIAL,
                    "PROVIDER_VEHICLE_COVERAGE_INCOMPLETE", lookup.routes().size());
        }
        if (lookup.routes().isEmpty()) {
            return failed(BusMappingStatus.UNMAPPED, "ROUTE_NOT_FOUND", 0);
        }
        // 하나의 카카오 step에 서로 다른 공식 routeId가 있으면 결과는 대안 집합이지 단일 segment다.
        if (lookup.distinctRouteIds() > 1) {
            return failed(BusMappingStatus.AMBIGUOUS,
                    lookup.normalizedProviderNames().size() > 1
                            ? "MULTIPLE_PROVIDER_VEHICLES"
                            : "ROUTE_NAME_AMBIGUOUS",
                    lookup.routes().size());
        }

        // 2단계: 남은 공식 노선 각각에서 승차→하차 정방향 부분경로를 전부 찾는다.
        // 동명 정류장과 같은 routeId의 지선이 있을 수 있어 첫 후보를 즉시 확정하지 않는다.
        List<Candidate> candidates = new ArrayList<>();
        for (BusRouteMaster route : lookup.routes()) {
            candidates.addAll(matchStops(route, request));
        }
        if (candidates.isEmpty()) {
            return partialRouteOnly(lookup.routes(), "STOP_OR_FORWARD_ORDER_NOT_FOUND", 0);
        }

        // 3단계: 통과 정류장 전체와 BUS endpoint 좌표가 모두 확인된 후보만 EXACT 풀에 넣는다.
        // 정렬은 결과와 진단 로그를 재현 가능하게 만들 뿐, 동점 후보를 임의 선택하는 수단이 아니다.
        candidates.sort(Comparator
                .comparing((Candidate candidate) -> candidate.geometryStatus() == GeometryStatus.VERIFIED)
                .reversed()
                .thenComparing(Comparator.comparingDouble(Candidate::passingRatio).reversed())
                .thenComparing(candidate -> candidate.route().routeId())
                .thenComparing(Candidate::identity));

        // 모든 제공 정류장이 공식 부분경로 순서와 맞고 양 끝 좌표까지 검증된 후보만 EXACT 대상이다.
        List<Candidate> exactCandidates = candidates.stream()
                .filter(candidate -> candidate.passingRatio() == 1.0)
                .filter(candidate -> candidate.geometryStatus() == GeometryStatus.VERIFIED)
                .toList();
        // routeId뿐 아니라 승차~하차의 전체 stopId 경로가 같아야 동일 후보로 본다.
        // 같은 노선 번호라도 분기에서 중간 정류장이 다르면 서로 다른 경로이므로 AMBIGUOUS다.
        long exactIdentities = exactCandidates.stream().map(Candidate::identity).distinct().count();
        if (exactIdentities > 1) {
            return failed(BusMappingStatus.AMBIGUOUS,
                    "MULTIPLE_VERIFIED_PATHS", candidates.size());
        }
        if (exactIdentities == 1) {
            return exact(exactCandidates.get(0), candidates.size());
        }

        return partialRouteOnly(lookup.routes(), failureReason(candidates), candidates.size());
    }

    /** 공식 이름에는 provider 별칭을 적용하지 않고 순수 기본 정규화 키만 만든다. */
    private Map<String, List<BusRouteMaster>> indexRoutesByName(Collection<BusRouteMaster> routes) {
        Map<String, List<BusRouteMaster>> index = new HashMap<>();
        for (BusRouteMaster route : validRoutes(routes)) {
            String key = normalizer.normalizeRouteName(route.routeName());
            if (!key.isEmpty()) {
                index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(route);
            }
        }
        index.replaceAll((key, value) -> List.copyOf(value));
        return Map.copyOf(index);
    }

    /** 같은 routeId의 지선/서비스 패턴을 버리지 않도록 값은 목록으로 보존한다. */
    private Map<String, List<BusRouteMaster>> indexRoutesById(Collection<BusRouteMaster> routes) {
        Map<String, List<BusRouteMaster>> index = new HashMap<>();
        for (BusRouteMaster route : validRoutes(routes)) {
            index.computeIfAbsent(route.routeId(), ignored -> new ArrayList<>()).add(route);
        }
        index.replaceAll((key, value) -> List.copyOf(value));
        return Map.copyOf(index);
    }

    /** 최소 두 정류장과 공식 ID·이름을 갖춘 노선만 검색 인덱스에 포함한다. */
    private List<BusRouteMaster> validRoutes(Collection<BusRouteMaster> routes) {
        return routes.stream()
                .filter(Objects::nonNull)
                .filter(route -> route.routeId() != null && !route.routeId().isBlank())
                .filter(route -> route.routeName() != null && !route.routeName().isBlank())
                .filter(route -> route.stops().size() >= 2)
                .toList();
    }

    /**
     * 각 provider vehicle을 독립적으로 해석한다. alias가 있으면 canonical ID를 직접 사용하며,
     * 해당 ID가 마스터에 없을 때 이름 검색으로 fallback하지 않는다.
     */
    private RouteLookup findRouteCandidates(List<String> providerRouteNames) {
        List<String> normalizedNames = providerRouteNames.stream()
                .map(normalizer::normalizeRouteName)
                .filter(name -> !name.isEmpty())
                .distinct()
                .toList();
        Set<BusRouteMaster> routes = new LinkedHashSet<>();
        int unresolved = 0;
        for (String name : normalizedNames) {
            Set<String> canonicalIds = routeAliasIds.get(name);
            List<BusRouteMaster> matches = canonicalIds == null
                    ? routesByNormalizedName.getOrDefault(name, List.of())
                    : canonicalIds.stream()
                    .flatMap(id -> routesById.getOrDefault(id, List.of()).stream())
                    .distinct()
                    .toList();
            if (matches.isEmpty()) {
                unresolved++;
            } else {
                routes.addAll(matches);
            }
        }
        long distinctRouteIds = routes.stream().map(BusRouteMaster::routeId).distinct().count();
        return new RouteLookup(normalizedNames, List.copyOf(routes), unresolved, distinctRouteIds);
    }

    /** 공식 노선 한 패턴에서 가능한 모든 정방향 승차·하차 조합을 만든다. */
    private List<Candidate> matchStops(BusRouteMaster route, BusMappingRequest request) {
        List<BusStopMaster> stops = route.stops();
        List<Candidate> result = new ArrayList<>();
        for (int startIndex = 0; startIndex < stops.size(); startIndex++) {
            if (!matchesStop(request.fromStopName(), stops.get(startIndex))) {
                continue;
            }
            for (int endIndex = startIndex + 1; endIndex < stops.size(); endIndex++) {
                if (!matchesStop(request.toStopName(), stops.get(endIndex))) {
                    continue;
                }
                double passingRatio = passingMatchRatio(
                        request.passingStopNames(), stops.subList(startIndex, endIndex + 1));
                GeometryStatus geometryStatus = verifyGeometry(
                        request.geometry(), stops.get(startIndex), stops.get(endIndex));
                result.add(new Candidate(
                        route, startIndex, endIndex, passingRatio, geometryStatus));
            }
        }
        return result;
    }

    /** 별칭이 있으면 이름이 아니라 공식 stopId를 비교하고, 없을 때만 기본 정규화 이름을 비교한다. */
    private boolean matchesStop(String providerName, BusStopMaster officialStop) {
        String normalizedProvider = normalizer.normalizeStopName(providerName);
        Set<String> aliasIds = stopAliasIds.get(normalizedProvider);
        if (aliasIds != null) {
            return aliasIds.contains(officialStop.stopId());
        }
        return normalizer.normalizeStopName(officialStop.stopName()).equals(normalizedProvider);
    }

    /** 제공 정류장들이 공식 구간에서 같은 순서의 부분수열인지 비율로 기록한다. */
    private double passingMatchRatio(List<String> providerNames, List<BusStopMaster> officialStops) {
        List<String> usableProviderNames = providerNames.stream()
                .filter(name -> name != null && !normalizer.normalizeStopName(name).isEmpty())
                .toList();
        if (usableProviderNames.isEmpty()) {
            return 0.0;
        }
        int matched = 0;
        int officialIndex = 0;
        for (String providerName : usableProviderNames) {
            while (officialIndex < officialStops.size()
                    && !matchesStop(providerName, officialStops.get(officialIndex))) {
                officialIndex++;
            }
            if (officialIndex < officialStops.size()) {
                matched++;
                officialIndex++;
            }
        }
        return (double) matched / usableProviderNames.size();
    }

    /**
     * path 첫·끝과 공식 승·하차 정류장의 절대 거리와 방향 배정을 함께 검증한다.
     *
     * <p>절대거리만 보면 서로 300m 이내인 두 정류장의 역방향 path도 양쪽 150m 반경을
     * 동시에 통과할 수 있다. 따라서 첫 점은 하차점보다 승차점에, 마지막 점은 승차점보다
     * 하차점에 명확히 더 가까워야 한다. 중점에 겹치거나 길이가 0인 path처럼 방향을
     * 증명할 수 없는 입력도 성공으로 추정하지 않는다.</p>
     */
    private GeometryStatus verifyGeometry(
            List<Coordinate> geometry,
            BusStopMaster start,
            BusStopMaster end
    ) {
        if (geometry.size() < 2
                || start.longitude() == null || start.latitude() == null
                || end.longitude() == null || end.latitude() == null) {
            return GeometryStatus.UNAVAILABLE;
        }
        Coordinate first = geometry.get(0);
        Coordinate last = geometry.get(geometry.size() - 1);
        if (!validCoordinate(first.longitude(), first.latitude())
                || !validCoordinate(last.longitude(), last.latitude())
                || !validCoordinate(start.longitude(), start.latitude())
                || !validCoordinate(end.longitude(), end.latitude())) {
            return GeometryStatus.INVALID;
        }
        double startDistance = haversineMeters(
                first.longitude(), first.latitude(), start.longitude(), start.latitude());
        double endDistance = haversineMeters(
                last.longitude(), last.latitude(), end.longitude(), end.latitude());
        if (startDistance > maxEndpointDistanceMeters || endDistance > maxEndpointDistanceMeters) {
            return GeometryStatus.ENDPOINT_TOO_FAR;
        }
        double firstToEndDistance = haversineMeters(
                first.longitude(), first.latitude(), end.longitude(), end.latitude());
        double lastToStartDistance = haversineMeters(
                last.longitude(), last.latitude(), start.longitude(), start.latitude());
        // 근거 없이 별도 GPS 여유값을 만들지 않는다. 동률까지 포함해 방향 우위가 없으면 실패한다.
        if (startDistance >= firstToEndDistance || endDistance >= lastToStartDistance) {
            return GeometryStatus.DIRECTION_NOT_CONFIRMED;
        }
        return GeometryStatus.VERIFIED;
    }

    private boolean validCoordinate(double longitude, double latitude) {
        return Double.isFinite(longitude) && Double.isFinite(latitude)
                && longitude >= -180 && longitude <= 180
                && latitude >= -90 && latitude <= 90;
    }

    /** WGS84 두 점 사이 대권거리를 미터로 계산한다. */
    private double haversineMeters(
            double firstLongitude,
            double firstLatitude,
            double secondLongitude,
            double secondLatitude
    ) {
        double latitudeDelta = Math.toRadians(secondLatitude - firstLatitude);
        double longitudeDelta = Math.toRadians(secondLongitude - firstLongitude);
        double firstLatitudeRadians = Math.toRadians(firstLatitude);
        double secondLatitudeRadians = Math.toRadians(secondLatitude);
        double a = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
                + Math.cos(firstLatitudeRadians) * Math.cos(secondLatitudeRadians)
                * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 후보별 실패 상태를 성공처럼 뭉개지 않고 가장 구체적인 원인으로 분류한다. */
    private String failureReason(List<Candidate> candidates) {
        if (candidates.stream().noneMatch(candidate -> candidate.passingRatio() == 1.0)) {
            return "PASSING_STOPS_NOT_CONFIRMED";
        }
        Set<GeometryStatus> statuses = candidates.stream()
                .filter(candidate -> candidate.passingRatio() == 1.0)
                .map(Candidate::geometryStatus)
                .collect(java.util.stream.Collectors.toSet());
        if (statuses.contains(GeometryStatus.INVALID)) {
            return "GEOMETRY_INVALID";
        }
        if (statuses.contains(GeometryStatus.ENDPOINT_TOO_FAR)) {
            return "GEOMETRY_ENDPOINT_TOO_FAR";
        }
        if (statuses.contains(GeometryStatus.DIRECTION_NOT_CONFIRMED)) {
            return "GEOMETRY_DIRECTION_NOT_CONFIRMED";
        }
        return "GEOMETRY_NOT_AVAILABLE";
    }

    /** 확정한 공식 부분경로를 A→B, B→C 형태의 인접 구간으로 전개한다. */
    private BusMappingResult exact(Candidate candidate, int candidateCount) {
        List<BusStopMaster> stops = candidate.route().stops();
        BusStopMaster start = stops.get(candidate.startIndex());
        BusStopMaster end = stops.get(candidate.endIndex());
        List<MappedBusSegment> segments = new ArrayList<>();
        for (int index = candidate.startIndex(); index < candidate.endIndex(); index++) {
            BusStopMaster from = stops.get(index);
            BusStopMaster to = stops.get(index + 1);
            segments.add(new MappedBusSegment(
                    segments.size() + 1,
                    candidate.route().routeId(),
                    from.stopId(),
                    to.stopId(),
                    from.sequence(),
                    to.sequence()));
        }
        return new BusMappingResult(
                BusMappingStatus.EXACT,
                candidate.route().routeId(),
                candidate.route().routeName(),
                start.stopId(), end.stopId(), start.sequence(), end.sequence(), "SEQUENCE_FORWARD",
                1.0, round(candidate.passingRatio()), candidateCount, "NONE", segments);
    }

    /** PARTIAL에서도 모든 후보가 같은 공식 노선일 때만 그 노선 ID를 진단 정보로 노출한다. */
    private BusMappingResult partialRouteOnly(
            List<BusRouteMaster> routes,
            String reason,
            int candidateCount
    ) {
        BusRouteMaster representative = routes.get(0);
        boolean sameRoute = routes.stream().allMatch(route ->
                Objects.equals(route.routeId(), representative.routeId()));
        return new BusMappingResult(
                BusMappingStatus.PARTIAL,
                sameRoute ? representative.routeId() : null,
                sameRoute ? representative.routeName() : null,
                null, null, null, null, null,
                0.0, 0.0, candidateCount, reason, List.of());
    }

    /** 공식 ID를 전혀 확정할 수 없는 실패 결과에는 진단값 외 식별자를 넣지 않는다. */
    private BusMappingResult failed(BusMappingStatus status, String reason, int candidateCount) {
        return new BusMappingResult(
                status, null, null, null, null, null, null, null,
                0.0, 0.0, candidateCount, reason, List.of());
    }

    /**
     * 같은 표기가 노선별 동명 정류장 여러 개를 가리킬 수 있어 alias→단일 ID로 축소하지 않는다.
     * Matcher는 현재 노선의 stopId와 집합을 비교하므로 다른 노선의 동명 ID는 자연스럽게 제외된다.
     */
    private Map<String, Set<String>> normalizedAliasIds(
            Map<String, Set<String>> aliases,
            boolean route
    ) {
        if (aliases == null || aliases.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> result = new HashMap<>();
        aliases.forEach((alias, canonicalIds) -> {
            String key = route
                    ? normalizer.normalizeRouteName(alias)
                    : normalizer.normalizeStopName(alias);
            if (key.isEmpty() || canonicalIds == null || canonicalIds.isEmpty()
                    || canonicalIds.stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException("버스 별칭 키와 canonical ID는 비어 있을 수 없습니다.");
            }
            result.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).addAll(canonicalIds);
        });
        result.replaceAll((key, value) -> Set.copyOf(value));
        return Map.copyOf(result);
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private enum GeometryStatus {
        VERIFIED,
        UNAVAILABLE,
        INVALID,
        ENDPOINT_TOO_FAR,
        DIRECTION_NOT_CONFIRMED
    }

    /** vehicles 전체를 조회한 결과와 마스터에서 찾지 못한 대안 수를 함께 보존한다. */
    private record RouteLookup(
            List<String> normalizedProviderNames,
            List<BusRouteMaster> routes,
            int unresolvedProviderCount,
            long distinctRouteIds
    ) { }

    /** identity는 실제 출력할 routeId와 전체 인접 stopId 경로를 포함해 지선 후보를 구분한다. */
    /** 한 공식 노선 패턴 안에서 가능한 승차·하차 부분경로와 검증 근거다. */
    private record Candidate(
            BusRouteMaster route,
            int startIndex,
            int endIndex,
            double passingRatio,
            GeometryStatus geometryStatus
    ) {
        private String identity() {
            String path = route.stops().subList(startIndex, endIndex + 1).stream()
                    .map(BusStopMaster::stopId)
                    .reduce((left, right) -> left + '>' + right)
                    .orElse("");
            return route.routeId() + ':' + path;
        }
    }
}

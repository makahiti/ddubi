package com.ssafy.ddubi.domain.schedule.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.entity.SavedRoute;
import com.ssafy.ddubi.domain.route.entity.SavedRouteSegment;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import com.ssafy.ddubi.domain.route.repository.SavedRouteRepository;
import com.ssafy.ddubi.domain.schedule.dto.response.MovementConditionsResponse;
import com.ssafy.ddubi.domain.schedule.entity.LocationSnapshot;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일정의 목적지와 현재 선택 경로를 Redis 최신 자료에 연결한다.
 *
 * <p>날씨는 WGS84 목적지 좌표를 기상청 5km 격자로 변환한다. 혼잡도와 도로 통제는 저장된 canonical 버스 {@code segmentId} 및 그때 사용한
 * 마스터 버전으로 활성 대응표를 조회한다. 지하철은 아직 호선별 공지 인덱스가 없어 제한된 SCAN 결과 중 선택 경로 호선과 일치하는 공지만 노출한다. 각 자료의 장애는 다른
 * 항목까지 실패시키지 않는다.
 */
@Service
public class MovementConditionsService {
    private static final String SCHEMA_VERSION = "movement-conditions-v1";
    private static final int MAX_ROUTE_EDGES = 128;
    private static final int MAX_ROAD_LINKS = 256;
    private static final int MAX_SUBWAY_ALERT_KEYS = 300;

    /** 발표용 가짜 통제 키. {@code RoadClosureDemoSupport} 가 일정마다 하나씩 쓴다. */
    static final String DEMO_ROAD_CONTROL_PREFIX = "demo:topis:road-control:schedule:";

    private static final Pattern DIGITS = Pattern.compile("(\\d+)");

    private final ScheduleRepository schedules;
    private final SavedRouteRepository routes;
    private final MovementConditionsRedisReader redis;
    private final ObjectMapper json;
    private final boolean provisionalRoadMappingEnabled;
    private final Duration congestionMaxAge;
    private final boolean demoRoadClosureEnabled;

    public MovementConditionsService(
            ScheduleRepository schedules,
            SavedRouteRepository routes,
            MovementConditionsRedisReader redis,
            ObjectMapper json,
            boolean provisionalRoadMappingEnabled,
            int congestionMaxAgeHours) {
        this(
                schedules,
                routes,
                redis,
                json,
                provisionalRoadMappingEnabled,
                congestionMaxAgeHours,
                false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MovementConditionsService(
            ScheduleRepository schedules,
            SavedRouteRepository routes,
            MovementConditionsRedisReader redis,
            ObjectMapper json,
            @Value("${ddubi.road.provisional-mapping-enabled:false}")
                    boolean provisionalRoadMappingEnabled,
            @Value("${ddubi.movement-conditions.congestion-max-age-hours:12}")
                    int congestionMaxAgeHours,
            @Value("${ddubi.demo.road-closure.enabled:false}") boolean demoRoadClosureEnabled) {
        this.demoRoadClosureEnabled = demoRoadClosureEnabled;
        this.schedules = schedules;
        this.routes = routes;
        this.redis = redis;
        this.json = json;
        this.provisionalRoadMappingEnabled = provisionalRoadMappingEnabled;
        if (congestionMaxAgeHours < 1 || congestionMaxAgeHours > 48)
            throw new IllegalArgumentException("congestion max age out of range");
        this.congestionMaxAge = Duration.ofHours(congestionMaxAgeHours);
    }

    /**
     * 사용자가 소유한 일정 하나의 이동 변수를 조회한다. Redis 값은 저장 TTL과 별개로 payload의 {@code validUntil}을 다시 확인하며,
     * 오류·결측·만료를 정상값으로 바꾸지 않는다.
     */
    @Transactional(readOnly = true)
    public MovementConditionsResponse get(Long userId, Long scheduleId) {
        return get(userId, scheduleId, Instant.now());
    }

    MovementConditionsResponse get(Long userId, Long scheduleId, Instant now) {
        Schedule schedule =
                schedules
                        .findById(scheduleId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
        if (!schedule.isOwnedBy(userId)) throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);

        RouteContext route =
                routes.findSelectedWithSegments(scheduleId)
                        .map(MovementConditionsService::routeContext)
                        .orElseGet(RouteContext::empty);

        // 외부 저장소 한 종류가 깨져도 나머지 세 종류는 화면에서 확인할 수 있어야 한다.
        var weather =
                isolated(
                        () -> weather(schedule.getDestination(), now),
                        () -> unavailableWeather("WEATHER_READ_FAILED"));
        var congestion =
                isolated(
                        () -> congestion(route.busEdges(), now),
                        () -> unavailableCongestion("CONGESTION_READ_FAILED"));
        var road =
                isolated(
                        () ->
                                demoRoad(schedule, route.busEdges(), now)
                                        .orElseGet(() -> roadBlock(route.busEdges(), now)),
                        () -> unavailableRoad("ROAD_CONTROL_READ_FAILED"));
        var subway =
                isolated(
                        () -> subwayDelay(route.subwayLines(), now),
                        () -> unavailableSubway("SUBWAY_ALERT_READ_FAILED", route.subwayLines()));

        Instant observedAt =
                java.util.stream.Stream.of(
                                weather.observedAt(),
                                congestion.observedAt(),
                                road.observedAt(),
                                subway.observedAt())
                        .filter(Objects::nonNull)
                        .max(Comparator.naturalOrder())
                        .orElse(null);
        return new MovementConditionsResponse(
                SCHEMA_VERSION, scheduleId, now, observedAt, weather, congestion, road, subway);
    }

    private MovementConditionsResponse.Weather weather(LocationSnapshot destination, Instant now) {
        Grid grid = kmaGrid(destination.getLat().doubleValue(), destination.getLng().doubleValue());
        JsonNode node = parse(redis.value("weather:latest:" + grid.nx() + ":" + grid.ny()));
        if (node == null) return unavailableWeather("WEATHER_NOT_FOUND", grid);
        Instant validUntil = instant(node.path("validUntil").asText(null));
        if (validUntil == null || !validUntil.isAfter(now))
            return unavailableWeather("WEATHER_STALE", grid);

        JsonNode features = node.path("features");
        return new MovementConditionsResponse.Weather(
                true,
                null,
                grid.nx(),
                grid.ny(),
                strings(node.path("districts")),
                number(features, "temperatureC"),
                number(features, "humidityPercent"),
                number(features, "precipitationMm"),
                integer(features, "precipitationTypeCode"),
                number(features, "windSpeedMps"),
                firstInstant(node, "observedAt", "occurredAt"),
                validUntil,
                text(node, "source", "kma.ultra-srt-ncst"));
    }

    private MovementConditionsResponse.Congestion congestion(List<EdgeRef> edges, Instant now) {
        if (edges.isEmpty()) return unavailableCongestion("NO_BUS_SEGMENT");
        Map<String, String> activeKeys = new HashMap<>();
        // 최신값이 있으면 그것만 쓴다. 하나도 없을 때만 유효시각이 지난 관측으로 내려간다.
        Map<String, JsonNode> current = new LinkedHashMap<>();
        Map<String, JsonNode> aged = new LinkedHashMap<>();
        for (EdgeRef edge : edges) {
            String mappingKey =
                    activeKeys.computeIfAbsent(
                            edge.masterVersion(),
                            version -> redis.value("ml:mapping:bus-city:active:" + version));
            if (!validMappingPointer(mappingKey, "ml:mapping:bus-city:")) continue;
            JsonNode mapping = parse(redis.hashValue(mappingKey, edge.segmentId()));
            if (mapping == null
                    || !"RESOLVED".equals(mapping.path("mappingStatus").asText())
                    || !edge.masterVersion().equals(mapping.path("masterVersion").asText()))
                continue;
            String areaId = mapping.path("primaryProviderAreaId").asText("");
            if (!safeKeyPart(areaId)) continue;
            JsonNode area = parse(redis.value("city:latest:area:" + areaId));
            if (area == null || !"VALID".equals(area.path("processingStatus").asText())) continue;
            if (fresh(area, now)) current.putIfAbsent(areaId, area);
            else if (withinMaxAge(area, now)) aged.putIfAbsent(areaId, area);
        }
        boolean stale = current.isEmpty();
        Map<String, JsonNode> areas = stale ? aged : current;
        if (areas.isEmpty()) return unavailableCongestion("CONGESTION_UNAVAILABLE_OR_STALE");

        Map.Entry<String, JsonNode> worst =
                areas.entrySet().stream()
                        .max(Comparator.comparingInt(entry -> congestionScore(entry.getValue())))
                        .orElseThrow();
        JsonNode node = worst.getValue();
        return new MovementConditionsResponse.Congestion(
                true,
                null,
                worst.getKey(),
                text(node, "areaName", null),
                text(node, "congestionLevel", null),
                text(node, "congestionMessage", null),
                integer(node, "congestionScore") != null
                        ? integer(node, "congestionScore")
                        : congestionScore(node),
                firstInstant(node, "observedAt", "occurredAt"),
                instant(node.path("validUntil").asText(null)),
                text(node, "source", "seoul.citydata"),
                stale);
    }

    /**
     * 유효시각이 지났어도 쓸 수 있는 관측인지 본다. 도시데이터는 6시간마다 모으는데 관측 유효시간은 15분이라, 이 문을 두지 않으면 하루 대부분 혼잡도가 비어
     * 있다(2026-09-23 확인). 관측 시각을 함께 내보내 앱이 언제 기준인지 밝히므로 옛 값을 지금 값처럼 보여 주지는 않는다. 미래 시각이거나 상한보다 오래된 값은
     * 쓰지 않는다.
     */
    private boolean withinMaxAge(JsonNode node, Instant now) {
        Instant observed = firstInstant(node, "observedAt", "occurredAt", "collectedAt");
        return observed != null
                && !observed.isAfter(now)
                && observed.isAfter(now.minus(congestionMaxAge));
    }

    /**
     * 발표용 가짜 통제가 이 일정에 걸려 있으면 도로 칸에도 보여준다. 추천은 이 통제로 경로를 바꾸는데 도로 칸만 "확인된 통제 없음" 이면 화면이 서로 어긋난다.
     *
     * <p>실제 사고로 오해하지 않게 문장과 출처에 시연임을 밝힌다. 판단 조건은 추천 쪽({@code RoadClosureDemoSupport#overlay})과 같다 —
     * 같은 일정·같은 입력 버전·아직 유효·지금 경로의 버스 구간일 때만. 기능이 꺼져 있으면 키를 읽지도 않는다.
     */
    private java.util.Optional<MovementConditionsResponse.RoadBlock> demoRoad(
            Schedule schedule, List<EdgeRef> edges, Instant now) {
        if (!demoRoadClosureEnabled || edges.isEmpty()) return java.util.Optional.empty();
        JsonNode event = parse(redis.value(DEMO_ROAD_CONTROL_PREFIX + schedule.getId()));
        if (event == null
                || !event.path("demo").asBoolean(false)
                || !"DEMO_TOPIS".equals(event.path("source").asText())
                || event.path("scheduleId").asLong(-1) != schedule.getId()
                || event.path("inputRevision").asLong(-1) != schedule.getInputRevision())
            return java.util.Optional.empty();
        Instant startsAt = instant(event.path("computedAt").asText(null));
        Instant until = instant(event.path("validUntil").asText(null));
        String segmentId = event.path("segmentId").asText("");
        if (startsAt == null
                || until == null
                || !until.isAfter(now)
                || edges.stream().noneMatch(edge -> edge.segmentId().equals(segmentId)))
            return java.util.Optional.empty();
        String message = "[시연] 선택한 버스 구간에 가짜 도로 통제를 적용했어요.";
        return java.util.Optional.of(
                new MovementConditionsResponse.RoadBlock(
                        true,
                        null,
                        1,
                        message,
                        List.of(
                                new MovementConditionsResponse.RoadIncident(
                                        event.path("eventId").asText("DEMO"),
                                        "DEMO",
                                        message,
                                        startsAt,
                                        until)),
                        startsAt,
                        "DEMO_TOPIS",
                        "DEMO"));
    }

    private MovementConditionsResponse.RoadBlock roadBlock(List<EdgeRef> edges, Instant now) {
        if (edges.isEmpty()) return unavailableRoad("NO_BUS_SEGMENT");
        Map<String, String> activeKeys = new HashMap<>();
        Map<String, String> provisionalKeys = new HashMap<>();
        Set<String> linkKeys = new LinkedHashSet<>();
        Set<String> mappedLinkIds = new LinkedHashSet<>();
        Set<String> linkMasterVersions = new LinkedHashSet<>();
        boolean provisionalUsed = false;
        for (EdgeRef edge : edges) {
            String mappingKey =
                    activeKeys.computeIfAbsent(
                            edge.masterVersion(),
                            version -> redis.value("ml:mapping:bus-road:active:" + version));
            JsonNode mapping =
                    validMappingPointer(mappingKey, "ml:mapping:bus-road:")
                            ? parse(redis.hashValue(mappingKey, edge.segmentId()))
                            : null;
            boolean provisional = false;
            if (!validRoadMapping(mapping, edge, now, "RESOLVED")
                    && provisionalRoadMappingEnabled) {
                String provisionalKey =
                        provisionalKeys.computeIfAbsent(
                                edge.masterVersion(),
                                version ->
                                        redis.value(
                                                "ml:mapping:bus-road-provisional:active:"
                                                        + version));
                mapping =
                        validMappingPointer(provisionalKey, "ml:mapping:bus-road-provisional:")
                                ? parse(redis.hashValue(provisionalKey, edge.segmentId()))
                                : null;
                provisional = validRoadMapping(mapping, edge, now, "PROVISIONAL");
            }
            if (!validRoadMapping(mapping, edge, now, provisional ? "PROVISIONAL" : "RESOLVED"))
                continue;
            provisionalUsed |= provisional;
            String namespace = mapping.path("topisLinkNamespace").asText("");
            if (!safeKeyPart(namespace)) continue;
            String linkMasterVersion = mapping.path("trafficLinkMasterVersion").asText("");
            if (safeKeyPart(linkMasterVersion)) linkMasterVersions.add(linkMasterVersion);
            for (JsonNode id : mapping.path("topisLinkIds")) {
                if (linkKeys.size() >= MAX_ROAD_LINKS) break;
                if (safeKeyPart(id.asText())) {
                    linkKeys.add("road:incidents:" + namespace + ":" + id.asText());
                    mappedLinkIds.add(id.asText());
                }
            }
        }
        if (linkKeys.isEmpty()) return unavailableRoad("ROAD_PATH_UNMAPPED");

        Map<String, MovementConditionsResponse.RoadIncident> incidents = new LinkedHashMap<>();
        Instant observedAt = null;
        for (String key : linkKeys) {
            for (Object raw : redis.hashEntries(key).values()) {
                JsonNode event = parse(String.valueOf(raw));
                if (!activeRoadEvent(event, now)) continue;
                String incidentId = event.path("providerIncidentId").asText("");
                if (!safeKeyPart(incidentId)) continue;
                JsonNode detail = parse(redis.value("traffic:latest:incident:" + incidentId));
                if (detail != null && !fresh(detail, now)) detail = null;
                String message = detail == null ? null : firstText(detail, 300);
                incidents.putIfAbsent(
                        incidentId,
                        new MovementConditionsResponse.RoadIncident(
                                incidentId,
                                text(event, "incidentTypeCode", null),
                                message,
                                instant(event.path("startsAt").asText(null)),
                                firstInstant(event, "expectedEndsAt")));
                observedAt = later(observedAt, firstInstant(event, "collectedAt"));
            }
        }
        JsonNode snapshot = parse(redis.value("traffic:latest:incident-snapshot:all-current"));
        String expectedMappingStatus = provisionalUsed ? "PROVISIONAL" : "RESOLVED";
        if (snapshot != null
                && snapshot.path("completeSnapshot").asBoolean(false)
                && snapshot.path("linkMasterAvailable").asBoolean(false)
                && "SEOUL_ACCINFO_LINK".equals(snapshot.path("linkNamespace").asText())
                && expectedMappingStatus.equals(
                        snapshot.path("linkMappingStatus").asText("RESOLVED"))
                && linkMasterVersions.contains(snapshot.path("linkMasterVersion").asText())
                && fresh(snapshot, now)) {
            for (JsonNode event : snapshot.path("incidents")) {
                if (!mappedLinkIds.contains(event.path("matchedLinkId").asText())) continue;
                String incidentId = event.path("providerIncidentId").asText("");
                if (!safeKeyPart(incidentId)) continue;
                JsonNode detail = parse(redis.value("traffic:latest:incident:" + incidentId));
                if (detail != null && !fresh(detail, now)) detail = null;
                String message = detail == null ? null : firstText(detail, 300);
                incidents.putIfAbsent(
                        incidentId,
                        new MovementConditionsResponse.RoadIncident(
                                incidentId,
                                event.at("/textFeatures/aiIncidentKind").asText(null),
                                message,
                                instant(event.path("startedAt").asText(null)),
                                instant(event.path("expectedClearAt").asText(null))));
            }
            observedAt = later(observedAt, firstInstant(snapshot, "collectedAt"));
        }
        if (incidents.isEmpty()) return unavailableRoad("NO_ACTIVE_ROAD_CONTROL");
        List<MovementConditionsResponse.RoadIncident> values = List.copyOf(incidents.values());
        String summary =
                values.stream()
                        .map(MovementConditionsResponse.RoadIncident::message)
                        .filter(value -> value != null && !value.isBlank())
                        .findFirst()
                        .orElse("선택 경로에 도로 돌발정보가 확인됐어요.");
        return new MovementConditionsResponse.RoadBlock(
                true,
                null,
                values.size(),
                summary,
                values,
                observedAt,
                "seoul.topis",
                provisionalUsed ? "PROVISIONAL" : "RESOLVED");
    }

    private MovementConditionsResponse.SubwayDelay subwayDelay(
            List<String> routeLines, Instant now) {
        if (routeLines.isEmpty()) return unavailableSubway("NO_SUBWAY_SEGMENT", routeLines);
        List<MovementConditionsResponse.SubwayAlert> alerts = new ArrayList<>();
        Instant observedAt = null;
        for (MovementConditionsRedisReader.KeyValue item :
                redis.scanValues("transit:latest:subway-alert:", MAX_SUBWAY_ALERT_KEYS)) {
            JsonNode node = parse(item.value());
            if (node == null || !fresh(node, now)) continue;
            String line = node.at("/observation/alertLineName").asText("");
            if (!matchesAnyLine(line, routeLines)) continue;
            String id = item.key().substring(item.key().lastIndexOf(':') + 1);
            alerts.add(
                    new MovementConditionsResponse.SubwayAlert(
                            id,
                            line,
                            nullableText(node.at("/observation/alertTitle")),
                            nullableText(node.at("/observation/alertContent")),
                            instant(node.path("validUntil").asText(null))));
            observedAt = later(observedAt, firstInstant(node, "occurredAt", "collectedAt"));
        }
        if (alerts.isEmpty()) return unavailableSubway("NO_MATCHING_ACTIVE_ALERT", routeLines);
        String summary =
                alerts.stream()
                        .map(MovementConditionsResponse.SubwayAlert::content)
                        .filter(value -> value != null && !value.isBlank())
                        .findFirst()
                        .orElseGet(
                                () ->
                                        alerts.stream()
                                                .map(MovementConditionsResponse.SubwayAlert::title)
                                                .filter(value -> value != null && !value.isBlank())
                                                .findFirst()
                                                .orElse("선택 경로 지하철에 운행 공지가 있어요."));
        return new MovementConditionsResponse.SubwayDelay(
                true,
                null,
                alerts.size(),
                summary,
                routeLines,
                List.copyOf(alerts),
                observedAt,
                "seoul.metro.alert");
    }

    private static RouteContext routeContext(SavedRoute route) {
        List<EdgeRef> edges = new ArrayList<>();
        Set<String> lines = new LinkedHashSet<>();
        for (SavedRouteSegment segment : route.getSegments()) {
            if (segment.getMode() == TransportMode.BUS) {
                for (var edge : segment.getEdges()) {
                    if (edges.size() >= MAX_ROUTE_EDGES) break;
                    if (safeKeyPart(edge.getSegmentId()) && safeKeyPart(edge.getMappingVersion()))
                        edges.add(new EdgeRef(edge.getSegmentId(), edge.getMappingVersion()));
                }
            } else if (segment.getMode() == TransportMode.SUBWAY
                    && segment.getProviderRouteKey() != null
                    && !segment.getProviderRouteKey().isBlank()) {
                lines.add(segment.getProviderRouteKey().strip());
            }
        }
        return new RouteContext(List.copyOf(edges), List.copyOf(lines));
    }

    private static boolean validRoadMapping(
            JsonNode mapping, EdgeRef edge, Instant now, String expectedStatus) {
        if (mapping == null
                || !expectedStatus.equals(mapping.path("mappingStatus").asText())
                || !edge.masterVersion().equals(mapping.path("masterVersion").asText()))
            return false;
        Instant from = instant(mapping.path("roadPathValidFrom").asText(null));
        Instant until = instant(mapping.path("roadPathValidUntil").asText(null));
        return from != null && until != null && !from.isAfter(now) && until.isAfter(now);
    }

    private static boolean activeRoadEvent(JsonNode event, Instant now) {
        if (event == null || event.path("conflicted").asBoolean(false)) return false;
        Instant collected = instant(event.path("collectedAt").asText(null));
        long expires = event.path("expiresEpoch").asLong(0);
        return collected != null && !collected.isAfter(now) && expires > now.getEpochSecond();
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return json.readTree(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean fresh(JsonNode node, Instant now) {
        Instant until = instant(node.path("validUntil").asText(null));
        Instant occurred = firstInstant(node, "observedAt", "occurredAt", "collectedAt");
        return until != null && until.isAfter(now) && (occurred == null || !occurred.isAfter(now));
    }

    private static int congestionScore(JsonNode node) {
        Integer score = integer(node, "congestionScore");
        if (score != null) return score;
        return switch (node.path("congestionLevel").asText("UNKNOWN")) {
            case "RELAXED" -> 1;
            case "NORMAL" -> 2;
            case "BUSY" -> 3;
            case "CROWDED" -> 4;
            default -> 0;
        };
    }

    private static boolean matchesAnyLine(String alertLine, List<String> routeLines) {
        Set<String> alertTokens = lineTokens(alertLine);
        return !alertTokens.isEmpty()
                && routeLines.stream()
                        .flatMap(value -> lineTokens(value).stream())
                        .anyMatch(alertTokens::contains);
    }

    private static Set<String> lineTokens(String value) {
        if (value == null || value.isBlank()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = DIGITS.matcher(value);
        while (matcher.find()) result.add(matcher.group(1));
        if (!result.isEmpty()) return Set.copyOf(result);
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^가-힣a-z]", "");
        return normalized.isBlank() ? Set.of() : Set.of(normalized);
    }

    /** 기상청 단기예보 5km Lambert 격자 변환. Spark와 같은 상수와 반올림 규칙을 사용한다. */
    static Grid kmaGrid(double latitude, double longitude) {
        if (!Double.isFinite(latitude)
                || !Double.isFinite(longitude)
                || latitude < -90
                || latitude > 90
                || longitude < -180
                || longitude > 180) throw new IllegalArgumentException("invalid coordinates");
        double degree = Math.PI / 180.0;
        double re = 6371.00877 / 5.0;
        double slat1 = 30.0 * degree;
        double slat2 = 60.0 * degree;
        double olon = 126.0 * degree;
        double olat = 38.0 * degree;
        double sn =
                Math.log(Math.cos(slat1) / Math.cos(slat2))
                        / Math.log(
                                Math.tan(Math.PI * 0.25 + slat2 * 0.5)
                                        / Math.tan(Math.PI * 0.25 + slat1 * 0.5));
        double sf = Math.pow(Math.tan(Math.PI * 0.25 + slat1 * 0.5), sn) * Math.cos(slat1) / sn;
        double ro = re * sf / Math.pow(Math.tan(Math.PI * 0.25 + olat * 0.5), sn);
        double ra = re * sf / Math.pow(Math.tan(Math.PI * 0.25 + latitude * degree * 0.5), sn);
        double theta = longitude * degree - olon;
        if (theta > Math.PI) theta -= 2.0 * Math.PI;
        if (theta < -Math.PI) theta += 2.0 * Math.PI;
        theta *= sn;
        return new Grid(
                (int) Math.floor(ra * Math.sin(theta) + 43.0 + 0.5),
                (int) Math.floor(ro - ra * Math.cos(theta) + 136.0 + 0.5));
    }

    private static MovementConditionsResponse.Weather unavailableWeather(String reason) {
        return unavailableWeather(reason, null);
    }

    private static MovementConditionsResponse.Weather unavailableWeather(String reason, Grid grid) {
        return new MovementConditionsResponse.Weather(
                false,
                reason,
                grid == null ? null : grid.nx(),
                grid == null ? null : grid.ny(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static MovementConditionsResponse.Congestion unavailableCongestion(String reason) {
        return new MovementConditionsResponse.Congestion(
                false, reason, null, null, null, null, null, null, null, null, false);
    }

    private static MovementConditionsResponse.RoadBlock unavailableRoad(String reason) {
        return new MovementConditionsResponse.RoadBlock(
                false, reason, 0, null, List.of(), null, null, null);
    }

    private static MovementConditionsResponse.SubwayDelay unavailableSubway(
            String reason, List<String> lines) {
        return new MovementConditionsResponse.SubwayDelay(
                false, reason, 0, null, lines, List.of(), null, null);
    }

    private static <T> T isolated(Supplier<T> action, Supplier<T> fallback) {
        try {
            return action.get();
        } catch (RuntimeException ignored) {
            return fallback.get();
        }
    }

    private static String firstText(JsonNode node, int max) {
        for (String path :
                List.of(
                        "/observation/incidentMessage",
                        "/incidentMessage",
                        "/observation/message",
                        "/message")) {
            JsonNode value = node.at(path);
            if (!value.isTextual() || value.asText().isBlank()) continue;
            String clean =
                    value.asText().replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").strip();
            return clean.length() <= max ? clean : clean.substring(0, max);
        }
        return null;
    }

    private static Instant firstInstant(JsonNode node, String... fields) {
        for (String field : fields) {
            Instant value = instant(node.path(field).asText(null));
            if (value != null) return value;
        }
        return null;
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception ignored) {
            try {
                return Instant.parse(value);
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private static Instant later(Instant left, Instant right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isAfter(right) ? left : right;
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.doubleValue() : null;
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() ? value.intValue() : null;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    private static String nullableText(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        node.forEach(
                value -> {
                    if (value.isTextual() && !value.asText().isBlank()) result.add(value.asText());
                });
        return List.copyOf(result);
    }

    private static boolean safeKeyPart(String value) {
        return value != null && value.matches("[0-9A-Za-z._:-]{1,180}");
    }

    private static boolean validMappingPointer(String value, String prefix) {
        return value != null
                && value.startsWith(prefix)
                && !value.contains(":active:")
                && !value.endsWith(":meta")
                && value.matches("[0-9A-Za-z._:-]{1,300}");
    }

    record Grid(int nx, int ny) {}

    private record EdgeRef(String segmentId, String masterVersion) {}

    private record RouteContext(List<EdgeRef> busEdges, List<String> subwayLines) {
        static RouteContext empty() {
            return new RouteContext(List.of(), List.of());
        }
    }
}

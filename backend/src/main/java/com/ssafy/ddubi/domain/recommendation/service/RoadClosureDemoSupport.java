package com.ssafy.ddubi.domain.recommendation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.ddubi.domain.recommendation.evaluation.ConditionalDeparturePolicy;
import com.ssafy.ddubi.domain.recommendation.evaluation.RouteEvidenceAssembler;
import com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.Result;
import com.ssafy.ddubi.domain.route.model.RouteCandidate;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 발표용 도로 통제만 다룬다. 사용자가 화면에서 첫 평가를 본 후 요청하면, 인증된 본인의 서울 일정에서 선택된 버스의 검증된 canonical edge 한 개를 찾아 일정
 * 전용 Redis 키에 보관한다. 실제 TOPIS 링크 매핑을 통과한 운영 사고가 아니므로 공용 segment:status/road:incidents 키에는 절대 쓰지 않는다.
 */
@Component
public class RoadClosureDemoSupport {
    private static final String PREFIX = "demo:topis:road-control:schedule:";
    private static final String TITLE_PREFIX = "[시연]";
    private final boolean enabled;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RoadClosureDemoSupport(
            @Value("${ddubi.demo.road-closure.enabled:false}") boolean enabled,
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            ObjectMapper json) {
        this.enabled = enabled;
        this.jdbc = jdbc;
        this.redis = redis;
        this.json = json;
    }

    /** 서울 좌표는 오입력 방지용 범위이고, 버스 edge 출처는 별도로 서울 공식 ID인지 검사한다. */
    public boolean eligible(ScheduleEvaluationStore.Job job) {
        if (!enabled
                || !seoul(job.origin().longitude(), job.origin().latitude())
                || !seoul(job.destination().longitude(), job.destination().latitude()))
            return false;
        return jdbc
                .queryForList(
                        "SELECT title FROM schedule WHERE id=? AND user_id=?",
                        String.class,
                        job.scheduleId(),
                        job.userId())
                .stream()
                .anyMatch(title -> title.startsWith(TITLE_PREFIX));
    }

    private static boolean seoul(double lon, double lat) {
        return lon >= 126.76 && lon <= 127.19 && lat >= 37.41 && lat <= 37.72;
    }

    /**
     * 최초 평가의 최선 경로가 버스 중심이고 피할 수 있는 서울 버스 edge가 있을 때만 시연 대상으로 삼는다. 느린 버스를 억지로 선택하면 첫 화면부터 다른 경로가
     * 제안되어, 통제 때문에 경로가 바뀌었다는 시연이 거짓이 된다. 최종 대안은 가짜 통제 뒤 실제 평가기가 다시 고른다.
     */
    public static Optional<Result> initialBusChoice(
            List<RouteCandidate> candidates, List<Result> results) {
        Result best = ConditionalDeparturePolicy.best(results);
        if (best == null) return Optional.empty();
        var byKey =
                results.stream().collect(Collectors.toMap(Result::journeyKey, r -> r, (a, b) -> a));
        return candidates.stream()
                .filter(c -> busSeconds(c) > 0 && busSeconds(c) > subwaySeconds(c))
                .filter(c -> hasDistinctAlternative(c, candidates, byKey))
                .map(c -> byKey.get(RouteEvidenceAssembler.key(c)))
                .filter(best::equals)
                .min(Comparator.comparing(Result::estimatedArrivalAt));
    }

    private static long busSeconds(RouteCandidate c) {
        return c.segments().stream()
                .filter(s -> s.mode() == TransportMode.BUS)
                .mapToLong(s -> s.durationSeconds())
                .sum();
    }

    private static long subwaySeconds(RouteCandidate c) {
        return c.segments().stream()
                .filter(s -> s.mode() == TransportMode.SUBWAY)
                .mapToLong(s -> s.durationSeconds())
                .sum();
    }

    private static List<String> seoulBusEdges(RouteCandidate c) {
        return c.segments().stream()
                .filter(s -> s.mode() == TransportMode.BUS)
                .flatMap(s -> RouteEvidenceAssembler.edges(s).stream())
                .map(e -> e.segmentId())
                .filter(
                        id ->
                                id.startsWith("BUS:")
                                        && !id.startsWith("BUS:GBIS.")
                                        && !id.startsWith("BUS:INCHEON_PUBLIC_CSV.")
                                        && !id.startsWith("BUS:BUSAN_GTFS."))
                .distinct()
                .toList();
    }

    /**
     * 대안은 막을 도로(정류장 쌍)를 아예 지나지 않아야 한다. 예전에는 버스 번호가 다른 같은 도로(145번 대신 3030번)도 대안으로 쳐서, 통제 뒤 "새 경로" 가
     * 버스만 바뀐 같은 길로 나왔다(2026-09-26 제보).
     */
    private static boolean hasDistinctAlternative(
            RouteCandidate selected, List<RouteCandidate> all, Map<String, Result> byKey) {
        Set<String> selectedPairs =
                seoulBusEdges(selected).stream()
                        .map(RoadClosureDemoSupport::stopPair)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet());
        if (selectedPairs.isEmpty()) return false;
        return all.stream()
                .filter(other -> other != selected)
                .anyMatch(
                        other ->
                                !RouteEvidenceAssembler.key(other)
                                                .equals(RouteEvidenceAssembler.key(selected))
                                        && ConditionalDeparturePolicy.usable(
                                                byKey.get(RouteEvidenceAssembler.key(other)))
                                        && selectedPairs.stream()
                                                .anyMatch(
                                                        pair -> !stopPairs(other).contains(pair)));
    }

    private static Set<String> allEdges(RouteCandidate c) {
        return c.segments().stream()
                .flatMap(s -> RouteEvidenceAssembler.edges(s).stream())
                .map(e -> e.segmentId())
                .collect(Collectors.toSet());
    }

    /**
     * 서울 버스 인접 구간 번호 "BUS:{노선}:{앞 정류장}:{뒤 정류장}" 에서 "{앞 정류장}:{뒤 정류장}". 같은 두 정류장 사이는 같은 도로라서, 도로 통제는
     * 그 사이를 지나는 모든 버스를 함께 막는다. 모양이 다르면 null.
     */
    public static String stopPair(String segmentId) {
        if (segmentId == null || !segmentId.startsWith("BUS:")) return null;
        String[] parts = segmentId.split(":");
        return parts.length == 4 ? stopNumber(parts[2]) + ":" + stopNumber(parts[3]) : null;
    }

    /**
     * 정류장 번호에서 출처 표시를 뗀다. 경기 버스는 같은 서울 정류장을 "GBIS.122000008" 처럼 앞에 표시를 붙여 적는다. 떼지 않으면 같은 정류장을 지나는
     * 경기 버스(3030번 등)가 다른 도로로 보여 통제를 피해 갔다(2026-09-26 제보).
     */
    private static String stopNumber(String node) {
        int dot = node.lastIndexOf('.');
        return dot >= 0 ? node.substring(dot + 1) : node;
    }

    private static Set<String> stopPairs(RouteCandidate c) {
        return allEdges(c).stream()
                .map(RoadClosureDemoSupport::stopPair)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** 이 후보가 막힌 도로를 지나는가. 정류장 쌍을 모르는 예전 키는 구간 번호 그대로 본다. */
    private static boolean crosses(RouteCandidate c, String segmentId, String pair) {
        return pair == null ? allEdges(c).contains(segmentId) : stopPairs(c).contains(pair);
    }

    /**
     * READY 결과와 동일한 입력 버전인지 확인한 다음에만 가짜 TOPIS 통제를 넣는다. 도로 링크 ID는 실측 링크라고 속이지 않는 DEMO 표식이다. 반환된
     * false는 이미 같은 시연이 실행됐다는 뜻이다.
     */
    public boolean activate(long scheduleId, long userId, Instant now) {
        if (!enabled) throw new BusinessException(ErrorCode.INVALID_STATE, "시연 기능이 꺼져 있습니다.");
        var rows =
                jdbc.queryForList(
                        "SELECT s.title,s.status,s.journey_state,s.origin_lng,s.origin_lat,s.dest_lng,s.dest_lat,"
                                + "s.arrive_by,s.input_revision AS schedule_revision,e.input_revision AS evaluation_revision,"
                                + "e.result_json,e.candidates_json FROM schedule s JOIN schedule_evaluation e"
                                + " ON e.schedule_id=s.id WHERE s.id=? AND s.user_id=?",
                        scheduleId,
                        userId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
        Map<String, Object> row = rows.get(0);
        if (!String.valueOf(row.get("title")).startsWith(TITLE_PREFIX)
                || !"UPCOMING".equals(String.valueOf(row.get("status")))
                || !"NOT_STARTED".equals(String.valueOf(row.get("journey_state")))
                || !seoul(
                        ((Number) row.get("origin_lng")).doubleValue(),
                        ((Number) row.get("origin_lat")).doubleValue())
                || !seoul(
                        ((Number) row.get("dest_lng")).doubleValue(),
                        ((Number) row.get("dest_lat")).doubleValue())
                || !(row.get("schedule_revision") instanceof Number scheduleRevision)
                || !(row.get("evaluation_revision") instanceof Number evaluationRevision)
                || scheduleRevision.longValue() != evaluationRevision.longValue())
            throw new BusinessException(ErrorCode.INVALID_STATE, "서울 시연 일정의 첫 계산이 필요합니다.");
        // 시연 통제는 일정마다 한 번이다. 제안 경로로 바꾸면 입력 버전이 올라가는데, 예전에는 그때 새 경로에 통제를 또
        // 걸어 계속 막혔다(2026-09-26 제보). 이미 걸린 통제가 있으면 버전과 관계없이 그대로 둔다.
        String existing = redis.opsForValue().get(PREFIX + scheduleId);
        if (existing != null) {
            try {
                json.readTree(existing);
                return false;
            } catch (Exception broken) {
                // 깨진 발표 데이터만 아래에서 같은 일정 키에 안전하게 덮어쓴다.
                redis.delete(PREFIX + scheduleId);
            }
        }
        try {
            var outcome =
                    json.readValue(
                            (String) row.get("result_json"),
                            ScheduleEvaluationWorkflow.Outcome.class);
            var cache =
                    json.readValue(
                            (String) row.get("candidates_json"),
                            ScheduleEvaluationStore.Cache.class);
            if (outcome == null
                    || cache == null
                    || !outcome.selectedKey().equals(cache.selectedKey()))
                throw new BusinessException(ErrorCode.INVALID_STATE, "선택 경로를 확인할 수 없습니다.");
            RouteCandidate selected = cache.selectedCandidate();
            if (selected == null || busSeconds(selected) <= subwaySeconds(selected))
                throw new BusinessException(ErrorCode.INVALID_STATE, "버스 중심 경로가 선택되지 않았습니다.");
            List<String> possible = new ArrayList<>(seoulBusEdges(selected));
            possible.removeIf(
                    id ->
                            cache.search().candidates().stream()
                                    .filter(
                                            c ->
                                                    !RouteEvidenceAssembler.key(c)
                                                            .equals(outcome.selectedKey()))
                                    .filter(
                                            c ->
                                                    outcome
                                                            .suggestion()
                                                            .originalCandidates()
                                                            .stream()
                                                            .anyMatch(
                                                                    r ->
                                                                            r.journeyKey()
                                                                                            .equals(
                                                                                                    RouteEvidenceAssembler
                                                                                                            .key(
                                                                                                                    c))
                                                                                    && ConditionalDeparturePolicy
                                                                                            .usable(
                                                                                                    r)))
                                    .noneMatch(c -> !crosses(c, id, stopPair(id))));
            if (possible.isEmpty())
                throw new BusinessException(ErrorCode.INVALID_STATE, "이 버스 구간을 피하는 대안이 없습니다.");
            Instant arriveBy =
                    ScheduleEvaluationStore.databaseTime(row.get("arrive_by"))
                            .atZone(ScheduleEvaluationStore.KST)
                            .toInstant();
            if (!arriveBy.isAfter(now) || arriveBy.isAfter(now.plus(Duration.ofHours(3))))
                throw new BusinessException(ErrorCode.INVALID_STATE, "3시간 이내의 시연 일정만 사용할 수 있습니다.");
            // 경로가 원래 도착 목표보다 늦어도 통제 구간 통과시간과 겹치게 1시간을 더 둔다.
            Instant until = arriveBy.plus(Duration.ofHours(1));
            ObjectNode event = json.createObjectNode();
            event.put("schemaVersion", "demo-topis-road-control-v1");
            event.put("source", "DEMO_TOPIS");
            event.put("demo", true);
            event.put("scheduleId", scheduleId);
            event.put("inputRevision", ((Number) row.get("schedule_revision")).longValue());
            event.put("eventId", "DEMO-TOPIS-" + scheduleId + "-" + row.get("schedule_revision"));
            event.put("roadLinkId", "DEMO-" + possible.get(0));
            event.put("segmentId", possible.get(0));
            // 막는 단위는 도로(정류장 쌍)다. 이 두 정류장 사이를 지나는 버스는 번호와 관계없이 모두 막힌다.
            if (stopPair(possible.get(0)) != null) event.put("stopPair", stopPair(possible.get(0)));
            // 화면·알림·안내 문장이 실제 통제처럼 읽히도록 막힌 구간의 정류장과 버스 번호를 남긴다.
            // 문장 앞의 "[시연]" 은 보여주는 쪽(displayMessage)이 붙인다.
            Place place = place(selected, possible.get(0), cache.search().candidates());
            if (place.routeName() != null) event.put("routeName", place.routeName());
            if (place.fromStop() != null) event.put("fromStop", place.fromStop());
            if (place.toStop() != null) event.put("toStop", place.toStop());
            event.put("message", place.message());
            event.put("status", "blocked");
            event.put("computedAt", now.toString());
            event.put("validUntil", until.toString());
            Boolean inserted =
                    redis.opsForValue()
                            .setIfAbsent(
                                    PREFIX + scheduleId,
                                    event.toString(),
                                    Duration.between(now, until));
            if (inserted == null)
                throw new BusinessException(ErrorCode.INVALID_STATE, "Redis에 시연 데이터를 기록하지 못했습니다.");
            return inserted;
        } catch (BusinessException error) {
            throw error;
        } catch (Exception invalid) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "시연 경로 자료를 읽을 수 없습니다.");
        }
    }

    /**
     * 막힌 구간의 버스 번호와 앞뒤 정류장. 모르면 null 이고, 문장은 아는 만큼만 만든다. [shared] 는 같은 두 정류장 사이를 다른 버스도 지나는가다 — 도로를
     * 막으면 그 버스들도 함께 막히므로 "145번 등 버스" 라고 쓴다.
     */
    public record Place(String routeName, String fromStop, String toStop, boolean shared) {
        public Place(String routeName, String fromStop, String toStop) {
            this(routeName, fromStop, toStop, false);
        }

        /** "[시연]" 을 뺀 본문. 실제 돌발 공지처럼 구간·원인·영향을 한 문장에 담는다. */
        public String message() {
            String bus = routeName == null ? "버스" : routeName + (shared ? "번 등 버스" : "번 버스");
            if (fromStop == null || toStop == null)
                return "버스 경로 구간 도로 통제로 " + bus + " 운행에 차질이 있어요.";
            return fromStop + " → " + toStop + " 구간 도로 통제로 " + bus + " 운행에 차질이 있어요.";
        }
    }

    /**
     * 막을 구간의 이름을 경로 자료에서 찾는다. 버스 구간의 정류장 목록은 보통 인접 구간 수보다 하나 많아 i 번째 구간이 i·i+1 번째 정류장을 잇는다. 수가 안
     * 맞으면(경기 버스 등) 그 구간의 승차·하차 정류장으로 대신한다.
     */
    static Place place(RouteCandidate selected, String segmentId) {
        return place(selected, segmentId, List.of());
    }

    /** [candidates] 에서 같은 두 정류장 사이를 지나는 다른 노선이 있으면 [Place#shared] 를 켠다. */
    static Place place(RouteCandidate selected, String segmentId, List<RouteCandidate> candidates) {
        Place place = placeOnSelected(selected, segmentId);
        String pair = stopPair(segmentId);
        if (pair == null || place.routeName() == null) return place;
        boolean shared =
                candidates.stream()
                        .flatMap(c -> c.segments().stream())
                        .filter(s -> s.mode() == TransportMode.BUS)
                        .flatMap(s -> RouteEvidenceAssembler.edges(s).stream())
                        .anyMatch(
                                e ->
                                        pair.equals(stopPair(e.segmentId()))
                                                && !e.segmentId().equals(segmentId));
        return new Place(place.routeName(), place.fromStop(), place.toStop(), shared);
    }

    private static Place placeOnSelected(RouteCandidate selected, String segmentId) {
        if (selected != null) {
            for (var segment : selected.segments()) {
                if (segment.mode() != TransportMode.BUS) continue;
                var edges = RouteEvidenceAssembler.edges(segment);
                for (int i = 0; i < edges.size(); i++) {
                    if (!edges.get(i).segmentId().equals(segmentId)) continue;
                    String route = blankToNull(edges.get(i).routeName());
                    if (route == null && !segment.vehicles().isEmpty())
                        route = blankToNull(segment.vehicles().get(0).name());
                    var stops = segment.stopNames();
                    if (stops.size() == edges.size() + 1)
                        return new Place(route, stopName(stops.get(i)), stopName(stops.get(i + 1)));
                    return new Place(
                            route, stopName(segment.fromName()), stopName(segment.toName()));
                }
            }
        }
        return new Place(null, null, null);
    }

    /** 정류장 이름 뒤의 보조 설명("숙대입구역.갈월동" 의 ".갈월동")은 뺀다. */
    private static String stopName(String raw) {
        String value = blankToNull(raw);
        if (value == null) return null;
        int dot = value.indexOf('.');
        String head = dot > 0 ? value.substring(0, dot).trim() : value;
        return head.isEmpty() ? value : head;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 사람에게 보여줄 시연 통제 문장. 저장된 본문이 없던 예전 키도 같은 모양으로 보여준다. */
    public static String displayMessage(JsonNode event) {
        String body = event == null ? null : blankToNull(event.path("message").asText(null));
        return TITLE_PREFIX + " " + (body == null ? "지금 버스 경로에 도로 통제가 있어요." : body);
    }

    /** 이 일정에 지금 걸려 있는 시연 통제. 기능이 꺼져 있거나 없으면 비어 있다. */
    public Optional<JsonNode> event(long scheduleId) {
        if (!enabled) return Optional.empty();
        try {
            String raw = redis.opsForValue().get(PREFIX + scheduleId);
            return raw == null ? Optional.empty() : Optional.of(json.readTree(raw));
        } catch (Exception unreadable) {
            return Optional.empty();
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** 최초 재평가가 실패한 경우에만 시연 키를 되돌려 같은 화면에서 안전하게 재시도한다. */
    public void rollback(long scheduleId) {
        redis.delete(PREFIX + scheduleId);
    }

    /**
     * 일정 전용 Redis 값만 평가 스냅샷에 얹는다. 다른 일정·실제 교통 상태는 수정하지 않는다. 통제는 일정마다 한 번이라 [inputRevision] 은 보지 않는다
     * — 제안 경로로 바꿔도 막힌 도로는 그대로 막혀 있어야 한다(2026-09-26).
     */
    public RouteEvidenceAssembler.Snapshot overlay(
            long scheduleId,
            long inputRevision,
            RouteEvidenceAssembler.Snapshot snapshot,
            List<RouteCandidate> candidates,
            Instant now) {
        if (!enabled) return snapshot;
        try {
            String raw = redis.opsForValue().get(PREFIX + scheduleId);
            if (raw == null) return snapshot;
            JsonNode event = json.readTree(raw);
            String id = event.path("segmentId").asText();
            String pair = event.path("stopPair").asText(null);
            if (!event.path("demo").asBoolean()
                    || !"DEMO_TOPIS".equals(event.path("source").asText())
                    || event.path("scheduleId").asLong() != scheduleId
                    || !Instant.parse(event.path("validUntil").asText()).isAfter(now)
                    || candidates.stream().noneMatch(c -> crosses(c, id, pair))) return snapshot;
            var states = new LinkedHashMap<>(snapshot.states());
            // 도로를 막는다: 같은 두 정류장 사이를 지나는 모든 버스 구간에 같은 사건을 얹는다. 사건 번호가 같아
            // 원인은 하나로 묶인다. 정류장 쌍이 없던 예전 키는 저장된 구간 하나만 막는다.
            states.put(id, event);
            if (pair != null)
                for (var candidate : candidates)
                    for (String edge : allEdges(candidate))
                        if (pair.equals(stopPair(edge))) states.put(edge, event);
            var reasons = new ArrayList<>(snapshot.reasons());
            reasons.add("DEMO_TOPIS_ROAD_CONTROL");
            return new RouteEvidenceAssembler.Snapshot(states, reasons, snapshot.roads());
        } catch (Exception unavailable) {
            // 시연 키가 사라지거나 Redis가 불안정하면 실제 사용자 경로를 막지 않는다.
            return snapshot;
        }
    }
}

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

    private static boolean hasDistinctAlternative(
            RouteCandidate selected, List<RouteCandidate> all, Map<String, Result> byKey) {
        Set<String> selectedEdges = Set.copyOf(seoulBusEdges(selected));
        if (selectedEdges.isEmpty()) return false;
        return all.stream()
                .filter(other -> other != selected)
                .anyMatch(
                        other ->
                                !RouteEvidenceAssembler.key(other)
                                                .equals(RouteEvidenceAssembler.key(selected))
                                        && ConditionalDeparturePolicy.usable(
                                                byKey.get(RouteEvidenceAssembler.key(other)))
                                        && selectedEdges.stream()
                                                .anyMatch(id -> !allEdges(other).contains(id)));
    }

    private static Set<String> allEdges(RouteCandidate c) {
        return c.segments().stream()
                .flatMap(s -> RouteEvidenceAssembler.edges(s).stream())
                .map(e -> e.segmentId())
                .collect(Collectors.toSet());
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
        String existing = redis.opsForValue().get(PREFIX + scheduleId);
        if (existing != null) {
            try {
                if (json.readTree(existing).path("inputRevision").asLong(-1)
                        == ((Number) row.get("schedule_revision")).longValue()) return false;
            } catch (Exception ignored) {
                // 오래된/깨진 발표 데이터는 아래에서 같은 일정 키에 안전하게 덮어쓴다.
            }
            redis.delete(PREFIX + scheduleId);
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
                                    .noneMatch(c -> !allEdges(c).contains(id)));
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

    /** 최초 재평가가 실패한 경우에만 시연 키를 되돌려 같은 화면에서 안전하게 재시도한다. */
    public void rollback(long scheduleId) {
        redis.delete(PREFIX + scheduleId);
    }

    /** 일정 전용 Redis 값만 평가 스냅샷에 얹는다. 다른 일정·실제 교통 상태는 수정하지 않는다. */
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
            if (!event.path("demo").asBoolean()
                    || !"DEMO_TOPIS".equals(event.path("source").asText())
                    || event.path("scheduleId").asLong() != scheduleId
                    || event.path("inputRevision").asLong(-1) != inputRevision
                    || !Instant.parse(event.path("validUntil").asText()).isAfter(now)
                    || candidates.stream().noneMatch(c -> allEdges(c).contains(id)))
                return snapshot;
            var states = new LinkedHashMap<>(snapshot.states());
            states.put(id, event);
            var reasons = new ArrayList<>(snapshot.reasons());
            reasons.add("DEMO_TOPIS_ROAD_CONTROL");
            return new RouteEvidenceAssembler.Snapshot(states, reasons, snapshot.roads());
        } catch (Exception unavailable) {
            // 시연 키가 사라지거나 Redis가 불안정하면 실제 사용자 경로를 막지 않는다.
            return snapshot;
        }
    }
}

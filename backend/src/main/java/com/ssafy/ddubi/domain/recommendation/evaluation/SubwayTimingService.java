package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.fasterxml.jackson.databind.*;
import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.route.model.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * EXACT 지하철 edge를 운행일별 시간표와 결합하고 Spark 최신 도착정보로 탑승시각을 보정한다.
 * 위치는 이미 지난 열차 제외에 사용하며 위치 스냅샷 시각을 실제 역 출발시각으로 간주하지 않는다.
 * Redis 읽기 실패/만료 시 TIMETABLE 출처로 복귀한다. ML 호출이나 서비스 DB 쓰기는 없다.
 */
@Service
public class SubwayTimingService {
    private static final String ARRIVAL = "transit:latest:subway-arrival:";
    private static final String POSITION = "transit:latest:subway-position:";
    private static final long FRESH_SECONDS = 120;
    private final SubwayTimetable timetable;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean decompositionVerified;

    public SubwayTimingService(SubwayTimetable timetable, StringRedisTemplate redis, ObjectMapper json,
            @Value("${ddubi.subway-timing.decomposition-verified:false}") boolean decompositionVerified) {
        this.timetable = timetable; this.redis = redis; this.json = json; this.decompositionVerified = decompositionVerified;
    }

    /**
     * 적용 여부는 시간표 정확성과 별개다. provider step의 대기 제외/승강장 접근·환승 도보 포함이
     * 검증되었을 때만 켜는 설정이며, 총시간 잔여가 0인 후보에 한해 실제 합계를 교체한다.
     */
    public boolean decompositionVerified() { return decompositionVerified; }

    /** 평가 한 번에서 조회한 원본을 고정한다. TTL 캐시나 다른 사용자의 평가로 공유하지 않는다. */
    public static final class Session {
        private final Map<String, String> values = new HashMap<>();
        private boolean unavailable;
    }
    public Session newSession() { return new Session(); }

    /**
     * waitSeconds는 승강장 도착부터 탑승 기준시각, rideSeconds는 그 시각부터 하차까지다.
     * 실시간 탑승은 예측 도착을 사용하고 정차시간은 시간표 ride에 포함한다. 모든 값은 잠정 추정이다.
     * 원본 payload 없이 eventId/발생시각/시간표 버전으로 적용 근거와 fallback을 추적한다.
     */
    public record Estimate(Long waitSeconds, Long rideSeconds, Instant boardingAt, Instant arrivalAt,
                           String tripKey, String source, String timetableVersion, String arrivalEventId,
                           Instant observedAt, String positionEventId, List<String> reasons) {
        public Estimate { reasons = List.copyOf(reasons); }
        static Estimate missing(String reason) {
            return new Estimate(null, null, null, null, null, "UNAVAILABLE", null, null, null, null, List.of(reason));
        }
    }
    private record Match(SubwayTimetable.Trip trip, int from, int to) { }
    private record Observation(JsonNode value, Instant at, Instant until, String reason) { }

    /**
     * 1. 버전·경로·정차역을 검증 → 2. 알려진 열차의 Redis 키를 MGET → 3. 탑승 가능 후보의 최소 도착 선택.
     * 역/열차 ID를 찾으려고 Redis KEYS/SCAN을 실행하지 않는다. 2048 시간표 후보/평가 1024키 초과는
     * 일부 열차만 보고 최적이라고 하지 않고 명시적으로 계산을 포기한다.
     */
    public Estimate estimate(RouteSegment segment, Instant asOf, Instant platformAt, Session session) {
        var data = timetable.data();
        if (data == null) return Estimate.missing(timetable.unavailableReason());
        var mapping = segment.mapping();
        if (segment.mode() != TransportMode.SUBWAY || mapping == null || mapping.status() != TransitMappingStatus.EXACT
                || mapping.segments().isEmpty()) return Estimate.missing("SUBWAY_MAPPING_UNVERIFIED");
        if (!data.masterDataVersion().equals(mapping.masterDataVersion())) return Estimate.missing("SUBWAY_MASTER_VERSION_MISMATCH");
        if (data.publishedAt().isAfter(asOf) || platformAt.isBefore(asOf) || platformAt.isBefore(data.validFrom())
                || !platformAt.isBefore(data.validUntil())) return Estimate.missing("SUBWAY_TIMETABLE_OUTSIDE_VALIDITY");
        var matches = new ArrayList<Match>();
        for (var trip : data.trips()) {
            var tripMatches = matches(trip, mapping.segments());
            // 다음 24시간의 실제 운행표만 검토한다. 지난 열차도 최신 지연 도착을 확인할 수 있게 30분 포함한다.
            for (Match match : tripMatches) {
                Instant departure = trip.calls().get(match.from()).departureAt();
                if (!departure.isBefore(platformAt.minusSeconds(1800)) && departure.isBefore(platformAt.plusSeconds(86400))) matches.add(match);
            }
        }
        if (matches.isEmpty()) return Estimate.missing("SUBWAY_NO_MATCHING_TRIP");
        if (matches.size() > 2048) return Estimate.missing("SUBWAY_LOOKUP_BUDGET_EXCEEDED");
        var keys = new LinkedHashSet<String>();
        // 실제 평일 전체 시간표는 한 역에 256회 이상 운행한다. 정적 계산 예산과 외부 조회 예산을 구분한다.
        // 검증된 열차 crosswalk가 없는 운행은 Redis에 null/정적 열차번호 키를 조회하지 않는다.
        for (var m : matches) if (m.trip().realtimeVerified()) { keys.add(arrivalKey(m)); keys.add(positionKey(m.trip())); }
        var missing = keys.stream().filter(k -> !session.values.containsKey(k)).toList();
        if (session.values.size() + missing.size() > 1024) return Estimate.missing("SUBWAY_LOOKUP_BUDGET_EXCEEDED");
        if (!missing.isEmpty() && !session.unavailable) {
            try {
                var values = redis.opsForValue().multiGet(missing);
                if (values == null || values.size() != missing.size()) throw new IllegalStateException();
                for (int i = 0; i < missing.size(); i++) session.values.put(missing.get(i), values.get(i));
            } catch (RuntimeException unavailable) { session.unavailable = true; }
        }

        var estimates = new ArrayList<Estimate>();
        var rejected = new TreeSet<String>();
        for (var m : matches) {
            var trip = m.trip();
            var from = trip.calls().get(m.from());
            var to = trip.calls().get(m.to());
            var reasons = new ArrayList<String>(data.limitations());
            if (session.unavailable) reasons.add("SUBWAY_REDIS_UNAVAILABLE");
            if (!trip.realtimeVerified()) reasons.add("SUBWAY_REALTIME_TRAIN_CROSSWALK_MISSING");
            Observation arrival = trip.realtimeVerified()
                    ? read(session.values.get(arrivalKey(m)), "subway.arrival", trip, from.providerStationId(), asOf)
                    : new Observation(null, null, null, "TRAIN_CROSSWALK_MISSING");
            Observation position = trip.realtimeVerified()
                    ? read(session.values.get(positionKey(trip)), "subway.position", trip, null, asOf)
                    : new Observation(null, null, null, "TRAIN_CROSSWALK_MISSING");
            String positionEvent = null;
            if (position.value() != null) {
                var obs = position.value().path("observation");
                if (!trip.positionDirection().equals(obs.path("direction").asText())
                        || !trip.positionDestination().equals(obs.path("destinationStation").asText())) {
                    rejected.add("SUBWAY_POSITION_IDENTITY_CONFLICT"); continue;
                }
                int at = index(trip, position.value().path("providerIds").path("stationId").asText());
                if (at < 0) { rejected.add("SUBWAY_POSITION_OFF_TRIP"); continue; }
                positionEvent = position.value().path("eventId").asText();
                if (at > m.from()) { rejected.add("SUBWAY_TRAIN_ALREADY_PASSED"); continue; }
            } else reasons.add("POSITION:" + position.reason());

            Instant boarding = from.departureAt();
            long ride = Duration.between(boarding, to.arrivalAt()).getSeconds();
            String source = "SUBWAY_TIMETABLE";
            String arrivalEvent = null;
            Instant observedAt = null;
            if (arrival.value() != null) {
                var obs = arrival.value().path("observation");
                if (!trip.arrivalDirection().equals(obs.path("arrivalDirection").asText())
                        || !trip.arrivalDestination().equals(obs.path("arrivalDestination").asText())
                        || !trip.trainType().equals(obs.path("arrivalTrainType").asText())) {
                    rejected.add("SUBWAY_ARRIVAL_IDENTITY_CONFLICT"); continue;
                }
                var seconds = obs.path("arrivalSeconds");
                // barvlDt=0은 도착/출발 상태가 섞일 수 있으므로 즉시 탑승 가능한 열차로 바꾸지 않는다.
                if (!seconds.isIntegralNumber() || !seconds.canConvertToInt() || seconds.asInt() <= 0 || seconds.asInt() > 1800) {
                    rejected.add("SUBWAY_ARRIVAL_SECONDS_UNUSABLE"); continue;
                }
                Instant prediction = arrival.at().plusSeconds(seconds.asInt());
                if (from.arrivalAt() == null) reasons.add("SUBWAY_BOARDING_DWELL_UNKNOWN");
                else if (platformAt.isBefore(arrival.until()) && prediction.isBefore(arrival.until())) {
                    boarding = prediction;
                    ride = Duration.between(from.arrivalAt(), to.arrivalAt()).getSeconds();
                    source = "SUBWAY_REALTIME_ARRIVAL_AND_TIMETABLE";
                    arrivalEvent = arrival.value().path("eventId").asText(); observedAt = arrival.at();
                } else reasons.add("SUBWAY_REALTIME_OUTSIDE_HORIZON");
            } else reasons.add("ARRIVAL:" + arrival.reason());
            // 놓친 열차의 출발을 현재시각으로 밀지 않는다. 실제 다음 열차를 평가한다.
            if (boarding.isBefore(platformAt)) { rejected.add("SUBWAY_TRAIN_NOT_BOARDABLE"); continue; }
            Instant destination = boarding.plusSeconds(ride);
            if (!destination.isBefore(data.validUntil())) { rejected.add("SUBWAY_TIMETABLE_OUTSIDE_VALIDITY"); continue; }
            estimates.add(new Estimate(Duration.between(platformAt, boarding).getSeconds(), ride, boarding, destination,
                    trip.key(), source, data.version(), arrivalEvent, observedAt, positionEvent, reasons));
        }
        if (estimates.isEmpty()) {
            rejected.add("SUBWAY_NO_BOARDABLE_TRAIN");
            return new Estimate(null, null, null, null, null, "UNAVAILABLE", data.version(), null, null, null, new ArrayList<>(rejected));
        }
        return estimates.stream().min(Comparator.comparing(Estimate::arrivalAt).thenComparing(Estimate::tripKey)).orElseThrow();
    }

    private List<Match> matches(SubwayTimetable.Trip t, List<MappedTransitSegment> edges) {
        var result = new ArrayList<Match>();
        var first = edges.get(0);
        if (!t.lineId().equals(first.routeId()) || (t.realtimeVerified() && !t.providerLineId().equals(first.realtimeRouteId()))
                || !t.direction().equals(first.direction())) return result;
        for (int start = 0; start + edges.size() < t.calls().size(); start++) {
            boolean valid = true;
            for (int i = 0; i < edges.size(); i++) {
                var e = edges.get(i); var a = t.calls().get(start + i); var b = t.calls().get(start + i + 1);
                if (e.mode() != TransportMode.SUBWAY || e.sequence() != i + 1 || !t.lineId().equals(e.routeId())
                        || !t.direction().equals(e.direction())
                        || !a.stationId().equals(e.fromNodeId()) || !b.stationId().equals(e.toNodeId())
                        || (t.realtimeVerified() && (!t.providerLineId().equals(e.realtimeRouteId())
                        || !a.providerStationId().equals(e.fromRealtimeNodeId()) || !b.providerStationId().equals(e.toRealtimeNodeId())))) { valid = false; break; }
            }
            // 순환선은 첫 방문을 놓쳐도 이후 회차의 실제 시각을 평가한다. 회차별 시각을 현재로 밀지 않는다.
            if (valid && t.calls().get(start).stops() && t.calls().get(start + edges.size()).stops()) result.add(new Match(t, start, start + edges.size()));
        }
        return result;
    }

    private Observation read(String raw, String type, SubwayTimetable.Trip trip, String station, Instant asOf) {
        if (raw == null) return new Observation(null, null, null, "MISSING");
        try {
            var node = json.readTree(raw); var ids = node.path("providerIds");
            if ("subway.arrival".equals(type) && node.path("observation").path("arrivalTrainType").asText().isBlank())
                return new Observation(null, null, null, "MISSING_TRAIN_TYPE");
            Instant at = Instant.parse(node.path("occurredAt").asText());
            Instant collected = Instant.parse(node.path("collectedAt").asText());
            Instant until = Instant.parse(node.path("validUntil").asText());
            Instant serviceStart = trip.serviceDate().atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
            // 수집기가 원본 생성시각 누락을 collectedAt으로 보완한 이벤트를 실시간 도착 근거로 승격하지 않는다.
            String generated = node.path("observation").path("sourceGeneratedAt").asText();
            Instant original;
            try { original = Instant.parse(generated); }
            catch (RuntimeException local) { original = LocalDateTime.parse(generated, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.of("Asia/Seoul")).toInstant(); }
            if (!type.equals(node.path("eventType").asText()) || !trip.providerLineId().equals(ids.path("lineId").asText())
                    || !trip.trainId().equals(ids.path("trainId").asText()) || (station != null && !station.equals(ids.path("stationId").asText()))
                    || node.path("eventId").asText().isBlank() || !at.equals(original) || at.isAfter(asOf) || collected.isAfter(asOf)
                    || at.isBefore(serviceStart) || !at.isBefore(serviceStart.plus(Duration.ofHours(30)))
                    || collected.isBefore(at) || !until.isAfter(asOf) || until.isAfter(at.plusSeconds(FRESH_SECONDS))
                    || !at.plusSeconds(FRESH_SECONDS).isAfter(asOf)) return new Observation(null, null, null, "STALE_OR_INVALID");
            return new Observation(node, at, until, null);
        } catch (Exception invalid) { return new Observation(null, null, null, "INVALID_PAYLOAD"); }
    }
    private int index(SubwayTimetable.Trip t, String station) {
        for (int i = 0; i < t.calls().size(); i++) if (t.calls().get(i).providerStationId().equals(station)) return i;
        return -1;
    }
    private String arrivalKey(Match m) { return ARRIVAL + m.trip().providerLineId() + ":" + m.trip().calls().get(m.from()).providerStationId() + ":" + m.trip().trainId(); }
    private String positionKey(SubwayTimetable.Trip t) { return POSITION + t.providerLineId() + ":" + t.trainId(); }
}

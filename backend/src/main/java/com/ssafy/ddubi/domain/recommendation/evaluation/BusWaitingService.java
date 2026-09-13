package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.fasterxml.jackson.databind.*;
import com.ssafy.ddubi.domain.route.model.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 서울 도착 API를 정규화한 Redis 최신값으로 정류장 도착 후 대기시간을 계산한다.
 * 노선·정류소·노선상 순번을 canonical 매핑과 대조하여 같은 정류소의 반대 방향/재방문을 구분한다.
 * 수집→Kafka→Spark 경계를 유지하며 외부 API 호출과 DB 쓰기는 하지 않는다.
 * 초 단위 exps1/2만 사용한다. traTime1/2(분), 평균 배차간격으로 가상의 다음 차를 만들지 않는다.
 */
@Service
public class BusWaitingService {
    private static final String PREFIX = "transit:latest:bus-arrival:";
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean decompositionVerified;

    public BusWaitingService(StringRedisTemplate redis, ObjectMapper json,
            @Value("${ddubi.bus-waiting.decomposition-verified:false}") boolean decompositionVerified) {
        this.redis = redis; this.json = json; this.decompositionVerified = decompositionVerified;
    }
    /** 제공자 이동 step과 접근/환승 도보의 시간 분해가 확인된 환경에서만 전체 ETA에 대기를 넣는다. */
    public boolean decompositionVerified() { return decompositionVerified; }

    /** 평가마다 생성한다. 같은 키의 결측/실패도 고정해 후보 간 증거가 바뀌거나 재시도가 폭증하지 않게 한다. */
    public static final class Session {
        private final Map<String, JsonNode> rows = new HashMap<>();
        private final Map<String, String> errors = new HashMap<>();
        private boolean unavailable;
    }
    public Session newSession() { return new Session(); }
    /** null seconds는 관측 부족이다. 0초 대기와 구분하며 source/eventId/발생시각으로 원본을 추적한다. */
    public record Estimate(Long seconds, Instant boardingAt, String vehicleId, String source,
                           String eventId, Instant observedAt, List<String> reasons) {
        public Estimate { reasons = List.copyOf(reasons); }
        static Estimate missing(String reason) { return new Estimate(null,null,null,"UNAVAILABLE",null,null,List.of(reason)); }
    }

    /**
     * 1. 경로와 관측 신선도 확인 → 2. 첫/두 번째 차량의 운행·도착정보 검사 → 3. 탈 수 있는 가장 이른 차량 선택.
     * 현재 확보한 관측의 validUntil 안에 정류장 도착과 탑승이 모두 들어올 때만 예측을 사용한다.
     * 순번/차량/발생시각 누락, 만차, 운행 종료, 우회는 성공으로 보완하지 않는다.
     */
    public Estimate estimate(RouteSegment segment, Instant asOf, Instant stationAt, Session session) {
        var edges = RouteEvidenceAssembler.edges(segment);
        if (segment.mode() != TransportMode.BUS || edges.isEmpty()) return Estimate.missing("BUS_WAIT_MAPPING_UNVERIFIED");
        var first = edges.get(0);
        for (int i = 0; i < edges.size(); i++) {
            var e = edges.get(i);
            if (e.mode() != TransportMode.BUS || !numeric(e.routeId()) || !e.routeId().equals(e.realtimeRouteId())
                    || !numeric(e.fromNodeId()) || !e.fromNodeId().equals(e.fromRealtimeNodeId())
                    || !numeric(e.toNodeId()) || !e.toNodeId().equals(e.toRealtimeNodeId()) || e.sequence() != i+1
                    || e.fromOrder() < 1 || e.toOrder() != e.fromOrder()+1 || !first.routeId().equals(e.routeId())
                    || !Objects.equals(first.direction(),e.direction()) || first.direction() == null
                    || (i > 0 && (!edges.get(i-1).toNodeId().equals(e.fromNodeId()) || edges.get(i-1).toOrder() != e.fromOrder())))
                return Estimate.missing("BUS_WAIT_MAPPING_UNVERIFIED");
        }
        String key = PREFIX + first.realtimeRouteId() + ":" + first.fromRealtimeNodeId();
        if (!session.rows.containsKey(key) && !session.errors.containsKey(key)) {
            // 서버 장애가 확인되면 다른 후보의 새 키마다 timeout을 반복하여 DB 평가 lease를 소진하지 않는다.
            if (session.unavailable) return Estimate.missing("BUS_WAIT_REDIS_UNAVAILABLE");
            if (session.rows.size()+session.errors.size() >= 256) return Estimate.missing("BUS_WAIT_LOOKUP_BUDGET_EXCEEDED");
            try {
                String raw = redis.opsForValue().get(key);
                if (raw == null) session.errors.put(key,"BUS_WAIT_MISSING");
                else {
                    try { session.rows.put(key,json.readTree(raw)); }
                    catch (Exception invalid) { session.errors.put(key,"BUS_WAIT_INVALID_PAYLOAD"); }
                }
            } catch (RuntimeException failure) { session.unavailable = true; session.errors.put(key,"BUS_WAIT_REDIS_UNAVAILABLE"); }
        }
        if (session.errors.containsKey(key)) return Estimate.missing(session.errors.get(key));
        try {
            var row = session.rows.get(key); var ids = row.path("providerIds"); var obs = row.path("observation");
            if (!"bus.arrival".equals(row.path("eventType").asText()) || !"SEOUL_BUS".equals(ids.path("namespace").asText())
                    || !first.realtimeRouteId().equals(ids.path("routeId").asText()) || !first.fromRealtimeNodeId().equals(ids.path("stopId").asText())
                    || !integer(obs.path("stopOrder")) || first.fromOrder() != obs.path("stopOrder").asInt()
                    || !"SEOUL_EXPS_SECONDS_V1".equals(obs.path("arrivalTimeBasis").asText()))
                return Estimate.missing("BUS_WAIT_IDENTITY_OR_UNIT_UNVERIFIED");
            Instant at = Instant.parse(row.path("occurredAt").asText());
            Instant collected = Instant.parse(row.path("collectedAt").asText());
            Instant until = Instant.parse(row.path("validUntil").asText());
            String generated = obs.path("sourceGeneratedAt").asText();
            Instant original;
            try { original = Instant.parse(generated); }
            catch (RuntimeException local) {
                // 서울 mkTm은 소수초가 붙는 공백 구분 KST 시각도 제공한다. 소수초를 버리거나 현재로 대체하지 않는다.
                original = LocalDateTime.parse(generated.replace(' ','T')).atZone(ZoneId.of("Asia/Seoul")).toInstant();
            }
            if (!original.equals(at) || at.isAfter(asOf) || collected.isAfter(asOf) || collected.isBefore(at)
                    || !until.isAfter(asOf) || until.isAfter(at.plusSeconds(120)) || stationAt.isBefore(asOf)
                    || !stationAt.isBefore(until) || row.path("eventId").asText().isBlank()) return Estimate.missing("BUS_WAIT_STALE_OR_OUTSIDE_HORIZON");
            if (!"00".equals(obs.path("detourCode").asText())) return Estimate.missing("BUS_WAIT_DETOUR_OR_STATUS_UNKNOWN");
            var reasons = new ArrayList<String>();
            var candidates = new ArrayList<Estimate>();
            var vehicles = new HashSet<String>();
            for (String prefix : List.of("first","second")) {
                String vehicle = obs.path(prefix+"VehicleId").asText();
                var seconds = obs.path(prefix+"ArrivalSeconds");
                if (!numeric(vehicle) || "0".equals(vehicle) || !integer(seconds) || seconds.asInt() <= 0 || seconds.asInt() > 7200
                        || !"0".equals(obs.path(prefix+"TerminalArrivalCode").asText()) || !"0".equals(obs.path(prefix+"FullCode").asText())) {
                    reasons.add(prefix+":BUS_WAIT_VEHICLE_UNUSABLE"); continue;
                }
                if (!vehicles.add(vehicle)) return Estimate.missing("BUS_WAIT_DUPLICATE_VEHICLE");
                Instant boarding = at.plusSeconds(seconds.asInt());
                if (boarding.isBefore(stationAt) || !boarding.isBefore(until)) { reasons.add(prefix+":BUS_WAIT_NOT_BOARDABLE"); continue; }
                candidates.add(new Estimate(Duration.between(stationAt,boarding).getSeconds(),boarding,vehicle,
                        "BUS_REALTIME_ARRIVAL",row.path("eventId").asText(),at,List.of()));
            }
            if (candidates.isEmpty()) reasons.add("BUS_WAIT_NO_BOARDABLE_VEHICLE");
            if (candidates.isEmpty()) return new Estimate(null,null,null,"UNAVAILABLE",row.path("eventId").asText(),at,reasons);
            var selected = candidates.stream().min(Comparator.comparing(Estimate::boardingAt).thenComparing(Estimate::vehicleId)).orElseThrow();
            return new Estimate(selected.seconds(),selected.boardingAt(),selected.vehicleId(),selected.source(),selected.eventId(),at,reasons);
        } catch (RuntimeException invalid) { return Estimate.missing("BUS_WAIT_INVALID_PAYLOAD"); }
    }
    private boolean numeric(String value) { return value != null && value.matches("[0-9]{1,30}"); }
    private boolean integer(JsonNode value) { return value.isIntegralNumber() && value.canConvertToInt(); }
}

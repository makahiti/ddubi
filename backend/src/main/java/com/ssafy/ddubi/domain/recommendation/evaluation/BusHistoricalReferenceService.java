package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.ssafy.ddubi.domain.pipeline.service.BusReferenceService;
import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.route.service.PublicBusReferenceService;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 완전 적재된 불변 버전의 정확키 버스 기준값을 RIDE fallback에 사용한다.
 *
 * <p>서울은 실제 날짜별 시간대 집계의 가중합·가중치 합을 사용하고, 경기·인천·부산은 GTFS에서 집계한 구간·시간대 충분통계를 사용한다. 런타임은 대용량 원본을 읽지 않고
 * 현재 마스터의 인접 구간 키로 충분통계를 조회해 수식을 다시 실행한다. 한 edge라도 결측·모호·버전 불일치이면 step 전체를 실패시켜 호출자가 카카오 원본시간을
 * 유지한다. formulaInput이 없는 기존 버전은 배포 호환을 위해 저장된 meanSeconds를 사용한다.
 */
@Service
public class BusHistoricalReferenceService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public record Policy(
            boolean enabled,
            String dataVersion,
            long expectedRows,
            long expectedChunks,
            int maxAgeDays,
            int minSamples,
            int minDates,
            int maxFutureDays,
            String sampleKind) {}

    public record Estimate(
            Long seconds,
            String reason,
            String dataVersion,
            String sampleKind,
            List<BusReferenceService.Row> rows) {
        static Estimate missing(String reason) {
            return new Estimate(null, "HISTORICAL_REFERENCE_" + reason, null, null, List.of());
        }
    }

    public static final class Session {
        final Map<BusReferenceService.Query, Map<String, Object>> rows = new HashMap<>();
    }

    private final BusReferenceService store;
    private final PublicBusReferenceService publicReferences;
    private final MetropolitanBusMasterService masters;
    private final MetropolitanBusCatalog catalog;
    private final Policy policy;
    private volatile boolean gatePassed;
    private volatile long retryAfterNanos;
    private static final Logger log = LoggerFactory.getLogger(BusHistoricalReferenceService.class);
    // gate 관측값. 비밀값·경로는 담지 않으며 진단 조회가 대용량 COUNT를 다시 실행하지 않도록 마지막 결과만 보존한다.
    private volatile String gateState = "NOT_EVALUATED";
    private volatile String gateReason;
    private volatile Instant gateCheckedAt;
    private volatile Long observedRows;
    private volatile Long observedChunks;

    @Autowired
    public BusHistoricalReferenceService(
            BusReferenceService store,
            PublicBusReferenceService publicReferences,
            MetropolitanBusMasterService masters,
            MetropolitanBusCatalog catalog,
            @Value("${ddubi.bus-reference.fallback.enabled:false}") boolean enabled,
            @Value("${ddubi.bus-reference.data-version:}") String version,
            @Value("${ddubi.bus-reference.fallback.expected-rows:0}") long rows,
            @Value("${ddubi.bus-reference.fallback.expected-chunks:0}") long chunks,
            @Value("${ddubi.bus-reference.fallback.max-age-days:90}") int maxAge,
            @Value("${ddubi.bus-reference.fallback.min-samples:3}") int samples,
            @Value("${ddubi.bus-reference.fallback.min-dates:3}") int dates,
            @Value("${ddubi.bus-reference.fallback.max-future-days:7}") int future,
            @Value("${ddubi.bus-reference.fallback.sample-kind:HOURLY_SOURCE_MEAN}") String kind) {
        this(
                store,
                publicReferences,
                masters,
                catalog,
                new Policy(enabled, version, rows, chunks, maxAge, samples, dates, future, kind));
    }

    public BusHistoricalReferenceService(
            BusReferenceService store,
            PublicBusReferenceService publicReferences,
            MetropolitanBusMasterService masters,
            MetropolitanBusCatalog catalog,
            Policy policy) {
        this.store = store;
        this.publicReferences = publicReferences;
        this.masters = masters;
        this.catalog = catalog;
        this.policy = policy;
    }

    private synchronized boolean gate() {
        if (gatePassed) return true;
        if (retryAfterNanos != 0 && System.nanoTime() < retryAfterNanos) return false;
        retryAfterNanos = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        gateCheckedAt = Instant.now();
        try {
            var status = store.status(policy.dataVersion());
            long rows = ((Number) status.get("totalRows")).longValue();
            long chunks = ((Number) status.get("totalChunks")).longValue();
            observedRows = rows;
            observedChunks = chunks;
            gatePassed =
                    policy.dataVersion().equals(status.get("dataVersion"))
                            && rows == policy.expectedRows()
                            && chunks == policy.expectedChunks();
            gateState = gatePassed ? "PASSED" : "FAILED";
            gateReason =
                    gatePassed
                            ? null
                            : (policy.dataVersion().equals(status.get("dataVersion"))
                                    ? "TOTALS_MISMATCH"
                                    : "VERSION_MISMATCH");
            if (gatePassed)
                log.info(
                        "버스 과거 평균 gate 통과 dataVersion={} rows={} chunks={}",
                        policy.dataVersion(),
                        rows,
                        chunks);
            else
                log.warn(
                        "버스 과거 평균 gate 불일치 dataVersion={} rows={}/{} chunks={}/{}",
                        policy.dataVersion(),
                        rows,
                        policy.expectedRows(),
                        chunks,
                        policy.expectedChunks());
        } catch (RuntimeException unavailable) {
            gateState = "FAILED";
            gateReason = "STATUS_UNAVAILABLE:" + unavailable.getClass().getSimpleName();
            log.warn(
                    "버스 과거 평균 gate 확인 실패 dataVersion={} type={}",
                    policy.dataVersion(),
                    unavailable.getClass().getSimpleName());
            return false;
        }
        return gatePassed;
    }

    private boolean policyInvalid() {
        return policy.dataVersion() == null
                || policy.dataVersion().isBlank()
                || policy.expectedRows() < 1
                || policy.expectedChunks() < 1
                || policy.maxAgeDays() < 1
                || policy.minSamples() < 1
                || policy.minDates() < 1
                || policy.maxFutureDays() < 0
                || !Set.of("HOURLY_SOURCE_MEAN", "ACTUAL_TRAVERSAL_MEAN")
                        .contains(policy.sampleKind());
    }

    /** 읽기 전용 진단. 설정·마지막 gate 결과·사유만 반환하며 COUNT를 실행하지 않는다. 운영 승인 값이 아니다. */
    public Map<String, Object> gateStatus() {
        var out = new LinkedHashMap<String, Object>();
        out.put("enabled", policy.enabled());
        out.put(
                "dataVersion",
                policy.dataVersion() == null || policy.dataVersion().isBlank()
                        ? null
                        : policy.dataVersion());
        out.put("expectedRows", policy.expectedRows());
        out.put("expectedChunks", policy.expectedChunks());
        out.put(
                "policy",
                Map.of(
                        "maxAgeDays",
                        policy.maxAgeDays(),
                        "minSamples",
                        policy.minSamples(),
                        "minDates",
                        policy.minDates(),
                        "maxFutureDays",
                        policy.maxFutureDays(),
                        "sampleKind",
                        String.valueOf(policy.sampleKind())));
        out.put("policyValid", !policyInvalid());
        out.put("gateState", gateState);
        out.put("gateReason", gateReason);
        out.put("gateCheckedAt", gateCheckedAt);
        out.put("observedRows", observedRows);
        out.put("observedChunks", observedChunks);
        out.put("productionReady", false);
        out.put("realtimeApplied", false);
        return out;
    }

    /** 큰 COUNT 검증은 앱 준비 시 선행해 첫 사용자 평가의 짧은 탐색 예산에서 분리한다. */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void warmGate() {
        if (policy.enabled() && policy.expectedRows() > 0 && policy.expectedChunks() > 0) gate();
    }

    private boolean masterMatches(String expected) {
        var snapshot = catalog.snapshot();
        return snapshot != null
                && expected.equals(snapshot.version())
                && masters.activeVersion().filter(expected::equals).isPresent();
    }

    /**
     * canonical component에 포함된 출처를 복원한다. 정적 마스터에서 발급된 구성요소만 허용하며 이름이나 좌표로 지역을 추측하지 않는다. 서울 기존 ID는
     * 접두사가 없다.
     */
    private String namespace(MappedTransitSegment edge) {
        for (String value : List.of(edge.routeId(), edge.fromNodeId(), edge.toNodeId())) {
            if (value == null || value.isBlank()) return null;
        }
        for (String namespace : List.of("GBIS", "INCHEON_PUBLIC_CSV", "BUSAN_GTFS")) {
            String prefix = namespace + ".";
            if (edge.routeId().startsWith(prefix)
                    && edge.fromNodeId().startsWith(prefix)
                    && edge.toNodeId().startsWith(prefix)) return namespace;
        }
        if (!edge.routeId().contains(".")
                && !edge.fromNodeId().contains(".")
                && !edge.toNodeId().contains(".")) return "SEOUL_BUS";
        return null;
    }

    public Estimate estimate(
            List<MappedTransitSegment> edges,
            String masterVersion,
            Instant enteredAt,
            long uncertaintySeconds,
            Instant asOf,
            Session session,
            long deadline) {
        if (!policy.enabled()) return Estimate.missing("DISABLED");
        if (policyInvalid()) return Estimate.missing("POLICY_INVALID");
        if (masterVersion == null || edges.isEmpty() || uncertaintySeconds < 0)
            return Estimate.missing("MAPPING_UNVERIFIED");
        if (!gate()) return Estimate.missing("DATASET_INCOMPLETE");
        try {
            if (!masterMatches(masterVersion)) return Estimate.missing("MASTER_VERSION_MISMATCH");
            long total = 0;
            Instant cursor = enteredAt;
            var used = new ArrayList<BusReferenceService.Row>();
            String stepNamespace = namespace(edges.get(0));
            if (stepNamespace == null
                    || edges.stream()
                            .anyMatch(edge -> !Objects.equals(stepNamespace, namespace(edge))))
                return Estimate.missing("MAPPING_UNVERIFIED");
            boolean scheduleFormula = !"SEOUL_BUS".equals(stepNamespace);
            for (int i = 0; i < edges.size(); i++) {
                if (System.nanoTime() >= deadline) return Estimate.missing("BUDGET_EXHAUSTED");
                var edge = edges.get(i);
                if (edge.mode() != com.ssafy.ddubi.domain.route.model.TransportMode.BUS
                        || edge.fromOrder() < 1
                        || edge.toOrder() != edge.fromOrder() + 1
                        || edge.sequence() != i + 1
                        || !edge.segmentId()
                                .equals(
                                        "BUS:"
                                                + edge.routeId()
                                                + ":"
                                                + edge.fromNodeId()
                                                + ":"
                                                + edge.toNodeId())
                        || (i > 0
                                && (!edges.get(i - 1).routeId().equals(edge.routeId())
                                        || !edges.get(i - 1).toNodeId().equals(edge.fromNodeId())
                                        || edges.get(i - 1).toOrder() != edge.fromOrder())))
                    return Estimate.missing("MAPPING_UNVERIFIED");
                var time = cursor.atZone(KST);
                var latest = cursor.plusSeconds(uncertaintySeconds).atZone(KST);
                if (!time.toLocalDate().equals(latest.toLocalDate())
                        || time.getHour() != latest.getHour())
                    return Estimate.missing("ENTRY_HOUR_UNCERTAIN");
                LocalDate today = asOf.atZone(KST).toLocalDate();
                if (time.toLocalDate().isBefore(today)
                        || time.toLocalDate().isAfter(today.plusDays(policy.maxFutureDays())))
                    return Estimate.missing("FORECAST_DATE_UNSUPPORTED");
                // GTFS 피드에는 실제 운행일 관측이 없으므로 dow=1을 템플릿 키로 고정한다.
                // 서울 실측은 기존 Spark dayofweek(일=1) 키를 그대로 사용한다.
                int lookupDow = scheduleFormula ? 1 : time.getDayOfWeek().getValue() % 7 + 1;
                var query =
                        new BusReferenceService.Query(
                                masterVersion,
                                stepNamespace,
                                edge.routeId(),
                                edge.fromNodeId(),
                                edge.toNodeId(),
                                edge.fromOrder(),
                                edge.toOrder(),
                                lookupDow,
                                time.getHour());
                var result = session.rows.get(query);
                if (result == null) {
                    if (session.rows.size() >= 256)
                        return Estimate.missing("LOOKUP_BUDGET_EXHAUSTED");
                    result = publicReferences.lookup(policy.dataVersion(), query);
                    session.rows.put(query, result);
                }
                if (!"STAGED_REFERENCE_NOT_APPROVED".equals(result.get("status"))
                        || Boolean.TRUE.equals(result.get("truncated")))
                    return Estimate.missing("EXACT_KEY_UNAVAILABLE");
                var candidates = new ArrayList<BusReferenceService.Row>();
                for (Object value : (List<?>) result.getOrDefault("rows", List.of())) {
                    if (!(value instanceof BusReferenceService.Row row))
                        return Estimate.missing("INVALID_ROW");
                    BusReferenceService.validate(row);
                    if (!row.masterVersion().equals(masterVersion)
                            || !row.namespace().equals(stepNamespace)
                            || !row.routeId().equals(edge.routeId())
                            || !row.fromStopId().equals(edge.fromNodeId())
                            || !row.toStopId().equals(edge.toNodeId())
                            || row.fromOrder() != edge.fromOrder()
                            || row.toOrder() != edge.toOrder()
                            || row.dow() != query.dow()
                            || row.hourSlot() != query.hourSlot())
                        return Estimate.missing("INVALID_ROW");
                    if (scheduleFormula) {
                        if (!row.sampleKind().equals("GTFS_SCHEDULE_FORMULA")
                                || row.sampleCount() < 1
                                || row.observedDates() != 0
                                || time.toLocalDate().isBefore(row.observationStart())
                                || time.toLocalDate().isAfter(row.observationEnd())
                                || row.meanSeconds() > 3600) continue;
                    } else if (!row.sampleKind().equals(policy.sampleKind())
                            || row.sampleCount() < policy.minSamples()
                            || row.observedDates() < policy.minDates()
                            || !row.observationEnd().isBefore(today)
                            || row.observationEnd().isBefore(today.minusDays(policy.maxAgeDays()))
                            || row.meanSeconds() > 3600) continue;
                    candidates.add(row);
                }
                if (candidates.isEmpty())
                    return Estimate.missing("SAMPLES_OR_FRESHNESS_INSUFFICIENT");
                LocalDate mostRecent =
                        candidates.stream()
                                .map(BusReferenceService.Row::observationEnd)
                                .max(Comparator.naturalOrder())
                                .orElseThrow();
                var selected =
                        (scheduleFormula
                                        ? candidates.stream()
                                        : candidates.stream()
                                                .filter(r -> r.observationEnd().equals(mostRecent)))
                                .distinct()
                                .toList();
                if (selected.size() != 1) return Estimate.missing("AMBIGUOUS_PERIOD");
                var row = selected.get(0);
                // 새 버전은 최종 숫자를 신뢰하지 않고 MySQL에 함께 저장된 충분통계로 Backend가
                // 수식을 직접 실행한다. 구형 청크는 배포 호환을 위해 meanSeconds를 사용하되,
                // 신규 버전 생성 시에는 formulaInput을 포함해야 한다.
                double calculated =
                        row.formulaInput() == null
                                ? row.meanSeconds()
                                : BusTravelTimeFormula.seconds(row.namespace(), row.formulaInput());
                long seconds = (long) Math.ceil(calculated);
                total += seconds;
                if (total > 86400) return Estimate.missing("INVALID_DURATION");
                cursor = cursor.plusSeconds(seconds);
                used.add(row);
            }
            if (!masterMatches(masterVersion)) return Estimate.missing("MASTER_VERSION_MISMATCH");
            return new Estimate(
                    total,
                    scheduleFormula ? "GTFS_FORMULA_APPLIED" : "HISTORICAL_REFERENCE_APPLIED",
                    policy.dataVersion(),
                    scheduleFormula ? "GTFS_SCHEDULE_FORMULA" : policy.sampleKind(),
                    List.copyOf(used));
        } catch (RuntimeException failure) {
            return Estimate.missing("LOOKUP_UNAVAILABLE");
        }
    }
}

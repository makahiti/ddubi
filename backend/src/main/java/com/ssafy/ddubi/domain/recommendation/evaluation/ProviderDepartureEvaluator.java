package com.ssafy.ddubi.domain.recommendation.evaluation;

import static com.ssafy.ddubi.domain.recommendation.evaluation.ConditionalDeparturePolicy.*;
import static com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.*;

import com.ssafy.ddubi.domain.route.model.RouteCandidate;
import java.time.*;
import java.util.*;

/**
 * 고정된 provider 소요시간으로 출발시각을 직접 역산하는 순수 계산기다. 도보 보강이 완료된 후보를 입력받으므로 성공한 도보 교체는 보존한다. 외부 API·ML·DB
 * 조회와 1분 반복 탐색은 없다. 실제 통제 구간은 타임라인에서 다시 검사한다. 시간표 계산 불가는 구간 원본 예상으로 대체한다. complete는 고정 시간과 현재 통제
 * 증거에 대한 계산 완료이며 실시간 운행/정시 도착 보장이 아니다.
 */
public final class ProviderDepartureEvaluator {
    private final RouteEvidenceAssembler assembler;
    private final RouteTimelineEvaluator timeline;

    public ProviderDepartureEvaluator(
            RouteEvidenceAssembler assembler, RouteTimelineEvaluator timeline) {
        this.assembler = assembler;
        this.timeline = timeline;
    }

    /** P에서 비교한 뒤 필요한 경우에만 D-600초-T를 검사한다. 시각을 과거로 돌려 성공시키지 않는다. */
    public ConditionalDepartureEvaluator.Evaluation evaluate(
            List<RouteCandidate> routes,
            RouteEvidenceAssembler.Snapshot snapshot,
            Instant asOf,
            Instant planned,
            Instant deadline,
            MlRoutePreview.Comparison previous,
            String reason) {
        var journeys = routes.stream().map(r -> assembler.assemble(r, snapshot, asOf)).toList();
        var closures = assembler.closures(snapshot);
        // 진단의 입력 시각·응답은 보존하되 기본시간에 반영하지 않은 값을 적용 성공으로 남기지 않는다.
        var predictions =
                previous == null
                        ? List.<MlRoutePreview.Prediction>of()
                        : previous.predictions().stream()
                                .map(
                                        p ->
                                                new MlRoutePreview.Prediction(
                                                        p.candidateKey(),
                                                        p.stepKey(),
                                                        p.segmentId(),
                                                        p.enteredAt(),
                                                        p.input(),
                                                        p.estimate(),
                                                        false))
                                .toList();
        var subway =
                previous == null
                        ? List.<MlRoutePreview.SubwayPrediction>of()
                        : previous.subwayPredictions().stream()
                                .map(
                                        p ->
                                                new MlRoutePreview.SubwayPrediction(
                                                        p.candidateKey(),
                                                        p.stepKey(),
                                                        p.platformAt(),
                                                        p.estimate(),
                                                        false,
                                                        reason))
                                .toList();
        var waits =
                previous == null
                        ? List.<MlRoutePreview.WaitPrediction>of()
                        : previous.waitPredictions().stream()
                                .map(
                                        p ->
                                                new MlRoutePreview.WaitPrediction(
                                                        p.candidateKey(),
                                                        p.stepKey(),
                                                        p.mode(),
                                                        p.stationAt(),
                                                        p.seconds(),
                                                        p.boardingAt(),
                                                        p.vehicleId(),
                                                        p.source(),
                                                        p.eventId(),
                                                        p.observedAt(),
                                                        p.validUntil(),
                                                        false,
                                                        java.util.stream.Stream.concat(
                                                                        p.reasons().stream(),
                                                                        java.util.stream.Stream.of(
                                                                                reason))
                                                                .distinct()
                                                                .toList()))
                                .toList();
        var cache = new LinkedHashMap<Instant, MlRoutePreview.Comparison>();
        java.util.function.Function<Instant, MlRoutePreview.Comparison> compare =
                at ->
                        cache.computeIfAbsent(
                                at,
                                t -> {
                                    var candidates = new ArrayList<MlRoutePreview.Candidate>();
                                    for (int index = 0; index < journeys.size(); index++) {
                                        var journey = journeys.get(index);
                                        Result result =
                                                timeline.evaluate(
                                                        journey, asOf, t, deadline, closures);
                                        var reasons = new ArrayList<>(result.reasons());
                                        reasons.add(reason);
                                        reasons.add("PROVIDER_DURATION_ESTIMATE");
                                        reasons.add("VALIDATION_ONLY");
                                        if (previous != null)
                                            previous.candidates().stream()
                                                    .filter(
                                                            c ->
                                                                    c.result()
                                                                            .journeyKey()
                                                                            .equals(journey.key()))
                                                    .flatMap(c -> c.result().reasons().stream())
                                                    .filter(
                                                            r ->
                                                                    !r.contains("APPLIED")
                                                                            && !r.startsWith(
                                                                                    "CLOSURE")
                                                                            && !r.startsWith(
                                                                                    "ROAD_"))
                                                    .forEach(reasons::add);
                                        // 사건 영향은 과거 제안 시각의 사유를 복사하지 않고 새 통과 시각으로 다시 판정한다.
                                        var route = routes.get(index);
                                        if (!snapshot.roads().isEmpty())
                                            for (var segment : route.segments()) {
                                                if (segment.mode()
                                                        != com.ssafy.ddubi.domain.route.model
                                                                .TransportMode.BUS) continue;
                                                var step =
                                                        result.steps().stream()
                                                                .filter(
                                                                        s ->
                                                                                s.key()
                                                                                        .equals(
                                                                                                "step-"
                                                                                                        + segment
                                                                                                                .sequence()))
                                                                .findFirst()
                                                                .orElse(null);
                                                if (step == null || step.exitedAt() == null)
                                                    reasons.add("ROAD_IMPACT_UNKNOWN");
                                                else
                                                    reasons.addAll(
                                                            RoadImpactEvidence.reasons(
                                                                    segment,
                                                                    snapshot.roads(),
                                                                    step.enteredAt(),
                                                                    step.exitedAt()));
                                            }
                                        result =
                                                new Result(
                                                        result.journeyKey(),
                                                        result.departureAt(),
                                                        result.estimatedArrivalAt(),
                                                        result.assessment(),
                                                        true,
                                                        result.steps(),
                                                        reasons.stream().distinct().toList(),
                                                        "provider-departure-v1");
                                        candidates.add(
                                                new MlRoutePreview.Candidate(
                                                        result,
                                                        "API_ONLY",
                                                        0,
                                                        journey.legs().size(),
                                                        0,
                                                        0));
                                    }
                                    return new MlRoutePreview.Comparison(
                                            "PREVIEW",
                                            false,
                                            best(
                                                    candidates.stream()
                                                            .map(MlRoutePreview.Candidate::result)
                                                            .toList()),
                                            candidates,
                                            predictions,
                                            false,
                                            subway,
                                            waits);
                                });
        Instant effective = planned.isBefore(asOf) ? asOf : planned;
        var initial = compare.apply(effective);
        var policy =
                new ConditionalDeparturePolicy()
                        .inspect(
                                initial.candidates().stream()
                                        .map(MlRoutePreview.Candidate::result)
                                        .toList(),
                                planned,
                                deadline,
                                asOf);
        if (!policy.searchRequired())
            return finish(
                    policy.departureMissed() ? Decision.DEPART_NOW : Decision.KEEP_DEPARTURE,
                    policy,
                    initial,
                    initial,
                    cache.size() * routes.size(),
                    reason);

        var departures = new TreeSet<Instant>(Comparator.reverseOrder());
        var lateDepartures = new TreeSet<Instant>();
        lateDepartures.add(asOf);
        for (int index = 0; index < routes.size(); index++) {
            long seconds =
                    journeys.get(index).legs().stream().mapToLong(l -> l.fallbackSeconds()).sum();
            departures.add(policy.target().minusSeconds(seconds));
            // 통제로 최신 출발이 막히면 통제 시작 직전 통과 후보도 직접 계산한다.
            // 이동 종료와 통제 시작이 같은 초도 기존 타임라인이 차단하므로 1초를 앞당긴다.
            long offset = 0;
            for (var leg : journeys.get(index).legs()) {
                long entered = offset;
                offset += leg.fallbackSeconds();
                for (var closure : closures)
                    if (leg.segmentIds().stream().anyMatch(closure.segmentIds()::contains)) {
                        departures.add(
                                closure.window()
                                        .validFrom()
                                        .minusSeconds(offset + leg.entryUncertaintySeconds() + 1));
                        lateDepartures.add(closure.window().validUntil().minusSeconds(entered));
                    }
            }
        }
        for (Instant at : departures) {
            if (at.isBefore(asOf) || at.isAfter(planned) || at.isAfter(policy.target())) continue;
            var comparison = compare.apply(at);
            if (comparison.fastest() != null
                    && !comparison.fastest().estimatedArrivalAt().isAfter(policy.target()))
                return finish(
                        at.equals(asOf) ? Decision.DEPART_NOW : Decision.ADVANCE_DEPARTURE,
                        policy,
                        initial,
                        comparison,
                        cache.size() * routes.size(),
                        reason);
        }
        // 여유 목표 달성이 불가능하면 현재 출발/확인된 통제 종료에서 가장 덜 늦는 대안을 제공한다.
        var chosen = initial;
        for (Instant at : lateDepartures)
            if (!at.isBefore(asOf)) {
                var comparison = compare.apply(at);
                if (comparison.fastest() != null
                        && (chosen.fastest() == null
                                || comparison
                                        .fastest()
                                        .estimatedArrivalAt()
                                        .isBefore(chosen.fastest().estimatedArrivalAt())))
                    chosen = comparison;
            }
        Decision decision =
                chosen.fastest() == null
                        ? (routes.isEmpty()
                                        || chosen.candidates().stream()
                                                .allMatch(
                                                        c ->
                                                                c.result().assessment()
                                                                        == Assessment.BLOCKED)
                                ? Decision.ROUTE_UNAVAILABLE
                                : Decision.DATA_INSUFFICIENT)
                        : chosen.fastest().estimatedArrivalAt().isBefore(deadline)
                                ? Decision.MARGIN_UNAVAILABLE
                                : Decision.LATE_EXPECTED;
        return finish(decision, policy, initial, chosen, cache.size() * routes.size(), reason);
    }

    private ConditionalDepartureEvaluator.Evaluation finish(
            Decision decision,
            Initial policy,
            MlRoutePreview.Comparison initial,
            MlRoutePreview.Comparison chosen,
            int count,
            String reason) {
        return new ConditionalDepartureEvaluator.Evaluation(
                decision,
                null,
                policy.target(),
                policy.bufferSeconds(),
                policy.departureMissed(),
                initial.fastest(),
                chosen.proposal(chosen.fastest(), policy.target()),
                initial,
                chosen,
                new ConditionalDepartureEvaluator.Search(
                        "PROVIDER_DURATION_BACKSOLVE",
                        count,
                        true,
                        0,
                        count,
                        0,
                        List.of("FIXED_PROVIDER_DURATION", reason)),
                null);
    }
}

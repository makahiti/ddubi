package com.ssafy.ddubi.domain.recommendation.evaluation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import static com.ssafy.ddubi.domain.recommendation.evaluation.JourneyEvidence.*;

/**
 * 출발 후보 한 개에 대해 도보→대기→이동→환승 순으로 예상 도착을 계산한다.
 * 외부 조회·DB 쓰기·현재시각 참조가 없어 고정 증거 Replay가 가능하다.
 * 사용자 사전 도착 여유나 임의의 지연 분을 추가하지 않으며, 입력 출발시각을 수정하지 않는다.
 */
@Component
public class RouteTimelineEvaluator {
    /** ON_TIME_ESTIMATE도 정시 도착 보장이 아니다. 근거 부족 여부는 결과의 tentative로 별도 제공한다. */
    public enum Assessment { ON_TIME_ESTIMATE, LATE_ESTIMATE, INSUFFICIENT_DATA, BLOCKED }

    /** 한 요소의 계산 근거다. 초 단위 시간, 출처, 탑승 후보와 품질 이유를 재현할 수 있게 보존한다. */
    public record Step(String key, Instant enteredAt, Instant exitedAt, String source,
                       String vehicleId, String reason) { }

    /**
     * 최종 예상값과 경로 내 근거다. 계산 불가/통제 시 ETA를 null로 하여 정상 도착으로 오인하지 않는다.
     * tentative는 확률이나 confidence 점수가 아니며 추정·결측 보완을 사용했다는 표시다.
     */
    public record Result(String journeyKey, Instant departureAt, Instant estimatedArrivalAt,
                         Assessment assessment, boolean tentative, List<Step> steps,
                         List<String> reasons, String policyVersion) {
        public Result {
            steps = List.copyOf(steps);
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * asOf까지 확보한 증거만으로 미래 departureAt의 여행을 평가한다.
     * 승강장 도착보다 앞선 차량은 건너뛰고 다음 후보를 검토한다. 누락된 다음 차량을 만들지 않는다.
     * 출발 후보는 asOf 이후여야 하며 과거 출발의 가상 성공 결과를 알림에 사용하지 않는다.
     */
    public Result evaluate(Journey journey, Instant asOf, Instant departureAt, Instant arriveBy,
                           List<Closure> closures) {
        Objects.requireNonNull(journey);
        Objects.requireNonNull(asOf);
        Objects.requireNonNull(departureAt);
        Objects.requireNonNull(arriveBy);
        if (departureAt.isBefore(asOf)) throw new IllegalArgumentException("과거 출발은 평가할 수 없습니다.");
        var steps = new ArrayList<Step>();
        var reasons = new ArrayList<String>();
        Instant cursor = departureAt;
        boolean tentative = false;

        // 1. 앞 요소의 결과가 다음 정류장/승강장 도착시각이 된다. 목표 도착시각을 입력으로 복사하지 않는다.
        for (Leg leg : journey.legs()) {
            Instant enteredAt = cursor;
            boolean fresh = leg.window() != null && leg.window().covers(asOf, cursor)
                    && leg.window().covers(asOf, cursor.plusSeconds(leg.entryUncertaintySeconds()));
            if (leg.entryUncertaintySeconds() > 0) {
                tentative = true;
                reasons.add(leg.key() + ":ENTRY_TIME_RANGE");
            }
            Long seconds = fresh ? leg.seconds() : null;
            String source = fresh ? leg.window().source() : "FALLBACK";
            String reason = "ESTIMATE";
            String vehicleId = null;

            // 2. 불확실한 도착 범위 안에서 승강장에 도착하면 앞 차량 탑승을 확정하지 않는다.
            if (leg.kind() == Kind.WAIT) {
                seconds = null;
                if (fresh) {
                    var candidates = leg.boardings().stream()
                            .sorted(Comparator.comparing(Boarding::earliest).thenComparing(Boarding::vehicleId)).toList();
                    for (Boarding boarding : candidates) {
                        Instant latestEntry = cursor.plusSeconds(leg.entryUncertaintySeconds());
                        if (boarding.latest().isBefore(cursor)) continue;
                        if (boarding.earliest().isBefore(latestEntry)) {
                            tentative = true;
                            reasons.add(leg.key() + ":BOARDING_UNCERTAIN:" + boarding.vehicleId());
                            continue;
                        }
                        // 도착정보의 유효범위를 넘긴 차량 예측을 미래 시간표처럼 이용하지 않는다.
                        if (!leg.window().covers(asOf, boarding.latest())) continue;
                        seconds = Duration.between(cursor, boarding.latest()).getSeconds();
                        vehicleId = boarding.vehicleId();
                        tentative |= !boarding.validatedBounds();
                        reason = boarding.validatedBounds() ? "BOARDING_ESTIMATE" : "BOARDING_POINT_ESTIMATE";
                        break;
                    }
                }
            }
            if (seconds == null) {
                seconds = leg.fallbackSeconds();
                source = "FALLBACK";
                reason = fresh ? "NO_USABLE_ESTIMATE" : "MISSING_STALE_OR_OUTSIDE_HORIZON";
                reasons.add(leg.key() + ":" + reason);
                tentative = true;
            }
            if (seconds == null) {
                steps.add(new Step(leg.key(), enteredAt, null, source, null, reason));
                return result(journey, departureAt, null, Assessment.INSUFFICIENT_DATA, true, steps, reasons);
            }
            cursor = cursor.plusSeconds(seconds);
            steps.add(new Step(leg.key(), enteredAt, cursor, source, vehicleId, reason));

            // 3. 통과 구간 전체와 통제 시간의 교집합을 확인한다. 도로 근처의 행사만으로 차단하지 않는다.
            for (Closure closure : closures) {
                Window w = closure.window();
                boolean overlaps = !w.availableAt().isAfter(asOf)
                        && enteredAt.isBefore(w.validUntil()) && !cursor.plusSeconds(leg.entryUncertaintySeconds()).isBefore(w.validFrom());
                if (overlaps && leg.segmentIds().stream().anyMatch(closure.segmentIds()::contains)) {
                    if (leg.entryUncertaintySeconds() > 0 && (enteredAt.plusSeconds(leg.entryUncertaintySeconds()).compareTo(w.validUntil()) >= 0
                            || cursor.isBefore(w.validFrom()))) {
                        reasons.add("CLOSURE_POSSIBLE:" + closure.eventId());
                        return result(journey, departureAt, null, Assessment.INSUFFICIENT_DATA, true, steps, reasons);
                    }
                    reasons.add("CLOSURE:" + closure.eventId());
                    return result(journey, departureAt, null, Assessment.BLOCKED, tentative, steps, reasons);
                }
            }
        }
        return result(journey, departureAt, cursor,
                cursor.isAfter(arriveBy) ? Assessment.LATE_ESTIMATE : Assessment.ON_TIME_ESTIMATE,
                tentative, steps, reasons);
    }

    private Result result(Journey journey, Instant departure, Instant arrival, Assessment assessment,
                          boolean tentative, List<Step> steps, List<String> reasons) {
        return new Result(journey.key(), departure, arrival, assessment, tentative, steps, reasons, "timeline-v1");
    }
}

package com.ssafy.ddubi.domain.recommendation.service;

import java.time.Instant;
import java.util.List;

/**
 * 도착 시각에서 <b>출발 시각을 거꾸로</b> 찾는다.
 *
 * <h3>왜 한 번에 안 되나 — 순환이다</h3>
 * 출발 시각을 알아야 각 구간의 진입 시각이 나오고, 진입 시각을 알아야 예측·기준선을 고를 수
 * 있고, 그래야 총 소요시간이 나온다. 그런데 총 소요시간을 알아야 출발 시각이 정해진다.
 *
 * <pre>
 *   추정 출발 → 구간별 진입 시각 → 소요시간 → 새 추정 출발 → ...
 * </pre>
 *
 * <h3>그래서 두 번만 돈다</h3>
 * 첫 추정은 경로 API 총시간으로 잡는다. 대개 실제와 몇 분 차이라, 한 번 다시 돌리면
 * 시간대(hour_slot)가 바뀌는 경계 사례까지 대부분 흡수된다. 더 돌려도 값이 거의 안 변하는데
 * <b>매 회차가 예측 배치 호출 한 번</b>이라 비용만 늘어난다.
 *
 * <p>수렴하지 않는 경우(값이 계속 튀는 경우)는 마지막 계산을 그대로 쓴다. 답이 없는 것보다
 * 조금 어긋난 답이 낫고, 어긋난 정도는 {@code duration} 과 실제 도착 예정의 차이로 드러난다.
 */
final class DepartureSolver {

    /** 이 이상 안 바뀌면 더 돌려도 의미가 없다. */
    private static final long TOLERANCE_SECONDS = 60;

    private static final int MAX_ROUNDS = 2;

    private DepartureSolver() {
    }

    /**
     * 한 번의 풀이 결과.
     *
     * <p>{@code estimate} 는 {@code departAt} 에서 최대 {@link #TOLERANCE_SECONDS} 초 떨어진
     * 시각으로 계산한 것이다. 수렴했다고 판단해 빠져나올 때 마지막 한 번을 다시 돌리지 않기
     * 때문이다 — 1분 차이로 구간 진입 시각이 바뀌어 봐야 같은 시간대·같은 기준선을 본다.
     */
    record Solution(Instant departAt, TravelTimeEstimate estimate) {

        /** 이 출발 시각으로 실제 도착하게 되는 시각. 여유는 포함하지 않는다. */
        Instant arrivalWithoutBuffer() {
            return departAt.plusSeconds(estimate.totalSeconds());
        }
    }

    /**
     * @param arriveBy      늦어도 이 시각까지는 도착해야 한다
     * @param bufferSeconds 도착 시각 앞에 두는 공통 안전 여유
     */
    static Solution solve(TravelTimeEstimator estimator, List<StepEstimateInput> steps,
                          Instant arriveBy, long bufferSeconds) {
        long routeApiTotal = steps.stream().mapToLong(StepEstimateInput::routeApiSeconds).sum();
        Instant departAt = arriveBy.minusSeconds(routeApiTotal + bufferSeconds);

        TravelTimeEstimate estimate = estimator.estimate(steps, departAt);

        for (int round = 0; round < MAX_ROUNDS; round++) {
            Instant next = arriveBy.minusSeconds(estimate.totalSeconds() + bufferSeconds);
            if (Math.abs(next.getEpochSecond() - departAt.getEpochSecond()) <= TOLERANCE_SECONDS) {
                return new Solution(next, estimate);
            }
            departAt = next;
            estimate = estimator.estimate(steps, departAt);
        }
        return new Solution(departAt, estimate);
    }
}

package com.ssafy.ddubi.domain.recommendation.port;

import java.util.Objects;

/**
 * 예측 결과 한 건. <b>예측 불가를 값으로 표현한다</b> — 분산팀 합의(2026-09-10):
 * "예측 불가는 이유와 함께 명시적으로 반환하고 백엔드에서 fallback 하도록 한다".
 *
 * <p>예외로 던지지 않는 이유: 배치 응답이라 한 구간이 실패해도 나머지는 살아 있다.
 * 예외를 쓰면 성공한 구간까지 같이 버리게 된다.
 */
public record BusTravelTimePrediction(Status status, Long seconds, String reason) {

    public enum Status { PREDICTED, UNAVAILABLE }

    public BusTravelTimePrediction {
        if ((status == Status.PREDICTED) != (seconds != null)) {
            throw new IllegalArgumentException("PREDICTED 일 때만 seconds 가 있어야 합니다.");
        }
    }

    public static BusTravelTimePrediction predicted(long seconds) {
        return new BusTravelTimePrediction(Status.PREDICTED, seconds, null);
    }

    public static BusTravelTimePrediction unavailable(String reason) {
        return new BusTravelTimePrediction(Status.UNAVAILABLE, null,
                Objects.requireNonNull(reason, "예측 불가에는 이유가 있어야 합니다."));
    }

    public boolean isPredicted() {
        return status == Status.PREDICTED;
    }
}

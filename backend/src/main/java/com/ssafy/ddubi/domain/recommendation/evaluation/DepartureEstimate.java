package com.ssafy.ddubi.domain.recommendation.evaluation;

import java.time.Instant;

/**
 * 사용자가 실제 출발을 확인한 순간, 선택된 저장 경로 하나를 그 시각부터 다시 계산한 결과다. 일정 시작 전 제안과 구분하여 평가 JSON에 보존한다. 계산이 불가능하면
 * ETA를 비워 과거 제안값을 새 도착 예정처럼 노출하지 않는다. 시각은 모두 UTC Instant, 소요시간은 초 단위다.
 */
public record DepartureEstimate(
        String status,
        Instant actualDepartAt,
        Instant estimatedArrivalAt,
        Long durationSeconds,
        Instant computedAt,
        String routeKey,
        String reason) {
    public static DepartureEstimate unavailable(
            Instant departedAt, String routeKey, String reason) {
        return new DepartureEstimate(
                "UNAVAILABLE", departedAt, null, null, Instant.now(), routeKey, reason);
    }
}

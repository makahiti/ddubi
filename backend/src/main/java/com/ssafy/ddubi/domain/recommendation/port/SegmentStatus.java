package com.ssafy.ddubi.domain.recommendation.port;

import java.time.Instant;

/**
 * Spark 가 계산한 구간의 <b>현재</b> 상태 (Redis {@code segment:status:{segmentId}}).
 *
 * <h3>이 값은 기본 소요시간이 아니다</h3>
 * 기본 소요시간은 예측·기준선·경로 API 에서 나온다. 이 값은 그 위에 얹는 <b>지연 보정</b>이다.
 * ML 결정 문서(2026-09-08)의 "현재 관측을 미래 구간의 실제 상태로 취급하지 않는다" 를 따른다 —
 * 한 시간 뒤에 지나갈 구간에 지금의 {@code delaySeconds} 를 더하면 그건 예측이 아니라 착각이다.
 *
 * @param delaySeconds 평시 대비 추가 소요 시간(초)
 * @param validUntil   이 시각을 넘기면 unknown 으로 취급한다. Redis TTL 과 맞춰져 있다
 */
public record SegmentStatus(
        String segmentId,
        String status,
        int delaySeconds,
        Double confidence,
        Instant computedAt,
        Instant validUntil,
        String source
) {

    /** 계산에 쓸 수 있는 상태인가. 값이 있어도 유효기간이 지났으면 없는 것으로 본다. */
    public boolean isUsableAt(Instant when) {
        return validUntil != null && when.isBefore(validUntil);
    }
}

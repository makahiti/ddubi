package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.recommendation.entity.Confidence;

import java.util.List;

/**
 * 경로 하나의 산정 결과.
 *
 * @param fallbackUsed 예측을 못 쓴 구간이 하나라도 있었는가. 규약 4장 규칙 8 이 요구하는
 *                     응답 필드다 — 사용자가 "이 숫자를 얼마나 믿을지" 판단할 근거
 */
public record TravelTimeEstimate(
        List<SegmentEstimate> segments,
        long totalSeconds,
        Confidence confidence,
        boolean fallbackUsed
) {

    public TravelTimeEstimate {
        segments = List.copyOf(segments);
    }
}

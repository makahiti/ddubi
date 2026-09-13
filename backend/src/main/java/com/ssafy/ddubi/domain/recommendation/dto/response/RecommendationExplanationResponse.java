package com.ssafy.ddubi.domain.recommendation.dto.response;

import com.ssafy.ddubi.domain.recommendation.entity.Recommendation;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * "왜 이 시각인가" 상세.
 *
 * <p>{@code reasons} 와 {@code snapshot} 은 <b>문자열 그대로</b> 내보낸다. 서버가 다시
 * 파싱해 구조를 바꾸면, 저장된 근거와 화면에 보이는 근거가 갈라질 수 있다. 이 응답의
 * 목적은 "저장된 것을 그대로 보여 주는" 것이다.
 */
@Schema(description = "추천 근거 상세")
public record RecommendationExplanationResponse(

        @Schema(description = "추천 ID")
        Long recommendationId,

        @Schema(description = "출처별 소요시간과 후보 비교 (JSON 문자열)")
        String reasons,

        @Schema(description = "계산에 쓴 구간별 원본 — 재현용 (JSON 문자열)")
        String segmentStatusSnapshot
) {

    public static RecommendationExplanationResponse from(Recommendation recommendation) {
        return new RecommendationExplanationResponse(
                recommendation.getId(),
                recommendation.getReasons(),
                recommendation.getSegmentStatusSnapshot());
    }
}

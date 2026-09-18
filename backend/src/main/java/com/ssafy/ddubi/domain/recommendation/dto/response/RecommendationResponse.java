package com.ssafy.ddubi.domain.recommendation.dto.response;

import com.ssafy.ddubi.domain.recommendation.entity.Confidence;
import com.ssafy.ddubi.domain.recommendation.entity.Recommendation;
import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 추천 응답.
 *
 * <h3>fallbackUsed 를 왜 내보내나</h3>
 * 규약 4장 규칙 8 이 요구한다. 지금은 예측 서비스도 구간 기준선도 없어서 <b>거의 항상
 * true</b> 다. 숨기면 사용자는 이 숫자가 실시간 계산인 줄 안다. 화면에 "평소 소요시간
 * 기준" 이라고 표시할 근거가 이 값이다.
 */
@Schema(description = "권장 출발 시각과 근거")
public record RecommendationResponse(

        @Schema(description = "추천 ID")
        Long recommendationId,

        @Schema(description = "이 추천이 계산한 경로 ID")
        Long routeId,

        @Schema(description = "권장 출발 시각 — 서비스의 최종 답")
        LocalDateTime departAt,

        @Schema(description = "예상 도착 시각. 안전 여유는 포함하지 않는다")
        LocalDateTime eta,

        @Schema(description = "예상 이동시간(분)")
        Integer durationMin,

        @Schema(description = "도착 시각 앞에 둔 공통 안전 여유(분)")
        Integer bufferMin,

        @Schema(description = "HIGH=전 구간 예측 / MID=기준선 섞임 / LOW=경로 API 시간 섞임")
        Confidence confidence,

        @Schema(description = "예측을 못 쓴 구간이 있었는가. 화면에 출처를 밝힐 근거")
        boolean fallbackUsed,

        @Schema(description = "사용한 교통 데이터의 확인 시각 — \"18:01 기준\" 표시용")
        LocalDateTime dataCheckedAt,

        @Schema(description = "계산 계기")
        TriggerType triggerType,

        @Schema(description = "추천 정책 버전")
        String policyVersion
) {

    public static RecommendationResponse from(Recommendation recommendation) {
        return new RecommendationResponse(
                recommendation.getId(),
                recommendation.getRoute().getId(),
                recommendation.getDepartAt(),
                recommendation.getEta(),
                recommendation.getDurationMin(),
                recommendation.getBufferMin(),
                recommendation.getConfidence(),
                recommendation.getConfidence() != Confidence.HIGH,
                recommendation.getDataCheckedAt(),
                recommendation.getTriggerType(),
                recommendation.getPolicyVersion());
    }
}

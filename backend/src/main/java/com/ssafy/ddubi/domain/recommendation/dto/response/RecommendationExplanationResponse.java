package com.ssafy.ddubi.domain.recommendation.dto.response;

import com.ssafy.ddubi.domain.recommendation.entity.Confidence;
import com.ssafy.ddubi.domain.recommendation.entity.Recommendation;
import com.ssafy.ddubi.domain.recommendation.service.ScheduleEvaluationWorkflow;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * "왜 이 시각인가" 상세.
 *
 * <p>{@code reasons} 와 {@code segmentStatusSnapshot} 은 <b>문자열 그대로</b> 내보낸다.
 * 서버가 다시 파싱해 구조를 바꾸면, 저장된 근거와 화면에 보이는 근거가 갈라질 수 있다.
 * 이 응답의 목적은 "저장된 것을 그대로 보여 주는" 것이다.
 *
 * <h3>그런데 요약은 왜 덧붙였나</h3>
 * 화면이 "검증용 추천입니다" 한 줄을 띄우려고 수백 KB짜리 Outcome JSON을 파싱해야 하면,
 * 파싱 규칙이 프론트·백엔드 양쪽에 생기고 언젠가 갈라진다. 그래서 <b>원문은 그대로 두고</b>
 * 표시에 필요한 값만 서버가 한 번 꺼내 준다. 요약은 원문에서 뽑은 것이라 둘이 어긋날 수 없다.
 *
 * <p>{@code preview} 이하가 전부 null 이면 이 정보를 남기기 전에 만들어진 추천이다.
 * 그 경우에도 조회는 실패시키지 않는다 — 원문 근거는 여전히 나간다.
 */
@Schema(description = "추천 근거 상세")
public record RecommendationExplanationResponse(

        @Schema(description = "추천 ID")
        Long recommendationId,

        @Schema(description = "권장 출발 시각")
        LocalDateTime departAt,

        @Schema(description = "예상 도착 시각")
        LocalDateTime eta,

        @Schema(description = "예상 이동시간(분)")
        Integer durationMin,

        @Schema(description = "신뢰도. 검증 전 모델이라 현재는 항상 LOW")
        Confidence confidence,

        @Schema(description = "사용한 교통 데이터의 확인 시각 — \"18:01 기준\" 표시용")
        LocalDateTime dataCheckedAt,

        @Schema(description = "추천 정책 버전")
        String policyVersion,

        @Schema(description = "검증용 추천인가. true면 화면에 검증용임을 표시해야 한다")
        Boolean preview,

        @Schema(description = "비교기 상태 원본. 현재는 PREVIEW")
        String previewStatus,

        @Schema(description = "모델 승인 여부. 현재는 항상 false")
        Boolean productionReady,

        @Schema(description = "계산 출처 — API_ONLY / ML_PREVIEW / SUBWAY_PREVIEW / MIXED_PREVIEW")
        String source,

        @Schema(description = "경로 API 시간을 그대로 쓴 step이 있었는가. 도보도 경로 API라 사실상 항상 true")
        Boolean fallbackUsed,

        @Schema(description = "데이터 부족 사유를 포함한 계산 사유 전체")
        List<String> reasonCodes,

        @Schema(description = "출처별 소요시간과 후보 비교 (JSON 문자열)")
        String reasons,

        @Schema(description = "계산에 쓴 구간별 원본 — 재현용 (JSON 문자열)")
        String segmentStatusSnapshot
) {

    /** {@code persistence} 는 저장된 근거 JSON에서 꺼낸 값이다. 예전 형식이면 null 이 온다. */
    public static RecommendationExplanationResponse from(Recommendation recommendation,
                                                         ScheduleEvaluationWorkflow.Persistence persistence) {
        return new RecommendationExplanationResponse(
                recommendation.getId(),
                recommendation.getDepartAt(),
                recommendation.getEta(),
                recommendation.getDurationMin(),
                recommendation.getConfidence(),
                recommendation.getDataCheckedAt(),
                recommendation.getPolicyVersion(),
                persistence == null ? null : persistence.preview(),
                persistence == null ? null : persistence.status(),
                persistence == null ? null : persistence.productionReady(),
                persistence == null ? null : persistence.source(),
                persistence == null ? null : persistence.fallbackUsed(),
                persistence == null ? null : persistence.reasons(),
                recommendation.getReasons(),
                recommendation.getSegmentStatusSnapshot());
    }
}

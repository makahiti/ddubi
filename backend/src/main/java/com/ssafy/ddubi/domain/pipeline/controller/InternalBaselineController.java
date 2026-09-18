package com.ssafy.ddubi.domain.pipeline.controller;

import com.ssafy.ddubi.domain.pipeline.dto.request.BaselineActivateRequest;
import com.ssafy.ddubi.domain.pipeline.dto.request.BaselineRowsRequest;
import com.ssafy.ddubi.domain.pipeline.dto.response.BaselineActivateResponse;
import com.ssafy.ddubi.domain.pipeline.dto.response.BaselineUploadResponse;
import com.ssafy.ddubi.domain.pipeline.service.BaselineIngestService;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서버1(Spark) → 서버2(Spring) 내부 API. 계약: {@code docs/contracts/internal-api.yaml}
 *
 * <h3>경로가 {@code /api/v1} 이 아닌 이유</h3>
 * nginx 가 {@code location /internal/} 을 서버1 IP 에서만 허용하도록 잡아 두었고
 * URI 를 그대로 프록시한다. 그 경로에 맞춘다. 사용자용 API 와 섞이지 않는 것도 이점이다.
 *
 * <h3>인증</h3>
 * {@code X-DDUBI-API-KEY} 헤더 ({@code InternalApiKeyFilter}). nginx 의 IP 제한과 합쳐 이중 잠금이다.
 */
@Tag(name = "internal", description = "파이프라인 전용 — 서버1 에서만 호출 가능")
@RestController
@RequestMapping("/internal/baselines")
public class InternalBaselineController {

    /** 지금은 segment 만. 다른 기준선이 생기면 여기에 추가한다. */
    private static final String SEGMENT = "segment";

    private final BaselineIngestService baselineIngestService;

    public InternalBaselineController(BaselineIngestService baselineIngestService) {
        this.baselineIngestService = baselineIngestService;
    }

    @Operation(summary = "기준선 행 업로드",
            description = """
                    청크 단위 업서트. 같은 청크를 다시 보내도 안전하다.
                    한 요청 최대 20,000행. 이 시점에는 아직 서빙되지 않는다 — activate 를 불러야 한다.
                    """)
    @PostMapping("/{baselineType}/versions/{dataVersion}/rows")
    public ApiResponse<BaselineUploadResponse> uploadRows(
            @PathVariable String baselineType,
            @PathVariable String dataVersion,
            @Valid @RequestBody BaselineRowsRequest request) {
        requireSupportedType(baselineType);
        return ApiResponse.success(baselineIngestService.upload(dataVersion, request));
    }

    @Operation(summary = "기준선 버전 활성화",
            description = """
                    그 버전을 서빙 대상으로 전환한다. 행이 0건이면 400.
                    allowShrink=true 면 급감 검사만 건너뛴다. 그때 shrinkReason 은 필수(없으면 400).
                    본문의 totalRows 와 저장된 행 수가 다르면 409 — 청크 유실을 잡는 체크섬이다.
                    직전 활성 버전의 절반 미만으로 줄어도 409.
                    직전 버전은 롤백용으로 보존하고, 48시간 지난 고아 버전은 함께 정리한다.
                    같은 버전 재활성화는 멱등하다.
                    """)
    @PostMapping("/{baselineType}/versions/{dataVersion}/activate")
    public ApiResponse<BaselineActivateResponse> activate(
            @PathVariable String baselineType,
            @PathVariable String dataVersion,
            @Valid @RequestBody BaselineActivateRequest request) {
        requireSupportedType(baselineType);
        if (request.isReasonMissing()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "allowShrink 를 켜려면 shrinkReason 이 필요합니다. 급감 검사를 끄는 것은 "
                            + "사람이 책임지고 넘기는 행위라, 왜 넘겼는지 남아야 합니다.");
        }
        return ApiResponse.success(baselineIngestService.activate(
                dataVersion, request.totalRows(), request.allowShrinkOrFalse(), request.shrinkReason()));
    }

    private void requireSupportedType(String baselineType) {
        if (!SEGMENT.equals(baselineType)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "지원하지 않는 baselineType 입니다: " + baselineType);
        }
    }
}

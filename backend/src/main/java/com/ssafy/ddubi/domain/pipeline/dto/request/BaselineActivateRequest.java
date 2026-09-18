package com.ssafy.ddubi.domain.pipeline.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.util.StringUtils;

/**
 * 활성화 요청 (internal-api.yaml activateBaselineVersion).
 *
 * <h3>왜 행 수를 같이 받나 — 체크섬이다</h3>
 * "업로드가 다 끝났다" 는 배치 쪽 판단이다. 그런데 청크 하나가 조용히 유실됐는데
 * 배치는 성공했다고 믿는 경우가 있다. 그대로 activate 하면 <b>반쪽 기준선이 서빙된다.</b>
 *
 * <p>업로드와 활성화를 나눈 것만으로는 "배치가 <b>죽는</b> 경우"만 막힌다.
 * "배치가 <b>성공했다고 착각하는</b> 경우"는 이 값이 잡는다.
 *
 * @param totalRows   배치가 보냈다고 주장하는 총 행 수. 서버에 저장된 수와 다르면 409
 * @param allowShrink 선택, 기본 false. true 면 <b>급감 검사만</b> 건너뛴다.
 *                    노선 폐지처럼 구간이 실제로 줄어드는 정상 축소를 운영자가 미리 알고
 *                    한 번만 허용하는 용도다. {@code totalRows} 대조는 그대로 한다
 * @param shrinkReason {@code allowShrink=true} 면 필수. <b>"짧은 사유 + 이슈 번호"</b> 한 줄(분산팀 합의).
 *                     로그와
 *                     {@code pipeline_config.baseline_last_shrink_reason} 에 남는다
 */
public record BaselineActivateRequest(
        @NotNull(message = "totalRows 는 필수입니다. 업로드한 총 행 수를 보내 주세요.")
        @PositiveOrZero(message = "totalRows 는 0 이상이어야 합니다.")
        Long totalRows,

        Boolean allowShrink,

        /*
         * pipeline_config.config_value 가 VARCHAR(100) 이라 그 이상은 받지 않는다.
         * DB 가 조용히 자르는 것보다 서버가 400 으로 거절하는 편이 낫다 —
         * 잘린 사유는 나중에 왜 축소를 허용했는지 설명하지 못한다.
         */
        @Size(max = MAX_REASON_LENGTH,
                message = "shrinkReason 은 " + MAX_REASON_LENGTH + "자를 넘을 수 없습니다. "
                        + "\"짧은 사유 + 이슈 번호\" 형식으로 한 줄만 적어 주세요. "
                        + "자세한 경위는 이슈에 남깁니다.")
        String shrinkReason
) {

    /** {@code pipeline_config.config_value} 컬럼 길이와 같다. */
    public static final int MAX_REASON_LENGTH = 100;

    /** 기본값 false. 계약상 선택 필드라 안 보내면 급감 검사를 그대로 한다. */
    public boolean allowShrinkOrFalse() {
        return Boolean.TRUE.equals(allowShrink);
    }

    /**
     * {@code allowShrink=true} 인데 사유가 없으면 400 (계약).
     *
     * 사유를 강제하는 이유: 급감 검사는 "배치 입력이 부실한가" 를 잡는 장치다. 그걸 끄는 것은
     * <b>사람이 책임지고 넘기는 행위</b>라, 나중에 누가 왜 넘겼는지 알 수 있어야 한다.
     */
    public boolean isReasonMissing() {
        return allowShrinkOrFalse() && !StringUtils.hasText(shrinkReason);
    }
}

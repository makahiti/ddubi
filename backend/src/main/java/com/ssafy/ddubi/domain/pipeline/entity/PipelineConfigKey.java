package com.ssafy.ddubi.domain.pipeline.entity;

/**
 * {@code pipeline_config.config_key} 로 쓰는 이름.
 *
 * 문자열을 곳곳에 흩어 놓으면 오타가 런타임에야 드러난다. 여기 한 곳에만 둔다.
 */
public final class PipelineConfigKey {

    private PipelineConfigKey() {
    }

    /** 지금 서빙 중인 기준선 버전. 추천은 이 버전의 행만 읽는다. */
    public static final String BASELINE_ACTIVE_VERSION = "baseline_active_version";

    /** 직전 활성 버전. 롤백용이자 삭제 보호 대상이다. */
    public static final String BASELINE_PREVIOUS_VERSION = "baseline_previous_version";

    /** 마지막 활성화 시각. 오래되면 추천 confidence 를 낮추고 DATA_STALE 알림을 띄운다. */
    public static final String BASELINE_ACTIVATED_AT = "baseline_activated_at";

    /**
     * 급감 검사를 마지막으로 건너뛴 사유 (allowShrink).
     *
     * 값이 남아 있다는 것은 "사람이 책임지고 넘긴 적이 있다" 는 뜻이다. config_key 가 PK 라
     * 직전 1건만 남는다 — 이력이 필요하면 로그를 본다.
     */
    public static final String BASELINE_LAST_SHRINK_REASON = "baseline_last_shrink_reason";
}

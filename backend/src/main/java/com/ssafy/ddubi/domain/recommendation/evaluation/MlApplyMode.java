package com.ssafy.ddubi.domain.recommendation.evaluation;

/**
 * 버스 ML 예측을 추천 계산에 쓰는 방식. 설정 하나(ddubi.recommendation.ml-apply-mode)로 즉시 바꿀 수 있다.
 * - SHADOW: 예측 수치와 실패 사유만 기록하고 시간 계산에는 쓰지 않는다(기본).
 * - TEST_APPLY: 테스트 환경 전용. PREVIEW 수치를 BUS step 전체 edge가 모두 숫자일 때만 시간 계산에 쓴다.
 *   결과에 시험 적용임을 남기고 productionReady는 그대로 false다(수락·운영 알림 차단 유지).
 * - PRODUCTION: 운영 계약(production_ready·training_ids_verified·feature_version·features_as_of·valid_until·
 *   input_digest)을 통과한 응답만 쓴다. 운영 승인 정책(productionReady=true 전환)은 별도 결정 사항이다.
 */
public enum MlApplyMode {
    SHADOW, TEST_APPLY, PRODUCTION;

    public static MlApplyMode parse(String value) {
        if (value == null || value.isBlank()) return SHADOW;
        try { return MlApplyMode.valueOf(value.trim().toUpperCase()); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("ML 적용 모드 설정 오류: " + value.trim()); }
    }

    /**
     * 설정값을 해석하되 TEST_APPLY는 테스트 환경이 명시적으로 허용(ddubi.recommendation.ml-test-apply-allowed=true)한
     * 경우에만 받는다. 운영 compose는 이 허용 값을 전달하지 않으므로 환경변수 하나로 시험 적용이 켜지지 않는다(기동 실패).
     */
    public static MlApplyMode resolve(String value, boolean testApplyAllowed) {
        MlApplyMode mode = parse(value);
        if (mode == TEST_APPLY && !testApplyAllowed)
            throw new IllegalStateException("ML 적용 모드 TEST_APPLY는 테스트 환경에서만 쓸 수 있다(ml-test-apply-allowed=true 필요). 운영은 SHADOW 또는 PRODUCTION.");
        return mode;
    }
}

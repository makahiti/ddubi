package com.ssafy.ddubi.domain.recommendation.evaluation;

import java.util.Set;

/**
 * 버스 이동시간 fallback 수식을 실행한다.
 *
 * <p>서울 ML 호출 실패 시에는 최근 180일 반감기 집계의 가중합/가중치 합을 사용한다. 경기·인천은 해당 구간·시간대 평균이 있으면 우선 사용하고, 결측이면 구간
 * 기준시간과 수도권 시간계수를 곱한다. 부산은 시간대 표본을 {@code alpha=0.25}만큼 구간 기준값 쪽으로 완화한다.
 *
 * <p>이 클래스는 외부 I/O나 상태를 가지지 않는다. MySQL 행에 저장된 수식 입력만 받아 초 단위 결과를 반환하므로 같은 입력은 항상 같은 값을 만든다. 입력이
 * 불완전하거나 결과가 0초 이하·1시간 초과이면 임의 보정하지 않고 실패시켜 호출자가 카카오 BUS step 시간으로 fallback하게 한다.
 */
public final class BusTravelTimeFormula {
    public static final String SEOUL_HALF_LIFE = "SEOUL_HALF_LIFE_180D";
    public static final String METROPOLITAN_GTFS = "METROPOLITAN_GTFS_TIME_BUCKET";
    public static final String BUSAN_GTFS = "BUSAN_GTFS_SHRINKAGE";
    public static final double BUSAN_ALPHA = 0.25;
    private static final Set<String> METROPOLITAN = Set.of("GBIS", "INCHEON_PUBLIC_CSV");

    private BusTravelTimeFormula() {}

    /**
     * 수식별 충분통계다. 사용하지 않는 필드는 null로 둔다.
     *
     * @param formula 적용할 수식 버전
     * @param weightedSeconds 서울의 {@code sum(weight * dailyMeanSeconds)}
     * @param weightTotal 서울의 {@code sum(weight)}
     * @param localMeanSeconds 해당 구간·시간대 GTFS 평균 {@code L(s,h)}
     * @param localSampleCount 해당 구간·시간대 GTFS 운행편 수 {@code n(s,h)}
     * @param segmentMeanSeconds 해당 구간의 시간대 동일가중 기준값 {@code B(s)}
     * @param hourFactor 지역 시간대 계수 {@code k(h)}
     * @param alpha 부산 완화계수. 현재 계약값은 0.25만 허용한다.
     */
    public record Input(
            String formula,
            Double weightedSeconds,
            Double weightTotal,
            Double localMeanSeconds,
            Long localSampleCount,
            Double segmentMeanSeconds,
            Double hourFactor,
            Double alpha) {}

    /** 지역과 수식 버전을 함께 검증한 뒤 초 단위 이동시간을 계산한다. */
    public static double seconds(String namespace, Input input) {
        if (namespace == null || input == null || input.formula() == null)
            throw new IllegalArgumentException("BUS_FORMULA_INPUT_MISSING");
        double result;
        switch (input.formula()) {
            case SEOUL_HALF_LIFE -> {
                if (!"SEOUL_BUS".equals(namespace))
                    throw new IllegalArgumentException("BUS_FORMULA_NAMESPACE_MISMATCH");
                result = positive(input.weightedSeconds()) / positive(input.weightTotal());
            }
            case METROPOLITAN_GTFS -> {
                if (!METROPOLITAN.contains(namespace))
                    throw new IllegalArgumentException("BUS_FORMULA_NAMESPACE_MISMATCH");
                long count = count(input.localSampleCount());
                result =
                        count > 0
                                ? positive(input.localMeanSeconds())
                                : positive(input.segmentMeanSeconds())
                                        * positive(input.hourFactor());
            }
            case BUSAN_GTFS -> {
                if (!"BUSAN_GTFS".equals(namespace))
                    throw new IllegalArgumentException("BUS_FORMULA_NAMESPACE_MISMATCH");
                long count = count(input.localSampleCount());
                double alpha = positive(input.alpha());
                if (Math.abs(alpha - BUSAN_ALPHA) > 1e-12)
                    throw new IllegalArgumentException("BUS_FORMULA_ALPHA_MISMATCH");
                double local = count == 0 ? 0.0 : positive(input.localMeanSeconds());
                result =
                        (count * local
                                        + alpha
                                                * positive(input.segmentMeanSeconds())
                                                * positive(input.hourFactor()))
                                / (count + alpha);
            }
            default -> throw new IllegalArgumentException("BUS_FORMULA_VERSION_UNSUPPORTED");
        }
        if (!Double.isFinite(result) || result <= 0 || result > 3600)
            throw new IllegalArgumentException("BUS_FORMULA_RESULT_INVALID");
        return result;
    }

    private static double positive(Double value) {
        if (value == null || !Double.isFinite(value) || value <= 0)
            throw new IllegalArgumentException("BUS_FORMULA_VALUE_INVALID");
        return value;
    }

    private static long count(Long value) {
        if (value == null || value < 0)
            throw new IllegalArgumentException("BUS_FORMULA_SAMPLE_COUNT_INVALID");
        return value;
    }
}

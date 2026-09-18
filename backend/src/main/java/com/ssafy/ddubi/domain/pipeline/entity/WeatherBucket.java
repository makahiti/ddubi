package com.ssafy.ddubi.domain.pipeline.entity;

/**
 * 기준선을 나누는 날씨 구간. {@code baseline_serving.weather_bucket} ENUM 과 1:1 이다.
 *
 * 비 오는 날은 평소보다 느리다는 것이 기준선의 전제라, 같은 구간·같은 시간대라도
 * 이 값이 다르면 다른 행이다(UK_BASELINE_KEY 에 포함).
 */
public enum WeatherBucket {
    DRY,
    RAIN,
    SNOW
}

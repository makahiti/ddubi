package com.ssafy.ddubi.domain.pipeline.entity;

import com.ssafy.ddubi.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 구간별 평소 이동시간 (Spark 배치 산출물).
 *
 * <p>"이 구간을 수요일 8시에 비 오는 날 지나가면 보통 몇 초 걸리나" 를 담는다.
 * 추천이 실시간 상태({@code segment:status})와 이 값을 비교해 "평소보다 얼마나 느린가" 를 판단한다.
 *
 * <h3>단위 주의</h3>
 * <b>p50/p80/p95 는 초(second)</b>다. {@code route}·{@code recommendation} 의 duration 은 분이라
 * 섞으면 60배 틀린다. 계약 문서({@code docs/contracts/redis-keys.yaml} units 절)에 못 박혀 있다.
 *
 * <h3>요일 주의</h3>
 * {@code dow} 는 <b>일=1, 토=7</b>(Spark dayofweek 기준)이다. 자바 {@code LocalDate} 는 월=1 이라
 * 변환이 필요하다 — {@code BaselineDayOfWeek} 가 그 일을 한다.
 *
 * <h3>왜 JPA 로 쓰지 않나</h3>
 * 이 엔티티는 <b>읽기용</b>이다. 적재는 수십만 행이라 {@code BaselineIngestService} 가
 * JdbcTemplate 배치 업서트로 한다. 엔티티 하나씩 저장하면 영속성 컨텍스트가 터진다.
 */
@Entity
@Table(name = "baseline_serving")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BaselineServing extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "segment_id", nullable = false, length = 180)
    private String segmentId;

    /** 요일 1~7. <b>일=1, 토=7</b> (Spark dayofweek 기준). */
    @Column(name = "dow", nullable = false)
    private Byte dow;

    /** 시간대 0~23. */
    @Column(name = "hour_slot", nullable = false)
    private Byte hourSlot;

    @Enumerated(EnumType.STRING)
    @Column(name = "weather_bucket", nullable = false)
    private WeatherBucket weatherBucket;

    /** 단위: 초. */
    @Column(name = "p50", nullable = false)
    private Integer p50;

    /** 단위: 초. */
    @Column(name = "p80", nullable = false)
    private Integer p80;

    /** 단위: 초. */
    @Column(name = "p95", nullable = false)
    private Integer p95;

    /** 표본 수. 적으면 추천이 신뢰도를 낮춘다. */
    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount;

    /** 배치 버전. 활성 버전은 {@code pipeline_config} 에서 조회한다. */
    @Column(name = "data_version", nullable = false, length = 20)
    private String dataVersion;
}

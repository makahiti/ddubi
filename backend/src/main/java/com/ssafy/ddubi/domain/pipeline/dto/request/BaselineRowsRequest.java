package com.ssafy.ddubi.domain.pipeline.dto.request;

import com.ssafy.ddubi.domain.pipeline.entity.WeatherBucket;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 기준선 행 묶음 업로드 (internal-api.yaml uploadBaselineRows).
 *
 * <p>Spark 배치가 결과를 청크로 나눠 여러 번 부른다. 한 청크가 실패하면 그 청크만
 * 다시 보내면 되도록 <b>업서트</b>로 처리한다.
 *
 * <p>행 수에 상한을 두는 이유: nginx {@code client_max_body_size} 가 20MB 이고
 * {@code proxy_read_timeout} 이 기본 60초다. 청크가 지나치게 크면 nginx 가 먼저 끊어
 * 502 를 내는데, 그러면 Spark 는 성공 여부를 모른 채 재시도하게 된다.
 * 서버가 먼저 400 으로 거절하면 원인이 분명해진다.
 */
public record BaselineRowsRequest(
        @NotEmpty(message = "rows 는 비어 있을 수 없습니다.")
        @Size(max = MAX_ROWS_PER_CHUNK,
                message = "한 번에 보낼 수 있는 행은 " + MAX_ROWS_PER_CHUNK + "개까지입니다. 청크를 나눠 주세요.")
        @Valid
        List<Row> rows
) {

    /** 한 요청에 담을 수 있는 최대 행 수. */
    public static final int MAX_ROWS_PER_CHUNK = 20_000;

    /**
     * @param dow         요일 1~7. <b>일=1, 토=7</b> (Spark dayofweek 기준)
     * @param hourSlot    시간대 0~23
     * @param p50         단위 <b>초</b>. route·recommendation 의 분과 혼동 금지
     * @param sampleCount 표본 수. 0 도 허용한다 — 데이터가 없었다는 것도 사실이다
     */
    public record Row(
            @NotBlank(message = "segmentId 는 필수입니다.")
            @Size(max = 180, message = "segmentId 는 180자를 넘을 수 없습니다.")
            @Pattern(regexp = "^(BUS|SUB):[A-Za-z0-9._-]{1,50}:[A-Za-z0-9._-]{1,50}:[A-Za-z0-9._-]{1,50}$",
                    message = "segmentId 형식이 올바르지 않습니다. BUS:{routeId}:{fromId}:{toId} 또는 SUB:{...}")
            String segmentId,

            @NotNull(message = "dow 는 필수입니다.")
            @Min(value = 1, message = "dow 는 1~7 입니다. 일=1, 토=7")
            @Max(value = 7, message = "dow 는 1~7 입니다. 일=1, 토=7")
            Integer dow,

            @NotNull(message = "hourSlot 은 필수입니다.")
            @Min(value = 0, message = "hourSlot 은 0~23 입니다.")
            @Max(value = 23, message = "hourSlot 은 0~23 입니다.")
            Integer hourSlot,

            @NotNull(message = "weatherBucket 은 필수입니다.")
            WeatherBucket weatherBucket,

            @NotNull(message = "p50 은 필수입니다.")
            @PositiveOrZero(message = "p50 은 0 이상이어야 합니다.")
            Integer p50,

            @NotNull(message = "p80 은 필수입니다.")
            @PositiveOrZero(message = "p80 은 0 이상이어야 합니다.")
            Integer p80,

            @NotNull(message = "p95 는 필수입니다.")
            @PositiveOrZero(message = "p95 는 0 이상이어야 합니다.")
            Integer p95,

            @NotNull(message = "sampleCount 는 필수입니다.")
            @PositiveOrZero(message = "sampleCount 는 0 이상이어야 합니다.")
            Integer sampleCount
    ) {

        /**
         * 백분위는 커지는 순서여야 한다.
         *
         * 뒤집힌 값이 들어오면 추천이 "여유 있게 잡을수록 빨라진다" 는 결론을 내게 된다.
         * Spark 쪽 계산 실수를 여기서 잡는 것이 나중에 이상한 추천을 디버깅하는 것보다 싸다.
         */
        public boolean isPercentileOrdered() {
            return p50 <= p80 && p80 <= p95;
        }
    }
}

package com.ssafy.ddubi.domain.pipeline.service;

import com.ssafy.ddubi.domain.pipeline.dto.request.BaselineActivateRequest;
import com.ssafy.ddubi.domain.pipeline.dto.request.BaselineRowsRequest;
import com.ssafy.ddubi.domain.pipeline.dto.response.BaselineActivateResponse;
import com.ssafy.ddubi.domain.pipeline.dto.response.BaselineUploadResponse;
import com.ssafy.ddubi.domain.pipeline.entity.PipelineConfig;
import com.ssafy.ddubi.domain.pipeline.entity.PipelineConfigKey;
import com.ssafy.ddubi.domain.pipeline.entity.WeatherBucket;
import com.ssafy.ddubi.domain.pipeline.repository.BaselineServingRepository;
import com.ssafy.ddubi.domain.pipeline.repository.PipelineConfigRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기준선 적재·활성화 (internal-api.yaml).
 *
 * 고정하려는 것:
 * <ul>
 *   <li>청크 재전송이 안전할 것 (업서트) — 재시도가 성립하는 근거</li>
 *   <li>activate 전에는 서빙 버전이 바뀌지 않을 것 — 절반만 올라간 데이터가 나가지 않는다</li>
 *   <li>활성·직전 버전은 정리에서 보호될 것 — 롤백 여지</li>
 *   <li>백분위 역전·행 0건 활성화를 거절할 것</li>
 * </ul>
 */
class BaselineIngestIntegrationTest extends IntegrationTestSupport {

    private static final String SEGMENT_A = "BUS:100100609:123456:123789";
    private static final String SEGMENT_B = "SUB:2:0222:0223";

    @Autowired
    private BaselineIngestService service;
    @Autowired
    private BaselineServingRepository baselineRepository;
    @Autowired
    private PipelineConfigRepository configRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private jakarta.validation.Validator validator;

    private static BaselineRowsRequest.Row row(String segmentId, int hour, int p50) {
        return new BaselineRowsRequest.Row(segmentId, 3, hour, WeatherBucket.DRY,
                p50, p50 + 60, p50 + 120, 42);
    }

    private static BaselineRowsRequest rows(BaselineRowsRequest.Row... rows) {
        return new BaselineRowsRequest(List.of(rows));
    }

    private String activeVersion() {
        return configRepository.findById(PipelineConfigKey.BASELINE_ACTIVE_VERSION)
                .map(PipelineConfig::getConfigValue).orElse(null);
    }

    // ------------------------------------------------------------------
    // 업로드
    // ------------------------------------------------------------------

    @Test
    @DisplayName("행을 올리면 그 버전에 쌓이고, 아직 서빙 버전은 바뀌지 않는다")
    void uploadDoesNotActivate() {
        BaselineUploadResponse response = service.upload("2026-09-01",
                rows(row(SEGMENT_A, 8, 1200), row(SEGMENT_B, 8, 600)));

        assertThat(response.received()).isEqualTo(2);
        assertThat(response.versionTotal()).isEqualTo(2);
        assertThat(activeVersion()).isNull();   // activate 전이므로 서빙 대상이 없다
    }

    @Test
    @DisplayName("같은 청크를 다시 보내도 행이 늘지 않고 값만 갱신된다 — 청크 재시도의 근거")
    void reuploadUpserts() {
        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200)));
        BaselineUploadResponse second = service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1500)));

        assertThat(second.versionTotal()).isEqualTo(1);
        Integer p50 = jdbcTemplate.queryForObject(
                "select p50 from baseline_serving where segment_id = ? and data_version = ?",
                Integer.class, SEGMENT_A, "2026-09-01");
        assertThat(p50).isEqualTo(1500);
    }

    @Test
    @DisplayName("서빙 중인 버전에는 덮어쓸 수 없다 — 추천이 요청마다 다른 값을 보게 된다")
    void cannotOverwriteActiveVersion() {
        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200)));
        service.activate("2026-09-01", 1, false, null);

        assertThatThrownBy(() -> service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 999))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("백분위가 뒤집히면 400 — 여유를 크게 잡을수록 빨라진다는 결론이 나온다")
    void rejectsUnorderedPercentiles() {
        BaselineRowsRequest bad = new BaselineRowsRequest(List.of(
                new BaselineRowsRequest.Row(SEGMENT_A, 3, 8, WeatherBucket.DRY, 900, 600, 1200, 10)));

        assertThatThrownBy(() -> service.upload("2026-09-01", bad))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("dataVersion 이 20자를 넘으면 400 — 컬럼이 VARCHAR(20) 이라 조용히 잘린다")
    void rejectsTooLongVersion() {
        assertThatThrownBy(() -> service.upload("x".repeat(21), rows(row(SEGMENT_A, 8, 1200))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    // ------------------------------------------------------------------
    // 활성화
    // ------------------------------------------------------------------

    @Test
    @DisplayName("활성화하면 서빙 버전이 바뀌고 직전 버전이 기록된다")
    void activateSwitchesVersion() {
        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200)));
        service.activate("2026-09-01", 1, false, null);

        service.upload("2026-09-02", rows(row(SEGMENT_A, 8, 1300)));
        BaselineActivateResponse response = service.activate("2026-09-02", 1, false, null);

        assertThat(response.activeVersion()).isEqualTo("2026-09-02");
        assertThat(response.previousVersion()).isEqualTo("2026-09-01");
        assertThat(response.rowCount()).isEqualTo(1);
        assertThat(activeVersion()).isEqualTo("2026-09-02");
    }

    @Test
    @DisplayName("행이 0건인 버전은 활성화할 수 없다 — 추천이 기준선을 통째로 잃는다")
    void cannotActivateEmptyVersion() {
        assertThatThrownBy(() -> service.activate("2026-09-09", 0, false, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("같은 버전 재활성화는 멱등 — 배치 재시도가 여기서 실패하면 안 된다")
    void reactivateIsIdempotent() {
        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200)));
        service.activate("2026-09-01", 1, false, null);

        BaselineActivateResponse again = service.activate("2026-09-01", 1, false, null);

        assertThat(again.activeVersion()).isEqualTo("2026-09-01");
        assertThat(again.purgedVersions()).isEmpty();
        assertThat(activeVersion()).isEqualTo("2026-09-01");
    }

    @Test
    @DisplayName("활성·직전 버전은 정리에서 보호된다 — 롤백할 곳이 남아야 한다")
    void activeAndPreviousSurvivePurge() {
        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200)));
        service.activate("2026-09-01", 1, false, null);
        service.upload("2026-09-02", rows(row(SEGMENT_A, 8, 1300)));
        service.activate("2026-09-02", 1, false, null);

        assertThat(baselineRepository.countByDataVersion("2026-09-01")).isEqualTo(1);
        assertThat(baselineRepository.countByDataVersion("2026-09-02")).isEqualTo(1);
    }

    @Test
    @DisplayName("48시간이 안 지난 고아 버전은 지우지 않는다 — 활성화를 기다리는 중일 수 있다")
    void freshOrphanIsKept() {
        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200)));
        service.upload("2026-08-31", rows(row(SEGMENT_B, 8, 700)));   // 방금 올린 고아
        service.activate("2026-09-01", 1, false, null);

        assertThat(baselineRepository.countByDataVersion("2026-08-31")).isEqualTo(1);
    }

    @Test
    @DisplayName("48시간 지난 고아 버전은 활성화할 때 함께 지운다")
    void staleOrphanIsPurged() {
        service.upload("2026-08-01", rows(row(SEGMENT_B, 8, 700)));   // 실패한 배치가 남긴 찌꺼기
        // created_at 을 과거로 밀어 48시간 경과를 만든다. 시간을 기다릴 수는 없다.
        jdbcTemplate.update(
                "update baseline_serving set created_at = created_at - interval 5 day where data_version = ?",
                "2026-08-01");

        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200)));
        BaselineActivateResponse response = service.activate("2026-09-01", 1, false, null);

        assertThat(response.purgedVersions()).containsExactly("2026-08-01");
        assertThat(baselineRepository.countByDataVersion("2026-08-01")).isZero();
    }

    @Test
    @DisplayName("활성화 시각이 기록된다 — 기준선이 낡았는지 판단하는 기준이다")
    void activationTimeIsRecorded() {
        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200)));
        service.activate("2026-09-01", 1, false, null);

        assertThat(configRepository.findById(PipelineConfigKey.BASELINE_ACTIVATED_AT))
                .isPresent();
    }

    // ------------------------------------------------------------------
    // 청크 상한 경계값
    // ------------------------------------------------------------------

    @Test
    @DisplayName("정확히 상한(20,000행)은 통과한다 — 경계에서 막히면 분산팀이 청크를 못 채운다")
    void acceptsExactlyMaxRows() {
        BaselineRowsRequest exact = generate(BaselineRowsRequest.MAX_ROWS_PER_CHUNK);

        BaselineUploadResponse response = service.upload("2026-09-10", exact);

        assertThat(response.received()).isEqualTo(BaselineRowsRequest.MAX_ROWS_PER_CHUNK);
        assertThat(response.versionTotal()).isEqualTo(BaselineRowsRequest.MAX_ROWS_PER_CHUNK);
    }

    @Test
    @DisplayName("상한을 1행 넘기면 @Size 로 걸러진다 — nginx 가 끊기 전에 서버가 이유를 알려준다")
    void rejectsOneRowOverMax() {
        BaselineRowsRequest tooMany = generate(BaselineRowsRequest.MAX_ROWS_PER_CHUNK + 1);

        var violations = validator.validate(tooMany);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).contains("청크를 나눠");
    }

    /** 서로 다른 키를 갖는 유효한 행 n개. 업서트로 합쳐지지 않게 조합을 흩어 놓는다. */
    private static BaselineRowsRequest generate(int n) {
        List<BaselineRowsRequest.Row> rows = new java.util.ArrayList<>(n);
        WeatherBucket[] buckets = WeatherBucket.values();
        for (int i = 0; i < n; i++) {
            rows.add(new BaselineRowsRequest.Row(
                    "BUS:" + (100100000 + i / 504) + ":" + (100000 + i) + ":" + (200000 + i),
                    (i % 7) + 1, i % 24, buckets[i % buckets.length],
                    300, 420, 600, 10));
        }
        return new BaselineRowsRequest(rows);
    }

    // ------------------------------------------------------------------
    // 행 수 대조 · 급감 검사 (2026-09-01 합의)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("totalRows 가 저장된 수와 다르면 409 — 청크가 조용히 유실된 경우를 잡는다")
    void rejectsRowCountMismatch() {
        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200), row(SEGMENT_B, 8, 600)));

        // 배치는 3행을 보냈다고 믿지만 실제로 저장된 것은 2행
        assertThatThrownBy(() -> service.activate("2026-09-01", 3, false, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_STATE);

        assertThat(activeVersion()).isNull();   // 실패했으면 서빙 버전이 바뀌면 안 된다
    }

    @Test
    @DisplayName("행 수가 맞으면 통과한다")
    void acceptsMatchingRowCount() {
        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200), row(SEGMENT_B, 8, 600)));

        service.activate("2026-09-01", 2, false, null);

        assertThat(activeVersion()).isEqualTo("2026-09-01");
    }

    @Test
    @DisplayName("직전 활성 버전의 절반 미만이면 409 — 배치 입력이 부실했을 수 있다")
    void rejectsSharpDrop() {
        // 활성 버전 4행
        service.upload("2026-09-01", rows(
                row(SEGMENT_A, 8, 1200), row(SEGMENT_A, 9, 1300),
                row(SEGMENT_B, 8, 600), row(SEGMENT_B, 9, 650)));
        service.activate("2026-09-01", 4, false, null);

        // 새 버전 1행 — 절반(2) 미만
        service.upload("2026-09-02", rows(row(SEGMENT_A, 8, 1250)));

        assertThatThrownBy(() -> service.activate("2026-09-02", 1, false, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_STATE);

        assertThat(activeVersion()).isEqualTo("2026-09-01");   // 활성 버전은 그대로
    }

    @Test
    @DisplayName("절반 이상이면 줄어들어도 통과한다 — 경계는 '절반 미만'이다")
    void acceptsModerateDecrease() {
        service.upload("2026-09-01", rows(
                row(SEGMENT_A, 8, 1200), row(SEGMENT_A, 9, 1300),
                row(SEGMENT_B, 8, 600), row(SEGMENT_B, 9, 650)));
        service.activate("2026-09-01", 4, false, null);

        // 4행 → 2행. 절반이므로 "절반 미만"이 아니다
        service.upload("2026-09-02", rows(row(SEGMENT_A, 8, 1250), row(SEGMENT_B, 8, 620)));
        service.activate("2026-09-02", 2, false, null);

        assertThat(activeVersion()).isEqualTo("2026-09-02");
    }

    @Test
    @DisplayName("첫 활성화는 비교 대상이 없으므로 급감 검사를 건너뛴다")
    void firstActivationSkipsDropCheck() {
        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200)));

        service.activate("2026-09-01", 1, false, null);

        assertThat(activeVersion()).isEqualTo("2026-09-01");
    }

    // ------------------------------------------------------------------
    // allowShrink — 사람이 책임지고 넘기는 문 (2026-09-08 계약)
    // ------------------------------------------------------------------

    /** 활성 4행 → 새 버전 1행. 절반 미만이라 기본값이면 막힌다. */
    private void givenSharpDropSetup() {
        service.upload("2026-09-01", rows(
                row(SEGMENT_A, 8, 1200), row(SEGMENT_A, 9, 1300),
                row(SEGMENT_B, 8, 600), row(SEGMENT_B, 9, 650)));
        service.activate("2026-09-01", 4, false, null);
        service.upload("2026-09-02", rows(row(SEGMENT_A, 8, 1250)));
    }

    @Test
    @DisplayName("allowShrink=true 면 급감이어도 활성화된다 — 노선 폐지 같은 정상 축소")
    void allowShrinkBypassesDropCheck() {
        givenSharpDropSetup();

        service.activate("2026-09-02", 1, true, "9월 개편으로 간선 노선 12개 폐지 S15P21E202-64");

        assertThat(activeVersion()).isEqualTo("2026-09-02");
    }

    @Test
    @DisplayName("건너뛴 사유가 pipeline_config 에 남는다 — 나중에 왜 넘겼는지 알아야 한다")
    void shrinkReasonIsRecorded() {
        givenSharpDropSetup();

        service.activate("2026-09-02", 1, true, "9월 개편으로 간선 노선 12개 폐지 S15P21E202-64");

        assertThat(configRepository.findById(PipelineConfigKey.BASELINE_LAST_SHRINK_REASON))
                .map(PipelineConfig::getConfigValue)
                .hasValue("9월 개편으로 간선 노선 12개 폐지 S15P21E202-64");
    }

    @Test
    @DisplayName("allowShrink 여도 totalRows 대조는 그대로 — 청크 유실은 여전히 잡는다")
    void allowShrinkDoesNotSkipRowCountCheck() {
        givenSharpDropSetup();

        assertThatThrownBy(() -> service.activate("2026-09-02", 99, true, "정상 축소"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_STATE);

        assertThat(activeVersion()).isEqualTo("2026-09-01");
    }

    @Test
    @DisplayName("급감이 없었으면 사유를 기록하지 않는다 — 매일 덮어쓰면 정작 필요할 때 못 찾는다")
    void reasonNotRecordedWhenNoDrop() {
        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200), row(SEGMENT_B, 8, 600)));
        service.activate("2026-09-01", 2, false, null);
        service.upload("2026-09-02", rows(row(SEGMENT_A, 8, 1250), row(SEGMENT_B, 8, 620)));

        // 플래그는 켜져 있지만 줄지 않았다
        service.activate("2026-09-02", 2, true, "켜두기만 한 사유");

        assertThat(configRepository.findById(PipelineConfigKey.BASELINE_LAST_SHRINK_REASON))
                .isEmpty();
    }

    @Test
    @DisplayName("allowShrink=true 인데 사유가 없으면 400 — DTO 단계에서 걸린다")
    void reasonIsRequiredWhenAllowShrink() {
        assertThat(new BaselineActivateRequest(1L, true, null).isReasonMissing()).isTrue();
        assertThat(new BaselineActivateRequest(1L, true, "  ").isReasonMissing()).isTrue();
        assertThat(new BaselineActivateRequest(1L, true, "사유").isReasonMissing()).isFalse();
        assertThat(new BaselineActivateRequest(1L, false, null).isReasonMissing()).isFalse();
    }

    @Test
    @DisplayName("shrinkReason 이 100자를 넘으면 걸러진다 — config_value 가 VARCHAR(100)")
    void reasonLengthIsLimited() {
        String tooLong = "가".repeat(BaselineActivateRequest.MAX_REASON_LENGTH + 1);

        var violations = validator.validate(new BaselineActivateRequest(1L, true, tooLong));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).contains("100자");
    }
}

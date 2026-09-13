package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.pipeline.entity.BaselineServing;
import com.ssafy.ddubi.domain.pipeline.repository.BaselineServingRepository;
import com.ssafy.ddubi.domain.recommendation.entity.Confidence;
import com.ssafy.ddubi.domain.recommendation.port.BusSegmentPredictionRequest;
import com.ssafy.ddubi.domain.recommendation.port.BusTravelTimePrediction;
import com.ssafy.ddubi.domain.recommendation.port.BusTravelTimePredictor;
import com.ssafy.ddubi.domain.recommendation.port.SegmentStateReader;
import com.ssafy.ddubi.domain.recommendation.port.SegmentStatus;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 소요시간 산정기. 고정하려는 것:
 *
 * <ul>
 *   <li>3단 폴백이 순서대로 내려갈 것 (예측 → 기준선 → 경로 API)</li>
 *   <li><b>출처를 step 단위로 고를 것</b> — 한 step 안에서 edge 별로 섞지 않는다</li>
 *   <li><b>균등 분배의 합이 원본 step 시간과 정확히 같을 것</b> — 반올림으로 시간이 새면
 *       출발이 앞당겨지고 그만큼 사용자가 늦는다</li>
 *   <li>배분값이 관측과 구분될 것 (출처·방식이 남는다)</li>
 *   <li>실시간 보정은 유효기간 안의 구간에만 붙을 것</li>
 *   <li>진입 시각이 앞 구간들의 소요시간만큼 밀릴 것</li>
 *   <li>예측 호출이 구간 수와 무관하게 한 번일 것</li>
 *   <li>Redis·예측 서비스가 죽어도 결과가 나올 것</li>
 * </ul>
 */
class TravelTimeEstimatorTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String EDGE_1 = "BUS:100100609:123456:123789";
    private static final String EDGE_2 = "BUS:100100609:123789:123900";
    private static final String EDGE_3 = "BUS:100100609:123900:124011";
    private static final String VERSION = "2026-09-01";

    /** 2026-09-10(목) 08:00 KST. 요일 4, 시간대 8. */
    private static final Instant DEPART_AT =
            LocalDateTime.of(2026, 9, 10, 8, 0).atZone(SEOUL).toInstant();

    private FakePredictor predictor;
    private FakeStateReader stateReader;
    private BaselineServingRepository baselineRepository;
    private BaselineVersionProvider versionProvider;

    @BeforeEach
    void setUp() {
        predictor = new FakePredictor();
        stateReader = new FakeStateReader();
        baselineRepository = mock(BaselineServingRepository.class);
        versionProvider = mock(BaselineVersionProvider.class);
        when(versionProvider.activeVersion()).thenReturn(VERSION);
        givenBaselines();
    }

    private TravelTimeEstimator estimator() {
        return new TravelTimeEstimator(predictor, stateReader, baselineRepository, versionProvider);
    }

    // ------------------------------------------------------------------
    // 3단 폴백
    // ------------------------------------------------------------------

    @Test
    @DisplayName("예측이 있으면 예측을 쓰고 신뢰도는 HIGH")
    void usesPredictionWhenAvailable() {
        predictor.predicted(EDGE_1, 600);

        TravelTimeEstimate result = estimator().estimate(List.of(busStep(1, 900, EDGE_1)), DEPART_AT);

        assertThat(result.segments()).singleElement().satisfies(segment -> {
            assertThat(segment.source()).isEqualTo(EstimateSource.PREDICTION);
            assertThat(segment.baseSeconds()).isEqualTo(600);
        });
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(result.fallbackUsed()).isFalse();
    }

    @Test
    @DisplayName("예측 불가면 기준선으로 내려가고 신뢰도는 MID")
    void fallsBackToBaseline() {
        predictor.unavailable(EDGE_1, "모델 입력 없음");
        givenBaselines(baseline(EDGE_1, (byte) 4, (byte) 8, 700));

        TravelTimeEstimate result = estimator().estimate(List.of(busStep(1, 900, EDGE_1)), DEPART_AT);

        assertThat(result.segments()).singleElement().satisfies(segment -> {
            assertThat(segment.source()).isEqualTo(EstimateSource.BASELINE);
            assertThat(segment.baseSeconds()).isEqualTo(700);
        });
        assertThat(result.confidence()).isEqualTo(Confidence.MID);
        assertThat(result.fallbackUsed()).isTrue();
    }

    @Test
    @DisplayName("기준선도 없으면 경로 API 시간을 쓰고 신뢰도는 LOW — 지금 실제 상태다")
    void fallsBackToRouteApi() {
        predictor.unavailable(EDGE_1, "예측 서비스가 연결되지 않았습니다.");

        TravelTimeEstimate result = estimator().estimate(List.of(busStep(1, 900, EDGE_1)), DEPART_AT);

        assertThat(result.segments()).singleElement().satisfies(segment -> {
            assertThat(segment.source()).isEqualTo(EstimateSource.ROUTE_API_SPLIT);
            assertThat(segment.baseSeconds()).isEqualTo(900);
        });
        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    @DisplayName("폴백한 이유가 결과에 남는다 — 조용히 내려가면 나중에 설명할 수 없다")
    void recordsWhyItFellBack() {
        predictor.unavailable(EDGE_1, "모델 입력 없음");

        TravelTimeEstimate result = estimator().estimate(List.of(busStep(1, 900, EDGE_1)), DEPART_AT);

        assertThat(result.segments().get(0).note())
                .contains("모델 입력 없음")
                .contains("기준선 없음");
    }

    @Test
    @DisplayName("도보는 항상 경로 API 이고 신뢰도 판정에 끼지 않는다")
    void walkDoesNotDragConfidenceDown() {
        predictor.predicted(EDGE_1, 600);

        TravelTimeEstimate result = estimator().estimate(
                List.of(walkStep(1, 300), busStep(2, 900, EDGE_1)), DEPART_AT);

        assertThat(result.segments().get(0).source()).isEqualTo(EstimateSource.ROUTE_API);
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);   // 도보 때문에 LOW 가 되면 안 된다
        assertThat(result.fallbackUsed()).isFalse();
    }

    @Test
    @DisplayName("가장 약한 step 이 전체 신뢰도를 정한다")
    void weakestStepDecidesConfidence() {
        predictor.predicted(EDGE_1, 600);
        predictor.unavailable(EDGE_2, "학습 범위 밖");

        TravelTimeEstimate result = estimator().estimate(
                List.of(busStep(1, 900, EDGE_1), busStep(2, 800, EDGE_2)), DEPART_AT);

        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
    }

    // ------------------------------------------------------------------
    // 부분 혼합 금지 (분산팀 조건 3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("한 edge 만 예측이 없으면 step 전체가 폴백한다 — 부분 혼합 금지")
    void oneMissingPredictionMakesWholeStepFallBack() {
        predictor.predicted(EDGE_1, 300);
        predictor.predicted(EDGE_2, 300);
        predictor.unavailable(EDGE_3, "학습 범위 밖");

        TravelTimeEstimate result = estimator().estimate(
                List.of(busStep(1, 900, EDGE_1, EDGE_2, EDGE_3)), DEPART_AT);

        assertThat(result.segments())
                .as("예측된 두 edge 까지 폴백해야 한다. 섞으면 합의 근거가 사라진다")
                .allSatisfy(segment ->
                        assertThat(segment.source()).isEqualTo(EstimateSource.ROUTE_API_SPLIT));
    }

    @Test
    @DisplayName("한 edge 만 기준선이 없으면 step 전체가 경로 API 로 내려간다")
    void oneMissingBaselineMakesWholeStepFallBack() {
        predictor.unavailable(EDGE_1, "예측 없음");
        predictor.unavailable(EDGE_2, "예측 없음");
        givenBaselines(baseline(EDGE_1, (byte) 4, (byte) 8, 400));   // EDGE_2 는 없다

        TravelTimeEstimate result = estimator().estimate(
                List.of(busStep(1, 900, EDGE_1, EDGE_2)), DEPART_AT);

        assertThat(result.segments()).allSatisfy(segment ->
                assertThat(segment.source()).isEqualTo(EstimateSource.ROUTE_API_SPLIT));
    }

    @Test
    @DisplayName("edge 가 전부 예측되면 step 전체가 예측을 쓴다")
    void allEdgesPredictedUsesPrediction() {
        predictor.predicted(EDGE_1, 300);
        predictor.predicted(EDGE_2, 400);

        TravelTimeEstimate result = estimator().estimate(
                List.of(busStep(1, 900, EDGE_1, EDGE_2)), DEPART_AT);

        assertThat(result.segments()).extracting(SegmentEstimate::baseSeconds)
                .containsExactly(300L, 400L);
        assertThat(result.totalSeconds()).isEqualTo(700);
    }

    // ------------------------------------------------------------------
    // 균등 분배 (분산팀 조건 1·2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("나누어떨어지지 않아도 합이 원본 step 시간과 정확히 같다")
    void splitSumEqualsOriginalExactly() {
        predictor.unavailable(EDGE_1, "예측 없음");
        predictor.unavailable(EDGE_2, "예측 없음");
        predictor.unavailable(EDGE_3, "예측 없음");

        // 901 / 3 = 300.33... — 그냥 나누면 900 이 되어 1초가 사라진다
        TravelTimeEstimate result = estimator().estimate(
                List.of(busStep(1, 901, EDGE_1, EDGE_2, EDGE_3)), DEPART_AT);

        assertThat(result.segments()).extracting(SegmentEstimate::baseSeconds)
                .containsExactly(301L, 300L, 300L);
        assertThat(result.totalSeconds())
                .as("반올림으로 시간이 새면 출발이 앞당겨지고 그만큼 사용자가 늦는다")
                .isEqualTo(901);
    }

    @Test
    @DisplayName("배분값은 출처로 구분되고 관측이 아님을 스스로 밝힌다")
    void splitValuesAreMarkedSynthetic() {
        predictor.unavailable(EDGE_1, "예측 없음");
        predictor.unavailable(EDGE_2, "예측 없음");

        TravelTimeEstimate result = estimator().estimate(
                List.of(busStep(1, 900, EDGE_1, EDGE_2)), DEPART_AT);

        assertThat(result.segments()).allSatisfy(segment -> {
            assertThat(segment.source()).isEqualTo(EstimateSource.ROUTE_API_SPLIT);
            assertThat(segment.isSynthetic()).isTrue();
            assertThat(segment.note()).contains("2등분");
        });
    }

    @Test
    @DisplayName("매핑이 없는 step 은 나누지 않고 경로 API 시간을 그대로 쓴다")
    void unmappedStepKeepsRouteApiTimeWhole() {
        TravelTimeEstimate result = estimator().estimate(
                List.of(unmappedStep(1, 900)), DEPART_AT);

        assertThat(result.segments()).singleElement().satisfies(segment -> {
            assertThat(segment.source()).isEqualTo(EstimateSource.ROUTE_API);
            assertThat(segment.isSynthetic()).isFalse();
            assertThat(segment.baseSeconds()).isEqualTo(900);
        });
    }

    // ------------------------------------------------------------------
    // 실시간 보정
    // ------------------------------------------------------------------

    @Test
    @DisplayName("진입 예상시각이 유효기간 안이면 지연을 더한다")
    void appliesAdjustmentWhenStatusIsStillValid() {
        predictor.predicted(EDGE_1, 600);
        stateReader.put(EDGE_1, 120, DEPART_AT.plusSeconds(600));

        TravelTimeEstimate result = estimator().estimate(List.of(busStep(1, 900, EDGE_1)), DEPART_AT);

        assertThat(result.segments().get(0).adjustmentSeconds()).isEqualTo(120);
        assertThat(result.totalSeconds()).isEqualTo(720);
    }

    @Test
    @DisplayName("유효기간이 지난 구간에는 지연을 더하지 않는다 — 지금의 정체를 한 시간 뒤에 얹지 않는다")
    void ignoresAdjustmentForSegmentsEnteredAfterValidUntil() {
        predictor.predicted(EDGE_1, 3600);
        predictor.predicted(EDGE_2, 600);
        stateReader.put(EDGE_2, 300, DEPART_AT.plusSeconds(300));   // 5분 뒤면 만료

        TravelTimeEstimate result = estimator().estimate(
                List.of(busStep(1, 3600, EDGE_1), busStep(2, 600, EDGE_2)), DEPART_AT);

        assertThat(result.segments().get(1).adjustmentSeconds())
                .as("한 시간 뒤 지나갈 구간에 지금의 지연을 더하면 예측이 아니라 착각이다")
                .isZero();
        assertThat(result.totalSeconds()).isEqualTo(4200);
    }

    @Test
    @DisplayName("보정은 배분값 위에도 얹힌다 — 지연은 관측이라 출처와 무관하다")
    void adjustmentAppliesRegardlessOfSource() {
        predictor.unavailable(EDGE_1, "예측 없음");
        stateReader.put(EDGE_1, 60, DEPART_AT.plusSeconds(3600));

        TravelTimeEstimate result = estimator().estimate(List.of(busStep(1, 900, EDGE_1)), DEPART_AT);

        assertThat(result.segments().get(0).source()).isEqualTo(EstimateSource.ROUTE_API_SPLIT);
        assertThat(result.segments().get(0).adjustmentSeconds()).isEqualTo(60);
    }

    // ------------------------------------------------------------------
    // 진입 시각 누적
    // ------------------------------------------------------------------

    @Test
    @DisplayName("진입 시각이 앞 구간들의 소요시간만큼 밀린다 — step 안의 edge 사이에서도")
    void entryTimeAccumulates() {
        predictor.predicted(EDGE_1, 300);
        predictor.predicted(EDGE_2, 400);

        TravelTimeEstimate result = estimator().estimate(
                List.of(walkStep(1, 180), busStep(2, 900, EDGE_1, EDGE_2)), DEPART_AT);

        assertThat(result.segments().get(0).enteredAt()).isEqualTo(DEPART_AT);
        assertThat(result.segments().get(1).enteredAt()).isEqualTo(DEPART_AT.plusSeconds(180));
        assertThat(result.segments().get(2).enteredAt()).isEqualTo(DEPART_AT.plusSeconds(480));
        assertThat(result.totalSeconds()).isEqualTo(880);
    }

    // ------------------------------------------------------------------
    // 배치 · 장애 내성
    // ------------------------------------------------------------------

    @Test
    @DisplayName("예측 호출은 구간이 몇 개든 한 번뿐이다")
    void predictsInOneBatch() {
        predictor.predicted(EDGE_1, 600);
        predictor.predicted(EDGE_2, 300);

        estimator().estimate(
                List.of(busStep(1, 900, EDGE_1), walkStep(2, 120), busStep(3, 400, EDGE_2)),
                DEPART_AT);

        assertThat(predictor.callCount).isEqualTo(1);
        assertThat(predictor.lastRequests).hasSize(2);   // 도보는 예측 대상이 아니다
    }

    @Test
    @DisplayName("예측 요청에는 조회 시각이 아니라 진입 예상 날짜·시간대가 들어간다")
    void predictionRequestCarriesEntryTime() {
        predictor.predicted(EDGE_2, 300);

        // 08:00 출발 + 2시간짜리 도보 → 다음 step 진입은 10시
        estimator().estimate(List.of(walkStep(1, 7200), busStep(2, 400, EDGE_2)), DEPART_AT);

        BusSegmentPredictionRequest request = predictor.lastRequests.get(0);
        assertThat(request.serviceDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(request.hourSlot()).isEqualTo(10);
    }

    @Test
    @DisplayName("예측 서비스가 터져도 추천은 나간다")
    void survivesPredictorFailure() {
        predictor.explode = true;

        TravelTimeEstimate result = estimator().estimate(List.of(busStep(1, 900, EDGE_1)), DEPART_AT);

        assertThat(result.totalSeconds()).isEqualTo(900);
        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    @DisplayName("실시간 상태를 못 읽어도 추천은 나간다 — 보정만 빠진다")
    void survivesStateReaderFailure() {
        predictor.predicted(EDGE_1, 600);
        stateReader.explode = true;

        TravelTimeEstimate result = estimator().estimate(List.of(busStep(1, 900, EDGE_1)), DEPART_AT);

        assertThat(result.totalSeconds()).isEqualTo(600);
        assertThat(result.segments().get(0).adjustmentSeconds()).isZero();
    }

    @Test
    @DisplayName("활성 기준선이 없으면 조회조차 하지 않는다 — 지금이 그 상태다")
    void skipsBaselineQueryWhenNothingActivated() {
        when(versionProvider.activeVersion()).thenReturn(null);
        predictor.unavailable(EDGE_1, "예측 없음");

        TravelTimeEstimate result = estimator().estimate(List.of(busStep(1, 900, EDGE_1)), DEPART_AT);

        assertThat(result.segments().get(0).source()).isEqualTo(EstimateSource.ROUTE_API_SPLIT);
        verify(baselineRepository, never()).findForEstimation(anyString(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // 픽스처
    // ------------------------------------------------------------------

    private void givenBaselines(BaselineServing... rows) {
        when(baselineRepository.findForEstimation(anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(rows));
    }

    private static BaselineServing baseline(String segmentId, byte dow, byte hourSlot, int p50) {
        BaselineServing row = mock(BaselineServing.class);
        when(row.getSegmentId()).thenReturn(segmentId);
        when(row.getDow()).thenReturn(dow);
        when(row.getHourSlot()).thenReturn(hourSlot);
        when(row.getP50()).thenReturn(p50);
        return row;
    }

    private static StepEstimateInput busStep(int sequence, long routeApiSeconds, String... segmentIds) {
        List<EdgeEstimateInput> edges = new ArrayList<>();
        int order = 3;
        for (int i = 0; i < segmentIds.length; i++) {
            edges.add(new EdgeEstimateInput(i + 1, segmentIds[i],
                    new BusProviderKey("100100609", "from" + i, "to" + i, order + i)));
        }
        return new StepEstimateInput(sequence, TransportMode.BUS, routeApiSeconds, edges);
    }

    private static StepEstimateInput walkStep(int sequence, long routeApiSeconds) {
        return new StepEstimateInput(sequence, TransportMode.WALK, routeApiSeconds, List.of());
    }

    /** 버스인데 서울 마스터에 못 맞춘 step. edge 가 없다. */
    private static StepEstimateInput unmappedStep(int sequence, long routeApiSeconds) {
        return new StepEstimateInput(sequence, TransportMode.BUS, routeApiSeconds, List.of());
    }

    /** 예측 서비스 대역. 호출 횟수를 세는 것이 목적이라 목 대신 손으로 만든다. */
    private static final class FakePredictor implements BusTravelTimePredictor {
        private final Map<String, BusTravelTimePrediction> answers = new LinkedHashMap<>();
        private List<BusSegmentPredictionRequest> lastRequests = List.of();
        private int callCount;
        private boolean explode;

        void predicted(String segmentId, long seconds) {
            answers.put(segmentId, BusTravelTimePrediction.predicted(seconds));
        }

        void unavailable(String segmentId, String reason) {
            answers.put(segmentId, BusTravelTimePrediction.unavailable(reason));
        }

        @Override
        public Map<String, BusTravelTimePrediction> predict(List<BusSegmentPredictionRequest> requests) {
            callCount++;
            lastRequests = new ArrayList<>(requests);
            if (explode) {
                throw new IllegalStateException("예측 서비스 응답 없음");
            }
            Map<String, BusTravelTimePrediction> results = new LinkedHashMap<>();
            for (BusSegmentPredictionRequest request : requests) {
                results.put(request.segmentId(), answers.getOrDefault(request.segmentId(),
                        BusTravelTimePrediction.unavailable("대역에 등록되지 않은 구간")));
            }
            return results;
        }
    }

    /** 실시간 상태 대역. */
    private static final class FakeStateReader implements SegmentStateReader {
        private final Map<String, SegmentStatus> statuses = new HashMap<>();
        private boolean explode;

        void put(String segmentId, int delaySeconds, Instant validUntil) {
            statuses.put(segmentId, new SegmentStatus(segmentId, "delayed", delaySeconds,
                    0.8, Instant.now(), validUntil, "spark.segment-status"));
        }

        @Override
        public Map<String, SegmentStatus> readAll(Collection<String> segmentIds) {
            if (explode) {
                return Map.of();   // 어댑터가 예외를 삼키고 빈 결과를 준다
            }
            Map<String, SegmentStatus> found = new LinkedHashMap<>();
            for (String id : segmentIds) {
                SegmentStatus status = statuses.get(id);
                if (status != null) {
                    found.put(id, status);
                }
            }
            return found;
        }
    }

    /** 분배 자체의 성질은 따로 고정한다 — 산정기를 거치지 않아도 깨지면 안 된다. */
    @Test
    @DisplayName("균등 분배는 개수·나머지와 무관하게 합을 보존한다")
    void evenSplitAlwaysPreservesTotal() {
        for (long total : new long[]{0, 1, 7, 900, 901, 3599}) {
            for (int parts = 1; parts <= 7; parts++) {
                long[] shares = EvenSplit.distribute(total, parts);
                assertThat(Arrays.stream(shares).sum())
                        .as("total=%d parts=%d", total, parts)
                        .isEqualTo(total);
                assertThat(shares).hasSize(parts);
            }
        }
    }
}

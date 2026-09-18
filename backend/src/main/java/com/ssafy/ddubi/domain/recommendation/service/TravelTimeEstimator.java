package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.pipeline.entity.BaselineServing;
import com.ssafy.ddubi.domain.pipeline.entity.WeatherBucket;
import com.ssafy.ddubi.domain.pipeline.repository.BaselineServingRepository;
import com.ssafy.ddubi.domain.recommendation.entity.Confidence;
import com.ssafy.ddubi.domain.recommendation.port.BusSegmentPredictionRequest;
import com.ssafy.ddubi.domain.recommendation.port.BusTravelTimePrediction;
import com.ssafy.ddubi.domain.recommendation.port.BusTravelTimePredictor;
import com.ssafy.ddubi.domain.recommendation.port.SegmentStateReader;
import com.ssafy.ddubi.domain.recommendation.port.SegmentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 경로의 구간별 소요시간을 정한다. 추천의 숫자는 전부 여기서 나온다.
 *
 * <h3>판단 단위는 step 이다</h3>
 * 외부 경로 API 가 주는 이동 구간 하나가 판단 단위고, 그 안의 edge 들은 <b>같은 출처</b>를
 * 쓴다. 분산팀 합의(2026-09-10)대로 한 step 안에서 일부 edge 만 ML 값으로 바꾸지 않는다 —
 * 부분 혼합은 별도 정책과 검증이 필요하다.
 *
 * <h3>출처는 3단으로 내려간다</h3>
 * <ol>
 *   <li>예측 서비스 — step 의 <b>모든</b> edge 를 예측했을 때만</li>
 *   <li>{@code baseline_serving} 평소값(p50) — 역시 모든 edge 가 있을 때만</li>
 *   <li>경로 API step 시간 — edge 가 있으면 균등 분배, 없으면 그대로</li>
 * </ol>
 *
 * <h3>실시간 상태는 4단째가 아니라 '보정'이다</h3>
 * Redis {@code segment:status} 는 <b>지금</b>의 상태다. 한 시간 뒤에 지나갈 구간에 지금의
 * 지연을 더하면 예측이 아니라 착각이 된다. ML 결정 문서(2026-09-08)의 "현재 관측을 미래 구간의
 * 실제 상태로 취급하지 않는다" 를 따라, <b>진입 예상시각이 그 값의 유효기간 안에 드는 edge 에만</b>
 * 더한다. 보정은 기본 시간과 달리 edge 단위다 — 관측된 지연이지 배분한 추정치가 아니다.
 *
 * <h3>예측은 왜 두 번 훑고 부르나</h3>
 * 예측 입력에 "진입 예상 날짜·시간대" 가 들어가는데, 그 값은 앞 구간들의 소요시간이 정해져야
 * 나온다. 그렇다고 구간마다 따로 부르면 왕복이 구간 수만큼 쌓인다. 그래서
 * <b>1차로 경로 API 시간을 써서 진입 시각을 잡아 배치 요청을 한 번에 보내고</b>,
 * 2차에서 받은 예측으로 다시 누적한다.
 */
@Service
public class TravelTimeEstimator {

    private static final Logger log = LoggerFactory.getLogger(TravelTimeEstimator.class);

    /** 기준선의 요일 구분과 시간대를 뽑는 기준 시간대. */
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 날씨 구간은 아직 예보를 연결하지 않아 한쪽으로 고정한다. 비 오는 날 값을 임의로
     * 고르면 근거 없이 낙관하거나 비관하는 셈이라, 확정될 때까지 건조를 쓴다.
     */
    private static final WeatherBucket DEFAULT_WEATHER = WeatherBucket.DRY;

    private final BusTravelTimePredictor predictor;
    private final SegmentStateReader segmentStateReader;
    private final BaselineServingRepository baselineRepository;
    private final BaselineVersionProvider baselineVersionProvider;

    public TravelTimeEstimator(BusTravelTimePredictor predictor,
                               SegmentStateReader segmentStateReader,
                               BaselineServingRepository baselineRepository,
                               BaselineVersionProvider baselineVersionProvider) {
        this.predictor = predictor;
        this.segmentStateReader = segmentStateReader;
        this.baselineRepository = baselineRepository;
        this.baselineVersionProvider = baselineVersionProvider;
    }

    /**
     * @param departAt 이 시각에 출발한다고 보고 구간별 진입 시각을 누적한다
     */
    public TravelTimeEstimate estimate(List<StepEstimateInput> steps, Instant departAt) {
        Objects.requireNonNull(departAt, "출발 시각이 있어야 진입 시각을 계산할 수 있습니다.");
        if (steps.isEmpty()) {
            return new TravelTimeEstimate(List.of(), 0, Confidence.LOW, true);
        }

        Map<String, BusTravelTimePrediction> predictions = fetchPredictions(steps, departAt);
        Map<String, SegmentStatus> statuses = fetchStatuses(steps);
        BaselineSnapshot baselines = loadBaselines(steps, departAt);

        List<SegmentEstimate> results = new ArrayList<>();
        Instant cursor = departAt;

        for (StepEstimateInput step : steps) {
            List<SegmentEstimate> estimates = estimateStep(step, cursor, predictions, statuses, baselines);
            results.addAll(estimates);
            for (SegmentEstimate estimate : estimates) {
                cursor = cursor.plusSeconds(estimate.totalSeconds());
            }
        }

        long total = results.stream().mapToLong(SegmentEstimate::totalSeconds).sum();
        return new TravelTimeEstimate(results, total, confidenceOf(results), isFallbackUsed(results));
    }

    // ------------------------------------------------------------------
    // step 하나 — 출처를 하나만 고른다
    // ------------------------------------------------------------------

    private List<SegmentEstimate> estimateStep(StepEstimateInput step,
                                               Instant enteredAt,
                                               Map<String, BusTravelTimePrediction> predictions,
                                               Map<String, SegmentStatus> statuses,
                                               BaselineSnapshot baselines) {
        if (step.hasNoEdges()) {
            // 도보이거나 매핑 실패. 나눌 것도 붙일 것도 없다.
            return List.of(new SegmentEstimate(step.sequence(), 0, null, enteredAt,
                    step.routeApiSeconds(), EstimateSource.ROUTE_API, 0, null));
        }

        long[] predicted = allPredicted(step, predictions);
        if (predicted != null) {
            return spread(step, enteredAt, predicted, EstimateSource.PREDICTION, null, statuses);
        }

        String note = predictionNote(step, predictions);

        long[] baseline = allBaselines(step, enteredAt, baselines);
        if (baseline != null) {
            return spread(step, enteredAt, baseline, EstimateSource.BASELINE, note, statuses);
        }

        long[] split = EvenSplit.distribute(step.routeApiSeconds(), step.edges().size());
        String splitNote = joinNotes(note, step.edges().size() == 1
                ? "기준선 없음"
                : "기준선 없음 · 경로 API step 시간을 " + step.edges().size() + "등분");
        return spread(step, enteredAt, split, EstimateSource.ROUTE_API_SPLIT, splitNote, statuses);
    }

    /**
     * edge 별 기본 시간을 실제 결과로 펼친다. 진입 시각은 edge 안에서도 누적된다 —
     * 첫 edge 에서 5분 걸리면 두 번째 edge 진입은 5분 뒤다.
     */
    private List<SegmentEstimate> spread(StepEstimateInput step, Instant stepEnteredAt,
                                         long[] baseSeconds, EstimateSource source, String note,
                                         Map<String, SegmentStatus> statuses) {
        List<SegmentEstimate> results = new ArrayList<>(step.edges().size());
        Instant cursor = stepEnteredAt;

        for (int i = 0; i < step.edges().size(); i++) {
            EdgeEstimateInput edge = step.edges().get(i);
            long adjustment = adjustmentFor(edge.segmentId(), cursor, statuses);
            results.add(new SegmentEstimate(step.sequence(), edge.edgeSeq(), edge.segmentId(),
                    cursor, baseSeconds[i], source, adjustment, note));
            cursor = cursor.plusSeconds(baseSeconds[i] + adjustment);
        }
        return results;
    }

    /**
     * step 의 <b>모든</b> edge 가 예측됐을 때만 값을 준다. 하나라도 빠지면 null —
     * 부분 혼합 금지(분산팀 조건 3).
     */
    private long[] allPredicted(StepEstimateInput step, Map<String, BusTravelTimePrediction> predictions) {
        if (!step.isPredictable()) {
            return null;
        }
        long[] values = new long[step.edges().size()];
        for (int i = 0; i < step.edges().size(); i++) {
            BusTravelTimePrediction prediction = predictions.get(step.edges().get(i).segmentId());
            if (prediction == null || !prediction.isPredicted()) {
                return null;
            }
            values[i] = prediction.seconds();
        }
        return values;
    }

    /** 기준선도 마찬가지로 전부 있을 때만 쓴다. 일부만 채우면 나머지를 나눌 근거가 없다. */
    private long[] allBaselines(StepEstimateInput step, Instant enteredAt, BaselineSnapshot baselines) {
        Instant cursor = enteredAt;
        long[] values = new long[step.edges().size()];
        for (int i = 0; i < step.edges().size(); i++) {
            ZonedDateTime entry = cursor.atZone(ZONE);
            byte dow = (byte) entry.getDayOfWeek().getValue();
            byte hourSlot = (byte) entry.getHour();
            String segmentId = step.edges().get(i).segmentId();
            Long p50 = segmentId == null ? null : baselines.p50(segmentId, dow, hourSlot);
            if (p50 == null) {
                return null;
            }
            values[i] = p50;
            cursor = cursor.plusSeconds(p50);
        }
        return values;
    }

    /** 예측이 왜 안 됐는지 — 첫 번째 이유만 남긴다. 같은 step 이면 대개 같은 이유다. */
    private String predictionNote(StepEstimateInput step, Map<String, BusTravelTimePrediction> predictions) {
        for (EdgeEstimateInput edge : step.edges()) {
            BusTravelTimePrediction prediction = predictions.get(edge.segmentId());
            if (prediction != null && !prediction.isPredicted()) {
                return prediction.reason();
            }
        }
        return null;
    }

    /**
     * 실시간 지연 보정. <b>진입 예상시각이 그 값의 유효기간 안일 때만</b> 더한다.
     * 유효기간이 지난 값은 없는 것으로 본다 — 오래된 지연을 미래에 그대로 얹는 것이
     * 보정을 아예 안 하는 것보다 나쁘다.
     */
    private long adjustmentFor(String segmentId, Instant enteredAt, Map<String, SegmentStatus> statuses) {
        if (segmentId == null) {
            return 0;
        }
        SegmentStatus status = statuses.get(segmentId);
        if (status == null || !status.isUsableAt(enteredAt)) {
            return 0;
        }
        return Math.max(0, status.delaySeconds());
    }

    // ------------------------------------------------------------------
    // 배치 조회
    // ------------------------------------------------------------------

    /** 1차 훑기 — 경로 API 시간으로 진입 시각을 잡아 예측 요청을 한 번에 만든다. */
    private Map<String, BusTravelTimePrediction> fetchPredictions(List<StepEstimateInput> steps,
                                                                  Instant departAt) {
        List<BusSegmentPredictionRequest> requests = new ArrayList<>();
        Instant cursor = departAt;

        for (StepEstimateInput step : steps) {
            if (step.isPredictable()) {
                long[] share = EvenSplit.distribute(step.routeApiSeconds(), step.edges().size());
                Instant edgeCursor = cursor;
                for (int i = 0; i < step.edges().size(); i++) {
                    EdgeEstimateInput edge = step.edges().get(i);
                    ZonedDateTime entry = edgeCursor.atZone(ZONE);
                    requests.add(new BusSegmentPredictionRequest(
                            edge.segmentId(),
                            edge.busKey().providerRouteId(),
                            edge.busKey().providerFromStopId(),
                            edge.busKey().providerToStopId(),
                            edge.busKey().fromStopSequence(),
                            entry.toLocalDate(),
                            entry.getHour()));
                    edgeCursor = edgeCursor.plusSeconds(share[i]);
                }
            }
            cursor = cursor.plusSeconds(step.routeApiSeconds());
        }

        if (requests.isEmpty()) {
            return Map.of();
        }
        try {
            return predictor.predict(requests);
        } catch (RuntimeException e) {
            // 예측 서비스가 죽어도 추천은 나가야 한다.
            log.warn("예측 서비스 호출 실패 — 기준선·경로 API 로 계산합니다: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, SegmentStatus> fetchStatuses(List<StepEstimateInput> steps) {
        Set<String> ids = new LinkedHashSet<>();
        for (StepEstimateInput step : steps) {
            for (EdgeEstimateInput edge : step.edges()) {
                if (edge.segmentId() != null) {
                    ids.add(edge.segmentId());
                }
            }
        }
        return ids.isEmpty() ? Map.of() : segmentStateReader.readAll(ids);
    }

    /**
     * 기준선을 <b>한 번의 쿼리로</b> 당겨 온다.
     *
     * <h3>어느 요일·시간대를 당기나</h3>
     * 1차 훑기(경로 API 시간)로 잡은 진입 시각들의 요일·시간대에 <b>앞뒤 한 시간</b>을 더한다.
     * 2차에서 예측·기준선이 1차보다 길거나 짧으면 진입 시각이 밀리는데, 그때 시간대 경계를
     * 넘어가면 스냅샷에 없는 칸을 찾게 된다. 한 시간씩 여유를 두면 그 경우가 덮인다.
     * 자정을 넘나드는 경로를 위해 0시·23시가 걸리면 앞뒤 요일도 함께 넣는다.
     */
    private BaselineSnapshot loadBaselines(List<StepEstimateInput> steps, Instant departAt) {
        String activeVersion = baselineVersionProvider.activeVersion();
        if (activeVersion == null) {
            return BaselineSnapshot.empty();
        }

        Set<String> segmentIds = new LinkedHashSet<>();
        Set<Byte> dows = new LinkedHashSet<>();
        Set<Byte> hours = new LinkedHashSet<>();

        for (byte dow = 1; dow <= 7; dow++) {
            dows.add(dow);
        }
        for (byte hour = 0; hour < 24; hour++) {
            hours.add(hour);
        }

        Instant cursor = departAt;
        for (StepEstimateInput step : steps) {
            if (!step.hasNoEdges()) {
                for (EdgeEstimateInput edge : step.edges()) {
                    if (edge.segmentId() != null) {
                        segmentIds.add(edge.segmentId());
                    }
                }
                ZonedDateTime entry = cursor.atZone(ZONE);
                int dow = entry.getDayOfWeek().getValue();
                int hour = entry.getHour();

                dows.add((byte) dow);
                hours.add((byte) hour);
                hours.add((byte) ((hour + 23) % 24));
                hours.add((byte) ((hour + 1) % 24));
                if (hour == 0) {
                    dows.add((byte) previousDow(dow));
                } else if (hour == 23) {
                    dows.add((byte) nextDow(dow));
                }
            }
            cursor = cursor.plusSeconds(step.routeApiSeconds());
        }

        if (segmentIds.isEmpty()) {
            return BaselineSnapshot.empty();
        }
        List<BaselineServing> rows = baselineRepository.findForEstimation(
                activeVersion, segmentIds, dows, hours, DEFAULT_WEATHER);
        return BaselineSnapshot.of(rows);
    }

    /** ISO 요일(월=1 … 일=7) 기준 순환. */
    private static int previousDow(int dow) {
        return dow == 1 ? 7 : dow - 1;
    }

    private static int nextDow(int dow) {
        return dow == 7 ? 1 : dow + 1;
    }

    // ------------------------------------------------------------------
    // 신뢰도
    // ------------------------------------------------------------------

    /** 가장 약한 구간이 전체를 정한다. 도보·미매핑 step 은 판정에서 뺀다. */
    private static Confidence confidenceOf(List<SegmentEstimate> results) {
        boolean anyRouteApi = false;
        boolean anyBaseline = false;
        for (SegmentEstimate estimate : results) {
            if (estimate.segmentId() == null) {
                continue;   // 도보·미매핑 — 경로 API 가 정해진 출처다
            }
            if (estimate.source() == EstimateSource.ROUTE_API
                    || estimate.source() == EstimateSource.ROUTE_API_SPLIT) {
                anyRouteApi = true;
            } else if (estimate.source() == EstimateSource.BASELINE) {
                anyBaseline = true;
            }
        }
        if (anyRouteApi) {
            return Confidence.LOW;
        }
        return anyBaseline ? Confidence.MID : Confidence.HIGH;
    }

    private static boolean isFallbackUsed(List<SegmentEstimate> results) {
        return results.stream()
                .filter(estimate -> estimate.segmentId() != null)
                .anyMatch(estimate -> estimate.source() != EstimateSource.PREDICTION);
    }

    private static String joinNotes(String first, String second) {
        if (first == null) {
            return second;
        }
        return first + " · " + second;
    }
}

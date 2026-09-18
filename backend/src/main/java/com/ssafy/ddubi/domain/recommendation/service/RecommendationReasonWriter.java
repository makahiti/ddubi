package com.ssafy.ddubi.domain.recommendation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 추천의 {@code reasons} 와 {@code segment_status_snapshot} JSON 을 만든다.
 *
 * <h3>무엇을 위한 값인가</h3>
 * {@code reasons} 는 사용자에게 "왜 이 시각인가" 를 보여 주기 위한 것이고,
 * {@code snapshot} 은 나중에 <b>같은 계산을 다시 해 보기 위한</b> 것이다. 목적이 달라서
 * 담는 것도 다르다 — 화면에 안 쓰는 값도 스냅샷에는 들어간다.
 *
 * <h3>직렬화가 실패해도 추천은 나간다</h3>
 * 이유를 못 만들었다고 출발 시각을 못 알려 줄 이유는 없다. JSON 은 부가 정보이므로
 * 실패하면 null 로 두고 로그만 남긴다. 컬럼도 nullable 이다.
 */
@Component
public class RecommendationReasonWriter {

    private static final Logger log = LoggerFactory.getLogger(RecommendationReasonWriter.class);

    private final ObjectMapper objectMapper;

    public RecommendationReasonWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 화면용 근거. 출처별로 몇 분씩 들어갔는지와, 고르지 않은 후보와 얼마나 차이 나는지.
     *
     * @param alternatives 모든 후보의 풀이 결과. 1순위 자신도 들어 있다
     */
    public String reasons(TravelTimeEstimate estimate, List<DepartureSolver.Solution> alternatives) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("factors", factors(estimate));
        root.put("confidence", estimate.confidence().name());
        root.put("fallbackUsed", estimate.fallbackUsed());
        root.put("candidates", candidates(alternatives));
        return write(root, "reasons");
    }

    /**
     * 출처별 합계(분). "예측 12분 + 기준선 5분" 처럼 보여 주기 위한 값이다.
     *
     * <p>실시간 보정은 출처와 별개로 따로 센다 — 사용자가 가장 궁금해하는 것이
     * "지금 막혀서 몇 분 더 걸리나" 이기 때문이다.
     */
    private List<Map<String, Object>> factors(TravelTimeEstimate estimate) {
        Map<EstimateSource, Long> bySource = new LinkedHashMap<>();
        long adjustment = 0;

        for (SegmentEstimate segment : estimate.segments()) {
            bySource.merge(segment.source(), segment.baseSeconds(), Long::sum);
            adjustment += segment.adjustmentSeconds();
        }

        List<Map<String, Object>> factors = new ArrayList<>();
        bySource.forEach((source, seconds) -> factors.add(factor(source.name(), seconds, source.isSynthetic())));
        if (adjustment > 0) {
            factors.add(factor("REALTIME_DELAY", adjustment, false));
        }
        return factors;
    }

    private static Map<String, Object> factor(String name, long seconds, boolean synthetic) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("factor", name);
        entry.put("min", toMinutes(seconds));
        entry.put("seconds", seconds);
        if (synthetic) {
            // 배분값이라는 사실이 화면과 로그 양쪽에 남아야 한다.
            entry.put("synthetic", true);
        }
        return entry;
    }

    /** 후보 비교 요약. 1순위 대비 몇 분 더 일찍 나가야 하는지. */
    private List<Map<String, Object>> candidates(List<DepartureSolver.Solution> alternatives) {
        List<Map<String, Object>> summaries = new ArrayList<>(alternatives.size());
        long bestDepart = alternatives.stream()
                .mapToLong(solution -> solution.departAt().getEpochSecond())
                .max()
                .orElse(0);

        for (DepartureSolver.Solution solution : alternatives) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("departAt", solution.departAt().toString());
            summary.put("durationMin", toMinutes(solution.estimate().totalSeconds()));
            summary.put("confidence", solution.estimate().confidence().name());
            summary.put("earlierByMin",
                    toMinutes(bestDepart - solution.departAt().getEpochSecond()));
            summaries.add(summary);
        }
        return summaries;
    }

    /**
     * 재현용 스냅샷. 구간마다 <b>어디서 온 숫자인지</b>까지 남긴다.
     * 이게 없으면 나중에 같은 추천을 다시 만들어 볼 수 없다.
     */
    public String snapshot(TravelTimeEstimate estimate) {
        List<Map<String, Object>> segments = new ArrayList<>(estimate.segments().size());
        for (SegmentEstimate segment : estimate.segments()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("stepSequence", segment.stepSequence());
            entry.put("edgeSeq", segment.edgeSeq());
            entry.put("segmentId", segment.segmentId());
            entry.put("enteredAt", segment.enteredAt().toString());
            entry.put("baseSeconds", segment.baseSeconds());
            entry.put("source", segment.source().name());
            entry.put("synthetic", segment.isSynthetic());
            entry.put("adjustmentSeconds", segment.adjustmentSeconds());
            if (segment.note() != null) {
                entry.put("note", segment.note());
            }
            segments.add(entry);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("totalSeconds", estimate.totalSeconds());
        root.put("segments", segments);
        return write(root, "segment_status_snapshot");
    }

    private String write(Object value, String what) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("{} 직렬화 실패 — 추천은 그대로 저장합니다: {}", what, e.getMessage());
            return null;
        }
    }

    private static long toMinutes(long seconds) {
        return (seconds + 59) / 60;
    }
}

package com.ssafy.ddubi.domain.recommendation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.recommendation.client.GmsRecommendationGuideClient;
import com.ssafy.ddubi.domain.recommendation.client.RecommendationGuideProperties;
import com.ssafy.ddubi.domain.recommendation.dto.response.RecommendationGuideResponse;
import com.ssafy.ddubi.domain.recommendation.evaluation.ScheduleEvaluationV2;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 최신 평가를 사용자용 추천 카드로 투영한다. 출발·도착·구간 순서와 지연 판정은 서버 계산값으로 고정하고 GMS에는 문장 표현만 맡긴다. 같은 evaluationId는
 * Redis에 캐시해 카드 조회마다 AI를 호출하지 않는다.
 */
@Service
public class RecommendationGuideService {
    private static final String CACHE_PREFIX = "recommendation:guide:";
    private static final List<String> CAUSE_PREFIXES =
            List.of(
                    "traffic:latest:incident-silver:",
                    "traffic:latest:incident:",
                    "transit:latest:subway-alert:",
                    "traffic:latest:topis-event:",
                    "traffic:latest:topis-notice:");

    private final ScheduleEvaluationStore store;
    private final GmsRecommendationGuideClient gms;
    private final RecommendationGuideProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RecommendationGuideService(
            ScheduleEvaluationStore store,
            GmsRecommendationGuideClient gms,
            RecommendationGuideProperties properties,
            StringRedisTemplate redis,
            ObjectMapper json) {
        this.store = store;
        this.gms = gms;
        this.properties = properties;
        this.redis = redis;
        this.json = json;
    }

    public RecommendationGuideResponse guide(long scheduleId, long userId, Instant now) {
        ScheduleEvaluationV2 evaluation = store.latestReadyV2(scheduleId, userId);
        if (evaluation.validUntil() == null || !evaluation.validUntil().isAfter(now))
            throw new BusinessException(ErrorCode.STALE_RECOMMENDATION);
        String cacheKey = CACHE_PREFIX + scheduleId + ":" + evaluation.evaluationId();
        RecommendationGuideResponse cached = cached(cacheKey);
        if (cached != null) return cached;

        List<RecommendationGuideResponse.RouteStep> steps = steps(evaluation);
        CauseDetail detail = cause(evaluation.causes(), now);
        RecommendationGuideResponse.Delay delay = delay(evaluation.causes(), detail);
        GmsRecommendationGuideClient.ChangeFact change = change(evaluation);
        String fallbackRoute =
                steps.isEmpty()
                        ? "이동 경로를 확인하고 있어요."
                        : steps.stream()
                                .map(RecommendationGuideResponse.RouteStep::instruction)
                                .reduce((left, right) -> left + " → " + right)
                                .orElse("이동 경로를 확인하고 있어요.");
        var facts =
                new GmsRecommendationGuideClient.Facts(
                        value(evaluation.proposedDepartAt()),
                        value(evaluation.estimatedArrivalAt()),
                        steps.stream()
                                .map(
                                        step ->
                                                new GmsRecommendationGuideClient.StepFact(
                                                        step.sequence(),
                                                        step.mode(),
                                                        step.routeLabel(),
                                                        step.fromName(),
                                                        step.toName(),
                                                        step.durationSeconds()))
                                .toList(),
                        evaluation.durationSeconds(),
                        evaluation.fare(),
                        change,
                        new GmsRecommendationGuideClient.DelayFact(
                                delay.status(), delay.reason(), delay.source(), delay.confirmed()));
        var generated = gms.generate(facts).orElse(null);
        String delayReason =
                generated != null && generated.delayExplanation() != null
                        ? generated.delayExplanation()
                        : delay.reason();
        String recommendationReason =
                generated == null
                        ? fallbackRecommendation(change.type(), delay)
                        : generated.recommendationReason();
        RecommendationGuideResponse response =
                new RecommendationGuideResponse(
                        "recommendation-guide-v1",
                        evaluation.evaluationId(),
                        evaluation.proposedDepartAt(),
                        evaluation.estimatedArrivalAt(),
                        evaluation.durationSeconds(),
                        evaluation.fare(),
                        headline(evaluation),
                        change.type(),
                        recommendationReason,
                        generated == null ? fallbackRoute : generated.routeSummary(),
                        steps,
                        new RecommendationGuideResponse.Delay(
                                delay.status(),
                                delay.delaySeconds(),
                                delayReason,
                                delay.source(),
                                delay.confirmed()),
                        evaluation.asOf(),
                        evaluation.validUntil(),
                        generated == null ? "TEMPLATE" : "GMS:" + properties.model());
        cache(cacheKey, response, now);
        return response;
    }

    /**
     * 선택·제안 차이는 서버가 확정하고 GMS에는 문장 작성에 필요한 최소 사실만 준다. 내부 route ID는 모델로 보내지 않으며, 같은 경로에서는 사용자가 실제로
     * 적용할 수 있는 출발 변경을 ETA 단순 갱신보다 우선한다.
     */
    static GmsRecommendationGuideClient.ChangeFact change(ScheduleEvaluationV2 evaluation) {
        boolean routeChanged =
                evaluation.selectedRouteId() != null
                        && evaluation.proposedRouteId() != null
                        && !evaluation.selectedRouteId().equals(evaluation.proposedRouteId());
        Long departureDelta = delta(evaluation.plannedDepartAt(), evaluation.proposedDepartAt());
        Long arrivalDelta =
                delta(evaluation.originalEstimatedArrivalAt(), evaluation.estimatedArrivalAt());
        String type =
                routeChanged
                        ? "ROUTE_CHANGED"
                        : departureDelta != null && departureDelta != 0
                                ? "DEPARTURE_TIME_CHANGED"
                                : arrivalDelta != null && arrivalDelta != 0
                                        ? "ETA_UPDATED"
                                        : "NO_CHANGE";
        return new GmsRecommendationGuideClient.ChangeFact(
                type,
                value(evaluation.plannedDepartAt()),
                value(evaluation.originalEstimatedArrivalAt()),
                departureDelta,
                arrivalDelta,
                routeChanged);
    }

    private static Long delta(LocalDateTime before, LocalDateTime after) {
        return before == null || after == null
                ? null
                : Duration.between(before, after).getSeconds();
    }

    private static String fallbackRecommendation(
            String changeType, RecommendationGuideResponse.Delay delay) {
        String cause = delay.reason();
        return switch (changeType) {
            case "ROUTE_CHANGED" ->
                    cause == null ? "현재 이동 상황을 반영해 다른 경로를 제안해요." : cause + " 다른 경로를 제안해요.";
            case "DEPARTURE_TIME_CHANGED" -> "같은 경로를 이용하되 출발시각 변경을 제안해요.";
            case "ETA_UPDATED" -> "경로와 출발시각은 유지하고 예상 도착시간을 갱신했어요.";
            default -> "현재 선택한 경로와 출발시각을 유지해도 돼요.";
        };
    }

    private RecommendationGuideResponse cached(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? null : json.readValue(value, RecommendationGuideResponse.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void cache(String key, RecommendationGuideResponse response, Instant now) {
        try {
            Duration ttl = Duration.between(now, response.validUntil());
            if (ttl.isNegative() || ttl.isZero()) return;
            // 실시간 사실의 유효시각을 넘겨 AI 문장만 오래 남기지 않는다.
            redis.opsForValue()
                    .set(
                            key,
                            json.writeValueAsString(response),
                            ttl.compareTo(Duration.ofMinutes(30)) > 0
                                    ? Duration.ofMinutes(30)
                                    : ttl);
        } catch (Exception ignored) {
            // 캐시 장애는 추천 계산이나 카드 조회 실패로 승격하지 않는다.
        }
    }

    private static List<RecommendationGuideResponse.RouteStep> steps(
            ScheduleEvaluationV2 evaluation) {
        return evaluation.segments().stream()
                .sorted(Comparator.comparingInt(ScheduleEvaluationV2.Segment::sequence))
                .map(
                        segment ->
                                new RecommendationGuideResponse.RouteStep(
                                        segment.sequence(),
                                        segment.mode(),
                                        segment.routeLabel(),
                                        name(segment.fromName(), "이전 지점"),
                                        name(segment.toName(), "다음 지점"),
                                        segment.durationSeconds(),
                                        instruction(segment)))
                .toList();
    }

    private static String instruction(ScheduleEvaluationV2.Segment segment) {
        String from = name(segment.fromName(), "이전 지점");
        String to = name(segment.toName(), "다음 지점");
        String route = name(segment.routeLabel(), "");
        return switch (segment.mode() == null ? "" : segment.mode()) {
            case "WALK" -> from + "에서 " + to + "까지 도보 이동";
            case "BUS" -> from + "에서 " + (route.isEmpty() ? "버스" : route) + " 탑승 후 " + to + "에서 하차";
            case "SUBWAY" ->
                    from + "에서 " + (route.isEmpty() ? "지하철" : route) + " 탑승 후 " + to + "에서 하차";
            default -> from + "에서 " + to + "까지 이동";
        };
    }

    private CauseDetail cause(List<ScheduleEvaluationV2.Cause> causes, Instant now) {
        if (causes == null || causes.isEmpty()) return null;
        var keys = new ArrayList<String>();
        var owners = new ArrayList<ScheduleEvaluationV2.Cause>();
        for (var cause : causes) {
            if (!safeId(cause.eventId())) continue;
            for (String prefix : CAUSE_PREFIXES) {
                keys.add(prefix + cause.eventId());
                owners.add(cause);
            }
        }
        if (keys.isEmpty()) return null;
        try {
            List<String> values = redis.opsForValue().multiGet(keys);
            if (values == null) return null;
            for (int i = 0; i < values.size(); i++) {
                String raw = values.get(i);
                if (raw == null) continue;
                JsonNode node = json.readTree(raw);
                Instant until = instant(node.path("validUntil").asText(null));
                if (until != null && !until.isAfter(now)) continue;
                String message = firstText(node, 300);
                if (message != null)
                    return new CauseDetail(owners.get(i), message, source(node, owners.get(i)));
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static RecommendationGuideResponse.Delay delay(
            List<ScheduleEvaluationV2.Cause> causes, CauseDetail detail) {
        if (causes == null || causes.isEmpty())
            return new RecommendationGuideResponse.Delay(
                    "NO_CONFIRMED_DELAY", null, null, null, false);
        ScheduleEvaluationV2.Cause chosen =
                causes.stream()
                        .filter(ScheduleEvaluationV2.Cause::confirmed)
                        .findFirst()
                        .orElse(causes.get(0));
        boolean confirmed = chosen.confirmed();
        String status =
                confirmed
                        ? "BLOCKED".equals(chosen.effect()) ? "DISRUPTED" : "DELAYED"
                        : "POSSIBLE";
        // 여러 원인이 함께 있으면 Redis에서 먼저 발견한 상세정보와 최종 선택 원인이 다를 수 있다.
        // 설명과 출처를 서로 다른 사건에서 섞지 않도록 eventId가 같은 상세정보만 사용한다.
        boolean detailMatchesChosen =
                detail != null && detail.cause().eventId().equals(chosen.eventId());
        String reason =
                detailMatchesChosen
                        ? detail.message()
                        : confirmed ? "선택 경로에 공식 교통 영향이 확인됐어요." : "선택 경로 주변에 교통 영향 가능성이 있어요.";
        String source = detailMatchesChosen ? detail.source() : chosen.source();
        // 평시·시간표 기준 차이가 아직 공개 계약에 없으므로 원인만으로 지연 분을 만들지 않는다.
        return new RecommendationGuideResponse.Delay(status, null, reason, source, confirmed);
    }

    private static String headline(ScheduleEvaluationV2 evaluation) {
        if (evaluation.proposedDepartAt() == null || evaluation.estimatedArrivalAt() == null)
            return "출발·도착 시각을 확인하고 있어요.";
        var time = DateTimeFormatter.ofPattern("HH:mm");
        return time.format(evaluation.proposedDepartAt())
                + " 출발 · "
                + time.format(evaluation.estimatedArrivalAt())
                + " 도착 예정";
    }

    private static String value(Object value) {
        return value == null ? null : value.toString();
    }

    private static String name(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean safeId(String value) {
        return value != null && value.matches("[0-9A-Za-z._:-]{1,160}");
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstText(JsonNode node, int max) {
        for (String path :
                List.of(
                        "/observation/incidentMessage",
                        "/observation/alertContent",
                        "/observation/alertTitle",
                        "/evidenceText",
                        "/contentText",
                        "/noticeContent",
                        "/incidentMessage",
                        "/alertContent",
                        "/title")) {
            JsonNode value = node.at(path);
            if (!value.isTextual() || value.asText().isBlank()) continue;
            String text = value.asText().replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").strip();
            return text.length() <= max ? text : text.substring(0, max);
        }
        return null;
    }

    private static String source(JsonNode node, ScheduleEvaluationV2.Cause cause) {
        String value = node.path("source").asText(null);
        return value == null || value.isBlank() ? cause.source() : value;
    }

    private record CauseDetail(ScheduleEvaluationV2.Cause cause, String message, String source) {}
}

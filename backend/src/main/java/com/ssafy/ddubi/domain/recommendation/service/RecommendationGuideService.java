package com.ssafy.ddubi.domain.recommendation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.recommendation.client.GmsRecommendationGuideClient;
import com.ssafy.ddubi.domain.recommendation.client.RecommendationGuideProperties;
import com.ssafy.ddubi.domain.recommendation.dto.response.RecommendationGuideResponse;
import com.ssafy.ddubi.domain.recommendation.evaluation.DepartureEstimate;
import com.ssafy.ddubi.domain.recommendation.evaluation.ScheduleEvaluationV2;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 최신 평가를 사용자용 추천 카드로 투영한다. 출발·도착·구간 순서와 지연 판정은 서버 계산값으로 고정하고 GMS에는 문장 표현만 맡긴다. 같은 evaluationId는
 * Redis에 캐시해 카드 조회마다 AI를 호출하지 않는다.
 *
 * <h3>GMS 를 부르는 조건 (2026-09-22 크레딧 사고 뒤)</h3>
 *
 * 하루에 크레딧 6 만이 나갔다. 원인은 셋이 겹친 것이다 — 출발이 가까우면 1 분마다 새 계산 번호가 생겨 캐시가 매번 빗나갔고, pro 모델이 20 초를 넘겨 시간 초과로
 * 버려지면서도 크레딧은 나갔고, 만료된 결과는 캐시에 넣지 않아 같은 계산에 또 불렀다. 그래서 이제는:
 *
 * <ul>
 *   <li>바뀐 것이 있을 때만 부른다 — 경로가 달라졌거나 출발시각이 달라졌거나 지연 원인이 있을 때. "그대로 유지" 는 서버 문장으로 충분하다.
 *   <li>같은 계산 번호에는 한 번만 부른다 — 부르기 <b>전에</b> Redis 에 "물어봤다" 표시를 하루 남긴다. 시간 초과로 답을 못 받았든, 캐시가 사라졌든,
 *       서버가 다시 떴든 같은 계산에 다시 묻지 않는다. 지금 만드는 중이면(같은 서버 안) 기다리지 않고 서버 문장을 준다.
 *   <li>하루 상한은 두지 않는다 — GMS 크레딧은 다른 기능도 함께 쓰므로 여기서 숫자로 막으면 정상 안내까지 끊긴다. 대신 위 두 규칙으로 새는 길 자체를 막고, 오늘
 *       호출 수는 Redis 카운터로만 센다(사람이 볼 용도).
 *   <li>결과는 실패든 성공이든 최소 5 분은 캐시한다 — 만료가 임박했다고 캐시를 건너뛰면 다음 조회가 또 부른다.
 * </ul>
 */
@Service
public class RecommendationGuideService {
    private static final Logger log = LoggerFactory.getLogger(RecommendationGuideService.class);
    private static final String CACHE_PREFIX = "recommendation:guide:";
    private static final String CALLS_PREFIX = CACHE_PREFIX + "gms-calls:";
    private static final String ASKED_PREFIX = CACHE_PREFIX + "asked:";
    private static final String COPY_PREFIX = CACHE_PREFIX + "copy:";

    /**
     * 같은 상황의 AI 문장을 다시 쓰는 시간. 문장에는 시각이 들어가지 않으므로(지시문·검사로 막는다) 몇 시간 뒤에 써도 틀리지 않는다. 경로나 지연 원인이 바뀌면 상황
     * 지문이 달라져 새로 묻는다.
     */
    static final Duration COPY_TTL = Duration.ofHours(6);

    /** 출발·도착이 이보다 적게 바뀌면 "바뀌었다" 로 보지 않는다. 출발 직전엔 제안 출발이 "지금(초 단위)" 이라 매번 몇 초씩 달라졌다. */
    static final long CHANGE_THRESHOLD_SECONDS = 60;

    /** "이 계산 번호는 이미 물어봤다" 표시를 남기는 시간. 계산 번호는 재계산마다 새로 생기니 하루면 충분히 길다. */
    static final Duration ASKED_TTL = Duration.ofDays(1);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 지금 GMS 문장을 만드는 중인 캐시 키. 같은 계산에 두 요청이 겹치면 뒤의 것은 부르지 않는다. */
    private final Set<String> generating = ConcurrentHashMap.newKeySet();

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
        var latest = store.latestOutcomeV2(scheduleId, userId);
        ScheduleEvaluationV2 evaluation = latest.evaluation();
        // 멈춘 일정의 마지막 결과는 유효기간을 따지지 않는다. 더 갱신되지 않는 결과라 "지났다" 가 곧 "틀렸다" 가 아니다 —
        // 출발한 뒤에도 출발할 때 본 안내를 그대로 다시 보여 주는 것이 목적이다.
        if (!latest.frozen()
                && (evaluation.validUntil() == null || !evaluation.validUntil().isAfter(now)))
            throw new BusinessException(ErrorCode.STALE_RECOMMENDATION);
        if (latest.frozen() && latest.departureEstimate() != null)
            return departedGuide(evaluation, latest.departureEstimate());
        String cacheKey = CACHE_PREFIX + scheduleId + ":" + evaluation.evaluationId();
        String askedKey = ASKED_PREFIX + scheduleId + ":" + evaluation.evaluationId();
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
                        change,
                        new GmsRecommendationGuideClient.DelayFact(
                                delay.status(), delay.reason(), delay.source(), delay.confirmed()));
        GmsRecommendationGuideClient.GeneratedCopy generated = null;
        boolean owner = false;
        String copyKey =
                COPY_PREFIX + scheduleId + ":" + situation(facts, steps, properties.model());
        try {
            // 발표용 사건은 실제 TOPIS 공지로 오해하면 안 된다. 서버의 [시연용] 문장을
            // 그대로 쓰고 생성 AI에는 보내지 않아 가짜 상황을 사실처럼 부풀리지 않는다.
            boolean syntheticDemo =
                    evaluation.causes().stream()
                            .anyMatch(cause -> "DEMO_TOPIS".equals(cause.source()));
            if (!syntheticDemo && worthAsking(change, delay)) {
                // 같은 상황(경로·변경 종류와 방향·지연 원인)을 이미 AI 에게 물었으면 그 문장을 다시 쓴다.
                // 출발 직전엔 1분마다 새 계산 번호가 생겨, 계산 번호만으로는 같은 문장을 매번 새로 받았다.
                generated = reusedCopy(copyKey);
                if (generated == null
                        && (owner = generating.add(cacheKey))
                        && firstAsk(askedKey, now)) {
                    generated = gms.generate(facts).orElse(null);
                    if (generated != null) rememberCopy(copyKey, generated);
                }
            }
        } finally {
            if (owner) generating.remove(cacheKey);
        }
        // GMS가 지시를 어기고 경로 구간을 재서술하면 사용자 화면의 상세 목록과 중복된다.
        // AI가 만든 문장만 버리고 서버 계산·교통 원인은 그대로 유지한다.
        if (generated != null && routeNarration(generated.recommendationReason())) generated = null;
        String delayReason =
                generated != null && generated.delayExplanation() != null
                        ? generated.delayExplanation()
                        : delay.reason();
        String recommendationReason =
                generated == null
                        ? fallbackRecommendation(change, delay)
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
                        fallbackRoute,
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
        // 다른 요청이 같은 계산의 AI 문장을 만드는 중이면 이 서버 문장은 캐시하지 않는다. 늦게 끝난 쪽이 AI 문장을 덮어쓰면 안 된다.
        if (owner || !generating.contains(cacheKey))
            cache(cacheKey, response, now, latest.frozen());
        return response;
    }

    /**
     * 출발 뒤 안내는 출발 전 evaluationId의 Redis/GMS 문장을 재사용하지 않는다. 새로 계산한 실제 출발·도착만 서버 문장으로 표현하고, 이전 구간별
     * 시간과 지연 원인을 지금의 사실로 옮겨 쓰지 않는다. 이 경로에서는 GMS를 다시 호출하지 않는다.
     */
    private static RecommendationGuideResponse departedGuide(
            ScheduleEvaluationV2 evaluation, DepartureEstimate estimate) {
        LocalDateTime departed = LocalDateTime.ofInstant(estimate.actualDepartAt(), KST);
        LocalDateTime arrival =
                estimate.estimatedArrivalAt() == null
                        ? null
                        : LocalDateTime.ofInstant(estimate.estimatedArrivalAt(), KST);
        String headline =
                arrival == null
                        ? "출발했어요 · 도착 예정 시간을 확인할 수 없어요."
                        : DateTimeFormatter.ofPattern("HH:mm").format(departed)
                                + " 출발 · "
                                + DateTimeFormatter.ofPattern("HH:mm").format(arrival)
                                + " 도착 예정";
        return new RecommendationGuideResponse(
                "recommendation-guide-v1",
                evaluation.evaluationId(),
                departed,
                arrival,
                estimate.durationSeconds(),
                evaluation.fare(),
                headline,
                "NO_CHANGE",
                arrival == null
                        ? "실제 출발 시각은 저장했지만 도착 예정은 계산하지 못했어요."
                        : "실제 출발 시각으로 선택한 경로를 다시 계산한 결과예요.",
                "선택한 경로를 따라 이동 중이에요.",
                List.of(),
                new RecommendationGuideResponse.Delay(
                        "NO_CONFIRMED_DELAY", null, null, null, false),
                estimate.computedAt(),
                null,
                "TEMPLATE:DEPARTURE");
    }

    /**
     * AI 문장이 서버 문장보다 나은 경우만 부른다. 경로나 출발시각이 바뀌었거나 지연 원인이 있을 때다. "지금 선택을 유지해도 돼요" 는 서버 문장으로 충분하고, 오늘
     * 나간 크레딧의 대부분이 바로 이 경우였다.
     */
    static boolean worthAsking(
            GmsRecommendationGuideClient.ChangeFact change,
            RecommendationGuideResponse.Delay delay) {
        return "ROUTE_CHANGED".equals(change.type())
                || "DEPARTURE_TIME_CHANGED".equals(change.type())
                || "ETA_UPDATED".equals(change.type())
                || !"NO_CONFIRMED_DELAY".equals(delay.status());
    }

    /**
     * 이 계산 번호로 GMS 를 처음 부르는지 확인하고, 처음이면 "물어봤다" 표시를 <b>먼저</b> 남긴다. 표시가 이미 있으면 부르지 않는다.
     *
     * <p>표시를 남기지 못하면(Redis 장애·응답 없음) 부르지 않는다. 표시 없이 부르면 다음 조회가 또 부를 수 있고, Redis 가 죽었으면 캐시도 없어 조회마다
     * 새 호출이 된다 — 바로 그 길이 2026-09-22 사고의 모양이다. 그 순간은 서버 문장으로 답하고 로그만 남긴다.
     */
    private boolean firstAsk(String askedKey, Instant now) {
        try {
            Boolean first = redis.opsForValue().setIfAbsent(askedKey, now.toString(), ASKED_TTL);
            if (!Boolean.TRUE.equals(first)) return false;
            countCall(now);
            return true;
        } catch (Exception e) {
            log.warn("GMS 호출 표시를 남기지 못해 이번에는 부르지 않는다: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /** 오늘(KST) 실제로 나간 GMS 호출 수. 막는 데 쓰지 않고, 사람이 크레딧 흐름을 확인할 때만 본다. */
    private void countCall(Instant now) {
        try {
            String key = CALLS_PREFIX + LocalDateTime.ofInstant(now, KST).toLocalDate();
            Long calls = redis.opsForValue().increment(key);
            if (calls != null && calls == 1) redis.expire(key, Duration.ofDays(2));
        } catch (Exception ignored) {
            // 카운터는 참고용이다. 실패해도 안내를 막지 않는다.
        }
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
                        : meaningful(departureDelta)
                                ? "DEPARTURE_TIME_CHANGED"
                                : meaningful(arrivalDelta) ? "ETA_UPDATED" : "NO_CHANGE";
        return new GmsRecommendationGuideClient.ChangeFact(
                type,
                value(evaluation.plannedDepartAt()),
                value(evaluation.originalEstimatedArrivalAt()),
                departureDelta,
                arrivalDelta,
                routeChanged);
    }

    /** 사용자가 느낄 만한 변화인가. 몇 초 차이로 "출발 시각이 13초 변경" 같은 안내를 내지 않는다(2026-09-24 제보). */
    private static boolean meaningful(Long deltaSeconds) {
        return deltaSeconds != null && Math.abs(deltaSeconds) >= CHANGE_THRESHOLD_SECONDS;
    }

    /**
     * AI 문장이 기대는 사실만으로 만든 상황 지문. 시각·소요시간·요금처럼 계산마다 조금씩 흔들리는 값은 넣지 않는다 — 넣으면 1분마다 지문이 달라져 다시 묻게 된다.
     * 모델을 바꾸면 지문도 바뀌어 새 모델로 다시 쓴다.
     *
     * <p>변경의 <b>방향</b>(앞당김/늦춤, 빨라짐/늦어짐)은 넣는다. AI 가 방향을 설명하므로, 방향이 바뀌었는데 지난 문장을 다시 쓰면 반대로 안내하게 된다. 몇
     * 분인지는 넣지 않고 문장에도 쓰지 못하게 한다({@link GmsRecommendationGuideClient#withoutAmount}).
     */
    static String situation(
            GmsRecommendationGuideClient.Facts facts,
            List<RecommendationGuideResponse.RouteStep> steps,
            String model) {
        StringBuilder text = new StringBuilder("v2|");
        var change = facts.change();
        text.append(model)
                .append('|')
                .append(change.type())
                .append('|')
                .append(direction(change.departureDeltaSeconds()))
                .append('/')
                .append(direction(change.arrivalDeltaSeconds()));
        for (var step : steps)
            text.append('|')
                    .append(step.mode())
                    .append('/')
                    .append(step.routeLabel())
                    .append('/')
                    .append(step.fromName())
                    .append('/')
                    .append(step.toName());
        var delay = facts.delay();
        text.append('|')
                .append(delay.status())
                .append('/')
                .append(delay.reason())
                .append('/')
                .append(delay.source())
                .append('/')
                .append(delay.confirmed());
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            text.toString()
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)))
                    .substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 느낄 만한 변화의 방향만. 1분 미만은 변화 없음으로 본다. */
    private static int direction(Long deltaSeconds) {
        return meaningful(deltaSeconds) ? Long.signum(deltaSeconds) : 0;
    }

    private GmsRecommendationGuideClient.GeneratedCopy reusedCopy(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null
                    ? null
                    : json.readValue(value, GmsRecommendationGuideClient.GeneratedCopy.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void rememberCopy(String key, GmsRecommendationGuideClient.GeneratedCopy copy) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(copy), COPY_TTL);
        } catch (Exception ignored) {
            // 재사용은 비용을 줄이는 장치일 뿐이다. 실패해도 이번 안내는 그대로 나간다.
        }
    }

    private static Long delta(LocalDateTime before, LocalDateTime after) {
        return before == null || after == null
                ? null
                : Duration.between(before, after).getSeconds();
    }

    private static String fallbackRecommendation(
            GmsRecommendationGuideClient.ChangeFact change,
            RecommendationGuideResponse.Delay delay) {
        String cause = delay.reason();
        String context = cause == null ? null : sentence(cause) + " 이를 반영해 ";
        return switch (change.type()) {
            case "ROUTE_CHANGED" ->
                    context == null ? "이동 상황을 다시 확인해 다른 경로를 제안해요." : context + "다른 경로를 제안해요.";
            case "DEPARTURE_TIME_CHANGED" ->
                    (context == null ? "최신 계산 결과를 반영해 " : context)
                            + "출발시각을 "
                            + departureDirection(change.departureDeltaSeconds())
                            + " 제안해요.";
            case "ETA_UPDATED" ->
                    (context == null ? "최신 계산 결과로 " : context)
                            + "예상 도착시각이 "
                            + arrivalDirection(change.arrivalDeltaSeconds())
                            + " 안내를 갱신했어요.";
            default ->
                    context == null
                            ? "현재 선택한 경로와 출발시각을 유지해도 돼요."
                            : sentence(cause) + " 현재 경로와 출발시각은 유지해요.";
        };
    }

    /** 원인 문장과 서버가 계산한 변경 방향을 결합한다. 원문을 사건의 확정 근거로 승격하지 않는다. */
    private static String sentence(String value) {
        String trimmed = value.strip();
        return trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")
                ? trimmed
                : trimmed + ".";
    }

    /** 경로 단계 열거와 교통 상황 설명을 구분한다. 시각/요금은 다른 카드의 책임이다. */
    static boolean routeNarration(String value) {
        if (value == null) return false;
        return value.contains("→")
                || (value.contains("탑승 후") && value.contains("하차") && value.contains("에서"));
    }

    private static String departureDirection(Long seconds) {
        if (seconds == null || seconds == 0) return "다시 확인하도록";
        long minutes = Math.abs(seconds) / 60;
        return (minutes > 0 ? minutes + "분 " : "") + (seconds < 0 ? "앞당기도록" : "늦추도록");
    }

    private static String arrivalDirection(Long seconds) {
        if (seconds == null || seconds == 0) return "변경되어";
        long minutes = Math.abs(seconds) / 60;
        return (minutes > 0 ? minutes + "분 " : "") + (seconds < 0 ? "빨라져" : "늦어져");
    }

    private RecommendationGuideResponse cached(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? null : json.readValue(value, RecommendationGuideResponse.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 멈춘 일정의 안내를 캐시에 두는 시간. 유효기간이 이미 지난 결과라 그 값으로는 TTL 을 잡을 수 없다. 하루 안에 다시 볼 일이 대부분이다. */
    static final Duration FROZEN_CACHE_TTL = Duration.ofHours(12);

    /**
     * 캐시에 두는 최소 시간. 예전에는 유효기간이 지났으면 캐시를 건너뛰었다. 그러면 같은 계산에 대한 다음 조회가 또 GMS 를 부른다 — 출발이 가까울 때 유효기간은 1
     * 분이고 pro 모델은 그보다 오래 걸렸으니, 만들자마자 버려지고 다시 불렀다.
     */
    static final Duration MIN_CACHE_TTL = Duration.ofMinutes(5);

    private void cache(
            String key, RecommendationGuideResponse response, Instant now, boolean frozen) {
        try {
            Duration ttl;
            if (frozen) {
                // 더 갱신되지 않는 결과다. 매번 GMS 를 다시 부르지 않게 고정 시간 동안 둔다.
                ttl = FROZEN_CACHE_TTL;
            } else {
                ttl = Duration.between(now, response.validUntil());
                if (ttl.compareTo(MIN_CACHE_TTL) < 0) ttl = MIN_CACHE_TTL;
                // 실시간 사실의 유효시각을 넘겨 AI 문장만 오래 남기지 않는다.
                if (ttl.compareTo(Duration.ofMinutes(30)) > 0) ttl = Duration.ofMinutes(30);
            }
            redis.opsForValue().set(key, json.writeValueAsString(response), ttl);
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
                        : "DEMO_TOPIS".equals(chosen.source())
                                ? "[시연용] 선택한 버스 경로에 가짜 도로 통제를 적용해 다른 경로를 계산했어요."
                                : confirmed
                                        ? "선택 경로에 공식 교통 영향이 확인됐어요."
                                        : "선택 경로 주변에 교통 영향 가능성이 있어요.";
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

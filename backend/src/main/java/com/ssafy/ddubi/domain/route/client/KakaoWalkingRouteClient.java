package com.ssafy.ddubi.domain.route.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.client.dto.KakaoWalkingResponse;
import com.ssafy.ddubi.domain.route.model.Coordinate;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 카카오맵 공식 도보 API 어댑터. 장애는 시간 0이나 전체 경로 오류 대신 후보 fallback으로 반환한다. */
@Component
public class KakaoWalkingRouteClient
        implements WalkingRouteClient, org.springframework.boot.actuate.health.HealthIndicator {
    private static final String SOURCE = "KAKAO_WALKING_API";

    /** 도보 한 건의 표시용 좌표 상한. 지도에 그리기에 충분하고 저장에도 부담이 없는 값이다. */
    private static final int MAX_PATH_POINTS = 500;
    private final RestClient restClient;
    private final KakaoWalkingProperties properties;
    private final KakaoPublicTransitProperties kakao;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private volatile Instant nextAllowedAt = Instant.MIN;
    private volatile String lastFailure = "NOT_CALLED";

    @org.springframework.beans.factory.annotation.Autowired
    public KakaoWalkingRouteClient(
            RestClient.Builder builder,
            KakaoWalkingProperties properties,
            KakaoPublicTransitProperties kakao,
            ObjectMapper json,
            MeterRegistry metrics) {
        this(build(builder, properties), properties, kakao, json, metrics);
    }

    private static RestClient build(RestClient.Builder builder, KakaoWalkingProperties properties) {
        if (properties.getTimeoutMillis() < 1
                || properties.getTimeoutMillis() > 10000
                || properties.getCacheSeconds() < 1
                || properties.getCacheSeconds() > 300)
            throw new IllegalArgumentException("도보 timeout/cache 범위 오류");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeoutMillis());
        factory.setReadTimeout(properties.getTimeoutMillis());
        return builder.clone().baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
    }

    /** HTTP fixture도 운영과 같은 응답 검증 경로를 사용한다. */
    KakaoWalkingRouteClient(
            RestClient restClient,
            KakaoWalkingProperties properties,
            KakaoPublicTransitProperties kakao,
            ObjectMapper json,
            MeterRegistry metrics) {
        this.restClient = restClient;
        this.properties = properties;
        this.kakao = kakao;
        this.json = json;
        this.metrics = metrics;
    }

    @Override
    public Evidence route(Request request) {
        if (!properties.isEnabled()) return fallback("WALK_DISABLED");
        if (!StringUtils.hasText(kakao.getRestApiKey())) return fallback("WALK_KEY_NOT_CONFIGURED");
        if (request == null
                || !valid(request.origin())
                || !valid(request.destination())
                || request.routeMode() == null
                || !Set.of("BROAD_FIRST", "SHORTEST", "ACCESSIBLE").contains(request.routeMode()))
            return fallback("WALK_INVALID_REQUEST");
        if (Instant.now().isBefore(nextAllowedAt)) return fallback("WALK_HTTP_429");
        try {
            var response =
                    restClient
                            .get()
                            .uri(
                                    uri ->
                                            uri.path(properties.getPath())
                                                    .queryParam(
                                                            "start_x",
                                                            format(request.origin().longitude()))
                                                    .queryParam(
                                                            "start_y",
                                                            format(request.origin().latitude()))
                                                    .queryParam(
                                                            "end_x",
                                                            format(
                                                                    request.destination()
                                                                            .longitude()))
                                                    .queryParam(
                                                            "end_y",
                                                            format(
                                                                    request.destination()
                                                                            .latitude()))
                                                    .queryParam("input_coord", "WGS84")
                                                    .queryParam("output_coord", "WGS84")
                                                    .queryParam("route_mode", request.routeMode())
                                                    .build())
                            .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + kakao.getRestApiKey())
                            .accept(MediaType.APPLICATION_JSON)
                            .retrieve()
                            .body(KakaoWalkingResponse.class);
            if (response == null) return fallback("WALK_EMPTY_RESPONSE");
            if (!"OK".equals(response.status())) return fallback("WALK_ROUTE_UNAVAILABLE");
            var cost = response.route() == null ? null : response.route().properties();
            if (cost == null
                    || cost.totalTime() == null
                    || cost.totalTime() <= 0
                    || cost.totalTime() > 86400
                    || cost.totalDistance() == null
                    || cost.totalDistance() < 0) return fallback("WALK_INVALID_RESPONSE");
            metrics.counter("ddubi.walking.requests", "result", "SUCCESS").increment();
            lastFailure = null;
            // 제공자 TTL이 아니다. 한 평가 안에서 사용하는 BE 캐시 정책의 유효기간이다.
            long plannedSeconds =
                    Math.addExact(
                            cost.totalTime(), WalkingSignalAllowance.seconds(response.route()));
            if (plannedSeconds > 86400) return fallback("WALK_INVALID_RESPONSE");
            return new Evidence(
                    plannedSeconds,
                    Instant.now().plusSeconds(properties.getCacheSeconds()),
                    SOURCE,
                    null,
                    path(response.route()));
        } catch (HttpStatusCodeException failure) {
            int status = failure.getStatusCode().value();
            if (status == 429 || quotaExceeded(failure.getResponseBodyAsString())) {
                defer(
                        failure.getResponseHeaders() == null
                                ? null
                                : failure.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER));
                return fallback("WALK_HTTP_429");
            }
            return fallback(
                    status == 401 || status == 403 ? "WALK_HTTP_403" : "WALK_PROVIDER_UNAVAILABLE");
        } catch (ResourceAccessException failure) {
            return fallback("WALK_TIMEOUT");
        } catch (RestClientException failure) {
            return fallback("WALK_INVALID_RESPONSE");
        }
    }

    /** 429 재시도는 호출 스레드에서 하지 않는다. Retry-After 동안 다음 후보/평가도 요청을 생략한다. */
    private synchronized void defer(String retryAfter) {
        Instant until = Instant.now().plusSeconds(60);
        try {
            if (retryAfter != null)
                until =
                        retryAfter.matches("[0-9]+")
                                ? Instant.now().plusSeconds(Long.parseLong(retryAfter))
                                : ZonedDateTime.parse(
                                                retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
                                        .toInstant();
        } catch (RuntimeException ignored) {
            /* 잘못된 헤더는 기본 backoff를 사용한다. */
        }
        if (until.isAfter(nextAllowedAt)) nextAllowedAt = until;
    }

    private boolean quotaExceeded(String body) {
        try {
            return json.readTree(body).path("code").asInt() == -10;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 공식 steps[].path.points 를 지도용 좌표열 하나로 잇는다.
     *
     * <h3>왜 좌표를 걸러 내나</h3>
     * 한 점만 어긋나도 지도는 서해 앞바다까지 선을 긋는다. 값이 이상하면 그 구간의 선을
     * 통째로 포기한다 — 틀린 선을 보여 주는 것보다 안 보여 주는 편이 낫다. 시간·거리는
     * 이 좌표와 무관하게 공식 totalTime/totalDistance 를 그대로 쓴다.
     *
     * <p>이어 붙일 때 앞 점과 같은 점은 건너뛴다. step 경계에서 같은 좌표가 겹쳐 온다.
     */
    private java.util.List<Coordinate> path(KakaoWalkingResponse.Route route) {
        if (route == null || route.legs() == null) return java.util.List.of();
        var points = new java.util.ArrayList<Coordinate>();
        for (var leg : route.legs()) {
            if (leg == null || leg.steps() == null) continue;
            for (var step : leg.steps()) {
                if (step == null || step.path() == null || step.path().points() == null) continue;
                for (var point : step.path().points()) {
                    if (point == null || point.size() < 2) continue;
                    Double longitude = point.get(0);
                    Double latitude = point.get(1);
                    if (longitude == null || latitude == null) continue;
                    Coordinate coordinate = new Coordinate(longitude, latitude);
                    if (!valid(coordinate) || (longitude == 0 && latitude == 0))
                        return java.util.List.of();
                    // 표시용 좌표가 아무리 길어도 응답 한 건을 넘기지 않는다.
                    if (points.size() >= MAX_PATH_POINTS) return java.util.List.copyOf(points);
                    if (!points.isEmpty() && points.get(points.size() - 1).equals(coordinate))
                        continue;
                    points.add(coordinate);
                }
            }
        }
        // 점 하나짜리 선은 그릴 수 없다. 그런 값은 없는 것과 같다.
        return points.size() < 2 ? java.util.List.of() : java.util.List.copyOf(points);
    }

    private Evidence fallback(String reason) {
        lastFailure = reason;
        metrics.counter("ddubi.walking.requests", "result", reason).increment();
        return new Evidence(null, null, SOURCE, reason);
    }

    /** 선택적 도보 의존성의 상태를 공개한다. fallback 가능한 장애가 전체 대중교통 readiness를 내리지 않는다. */
    @Override
    public org.springframework.boot.actuate.health.Health health() {
        String state =
                !properties.isEnabled()
                        ? "DISABLED"
                        : !StringUtils.hasText(kakao.getRestApiKey())
                                ? "KEY_NOT_CONFIGURED"
                                : lastFailure == null ? "READY" : lastFailure;
        return org.springframework.boot.actuate.health.Health.up()
                .withDetail("walkingStatus", state)
                .withDetail("fallbackAvailable", true)
                .build();
    }

    private boolean valid(Coordinate point) {
        return point != null
                && Double.isFinite(point.longitude())
                && Double.isFinite(point.latitude())
                && Math.abs(point.longitude()) <= 180
                && Math.abs(point.latitude()) <= 90;
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.8f", value);
    }
}

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
public class KakaoWalkingRouteClient implements WalkingRouteClient, org.springframework.boot.actuate.health.HealthIndicator {
    private static final String SOURCE = "KAKAO_WALKING_API";
    private final RestClient restClient;
    private final KakaoWalkingProperties properties;
    private final KakaoPublicTransitProperties kakao;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private volatile Instant nextAllowedAt = Instant.MIN;
    private volatile String lastFailure = "NOT_CALLED";

    @org.springframework.beans.factory.annotation.Autowired
    public KakaoWalkingRouteClient(RestClient.Builder builder, KakaoWalkingProperties properties,
            KakaoPublicTransitProperties kakao, ObjectMapper json, MeterRegistry metrics) {
        this(build(builder,properties),properties,kakao,json,metrics);
    }

    private static RestClient build(RestClient.Builder builder,KakaoWalkingProperties properties) {
        if (properties.getTimeoutMillis() < 1 || properties.getTimeoutMillis() > 10000
                || properties.getCacheSeconds() < 1 || properties.getCacheSeconds() > 300)
            throw new IllegalArgumentException("도보 timeout/cache 범위 오류");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeoutMillis());
        factory.setReadTimeout(properties.getTimeoutMillis());
        return builder.clone().baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
    }

    /** HTTP fixture도 운영과 같은 응답 검증 경로를 사용한다. */
    KakaoWalkingRouteClient(RestClient restClient,KakaoWalkingProperties properties,
            KakaoPublicTransitProperties kakao,ObjectMapper json,MeterRegistry metrics) {
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
        if (request == null || !valid(request.origin()) || !valid(request.destination())
                || request.routeMode() == null || !Set.of("BROAD_FIRST", "SHORTEST", "ACCESSIBLE").contains(request.routeMode()))
            return fallback("WALK_INVALID_REQUEST");
        if (Instant.now().isBefore(nextAllowedAt)) return fallback("WALK_HTTP_429");
        try {
            var response = restClient.get().uri(uri -> uri.path(properties.getPath())
                            .queryParam("start_x", format(request.origin().longitude()))
                            .queryParam("start_y", format(request.origin().latitude()))
                            .queryParam("end_x", format(request.destination().longitude()))
                            .queryParam("end_y", format(request.destination().latitude()))
                            .queryParam("input_coord", "WGS84").queryParam("output_coord", "WGS84")
                            .queryParam("route_mode", request.routeMode()).build())
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + kakao.getRestApiKey())
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(KakaoWalkingResponse.class);
            if (response == null) return fallback("WALK_EMPTY_RESPONSE");
            if (!"OK".equals(response.status())) return fallback("WALK_ROUTE_UNAVAILABLE");
            var cost = response.route() == null ? null : response.route().properties();
            if (cost == null || cost.totalTime() == null || cost.totalTime() <= 0 || cost.totalTime() > 86400
                    || cost.totalDistance() == null || cost.totalDistance() < 0)
                return fallback("WALK_INVALID_RESPONSE");
            metrics.counter("ddubi.walking.requests", "result", "SUCCESS").increment();
            lastFailure = null;
            // 제공자 TTL이 아니다. 한 평가 안에서 사용하는 BE 캐시 정책의 유효기간이다.
            return new Evidence(cost.totalTime(), Instant.now().plusSeconds(properties.getCacheSeconds()), SOURCE, null);
        } catch (HttpStatusCodeException failure) {
            int status = failure.getStatusCode().value();
            if (status == 429 || quotaExceeded(failure.getResponseBodyAsString())) {
                defer(failure.getResponseHeaders() == null ? null : failure.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER));
                return fallback("WALK_HTTP_429");
            }
            return fallback(status == 401 || status == 403 ? "WALK_HTTP_403" : "WALK_PROVIDER_UNAVAILABLE");
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
            if (retryAfter != null) until = retryAfter.matches("[0-9]+")
                    ? Instant.now().plusSeconds(Long.parseLong(retryAfter))
                    : ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (RuntimeException ignored) { /* 잘못된 헤더는 기본 backoff를 사용한다. */ }
        if (until.isAfter(nextAllowedAt)) nextAllowedAt = until;
    }

    private boolean quotaExceeded(String body) {
        try { return json.readTree(body).path("code").asInt() == -10; }
        catch (Exception ignored) { return false; }
    }

    private Evidence fallback(String reason) {
        lastFailure = reason;
        metrics.counter("ddubi.walking.requests", "result", reason).increment();
        return new Evidence(null, null, SOURCE, reason);
    }

    /** 선택적 도보 의존성의 상태를 공개한다. fallback 가능한 장애가 전체 대중교통 readiness를 내리지 않는다. */
    @Override
    public org.springframework.boot.actuate.health.Health health() {
        String state = !properties.isEnabled()?"DISABLED":!StringUtils.hasText(kakao.getRestApiKey())?"KEY_NOT_CONFIGURED"
                :lastFailure==null?"READY":lastFailure;
        return org.springframework.boot.actuate.health.Health.up().withDetail("walkingStatus",state)
                .withDetail("fallbackAvailable",true).build();
    }

    private boolean valid(Coordinate point) {
        return point != null && Double.isFinite(point.longitude()) && Double.isFinite(point.latitude())
                && Math.abs(point.longitude()) <= 180 && Math.abs(point.latitude()) <= 90;
    }

    private String format(double value) { return String.format(Locale.ROOT, "%.8f", value); }
}
